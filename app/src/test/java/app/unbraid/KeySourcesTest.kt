package app.unbraid

import app.unbraid.data.AirVpn
import app.unbraid.data.Catalog
import app.unbraid.data.KeyProvider
import app.unbraid.data.Location
import app.unbraid.data.Server
import app.unbraid.data.Snapshot
import app.unbraid.data.Surfshark
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/** Server keys come only from the provider (or the user's .conf), never from gluetun's catalogue. */
/** Surfshark's addresses come from the catalogue: give every one of [entries]' hosts the address [ip]. */
internal fun surfsharkAt(entries: List<Catalog.Entry>, ip: String = "203.0.113.7") {
    Surfshark.catalogue = { entries.map { Catalog.Entry("wireguard", it.place.ifEmpty { "Netherlands" }, hostname = it.hostname, wgpubkey = "unused", ips = listOf(ip)) } }
}

class KeySourcesTest {
    private val priv = "dW5icmFpZC10ZXN0LXByaXZhdGUta2V5LTAwMDAwMDE="
    private val psk = "dW5icmFpZC10ZXN0LXByZXNoYXJlZC1rZXktMDAwMDE="
    private val accountKey = "vOgsnvam/Xc6w8zfQeGcynOr8RMxYzpki3b0EaOkEoI="
    private val evilKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    private fun res(name: String) = javaClass.getResource("/$name")!!.readText()
    private fun <T : KeyProvider> T.signed(key: String) = signIn(mapOf("key" to key))
    private val liveBody = res("surfshark_clusters.json")
    private val live = Surfshark.parse(liveBody)

    @Before fun offline() {
        Surfshark.addressBook = Snapshot.memory()
        Surfshark.catalogue = { throw IOException("offline") }
        Surfshark.live = { throw IOException("offline") }
        Surfshark.snapshot = Snapshot.memory()
        AirVpn.status = { throw IOException("offline") }
        AirVpn.catalogue = { throw IOException("offline") }
        AirVpn.api = { _, _, _ -> throw IOException("offline") }
        AirVpn.pause = { fail("no waiting in tests") }
    }

    @After fun reset() = offline()

    @Test fun surfsharkTakesOnlyAddressesFromTheCatalogue() {
        val host = live.first().hostname
        // A swapped catalogue: same host name, an attacker's address and key.
        Surfshark.catalogue = { listOf(Catalog.Entry("wireguard", live.first().country, hostname = host, wgpubkey = evilKey, ips = listOf("198.51.100.66"))) }
        val l = Surfshark.link(Location("surfshark", country = live.first().place), Surfshark.account(Surfshark.signed(priv))) { live }
        assertEquals("198.51.100.66:51820", l.peer.endpoint)
        assertEquals(live.first().wgpubkey, l.peer.publicKey)
    }

    /** A pinned server with no address to be had (catalogue down, nothing kept) keeps its saved address, with Surfshark's current key. */
    @Test fun surfsharkPinnedServerKeepsItsAddressWhenNoneCanBeRead() {
        val e = live.first()
        val pin = Server(e.hostname.substringBefore('.'), e.hostname, "203.0.113.5", "stale", 0)
        val l = Surfshark.link(Location("surfshark", country = e.place, pinned = pin), Surfshark.account(Surfshark.signed(priv))) { live }
        assertEquals("203.0.113.5:51820", l.peer.endpoint)
        assertEquals(e.wgpubkey, l.peer.publicKey)
    }

    @Test fun surfsharkIgnoresCatalogueHostsItDoesNotList() {
        Surfshark.catalogue = { listOf(Catalog.Entry("wireguard", live.first().country, hostname = "nl-evil.prod.surfshark.com", wgpubkey = evilKey, ips = listOf("198.51.100.66"))) }
        try {
            Surfshark.link(Location("surfshark", country = live.first().place), Surfshark.account(Surfshark.signed(priv))) { live }
            fail("a host only the catalogue lists must not be used")
        } catch (_: IOException) {
        }
    }

    @Test fun surfsharkKeepsItsLastListAndUsesItWhenTheApiIsDown() {
        val account = Surfshark.signed(priv)
        Surfshark.live = { liveBody }
        val fresh = Surfshark.countries(account)
        assertEquals(liveBody, Surfshark.snapshot.read())
        Surfshark.live = { throw IOException("down") }
        Surfshark.catalogue = { error("the catalogue must not be read for keys") }
        assertEquals(fresh, Surfshark.countries(account))
    }

    @Test fun surfsharkWithoutAnyOwnListFailsInsteadOfTrustingTheCatalogue() {
        Surfshark.catalogue = { error("the catalogue must not be read for keys") }
        try {
            Surfshark.countries(Surfshark.signed(priv))
            fail("no list should be available")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Surfshark"))
        }
    }

