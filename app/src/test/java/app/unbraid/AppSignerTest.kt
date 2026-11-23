package app.unbraid

import app.unbraid.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppSignerTest {
    @Test fun anOlderBuildMustRefuseAnAppExceptionBoundToItsSigner() {
        val certificate = "ab".repeat(32)
        val app = "org.example.mail"
        val setup = Json { ignoreUnknownKeys = true }.decodeFromString<Setup>(
            """{"apps":{"$app":"direct"},"appSigners":{"$app":"$certificate"}}"""
        )
        assertTrue(setup.stamped.version >= 5)
        val restored = Backup.read(Backup.export(setup, "", 0)).setup
        assertEquals(setup.apps, restored.apps)
        assertEquals(5, restored.stamped.version)
    }

    @Test fun refusingAProfileSwitchKeepsTheNewerIdentityOfTheOriginalRoute() {
        val app = "org.example.mail"
        val old = "11".repeat(32)
        val newer = "22".repeat(32)
        val json = Json { ignoreUnknownKeys = true }
        val running = json.decodeFromString<Setup>("""{"profile":"Work","apps":{"$app":"direct"},"appSigners":{"$app":"$old"}}""")
        val saved = json.decodeFromString<Setup>("""{"profiles":[{"name":"Work","defaultRoute":"nl","apps":{"$app":"direct"},"appSigners":{"$app":"$newer"}}]}""")
        val backed = json.encodeToJsonElement(Setup.serializer(), saved.back(running)).jsonObject
        assertEquals(newer, backed["appSigners"]?.jsonObject?.get(app)?.jsonPrimitive?.content)
    }
}
