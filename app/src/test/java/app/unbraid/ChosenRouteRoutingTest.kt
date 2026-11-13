package app.unbraid

import app.unbraid.data.DIRECT
import app.unbraid.data.Filter
import app.unbraid.data.Home
import app.unbraid.data.Prefs
import app.unbraid.data.Setup
import app.unbraid.data.Store
import app.unbraid.data.VERSION
import app.unbraid.vpn.Compiler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** What the engine is given for apps that chose a route, compared with apps that follow the default. */
class ChosenRouteRoutingTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun sealed(text: String) = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }.let { it.iv + it.doFinal(text.toByteArray()) }

    private fun compile(s: Setup, lockdown: Boolean = false) =
        Json.parseToJsonElement(Compiler.compile(Compiler.Input(s, emptyMap(), "app.unbraid", lockdown, "/tmp/ts", "/tmp/cache.db"))).jsonObject
    private fun JsonObject.excluded() = this["inbounds"]!!.jsonArray[0].jsonObject["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content }

    /**
     * A file as the released version wrote it after removing the only route (a saved server, the default): the default
     * fell to Direct, the app set to Direct kept its entry, home Tailscale stays on. After the update the engine gets
     * exactly the same config: the app stays outside the VPN, its DNS and home network handling unchanged.
     */
    @Test fun anUpdatedFileRoutesTheSame() {
        val v1 = """{"routes":[],"defaultRoute":"direct","apps":{"org.example.bank":"direct"},""" +
            """"home":{"tailscale":true,"subnets":["192.168.1.0/24"]},"prefs":{"filter":{"id":"quad9","direct":true}},"version":1}"""
        val dir = tmp.newFolder()
        File(dir, "setup.bin").writeBytes(sealed(v1))
        val before = Json { ignoreUnknownKeys = true }.decodeFromString(Setup.serializer(), v1) // as the released version reads it
        val after = Store(dir) { key }.setup.value
        assertEquals(VERSION, after.version)
        assertEquals(before.apps, after.apps)
        for (lockdown in listOf(false, true)) assertEquals(compile(before, lockdown), compile(after, lockdown))
        assertEquals(listOf("org.example.bank", "app.unbraid"), compile(after).excluded())
    }

    /**
     * Direct chosen for an app is outside unbraid at Android level ([Setup.outside]); an app that only follows a Direct
     * default passes through, for the filter and the home network. The model says which, as the compiler does it.
     */
    @Test fun chosenDirectIsOutsideAndFollowingDirectIsNot() {
        val s = Setup(routes = emptyList(), defaultRoute = DIRECT, apps = mapOf("org.example.bank" to DIRECT), home = Home(tailscale = true),
            prefs = Prefs(filter = Filter("quad9", direct = true)))
        assertTrue(s.outside("org.example.bank"))
        assertFalse(s.outside("org.example.other")) // follows the Direct default
        assertEquals(s.apps.keys.filter(s::outside) + "app.unbraid", compile(s).excluded())
        // Passed through for the filter (every Direct app, or this one): not outside, and not left out of the VPN.
        for (f in listOf(Filter("quad9", direct = true, directApps = true), Filter("quad9", direct = true, apps = mapOf("org.example.bank" to true)))) {
            val t = s.copy(prefs = Prefs(filter = f))
            assertFalse(t.outside("org.example.bank"))
            assertEquals(listOf("app.unbraid"), compile(t).excluded())
        }
    }
}