    @Test fun surfsharkKeepsTheOldCopyWhenTheApiSendsNothingUsable() {
        Surfshark.snapshot = Snapshot.memory(liveBody)
        Surfshark.live = { "[]" }
        assertTrue(Surfshark.countries(Surfshark.signed(priv)).isNotEmpty())
        assertEquals(liveBody, Surfshark.snapshot.read())
    }

    @Test fun airvpnFallbackUsesTheAccountKeyNotTheCatalogues() {
        val conf = """
            [Interface]
            PrivateKey = $priv
            Address = 10.164.20.6/32
            DNS = 10.128.0.1

            [Peer]
            PublicKey = $accountKey
            PresharedKey = $psk
            AllowedIPs = 0.0.0.0/0
            Endpoint = at.vpn.airdns.org:1637
        """.trimIndent()
        val cat = Catalog.parse(res("catalog_airvpn.json"), "wireguard").map { it.copy(wgpubkey = evilKey) }
        AirVpn.catalogue = { cat }
        val a = AirVpn.account(AirVpn.signed(conf))
        val place = cat.first().place
        val l = AirVpn.connect(Location("airvpn", country = place), AirVpn.signed(conf)) as app.unbraid.data.WgLink
        assertEquals(accountKey, l.peer.publicKey)
        assertEquals(a.presharedKey, l.peer.presharedKey)
        assertTrue(l.peer.endpoint.substringBefore(':') in cat.flatMap { it.ips })
    }

    // ---- AirVPN API sign-in

    private val apiKey = "0123456789abcdef".repeat(4)

    private class Dev(val id: String, var name: String, var status: String, var key: String, var ip: String, var readyAt: Int = 0, var deleteUndone: Boolean = false)

    /**
     * An AirVPN account as its API behaves: devices by id; an added one starts as "New device" and turns ready after
     * [readyAfter] lists, also when it was deleted before (AirVPN undoes such a delete); the generator answers a device's
     * own address, or the first (Default) device's for a name it doesn't know.
     */
    private inner class FakeAir(vararg names: String) {
        val devices = names.mapIndexed { i, n -> Dev("id$i", n, "ready", "k$i", "10.171.0.${i + 1}") }.toMutableList()
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        var refuseAdd = false
        var loseAdd = false
        var readyAfter = 2
        var generated: String? = null
        var lists = 0
        var renews = 0
        var refuseRenew = false
        var sessions = 0

        init {
            AirVpn.status = { res("airvpn_status.json") }
            AirVpn.pause = {}
            AirVpn.api = { service, key, query -> assertEquals(apiKey, key); answer(service, query.toMap()) }
        }

        fun names() = devices.map { it.name }

        private fun answer(service: String, q: Map<String, String>): String {
            calls += service to q
            return when (service) {
                "devices" -> when (q["action"]) {
                    null -> {
                        lists++
                        devices.filter { it.status == "create" && lists >= it.readyAt }.forEach { it.status = "ready"; it.key = "k-${it.id}-$renews"; it.ip = "10.171.9.${devices.indexOf(it) + 10 * renews}" }
                        """{"action":"list","devices":[${devices.joinToString(",") { """{"id":"${it.id}","name":"${it.name}","status":"${it.status}","wireguard_public_key":${it.key.ifEmpty { null }?.let { k -> "\"$k\"" }},"wireguard_ipv4":${it.ip.ifEmpty { null }?.let { i -> "\"$i\"" }}}""" }}],"result":"ok"}"""
                    }
                    "add" -> {
                        if (refuseAdd) return """{"error":"Too many devices"}"""
                        val d = Dev("new${devices.size}", "New device", "create", "", "", lists + readyAfter).also { devices += it }
                        if (loseAdd) throw IOException("reply lost")
                        """{"action":"add","id":"${d.id}","result":"ok"}"""
                    }
                    // As AirVPN: queued, the old key gone at once, a new key and address once ready.
                    "renew" -> if (refuseRenew) """{"error":"Not allowed"}""" else {
                        renews++; devices.first { it.id == q["id"] }.apply { status = "create"; key = ""; readyAt = lists + 2 }; """{"action":"renew","result":"ok"}"""
                    }
                    "modify" -> { devices.first { it.id == q["id"] }.name = q.getValue("name"); """{"action":"modify","result":"ok"}""" }
                    "delete" -> when (val d = devices.firstOrNull { it.id == q["id"] }) {
                        null -> """{"error":"Device unknown"}"""
                        else -> { if (d.status == "create") d.deleteUndone = true else devices.remove(d); """{"action":"delete","result":"ok"}""" }
                    }
                    else -> fail("action ${q["action"]}").let { "" }
                }
                "generator" -> generated ?: """
                    [Interface]
                    Address = ${(devices.firstOrNull { it.name == q["device"] } ?: devices.first()).ip}/32
                    PrivateKey = $priv
                    MTU = 1320
                    DNS = 10.128.0.1

                    [Peer]
                    PublicKey = $accountKey
                    PresharedKey = $psk
                    Endpoint = 198.51.100.9:1637
                    AllowedIPs = 0.0.0.0/0
                    PersistentKeepalive = 15
                """.trimIndent()
                "userinfo" -> """{"user":{"login":"x","premium":true,"expiration_unix":1795348998},"sessions":[${List(sessions) { i -> """{"device_name":"d$i","server_name":"S$i"}""" }.joinToString(",")}],"result":"ok"}"""
                else -> fail(service).let { "" }
            }
        }
    }

