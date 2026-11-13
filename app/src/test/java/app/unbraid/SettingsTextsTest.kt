package app.unbraid

import app.unbraid.data.DIRECT
import app.unbraid.data.Filter
import app.unbraid.ui.filterChosen
import app.unbraid.ui.filterSummary
import app.unbraid.ui.removeServerText
import app.unbraid.ui.tailscaleHubSub
import app.unbraid.data.CarriesDefault
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class SettingsTextsTest {
    @Test fun firstChoiceSwitchesVpnRoutesOn() {
        val f = filterChosen(Filter(), "quad9")
        assertEquals("quad9", f.id); assertTrue(f.routes)
    }

    @Test fun laterChoiceKeepsTheUsersSwitch() {
        val f = filterChosen(Filter(id = "quad9", routes = false), "adguard")
        assertEquals("adguard", f.id); assertTrue(!f.routes)
    }

    @Test fun nothingAppliesSaysNotApplied() {
        assertEquals("Quad9 · not applied", filterSummary(Filter(id = "quad9"), "nl", emptyMap()))
        // "Direct traffic" is on but the default route is not Direct, and no app is set to Direct.
        assertEquals("Quad9 · not applied", filterSummary(Filter(id = "quad9", direct = true, directApps = true), "nl", emptyMap()))
    }

    @Test fun directShowsOnlyWhenItReallyApplies() {
        assertEquals("Quad9 · Direct", filterSummary(Filter(id = "quad9", direct = true), DIRECT, emptyMap()))
        assertEquals("Quad9 · Direct", filterSummary(Filter(id = "quad9", directApps = true), "nl", mapOf("a.b" to DIRECT)))
        assertEquals("Quad9 · not applied", filterSummary(Filter(id = "quad9", directApps = true), "nl", mapOf("a.b" to "nl")))
    }

    @Test fun routesAndDirectTogether() {
        assertEquals("Quad9 · VPN routes · Direct", filterSummary(Filter(id = "quad9", routes = true, direct = true), DIRECT, emptyMap()))
    }

    @Test fun noFilterNoSummary() = assertNull(filterSummary(Filter(), "nl", emptyMap()))

    @Test fun removingTheServerThatCarriesTheDefaultSaysSo() {
        assertEquals("Home carries your default route. Choose another default route first.", removeServerText(2, CarriesDefault("Home", true, emptyList())))
        assertEquals("It isn't used by any route.", removeServerText(0, null))
        assertEquals("The routes using it are removed too; their apps follow the default route.", removeServerText(1, null))
    }

    @Test fun tailscaleOverviewNamesExitOnlyUse() {
        assertEquals("Connected", tailscaleHubSub(true, false, "Connected"))
        assertEquals("Exit node only · Sign-in needed", tailscaleHubSub(false, true, "Sign-in needed"))
        assertEquals("Off", tailscaleHubSub(false, false, "Reach your tailnet from every app"))
    }
}
