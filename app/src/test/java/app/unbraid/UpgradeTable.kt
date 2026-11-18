package app.unbraid

import app.unbraid.data.BLOCK
import app.unbraid.data.DIRECT
import app.unbraid.data.Home
import app.unbraid.data.Location
import app.unbraid.data.Prefs
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.data.WgPeer
import app.unbraid.data.Country
import app.unbraid.data.Field
import app.unbraid.data.Link
import app.unbraid.data.Profile
import app.unbraid.data.Provider
import app.unbraid.data.Server
import app.unbraid.data.WgLink
import app.unbraid.vpn.Compiler
import app.unbraid.vpn.LinkPicker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    private const val PUB = "5p4RkybdRU5uaDi90eu4KZPTFif0lKCg4Qp6t1c4F30="
    private const val KEY = "aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8tMTI="
    private fun peer(n: String, ip: String, keepalive: Int?) = WgPeer(n, KEY, listOf("10.5.0.2/32"), PUB, endpoint = "$ip:51820", allowedIps = listOf("0.0.0.0/0"), keepalive = keepalive)

    /** A one-key provider whose best servers are S1, then S2. */
    private object TwoServers : Provider {
        override val id = "fake"; override val name = "Fake"; override val fields = emptyList<Field>(); override val help = ""; override val oneKey = true
        private val best = listOf(Server("S1", "s1.example", "198.51.100.1", PUB, 0), Server("S2", "s2.example", "198.51.100.2", PUB, 0))
        override fun signIn(input: Map<String, String>) = "acc"
        override fun countries(account: String) = emptyList<Country>()
        override fun connect(loc: Location, account: String): Link = (loc.pinned ?: best[0]).let { WgLink(it, peer(it.name, it.ip, null)) }
        override fun servers(loc: Location, account: String, limit: Int) = best.take(limit)
    }

    /**
     * WireGuard clashes, in saved order A, B, C, with local network access on and off. "wg-clash": three imported configs for
     * one client key and server, A and C with keepalive 25, B with 17. "provider-clash": A and C unpinned on [TwoServers],
     * B importing S1's identity with keepalive 17. A's only use is a local name the local network answers. Each gives where
     * the names go, which server each route got (or why none), and the WireGuard tunnels the service compiles.
     */
    private val clashNames = listOf("printer.lan", "mail.example", "bank.example")
    fun clashLines(): List<String> = listOf(true, false).flatMap { lan ->
        listOf(
            "wg-clash" to listOf(Route("a", wg = peer("a", "198.51.100.7", 25)), Route("b", wg = peer("b", "198.51.100.7", 17)), Route("c", wg = peer("c", "198.51.100.7", 25))),
            "provider-clash" to listOf(Route("a", Location("fake", countryCode = "NL")), Route("b", wg = peer("b", "198.51.100.1", 17)), Route("c", Location("fake", countryCode = "NL"))),
        ).flatMap { (case, routes) ->
            val s = Setup(routes = routes, defaultRoute = DIRECT, sites = mapOf("printer.lan" to "a", "mail.example" to "b", "bank.example" to "c"), prefs = Prefs(lan = lan), version = 3)
            val p = LinkPicker.pick(s, emptyMap(), null, emptyMap(), mapOf("fake" to "acc"), emptyMap()) { if (it == "fake") TwoServers else null }
            val c = Json.parseToJsonElement(Compiler.compile(Compiler.Input(s, p.links, "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", null, blocked = p.blocked.keys))).jsonObject
            val tunnels = c["endpoints"]?.jsonArray.orEmpty().map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "wireguard" }.map { e ->
                val peer = e["peers"]!!.jsonArray[0].jsonObject
                "${e["tag"]!!.jsonPrimitive.content}@${peer["address"]!!.jsonPrimitive.content}:${peer["port"]!!.jsonPrimitive.content}/ka${peer["persistent_keepalive_interval"]!!.jsonPrimitive.content}"
            }
            val links = s.routes.joinToString(" ") { r -> "${r.id}=" + (p.links[r.id]?.server?.name ?: p.blocked[r.id]?.let { "refused" } ?: "none") }
            RoutingProbe.outcomes(s, null, clashNames, apps, p.blocked.keys, p.links).map { "$case-lan-$lan $it" } +
                "$case-lan-$lan active=${s.activeRoutes.joinToString(",") { it.id }} $links tunnels=${tunnels.joinToString(",")}"
        }
    }

    /**
     * Route ownership while another profile is edited: an unprofiled live setup uses route A only for a local name the local
     * network answers, and the inactive Work profile uses A too. The check the profile editor makes before changing Work's
     * location: A shared with the live setup is copied first, so the live website keeps its exit.
     */
    fun ownershipLines(): List<String> = listOf(true, false).map { lan ->
        val s = Setup(routes = listOf(Route("a", Location("nord", 153, "NL", "Netherlands"))), defaultRoute = DIRECT, sites = mapOf("printer.lan" to "a"),
            profiles = listOf(Profile("Work", DIRECT, sites = mapOf("printer.lan" to "a"))), prefs = Prefs(lan = lan), version = 3)
        val shared = s.activeRoutes.any { it.id == "a" } || "a" in s.added || s.saved.any { p -> p.name != "Work" && "a" in p.own }
        "ownership-lan-$lan active=${s.activeRoutes.joinToString(",") { it.id }} live-shares-a=$shared"
    }

    /** Every case's outcomes, one line each, prefixed by the case name, then the routes each case keeps a tunnel for. */
    fun lines() = cases.flatMap { (name, s, rest) -> RoutingProbe.outcomes(s, rest.first, names, apps, rest.second).map { "$name $it" } } + clashLines() + ownershipLines() +
        cases.map { (name, s, _) -> "$name active=${s.activeRoutes.joinToString(",") { it.id }}" }
}