    /** Only unbraid's own device is renewed, when due; asked for once, finished where it stopped, dropped when refused or lost. */
    @Test fun airvpnRenewsItsOwnDeviceKey() {
        val air = FakeAir("Default")
        val blob = airSignIn()
        val a = AirVpn.account(blob)
        val day = 86_400_000L
        val due = a.keyAt + 8 * day
        assertTrue(a.keyAt > 0)
        assertTrue(AirVpn.options(blob).any { it.key == app.unbraid.data.ROTATE_OPT })
        val saved = mutableListOf<String>()
        val save = { b: String -> saved += b; true }
        assertFalse(AirVpn.rotate(blob, 7, a.keyAt + day, false, save))
        assertFalse(AirVpn.rotate(blob, null, a.keyAt + 400 * day, false, save)) // Never
        assertFalse(AirVpn.rotate(blob, 7, due, true, save)) // health rounds only finish
        assertTrue(saved.isEmpty() && air.renews == 0)
        assertTrue(AirVpn.rotate(blob, 7, due, false, save))
        assertEquals(1, air.renews)
        assertEquals(due, AirVpn.account(saved[0]).renewing)
        assertFalse(AirVpn.revoked(saved[0])) // mid-renewal: not taken for a device renewed elsewhere
        val b = AirVpn.account(saved[1])
        val dev = air.devices.last()
        assertEquals(listOf(dev.key, "${dev.ip}/32", 0L, due), listOf(b.deviceKey, b.address.single(), b.renewing, b.keyAt))
        assertTrue(b.deviceKey != a.deviceKey)
        assertFalse(AirVpn.revoked(saved[1]))
        // Cut short after asking (AirVPN still lists the old key for a while): looked for, never asked again.
        saved.clear(); air.renews = 0
        val asked = enc(b.copy(renewing = due))
        assertFalse(AirVpn.revoked(asked))
        try { AirVpn.rotate(asked, null, due + 60_000, true, save); fail() } catch (e: IOException) { }
        assertEquals(0, air.renews)
        assertTrue(saved.isEmpty())
        // Still the old key an hour on: the request was lost, the old key stays.
        assertFalse(AirVpn.rotate(asked, null, due + 2 * 3_600_000L, true, save))
        assertEquals(listOf(0L), saved.map { AirVpn.account(it).renewing })
        // The new key already there: fetched without asking again.
        saved.clear()
        assertTrue(AirVpn.rotate(enc(a.copy(renewing = due)), null, due, true, save))
        assertEquals(0, air.renews)
        assertEquals(dev.key, AirVpn.account(saved.single()).deviceKey)
        // Refused: kept until the list settles it; the old key still listed, the renewal is dropped an hour on.
        saved.clear()
        air.refuseRenew = true
        try { AirVpn.rotate(enc(b), 7, b.keyAt + 8 * day, false, save); fail() } catch (e: IOException) { }
        assertEquals(listOf(b.keyAt + 8 * day), saved.map { AirVpn.account(it).renewing })
        assertFalse(AirVpn.rotate(saved.single(), 7, b.keyAt + 9 * day, true, save))
        assertEquals(0L, AirVpn.account(saved.last()).renewing)
        // Deleted on the website meanwhile: signed out, renewal or not.
        air.devices.removeAt(air.devices.size - 1)
        assertTrue(AirVpn.revoked(enc(b.copy(renewing = due))))
        // One of the user's own devices is never renewed.
        val theirs = enc(a.copy(ownDevice = false))
        assertFalse(AirVpn.rotate(theirs, 1, a.keyAt + 400 * day, false, save))
        assertFalse(AirVpn.options(theirs).any { it.key == app.unbraid.data.ROTATE_OPT })
    }

    private fun enc(a: KeyProvider.Account) = app.unbraid.data.NordApi.json.encodeToString(KeyProvider.Account.serializer(), a)

