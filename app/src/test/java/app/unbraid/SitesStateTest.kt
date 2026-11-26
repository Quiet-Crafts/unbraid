package app.unbraid

import app.unbraid.data.BLOCK
import app.unbraid.data.DIRECT
import app.unbraid.data.Home
import app.unbraid.data.Location
import app.unbraid.data.Nord
import app.unbraid.data.Profile
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.data.Sites
import app.unbraid.data.VERSION
import app.unbraid.data.WgLink
import app.unbraid.ui.siteHint
import app.unbraid.ui.siteState
import app.unbraid.ui.sitesBy
import app.unbraid.ui.summary
import app.unbraid.vpn.Compiler
import app.unbraid.vpn.LinkPicker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each state a saved website can be in, through every place that asks: what the compiler emits, what its row says,
 * whether it counts, and where a connection by an address from before goes. Its route keeps its tunnel in every state.
 */
class SitesStateTest {
    private val italy = Route("it", Location("nord", 106, "IT", "Italy"))
    private val base = Setup(routes = listOf(italy), defaultRoute = DIRECT, prefs = app.unbraid.data.Prefs(lan = false), version = VERSION)
    private val browser = "com.browser"

    /** One cell of the table: the setup with [name] on Italy and [more], what the row says, whether Italy is in use, where a fresh connection and one by a kept stand-in go. */
    private fun cell(s: Setup, name: String, row: String, inUse: Boolean, fresh: String, kept: String, reported: String? = null, blocked: Set<String> = emptySet()) {
        assertEquals(name, row, siteState(s, name, reported))
        assertTrue(name, s.activeRoutes.any { it.id == "it" })
        assertEquals(name, inUse, "it" in sitesBy(s, reported))
        if (!s.home.tailscale && BLOCK !in s.sites.values) assertEquals(name, !inUse, s.directOnly) // shown as protecting nothing unless it applies
        val (dns, route) = RoutingProbe.compiled(s, reported, blocked)
        assertEquals(name, fresh, RoutingProbe.connect(route, dns, browser, name))
        assertEquals(name, kept, RoutingProbe.connect(route, dns, browser, name, kept = "fake"))
        // The tunnel opens whenever the route has one to open, as in 3.0.2.
        val links = s.routes.associate { r -> Server("x #1", "x.nordvpn.com", "10.9.0.1", "5p4RkybdRU5uaDi90eu4KZPTFif0lKCg4Qp6t1c4F30=", 10).let { r.id to WgLink(it, Nord.peer(it, KEY)) } }
        val config = Json.parseToJsonElement(Compiler.compile(Compiler.Input(s, links, "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", reported, blocked = blocked))).jsonObject
        assertEquals(name, "it" !in blocked, config["endpoints"]!!.jsonArray.any { it.jsonObject["tag"]!!.jsonPrimitive.content == "r-it" })
    }

    @Test fun applied() = cell(base.copy(sites = mapOf("example.com" to "it")), "example.com", "Italy", true, "r-it", "r-it")

    @Test fun answeredByTheLocalNetwork() =
        cell(base.copy(prefs = base.prefs.copy(lan = true), sites = mapOf("x.fritz.box" to "it")), "x.fritz.box",
            "Not applied: your network answers this name while Local network bypasses the VPN is on", false, "direct", "reject")

    @Test fun answeredByTheTailnet() =
        cell(base.copy(home = Home(tailscale = true), sites = mapOf("nas.tail1.ts.net" to "it")), "nas.tail1.ts.net",
            "Not applied: Tailscale answers ts.net names while Reach your tailnet is on", false, "home-ts", "reject")

    /**
     * On Headscale only once Tailscale reports the domain the user allowed; before, the website applies.
     */
    /**
     * After connect the lists and widgets judge by the tailnet domain the running config was compiled with: the first On
     * state carries it, as a reload that changes nothing never sets it again. Without it this website counted on Italy
     * while the engine sent it to the tailnet.
     */
    @Test fun connectShowsTheTailnetDomainItRunsWith() {
        val s = base.copy(home = Home(tailscale = true, tsControlUrl = "https://hs.example.org", tsSuffixAllowed = "corp.example"), sites = mapOf("box.corp.example" to "it"))
        val compiled = Compiler.Input(s, emptyMap(), "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", "corp.example")
        val on = app.unbraid.vpn.VpnState(app.unbraid.vpn.VpnState.Phase.On, tsSuffix = compiled.tsSuffix)
        assertEquals(emptyMap<String, String>(), Sites.applied(s, reported = on.tsSuffix))
        assertEquals(s.sites, Sites.applied(s, reported = null)) // what the first On state said before
        val connect = java.io.File("src/main/java/app/unbraid/vpn/UnbraidVpn.kt").readText()
            .substringAfter("private fun connect(").substringBefore("\n    }\n")
        val first = connect.indexOf("state.value = VpnState(VpnState.Phase.On,")
        assertTrue(connect.substring(first).substringBefore("\n").contains("tsSuffix = lastInput?.tsSuffix"))
        val applied = connect.indexOf("apply(srv, setup, picked, accounts, seen)")
        val current = connect.indexOf("requireCurrent(seen)", applied)
        assertTrue(applied in 0 until current && current < first)
    }

