package app.unbraid

import app.unbraid.data.Manual
import app.unbraid.data.OvpnProfile
import app.unbraid.data.WgConf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64

class ManualTest {
    private val pretty = Json { prettyPrint = true }

    private fun fixture(name: String) = javaClass.getResource("/ovpn/$name")!!.readText()

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
    private fun JsonObject.obj(k: String) = this.getValue(k).jsonObject
    private fun JsonObject.strings(k: String) = getValue(k).jsonArray.map { it.jsonPrimitive.content }

    /** The smallest full sing-box config that routes everything through [tag]; checked with `sing-box check` after the run. */
    private fun write(file: String, tag: String, endpoint: JsonObject? = null, outbound: JsonObject? = null) {
        val cfg = buildJsonObject {
            putJsonObject("log") { put("level", "info") }
            if (endpoint != null) putJsonArray("endpoints") { add(endpoint) }
            putJsonArray("outbounds") {
                add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
                outbound?.let { add(it) }
            }
            putJsonObject("route") { put("final", tag) }
        }
        File("build/manual").apply { mkdirs() }.resolve("$file.json").writeText(pretty.encodeToString(JsonObject.serializer(), cfg))
    }

    private fun ovpn(file: String, text: String, user: String = "u", pass: String = "p"): JsonObject {
        val p = Manual.parseOvpn(text, file, user, pass)
        assertEquals(text, p.config)
        return Manual.ovpnEndpoint("ovpn-$file", p).also { write(file, "ovpn-$file", endpoint = it) }
    }

    private fun rejects(why: String, block: () -> Unit) {
        try { block() } catch (e: IllegalArgumentException) { assertTrue("'${e.message}' should mention '$why'", e.message!!.contains(why, ignoreCase = true)); return }
        fail("expected rejection mentioning '$why'")
    }

    @Test fun expressVpn() {
        val e = ovpn("expressvpn", fixture("expressvpn.ovpn"))
        assertEquals("openvpn-client", e.str("type"))
        assertEquals("usa-new-york-1-ca-version-2.expressnetw.com", e.str("server"))
        assertEquals("1195", e.str("server_port"))
        assertEquals("udp", e.str("network"))
        assertEquals("u", e.str("username")); assertEquals("p", e.str("password"))
        assertEquals("SHA512", e.str("auth"))
        assertNull(e["data_ciphers"]); assertNull(e["data_ciphers_fallback"])
        assertEquals("no", e.str("compression_lzo"))
        assertNull(e["allow_compression"])
        assertEquals("1300", e.str("fragment")); assertEquals("1200", e.str("mss_fix")); assertEquals("1500", e.str("mtu"))
        assertEquals("true", e.str("route_no_pull"))
        val tls = e.obj("tls")
        assertEquals("Server", tls.str("server_name")); assertEquals("name-prefix", tls.str("server_name_type"))
        assertEquals("server", tls.str("ns_certificate_type"))
        assertTrue(tls.str("certificate")!!.startsWith("-----BEGIN CERTIFICATE-----"))
        assertNull(tls["client_certificate"])
        val wrap = tls.obj("control_wrap")
        assertEquals("tls_auth", wrap.str("type")); assertEquals("client", wrap.str("direction"))
        assertTrue(wrap.str("key")!!.startsWith("-----BEGIN OpenVPN Static key V1-----"))
        assertFalse(wrap.str("key")!!.contains("#"))
    }

    @Test fun cyberGhost() {
        val e = ovpn("cyberghost", fixture("cyberghost.ovpn"))
        assertEquals("87-1-nl.cg-dialup.net", e.str("server")); assertEquals("443", e.str("server_port"))
        assertNull(e["data_ciphers_fallback"]); assertNull(e["data_ciphers"])
        assertEquals("SHA256", e.str("auth"))
        assertEquals("5s", e.str("ping_interval")); assertEquals("20s", e.str("ping_restart"))
        assertEquals("true", e.str("renegotiate_disabled")); assertEquals("2", e.str("explicit_exit_notify"))
        assertEquals("adaptive", e.str("compression_lzo")); assertEquals("asym", e.str("allow_compression"))
        val tls = e.obj("tls")
        assertEquals("server", tls.str("remote_certificate_tls"))
        assertTrue(tls.str("client_certificate")!!.contains("BEGIN CERTIFICATE"))
        assertTrue(tls.str("client_key")!!.contains("BEGIN PRIVATE KEY"))
        val wrap = tls.obj("control_wrap")
        assertEquals("tls_crypt", wrap.str("type")); assertNull(wrap["direction"])
    }