    /** An AirVPN generator OpenVPN profile (shape from the live test's sample), reaching [remote] on port 443. */
    private fun airOvpn(remote: String, tcp: Boolean) = """
        client
        dev tun
        remote $remote 443
        proto ${if (tcp) "tcp" else "udp"}
        resolv-retry infinite
        nobind
        persist-key
        persist-tun
        auth-nocache
        verb 3
        ${if (tcp) "" else "explicit-exit-notify 5"}
        keepalive 10 60
        reneg-sec 3600
        push-peer-info
        setenv UV_IPV6 yes
        remote-cert-tls server
        tls-version-min 1.2
        data-ciphers AES-256-GCM:AES-256-CBC:AES-192-GCM:AES-192-CBC:AES-128-GCM:AES-128-CBC:CHACHA20-POLY1305
        data-ciphers-fallback AES-256-CBC
        auth SHA512
        <ca>
        -----BEGIN CERTIFICATE-----
        MIIBhTCCASugAwIBAgIUQ1Q7fFb2Y9bqMUZkO9X0m2lC1c0wCgYIKoZIzj0EAwIw
        -----END CERTIFICATE-----
        </ca>
        <cert>
        -----BEGIN CERTIFICATE-----
        MIIBhTCCASugAwIBAgIUQ1Q7fFb2Y9bqMUZkO9X0m2lC1c0wCgYIKoZIzj0EAwIw
        -----END CERTIFICATE-----
        </cert>
        <key>
        -----BEGIN TEST KEY-----
        MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgAAAAAAAAAAAAAAAA
        -----END TEST KEY-----
        </key>
        ${if (tcp) "<tls-crypt>" else "key-direction 1\n<tls-auth>"}
        -----BEGIN OpenVPN Static key V1-----
        00000000000000000000000000000000
        -----END OpenVPN Static key V1-----
        ${if (tcp) "</tls-crypt>" else "</tls-auth>"}
    """.trimIndent()

