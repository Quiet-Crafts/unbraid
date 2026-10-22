package app.unbraid

import app.unbraid.data.Catalog
import org.junit.Assert.assertEquals
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
}
