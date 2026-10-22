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
import app.unbraid.data.asListed
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

    /** Two imports of one key on one server with other settings: the second is blocked, the first runs. */
    @Test fun aClashingWireGuardRouteIsBlocked() {
        val peer = link(a).peer
        val s = setup(Route("1", wg = peer), Route("2", wg = peer.copy(mtu = 1200)))
        val out = LinkPicker.pick(s, emptyMap(), null, emptyMap(), emptyMap(), emptyMap()) { null }
        assertEquals(setOf("1"), out.links.keys)
        assertTrue(out.blocked.getValue("2") is Fail)
    }

    /** Offline, a pinned server starts from its last refreshed copy (same host), not the older one saved with the route. */
    @Test fun anOfflinePinnedServerUsesItsLastRefreshedCopy() {
        var asked: Server? = null
        val p = object : Provider by Fake(listOf(a)) { override fun connect(loc: Location, account: String): Link { asked = loc.pinned; return link(loc.pinned!!) } }
        val r = Route("1", Location("fake", countryCode = "NL", pinned = a))
        val newer = a.copy(ip = "10.0.0.77", publicKey = "newer")
        LinkPicker.pick(setup(r), emptyMap(), null, emptyMap(), mapOf("fake" to "acc"), mapOf(LinkPicker.cacheKey(r) to link(newer))) { p }
        assertEquals(newer, asked)
    }

    /** A server no longer offered blocks every route pinned to it in the same pass, whichever asked first. */
    @Test fun aServerNoLongerOfferedBlocksEveryRoutePinnedToIt() {
        var calls = 0
        val p = object : Provider by Fake(listOf(a)) { override fun connect(loc: Location, account: String): Link = if (calls++ == 0) link(loc.pinned!!) else throw app.unbraid.data.NoLongerOffered(a) }
        val s = setup(Route("1", Location("fake", countryCode = "NL", pinned = a)), Route("2", Location("fake", countryCode = "DE", pinned = a)))
        val out = LinkPicker.pick(s, emptyMap(), null, emptyMap(), mapOf("fake" to "acc"), emptyMap()) { p }
        assertEquals(setOf("1", "2"), out.blocked.keys)
        // Another host that once had this address is never taken from the cache for it.
        val r = Route("3", Location("fake", countryCode = "NL", pinned = a))
        val other = link(a.copy(host = "other.example"))
        val down = Fake(listOf(a), online = false)
        assertTrue(LinkPicker.pick(setup(r), emptyMap(), null, emptyMap(), mapOf("fake" to "acc"), mapOf(LinkPicker.cacheKey(r) to other)) { down }.links.isEmpty())
        // The pin decision: as listed now, as saved without a list, gone when the list lacks it.
        val fresh = a.copy(ip = "10.0.0.88")
        assertEquals(fresh, a.asListed(listOf(fresh)))
        assertEquals(a, a.asListed(null))
        try { a.asListed(listOf(b.copy(host = "elsewhere.example"))); fail() } catch (e: app.unbraid.data.NoLongerOffered) {}
    }

    /** A pinned server the provider no longer lists is blocked, never brought back from the cache. */
    @Test fun aServerNoLongerOfferedIsNotTakenFromTheCache() {
        val gone = object : Provider by Fake(listOf(a)) { override fun connect(loc: Location, account: String): Link = throw app.unbraid.data.NoLongerOffered(a) }
        val r = Route("1", Location("fake", countryCode = "NL", pinned = a))
        val out = LinkPicker.pick(setup(r), emptyMap(), null, emptyMap(), mapOf("fake" to "acc"), mapOf(LinkPicker.cacheKey(r) to link(a))) { gone }
        assertTrue(out.links.isEmpty())
        assertTrue(out.blocked.getValue("1") is app.unbraid.data.NoLongerOffered)
    }

    @Test fun aSignedOutProvidersRouteIsBlockedAndTheOthersStillRun() {
        val other = object : Provider by Fake(listOf(b)) { override val id = "other" }
        val s = setup(Route("1", Location("fake", countryCode = "NL")), Route("2", Location("other", countryCode = "DE")))
        val r = LinkPicker.pick(s, emptyMap(), null, emptyMap(), mapOf("fake" to "acc"), emptyMap()) { if (it == "fake") Fake(listOf(a)) else other }
        assertEquals(a, r.links.getValue("1").server)
        assertTrue(r.blocked.getValue("2") is AuthException)
        assertTrue("2" !in r.links && "2" !in r.remember.values.map { it.server.name })
    }

    /** The entry address the provider page picks, by number; a tunnel without that many entries keeps its own. */
    @Test fun entryFollowsTheProviderPage() {
        val peer = WgPeer("x", "k", listOf("10.0.0.2/32"), "p", endpoint = "198.51.100.1:1637", allowedIps = listOf("0.0.0.0/0"))
        val s = setup(Route("1", Location("fake", countryCode = "NL")), Route("2", Location("fake", countryCode = "DE")))
        val links = mapOf("1" to WgLink(a, peer, listOf(1637, 47107), listOf("198.51.100.1", "198.51.100.3")), "2" to WgLink(a, peer))
        fun at(s: Setup, step: Map<String, Int> = emptyMap()) = LinkPicker.ported(s, links, step).mapValues { (it.value as WgLink).peer.endpoint }
        assertEquals(mapOf("1" to "198.51.100.1:1637", "2" to "198.51.100.1:1637"), at(s))
        val alt = s.copy(opts = mapOf("fake" to mapOf("entry" to "2")))
        assertEquals(mapOf("1" to "198.51.100.3:1637", "2" to "198.51.100.1:1637"), at(alt))
        assertEquals("198.51.100.3:47107", at(alt, mapOf("fake" to 1))["1"])
        assertEquals("198.51.100.1:1637", at(s.copy(opts = mapOf("fake" to mapOf("entry" to "9"))))["1"])
    }

    /** A fixed port wins; Automatic walks the server's ports one step at a time, per provider; imports keep theirs. */
    @Test fun portsFollowTheProviderPage() {
        val peer = WgPeer("x", "k", listOf("10.0.0.2/32"), "p", endpoint = "198.51.100.1:2049", allowedIps = listOf("0.0.0.0/0"))
        val l = WgLink(a, peer, listOf(2049, 443, 53))
        val s = setup(Route("1", Location("fake", countryCode = "NL")), Route("2", wg = peer))
        val links = mapOf("1" to l, "2" to WgLink(a, peer, listOf(1, 2)))
        fun port(s: Setup, step: Map<String, Int> = emptyMap(), id: String = "1") = (LinkPicker.ported(s, links, step)[id] as WgLink).peer.endpoint
        assertEquals("198.51.100.1:2049", port(s))
        assertEquals("198.51.100.1:443", port(s, mapOf("fake" to 1)))
        assertEquals("198.51.100.1:2049", port(s, mapOf("fake" to 3)))
        assertEquals("198.51.100.1:53", port(s, mapOf("other" to 1, "fake" to 2)))
        assertEquals("198.51.100.1:2049", port(s, mapOf("fake" to 1), "2"))
        assertEquals("198.51.100.1:80", port(s.copy(opts = mapOf("fake" to mapOf("port" to "80"))), mapOf("fake" to 1)))
        assertEquals("198.51.100.1:2049", port(s.copy(opts = mapOf("fake" to mapOf("port" to "99999")))))
        // A tunnel saved without a list (offline) still takes its provider's step, so two on one server never differ.
        val offline = mapOf("1" to l, "3" to WgLink(a, peer))
        val s3 = setup(Route("1", Location("fake", countryCode = "NL")), Route("3", Location("fake", countryCode = "DE")))
        assertEquals(setOf("198.51.100.1:443"), LinkPicker.ported(s3, offline, mapOf("fake" to 1)).values.map { (it as WgLink).peer.endpoint }.toSet())
    }

    /** Provider tunnels take their page's MTU (1280 by default, 1280 to 1420); imports keep theirs, at most 1280. */
    @Test fun mtuFollowsTheProviderPage() {
        val peer = WgPeer("x", "k", listOf("10.0.0.2/32"), "p", endpoint = "198.51.100.1:51820", allowedIps = listOf("0.0.0.0/0"))
        val s = setup(Route("1", Location("fake", countryCode = "NL")), Route("2", wg = peer), Route("3", wg = peer.copy(mtu = 1200)))
        val links = mapOf("1" to WgLink(a, peer), "2" to WgLink(a, peer), "3" to WgLink(a, peer.copy(mtu = 1200)))
        fun mtu(s: Setup) = LinkPicker.ported(s, links, emptyMap()).mapValues { (it.value as WgLink).peer.mtu }
        assertEquals(mapOf("1" to 1280, "2" to 1280, "3" to 1200), mtu(s))
        assertEquals(mapOf("1" to 1400, "2" to 1280, "3" to 1200), mtu(s.copy(opts = mapOf("fake" to mapOf("mtu" to "1400")))))
        for (bad in listOf("9000", "1000", "x")) assertEquals(1280, mtu(s.copy(opts = mapOf("fake" to mapOf("mtu" to bad))))["1"])
        assertEquals("auto", s.opt("anything", "mtu"))
    }
}