    /** An OpenVPN route takes the server WireGuard would, with a profile the generator makes for this device; checked to reach that server. */
    @Test fun airvpnOpenVpnRoutesUseAProfileMadeForTheDevice() {
        val air = FakeAir("Default")
        val blob = airSignIn()
        val status = AirVpn.parse(res("airvpn_status.json"), "")
        val fake = AirVpn.api
        var asked = emptyMap<String, String>()
        var remote: (Catalog.Entry, Boolean) -> String = { e, tcp -> if (tcp) e.ips[1] else e.ips[0] }
        AirVpn.api = { service, key, query ->
            val q = query.toMap()
            if (service == "generator" && q.getValue("protocols").startsWith("openvpn")) {
                asked = q
                val tcp = "tcp" in q.getValue("protocols")
                airOvpn(remote(status.first { it.serverName == q["servers"] }, tcp), tcp)
            } else fake(service, key, query)
        }
        val nl = Location("airvpn", country = "Netherlands")
        val tcp = AirVpn.connect(nl.copy(protocol = "openvpn_tcp"), blob) as app.unbraid.data.OvpnLink
        assertEquals(listOf("openvpn_3_tcp_443", tcp.server.name, AirVpn.account(blob).device), listOf(asked["protocols"], asked["servers"], asked["device"]))
        assertTrue("<tls-crypt>" in tcp.profile.config)
        assertEquals("Netherlands", status.first { it.serverName == tcp.server.name }.place)
        val udp = AirVpn.connect(nl.copy(protocol = "openvpn_udp"), blob) as app.unbraid.data.OvpnLink
        assertEquals("openvpn_1_udp_443", asked["protocols"])
        assertTrue("<tls-auth>" in udp.profile.config)
        // A device renamed on the website: the profile is asked for under its new name, found by its id.
        air.devices.first { it.name == AirVpn.account(blob).device }.name = "Renamed"
        AirVpn.connect(nl.copy(protocol = "openvpn_udp"), blob)
        assertEquals("Renamed", asked["device"])
        // WireGuard stays as it was: no OpenVPN profile asked for.
        asked = emptyMap()
        assertTrue(AirVpn.connect(nl, blob) is app.unbraid.data.WgLink)
        assertTrue(asked.isEmpty())
        // Automatic: WireGuard until it failed on this network, then OpenVPN TCP, and WireGuard again after a reset.
        val auto = app.unbraid.data.Route("a", nl.copy(protocol = app.unbraid.data.AUTO_PROTOCOL))
        app.unbraid.data.AutoProtocol.reset()
        assertEquals(null, app.unbraid.data.AutoProtocol.of(auto))
        assertTrue(AirVpn.connect(auto.loc, blob) is app.unbraid.data.WgLink) // asked directly: WireGuard
        val wg = AirVpn.connect(nl, blob)
        assertTrue(app.unbraid.data.AutoProtocol.matches(auto, wg))
        assertTrue(app.unbraid.data.AutoProtocol.toTcp("a"))
        assertFalse(app.unbraid.data.AutoProtocol.toTcp("a")) // once
        assertEquals("openvpn_tcp", app.unbraid.data.AutoProtocol.of(auto))
        assertFalse(app.unbraid.data.AutoProtocol.matches(auto, wg)) // the dead WireGuard tunnel isn't kept or reused
        val over = AirVpn.connect(auto.loc.copy(protocol = app.unbraid.data.AutoProtocol.of(auto)), blob)
        assertTrue(over is app.unbraid.data.OvpnLink && app.unbraid.data.AutoProtocol.matches(auto, over))
        assertEquals("openvpn_3_tcp_443", asked["protocols"])
        // Given a Via since: WireGuard, the only protocol Via takes; the TCP tunnel isn't kept.
        assertEquals(null, app.unbraid.data.AutoProtocol.of(auto.copy(via = "e")))
        assertFalse(app.unbraid.data.AutoProtocol.matches(auto.copy(via = "e"), over))
        assertTrue(app.unbraid.data.AutoProtocol.matches(auto.copy(via = "e"), wg))
        assertEquals("openvpn_tcp", app.unbraid.data.AutoProtocol.of(auto.copy(loc = auto.loc.copy(pinned = app.unbraid.data.Server("x", "h", "1.2.3.4", "k", 40))))) // same route, whatever its server details
        app.unbraid.data.AutoProtocol.reset()
        assertEquals(null, app.unbraid.data.AutoProtocol.of(auto))
        // A profile that would dial anything else is refused: another address, the other entry, another port or transport, a second remote.
        fun refused(r: (Catalog.Entry, Boolean) -> String, proto: String = "openvpn_tcp") { remote = r; assertFailsWith("doesn't match") { AirVpn.connect(nl.copy(protocol = proto), blob) } }
        refused({ _, _ -> "203.0.113.99" })
        refused({ e, _ -> e.ips[0] })
        refused({ e, _ -> e.ips[1] }, "openvpn_udp")
        refused({ e, _ -> "${e.ips[1]} 443\nremote 203.0.113.99" })
        refused({ e, _ -> "${e.ips[1]} 443\n--remote 203.0.113.99 443" })
        refused({ e, _ -> "${e.ips[1]} 1194" })
        refused({ e, _ -> "${e.ips[1]}\n#" }) // no port given (the template's 443 becomes a comment): OpenVPN would use 1194
        refused({ e, _ -> "${e.ips[1]} 443 udp" })
        refused({ e, _ -> "${e.ips[1]}\n<connection>\nremote 203.0.113.99 443\n</connection>" })
        remote = { e, tcp -> if (tcp) e.ips[1] else e.ips[0] }
        AirVpn.api = { service, key, query -> if (service == "generator" && query.toMap().getValue("protocols").startsWith("openvpn")) """{"error":"Server unknown"}""" else fake(service, key, query) }
        assertFailsWith("couldn't answer") { AirVpn.connect(nl.copy(protocol = "openvpn_tcp"), blob) }
        assertFailsWith("doesn't offer") { AirVpn.connect(nl.copy(protocol = "openvpn_xyz"), blob) }
        // The device is gone: sign in again, no profile asked for.
        AirVpn.api = fake
        air.devices.clear()
        assertFailsWith("Sign in to AirVPN again") { AirVpn.connect(nl.copy(protocol = "openvpn_udp"), blob) }
        // A pasted config has no API key: it says so, final (no cached tunnel stands in), and asks AirVPN nothing.
        var calls = 0
        AirVpn.api = { _, _, _ -> calls++; "" }
        val pasted = AirVpn.signed(conf("10.164.20.6/32"))
        val e = runCatching { AirVpn.connect(nl.copy(protocol = "openvpn_udp"), pasted) }.exceptionOrNull()
        assertTrue(e is app.unbraid.data.Unsupported && "API key" in e.message.orEmpty())
        assertEquals(0, calls)
    }

    /** OpenVPN TCP checks the server's alternative entry: with only the catalogue (no entries), it says so instead of guessing. */
    @Test fun airvpnOpenVpnTcpNeedsTheStatusList() {
        FakeAir("Default")
        val blob = airSignIn()
        AirVpn.status = { throw IOException("offline") }
        AirVpn.catalogue = { Catalog.parse(res("catalog_airvpn.json"), "wireguard") }
        assertFailsWith("can't be read right now") { AirVpn.connect(Location("airvpn", country = "Netherlands", protocol = "openvpn_tcp"), blob) }
    }

    private fun assertFailsWith(text: String, f: () -> Unit) {
        val e = runCatching(f).exceptionOrNull() ?: return fail("expected a failure with: $text")
        assertTrue("${e.message}", e.message.orEmpty().contains(text))
    }

    private fun airSignIn(device: String? = null) = AirVpn.signIn(mapOf("key" to " $apiKey\n") + listOfNotNull(device?.let { "device" to it }))

