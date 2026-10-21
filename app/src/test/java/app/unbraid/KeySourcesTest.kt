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
