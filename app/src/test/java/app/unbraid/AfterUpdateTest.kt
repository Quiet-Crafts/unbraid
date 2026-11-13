package app.unbraid

import app.unbraid.vpn.AfterUpdate
import app.unbraid.vpn.AfterUpdate.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class AfterUpdateTest {
    /** Only a VPN that was on in this boot comes back after the app is replaced; without consent the user is told. */
    @Test fun reconnectsOnlyWhatWasOn() {
        assertEquals(Step.Start, AfterUpdate.step(onBoot = 7, boot = 7, consent = true))
        assertEquals(Step.Tell, AfterUpdate.step(onBoot = 7, boot = 7, consent = false))
        // Turned off, paused or failed before the update: nothing, consent or not.
        assertEquals(Step.None, AfterUpdate.step(onBoot = null, boot = 7, consent = true))
        assertEquals(Step.None, AfterUpdate.step(onBoot = null, boot = 7, consent = false))
        // Noted before a reboot (the phone went off with it on): never a start after a later update.
        assertEquals(Step.None, AfterUpdate.step(onBoot = 6, boot = 7, consent = true))
        assertEquals("unbraid is off after the update. Tap to reconnect.", AfterUpdate.OFF)
    }
}