    /** Like other providers' device slots: added, named, used, removed again on sign-out; the user's devices untouched. */
    @Test fun airvpnApiKeyMakesItsOwnDeviceAndRemovesItOnSignOut() {
        val air = FakeAir("Default")
        val blob = airSignIn()
        val a = AirVpn.account(blob)
        assertEquals(listOf("Default", "unbraid"), air.names())
        val made = air.devices.last()
        assertEquals(listOf("${made.ip}/32"), a.address)
        assertEquals(listOf(apiKey, "unbraid", made.key, made.id, true), listOf(a.apiKey, a.device, a.deviceKey, a.deviceId, a.ownDevice))
        assertEquals(Triple(accountKey, psk, 1637), Triple(a.publicKey, a.presharedKey, a.port))
        assertEquals(1, air.calls.count { it.second["action"] == "add" })
        val gen = air.calls.single { it.first == "generator" }.second
        assertEquals(listOf("unbraid", "wireguard_1_udp_1637"), listOf(gen["device"], gen["protocols"]))
        assertTrue(gen["servers"] in AirVpn.parse(res("airvpn_status.json"), "").map { it.serverName })
        assertEquals("Its own device, unbraid, removed when you sign out. See all your devices in the AirVPN Client Area", AirVpn.device(blob)?.line)
        assertTrue(AirVpn.signOut(blob))
        assertEquals(listOf("Default"), air.names())
        // Already gone (removed on the website): still released.
        assertTrue(AirVpn.signOut(blob))
    }

    @Test fun airvpnOwnDeviceGetsAFreeName() {
        val air = FakeAir("Default", "unbraid")
        assertEquals("unbraid 2", AirVpn.account(airSignIn()).device)
        assertEquals(listOf("Default", "unbraid", "unbraid 2"), air.names())
    }

    /**
     * After a lost reply the new device could be another app's: never renamed, used or removed. One that doesn't get ready
     * is removed again, or the user is told it is left.
     */
    @Test fun airvpnOwnDeviceIsNeverGuessedAndIsCleanedUpWhenNotReady() {
        val air = FakeAir("Default").apply { loseAdd = true }
        val lost = runCatching { airSignIn() }.exceptionOrNull()
        assertTrue(lost is app.unbraid.vpn.Fail && "Client Area" in lost.message!!)
        assertEquals(listOf("Default", "New device"), air.names())
        assertTrue(air.calls.none { it.second["action"] in setOf("modify", "delete") })

        // Ready only after unbraid gave up: removed once it is, not before (a delete then would be undone).
        val slow = FakeAir("Default").apply { readyAfter = 35 }
        assertTrue(runCatching { airSignIn() }.exceptionOrNull() is app.unbraid.vpn.Fail)
        assertEquals(listOf("Default"), slow.names())
        assertTrue(slow.calls.filter { it.second["action"] == "delete" }.size == 1)

        val broken = FakeAir("Default").apply { generated = """{"error": "Unexpected, zero files in package. Contact our staff, thanks."}""" }
        val e = runCatching { airSignIn() }.exceptionOrNull()
        assertTrue(e is app.unbraid.vpn.Fail && e !is app.unbraid.data.AuthException)
        assertEquals(listOf("Default"), broken.names())

        // Removing it fails too: the user is told which device is left.
        val stuck = FakeAir("Default").apply { generated = """{"error": "Unexpected"}""" }
        AirVpn.api.let { real -> AirVpn.api = { s, k, q -> if (q.toMap()["action"] == "delete") """{"error":"Try later"}""" else real(s, k, q) } }
        val left = runCatching { airSignIn() }.exceptionOrNull()
        assertTrue(left is app.unbraid.vpn.Fail && "unbraid" in left.message!! && "Client Area" in left.message!!)
        assertEquals(listOf("Default", "unbraid"), stuck.names())
    }

    /** Only when AirVPN refuses a new device: the user picks one of theirs, which sign-out leaves alone. */
    @Test fun airvpnRefusedDeviceAsksWhichOfTheirsToUse() {
        val air = FakeAir("Default", "Phone").apply { refuseAdd = true }
        val asked = runCatching { airSignIn() }.exceptionOrNull()
        assertTrue(asked is app.unbraid.data.Challenge && asked.field.key == "device" && "Default, Phone" in asked.message!!)
        assertTrue(runCatching { airSignIn("Tablet") }.exceptionOrNull() is app.unbraid.data.Challenge)
        assertTrue(runCatching { airSignIn("") }.exceptionOrNull() is app.unbraid.data.Challenge)
        air.calls.clear()
        val blob = airSignIn("phone")
        val a = AirVpn.account(blob)
        assertEquals(listOf("10.171.0.2/32"), a.address)
        assertEquals(listOf("Phone", "k1", "id1", false), listOf(a.device, a.deviceKey, a.deviceId, a.ownDevice))
        // Answering the question never tries to add a device again.
        assertTrue(air.calls.none { it.second["action"] == "add" })
        assertEquals("Uses your device Phone. See all your devices in the AirVPN Client Area", AirVpn.device(blob)?.line)
        assertTrue(AirVpn.signOut(blob))
        assertEquals(listOf("Default", "Phone"), air.names())

        FakeAir("Default").apply { refuseAdd = true }
        assertEquals("Default", AirVpn.account(airSignIn()).device)
    }

