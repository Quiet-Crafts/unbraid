package app.unbraid

import app.unbraid.data.Backup
import app.unbraid.data.DIRECT
import app.unbraid.data.Location
import app.unbraid.data.Nord
import app.unbraid.data.Profile
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.data.Sites
import app.unbraid.data.Store
import app.unbraid.data.VERSION
import app.unbraid.data.WgLink
import app.unbraid.data.restoredFrom
import app.unbraid.stats.Stats
import app.unbraid.ui.appLabel
import app.unbraid.ui.appsLabel
import app.unbraid.ui.flowsSub
import app.unbraid.ui.laneCount
import app.unbraid.ui.dropText
import app.unbraid.ui.emptySites
import app.unbraid.ui.shownSites
import app.unbraid.ui.missingSitesText
import app.unbraid.ui.removeText
import app.unbraid.ui.siteError
import app.unbraid.ui.siteState
import app.unbraid.ui.sitesNote
import app.unbraid.ui.summary
import app.unbraid.vpn.Compiler
import app.unbraid.vpn.LinkPicker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Websites with a route of their own: how an address is kept, what the engine gets, and how profiles, saves and backups carry them. */
class SitesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val wgKey = "aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8tMTI="
    private fun sv(n: String) = Server("$n #1", "$n.nordvpn.com", "10.9.0.${n.hashCode() and 0x7f}", "5p4RkybdRU5uaDi90eu4KZPTFif0lKCg4Qp6t1c4F30=", 10)

    /** NL default, app A on US, example.com on IT (no app uses IT), a name under it on US, bank.example Direct. */
    private val setup = Setup(
        routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands")), Route("us", Location("nord", 228, "US", "United States")), Route("it", Location("nord", 106, "IT", "Italy"))),
        defaultRoute = "nl", apps = mapOf("com.a" to "us"),
        sites = mapOf("example.com" to "it", "api.example.com" to "us", "bank.example" to DIRECT), version = VERSION,
    )

    private fun compile(s: Setup, blocked: Set<String> = emptySet(), tsSuffix: String? = null): JsonObject {
        val links = s.activeRoutes.filter { it.id !in blocked }.associate { it.id to WgLink(sv(it.id), Nord.peer(sv(it.id), wgKey)) }
        return Json.parseToJsonElement(Compiler.compile(Compiler.Input(s, links, "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", tsSuffix, blocked = blocked))).jsonObject.also { assertRefusesOtherBlocks(it) }
    }
    private fun JsonObject.rules() = this["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.dns() = this["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.strings(k: String) = this[k]?.jsonArray?.map { it.jsonPrimitive.content }
    private fun JsonObject.text(k: String) = this[k]?.jsonPrimitive?.content

    @Test fun anAddressIsKeptAsItsName() {
        for ((raw, want) in listOf(
            "netflix.com" to "netflix.com", " https://www.Netflix.com/browse?x=1#top " to "netflix.com", "http://user:pw@Example.org:8080/a" to "example.org",
            "sub.example.co.uk." to "sub.example.co.uk", "www.rai.it" to "rai.it", "www.www.example.com" to "example.com", "WWW.www.com" to "www.com", "münchen.de" to "xn--mnchen-3ya.de", "www.com" to "www.com",
        )) assertEquals(raw, want, Sites.clean(raw))
        for (bad in listOf("", "   ", "localhost", "com", "192.168.1.1", "http://10.0.0.1/x", "[2001:db8::1]", "exa mple.com", "-bad.com", "bad-.com", "a_b.com", "*.example.com",
            "example..com", "a".repeat(64) + ".com", ("a".repeat(60) + ".").repeat(5) + "com")) assertNull(bad, Sites.clean(bad))
        // What is kept reads back as itself: a saved address is checked that way.
        for (raw in listOf("WWW.netflix.com", "www.www.example.com", "www.com", "www.www.com", "https://www.a.b.example/x")) Sites.clean(raw)!!.let { assertEquals(it, Sites.clean(it)) }
        // So a backup of any address the app accepts opens again.
        assertEquals(mapOf("example.com" to "it"), Backup.read(Backup.export(setup.copy(sites = mapOf(Sites.clean("www.www.example.com")!! to "it")), "2.10.0", 0)).setup.sites)
    }

    @Test fun aNameUnderAnotherComesFirstAndRunsShareARoute() {
        assertEquals(listOf("us" to listOf("api.example.com"), DIRECT to listOf("bank.example"), "it" to listOf("example.com")), Sites.runs(setup.sites))
        assertEquals(listOf("us" to listOf("a.com", "b.com")), Sites.runs(mapOf("b.com" to "us", "a.com" to "us")))
    }

    /** A route only a website uses gets its tunnel, and the website's rules come before every app's, after the home and LAN rules. */
    @Test fun aWebsitesRouteBeatsItsApps() {
        val c = compile(setup)
        assertEquals(listOf("r-nl", "r-us", "r-it"), c["endpoints"]!!.jsonArray.map { it.jsonObject.text("tag") })
        val rules = c.rules()
        val out = rules.filter { it["domain"] != null && it["outbound"] != null }
        assertEquals(listOf("r-us" to listOf("api.example.com"), "direct" to listOf("bank.example"), "r-it" to listOf("example.com")), out.map { it.text("outbound") to it.strings("domain") })
        // The name itself and every name under it, never a name that only ends the same ("notexample.com").
        assertEquals(listOf(".example.com"), out.last().strings("domain_suffix"))
        val firstApp = rules.indexOfFirst { it["package_name"] != null }
        assertTrue(rules.indexOf(out.last()) < firstApp)
        val lan = rules.indexOfFirst { it.strings("ip_cidr")?.contains("192.168.0.0/16") == true }
        assertTrue(lan in 0 until rules.indexOf(out.first()))
        // Each is looked up at its own exit first (Direct: the phone's resolver), and the LAN rule follows that lookup.
        val look = rules.filter { it["domain"] != null && it.text("action") == "resolve" }
        assertEquals(listOf("dns-r-us", "local", "dns-r-it"), look.map { it.text("server") })
        look.forEach { assertTrue(rules[rules.indexOf(it) + 1].strings("ip_cidr")!!.contains("192.168.0.0/16")) }
        // Other record types of a website's names go to its exit, before any app's rule.
        val dns = c.dns()
        assertEquals(listOf("dns-r-us", "local", "dns-r-it"), dns.filter { it["domain"] != null && it["server"] != null && it.strings("domain") != null && it["query_type"] == null && it.strings("domain")!!.none { d -> d == "local" } }.map { it.text("server") })
        assertTrue(dns.indexOfFirst { it.strings("domain") == listOf("example.com") } < dns.indexOfFirst { it["package_name"] != null })
        assertTrue(dns.indexOfFirst { it.text("server") == "fake" } < dns.indexOfFirst { it.strings("domain") == listOf("example.com") })
    }

    /** A name is only ever the one a lookup gave: nothing is read off a connection, where an app could write any name to take a website's route. */
    @Test fun noNameIsReadOffAConnection() {
        assertTrue(compile(setup).rules().none { it.text("action") == "sniff" })
        assertTrue(compile(setup.copy(sites = emptyMap())).rules().none { it["domain"] != null })
    }

    /** A website on a route without a tunnel, or on one that is gone, is refused: never sent on with its app or the default. */
    @Test fun aWebsiteWithoutATunnelIsRefused() {
        val c = compile(setup, blocked = setOf("it"))
        val r = c.rules().single { it.strings("domain") == listOf("example.com") }
        assertEquals("reject", r.text("action")); assertEquals("true", r.text("no_drop"))
        assertEquals("reject", c.dns().single { it.strings("domain") == listOf("example.com") }.text("action"))
        val gone = setup.copy(routes = setup.routes.filter { it.id != "it" })
        assertEquals(setOf("it"), Compiler.missing(gone)); assertEquals(setOf("example.com"), gone.sitesOnMissing)
        assertEquals("reject", compile(gone).rules().single { it.strings("domain") == listOf("example.com") }.text("action"))
    }

    /** While Tailscale waits for a sign-in, a website on its exit that is itself a sign-in page goes Direct, before its own lookup. */
    @Test fun aWebsiteOnATailscaleExitLeavesTheSignInPagesOut() {
        val s = Setup(routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands")), Route("ts", tsExit = "100.64.0.9")), defaultRoute = "nl",
            sites = mapOf("tailscale.com" to "ts"), home = app.unbraid.data.Home(tailscale = true), version = VERSION)
        val links = mapOf("nl" to WgLink(sv("nl"), Nord.peer(sv("nl"), wgKey)), "ts" to app.unbraid.data.TsLink(sv("ts")))
        fun rules(login: Boolean) = Json.parseToJsonElement(Compiler.compile(Compiler.Input(s, links, "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", tsLoginPending = login))).jsonObject.rules()
        val pending = rules(true)
        val escape = pending.indexOfFirst { it.text("type") == "logical" && it.text("outbound") == "direct" }
        assertTrue(escape in 0 until pending.indexOfFirst { it.strings("domain") == listOf("tailscale.com") && it.text("action") == "resolve" })
        assertEquals(listOf(listOf("tailscale.com"), null), pending[escape]["rules"]!!.jsonArray.map { it.jsonObject.strings("domain") })
        assertTrue(rules(false).none { it.text("type") == "logical" })
    }

    /** HTTPS and SVCB lookups of a routed website are answered empty, before its other types go to its exit; every other lookup is as before. */
    @Test fun addressHintsOfARoutedWebsiteAreAnsweredEmpty() {
        val c = compile(setup)
        val dns = c.dns()
        fun empty(vararg names: String) = dns.indexOfFirst { it.strings("domain") == names.toList() && it.strings("query_type") == listOf("HTTPS", "SVCB") }
        for (names in listOf(arrayOf("api.example.com"), arrayOf("bank.example"), arrayOf("example.com"))) {
            val i = empty(*names)
            assertTrue(names.joinToString(), i >= 0)
            assertEquals("predefined", dns[i].text("action")); assertEquals("NOERROR", dns[i].text("rcode")); assertEquals(null, dns[i]["server"])
            assertEquals(listOf(names.single().let { ".$it" }), dns[i].strings("domain_suffix"))
            // Right before the website's own rule for the other types, which still asks its exit; both after the stand-in rule for A and AAAA.
            assertEquals(names.toList(), dns[i + 1].strings("domain")); assertTrue(dns[i + 1].text("server") != null && dns[i + 1]["query_type"] == null)
            assertTrue(dns.indexOfFirst { it.text("server") == "fake" } < i)
        }
        assertEquals(listOf("A", "AAAA"), dns.single { it.text("server") == "fake" }.strings("query_type"))
        // A refused website (no tunnel) and a blocked one keep their refusals; a setup without websites gains no rule.
        val refused = compile(setup, blocked = setOf("it")).dns()
        assertEquals(listOf("reject"), refused.filter { it.strings("domain") == listOf("example.com") }.map { it.text("action") })
        val blocked = compile(setup.copy(sites = mapOf("example.com" to app.unbraid.data.BLOCK))).dns()
        assertEquals(listOf("reject"), blocked.filter { it.strings("domain") == listOf("example.com") }.map { it.text("action") })
        assertTrue(compile(setup.copy(sites = emptyMap())).dns().none { it.strings("query_type") == listOf("HTTPS", "SVCB") })
    }

    /**
     * A route or Direct for a name the tailnet's or the local network's lookup rule answers first gets no rule, exactly
     * while that rule is on; a block of one stays a block. The same names apply as ordinary websites otherwise.
     */
    @Test fun aNameAnsweredByAnEarlierLookupRuleGetsNoRule() {
        val home = app.unbraid.data.Home(tailscale = true, tsControlUrl = "https://hs.example.org", tsSuffixAllowed = "corp.example")
        val sites = mapOf("printer.lan" to "it", "box.corp.example" to "it", "nas.ts.net" to app.unbraid.data.BLOCK, "x.fritz.box" to DIRECT, "example.com" to "it", "host.tail9.ts.net" to "it")
        val s = setup.copy(home = home, sites = sites)
        // Headscale: its own domain is the tailnet's once Tailscale reports it, ts.net is anyone's (a Funnel site, say).
        assertEquals(mapOf("nas.ts.net" to app.unbraid.data.BLOCK, "example.com" to "it", "host.tail9.ts.net" to "it"), Sites.applied(s, reported = "corp.example"))
        // Before it reports that domain (or reports another), the tailnet's lookup rule isn't there: the website applies,
        // and its route stays in use, so the compiler never names a route without a tunnel.
        assertEquals(sites - "printer.lan" - "x.fritz.box", Sites.applied(s)); assertEquals(sites - "printer.lan" - "x.fritz.box", Sites.applied(s, reported = "other.example"))
        assertTrue(compile(s).rules().any { it.strings("domain")?.contains("box.corp.example") == true && it.text("outbound") == "r-it" })
        val c = compile(s, tsSuffix = "corp.example")
        val named = c.rules().filter { it["domain"] != null && it["outbound"] != null }.flatMap { it.strings("domain")!! }
        assertEquals(listOf("example.com", "host.tail9.ts.net"), named.sorted())
        // The names left out are refused only on a connection that still carries them (a stand-in address from before);
        // their lookups go to the rule that answers them, as in 3.0.2, with no rule of their own.
        assertEquals(listOf("box.corp.example", "printer.lan", "x.fritz.box", "nas.ts.net"), c.rules().filter { it.text("action") == "reject" && it["domain"] != null }.flatMap { it.strings("domain")!! })
        assertTrue(c.dns().none { r -> r.strings("domain").orEmpty().any { it in setOf("printer.lan", "x.fritz.box", "box.corp.example") } })
        // Tailscale's own server: all of ts.net is the tailnet's. Reach your tailnet off, or the local network not bypassing the VPN: every one applies.
        assertEquals(setOf("nas.ts.net", "example.com", "box.corp.example"), Sites.applied(s.copy(home = home.copy(tsControlUrl = null))).keys)
        assertEquals(sites.keys - "printer.lan" - "x.fritz.box", Sites.applied(s.copy(home = home.copy(tailscale = false))).keys)
        assertEquals(sites, Sites.applied(s.copy(home = home.copy(tailscale = false), prefs = s.prefs.copy(lan = false))))
        // The rows say why, in the screen's words.
        assertEquals("Not applied: your network answers this name while Local network bypasses the VPN is on", siteState(s, "printer.lan"))
        assertEquals("Not applied: your tailnet answers this name while Reach your tailnet is on", siteState(s, "box.corp.example", "corp.example"))
        assertEquals("Italy", siteState(s, "box.corp.example"))
        assertEquals("Not applied: Tailscale answers ts.net names while Reach your tailnet is on", siteState(s.copy(home = home.copy(tsControlUrl = null)), "host.tail9.ts.net"))
        assertEquals("Blocked, no traffic", siteState(s, "nas.ts.net")); assertEquals("Italy", siteState(s, "example.com")); assertEquals("Italy", siteState(s, "host.tail9.ts.net"))
        // Adding one now says it won't apply, unless it is blocked.
        assertEquals("Covers nas.lan and every address under it. Not applied now: your network answers this name while Local network bypasses the VPN is on", app.unbraid.ui.siteHint(s, "nas.lan", "it"))
        assertEquals("Covers nas.lan and every address under it", app.unbraid.ui.siteHint(s, "nas.lan", app.unbraid.data.BLOCK))
    }

    /** A name an older version saved that today's reader refuses is listed, marked, routed as before and removable. */
    @Test fun aNameSavedByAnOlderVersionStaysUsable() {
        val old = listOf("x.0xff", "xn--ax-iuk.com") // a number as the last label; a Hangul filler browsers drop
        val s = setup.copy(sites = old.associateWith { "it" })
        for (n in old) {
            assertNull(n, Sites.clean(n)); assertEquals(n, Sites.shown(n)); assertTrue(n, n in Sites.readings(n))
            assertEquals("Italy · not an address browsers open", siteState(s, n))
        }
        assertEquals(old, shownSites(s, "", null)); assertEquals(old.toSet(), Sites.applied(s).keys)
        assertTrue(compile(s).rules().any { it.strings("domain")?.containsAll(old) == true && it.text("outbound") == "r-it" })
        assertEquals(listOf("xn--ax-iuk.com"), s.site(null, "x.0xff", null).sites.keys.toList())
    }

    /** A digit-leading label beside a right-to-left one: new input is refused, but a saved name browsers open is not called unopenable. */
    @Test fun aSavedNameOnlyTheDirectionRuleRefusesIsNotCalledUnopenable() {
        val n = "123.xn--mgbh0fb.com"
        assertNull(Sites.clean(n)); assertTrue(Sites.stored(n)); assertTrue(Sites.opens(n))
        assertEquals("Italy", siteState(setup.copy(sites = mapOf(n to "it")), n))
        assertFalse(Sites.opens("xn--ax-iuk.com")); assertFalse(Sites.opens("x.0xff"))
    }

    /** A website that doesn't apply opens no tunnel, protects nothing, and is in no count of what a route carries. */
    @Test fun aWebsiteNotAppliedIsNotInUse() {
        val s = setup.copy(defaultRoute = DIRECT, apps = emptyMap(), sites = mapOf("printer.lan" to "it"))
        assertTrue(Sites.applied(s).isEmpty())
        assertEquals(emptyList<String>(), s.activeRoutes.map { it.id })
        assertTrue(s.directOnly)
        assertFalse(app.unbraid.vpn.glance(app.unbraid.vpn.VpnState(app.unbraid.vpn.VpnState.Phase.On), s).protects)
        assertEquals(emptyMap<String, List<String>>(), app.unbraid.ui.sitesBy(s))
        assertEquals("Direct · no apps elsewhere · 1 website, 1 not applied", summary(s, Profile("P", DIRECT, sites = s.sites)))
        assertTrue(compile(s).rules().none { it["domain"] != null && it.text("action") != "reject" })
        // The same website where it applies: on Italy, with a tunnel, and counted.
        val on = s.copy(prefs = s.prefs.copy(lan = false))
        assertEquals(listOf("it"), on.activeRoutes.map { it.id }); assertFalse(on.directOnly)
        assertEquals(mapOf("it" to listOf("printer.lan")), app.unbraid.ui.sitesBy(on))
        assertEquals("Direct · no apps elsewhere · 1 website", summary(on, Profile("P", DIRECT, sites = on.sites)))
    }

    @Test fun profilesCarryTheirWebsites() {
        val s = setup.saveAs("Home").add("Trip", empty = false).site("Trip", "rai.it", "it").site("Trip", "example.com", null)
        assertEquals(setup.sites, s.sites) // a profile not in use is edited alone
        val trip = s.use("Trip")
        assertEquals(mapOf("api.example.com" to "us", "bank.example" to DIRECT, "rai.it" to "it"), trip.sites)
        assertEquals(setup.sites, trip.profiles.single { it.name == "Home" }.sites)
        assertEquals(setup.sites, trip.viewAs("Home").sites)
        assertEquals(setup.sites, trip.use("Home").sites)
        // The one in use follows every edit; a failed switch goes back to what ran, websites included.
        assertEquals(mapOf("x.org" to "nl"), s.site(null, "x.org", "nl").saved.single { it.name == "Home" }.sites - setup.sites.keys)
        assertEquals(s.sites, trip.back(s).sites); assertEquals("Home", trip.back(s).profile)
        // A website keeps its route listed and running; removing the route removes its websites everywhere.
        assertTrue("it" in trip.activeRoutes.map { it.id }); assertFalse(trip.hiddenRoutes(null).contains("it"))
        val tidy = trip.copy(routes = trip.routes.filter { it.id != "it" }).tidy(setOf("it"))
        assertTrue(tidy.sites.values.none { it == "it" } && tidy.profiles.all { p -> p.sites.values.none { it == "it" } })
        // Only websites on a VPN make a Direct setup worth running; a setup in no profile with websites is not held by one without.
        assertTrue(Setup(routes = setup.routes, defaultRoute = DIRECT, sites = mapOf("a.com" to DIRECT)).directOnly)
        assertFalse(Setup(routes = setup.routes, defaultRoute = DIRECT, sites = mapOf("a.com" to "us")).directOnly)
        assertTrue(setup.copy(profiles = listOf(Profile("P", "nl", setup.apps))).loose)
        // A website new on a route that waits for its server may have gone Direct until now.
        assertTrue(LinkPicker.leavesDirect(setup.copy(sites = emptyMap()), setup, setOf("it")))
        assertFalse(LinkPicker.leavesDirect(setup, setup, setOf("it")))
    }

    /** Saved as format 3 only with a website somewhere, so a setup without one still opens in a build from before them. */
    @Test fun onlyASetupWithWebsitesIsSavedInTheNewFormat() {
        assertEquals(VERSION, setup.stamped.version)
        assertEquals(2, setup.copy(sites = emptyMap()).stamped.version)
        assertEquals(VERSION, setup.copy(sites = emptyMap(), profiles = listOf(Profile("P", "nl", sites = mapOf("a.com" to "nl")))).stamped.version)
        val dir = tmp.newFolder()
        val store = Store(dir) { key }
        assertTrue(store.update { setup.copy(sites = emptyMap()) })
        fun saved() = Store(dir) { key }.setup.value
        assertEquals(VERSION, saved().version) // read back as the current format, whatever the file says
        assertTrue(store.update { it.site(null, "example.com", "it") })
        assertEquals(mapOf("example.com" to "it"), saved().sites)
        // Backups likewise: 2 without a website (an older build restores it), 3 with one.
        fun stamp(s: Setup) = Json.parseToJsonElement(String(Backup.export(s, "2.10.0", 0))).jsonObject["setup"]!!.jsonObject.text("version")
        assertEquals("2", stamp(setup.copy(sites = emptyMap()))); assertEquals("$VERSION", stamp(setup))
    }

    @Test fun backupsCarryAndCheckWebsites() {
        val s = setup.copy(profiles = listOf(Profile("Trip", "us", sites = mapOf("rai.it" to "it"))), onNet = mapOf("wifi" to "Trip"))
        val back = Backup.read(Backup.export(s, "2.10.0", 0)).setup
        assertEquals(s.sites, back.sites); assertEquals(s.profiles, back.profiles); assertEquals(s.onNet, back.onNet)
        fun refused(bad: Setup) = runCatching { Backup.valid(bad) }.exceptionOrNull() is Backup.Failed
        assertTrue(refused(s.copy(sites = mapOf("Example.com" to "it")))) // not as unbraid writes it
        assertTrue(refused(s.copy(sites = mapOf("example.com" to "gone"))))
        assertTrue(refused(s.copy(sites = (1..Sites.MAX + 1).associate { "s$it.example" to "it" })))
        assertTrue(refused(s.copy(profiles = listOf(Profile("Trip", "us", sites = mapOf("1.2.3.4" to "it"))))))
        assertTrue(refused(s.copy(onNet = mapOf("wifi" to "Nobody")))); assertTrue(refused(s.copy(onNet = mapOf("bluetooth" to "Trip"))))
        // Restoring over a setup kept as a profile: its websites come along, on its routes as they are named there.
        val here = Setup(routes = listOf(Route("it", Location("nord", 81, "DE", "Germany"))), defaultRoute = "it", sites = mapOf("spiegel.de" to "it"), version = VERSION)
        val r = here.restoredFrom(s, "Before", 0)!!
        val kept = r.profiles.single { it.name == "Before" }
        val de = r.route(kept.sites.getValue("spiegel.de"))!!
        assertEquals("DE", de.loc.countryCode); assertEquals(de.id, kept.defaultRoute); assertTrue(de.id != "it")
        assertEquals(s.sites, r.sites)
    }

    @Test fun whatTheScreensSay() {
        assertNull(siteError(setup, "")); assertNull(siteError(setup, "rai.it"))
        assertEquals("That isn't a website address. Try one like example.com", siteError(setup, "not a site"))
        assertEquals("This website is already on the list", siteError(setup, "https://www.example.com/"))
        assertEquals("A profile holds up to 50 websites", siteError(setup.copy(sites = (1..50).associate { "s$it.example" to "it" }), "rai.it"))
        assertEquals("Italy", siteState(setup, "example.com")); assertEquals("Direct, skips the VPN", siteState(setup, "bank.example"))
        assertEquals("A route that no longer exists", siteState(setup.copy(routes = emptyList()), "example.com"))
        // A website under a blocked one is blocked with it, whatever route it has, and its row says so.
        assertEquals("Blocked: it is under example.com", siteState(setup.copy(sites = mapOf("example.com" to app.unbraid.data.BLOCK, "api.example.com" to "us")), "api.example.com"))
        assertEquals("Blocked, no traffic", siteState(setup.copy(sites = mapOf("example.com" to app.unbraid.data.BLOCK)), "example.com"))
        assertTrue(sitesNote(true).endsWith("Apps set to Direct are outside unbraid: websites opened in them aren't re-routed."))
        assertFalse("Direct" in sitesNote(false))
        // A route's row names its apps and websites while they fit, then counts them.
        assertEquals("No apps assigned", appsLabel(emptyList(), emptyMap()))
        assertEquals("Mail, example.com", appsLabel(listOf("org.mail"), mapOf("org.mail" to "Mail"), listOf("example.com")))
        assertEquals("example.com", appsLabel(emptyList(), emptyMap(), listOf("example.com")))
        assertEquals("3 apps · 1 website", appsLabel(listOf("a", "b", "c"), emptyMap(), listOf("example.com")))
        assertEquals("4 websites", appsLabel(emptyList(), emptyMap(), listOf("a.com", "b.com", "c.com", "d.com")))
        assertEquals("No app chose it. 1 website on it goes with its app again. You can add the route again, but not those choices.", removeText(0, emptyList(), 1))
        assertEquals("No app in Work chose it. 2 websites on it go with their app again. The route stays for other profiles.", dropText("Work", 0, sites = 2))
        assertEquals("1 website is on a route that no longer exists, so it's blocked. Remove it, or choose a route on Apps.", missingSitesText(1))
        assertEquals("Netherlands · 1 app elsewhere · 3 websites", summary(setup, Profile("P", "nl", setup.apps, sites = setup.sites)))
    }

    /** The Websites list filters and searches like the Apps list, and groups the same way: other exits A to Z, Direct, then the default. */
    @Test fun theWebsitesListFiltersAndGroupsLikeApps() {
        val s = setup.copy(sites = setup.sites + ("nos.nl" to "nl") + ("rai.it" to "it"))
        assertEquals(listOf("example.com", "rai.it", "api.example.com", "bank.example", "nos.nl"), shownSites(s, "", null)) // Italy, United States, Direct, the default
        assertEquals(listOf("example.com", "rai.it"), shownSites(s, "", "it"))
        assertEquals(listOf("example.com", "api.example.com", "bank.example"), shownSites(s, "EXAMPLE", null))
        assertEquals(listOf("api.example.com"), shownSites(s, "example", "us"))
        assertEquals(listOf("bank.example"), shownSites(s, "", DIRECT))
        assertTrue(shownSites(s, "nothing", null).isEmpty())
        assertEquals("No websites yet. Add one to send it through a route of its own, in any browser or app." to false, emptySites(false, "", null))
        assertEquals("No websites use Italy." to true, emptySites(true, "", "Italy"))
        assertEquals("No websites on Italy match \"x\"." to true, emptySites(true, "x", "Italy"))
        assertEquals("No websites match \"x\"." to false, emptySites(true, "x", null))
    }

    /** A connection counts under the most specific of the user's websites it goes to; any other name counts under its app, and is not kept. */
    @Test fun trafficIsCountedUnderTheWebsiteItGoesTo() {
        val sites = setup.sites.keys
        assertEquals("example.com", Sites.match("example.com", sites)); assertEquals("example.com", Sites.match("WWW.Example.com.", sites))
        assertEquals("api.example.com", Sites.match("v2.api.example.com", sites))
        for (other in listOf("notexample.com", "example.org", "com", "", "93.184.216.34", "example.com.evil.net")) assertNull(other, Sites.match(other, sites))
        assertNull(Sites.match("example.com", emptySet()))
        // In Flows a website stands beside the apps under its address, never as a package.
        assertFalse(Stats.isPackage(Stats.SITE + "example.com")); assertTrue(Stats.isPackage("org.example.app"))
        assertEquals("example.com", appLabel(Stats.SITE + "example.com", null, null))
        assertEquals("2 apps and 1 website into 3 routes · Direct traffic not shown", flowsSub(listOf("a", "b", Stats.SITE + "example.com"), 3))
        assertEquals("1 app into 1 route · Direct traffic not shown", flowsSub(listOf("a"), 1))
        assertEquals(2, setup.copy(sites = emptyMap()).stamped.version)
        assertEquals("1 app · 2 websites", laneCount(listOf("a", Stats.SITE + "x.com", Stats.SITE + "y.com"))); assertEquals("1 website", laneCount(listOf(Stats.SITE + "x.com"))); assertEquals("0 apps", laneCount(emptyList()))
    }
}
