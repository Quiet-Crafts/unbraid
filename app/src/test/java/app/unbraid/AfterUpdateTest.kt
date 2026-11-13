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

    /**
     * Connected, then Force stop (the note survives: the process got no word), the app opened again without connecting,
     * then an update in the same boot: the VPN process's own end says the user stopped it, so nothing starts.
     */
    @Test fun forceStopThenUpdateNeverReconnects() {
        val forced = AfterUpdate.end(marked = 4100, mine = 5200, reason = REASON_USER_REQUESTED, sdk = 34)
        assertEquals(End.UserStopped, forced)
        assertEquals(Step.None, AfterUpdate.step(onBoot = 7, boot = 7, consent = true, end = forced))
        assertEquals(Step.None, AfterUpdate.step(7, 7, true, AfterUpdate.end(4100, 5200, REASON_USER_STOPPED, 36)))
        // Ended by the update itself: reconnects.
        assertEquals(Step.Start, AfterUpdate.step(7, 7, true, AfterUpdate.end(4100, 5200, REASON_PACKAGE_UPDATED, 34)))
        // Android 13 records an update and a Force stop alike, and a missing record says nothing: only the notice.
        assertEquals(Step.Tell, AfterUpdate.step(7, 7, true, AfterUpdate.end(4100, 5200, REASON_USER_REQUESTED, 33)))
        assertEquals(Step.Tell, AfterUpdate.step(7, 7, true, AfterUpdate.end(4100, 5200, null, 35)))
        assertEquals(Step.Tell, AfterUpdate.step(7, 7, true, AfterUpdate.end(null, 5200, REASON_PACKAGE_UPDATED, 35)))
        // It died before the update (a crash) and nothing started it again: it wasn't running, so nothing.
        assertEquals(Step.None, AfterUpdate.step(7, 7, true, AfterUpdate.end(4100, 5200, REASON_CRASH, 35)))
    }

    /** Android's Always-on (or a manual start) already brought it back in this process: no second start, the note stays. */
    @Test fun alreadyRunningAgainIsLeftAlone() {
        val end = AfterUpdate.end(marked = 5200, mine = 5200, reason = null, sdk = 35)
        assertEquals(End.Alive, end)
        assertEquals(Step.None, AfterUpdate.step(7, 7, true, end))
    }
}
