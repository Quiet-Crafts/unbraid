package app.unbraid

import app.unbraid.ui.Tone
import app.unbraid.ui.providerStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusTest {
    private fun tone(used: Boolean = true, refused: Boolean = false, settingUp: Boolean = false, blocked: Boolean = false, fresh: Boolean = true, connected: Boolean = false) =
        providerStatus("IVPN", used, refused, settingUp, blocked, fresh, connected).first

    /** The most pressing reason wins: the user must act, then a passing issue, then good news; unused is grey. */
    @Test fun mostPressingFirst() {
        assertEquals(Tone.Bad, tone(refused = true, settingUp = true, blocked = true, fresh = false))
        assertEquals(Tone.Bad, tone(used = false, refused = true))
        assertEquals(Tone.Idle, tone(used = false, fresh = false))
        assertEquals(Tone.Warn, tone(settingUp = true, connected = true))
        assertEquals(Tone.Warn, tone(blocked = true))
        assertEquals(Tone.Warn, tone(fresh = false, connected = true))
        assertEquals(Tone.Good, tone(connected = true))
        assertEquals(Tone.Good, tone())
        assertEquals("Setting up your key", providerStatus("IVPN", true, false, true, false, true, true).second)
    }
}