    @Test fun selfHosted() {
        val e = ovpn("selfhosted", fixture("selfhosted.ovpn"))
        assertNull(e["server"])
        val servers = e["servers"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("vpn.example.net", "vpn2.example.net", "203.0.113.10"), servers.map { it.str("server") })
        assertEquals(listOf("443", "8443", "1194"), servers.map { it.str("server_port") })
        assertEquals(listOf("tcp", "tcp", "udp"), servers.map { it.str("network") })
        assertEquals("true", e.str("remote_random"))
        assertEquals(listOf("AES-256-GCM", "AES-128-GCM", "CHACHA20-POLY1305"), e.strings("data_ciphers"))
        assertEquals("AES-128-CBC", e.str("data_ciphers_fallback"))
        assertEquals("3600s", e.str("renegotiate_interval"))
        assertEquals("10s", e.str("ping_interval")); assertEquals("60s", e.str("ping_restart"))
        val tls = e.obj("tls")
        assertEquals("vpn.example.net", tls.str("server_name")); assertEquals("name", tls.str("server_name_type"))
        assertEquals("1.2", tls.str("version_min"))
        assertEquals("client", tls.obj("control_wrap").str("direction"))
        assertNull(e["redirect_gateway"])
    }

    @Test fun ovpnRejections() {
        val ex = fixture("expressvpn.ovpn")
        fun with(extra: String) = ex + "\n" + extra
        fun without(re: String) = ex.replace(Regex(re), "")
        rejects("username and password") { Manual.parseOvpn(ex, "x", "", "") }
        rejects("username and password") { Manual.parseOvpn(ex, "x", "u", "") }
        rejects("too large") { Manual.parseOvpn(ex + "#".repeat(300_000), "x", "u", "p") }
        rejects("no 'remote'") { Manual.parseOvpn(without("remote [^\\n]+\\n"), "x", "u", "p") }
        rejects("client profile") { Manual.parseOvpn(without("tls-client\\n"), "x", "u", "p") }
        rejects("<ca>") { Manual.parseOvpn(without("(?s)<ca>.*?</ca>\\n"), "x", "u", "p") }
        rejects("tun") { Manual.parseOvpn(ex.replace("dev tun", "dev tap"), "x", "u", "p") }
        rejects("cipher") { Manual.parseOvpn(ex.replace("AES-256-CBC", "BF-CBC"), "x", "u", "p") }
        rejects("auth digest") { Manual.parseOvpn(ex.replace("SHA512", "MD5"), "x", "u", "p") }
        rejects("PKCS#12") { Manual.parseOvpn(with("<pkcs12>\nAAAA\n</pkcs12>"), "x", "u", "p") }
        rejects("PKCS#12") { Manual.parseOvpn(with("pkcs12 client.p12"), "x", "u", "p") }
        rejects("inline") { Manual.parseOvpn(without("(?s)<ca>.*?</ca>\\n").let { it + "\nca ca.crt" }, "x", "u", "p") }
        rejects("inline") { Manual.parseOvpn(with("cert client.crt"), "x", "u", "p") }
        rejects("both <cert> and <key>") { Manual.parseOvpn(with("<cert>\n-----BEGIN CERTIFICATE-----\nAA==\n-----END CERTIFICATE-----\n</cert>"), "x", "u", "p") }
        rejects("compress lzo") { Manual.parseOvpn(with("compress lzo"), "x", "u", "p") }
        rejects("proxy") { Manual.parseOvpn(with("http-proxy 10.0.0.1 8080"), "x", "u", "p") }
        rejects("2FA") { Manual.parseOvpn(with("static-challenge \"Code\" 1"), "x", "u", "p") }
        rejects("fragment only works over UDP") { Manual.parseOvpn(ex.replace("proto udp", "proto tcp"), "x", "u", "p") }
        rejects("Unsupported OpenVPN option: frobnicate") { Manual.parseOvpn(with("frobnicate 1"), "x", "u", "p") }
        rejects("Encrypted private key") {
            Manual.parseOvpn(with("<cert>\n-----BEGIN CERTIFICATE-----\nAA==\n-----END CERTIFICATE-----\n</cert>\n<key>\n-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nAA==\n-----END RSA PRIVATE KEY-----\n</key>"), "x", "u", "p")
        }
        rejects("Unterminated") { Manual.parseOvpn(with("<ca>\nxx"), "x", "u", "p") }
    }

