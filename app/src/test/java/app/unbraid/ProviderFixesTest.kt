package app.unbraid

import app.unbraid.data.AirVpn
import app.unbraid.data.Catalog
import app.unbraid.data.ExpressVpn
import app.unbraid.data.Ivpn
import app.unbraid.data.KeyProvider
import app.unbraid.data.Location
import app.unbraid.data.Mullvad
import app.unbraid.data.Nord
import app.unbraid.data.NordApi
import app.unbraid.data.Pia
import app.unbraid.data.Proton
import app.unbraid.data.ProtonFree
import app.unbraid.data.Server
import app.unbraid.data.Surfshark
import app.unbraid.data.Windscribe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.LocalDateTime

class ProviderFixesTest {
    private val priv = "dW5icmFpZC10ZXN0LXByaXZhdGUta2V5LTAwMDAwMDE="
    private val psk = "dW5icmFpZC10ZXN0LXByZXNoYXJlZC1rZXktMDAwMDE="
    private val key1 = "vOgsnvam/Xc6w8zfQeGcynOr8RMxYzpki3b0EaOkEoI="
    private val key2 = "PyLCXAQT8KkM4T+dUsOQfn+Ub3pGxfGlxkIApuig+hk="
    private val account = KeyProvider.Account(priv, listOf("10.2.0.2/32"), listOf("10.2.0.1"), 51820, 1420)

    // ---- load is shown only where it is real

    @Test fun onlyProvidersWithRealLoadSayFastest() {
        assertEquals(setOf("nord", "ivpn", "surfshark", "airvpn"), listOf(Nord, Ivpn, Surfshark, AirVpn).filter { it.hasLoad }.map { it.id }.toSet())
        assertTrue(listOf(Mullvad, Pia, Proton, ProtonFree, ExpressVpn, Windscribe).none { it.hasLoad })
    }

    @Test fun liveLoadIsIngestedAndRanksTheCatalogue() {
        val clusters = Surfshark.parse(
            """[{"country":"Netherlands","location":"Amsterdam","connectionName":"nl-ams.prod.surfshark.com","pubKey":"$key1","load":41},
                {"country":"Netherlands","location":"Amsterdam","connectionName":"nl-ams2.prod.surfshark.com","pubKey":"$key1","load":6},
                {"country":"Netherlands","location":"Amsterdam","connectionName":"nl-ams3.prod.surfshark.com","pubKey":"$key1"}]""",
        )
        assertEquals(listOf(41, 6, -1), clusters.map { it.load })
        Surfshark.resolve = { listOf("203.0.113.7") }
        val l = Surfshark.link(Location("surfshark", country = "Netherlands"), account) { clusters }
        assertEquals("nl-ams2.prod.surfshark.com", l.server.host)
        assertEquals(6, l.server.load)

        val air = AirVpn.parse(
            """{"servers":[{"public_name":"A","country_name":"Austria","country_code":"at","location":"Vienna","continent":"Europe","ip_v4_in1":"1.2.3.4","health":"ok","currentload":70},
                {"public_name":"B","country_name":"Austria","country_code":"at","location":"Vienna","continent":"Europe","ip_v4_in1":"1.2.3.5","health":"ok","currentload":12}]}""",
            key1,
        )
        assertEquals(listOf(70, 12), air.map { it.load })
        val sv = AirVpn.link(Location("airvpn", country = "Austria"), account) { air }.server
        assertEquals("B", sv.name)
        assertEquals(12, sv.load)
        assertEquals(-1, Catalog.server(Catalog.Entry("wireguard", "Austria", ips = listOf("1.2.3.4"))).load)
    }

    // ---- pinned servers are refreshed

    private val relays = """{"locations":{"se-got":{"country":"Sweden","city":"Gothenburg"}},"wireguard":{"relays":[
        {"hostname":"se-got-wg-001","location":"se-got","active":true,"ipv4_addr_in":"10.0.0.9","public_key":"$key2","weight":1},
        {"hostname":"se-got-wg-002","location":"se-got","active":false,"ipv4_addr_in":"10.0.0.10","public_key":"$key2","weight":1}]}}"""
    private val mullvad = Mullvad.Account("1234567890123456", priv, "10.66.12.34/32", "dev-1")

