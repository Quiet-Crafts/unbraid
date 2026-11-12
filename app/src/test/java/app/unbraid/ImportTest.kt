package app.unbraid

import app.unbraid.data.WgConf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class ImportTest {
    private val key = "A".repeat(43) + "="
    private fun conf(
        address: String = "10.0.0.2/32", endpoint: String = "203.0.113.1:51820", allowed: String = "0.0.0.0/0",
        private: String = key, public: String = key, extra: String = "",
    ) = "[Interface]\nPrivateKey = $private\nAddress = $address\n$extra\n[Peer]\nPublicKey = $public\nEndpoint = $endpoint\nAllowedIPs = $allowed\n"

    private fun refused(text: String): String = try { WgConf.parse(text, "x"); fail("accepted"); "" } catch (e: IllegalArgumentException) { e.message.orEmpty() }

    @Test fun wireguardAddressesAreChecked() {
        for (bad in listOf("300.1.1.1/32", "10.0.0.2/99", "10.0.0.2/33", "10.0.0.x", "10.0.0.2/32, 1.2.3.999")) assertEquals("Invalid Address in the config", refused(conf(address = bad)))
        assertEquals(listOf("10.0.0.2/32", "10.1.0.5/24"), WgConf.parse(conf(address = "10.0.0.2, 10.1.0.5/24, fd00::2/64"), "x").address)
    }

    @Test fun wireguardOtherFieldsAreChecked() {
        assertEquals("Endpoint needs host:port", refused(conf(endpoint = "203.0.113.1:0")))
        assertEquals("Endpoint has an invalid host", refused(conf(endpoint = "bad host:51820")))
        assertEquals("Endpoint has an invalid host", refused(conf(endpoint = ":51820")))
        WgConf.parse(conf(endpoint = "[2001:db8::1]:51820"), "x")
        WgConf.parse(conf(endpoint = "vpn.example.net:51820"), "x")
        assertEquals("AllowedIPs has an invalid range: 10.0.0.0/40", refused(conf(allowed = "0.0.0.0/0, 10.0.0.0/40")))
        // Keys are named by field and never quoted.
        val secret = "Zm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9v" // right shape, not a key (33 bytes)
        for (bad in listOf(secret, key.dropLast(2) + "!=")) {
            val m = refused(conf(private = bad)); assertEquals("Invalid PrivateKey in the config", m); assertFalse(bad in m)
        }
        assertEquals("Invalid PublicKey in the config", refused(conf(public = "short")))
        assertEquals("Invalid PresharedKey in the config", refused(conf(extra = "").replace("[Peer]", "[Peer]\nPresharedKey = nope")))
        // A DNS entry the engine can't take is left out, not passed on.
        assertEquals(listOf("1.1.1.1"), WgConf.parse(conf(extra = "DNS = 1.1.1.1, 1.2.3.999, 9.9.9.9/8, corp.example"), "x").dns)
    }
}