    @Test fun tlsCipherIsEnforcedAndTls13SuitesAreRefused() {
        val ex = fixture("expressvpn.ovpn")
        val e = ovpn("tls-cipher", ex + "\ntls-cipher ECDHE-RSA-AES256-GCM-SHA384:TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256")
        assertEquals("ECDHE-RSA-AES256-GCM-SHA384:TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", e.obj("tls").str("cipher"))
        assertNull(ovpn("no-tls-cipher", ex).obj("tls")["cipher"])
        rejects("tls-cipher") { Manual.parseOvpn(ex + "\ntls-cipher DEFAULT", "x", "u", "p") }
        rejects("tls-cipher") { Manual.parseOvpn(ex + "\ntls-cipher TLS-DHE-RSA-WITH-AES-256-GCM-SHA384", "x", "u", "p") }
        rejects("tls-ciphersuites") { Manual.parseOvpn(ex + "\ntls-ciphersuites TLS_AES_256_GCM_SHA384", "x", "u", "p") }
    }

    @Test fun ncpDisableWinsOverAnExplicitCipherList() {
        val ex = fixture("expressvpn.ovpn")
        val e = ovpn("ncp-disable", ex + "\nncp-disable\ndata-ciphers AES-128-GCM:CHACHA20-POLY1305\ndata-ciphers-fallback AES-128-CBC")
        assertEquals(listOf("AES-256-CBC"), e.strings("data_ciphers"))
        assertEquals("AES-256-CBC", e.str("data_ciphers_fallback"))
        val gcm = ovpn("ncp-disable-gcm", fixture("cyberghost.ovpn") + "\nncp-disable\nncp-ciphers AES-128-GCM")
        assertEquals(listOf("AES-256-GCM"), gcm.strings("data_ciphers"))
        assertEquals("AES-256-GCM", gcm.str("data_ciphers_fallback"))
        rejects("ncp-disable needs a cipher") { Manual.parseOvpn(ex.replace(Regex("cipher AES-256-CBC\\n"), "") + "\nncp-disable", "x", "u", "p") }
    }

    @Test fun pingRestartZeroDisablesTheRestartTimeout() {
        val ex = fixture("expressvpn.ovpn")
        val off = ovpn("ping-restart-0", ex + "\nping-restart 0")
        assertEquals("true", off.str("ping_restart_disabled")); assertNull(off["ping_restart"])
        val keepalive = ovpn("keepalive-0", ex + "\nkeepalive 10 0")
        assertEquals("true", keepalive.str("ping_restart_disabled")); assertEquals("10s", keepalive.str("ping_interval"))
        assertNull(ovpn("ping-restart-default", ex)["ping_restart_disabled"])
        assertNull(ovpn("ping-restart-30", ex + "\nping-restart 30")["ping_restart_disabled"])
    }

    @Test fun ovpnWithoutLoginNeedsNone() {
        val text = fixture("cyberghost.ovpn").replace("auth-user-pass\n", "")
        val e = Manual.ovpnEndpoint("t", Manual.parseOvpn(text, "x", "", ""))
        assertNull(e["username"])
    }

    @Test fun inlineLoginIsUsedWhenNothingWasTyped() {
        val base = fixture("cyberghost.ovpn")
        val text = base.replace("auth-user-pass\n", "<auth-user-pass>\n  alice \npass word \n</auth-user-pass>\n")
        val e = Manual.ovpnEndpoint("t", Manual.parseOvpn(text, "x", "", ""))
        assertEquals("alice", e.str("username")); assertEquals("pass word ", e.str("password"))
        write("inline-login", "t", endpoint = e)
        val typed = Manual.ovpnEndpoint("t", Manual.parseOvpn(text, "x", "bob", " s p "))
        assertEquals("bob", typed.str("username")); assertEquals(" s p ", typed.str("password"))
        rejects("username and password") { Manual.parseOvpn(base.replace("auth-user-pass\n", "<auth-user-pass>\nalice\n</auth-user-pass>\n"), "x", "", "") }
        rejects("username and password") { Manual.parseOvpn(base, "x", "", "") }
    }

    @Test fun extraCertsExtendTheClientChain() {
        val base = fixture("cyberghost.ovpn")
        val ca = Regex("(?s)<ca>\\s*(.*?)\\s*</ca>").find(base)!!.groupValues[1]
        val e = ovpn("extra-certs", base.replace("<key>", "<extra-certs>\n$ca\n</extra-certs>\n<key>"))
        val chain = e.obj("tls").str("client_certificate")!!
        assertEquals(2, Regex("BEGIN CERTIFICATE").findAll(chain).count())
        assertTrue(chain.endsWith(ca.lines().last().trim()))
        assertEquals(1, Regex("BEGIN CERTIFICATE").findAll(ovpn("no-extra-certs", base).obj("tls").str("client_certificate")!!).count())
        val ex = fixture("expressvpn.ovpn")
        rejects("extra-certs") { Manual.parseOvpn(ex + "\n<extra-certs>\n$ca\n</extra-certs>", "x", "u", "p") }
    }