    /** The generator quietly answers with the Default device for a name it doesn't know: such a config is refused. */
    @Test fun airvpnConfigMustBeTheDevicesOwn() {
        val air = FakeAir("Default", "Phone").apply { refuseAdd = true }
        air.devices[1].name = "Phone"
        AirVpn.api.let { real -> AirVpn.api = { s, k, q -> real(s, k, if (s == "generator") q.map { if (it.first == "device") it.first to "renamed" else it } else q) } }
        assertTrue(runCatching { airSignIn("Phone") }.exceptionOrNull() is app.unbraid.vpn.Fail)
    }

    @Test fun airvpnApiErrorsSayWhatToDo() {
        FakeAir().apply { refuseAdd = true }
        assertTrue(runCatching { airSignIn() }.exceptionOrNull() is app.unbraid.data.AuthException)
        AirVpn.api = { _, _, _ -> """{"error": "Not authorized"}""" }
        val refused = runCatching { airSignIn() }.exceptionOrNull()
        assertTrue(refused is app.unbraid.data.AuthException && "API key" in refused.message!!)
    }

    /** Revoked only when the list was read and the device is gone or has a new key; a pasted .conf is never checked. */
    @Test fun airvpnDeviceDeletedOrRenewedIsRevoked() {
        val air = FakeAir("Default")
        val blob = airSignIn()
        assertTrue(!AirVpn.revoked(blob))
        air.devices.last().key = "renewed"
        assertTrue(AirVpn.revoked(blob))
        air.devices.removeAt(1)
        assertTrue(AirVpn.revoked(blob))
        AirVpn.api = { _, _, _ -> throw IOException("offline") }
        assertTrue(!AirVpn.revoked(blob))
        AirVpn.api = { _, _, _ -> """{"error": "Not authorized"}""" }
        assertTrue(!AirVpn.revoked(blob))
        AirVpn.api = { _, _, _ -> fail("a pasted config has no API key"); "" }
        val pasted = AirVpn.signed(AirVpn.account(blob).let { conf(it.address.single()) })
        assertTrue(!AirVpn.revoked(pasted))
        assertTrue(AirVpn.signOut(pasted))
        assertEquals(null, AirVpn.plan(pasted))
    }

    /** The page counts every app's open AirVPN connections, from the account's sessions; unknown when AirVPN can't be asked. */
    @Test fun airvpnCountsEveryAppsConnections() {
        val air = FakeAir("Default")
        val blob = airSignIn()
        assertEquals(0, AirVpn.inUse(blob))
        air.sessions = 3
        assertEquals(3, AirVpn.inUse(blob))
        // AirVPN answers a refused key as HTTP 200 with an error: not known, never 0 in use; no plan line either.
        AirVpn.api = { _, _, _ -> """{"error":"Not authorized","user":{"login":"x","premium":true,"expiration_unix":1795348998},"sessions":[]}""" }
        assertEquals(null, AirVpn.inUse(blob))
        assertEquals(null, AirVpn.plan(blob))
        AirVpn.api = { _, _, _ -> """{"user":{"login":"x"},"result":"ok"}""" }
        assertEquals(null, AirVpn.inUse(blob))
        AirVpn.api = { _, _, _ -> throw IOException("offline") }
        assertEquals(null, AirVpn.inUse(blob))
        // The device's own line needs no request, nor does a pasted config (it has no API key).
        var asked = 0
        AirVpn.api = { _, _, _ -> asked++; "" }
        assertEquals("Its own device, unbraid, removed when you sign out. See all your devices in the AirVPN Client Area", AirVpn.device(blob)?.line)
        assertEquals(null, AirVpn.inUse(AirVpn.signed(AirVpn.account(blob).let { conf(it.address.single()) })))
        assertEquals(0, asked)
    }

    @Test fun airvpnPlanComesFromTheAccount() {
        FakeAir("Default")
        assertEquals(app.unbraid.data.Plans.line(1795348998), AirVpn.plan(airSignIn()))
        assertTrue(app.unbraid.data.Plans.line(1795348998, now = 0).startsWith("Paid until "))
        assertTrue(app.unbraid.data.Plans.line(1795348998, now = 1795348999).startsWith("Ended on "))
        assertEquals("No active plan", app.unbraid.data.Plans.line(null))
    }

