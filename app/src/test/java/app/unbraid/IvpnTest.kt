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
