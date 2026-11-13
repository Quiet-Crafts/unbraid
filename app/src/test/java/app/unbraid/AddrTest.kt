package app.unbraid

import app.unbraid.data.Addr
import app.unbraid.data.AirVpn
import app.unbraid.data.Http
import app.unbraid.data.Ivpn
import app.unbraid.data.Mullvad
import app.unbraid.data.NordApi
import app.unbraid.data.Pia
import app.unbraid.data.Windscribe
import app.unbraid.vpn.Fail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URL
import java.net.URLConnection

/** Addresses and keys a provider's reply hands over are checked before they are used or saved. */
class AddrTest {
    private val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val bad = listOf("89.187.175.947235", "10.0.0.9", "192.168.1.1", "127.0.0.1", "169.254.1.1", "100.64.0.1", "0.1.2.3", "224.0.0.1", "255.255.255.255",
        "1.2.3.4/32", " 1.2.3.4", "01.2.3.4", "host.example", "", "1.2.3")

    private fun failsAsUnexpected(f: () -> Unit) = try { f(); fail("accepted") } catch (e: Fail) { assertTrue(e.message!!.startsWith("Unexpected reply")) }

    @Test fun serverAddresses() {
        for (ip in listOf("1.2.3.4", "185.65.135.9", "89.187.175.235", "223.255.255.254")) assertTrue(ip, Addr.server(ip))
        for (ip in bad) assertFalse(ip, Addr.server(ip))
    }

    @Test fun assignedAddresses() {
        assertEquals("10.68.1.2", Addr.assigned("10.68.1.2/32"))
        assertEquals("172.16.1.2", Addr.assigned("172.16.1.2"))
        for (ip in listOf("10.68.1.2.5", "10.68.1.256", "fc00::2/128", " 10.1.1.1", "", "x")) assertNull(ip, Addr.assigned(ip))
    }

    @Test fun mullvadDropsRelaysAtLocalAddressesAndRefusesAMalformedDeviceAddress() {
        val l = Mullvad.parse("""{"locations":{"se-got":{"country":"Sweden","city":"Gothenburg"}},"wireguard":{"relays":[
            {"hostname":"a","location":"se-got","ipv4_addr_in":"192.168.1.1","public_key":"$key"},
            {"hostname":"b","location":"se-got","ipv4_addr_in":"185.65.135.9","public_key":"$key"}]}}""")
        assertEquals(listOf("b"), l.usable.map { it.hostname })
        failsAsUnexpected { Mullvad.device(201 to """{"id":"d","pubkey":"k","ipv4_address":"10.68.1.2.5/32"}""", "1234567890123456", key) }
    }

    @Test fun ivpnDropsHostsAtLocalAddressesAndRefusesAMalformedTunnelAddress() {
        val l = Ivpn.parse("""{"wireguard":[{"gateway":"nl.gw","country_code":"NL","country":"Netherlands","city":"Amsterdam","hosts":[
            {"hostname":"a","host":"10.1.2.3","public_key":"$key","local_ip":"172.16.0.1/12"},
            {"hostname":"b","host":"1.2.3.4","public_key":"$key","local_ip":"172.16.0.1/12"}]}]}""")
        assertEquals(listOf("b"), l.wireguard.single().hosts.map { it.hostname })
        failsAsUnexpected {
            Ivpn.session(200 to """{"status":200,"token":"tok","service_status":{"is_active":true},"wireguard":{"status":200,"ip_address":"172.16.1"}}""", "i-ABCD-EFGH-IJKL", "priv")
        }
    }