    private fun conf(address: String, port: Int = 1637) = """
        [Interface]
        PrivateKey = $priv
        Address = $address

        [Peer]
        PublicKey = $accountKey
        PresharedKey = $psk
        AllowedIPs = 0.0.0.0/0
        Endpoint = at.vpn.airdns.org:$port
    """.trimIndent()

    /** Every port, the account's first, 51820 tied to entry 3; both entries from AirVPN's list, none from the catalogue's. */
    @Test fun airvpnTunnelsCarryPortsAndEntries() {
        val entries = AirVpn.parse(res("airvpn_status.json"), accountKey)
        val l = AirVpn.link(Location("airvpn", country = "Netherlands"), AirVpn.account(AirVpn.signed(conf("10.164.20.6/32")))) { entries }
        assertEquals(listOf(1637, 47107, 51820), l.ports)
        val e = entries.first { it.ipv4 == l.server.ip }
        assertEquals(e.ips, l.entries)
        assertTrue(l.entries.size == 2 && l.entries[0] == l.server.ip && l.entries[1] != l.server.ip)
        assertEquals(listOf(47107, 1637), AirVpn.link(Location("airvpn", country = "Netherlands"), AirVpn.account(AirVpn.signed(conf("10.164.20.6/32", 47107)))) { entries }.ports.take(2))
        AirVpn.catalogue = { Catalog.parse(res("catalog_airvpn.json"), "wireguard") }
        val offline = AirVpn.connect(Location("airvpn"), AirVpn.signed(conf("10.164.20.6/32"))) as app.unbraid.data.WgLink
        assertTrue(offline.entries.isEmpty())
        assertEquals(mapOf(51820 to 2), offline.portEntry)
    }

    @Test fun surfsharkDropsMalformedKeysBeforeKeepingAList() {
        val bad = liveBody.replace(live.first().wgpubkey!!, "not-a-key")
        assertTrue(Surfshark.parse(bad).none { it.hostname == live.first().hostname })
    }

    @Test fun savedTunnelsNeedATrustedKey() {
        val account = Surfshark.signed(priv)
        surfsharkAt(live)
        val good = Surfshark.link(Location("surfshark", country = live.first().place), Surfshark.account(account)) { live }
        // No own list kept yet: a saved Surfshark tunnel can't be vouched for.
        assertTrue(!Surfshark.trusts(good, account))
        Surfshark.snapshot = Snapshot.memory(liveBody)
        assertTrue(Surfshark.trusts(good, account))
        val swapped = good.copy(peer = good.peer.copy(publicKey = evilKey), server = good.server.copy(publicKey = evilKey))
        assertTrue(!Surfshark.trusts(swapped, account))
    }

    @Test fun airvpnSavedTunnelsMustUseTheAccountKey() {
        val conf = """
            [Interface]
            PrivateKey = $priv
            Address = 10.164.20.6/32

            [Peer]
            PublicKey = $accountKey
            PresharedKey = $psk
            AllowedIPs = 0.0.0.0/0
            Endpoint = at.vpn.airdns.org:1637
        """.trimIndent()
        val blob = AirVpn.signed(conf)
        val entries = AirVpn.parse(res("airvpn_status.json"), accountKey)
        val l = AirVpn.link(Location("airvpn", country = entries.first().place), AirVpn.account(blob)) { entries }
        assertTrue(AirVpn.trusts(l, blob))
        val old = l.copy(peer = l.peer.copy(publicKey = evilKey), server = l.server.copy(publicKey = evilKey))
        assertTrue(!AirVpn.trusts(old, blob))
        // A pinned server whose saved key is the catalogue's still connects with the account key.
        val pinned = AirVpn.link(Location("airvpn", country = entries.first().place, pinned = l.server.copy(publicKey = evilKey)), AirVpn.account(blob)) { entries }
        assertEquals(accountKey, pinned.peer.publicKey)
    }

    @Test fun surfsharkTrustsTheServerKeyOfTheUsersOwnConf() {
        val conf = """
            [Interface]
            PrivateKey = $priv
            Address = 10.14.0.2/16

            [Peer]
            PublicKey = ${live.first().wgpubkey}
            AllowedIPs = 0.0.0.0/0
            Endpoint = ${live.first().hostname}:51820
        """.trimIndent()
        val account = Surfshark.signed(conf)
        surfsharkAt(live)
        val l = Surfshark.link(Location("surfshark", country = live.first().place), Surfshark.account(account)) { live }
        // Right after an upgrade, with no own list kept yet: the .conf's own server is still trusted.
        assertTrue(Surfshark.trusts(l, account))
    }

    @Test fun aSnapshotThatCantBeSavedIsStillHeldInMemory() {
        val s = Snapshot({ null }, { throw IOException("disk full") })
        s.write("list")
        assertEquals("list", s.read())
    }
}
