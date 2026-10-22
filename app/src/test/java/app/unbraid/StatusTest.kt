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

    /** A route that keeps dropping is a passing issue: below a refusal or blocked routes, above an old list. */
    @Test fun droppingWarns() {
        val d = "Netherlands keeps dropping. Proton VPN Free may be over its connection limit."
        assertEquals(Tone.Warn to d, providerStatus("Proton VPN Free", true, false, false, false, false, true, d))
        assertEquals(Tone.Bad, providerStatus("Proton VPN Free", true, true, false, false, true, true, d).first)
        assertEquals(Tone.Good, providerStatus("Proton VPN Free", true, false, false, false, true, true, null).first)
    }

    /** A provider's only server list published long ago warns, after a dropping route, with its date. */
    @Test fun oldListWarns() {
        assertEquals(Tone.Warn to "Its server list is from 6 Aug 2026 and may be out of date", providerStatus("Proton VPN Free", true, false, false, false, true, true, null, "6 Aug 2026"))
        assertEquals("6 Aug 2026", app.unbraid.ui.publishedOn(1785990858))
    }
}