    private val key44 = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 7 + 3).toByte() })
    private val key16 = Base64.getEncoder().encodeToString(ByteArray(16) { (it * 11 + 5).toByte() })
    private val key32 = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 13 + 1).toByte() })

    private fun ss(file: String, link: String, name: String = ""): JsonObject {
        val p = Manual.parseSs(link, name)
        return Manual.ssOutbound("ss-$file", p).also { write(file, "ss-$file", outbound = it) }
    }

    private val base get() = fixture("expressvpn.ovpn").replace("ns-cert-type server\n", "")

    private fun tls(name: String, extra: String) = ovpn(name, base + "\n" + extra).obj("tls")

    @Test fun bareRemoteCertKuKeepsTheExtensionRequirementAlongsideEku() {
        val server = tls("ku-eku-server", "remote-cert-ku\nremote-cert-eku \"TLS Web Server Authentication\"")
        assertEquals("server", server.str("remote_certificate_tls")); assertNull(server["remote_certificate_eku"])
        val client = tls("ku-eku-client", "remote-cert-eku 1.3.6.1.5.5.7.3.2\nremote-cert-ku")
        assertEquals("client", client.str("remote_certificate_tls")); assertNull(client["remote_certificate_eku"])
        val tlsPlusEku = tls("tls-eku", "remote-cert-tls client\nremote-cert-eku clientAuth")
        assertEquals("client", tlsPlusEku.str("remote_certificate_tls")); assertNull(tlsPlusEku["remote_certificate_eku"])
        rejects("can't be combined") { Manual.parseOvpn(base + "\nremote-cert-ku\nremote-cert-eku 1.3.6.1.5.5.7.3.3", "x", "u", "p") }
        // Alone, a bare remote-cert-ku demands a key-usage extension the engine can't express: refused, not dropped.
        rejects("bare remote-cert-ku") { Manual.parseOvpn(base + "\nremote-cert-ku", "x", "u", "p") }
        rejects("can't be combined") { Manual.parseOvpn(base + "\nremote-cert-tls server\nremote-cert-eku 1.3.6.1.5.5.7.3.3", "x", "u", "p") }
        val values = tls("ku-values-eku", "remote-cert-ku a0 88\nremote-cert-eku 1.3.6.1.5.5.7.3.3")
        assertEquals(listOf("a0", "88"), values.strings("remote_certificate_ku")); assertEquals("1.3.6.1.5.5.7.3.3", values.str("remote_certificate_eku"))
        val eku = tls("eku-only", "remote-cert-eku 1.3.6.1.5.5.7.3.3")
        assertEquals("1.3.6.1.5.5.7.3.3", eku.str("remote_certificate_eku")); assertNull(eku["remote_certificate_tls"])
        assertEquals("server", tls("tls-only", "remote-cert-tls server").str("remote_certificate_tls"))
        assertEquals("1.3.6.1.5.5.7.3.3", tls("eku-after-tls-ku", "remote-cert-tls server\nremote-cert-ku a0\nremote-cert-eku 1.3.6.1.5.5.7.3.3").str("remote_certificate_eku"))
    }

    @Test fun cipherAloneNeverWidensNegotiation() {
        val lone = ovpn("cipher-only", base)
        assertNull(lone["data_ciphers"]); assertNull(lone["data_ciphers_fallback"])
        val withList = ovpn("cipher-list", base + "\ndata-ciphers AES-128-GCM")
        assertEquals(listOf("AES-128-GCM"), withList.strings("data_ciphers")); assertNull(withList["data_ciphers_fallback"])
        val fb = ovpn("cipher-fallback", base + "\ndata-ciphers-fallback AES-128-CBC")
        assertNull(fb["data_ciphers"]); assertEquals("AES-128-CBC", fb.str("data_ciphers_fallback"))
        val flipped = ovpn("fallback-then-cipher", base.replace("cipher AES-256-CBC\n", "") + "\ndata-ciphers-fallback AES-128-CBC\ncipher AES-192-CBC")
        assertEquals("AES-192-CBC", flipped.str("data_ciphers_fallback"))
        val onlyFallback = ovpn("fallback-only", base.replace("cipher AES-256-CBC\n", "") + "\ndata-ciphers-fallback AES-128-CBC")
        assertEquals("AES-128-CBC", onlyFallback.str("data_ciphers_fallback"))
    }

    @Test fun compatModeRestoresTheLegacyCipherOnlyWhereOpenVpnDoes() {
        val c24 = ovpn("compat-2.4.0", base + "\ncompat-mode 2.4.0")
        assertEquals(listOf("AES-256-GCM", "AES-128-GCM", "CHACHA20-POLY1305", "AES-256-CBC"), c24.strings("data_ciphers"))
        assertNull(c24["data_ciphers_fallback"])
        val c24Fixed = ovpn("compat-2.4.0-list", base + "\ncompat-mode 2.4.0\ndata-ciphers AES-128-GCM")
        assertEquals(listOf("AES-128-GCM", "AES-256-CBC"), c24Fixed.strings("data_ciphers"))
        val c24Gcm = ovpn("compat-2.4.0-gcm", base.replace("AES-256-CBC", "AES-256-GCM") + "\ncompat-mode 2.4.0")
        assertNull(c24Gcm["data_ciphers"])
        val c25 = ovpn("compat-2.5.0", base + "\ncompat-mode 2.5.0")
        assertNull(c25["data_ciphers"]); assertNull(c25["data_ciphers_fallback"])
        val c23 = ovpn("compat-2.3.0", base + "\ncompat-mode 2.3.0")
        assertEquals("AES-256-CBC", c23.str("data_ciphers_fallback"))
        assertEquals(listOf("AES-256-GCM", "AES-128-GCM", "CHACHA20-POLY1305", "AES-256-CBC"), c23.strings("data_ciphers"))
        rejects("compat-mode") { Manual.parseOvpn(base + "\ncompat-mode 2.4", "x", "u", "p") }
        rejects("needs a cipher") { Manual.parseOvpn(base.replace("cipher AES-256-CBC\n", "") + "\ncompat-mode 2.3.0", "x", "u", "p") }
        rejects("Invalid data-ciphers-fallback") { Manual.parseOvpn(base + "\ndata-ciphers-fallback AES-128-CBC:AES-256-CBC", "x", "u", "p") }
    }

    @Test fun pullFiltersKeepTheirOrderAndRouteNoPullStaysOn() {
        val e = ovpn("pull-filters", base + "\npull-filter accept \"dhcp-option DNS 10.\"\npull-filter ignore \"dhcp-option\"\npull-filter reject redirect-gateway")
        val f = e.getValue("pull_filters").jsonArray.map { it.jsonObject }
        assertEquals(listOf("accept" to "dhcp-option DNS 10.", "ignore" to "dhcp-option", "reject" to "redirect-gateway"), f.map { it.str("action")!! to it.str("text")!! })
        assertEquals("true", e.str("route_no_pull"))
        assertNull(ovpn("no-pull-filters", base)["pull_filters"])
        val shipped = ovpn("selfhosted-filters", fixture("selfhosted.ovpn")).getValue("pull_filters").jsonArray.single().jsonObject
        assertEquals("ignore", shipped.str("action")); assertEquals("route-ipv6", shipped.str("text"))
        rejects("pull-filter") { Manual.parseOvpn(base + "\npull-filter drop foo", "x", "u", "p") }
        rejects("pull-filter") { Manual.parseOvpn(base + "\npull-filter ignore", "x", "u", "p") }
        rejects("pull-filter") { Manual.parseOvpn(base + "\npull-filter ignore a b", "x", "u", "p") }
    }

    @Test fun portAndRportFollowDirectiveOrderForRemotesWithoutAPort() {
        val noPort = base.replace("remote usa-new-york-1-ca-version-2.expressnetw.com 1195", "remote a.example.net\nremote b.example.net 8443")
        fun ports(extra: String) = ovpn("ports", noPort + "\n" + extra).getValue("servers").jsonArray.map { it.jsonObject.str("server_port") }
        assertEquals(listOf("1194", "8443"), ports(""))
        assertEquals(listOf("443", "8443"), ports("port 1194\nrport 443"))
        assertEquals(listOf("1194", "8443"), ports("rport 443\nport 1194"))
        assertEquals(listOf("443", "8443"), ports("rport 443"))
        assertEquals(listOf("1195", "8443"), ports("port 1195"))
        assertEquals(listOf("443", "8443"), ports("port 1194\nremote-random\nrport 443"))
        rejects("Invalid port") { Manual.parseOvpn(noPort + "\nrport x", "x", "u", "p") }
    }

    @Test fun wireGuardImportRulesForRoutesAndAmnezia() {
        val key = "A".repeat(43) + "="
        fun conf(allowed: String, extra: String = "") =
            "[Interface]\nPrivateKey = $key\nAddress = 10.0.0.2/32\n$extra\n[Peer]\nPublicKey = $key\nEndpoint = 203.0.113.1:51820\nAllowedIPs = $allowed\n"
        val full = WgConf.parse(conf("0.0.0.0/0, ::/0"), "x").also(WgConf::requireFullTunnel)
        assertEquals(listOf("0.0.0.0/0"), full.allowedIps)
        val subnet = WgConf.parse(conf("10.0.0.0/24, 192.168.1.0/24"), "x")
        assertEquals(listOf("10.0.0.0/24", "192.168.1.0/24"), subnet.allowedIps)
        rejects("only reaches 10.0.0.0/24, 192.168.1.0/24; import it under Settings → Home") { WgConf.requireFullTunnel(subnet) }
        // Ranges that together cover every IPv4 address are a full tunnel too, in any order, overlapping or not.
        for (all in listOf("0.0.0.0/1, 128.0.0.0/1", "128.0.0.0/1, 0.0.0.0/1", "0.0.0.0/2, 64.0.0.0/2, 128.0.0.0/2, 192.0.0.0/2", "0.0.0.0/1, 0.0.0.0/0", "0.0.0.0/1, 64.0.0.0/2, 128.0.0.0/1"))
            WgConf.requireFullTunnel(WgConf.parse(conf(all), "x"))
        // A malformed range is refused, never silently dropped.
        rejects("invalid range: 999.1.1.1/32") { WgConf.requireFullTunnel(WgConf.parse(conf("0.0.0.0/1, 128.0.0.0/1, 999.1.1.1/32"), "x")) }
        rejects("invalid range: 0.0.0.0/33") { WgConf.requireFullTunnel(WgConf.parse(conf("0.0.0.0/33"), "x")) }
        // A gap of even one address, or the official app's "exclude private IPs" list, is not.
        rejects("leaves out some addresses") { WgConf.requireFullTunnel(WgConf.parse(conf("0.0.0.0/1, 128.0.0.0/2, 192.0.0.0/3, 224.0.0.0/4, 240.0.0.0/5, 248.0.0.0/6, 252.0.0.0/7, 254.0.0.0/8, 255.0.0.0/9, 255.128.0.0/10, 255.192.0.0/11, 255.224.0.0/12, 255.240.0.0/13, 255.248.0.0/14, 255.252.0.0/15, 255.254.0.0/16, 255.255.0.0/17, 255.255.128.0/18, 255.255.192.0/19, 255.255.224.0/20, 255.255.240.0/21, 255.255.248.0/22, 255.255.252.0/23, 255.255.254.0/24, 255.255.255.0/25, 255.255.255.128/26, 255.255.255.192/27, 255.255.255.224/28, 255.255.255.240/29, 255.255.255.248/30, 255.255.255.252/31, 255.255.255.254/32"), "x")) }
        rejects("leaves out some addresses") { WgConf.requireFullTunnel(WgConf.parse(conf("0.0.0.0/5, 8.0.0.0/7, 11.0.0.0/8, 12.0.0.0/6, 16.0.0.0/4, 32.0.0.0/3, 64.0.0.0/2, 128.0.0.0/3, 160.0.0.0/5, 168.0.0.0/6, 172.0.0.0/12, 172.32.0.0/11, 172.64.0.0/10, 172.128.0.0/9, 173.0.0.0/8, 174.0.0.0/7, 176.0.0.0/4, 192.0.0.0/9, 192.128.0.0/11, 192.160.0.0/13, 192.169.0.0/16, 192.170.0.0/15, 192.172.0.0/14, 192.176.0.0/12, 192.192.0.0/10, 193.0.0.0/8, 194.0.0.0/7, 196.0.0.0/6, 200.0.0.0/5, 208.0.0.0/4, 224.0.0.0/3"), "x")) }
        for (k in listOf("Jc", "Jmin", "Jmax", "S1", "S2", "H1", "H2", "H3", "H4", "I1", "I5")) {
            rejects("Obfuscated (AmneziaWG) configs aren't supported") { WgConf.parse(conf("0.0.0.0/0", "$k = 5"), "x") }
        }
        rejects("AmneziaWG") { WgConf.parse(conf("0.0.0.0/0").replace("Endpoint", "jc = 4\nEndpoint"), "x") }
    }

    @Test fun ssSip002Base64() {
        val userinfo = Base64.getUrlEncoder().withoutPadding().encodeToString("chacha20-ietf-poly1305:pa:ss/w0rd+1".toByteArray())
        val o = ss("ss-sip002-b64", "ss://$userinfo@ss.example.net:8388/?outline=1#Outline%20NL")
        assertEquals("shadowsocks", o.str("type"))
        assertEquals("ss.example.net", o.str("server")); assertEquals("8388", o.str("server_port"))
        assertEquals("chacha20-ietf-poly1305", o.str("method")); assertEquals("pa:ss/w0rd+1", o.str("password"))
        assertNull(o["plugin"])
        assertEquals("Outline NL", Manual.parseSs("ss://$userinfo@ss.example.net:8388#Outline%20NL").name)
        assertEquals("Mine", Manual.parseSs("ss://$userinfo@ss.example.net:8388#x", "Mine").name)
        assertEquals("ss.example.net", Manual.parseSs("ss://$userinfo@ss.example.net:8388").name)
        // v2ray-plugin's cert= would make the engine read a file on the phone.
        rejects("certRaw") { Manual.parseSs("ss://$userinfo@ss.example.net:8388/?plugin=v2ray-plugin%3Btls%3Bcert%3D%2Fdev%2Fzero#x") }
        rejects("certRaw") { Manual.parseSs("ss://$userinfo@ss.example.net:8388/?plugin=v2ray-plugin%3Btls%3Bc%5Cert%3D%2Fdev%2Fzero#x") }
        assertEquals("tls;host=a.example", Manual.parseSs("ss://$userinfo@ss.example.net:8388/?plugin=v2ray-plugin%3Btls%3Bhost%3Da.example#x").pluginOpts)
    }

    @Test fun ssSip002PlainWithPlugin() {
        val pw = java.net.URLEncoder.encode(key32, "UTF-8")
        val o = ss("ss-sip002-plain", "ss://2022-blake3-aes-256-gcm:$pw@[2001:db8::1]:443/?plugin=v2ray-plugin%3Bhost%3Dcdn.example.net%3Bpath%3D%2Fws#IPv6")
        assertEquals("2001:db8::1", o.str("server")); assertEquals("443", o.str("server_port"))
        assertEquals("2022-blake3-aes-256-gcm", o.str("method")); assertEquals(key32, o.str("password"))
        assertEquals("v2ray-plugin", o.str("plugin")); assertEquals("host=cdn.example.net;path=/ws", o.str("plugin_opts"))
    }

    @Test fun ssLegacy() {
        val body = Base64.getEncoder().encodeToString("aes-128-gcm:s3cret@legacy.example.net:8443".toByteArray())
        val o = ss("ss-legacy", "ss://$body#Legacy%20box")
        assertEquals("legacy.example.net", o.str("server")); assertEquals("8443", o.str("server_port"))
        assertEquals("aes-128-gcm", o.str("method")); assertEquals("s3cret", o.str("password"))
        assertEquals("Legacy box", Manual.parseSs("ss://$body#Legacy%20box").name)
    }

    @Test fun ssObfs() {
        val userinfo = Base64.getEncoder().encodeToString("2022-blake3-aes-128-gcm:$key16".toByteArray())
        val o = ss("ss-obfs", "ss://$userinfo@203.0.113.7:8388/?plugin=obfs-local%3Bobfs%3Dtls%3Bobfs-host%3Dcdn.example.com")
        assertEquals("obfs-local", o.str("plugin")); assertEquals("obfs=tls;obfs-host=cdn.example.com", o.str("plugin_opts"))
        assertEquals("obfs-local", Manual.parseSs("ss://$userinfo@h.example:1/?plugin=simple-obfs%3Bobfs%3Dhttp").plugin)
    }

    @Test fun ssRejections() {
        val ok = Base64.getEncoder().encodeToString("aes-256-gcm:pw".toByteArray())
        rejects("ss://") { Manual.parseSs("vmess://abc") }
        rejects("ssconf") { Manual.parseSs("ssconf://example.com/key") }
        rejects("cipher") { Manual.parseSs("ss://" + Base64.getEncoder().encodeToString("bf-cfb:pw".toByteArray()) + "@h.example:1") }
        rejects("cipher") { Manual.parseSs("ss://none:pw@h.example:1") }
        rejects("port") { Manual.parseSs("ss://$ok@h.example:0") }
        rejects("port") { Manual.parseSs("ss://$ok@h.example:70000") }
        rejects("port") { Manual.parseSs("ss://$ok@h.example") }
        rejects("host") { Manual.parseSs("ss://$ok@:8388") }
        rejects("empty password") { Manual.parseSs("ss://aes-256-gcm:@h.example:1") }
        rejects("plugin") { Manual.parseSs("ss://$ok@h.example:1/?plugin=kcptun%3Bkey%3Dx") }
        rejects("prefix") { Manual.parseSs("ss://$ok@h.example:1/?prefix=%16%03%01") }
        rejects("malformed") { Manual.parseSs("ss://%%%@h.example:1") }
        rejects("decode") { Manual.parseSs("ss://!!!notbase64!!!") }
    }

    @Test fun ssSizeLimits() {
        val ok = Base64.getEncoder().encodeToString("aes-256-gcm:pw".toByteArray())
        rejects("too long") { Manual.parseSs("ss://$ok@h.example:1/?plugin=" + "a".repeat(9000)) }
        val longPw = Base64.getEncoder().encodeToString(("aes-256-gcm:" + "p".repeat(2000)).toByteArray())
        rejects("credentials are too long") { Manual.parseSs("ss://$longPw@h.example:1") }
        rejects("credentials are too long") { Manual.parseSs("ss://aes-256-gcm:" + "p".repeat(2000) + "@h.example:1") }
        rejects("name is too long") { Manual.parseSs("ss://$ok@h.example:1#" + "n".repeat(200)) }
        rejects("name is too long") { Manual.parseSs("ss://$ok@h.example:1", "n".repeat(200)) }
        rejects("host is too long") { Manual.parseSs("ss://$ok@" + "h".repeat(300) + ":1") }
        assertEquals(128, Manual.parseSs("ss://$ok@h.example:1#" + "n".repeat(128)).name.length)
    }

    /** A profile that only trusts its CA (which signs every customer's certificate too) gets the warning; a server check clears it. */
    @Test fun aProfileThatNeverChecksTheServerIsFlagged() {
        val noCheck = base.lines().filterNot { it.startsWith("remote-cert-tls") || it.startsWith("verify-x509-name") || it.startsWith("ns-cert-type") }.joinToString("\n")
        fun w(extra: String) = Manual.weakness(Manual.parseOvpn(noCheck + "\n" + extra, "x", "u", "p"))
        assertTrue(w("")!!.contains("right server"))
        for (ok in listOf("remote-cert-tls server", "remote-cert-eku \"TLS Web Server Authentication\"", "remote-cert-eku serverAuth", "ns-cert-type server", "verify-x509-name vpn.example name"))
            assertNull(ok, w(ok))
        // The client's role, or a later EKU overriding a server check, is no server check.
        assertTrue(w("remote-cert-tls client")!!.contains("right server"))
        assertTrue(w("remote-cert-tls server\nremote-cert-eku clientAuth\nremote-cert-ku a0")!!.contains("right server"))
        // Weak crypto and no server check are both named.
        assertTrue(w("tls-version-min 1.0")!!.let { "TLS 1.0" in it && "right server" in it })
    }

    /** The engine checks a name against the CN only: a profile asking for another certificate field is refused. */
    @Test fun x509UsernameFieldOtherThanCnIsRefused() {
        val named = base.lines().filterNot { it.startsWith("verify-x509-name") }.joinToString("\n") + "\nverify-x509-name gw.example name"
        Manual.parseOvpn(named, "x", "u", "p")
        Manual.parseOvpn(named + "\nx509-username-field CN", "x", "u", "p")
        for (f in listOf("OU", "emailAddress", "ext:subjectAltName", "CN OU")) rejects("only the certificate's CN") { Manual.parseOvpn(named + "\nx509-username-field $f", "x", "u", "p") }
        // Without a name check the field changes nothing on the client.
        Manual.parseOvpn(base.lines().filterNot { it.startsWith("verify-x509-name") }.joinToString("\n") + "\nx509-username-field OU", "x", "u", "p")
    }

    @Test fun weakEncryptionIsFlaggedButStillImports() {
        assertNull(Manual.weakness(Manual.parseOvpn(base, "x", "u", "p")))
        for ((extra, says) in listOf("tls-version-min 1.0" to "TLS 1.0", "tls-cipher AES128-SHA:DES-CBC3-SHA" to "3DES", "tls-cert-profile insecure" to "certificates")) {
            assertTrue(extra, Manual.weakness(Manual.parseOvpn(base + "\n" + extra, "x", "u", "p"))!!.contains(says))
        }
        assertNull(Manual.weakness(Manual.parseOvpn(base + "\ntls-version-min 1.2", "x", "u", "p")))
        fun link(userinfo: String) = Manual.parseSs("ss://" + Base64.getUrlEncoder().withoutPadding().encodeToString(userinfo.toByteArray()) + "@h.example:1")
        assertNull(Manual.weakness(link("chacha20-ietf-poly1305:pw")))
        assertNull(Manual.weakness(link("2022-blake3-aes-128-gcm:$key16")))
        assertTrue(Manual.weakness(link("aes-256-cfb:pw"))!!.contains("aes-256-cfb"))
    }
}
