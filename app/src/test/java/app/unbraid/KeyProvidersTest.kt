package app.unbraid

import app.unbraid.data.AirVpn
import app.unbraid.data.Catalog
import app.unbraid.data.KeyProvider
import app.unbraid.data.Location
import app.unbraid.data.Proton
import app.unbraid.data.ProtonFree
import app.unbraid.data.Snapshot
import app.unbraid.data.Surfshark
import app.unbraid.data.WgLink
import app.unbraid.data.WgPeer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class KeyProvidersTest {
    private val priv = "dW5icmFpZC10ZXN0LXByaXZhdGUta2V5LTAwMDAwMDE="
    private val psk = "dW5icmFpZC10ZXN0LXByZXNoYXJlZC1rZXktMDAwMDE="
    private val serverKey = "vOgsnvam/Xc6w8zfQeGcynOr8RMxYzpki3b0EaOkEoI="

    private fun res(name: String) = javaClass.getResource("/$name")!!.readText()
    private fun catalog(name: String) = Catalog.parse(res("catalog_$name.json"), "wireguard")
    private val iso = Regex("[A-Z]{2}")

    private fun conf(address: String, dns: String, endpoint: String, preshared: Boolean = false, extra: String = "") = """
        [Interface]
        # Key for Android
        PrivateKey = $priv
        Address = $address
        DNS = $dns
        $extra

        [Peer]
        PublicKey = $serverKey
        ${if (preshared) "PresharedKey = $psk" else ""}
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = $endpoint
    """.trimIndent()

    private fun <T : KeyProvider> T.signed(key: String) = account(signIn(mapOf("key" to key)))

    private fun assertRejected(p: KeyProvider, key: String) {
        try {
            p.signIn(mapOf("key" to key))
            fail("accepted: ${key.take(8)}")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message!!.contains(priv))
        }
    }

    @Before fun noNetwork() {
        Surfshark.addressBook = Snapshot.memory()
        Surfshark.catalogue = { throw IOException("offline") }
    }

    private fun cityLoc(provider: String, entries: List<Catalog.Entry>): Location {
        val c = Catalog.countries(entries).first { it.cities.isNotEmpty() }
        val city = c.cities.first()
        return Location(provider, countryCode = c.code, country = c.name, city = city.name, region = city.key)
    }

    // ---- Proton

    @Test fun protonDropsSecureCoreAndTor() {
        val raw = catalog("protonvpn")
        val kept = Proton.filtered(raw)
        assertTrue(raw.any { it.serverName!!.matches(Regex("(CH|IS|SE)-[A-Z]{2}#\\d+")) })
        assertTrue(kept.isNotEmpty() && kept.size < raw.size)
        assertTrue(kept.none { it.serverName!!.matches(Regex("(CH|IS|SE)-[A-Z]{2}#\\d+")) || it.serverName!!.endsWith("-TOR") })
        val cs = Catalog.countries(kept)
        assertTrue(cs.isNotEmpty())
        assertTrue(cs.all { iso.matches(it.code) && it.serverCount > 0 })
    }

    @Test fun protonBareKeyConnectsToACountryAndACity() {
        val a = Proton.signed(priv)
        val entries = Proton.filtered(catalog("protonvpn"))
        val country = Location("proton", country = "Germany", countryCode = "DE")
        val l = Proton.link(country, a) { entries }
        val ips = entries.filter { it.place == "Germany" }.map { it.ipv4 }
        assertTrue(l.peer.endpoint.substringBefore(':') in ips)
        assertEquals("Germany", entries.first { it.ipv4 == l.server.ip }.place)
        assertEquals(l.server.publicKey, l.peer.publicKey)
        assertEquals(listOf("10.2.0.2/32"), l.peer.address)
        assertEquals(listOf("10.2.0.1"), l.peer.dns)
        assertTrue(l.peer.endpoint.endsWith(":51820"))
        assertEquals(listOf("0.0.0.0/0"), l.peer.allowedIps)
        assertNull(l.peer.presharedKey)
        assertEquals(priv, l.peer.privateKey)

        val byCode = Proton.link(Location("proton", countryCode = "DE"), a) { entries }
        assertEquals("Germany", entries.first { it.ipv4 == byCode.server.ip }.place)
        assertEquals("DE", byCode.server.country)

        val city = cityLoc("proton", entries)
        val lc = Proton.link(city, a) { entries }
        assertEquals(city.region, lc.server.city)
        assertEquals(city.country, entries.first { it.ipv4 == lc.server.ip }.place)
    }

    @Test fun protonFreeOffersOnlyFreeServers() {
        val raw = catalog("protonvpn").map { if (it.place == "Austria") it.copy(free = true) else it }
        val free = ProtonFree.filtered(raw)
        assertTrue(free.isNotEmpty() && free.all { it.free && it.place == "Austria" })
        val all = Proton.filtered(raw)
        assertTrue(all.containsAll(free) && all.any { !it.free })
        assertEquals(listOf("Austria"), Catalog.countries(free).map { it.name })
        assertEquals("proton-free", ProtonFree.id)
        assertEquals("Proton VPN Free", ProtonFree.name)
        assertEquals("proton", Proton.id)
        assertTrue(ProtonFree.help.contains("free-vpn/server"))

        val a = ProtonFree.signed(priv)
        val l = ProtonFree.link(Location("proton-free", countryCode = "AT"), a) { free }
        assertEquals("Austria", free.first { it.ipv4 == l.server.ip }.place)
        try {
            ProtonFree.link(Location("proton-free", country = "Germany"), a) { free }
            fail("Germany has no free server")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Germany"))
        }
    }

    @Test fun protonConfOverridesDefaults() {
        val a = Proton.signed(conf("10.2.0.2/32, 2a07:b944::2:2/128", "10.2.0.1", "79.135.105.1:51820", extra = "MTU = 1400"))
        assertEquals(priv, a.privateKey)
        assertEquals(listOf("10.2.0.2/32"), a.address)
        assertEquals(listOf("10.2.0.1"), a.dns)
        assertEquals(1400, a.mtu)
        val b = Proton.signed(conf("10.3.0.2/32", "10.3.0.1", "79.135.105.1:51820"))
        assertEquals(listOf("10.3.0.2/32"), b.address)
        assertEquals(listOf("10.3.0.1"), b.dns)
        assertEquals(1420, b.mtu)
    }

    @Test fun protonPinnedServerNeedsNoList() {
        val a = Proton.signed(priv)
        val sv = Proton.link(Location("proton", country = "Germany"), a) { Proton.filtered(catalog("protonvpn")) }.server
        val l = Proton.link(Location("proton", pinned = sv), a) { throw IOException("offline") }
        assertEquals(sv, l.server)
        assertEquals("${sv.ip}:51820", l.peer.endpoint)
    }

    @Test fun badKeysAreRejected() {
        for (p in listOf(Proton, ProtonFree, Surfshark, AirVpn)) {
            assertRejected(p, "")
            assertRejected(p, "hello")
            assertRejected(p, "a".repeat(64))
            assertRejected(p, priv.dropLast(1))
            assertRejected(p, "!" + priv.drop(1))
            assertRejected(p, conf("10.2.0.2/32", "10.2.0.1", "1.2.3.4:51820").replace(priv, "short"))
            assertRejected(p, conf("10.2.0.2/32", "10.2.0.1", "1.2.3.4:51820").replace("[Peer]", ""))
        }
    }

    // ---- Surfshark

    private val clusters = Surfshark.parse(res("surfshark_clusters.json"))

    @Test fun surfsharkLiveListHasCountriesAndKeysButNoIps() {
        assertTrue(clusters.isNotEmpty())
        assertTrue(clusters.all { it.ips.isEmpty() && it.hostname.endsWith(".prod.surfshark.com") && it.wgpubkey!!.length == 44 })
        val cs = Catalog.countries(clusters)
        assertTrue(cs.any { it.name == "Netherlands" && it.code == "NL" && "Amsterdam" in it.cities.map { c -> c.name } })
        assertTrue(cs.all { iso.matches(it.code) })
    }

    /** The chosen host's address comes from the catalogue, its key from Surfshark's own list; nothing is looked up. */
    @Test fun surfsharkTakesTheChosenHostsAddressFromTheCatalogue() {
        surfsharkAt(clusters)
        val a = Surfshark.signed(priv)
        val l = Surfshark.link(Location("surfshark", country = "Netherlands"), a) { clusters }
        assertTrue(l.server.host.startsWith("nl-"))
        assertEquals("203.0.113.7:51820", l.peer.endpoint)
        assertEquals(listOf("10.14.0.2/16"), l.peer.address)
        assertEquals(listOf("162.252.172.57", "149.154.159.92"), l.peer.dns)
        assertEquals(1280, l.peer.mtu)
        assertEquals(clusters.first { it.hostname == l.server.host }.wgpubkey, l.peer.publicKey)
        assertEquals("Amsterdam", l.server.city)

        val byCode = Surfshark.link(Location("surfshark", countryCode = "DE"), a) { clusters }
        assertEquals("DE", byCode.server.country)
        assertTrue(byCode.server.host.startsWith("de-"))

        val city = cityLoc("surfshark", clusters)
        val lc = Surfshark.link(city, a) { clusters }
        assertEquals(city.region, lc.server.city)
    }

    private val staticHost = Catalog.Entry("wireguard", "Germany", "Europe", "Frankfurt", hostname = "de-fra-st001.prod.surfshark.com", wgpubkey = serverKey, ips = listOf("5.5.5.5"))

    @Test fun surfsharkFallsBackToCatalogueIpsOfTheSameLocation() {
        val cat = catalog("surfshark") + staticHost
        val de = Surfshark.fallback(cat).filter { it.place == "Germany" }.flatMap { it.ips }
        assertTrue(de.isNotEmpty())
        Surfshark.catalogue = { cat }
        val a = Surfshark.signed(priv)
        val l = Surfshark.link(Location("surfshark", country = "Germany"), a) { clusters }
        assertTrue(l.peer.endpoint.substringBefore(':') in de)
        assertEquals("DE", l.server.country)
        assertFalse(l.peer.endpoint.startsWith("5.5.5.5:"))

        val city = cityLoc("surfshark", Surfshark.fallback(cat))
        val lc = Surfshark.link(city, a) { clusters }
        assertEquals(city.region, lc.server.city)
        assertEquals(city.countryCode, lc.server.country)
    }

    /** Each host once, also when the list is asked for more than it has; the catalogue is read once a day, its last copy offline. */
    @Test fun surfsharkListsEachHostOnceAndReadsTheCatalogueOnceADay() {
        var reads = 0
        val nl = clusters.filter { it.place == "Netherlands" }
        Surfshark.catalogue = { reads++; nl.map { Catalog.Entry("wireguard", "Netherlands", hostname = it.hostname, wgpubkey = "unused", ips = listOf("203.0.113.7")) } }
        Surfshark.live = { res("surfshark_clusters.json") }
        Surfshark.snapshot = Snapshot.memory()
        val acc = Surfshark.signIn(mapOf("key" to priv))
        val listed = Surfshark.servers(Location("surfshark", country = "Netherlands"), acc, 200)
        assertEquals(nl.size, listed.size)
        assertEquals(listed.size, listed.map { it.host }.distinct().size)
        Surfshark.servers(Location("surfshark", country = "Netherlands"), acc, 200)
        assertEquals(1, reads)
        assertEquals(mapOf(nl.first().hostname to listOf("203.0.113.7")), Surfshark.addresses().filterKeys { it == nl.first().hostname })
        // Two days later, the catalogue unreachable and nothing in memory (a new process): the copy kept on disk, and no
        // second try within 15 minutes.
        Surfshark.catalogue = { reads++; throw IOException("offline") }
        val later = System.currentTimeMillis() + 2 * 86_400_000L
        assertEquals(listOf("203.0.113.7"), Surfshark.addresses(later)[nl.first().hostname])
        Surfshark.addresses(later + 60_000)
        assertEquals(2, reads)
        // A host name in the catalogue's addresses is never used (it would be looked up on the phone's network).
        Surfshark.catalogue = { listOf(Catalog.Entry("wireguard", "Netherlands", hostname = nl.first().hostname, wgpubkey = "unused", ips = listOf("evil.example.com", "203.0.113.8"))) }
        assertEquals(listOf("203.0.113.8"), Surfshark.addresses()[nl.first().hostname])
        Surfshark.live = { throw IOException("offline") }
    }

    @Test fun surfsharkSkipsHostsWithoutAnAddress() {
        val wanted = clusters.last { it.place == "Germany" }
        Surfshark.catalogue = { listOf(Catalog.Entry("wireguard", "Germany", hostname = wanted.hostname, wgpubkey = "unused", ips = listOf("198.51.100.9"))) }
        val l = Surfshark.link(Location("surfshark", country = "Germany"), Surfshark.signed(priv)) { clusters }
        assertEquals("198.51.100.9:51820", l.peer.endpoint)
        Surfshark.catalogue = { listOf(Catalog.Entry("wireguard", "Germany", hostname = "elsewhere.prod.surfshark.com", wgpubkey = "unused", ips = listOf("198.51.100.9"))) }
        try {
            Surfshark.link(Location("surfshark", country = "Germany"), Surfshark.signed(priv)) { clusters }
            fail("no server has an address")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Germany"))
        }
    }

    @Test fun surfsharkCatalogueFallbackHasIpsAndNoStaticServers() {
        val raw = catalog("surfshark") + Catalog.Entry("wireguard", "Germany", "Europe", "Frankfurt", hostname = "de-fra-st001.prod.surfshark.com", wgpubkey = serverKey, ips = listOf("5.5.5.5"))
        val kept = Surfshark.fallback(raw)
        val static = Regex("-st\\d+\\.")
        assertTrue(raw.count { static.containsMatchIn(it.hostname) } >= 1)
        assertEquals(raw.filterNot { static.containsMatchIn(it.hostname) }, kept)
        val l = Surfshark.link(Location("surfshark", country = "Netherlands"), Surfshark.signed(priv)) { kept }
        assertTrue(l.peer.endpoint.substringBefore(':') in kept.filter { it.place == "Netherlands" }.flatMap { it.ips })
        assertTrue(l.peer.endpoint.endsWith(":51820"))
    }

    @Test fun surfsharkConfIsUsedAsPasted() {
        val a = Surfshark.signed(conf("10.14.0.2/16", "162.252.172.57, 149.154.159.92", "nl-ams.prod.surfshark.com:51820"))
        assertEquals(listOf("10.14.0.2/16"), a.address)
        assertEquals(listOf("162.252.172.57", "149.154.159.92"), a.dns)
        assertEquals(51820, a.port)
        assertEquals(1280, a.mtu)
        assertNull(a.presharedKey)
        val b = Surfshark.signed(conf("10.64.222.21/16", "1.1.1.1", "nl-ams.prod.surfshark.com:51820"))
        assertEquals(listOf("10.64.222.21/16"), b.address)
        assertEquals(listOf("1.1.1.1"), b.dns)
    }

    // ---- AirVPN

    private val nodes = AirVpn.parse(res("airvpn_status.json"), serverKey)
    private val airConf = conf("10.164.20.6/32, fd7d:76ee:e68f:a993:1:2:3:4/128", "10.128.0.1", "at.vpn.airdns.org:1637", preshared = true)

    @Test fun airvpnLiveListUsesHealthyIpv4Entries() {
        assertTrue(nodes.isNotEmpty())
        assertTrue(nodes.all { it.vpn == "wireguard" && it.ips.size == 1 && '.' in it.ips[0] && it.wgpubkey == serverKey && it.hostname.endsWith(".vpn.airdns.org") })
        val cs = Catalog.countries(nodes)
        assertTrue(cs.any { it.name == "Netherlands" && it.code == "NL" })
        assertTrue(cs.all { iso.matches(it.code) })
        assertTrue(nodes.mapNotNull { it.city }.none { ',' in it })
        val body = """{"servers":[{"public_name":"A","country_name":"Austria","country_code":"at","location":"Vienna","continent":"Europe","ip_v4_in1":"1.2.3.4","health":"warning"},
            {"public_name":"B","country_name":"Austria","country_code":"at","location":"Vienna","continent":"Europe","ip_v4_in1":"1.2.3.5","health":"ok"}]}"""
        assertEquals(listOf("1.2.3.5"), AirVpn.parse(body, serverKey).map { it.ips.single() })
    }

    @Test fun airvpnNeedsTheWholeConf() {
        assertRejected(AirVpn, priv)
        assertRejected(AirVpn, conf("10.164.20.6/32", "10.128.0.1", "at.vpn.airdns.org:1637", preshared = false))
        val a = AirVpn.signed(airConf)
        assertEquals(listOf("10.164.20.6/32"), a.address)
        assertEquals(psk, a.presharedKey)
        assertEquals(serverKey, a.publicKey)
        assertEquals(1637, a.port)
        assertEquals(listOf("10.128.0.1"), a.dns)
    }

    @Test fun airvpnConnectsWithPresharedKeyOnPort1637() {
        val a = AirVpn.signed(airConf)
        val entries = AirVpn.parse(res("airvpn_status.json"), a.publicKey!!)
        val l = AirVpn.link(Location("airvpn", country = "Netherlands"), a) { entries }
        assertTrue(l.peer.endpoint.substringBefore(':') in entries.filter { it.place == "Netherlands" }.map { it.ipv4 })
        assertTrue(l.peer.endpoint.endsWith(":1637"))
        assertEquals(psk, l.peer.presharedKey)
        assertEquals(serverKey, l.peer.publicKey)
        assertEquals(listOf("10.164.20.6/32"), l.peer.address)
        assertEquals(listOf("10.128.0.1"), l.peer.dns)

        val byCode = AirVpn.link(Location("airvpn", countryCode = "NL"), a) { entries }
        assertEquals("NL", byCode.server.country)
        assertEquals("Netherlands", entries.first { it.ipv4 == byCode.server.ip }.place)

        val city = cityLoc("airvpn", entries)
        assertEquals(city.region, AirVpn.link(city, a) { entries }.server.city)
    }

    @Test fun airvpnCatalogueFallbackUsesTheAccountKey() {
        val entries = catalog("airvpn")
        assertTrue(entries.isNotEmpty() && entries.all { '.' in it.ipv4!! })
        val l = AirVpn.link(Location("airvpn", country = "Germany"), AirVpn.signed(airConf)) { entries }
        // The key is the account's own [Peer] key (every AirVPN server shares it), never the catalogue's.
        assertEquals(serverKey, l.peer.publicKey)
        assertTrue(l.peer.endpoint.endsWith(":1637"))
    }

    // ---- sing-box

    private val pretty = Json { prettyPrint = true }

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

    private fun write(file: String, l: WgLink): File {
        val cfg = buildJsonObject {
            putJsonObject("log") { put("level", "info") }
            putJsonArray("endpoints") { add(wg(file, l.peer)) }
            putJsonArray("outbounds") { add(buildJsonObject { put("type", "direct"); put("tag", "direct") }) }
            putJsonObject("route") { put("final", file) }
        }
        return File("build/keyproviders").apply { mkdirs() }.resolve("$file.json").also { it.writeText(pretty.encodeToString(JsonObject.serializer(), cfg)) }
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

    @Test fun singBoxAcceptsEveryLink() {
        surfsharkAt(clusters)
        val pe = Proton.filtered(catalog("protonvpn"))
        val pa = Proton.signed(priv)
        val sa = Surfshark.signed(priv)
        val aa = AirVpn.signed(airConf)
        val ae = AirVpn.parse(res("airvpn_status.json"), aa.publicKey!!)
        val links = listOf(
            "proton-country" to Proton.link(Location("proton", country = "Germany"), pa) { pe },
            "proton-city" to Proton.link(cityLoc("proton", pe), pa) { pe },
            "surfshark-country" to Surfshark.link(Location("surfshark", country = "Netherlands"), sa) { clusters },
            "surfshark-city" to Surfshark.link(cityLoc("surfshark", clusters), sa) { clusters },
            "airvpn-country" to AirVpn.link(Location("airvpn", country = "Germany"), aa) { ae },
            "airvpn-city" to AirVpn.link(cityLoc("airvpn", ae), aa) { ae },
        )
        val files = links.map { (name, l) -> write(name, l) }
        files.forEach(::check)
    }
}
