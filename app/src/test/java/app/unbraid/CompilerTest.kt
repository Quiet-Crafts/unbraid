package app.unbraid

import app.unbraid.data.DIRECT
import app.unbraid.data.Home
import app.unbraid.data.Link
import app.unbraid.data.Manual
import app.unbraid.data.Nord
import app.unbraid.data.OvpnLink
import app.unbraid.data.TsLink
import app.unbraid.data.WgLink
import app.unbraid.data.WgPeer
import app.unbraid.data.Location
import app.unbraid.data.NordApi
import app.unbraid.data.Prefs
import app.unbraid.data.Filter
import app.unbraid.data.Filters
import app.unbraid.data.PROTECT
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.data.Socks5Link
import app.unbraid.data.Socks5Profile
import app.unbraid.data.SsLink
import app.unbraid.data.SsProfile
import app.unbraid.data.WgConf
import app.unbraid.vpn.Compiler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class CompilerTest {
    private val key = "aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8tMTI="
    private fun sv(n: String) = Server("$n #1", "$n.nordvpn.com", "10.9.${n.hashCode() and 0xff}.${n.hashCode() shr 8 and 0xff}", "5p4RkybdRU5uaDi90eu4KZPTFif0lKCg4Qp6t1c4F30=", 10)

    /** Example setup: NL default, US for A, IT for B, X+Y direct, home over Tailscale. */
    private val example = Setup(
        routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands")), Route("us", Location("nord", 228, "US", "United States"), hue = 1), Route("it", Location("nord", 106, "IT", "Italy"), hue = 2)),
        defaultRoute = "nl",
        apps = mapOf("com.a" to "us", "com.b" to "it", "com.x" to DIRECT, "com.y" to DIRECT),
        home = Home(tailscale = true, subnets = listOf("192.168.178.0/24")),
    )

    private fun links(s: Setup): Map<String, Link> = s.activeRoutes.associate { r ->
        r.id to when {
            r.wg != null -> WgLink(sv(r.id), r.wg!!)
            r.tsExit != null -> TsLink(sv(r.id))
            else -> WgLink(sv(r.id), Nord.peer(sv(r.id), key))
        }
    }

    private fun compile(s: Setup, lockdown: Boolean = false, links: Map<String, Link> = links(s), tsSuffix: String? = null, blocked: Set<String> = emptySet(), login: Boolean = false): JsonObject {
        val out = Compiler.compile(Compiler.Input(s, links - blocked, "app.unbraid", lockdown, "/tmp/ts", "/tmp/cache.db", tsSuffix, blocked = blocked, tsLoginPending = login))
        File("build/compiled").apply { mkdirs() }.resolve("${s.hashCode()}-$lockdown-${blocked.sorted()}-$login.json").writeText(out)
        return Json.parseToJsonElement(out).jsonObject.also { assertRefusesOtherBlocks(it) }
    }

    private fun JsonObject.rules() = this["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.endpointTags() = this["endpoints"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
    private fun JsonObject.excluded() = this["inbounds"]!!.jsonArray[0].jsonObject["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content }

    private val socks = Route("socks", socks5 = Socks5Profile("Companion", "127.0.0.1", 10808,
        "com.proxy", "a".repeat(64), "listener-user", "listener-password"))

    private fun socksInput(setup: Setup, lockdown: Boolean = false, bridge: Boolean = true, bridgeUdp: Boolean = false) =
        Compiler.Input(setup, mapOf(socks.id to Socks5Link(sv(socks.id))), "app.unbraid",
            lockdown, "/tmp/ts", "/tmp/cache.db", socksBridges = if (bridge)
                mapOf(socks.id to Compiler.SocksBridge(22331, "bridge-user", "bridge-password", bridgeUdp)) else emptyMap())

    @Test fun socksUsesOnlyTheTcpBridgeAndGivesItsCompanionPriority() {
        val setup = Setup(routes = listOf(socks), defaultRoute = DIRECT,
            apps = mapOf("com.target" to socks.id, "com.proxy" to DIRECT), sites = mapOf("upstream.example" to DIRECT))
        val input = socksInput(setup)
        val raw = Compiler.compile(input)
        val config = Json.parseToJsonElement(raw).jsonObject
        val outbound = config["outbounds"]!!.jsonArray.map { it.jsonObject }.single { it["type"] == JsonPrimitive("socks") }
        assertEquals(JsonPrimitive(22331), outbound["server_port"])
        assertEquals(JsonPrimitive("bridge-user"), outbound["username"])
        assertEquals(JsonPrimitive("bridge-password"), outbound["password"])
        assertNull(outbound["udp_over_tcp"])
        assertEquals(setOf("r-socks"), Compiler.tcpOnly(raw))
        assertFalse(raw.contains("10808"))
        assertFalse(raw.contains("listener-password"))
        assertEquals(setOf("com.proxy"), input.socksCompanions)
        assertFalse("com.proxy" in config.excluded())
        val dns = config["dns"]!!.jsonObject
        val resolver = dns["servers"]!!.jsonArray.map { it.jsonObject }.single { it["tag"] == JsonPrimitive("dns-r-socks") }
        assertEquals(JsonPrimitive("tcp"), resolver["type"])
        assertEquals(JsonPrimitive("r-socks"), resolver["detour"])
        val companion = JsonArray(listOf(JsonPrimitive("com.proxy")))
        val dnsRules = dns["rules"]!!.jsonArray.map { it.jsonObject }
        assertEquals(JsonPrimitive("local"), dnsRules.first { it["package_name"] == companion }["server"])
        assertTrue(dnsRules.indexOfFirst { it["package_name"] == companion } < dnsRules.indexOfFirst { it["server"] == JsonPrimitive("fake") })
        val routeRules = config.rules()
        assertTrue(routeRules.any {
            it["package_name"] == JsonArray(listOf(JsonPrimitive("com.target"))) &&
                it["network"] == JsonPrimitive("udp") && it["action"] == JsonPrimitive("reject")
        })
        val direct = routeRules.indexOfFirst { it["package_name"] == companion && it["outbound"] == JsonPrimitive("direct") }
        val website = routeRules.indexOfFirst { it["domain"] != null || it["domain_suffix"] != null }
        assertTrue(direct >= 0 && website > direct)
    }

    @Test fun socksUdpRequiresBothOptInAndACapableBridgeButKeepsTcpDns() {
        val enabled = socks.copy(socks5 = socks.socks5!!.copy(allowUdp = true))
        val setup = Setup(routes = listOf(enabled), defaultRoute = DIRECT,
            apps = mapOf("com.target" to socks.id, "com.proxy" to DIRECT))
        val raw = Compiler.compile(socksInput(setup, bridgeUdp = true))
        val config = Json.parseToJsonElement(raw).jsonObject
        val outbound = config["outbounds"]!!.jsonArray.map { it.jsonObject }.single { it["type"] == JsonPrimitive("socks") }
        assertNull(outbound["network"])
        assertEquals(JsonPrimitive(true), outbound["udp_over_tcp"])
        assertTrue(Compiler.tcpOnly(raw).isEmpty())
        val resolver = config["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
            .single { it["tag"] == JsonPrimitive("dns-r-socks") }
        assertEquals(JsonPrimitive("tcp"), resolver["type"])
        val target = JsonArray(listOf(JsonPrimitive("com.target")))
        assertTrue(config.rules().any { it["package_name"] == target && it["outbound"] == JsonPrimitive("r-socks") })
        assertFalse(config.rules().any {
            it["package_name"] == target && it["network"] == JsonPrimitive("udp") && it["action"] == JsonPrimitive("reject")
        })
        for (input in listOf(socksInput(setup), socksInput(setup.copy(routes = listOf(socks)), bridgeUdp = true))) {
            assertEquals(setOf(socks.id), input.unavailableSocks)
            val refused = Json.parseToJsonElement(Compiler.compile(input)).jsonObject
            assertTrue(refused.rules().any { it["package_name"] == target && it["action"] == JsonPrimitive("reject") })
            assertFalse(refused["outbounds"]!!.jsonArray.any { it.jsonObject["type"] == JsonPrimitive("socks") })
        }
    }

    @Test fun unavailableOrUnsafeSocksIsRefusedWithoutAnOutbound() {
        val setup = Setup(routes = listOf(socks), defaultRoute = DIRECT,
            apps = mapOf("com.target" to socks.id, "com.proxy" to DIRECT))
        for (input in listOf(
            socksInput(setup, bridge = false),
            socksInput(setup, lockdown = true),
            socksInput(setup.copy(apps = setup.apps - "com.proxy")),
            socksInput(setup.copy(sites = mapOf("example.com" to socks.id))),
        )) {
            assertEquals(setOf(socks.id), input.unavailableSocks)
            if (input.socksBridges.isNotEmpty()) assertTrue(input.socksCompanions.isEmpty())
            val raw = Compiler.compile(input)
            assertFalse(raw.contains("bridge-password"))
            val rules = Json.parseToJsonElement(raw).jsonObject.rules()
            assertTrue(rules.any { it["package_name"] == JsonArray(listOf(JsonPrimitive("com.target"))) && it["action"] == JsonPrimitive("reject") })
        }
    }

    /** A proxy route that can't be used yet leaves the same apps outside the tunnel, so Android swaps tunnels without a gap. */
    @Test fun aProxyRouteComingOrGoingMovesNoAppOutOfTheTunnel() {
        val setup = Setup(routes = listOf(socks), defaultRoute = DIRECT,
            apps = mapOf("com.target" to socks.id, "com.proxy" to DIRECT, "com.direct" to DIRECT))
        val (up, down) = listOf(true, false).map { bridge ->
            Json.parseToJsonElement(Compiler.compile(socksInput(setup, bridge = bridge))).jsonObject }
        fun outside(c: JsonObject) = c["inbounds"]!!.jsonArray.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "tun" }["exclude_package"]
        assertEquals(outside(up), outside(down))
        assertTrue("com.proxy" !in outside(down)!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(down.rules().any { it["package_name"] == JsonArray(listOf(JsonPrimitive("com.target"))) && it["action"] == JsonPrimitive("reject") })
        assertTrue(down.rules().any { r -> r["package_name"] == JsonArray(listOf(JsonPrimitive("com.proxy"))) && r["outbound"]?.jsonPrimitive?.content == "direct" })
    }

    private fun JsonObject.final() = this["route"]!!.jsonObject["final"]!!.jsonPrimitive.content
    private fun JsonObject.dnsRules() = this["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.refuses() = this["action"] == JsonPrimitive("reject")
    private fun JsonObject.unnamed() = this["type"] == JsonPrimitive("logical") && this["rules"]!!.jsonArray.map { it.jsonObject }.any { it["package_name_regex"] != null && it["invert"] == JsonPrimitive(true) }

    /** A proxy as the default, its own app the only exception: nothing without a name, no ping and no UDP gets past it. */
    @Test fun aProxyDefaultCarriesOrRefusesEverythingItsAppIsTheOnlyWayOut() {
        val setup = Setup(routes = listOf(socks), defaultRoute = socks.id, apps = mapOf("com.proxy" to DIRECT))
        for (udp in listOf(false, true)) {
            val input = socksInput(setup.copy(routes = listOf(socks.copy(socks5 = socks.socks5!!.copy(allowUdp = udp)))), bridgeUdp = udp)
            val c = Json.parseToJsonElement(Compiler.compile(input)).jsonObject
            val rules = c.rules()
            assertEquals("r-socks", c.final())
            assertEquals(setOf("com.proxy"), input.socksCompanions)
            assertEquals(listOf("app.unbraid"), c.excluded()) // its app stays inside: no tunnel swap when it changes
            val last = rules.indexOfFirst { it["action"] == JsonPrimitive("resolve") && "package_name" !in it }
            val before = rules.take(last)
            assertTrue(before.any { it.refuses() && it.unnamed() })
            // After the home rules saw the name: a home box is reached by name too.
            assertTrue(rules.drop(last).any { it.refuses() && it["network"] == JsonPrimitive("icmp") })
            assertEquals(!udp, rules.drop(last).any { it.refuses() && it["network"] == JsonPrimitive("udp") && "package_name" !in it })
            // Its own app first, straight out with the phone's DNS; the names of everything else through the proxy.
            val own = rules.indexOfFirst { it["outbound"] == JsonPrimitive("direct") && it["package_name"] == JsonArray(listOf(JsonPrimitive("com.proxy"))) }
            assertTrue(own in 0 until before.indexOfFirst { it.refuses() && it.unnamed() })
            assertEquals(1, rules.count { it["outbound"] == JsonPrimitive("direct") && "ip_cidr" !in it })
            assertEquals("dns-r-socks", rules[last]["server"]!!.jsonPrimitive.content)
            assertEquals("dns-r-socks", c["dns"]!!.jsonObject["final"]!!.jsonPrimitive.content)
            assertTrue(c.dnsRules().any { it.refuses() && it.unnamed() })
            // An app sharing the proxy app's user id is refused before its rules, for connections and names alike, signed choices or not.
            val denied = { r: JsonObject -> r.refuses() && "@unverified" in r["package_name"]?.toString().orEmpty() }
            assertTrue(rules.indexOfFirst(denied) in 0 until own)
            assertEquals(0, c.dnsRules().indexOfFirst(denied))
            assertTrue(c.dnsRules().none { it["server"] == JsonPrimitive("local") && it["package_name"] != JsonArray(listOf(JsonPrimitive("com.proxy"))) && "domain_suffix" !in it })
        }
    }

    /** A proxy default that can't be used leaves the apps on it offline, names included: no other way out, only its own app. */
    @Test fun anUnusableProxyDefaultIsOffline() {
        val setup = Setup(routes = listOf(socks), defaultRoute = socks.id, apps = mapOf("com.proxy" to DIRECT))
        val noDirect = setup.copy(apps = emptyMap())
        val unconfirmed = setup.copy(routes = listOf(socks.copy(socks5 = socks.socks5!!.copy(needsConfirmation = true))))
        for (input in listOf(socksInput(setup, bridge = false), socksInput(setup, lockdown = true), socksInput(noDirect), socksInput(unconfirmed), socksInput(setup, bridgeUdp = true))) {
            val c = Json.parseToJsonElement(Compiler.compile(input)).jsonObject
            assertEquals(setOf(socks.id), input.unavailableSocks)
            assertTrue(c.rules().last().let { it.refuses() && it.keys == setOf("action", "no_drop") })
            assertTrue(c.dnsRules().last().let { it.refuses() && it.keys == setOf("action") })
            assertFalse("bridge-password" in c.toString())
            val direct = c.rules().filter { it["outbound"] == JsonPrimitive("direct") && "ip_cidr" !in it }
            assertTrue(direct.all { it["package_name"] == JsonArray(listOf(JsonPrimitive("com.proxy"))) })
        }
    }

    /** Each new domain table hands out fake IPs from its block of the pool and refuses the others, before any exit is chosen. */
    @Test fun aFakeIpFromAnotherBlockIsRefused() = Compiler.FAKE_RANGES.indices.forEach { h ->
        val raw = Compiler.compile(Compiler.Input(example, links(example), "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", fakeBlock = h))
        File("build/compiled").apply { mkdirs() }.resolve("fakeblock-$h.json").writeText(raw)
        val c = Json.parseToJsonElement(raw).jsonObject
        val fake = c["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }.single { it["tag"]?.jsonPrimitive?.content == "fake" }
        assertEquals(Compiler.FAKE_RANGES[h], fake["inet4_range"]!!.jsonPrimitive.content)
        val rules = c.rules()
        val refuse = rules.indexOfFirst { it["ip_cidr"]?.jsonArray?.map { r -> r.jsonPrimitive.content } == Compiler.FAKE_RANGES - Compiler.FAKE_RANGES[h] }
        assertEquals("reject", rules[refuse]["action"]!!.jsonPrimitive.content)
        assertEquals("true", rules[refuse]["no_drop"]!!.jsonPrimitive.content) // refused every time, never silently dropped
        assertTrue(rules.take(refuse).all { it["action"]?.jsonPrimitive?.content == "hijack-dns" || it["inbound"] != null && it["action"]?.jsonPrimitive?.content == "reject" })
    }

    /** New tables walk through all eight blocks; a reload keeps its block; older versions' halves start in the other half. */
    @Test fun fakeBlocksRotateAndMigrate() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 0), (0 until 8).map { Compiler.nextBlock(it, open = false) })
        assertEquals(5, Compiler.nextBlock(5, open = true))
        // Half 0 was 198.18.0.0/16 (blocks 0 to 3), half 1 was 198.19.0.0/16 (blocks 4 to 7).
        assertEquals(4, Compiler.nextBlock(Compiler.blockAfterHalf(0), open = false))
        assertEquals(0, Compiler.nextBlock(Compiler.blockAfterHalf(1), open = false))
        assertEquals(4, Compiler.nextBlock(Compiler.blockAfterHalf(null), open = false))
    }

    /** Eight equal blocks that tile 198.18.0.0/15 exactly. */
    @Test fun fakeBlocksTileTheBenchmarkRange() {
        val starts = Compiler.FAKE_RANGES.map { r -> assertTrue(r.endsWith("/18")); r.removeSuffix("/18").split('.').fold(0L) { a, o -> a shl 8 or o.toLong() } }
        val base = (198L shl 24) or (18L shl 16)
        assertEquals((0 until 8).map { base + it * 16384L }, starts)
    }

    /** Health checks look their STUN servers up through each tunnel's own DNS server, read back from the config. */
    @Test fun eachTunnelsDnsServerIsKnown() {
        val raw = Compiler.compile(Compiler.Input(example, links(example), "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", probe = 40000 to "pw"))
        val dns = Compiler.probeDns(raw)
        for (t in listOf("r-nl", "r-us", "r-it")) assertEquals(t, "dns-r-${t.removePrefix("r-")}", dns[t])
        val servers = Json.parseToJsonElement(raw).jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        for ((t, d) in dns.filterKeys { it.startsWith("r-") }) assertEquals(t, servers.single { it["tag"]!!.jsonPrimitive.content == d }["detour"]!!.jsonPrimitive.content)
    }

    /** A new probe pair always changes the config (so every engine start gets one), the same pair never. */
    @Test fun theProbePairIsPartOfTheConfig() {
        fun raw(p: Pair<Int, String>?) = Compiler.compile(Compiler.Input(example, links(example), "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", null, p))
        val a = raw(41001 to "pw-one")
        assertEquals(a, raw(41001 to "pw-one"))
        assertTrue(a != raw(41001 to "pw-two"))
        assertTrue(a != raw(41002 to "pw-one"))
        val probe = Json.parseToJsonElement(a).jsonObject["inbounds"]!!.jsonArray.map { it.jsonObject }.single { it["tag"]?.jsonPrimitive?.content == Compiler.PROBE }
        assertEquals("127.0.0.1", probe["listen"]!!.jsonPrimitive.content)
        assertTrue(probe["users"]!!.jsonArray.all { it.jsonObject["password"]!!.jsonPrimitive.content == "pw-one" })
        assertTrue(Json.parseToJsonElement(raw(null)).jsonObject["inbounds"]!!.jsonArray.none { it.jsonObject["tag"]?.jsonPrimitive?.content == Compiler.PROBE })
    }

    @Test fun exampleRoutesEachAppToItsExit() {
        val c = compile(example)
        assertEquals(listOf("r-nl", "r-us", "r-it", "home-ts"), c.endpointTags())
        assertEquals("r-nl", c["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        val routed = c.rules().filter { it["outbound"] != null && it["package_name"] != null }
            .associate { it["outbound"]!!.jsonPrimitive.content to it["package_name"]!!.jsonArray.map { p -> p.jsonPrimitive.content } }
        assertEquals(mapOf("r-it" to listOf("com.b"), "r-us" to listOf("com.a")), routed)
        assertEquals(listOf("com.x", "com.y", "app.unbraid"), c.excluded())
    }

    @Test fun homeBeatsLanAndAppRules() {
        val rules = compile(example).rules()
        val home = rules.indexOfFirst { it["outbound"]?.jsonPrimitive?.content == "home-ts" }
        val lan = rules.indexOfFirst { it["outbound"]?.jsonPrimitive?.content == "direct" && it["ip_cidr"] != null }
        val app = rules.indexOfFirst { it["package_name"] != null }
        assertTrue(home in 0 until lan && lan < app)
        assertTrue(rules[home]["ip_cidr"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("192.168.178.0/24"))
    }

    private fun JsonObject.dnsServers() = this["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }.associateBy { it["tag"]!!.jsonPrimitive.content }
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content

    /** An own DNS server that lives at home is asked through the home tunnel, on VPN routes and on Direct; any other as before. */
    @Test fun filterAtHomeGoesThroughTheHomeTunnel() {
        fun own(v: String, s: Setup = example, suffix: String? = null) = compile(s.copy(prefs = Prefs(filter = Filter(Filters.OWN, v, routes = true, directApps = true))), tsSuffix = suffix).dnsServers()
        val routes = listOf("dns-r-nl", "dns-r-us", "dns-r-it")
        // A tailnet address, or one in a home subnet behind Tailscale: every route and Direct ask through Tailscale, over UDP.
        for (ip in listOf("100.101.1.2", "192.168.178.5")) own(ip).let { d ->
            for (t in routes + "dns-filter") { assertEquals("$ip $t", "home-ts", d[t]!!.str("detour")); assertEquals(ip, d[t]!!.str("server")); assertEquals("udp", d[t]!!.str("type")) }
            assertTrue(d.keys.none { it.startsWith("boot-") })
        }
        // Not at home: each route's own tunnel and straight out, as before. So is a tailnet address while the tailnet is off.
        for ((ip, s) in listOf("192.168.1.5" to example, "1.1.1.1" to example, "100.101.1.2" to example.copy(home = Home()))) own(ip, s).let { d ->
            assertEquals(ip, listOf("r-nl", "r-us", "r-it"), routes.map { d[it]!!.str("detour") }); assertNull(ip, d["dns-filter"]!!["detour"])
        }
        // The home WireGuard tunnel for an address in its networks, before the tailnet.
        val wg = Home(tailscale = true, wg = WgConf.parse(FRITZ, "Home").copy(allowedIps = listOf("192.168.1.0/24", "100.64.0.0/10")))
        for (ip in listOf("192.168.1.5", "100.101.1.2")) own(ip, example.copy(home = wg)).let { d -> for (t in routes + "dns-filter") assertEquals("$ip $t", "home-wg", d[t]!!.str("detour")) }
        // A name the tailnet's DNS answers is looked up there and reached through Tailscale; any other name through the route.
        own("tls://box.tail1234.ts.net", suffix = "tail1234.ts.net").let { d ->
            for (t in routes + "dns-filter") { assertEquals("home-ts", d[t]!!.str("detour")); assertEquals("dns-tailnet", d[t]!!.str("domain_resolver")) }
            assertTrue(d.keys.none { it.startsWith("boot-") }); assertTrue("dns-tailnet" in d)
        }
        own("tls://dns.example.org").let { d -> assertEquals("r-nl", d["dns-r-nl"]!!.str("detour")); assertEquals("boot-r-nl", d["dns-r-nl"]!!.str("domain_resolver")) }
        // On another control server only the suffix the user allowed counts, never ts.net.
        val hs = example.copy(home = example.home.copy(tsControlUrl = "https://hs.example.org", tsSuffixAllowed = "hs.example.org"))
        assertEquals("r-nl", own("tls://box.tail1234.ts.net", hs, "hs.example.org")["dns-r-nl"]!!.str("detour"))
        assertEquals("home-ts", own("tls://box.hs.example.org", hs, "hs.example.org")["dns-r-nl"]!!.str("detour"))
        assertEquals("home-ts", own("100.101.1.2", hs)["dns-r-nl"]!!.str("detour"))
        // A suffix made of digits never turns a public address into a tailnet name.
        val digits = example.copy(home = example.home.copy(tsControlUrl = "https://hs.example.org", tsSuffixAllowed = "1.1"))
        assertEquals("r-nl", own("1.1.1.1", digits, "1.1")["dns-r-nl"]!!.str("detour"))
        // The home WireGuard tunnel alone, with the tailnet off.
        assertEquals("home-wg", own("192.168.1.5", example.copy(home = wg.copy(tailscale = false)))["dns-r-nl"]!!.str("detour"))
    }

    /** unbraid's filter: over HTTPS by address and TLS name, through each route's tunnel; Direct scopes go straight to it. */
    @Test fun filterScopes() {
        val quad9 = Filter("quad9")
        // Chosen but on nothing: everything as before.
        val none = compile(example.copy(prefs = Prefs(filter = quad9)))
        assertEquals("udp", none.dnsServers()["dns-r-nl"]!!.str("type"))
        assertFalse("dns-filter" in none.dnsServers())
        assertEquals(listOf("com.x", "com.y", "app.unbraid"), none.excluded())
        // VPN routes: each route's resolver is the filter, through its own tunnel, by address with its TLS name.
        val routes = compile(example.copy(prefs = Prefs(filter = quad9.copy(routes = true))))
        routes.dnsServers()["dns-r-us"]!!.let {
            assertEquals("https", it.str("type")); assertEquals("9.9.9.9", it.str("server")); assertEquals("r-us", it.str("detour")); assertEquals("/dns-query", it.str("path"))
            assertEquals("dns.quad9.net", it["tls"]!!.jsonObject.str("server_name")); assertNull(it["domain_resolver"])
        }
        // Apps set to Direct pass through unbraid, out directly, their lookups to the filter (one app kept outside).
        for (lockdown in listOf(false, true)) {
            val c = compile(example.copy(prefs = Prefs(filter = quad9.copy(directApps = true, apps = mapOf("com.y" to false)))), lockdown)
            val ctx = "lockdown=$lockdown"
            assertEquals(ctx, if (lockdown) listOf("app.unbraid") else listOf("com.y", "app.unbraid"), c.excluded())
            assertNull(ctx, c.dnsServers()["dns-filter"]!!["detour"])
            val dnsRule = c["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }.first { it["package_name"]?.jsonArray?.map { p -> p.jsonPrimitive.content } == listOf("com.x") }
            assertEquals(ctx, "dns-filter", dnsRule.str("server"))
            val out = c.rules().first { it.str("outbound") != null && it["package_name"]?.jsonArray?.map { p -> p.jsonPrimitive.content } == listOf("com.x") }
            assertEquals(ctx, "direct", out.str("outbound"))
            // Kept outside: only under lockdown does it pass through, with the phone's DNS.
            assertEquals(ctx, lockdown, c.rules().any { it["package_name"]?.jsonArray?.map { p -> p.jsonPrimitive.content } == listOf("com.y") && it.str("server") == "local" })
        }
        // Direct default: apps following it ask the filter, straight out.
        val d = example.copy(defaultRoute = DIRECT, apps = mapOf("com.a" to "us"), prefs = Prefs(filter = quad9.copy(direct = true)))
        assertEquals("dns-filter", compile(d)["dns"]!!.jsonObject.str("final"))
        assertEquals("local", compile(d.copy(prefs = Prefs(filter = quad9)))["dns"]!!.jsonObject.str("final"))
        // An app set to Direct with its lookups unfiltered keeps the phone's DNS under lockdown, where it passes through,
        // also when the Direct default asks the filter; one set to pass through asks the filter.
        val both = d.copy(apps = mapOf("com.a" to "us", "com.x" to DIRECT, "com.y" to DIRECT), prefs = Prefs(filter = quad9.copy(direct = true, apps = mapOf("com.y" to true))))
        for (lockdown in listOf(false, true)) {
            val c = compile(both, lockdown)
            val of = { pkg: String -> c["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["package_name"]?.jsonArray?.map { p -> p.jsonPrimitive.content } == listOf(pkg) }?.str("server") }
            assertEquals("lockdown=$lockdown", if (lockdown) "local" else null, of("com.x"))
            assertEquals("lockdown=$lockdown", lockdown, c.rules().any { it["package_name"]?.jsonArray?.map { p -> p.jsonPrimitive.content } == listOf("com.x") && it.str("server") == "local" })
            assertEquals("lockdown=$lockdown", "dns-filter", of("com.y"))
            assertEquals("lockdown=$lockdown", if (lockdown) listOf("app.unbraid") else listOf("com.x", "app.unbraid"), c.excluded())
        }
        // NextDNS needs a profile; an own server by name is looked up first, through the same tunnel.
        assertNull(Filter("nextdns").server())
        assertEquals("/abc123", Filter("nextdns", "abc123").server()!!.path)
        val own = compile(example.copy(prefs = Prefs(filter = Filter(Filters.OWN, "tls://dns.example.org", routes = true)))).dnsServers()
        assertEquals("tls", own["dns-r-nl"]!!.str("type")); assertEquals("boot-r-nl", own["dns-r-nl"]!!.str("domain_resolver"))
        assertEquals(own.keys.filter { it.startsWith("boot-") }.size, own.values.mapNotNull { it.str("domain_resolver") }.toSet().size); assertEquals(853, own["dns-r-nl"]!!["server_port"]!!.jsonPrimitive.content.toInt())
        assertEquals(Compiler.NORD_TP, own["boot-r-nl"]!!.str("server")); assertEquals("r-nl", own["boot-r-nl"]!!.str("detour"))
        for (bad in listOf("http://x.org", "https://x.org:99999/q", "tls://x.org/path", "dns.example.org", "1.2.3", "https://a b", "https://x.org/q%", "https://x.org/q%GG")) assertNull(bad, Filters.own(bad))
        assertEquals("/a%2Fb", Filters.own("https://x.org/a%2Fb")!!.path)
        // An older custom DNS server becomes this filter on VPN routes, the same resolver as before.
        assertEquals(Filter(Filters.OWN, "1.1.1.1", routes = true), example.copy(prefs = Prefs(customDns = listOf("1.1.1.1"))).migrated().prefs.filter)
    }

    @Test fun lockdownRoutesDirectAppsInsteadOfExcluding() {
        val c = compile(example, lockdown = true)
        assertEquals(listOf("app.unbraid"), c.excluded())
        assertTrue(c.rules().any { it["outbound"]?.jsonPrimitive?.content == "direct" && it["package_name"] != null })
    }

    /** Home subnets are used as Android reads them (host bits cleared), so the LAN exclusion never cancels one; bad ones go. */
    @Test fun homeSubnetsAreCanonical() {
        assertEquals("192.168.0.0/16", Compiler.canonical("192.168.1.0/16"))
        assertEquals("10.0.0.0/8", Compiler.canonical("10.42.1.9/8"))
        assertEquals("10.1.2.3/32", Compiler.canonical("10.1.2.3"))
        for (bad in listOf("10.0.0.1/33", "1.2.3", "1.2.3.4.5", "256.1.1.1/8", "a.b.c.d", "10.0.0.0/8/8", " ", "１.2.3.4")) assertEquals(bad, null, Compiler.canonical(bad))
        val s = example.copy(home = Home(tailscale = true, subnets = listOf("192.168.1.0/16", "192.168.0.0/16", "10.0.0.1/33", "8.8.8.0/24")))
        val c = compile(s)
        val tun = c["inbounds"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("0.0.0.0/0", "192.168.0.0/16"), tun["route_address"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(tun["route_exclude_address"]!!.jsonArray.any { it.jsonPrimitive.content == "192.168.0.0/16" })
        assertEquals(listOf("100.64.0.0/10", "192.168.0.0/16"), c.rules().first { it["outbound"]?.jsonPrimitive?.content == "home-ts" }["ip_cidr"]!!.jsonArray.map { it.jsonPrimitive.content })
        // A home WireGuard tunnel's ranges too, and its interface's own subnet when it lists no private ranges.
        val fritz = WgConf.parse(FRITZ, "Home")
        val wg = compile(example.copy(home = Home(wg = fritz.copy(allowedIps = listOf("192.168.1.0/16", "10.0.0.1/33")))))
        assertEquals(listOf("192.168.0.0/16"), wg.rules().first { it["outbound"]?.jsonPrimitive?.content == "home-wg" }["ip_cidr"]!!.jsonArray.map { it.jsonPrimitive.content })
        val wgTun = wg["inbounds"]!!.jsonArray[0].jsonObject
        assertTrue(wgTun["route_address"]!!.jsonArray.any { it.jsonPrimitive.content == "192.168.0.0/16" })
        assertFalse(wgTun["route_exclude_address"]!!.jsonArray.any { it.jsonPrimitive.content == "192.168.0.0/16" })
        assertEquals(listOf("10.7.0.0/24"), Compiler.homeCidrs(fritz.copy(allowedIps = listOf("0.0.0.0/0"), address = listOf("10.7.0.5/24", "bad", "1.2.3.4/33"))))
        assertEquals(listOf("192.168.1.0/24"), Compiler.homeCidrs(fritz.copy(allowedIps = listOf("010.0.0.0/8", "192.168.1.9/24"))))
    }

    /** A Headscale host is an address only when it truly is one: a name that starts with a digit stays a name. */
    @Test fun controlHostAddressOrName() {
        fun login(url: String): JsonObject {
            val s = example.copy(home = Home(tsControlUrl = url), routes = example.routes + Route("ts", tsExit = "100.101.102.103", hue = 4), apps = example.apps + ("com.n" to "ts"))
            return compile(s, login = true).rules().first { it["package_name"]?.jsonArray?.map { p -> p.jsonPrimitive.content } == listOf("com.n") && it["outbound"]?.jsonPrimitive?.content == "direct" && (it["domain"] != null || it["ip_cidr"] != null) }
        }
        assertEquals("1hs.example.com", login("https://1hs.example.com")["domain"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("10.1.2.3/32", login("https://10.1.2.3:8443")["ip_cidr"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("010.1.2.3", login("https://010.1.2.3")["domain"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("fd7a:115c::1/128", login("https://[fd7a:115c::1]")["ip_cidr"]!!.jsonArray.single().jsonPrimitive.content)
    }

    /** Connections a VPN server or tailnet peer opens are refused first; only the phone's apps get DNS answered. */
    @Test fun tunnelsNeverOpenConnectionsIntoThePhone() {
        val home = example.copy(home = example.home.copy(wg = WgConf.parse(FRITZ, "Home")))
        val rules = compile(home).rules()
        val first = rules.first()
        assertEquals("reject", first["action"]!!.jsonPrimitive.content)
        assertEquals(compile(home).endpointTags().toSet(), first["inbound"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertTrue(first.keys == setOf("inbound", "action")) // every protocol, TCP and UDP alike
        rules.filter { it["action"]?.jsonPrimitive?.content == "hijack-dns" }.also { assertEquals(2, it.size) }
            .forEach { assertEquals(listOf("tun"), it["inbound"]!!.jsonArray.map { t -> t.jsonPrimitive.content }) }
        assertTrue(compile(Setup(routes = emptyList(), defaultRoute = DIRECT), links = emptyMap()).rules().none { it["action"]?.jsonPrimitive?.content == "reject" && it["inbound"] != null })
        // WireGuard, two OpenVPN routes sharing one endpoint and Shadowsocks, with the probe: every endpoint once, no outbound or probe.
        val ss = SsProfile("s", "h.example", 8388, "aes-256-gcm", "pw")
        val mixed = example.copy(routes = example.routes + ovpnRoute("o1", "u") + ovpnRoute("o2", "u") + Route("s", ss = ss),
            apps = example.apps + mapOf("com.o1" to "o1", "com.o2" to "o2", "com.s" to "s"))
        val raw = Compiler.compile(Compiler.Input(mixed, links(mixed) + mapOf("o1" to OvpnLink(sv("o1"), mixed.route("o1")!!.ovpn!!), "o2" to OvpnLink(sv("o2"), mixed.route("o2")!!.ovpn!!), "s" to SsLink(sv("s"), ss)),
            "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", probe = 40000 to "pw"))
        File("build/compiled").apply { mkdirs() }.resolve("ingress-mixed.json").writeText(raw)
        val c = Json.parseToJsonElement(raw).jsonObject
        val refused = c.rules().first()["inbound"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(c.endpointTags(), refused)
        assertEquals(listOf("r-nl", "r-us", "r-it", "r-o1", "home-ts"), refused)
    }

    /** A route without a tunnel refuses its apps' connections and lookups; the other routes keep running. */
    @Test fun aBlockedRouteRefusesItsAppsOnly() = listOf(false, true).forEach { lockdown ->
        val c = compile(example, lockdown, blocked = setOf("us"))
        assertEquals(listOf("r-nl", "r-it", "home-ts"), c.endpointTags())
        assertFalse(c.toString().contains("r-us"))
        val refused = c.rules().single { it["package_name"]?.jsonArray?.map { p -> p.jsonPrimitive.content } == listOf("com.a") }
        assertEquals("reject", refused["action"]!!.jsonPrimitive.content)
        val dns = c["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertEquals("reject", dns.single { it["package_name"]?.jsonArray?.map { p -> p.jsonPrimitive.content } == listOf("com.a") }["action"]!!.jsonPrimitive.content)
        assertTrue(c.rules().any { it["outbound"]?.jsonPrimitive?.content == "r-it" })
        assertEquals("r-nl", c["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
    }

    /** A blocked default refuses everything not sent elsewhere, after the home and LAN rules, and never resolves it locally. */
    @Test fun aBlockedDefaultRefusesTheRest() = listOf(false, true).forEach { lockdown ->
        val c = compile(example, lockdown, blocked = setOf("nl"))
        val rules = c.rules()
        assertEquals(mapOf("action" to "reject", "no_drop" to "true"), rules.last().mapValues { it.value.jsonPrimitive.content })
        assertTrue(rules.indexOfFirst { it["outbound"]?.jsonPrimitive?.content == "home-ts" } < rules.size - 1)
        assertTrue(rules.any { it["outbound"]?.jsonPrimitive?.content == "r-us" } && rules.any { it["outbound"]?.jsonPrimitive?.content == "r-it" })
        assertEquals(lockdown, rules.any { it["outbound"]?.jsonPrimitive?.content == "direct" && it["package_name"] != null })
        assertEquals("reject", c["dns"]!!.jsonObject["rules"]!!.jsonArray.last().jsonObject["action"]!!.jsonPrimitive.content)
        assertFalse(c.toString().contains("r-nl"))
        assertEquals("direct", c["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
    }

    /** A default route the setup no longer has is refused with its own message, never run as Direct or as another route. */
    @Test fun missingDefaultRefuses() {
        val noRouteLeft = example.copy(defaultRoute = "gone", apps = mapOf("com.x" to DIRECT), home = Home())
        val withOthers = example.copy(defaultRoute = "gone")
        for (s in listOf(noRouteLeft, withOthers)) {
            val e = assertThrows(Compiler.MissingDefault::class.java) { compile(s) }
            assertEquals(Compiler.MISSING_DEFAULT, e.message)
            assertEquals(Compiler.MISSING_DEFAULT, app.unbraid.vpn.Redact.message(e, "fallback"))
        }
        // Direct chosen as the default is a real choice and still runs.
        assertEquals("direct", compile(example.copy(defaultRoute = DIRECT, home = Home()))["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
    }

    /** An app set to a route the setup no longer has is refused, connections and lookups, instead of following the default. */
    @Test fun appOnMissingRouteIsBlocked() = listOf(false, true).forEach { lockdown ->
        for (default in listOf("nl", DIRECT)) {
            val s = example.copy(defaultRoute = default, apps = example.apps + ("com.g" to "gone"))
            assertEquals(setOf("gone"), Compiler.missing(s))
            val c = compile(s, lockdown)
            val mine = { o: JsonObject -> o["package_name"]?.jsonArray?.map { it.jsonPrimitive.content } == listOf("com.g") }
            assertEquals(listOf(mapOf("package_name" to "[\"com.g\"]", "action" to "\"reject\"", "no_drop" to "true")),
                c.rules().filter(mine).map { r -> r.mapValues { it.value.toString() } })
            val dns = c["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
            assertEquals("reject", dns.single(mine)["action"]!!.jsonPrimitive.content)
            assertFalse(c.toString().contains("r-gone"))
            assertFalse(c.excluded().contains("com.g"))
            // The other apps keep their routes.
            assertTrue(c.rules().any { it["outbound"]?.jsonPrimitive?.content == "r-us" })
        }
    }

    /** While settings can't be read, every app's connections and lookups are refused; nothing reaches the direct outbound. */
    @Test fun blockAllRefusesEverything() = Compiler.FAKE_RANGES.indices.forEach { b ->
        val raw = Compiler.blockAll("app.unbraid", "/tmp/cache.db", b)
        File("build/compiled").apply { mkdirs() }.resolve("blockall-$b.json").writeText(raw)
        val c = Json.parseToJsonElement(raw).jsonObject
        assertEquals(listOf("app.unbraid"), c.excluded())
        val tun = c["inbounds"]!!.jsonArray.single().jsonObject
        assertNull(tun["route_exclude_address"]) // no LAN or other range left outside the VPN
        assertTrue(c["endpoints"]!!.jsonArray.isEmpty())
        assertEquals(listOf("direct"), c["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content })
        // No rule sends anything anywhere; the last one refuses whatever is left, so the final outbound is never reached.
        val rules = c.rules()
        assertTrue(rules.none { it["outbound"] != null })
        assertEquals(mapOf("action" to "reject", "no_drop" to "true"), rules.last().mapValues { it.value.jsonPrimitive.content })
        assertTrue(rules.all { it["action"]?.jsonPrimitive?.content in setOf("reject", "hijack-dns", "resolve") })
        // Lookups: names get a fake address (which is then refused), everything else is refused; nothing is asked upstream.
        val dns = c["dns"]!!.jsonObject
        assertEquals(setOf("fakeip", "local"), dns["servers"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }.toSet())
        assertEquals(mapOf("action" to "\"reject\""), dns["rules"]!!.jsonArray.last().jsonObject.mapValues { it.value.toString() })
        assertEquals(Compiler.FAKE_RANGES[b], dns["servers"]!!.jsonArray.first().jsonObject["inet4_range"]!!.jsonPrimitive.content)
        assertFalse(raw.contains("probe"))
    }

    @Test fun unusedRoutesDoNotConnect() {
        val c = compile(example.copy(apps = mapOf("com.a" to "us")))
        assertEquals(listOf("r-nl", "r-us", "home-ts"), c.endpointTags())
    }

    @Test fun directDefaultTunnelsOnlyAssignedApps() {
        val c = compile(example.copy(defaultRoute = DIRECT, home = Home()))
        assertEquals("direct", c["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        assertEquals(listOf("r-us", "r-it"), c.endpointTags())
    }

    @Test fun nothingActiveIsAllDirect() {
        val c = compile(Setup(routes = emptyList(), defaultRoute = DIRECT), links = emptyMap())
        assertTrue(c["endpoints"]!!.jsonArray.isEmpty())
    }

    @Test fun threatProtectionAndCustomDns() {
        fun dnsOf(c: JsonObject) = c["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }.first { it["tag"]!!.jsonPrimitive.content == "dns-r-nl" }["server"]!!.jsonPrimitive.content
        assertEquals(Compiler.NORD_TP, dnsOf(compile(example)))
        assertEquals(Compiler.NORD_DNS, dnsOf(compile(example.copy(opts = mapOf("nord" to mapOf(PROTECT to "off"))))))
        // A save from before the move keeps the user's choice, now on NordVPN's page.
        val old = example.copy(prefs = Prefs(threatProtection = false)).migrated()
        assertEquals("off", old.opt("nord", PROTECT))
        assertEquals(Compiler.NORD_DNS, dnsOf(compile(old)))
        assertEquals(old, old.migrated())
        // A NordVPN route that kept another provider's blocking flag follows the NordVPN page, as it did; new setups aren't migrated.
        val kept = example.copy(prefs = Prefs(threatProtection = false), routes = example.routes.map { if (it.id == "nl") it.copy(filter = true) else it }).migrated()
        assertEquals(Compiler.NORD_DNS, dnsOf(compile(kept)))
        val fresh = example.copy(version = app.unbraid.data.VERSION, routes = example.routes.map { if (it.id == "nl") it.copy(filter = false) else it })
        assertEquals(fresh, fresh.migrated())
        // One route can turn it off for itself.
        assertEquals(Compiler.NORD_DNS, dnsOf(compile(example.copy(routes = example.routes.map { if (it.id == "nl") it.copy(filter = false) else it }))))
        assertEquals("9.9.9.9", dnsOf(compile(example.copy(prefs = Prefs(customDns = listOf("9.9.9.9"))).migrated())))
    }

    @Test fun lanBypassesTheTunnelButHomeSubnetsStayRouted() {
        val tun = compile(example)["inbounds"]!!.jsonArray[0].jsonObject
        assertTrue(tun["route_exclude_address"]!!.jsonArray.any { it.jsonPrimitive.content == "192.168.0.0/16" })
        assertTrue(tun["route_address"]!!.jsonArray.any { it.jsonPrimitive.content == "192.168.178.0/24" })
        assertFalse(compile(example.copy(prefs = Prefs(lan = false)))["inbounds"]!!.jsonArray[0].jsonObject.containsKey("route_exclude_address"))
    }

    @Test fun lockdownKeepsLanInsideTheTunnel() {
        val tun = compile(example, lockdown = true)["inbounds"]!!.jsonArray[0].jsonObject
        assertFalse(tun.containsKey("route_exclude_address"))
    }

    /** With a Tailscale exit carrying traffic, its login must not ride that exit; otherwise those sites follow each app. */
    @Test fun tailscaleLoginGoesDirectOnlyWithAnExitNode() {
        fun direct(s: Setup, login: Boolean = true) = compile(s, login = login).rules().firstOrNull { it["domain_suffix"]?.toString()?.contains("tailscale.com") == true }
        assertEquals(null, direct(example))
        val withExit = example.copy(routes = example.routes + Route("ts", tsExit = "100.101.102.103", hue = 4), apps = example.apps + ("com.n" to "ts"))
        // Only while a sign-in waits: once signed in, tailscale.com follows the app's exit like any site.
        assertEquals(null, direct(withExit, login = false))
        // A Headscale tailnet signs in on its own server: Tailscale's sites never skip the exit.
        assertEquals(null, direct(withExit.copy(home = withExit.home.copy(tsControlUrl = "https://hs.example.org"))))
        assertEquals("direct", direct(withExit)!!["outbound"]!!.jsonPrimitive.content)
        // Only for the apps on that exit: a browser on another VPN keeps its own exit for tailscale.com.
        assertEquals("[\"com.n\"]", direct(withExit)!!["package_name"].toString())
        assertEquals(1, compile(withExit, login = true).rules().count { it["domain_suffix"]?.toString()?.contains("tailscale.com") == true })
        // Ahead of that app's resolve through the exit, which can't answer before the exit node is signed in.
        val rules = compile(withExit, login = true).rules()
        assertTrue(rules.indexOf(direct(withExit)) < rules.indexOfFirst { it["package_name"].toString() == "[\"com.n\"]" && it["action"]?.jsonPrimitive?.content == "resolve" })
    }

    /** Ping has no owner to route by: with Direct as the default it would leave a VPN app from the phone's address. */
    @Test fun pingIsRefusedWhenItWouldBypassAnAppsVpn() {
        fun icmp(s: Setup) = compile(s).rules().filter { it["network"]?.jsonPrimitive?.content == "icmp" }
        assertEquals(emptyList<JsonObject>(), icmp(example))
        val directDefault = example.copy(defaultRoute = DIRECT)
        assertEquals("reject", icmp(directDefault).single()["action"]!!.jsonPrimitive.content)
        // LAN and home rules come first, so pinging the router or a home box still works.
        val rules = compile(directDefault).rules()
        assertTrue(rules.indexOfFirst { it["network"]?.jsonPrimitive?.content == "icmp" } > rules.indexOfFirst { it["ip_cidr"] != null })
        assertEquals(emptyList<JsonObject>(), icmp(directDefault.copy(apps = mapOf("com.x" to DIRECT))))
    }

    /** A connection or lookup with no known app could be a VPN app's: with Direct as the default it is refused, never sent direct. */
    /** The hold for unreadable settings answers no lookup when its block of stand-in addresses couldn't be noted down. */
    @Test fun aHoldWithAnUnnotedBlockAnswersNoLookup() {
        val held = Json.parseToJsonElement(Compiler.blockAll("app.unbraid", "/tmp/cache.db", 3, lookups = false)).jsonObject
        assertEquals("""{"action":"reject"}""", held["dns"]!!.jsonObject["rules"]!!.jsonArray.first().toString())
        val usual = Json.parseToJsonElement(Compiler.blockAll("app.unbraid", "/tmp/cache.db", 3)).jsonObject
        assertEquals(usual["route"], held["route"])
        assertEquals(null, held["experimental"]?.jsonObject?.get("cache_file"))
        assertEquals(usual["dns"]!!.jsonObject["rules"]!!.jsonArray.size + 1, held["dns"]!!.jsonObject["rules"]!!.jsonArray.size)
    }

    @Test fun unattributedDataCannotUseADifferentVpnDefault() {
        val traffic = app.unbraid.data.RestoreTraffic
        val setup = example.copy(apps = mapOf("com.a" to "us"))
        for (assigned in listOf("us", "removed")) for (blocked in listOf(emptySet(), setOf("us"))) {
            val s = setup.copy(apps = mapOf("com.a" to assigned))
            val c = app.unbraid.data.RestoreTraffic.Config(compile(s, blocked = blocked).toString(), s)
            for (net in listOf("tcp", "udp")) for (name in listOf(null, "example.org")) {
                val step = traffic.walk(c, emptySet(), name, Compiler.ipv4("203.0.113.4")!!.first, net, null)!!
                assertEquals(traffic.HELD, step.out.exit)
                assertEquals(app.unbraid.data.RestoreTraffic.By.App, step.by)
                assertEquals("r-nl", traffic.connect(c, setOf("com.other"), name, net = net)?.exit)
                assertEquals(if (assigned == "us" && blocked.isEmpty()) "r-us" else traffic.REJECT,
                    traffic.connect(c, setOf("com.a"), name, net = net)?.exit)
            }
            assertEquals("r-nl", traffic.connect(c, emptySet(), null, net = "icmp")?.exit)
        }
    }

    @Test fun unattributedDataOnlyNeedsHoldingForInTunnelAssignments() {
        val traffic = app.unbraid.data.RestoreTraffic
        fun unknown(s: Setup, lockdown: Boolean = false, kin: Set<String> = emptySet()): String? {
            val input = Compiler.Input(s, links(s), "app.unbraid", lockdown, "/tmp/ts", "/tmp/cache.db", blockedKin = kin)
            return traffic.connect(app.unbraid.data.RestoreTraffic.Config(Compiler.compile(input), s), emptySet(), null)?.exit
        }
        for (apps in listOf(emptyMap(), mapOf("com.a" to "nl"), mapOf("com.a" to DIRECT)))
            assertEquals("r-nl", unknown(example.copy(apps = apps)))
        val direct = example.copy(apps = mapOf("com.a" to DIRECT))
        assertEquals(traffic.HELD, unknown(direct, lockdown = true))
        assertEquals("r-nl", unknown(direct, lockdown = true, kin = setOf("com.a")))
        val filtered = direct.copy(prefs = direct.prefs.copy(filter = Filter(id = "quad9", directApps = true)))
        assertEquals(traffic.HELD, unknown(filtered))
        assertEquals("r-nl", unknown(filtered, kin = setOf("com.a")))
    }

    @Test fun aVpnDefaultsOwnerGuardPreservesExplicitRouteExceptions() {
        val traffic = app.unbraid.data.RestoreTraffic
        val s = example.copy(defaultRoute = "ts", routes = example.routes + Route("ts", tsExit = "nas"),
            apps = mapOf("com.a" to "us"), sites = mapOf("example.org" to "us"))
        val raw = Compiler.compile(Compiler.Input(s, links(s), "app.unbraid", false, "/tmp/ts", "/tmp/cache.db",
            tsLoginPending = true, probe = 40000 to "pw"))
        val c = app.unbraid.data.RestoreTraffic.Config(raw, s)
        for ((addr, exit) in listOf("192.168.1.9" to "direct", "192.168.178.7" to Compiler.TS_TAG, "100.100.100.9" to Compiler.TS_TAG))
            assertEquals(exit, traffic.connect(c, emptySet(), null, address = addr)?.exit)
        assertEquals("r-us", traffic.connect(c, emptySet(), "example.org")?.exit)
        assertEquals("direct", traffic.connect(c, setOf("com.default"), "login.tailscale.com")?.exit)
        assertEquals("r-us", traffic.connect(c, setOf("com.a"), "login.tailscale.com")?.exit)
        assertEquals(traffic.HELD, traffic.connect(c, emptySet(), "login.tailscale.com")?.exit)

        val rules = Json.parseToJsonElement(raw).jsonObject.rules()
        val guard = rules.indexOfFirst { it["action"]?.jsonPrimitive?.content == "reject" && it["rules"]?.toString()?.contains("package_name_regex") == true }
        val probes = rules.withIndex().filter { it.value["auth_user"] != null }
        assertTrue(probes.isNotEmpty() && probes.all { it.index < guard })
        assertTrue(rules[guard]["rules"]!!.jsonArray.contains(Json.parseToJsonElement("""{"inbound":["tun"]}""")))
    }

    @Test fun unknownOwnerIsRefusedWhenDirectDefaultAndAnAppUsesAVpn() {
        val unknown = """{"package_name_regex":["."],"invert":true,"action":"reject"}"""
        // A lookup's: from the phone's apps only, as the tailnet's own lookups name no app either. Addresses pass (a
        // stand-in, as for every app: Android often can't name the app of an ordinary app's lookup); HTTPS and SVCB are
        // answered empty; the rest is refused.
        val who = """[{"inbound":["tun"]},{"package_name_regex":["."],"invert":true}"""
        val unknownLookups = listOf(
            """{"type":"logical","mode":"and","rules":$who,{"query_type":["HTTPS","SVCB"]}],"action":"predefined","rcode":"NOERROR"}""",
            """{"type":"logical","mode":"and","rules":$who,{"query_type":["A","AAAA"],"invert":true}],"action":"reject"}""")
        fun rules(s: Setup) = compile(s).rules()
        fun dnsOf(c: JsonObject) = c["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.toString() }
        fun conns(s: Setup, lockdown: Boolean = false, blocked: Set<String> = emptySet()) = compile(s, lockdown, blocked = blocked).rules().count { it.toString() == unknown }
        fun lookups(s: Setup, lockdown: Boolean = false, blocked: Set<String> = emptySet()) =
            dnsOf(compile(s, lockdown, blocked = blocked)).filter { it in unknownLookups }.also { assertTrue(it.isEmpty() || it == unknownLookups) }.isNotEmpty()
        val directDefault = example.copy(defaultRoute = DIRECT)
        assertEquals(1, conns(directDefault))
        assertTrue(lookups(directDefault))
        // After every app rule and the LAN and home rules, before the default's own lookup.
        val r = rules(directDefault)
        val at = r.indexOfFirst { it.toString() == unknown }
        assertTrue(at > r.indexOfLast { it["package_name"] != null })
        assertTrue(at > r.indexOfFirst { it["ip_cidr"]?.toString()?.contains("192.168.0.0/16") == true })
        assertEquals("""{"action":"resolve","server":"local"}""", r[at + 1].toString())
        // Lookups and the app's own probes come first and are left alone.
        assertTrue(r.indexOfFirst { it["action"]?.jsonPrimitive?.content == "hijack-dns" } < at)
        // A lookup with no known app: after the stand-in addresses and every app's own rule.
        val dns = dnsOf(compile(directDefault))
        val asked = dns.indexOf(unknownLookups[0])
        assertEquals(asked + 1, dns.indexOf(unknownLookups[1]))
        val dnsObjs = compile(directDefault)["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertTrue(asked > dnsObjs.indexOfFirst { it.str("server") == "fake" })
        assertTrue(asked > dnsObjs.indexOfLast { it["package_name"] != null })
        assertEquals("local", compile(directDefault)["dns"]!!.jsonObject.str("final"))
        val probe = RoutingProbe.compiled(directDefault, null).first
        assertEquals("fake", RoutingProbe.lookup(probe, "com.a", "example.org", "A"))
        assertEquals("dns-r-us", RoutingProbe.lookup(probe, "com.a", "example.org", "TXT"))
        assertEquals("fake", RoutingProbe.lookup(probe, "", "example.org", "A"))
        assertEquals("reject", RoutingProbe.lookup(probe, "", "example.org", "TXT"))
        assertEquals("empty", RoutingProbe.lookup(probe, "", "example.org", "HTTPS"))
        val walked = app.unbraid.data.RestoreTraffic.compile(directDefault.copy(home = Home()), { emptySet() })!!
        assertEquals(app.unbraid.data.RestoreTraffic.HELD, app.unbraid.data.RestoreTraffic.lookup(walked, emptySet(), "example.org", "TXT")!!.out.exit)
        // An app on a route that is blocked or missing still counts (its apps are refused).
        assertEquals(1, conns(directDefault, blocked = setOf("us")))
        assertEquals(1, conns(directDefault.copy(apps = mapOf("com.a" to "gone", "com.x" to DIRECT))))
        // The Direct-default rule also covers ICMP. A VPN default uses a separate data-only rule.
        assertEquals(0, conns(example))
        assertEquals(0, conns(directDefault.copy(apps = mapOf("com.x" to DIRECT))))
        assertEquals(0, conns(directDefault.copy(apps = mapOf("com.x" to DIRECT)), lockdown = true))
        assertEquals(0, conns(directDefault.copy(apps = emptyMap())))
        // Lookups: refused whenever an app has a rule of its own, a VPN default with an app on another VPN included (its
        // TXT lookups would go to the default's resolver); not while every app goes the default's way.
        assertTrue(lookups(example))
        // There an app's TXT lookup Android names no app for would reach the default VPN's resolver, not the app's own.
        val vpnProbe = RoutingProbe.compiled(example, null).first
        assertEquals("reject", RoutingProbe.lookup(vpnProbe, "", "example.org", "TXT"))
        assertEquals("fake", RoutingProbe.lookup(vpnProbe, "", "example.org", "A"))
        assertFalse(lookups(example.copy(apps = emptyMap())))
        assertFalse(lookups(directDefault.copy(apps = mapOf("com.x" to DIRECT))))
        assertFalse(lookups(directDefault.copy(apps = emptyMap())))
        // With an app on Block, also with no app on a VPN: a lookup of no known app may be the blocked app's.
        val block = directDefault.copy(apps = mapOf("com.b" to app.unbraid.data.BLOCK))
        assertTrue(lookups(block))
        val blockProbe = RoutingProbe.compiled(block, null).first
        assertEquals("reject", RoutingProbe.lookup(blockProbe, "", "example.org", "TXT"))
        // Addresses still get a stand-in: an ordinary app's lookup Android can't name must not fail under Block.
        assertEquals("fake", RoutingProbe.lookup(blockProbe, "", "example.org", "A"))
        assertEquals("reject", RoutingProbe.lookup(blockProbe, "com.b", "example.org", "A"))
        assertEquals("local", RoutingProbe.lookup(blockProbe, "com.x", "example.org", "TXT"))
        // Blocked apps are kept off the local network too (the default): a local name of no known app is refused, as its
        // connections are; ordinary apps' are answered. Letting blocked apps reach it answers every local name.
        assertEquals("reject", RoutingProbe.lookup(blockProbe, "", "printer.lan", "A"))
        assertEquals("local", RoutingProbe.lookup(blockProbe, "com.x", "printer.lan", "A"))
        val openProbe = RoutingProbe.compiled(block.copy(prefs = block.prefs.copy(blockLan = false)), null).first
        assertEquals("local", RoutingProbe.lookup(openProbe, "", "printer.lan", "A"))
        val walkedBlock = app.unbraid.data.RestoreTraffic.compile(block.copy(home = Home()), { emptySet() })!!
        assertEquals(app.unbraid.data.RestoreTraffic.HELD, app.unbraid.data.RestoreTraffic.lookup(walkedBlock, emptySet(), "example.org", "TXT")!!.out.exit)
    }

    /**
     * Android leaves apps out of the VPN by user id: a Direct app that shares one with an app on a VPN route stays inside,
     * with no rule of its own, so the user id goes as that app does instead of taking it out.
     */
    @Test fun aDirectAppSharingAUserIdWithARoutedAppStaysInside() {
        val s = example.copy(apps = mapOf("com.a" to "us", "com.x" to DIRECT, "com.y" to DIRECT))
        val kin = { p: String -> if (p == "com.a" || p == "com.x") setOf("com.a", "com.x") else setOf(p) }
        val kept = Compiler.keptIn(s, kin)
        assertEquals(setOf("com.x"), kept)
        // A failed lookup keeps the app inside too; one with no other app on its user id (or not installed) is left out.
        assertEquals(setOf("com.x", "com.y"), Compiler.keptIn(s) { p -> if (p == "com.y") null else kin(p) })
        assertEquals(emptySet<String>(), Compiler.keptIn(s) { emptySet() })
        for (lockdown in listOf(false, true)) {
            val c = Json.parseToJsonElement(Compiler.compile(Compiler.Input(s, links(s), "app.unbraid", lockdown, "/tmp/ts", "/tmp/cache.db", blockedKin = kept))).jsonObject
            assertEquals(if (lockdown) listOf("app.unbraid") else listOf("com.y", "app.unbraid"), c.excluded())
            assertFalse("lockdown=$lockdown", c.toString().contains("\"com.x\""))
        }
        // The engine sees the user id's apps together: com.x's connections take com.a's exit.
        val walked = app.unbraid.data.RestoreTraffic.compile(s.copy(home = Home()), kin)!!
        assertEquals("r-us", app.unbraid.data.RestoreTraffic.connect(walked, setOf("com.a", "com.x"), "example.org")!!.exit)
        // With its lookups filtered it passes through anyway, but its own filtered Direct rule would take com.a along: none either.
        val filtered = s.copy(prefs = Prefs(filter = Filter("quad9", apps = mapOf("com.x" to true))))
        assertEquals(setOf("com.x"), Compiler.keptIn(filtered, kin))
        val f = Json.parseToJsonElement(Compiler.compile(Compiler.Input(filtered, links(filtered), "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", blockedKin = setOf("com.x")))).jsonObject
        assertFalse(f.toString().contains("\"com.x\""))
        assertEquals(listOf("com.y", "app.unbraid"), f.excluded())
        assertEquals("r-us", app.unbraid.data.RestoreTraffic.connect(app.unbraid.data.RestoreTraffic.compile(filtered.copy(home = Home()), kin)!!, setOf("com.a", "com.x"), "example.org")!!.exit)
        // A Direct website is still looked up with the filter: the filtered app kept inside was the only one passing through.
        val site = filtered.copy(sites = mapOf("example.net" to DIRECT))
        fun siteServer(kept: Set<String>) = Json.parseToJsonElement(Compiler.compile(Compiler.Input(site, links(site), "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", blockedKin = kept))).jsonObject["dns"]!!
            .jsonObject["rules"]!!.jsonArray.map { it.jsonObject }.first { it["domain"]?.toString()?.contains("example.net") == true && it["server"] != null }.str("server")
        assertEquals("dns-filter", siteServer(emptySet()))
        assertEquals("dns-filter", siteServer(setOf("com.x")))
        // Next to an app that only follows a Direct default, a filtered one keeps its filter: both go Direct anyway.
        assertEquals(emptySet<String>(), Compiler.keptIn(filtered.copy(defaultRoute = DIRECT, apps = mapOf("com.x" to DIRECT)), kin))
    }

    /** Over plain HTTP the Headscale key exchange could be faked, so such a control URL is refused, not used. */
    @Test fun headscaleNeedsHttps() {
        fun with(url: String) = example.copy(home = example.home.copy(tailscale = true, tsControlUrl = url))
        assertTrue(compile(with("https://hs.example.com")).toString().contains("https://hs.example.com"))
        assertTrue(runCatching { compile(with("http://hs.example.com")) }.exceptionOrNull() is IllegalArgumentException)
    }

    /** Apps test "online?" with a TCP connect to 8.8.8.8:53; a hijacked TCP flow never completes, so only UDP is hijacked. */
    @Test fun onlyUdpDnsIsHijackedSoTcpPort53Connects() {
        val byPort = compile(example).rules().filter { it["port"]?.jsonPrimitive?.content == "53" }
        assertEquals(1, byPort.size)
        assertEquals("udp", byPort[0]["network"]!!.jsonPrimitive.content)
        assertEquals("hijack-dns", byPort[0]["action"]!!.jsonPrimitive.content)
    }

    /** A route asking for its provider's blocking resolver uses it; a custom DNS still wins. */
    @Test fun providerBlockingDnsPerRoute() {
        fun dns(s: Setup, id: String) = compile(s)["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
            .first { it["tag"]!!.jsonPrimitive.content == "dns-r-$id" }["server"]!!.jsonPrimitive.content
        val r = example.routes.first { it.id != example.defaultRoute }
        // Each provider's blocking resolver while on for the route or its provider page; a custom DNS server replaces it (the choice is kept for later).
        for ((provider, blocking) in mapOf("mullvad" to "100.64.0.7", "pia" to "10.0.0.244", "ivpn" to "10.0.254.2")) {
            val moved = example.copy(routes = example.routes.map { if (it.id == r.id) it.copy(loc = it.loc.copy(provider = provider)) else it })
            val on = moved.copy(routes = moved.routes.map { if (it.id == r.id) it.copy(filter = true) else it })
            assertEquals(provider, blocking, dns(on, r.id))
            assertEquals(provider, blocking, dns(moved.copy(opts = mapOf(provider to mapOf(PROTECT to "on"))), r.id))
            assertNotEquals(provider, blocking, dns(moved, r.id))
            assertEquals(provider, "1.1.1.1", dns(on.copy(prefs = on.prefs.copy(filter = Filter(Filters.OWN, "1.1.1.1", routes = true))), r.id))
            assertTrue(provider, on.route(r.id)!!.filter!!)
        }
    }

    @Test fun lanOffRemovesDirectLanRule() {
        assertFalse(compile(example.copy(prefs = Prefs(lan = false))).rules().any { it["outbound"]?.jsonPrimitive?.content == "direct" && it["ip_cidr"] != null })
    }

    @Test fun customWireGuardRoute() {
        val peer = WgConf.parse(FRITZ, "Home box")
        val s = example.copy(routes = example.routes + Route("w1", wg = peer, hue = 3), apps = example.apps + ("com.c" to "w1"))
        val ep = compile(s)["endpoints"]!!.jsonArray.map { it.jsonObject }.first { it["tag"]!!.jsonPrimitive.content == "r-w1" }
        assertEquals("abc.myfritz.net", ep["peers"]!!.jsonArray[0].jsonObject["address"]!!.jsonPrimitive.content)
        assertEquals(51820, ep["peers"]!!.jsonArray[0].jsonObject["port"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun tailscaleExitRouteRidesTheTailscaleEndpoint() {
        val s = example.copy(home = Home(), routes = example.routes + Route("ts", tsExit = "nas", hue = 4), apps = example.apps + ("com.n" to "ts"))
        val c = compile(s)
        val ts = c["endpoints"]!!.jsonArray.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "tailscale" }
        assertEquals("nas", ts["exit_node"]!!.jsonPrimitive.content)
        assertTrue(c.rules().any { it["outbound"]?.jsonPrimitive?.content == "home-ts" && it["package_name"]?.jsonArray?.any { p -> p.jsonPrimitive.content == "com.n" } == true })
        assertFalse(c.endpointTags().contains("r-ts"))
    }

    /** Keeping an exit alive must not grant every app the separately controlled home access. */
    @Test fun tailnetAccessToggleIsIndependentOfAnActiveExit() {
        val subnets = listOf("192.168.0.0/16", "10.42.0.0/16")
        for (homeAccess in listOf(false, true)) for (default in listOf(DIRECT, "nl", "ts")) {
            for (lan in listOf(false, true)) for (lockdown in listOf(false, true)) {
                val s = example.copy(
                    home = Home(tailscale = homeAccess, subnets = subnets),
                    routes = example.routes + Route("ts", tsExit = "100.101.102.103", hue = 4),
                    defaultRoute = default,
                    apps = example.apps + ("com.n" to "ts"),
                    prefs = example.prefs.copy(lan = lan),
                )
                val c = compile(s, lockdown, tsSuffix = "tail1234.ts.net")
                val context = "home=$homeAccess default=$default lan=$lan lockdown=$lockdown"
                val homeRules = c.rules().filter { it["outbound"]?.jsonPrimitive?.content == "home-ts" && it["ip_cidr"] != null }
                assertEquals(context, homeAccess, homeRules.isNotEmpty())
                homeRules.forEach { rule ->
                    assertEquals(context, listOf("100.64.0.0/10") + subnets, rule["ip_cidr"]!!.jsonArray.map { it.jsonPrimitive.content })
                }
                val dns = c["dns"]!!.jsonObject
                val tailnetDns = dns["rules"]!!.jsonArray.map { it.jsonObject }.filter { it["server"]?.jsonPrimitive?.content == "dns-tailnet" }
                assertEquals(context, homeAccess, tailnetDns.isNotEmpty())
                if (homeAccess) assertEquals(listOf("ts.net", "tail1234.ts.net"), tailnetDns.single()["domain_suffix"]!!.jsonArray.map { it.jsonPrimitive.content })
                assertEquals(context, homeAccess, dns["servers"]!!.jsonArray.any { it.jsonObject["tag"]?.jsonPrimitive?.content == "dns-tailnet" })
                val tun = c["inbounds"]!!.jsonArray[0].jsonObject
                if (lan && !lockdown) {
                    assertEquals(context, listOf("0.0.0.0/0") + if (homeAccess) subnets else emptyList(), tun["route_address"]!!.jsonArray.map { it.jsonPrimitive.content })
                    assertEquals(context, !homeAccess, tun["route_exclude_address"]!!.jsonArray.any { it.jsonPrimitive.content == "192.168.0.0/16" })
                }
                val ts = c["endpoints"]!!.jsonArray.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "tailscale" }
                assertEquals(context, "100.101.102.103", ts["exit_node"]!!.jsonPrimitive.content)
                assertEquals(context, "home-ts", c.dnsServer("dns-r-ts")["detour"]!!.jsonPrimitive.content)
                assertEquals(context, when (default) { DIRECT -> "direct"; "ts" -> "home-ts"; else -> "r-nl" }, c["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
                if (default != "ts") assertTrue(context, c.rules().any { it["outbound"]?.jsonPrimitive?.content == "home-ts" && it["package_name"]?.jsonArray?.any { p -> p.jsonPrimitive.content == "com.n" } == true })
                assertTrue(context, c.rules().any { it["outbound"]?.jsonPrimitive?.content == "r-us" && it["package_name"]?.jsonArray?.any { p -> p.jsonPrimitive.content == "com.a" } == true })
            }
        }
    }

    @Test fun disablingTailnetAccessPreservesTheSeparateHomeWireGuardTunnel() {
        val s = example.copy(
            home = Home(subnets = listOf("10.42.0.0/16"), wg = WgConf.parse(FRITZ, "Home")),
            routes = example.routes + Route("ts", tsExit = "100.101.102.103", hue = 4),
            apps = example.apps + ("com.n" to "ts"),
        )
        val c = compile(s)
        assertTrue(c.rules().any { it["outbound"]?.jsonPrimitive?.content == "home-wg" && it["ip_cidr"]?.jsonArray?.any { ip -> ip.jsonPrimitive.content == "192.168.178.0/24" } == true })
        assertEquals(listOf("0.0.0.0/0", "192.168.178.0/24"), c["inbounds"]!!.jsonArray[0].jsonObject["route_address"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun nordOpenVpnKeepsNordDnsWhileOtherOpenVpnUsesPushedDns() {
        val ovpn = javaClass.getResource("/ovpn/nord-double.udp.ovpn")!!.readText()
        val p = app.unbraid.data.Manual.parseOvpn(ovpn, "dv", "u", "p")
        val s = example.copy(home = Home(), routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands")), Route("ex", Location("expressvpn", countryCode = "JP", country = "Japan"))), apps = mapOf("com.e" to "ex"))
        val c = compile(s, links = mapOf("nl" to app.unbraid.data.OvpnLink(sv("nl"), p), "ex" to app.unbraid.data.OvpnLink(sv("exx"), p.copy(username = "other"))))
        val dns = c["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        assertEquals("udp", dns.first { it["tag"]!!.jsonPrimitive.content == "dns-r-nl" }["type"]!!.jsonPrimitive.content)
        assertEquals("openvpn", dns.first { it["tag"]!!.jsonPrimitive.content == "dns-ovpn-r-ex" }["type"]!!.jsonPrimitive.content)
    }

    /** A NordVPN OpenVPN route (Nord's DNS) and an import of the same profile share a tunnel, in either order. */
    @Test fun nordAndImportSharingOneOpenVpnTunnelKeepPushedDns() {
        val p = app.unbraid.data.Manual.parseOvpn(javaClass.getResource("/ovpn/nord-double.udp.ovpn")!!.readText(), "dv", "u", "p")
        val nord = Route("nl", Location("nord", 153, "NL", "Netherlands")); val imp = Route("im", ovpn = p, hue = 1)
        for (order in listOf(listOf(nord, imp), listOf(imp, nord))) for (custom in listOf(emptyList(), listOf("1.1.1.1"))) {
            val s = Setup(routes = order, defaultRoute = order[0].id, apps = mapOf("com.x" to order[1].id), prefs = Prefs(filter = if (custom.isEmpty()) Filter() else Filter(Filters.OWN, custom[0], routes = true)))
            val raw = Compiler.compile(Compiler.Input(s, mapOf("nl" to OvpnLink(sv("nl"), p), "im" to OvpnLink(sv("im"), p)), "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", probe = 40000 to "pw"))
            File("build/compiled").apply { mkdirs() }.resolve("ovpn-mixed-${order[0].id}-${custom.size}.json").writeText(raw)
            val c = Json.parseToJsonElement(raw).jsonObject
            val ctx = "order=${order.map { it.id }} custom=$custom"
            val ep = c["endpoints"]!!.jsonArray.single().jsonObject
            val servers = c["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
            val ovpn = servers.filter { it["type"]!!.jsonPrimitive.content == "openvpn" }
            if (custom.isEmpty()) {
                // The import uses the pushed DNS: one server for the tunnel, which keeps it whichever route owns the tunnel.
                assertEquals(ctx, listOf(ep["tag"]!!.jsonPrimitive.content), ovpn.map { it["endpoint"]!!.jsonPrimitive.content })
                assertFalse(ctx, ep.containsKey("route_no_pull"))
            } else {
                assertTrue(ctx, ovpn.isEmpty())
                assertEquals(ctx, "true", ep["route_no_pull"]!!.jsonPrimitive.content)
            }
            // Nord's route keeps Nord's resolver through the tunnel either way.
            assertEquals(ctx, if (custom.isEmpty()) Compiler.NORD_TP else "1.1.1.1", servers.first { it["tag"]!!.jsonPrimitive.content == "dns-r-nl" }["server"]!!.jsonPrimitive.content)
            val tags = servers.map { it["tag"]!!.jsonPrimitive.content }.toSet()
            val used = (c["dns"]!!.jsonObject["rules"]!!.jsonArray + c["route"]!!.jsonObject["rules"]!!.jsonArray).mapNotNull { it.jsonObject["server"]?.jsonPrimitive?.content }
            assertTrue(ctx, used.all { it in tags })
        }
    }

    @Test fun homeTunnelNeverCatchesAll() {
        val full = WgConf.parse(FRITZ.replace("192.168.178.0/24, ", ""), "Home") // AllowedIPs = 0.0.0.0/0 only
        assertEquals(listOf("192.168.178.0/24"), Compiler.homeCidrs(full))
        val c = compile(example.copy(home = Home(wg = full)))
        c.rules().filter { it["outbound"]?.jsonPrimitive?.content == "home-wg" }.forEach { r ->
            assertFalse(r["ip_cidr"]!!.jsonArray.any { it.jsonPrimitive.content.endsWith("/0") })
        }
    }

    @Test fun homeTunnelIgnoresPublicRanges() {
        val evil = WgConf.parse(FRITZ.replace("192.168.178.0/24, 0.0.0.0/0, ::/0", "0.0.0.0/1, 128.0.0.0/1, 10.9.0.0/16"), "Home")
        assertEquals(listOf("10.9.0.0/16"), Compiler.homeCidrs(evil))
        assertTrue(Compiler.isPrivate("192.168.178.0/24") && Compiler.isPrivate("100.100.1.0/24"))
        assertFalse(Compiler.isPrivate("0.0.0.0/1") || Compiler.isPrivate("8.8.8.0/24") || Compiler.isPrivate("10.0.0.0/7"))
    }

    @Test fun publicBoxTldIsNotLocal() {
        val dns = compile(example)["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertFalse(dns.any { r -> r["domain_suffix"]?.jsonArray?.any { it.jsonPrimitive.content == "box" } == true })
    }

    @Test(expected = IllegalArgumentException::class) fun wgConfWithTwoPeersIsRejected() {
        WgConf.parse(FRITZ + "\n[Peer]\nPublicKey = 5p4RkybdRU5uaDi90eu4KZPTFif0lKCg4Qp6t1c4F30=\nEndpoint = x:1\nAllowedIPs = 10.0.0.0/8\n", "x")
    }

    @Test(expected = IllegalArgumentException::class) fun wgConfWithBadKeyIsRejected() {
        WgConf.parse(FRITZ.replace("PrivateKey = aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8tMTI=", "PrivateKey = nope"), "x")
    }

    @Test(expected = IllegalArgumentException::class) fun wgConfWithoutIpv4AllowedIpsIsRejected() {
        WgConf.parse(FRITZ.replace("AllowedIPs = 192.168.178.0/24, 0.0.0.0/0, ::/0", "AllowedIPs = ::/0"), "x")
    }

    @Test fun nonAddressDnsFollowsTheAppsExit() {
        val rules = compile(example.copy(defaultRoute = DIRECT))["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val us = rules.first { it["package_name"]?.jsonArray?.map { p -> p.jsonPrimitive.content } == listOf("com.a") }
        assertEquals("dns-r-us", us["server"]!!.jsonPrimitive.content)
    }

    @Test fun homeAndLanRulesFollowEveryResolve() {
        val rules = compile(example).rules()
        rules.withIndex().filter { it.value["action"]?.jsonPrimitive?.content == "resolve" }.forEach { (i, _) ->
            assertEquals("home-ts", rules[i + 1]["outbound"]!!.jsonPrimitive.content)
        }
    }

    @Test fun wgConfParsesFritzExport() {
        val p = WgConf.parse(FRITZ, "Home")
        assertEquals(listOf("192.168.178.201/24"), p.address)
        assertEquals(listOf("192.168.178.0/24", "0.0.0.0/0"), p.allowedIps)
        assertEquals(listOf("192.168.178.1"), p.dns)
        assertEquals("abc.myfritz.net:51820", p.endpoint)
    }

    @Test fun parsesNordRecommendations() {
        val body = javaClass.getResource("/recommendations.json")!!.readText()
        val s = NordApi.parseServers(body).single()
        assertTrue(s.host.matches(Regex("nl\\d+\\.nordvpn\\.com")))
        assertEquals("Amsterdam", s.city)
        assertEquals(44, s.publicKey.length)
    }

    // ---- shared tunnels ----

    private fun two(second: Link, first: Link = WgLink(sv("a"), Nord.peer(sv("a"), key)), s: Setup = Setup(
        routes = listOf(Route("a", Location("nord", 1, "NL", "Netherlands")), Route("b", Location("nord", 2, "DE", "Germany"), hue = 1)),
        defaultRoute = "a", apps = mapOf("com.b" to "b"),
    )) = compile(s, links = mapOf("a" to first, "b" to second)) to s

    @Test fun sameServerAndKeyShareOneEndpoint() {
        val (c, _) = two(WgLink(sv("a"), Nord.peer(sv("a"), key)))
        assertEquals(listOf("r-a"), c.endpointTags())
        assertTrue(c.rules().any { it["outbound"]?.jsonPrimitive?.content == "r-a" && it["package_name"]?.jsonArray?.any { p -> p.jsonPrimitive.content == "com.b" } == true })
        assertFalse(c.toString().contains("\"r-b\""))
        val dns = c["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }.first { it["tag"]!!.jsonPrimitive.content == "dns-r-b" }
        assertEquals("r-a", dns["detour"]!!.jsonPrimitive.content)
    }

    /** A disguised tunnel goes through its own V2Ray hop; the engine accepts the result. */
    @Test fun disguisedTunnelGoesThroughV2Ray() {
        val v = app.unbraid.data.Vmess("149.22.83.102", 80, "27de860d-5601-412d-8b71-baa048a94b98")
        val peer = Nord.peer(sv("b"), "5p4RkybdRU5uaDi90eu4KZPTFif0lKCg4Qp6t1c4F30=").copy(vmess = v)
        val (c, _) = two(WgLink(sv("b"), peer))
        val ep = c["endpoints"]!!.jsonArray.map { it.jsonObject }.single { it["tag"]!!.jsonPrimitive.content == "r-b" }
        assertEquals("v2-r-b", ep["detour"]!!.jsonPrimitive.content)
        assertNull(c["endpoints"]!!.jsonArray.map { it.jsonObject }.single { it["tag"]!!.jsonPrimitive.content == "r-a" }["detour"])
        val o = c["outbounds"]!!.jsonArray.map { it.jsonObject }.single { it["tag"]!!.jsonPrimitive.content == "v2-r-b" }
        assertEquals(listOf("vmess", "149.22.83.102", "80", v.uuid, "http"), listOf(o["type"], o["server"], o["server_port"], o["uuid"], o["transport"]!!.jsonObject["type"]).map { it!!.jsonPrimitive.content })
        // A bad id or a local address never reaches the engine.
        for (bad in listOf(v.copy(uuid = "x"), v.copy(server = "10.0.0.1"))) try { two(WgLink(sv("b"), peer.copy(vmess = bad))); fail() } catch (e: IllegalArgumentException) { }
        val bin = java.io.File(System.getenv("SING_BOX_CLI") ?: "${System.getenv("WORK") ?: "${System.getProperty("user.home")}/.cache/unbraid-libbox"}/sing-box-cli")
        org.junit.Assume.assumeTrue("sing-box-cli not found", bin.canExecute())
        val f = java.io.File.createTempFile("v2ray", ".json")
        try {
            f.writeText(c.toString())
            val p = ProcessBuilder(bin.path, "check", "-c", f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue("sing-box check timed out", p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("sing-box check: $out", 0, p.exitValue())
        } finally { f.delete() }
    }

    /** Via: route b's tunnel goes through route a's; a runs even with no app of its own; the engine accepts it. */
    @Test fun viaGoesThroughTheEntryTunnel() {
        val s = Setup(
            routes = listOf(Route("a", Location("nord", 1, "NL", "Netherlands")), Route("b", Location("nord", 2, "DE", "Germany"), hue = 1, via = "a")),
            defaultRoute = "b",
        )
        assertEquals(listOf("a", "b"), s.activeRoutes.map { it.id })
        val other = "5p4RkybdRU5uaDi90eu4KZPTFif0lKCg4Qp6t1c4F30="
        val links = mapOf("a" to WgLink(sv("a"), Nord.peer(sv("a"), key)), "b" to WgLink(sv("b"), Nord.peer(sv("b"), other)))
        val picked = app.unbraid.vpn.LinkPicker.ported(s, links, emptyMap())
        assertEquals(1200, (picked["b"] as WgLink).peer.mtu)
        assertTrue((picked["a"] as WgLink).peer.mtu > 1200)
        val c = compile(s, links = picked)
        val eps = c["endpoints"]!!.jsonArray.map { it.jsonObject }.associateBy { it["tag"]!!.jsonPrimitive.content }
        assertEquals("r-a", eps.getValue("r-b")["detour"]!!.jsonPrimitive.content)
        assertNull(eps.getValue("r-a")["detour"])
        // The same server and key straight and through a Via are two paths: a clash, never one shared tunnel.
        val both = s.copy(routes = s.routes + Route("c", Location("nord", 2, "DE", "Germany"), hue = 2), apps = mapOf("x" to "c"))
        assertEquals(setOf("c"), Compiler.clashes(both, links + ("c" to links.getValue("b"))))
        // A disguised tunnel and a plain one on the same key and server are never one tunnel: the later one gives way.
        val disguised = (links.getValue("a") as WgLink).let { it.copy(peer = it.peer.copy(vmess = app.unbraid.data.Vmess("203.0.113.1", 443, "00000000-0000-0000-0000-000000000000"))) }
        val twin = s.copy(routes = s.routes.map { it.copy(via = null) } + Route("d", Location("nord", 3, "FR", "France"), hue = 3), defaultRoute = "a", apps = mapOf("x" to "d"))
        assertEquals(setOf("d"), Compiler.clashes(twin, mapOf("a" to links.getValue("a"), "b" to links.getValue("b"), "d" to disguised)))
        // The checks: no entry, itself, a chain of three, a Tailscale exit, OpenVPN.
        assertNull(s.viaProblem(s.route("b")!!))
        // An imported server on IPv6 can't be reached through the entry, which carries IPv4 only.
        val v6 = Route("v6", wg = WgPeer("v6", "k", listOf("10.0.0.2/32"), "p", endpoint = "[2001:db8::1]:51820", allowedIps = listOf("0.0.0.0/0")), via = "a")
        assertEquals("v6 needs an IPv4 server address to go through another route", s.copy(routes = s.routes + v6).viaProblem(v6))
        // The limit sheet offers no shared server for a Via of that provider: a route and its entry would meet there.
        assertTrue(java.io.File("src/main/java/app/unbraid/ui/Ui.kt").readText().contains("takeIf { a -> !paired && UnbraidVpn.running"))
        // Saved in a format older builds refuse: they would send b straight to its server.
        assertEquals(4, s.stamped.version)
        assertEquals(2, s.copy(routes = s.routes.map { it.copy(via = null) }, version = app.unbraid.data.VERSION).stamped.version)
        // PIA sets its key up with the server itself; a WireGuard config naming its server by host name; a Shadowsocks plugin entry.
        val pia = s.copy(routes = listOf(s.routes[0], Route("b", Location("pia", country = "Germany"), via = "a")))
        assertTrue(pia.viaProblem(pia.route("b")!!)!!.contains("contacts its server directly"))
        // Refused before any provider is asked: PIA's set-up never reaches the exit.
        val r = app.unbraid.vpn.LinkPicker.pick(pia, emptyMap(), null, emptyMap(), mapOf("pia" to "{}", "nord" to "{}"), emptyMap()) { if (it == "pia") error("PIA was asked") else null }
        assertTrue(r.blocked.getValue("b").message!!.contains("contacts its server directly"))
        val named = s.copy(routes = listOf(s.routes[0], Route("b", wg = Nord.peer(sv("b"), key).copy(endpoint = "vpn.example.com:51820"), via = "a")))
        assertTrue(named.viaProblem(named.route("b")!!) != null)
        val ssEntry = s.copy(routes = listOf(Route("a", ss = app.unbraid.data.SsProfile("s", "198.51.100.4", 8388, "aes-256-gcm", "p", plugin = "obfs-local")), s.routes[1]))
        assertTrue(ssEntry.viaProblem(ssEntry.route("b")!!) != null)
        for (bad in listOf(s.route("b")!!.copy(via = "gone"), s.route("b")!!.copy(via = "b"))) assertTrue(s.copy(routes = listOf(s.routes[0], bad)).viaProblem(bad) != null)
        val chain = s.copy(routes = listOf(s.routes[0].copy(via = "b"), s.routes[1]))
        assertTrue(chain.viaProblem(chain.route("b")!!) != null)
        val ts = s.copy(routes = listOf(Route("a", tsExit = "100.64.0.1"), s.routes[1]))
        assertTrue(ts.viaProblem(ts.route("b")!!) != null)
        val bin = java.io.File(System.getenv("SING_BOX_CLI") ?: "${System.getenv("WORK") ?: "${System.getProperty("user.home")}/.cache/unbraid-libbox"}/sing-box-cli")
        org.junit.Assume.assumeTrue("sing-box-cli not found", bin.canExecute())
        val f = java.io.File.createTempFile("via", ".json")
        try {
            f.writeText(c.toString())
            val p = ProcessBuilder(bin.path, "check", "-c", f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue("sing-box check timed out", p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("sing-box check: $out", 0, p.exitValue())
        } finally { f.delete() }
    }

    /** A route whose Via entry isn't connected (signed out, say) is blocked, never sent straight to its server. */
    @Test fun viaWaitsForItsEntry() {
        val peer = Nord.peer(sv("b"), "5p4RkybdRU5uaDi90eu4KZPTFif0lKCg4Qp6t1c4F30=").copy(name = "Home")
        val s = Setup(routes = listOf(Route("a", Location("nord", 1, "NL", "Netherlands")), Route("b", wg = peer, via = "a")), defaultRoute = "b")
        val r = app.unbraid.vpn.LinkPicker.pick(s, emptyMap(), null, emptyMap(), emptyMap(), emptyMap(), offline = true) { null }
        assertTrue(r.links.isEmpty())
        assertTrue(r.blocked.getValue("b").message!!.contains("waits for"))
        val gone = s.copy(routes = listOf(s.routes[1].copy(via = "x")))
        assertTrue(app.unbraid.vpn.LinkPicker.pick(gone, emptyMap(), null, emptyMap(), emptyMap(), emptyMap(), offline = true) { null }.blocked.containsKey("b"))
    }

    /** Junk packets: the provider page's switch reaches each of its WireGuard tunnels, and only those. */
    @Test fun junkPacketsFollowTheProviderPage() {
        val s = Setup(routes = listOf(Route("a", Location("nord", 1, "NL", "Netherlands"))), defaultRoute = "a")
        val links = mapOf("a" to WgLink(sv("a"), Nord.peer(sv("a"), key)))
        fun ep(setup: Setup) = compile(setup, links = app.unbraid.vpn.LinkPicker.ported(setup, links, emptyMap()))["endpoints"]!!.jsonArray.single().jsonObject
        assertNull(ep(s)["junk"])
        val on = s.copy(opts = mapOf("nord" to mapOf(app.unbraid.data.JUNK_OPT to "on")))
        assertEquals(5, ep(on)["junk"]!!.jsonPrimitive.content.toInt())
    }

    /**
     * The features together: a Via exit with a quantum-safe key and junk packets, and a disguised tunnel beside it, pass
     * the patched engine's check. A disguise with a Via is refused instead.
     */
    @Test fun featuresTogetherPassTheEngine() {
        val other = "5p4RkybdRU5uaDi90eu4KZPTFif0lKCg4Qp6t1c4F30="
        val psk = "cHJlc2hhcmVkLXByZXNoYXJlZC1wcmVzaGFyZWQtMTI="
        val v = app.unbraid.data.Vmess("149.22.83.102", 80, "27de860d-5601-412d-8b71-baa048a94b98")
        val s = Setup(
            routes = listOf(Route("a", Location("nord", 1, "NL", "Netherlands")), Route("b", Location("nord", 2, "DE", "Germany"), hue = 1, via = "a"),
                Route("c", Location("nord", 3, "FR", "France"), hue = 2)),
            defaultRoute = "b", apps = mapOf("x" to "c"), opts = mapOf("nord" to mapOf(app.unbraid.data.JUNK_OPT to "on")),
        )
        val links = mapOf("a" to WgLink(sv("a"), Nord.peer(sv("a"), key)), "b" to WgLink(sv("b"), Nord.peer(sv("b"), other).copy(presharedKey = psk)))
        val picked = app.unbraid.vpn.LinkPicker.ported(s, links, emptyMap()) +
            ("c" to WgLink(sv("c"), Nord.peer(sv("c"), "aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8tMTI=").copy(vmess = v)))
        val c = compile(s, links = picked)
        val eps = c["endpoints"]!!.jsonArray.map { it.jsonObject }.associateBy { it["tag"]!!.jsonPrimitive.content }
        val b = eps.getValue("r-b")
        assertEquals(listOf("r-a", "5", psk), listOf(b["detour"], b["junk"], b["peers"]!!.jsonArray[0].jsonObject["pre_shared_key"]).map { it!!.jsonPrimitive.content })
        assertEquals("v2-r-c", eps.getValue("r-c")["detour"]!!.jsonPrimitive.content)
        try { compile(s, links = picked + ("b" to (picked.getValue("b") as WgLink).let { it.copy(peer = it.peer.copy(vmess = v)) })); fail() } catch (e: IllegalArgumentException) { }
        val bin = java.io.File(System.getenv("SING_BOX_CLI") ?: "${System.getenv("WORK") ?: "${System.getProperty("user.home")}/.cache/unbraid-libbox"}/sing-box-cli")
        org.junit.Assume.assumeTrue("sing-box-cli not found", bin.canExecute())
        val f = java.io.File.createTempFile("together", ".json")
        try {
            f.writeText(c.toString())
            val p = ProcessBuilder(bin.path, "check", "-c", f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue("sing-box check timed out", p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("sing-box check: $out", 0, p.exitValue())
        } finally { f.delete() }
    }

    @Test fun differentKeyOrServerIsNotShared() {
        val other = "5p4RkybdRU5uaDi90eu4KZPTFif0lKCg4Qp6t1c4F30="
        assertEquals(listOf("r-a", "r-b"), two(WgLink(sv("a"), Nord.peer(sv("a"), other))).first.endpointTags())
        assertEquals(listOf("r-a", "r-b"), two(WgLink(sv("b"), Nord.peer(sv("b"), key))).first.endpointTags())
    }

    /** Sharing follows what the engine's WireGuard device is built from, never the provider label. */
    @Test fun wireGuardSharingFollowsTheRealSettings() {
        val peer = Nord.peer(sv("a"), key)
        val imported = Route("b", wg = peer.copy(name = "mine"))
        fun tags(second: WgPeer, r: Route = imported) = compile(Setup(routes = listOf(Route("a", Location("nord", 1, "NL", "Netherlands")), r), defaultRoute = "a", apps = mapOf("com.b" to "b")),
            links = mapOf("a" to WgLink(sv("a"), peer), "b" to WgLink(sv("a"), second))).endpointTags()
        // A provider route and an import of the same config share one tunnel.
        assertEquals(listOf("r-a"), tags(peer))
        // Another server or another client key is another tunnel.
        assertEquals(listOf("r-a", "r-b"), tags(peer.copy(endpoint = "10.9.9.9:51820")))
        assertEquals(listOf("r-a", "r-b"), tags(peer.copy(privateKey = "5p4RkybdRU5uaDi90eu4KZPTFif0lKCg4Qp6t1c4F30=")))
        // The same key on the same server with another preshared key, addresses (also their order) or MTU clashes;
        // the compiler refuses it outright, and LinkPicker blocks such a route before it gets there.
        val two = peer.copy(address = listOf("10.5.0.2/32", "10.6.0.2/32"))
        for (other in listOf(peer.copy(presharedKey = key), peer.copy(address = listOf("10.5.0.9/32")), peer.copy(mtu = 1200))) {
            try { tags(other); fail("ran two tunnels for one identity: $other") } catch (e: IllegalArgumentException) { assertFalse(e.message!!.contains(key)) }
        }
        val s2 = Setup(routes = listOf(Route("a", wg = two), Route("b", wg = two.copy(address = two.address.reversed()), hue = 1)), defaultRoute = "a", apps = mapOf("com.b" to "b"))
        assertEquals(setOf("b"), Compiler.clashes(s2, mapOf("a" to WgLink(sv("a"), two), "b" to WgLink(sv("a"), two.copy(address = two.address.reversed())))))
        // Nor as both a route and the home tunnel.
        val s = Setup(routes = listOf(Route("a", Location("nord", 1, "NL", "Netherlands"))), defaultRoute = "a", home = Home(wg = peer))
        assertEquals(setOf("a"), Compiler.clashes(s, mapOf("a" to WgLink(sv("a"), peer))))
    }

    @Test fun tunnelMtuComesFromThePortedPeerAtMost1420() {
        val peer = Nord.peer(sv("a"), key)
        fun mtu(m: Int) = compile(Setup(routes = listOf(Route("a", Location("nord", 1, "NL", "Netherlands"))), defaultRoute = "a"),
            links = mapOf("a" to WgLink(sv("a"), peer.copy(mtu = m))))["endpoints"]!!.jsonArray[0].jsonObject.str("mtu")
        assertEquals("1400", mtu(1400))
        assertEquals("1420", mtu(1500))
        val home = Setup(routes = listOf(Route("a", Location("nord", 1, "NL", "Netherlands"))), defaultRoute = "a", home = Home(wg = peer.copy(endpoint = "10.1.1.1:51820", mtu = 1420)))
        assertEquals("1280", compile(home, links = mapOf("a" to WgLink(sv("a"), peer)))["endpoints"]!!.jsonArray.map { it.jsonObject }.first { it.str("tag") == "home-wg" }.str("mtu"))
    }

    private fun ovpnRoute(id: String, user: String) = Route(id, ovpn = Manual.parseOvpn(javaClass.getResource("/ovpn/expressvpn.ovpn")!!.readText(), id, user, "pw"))

    @Test fun sameOpenVpnRemoteAndUserShareOneEndpoint() {
        val a = ovpnRoute("a", "u"); val b = ovpnRoute("b", "u"); val c = ovpnRoute("c", "other")
        val s = Setup(routes = listOf(a, b, c), defaultRoute = "a", apps = mapOf("com.b" to "b", "com.c" to "c"))
        val out = compile(s, links = s.routes.associate { it.id to OvpnLink(sv(it.id), it.ovpn!!) })
        assertEquals(listOf("r-a", "r-c"), out.endpointTags())
        // One DNS server per tunnel, which the engine requires; every reference names one that exists.
        val servers = out["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("r-a", "r-c"), servers.filter { it["type"]!!.jsonPrimitive.content == "openvpn" }.map { it["endpoint"]!!.jsonPrimitive.content })
        val tags = servers.map { it["tag"]!!.jsonPrimitive.content }.toSet()
        val used = (out["dns"]!!.jsonObject["rules"]!!.jsonArray + out["route"]!!.jsonObject["rules"]!!.jsonArray).mapNotNull { it.jsonObject["server"]?.jsonPrimitive?.content } + out["dns"]!!.jsonObject["final"]!!.jsonPrimitive.content
        assertTrue(used.toString(), used.all { it in tags })
        assertTrue(used.contains("dns-ovpn-r-a"))
    }

    @Test fun shadowsocksRoutesWithOtherPluginOptionsAreNotShared() {
        val p = SsProfile("a", "h.example", 8388, "aes-256-gcm", "pw", "v2ray-plugin", "tls;host=a.example")
        fun key(q: SsProfile) = Compiler.linkKey(Route("a", ss = q), SsLink(sv("a"), q))
        assertEquals(key(p), key(p.copy(name = "b")))
        assertNotEquals(key(p), key(p.copy(pluginOpts = "host=a.example")))
        assertNotEquals(key(p), key(p.copy(plugin = null, pluginOpts = null)))
    }

    private val ssPlain = SsProfile("s", "h.example", 8388, "aes-256-gcm", "pw")
    private fun ssSetup(p: SsProfile, prefs: Prefs = Prefs(lan = false)) = Setup(routes = listOf(Route("s", ss = p)), defaultRoute = "s", home = Home(), prefs = prefs)
    private fun ssCompile(p: SsProfile, prefs: Prefs = Prefs(lan = false)) = compile(ssSetup(p, prefs), links = mapOf("s" to SsLink(sv("s"), p)))

    /** A plugin carries TCP only, so lookups go over TCP through the route and no UDP is handed to the server past the plugin. */
    @Test fun shadowsocksRouteWithAPluginResolvesOverTcp() = listOf("obfs-local" to "obfs=http;obfs-host=a.example", "v2ray-plugin" to "tls;host=a.example").forEach { (plugin, opts) ->
        val p = ssPlain.copy(plugin = plugin, pluginOpts = opts)
        val c = ssCompile(p)
        c.dnsServers()["dns-r-s"]!!.let { assertEquals(plugin, "tcp", it.str("type")); assertEquals(plugin, "r-s", it.str("detour")); assertEquals("9.9.9.9", it.str("server")) }
        val out = c["outbounds"]!!.jsonArray.map { it.jsonObject }.single { it.str("tag") == "r-s" }
        assertEquals(plugin, "tcp", out.str("network"))
        assertEquals(plugin, plugin, out.str("plugin"))
        // A filter by address on VPN routes is asked over TCP too; one by name is looked up over TCP first.
        val plainFilter = ssCompile(p, Prefs(lan = false, filter = Filter(Filters.OWN, "1.1.1.1", routes = true))).dnsServers()
        assertEquals("tcp", plainFilter["dns-r-s"]!!.str("type")); assertEquals("r-s", plainFilter["dns-r-s"]!!.str("detour"))
        val named = ssCompile(p, Prefs(lan = false, filter = Filter(Filters.OWN, "tls://dns.example.org", routes = true))).dnsServers()
        assertEquals("tls", named["dns-r-s"]!!.str("type"))
        assertEquals("tcp", named["boot-r-s"]!!.str("type")); assertEquals("r-s", named["boot-r-s"]!!.str("detour"))
        // The same plugin route as an app's exit, with a plain route beside it: only the plugin route changes.
        val mixed = Setup(routes = listOf(Route("s", ss = p), Route("t", ss = ssPlain.copy(server = "t.example"))), defaultRoute = "t", apps = mapOf("com.s" to "s"), home = Home(), prefs = Prefs(lan = false))
        val m = compile(mixed, links = mapOf("s" to SsLink(sv("s"), p), "t" to SsLink(sv("t"), ssPlain.copy(server = "t.example"))))
        assertEquals("tcp", m.dnsServers()["dns-r-s"]!!.str("type")); assertEquals("udp", m.dnsServers()["dns-r-t"]!!.str("type"))
        assertNull(m["outbounds"]!!.jsonArray.map { it.jsonObject }.single { it.str("tag") == "r-t" }["network"])
        // Health checks read which tunnels carry TCP only, and send no UDP through those.
        assertEquals(setOf("r-s"), Compiler.tcpOnly(m.toString()))
        assertEquals(setOf("r-s"), Compiler.tcpOnly(c.toString()))
    }

    /** A Shadowsocks route without a plugin compiles exactly as it always has. */
    @Test fun shadowsocksRouteWithoutAPluginIsUnchanged() {
        assertEquals(
            """{"log":{"level":"warn","timestamp":false},"dns":{"servers":[{"type":"fakeip","tag":"fake","inet4_range":"198.18.0.0/18"},{"type":"local","tag":"local"},{"type":"udp","tag":"dns-r-s","server":"9.9.9.9","detour":"r-s"}],"rules":[{"query_type":["A","AAAA"],"server":"fake","rewrite_ttl":1}],"final":"dns-r-s","strategy":"ipv4_only","optimistic":{"enabled":true,"timeout":"1h"},"timeout":"5s","cache_capacity":4096},"inbounds":[{"type":"tun","tag":"tun","address":["172.19.0.1/30"],"mtu":9000,"auto_route":true,"strict_route":true,"stack":"gvisor","udp_timeout":"30m","exclude_package":["app.unbraid"]}],"endpoints":[],"outbounds":[{"type":"direct","tag":"direct"},{"type":"shadowsocks","tag":"r-s","server":"h.example","server_port":8388,"method":"aes-256-gcm","password":"pw"}],"route":{"rules":[{"inbound":["tun"],"protocol":"dns","action":"hijack-dns"},{"inbound":["tun"],"port":53,"network":"udp","action":"hijack-dns"},{"ip_cidr":["198.18.64.0/18","198.18.128.0/18","198.18.192.0/18","198.19.0.0/18","198.19.64.0/18","198.19.128.0/18","198.19.192.0/18"],"action":"reject","no_drop":true},{"action":"resolve","server":"dns-r-s"}],"final":"r-s","auto_detect_interface":true,"default_domain_resolver":"local"},"experimental":{"cache_file":{"enabled":true,"path":"/tmp/cache.db","store_fakeip":true}}}""",
            ssCompile(ssPlain).toString(),
        )
        val plainFilter = ssCompile(ssPlain, Prefs(lan = false, filter = Filter(Filters.OWN, "1.1.1.1", routes = true))).dnsServers()
        assertEquals("udp", plainFilter["dns-r-s"]!!.str("type"))
        assertEquals(emptySet<String>(), Compiler.tcpOnly(ssCompile(ssPlain).toString()))
    }

    // ---- WireGuard keys ----

    private fun keyError(f: (WgLink) -> WgLink): String {
        val s = Setup(routes = listOf(Route("a", Location("nord", 1, "NL", "Netherlands"))), defaultRoute = "a")
        try { compile(s, links = mapOf("a" to f(WgLink(sv("a"), Nord.peer(sv("a"), key))))) } catch (e: IllegalArgumentException) { return e.message!! }
        fail("expected a rejection"); error("unreachable")
    }

    @Test fun malformedWireGuardKeysAreRejectedWithoutQuotingThem() {
        val secret = "this-is-not-a-key-but-looks-like-one-0123456789"
        val msg = keyError { it.copy(peer = it.peer.copy(privateKey = secret)) }
        assertEquals("Invalid WireGuard key for Netherlands", msg)
        assertFalse(msg.contains(secret))
        keyError { it.copy(peer = it.peer.copy(publicKey = "AAAA")) }
        keyError { it.copy(peer = it.peer.copy(presharedKey = "c2hvcnQ=")) }
        keyError { it.copy(peer = it.peer.copy(privateKey = "")) }
    }

    // ---- OpenVPN DNS ----

    private fun ovpnSetup(prefs: Prefs = Prefs()) = Setup(routes = listOf(ovpnRoute("x", "u")), defaultRoute = "x", prefs = prefs)
    private fun JsonObject.dnsServer(tag: String) = this["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }.first { it["tag"]!!.jsonPrimitive.content == tag }

    @Test fun openVpnRoutesUseThePushedDnsButIgnorePushedRoutes() {
        val s = ovpnSetup()
        val c = compile(s, links = mapOf("x" to OvpnLink(sv("x"), s.routes[0].ovpn!!)))
        val dns = c.dnsServer("dns-ovpn-r-x")
        assertEquals("openvpn", dns["type"]!!.jsonPrimitive.content)
        // A server that pushes no DNS is answered through the same tunnel, never not at all.
        assertEquals("9.9.9.9", dns["fallback_server"]!!.jsonPrimitive.content)
        assertEquals("r-x", dns["endpoint"]!!.jsonPrimitive.content)
        val ep = c["endpoints"]!!.jsonArray[0].jsonObject
        assertFalse(ep.containsKey("route_no_pull"))
        val ignored = ep["pull_filters"]!!.jsonArray.map { it.jsonObject }.filter { it["action"]!!.jsonPrimitive.content == "ignore" }.map { it["text"]!!.jsonPrimitive.content }
        assertTrue(ignored.containsAll(listOf("route ", "route-ipv6 ", "redirect-gateway")))
        // First match wins in the engine, so the app's ignores must come before any filter from the profile.
        val first = ep["pull_filters"]!!.jsonArray.first().jsonObject
        assertEquals("ignore" to "route ", first["action"]!!.jsonPrimitive.content to first["text"]!!.jsonPrimitive.content)
        assertFalse(ignored.any { it.startsWith("dhcp-option") || it.startsWith("dns") })
    }

    /** An own filter server by name, on an imported OpenVPN route, is looked up with the DNS that tunnel pushes. */
    @Test fun ownFilterByNameOnOpenVpnUsesPushedDns() {
        val s = ovpnSetup(Prefs(filter = Filter(Filters.OWN, "tls://dns.internal.example", routes = true)))
        val c = compile(s, links = mapOf("x" to OvpnLink(sv("x"), s.routes[0].ovpn!!)))
        assertEquals("openvpn", c.dnsServer("boot-r-x")["type"]!!.jsonPrimitive.content)
        // Two routes on that one tunnel share one lookup server: the engine allows one OpenVPN DNS server per endpoint.
        val two = s.copy(routes = s.routes + s.routes[0].copy(id = "y"), apps = mapOf("com.y" to "y"))
        val c2 = compile(two, links = mapOf("x" to OvpnLink(sv("x"), s.routes[0].ovpn!!), "y" to OvpnLink(sv("x"), s.routes[0].ovpn!!)))
        assertEquals(1, c2["dns"]!!.jsonObject["servers"]!!.jsonArray.count { it.jsonObject["type"]!!.jsonPrimitive.content == "openvpn" })
        assertEquals("tls", c.dnsServer("dns-r-x")["type"]!!.jsonPrimitive.content)
        assertFalse(c["endpoints"]!!.jsonArray[0].jsonObject.containsKey("route_no_pull"))
    }

    @Test fun customDnsKeepsOpenVpnOnItsOwnResolverAndNoPull() {
        val s = ovpnSetup(Prefs(filter = Filter(Filters.OWN, "1.1.1.1", routes = true)))
        val c = compile(s, links = mapOf("x" to OvpnLink(sv("x"), s.routes[0].ovpn!!)))
        assertEquals("udp", c.dnsServer("dns-r-x")["type"]!!.jsonPrimitive.content)
        assertEquals("true", c["endpoints"]!!.jsonArray[0].jsonObject["route_no_pull"]!!.jsonPrimitive.content)
    }

    // ---- tailnet DNS ----

    @Test fun unattributedAppLookupsCannotReachTailnetDnsWhileAnAppIsBlocked() {
        val block = app.unbraid.data.BLOCK
        val headscale = example.home.copy(tsControlUrl = "https://control.example.org", tsSuffixAllowed = "corp.example")
        for ((home, suffix) in listOf(example.home to "ts.net", headscale to "corp.example")) {
            for (blockLan in listOf(false, true)) {
                val s = example.copy(home = home, prefs = example.prefs.copy(blockLan = blockLan), apps = example.apps + ("com.barred" to block))
                val raw = compile(s, tsSuffix = suffix)
                val dns = raw["dns"]!!.jsonObject
                val preview = app.unbraid.data.RestoreTraffic.Config(raw.toString(), s)
                for (name in listOf(suffix, "printer.$suffix")) for (type in listOf("A", "AAAA", "HTTPS", "SVCB", "TXT", "PTR")) {
                    assertEquals("$name $type", "reject", RoutingProbe.lookup(dns, "", name, type))
                    assertEquals(app.unbraid.data.RestoreTraffic.HELD, app.unbraid.data.RestoreTraffic.lookup(preview, emptySet(), name, type)?.out?.exit)
                }
            }
        }
    }

    @Test fun knownAppsAndEngineLookupsKeepTheirTailnetDnsPolicy() {
        val s = example.copy(apps = example.apps + ("com.barred" to app.unbraid.data.BLOCK),
            sites = mapOf("hidden.ts.net" to app.unbraid.data.BLOCK))
        for (blockLan in listOf(false, true)) {
            val dns = compile(s.copy(prefs = s.prefs.copy(blockLan = blockLan)))["dns"]!!.jsonObject
            for (type in listOf("A", "AAAA", "TXT", "HTTPS", "SVCB", "PTR")) {
                assertEquals("dns-tailnet", RoutingProbe.lookup(dns, "com.a", "peer.ts.net", type))
                for (inbound in listOf("", Compiler.TS_TAG))
                    assertEquals("dns-tailnet", RoutingProbe.lookup(dns, "", "peer.ts.net", type, inbound))
                assertEquals("reject", RoutingProbe.lookup(dns, "com.barred", "peer.ts.net", type))
                assertEquals("reject", RoutingProbe.lookup(dns, "com.a", "hidden.ts.net", type))
                assertEquals("reject", RoutingProbe.lookup(dns, "", "hidden.ts.net", type, inbound = ""))
                assertEquals(if (blockLan) "reject" else "local", RoutingProbe.lookup(dns, "", "printer.lan", type))
            }
            assertEquals("fake", RoutingProbe.lookup(dns, "", "ts.net.example", "A"))
        }
        val unapproved = s.copy(home = example.home.copy(tsControlUrl = "https://control.example.org"))
        assertEquals("fake", RoutingProbe.lookup(compile(unapproved, tsSuffix = "corp.example")["dns"]!!.jsonObject, "", "host.corp.example", "A"))
        val exitOnly = s.copy(home = Home(), defaultRoute = "ts", routes = s.routes + Route("ts", tsExit = "nas"))
        assertEquals("fake", RoutingProbe.lookup(compile(exitOnly)["dns"]!!.jsonObject, "", "peer.ts.net", "A"))
        val dns = compile(example)["dns"]!!.jsonObject
        assertEquals("dns-tailnet", RoutingProbe.lookup(dns, "", "peer.ts.net", "TXT"))
    }

    @Test fun tailnetSuffixIsRoutedToTailscaleDns() {
        fun suffixes(suffix: String?, home: Home = example.home): List<String> {
            val s = example.copy(home = home)
            val i = Compiler.Input(s, links(s), "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", suffix)
            val rules = Json.parseToJsonElement(Compiler.compile(i)).jsonObject["dns"]!!.jsonObject["rules"]!!.jsonArray
            return rules.map { it.jsonObject }.firstOrNull { it["server"]?.jsonPrimitive?.content == "dns-tailnet" }?.get("domain_suffix")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        }
        // Tailscale's own server: its ts.net names, never another domain it reports.
        assertEquals(listOf("ts.net"), suffixes(null))
        assertEquals(listOf("ts.net", "tail1234.ts.net"), suffixes("Tail1234.ts.net."))
        for (bad in listOf("corp.example", "com", "co.uk", "ts.net", "bad\" domain", "-x.ts.net", "a..ts.net", "x".repeat(64) + ".ts.net")) assertEquals(bad, listOf("ts.net"), suffixes(bad))
        // Headscale: no ts.net, and its suffix only once allowed (a switch in Settings), exactly that one.
        val hs = example.home.copy(tsControlUrl = "https://hs.example.org")
        assertEquals(emptyList<String>(), suffixes("corp.example", hs))
        assertEquals(listOf("corp.example"), suffixes("corp.example", hs.copy(tsSuffixAllowed = "corp.example")))
        assertEquals(emptyList<String>(), suffixes("com", hs.copy(tsSuffixAllowed = "corp.example")))
        assertEquals(listOf("ts.net", "tail9.ts.net"), suffixes("tail9.ts.net", example.home.copy(tsControlUrl = "https://controlplane.tailscale.com")))
    }

    @Test fun tailscaleExitNodeTakesAnIpAndKeepsItsName() {
        val s = example.copy(home = Home(), routes = example.routes + Route("ts", tsExit = "100.101.102.103", tsName = "nas", hue = 4), apps = example.apps + ("com.n" to "ts"))
        val ts = compile(s)["endpoints"]!!.jsonArray.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "tailscale" }
        assertEquals("100.101.102.103", ts["exit_node"]!!.jsonPrimitive.content)
        assertEquals("nas", s.route("ts")!!.name)
    }

    /** A config's own PersistentKeepalive is kept (0 = none); without one, and for provider servers, 25 s. */
    @Test fun keepaliveFollowsTheConfig() {
        fun keepalive(text: String) = compile(example.copy(home = Home(wg = WgConf.parse(text, "Home")))).let { c ->
            c["endpoints"]!!.jsonArray.map { it.jsonObject }.first { it["tag"]!!.jsonPrimitive.content == "home-wg" }["peers"]!!.jsonArray[0].jsonObject["persistent_keepalive_interval"]!!.jsonPrimitive.content
        }
        assertEquals("25", keepalive(FRITZ))
        assertEquals("15", keepalive(FRITZ.replace("PersistentKeepalive = 25", "PersistentKeepalive = 15")))
        assertEquals("0", keepalive(FRITZ.replace("PersistentKeepalive = 25", "PersistentKeepalive = off")))
        assertEquals("25", keepalive(FRITZ.replace("PersistentKeepalive = 25", "")))
        assertEquals(null, WgConf.parse(FRITZ.replace("PersistentKeepalive = 25", ""), "x").keepalive)
        assertThrows(IllegalArgumentException::class.java) { WgConf.parse(FRITZ.replace("PersistentKeepalive = 25", "PersistentKeepalive = soon"), "x") }
        val provider = compile(example)["endpoints"]!!.jsonArray.map { it.jsonObject }.first { it["type"]!!.jsonPrimitive.content == "wireguard" }
        assertEquals("25", provider["peers"]!!.jsonArray[0].jsonObject["persistent_keepalive_interval"]!!.jsonPrimitive.content)
    }

    companion object {
        const val FRITZ = """
[Interface]
PrivateKey = aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8tMTI=
Address = 192.168.178.201/24, fd00::201/64
DNS = 192.168.178.1, fd00::1, fritz.box

[Peer]
PublicKey = 5p4RkybdRU5uaDi90eu4KZPTFif0lKCg4Qp6t1c4F30=
PresharedKey = 5p4RkybdRU5uaDi90eu4KZPTFif0lKCg4Qp6t1c4F30=
AllowedIPs = 192.168.178.0/24, 0.0.0.0/0, ::/0
Endpoint = abc.myfritz.net:51820
PersistentKeepalive = 25
"""
    }
}
