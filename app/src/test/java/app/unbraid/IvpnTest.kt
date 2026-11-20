package app.unbraid

import app.unbraid.data.AuthException
import app.unbraid.data.Challenge
import app.unbraid.data.Ivpn
import app.unbraid.data.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.IOException
import java.util.Base64

class IvpnTest {
    private companion object { val PSK: String = Base64.getEncoder().encodeToString(ByteArray(32) { 7 }) }

    /** Stands in for the engine's Kyber: only the private key "kem" opens the ciphertext "good". */
    @org.junit.Before fun fakeKyber() {
        Ivpn.kyber = object : Ivpn.Kyber {
            override fun keyPair() = "pub" to "kem"
            override fun psk(private: String, cipher: String) = if (private == "kem" && cipher == "good") PSK else if (cipher == "short") "c2hvcnQ=" else throw IllegalArgumentException("bad")
        }
    }

    /** Every sign-in gets the quantum-safe key; a reply without a usable one ends it, naming the session to close. */
    @Test fun quantumSafeKeyIsRequired() {
        val ok = Ivpn.account(Ivpn.session(200 to """{"status":200,"token":"tok","wireguard":{"ip_address":"172.16.1.2","kem_cipher1":"good"}}""", "a", "p", "kem"))
        assertEquals(PSK, ok.psk)
        assertEquals(PSK, Ivpn.link(list, Location("ivpn", countryCode = "NL"), ok).peer.presharedKey)
        for (cipher in listOf(null, "bad", "short")) {
            val wg = if (cipher == null) """{"ip_address":"172.16.1.2"}""" else """{"ip_address":"172.16.1.2","kem_cipher1":"$cipher"}"""
            val e = try { Ivpn.session(200 to """{"status":200,"token":"tok2","wireguard":$wg}""", "a", "p", "kem"); null } catch (e: Ivpn.NoPq) { e }
            assertEquals("tok2", e!!.token)
            assertEquals(Ivpn.NO_PQ, e.message)
            // The device this sign-in took is given back; when that fails, the user is told to remove it.
            assertSame(e, Ivpn.unfreed(e, freed = true))
            assertTrue("IVPN client area" in Ivpn.unfreed(e, freed = false).message!!)
            // Told also when the sign-in form was closed before IVPN answered.
            assertTrue(Ivpn.unfreed(e, freed = false) is app.unbraid.data.DeviceLeftTaken)
            assertTrue(java.io.File("src/main/java/app/unbraid/ui/Ui.kt").readText().contains("if (dismissed.remove(id)) { if (failure is app.unbraid.data.DeviceLeftTaken && error != null) warn(error) }\n                else {"))
        }
        // Signed in before quantum-safe existed: still connects, the page says it isn't on yet.
        val old = """{"accountId":"a","sessionToken":"t","privateKey":"k","ipAddress":"10.0.0.2"}"""
        assertNull(Ivpn.account(old).psk)
        assertFalse(Ivpn.quantumSafe(old))
        assertTrue(Ivpn.quantumSafe(Ivpn.session(200 to """{"status":200,"token":"tok","wireguard":{"ip_address":"172.16.1.2","kem_cipher1":"good"}}""", "a", "p", "kem")))
        assertNull(Ivpn.link(list, Location("ivpn", countryCode = "NL"), acct).peer.presharedKey)
        // A tunnel cached from the earlier sign-in, without the quantum-safe key, never stands in for the new one.
        val blob = Ivpn.session(200 to """{"status":200,"token":"tok","wireguard":{"ip_address":"172.16.1.2","kem_cipher1":"good"}}""", "a", acct.privateKey, "kem")
        val nl = Location("ivpn", countryCode = "NL")
        assertFalse(Ivpn.trusts(Ivpn.link(list, nl, acct), blob))
        assertTrue(Ivpn.trusts(Ivpn.link(list, nl, Ivpn.account(blob)), blob))
        assertFalse(Ivpn.trusts(Ivpn.link(list, nl, Ivpn.account(blob)), "not json"))
    }
    private val list = Ivpn.parse(javaClass.getResource("/ivpn_servers.json")!!.readText())
    private val acct = Ivpn.Account("i-ABCD-EFGH-IJKL", "session", "cHJpdmF0ZS1wcml2YXRlLXByaXZhdGUtcHJpdmF0ZTE=", "172.16.34.56")

