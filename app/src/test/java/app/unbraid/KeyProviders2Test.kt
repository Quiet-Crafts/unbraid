package app.unbraid

import app.unbraid.data.Catalog
import app.unbraid.data.ExpressVpn
import app.unbraid.data.Location
import app.unbraid.data.Manual
import app.unbraid.data.OvpnLink
import app.unbraid.data.WgLink
import app.unbraid.data.WgPeer
import app.unbraid.data.Windscribe
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class KeyProviders2Test {
    private val pretty = Json { prettyPrint = true }
    private val isoCodes = Locale.getISOCountries().toSet()

    private val list = Windscribe.parse(javaClass.getResource("/windscribe_serverlist.json")!!.readText())
    private val rows = Windscribe.rows(list)
    private val express = Catalog.parse(javaClass.getResource("/catalog_expressvpn.json")!!.readText(), "openvpn")

    init {
        Windscribe.nodeHosts = { null }
    }

    private val priv = "cHJpdmF0ZS1wcml2YXRlLXByaXZhdGUtcHJpdmF0ZTE="
    private val psk = "cHJlc2hhcmVkLXByZXNoYXJlZC1wcmVzaGFyZWQtMTI="
    private val peerKey = "c2VydmVyLXNlcnZlci1zZXJ2ZXItc2VydmVyLTEyMzQ="

    private fun wsConfig(port: Int = 443, extra: String = "PresharedKey = $psk") = """
        [Interface]
        PrivateKey = $priv
        Address = 100.96.12.34/32
        DNS = 10.255.255.1

        [Peer]
        PublicKey = $peerKey
        $extra
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = us-central-117.whiskergalaxy.com:$port
    """.trimIndent()

    private fun ws(port: Int = 443) = Windscribe.account(Windscribe.signIn(mapOf("config" to wsConfig(port))))

    // ---- Windscribe

    @Test fun windscribeCountriesCarryIsoCodesAndCities() {
        val cs = Windscribe.countries(rows)
        assertEquals(cs.map { it.name }.sorted(), cs.map { it.name })
        assertEquals(listOf("Canada East", "Fake Antarctica", "Italy", "Japan", "Netherlands", "US Central", "US East"), cs.map { it.name })
        assertTrue(cs.all { it.code in isoCodes })
        assertEquals(cs.size, cs.map { it.id }.toSet().size)
        assertEquals(listOf("Milan", "Palermo"), cs.first { it.name == "Italy" }.cities.map { it.name })
        assertEquals(listOf("Milan", "Palermo"), cs.first { it.name == "Italy" }.cities.map { it.key })
        assertEquals("US", cs.first { it.name == "US East" }.code)
        assertEquals(rows.size, cs.sumOf { it.serverCount })
    }

    @Test fun windscribeUsesIp3AndGroupKey() {
        val g = list.data.first { it.name == "Italy" }.groups.first()
        val n = g.nodes.first()
        val row = rows.first { it.node.hostname == n.hostname }
        assertEquals(n.ip3, row.node.ip3)
        assertEquals(g.publicKey, row.publicKey)
        assertTrue(rows.all { it.node.ip3 != it.node.hostname && '.' in it.node.ip3 })
    }

    @Test fun windscribeSignInKeepsTheConfigsFields() {
        val a = ws(443)
        assertEquals(priv, a.privateKey)
        assertEquals(listOf("100.96.12.34/32"), a.address)
        assertEquals(psk, a.presharedKey)
        assertEquals(listOf("10.255.255.1"), a.dns)
        assertEquals(443, a.port)
        assertEquals(65142, ws(65142).port)
        assertEquals(1194, ws(51820).port)
    }

    @Test fun windscribeSignInRejectsConfigsWithoutAPresharedKeyOrAPeer() {
        for (bad in listOf(wsConfig(extra = ""), "", "[Interface]\nPrivateKey = $priv\nAddress = 10.0.0.2/32", "PrivateKey = SECRET-KEY-TEXT")) {
            try { Windscribe.signIn(mapOf("config" to bad)); fail() } catch (e: IllegalArgumentException) {
                assertFalse(e.message!!.contains("SECRET")); assertFalse(e.message!!.contains(priv))
            }
        }
    }

    @Test fun windscribeCountryLinkPicksTheLeastLoadedNode() {
        val l = Windscribe.link(rows, Location("windscribe", country = "Netherlands", countryCode = "NL"), ws(443))
        val best = rows.filter { it.region == "Netherlands" }.minOf { it.node.health }
        assertEquals(best, l.server.load)
        val p = l.peer
        assertEquals("${l.server.ip}:443", p.endpoint)
        assertEquals(listOf("0.0.0.0/0"), p.allowedIps)
        assertEquals(listOf("100.96.12.34/32"), p.address)
        assertEquals(listOf("10.255.255.1"), p.dns)
        assertEquals(priv, p.privateKey); assertEquals(psk, p.presharedKey)
        assertEquals(l.server.publicKey, p.publicKey)
        assertEquals("NL", l.server.country)
        assertEquals(1420, p.mtu)
    }

    @Test fun windscribeCityAndRegionFilters() {
        val milan = Windscribe.link(rows, Location("windscribe", country = "Italy", region = "Milan"), ws())
        assertEquals("Milan", milan.server.city)
        val east = Windscribe.link(rows, Location("windscribe", country = "US East", countryCode = "US"), ws())
        assertTrue(east.server.host.startsWith("us-east"))
        val anyUs = Windscribe.countries(rows).filter { it.code == "US" }
        assertEquals(2, anyUs.size)
        val byCode = Windscribe.link(rows, Location("windscribe", countryCode = "CA"), ws())
        assertEquals("CA", byCode.server.country)
        try { Windscribe.link(rows, Location("windscribe", country = "Italy", region = "Rome"), ws()); fail() } catch (e: IOException) { }
    }

    @Test fun windscribeAndExpressPinnedServersAreRefreshed() {
        val sv = Windscribe.link(rows, Location("windscribe", countryCode = "JP"), ws()).server
        assertEquals(sv.ip, Windscribe.link(rows, Location("windscribe", pinned = sv.copy(ip = "203.0.113.9")), ws()).server.ip)
        try { Windscribe.link(rows, Location("windscribe", pinned = sv.copy(host = "gone.whiskergalaxy.com")), ws()); fail() } catch (e: app.unbraid.data.NoLongerOffered) {}
        val ex = ExpressVpn.link(express, Location("expressvpn", countryCode = "SE"), ExpressVpn.Account("u", "p")).server
        // Its old address while the server still has it, else a current one.
        assertEquals(ex.ip, ExpressVpn.link(express, Location("expressvpn", pinned = ex), ExpressVpn.Account("u", "p")).server.ip)
        assertTrue(ExpressVpn.link(express, Location("expressvpn", pinned = ex.copy(ip = "203.0.113.9")), ExpressVpn.Account("u", "p")).server.ip != "203.0.113.9")
        try { ExpressVpn.link(express, Location("expressvpn", pinned = ex.copy(host = "gone.expressnetw.com")), ExpressVpn.Account("u", "p")); fail() } catch (e: app.unbraid.data.NoLongerOffered) {}
        // Nothing but a dotted IPv4 goes into the profile, a saved pinned address included.
        for (bad in listOf("1.2.3.4\nscript-security 2", "1.2.3", "1.2.3.256", "a.b.c.d", " 1.2.3.4", "١.2.3.4"))
            try { ExpressVpn.link(null, Location("expressvpn", pinned = ex.copy(ip = bad)), ExpressVpn.Account("u", "p")); fail(bad) } catch (e: IllegalArgumentException) {}
    }

    @Test fun windscribePinnedServerNeedsNoList() {
        val sv = Windscribe.link(rows, Location("windscribe", countryCode = "JP"), ws()).server
        val again = Windscribe.link(null, Location("windscribe", pinned = sv), ws(80))
        assertEquals(sv, again.server)
        assertEquals("${sv.ip}:80", again.peer.endpoint)
    }

    @Test fun windscribeSkipsNodesWithoutWireguard() {
        val body = """{"data":[{"name":"X","country_code":"nl","status":1,"groups":[
            {"city":"A","wg_pubkey":"","nodes":[{"hostname":"x-1.w.com","ip3":"1.2.3.4","health":1}]},
            {"city":"B","wg_pubkey":"$peerKey","nodes":[{"hostname":"x-2.w.com","ip3":"","health":1},{"hostname":"x-3.w.com","ip3":"1.2.3.5","health":2}]}]},
            {"name":"Down","country_code":"de","status":0,"groups":[{"city":"C","wg_pubkey":"$peerKey","nodes":[{"hostname":"d.w.com","ip3":"1.2.3.6"}]}]}]}"""
        val r = Windscribe.rows(Windscribe.parse(body))
        assertEquals(listOf("x-3.w.com"), r.map { it.node.hostname })
        assertEquals("NL", r.single().code)
        try { Windscribe.parse("SECRET-LIST"); fail() } catch (e: IOException) { assertFalse(e.message!!.contains("SECRET")); assertNull(e.cause) }
    }

    // ---- ExpressVPN

    @Test fun expressSkipsEntriesWithoutStrictIpv4() {
        val valid = express.first { it.ips.any { ip -> ip.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) } }
        val invalid = valid.copy(hostname = "bad.example", ips = listOf("1.2.3.999"), load = 0)
        val loc = Location("expressvpn")
        val account = ExpressVpn.Account("u", "p")
        assertEquals(valid.hostname, ExpressVpn.link(listOf(invalid, valid), loc, account).server.host)
        try { ExpressVpn.link(listOf(invalid), loc, account); fail() } catch (e: IOException) { assertTrue(e.message!!.startsWith("No ExpressVPN server")) }
    }

    @Test fun expressCountriesCarryIsoCodes() {
        val cs = ExpressVpn.countries(express)
        assertEquals(cs.map { it.name }.sorted(), cs.map { it.name })
        val bad = cs.filter { it.code !in isoCodes }
        assertTrue("not ISO: $bad", bad.isEmpty())
        assertEquals("US", cs.first { it.name == "USA" }.code)
        assertEquals("GB", cs.first { it.name == "UK" }.code)
        assertEquals("IN", cs.first { it.name == "India (via UK)" }.code)
        assertEquals("HK", cs.first { it.name == "Hong Kong" }.code)
        assertEquals("TR", cs.first { it.name == "Turkey" }.code)
        assertTrue(cs.first { it.name == "USA" }.cities.size >= 3)
        assertEquals(express.size, cs.sumOf { it.serverCount })
    }

    @Test fun expressLinkParsesIntoAnOpenvpnEndpoint() {
        val acct = ExpressVpn.Account("user1", "pass1")
        val l = ExpressVpn.link(express, Location("expressvpn", country = "Netherlands", region = "Amsterdam"), acct)
        assertEquals("Amsterdam", l.server.city)
        assertEquals("NL", l.server.country)
        assertEquals("NL", ExpressVpn.link(express, Location("expressvpn", countryCode = "NL"), acct).server.country)
        assertTrue(l.server.host.endsWith(".expressnetw.com"))
        assertTrue(express.first { it.hostname == l.server.host }.ips.contains(l.server.ip))
        assertEquals("user1", l.profile.username); assertEquals("pass1", l.profile.password)
        val e = Manual.ovpnEndpoint("express", l.profile)
        assertEquals("openvpn-client", e.str("type"))
        assertEquals(l.server.ip, e.str("server")); assertEquals("1195", e.str("server_port")); assertEquals("udp", e.str("network"))
        assertEquals("user1", e.str("username"))
        assertEquals("SHA512", e.str("auth"))
        assertEquals(listOf("AES-256-GCM", "AES-256-CBC", "AES-128-GCM"), e.getValue("data_ciphers").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("AES-256-GCM", e.str("data_ciphers_fallback"))
        assertEquals("1200", e.str("mss_fix")); assertEquals("1300", e.str("fragment"))
        val tls = e.getValue("tls").jsonObject
        assertEquals("Server", tls.str("server_name")); assertEquals("name-prefix", tls.str("server_name_type"))
        assertEquals("server", tls.str("remote_certificate_tls"))
        assertNotNull(tls["client_certificate"]); assertTrue(tls.str("client_key")!!.startsWith("-----BEGIN RSA PRIVATE KEY-----"))
        assertEquals(2, Regex("BEGIN CERTIFICATE").findAll(tls.str("certificate")!!).count())
        val wrap = tls.getValue("control_wrap").jsonObject
        assertEquals("tls_auth", wrap.str("type")); assertEquals("client", wrap.str("direction"))
        assertEquals(16, wrap.str("key")!!.lines().count { it.matches(Regex("[0-9a-f]{32}")) })
    }

    @Test fun expressSignInChecksTheFormatOnly() {
        val a = ExpressVpn.account(ExpressVpn.signIn(mapOf("username" to " abc123 ", "password" to "pw")))
        assertEquals("abc123", a.username)
        for (bad in listOf(mapOf("username" to "", "password" to "x"), mapOf("username" to "a", "password" to "b c"), mapOf("username" to "a\nb", "password" to "SECRET"))) {
            try { ExpressVpn.signIn(bad); fail() } catch (e: IllegalArgumentException) { assertFalse(e.message!!.contains("SECRET")) }
        }
    }

    @Test fun expressPinnedServerKeepsItsAddress() {
        val sv = ExpressVpn.link(express, Location("expressvpn", countryCode = "SE"), ExpressVpn.Account("u", "p")).server
        val l = ExpressVpn.link(null, Location("expressvpn", pinned = sv), ExpressVpn.Account("u", "p"))
        assertEquals(sv, l.server)
        assertTrue(l.profile.config.contains("remote ${sv.ip} 1195"))
    }

    // ---- sing-box

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content

    private fun arr(vararg v: String) = JsonArray(v.map(::JsonPrimitive))

    /** Same shape as Compiler's private wg(). */
    private fun wg(tag: String, p: WgPeer) = buildJsonObject {
        put("type", "wireguard"); put("tag", tag)
        put("mtu", p.mtu)
        put("address", JsonArray(p.address.map(::JsonPrimitive)))
        put("private_key", p.privateKey)
        putJsonArray("peers") {
            add(buildJsonObject {
                put("address", p.endpoint.substringBeforeLast(':'))
                put("port", p.endpoint.substringAfterLast(':').toInt())
                put("public_key", p.publicKey)
                p.presharedKey?.let { put("pre_shared_key", it) }
                put("allowed_ips", arr("0.0.0.0/0"))
                put("persistent_keepalive_interval", 25)
            })
        }
    }

    private fun write(file: String, tag: String, endpoint: JsonObject): File {
        val cfg = buildJsonObject {
            putJsonObject("log") { put("level", "info") }
            putJsonArray("endpoints") { add(endpoint) }
            putJsonArray("outbounds") { add(buildJsonObject { put("type", "direct"); put("tag", "direct") }) }
            putJsonObject("route") { put("final", tag) }
        }
        return File("build/keyproviders2").apply { mkdirs() }.resolve("$file.json").also { it.writeText(pretty.encodeToString(JsonObject.serializer(), cfg)) }
    }

    private fun check(f: File) {
        val bin = File(System.getenv("SING_BOX_CLI") ?: "${System.getenv("WORK") ?: "${System.getProperty("user.home")}/.cache/unbraid-libbox"}/sing-box-cli")
        assumeTrue("sing-box-cli not found", bin.canExecute())
        val log = File.createTempFile("sing-box-check", ".log")
        try {
            val p = ProcessBuilder(bin.path, "check", "-c", f.absolutePath).redirectErrorStream(true).redirectOutput(log).start()
            val done = p.waitFor(60, TimeUnit.SECONDS)
            if (!done) p.destroyForcibly()
            val out = log.readText()
            assertTrue("sing-box check ${f.name} timed out: $out", done)
            assertEquals("sing-box check ${f.name}: $out", 0, p.exitValue())
        } finally {
            log.delete()
        }
    }

    @Test fun singBoxAcceptsWindscribeEndpoints() {
        for ((name, loc) in listOf("us" to Location("windscribe", country = "US East"), "nl" to Location("windscribe", countryCode = "NL"))) {
            val l: WgLink = Windscribe.link(rows, loc, ws(443))
            val f = write("windscribe-$name", "ws-$name", wg("ws-$name", l.peer))
            check(f)
        }
    }

    @Test fun singBoxAcceptsExpressvpnEndpoints() {
        for ((name, loc) in listOf("nl" to Location("expressvpn", country = "Netherlands"), "us" to Location("expressvpn", country = "USA", region = "Atlanta"))) {
            val l: OvpnLink = ExpressVpn.link(express, loc, ExpressVpn.Account("user1", "pass1"))
            val f = write("expressvpn-$name", "ev-$name", Manual.ovpnEndpoint("ev-$name", l.profile))
            check(f)
        }
    }
}
