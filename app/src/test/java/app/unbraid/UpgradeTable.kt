package app.unbraid

import app.unbraid.data.BLOCK
import app.unbraid.data.DIRECT
import app.unbraid.data.Home
import app.unbraid.data.Location
import app.unbraid.data.Prefs
import app.unbraid.data.Route
import app.unbraid.data.Setup

/**
 * Setups as version 3.0.2 saved them, with websites on public names, on Tailscale names with Tailscale on and off, on
 * local names with local network access on and off, and on names the newer address reader refuses. Each case is a
 * setup, the tailnet domain Tailscale reported, and the routes without a tunnel. Built only from what 3.0.2 already had,
 * so the same table compiles there.
 */
object UpgradeTable {
    private val nl = Route("nl", Location("nord", 153, "NL", "Netherlands"))
    private val it = Route("it", Location("nord", 106, "IT", "Italy"))
    private val us = Route("us", Location("nord", 228, "US", "United States"))
    private val exit = Route("ts", Location("tailscale", 0, "TS", "Tailscale exit"), tsExit = "100.64.0.9")

    val names = listOf(
        "example.com", "www.example.com", "api.example.com", "v2.api.example.com", "bank.example", "ads.example", "other.org",
        "host.tailnet.ts.net", "nas.tail1234.ts.net", "a.b.tail1234.ts.net", "box.corp.example", "corp.example",
        "printer.lan", "nas.local", "x.fritz.box", "router.home.arpa", "corp.internal", "1.0.0.10.in-addr.arpa", "foo.localhost", "x.0xff",
        "wiki.corp.office.lan", "printer.office.lan", "wiki.tail.example.org", "www.example.org", "storage.googleapis.com", "raw.githubusercontent.com", "www.gob.mx",
    )
    val apps = listOf("org.browser", "com.chat")

    private val public = mapOf("example.com" to "it", "api.example.com" to "us", "bank.example" to DIRECT, "ads.example" to BLOCK)
    private val odd = mapOf(
        "host.tailnet.ts.net" to "it", "nas.tail1234.ts.net" to "us", "box.corp.example" to "it", "printer.lan" to "it", "nas.local" to DIRECT,
        "x.fritz.box" to BLOCK, "router.home.arpa" to "us", "corp.internal" to "it", "1.0.0.10.in-addr.arpa" to "it", "foo.localhost" to "it", "x.0xff" to "it",
    )
    private fun setup(default: String, sites: Map<String, String>, home: Home = Home(), lan: Boolean = true, routes: List<Route> = listOf(nl, it, us)) =
        Setup(routes = routes, defaultRoute = default, apps = mapOf("com.chat" to "us"), sites = sites, home = home, prefs = Prefs(lan = lan), version = 3)

    private val official = Home(tailscale = true)
    private val headscale = Home(tailscale = true, tsControlUrl = "https://hs.example.org", tsSuffixAllowed = "corp.example")

    /** Case name to (setup, reported tailnet domain, routes without a tunnel). */
    val cases: List<Triple<String, Setup, Pair<String?, Set<String>>>> = listOf(
        Triple("public", setup("nl", public), null to emptySet()),
        Triple("public-direct-default", setup(DIRECT, public), null to emptySet()),
        Triple("public-it-down", setup("nl", public), null to setOf("it")),
        Triple("odd-ts-off-lan-off", setup(DIRECT, public + odd, lan = false), null to emptySet()),
        Triple("odd-ts-off-lan-on", setup(DIRECT, public + odd), null to emptySet()),
        Triple("odd-ts-off-nl-default", setup("nl", public + odd, lan = false), null to emptySet()),
        Triple("odd-ts-home-on", setup(DIRECT, public + odd, official), "tail1234.ts.net" to emptySet()),
        Triple("odd-ts-home-on-lan-off", setup("nl", public + odd, official, lan = false), "tail1234.ts.net" to emptySet()),
        Triple("odd-ts-home-on-unreported", setup(DIRECT, public + odd, official), null to emptySet()),
        Triple("odd-ts-exit-only", setup(DIRECT, public + odd + ("other.org" to "ts"), routes = listOf(nl, it, us, exit)), "tail1234.ts.net" to emptySet()),
        Triple("odd-headscale", setup(DIRECT, public + odd, headscale), "corp.example" to emptySet()),
        Triple("odd-headscale-unreported", setup(DIRECT, public + odd, headscale), null to emptySet()),
        Triple("odd-headscale-other-domain", setup(DIRECT, public + odd, headscale), "other.example" to emptySet()),
        Triple("tailnet-parent", setup(DIRECT, mapOf("ts.net" to "it", "corp.example" to "us", "home.arpa" to "it"), headscale), "corp.example" to emptySet()),
        // A Headscale domain inside a saved local name, and one inside a saved public parent; platforms and a government
        // ending 3.0.2 took as websites.
        Triple("private-zone-in-parent", setup(DIRECT, platforms + mapOf("office.lan" to "it", "example.org" to "us"), headscale.copy(tsSuffixAllowed = "corp.office.lan")), "corp.office.lan" to emptySet()),
        Triple("private-zone-in-parent-lan-off", setup("nl", platforms + mapOf("office.lan" to "it", "example.org" to "us"), headscale.copy(tsSuffixAllowed = "tail.example.org"), lan = false), "tail.example.org" to emptySet()),
    )
    private val platforms get() = mapOf("googleapis.com" to "it", "githubusercontent.com" to DIRECT, "gob.mx" to "us")

    /** Every case's outcomes, one line each, prefixed by the case name. */
    fun lines() = cases.flatMap { (name, s, rest) -> RoutingProbe.outcomes(s, rest.first, names, apps, rest.second).map { "$name $it" } }
}
