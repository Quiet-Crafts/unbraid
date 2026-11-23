package app.unbraid

import app.unbraid.data.AppIdentity
import app.unbraid.data.BLOCK
import app.unbraid.data.DIRECT
import app.unbraid.data.InstallState
import app.unbraid.data.Filter
import app.unbraid.data.Prefs
import app.unbraid.data.Profile
import app.unbraid.data.Setup
import app.unbraid.ui.AppRoutes
import app.unbraid.ui.chooseAppRoute
import app.unbraid.ui.keepKnown
import app.unbraid.ui.verifiedAppChoice
import org.junit.Assert.*
import org.junit.Test

class AppRouteIdentityUiTest {
    private val pkg = "org.example.app"
    private val signer = AppIdentity("current", setOf("old"))
    private val direct = Setup(defaultRoute = DIRECT, apps = mapOf(pkg to DIRECT),
        prefs = Prefs(filter = Filter(id = "quad9", directApps = true, apps = mapOf(pkg to false))))

    @Test fun heldChoicesKeepTheirRoutesForExplicitReauthorization() {
        for (pins in listOf(emptyMap(), mapOf(pkg to "other"))) {
            val raw = direct.copy(appSigners = pins)
            val shown = AppRoutes(raw) { signer }
            assertTrue(shown.held(pkg))
            assertEquals(BLOCK, shown.effective.apps[pkg])
            assertEquals(DIRECT, shown.saved.apps[pkg])
            assertEquals(pins, raw.appSigners)
            val next = raw.chooseAppRoute(null, pkg, DIRECT, signer)
            assertFalse(AppRoutes(next) { signer }.held(pkg))
            assertEquals(signer.current, next.appSigners[pkg])
            assertTrue(next.prefs.filter.through(pkg))
        }
        val signed = direct.copy(appSigners = mapOf(pkg to "old"))
        assertFalse(AppRoutes(signed) { signer }.held(pkg))
        assertTrue(AppRoutes(signed) { null }.held(pkg))
        assertFalse(signed.chooseAppRoute(null, pkg, DIRECT, signer).prefs.filter.through(pkg))
        val blocked = signed.chooseAppRoute(null, pkg, BLOCK, null)
        assertFalse(AppRoutes(blocked) { null }.held(pkg))
        assertEquals(BLOCK, blocked.apps[pkg])
        assertFalse(pkg in blocked.appSigners)
        assertSame(blocked, blocked.chooseAppRoute(null, pkg, null, null))
        val follows = blocked.chooseAppRoute(null, pkg, null, signer)
        assertFalse(pkg in follows.apps)
        assertFalse(pkg in follows.appSigners)
        assertSame(blocked, blocked.chooseAppRoute(null, pkg, DIRECT, null))
    }

    @Test fun eachProfileChecksItsOwnGrantAndKeepsTheSavedRouteAvailable() {
        val other = Profile("Trip", DIRECT, apps = mapOf(pkg to "trip"), appSigners = mapOf(pkg to "other"))
        val raw = direct.copy(appSigners = mapOf(pkg to signer.current), profiles = listOf(other))
        assertFalse(AppRoutes(raw) { signer }.held(pkg))
        val draft = AppRoutes(raw.viewAs("Trip")) { signer }
        assertTrue(draft.held(pkg))
        assertEquals("trip", draft.saved.apps[pkg])
        val reauthorized = raw.chooseAppRoute("Trip", pkg, "trip", signer)
        assertEquals(raw.apps, reauthorized.apps)
        assertEquals(raw.appSigners, reauthorized.appSigners)
        assertFalse(AppRoutes(reauthorized.viewAs("Trip")) { signer }.held(pkg))
        assertEquals("trip", AppRoutes(reauthorized.use("Trip", null)) { signer }.effective.apps[pkg])
        // A new draft assignment for the already-authorized app keeps its current live DNS preference.
        val blank = raw.copy(profiles = listOf(other.copy(apps = emptyMap(), appSigners = emptyMap())))
        assertFalse(blank.chooseAppRoute("Trip", pkg, "trip", signer).prefs.filter.through(pkg))
    }