    @Test fun airvpnAndWindscribeDropServersAtLocalAddresses() {
        val air = AirVpn.parse("""{"servers":[
            {"public_name":"A","country_name":"Austria","country_code":"at","location":"Vienna","ip_v4_in1":"127.0.0.1","health":"ok"},
            {"public_name":"B","country_name":"Austria","country_code":"at","location":"Vienna","ip_v4_in1":"1.2.3.5","ip_v4_in3":"10.0.0.1","health":"ok"}]}""", key)
        assertEquals(listOf("B"), air.map { it.serverName })
        assertEquals(listOf("1.2.3.5"), air.single().ips)
        val ws = Windscribe.rows(Windscribe.parse("""{"data":[{"name":"X","country_code":"nl","status":1,"groups":[{"city":"A","wg_pubkey":"$key","nodes":[
            {"hostname":"x-1.w.com","ip3":"999.2.3.4","health":1},{"hostname":"x-2.w.com","ip3":"192.168.0.2","health":1},{"hostname":"x-3.w.com","ip3":"1.2.3.5","health":1}]}]}]}"""))
        assertEquals(listOf("x-3.w.com"), ws.map { it.node.hostname })
    }

    @Test fun nordDropsServersAtMalformedAddresses() {
        fun sv(host: String, station: String) = """{"name":"$host","hostname":"$host.nordvpn.com","station":"$station","load":3,
            "technologies":[{"identifier":"wireguard_udp","metadata":[{"name":"public_key","value":"$key"}]}]}"""
        val list = NordApi.parseServers("[${sv("us1", "89.187.175.947235")},${sv("us2", "10.5.0.1")},${sv("us3", "89.187.175.235")}]")
        assertEquals(listOf("us3.nordvpn.com"), list.map { it.host })
    }

    @Test fun nordRefusesAMalformedWireguardKeyAtSignIn() {
        val original = Http.open
        fun reply(k: String) { Http.open = { u: URL -> FakeHttps(u, 200, """{"nordlynx_private_key":"$k","username":"u","password":"p"}""") as URLConnection } }
        try {
            reply(key)
            assertEquals(key, NordApi.credentials("t").first)
            reply("short")
            failsAsUnexpected { NordApi.credentials("t") }
            // 32 bytes without the final "=": Java decodes it, the engine refuses it.
            reply("A".repeat(43))
            failsAsUnexpected { NordApi.credentials("t") }
        } finally { Http.open = original }
    }

    @Test fun piaDropsServersAtLocalAddressesAndChecksTheKeyReply() {
        val rs = Pia.parseRegions("""{"regions":[{"id":"a","name":"A","country":"NL","servers":{"wg":[{"ip":"10.1.1.1","cn":"x"}]}},""" +
            """{"id":"b","name":"B","country":"NL","servers":{"wg":[{"ip":"192.168.0.1","cn":"y"},{"ip":"1.1.1.3","cn":"z"}]}}]}""")
        assertEquals(listOf("b"), rs.map { it.id })
        assertEquals(listOf("z"), rs.single().servers.wg.map { it.cn })
        val ok = Pia.parseAddKey("""{"status":"OK","server_key":"$key","server_port":1337,"peer_ip":"10.1.2.3","dns_servers":["10.0.0.243"]}""")
        assertEquals("10.1.2.3", ok.peerIp)
        failsAsUnexpected { Pia.parseAddKey("""{"status":"OK","server_key":"not-a-key","server_port":1337,"peer_ip":"10.1.2.3"}""") }
        failsAsUnexpected { Pia.parseAddKey("""{"status":"OK","server_key":"$key","server_port":1337,"peer_ip":"10.1.2.3.4"}""") }
        failsAsUnexpected { Pia.parseAddKey("""{"status":"OK","server_key":"${"A".repeat(43)}","server_port":1337,"peer_ip":"10.1.2.3"}""") }
    }

    /** A key passes only in the form the engine decodes: 43 standard Base64 characters and "=", 32 bytes. */
    @Test fun keysMustBeInTheEnginesForm() {
        assertTrue(app.unbraid.data.validWgKey("A".repeat(43) + "="))
        for (bad in listOf("A".repeat(43), "A".repeat(42) + "==", "-_" + "A".repeat(41) + "=", "A".repeat(44) + "=", " " + "A".repeat(43) + "=", ""))
            assertFalse(bad, app.unbraid.data.validWgKey(bad))
    }
}
