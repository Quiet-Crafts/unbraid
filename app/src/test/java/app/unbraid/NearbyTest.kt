package app.unbraid

import app.unbraid.data.Home
import app.unbraid.data.Setup
import app.unbraid.ui.nearbyAfterConnect
import app.unbraid.ui.nearbyReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NearbyTest {
    /** A first connect under Block connections without VPN asks for Nearby devices once the VPN says Block is on. */
    @Test fun askedOnceTheConnectIsUp() {
        // Still connecting: wait.
        assertNull(nearbyAfterConnect(waiting = true, on = false, needs = false, free = true))
        // Up, still needed (Block on, permission missing), nothing else on screen: ask.
        assertEquals(true, nearbyAfterConnect(waiting = true, on = true, needs = true, free = true))
        // Up but the tour or another sheet is showing: wait for it to close.
        assertNull(nearbyAfterConnect(waiting = true, on = true, needs = true, free = false))
        // Up and not needed (Block off, granted, or local network not bypassing): stop waiting, never ask.
        assertEquals(false, nearbyAfterConnect(waiting = true, on = true, needs = false, free = true))
        // Asked before the connect already, or no connect from the app: nothing.
        assertEquals(false, nearbyAfterConnect(waiting = false, on = true, needs = true, free = true))
    }

    /** The question after a connect says what the setting in Local & home access says. */
    @Test fun sameReasonAsTheSetting() {
        assertEquals("Printers, casting and NAS need it while Block connections without VPN is on.", nearbyReason(Setup(), lockdownLan = true))
        assertEquals("Lets Tailscale reach devices on this network directly, not through a slower relay.", nearbyReason(Setup(home = Home(tailscale = true)), lockdownLan = true))
    }
}