    @Test fun appChoicesRequireTheDisplayedSignerAndDisclosure() {
        var reads = 0
        val read = { reads++; signer }
        assertNull(verifiedAppChoice(false, "current", read))
        assertNull(verifiedAppChoice(true, null, read))
        assertEquals(0, reads)
        assertNull(verifiedAppChoice(true, "other", read))
        assertNull(verifiedAppChoice(true, "current") { null })
        assertEquals(signer, verifiedAppChoice(true, "old", read))
        assertEquals(signer, verifiedAppChoice(true, "current", read))
        // A package replaced after a row or confirmation was displayed cannot take its grant.
        assertNull(verifiedAppChoice(true, signer.current) { AppIdentity("replacement", emptySet()) })
    }

    @Test fun choosingARouteAgainDoesNotRenewTheOtherProfilesExpiredGrant() {
        val trip = Profile("Trip", DIRECT, apps = mapOf(pkg to DIRECT), appSigners = mapOf(pkg to signer.current))
        val raw = direct.copy(appSigners = mapOf(pkg to signer.current), profiles = listOf(trip),
            waiting = setOf(pkg), waitingUntil = 50)
        val late = { _: String -> InstallState.Present(51, signer) }
        val refreshed = raw.chooseAppRoute("Trip", pkg, DIRECT, signer)
        val live = AppRoutes(refreshed, installed = late, now = 60) { signer }
        val picked = AppRoutes(refreshed, "Trip", installed = late, now = 60) { signer }
        assertTrue(live.effective.follows(pkg))
        assertFalse(live.effective.outside(pkg))
        assertEquals(DIRECT, picked.effective.apps[pkg])
        assertFalse(picked.effective.follows(pkg))
    }

    @Test fun pendingGrantStaysHeldWhenTheInstallationTimeCannotBeVerified() {
        val raw = direct.copy(appSigners = mapOf(pkg to signer.current), waiting = setOf(pkg), waitingUntil = 50)
        val unclear = { _: String -> InstallState.Present(null, signer) }
        val pending = keepKnown(raw, setOf(pkg), now = 51, observed = unclear)
        assertEquals(raw.apps, pending.apps)
        assertEquals(raw.waiting, pending.waiting)
        assertTrue(AppRoutes(pending, installed = unclear, now = 51) { signer }.held(pkg))
        val timely = keepKnown(pending, setOf(pkg), now = 52, observed = { InstallState.Present(49, signer) })
        assertTrue(timely.waiting.isEmpty())
        assertEquals(DIRECT, AppRoutes(timely) { signer }.effective.apps[pkg])
    }

    @Test fun removingMissingAppsAlsoRemovesOnlyTheirPinsAfterWaiting() {
        val raw = direct.copy(appSigners = mapOf(pkg to signer.current),
            profiles = listOf(Profile("Trip", DIRECT, apps = direct.apps, appSigners = mapOf(pkg to "other"))),
            waiting = setOf(pkg), waitingUntil = 10)
        assertEquals(raw, keepKnown(raw, emptySet(), now = 5))
        val gone = keepKnown(raw, emptySet(), now = 10)
        assertTrue(gone.apps.isEmpty() && gone.appSigners.isEmpty())
        assertTrue(gone.profiles.single().apps.isEmpty() && gone.profiles.single().appSigners.isEmpty())
        val kept = keepKnown(raw, setOf(pkg), now = 5, observed = { InstallState.Present(3, signer) })
        // The other profile names another signer, so observing this package cannot settle its choice.
        assertTrue(pkg in kept.waiting)
        assertEquals(raw.appSigners, kept.appSigners)
        assertEquals(raw.profiles, kept.profiles)
    }
}
