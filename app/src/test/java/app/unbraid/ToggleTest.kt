package app.unbraid

import app.unbraid.ui.tailscaleSub
import app.unbraid.vpn.Ask
import app.unbraid.vpn.VpnState.Phase
import app.unbraid.vpn.vpnOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToggleTest {
    /** With nothing waiting, the phase decides: connecting already counts as on. */
    @Test fun phaseDecidesWithoutRequest() {
        assertTrue(vpnOn(Phase.On, null))
        assertTrue(vpnOn(Phase.Connecting, null))
        assertFalse(vpnOn(Phase.Off, null))
        assertFalse(vpnOn(Phase.Paused, null))
    }

    /** A second quick tap undoes the first: a start not yet handled reads as on, a stop or pause as off. */
    @Test fun waitingRequestWins() {
        assertTrue(vpnOn(Phase.Off, Ask(1, true)))
        assertTrue(vpnOn(Phase.Paused, Ask(1, true)))
        assertFalse(vpnOn(Phase.On, Ask(2, false)))
        assertFalse(vpnOn(Phase.Connecting, Ask(2, false)))
    }

    /** Tailscale's states read as what they are; no status yet while connected is starting, not idle. */
    @Test fun tailscaleStatusText() {
        assertEquals("Starting", tailscaleSub(enabled = true, connected = true, state = null))
        assertEquals("Starting", tailscaleSub(enabled = true, connected = true, state = ""))
        assertEquals("In use by another user on this device", tailscaleSub(enabled = true, connected = true, state = "InUseOtherUser"))
        assertEquals("Sign-in needed", tailscaleSub(enabled = true, connected = true, state = "NeedsLogin"))
        assertEquals("Connected", tailscaleSub(enabled = true, connected = true, state = "Running"))
        assertEquals("Connects with unbraid", tailscaleSub(enabled = true, connected = false, state = null))
    }
}
