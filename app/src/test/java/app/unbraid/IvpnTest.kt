package app.unbraid

import app.unbraid.data.AuthException
import app.unbraid.data.Challenge
import app.unbraid.data.Ivpn
import app.unbraid.data.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.IOException
import java.util.Base64

class IvpnTest {
    private val list = Ivpn.parse(javaClass.getResource("/ivpn_servers.json")!!.readText())
    private val acct = Ivpn.Account("i-ABCD-EFGH-IJKL", "session", "cHJpdmF0ZS1wcml2YXRlLXByaXZhdGUtcHJpdmF0ZTE=", "172.16.34.56")

    /** IVPN's multi-hop for a Via between two IVPN routes: one tunnel to the exit's forwarding port at the entry's server. */
    @Test fun nativeMultiHop() {
        val nl = Location("ivpn", countryCode = "NL"); val se = Location("ivpn", countryCode = "SE")
        val setup = app.unbraid.data.Setup(routes = listOf(app.unbraid.data.Route("nl", nl), app.unbraid.data.Route("se", se, via = "nl", viaNative = true)),
            defaultRoute = "se", apps = mapOf("x" to "nl"))
        val keep = mapOf("nl" to Ivpn.link(list, nl, acct), "se" to Ivpn.link(list, se, acct))
        val exit = list.wireguard.flatMap { it.hosts }.single { it.hostname == keep.getValue("se").server.host }
        assertTrue(exit.multihopPort > 0)
        val blob = app.unbraid.data.NordApi.json.encodeToString(Ivpn.Account.serializer(), acct)
        fun pick(s: app.unbraid.data.Setup) = app.unbraid.vpn.LinkPicker.pick(s, keep, s, mapOf("ivpn" to blob), mapOf("ivpn" to blob), emptyMap(), offline = true) { Ivpn }
        val r = pick(setup)
        val l = r.links.getValue("se") as app.unbraid.data.WgLink
        assertEquals("${keep.getValue("nl").server.ip}:${exit.multihopPort}" to keep.getValue("nl").server.ip, l.peer.endpoint to l.hopEntry)
        assertEquals(exit.publicKey, l.peer.publicKey)
        // The engine gets one ordinary tunnel (no detour), with the page's MTU, not moved by the port setting.
        val ported = app.unbraid.vpn.LinkPicker.ported(setup.copy(opts = mapOf("ivpn" to mapOf(app.unbraid.data.PORT_OPT to "443"))), r.links, emptyMap())["se"] as app.unbraid.data.WgLink
        assertEquals(l.peer.endpoint, ported.peer.endpoint)
        assertEquals(1280, ported.peer.mtu)
        val c = kotlinx.serialization.json.Json.parseToJsonElement(app.unbraid.vpn.Compiler.compile(app.unbraid.vpn.Compiler.Input(setup, app.unbraid.vpn.LinkPicker.ported(setup, r.links, emptyMap()), "app.unbraid", false, "/tmp/ts", "/tmp/c.db")))
        val ep = c.jsonObject["endpoints"]!!.jsonArray.map { it.jsonObject }.single { it["tag"]!!.jsonPrimitive.content == "r-se" }
        assertNull(ep["detour"])
        // The entry route isn't needed for it: unused by apps, it has no tunnel of its own.
        assertEquals(listOf("se"), setup.copy(apps = emptyMap()).activeRoutes.map { it.id })
        // Same country, another provider's entry, or blocking with a list that has no multi-hop resolver: blocked.
        val same = setup.copy(routes = listOf(setup.routes[0], setup.routes[1].copy(loc = nl)))
        assertTrue(same.viaProblem(same.route("se")!!) != null)
        val blocking = setup.copy(opts = mapOf("ivpn" to mapOf(app.unbraid.data.PROTECT to "on", "list" to "10.0.254.6|10.0.254.7")))
        assertTrue("se" in pick(blocking).blocked)
        val oisd = setup.copy(opts = mapOf("ivpn" to mapOf(app.unbraid.data.PROTECT to "on")))
        val dns = kotlinx.serialization.json.Json.parseToJsonElement(app.unbraid.vpn.Compiler.compile(app.unbraid.vpn.Compiler.Input(oisd, app.unbraid.vpn.LinkPicker.ported(oisd, pick(oisd).links, emptyMap()), "app.unbraid", false, "/tmp/ts", "/tmp/c.db")))
            .jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }.single { it["tag"]!!.jsonPrimitive.content == "dns-r-se" }
        assertEquals("10.0.254.102", dns["server"]!!.jsonPrimitive.content)
        // The same exit server straight from another route too: one key there by two paths, a clash.
        val twin = setup.copy(routes = setup.routes + app.unbraid.data.Route("se2", se), apps = mapOf("x" to "nl", "y" to "se2"))
        val t = app.unbraid.vpn.LinkPicker.pick(twin, keep + ("se2" to keep.getValue("se")), twin, mapOf("ivpn" to blob), mapOf("ivpn" to blob), emptyMap(), offline = true) { Ivpn }
        assertTrue("se" in t.blocked && "se2" in t.links)
        // Never kept: the next pick builds it again from the current Via, so a changed or removed one never lingers.
        val again = app.unbraid.vpn.LinkPicker.pick(setup.copy(routes = listOf(setup.routes[0], setup.routes[1].copy(via = null, viaNative = false))), keep + ("se" to l), setup,
            mapOf("ivpn" to blob), mapOf("ivpn" to blob), emptyMap(), offline = true) { Ivpn }
        assertTrue("se" in again.blocked || (again.links["se"] as? app.unbraid.data.WgLink)?.hopEntry == null)
        // Offline with the entry not running: waits (blocked), never straight.
        val waiting = pick(setup.copy(apps = emptyMap()))
        assertTrue("se" in waiting.blocked)
    }

    @Test fun countriesAreGroupedAndKeyed() {
        val cs = Ivpn.countries(list)
        assertEquals(listOf("Argentina", "Netherlands", "Sweden", "United States"), cs.map { it.name })
        assertEquals(listOf("AR", "NL", "SE", "US"), cs.map { it.code })
        assertEquals(cs.size, cs.map { it.id }.toSet().size)
        val us = cs.first { it.code == "US" }
        assertTrue(us.cities.size > 5)
        assertTrue(us.cities.all { it.key!!.endsWith(".wg.ivpn.net") })
        assertEquals(us.cities.map { it.name }, us.cities.map { it.name }.sorted())
        assertEquals(us.cities.size, us.cities.map { it.key }.toSet().size)
        assertEquals(list.wireguard.filter { it.countryCode == "US" }.sumOf { it.hosts.size }, us.serverCount)
    }

    @Test fun hostKeysAreWireGuardKeys() {
        val hosts = list.wireguard.flatMap { it.hosts }
        assertTrue(hosts.isNotEmpty())
        assertTrue(hosts.all { it.publicKey.length == 44 && Base64.getDecoder().decode(it.publicKey).size == 32 })
    }

    @Test fun countryLinkPicksTheLeastLoadedHost() {
        val l = Ivpn.link(list, Location("ivpn", countryCode = "NL"), acct)
        val best = list.wireguard.filter { it.countryCode == "NL" }.flatMap { it.hosts }.minBy { it.load }
        assertEquals(best.hostname, l.server.host)
        val p = l.peer
        assertEquals(best.publicKey, p.publicKey)
        assertEquals("${best.host}:2049", p.endpoint)
        assertEquals(listOf("172.16.34.56/32"), p.address)
        assertEquals(listOf("172.16.0.1"), p.dns)
        assertEquals(listOf("0.0.0.0/0"), p.allowedIps)
        assertEquals(acct.privateKey, p.privateKey)
        assertEquals("Amsterdam", l.server.city)
    }

    /** Automatic tries 2049, then the ports restrictive networks let through, all from IVPN's own list; offline only the default. */
    @Test fun linksCarryTheFallbackPortsIvpnLists() {
        val l = Ivpn.link(list, Location("ivpn", countryCode = "NL"), acct)
        assertEquals(listOf(2049, 443, 53, 80, 123, 2050, 1194, 500, 4500), l.ports)
        assertTrue(list.ports.containsAll(l.ports))
        assertEquals(emptyList<Int>(), Ivpn.link(null, Location("ivpn", pinned = l.server), acct).ports)
        assertEquals(listOf(2049, 53), Ivpn.link(list.copy(config = Ivpn.Config(Ivpn.Ports(listOf(Ivpn.Port("UDP", 53), Ivpn.Port("TCP", 443), Ivpn.Port("UDP", 2049))))), Location("ivpn", countryCode = "NL"), acct).ports)
    }

    /** Every host of a country can be picked by hand (the US has more than twenty). */
    @Test fun everyServerOfACountryIsListed() {
        val us = list.wireguard.filter { it.countryCode == "US" }.sumOf { it.hosts.size }
        assertEquals(us, with(Ivpn) { list.match(Location("ivpn", countryCode = "US")) }.size)
    }

    /** AntiTracker's lists come from IVPN's list, saved as both resolvers; Hardcore picks the second; nonsense falls back. */
    @Test fun antiTrackerListsAndHardcore() {
        val list = Ivpn.options(this.list).first { it.key == "list" }.also { assertEquals(10, it.choices.size) }
        val pro = list.choices.first { it.second == "Hagezi Pro" }.first
        assertEquals("10.0.254.10|10.0.254.11", pro)
        fun dns(vararg o: Pair<String, String>) = Ivpn.blockingDns { k -> mapOf(*o)[k] ?: Ivpn.defaults[k] }
        assertEquals("10.0.254.2", dns())
        assertEquals("10.0.254.3", dns("hardcore" to "on"))
        assertEquals("10.0.254.10", dns("list" to pro))
        assertEquals("10.0.254.11", dns("list" to pro, "hardcore" to "on"))
        assertEquals("10.0.254.2", dns("list" to "1.2.3.4/0|x", "hardcore" to "on"))
    }

    @Test fun cityLinkStaysInThatGateway() {
        val l = Ivpn.link(list, Location("ivpn", countryCode = "US", region = "us-ny.wg.ivpn.net"), acct)
        assertEquals("New York, NY", l.server.city)
        assertTrue(l.server.host.startsWith("us-ny"))
    }

    /** Only IVPN's own "session not found" (or a refused login) counts as removed; anything unclear doesn't. */
    @Test fun aSessionLoggedOutElsewhereIsSeen() {
        assertEquals(true, Ivpn.sessionGone(200 to "{\"status\":601,\"message\":\"Session not found\"}"))
        assertEquals(true, Ivpn.sessionGone(401 to "not json"))
        assertEquals(false, Ivpn.sessionGone(200 to "{\"status\":200}"))
        assertEquals(false, Ivpn.sessionGone(500 to "<html>"))
        assertEquals(false, Ivpn.sessionGone(429 to "{\"status\":429}"))
    }

    @Test fun pinnedServerNeedsNoList() {
        val sv = Ivpn.link(list, Location("ivpn", countryCode = "SE"), acct).server
        assertEquals(sv, Ivpn.link(null, Location("ivpn", pinned = sv), acct).server)
        try { Ivpn.link(null, Location("ivpn", pinned = sv.copy(ip = "172.16.0.9")), acct); org.junit.Assert.fail() } catch (e: app.unbraid.vpn.Fail) { }
    }

    /** With the list readable, a pinned server takes its current address and key; gone from the list, it is final. */
    @Test fun pinnedServerIsRefreshedFromTheList() {
        val sv = Ivpn.link(list, Location("ivpn", countryCode = "SE"), acct).server
        val stale = sv.copy(ip = "203.0.113.9", publicKey = "x".repeat(43) + "=")
        val l = Ivpn.link(list, Location("ivpn", pinned = stale), acct)
        assertEquals(sv.ip, l.server.ip); assertEquals(sv.publicKey, l.peer.publicKey); assertEquals(sv.host, l.server.host)
        try { Ivpn.link(list, Location("ivpn", pinned = sv.copy(host = "gone.wg.ivpn.net")), acct); org.junit.Assert.fail() } catch (e: app.unbraid.data.NoLongerOffered) {}
    }

    @Test fun unknownCountryFails() {
        try { Ivpn.link(list, Location("ivpn", countryCode = "ZZ"), acct); fail() } catch (e: IOException) { }
    }

    @Test fun sessionReplies() {
        val ok = Ivpn.session(200 to """{"status":200,"token":"tok","vpn_username":"u","vpn_password":"p","service_status":{"is_active":true},"wireguard":{"status":200,"message":"Success","ip_address":"172.16.1.2"}}""", "i-ABCD-EFGH-IJKL", "priv")
        assertEquals(Ivpn.Account("i-ABCD-EFGH-IJKL", "tok", "priv", "172.16.1.2"), Ivpn.account(ok))
        // The device name and the plan's limit, for the IVPN page.
        val named = Ivpn.session(200 to """{"status":200,"token":"tok","device_name":"Brave Owl","service_status":{"current_plan":"IVPN Standard","available_plans":[{"name":"Pro","device_limit":10},{"name":"Standard","device_limit":5}]},"wireguard":{"ip_address":"172.16.1.2"}}""", "a", "p")
        assertEquals("Brave Owl" to 5, Ivpn.account(named).let { it.device to it.limit })
        // A full account says how many devices and links to where they are listed.
        val full = try { Ivpn.session(400 to """{"status":602,"message":"You've reached the maximum number of connected devices","data":{"limit":5}}""", "a", "p"); null } catch (e: AuthException) { e }
        assertEquals("All 5 IVPN devices are in use. Sign out another device, then try again.", full!!.message)
        assertTrue(full.url!!.startsWith("https://www.ivpn.net/"))
        for (body in listOf("""{"status":401,"message":"The provided Account ID is not valid"}""", """{"status":702,"message":"inactive"}""", """{"status":70001}""")) {
            try { Ivpn.session(400 to body, "a", "p"); fail() } catch (e: AuthException) { }
        }
        // Two-factor and captcha are one more question, then the same sign-in again.
        for (st in listOf(70011, 70012)) try { Ivpn.session(400 to """{"status":$st}""", "a", "p"); fail() } catch (e: Challenge) { assertEquals("confirmation", e.field.key); assertNull(e.image) }
        val png = byteArrayOf(-119, 80, 78, 71)
        try { Ivpn.session(400 to """{"status":70002,"captcha_id":"c-1","captcha_image":"data:image/png;base64,${Base64.getEncoder().encodeToString(png)}"}""", "a", "p"); fail() } catch (e: Challenge) {
            assertEquals("captcha", e.field.key); assertEquals(mapOf("captcha_id" to "c-1"), e.keep); assertTrue(png.contentEquals(e.image))
        }
        try { Ivpn.session(502 to "<html>", "a", "p"); fail() } catch (e: IOException) { assertFalse(e is AuthException) }
    }

    @Test fun parseFailuresDoNotQuoteTheReply() {
        for (f in listOf({ Ivpn.session(200 to """{"status":200,"token":"SECRET-TOKEN" """, "a", "p") }, { Ivpn.account("""{"sessionToken":"SECRET" """) }, { Ivpn.parse("SECRET-LIST") })) {
            try { f(); fail() } catch (e: IOException) { assertFalse(e.message!!.contains("SECRET")); assertNull(e.cause) }
        }
        try { Ivpn.session(200 to """{"status":200,"token":"t"}""", "a", "p"); fail() } catch (e: IOException) { assertFalse(e is AuthException) }
        try { Ivpn.session(400 to """{"status":999,"message":"echoes SECRET"}""", "a", "p"); fail() } catch (e: IOException) { assertFalse(e.message!!.contains("SECRET")) }
    }

    @Test fun badAccountIdIsRejectedBeforeAnyRequest() {
        for (bad in listOf("", "i-ABCD", "x-ABCD-EFGH-IJKL", "1234567890")) {
            try { Ivpn.signIn(mapOf("account" to bad)); fail() } catch (e: IllegalArgumentException) { }
        }
    }
}