    @Test fun answeredByAHeadscaleTailnet() {
        val s = base.copy(home = Home(tailscale = true, tsControlUrl = "https://hs.example.org", tsSuffixAllowed = "corp.example"), sites = mapOf("box.corp.example" to "it"))
        cell(s, "box.corp.example", "Not applied: your tailnet answers this name while Reach your tailnet is on", false, "home-ts", "reject", reported = "corp.example")
        cell(s, "box.corp.example", "Italy", true, "r-it", "r-it")
        cell(s, "box.corp.example", "Italy", true, "r-it", "r-it", reported = "other.example")
        assertEquals("Direct · no apps elsewhere · 1 website, 1 not applied", summary(s, Profile("P", DIRECT, sites = s.sites), "corp.example"))
        assertEquals("Direct · no apps elsewhere · 1 website", summary(s, Profile("P", DIRECT, sites = s.sites)))
    }

    /**
     * Under a blocked website: the most specific rule wins, so a name with a route of its own takes that route, with every
     * name under it, while the rest of the blocked website stays blocked.
     */
    @Test fun underABlockedWebsite() {
        val s = base.copy(sites = mapOf("example.com" to BLOCK, "api.example.com" to "it"))
        cell(s, "api.example.com", "Italy", true, "r-it", "r-it")
        assertEquals(s.sites, Sites.applied(s))
        assertEquals("Covers api.example.com and every address under it", siteHint(s - "api.example.com", "api.example.com", "it"))
        val (dns, route) = RoutingProbe.compiled(s, null, emptySet())
        assertEquals("r-it", RoutingProbe.connect(route, dns, browser, "cdn.api.example.com"))
        assertEquals("none", RoutingProbe.connect(route, dns, browser, "www.example.com"))
        assertEquals("none", RoutingProbe.connect(route, dns, browser, "example.com"))
        assertEquals("reject", RoutingProbe.connect(route, dns, browser, "www.example.com", kept = "fake"))
        // A block under a block, and a block under a route, stay blocks.
        assertEquals("Blocked, no traffic", siteState(s.copy(sites = s.sites + ("ads.api.example.com" to BLOCK)), "ads.api.example.com"))
        assertEquals(mapOf("x.example.org" to BLOCK, "example.org" to "it"), Sites.applied(base.copy(sites = mapOf("x.example.org" to BLOCK, "example.org" to "it"))))
        // Only a name under it: "notexample.com" is not.
        assertEquals("Italy", siteState(base.copy(sites = mapOf("example.com" to BLOCK, "notexample.com" to "it")), "notexample.com"))
    }

    /** A route with no tunnel (signed out, waiting) is still in use: it waits; its website is refused, fresh or by an address from before. */
    @Test fun onARouteWithoutATunnel() = cell(base.copy(sites = mapOf("example.com" to "it")), "example.com", "Italy", true, "reject", "reject", blocked = setOf("it"))

    /** A name an older version saved that today's reader refuses applies as it did. */
    @Test fun aNameNoLongerReadable() = cell(base.copy(sites = mapOf("x.0xff" to "it")), "x.0xff", "Italy", true, "r-it", "r-it")

    /** Turning the local network bypass off makes a website apply again: that counts as a change that moves traffic off Direct, so what refuses it until its route is ready is installed at once. */
    @Test fun aWebsiteThatStartsApplyingLeavesDirect() {
        val on = base.copy(prefs = base.prefs.copy(lan = true), sites = mapOf("x.fritz.box" to "it"))
        val off = on.copy(prefs = on.prefs.copy(lan = false))
        assertTrue(LinkPicker.leavesDirect(on, off, off.activeRoutes.map { it.id }.toSet()))
        assertFalse(LinkPicker.leavesDirect(on, on, setOf("it")))
        // The tailnet's domain alone changing does too: a Direct website a Headscale domain now answers.
        val hs = base.copy(home = Home(tailscale = true, tsControlUrl = "https://hs.example.org", tsSuffixAllowed = "corp.example"), routes = listOf(italy), defaultRoute = "it", sites = mapOf("box.corp.example" to DIRECT))
        assertTrue(LinkPicker.leavesDirect(hs, hs, setOf("it"), oldSuffix = null, newSuffix = "corp.example"))
        assertFalse(LinkPicker.leavesDirect(hs, hs, setOf("it"), oldSuffix = "corp.example", newSuffix = "corp.example"))
    }

