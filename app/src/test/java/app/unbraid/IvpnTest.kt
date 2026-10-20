package app.unbraid

import app.unbraid.data.AuthException
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
    private val list = Ivpn.parse(javaClass.getResource("/ivpn_servers.json")!!.readText())
    private val acct = Ivpn.Account("i-ABCD-EFGH-IJKL", "session", "cHJpdmF0ZS1wcml2YXRlLXByaXZhdGUtcHJpdmF0ZTE=", "172.16.34.56")

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

    @Test fun cityLinkStaysInThatGateway() {
        val l = Ivpn.link(list, Location("ivpn", countryCode = "US", region = "us-ny.wg.ivpn.net"), acct)
        assertEquals("New York, NY", l.server.city)
        assertTrue(l.server.host.startsWith("us-ny"))
    }

    @Test fun pinnedServerNeedsNoList() {
        val sv = Ivpn.link(list, Location("ivpn", countryCode = "SE"), acct).server
        assertEquals(sv, Ivpn.link(null, Location("ivpn", pinned = sv), acct).server)
    }

    @Test fun unknownCountryFails() {
        try { Ivpn.link(list, Location("ivpn", countryCode = "ZZ"), acct); fail() } catch (e: IOException) { }
    }

    @Test fun sessionReplies() {
        val ok = Ivpn.session(200 to """{"status":200,"token":"tok","vpn_username":"u","vpn_password":"p","service_status":{"is_active":true},"wireguard":{"status":200,"message":"Success","ip_address":"172.16.1.2"}}""", "i-ABCD-EFGH-IJKL", "priv")
        assertEquals(Ivpn.Account("i-ABCD-EFGH-IJKL", "tok", "priv", "172.16.1.2"), Ivpn.account(ok))
        val limit = try { Ivpn.session(400 to """{"status":602,"message":"You've reached the maximum number of connected devices","data":{"limit":5}}""", "a", "p"); null } catch (e: AuthException) { e.message }
        assertTrue(limit!!.contains("device limit"))
        for (body in listOf("""{"status":401,"message":"The provided Account ID is not valid"}""", """{"status":702,"message":"inactive"}""", """{"status":70011}""", """{"status":70001}""")) {
            try { Ivpn.session(400 to body, "a", "p"); fail() } catch (e: AuthException) { }
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
