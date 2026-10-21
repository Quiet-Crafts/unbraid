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
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.data.SsLink
import app.unbraid.data.SsProfile
import app.unbraid.data.WgConf
import app.unbraid.vpn.Compiler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
        return Json.parseToJsonElement(out).jsonObject
    }

    private fun JsonObject.rules() = this["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.endpointTags() = this["endpoints"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
    private fun JsonObject.excluded() = this["inbounds"]!!.jsonArray[0].jsonObject["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content }

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
        assertEquals(Compiler.NORD_DNS, dnsOf(compile(example.copy(prefs = Prefs(threatProtection = false)))))
        assertEquals("9.9.9.9", dnsOf(compile(example.copy(prefs = Prefs(customDns = listOf("9.9.9.9"))))))
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
        val mullvad = example.copy(routes = example.routes.map { if (it.id == r.id) it.copy(loc = it.loc.copy(provider = "mullvad"), filter = true) else it })
        assertEquals("100.64.0.7", dns(mullvad, r.id))
        assertEquals("9.9.9.9", dns(mullvad.copy(prefs = mullvad.prefs.copy(customDns = listOf("9.9.9.9"))), r.id))
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
        assertEquals("openvpn", dns.first { it["tag"]!!.jsonPrimitive.content == "dns-r-ex" }["type"]!!.jsonPrimitive.content)
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

    private fun ovpnRoute(id: String, user: String) = Route(id, ovpn = Manual.parseOvpn(javaClass.getResource("/ovpn/expressvpn.ovpn")!!.readText(), id, user, "pw"))

    @Test fun sameOpenVpnRemoteAndUserShareOneEndpoint() {
        val a = ovpnRoute("a", "u"); val b = ovpnRoute("b", "u"); val c = ovpnRoute("c", "other")
        val s = Setup(routes = listOf(a, b, c), defaultRoute = "a", apps = mapOf("com.b" to "b", "com.c" to "c"))
        val out = compile(s, links = s.routes.associate { it.id to OvpnLink(sv(it.id), it.ovpn!!) })
        assertEquals(listOf("r-a", "r-c"), out.endpointTags())
    }

    @Test fun shadowsocksRoutesWithOtherPluginOptionsAreNotShared() {
        val p = SsProfile("a", "h.example", 8388, "aes-256-gcm", "pw", "v2ray-plugin", "tls;host=a.example")
        fun key(q: SsProfile) = Compiler.linkKey(Route("a", ss = q), SsLink(sv("a"), q))
        assertEquals(key(p), key(p.copy(name = "b")))
        assertNotEquals(key(p), key(p.copy(pluginOpts = "host=a.example")))
        assertNotEquals(key(p), key(p.copy(plugin = null, pluginOpts = null)))
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
        val dns = c.dnsServer("dns-r-x")
        assertEquals("openvpn", dns["type"]!!.jsonPrimitive.content)
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

    @Test fun customDnsKeepsOpenVpnOnItsOwnResolverAndNoPull() {
        val s = ovpnSetup(Prefs(customDns = listOf("1.1.1.1")))
        val c = compile(s, links = mapOf("x" to OvpnLink(sv("x"), s.routes[0].ovpn!!)))
        assertEquals("udp", c.dnsServer("dns-r-x")["type"]!!.jsonPrimitive.content)
        assertEquals("true", c["endpoints"]!!.jsonArray[0].jsonObject["route_no_pull"]!!.jsonPrimitive.content)
    }

    // ---- tailnet DNS ----

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