    /**
     * A saved rule for a reverse lookup zone ("168.192.in-addr.arpa", refused as new input) applies in every state: no
     * earlier rule answers reverse lookups (the local network's answers local, lan, home.arpa and fritz.box; the tailnet's
     * its own domains), so without it they go to the asking app's or the default's resolver, a VPN's unless Direct. The
     * rule only picks another resolver for them, the one the user saved; no connection ever carries such a name.
     */
    @Test fun aReverseZoneOnlyPicksTheResolverOfItsLookups() {
        val zone = "168.192.in-addr.arpa"
        for (s in listOf(base, base.copy(prefs = base.prefs.copy(lan = true)), base.copy(home = Home(tailscale = true)))) {
            val (dns, _) = RoutingProbe.compiled(s.copy(sites = mapOf(zone to "it")), null)
            assertEquals("dns-r-it", RoutingProbe.lookup(dns, browser, "20.1.$zone", "PTR"))
            assertEquals("local", RoutingProbe.lookup(RoutingProbe.compiled(s, null).first, browser, "20.1.$zone", "PTR")) // Direct default: the phone's resolver
            assertEquals("Italy", siteState(s.copy(sites = mapOf(zone to "it")), zone))
        }
        val tailnet = base.copy(home = Home(tailscale = true), sites = mapOf("64.100.in-addr.arpa" to "it"))
        assertEquals("dns-r-it", RoutingProbe.lookup(RoutingProbe.compiled(tailnet, null).first, browser, "9.0.64.100.in-addr.arpa", "PTR"))
    }

    /** A name that answers with a local and a public address: the local route gets the local one, never the public one. */
    @Test fun aMixedAnswerTakesNoPublicAddressOutOfTheVpn() {
        val onItaly = base.copy(apps = mapOf(browser to "it"), prefs = base.prefs.copy(lan = true))
        val mixed = listOf("192.168.1.10", "203.0.113.5")
        val (dns, route) = RoutingProbe.compiled(onItaly, null)
        assertEquals("fake", RoutingProbe.lookup(dns, browser, "nas.example.com", "A"))
        assertEquals("direct" to listOf("192.168.1.10"), RoutingProbe.dial(route, dns, browser, "nas.example.com", answer = mixed))
        assertEquals("r-it" to listOf("203.0.113.5"), RoutingProbe.dial(route, dns, browser, "nas.example.com", answer = listOf("203.0.113.5")))
        // The same for the home tunnel: the public address never enters the tailnet.
        val (tsDns, tsRoute) = RoutingProbe.compiled(onItaly.copy(home = Home(tailscale = true)), null)
        assertEquals(Compiler.TS_TAG to listOf("100.64.0.7"), RoutingProbe.dial(tsRoute, tsDns, browser, "nas.example.com", answer = listOf("100.64.0.7", "203.0.113.5")))
    }

    /** Tailscale running only for an exit node (Reach your tailnet off): no rule answers a tailnet name, so a website rule for one applies. */
    @Test fun aTailnetNameAppliesWhileTailscaleOnlyCarriesAnExit() {
        val s = base.copy(routes = listOf(italy, Route("ts", tsExit = "100.64.0.9")), apps = mapOf("com.chat" to "ts"), sites = mapOf("nas.tail1.ts.net" to "it"))
        assertTrue(s.tailscale && !s.home.tailscale)
        val (dns, route) = RoutingProbe.compiled(s, "tail1.ts.net")
        assertTrue(dns.toString().contains("\"tag\":\"dns-tailnet\"").not() && !dns["rules"].toString().contains("dns-tailnet"))
        assertEquals("fake", RoutingProbe.lookup(dns, browser, "nas.tail1.ts.net", "A"))
        assertEquals("r-it", RoutingProbe.connect(route, dns, browser, "nas.tail1.ts.net"))
        assertEquals("Italy", siteState(s, "nas.tail1.ts.net", "tail1.ts.net"))
    }

