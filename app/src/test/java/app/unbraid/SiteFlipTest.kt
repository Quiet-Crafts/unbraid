package app.unbraid

import app.unbraid.data.BLOCK
import app.unbraid.data.DIRECT
import app.unbraid.data.Home
import app.unbraid.data.Location
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.data.Sites
import app.unbraid.data.VERSION
import app.unbraid.ui.siteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A website whose rule starts or stops applying while apps hold addresses for it: the setting changes, the engine
 * reloads with the same domain table, and an app connects with the address it had. Such a connection never leaves by
 * a weaker way than a fresh lookup would give before or after: refused, or one of those two.
 */
class SiteFlipTest {
    private val it = Route("it", Location("nord", 106, "IT", "Italy"))
    private val base = Setup(routes = listOf(it), defaultRoute = DIRECT, version = VERSION)
    private val headscale = Home(tailscale = true, tsControlUrl = "https://hs.example.org", tsSuffixAllowed = "corp.example")

    /** Each change that flips a website on Italy between applied and not: the name, the two states, each with the tailnet domain Tailscale reported. */
    private val flips = listOf(
        Triple("x.fritz.box", base.copy(prefs = base.prefs.copy(lan = false)) to null, base to null), // Local network bypasses the VPN turned on
        Triple("nas.tail1234.ts.net", base to null, base.copy(home = Home(tailscale = true)) to null), // Reach your tailnet turned on
        Triple("box.corp.example", base.copy(home = headscale) to null, base.copy(home = headscale) to "corp.example"), // Headscale reports its domain
    ).map { (name, a, b) -> Triple(name, a.first.copy(sites = a.first.sites + (name to "it")) to a.second, b.first.copy(sites = b.first.sites + (name to "it")) to b.second) }

    @Test fun anAddressKeptAcrossAFlipNeverTakesAWeakerWay() {
        for ((name, x, y) in flips) for ((from, to) in listOf(x to y, y to x)) {
            val (dnsA, routeA) = RoutingProbe.compiled(from.first, from.second)
            val (dnsB, routeB) = RoutingProbe.compiled(to.first, to.second)
            val app = "com.browser"
            val freshA = RoutingProbe.connect(routeA, dnsA, app, name)
            val freshB = RoutingProbe.connect(routeB, dnsB, app, name)
            val had = RoutingProbe.lookup(dnsA, app, name, "A")
            val kept = RoutingProbe.connect(routeB, dnsB, app, name, kept = had)
            val why = "$name: ${siteState(from.first, name, from.second)} -> ${siteState(to.first, name, to.second)}, kept $had"
            // A stand-in address names the website: refused, or where a fresh lookup goes. A plain address (the tailnet's
            // or the network's, given while the rule didn't apply) names nothing, so it follows the address rules until
            // the app looks the name up again, as in 3.0.2: a limit of routing by name.
            if (had == "fake") assertTrue("$why went $kept, fresh $freshA / $freshB", kept in setOf("reject", freshA, freshB))
        }
    }

    /**
     * The website applies, or a lookup rule answers its name first; both ways, for every flip. Its route keeps its tunnel
     * either way: routes in use go by the saved websites, never by what answers a name.
     */
    @Test fun eachFlipChangesWhatIsInUse() {
        for ((name, x, y) in flips) {
            assertEquals(name, mapOf(name to "it"), Sites.applied(x.first, reported = x.second).filterKeys { it == name })
            assertTrue(name, Sites.applied(y.first, reported = y.second).isEmpty() || Sites.applied(y.first, reported = y.second).values.all { it == BLOCK })
            assertEquals(name, listOf("it"), x.first.activeRoutes.map { it.id })
            assertEquals(name, listOf("it"), y.first.activeRoutes.map { it.id })
        }
    }
}
