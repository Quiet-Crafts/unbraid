package app.unbraid

import app.unbraid.data.DIRECT
import app.unbraid.data.Home
import app.unbraid.data.Location
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.data.WgPeer
import app.unbraid.vpn.Glance
import app.unbraid.vpn.Health
import app.unbraid.vpn.VpnState
import app.unbraid.vpn.VpnState.Phase
import app.unbraid.vpn.glance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The network the checks in these tests ran on ([app.unbraid.vpn.AutoSwitch.Net.id]). */
private const val NET = 7L

class WidgetGlanceTest {
    private fun r(id: String, code: String, country: String, provider: String = "nord") = Route(id, Location(provider, 1, code, country))
    private val srv = Server("s", "h", "1.1.1.1", "k", 1)
    private val setup = Setup(
        routes = listOf(r("nl", "NL", "Netherlands"), r("us", "US", "United States"), r("it", "IT", "Italy"), r("br", "BR", "Brazil", "mullvad"), r("idle", "SE", "Sweden")),
        defaultRoute = "nl", apps = mapOf("a" to "us", "b" to "us", "c" to "it", "d" to DIRECT, "e" to "br"), profile = "Home",
    )
    private val servers = mapOf("nl" to srv, "us" to srv, "it" to srv, "br" to srv)

    /** Every VPN route in use answered its check on this engine and network ([NET]). */
    private val all = Health.Passed(0, NET, setOf("nl", "us", "it", "br"))

    @Test fun countsOnlyTheVpnRoutesInUseAndOrdersThemLikeRoutes() {
        val g = glance(VpnState(Phase.On, servers = servers, passed = all), setup, NET)
        assertEquals(4 to 4, g.ok to g.total) // Direct and the unused Sweden route count in neither
        assertEquals(0, g.unchecked)
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
        assertEquals(3 to 4, glance(VpnState(Phase.On, servers = servers, stalled = setOf("it"), passed = all), setup, NET).let { it.ok to it.total })
        // A failure counts, a route nothing checked doesn't.
        assertEquals(Triple(0, 1, 3), glance(VpnState(Phase.On, servers = servers, stalled = setOf("it")), setup, NET).let { Triple(it.ok, it.total, it.unchecked) })
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
        val nl = Health.Passed(0, NET, setOf("nl"))
        // A Tailscale exit is never checked here: it works while Tailscale says it runs, and isn't known while it starts.
        assertTrue(glance(VpnState(Phase.On, servers = all, tsState = "Running"), ts, NET).others.single().let { !it.failing && it.working })
        assertEquals(Triple(0, 0, 2), glance(VpnState(Phase.On, servers = all, tsState = "Starting"), ts, NET).let { Triple(it.ok, it.total, it.unchecked) })
        val down = glance(VpnState(Phase.On, servers = all, tsState = "NeedsLogin", passed = nl), ts, NET)
        assertTrue(down.others.single().failing)
        assertEquals("Tailscale", down.others.single().name)
        assertEquals(1 to 2, down.ok to down.total)
    }

    /** A route only websites use is in use: listed, counted and checked like one with apps. */
    @Test fun aRouteOnlyWebsitesUseIsShown() {
        val s = Setup(routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands")), Route("it", Location("nord", 106, "IT", "Italy"))), defaultRoute = "nl",
            sites = mapOf("example.com" to "it", "bank.example" to app.unbraid.data.DIRECT))
        val srv = app.unbraid.data.Server("x", "x", "10.0.0.1", "k", 1)
        val g = glance(VpnState(Phase.On, servers = mapOf("nl" to srv)), s)
        assertEquals(listOf("Italy", "Direct"), g.others.map { it.name })
        assertEquals(listOf(1, 1), g.others.map { it.sites })
        assertEquals(Triple(0, 1, 1), Triple(g.ok, g.total, g.unchecked)) // Italy has no tunnel yet; nothing checked the Netherlands
        assertEquals("1 website", app.unbraid.vpn.usesLabel(0, 1)); assertEquals("2 apps · 3 websites", app.unbraid.vpn.usesLabel(2, 3)); assertEquals("0 apps", app.unbraid.vpn.usesLabel(0, 0))
        assertEquals("1 app, 2 sites", app.unbraid.vpn.usesLabel(1, 2, short = true)); assertEquals("2 websites", app.unbraid.vpn.usesLabel(0, 2, short = true))
        // Tailscale against the user's own server is tagged HS.
        assertFalse(g.hs); assertTrue(glance(VpnState(Phase.On), s.copy(home = Home(tailscale = true, tsControlUrl = "https://hs.example"))).hs)
    }
}
