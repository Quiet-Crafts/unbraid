package app.unbraid

import app.unbraid.data.DIRECT
import app.unbraid.data.Home
import app.unbraid.data.Location
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.data.WgPeer
import app.unbraid.vpn.Glance
import app.unbraid.vpn.VpnState
import app.unbraid.vpn.VpnState.Phase
import app.unbraid.vpn.glance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetGlanceTest {
    private fun r(id: String, code: String, country: String, provider: String = "nord") = Route(id, Location(provider, 1, code, country))
    private val srv = Server("s", "h", "1.1.1.1", "k", 1)
    private val setup = Setup(
        routes = listOf(r("nl", "NL", "Netherlands"), r("us", "US", "United States"), r("it", "IT", "Italy"), r("br", "BR", "Brazil", "mullvad"), r("idle", "SE", "Sweden")),
        defaultRoute = "nl", apps = mapOf("a" to "us", "b" to "us", "c" to "it", "d" to DIRECT, "e" to "br"), profile = "Home",
    )
    private val servers = mapOf("nl" to srv, "us" to srv, "it" to srv, "br" to srv)

    @Test fun countsOnlyTheVpnRoutesInUseAndOrdersThemLikeRoutes() {
        val g = glance(VpnState(Phase.On, servers = servers), setup)
        assertEquals(4 to 4, g.ok to g.total) // Direct and the unused Sweden route count in neither
        assertEquals("Netherlands", g.default?.name)
        assertEquals(listOf("Brazil", "Italy", "United States", "Direct"), g.others.map { it.name })
        assertEquals(listOf(1, 1, 2, 1), g.others.map { it.apps })
        assertEquals("Home", g.profile)
        assertTrue(g.protects)
    }

    @Test fun aRouteFailsWhenItStallsStopsAnsweringKeepsDroppingOrHasNoTunnel() {
        fun down(v: VpnState) = glance(v, setup).let { g -> (listOfNotNull(g.default) + g.others).filter { it.failing }.map { it.code } }
        assertEquals(listOf("IT"), down(VpnState(Phase.On, servers = servers, stalled = setOf("it"))))
        assertEquals(listOf("US"), down(VpnState(Phase.On, servers = servers, notAnswering = setOf("us"))))
        assertEquals(listOf("NL"), down(VpnState(Phase.On, servers = servers, dropping = setOf("nl"))))
        // Mullvad gave Brazil no server; the other routes are untouched.
        assertEquals(listOf("BR"), down(VpnState(Phase.On, servers = servers - "br", stuck = mapOf("mullvad" to false))))
        assertEquals(3 to 4, glance(VpnState(Phase.On, servers = servers, stalled = setOf("it")), setup).let { it.ok to it.total })
        // A failing route comes first, so no widget hides it below its fold.
        assertEquals(listOf("United States", "Brazil", "Italy", "Direct"), glance(VpnState(Phase.On, servers = servers, notAnswering = setOf("us")), setup).others.map { it.name })
    }

    @Test fun nothingIsLiveOrFailingUnlessConnected() {
        for (p in listOf(Phase.Off, Phase.Connecting, Phase.Paused)) {
            val g = glance(VpnState(p, stalled = setOf("it"), pausedUntil = 99), setup)
            assertTrue((listOfNotNull(g.default) + g.others).none { it.live || it.failing })
            assertEquals(if (p == Phase.Paused) 99L else 0L, g.until)
        }
    }

    @Test fun tailscaleShowsOnlyWhenSetUp() {
        val ts = setup.copy(home = Home(tailscale = true))
        assertEquals(Glance.Ts.None, glance(VpnState(Phase.On, tsState = "Running"), setup).ts)
        assertEquals(Glance.Ts.Up, glance(VpnState(Phase.On, tsState = "Running"), ts).ts)
        assertEquals(Glance.Ts.Down, glance(VpnState(Phase.On, tsState = "NeedsLogin"), ts).ts)
        assertEquals(Glance.Ts.Idle, glance(VpnState(Phase.On, tsState = "Starting"), ts).ts)
        assertEquals(Glance.Ts.Idle, glance(VpnState(Phase.Paused, tsState = "Running"), ts).ts)
    }

    @Test fun directDefaultAndMissingRoutes() {
        val g = glance(VpnState(Phase.On, servers = servers), Setup(routes = listOf(r("us", "US", "United States")), defaultRoute = DIRECT, apps = mapOf("a" to "gone", "b" to DIRECT)))
        assertTrue(g.default!!.direct)
        assertTrue(g.others.isEmpty()) // a route that no longer exists is left out; Direct is already the default
        assertEquals(0, g.total)
        assertNull(g.profile)
        // Only when every app goes Direct does running protect nothing.
        assertTrue(g.protects)
        assertFalse(glance(VpnState(Phase.On), Setup(defaultRoute = DIRECT, apps = mapOf("b" to DIRECT))).protects)
    }

    @Test fun ownServersShowTheirKindNotTheirNameAndFailWithoutATunnel() {
        val own = setup.copy(routes = setup.routes + Route("wg", wg = WgPeer("Home box", "k", listOf("10.0.0.2/32"), "p", endpoint = "h:1", allowedIps = listOf("0.0.0.0/0"))), apps = mapOf("a" to "wg"))
        val up = glance(VpnState(Phase.On, servers = mapOf("nl" to srv, "wg" to srv)), own).others.single()
        assertEquals(Triple("WG", "WireGuard", false), Triple(up.code, up.name, up.failing))
        // Restored without its keys it gets no tunnel: its apps are offline, so it doesn't read as working.
        assertTrue(glance(VpnState(Phase.On, servers = mapOf("nl" to srv)), own).others.single().failing)
    }

    @Test fun aTailscaleExitFailsWhileTailscaleIsNotConnected() {
        val ts = setup.copy(routes = setup.routes + Route("ts", tsExit = "100.64.0.9", tsName = "nas"), apps = mapOf("a" to "ts"))
        val all = mapOf("nl" to srv, "ts" to srv)
        assertFalse(glance(VpnState(Phase.On, servers = all, tsState = "Running"), ts).others.single().failing)
        val down = glance(VpnState(Phase.On, servers = all, tsState = "NeedsLogin"), ts)
        assertTrue(down.others.single().failing)
        assertEquals("Tailscale", down.others.single().name)
        assertEquals(1 to 2, down.ok to down.total)
    }
}