    /** A new key is due by age, to finish a change, or to add the quantum-safe key; IVPN's answer decides what's kept. */
    @Test fun keyRotation() {
        val day = 86_400_000L
        val a = acct.copy(psk = PSK, keyAt = 10 * day)
        assertFalse(Ivpn.due(a, 7, 16 * day))
        assertTrue(Ivpn.due(a, 7, 17 * day))
        assertFalse(Ivpn.due(a, null, 400 * day)) // Never
        assertTrue(Ivpn.due(acct, null, 0)) // no quantum-safe key yet: due whatever the setting
        val p = Ivpn.Pending("bmV3LXByaXZhdGUtbmV3LXByaXZhdGUtbmV3LXByaXY=", "newpub", "kpub", "kem")
        val pending = a.copy(pending = p)
        assertTrue(Ivpn.due(pending, null, 0))
        // Taken: the new key, its quantum-safe key and address replace the old ones, and the change is finished.
        val next = Ivpn.rekeyed(200 to """{"status":200,"ip_address":"172.16.9.9","kem_cipher1":"good"}""", pending, 20 * day)
        assertEquals(Ivpn.Account(a.accountId, a.sessionToken, p.privateKey, "172.16.9.9", psk = PSK, keyAt = 20 * day), next)
        assertEquals(a.ipAddress, Ivpn.rekeyed(200 to """{"status":200,"kem_cipher1":"good"}""", pending, 0).ipAddress)
        // Refused: the old key stays. Signed out elsewhere: a new sign-in. No usable quantum-safe key: never a plain key.
        try { Ivpn.rekeyed(200 to """{"status":422,"message":"bad"}""", pending, 0); fail() } catch (e: Ivpn.Rejected) { }
        try { Ivpn.rekeyed(200 to """{"status":601}""", pending, 0); fail() } catch (e: AuthException) { }
        try { Ivpn.rekeyed(401 to "", pending, 0); fail() } catch (e: AuthException) { }
        // Busy or failing: the earlier request may have gone through, so the new key is kept for another try.
        for ((code, st) in listOf(429 to 429, 408 to 408, 500 to 500, 503 to 503, 503 to 422, 200 to 408)) try { Ivpn.rekeyed(code to """{"status":$st}""", pending, 0); fail() } catch (e: IOException) { assertFalse(e is Ivpn.Rejected || e is AuthException) }
        for (c in listOf("", ""","kem_cipher1":"bad"""", ""","kem_cipher1":"short"""")) {
            try { Ivpn.rekeyed(200 to """{"status":200,"ip_address":"172.16.9.9"$c}""", pending, 0); fail() } catch (e: IOException) { assertFalse(e is Ivpn.Rejected || e is AuthException) }
        }
        try { Ivpn.rekeyed(200 to """{"status":200,"ip_address":"10.0.0.1/8x","kem_cipher1":"good"}""", pending, 0); fail() } catch (e: IOException) { }
        // An account saved before these fields existed still reads.
        assertEquals(0L, Ivpn.account("""{"accountId":"a","sessionToken":"t","privateKey":"k","ipAddress":"10.0.0.2"}""").keyAt)
    }

    /** The Port setting also takes a number of the user's own, only inside IVPN's ranges; a typed port is used as is. */
    @Test fun typedPortStaysInIvpnsRanges() {
        val port = Ivpn.options(list).single { it.key == app.unbraid.data.PORT_OPT }
        assertEquals(listOf(5500..19999, 30000..65000), port.custom)
        for (ok in listOf("5500", "12345", "19999", "30000", "65000")) assertTrue(ok, port.allows(ok))
        for (no in listOf("", "53", "5499", "20000", "29999", "65001", "abc", "-1")) assertFalse(no, port.allows(no))
        val setup = app.unbraid.data.Setup(routes = listOf(app.unbraid.data.Route("r", Location("ivpn", countryCode = "NL"))), defaultRoute = "r", opts = mapOf("ivpn" to mapOf(app.unbraid.data.PORT_OPT to "12345")))
        val l = app.unbraid.vpn.LinkPicker.ported(setup, mapOf("r" to Ivpn.link(list, Location("ivpn", countryCode = "NL"), acct)), emptyMap())["r"] as app.unbraid.data.WgLink
        assertTrue(l.peer.endpoint.endsWith(":12345"))
    }

    /** With Disguise on, an IVPN tunnel goes inside V2Ray to the server's WireGuard port behind it; off, nothing changes. */
    @Test fun disguiseUsesV2Ray() {
        val id = "27de860d-5601-412d-8b71-baa048a94b98"
        val v2 = list.copy(config = list.config.copy(ports = list.config.ports.copy(v2ray = Ivpn.V2ray(id, listOf(Ivpn.Port("UDP", 15351))))))
        assertTrue(Ivpn.options(v2).any { it.key == app.unbraid.data.DISGUISE_OPT })
        assertFalse(Ivpn.options(list).any { it.key == app.unbraid.data.DISGUISE_OPT }) // the list names no V2Ray id
        val loc = Location("ivpn", countryCode = "NL")
        val l = Ivpn.link(v2, loc, acct)
        val host = v2.wireguard.flatMap { it.hosts }.single { it.hostname == l.server.host }
        assertEquals(app.unbraid.data.Disguise(app.unbraid.data.Vmess(host.v2ray, 80, id), 15351), l.disguise)
        assertNull(Ivpn.link(list, loc, acct).disguise)
        fun ported(on: String) = app.unbraid.vpn.LinkPicker.ported(app.unbraid.data.Setup(routes = listOf(app.unbraid.data.Route("r", loc)), defaultRoute = "r",
            opts = mapOf("ivpn" to mapOf(app.unbraid.data.DISGUISE_OPT to on, app.unbraid.data.PORT_OPT to "443"))), mapOf("r" to l), emptyMap())["r"] as app.unbraid.data.WgLink
        assertEquals("${l.server.ip}:15351" to l.disguise!!.vmess, ported("v2ray").peer.let { it.endpoint to it.vmess })
        assertEquals("${l.server.ip}:443" to null, ported("off").peer.let { it.endpoint to it.vmess })
        // Disguise on but none for this server (the list didn't say): the route is blocked, never connected plain.
        val r = app.unbraid.data.Route("r", loc)
        val on = app.unbraid.data.Setup(routes = listOf(r), defaultRoute = "r", opts = mapOf("ivpn" to mapOf(app.unbraid.data.DISGUISE_OPT to "v2ray")))
        val blob = app.unbraid.data.NordApi.json.encodeToString(Ivpn.Account.serializer(), acct)
        val plain = Ivpn.link(list, loc, acct)
        val picked = app.unbraid.vpn.LinkPicker.pick(on, mapOf("r" to plain), on, mapOf("ivpn" to blob), mapOf("ivpn" to blob), emptyMap(), emptySet(), emptyMap(), true) { Ivpn }
        assertTrue(picked.links.isEmpty() && "r" in picked.blocked)
        // A host without a V2Ray address, or a malformed id, gives no disguise.
        assertNull(Ivpn.disguise(v2, host.copy(v2ray = "")))
        assertNull(Ivpn.disguise(v2.copy(config = v2.config.copy(ports = v2.config.ports.copy(v2ray = Ivpn.V2ray(id, listOf(Ivpn.Port("UDP", 65536)))))), host))
        assertNull(Ivpn.disguise(v2.copy(config = v2.config.copy(ports = v2.config.ports.copy(v2ray = Ivpn.V2ray("not-an-id", listOf(Ivpn.Port("UDP", 15351)))))), host))
    }

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
        // Same country or another provider's entry: blocked.
        val same = setup.copy(routes = listOf(setup.routes[0], setup.routes[1].copy(loc = nl)))
        assertTrue(same.viaProblem(same.route("se")!!) != null)
        // Blocking on multi-hop uses the chosen list's usual resolver, any list (the published multi-hop one doesn't answer).
        for ((opts, want) in listOf(emptyMap<String, String>() to "10.0.254.2", mapOf("list" to "10.0.254.4|10.0.254.5") to "10.0.254.4",
                mapOf("list" to "10.0.254.4|10.0.254.5", "hardcore" to "on") to "10.0.254.5")) {
            val on = setup.copy(opts = mapOf("ivpn" to opts + (app.unbraid.data.PROTECT to "on")))
            assertTrue("se" in pick(on).links)
            val dns = kotlinx.serialization.json.Json.parseToJsonElement(app.unbraid.vpn.Compiler.compile(app.unbraid.vpn.Compiler.Input(on, app.unbraid.vpn.LinkPicker.ported(on, pick(on).links, emptyMap()), "app.unbraid", false, "/tmp/ts", "/tmp/c.db")))
                .jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }.single { it["tag"]!!.jsonPrimitive.content == "dns-r-se" }
            assertEquals(want to "r-se", dns["server"]!!.jsonPrimitive.content to dns["detour"]!!.jsonPrimitive.content) // inside the exit's own tunnel
        }
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

    /** Hosts IVPN's status list marks inactive or in maintenance are picked last, and only when nothing else is left. */
    @Test fun downHostsArePickedLast() {
        val down = Ivpn.down("""{"servers":[
            {"gateway":"a","is_active":false,"in_maintenance":false,"hosts":{"wireguard":{"hostname":"x1.wg.ivpn.net"}}},
            {"gateway":"b","is_active":true,"in_maintenance":true,"hosts":{"wireguard":{"hostname":"x2.wg.ivpn.net"}}},
            {"gateway":"c","is_active":true,"in_maintenance":false,"hosts":{"wireguard":{"hostname":"x3.wg.ivpn.net"}}},
            {"gateway":"d","is_active":false,"hosts":{}}],"config":{}}""")
        assertEquals(setOf("x1.wg.ivpn.net", "x2.wg.ivpn.net"), down)
        val nl = list.wireguard.filter { it.countryCode == "NL" }.flatMap { it.hosts }.sortedBy { it.load }
        assertTrue(nl.size > 1)
        val loc = Location("ivpn", countryCode = "NL")
        assertEquals(nl[1].hostname, Ivpn.link(list.copy(down = setOf(nl[0].hostname)), loc, acct).server.host)
        assertEquals(nl[0].hostname, Ivpn.link(list.copy(down = nl.map { it.hostname }.toSet()), loc, acct).server.host)
        // The server list says so, with the hosting company, and lists it last; a saved server never keeps the note.
        val listed = Ivpn.listed(list.copy(down = setOf(nl[0].hostname)), loc, 20)
        assertEquals(nl[0].hostname, listed.last().host)
        assertEquals("Out of service · ${nl[0].isp}" to true, listed.last().note to listed.last().flagged)
        assertEquals(nl[1].isp to false, listed.first().note to listed.first().flagged)
        assertTrue(nl[0].isp.isNotBlank())
        val saved = app.unbraid.data.NordApi.json.decodeFromString(app.unbraid.data.Server.serializer(), app.unbraid.data.NordApi.json.encodeToString(app.unbraid.data.Server.serializer(), listed.last()))
        assertEquals("" to false, saved.note to saved.flagged)
        assertEquals(listed.last(), saved)
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
        val ok = Ivpn.session(200 to """{"status":200,"token":"tok","vpn_username":"u","vpn_password":"p","service_status":{"is_active":true},"wireguard":{"status":200,"message":"Success","ip_address":"172.16.1.2","kem_cipher1":"good"}}""", "i-ABCD-EFGH-IJKL", "priv", "kem")
        Ivpn.account(ok).let { assertEquals(Ivpn.Account("i-ABCD-EFGH-IJKL", "tok", "priv", "172.16.1.2", psk = PSK, keyAt = it.keyAt), it); assertTrue(it.keyAt > 0) }
        // The device name and the plan's limit, for the IVPN page.
        val named = Ivpn.session(200 to """{"status":200,"token":"tok","device_name":"Brave Owl","service_status":{"current_plan":"IVPN Standard","available_plans":[{"name":"Pro","device_limit":10},{"name":"Standard","device_limit":5}]},"wireguard":{"ip_address":"172.16.1.2","kem_cipher1":"good"}}""", "a", "p", "kem")
        assertEquals("Brave Owl" to 5, Ivpn.account(named).let { it.device to it.limit })
        // A full account says how many devices and links to where they are listed.
        val limit = """{"status":602,"message":"You've reached the maximum number of connected devices","data":{"limit":5}}"""
        val full = try { Ivpn.session(400 to limit, "a", "p", "kem"); null } catch (e: app.unbraid.data.SlotsFull) { e }
        assertEquals("All 5 IVPN devices are in use.", full!!.message)
        assertTrue(full.url!!.startsWith("https://www.ivpn.net/"))
        // It offers to sign out the other devices, says it's all of them, and only a sign-in carrying its retry does it.
        assertTrue("every other device" in full.confirm)
        assertEquals(mapOf("force" to "a"), full.retry)
        assertEquals("false", Ivpn.sessionBody("a", "pub", "kpub", mapOf("account" to "a"))["force"].toString())
        assertEquals("true", Ivpn.sessionBody("a", "pub", "kpub", mapOf("account" to "a") + full.retry)["force"].toString())
        // The agreement is for that account only: another ID typed or scanned since, or any other value, is never forced.
        assertEquals("false", Ivpn.sessionBody("b", "pub", "kpub", mapOf("account" to "b") + full.retry)["force"].toString())
        for (v in listOf("true", "yes", "")) assertEquals("false", Ivpn.sessionBody("a", "pub", "kpub", mapOf("force" to v))["force"].toString())
        // Still full after forcing: no second offer, the plain message.
        val again = try { Ivpn.session(400 to limit, "a", "p", "kem", force = true); null } catch (e: AuthException) { e }
        assertFalse(again is app.unbraid.data.SlotsFull)
        assertEquals("All 5 IVPN devices are in use. Sign out another device, then try again.", again!!.message)
        // A forced sign-in that IVPN asks a two-factor code or captcha for stays forced when answered; an unforced one doesn't.
        try { Ivpn.session(400 to """{"status":70011}""", "a", "p", "kem", force = true); fail() } catch (e: Challenge) { assertEquals(mapOf("force" to "a"), e.keep) }
        try { Ivpn.session(400 to """{"status":70011}""", "a", "p", "kem"); fail() } catch (e: Challenge) { assertTrue(e.keep.isEmpty()) }
        try { Ivpn.session(400 to """{"status":70001,"captcha_id":"c-2"}""", "a", "p", "kem", force = true); fail() } catch (e: Challenge) { assertEquals(mapOf("captcha_id" to "c-2", "force" to "a"), e.keep) }
        for (body in listOf("""{"status":401,"message":"The provided Account ID is not valid"}""", """{"status":702,"message":"inactive"}""", """{"status":70001}""")) {
            try { Ivpn.session(400 to body, "a", "p", "kem"); fail() } catch (e: AuthException) { }
        }
        // Two-factor and captcha are one more question, then the same sign-in again.
        for (st in listOf(70011, 70012)) try { Ivpn.session(400 to """{"status":$st}""", "a", "p", "kem"); fail() } catch (e: Challenge) { assertEquals("confirmation", e.field.key); assertNull(e.image) }
        val png = byteArrayOf(-119, 80, 78, 71)
        try { Ivpn.session(400 to """{"status":70002,"captcha_id":"c-1","captcha_image":"data:image/png;base64,${Base64.getEncoder().encodeToString(png)}"}""", "a", "p", "kem"); fail() } catch (e: Challenge) {
            assertEquals("captcha", e.field.key); assertEquals(mapOf("captcha_id" to "c-1"), e.keep); assertTrue(png.contentEquals(e.image))
        }
        try { Ivpn.session(502 to "<html>", "a", "p", "kem"); fail() } catch (e: IOException) { assertFalse(e is AuthException) }
    }

    @Test fun parseFailuresDoNotQuoteTheReply() {
        for (f in listOf({ Ivpn.session(200 to """{"status":200,"token":"SECRET-TOKEN" """, "a", "p", "kem") }, { Ivpn.account("""{"sessionToken":"SECRET" """) }, { Ivpn.parse("SECRET-LIST") })) {
            try { f(); fail() } catch (e: IOException) { assertFalse(e.message!!.contains("SECRET")); assertNull(e.cause) }
        }
        try { Ivpn.session(200 to """{"status":200,"token":"t"}""", "a", "p", "kem"); fail() } catch (e: IOException) { assertFalse(e is AuthException) }
        try { Ivpn.session(400 to """{"status":999,"message":"echoes SECRET"}""", "a", "p", "kem"); fail() } catch (e: IOException) { assertFalse(e.message!!.contains("SECRET")) }
    }

    @Test fun badAccountIdIsRejectedBeforeAnyRequest() {
        for (bad in listOf("", "i-ABCD", "x-ABCD-EFGH-IJKL", "1234567890")) {
            try { Ivpn.signIn(mapOf("account" to bad)); fail() } catch (e: IllegalArgumentException) { }
        }
    }
}