    @Test fun mullvadPinnedServerUsesTheFreshAddressAndKey() {
        val stale = Server("se-got-wg-001", "se-got-wg-001", "10.0.0.1", key1, 0, "Gothenburg")
        val l = Mullvad.link(Mullvad.parse(relays), Location("mullvad", pinned = stale), mullvad)
        assertEquals("10.0.0.9:51820", l.peer.endpoint)
        assertEquals(key2, l.peer.publicKey)
        assertEquals(key2, l.server.publicKey)
        // The list is down: the pinned values stand in.
        assertEquals("10.0.0.1:51820", Mullvad.link(null, Location("mullvad", pinned = stale), mullvad).peer.endpoint)
        // A relay the list no longer offers is an error, not a stale endpoint.
        for (gone in listOf(stale.copy(name = "se-got-wg-002", host = "se-got-wg-002", ip = "10.0.0.10"), stale.copy(name = "se-got-wg-777", host = "se-got-wg-777"))) {
            try { Mullvad.link(Mullvad.parse(relays), Location("mullvad", pinned = gone), mullvad); fail() } catch (e: IOException) { }
        }
    }

    @Test fun keyProviderPinnedServerUsesTheFreshEntry() {
        val old = Catalog.Entry("wireguard", "Germany", city = "Berlin", serverName = "DE#1", hostname = "node-de-01.protonvpn.net", wgpubkey = key1, ips = listOf("1.1.1.1"))
        val fresh = old.copy(wgpubkey = key2, ips = listOf("2.2.2.2"))
        val pinned = Catalog.server(old)
        val l = Proton.link(Location("proton", pinned = pinned), account) { listOf(fresh) }
        assertEquals("2.2.2.2:51820", l.peer.endpoint)
        assertEquals(key2, l.peer.publicKey)
        assertEquals("DE#1", l.server.name)
        // Moved to a new host name but the same IP: still found.
        assertEquals(key2, Proton.link(Location("proton", pinned = pinned.copy(host = "old-name")), account) { listOf(fresh.copy(ips = listOf("1.1.1.1"))) }.peer.publicKey)
        assertEquals("1.1.1.1:51820", Proton.link(Location("proton", pinned = pinned), account) { throw IOException("offline") }.peer.endpoint)
        try { Proton.link(Location("proton", pinned = pinned.copy(name = "DE#9", host = "x")), account) { listOf(fresh) }; fail() } catch (e: IOException) { }
    }

    @Test fun airvpnPinnedServerIsFoundByNameBecauseHostNamesAreShared() {
        val body = """{"servers":[{"public_name":"A","country_name":"Austria","country_code":"at","location":"Vienna","continent":"Europe","ip_v4_in1":"1.2.3.4","health":"ok"},
            {"public_name":"B","country_name":"Austria","country_code":"at","location":"Vienna","continent":"Europe","ip_v4_in1":"1.2.3.5","health":"ok"}]}"""
        val list = AirVpn.parse(body, key1)
        val pinned = Server("B", "at.vpn.airdns.org", "9.9.9.9", key1, 0)
        assertEquals("1.2.3.5:1637", AirVpn.link(Location("airvpn", pinned = pinned), account.copy(port = 1637)) { list }.peer.endpoint)
    }

    // ---- NordVPN

    @Test fun groupCountriesAreReadFromTheFullServerList() {
        val body = """[{"hostname":"a","locations":[{"country":{"code":"US"}}]},{"hostname":"b","locations":[{"country":{"code":"NL"}}]},{"hostname":"c","locations":[{"country":{"code":"US"}}]}]"""
        assertEquals(setOf("US", "NL"), NordApi.countryCodes(body))
    }

