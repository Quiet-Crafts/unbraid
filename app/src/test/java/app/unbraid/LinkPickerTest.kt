package app.unbraid

import app.unbraid.data.AuthException
import app.unbraid.data.Country
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
        try { pick(setup(Route("1", loc)), p, mapOf(other)); fail("expected failure") } catch (e: Fail) { assertEquals("Fastest: a is unreachable", e.message) }
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
        try { pick(setup(r), p, mapOf(LinkPicker.cacheKey(r) to link(a))); fail("an untrusted cached tunnel was reused") } catch (e: java.io.IOException) { assertEquals("down", e.message) }
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

    @Test fun signedOutProviderFails() {
        try { LinkPicker.pick(setup(Route("1", Location("fake"))), emptyMap(), null, emptyMap(), emptyMap(), emptyMap()) { Fake(listOf(a)) }; fail() } catch (e: AuthException) {}
    }
}
