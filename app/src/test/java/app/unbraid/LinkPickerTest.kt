package app.unbraid

import app.unbraid.data.AuthException
import app.unbraid.data.Country
import app.unbraid.data.DIRECT
import app.unbraid.data.Field
import app.unbraid.data.Link
import app.unbraid.data.Location
import app.unbraid.data.Provider
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.data.WgLink
import app.unbraid.data.WgPeer
import app.unbraid.vpn.Fail
import app.unbraid.vpn.LinkPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun link(sv: Server) = WgLink(sv, WgPeer(sv.name, "priv", listOf("10.5.0.2/32"), sv.publicKey, endpoint = "${sv.ip}:51820", allowedIps = listOf("0.0.0.0/0")))

class LinkPickerTest {
    private fun server(n: String, ip: String = "10.0.0.${n.length}", host: String = "shared.example") = Server(n, host, ip, "pub", 0)

    /** [best] is what connect() returns for an unpinned location; a pinned one returns its own server. */
    private class Fake(val best: List<Server>, var online: Boolean = true, var trusted: Boolean = true, var listed: Boolean = true) : Provider {
        override val id = "fake"
        override val oneKey = true
        override val name = "Fake"
        override val fields = emptyList<Field>()
        override val help = ""
        override fun signIn(input: Map<String, String>) = "acc"
        override fun countries(account: String) = emptyList<Country>()
        override fun connect(loc: Location, account: String): Link {
            if (!online) throw java.io.IOException("down")
            return link(loc.pinned ?: best[0])
        }
        override fun servers(loc: Location, account: String, limit: Int) = if (online && listed) best.take(limit) else throw java.io.IOException("down")
        override fun trusts(link: Link, account: String) = trusted
    }

    private fun setup(vararg routes: Route) = Setup(routes = routes.toList(), defaultRoute = routes[0].id, apps = routes.drop(1).associate { "app.${it.id}" to it.id })
    private fun pick(s: Setup, p: Fake, last: Map<String, Link> = emptyMap()) =
        LinkPicker.pick(s, emptyMap(), null, emptyMap(), mapOf("fake" to "acc"), last) { if (it == "fake") p else null }

    private val a = server("a")
    private val b = server("bb", "10.0.0.9")

    @Test fun unpinnedCollisionMovesToAnotherServerByIpNotHostname() {
        val p = Fake(listOf(a, b))
        val r = pick(setup(Route("1", Location("fake", countryCode = "NL")), Route("2", Location("fake", countryCode = "DE"))), p)
        assertEquals("a", r.links.getValue("1").server.name)
        assertEquals("bb", r.links.getValue("2").server.name)
    }

    @Test fun pinnedCollisionSharesTheServer() {
        val p = Fake(listOf(a, b))
        val r = pick(setup(Route("1", Location("fake", countryCode = "NL", pinned = a)), Route("2", Location("fake", countryCode = "DE", pinned = a))), p)
        assertEquals(a, r.links.getValue("1").server)
        assertEquals(a, r.links.getValue("2").server)
    }

    @Test fun pinnedRouteNeverFallsBackToAnotherServer() {
        val p = Fake(listOf(a, b), online = false)
        val loc = Location("fake", countryCode = "NL", pinned = a)
        val other = LinkPicker.cacheKey(Route("x", Location("fake", countryCode = "NL", pinned = b))) to link(b)
        val failed = pick(setup(Route("1", loc)), p, mapOf(other))
        assertTrue("1" !in failed.links)
        assertEquals("Fastest: a is unreachable", (failed.blocked.getValue("1") as Fail).message)
        val same = LinkPicker.cacheKey(Route("1", loc)) to link(a)
        assertEquals(a, pick(setup(Route("1", loc)), p, mapOf(same)).links.getValue("1").server)
    }

    @Test fun unpinnedRouteMayUseTheLastGoodTunnel() {
        val p = Fake(listOf(a), online = false)
        val r = Route("1", Location("fake", countryCode = "NL"))
        assertEquals(a, pick(setup(r), p, mapOf(LinkPicker.cacheKey(r) to link(a))).links.getValue("1").server)
    }

