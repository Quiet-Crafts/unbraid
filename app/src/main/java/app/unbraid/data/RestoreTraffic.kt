package app.unbraid.data

import app.unbraid.vpn.Compiler
import app.unbraid.vpn.LinkPicker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.util.Base64
import java.util.IdentityHashMap

/**
 * What a restore does to the traffic, asked of the compiler rather than worked out beside it. This phone's setup and the
 * restored one are compiled as the engine gets them: each provider route with a stand-in tunnel of its own (a restore
 * sheet has no sign-in, and nothing about a provider route's protection depends on its tunnel), each own server with its
 * config, a route waiting for its config blocked, and the real user ids of apps that share one with a blocked app. The
 * same connections then go through both configs as the phone and the engine take them: Android's left-out apps and the
 * tunnel's own address ranges first, then the app's lookup of the name, then the route rules, first match, `resolve`
 * going on to the next rule. What each connection gets (refused, a tunnel, Direct, and whose resolver looked its name
 * up) is compared. A rule this walk doesn't know makes it give up rather than guess.
 */
object RestoreTraffic {
    const val REJECT = "reject"
    private const val DIRECT_TAG = "direct"
    private const val HOME_TAG = "home-wg"

    /** Who answered a connection's name: unbraid's filter, the provider's own blocking, the phone's network, a route's own, the home network's. */
    enum class Resolver { Filter, Provider, Local, Route, Home }

    /** How one connection leaves: [REJECT], Direct, the home tunnel, the tailnet or a tunnel's tag; whose resolver answered its name (none for an address). */
    data class Outcome(val exit: String, val resolver: Resolver? = null)

    /** A program no app rule names, and a name no website rule covers: they follow the default route. */
    const val OTHER = "app.unbraid.check.other"
    const val OTHER_NAME = "unbraid-check.example"
    private const val PUBLIC = "203.0.113.80"
    /** What the fake resolver hands out: an address in the block the configs are compiled with. */
    private const val FAKE = "198.18.0.5"
    private const val LAN_ADDRESS = "192.168.1.20"
    private const val TAILNET_ADDRESS = "100.100.100.101"
    /** The most walks one comparison makes: every app, website and address is walked at least once whatever this says. */
    const val MAX_WALKS = 20_000

    /** A compiled config, read for walking. */
    class Config(text: String, setup: Setup, private val barred: Set<String>) {
        private val o = Json.parseToJsonElement(text).jsonObject
        private val dns = o.obj("dns")
        val dnsRules = dns.list("rules")
        val dnsFinal = dns.str("final") ?: "local"
        val servers = dns.list("servers").associateBy { it.str("tag").orEmpty() }
        val rules = o.obj("route").list("rules")
        val final = o.obj("route").str("final") ?: DIRECT_TAG
        val resolver = o.obj("route").str("default_domain_resolver") ?: "local"
        private val tun = o.list("inbounds").first { it.str("type") == "tun" }
        val included = strings(tun["route_address"])
        val excluded = strings(tun["route_exclude_address"])
        val left = strings(tun["exclude_package"]).toHashSet()
        /** The providers' own blocking resolvers with this setup's choices, to tell them from a route's ordinary one. */
        val blocking = Providers.all.mapNotNullTo(HashSet()) { p -> p.blockingDns { setup.opt(p.id, it) } }
        private val sets = IdentityHashMap<JsonElement, Set<String>>()
        private val patterns = IdentityHashMap<JsonElement, List<Regex>>()
        fun set(e: JsonElement?): Set<String> = e?.let { sets.getOrPut(it) { strings(it).toHashSet() } }.orEmpty()
        fun regexes(e: JsonElement): List<Regex> = patterns.getOrPut(e) { strings(e).map(::Regex) }
        /** Where an app of user id [pkgs] stands: outside unbraid, blocked, or inside, where websites' rules reach it. */
        fun side(pkgs: Set<String>) = if (pkgs.any { it in left }) 0 else if (pkgs.any { it in barred }) 1 else 2
    }

