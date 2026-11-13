package app.unbraid

import app.unbraid.data.Catalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    @Test fun countryCodes() {
        mapOf(
            "Netherlands" to "NL", "United States" to "US", "USA" to "US", "Hong Kong" to "HK", "Macau" to "MO", "Turkey" to "TR",
            "Myanmar" to "MM", "Czech Republic" to "CZ", "United Kingdom" to "GB", "UK" to "GB", "South Korea" to "KR",
            "Bosnia and Herzegovina" to "BA", "United Arab Emirates" to "AE", "Italy" to "IT", "Switzerland" to "CH",
            "Democratic Republic of the Congo" to "CD", "Cote d'Ivoire" to "CI", "Brunei Darussalam" to "BN", "Palestine, State of" to "PS",
            "Atlantis" to "··",
        ).forEach { (name, code) -> assertEquals(name, code, Catalog.code(name)) }
    }

    @Test fun everyCatalogueCountryGetsARealIsoCode() {
        val iso = java.util.Locale.getISOCountries().toSet()
        for (p in listOf("protonvpn", "surfshark", "airvpn", "expressvpn")) {
            val vpn = if (p == "expressvpn") "openvpn" else "wireguard"
            val entries = Catalog.parse(javaClass.getResource("/catalog_$p.json")!!.readText(), vpn)
            val bad = Catalog.countries(entries).filter { it.code !in iso }.map { "${it.name}→${it.code}" }
            assertEquals("$p: $bad", emptyList<String>(), bad)
        }
    }

    /** The catalogue's publication time is read with it; a catalogue without one says 0 (unknown). */
    @Test fun publicationTime() {
        assertEquals(1785990858L, Catalog.timestamp(javaClass.getResource("/catalog_protonvpn.json")!!.readText()))
        assertEquals(0L, Catalog.timestamp("""{"servers":[]}"""))
    }

    /** Only internet addresses are kept: a local, malformed or IPv6 address is dropped, and so is a server left with none. */
    @Test fun onlyServerAddressesAreKept() {
        fun e(host: String, vararg ips: String) =
            """{"vpn":"wireguard","country":"Netherlands","hostname":"$host","wgpubkey":"k","ips":[${ips.joinToString(",") { "\"$it\"" }}]}"""
        val body = """{"servers":[${listOf(
            e("mixed", "192.168.1.1", "2001:db8::1", "185.65.135.9", "010.1.2.3"),
            e("local", "10.0.0.9", "127.0.0.1", "100.64.0.1"),
            e("named", "vpn.example.com"),
            e("prefix", "185.65.135.10/32"),
        ).joinToString(",")}]}"""
        val entries = Catalog.parse(body, "wireguard")
        assertEquals(listOf("mixed"), entries.map { it.hostname })
        assertEquals(listOf("185.65.135.9"), entries.single().ips)
        assertEquals("185.65.135.9", entries.single().ipv4)
        for (p in listOf("protonvpn", "surfshark", "airvpn", "expressvpn")) {
            val vpn = if (p == "expressvpn") "openvpn" else "wireguard"
            val all = Catalog.parse(javaClass.getResource("/catalog_$p.json")!!.readText(), vpn)
            assertTrue(p, all.isNotEmpty() && all.all { it.ips.all(app.unbraid.data.Addr::server) })
        }
    }
}