    /**
     * A website above a private zone (a Headscale domain, a local name) never takes that zone: its lookups go to the
     * tailnet's or the network's resolver first, the tailnet's before the network's, and a stand-in address handed out
     * for a name in it before is refused, not sent the parent's way. The rest of the parent keeps its route.
     */
    @Test fun aParentWebsiteNeverTakesAPrivateZoneUnderIt() {
        val hs = { d: String -> Home(tailscale = true, tsControlUrl = "https://hs.example.org", tsSuffixAllowed = d) }
        for (to in listOf("it", DIRECT)) {
            val (dns, route) = RoutingProbe.compiled(base.copy(home = hs("tail.example.org"), sites = mapOf("example.org" to to)), "tail.example.org")
            assertEquals("dns-tailnet", RoutingProbe.lookup(dns, browser, "wiki.tail.example.org", "A"))
            assertEquals("dns-tailnet", RoutingProbe.lookup(dns, browser, "wiki.tail.example.org", "TXT"))
            assertEquals("reject", RoutingProbe.connect(route, dns, browser, "wiki.tail.example.org", kept = "fake"))
            val out = if (to == DIRECT) "direct" else "r-it"
            assertEquals(out, RoutingProbe.connect(route, dns, browser, "www.example.org")); assertEquals(out, RoutingProbe.connect(route, dns, browser, "www.example.org", kept = "fake"))
        }
        // A Headscale domain inside a local name: the tailnet answers it, the network the rest.
        val (dns, route) = RoutingProbe.compiled(base.copy(prefs = base.prefs.copy(lan = true), home = hs("corp.office.lan"), sites = mapOf("office.lan" to "it")), "corp.office.lan")
        assertEquals("dns-tailnet", RoutingProbe.lookup(dns, browser, "wiki.corp.office.lan", "A"))
        assertEquals("local", RoutingProbe.lookup(dns, browser, "printer.office.lan", "A"))
        for (n in listOf("wiki.corp.office.lan", "printer.office.lan")) assertEquals(n, "reject", RoutingProbe.connect(route, dns, browser, n, kept = "fake"))
    }

    /** A blocked website under a local or tailnet name blocks in every state: its lookups and a stand-in address from before are refused. */
    @Test fun aBlockedWebsiteBlocksInEveryState() {
        val names = listOf("x.fritz.box", "nas.tail1.ts.net", "box.corp.example")
        val hs = Home(tailscale = true, tsControlUrl = "https://hs.example.org", tsSuffixAllowed = "corp.example")
        for (lan in listOf(true, false)) for ((home, reported) in listOf(Home() to null, Home(tailscale = true) to "tail1.ts.net", Home(tailscale = true) to null, hs to "corp.example", hs to null)) {
            val s = base.copy(prefs = base.prefs.copy(lan = lan), home = home, sites = names.associateWith { BLOCK })
            val (dns, route) = RoutingProbe.compiled(s, reported)
            for (n in names) for (x in listOf(n, "a.$n")) {
                assertEquals("$x $lan $reported", "reject", RoutingProbe.lookup(dns, browser, x, "A"))
                assertEquals("$x $lan $reported", "reject", RoutingProbe.connect(route, dns, browser, x, kept = "fake"))
            }
            for (n in names) assertEquals("Blocked, no traffic", siteState(s, n, reported))
        }
    }

    /**
     * Reach your tailnet on with Tailscale's own server: all of ts.net goes to the tailnet's resolver, as in 3.0.2, the
     * user's own tailnet and the names of devices other tailnets share with it alike. So a rule for another tailnet's
     * public (Funnel) name doesn't apply, and its row says so; the tailnet answers that name only if it knows it.
     */
    @Test fun aFunnelNameOfAnotherTailnetIsTheTailnets() {
        val s = base.copy(home = Home(tailscale = true), sites = mapOf("service.tail-b.ts.net" to "it"))
        val (dns, route) = RoutingProbe.compiled(s, "tail-a.ts.net")
        assertEquals("dns-tailnet", RoutingProbe.lookup(dns, browser, "service.tail-b.ts.net", "A"))
        assertEquals("reject", RoutingProbe.connect(route, dns, browser, "service.tail-b.ts.net", kept = "fake"))
        assertEquals("Not applied: Tailscale answers ts.net names while Reach your tailnet is on", siteState(s, "service.tail-b.ts.net", "tail-a.ts.net"))
        assertFalse(dns["rules"].toString().contains("service.tail-b.ts.net"))
    }

    private operator fun Setup.minus(site: String) = copy(sites = sites - site)

    private companion object { const val KEY = "aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8tMTI=" }
}
