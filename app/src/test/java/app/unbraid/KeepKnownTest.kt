package app.unbraid

import app.unbraid.data.DIRECT
import app.unbraid.data.Location
import app.unbraid.data.Profile
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.ui.keepKnown
import app.unbraid.ui.knownApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class KeepKnownTest {
    private val s = Setup(
        routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands")), Route("us", Location("nord", 228, "US", "United States"))),
        defaultRoute = "nl",
        apps = mapOf("org.example.kept" to "us", "org.example.archived" to "us", "org.example.gone" to DIRECT),
        profiles = listOf(Profile("Trip", "us", mapOf("org.example.archived" to "nl", "org.example.gone" to "us"))),
    )

    /** An archived app is still known to Android: its choices stay, in the setup and every profile. A removed app's go. */
    @Test fun archivedAppsKeepTheirRoute() {
        val known = setOf("org.example.kept", "org.example.archived")
        val kept = keepKnown(s, known)
        assertEquals(mapOf("org.example.kept" to "us", "org.example.archived" to "us"), kept.apps)
        assertEquals(mapOf("org.example.archived" to "nl"), kept.profiles.single().apps)
        // Nothing gone: the setup is unchanged, so nothing is written.
        assertEquals(s, keepKnown(s, known + "org.example.gone"))
        // Without the archived app in the known set (what happened before), its route went.
        assertEquals(setOf("org.example.kept"), keepKnown(s, setOf("org.example.kept")).apps.keys)
        assertSame(s.routes, kept.routes)
    }

    /**
     * Android 15's archived-apps query also lists an app uninstalled with its data kept: it isn't archived, so its
     * route goes, while an archived app's stays.
     */
    @Test fun anAppUninstalledWithItsDataKeptLosesItsRoute() {
        val known = knownApps(listOf("org.example.kept"), listOf("org.example.kept" to false, "org.example.archived" to true, "org.example.gone" to false))
        assertEquals(setOf("org.example.kept", "org.example.archived"), known)
        assertEquals(setOf("org.example.kept", "org.example.archived"), keepKnown(s, known).apps.keys)
    }
}
