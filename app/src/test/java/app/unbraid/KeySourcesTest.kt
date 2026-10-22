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
                        devices.filter { it.status == "create" && lists >= it.readyAt }.forEach { it.status = "ready"; it.key = "k-${it.id}"; it.ip = "10.171.9.${devices.indexOf(it)}" }
                        """{"action":"list","devices":[${devices.joinToString(",") { """{"id":"${it.id}","name":"${it.name}","status":"${it.status}","wireguard_public_key":${it.key.ifEmpty { null }?.let { k -> "\"$k\"" }},"wireguard_ipv4":${it.ip.ifEmpty { null }?.let { i -> "\"$i\"" }}}""" }}],"result":"ok"}"""
                    }
                    "add" -> {
                        if (refuseAdd) return """{"error":"Too many devices"}"""
                        val d = Dev("new${devices.size}", "New device", "create", "", "", lists + readyAfter).also { devices += it }
                        if (loseAdd) throw IOException("reply lost")
                        """{"action":"add","id":"${d.id}","result":"ok"}"""
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
                "userinfo" -> """{"user":{"login":"x","premium":true,"expiration_unix":1795348998},"result":"ok"}"""
                else -> fail(service).let { "" }
            }
        }
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

    /** Both ports, the account's first; both entries from AirVPN's list, none from the catalogue's. */
    @Test fun airvpnTunnelsCarryPortsAndEntries() {
        val entries = AirVpn.parse(res("airvpn_status.json"), accountKey)
        val l = AirVpn.link(Location("airvpn", country = "Netherlands"), AirVpn.account(AirVpn.signed(conf("10.164.20.6/32")))) { entries }
        assertEquals(listOf(1637, 47107), l.ports)
        val e = entries.first { it.ipv4 == l.server.ip }
        assertEquals(e.ips, l.entries)
        assertTrue(l.entries.size == 2 && l.entries[0] == l.server.ip && l.entries[1] != l.server.ip)
        assertEquals(listOf(47107, 1637), AirVpn.link(Location("airvpn", country = "Netherlands"), AirVpn.account(AirVpn.signed(conf("10.164.20.6/32", 47107)))) { entries }.ports)
        AirVpn.catalogue = { Catalog.parse(res("catalog_airvpn.json"), "wireguard") }
        val offline = AirVpn.connect(Location("airvpn"), AirVpn.signed(conf("10.164.20.6/32"))) as app.unbraid.data.WgLink
        assertTrue(offline.entries.isEmpty())
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
