package app.unbraid

import app.unbraid.data.Ivpn
import app.unbraid.data.Limit
import app.unbraid.data.Limits
import app.unbraid.data.Mullvad
import app.unbraid.data.Nord
import app.unbraid.data.Pia
import app.unbraid.data.Proton
import app.unbraid.data.ProtonFree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LimitsTest {
    /** One wording for every provider: only the name, the numbers and the unit change. */
    @Test fun fixedWording() {
        assertEquals("Proton VPN Free allows 1 connection. unbraid is using 2. Your other devices aren't counted.", Limits.line("Proton VPN Free", 1, "connection", 2))
        assertEquals("IVPN allows 5 devices. unbraid is using 1. Your other devices aren't counted.", Limits.line("IVPN", 5, "device", 1))
        assertEquals("unbraid is using 2 PIA connections. Check how many your plan allows: over the limit, routes can stop working without an error.", Limits.line("PIA", null, "connection", 2))
        assertEquals("unbraid is using 1 Proton VPN connection. Check how many your plan allows: over the limit, routes can stop working without an error.", Limits.line("Proton VPN", null, "connection", 1))
        assertEquals("NordVPN allows 10 connections. This would make it 11. Over the limit, routes can stop working without an error.", Limits.warning("NordVPN", 10, "connection", 11))
        assertEquals("All 5 IVPN devices are in use. Sign out another device, then try again.", Limits.full("IVPN", 5, "device"))
        assertEquals("All IVPN devices are in use. Sign out another device, then try again.", Limits.full("IVPN", null, "device"))
        assertEquals("Netherlands keeps dropping. Proton VPN Free may be over its connection limit.", Limits.dropping("Netherlands", "Proton VPN Free", "connection"))
    }

    /** The user's own figure wins; empty or nonsense falls back to the provider's, which may be unknown. */
    @Test fun ownLimitFirst() {
        val p = Limit("connection", true, 10)
        assertEquals(3, Limits.effective(p, "3"))
        assertEquals(10, Limits.effective(p, ""))
        assertEquals(10, Limits.effective(p, null))
        assertEquals(10, Limits.effective(p, "0"))
        assertEquals(10, Limits.effective(p, "abc"))
        assertNull(Limits.effective(Limit("connection", true), ""))
        assertEquals(7, Limits.effective(Limit("connection", true), "7"))
    }

    /** What each provider declares: known figures only where documented or reported, how unbraid counts its own use. */
    @Test fun providers() {
        assertEquals(Limit("connection", true, 10), Nord.limit(""))
        assertEquals(Limit("connection", true, 1), ProtonFree.limit(""))
        assertEquals(Limit("connection", true, 10), Proton.limit(""))
        assertEquals(Limit("device", false, 5), Mullvad.limit(""))
        assertEquals(Limit("connection", true), Pia.limit(""))
        val ivpn = """{"accountId":"i-AAAA-BBBB-CCCC","sessionToken":"t","privateKey":"k","ipAddress":"10.0.0.2","device":"Tidy Falcon","limit":5}"""
        assertEquals(Limit("device", false, 5), Ivpn.limit(ivpn))
        assertEquals(Limit("device", false), Ivpn.limit("""{"accountId":"a","sessionToken":"t","privateKey":"k","ipAddress":"10.0.0.2"}"""))
        assertEquals(Limit("device", false), Ivpn.limit("not json"))
    }
}
