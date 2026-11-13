package app.unbraid

import android.app.ApplicationExitInfo.REASON_CRASH
import android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED
import android.app.ApplicationExitInfo.REASON_USER_REQUESTED
import android.app.ApplicationExitInfo.REASON_USER_STOPPED
import app.unbraid.vpn.AfterUpdate
import app.unbraid.vpn.AfterUpdate.End
import app.unbraid.vpn.AfterUpdate.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class AfterUpdateTest {
    /** Only a VPN that was on in this boot comes back after the app is replaced; without consent the user is told. */
    @Test fun reconnectsOnlyWhatWasOn() {
        assertEquals(Step.Start, AfterUpdate.step(onBoot = 7, boot = 7, consent = true, end = End.Updated))
        assertEquals(Step.Tell, AfterUpdate.step(onBoot = 7, boot = 7, consent = false, end = End.Updated))
        // Turned off, paused or failed before the update: nothing, consent or not.
        assertEquals(Step.None, AfterUpdate.step(onBoot = null, boot = 7, consent = true, end = End.Updated))
        assertEquals(Step.None, AfterUpdate.step(onBoot = null, boot = 7, consent = false, end = End.Updated))
        // Noted before a reboot (the phone went off with it on): never a start after a later update.
        assertEquals(Step.None, AfterUpdate.step(onBoot = 6, boot = 7, consent = true, end = End.Updated))
        assertEquals("unbraid is off after the update. Tap to reconnect.", AfterUpdate.OFF)
    }

    private val now = 1_000_000_000L
    private fun ended(reason: Int, run: String? = "vpn", ago: Long = 5_000) = AfterUpdate.Exit(run, reason, now - ago)
    private fun end(exits: List<AfterUpdate.Exit>, sdk: Int = 35, marked: String? = "vpn", mine: String = "receiver") =
        AfterUpdate.end(marked, mine, exits, sdk, now)

    /**
     * Connected, then Force stop (the note survives: the process got no word), the app opened again without connecting,
     * then an update in the same boot: the VPN process's own end says the user stopped it, so nothing starts.
     */
    @Test fun forceStopThenUpdateNeverReconnects() {
        val forced = end(listOf(ended(REASON_USER_REQUESTED)), sdk = 34)
        assertEquals(End.UserStopped, forced)
        assertEquals(Step.None, AfterUpdate.step(onBoot = 7, boot = 7, consent = true, end = forced))
        assertEquals(Step.None, AfterUpdate.step(7, 7, true, end(listOf(ended(REASON_USER_STOPPED)), 36)))
        // Ended by the update itself: reconnects.
        assertEquals(Step.Start, AfterUpdate.step(7, 7, true, end(listOf(ended(REASON_PACKAGE_UPDATED)), 34)))
        // Android 13 records an update and a Force stop alike, and a missing record says nothing: only the notice.
        assertEquals(Step.Tell, AfterUpdate.step(7, 7, true, end(listOf(ended(REASON_USER_REQUESTED)), 33)))
        assertEquals(Step.Tell, AfterUpdate.step(7, 7, true, end(emptyList())))
        assertEquals(Step.Tell, AfterUpdate.step(7, 7, true, end(listOf(ended(REASON_PACKAGE_UPDATED)), marked = null)))
        // It died before the update (a crash) and nothing started it again: it wasn't running, so nothing.
        assertEquals(Step.None, AfterUpdate.step(7, 7, true, end(listOf(ended(REASON_CRASH)))))
    }

    /**
     * The VPN process is force stopped; later in the same boot the app is opened without connecting and that process
     * gets the same process id; the update ends it. Its record (newest at that id) says "updated", but it is not the
     * VPN's run: the VPN's own record wins, and without one only the notice is shown, never a start.
     */
    @Test fun aLaterProcessAtTheSameIdNeverReconnects() {
        val ui = ended(REASON_PACKAGE_UPDATED, run = null, ago = 1_000)
        assertEquals(Step.None, AfterUpdate.step(7, 7, true, end(listOf(ui, ended(REASON_USER_REQUESTED, ago = 600_000)), 34)))
        assertEquals(Step.Tell, AfterUpdate.step(7, 7, true, end(listOf(ui))))
        // Another run of the VPN that also noted itself, at the same id: still not the noted run.
        assertEquals(Step.Tell, AfterUpdate.step(7, 7, true, end(listOf(ended(REASON_PACKAGE_UPDATED, run = "other")))))
        // The noted run's update record from long ago (its handler never ran): not this update, only the notice.
        assertEquals(Step.Tell, AfterUpdate.step(7, 7, true, end(listOf(ended(REASON_PACKAGE_UPDATED, ago = AfterUpdate.FRESH + 1)))))
        assertEquals(Step.Tell, AfterUpdate.step(7, 7, true, end(listOf(ended(REASON_PACKAGE_UPDATED, ago = -60_000)))))
    }

    /** Android's Always-on (or a manual start) already brought it back in this process: no second start, the note stays. */
    @Test fun alreadyRunningAgainIsLeftAlone() {
        val again = end(emptyList(), marked = "receiver")
        assertEquals(End.Alive, again)
        assertEquals(Step.None, AfterUpdate.step(7, 7, true, again))
    }
}