    @Test fun anUntrustedCachedTunnelIsNotReused() {
        val p = Fake(listOf(a), online = false, trusted = false)
        val r = Route("1", Location("fake", countryCode = "NL"))
        val out = pick(setup(r), p, mapOf(LinkPicker.cacheKey(r) to link(a)))
        assertTrue("an untrusted cached tunnel was reused", out.links.isEmpty())
        assertEquals("down", out.blocked.getValue("1").message)
    }

    @Test fun anUntrustedKeptTunnelIsReplaced() {
        val r = Route("1", Location("fake", countryCode = "NL"))
        val s = setup(r)
        val accounts = mapOf("fake" to "acc")
        val keep = mapOf("1" to link(b) as Link)
        val trusted = Fake(listOf(a), trusted = true)
        assertEquals(b, LinkPicker.pick(s, keep, s, accounts, accounts, emptyMap()) { trusted }.links.getValue("1").server)
        val untrusted = Fake(listOf(a), trusted = false)
        assertEquals(a, LinkPicker.pick(s, keep, s, accounts, accounts, emptyMap()) { untrusted }.links.getValue("1").server)
    }

    @Test fun pinnedAndUnpinnedHaveDifferentCacheKeys() {
        val loc = Location("fake", countryCode = "NL")
        assertTrue(LinkPicker.cacheKey(Route("1", loc)) != LinkPicker.cacheKey(Route("1", loc.copy(pinned = a))))
        assertTrue(LinkPicker.cacheKey(Route("1", loc.copy(pinned = a))) != LinkPicker.cacheKey(Route("1", loc.copy(pinned = b))))
    }

    @Test fun deadServerIsAvoided() {
        val p = Fake(listOf(a, b))
        val r = Route("1", Location("fake", countryCode = "NL"))
        val dead = LinkPicker.serverKey(r, link(a))
        val picked = LinkPicker.pick(setup(r), emptyMap(), null, emptyMap(), mapOf("fake" to "acc"), emptyMap(), setOf(dead)) { p }
        assertEquals("bb", picked.links.getValue("1").server.name)
    }

    @Test fun aFreshConnectKeepsTheLastServerWhileItIsStillOffered() {
        val r = Route("1", Location("fake", countryCode = "NL"))
        val last = mapOf(LinkPicker.cacheKey(r) to link(b))
        assertEquals("bb", pick(setup(r), Fake(listOf(a, b)), last).links.getValue("1").server.name)
        assertEquals("a", pick(setup(r), Fake(listOf(a)), last).links.getValue("1").server.name)
    }

    @Test fun aFailedRetryOfTheLastServerPicksAsUsual() {
        val r = Route("1", Location("fake", countryCode = "NL"))
        assertEquals("a", pick(setup(r), Fake(listOf(a, b), listed = false), mapOf(LinkPicker.cacheKey(r) to link(b))).links.getValue("1").server.name)
    }

    @Test fun theLastServerIsLeftWhenAvoidedOrTakenByAnotherRoute() {
        val r = Route("1", Location("fake", countryCode = "NL"))
        val p = Fake(listOf(a, b))
        val last = mapOf(LinkPicker.cacheKey(r) to link(b))
        val avoided = LinkPicker.pick(setup(r), emptyMap(), null, emptyMap(), mapOf("fake" to "acc"), last, setOf(LinkPicker.serverKey(r, link(b)))) { p }
        assertEquals("a", avoided.links.getValue("1").server.name)
        val first = Route("0", Location("fake", countryCode = "DE", pinned = b))
        assertEquals("a", pick(setup(first, r), p, last).links.getValue("1").server.name)
    }

    /** A refusal holds: the route stays blocked, and neither the provider nor its cached tunnel is tried again. */
    @Test fun aRefusedRouteStaysBlockedWithoutACachedTunnel() {
        val p = Fake(listOf(a), online = false)
        val r = Route("1", Location("fake", countryCode = "NL"))
        val refusal = AuthException("Sign-in was refused")
        val out = LinkPicker.pick(setup(r), emptyMap(), null, emptyMap(), mapOf("fake" to "acc"), mapOf(LinkPicker.cacheKey(r) to link(a)), refused = mapOf("1" to refusal)) { p }
        assertTrue(out.links.isEmpty())
        assertTrue(out.blocked.getValue("1") === refusal)
    }

