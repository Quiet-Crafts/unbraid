package app.unbraid

import app.unbraid.data.Backup
import app.unbraid.data.Location
import app.unbraid.data.MAX_PROFILES
import app.unbraid.data.MOBILE
import app.unbraid.data.PREVIOUS
import app.unbraid.data.Profile
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.data.VERSION
import app.unbraid.data.WIFI
import app.unbraid.ui.onNetSub
import app.unbraid.vpn.AutoSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The profile a kind of network takes by itself: when it switches, when it leaves things alone, and when it says why not. */
class AutoSwitchTest {
    /** Home in use; Wi-Fi takes Home, mobile data takes Trip. */
    private val setup = Setup(
        routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands")), Route("us", Location("nord", 228, "US", "United States"))),
        defaultRoute = "nl", profiles = listOf(Profile("Home", "nl"), Profile("Trip", "us"), Profile("Empty", "")), profile = "Home",
        onNet = mapOf(WIFI to "Home", MOBILE to "Trip"), version = VERSION,
    )
    private fun step(s: Setup, kind: String, acted: Pair<String, Map<String, String>>? = null, over: Boolean = false) = AutoSwitch.decide(s, kind, acted) { _, _ -> over }

    @Test fun aNewKindOfNetworkTakesItsProfile() {
        assertEquals("Trip", step(setup, MOBILE).to)
        assertEquals("Trip", step(setup, MOBILE, acted = WIFI to setup.onNet).to)
        // Already in use, no profile set for it, or one that can't be used yet: nothing, and nothing said.
        for (s in listOf(step(setup, WIFI), step(setup.copy(onNet = mapOf(WIFI to "Home")), MOBILE), step(setup.copy(onNet = mapOf(MOBILE to "Empty")), MOBILE), step(setup.copy(onNet = mapOf(MOBILE to "Gone")), MOBILE))) {
            assertNull(s.to); assertNull(s.blocked)
        }
    }

    /** Decided once per kind of network and set of rules: a profile picked by hand since then stays until one of them changes. */
    @Test fun aProfilePickedByHandStays() {
        val byHand = setup.use("Trip") // on Wi-Fi, whose profile is Home
        assertNull(step(byHand, WIFI, acted = WIFI to setup.onNet).to)
        assertEquals("Home", step(byHand, WIFI, acted = MOBILE to setup.onNet).to)
        assertEquals("Home", step(byHand, WIFI, acted = WIFI to mapOf(WIFI to "Trip")).to) // the rules changed: they apply now
    }

    @Test fun aSwitchThatCantHappenSaysWhy() {
        assertTrue(step(setup, MOBILE, over = true).let { it.to == null && it.blocked!!.startsWith("Not switched to Trip: it needs more connections than a provider allows.") })
        // A setup in no profile is kept as "Previous" first; with no room for it, nothing switches.
        val loose = setup.copy(profile = null, defaultRoute = "us", apps = mapOf("org.a" to "nl"))
        assertEquals("Trip", step(loose, MOBILE).to)
        assertEquals(PREVIOUS, loose.use("Trip", PREVIOUS).profiles.last().name)
        val full = loose.copy(profiles = loose.profiles + (1..MAX_PROFILES - 3).map { Profile("P$it", "nl") })
        assertTrue(step(full, MOBILE).let { it.to == null && it.blocked!!.startsWith("Not switched to Trip: there is no room to keep your current setup") })
    }

    @Test fun renamingAndDeletingAProfileKeepsTheRulesRight() {
        assertEquals(mapOf(WIFI to "Home", MOBILE to "Away"), setup.rename("Trip", "Away").onNet)
        assertEquals(mapOf(WIFI to "Home"), setup.forget("Trip").onNet)
        // Said on the Profiles page, under the kind of network.
        assertEquals("A cable too", onNetSub(setup, WIFI)); assertNull(onNetSub(setup, MOBILE))
        assertEquals("This profile needs a default route first", onNetSub(setup.copy(onNet = mapOf(MOBILE to "Empty")), MOBILE))
        // The rules are not a new format: a setup with them is saved as before, and an older build reads it without them.
        assertEquals(2, setup.stamped.version)
        assertEquals(setup.onNet, Backup.read(Backup.export(setup, "2.10.0", 0)).setup.onNet)
    }
}
