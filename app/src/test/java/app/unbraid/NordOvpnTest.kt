package app.unbraid

import app.unbraid.data.Manual
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class NordOvpnTest {
    private fun ovpn(n: String) = javaClass.getResource("/ovpn/$n")!!.readText()

    @Test fun nordDoubleVpnProfilesParseAndValidate() {
        for (n in listOf("nord-double.udp.ovpn", "nord-double.tcp.ovpn")) {
            val p = Manual.parseOvpn(ovpn(n), "UK-NL #19", "user", "pass")
            val ep = Manual.ovpnEndpoint("r-nord", p)
            assertEquals("openvpn-client", ep["type"]!!.jsonPrimitive.content)
            assertEquals("true", ep["ping_restart_disabled"]!!.jsonPrimitive.content)
            assertNull(ep["ping_restart"])
            assertEquals("15s", ep["ping_interval"]!!.jsonPrimitive.content)
            File("build/manual").apply { mkdirs() }.resolve("nord-$n.json").writeText(
                """{"log":{"level":"warn"},"endpoints":[$ep],"route":{"final":"r-nord"}}""",
            )
        }
    }

    @Test fun nordProfilesStillOfferTheCbcCipherNordServersNeed() {
        val p = Manual.parseOvpn(app.unbraid.data.Nord.withLegacyCiphers(ovpn("nord-double.udp.ovpn")), "dv", "user", "pass")
        val ep = Manual.ovpnEndpoint("r", p)
        val ciphers = ep["data_ciphers"]!!.jsonArray.map { it.jsonPrimitive.content.uppercase() }
        assert("AES-256-CBC" in ciphers && "AES-256-GCM" in ciphers) { ciphers.toString() }
        assertEquals("AES-256-CBC", ep["data_ciphers_fallback"]!!.jsonPrimitive.content.uppercase())
    }
}
