package app.unbraid

import app.unbraid.data.AuthException
import app.unbraid.data.Link
import app.unbraid.data.Location
import app.unbraid.data.Mullvad
import app.unbraid.data.Provider
import app.unbraid.data.Providers
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.vpn.LinkPicker
import app.unbraid.data.WgKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPublicKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import kotlin.random.Random

class MullvadTest {
    private val list = Mullvad.parse(javaClass.getResource("/mullvad_relays.json")!!.readText())
    private val acct = Mullvad.Account("1234567890123456", "cHJpdmF0ZS1wcml2YXRlLXByaXZhdGUtcHJpdmF0ZTE=", "10.66.12.34/32", "dev-1")

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** X25519 public key = scalar times the base point (u = 9). */
    private fun derive(priv: ByteArray): ByteArray {
        val k = KeyFactory.getInstance("X25519").generatePrivate(PKCS8EncodedKeySpec(hex("302e020100300506032b656e04220420") + priv))
        val base = KeyFactory.getInstance("XDH").generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger.valueOf(9)))
        return KeyAgreement.getInstance("X25519").run { init(k); doPhase(base, true); generateSecret() }
    }

    @Test fun derivationMatchesRfc7748() {
        val pub = derive(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        assertArrayEquals(hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), pub)
    }

    /** RFC 7748 section 6.1 (Alice), and agreement with the platform's own key pairs. */
    @Test fun publicKeyFromPrivate() {
        val b64 = { h: String -> Base64.getEncoder().encodeToString(hex(h)) }
        assertEquals(b64("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), WgKeys.publicOf(b64("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")))
        repeat(20) { WgKeys.generate().let { k -> assertEquals(k.public, WgKeys.publicOf(k.private)) } }
        assertNull(WgKeys.publicOf("not a key"))
        assertNull(WgKeys.publicOf(Base64.getEncoder().encodeToString(ByteArray(16))))
    }

    @Test fun generatedKeysAreWireGuardFormat() {
        val k = WgKeys.generate()
        assertEquals(44, k.private.length)
        assertEquals(44, k.public.length)
        val priv = Base64.getDecoder().decode(k.private)
        assertEquals(0, priv[0].toInt() and 7)
        assertEquals(64, priv[31].toInt() and 0xC0)
        assertArrayEquals(Base64.getDecoder().decode(k.public), derive(priv))
        assertNotEquals(k.private, WgKeys.generate().private)
    }

    @Test fun xdhFallbackProducesTheSameFormat() {
        val k = WgKeys.generate(WgKeys.xdh())
        assertEquals(44, k.private.length)
        val priv = Base64.getDecoder().decode(k.private)
        assertEquals(0, priv[0].toInt() and 7)
        assertEquals(64, priv[31].toInt() and 0xC0)
        assertArrayEquals(Base64.getDecoder().decode(k.public), derive(priv))
    }

    @Test fun countriesAreGroupedAndKeyed() {
        val cs = Mullvad.countries(list)
        assertEquals(listOf("Netherlands", "Sweden", "USA"), cs.map { it.name })
        assertEquals(listOf("NL", "SE", "US"), cs.map { it.code })
        assertEquals(cs.size, cs.map { it.id }.toSet().size)
        val se = cs.first { it.code == "SE" }
        assertEquals(listOf("se-got", "se-mma", "se-sto"), se.cities.sortedBy { it.key }.map { it.key })
        assertEquals(se.cities.map { it.name }, se.cities.map { it.name }.sorted())
        assertTrue(cs.all { it.serverCount > 0 && it.cities.all { c -> c.key!!.startsWith(it.code.lowercase() + "-") } })
    }

    @Test fun inactiveRelaysAreDropped() {
        assertTrue(list.wireguard.relays.any { !it.active })
        assertTrue(list.usable.all { it.active })
        val nl = Mullvad.countries(list).first { it.code == "NL" }
        assertEquals(list.usable.count { it.location == "nl-ams" }, nl.serverCount)
    }

    @Test fun relayKeysAreWireGuardKeys() {
        assertTrue(list.wireguard.relays.isNotEmpty())
        assertTrue(list.wireguard.relays.all { it.publicKey.length == 44 && Base64.getDecoder().decode(it.publicKey).size == 32 })
    }

    @Test fun countryLinkUsesAnActiveRelayInThatCountry() {
        val eligible = list.usable.filter { it.location.startsWith("se-") && it.inCountry }.map { it.hostname }.toSet()
        val seen = (1..60).map { Mullvad.link(list, Location("mullvad", countryCode = "SE"), acct, Random(it)) }
        seen.forEach { l ->
            assertTrue(l.server.host in eligible)
            val p = l.peer
            assertEquals(l.server.publicKey, p.publicKey)
            assertEquals("${l.server.ip}:51820", p.endpoint)
            assertEquals(listOf("10.66.12.34/32"), p.address)
            assertEquals(listOf("0.0.0.0/0"), p.allowedIps)
            assertEquals(listOf("10.64.0.1"), p.dns)
            assertEquals(acct.privateKey, p.privateKey)
        }
        assertFalse(seen.any { it.server.host == "se-mma-wg-004" })
    }

    @Test fun cityLinkIncludesRelaysHiddenFromTheCountry() {
        val picks = (1..60).map { Mullvad.link(list, Location("mullvad", countryCode = "SE", region = "se-mma"), acct, Random(it)).server }
        assertTrue(picks.all { it.host.startsWith("se-mma-") && it.city == "Malmö" })
        assertTrue(picks.any { it.host == "se-mma-wg-004" })
    }

    @Test fun weightDecidesThePick() {
        val rs = Mullvad.parse("""{"locations":{"xx-aaa":{"country":"X","city":"A"}},"wireguard":{"relays":[
            {"hostname":"xx-aaa-wg-001","location":"xx-aaa","active":true,"ipv4_addr_in":"1.1.1.1","public_key":"k","weight":0},
            {"hostname":"xx-aaa-wg-002","location":"xx-aaa","active":true,"ipv4_addr_in":"1.1.1.2","public_key":"k","weight":100}]}}""")
        assertTrue((1..30).all { Mullvad.link(rs, Location("mullvad", countryCode = "XX"), acct, Random(it)).server.host == "xx-aaa-wg-002" })
    }

    @Test fun pinnedServerNeedsNoRelayList() {
        val sv = Mullvad.link(list, Location("mullvad", countryCode = "NL"), acct).server
        val l = Mullvad.link(null, Location("mullvad", pinned = sv), acct)
        assertEquals(sv, l.server)
        assertEquals("${sv.ip}:51820", l.peer.endpoint)
    }

    @Test fun missingPinnedRelayIsNoLongerOffered() {
        val sv = Mullvad.link(list, Location("mullvad", countryCode = "NL"), acct).server
        try { Mullvad.link(list, Location("mullvad", pinned = sv.copy(host = "gone", ip = "203.0.113.99")), acct); fail() }
        catch (e: app.unbraid.data.NoLongerOffered) { }
    }

    @Test fun ownedOnlyLeavesOutRentedServers() {
        val us = { owned: Boolean -> Mullvad.countries(list, owned).first { it.code == "US" }.cities.map { it.key } }
        assertEquals(listOf("us-lax", "us-nyc"), us(false))
        assertEquals(listOf("us-nyc"), us(true))
        assertTrue((1..30).all { Mullvad.link(list, Location("mullvad", countryCode = "US"), acct, Random(it), ownedOnly = true).server.host.startsWith("us-nyc-") })
        try { Mullvad.link(list, Location("mullvad", countryCode = "US", region = "us-lax"), acct, ownedOnly = true); fail() } catch (e: IOException) { assertTrue("Mullvad-owned" in e.message!!) }
        val rented = Mullvad.link(list, Location("mullvad", countryCode = "US", region = "us-lax"), acct).server
        assertEquals(rented, Mullvad.link(list, Location("mullvad", pinned = rented), acct, ownedOnly = true).server)
    }

    /** Turned on, Mullvad-owned only moves a running or cached unpinned route off a rented server, or blocks it; a pin stays. */
    @Test fun ownedOnlyMovesARunningRouteOffARentedServer() {
        val before = Providers.opt
        try {
            var on = false
            Providers.opt = { p, k -> if (k == "owned") (if (on) "on" else "off") else before(p, k) }
            Mullvad.ownedRelays = list.usable.filter { it.owned }.mapTo(HashSet()) { Triple(it.hostname, it.ip, it.publicKey) }
            val p = object : Provider by Mullvad { override fun connect(loc: Location, account: String): Link = Mullvad.link(list, loc, acct) }
            val rented = Mullvad.link(list, Location("mullvad", countryCode = "US", region = "us-lax"), acct)
            val accounts = mapOf("mullvad" to "acc")
            fun picked(r: Route, offline: Boolean = false): LinkPicker.Result {
                val s = Setup(routes = listOf(r), defaultRoute = r.id)
                return LinkPicker.pick(s, mapOf(r.id to rented), s, accounts, accounts, mapOf(LinkPicker.cacheKey(r) to rented), offline = offline) { p }
            }
            val lax = Route("1", Location("mullvad", countryCode = "US", region = "us-lax"))
            val us = Route("1", Location("mullvad", countryCode = "US"))
            val pin = Route("1", Location("mullvad", countryCode = "US", pinned = rented.server))
            assertEquals(rented.server, picked(lax).links.getValue("1").server)
            on = true
            // Before the provider is asked, the running tunnel already stops: its apps wait blocked ([LinkPicker.Unsuited]).
            assertTrue(picked(lax, offline = true).blocked.getValue("1") is LinkPicker.Unsuited)
            assertTrue(picked(pin, offline = true).links.containsKey("1"))
            val vpn = java.io.File("src/main/java/app/unbraid/vpn/UnbraidVpn.kt").readText()
            assertTrue(vpn.contains("(e is AuthException || e is LinkPicker.Unsuited) && id in links"))
            val blocked = picked(lax)
            assertTrue(blocked.links.isEmpty())
            assertTrue("Mullvad-owned" in blocked.blocked.getValue("1").message!!)
            val owned = picked(us).links.getValue("1").server
            assertTrue(owned.host.startsWith("us-nyc-"))
            assertEquals(rented.server, picked(pin).links.getValue("1").server)
            // Owned is confirmed for the relay as listed: another address or key under its name isn't.
            assertFalse(Mullvad.suits(owned.copy(ip = "203.0.113.99")))
            assertFalse(Mullvad.suits(owned.copy(publicKey = "other")))
            // Two unpinned routes on one server: the other one taken for the second must be confirmed owned too, else it
            // shares the owned one already found.
            val nyc = picked(us).links.getValue("1")
            val q = object : Provider by Mullvad {
                override fun connect(loc: Location, account: String): Link = if (loc.pinned != null) Mullvad.link(list, loc, acct) else nyc
                override fun servers(loc: Location, account: String, limit: Int) = listOf(rented.server)
            }
            val two = Setup(routes = listOf(us, us.copy(id = "2")), defaultRoute = "1", apps = mapOf("app.two" to "2"))
            val out = LinkPicker.pick(two, emptyMap(), null, emptyMap(), accounts, emptyMap()) { q }
            assertEquals(nyc.server, out.links.getValue("2").server)
            // A list read for the second route that no longer confirms the first one's server blocks the first.
            val owned1 = Mullvad.ownedRelays
            val nl = Route("2", Location("mullvad", countryCode = "NL"))
            val refreshing = object : Provider by Mullvad {
                override fun connect(loc: Location, account: String): Link = if (loc.countryCode == "NL")
                    Mullvad.link(list, loc, acct, ownedOnly = false).also { Mullvad.ownedRelays = setOf(Triple(it.server.host, it.server.ip, it.server.publicKey)) }
                else nyc
            }
            val later = LinkPicker.pick(Setup(routes = listOf(us, nl), defaultRoute = "1", apps = mapOf("app.two" to "2")), emptyMap(), null, emptyMap(), accounts, emptyMap()) { refreshing }
            assertEquals(setOf("2"), later.links.keys)
            assertTrue("suits your settings" in later.blocked.getValue("1").message!!)
            Mullvad.ownedRelays = owned1
            // Without a list read to confirm it, a saved server isn't taken for owned.
            Mullvad.ownedRelays = null
            assertFalse(Mullvad.suits(owned))
        } finally {
            Providers.opt = before
            Mullvad.ownedRelays = null
        }
    }

    @Test fun automaticPortsAndBlockingCategories() {
        assertEquals(listOf(51820, 53, 123, 4500), Mullvad.link(list, Location("mullvad", countryCode = "NL"), acct).ports)
        assertEquals("100.64.0.7", Mullvad.blockingDns { null })
        assertEquals("100.64.0.47", Mullvad.blockingDns { if (it == "adult" || it == "social") "on" else "off" })
        assertEquals("100.64.0.63", Mullvad.blockingDns { "on" })
        assertEquals(listOf("adult", "gambling", "social", "owned", "port"), Mullvad.options("").map { it.key })
    }

    @Test fun unknownCountryFails() {
        try { Mullvad.link(list, Location("mullvad", countryCode = "ZZ"), acct); fail() } catch (e: IOException) { }
    }

    @Test fun accountBlobRoundTrips() {
        val blob = Mullvad.device(201 to """{"id":"7e0cb5c6-0a0f-4f4a-9a61-0d2f0f0c0b8e","name":"x","pubkey":"k","ipv4_address":"10.68.1.2/32","ipv6_address":"fc00::2/128","hijack_dns":false,"created":"2026-10-18T00:00:00Z"}""", "1234567890123456", "priv")
        val a = Mullvad.account(blob)
        assertEquals(Mullvad.Account("1234567890123456", "priv", "10.68.1.2/32", "7e0cb5c6-0a0f-4f4a-9a61-0d2f0f0c0b8e", "x"), a)
    }

    @Test fun tokenAndDeviceErrors() {
        assertEquals("tok", Mullvad.token(200 to """{"access_token":"tok","expiry":"2026-10-19T00:00:00Z"}"""))
        for (r in listOf(400 to """{"code":"INVALID_ACCOUNT","error":"Bad account number"}""", 401 to "")) {
            try { Mullvad.token(r); fail() } catch (e: AuthException) { }
        }
        try { Mullvad.device(400 to """{"code":"MAX_DEVICES_REACHED","error":"x"}""", "1", "p"); fail() } catch (e: AuthException) { assertTrue("All 5 Mullvad devices are in use" in e.message!!) }
        try { Mullvad.device(500 to "", "1", "p"); fail() } catch (e: IOException) { assertFalse(e is AuthException) }
    }

    @Test fun lostRegistrationReplyIsReconciledByPublicKey() {
        val keys = WgKeys.Keys("priv", "ourpub")
        val devices = """[{"id":"d-1","name":"a","pubkey":"other","ipv4_address":"10.68.0.1/32"},{"id":"d-2","name":"b","pubkey":"ourpub","ipv4_address":"10.68.0.2/32","hijack_dns":false}]"""
        assertEquals(Mullvad.Account("1234567890123456", "priv", "10.68.0.2/32", "d-2", "b"), Mullvad.account(Mullvad.adopt(devices, "1234567890123456", keys)!!))
        assertNull(Mullvad.adopt("[]", "1234567890123456", keys))
        assertNull(Mullvad.adopt(devices, "1234567890123456", WgKeys.Keys("p", "unknown")))
    }

    @Test fun oldBlobWithoutDeviceNameStillLoads() {
        assertEquals("", Mullvad.account("""{"account":"1","privateKey":"p","ipv4Address":"10.0.0.1/32","deviceId":"d"}""").device)
    }

    @Test fun deviceLineCountsSlotsAndNamesOurs() {
        val list = Mullvad.devices("""[{"id":"d-1","name":"tidy cat","pubkey":"a","ipv4_address":"10.68.0.1/32"},{"id":"dev-1","name":"rapid otter","pubkey":"b","ipv4_address":"10.68.0.2/32"}]""")
        assertEquals(app.unbraid.data.Device("Its own device, Rapid Otter, removed when you sign out", "https://mullvad.net/account/devices", 2), Mullvad.deviceLine(acct, list))
        assertEquals(app.unbraid.data.Device("Its own device, Old Name, removed when you sign out", "https://mullvad.net/account/devices"), Mullvad.deviceLine(acct.copy(device = "old name"), null))
        assertEquals("Its own device, removed when you sign out", Mullvad.deviceLine(acct, null).line)
    }

    @Test fun expiryIsReadFromAccountReply() {
        assertEquals(1795334400L, Mullvad.expiry("""{"id":"x","expiry":"2026-11-22T08:00:00+00:00","max_devices":5}"""))
        assertEquals(1795334400L, Mullvad.expiry("""{"expiry":"2026-11-22T08:00:00Z"}"""))
        assertNull(Mullvad.expiry("""{"id":"x"}"""))
        assertNull(Mullvad.expiry("""{"expiry":"soon"}"""))
    }

    @Test fun parseFailuresDoNotQuoteTheReply() {
        val bad = """{"access_token": SECRET-TOKEN"""
        for (f in listOf({ Mullvad.token(200 to bad) }, { Mullvad.device(201 to bad, "1", "p") }, { Mullvad.account("""{"account":"SECRET-ACCT" """) }, { Mullvad.parse("SECRET-LIST") })) {
            try { f(); fail() } catch (e: IOException) { assertFalse(e.message!!.contains("SECRET")); assertNull(e.cause) }
        }
    }

    @Test fun badAccountNumberIsRejectedBeforeAnyRequest() {
        for (bad in listOf("", "1234", "12345678901234567", "abcd1234abcd1234")) {
            try { Mullvad.signIn(mapOf("account" to bad)); fail() } catch (e: IllegalArgumentException) { }
        }
    }

    /** Only Mullvad's own "gone" answers mean the phone was removed; a 401 or an outage is not proof. */
    @Test fun revokedOnlyOnMullvadsOwnAnswer() {
        val ok = 200 to """{"access_token":"t"}"""
        fun lookup(code: Int?): (String) -> Unit = { if (code == 401) throw AuthException("Sign-in was refused") else if (code != null) throw app.unbraid.data.Http.Status(code, "HTTP $code") }
        assertTrue(Mullvad.revoked({ ok }, lookup(404)))
        assertTrue(Mullvad.revoked({ 400 to """{"code":"INVALID_ACCOUNT"}""" }, lookup(null)))
        assertFalse(Mullvad.revoked({ ok }, lookup(null)))
        assertFalse(Mullvad.revoked({ ok }, lookup(401)))
        assertFalse(Mullvad.revoked({ ok }, lookup(403)))
        assertFalse(Mullvad.revoked({ ok }, lookup(503)))
        assertFalse(Mullvad.revoked({ 401 to "" }, lookup(null)))
        // A 404 from the token endpoint is not the device lookup's 404.
        assertFalse(Mullvad.revoked({ 404 to "" }, lookup(null)))
        assertFalse(Mullvad.revoked({ 404 to "" }, lookup(404)))
        assertFalse(Mullvad.revoked({ throw IOException("offline") }, lookup(null)))
    }

    /** A sign-out releases the device when it is deleted, already gone, or the account itself is no longer valid. */
    @Test fun releasedAlsoWhenTheAccountIsGone() {
        val ok = 200 to """{"access_token":"t"}"""
        var deleted = 0
        fun delete(code: Int?): (String) -> Unit = { deleted++; if (code != null) throw app.unbraid.data.Http.Status(code, "HTTP $code") }
        assertTrue(Mullvad.released({ ok }, delete(null)))
        assertTrue(Mullvad.released({ ok }, delete(404)))
        deleted = 0
        assertTrue(Mullvad.released({ 400 to """{"code":"INVALID_ACCOUNT"}""" }, delete(null)))
        assertEquals(0, deleted)
        assertFalse(Mullvad.released({ 401 to "" }, delete(null)))
        assertFalse(Mullvad.released({ 404 to "" }, delete(null)))
        assertFalse(Mullvad.released({ ok }, delete(503)))
        assertFalse(Mullvad.released({ throw IOException("offline") }, delete(null)))
        // The notice names the device the account was given.
        val blob = app.unbraid.data.NordApi.json.encodeToString(Mullvad.Account.serializer(), Mullvad.Account("1234567890123456", "k", "10.0.0.2", "d1", "brave fox"))
        assertEquals("Brave Fox", Mullvad.deviceName(blob))
        assertNull(Mullvad.deviceName(blob.replace("brave fox", "")))
    }
}