    /** Offline, only tunnels kept as they are and imports come out; everything needing the provider waits blocked. */
    @Test fun offlinePickingNeverCallsTheProvider() {
        val p = Fake(listOf(a), online = false)
        val kept = Route("1", Location("fake", countryCode = "NL"))
        val s = setup(kept, Route("2", Location("fake", countryCode = "DE")))
        val out = LinkPicker.pick(s, mapOf("1" to link(a) as Link), s, mapOf("fake" to "acc"), mapOf("fake" to "acc"), emptyMap(), offline = true) { p }
        assertEquals(a, out.links.getValue("1").server)
        assertTrue(out.blocked.getValue("2") is LinkPicker.Waiting)
        // A signed-out provider's route is refused at once, offline too: it never waits for a server.
        val signedOut = LinkPicker.pick(s, emptyMap(), null, emptyMap(), emptyMap(), emptyMap(), offline = true) { p }
        assertTrue(signedOut.blocked.values.all { it is AuthException })
    }

    /** One refusal blocks every route of that provider, also one that could keep its running tunnel. */
    @Test fun aRefusalBlocksTheProvidersOtherRoutesToo() {
        val refusing = object : Provider by Fake(listOf(a)) { override fun connect(loc: Location, account: String): Link = throw AuthException("Sign-in was refused") }
        val kept = Route("1", Location("fake", countryCode = "NL"))
        val s = setup(kept, Route("2", Location("fake", countryCode = "DE")))
        val out = LinkPicker.pick(s, mapOf("1" to link(a) as Link), s, mapOf("fake" to "acc"), mapOf("fake" to "acc"), emptyMap()) { refusing }
        assertTrue(out.links.isEmpty() && out.remember.isEmpty())
        assertEquals(setOf("1", "2"), out.blocked.keys)
    }

    @Test fun leavingDirectIsSeenExplicitlyAndByDefault() {
        val vpn = Route("v", Location("fake"))
        val directDefault = Setup(routes = listOf(vpn), defaultRoute = DIRECT, apps = mapOf("a" to "v"))
        // Following a Direct default, now sent to the route.
        assertTrue(LinkPicker.leavesDirect(directDefault, directDefault.copy(apps = mapOf("a" to "v", "b" to "v")), setOf("v")))
        // Explicitly Direct, now following a default that is the route (its entry is gone from apps).
        val vpnDefault = Setup(routes = listOf(vpn), defaultRoute = "v", apps = mapOf("b" to DIRECT))
        assertTrue(LinkPicker.leavesDirect(vpnDefault, vpnDefault.copy(apps = emptyMap()), setOf("v")))
        // The default itself turns from Direct to the route.
        assertTrue(LinkPicker.leavesDirect(directDefault, directDefault.copy(defaultRoute = "v"), setOf("v")))
        // Already on the route, or another route waits: nothing leaves Direct for it.
        assertFalse(LinkPicker.leavesDirect(directDefault, directDefault, setOf("v")))
        assertFalse(LinkPicker.leavesDirect(null, directDefault, setOf("v")))
    }

    @Test fun aSignedOutProvidersRouteIsBlockedAndTheOthersStillRun() {
        val other = object : Provider by Fake(listOf(b)) { override val id = "other" }
        val s = setup(Route("1", Location("fake", countryCode = "NL")), Route("2", Location("other", countryCode = "DE")))
        val r = LinkPicker.pick(s, emptyMap(), null, emptyMap(), mapOf("fake" to "acc"), emptyMap()) { if (it == "fake") Fake(listOf(a)) else other }
        assertEquals(a, r.links.getValue("1").server)
        assertTrue(r.blocked.getValue("2") is AuthException)
        assertTrue("2" !in r.links && "2" !in r.remember.values.map { it.server.name })
    }
}
