package app.unbraid

import app.unbraid.data.DIRECT
import app.unbraid.data.Location
import app.unbraid.data.Profile
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.data.graphemes
import app.unbraid.data.takeGraphemes
import app.unbraid.vpn.switchDone
import app.unbraid.vpn.VpnState
import app.unbraid.vpn.VpnState.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfilesTest {
    private val nl = Route("nl", Location("nord", 153, "NL", "Netherlands"))
    private val us = Route("us", Location("nord", 228, "US", "United States"))
    private val it = Route("it", Location("nord", 106, "IT", "Italy"))
    private val base = Setup(routes = listOf(nl, us, it), defaultRoute = "nl", apps = mapOf("netflix" to "us"))

    /** Saving puts the new profile in use; switching keeps what the old one had, edits included, and loads the other. */
    @Test fun saveSwitchAndFollowEdits() {
        val home = base.saveAs("Home")
        assertEquals("Home", home.profile)
        val edited = home.copy(apps = home.apps + ("bank" to DIRECT)) // an edit while Home is in use
        val travel = edited.saveAs("Travel").copy(defaultRoute = "us", apps = emptyMap())
        val back = travel.use("Home")
        assertEquals("nl", back.defaultRoute)
        assertEquals(mapOf("netflix" to "us", "bank" to DIRECT), back.apps)
        assertEquals(Profile("Travel", "us"), back.profiles[1])
        assertEquals(back, back.use("Nowhere")) // an unknown name changes nothing
    }

    /** Deleting the profile in use leaves the routes as they are; renaming it keeps it in use. */
    @Test fun forgetAndRename() {
        val s = base.saveAs("Home").saveAs("Travel")
        assertEquals("Trip", s.rename("Travel", "Trip").profile)
        val gone = s.forget("Travel")
        assertNull(gone.profile)
        assertEquals(base.apps, gone.apps)
        assertEquals(listOf("Home"), gone.profiles.map { p -> p.name })
    }

    @Test fun reorder() {
        val s = base.saveAs("A").saveAs("B").saveAs("C")
        assertEquals(listOf("C", "A", "B"), s.move("C", -5).profiles.map { p -> p.name })
        assertEquals(listOf("B", "C", "A"), s.move("A", 2).profiles.map { p -> p.name })
    }

    /** Twelve characters people see, trimmed, unique regardless of case; renaming may keep its own name. */
    @Test fun names() {
        val s = base.saveAs("Home")
        assertEquals("Geschäftlich", s.profileName(" Geschäftlich "))
        assertNull(s.profileName("Thirteen chrs"))
        assertNull(s.profileName("   "))
        assertNull(s.profileName("home"))
        assertEquals("HOME", s.profileName("HOME", except = "Home"))
        assertEquals(12, graphemes("👨‍👩‍👧 Casa al ma"))
        assertEquals("ét", "été".takeGraphemes(2)) // never splits an accent from its letter
    }

    /** A removed route: profiles' apps on it follow their default; a removed default becomes the first route. */
    @Test fun tidyAfterRouteRemoved() {
        val s = base.saveAs("Home").copy(profiles = listOf(Profile("Home", "nl", mapOf("netflix" to "us")), Profile("Trip", "us", mapOf("rai" to "it"))))
        val t = s.copy(routes = listOf(nl, it)).tidy()
        assertEquals(Profile("Home", "nl", emptyMap()), t.profiles[0])
        assertEquals(Profile("Trip", "nl", mapOf("rai" to "it")), t.profiles[1])
        assertEquals(s, s.tidy()) // nothing to mend: the same setup
    }

    /** A switch is done once the engine runs that exact setup in full, undone once it refused it, and waits otherwise. */
    @Test fun switchOutcome() {
        val h = base.saveAs("Home").hashCode()
        assertEquals(true, switchDone(VpnState(Phase.On, applied = h), h))
        assertEquals(false, switchDone(VpnState(Phase.On, applied = 1, refused = h), h))
        assertNull(switchDone(VpnState(Phase.On, applied = 1, refused = 2), h)) // still waiting (e.g. a server being found)
        assertNull(switchDone(VpnState(Phase.On), h))
        assertEquals(true, switchDone(VpnState(Phase.Paused), h)) // nothing runs: nothing to wait for
        assertEquals(true, switchDone(VpnState(Phase.Off, refused = h), h))
    }

    /** The hash a switch waits for survives saving and loading (it compares a fresh copy with the one in memory). */
    @Test fun hashSurvivesSaving() {
        val s = base.saveAs("Home").saveAs("Travel")
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        assertEquals(s.hashCode(), json.decodeFromString(Setup.serializer(), json.encodeToString(Setup.serializer(), s)).hashCode())
    }

    /** A refused switch goes back to what the engine still runs, even if that profile was renamed or a route removed meanwhile. */
    @Test fun backToWhatRuns() {
        val ran = base.saveAs("Home") // the engine runs Home: netflix on us
        val trip = ran.saveAs("Trip").copy(defaultRoute = "it", apps = mapOf("rai" to DIRECT))
        val back = trip.back(ran)
        assertEquals("Home", back.profile)
        assertEquals("nl", back.defaultRoute)
        assertEquals(mapOf("netflix" to "us"), back.apps)
        assertEquals(Profile("Trip", "it", mapOf("rai" to DIRECT)), back.profiles[1]) // the refused one keeps what it had
        val renamed = trip.rename("Home", "House").copy(routes = listOf(nl, it))
        val b2 = renamed.back(ran)
        assertNull(b2.profile) // its profile is gone by that name: none selected, the routes still as they ran
        assertEquals(emptyMap<String, String>(), b2.apps) // netflix's route was removed meanwhile
    }
}