    private fun JsonObject.obj(k: String) = this[k] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.list(k: String) = (this[k] as? JsonArray).orEmpty().map { it.jsonObject }
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun strings(e: JsonElement?): List<String> = when (e) { is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.content }; is JsonPrimitive -> listOf(e.content); else -> emptyList() }

    /**
     * [s] compiled with stand-in tunnels for its provider routes (and, [given], for routes waiting for their config, as
     * once it is added); null when the compiler refuses it. [kin]: the apps sharing a user id with an app.
     */
    fun compile(s: Setup, kin: (String) -> Set<String>, given: Boolean = false): Config? = runCatching {
        val p = LinkPicker.pick(s, emptyMap(), null, emptyMap(), emptyMap(), emptyMap(), offline = true) { Providers.get(it) }
        val providers = s.activeRoutes.filter { !it.imported || (given && p.blocked[it.id] is LinkPicker.KeyLeftOut) }
        val links = p.links + providers.mapIndexed { n, r -> r.id to standIn(r, n) }
        val kinOfBlocked = s.apps.filterValues { it == BLOCK }.keys.flatMapTo(HashSet()) { kin(it) + it }
        Config(Compiler.compile(Compiler.Input(s, links, "app.unbraid", false, "", "", blocked = p.blocked.keys - providers.map { it.id }.toSet(), blockedKin = kinOfBlocked)), s, kinOfBlocked)
    }.getOrNull()

    /** A WireGuard tunnel of its own for provider route [r], made up: distinct per route, so no two share one. */
    private fun standIn(r: Route, n: Int): Link {
        val key = { salt: String -> Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest((salt + r.id).toByteArray())) }
        val host = "198.51.100.${n % 250 + 1}"
        val peer = WgPeer(r.name, key("private"), listOf("10.5.0.2/32"), key("public"), null, "$host:51820", listOf("0.0.0.0/0"))
        return WgLink(Server(r.name, host, host, peer.publicKey, 0), peer)
    }

    /**
     * A TCP connection to port 443 from an app of user id [pkgs] to [name] (looked up as the app would) or to [address].
     * Null when a rule holds something this walk doesn't know.
     */
    fun connect(c: Config, pkgs: Set<String>, name: String?, address: String = PUBLIC): Outcome? {
        var ip = address
        var host = name
        var resolver: String? = null
        val outcome = { tag: String -> Outcome(tag, if (name == null) null else kind(c, resolver ?: c.resolver)) }
        // Left outside unbraid by Android: Direct, looked up by the phone's network.
        if (pkgs.any { it in c.left }) return Outcome(DIRECT_TAG, Resolver.Local.takeIf { name != null })
        if (name != null) {
            // The app's lookup: refused, a stand-in address (the name goes on with the connection), or a real one from another resolver.
            val r = c.dnsRules.firstOrNull { match(c, it, pkgs, name, ip, dns = true) ?: return null }
            val server = r?.str("server") ?: if (r == null) c.dnsFinal else null
            when {
                r?.str("action") == "reject" -> return Outcome(REJECT)
                server == "fake" -> ip = FAKE
                server != null -> { resolver = server; host = null; ip = if (server == "dns-tailnet") TAILNET_ADDRESS else LAN_ADDRESS }
                else -> return null
            }
        }
        // Outside the tunnel's own routes, Android sends it straight to the network.
        if (!inside(c, ip)) return outcome(DIRECT_TAG)
        for (r in c.rules) {
            if (!(match(c, r, pkgs, host, ip, dns = false) ?: return null)) continue
            r.str("outbound")?.let { return outcome(it) }
            when (r.str("action")) {
                "reject" -> return Outcome(REJECT)
                "resolve" -> if (host != null) { resolver = r.str("server") ?: c.resolver; ip = PUBLIC }
                "sniff", "route-options" -> Unit
                else -> return null
            }
        }
        return outcome(c.final)
    }

    /** Whether rule [r] matches; null when it holds a condition this walk can't judge. */
    private fun match(c: Config, r: JsonObject, pkgs: Set<String>, host: String?, ip: String, dns: Boolean): Boolean? {
        var hit = true
        // A rule's name conditions are one: any of them matching is enough, as in the engine.
        if ("domain" in r || "domain_suffix" in r) hit = host != null && (host in c.set(r["domain"]) ||
            c.set(r["domain_suffix"]).any { s -> if (s.startsWith(".")) host.endsWith(s) else host == s || host.endsWith(".$s") })
        for ((k, v) in r) when (k) {
            "action", "outbound", "server", "no_drop", "rewrite_ttl", "invert", "mode", "domain", "domain_suffix" -> Unit
            "type" -> if ((v as? JsonPrimitive)?.content != "logical") return null
            "rules" -> {
                val subs = (v as? JsonArray ?: return null).map { match(c, it as? JsonObject ?: return null, pkgs, host, ip, dns) ?: return null }
                hit = hit && if (r.str("mode") == "or") subs.any { it } else subs.all { it }
            }
            "inbound" -> hit = hit && "tun" in strings(v)
            // The app's own tests and lookups to port 53: never this connection.
            "auth_user", "protocol" -> hit = false
            "network" -> hit = hit && "tcp" in strings(v)
            "port" -> hit = hit && "443" in strings(v)
            "query_type" -> hit = hit && dns && "A" in strings(v)
            "ip_cidr" -> { if (dns) return null; hit = hit && strings(v).any { within(ip, it) } }
            "package_name" -> hit = hit && pkgs.any { it in c.set(v) }
            "package_name_regex" -> hit = hit && pkgs.any { p -> c.regexes(v).any { it.containsMatchIn(p) } }
            else -> return null
        }
        return hit != ((r["invert"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    /** Whether Android sends [ip] into the tunnel: the most specific of its routes and exclusions wins, an exclusion on a tie. */
    private fun inside(c: Config, ip: String): Boolean {
        if (c.included.isEmpty()) return true
        val len = { l: List<String> -> l.filter { within(ip, it) }.maxOfOrNull { Compiler.ipv4(it)!!.second } }
        val inc = len(c.included) ?: return false
        return len(c.excluded)?.let { it < inc } ?: true
    }

    private fun within(ip: String, cidr: String): Boolean {
        val (a, l) = Compiler.ipv4(cidr) ?: return false
        val (b, _) = Compiler.ipv4(ip) ?: return false
        return l == 0 || (a xor b) shr (32 - l) == 0L
    }

    private fun kind(c: Config, tag: String): Resolver = when (tag) {
        "dns-filter" -> Resolver.Filter
        "local" -> Resolver.Local
        "dns-tailnet" -> Resolver.Home
        // The filter is written with its own port (and TLS or a path); a route's resolver with neither.
        else -> c.servers[tag]?.let { d -> if ("server_port" in d) Resolver.Filter else if (d.str("server") in c.blocking) Resolver.Provider else null } ?: Resolver.Route
    }

    // ---- what gets weaker ----

    /** How an app, website or home address protects less after the restore. */
    enum class Loss { Unblocked, ToDirect, Filter, ProviderBlocking, HomeDirect }

    fun loss(was: Outcome, now: Outcome): Loss? = when {
        was.exit == REJECT -> Loss.Unblocked.takeIf { now.exit != REJECT }
        now.exit == REJECT -> null
        was.exit != DIRECT_TAG && now.exit == DIRECT_TAG -> Loss.ToDirect
        was.resolver == Resolver.Filter && now.resolver != Resolver.Filter -> Loss.Filter
        was.resolver == Resolver.Provider && now.resolver != Resolver.Provider && now.resolver != Resolver.Filter -> Loss.ProviderBlocking
        else -> null
    }

    /** The worst of several (an app seen from several copies, a website from several apps) is the one kept. */
    private fun rank(l: Loss?) = when (l) { Loss.Unblocked -> 5; Loss.ToDirect, Loss.HomeDirect -> 4; Loss.Filter -> 3; Loss.ProviderBlocking -> 2; null -> 0 }

    /** What a restore changes for the worse: the default route, per app, per website name (with a name under it), and home addresses. */
    data class Losses(val defaultRoute: Loss? = null, val apps: Map<String, Loss?> = emptyMap(), val sites: Map<String, Loss?> = emptyMap(), val home: Set<String> = emptySet())

    /**
     * What restoring [next] over [current] makes weaker, connection by connection; null when either can't be compiled.
     * [kin]: the apps sharing an Android user id with an app (the engine and Android decide by user id).
     */
    fun losses(current: Setup, next: Setup, kin: (String) -> Set<String> = { emptySet() }): Losses? {
        val keys = (current.apps.keys + next.apps.keys + current.prefs.filter.apps.keys + next.prefs.filter.apps.keys).sorted()
        val group = keys.associateWith { kin(it) + it }
        // Apps the compiler can't tell apart (the same choice and filter switch on both sides, sharing no user id) go the
        // same way: one of each kind is compiled and walked, and stands for the rest.
        val kinds = HashMap<String, String>()
        val stands = keys.associateWith { k ->
            if (group.getValue(k).size > 1) k else kinds.getOrPut("${current.apps[k]}|${next.apps[k]}|${current.prefs.filter.apps[k]}|${next.prefs.filter.apps[k]}") { k }
        }
        val reps = stands.values.toSet()
        val keeping = { s: Setup -> s.copy(apps = s.apps.filterKeys { it in reps }, prefs = s.prefs.copy(filter = s.prefs.filter.copy(apps = s.prefs.filter.apps.filterKeys { it in reps }))) }
        val was = compile(keeping(current), kin) ?: return null
        val now = compile(keeping(next), kin) ?: return null
        // A block that becomes a route waiting for its config is refused on both sides, but opens once the config is
        // added: both are compiled again as they will be then.
        val waiting = listOf(current, next).any { s -> s.routes.any { it.keyless } || s.servers.any { it.keyless } }
        val wasGiven = if (waiting) compile(keeping(current), kin, given = true) ?: return null else null
        val nowGiven = if (waiting) compile(keeping(next), kin, given = true) ?: return null else null
        class Walk(val was: Outcome, val now: Outcome, val loss: Loss?)
        fun pair(pkgs: Set<String>, name: String?, address: String): Walk? {
            val a = connect(was, pkgs, name, address) ?: return null
            val b = connect(now, pkgs, name, address) ?: return null
            if (wasGiven == null || nowGiven == null || a.exit != REJECT || b.exit != REJECT) return Walk(a, b, loss(a, b))
            val c = connect(wasGiven, pkgs, name, address) ?: return null
            val e = connect(nowGiven, pkgs, name, address) ?: return null
            return Walk(a, b, Loss.Unblocked.takeIf { c.exit == REJECT && e.exit != REJECT })
        }
        val other = setOf(OTHER)
        val d = pair(other, OTHER_NAME, PUBLIC) ?: return null
        val defaultLoss = d.loss
        // Programs that go differently before or after, or stand elsewhere (outside unbraid, blocked, inside), each with
        // what it loses on its own: a website seen from it says only what is the website's.
        val programs = linkedMapOf(listOf(d.was, d.now, was.side(other), now.side(other)) to other)
        val own = hashMapOf(other to defaultLoss)
        val apps = HashMap<String, Loss?>()
        for (k in reps.sorted()) {
            val o = pair(group.getValue(k), OTHER_NAME, PUBLIC) ?: return null
            apps[k] = o.loss
            if (programs.putIfAbsent(group.getValue(k).let { g -> listOf(o.was, o.now, was.side(g), now.side(g)) }, group.getValue(k)) == null) own[group.getValue(k)] = o.loss
        }
        for (k in keys) apps[k] = apps[stands.getValue(k)]
        // Every website name either side has, and one under it, from every kind of app: a website's route comes before its app's.
        val names = (current.sites.keys + next.sites.keys).sorted()
        val from = programs.values.toList().let { if (names.size * 2 * it.size <= MAX_WALKS) it else listOf(other) }
        val sites = names.associateWith { n ->
            var worst: Loss? = null
            for (probe in listOf(n, "zz-check.$n")) for (p in from) {
                val o = pair(p, probe, PUBLIC) ?: return null
                val l = o.loss
                // The app's own change (unblocked, say) is said for the app, not again for each website it opens.
                if (l != own[p] && rank(l) > rank(worst)) worst = l
            }
            worst
        }
        // Both ends of every home range either side reaches through a tunnel (narrowed at either end shows there), and one LAN address.
        val ranges = listOf(current, next).flatMap { s -> s.home.wg?.let(Compiler::homeCidrs).orEmpty() + if (s.home.tailscale) s.home.subnets.mapNotNull(Compiler::canonical).filter(Compiler::isPrivate) else emptyList() }
        val home = (ranges.flatMap(::edges) + LAN_ADDRESS).toSortedSet().filterTo(sortedSetOf()) { a ->
            val o = pair(other, null, a) ?: return null
            (o.was.exit == HOME_TAG || o.was.exit == Compiler.TS_TAG) && o.now.exit == DIRECT_TAG
        }
        return Losses(defaultLoss, apps, sites, home)
    }

    /** The first and last host addresses of an IPv4 range; a /32 or /31 its own addresses. */
    fun edges(cidr: String): List<String> {
        val (a, l) = Compiler.ipv4(Compiler.canonical(cidr) ?: return emptyList()) ?: return emptyList()
        val last = a or ((1L shl (32 - l)) - 1)
        val ends = if (l >= 31) listOf(a, last) else listOf(a + 1, last - 1)
        return ends.distinct().map { v -> (3 downTo 0).joinToString(".") { (v shr (it * 8) and 255).toString() } }
    }

    // ---- in the sheet's words ----

    const val UNKNOWN = "What this restore changes for each app and website couldn't be worked out."

    /**
     * What restoring [next] over [current] makes weaker, in a few sentences: settings that are not per connection compared
     * directly (the filter, the local network, providers' blocking choices, own servers and the home tunnel by every
     * address they connect to), then what each connection gets ([losses]). Only the setup in use is judged: a saved
     * profile runs nothing until it is chosen. Slow for a large setup: never on the main thread.
     */
    fun notes(current: Setup, next: Setup, kin: (String) -> Set<String> = { emptySet() }): List<String> {
        val out = mutableListOf<String>()
        val was = current.prefs.filter.server()
        val now = next.prefs.filter.server()
        val filterOff = was != null && now == null
        if (filterOff) out += "The DNS filter is turned off." else if (was != null && now != null && was != now) out += "The DNS filter changes to another server."
        if (!current.prefs.lan && next.prefs.lan) out += "Apps can reach the local network again."
        if (current.prefs.blockLan && !next.prefs.blockLan && BLOCK in next.apps.values) out += "Blocked apps can reach the local network."
        exitChanges(current, next).forEach { if (it !in out) out += it }
        // A new install's placeholder isn't the user's: there is no traffic to compare.
        if (current.untouched) return out
        out += providerWeakens(current, next)
        val l = losses(current, next, kin) ?: return out + UNKNOWN
        return out + trafficNotes(l, current, filterOff)
    }

    private fun count(n: Int, what: String) = "$n ${if (n == 1) what else what + "s"}"
    private fun list(p: List<String>) = if (p.size < 2) p.joinToString("") else p.dropLast(1).joinToString(", ") + " and " + p.last()
    /** The host of "host:port" or "host:port/network". */
    private fun hostOf(r: String) = r.substringBefore('/').substringBeforeLast(':').trim('[', ']')

    /** A host or name as the sheet shows it: at most 60 characters, its end kept (where names differ most). */
    private fun shown(s: String) = if (s.length <= 60) s else "…" + s.takeLast(59)

    /** One exit the user set up: its key (a server's or route's id), name, where it connects and the DNS servers it sets. */
    private data class Exit(val key: String, val name: String, val host: String?, val remotes: List<String>, val peer: String?, val dns: List<String>)

    /** Every own server, every route through one (it runs its own copy) and the home tunnel; none waiting for its config. */
    private fun exits(s: Setup): List<Exit> {
        fun exit(key: String, name: String, wg: WgPeer?, ovpn: OvpnProfile?, ss: SsProfile?): Exit {
            // OpenVPN: every remote the engine may pick, with the port and protocol it gets, as the import builds them.
            val remotes = wg?.let { listOf(it.endpoint) } ?: ss?.let { listOf("${it.server}:${it.port}") } ?: ovpn?.let { o ->
                runCatching { Manual.ovpnEndpoint("check", o).let { e -> (e["servers"] as? JsonArray)?.map { it.jsonObject } ?: listOf(e) }
                    .map { "${it.str("server")}:${it["server_port"]}/${it.str("network")}" } }.getOrDefault(emptyList())
            }.orEmpty()
            val host = wg?.endpoint?.substringBeforeLast(':')?.trim('[', ']') ?: ss?.server ?: remotes.firstOrNull()?.let(::hostOf)
            return Exit(key, name, host, remotes.map { it.lowercase() }, wg?.publicKey, wg?.dns.orEmpty())
        }
        return s.servers.filter { !it.keyless }.map { exit("s:" + it.id, it.name, it.wg, it.ovpn, it.ss) } +
            s.routes.filter { it.imported && it.tsExit == null && !it.keyless }.map { exit("r:" + it.id, it.name, it.wg, it.ovpn, it.ss) } +
            listOfNotNull(s.home.wg?.let { exit("home", "", it, null, null) })
    }

    /**
     * An own server, a route through one or the home tunnel that keeps its id or name but connects to another host, port,
     * server or key, or sets other DNS servers: a familiar name is no sign the traffic goes where it went. Hosts are
     * compared whole and shortened only to be shown; never a key.
     */
    private fun exitChanges(current: Setup, next: Setup): List<String> {
        val was = exits(current)
        return exits(next).flatMap { n ->
            val c = was.firstOrNull { it.key == n.key } ?: was.firstOrNull { n.key != "home" && it.key != "home" && it.name == n.name } ?: return@flatMap emptyList()
            val who = if (n.key == "home") "Your home WireGuard" else "\"${shown(n.name)}\""
            listOfNotNull(
                when {
                    // Compared whole, shown shortened: two long names that differ only at their start are still two servers.
                    n.host != null && c.host != null && !n.host.equals(c.host, ignoreCase = true) && shown(n.host) != shown(c.host) -> "$who connects to ${shown(n.host)} instead of ${shown(c.host)}."
                    !n.host.equals(c.host, ignoreCase = true) || n.remotes != c.remotes || n.peer != c.peer ->
                        "$who connects to another server at ${shown((n.remotes.firstOrNull { it !in c.remotes }?.let(::hostOf) ?: n.host).orEmpty())}."
                    else -> null
                },
                if (n.dns == c.dns) null else if (n.dns.isEmpty()) "$who no longer sets its own DNS servers." else "$who uses other DNS servers: ${n.dns.joinToString(", ") { shown(it) }}.",
            )
        }.distinct()
    }

    /** Provider settings that block more, or choose more carefully, turned off for a provider [next] has routes on. */
    private fun providerWeakens(current: Setup, next: Setup): List<String> {
        val blocks = linkedMapOf("adult" to "adult sites", "gambling" to "gambling sites", "social" to "social media", "hardcore" to "Google and Facebook")
        val out = mutableListOf<String>()
        for (id in next.routes.filter { !it.imported }.map { it.loc.provider }.distinct().sorted()) {
            val p = Providers.get(id) ?: continue
            val off = { k: String -> k in p.defaults && current.opt(id, k) == "on" && next.opt(id, k) != "on" }
            // Blocking choices count only where a route of that provider blocks now.
            if (current.routes.any { !it.imported && it.loc.provider == id && current.protects(it) }) {
                blocks.filterKeys(off).values.toList().takeIf { it.isNotEmpty() }?.let { out += "${p.name} stops blocking ${list(it)}." }
                p.defaults["list"]?.let { d -> if (current.opt(id, "list") != d && next.opt(id, "list") == d) out += "${p.name}'s block list goes back to the standard one." }
            }
            if (off("owned")) out += "${p.name} no longer keeps to the servers it owns."
        }
        return out
    }

    /** The losses in the sheet's few sentences: every kind found has one. */
    private fun trafficNotes(found: Losses, current: Setup, filterOff: Boolean): List<String> {
        // Leaving a home tunnel for Direct is leaving a tunnel for Direct, wherever it is found.
        val plain = { x: Loss? -> if (x == Loss.HomeDirect) Loss.ToDirect else x }
        val def = plain(found.defaultRoute)
        val apps = found.apps.values.map(plain)
        val sites = found.sites.values.map(plain)
        val parts = { k: Loss -> listOfNotNull("The default route".takeIf { def == k }, apps.count { it == k }.takeIf { it > 0 }?.let { count(it, "app") }, sites.count { it == k }.takeIf { it > 0 }?.let { count(it, "website") }) }
        val one = { p: List<String> -> p.size == 1 && (p[0].startsWith("The") || p[0].startsWith("1 ")) }
        val out = mutableListOf<String>()
        if (def == Loss.ToDirect) out += "The default route goes from ${current.route(current.defaultRoute)?.name ?: "a VPN"} to Direct."
        parts(Loss.ToDirect).filter { !it.startsWith("The") }.takeIf { it.isNotEmpty() }?.let { out += "${list(it)} ${if (one(it)) "goes" else "go"} from a VPN route to Direct." }
        // Said once for all when the filter is turned off.
        parts(Loss.Filter).takeIf { it.isNotEmpty() && !filterOff }?.let { out += "${list(it)} ${if (one(it)) "loses" else "lose"} the DNS filter." }
        parts(Loss.ProviderBlocking).takeIf { it.isNotEmpty() }?.let { out += "${list(it)} ${if (one(it)) "loses the provider's ad and tracker blocking it has" else "lose the provider's ad and tracker blocking they have"} now." }
        // Refused now because its route waits for its server's config, rather than blocked by choice: said apart.
        val waiting = { id: String? -> current.route(id)?.let { r -> r.keyless || current.servers.any { it.id == r.own && it.keyless } } == true }
        if (def == Loss.Unblocked) out += if (waiting(current.defaultRoute)) "The default route can't connect now, because its route waits for a server's config. After the restore its traffic goes out."
            else "The default route can't connect now. After the restore its traffic goes out."
        val opened = found.apps.filterValues { it == Loss.Unblocked }.keys
        val openedSites = found.sites.filterValues { it == Loss.Unblocked }.keys
        val appsWaiting = opened.count { waiting(current.routeOf(it)) }
        val sitesWaiting = openedSites.count { n -> waiting(Sites.match(n, current.sites.keys)?.let(current.sites::get)) }
        (opened.size - appsWaiting).takeIf { it > 0 }?.let { out += if (it == 1) "1 app that is blocked now won't be." else "$it apps that are blocked now won't be." }
        (openedSites.size - sitesWaiting).takeIf { it > 0 }?.let { out += if (it == 1) "1 website that is blocked now won't be." else "$it websites that are blocked now won't be." }
        listOfNotNull(appsWaiting.takeIf { it > 0 }?.let { count(it, "app") }, sitesWaiting.takeIf { it > 0 }?.let { count(it, "website") }).takeIf { it.isNotEmpty() }?.let {
            out += "${list(it)} that can't connect now, because ${if (one(it)) "its route waits" else "their routes wait"} for a server's config, ${if (one(it)) "goes" else "go"} out without it."
        }
        if (found.home.isNotEmpty()) out += "Part of the home network goes Direct instead of through the home tunnel."
        return out
    }
}
