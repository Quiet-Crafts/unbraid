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
 * What a restore does to the traffic, asked of the compiler rather than worked out beside it.
 *
 * 1. Traffic classes. Both setups are compiled as the engine gets them, with Android's lockdown off and on: each provider
 *    route with a stand-in tunnel of its own (a restore sheet has no sign-in, and nothing about a provider route's
 *    protection depends on its tunnel), each own server with its config, a route waiting for its config refused, and the
 *    real user ids of apps that share one with a blocked app. A connection is an app (a user id, or none: Android names no
 *    app), the name it looked up (or none), the address it really goes to, and how: TCP (UDP goes the same way, as no rule
 *    tells them apart but for port 53, which the engine answers as a lookup) or a ping. A lookup alone has a question type.
 *    Every condition a rule can test is a package list or pattern, a name list (the name, or it and the names under it), an
 *    address range, the network and port, or the question type: two connections that meet the same conditions in every
 *    config go the same way everywhere. So the classes are read from the configs: every user id a package list names, one
 *    that none names, and none; every name a rule names, one under it that no rule names, and one no rule covers; one
 *    address between every two range boundaries; and each question type a rule names, and one it doesn't. A made-up probe
 *    is checked against every rule, so no rule of the user's can capture it. Classes that meet the same conditions are
 *    walked once.
 * 2. Outcomes. Each class goes through each config as the phone and the engine take it: Android's left-out apps and the
 *    tunnel's own routes first, then the app's lookup, then the route rules, first match, `resolve` going on. An outcome is
 *    refused, an empty answer, a tunnel or Direct, and whose resolver answered the name; with what decided it ([By]).
 * 3. The order. Refused is above any tunnel, a tunnel above Direct; for a name, the filter above the provider's blocking
 *    above any other resolver. An outcome is weaker when its exit is lower (a refused one let out, a tunnel gone Direct)
 *    or, at an exit no lower, its name loses the filter or the provider's blocking ([loss]). Neither an empty answer nor
 *    a hold ([HELD]: traffic refused only because it can't be told from a VPN app's or a blocked app's) is ranked: what
 *    lifts a hold is said for those apps.
 * 4. Setups compared: the one in use against the restored one, and on each kind of network the profile the restored rules
 *    switch to by themselves against what the phone runs there now ([check]).
 * 5. Sentences. A weaker class is said for what decides it ([Who], [who]): an app's or the default route's own traffic,
 *    and whatever goes as that traffic goes or as the app decides before anything else (left out by Android, blocked); a
 *    lifted refusal for what refused it; a website, when a rule for its name decides it on either side; the home or the
 *    local network when an address range does, or a name on that network. Each thing is said once, for the worst it
 *    loses, and each kind of thing has its own sentence, so a website's loss is never folded into the default route's
 *    ([trafficNotes]). A setup too large to walk every app against every website is said to be so ([CUT]).
 */
object RestoreTraffic {
    const val REJECT = "reject"
    /** An answer the engine makes up, empty (a website's HTTPS and SVCB records): it neither blocks nor lets anything out. */
    const val EMPTY = "empty"
    /** Refused because Android names no app for it, or a ping: held off Direct, as a VPN app's traffic would be. */
    const val HELD = "held"
    private const val DIRECT_TAG = "direct"
    private const val PUBLIC = "203.0.113.80"

    /** Who answered a connection's name: unbraid's filter, the provider's own blocking, the phone's network, a route's own, the home network's. */
    enum class Resolver { Filter, Provider, Local, Route, Home }

    /** How one connection leaves: [REJECT], [EMPTY], Direct, the home tunnel, the tailnet or a tunnel's tag; whose resolver answered its name (none for an address). */
    data class Outcome(val exit: String, val resolver: Resolver? = null)

    /**
     * What decided an outcome, least specific first: the default; an app's own rule; an app before any rule for a name
     * (Android leaving it out, Block); an address range (or a ping); a name.
     */
    enum class By { None, App, AppFirst, Address, Name }

    /** An outcome, what decided its exit ([by]), and what decided who answered its name ([rby]). */
    class Step(val out: Outcome, val by: By, val rby: By)

    /** The most app and name classes walked against each other in one comparison; beyond, the rest of the apps meet only their own traffic. */
    const val MAX_PAIRS = 30_000

    /** Set while a walk tests the real address: when it doesn't, every address goes the same way. */
    class Touch { var hit = false }

    /** A compiled config, read for walking. */
    class Config(text: String, setup: Setup) {
        private val o = Json.parseToJsonElement(text).jsonObject
        private val dns = o.obj("dns")
        val dnsRules = dns.list("rules")
        val dnsFinal = dns.str("final") ?: "local"
        val servers = dns.list("servers").associateBy { it.str("tag").orEmpty() }
        /** What the fake resolver hands out: an address in the range this config's lookups get, wherever the pool stands. */
        val fake = servers["fake"]?.str("inet4_range")?.let(Compiler::ipv4)?.first?.plus(5) ?: 0xC6120005L
        val rules = o.obj("route").list("rules")
        val final = o.obj("route").str("final") ?: DIRECT_TAG
        val resolver = o.obj("route").str("default_domain_resolver") ?: "local"
        private val tun = o.list("inbounds").first { it.str("type") == "tun" }
        val included = strings(tun["route_address"])
        val excluded = strings(tun["route_exclude_address"])
        val left = strings(tun["exclude_package"]).toHashSet()
        val includedRanges = included.mapNotNull(Compiler::ipv4)
        val excludedRanges = excluded.mapNotNull(Compiler::ipv4)
        /** The providers' own blocking resolvers with this setup's choices, to tell them from a route's ordinary one. */
        val blocking = Providers.all.mapNotNullTo(HashSet()) { p -> p.blockingDns { setup.opt(p.id, it) } }
        val dnsBy = decided(dnsRules)
        val routeBy = decided(rules)
        /** Refusals of what no app is named for, or of pings: there to keep a VPN app's traffic off Direct, they count as a tunnel ([level]). */
        val held = rules.map { r -> r.str("action") == "reject" && ("network" in keys(r) || unnamed(r)) }
        private fun unnamed(r: JsonObject): Boolean = ((r["invert"] as? JsonPrimitive)?.booleanOrNull == true && "package_name_regex" in r) ||
            (r["rules"] as? JsonArray).orEmpty().any { it is JsonObject && unnamed(it) }
        /** Every network and port a rule tests is one the walk knows: TCP, a ping, and UDP to port 53 taken over as a lookup. */
        val known = (dnsRules + rules).all(::transports)
        private val sets = IdentityHashMap<JsonElement, Set<String>>()
        private val patterns = IdentityHashMap<JsonElement, List<Regex>>()
        private val ranges = IdentityHashMap<JsonElement, List<Pair<Long, Int>>>()
        fun set(e: JsonElement?): Set<String> = e?.let { sets.getOrPut(it) { strings(it).toHashSet() } }.orEmpty()
        fun regexes(e: JsonElement): List<Regex> = patterns.getOrPut(e) { strings(e).map(::Regex) }
        fun cidrs(e: JsonElement): List<Pair<Long, Int>> = ranges.getOrPut(e) { strings(e).mapNotNull(Compiler::ipv4) }

        /**
         * The rules of [list] an app of user id [pkgs] can meet: an app list or pattern that names none of its packages
         * skips the rule (a rule of rules or an inverted one is always kept). Only for speed: what matches is unchanged.
         */
        fun reach(dns: Boolean, pkgs: Set<String>): IntArray = (if (dns) reachedDns else reachedRoute).getOrPut(pkgs) {
            val list = if (dns) dnsRules else rules
            list.indices.filter { i -> val r = list[i]
                r["type"] != null || r["invert"] != null ||
                    (r["package_name"]?.let { e -> pkgs.any { it in set(e) } } ?: true) && (r["package_name_regex"]?.let { e -> pkgs.any { p -> regexes(e).any { it.containsMatchIn(p) } } } ?: true)
            }.toIntArray()
        }
        private val reachedDns = IdentityHashMap<Set<String>, IntArray>()
        private val reachedRoute = IdentityHashMap<Set<String>, IntArray>()

        /** Whether [host] is in name list [domain] or under a name of suffix list [suffix], as the engine matches: by the host's own label endings. */
        fun named(host: String, domain: JsonElement?, suffix: JsonElement?): Boolean {
            if (domain != null && host in set(domain)) return true
            val s = set(suffix ?: return false)
            var i = -1
            do { val end = host.substring(i + 1); if (end in s || (i >= 0 && ".$end" in s)) return true; i = host.indexOf('.', i + 1) } while (i >= 0)
            return false
        }

        /** Every list under [key] in every rule, those inside a rule of rules too. */
        fun atoms(key: String): List<Set<String>> = (dnsRules + rules).flatMap { all(it, key) }.map(::set)
        private fun all(r: JsonObject, key: String): List<JsonElement> = listOfNotNull(r[key]) + (r["rules"] as? JsonArray).orEmpty().flatMap { all(it as? JsonObject ?: return@flatMap emptyList(), key) }

        /** The ranges an address rule sends through a home tunnel, and those it lets go to the local network straight. */
        val home = rules.filter { it.str("outbound") == "home-wg" || it.str("outbound") == Compiler.TS_TAG }.flatMap { strings(it["ip_cidr"]) }
        val local = excluded + rules.filter { it.str("outbound") == DIRECT_TAG && "ip_cidr" in it }.flatMap { strings(it["ip_cidr"]) }
    }

    private fun JsonObject.obj(k: String) = this[k] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.list(k: String) = (this[k] as? JsonArray).orEmpty().map { it.jsonObject }
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun strings(e: JsonElement?): List<String> = when (e) { is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.content }; is JsonPrimitive -> listOf(e.content); else -> emptyList() }

    /** The condition keys of [r], those of its rules of rules too. */
    private fun keys(r: JsonObject): Set<String> = r.keys + (r["rules"] as? JsonArray).orEmpty().flatMap { (it as? JsonObject)?.let(::keys).orEmpty() }

    /** What each rule of [list] decides by; an app's rule before any rule about where or how a connection goes holds for all of them. */
    private fun decided(list: List<JsonObject>): List<By> {
        val first = list.indexOfFirst { r -> keys(r).any { it in setOf("domain", "domain_suffix", "ip_cidr", "network", "port", "protocol", "query_type") } }.let { if (it < 0) Int.MAX_VALUE else it }
        return list.mapIndexed { i, r -> keys(r).let { k -> when {
            "domain" in k || "domain_suffix" in k -> By.Name
            "ip_cidr" in k || "network" in k -> By.Address
            "package_name" in k || "package_name_regex" in k -> if (i < first) By.AppFirst else By.App
            else -> By.None
        } } }
    }

    private fun transports(r: JsonObject): Boolean {
        val net = strings(r["network"]); val port = strings(r["port"])
        val ok = net.all { it in setOf("tcp", "udp", "icmp") } && (r["rules"] as? JsonArray).orEmpty().all { it is JsonObject && transports(it) }
        // UDP and ports appear only where port 53 is taken over as a lookup.
        return ok && (("udp" !in net && port.isEmpty()) || (net == listOf("udp") && port == listOf("53") && r.str("action") == "hijack-dns"))
    }

    /**
     * [s] compiled with stand-in tunnels for its provider routes (and, [given], for routes waiting for their config, as
     * once it is added), under Android's [lockdown] or not; null when the compiler refuses it. [kin]: the apps sharing a user id with an app.
     */
    fun compile(s: Setup, kin: (String) -> Set<String>, given: Boolean = false, lockdown: Boolean = false): Config? = runCatching {
        val p = LinkPicker.pick(s, emptyMap(), null, emptyMap(), emptyMap(), emptyMap(), offline = true) { Providers.get(it) }
        val providers = s.activeRoutes.filter { !it.imported || (given && p.blocked[it.id] is LinkPicker.KeyLeftOut) }
        val links = p.links + providers.mapIndexed { n, r -> r.id to standIn(r, n) }
        val kinOfBlocked = s.apps.filterValues { it == BLOCK }.keys.flatMapTo(HashSet()) { kin(it) + it }
        Config(Compiler.compile(Compiler.Input(s, links, "app.unbraid", lockdown, "", "", blocked = p.blocked.keys - providers.map { it.id }.toSet(), blockedKin = kinOfBlocked)), s)
    }.getOrNull()

    /** A WireGuard tunnel of its own for provider route [r], made up: distinct per route, so no two share one. */
    private fun standIn(r: Route, n: Int): Link {
        val key = { salt: String -> Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest((salt + r.id).toByteArray())) }
        val host = "198.51.100.${n % 250 + 1}"
        val peer = WgPeer(r.name, key("private"), listOf("10.5.0.2/32"), key("public"), null, "$host:51820", listOf("0.0.0.0/0"))
        return WgLink(Server(r.name, host, host, peer.publicKey, 0), peer)
    }

    private fun ip(a: String) = Compiler.ipv4(a)!!.first

    /**
     * A connection from an app of user id [pkgs] (empty: Android names no app) to [name] (looked up as the app would) whose
     * real address is [address], by TCP ([net] "tcp") or a ping ("icmp"). Null when a rule holds something this walk doesn't know.
     */
    fun connect(c: Config, pkgs: Set<String>, name: String?, address: String = PUBLIC, net: String = "tcp"): Outcome? = walk(c, pkgs, name, ip(address), net, null)?.out

    fun walk(c: Config, pkgs: Set<String>, name: String?, address: Long, net: String, touch: Touch?): Step? {
        // Left outside unbraid by Android: Direct, looked up by the phone's network.
        if (pkgs.any { it in c.left }) return Step(Outcome(DIRECT_TAG, Resolver.Local.takeIf { name != null }), By.AppFirst, By.AppFirst)
        var ip = address
        var real = true
        var host = name
        var resolver: String? = null
        var rby = By.None
        val outcome = { tag: String -> Outcome(tag, if (name == null) null else kind(c, resolver ?: c.resolver)) }
        if (name != null) {
            // The app's lookup: refused, a stand-in address (the name goes on with the connection), or the real one from another resolver.
            val i = c.reach(true, pkgs).firstOrNull { match(c, c.dnsRules[it], pkgs, name, null, false, "A", net, null) ?: return null }
            val r = i?.let(c.dnsRules::get)
            val server = r?.str("server") ?: if (r == null) c.dnsFinal else null
            when {
                r?.str("action") == "reject" -> return Step(Outcome(REJECT), c.dnsBy[i], c.dnsBy[i])
                server == "fake" -> { ip = c.fake; real = false }
                server != null -> { resolver = server; host = null; rby = i?.let(c.dnsBy::get) ?: By.None }
                else -> return null
            }
        }
        // Outside the tunnel's own routes, Android sends it straight to the network.
        if (!inside(c, ip, if (real) touch else null)) return Step(outcome(DIRECT_TAG), By.Address, rby)
        for (i in c.reach(false, pkgs)) {
            val r = c.rules[i]
            if (!(match(c, r, pkgs, host, ip, real, null, net, touch) ?: return null)) continue
            val d = c.routeBy[i]
            r.str("outbound")?.let { return Step(outcome(it), d, rby) }
            when (r.str("action")) {
                "reject" -> return Step(Outcome(if (c.held[i]) HELD else REJECT), d, d)
                "resolve" -> if (host != null && !real) { resolver = r.str("server") ?: c.resolver; rby = d; ip = address; real = true }
                "sniff", "route-options" -> Unit
                else -> return null
            }
        }
        return Step(outcome(c.final), By.None, rby)
    }

    /** A lookup alone, of question [type], for [name] by an app of user id [pkgs]: refused, answered empty, or by which resolver through which exit. */
    fun lookup(c: Config, pkgs: Set<String>, name: String, type: String): Step? {
        if (pkgs.any { it in c.left }) return Step(Outcome(DIRECT_TAG, Resolver.Local), By.AppFirst, By.AppFirst)
        for (i in c.reach(true, pkgs)) {
            val r = c.dnsRules[i]
            if (!(match(c, r, pkgs, name, null, false, type, "udp", null) ?: return null)) continue
            val by = c.dnsBy[i]
            return when (r.str("action")) {
                "reject" -> Step(Outcome(REJECT), by, by)
                "predefined" -> Step(Outcome(EMPTY), by, by)
                else -> Step(answer(c, r.str("server") ?: return null), by, by)
            }
        }
        return Step(answer(c, c.dnsFinal), By.None, By.None)
    }

    private fun answer(c: Config, tag: String) = c.servers[tag].let { d -> Outcome(if (tag == "fake") "fake" else d?.str("detour") ?: d?.str("endpoint") ?: DIRECT_TAG, kind(c, tag)) }

    /** Whether rule [r] matches; null when it holds a condition this walk can't judge. [dns]: the question type, for a lookup rule. */
    private fun match(c: Config, r: JsonObject, pkgs: Set<String>, host: String?, ip: Long?, real: Boolean, dns: String?, net: String, touch: Touch?): Boolean? {
        var hit = true
        // A rule's name conditions are one: any of them matching is enough, as in the engine.
        if ("domain" in r || "domain_suffix" in r) hit = host != null && c.named(host, r["domain"], r["domain_suffix"])
        for ((k, v) in r) when (k) {
            "action", "outbound", "server", "no_drop", "rewrite_ttl", "invert", "mode", "domain", "domain_suffix", "rcode" -> Unit
            "type" -> if ((v as? JsonPrimitive)?.content != "logical") return null
            "rules" -> {
                val subs = (v as? JsonArray ?: return null).map { match(c, it as? JsonObject ?: return null, pkgs, host, ip, real, dns, net, touch) ?: return null }
                hit = hit && if (r.str("mode") == "or") subs.any { it } else subs.all { it }
            }
            "inbound" -> hit = hit && "tun" in strings(v)
            // The app's own tests, and DNS: never a connection walked here (lookups are walked as lookups).
            "auth_user", "protocol" -> hit = false
            "network" -> hit = hit && net in strings(v)
            "port" -> hit = hit && net == "tcp" && "443" in strings(v)
            "query_type" -> hit = hit && dns != null && dns in strings(v)
            "ip_cidr" -> { if (dns != null || ip == null) return null; if (real) touch?.hit = true; hit = hit && c.cidrs(v).any { within(ip, it) } }
            "package_name" -> hit = hit && pkgs.any { it in c.set(v) }
            "package_name_regex" -> hit = hit && pkgs.any { p -> c.regexes(v).any { it.containsMatchIn(p) } }
            else -> return null
        }
        return hit != ((r["invert"] as? JsonPrimitive)?.booleanOrNull == true)
    }


    /** Whether Android sends [ip] into the tunnel: the most specific of its routes and exclusions wins, an exclusion on a tie. */
    private fun inside(c: Config, ip: Long, touch: Touch?): Boolean {
        if (c.included.isEmpty()) return true
        touch?.hit = true
        val len = { l: List<Pair<Long, Int>> -> l.filter { within(ip, it) }.maxOfOrNull { it.second } }
        val inc = len(c.includedRanges) ?: return false
        return len(c.excludedRanges)?.let { it < inc } ?: true
    }

    internal fun within(ip: Long, r: Pair<Long, Int>) = r.second == 0 || (r.first xor ip) shr (32 - r.second) == 0L

    private fun kind(c: Config, tag: String): Resolver = when (tag) {
        "dns-filter" -> Resolver.Filter
        "local" -> Resolver.Local
        "dns-tailnet" -> Resolver.Home
        // The filter is written with its own port (and TLS or a path); a route's resolver with neither.
        else -> c.servers[tag]?.let { d -> if ("server_port" in d) Resolver.Filter else if (d.str("server") in c.blocking) Resolver.Provider else null } ?: Resolver.Route
    }

    // ---- what gets weaker ----

    /** How a connection protects less after the restore. */
    enum class Loss { Unblocked, ToDirect, Filter, ProviderBlocking }

    private fun level(o: Outcome) = when (o.exit) { REJECT -> 2; DIRECT_TAG -> 0; else -> 1 }
    private fun shield(o: Outcome) = when (o.resolver) { Resolver.Filter -> 2; Resolver.Provider -> 1; else -> 0 }

    /** [now] against [was] in the order above: null when it protects as much. */
    fun loss(was: Outcome, now: Outcome): Loss? = when {
        was.exit == EMPTY || now.exit == EMPTY || was.exit == HELD || now.exit == HELD -> null
        level(now) < level(was) -> if (was.exit == REJECT) Loss.Unblocked else Loss.ToDirect
        now.exit == REJECT -> null
        shield(now) < shield(was) -> if (was.resolver == Resolver.Filter) Loss.Filter else Loss.ProviderBlocking
        else -> null
    }

    /** What a weaker class is said for. */
    sealed interface Who {
        data object Default : Who
        data class App(val pkg: String) : Who
        data class Site(val name: String) : Who
        /** Connections Android names no app for, pings included. */
        data object NoApp : Who
        /** The local network, its addresses and the names its own resolver answers. */
        data object Local : Who
        /** The home network behind a home tunnel, its addresses and the names the tailnet answers. */
        data object Home : Who
    }

    /**
     * What a restore makes weaker, by what it is said for; [leaving]: apps that go outside unbraid (left out by Android),
     * where no website rule, filter or home tunnel reaches them; [cut] when not every app met every website.
     */
    data class Found(val losses: Map<Who, Set<Loss>>, val leaving: Map<Who, Loss?> = emptyMap(), val cut: Boolean = false)

    private class Program(val pkgs: Set<String>, val members: List<Set<String>>, val ping: Boolean = false)
    private class Name(val name: String?, val members: List<String>)
    private class Address(val ip: Long, val home: Boolean, val local: Boolean)
    private class Duo(val was: Step, val now: Step, val loss: Loss?)

    /** The first of [base], then [base] numbered, that [taken] refuses: a probe no rule names. */
    private fun fresh(base: (Int) -> String, taken: (String) -> Boolean) = generateSequence(0) { it + 1 }.map(base).first { !taken(it) }

    /**
     * What restoring [next] over [current] makes weaker, class by class; null when a config can't be built or walked.
     * [kin]: the apps sharing an Android user id with an app (the engine and Android decide by user id).
     */
    fun found(current: Setup, next: Setup, kin: (String) -> Set<String> = { emptySet() }): Found? {
        val waiting = listOf(current, next).any { s -> s.routes.any { it.keyless } || s.servers.any { it.keyless } }
        class Side(val was: Config, val now: Config, val wasGiven: Config?, val nowGiven: Config?)
        val sides = listOf(false, true).map { l ->
            Side(compile(current, kin, lockdown = l) ?: return null, compile(next, kin, lockdown = l) ?: return null,
                if (waiting) compile(current, kin, true, l) ?: return null else null, if (waiting) compile(next, kin, true, l) ?: return null else null)
        }
        val all = sides.flatMap { listOfNotNull(it.was, it.now, it.wasGiven, it.nowGiven) }
        if (all.any { !it.known }) return null

        // ---- the classes, read from the configs ----
        val keys = (current.apps.keys + next.apps.keys + current.prefs.filter.apps.keys + next.prefs.filter.apps.keys).toSortedSet()
        val pkgLists = (all.flatMap { it.atoms("package_name") } + all.map { it.left }).distinct()
        val pkgPatterns = all.flatMap { c -> c.atoms("package_name_regex").map { s -> s.map(::Regex) } }.distinct()
        val inLists = HashMap<String, MutableList<Int>>().apply { pkgLists.forEachIndexed { i, l -> l.forEach { getOrPut(it) { mutableListOf() } += i } } }
        val named = (inLists.keys + keys).toSortedSet()
        val other = fresh({ "app.unbraid.check.other" + if (it == 0) "" else "$it" }) { it in named }
        val pkgSig = { g: Set<String> -> g.flatMap { inLists[it].orEmpty() }.toSortedSet().toString() + pkgPatterns.indices.filter { i -> g.any { p -> pkgPatterns[i].any { it.containsMatchIn(p) } } } }
        val programs = (listOf(setOf(other), emptySet()) + named.map { kin(it) + it }.distinct())
            .groupBy(pkgSig).values.map { Program(it.first(), it) } + Program(emptySet(), listOf(emptySet()), ping = true)

        val exact = all.flatMap { it.atoms("domain") }.distinct()
        val under = all.flatMap { it.atoms("domain_suffix") }.distinct()
        val roots = (exact.flatten() + under.flatten()).map { it.removePrefix(".") }.toSortedSet()
        val endings = { n: String -> generateSequence(n) { it.substringAfter('.', "").ifEmpty { null } }.toList() }
        val covered = { n: String -> endings(n).any { it in roots } }
        val top = fresh({ if (it == 0) "unbraid-check.example" else "unbraid-check-$it.t$it" }, covered)
        val nameSig = { n: String -> endings(n).let { e -> exact.map { n in it } + under.map { l -> e.withIndex().any { (i, x) -> x in l || (i > 0 && ".$x" in l) } } }.toString() }
        val candidates = listOf(top) + roots.flatMap { r -> listOf(r, fresh({ "zz-check${if (it == 0) "" else it}.$r" }) { it in roots }) }
        val names = listOf(Name(null, emptyList())) + candidates.groupBy(nameSig).values.map { Name(it.first(), it) }.sortedBy { it.name != top }

        val homeRanges = all.flatMap { it.home }.mapNotNull(Compiler::ipv4)
        val localRanges = all.flatMap { it.local }.mapNotNull(Compiler::ipv4)
        val cidrLists = all.flatMap { c -> c.atoms("ip_cidr") + listOf(c.included.toSet(), c.excluded.toSet()) }.distinct().map { l -> l.mapNotNull(Compiler::ipv4) }
        val bounds = (cidrLists.flatten() + homeRanges + localRanges + Compiler.FAKE_RANGES.mapNotNull(Compiler::ipv4)).flatMap { (a, l) -> listOf(a, a + (1L shl (32 - l))) }.plus(listOf(0L, 1L shl 32, ip(PUBLIC), ip(PUBLIC) + 1)).toSortedSet().toList()
        // One address in every stretch between two boundaries (the stand-in public one in its own), those that meet the same ranges everywhere walked once.
        // The stand-in pool (a benchmark range) is no real destination: only lookups' stand-ins point there.
        val pool = Compiler.FAKE_RANGES.mapNotNull(Compiler::ipv4)
        val addresses = bounds.zipWithNext().filter { (a, _) -> pool.none { within(a, it) } }.map { (a, b) -> Address(if (b - a > 2) a + 1 else a, homeRanges.any { within(a, it) }, localRanges.any { within(a, it) }) }
            .groupBy { a -> "${a.home}${a.local}" + cidrLists.map { l -> l.any { within(a.ip, it) } } + all.map { inside(it, a.ip, null) } }.values
            .map { g -> g.firstOrNull { it.ip == ip(PUBLIC) } ?: g.first() }
        val public = addresses.first { it.ip == ip(PUBLIC) }

        val typeLists = all.flatMap { it.atoms("query_type") }
        val other2 = fresh({ listOf("TXT", "NULL", "CAA", "NAPTR")[it % 4] + if (it < 4) "" else "$it" }) { t -> typeLists.any { t in it } }
        val types = (typeLists.flatten() + "HTTPS" + other2).distinct().groupBy { t -> typeLists.map { t in it }.toString() }.values.map { it.first() }
            .filter { t -> typeLists.map { t in it } != typeLists.map { "A" in it } }

        // ---- the walk, every class through every config ----
        val sitesBoth = current.sites.keys + next.sites.keys
        val lost = HashMap<Who, MutableSet<Loss>>()
        val leaving = HashMap<Who, Loss?>()
        val perProgram = names.size * sides.size
        val full = maxOf(3, MAX_PAIRS / maxOf(1, perProgram))
        val cut = programs.size > full
        for (side in sides) {
            fun duo(f: (Config) -> Step?): Duo? {
                val a = f(side.was) ?: return null
                val b = f(side.now) ?: return null
                if (side.wasGiven == null || side.nowGiven == null || a.out.exit != REJECT || b.out.exit != REJECT) return Duo(a, b, loss(a.out, b.out))
                // Refused on both sides, but by a route waiting for its config: it opens once the config is added.
                val c = f(side.wasGiven) ?: return null
                val e = f(side.nowGiven) ?: return null
                return Duo(a, b, Loss.Unblocked.takeIf { c.out.exit == REJECT && e.out.exit != REJECT })
            }
            // The default program's own traffic and lookups: what traffic no app is named for is judged against.
            var dflt: Duo? = null
            val dfltLookups = HashMap<String, Duo>()
            val say = { w: Set<Who>, l: Loss -> w.forEach { lost.getOrPut(it) { mutableSetOf() } += l } }
            for ((pi, p) in programs.withIndex()) {
                val net = if (p.ping) "icmp" else "tcp"
                val ownName = if (p.ping) null else top
                val own = duo { walk(it, p.pkgs, ownName, public.ip, net, null) } ?: return null
                if (pi == 0) dflt = own
                val pe = entity(p, own, keys, dflt)
                own.loss?.let { say(pe, it) }
                val left = { s: Step -> s.by == By.AppFirst && s.out.exit == DIRECT_TAG }
                if (left(own.now) && !left(own.was)) pe.filterIsInstance<Who.App>().forEach { leaving[it] = own.loss }
                // Its own lookups of each question type, which its other lookups are judged against.
                val ownLookups = if (p.ping) emptyMap() else types.associateWith { q -> duo { lookup(it, p.pkgs, top, q) } ?: return null }
                if (pi == 0) dfltLookups.putAll(ownLookups)
                val lookupEntities = ownLookups.mapValues { (q, o) -> entity(p, o, keys, dfltLookups[q]).also { e -> o.loss?.let { say(e, it) } } }
                for (n in names) {
                    if (p.ping && n.name != null) continue
                    // Beyond the budget an app meets only its own traffic; the sheet says so.
                    if (pi >= full && n.name != null && n.name != top) continue
                    val t = Touch()
                    val first = duo { walk(it, p.pkgs, n.name, addresses[0].ip, net, t) } ?: return null
                    for ((ai, a) in addresses.withIndex()) {
                        val d = if (ai == 0 || !t.hit) first else duo { walk(it, p.pkgs, n.name, a.ip, net, null) } ?: return null
                        val l = d.loss ?: continue
                        if (n.name == ownName && a === public) continue
                        say(who(n, a, d, own, pe, sitesBoth), l)
                    }
                    if (n.name != null && n.name != top && !p.ping) for (q in types) {
                        val d = duo { lookup(it, p.pkgs, n.name, q) } ?: return null
                        say(who(n, null, d, ownLookups.getValue(q), lookupEntities.getValue(q), sitesBoth), d.loss ?: continue)
                    }
                }
            }
        }
        return Found(lost, leaving.filterKeys { it in lost }, cut)
    }

    /** What an app's own traffic is said for: the app when its own rule decides it on either side, else the default route. */
    private fun entity(p: Program, own: Duo, keys: Set<String>, default: Duo?): Set<Who> {
        val by = setOf(own.was.by, own.now.by, own.was.rby, own.now.rby)
        return when {
            p.pkgs.isEmpty() -> if (default != null && !alike(own, default, own.loss)) setOf(Who.NoApp) else setOf(Who.Default)
            p.members.none { g -> g.any { it in keys } } -> setOf(Who.Default)
            by.any { it == By.App || it == By.AppFirst } -> p.members.flatten().filter { it in keys }.mapTo(HashSet()) { Who.App(it) }
            else -> setOf(Who.Default)
        }
    }

    /** Whether [a] goes as [b] does, for loss [l]: by its exits for a lower exit, by its whole outcomes for a lost filter or blocking. */
    private fun alike(a: Duo, b: Duo, l: Loss?) = if (l == Loss.Unblocked || l == Loss.ToDirect) a.was.out.exit == b.was.out.exit && a.now.out.exit == b.now.out.exit
        else a.was.out == b.was.out && a.now.out == b.now.out

    /** What a class other than an app's own traffic is said for ([RestoreTraffic] point 5). */
    private fun who(n: Name, a: Address?, d: Duo, own: Duo, pe: Set<Who>, sites: Set<String>): Set<Who> {
        // Going as the app's own traffic goes, or as an app decided before anything else: the app's.
        if (alike(d, own, d.loss)) return pe
        // Left out by Android or blocked now, the app's own rule decides all it does: the app's.
        if (d.now.by == By.AppFirst && d.now.out.exit == own.now.out.exit) return pe
        val place = a?.let { if (it.home) Who.Home else if (it.local) Who.Local else null }
        // A website's names are its own; a name no website has is the own network's, the home's when the tailnet answers it.
        val home = d.was.out.resolver == Resolver.Home || d.now.out.resolver == Resolver.Home
        val names = { n.members.mapTo(HashSet<Who>()) { m -> Sites.match(m, sites)?.let { Who.Site(it) } ?: if (home) Who.Home else Who.Local } }
        // A refusal lifted is said for what refused it.
        if (d.loss == Loss.Unblocked) return when (d.was.by) {
            By.Name -> if (n.name != null) names() else pe
            By.App, By.AppFirst -> if (d.loss == own.loss) pe else place?.let(::setOf) ?: pe
            By.Address -> place?.let(::setOf) ?: pe
            By.None -> pe
        }
        // A lost filter or blocking is decided by what chose the resolver; a lower exit by what chose the exit.
        val resolver = d.loss == Loss.Filter || d.loss == Loss.ProviderBlocking
        val was = if (resolver) d.was.rby else d.was.by
        val now = if (resolver) d.now.rby else d.now.by
        if (d.loss == own.loss && was == By.AppFirst && d.was.out.exit == own.was.out.exit) return pe
        val at = place.takeIf { !resolver }
        // An address range decides when the name was resolved alike on both sides; else what resolved it does.
        if ((was == By.Address || now == By.Address) && at != null && d.was.rby == d.now.rby) return setOf(at)
        if (n.name != null && By.Name in listOf(was, now, d.was.rby, d.now.rby)) return names()
        return at?.let(::setOf) ?: pe
    }

    // ---- in the sheet's words ----

    const val UNKNOWN = "What this restore changes for each app and website couldn't be worked out."
    const val CUT = "This setup is too large to check every app against every website, so not everything that changes may be said here."

    /** A sentence of the sheet, with the loss and what it is said for when it comes from the walk. */
    data class Note(val text: String, val loss: Loss? = null, val who: Set<Who> = emptySet())

    /** [check]'s sentences. Slow for a large setup: never on the main thread. */
    fun notes(current: Setup, next: Setup, kin: (String) -> Set<String> = { emptySet() }): List<String> = check(current, next, kin).map { it.text }

    /** What the phone runs on a network of [kind] with [s]: the profile its rules switch to there by themselves, or the setup in use. */
    fun on(s: Setup, kind: String): Setup = s.onNet[kind]?.takeIf { p -> s.profiles.any { it.name == p && it.ready } }?.let(s::viewAs) ?: s

    /**
     * What restoring [next] over [current] makes weaker, in a few sentences: settings that are not per connection compared
     * directly (the filter, providers' blocking choices, own servers and the home tunnel by every address they connect
     * to), then what each connection gets ([found]); then, for each kind of network on which the restored rules switch to
     * another setup than the phone runs there now, the same for that pair.
     */
    fun check(current: Setup, next: Setup, kin: (String) -> Set<String> = { emptySet() }): List<Note> {
        val out = mutableListOf<Note>()
        val was = current.prefs.filter.server()
        val now = next.prefs.filter.server()
        val filterOff = was != null && now == null
        if (was != null && now != null && was != now) out += Note("The DNS filter changes to another server.")
        exitChanges(current, next).forEach { if (out.none { n -> n.text == it }) out += Note(it) }
        // A new install's placeholder isn't the user's: there is no traffic to compare.
        if (current.untouched) return listOfNotNull(Note(FILTER_OFF).takeIf { filterOff }) + out
        out += providerWeakens(current, next).map(::Note)
        val traffic = found(current, next, kin)?.let { trafficNotes(it, current, filterOff) } ?: (listOfNotNull(Note(FILTER_OFF).takeIf { filterOff }) + Note(UNKNOWN))
        out.addAll(0, traffic.filter { it.text == FILTER_OFF })
        out += traffic.filter { it.text != FILTER_OFF }
        // A profile the restored rules switch to by themselves runs without anyone choosing it: judged as the setup in use is.
        val switches = { s: Setup, k: String -> s.onNet[k]?.takeIf { p -> p != s.profile && s.profiles.any { it.name == p && it.ready } } }
        for (k in NET_KINDS.sorted()) {
            val a = on(current, k)
            val b = on(next, k)
            if (a == current && b == next) continue
            val said = (found(a, b, kin)?.let { trafficNotes(it, a, filterOff) } ?: listOf(Note(UNKNOWN))).filter { it.text != FILTER_OFF }
            if (said.isEmpty()) continue
            val where = if (k == WIFI) "Wi-Fi" else "mobile data"
            out += Note(switches(next, k)?.let { "On $where unbraid switches to the profile $it by itself. Compared with now there:" }
                ?: "On $where unbraid no longer switches to the profile ${switches(current, k)} by itself. Compared with now there:")
            out += said
        }
        return out
    }

    private const val FILTER_OFF = "The DNS filter is turned off."

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

    /**
     * The losses in the sheet's sentences: for each kind of loss, one sentence for each kind of thing it is said for, the
     * default route, the home and the local network in their own words, apps, websites and the rest counted together.
     */
    fun trafficNotes(found: Found, current: Setup, filterOff: Boolean): List<Note> {
        val out = mutableListOf<Note>()
        // An app that goes outside unbraid is said once, by what its own traffic loses (a VPN route gone Direct, a block
        // lifted); one on Direct already that loses more than its own, in a sentence of its own.
        val gone = found.leaving.filter { (w, own) -> own != Loss.ToDirect && own != Loss.Unblocked && found.losses[w].orEmpty().any { it != own } }.keys
        gone.size.takeIf { it > 0 }?.let { n -> out += Note(if (n == 1) "1 app on Direct no longer passes through unbraid, so the DNS filter, website rules and the home tunnel don't reach it."
            else "$n apps on Direct no longer pass through unbraid, so the DNS filter, website rules and the home tunnel don't reach them.", null, gone) }
        // Each thing is said once, for the worst it loses.
        val worst = found.losses.filterKeys { it !in gone }.filterValues { it.isNotEmpty() }
            .mapValues { (w, l) -> found.leaving[w] ?: l.minBy { it.ordinal } }
        val with = { l: Loss -> worst.filterValues { it == l }.keys }
        val apps = { w: Set<Who> -> w.filterIsInstance<Who.App>() }
        val sites = { w: Set<Who> -> w.filterIsInstance<Who.Site>() }
        // The parts of a sentence, each with whether it reads as many.
        fun parts(w: Set<Who>): List<Pair<String, Boolean>> = listOfNotNull(
            ("the default route" to false).takeIf { Who.Default in w },
            apps(w).size.takeIf { it > 0 }?.let { count(it, "app") to (it > 1) },
            sites(w).size.takeIf { it > 0 }?.let { count(it, "website") to (it > 1) },
            ("traffic Android can't tie to an app" to false).takeIf { Who.NoApp in w },
            ("the local network" to false).takeIf { Who.Local in w },
            ("the home network" to false).takeIf { Who.Home in w },
        )
        fun say(w: Set<Who>, l: Loss, verb: (Boolean) -> String) {
            val p = parts(w).takeIf { it.isNotEmpty() } ?: return
            out += Note(list(p.map { it.first }).replaceFirstChar(Char::uppercase) + " " + verb(p.size > 1 || p[0].second) + ".", l, w)
        }
        val direct = with(Loss.ToDirect)
        if (Who.Default in direct) out += Note("The default route goes from ${current.route(current.defaultRoute)?.name ?: "a VPN"} to Direct.", Loss.ToDirect, setOf(Who.Default))
        say(direct.filterTo(HashSet()) { it is Who.App || it is Who.Site || it == Who.NoApp }, Loss.ToDirect) { if (it) "go from a VPN route to Direct" else "goes from a VPN route to Direct" }
        if (Who.Home in direct) out += Note("Part of the home network goes Direct instead of through the home tunnel.", Loss.ToDirect, setOf(Who.Home))
        if (Who.Local in direct) out += Note("Apps can reach the local network again.", Loss.ToDirect, setOf(Who.Local))

        // Refused now because its route waits for its server's config, rather than blocked by choice: said apart.
        val waits = { id: String? -> current.route(id)?.let { r -> r.keyless || current.servers.any { it.id == r.own && it.keyless } } == true }
        val opened = with(Loss.Unblocked)
        if (Who.Default in opened) out += Note(if (waits(current.defaultRoute)) "The default route can't connect now, because its route waits for a server's config. After the restore its traffic goes out."
            else "The default route can't connect now. After the restore its traffic goes out.", Loss.Unblocked, setOf(Who.Default))
        val waiting = opened.filterTo(HashSet()) { w -> (w is Who.App && waits(current.routeOf(w.pkg))) || (w is Who.Site && waits(Sites.match(w.name, current.sites.keys)?.let(current.sites::get))) }
        val blockedApps = apps(opened).filterNot { it in waiting }
        val blockedSites = sites(opened).filterNot { it in waiting }
        blockedApps.size.takeIf { it > 0 }?.let { out += Note(if (it == 1) "1 app that is blocked now won't be." else "$it apps that are blocked now won't be.", Loss.Unblocked, blockedApps.toSet()) }
        blockedSites.size.takeIf { it > 0 }?.let { out += Note(if (it == 1) "1 website that is blocked now won't be." else "$it websites that are blocked now won't be.", Loss.Unblocked, blockedSites.toSet()) }
        say(waiting, Loss.Unblocked) { many -> "that can't connect now, because ${if (many) "their routes wait" else "its route waits"} for a server's config, ${if (many) "go" else "goes"} out without it" }
        say(opened.filterTo(HashSet()) { it == Who.NoApp }, Loss.Unblocked) { "is no longer refused" }
        if (Who.Local in opened) out += Note("Blocked apps can reach the local network.", Loss.Unblocked, setOf(Who.Local))
        if (Who.Home in opened) out += Note("Part of the home network that is refused now won't be.", Loss.Unblocked, setOf(Who.Home))

        // Said once for all when the filter is turned off.
        val filter = with(Loss.Filter)
        if (filterOff) out += Note(FILTER_OFF, Loss.Filter, filter) else say(filter, Loss.Filter) { if (it) "lose the DNS filter" else "loses the DNS filter" }
        say(with(Loss.ProviderBlocking), Loss.ProviderBlocking) { if (it) "lose the provider's ad and tracker blocking they have now" else "loses the provider's ad and tracker blocking it has now" }
        if (found.cut) out += Note(CUT)
        return out
    }
}
