package app.unbraid

import app.unbraid.data.Manual
import app.unbraid.data.OvpnProfile
import app.unbraid.data.WgConf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        for (good in listOf("203.0.113.1:51820", "[::1]:1", "[::ffff:192.0.2.1]:443", "[2001:db8:0:0:0:0:0:1]:51820", "x:1", "a-1.b_c.example.:65535"))
            WgConf.parse(conf(endpoint = good), "x")
        for (bad in listOf("51820", "[2001:db8::1]", "[2001:db8::1]51820", "203.0.113.1:+51", "203.0.113.1:"))
            assertEquals(bad, "Endpoint needs host:port", refused(conf(endpoint = bad)))
        for (bad in listOf("[2001:db8:::1]:51820", "[1:2:3:4:5:6:7:8:9]:1", "[fe80::1%wlan0]:1", "[::1.2.3.999]:1", "[]:1", "999.1.2.3:51820", "1.2.3:51820",
            "-a.example:1", "a..example:1", "a/b:1", "2001:db8::1:51820"))
            assertEquals(bad, "Endpoint has an invalid host", refused(conf(endpoint = bad)))
        assertEquals("Invalid AllowedIPs range in the config", refused(conf(allowed = "0.0.0.0/0, 10.0.0.0/40")))
        // A word pasted into the wrong line is never shown back.
        assertEquals("Invalid AllowedIPs range in the config", refused(conf(allowed = "0.0.0.0/0, rosebud")))
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

    private val ovpn = "client\ndev tun\nremote 203.0.113.1 1194\nremote-cert-tls server\n"
    private fun ovpnWith(ca: String) = "$ovpn<ca>\n$ca\n</ca>\n"

    @Test fun pemScanIsLinear() {
        // 64 KB of BEGIN lines that never close, then one real certificate.
        val noise = "-----BEGIN CERTIFICATE-----\n".repeat(64 * 1024 / 28)
        val real = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----"
        val t0 = System.nanoTime()
        val found = Manual.pemBlocks(noise + real)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(listOf(real), found.map { it.substring(it.lastIndexOf("-----BEGIN")) })
        assertTrue("took $ms ms", ms < 200)
        val t1 = System.nanoTime()
        try { Manual.parseOvpn(ovpnWith(noise), "x", "u", "p"); fail("accepted") } catch (e: IllegalArgumentException) { assertEquals("The <ca> block is empty or malformed", e.message) }
        assertTrue("parse took ${(System.nanoTime() - t1) / 1_000_000} ms", (System.nanoTime() - t1) / 1_000_000 < 500)
        // Many different labels, and many closed pairs, stay fast too.
        val labels = (1..2000).joinToString("\n") { "-----BEGIN L$it-----" }
        val t2 = System.nanoTime(); Manual.pemBlocks(labels + "\n" + labels.replace("BEGIN", "END")); assertTrue((System.nanoTime() - t2) / 1_000_000 < 500)
        val pairs = "-----BEGIN A-----\nx\n-----END A-----\n".repeat(2000)
        val t3 = System.nanoTime(); assertEquals(2000, Manual.pemBlocks(pairs).size); assertTrue((System.nanoTime() - t3) / 1_000_000 < 500)
    }

    @Test fun pemScanMatchesTheOldPattern() {
        val old = Regex("-----BEGIN ([A-Za-z0-9 ]+)-----[\\s\\S]*?-----END \\1-----")
        for (t in listOf(
            "-----BEGIN A-----\nx\n-----END A-----", "-----BEGIN A-----\n-----BEGIN B-----\nx\n-----END B-----", "-----BEGIN A-----\nx\n-----END B-----",
            "-----BEGIN A-----\n-----BEGIN A-----\nx\n-----END A-----\n-----END A-----", "-----BEGIN A B-----x-----END A B-----junk-----BEGIN C-----y-----END C-----",
            "-----BEGIN A-----\n-----END A-----\n-----BEGIN A-----\n-----END A-----", "-----BEGIN -----\n-----END -----", "-----BEGIN A----\n-----END A-----", "",
        )) assertEquals(t, old.findAll(t).map { it.value }.toList(), Manual.pemBlocks(t))
    }

    @Test fun serverNamesAreCleaned() {
        assertEquals("ab", Manual.cleanName("\u202Ea\nb\u200F ", 40))
        // 39 letters then a family emoji (several code points) then more: the cut is before or after it, never inside.
        val cut = Manual.cleanName("a".repeat(39) + "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67bcd", 40)
        assertEquals("a".repeat(39) + "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", cut)
        assertEquals("a".repeat(39) + "\uD83D\uDE00", Manual.cleanName("a".repeat(39) + "\uD83D\uDE00" + "z", 40))
        val ss = Manual.parseSs("ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwdw@h.example:8388#%E2%80%AEevil%0Aname")
        assertEquals("evilname", ss.name)
    }

    @Test fun importMessagesNeverQuoteTheFile() {
        val secret = "hunter2hunter2hunter2"
        fun msg(text: String) = try { Manual.parseOvpn(text, "x", "u", "p"); fail("accepted"); "" } catch (e: IllegalArgumentException) { e.message.orEmpty() }
        assertEquals("Invalid remote port", msg("client\ndev tun\nremote h $secret\n"))
        assertEquals("Unsupported protocol (use udp or tcp)", msg("client\ndev tun\nproto $secret\nremote h 1\n"))
        assertEquals("Invalid port", msg("client\ndev tun\nport $secret\nremote h\n"))
        assertTrue(secret !in msg("client\ndev tun\nremote h 1\ncipher $secret\n"))
        assertTrue(secret !in msg("client\ndev tun\nremote h 1\n$secret abc\n"))
        assertEquals("Unsupported OpenVPN option", msg("client\ndev tun\nremote h 1\nPass1234Word abc\n"))
        assertEquals("Unsupported OpenVPN option: frobnicate", msg("client\ndev tun\nremote h 1\nfrobnicate abc\n"))
        for (link in listOf("ss://$secret:pw@h.example:8388", "ss://aes-256-gcm:pw@h.example:8388/?plugin=$secret")) {
            val m = try { Manual.parseSs(link); fail("accepted"); "" } catch (e: IllegalArgumentException) { e.message.orEmpty() }
            assertTrue(m, secret !in m)
        }
    }
}
