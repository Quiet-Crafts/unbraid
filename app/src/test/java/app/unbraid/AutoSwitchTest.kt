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
import org.junit.Assert.assertFalse
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
    private fun step(s: Setup, kind: String, over: Boolean = false) = AutoSwitch.decide(s, s.onNet[kind]) { _, _ -> over }

    @Test fun aNewKindOfNetworkTakesItsProfile() {
        assertEquals("Trip", step(setup, MOBILE).to)
        // Already in use, no profile set for it, or one that can't be used yet: nothing, and nothing said.
        for (s in listOf(step(setup, WIFI), step(setup.copy(onNet = mapOf(WIFI to "Home")), MOBILE), step(setup.copy(onNet = mapOf(MOBILE to "Empty")), MOBILE), step(setup.copy(onNet = mapOf(MOBILE to "Gone")), MOBILE))) {
            assertNull(s.to); assertNull(s.blocked)
        }
    }

    /** Decided once per network and the profile its rule names: a profile picked by hand there stays until the phone leaves it. */
    @Test fun aProfilePickedByHandStays() {
        val wifi = AutoSwitch.Net(WIFI, 100)
        val run = AutoSwitch.Run()
        run.seen(wifi)
        assertEquals("Home", run.settle(wifi, setup.onNet)?.profile)
        run.pick(setup.onNet) // Trip, by hand
        assertNull(run.settle(wifi, setup.onNet))
        assertNull(run.settle(wifi, setup.onNet + (MOBILE to "Home"))) // the other network's rule: nothing here
        assertEquals("Trip", run.settle(wifi, setup.onNet + (WIFI to "Trip"))?.profile) // a new rule for this network applies over it
        run.pick(setup.onNet + (WIFI to "Trip"))
        val other = AutoSwitch.Net(WIFI, 101)
        run.seen(other)
        assertEquals("Trip", run.settle(other, setup.onNet + (WIFI to "Trip"))?.profile) // another Wi-Fi: its rule
    }

    /** The Not switched card names its profile by the rule that chose it: renamed, deleted, switched to or left, it goes. */
    @Test fun theNotSwitchedCardHoldsOnlyForItsOwnProfile() {
        val wifi = AutoSwitch.State(true, AutoSwitch.Net(WIFI, 100))
        val ns = AutoSwitch.NotSwitched("Trip", MOBILE, 100, "Not switched to Trip: …")
        assertTrue(ns.holds(setup, wifi))
        assertTrue(ns.holds(setup, wifi.copy(here = AutoSwitch.Net(null, 100)))) // the same network, its kind not reported again yet
        assertTrue(ns.holds(setup, AutoSwitch.State())) // nothing watches the network (the VPN off): not known, still shown
        assertFalse(ns.holds(setup, AutoSwitch.State(true, AutoSwitch.Net(MOBILE, 101)))) // the phone left that network
        // That network seen lost (airplane mode): left, so the card goes, though no other network came.
        assertFalse(ns.holds(setup, AutoSwitch.step(wifi, AutoSwitch.Event.Lost(100)).state))
        assertTrue(ns.holds(setup, AutoSwitch.step(wifi, AutoSwitch.Event.Lost(99)).state)) // another network lost: no change
        // Left and joined again before anything collected the change (the same handle): the stay the card was for is over.
        fun after(vararg e: AutoSwitch.Event) = e.fold(wifi) { st, ev -> AutoSwitch.step(st, ev).state }
        val back = AutoSwitch.Net(WIFI, 100)
        assertFalse(ns.holds(setup, after(AutoSwitch.Event.Seen(AutoSwitch.Net(MOBILE, 101)), AutoSwitch.Event.Seen(back))))
        assertFalse(ns.holds(setup, after(AutoSwitch.Event.Lost(100), AutoSwitch.Event.Seen(back))))
        assertFalse(ns.holds(setup, after(AutoSwitch.Event.Seen(null), AutoSwitch.Event.Seen(back))))
        // Lost, then the monitor stops at once: still left, though nothing watches now.
        assertFalse(ns.holds(setup, after(AutoSwitch.Event.Lost(100), AutoSwitch.Event.Unwatched)))
        // The VPN off and on again on the same network: no departure.
        assertTrue(ns.holds(setup, after(AutoSwitch.Event.Unwatched, AutoSwitch.Event.Seen(back))))
        assertTrue(ns.holds(setup, after(AutoSwitch.Event.Unwatched)))
        assertFalse(ns.holds(setup.copy(profile = "Trip"), wifi)) // switched to
        assertFalse(ns.holds(setup.forget("Trip"), wifi)) // deleted
        // Renamed, then another profile made under the old name: the card doesn't act on that one.
        val renamed = setup.rename("Trip", "Work")
        assertFalse(ns.holds(renamed, wifi))
        assertFalse(ns.holds(renamed.copy(profiles = renamed.profiles + Profile("Trip", "nl")), wifi))
        assertFalse(ns.holds(setup.copy(onNet = mapOf(MOBILE to "Home")), wifi)) // its rule now names another profile
    }

    /** The card says to delete a profile only while that is still the reason: once the switch is possible it goes. */
    @Test fun theNotSwitchedCardGoesOnceTheSwitchIsPossible() {
        val loose = setup.copy(profile = null, defaultRoute = "us", apps = mapOf("org.a" to "nl"))
        val full = loose.copy(profiles = loose.profiles + (1..MAX_PROFILES - 3).map { Profile("P$it", "nl") })
        val here = AutoSwitch.State(true, AutoSwitch.Net(WIFI, 100))
        val ns = AutoSwitch.NotSwitched("Trip", MOBILE, 100, AutoSwitch.decide(full, "Trip") { _, _ -> false }.blocked!!)
        assertEquals(ns, ns.current(full, here, emptyMap())) // no room: still the reason
        assertNull(ns.current(full.forget("P1"), here, emptyMap())) // a profile deleted: the switch is possible now
        assertNull(ns.current(full.copy(profile = "Trip"), here, emptyMap())) // switched to
        assertNull(ns.current(full, AutoSwitch.State(true, AutoSwitch.Net(MOBILE, 101)), emptyMap())) // the phone left that network
    }

    /** A switch blocked for lack of room, then possible once a profile is deleted: the card goes and the switch is decided afresh. */
    @Test fun aBlockedSwitchThatBecomesPossibleIsDecidedAgain() {
        val loose = setup.copy(profile = null, defaultRoute = "us", apps = mapOf("org.a" to "nl"))
        val full = loose.copy(profiles = loose.profiles + (1..MAX_PROFILES - 3).map { Profile("P$it", "nl") })
        val wifi = AutoSwitch.Net(WIFI, 100)
        AutoSwitch.Run().run {
            seen(wifi)
            val d = settle(wifi, full.onNet)!!
            assertEquals("Home", d.profile)
            assertNull(AutoSwitch.decide(full, "Home") { _, _ -> false }.to)
            assertTrue(commit(d, full.onNet, false, blocked = true))
            assertNull(settle(wifi, full.onNet)) // acted on: nothing again while it stays blocked
            val ns = AutoSwitch.NotSwitched("Home", WIFI, 100, "blocked", state.left)
            val freed = full.forget("P1")
            assertTrue(ns.freed(freed, state, emptyMap()))
            assertFalse(ns.freed(full, state, emptyMap()))
            unblocked(ns.net, ns.profile)
            assertEquals("Home", settle(wifi, freed.onNet)?.profile)
        }
        // A hand pick since is not forgotten: only the blocked decision is.
        AutoSwitch.Run().run {
            seen(wifi)
            val d = settle(wifi, full.onNet)!!
            commit(d, full.onNet, false, blocked = true)
            pick(full.onNet)
            unblocked(100, "Home")
            assertNull(settle(wifi, full.onNet))
        }
    }

    /**
     * Wi-Fi -> Home blocked for lack of room (20 profiles, an unsaved setup): deleting Trip, as the Profiles screen does
     * ([AutoSwitch.Run.deleted]), frees the room and Home is then switched to. A rename or a rule edit for the other kind
     * of network keeps the blocked decision too; one that concerns it doesn't.
     */
    @Test fun freeingRoomByDeletingAnotherProfileSwitches() {
        val loose = setup.copy(profile = null, defaultRoute = "us", apps = mapOf("org.a" to "nl"))
        val full = loose.copy(profiles = loose.profiles + (1..MAX_PROFILES - 3).map { Profile("P$it", "nl") })
        assertEquals(MAX_PROFILES, full.profiles.size)
        val wifi = AutoSwitch.Net(WIFI, 100)
        fun blocked(then: AutoSwitch.Run.(AutoSwitch.NotSwitched) -> Setup): String? = AutoSwitch.Run().run {
            seen(wifi)
            val d = settle(wifi, full.onNet)!!
            val why = AutoSwitch.decide(full, d.profile) { _, _ -> false }.blocked!!
            assertTrue(commit(d, full.onNet, false, blocked = true))
            val ns = AutoSwitch.NotSwitched(d.profile, WIFI, wifi.id, why, d.left)
            val now = then(ns)
            // The card as the app runs it: gone once the switch is possible, and then decided afresh.
            if (ns.current(now, state, emptyMap()) == null && ns.freed(now, state, emptyMap())) unblocked(ns.net, ns.profile)
            settle(wifi, now.onNet)?.profile
        }
        assertEquals("Home", blocked { deleted("Trip"); full.forget("Trip") })
        assertEquals("Home", blocked { renamed("Trip", "Away"); deleted("Away"); full.rename("Trip", "Away").forget("Away") })
        assertEquals("Home", blocked { ruleEdited(MOBILE); deleted("P1"); full.copy(onNet = mapOf(WIFI to "Home")).forget("P1") })
        // Still blocked with no room: nothing.
        assertNull(blocked { deleted("Trip"); full.forget("Trip").copy(profiles = full.forget("Trip").profiles + Profile("New", "nl")) })
    }

    /** A rule edited for the network the phone is on is decided afresh; the pick made before it no longer holds. */
    @Test fun aRuleEditForTheNetworkForgetsWhatWasActedOn() {
        val wifi = AutoSwitch.Net(WIFI, 100)
        val run = AutoSwitch.Run()
        run.seen(wifi)
        run.pick(setup.onNet) // Work by hand, under Wi-Fi -> Home
        run.ruleEdited(WIFI) // the rule removed ...
        assertNull(run.settle(wifi, emptyMap()))
        run.ruleEdited(WIFI) // ... and set to Home again, after the app was left in between
        assertEquals("Home", run.settle(wifi, setup.onNet)?.profile)
        // An edit of another kind's rule leaves a pick alone.
        run.pick(setup.onNet)
        run.ruleEdited(MOBILE)
        assertNull(run.settle(wifi, setup.onNet))
    }

    @Test fun aSwitchThatCantHappenSaysWhy() {
        assertTrue(step(setup, MOBILE, over = true).let { it.to == null && it.blocked!!.startsWith("Not switched to Trip: it needs more connections than a provider allows.") })
        // A setup in no profile is kept as "Previous" first; with no room for it, nothing switches.
        val loose = setup.copy(profile = null, defaultRoute = "us", apps = mapOf("org.a" to "nl"))
        assertEquals("Trip", step(loose, MOBILE).to)
        assertEquals(PREVIOUS, loose.use("Trip", PREVIOUS).profiles.last().name)
        // Kept over an older one under the name that one has, so a kind of network set to it still names a profile.
        val named = loose.copy(profiles = loose.profiles + Profile("previous", "nl"), onNet = mapOf(WIFI to "previous"))
        assertEquals("previous", named.use("Trip", PREVIOUS).profiles.last().name)
        assertEquals("previous", Backup.valid(named.use("Trip", PREVIOUS)).onNet[WIFI])
        val full = loose.copy(profiles = loose.profiles + (1..MAX_PROFILES - 3).map { Profile("P$it", "nl") })
        assertTrue(step(full, MOBILE).let { it.to == null && it.blocked!!.startsWith("Not switched to Trip: there is no room to keep your current setup") })
    }

    @Test fun renamingAndDeletingAProfileKeepsTheRulesRight() {
        assertEquals(mapOf(WIFI to "Home", MOBILE to "Away"), setup.rename("Trip", "Away").onNet)
        assertEquals(mapOf(WIFI to "Home"), setup.forget("Trip").onNet)
        // Said on the Profiles page, under the kind of network.
        assertEquals("Any Wi-Fi, public ones too, and a cable", onNetSub(setup, WIFI)); assertNull(onNetSub(setup, MOBILE))
        assertEquals("This profile needs a default route first", onNetSub(setup.copy(onNet = mapOf(MOBILE to "Empty")), MOBILE))
        // The rules are not a new format: a setup with them is saved as before, and an older build reads it without them.
        assertEquals(2, setup.stamped.version)
        assertEquals(setup.onNet, Backup.read(Backup.export(setup, "2.10.0", 0)).setup.onNet)
    }
}