    @Test fun dedicatedServersComeFromTheAccountsOwnService() {
        val services = """[{"ID":1,"expires_at":"2099-01-01 00:00:00","service":{"id":1,"name":"VPN"},"details":{}},
            {"ID":2,"expires_at":"2099-01-01 00:00:00","service":{"id":11,"name":"Dedicated IP"},"details":{"servers":[{"id":947235},{"id":947238}]}},
            {"ID":3,"expires_at":"2020-01-01 00:00:00","service":{"id":11,"name":"Dedicated IP"},"details":{"servers":[{"id":111}]}},
            {"ID":4,"expires_at":"2099-01-01 00:00:00","service":{"id":33,"name":"Dedicated server"},"details":{"servers":[{"id":222}]}}]"""
        val ids = NordApi.dedicatedIds(services, LocalDateTime.of(2026, 10, 18, 12, 0))
        assertEquals(setOf(947235L, 947238L), ids)
        assertTrue(NordApi.dedicatedIds("[]").isEmpty())

        fun sv(id: Long, host: String) = """{"id":$id,"name":"United States #$id","hostname":"$host.nordvpn.com","station":"89.187.175.$id","load":3,
            "locations":[{"country":{"code":"US","city":{"name":"Dallas"}}}],
            "technologies":[{"identifier":"wireguard_udp","metadata":[{"name":"public_key","value":"$key1"}]}]}"""
        val all = "[${sv(947235, "us2943")},${sv(947238, "us2944")},${sv(5, "us1")}]"
        val mine = NordApi.withIds(all, ids, true)
        assertEquals(listOf("us2943.nordvpn.com", "us2944.nordvpn.com"), mine.map { it.host })
        assertEquals("Dallas", mine.first().city)
        assertEquals(key1, mine.first().publicKey)
    }

    // ---- Windscribe, AirVPN

    private val wsConf = { endpoint: String -> """
        [Interface]
        PrivateKey = $priv
        Address = 100.96.12.34/32
        DNS = 10.255.255.1

        [Peer]
        PublicKey = $key1
        PresharedKey = $psk
        AllowedIPs = 0.0.0.0/0
        Endpoint = $endpoint
    """.trimIndent() }

    @Test fun windscribeStaticIpConfigsAreRefused() {
        Windscribe.nodeHosts = { setOf("us-central-117.whiskergalaxy.com") }
        Windscribe.signIn(mapOf("config" to wsConf("us-central-117.whiskergalaxy.com:443")))
        for (endpoint in listOf("203.0.113.5:443", "static-1.whiskergalaxy.com:443", "unlisted-1.whiskergalaxy.com:443")) {
            try { Windscribe.signIn(mapOf("config" to wsConf(endpoint))); fail(endpoint) } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("Your own servers")); assertFalse(e.message!!.contains(priv))
            }
        }
        // The list can't be read: only the endpoint's shape is checked.
        Windscribe.nodeHosts = { null }
        Windscribe.signIn(mapOf("config" to wsConf("unlisted-1.whiskergalaxy.com:443")))
    }

    @Test fun airvpnRefusesAmneziaConfigs() {
        val conf = { extra: String -> """
            [Interface]
            PrivateKey = $priv
            Address = 10.164.20.6/32
            DNS = 10.128.0.1
            $extra

            [Peer]
            PublicKey = $key1
            PresharedKey = $psk
            AllowedIPs = 0.0.0.0/0
            Endpoint = at.vpn.airdns.org:1637
        """.trimIndent() }
        AirVpn.signIn(mapOf("key" to conf("")))
        for (line in listOf("Jc = 4", "Jmin = 8", "Jmax=80", "S1 = 15", "S2 = 18", "H1 = 123456", "H4 = 5", "I1 = <b 0x00>", "i5 = x")) {
            try { AirVpn.signIn(mapOf("key" to conf(line))); fail(line) } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("Amnezia")); assertFalse(e.message!!.contains(priv))
            }
        }
    }
}
