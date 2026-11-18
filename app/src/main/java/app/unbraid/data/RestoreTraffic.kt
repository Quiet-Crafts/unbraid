@file:OptIn(app.unbraid.data.SavedSites::class)
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
 * 1. Situations. What the compiler builds depends on more than the setup: Android's lockdown, the tailnet domain
 *    Tailscale reported (a Headscale tailnet's own domain takes its names off website rules) and whether Tailscale
 *    waits for a sign-in (its sign-in pages then skip the exit). Each is an explicit input ([Situation]), every value
 *    it can take is walked, and none is read from the running VPN: the check holds whatever state Tailscale is in.
 *    Routes waiting for their config are walked as they are and as once it is added ([Duo.later]).
 * 2. Traffic classes. Both setups are compiled as the engine gets them: each provider route with a stand-in tunnel of
 *    its own (a restore sheet has no sign-in, and nothing about a provider route's protection depends on its tunnel),
 *    each own server with its config, a route waiting for its config refused, and the real user ids of apps that share
 *    one. A connection is an app (a user id, or none: Android names no app), the name it looked up (or none), the
 *    address it really goes to, and how: TCP, UDP (which goes as TCP does but where an exit carries TCP only) or a
 *    ping. A lookup alone has a question type. Every condition a rule can test is a package list or pattern, a name list,
 *    an address range, the network and port, or the question type, so the classes are read from the configs' own rules:
 *    every user id a package list names, one none names, and none; every name a rule names, and one no rule covers;
 *    one address between every two range boundaries; each question type a rule names, and one it doesn't. A made-up probe is checked against every rule. Classes that meet the same conditions are walked once.
 * 3. Outcomes, part by part ([Outcome]): the exit (refused, an empty answer, a tunnel or Direct, per protocol), who
 *    answered the name and the way that question went out, and what that resolver blocks. Each part is compared on its
 *    own ([losses]): refused above any tunnel above Direct; a question kept on the phone or sent through a tunnel above
 *    one sent Direct; the filter above the provider's blocking above any other resolver; and blocking that covers every
 *    kind of site it covered. A hold ([HELD]) ranks against Direct only, as a refusal; a question answered on the phone against no exit.
 * 4. Setups compared: the one in use against the restored one, then every other pair the phone can run without this
 *    sheet ([pairs]): after any order of networks, the profiles the rules switch to (and on a network with no rule, the
 *    one it switched to before), and each profile both have by name, as picked by hand or from a tile.
 * 5. Sentences. A weaker class is said for what decides it ([Who], [who]), each thing once, for the worst it loses,
 *    each kind of thing in its own sentence ([trafficNotes]).
 * 6. Cost. Before anything is built or walked, its cost is worked out from the setups and the configs' rule counts and
 *    taken from one [Budget] for the whole check: nothing is done that the budget can't pay for, and what is left out
 *    is said ([CUT]). Range lists are tested by binary search, so a long one costs no more per test than a short one.
 */
object RestoreTraffic {
    const val REJECT = "reject"
    /** An answer the engine makes up, empty (a website's HTTPS and SVCB records): the question never leaves the phone. */
    const val EMPTY = "empty"
    /** Refused because Android names no app for it, or a ping: held off Direct, as a VPN app's traffic would be. */
    const val HELD = "held"
    /** UDP an exit can't carry (a Shadowsocks plugin carries TCP only): refused there. */
    const val TCP_ONLY = "tcp-only"
    private const val DIRECT_TAG = "direct"
    private const val PUBLIC = "203.0.113.80"

    /** Who answered a connection's name: unbraid's filter, the provider's own blocking, the phone's network, a route's own, the home network's. */
    enum class Resolver { Filter, Provider, Local, Route, Home }

    /**
     * How one connection leaves: [exit] ([REJECT], [EMPTY], [TCP_ONLY], Direct, the home tunnel, the tailnet or a
     * tunnel's tag); [resolver], who answered its name, through exit [via]; and what that resolver blocks ([blocks]).
     * For a lookup alone, [exit] is the way the question goes out.
     */
    data class Outcome(val exit: String, val resolver: Resolver? = null, val via: String? = null, val blocks: Set<String> = emptySet())

    /**
     * What decided an outcome, least specific first: the default; an app's own rule; an app before any rule for a name
     * (Android leaving it out, Block); an address range (or a ping); a name.
     */
    enum class By { None, App, AppFirst, Address, Name }

    /** An outcome, what decided its exit ([by]), and what decided who answered its name ([rby]). */
    class Step(val out: Outcome, val by: By, val rby: By)

    /** One state of the phone that changes what the compiler builds ([RestoreTraffic] point 1). */
    data class Situation(val lockdown: Boolean, val suffix: String? = null, val signIn: Boolean = false)

    /** Every situation a phone with [a] or [b] can be in: lockdown off and on, and Tailscale's states where they change the config. */
    fun situations(a: Setup, b: Setup): List<Situation> {
        // Tailscale's own server: all of ts.net is the tailnet's anyway. A Headscale's domain counts only once reported, and only the allowed one.
        val suffixes = listOf<String?>(null) + listOf(a, b).mapNotNull { s -> s.home.tsSuffixAllowed.takeIf { s.home.tailscale && !Compiler.officialTs(s.home) } }.distinct()
        val signIn = listOf(false) + listOfNotNull(true.takeIf { a.tsExit != null || b.tsExit != null })
        return listOf(false, true).flatMap { l -> suffixes.flatMap { x -> signIn.map { Situation(l, x, it) } } }
    }

    /** What the whole check may spend: config entries built and rule tests walked. [tick] is called often, to stop a cancelled check. */
    class Budget(var left: Long = BUDGET, val tick: () -> Unit = {}) {
        /** Takes [n] when there is that much left. */
        fun take(n: Long): Boolean { tick(); return (n <= left).also { if (it) left -= n } }
    }

    /** About a second of work on a computer, a few on a phone (measured with the largest setups a backup may hold). */
    const val BUDGET = 60_000_000L

    /** The most entries one config may hold to be checked: a few megabytes in memory, far beyond any real setup. */
    const val MAX_ENTRIES = 100_000L

    /** Set while a walk tests the real address: when it doesn't, every address goes the same way. */
    class Touch { var hit = false }

    /** Address ranges merged into sorted stretches: whether one holds an address is a binary search. */
    class Ranges(list: List<Pair<Long, Int>>) {
        private val from: LongArray
        private val to: LongArray
        init {
            val merged = mutableListOf<LongArray>()
            for ((a, l) in list.map { (x, l) -> val lo = if (l == 0) 0L else x and (0xFFFFFFFFL shl (32 - l)) and 0xFFFFFFFFL; lo to lo + (1L shl (32 - l)) }.sortedBy { it.first })
                if (merged.isNotEmpty() && a <= merged.last()[1]) merged.last()[1] = maxOf(merged.last()[1], l) else merged += longArrayOf(a, l)
            from = LongArray(merged.size) { merged[it][0] }; to = LongArray(merged.size) { merged[it][1] }
        }
        fun has(ip: Long): Boolean { val i = from.binarySearch(ip).let { if (it < 0) -it - 2 else it }; return i >= 0 && ip < to[i] }
    }

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
        /** Exits that carry TCP only: UDP is refused there. */
        val tcpOnly = Compiler.tcpOnly(text)
        /**
         * What a provider's own blocking blocks, with this setup's choices, by the tag of the route resolver it answers as:
         * only where that route's provider policy chose it, as the compiler does. Another resolver at the same address (a
         * WireGuard config's own DNS server) blocks nothing known. And what the filter blocks.
         */
        val blocking = setup.routes.mapNotNull { r ->
            val p = Providers.get(r.loc.provider)?.takeIf { !r.imported && setup.protects(r) } ?: return@mapNotNull null
            val tag = "dns-r-${r.id}"
            tag.takeIf { p.blockingDns { setup.opt(p.id, it) }.let { it != null && it == servers[tag]?.str("server") } }?.let { it to providerBlocks(p, setup) }
        }.toMap()
        val filterBlocks = setup.prefs.filter.server()?.let { blocks(it.blocks) }.orEmpty()
        val dnsBy = decided(dnsRules)
        val routeBy = decided(rules)
        /** Refusals of what no app is named for, or of pings: there to keep a VPN app's traffic off Direct, they count as a tunnel ([level]). */
        val held = rules.map { r -> r.str("action") == "reject" && ("network" in keys(r) || unnamed(r)) }
        private fun unnamed(r: JsonObject): Boolean = ((r["invert"] as? JsonPrimitive)?.booleanOrNull == true && "package_name_regex" in r) ||
            (r["rules"] as? JsonArray).orEmpty().any { it is JsonObject && unnamed(it) }
        /** Every network and port a rule tests is one the walk knows: TCP, UDP, a ping, and UDP to port 53 taken over as a lookup. */
        val known = (dnsRules + rules).all(::transports)
        // By the list itself, then by what it holds: the address rules come again after every app group, as equal lists.
        private val sets = IdentityHashMap<JsonElement, Set<String>>()
        private val setsByContent = HashMap<JsonElement, Set<String>>()
        private val patterns = IdentityHashMap<JsonElement, List<Regex>>()
        private val ranges = IdentityHashMap<JsonElement, Ranges>()
        private val rangesByContent = HashMap<JsonElement, Ranges>()
        private val insides = HashMap<Long, Boolean>()
        fun set(e: JsonElement?): Set<String> = e?.let { sets.getOrPut(it) { setsByContent.getOrPut(it) { strings(it).toHashSet() } } }.orEmpty()
        fun regexes(e: JsonElement): List<Regex> = patterns.getOrPut(e) { strings(e).map(::Regex) }
        fun ranges(e: JsonElement): Ranges = ranges.getOrPut(e) { rangesByContent.getOrPut(e) { Ranges(strings(e).mapNotNull(Compiler::ipv4)) } }
        fun inside(ip: Long): Boolean = insides.getOrPut(ip) { insideOf(this, ip) }

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
        val home = rules.filter { it.str("outbound") == "home-wg" || it.str("outbound") == Compiler.TS_TAG }.flatMapTo(HashSet()) { strings(it["ip_cidr"]) }
        val local = (excluded + rules.filter { it.str("outbound") == DIRECT_TAG && "ip_cidr" in it }.flatMap { strings(it["ip_cidr"]) }).toHashSet()
    }

    private fun JsonObject.obj(k: String) = this[k] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.list(k: String) = (this[k] as? JsonArray).orEmpty().map { it.jsonObject }
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun strings(e: JsonElement?): List<String> = when (e) { is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.content }; is JsonPrimitive -> listOf(e.content); else -> emptyList() }

    /** What a resolver's description says it blocks; "?" when it names nothing unbraid knows (a NextDNS profile, an own server). */
    fun blocks(text: String): Set<String> = text.lowercase().split(Regex("[^a-z]+")).let { w ->
        listOfNotNull("ads".takeIf { "ads" in w }, "trackers".takeIf { "trackers" in w }, "malware".takeIf { "malware" in w || "malicious" in w }, "adult".takeIf { "adult" in w })
    }.toSet().ifEmpty { setOf("?") }

    /** Provider choices that add to what its blocking blocks, by their key. */
    private val EXTRA = linkedMapOf("adult" to "adult sites", "gambling" to "gambling sites", "social" to "social media", "hardcore" to "Google and Facebook")

    /** What provider [p]'s own blocking blocks with [s]'s choices: what its switch says, and each extra choice that is on. */
    private fun providerBlocks(p: Provider, s: Setup) = (p.protection?.sub?.let(::blocks) ?: setOf("?")) + EXTRA.keys.filter { s.opt(p.id, it) == "on" }

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
     * once it is added), in situation [at]; null when the compiler refuses it. [kin]: the apps sharing a user id with an app.
     */
    fun compile(s: Setup, kin: (String) -> Set<String>, given: Boolean = false, at: Situation = Situation(false)): Config? = runCatching {
        val p = LinkPicker.pick(s, emptyMap(), null, emptyMap(), emptyMap(), emptyMap(), offline = true) { Providers.get(it) }
        val providers = s.activeRoutes.filter { !it.imported || (given && p.blocked[it.id] is LinkPicker.KeyLeftOut) }
        val links = p.links + providers.mapIndexed { n, r -> r.id to standIn(r, n) }
        val kinOfBlocked = s.apps.filterValues { it == BLOCK }.keys.flatMapTo(HashSet()) { kin(it) + it }
        Config(Compiler.compile(Compiler.Input(s, links, "app.unbraid", at.lockdown, "", "", at.suffix, blocked = p.blocked.keys - providers.map { it.id }.toSet(),
            tsLoginPending = at.signIn, blockedKin = kinOfBlocked)), s)
    }.getOrNull()

    /** About how many entries [s]'s config holds, known before it is built: the address rules come again after every app group and website. */
    internal fun size(s: Setup): Long {
        val ranges = (s.home.wg?.let(Compiler::homeCidrs)?.size ?: 0) + s.home.subnets.size + 12
        val sites = Sites.applied(s).size
        return (s.apps.values.toSet().size + sites + 3L) * (ranges + 6) + s.apps.size + sites * 8L + s.routes.size * 8L
    }

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
     * real address is [address], by TCP ([net] "tcp"), UDP ("udp") or a ping ("icmp"). Null when a rule holds something this walk doesn't know.
     */
    fun connect(c: Config, pkgs: Set<String>, name: String?, address: String = PUBLIC, net: String = "tcp"): Outcome? = walk(c, pkgs, name, ip(address), net, null)?.out

    fun walk(c: Config, pkgs: Set<String>, name: String?, address: Long, net: String, touch: Touch?): Step? {
        // Left outside unbraid by Android: Direct, looked up by the phone's network.
        if (pkgs.any { it in c.left }) return Step(Outcome(DIRECT_TAG, Resolver.Local.takeIf { name != null }, DIRECT_TAG.takeIf { name != null }), By.AppFirst, By.AppFirst)
        var ip = address
        var real = true
        var host = name
        var resolver: String? = null
        var rby = By.None
        val outcome = { tag: String -> if (net == "udp" && tag in c.tcpOnly) Outcome(TCP_ONLY) else if (name == null) Outcome(tag) else (resolver ?: c.resolver).let { Outcome(tag, kind(c, it), via(c, it), blocksOf(c, it)) } }
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
        if (c.included.isNotEmpty()) { if (real) touch?.hit = true; if (!c.inside(ip)) return Step(outcome(DIRECT_TAG), By.Address, rby) }
        for (i in c.reach(false, pkgs)) {
            val r = c.rules[i]
            if (!(match(c, r, pkgs, host, ip, real, null, net, touch) ?: return null)) continue
            val d = c.routeBy[i]
            // Sent on with its name not yet looked up: the exit looks it up, as this rule decided.
            r.str("outbound")?.let { if (host != null && !real) rby = d; return Step(outcome(it), d, rby) }
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

    private fun answer(c: Config, tag: String) = Outcome(if (tag == "fake") "fake" else via(c, tag), kind(c, tag), blocks = blocksOf(c, tag))

    /** The exit a resolver's questions go out through. */
    private fun via(c: Config, tag: String) = c.servers[tag]?.let { it.str("detour") ?: it.str("endpoint") } ?: DIRECT_TAG

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
            // Ports appear only on the lookups taken over at port 53: no connection walked here goes there.
            "port" -> hit = false
            "query_type" -> hit = hit && dns != null && dns in strings(v)
            "ip_cidr" -> { if (dns != null || ip == null) return null; if (real) touch?.hit = true; hit = hit && c.ranges(v).has(ip) }
            "package_name" -> hit = hit && pkgs.any { it in c.set(v) }
            "package_name_regex" -> hit = hit && pkgs.any { p -> c.regexes(v).any { it.containsMatchIn(p) } }
            else -> return null
        }
        return hit != ((r["invert"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    /** Whether Android sends [ip] into the tunnel: the most specific of its routes and exclusions wins, an exclusion on a tie. */
    private fun insideOf(c: Config, ip: Long): Boolean {
        if (c.included.isEmpty()) return true
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
        else -> c.servers[tag]?.let { d -> if ("server_port" in d) Resolver.Filter else if (tag in c.blocking) Resolver.Provider else null } ?: Resolver.Route
    }

    private fun blocksOf(c: Config, tag: String) = when (kind(c, tag)) {
        Resolver.Filter -> c.filterBlocks
        Resolver.Provider -> c.blocking[tag].orEmpty()
        else -> emptySet()
    }

    // ---- what gets weaker ----

    /** How a connection protects less after the restore, the worst first. */
    enum class Loss { Unblocked, ToDirect, Lookups, Filter, ProviderBlocking, Blocking, Udp }

    /** Losses that what chose the resolver decides, not what chose the exit. */
    private val ANSWERED = setOf(Loss.Lookups, Loss.Filter, Loss.ProviderBlocking, Loss.Blocking)

    private fun level(o: Outcome) = when (o.exit) { REJECT, TCP_ONLY, EMPTY -> 2; DIRECT_TAG -> 0; else -> 1 }
    private fun shield(o: Outcome) = when (o.resolver) { Resolver.Filter -> 2; Resolver.Provider -> 1; else -> 0 }

    /**
     * [now] against [was], part by part ([RestoreTraffic] point 3): empty when it protects as much. [lookup]: a question
     * alone, whose exit is the way it goes out; one answered on the phone counts only against one sent Direct.
     */
    fun losses(was: Outcome, now: Outcome, lookup: Boolean = false): Set<Loss> {
        // Held off Direct, then out Direct: a refusal lifted, and Direct.
        if (was.exit == HELD && now.exit == DIRECT_TAG) return setOf(Loss.Unblocked, Loss.ToDirect)
        if (was.exit == HELD || now.exit == HELD) return emptySet()
        if (was.exit == EMPTY || now.exit == EMPTY) return if (was.exit == EMPTY && now.exit == DIRECT_TAG) setOf(Loss.Lookups) else emptySet()
        val out = HashSet<Loss>()
        if (level(now) < level(was)) out += when {
            was.exit == TCP_ONLY && level(now) == 1 -> Loss.Udp
            was.exit == REJECT || was.exit == TCP_ONLY -> Loss.Unblocked
            lookup -> Loss.Lookups
            else -> Loss.ToDirect
        }
        // What could not leave and now leaves outside any VPN is both: a refusal lifted, and Direct.
        if (!lookup && level(was) == 2 && now.exit == DIRECT_TAG) out += Loss.ToDirect
        if (level(was) == 2 || level(now) == 2) return out
        // The way the name's question went out.
        if (!lookup && was.via != null && was.via != DIRECT_TAG && now.via == DIRECT_TAG) out += Loss.Lookups
        when {
            shield(now) < shield(was) -> out += if (was.resolver == Resolver.Filter) Loss.Filter else Loss.ProviderBlocking
            !now.blocks.containsAll(was.blocks) -> out += Loss.Blocking
        }
        return out
    }

    /** What a weaker class is said for. */
    sealed interface Who {
        data object Default : Who
        /** App [pkg], or an app sharing its user id with [of], whose choice it follows. */
        data class App(val pkg: String, val of: String = pkg) : Who
        data class Site(val name: String) : Who
        /** Connections Android names no app for, pings included. */
        data object NoApp : Who
        /** The local network, its addresses and the names its own resolver answers. */
        data object Local : Who
        /** The home network behind a home tunnel, its addresses and the names the tailnet answers. */
        data object Home : Who
        /** Tailscale's sign-in pages, which skip the exit while Tailscale waits for a sign-in. */
        data object SignIn : Who
    }

    /**
     * What a restore makes weaker, by what it is said for; [later]: what gets weaker once the routes waiting for their
     * config get it, both setups compared that way (the sheet says what it adds to now); [leaving]: apps that go outside unbraid (left out by Android), where no website rule, filter or home
     * tunnel reaches them, with the worst their own traffic loses; [cut] when the budget left part of it unchecked.
     */
    data class Found(val losses: Map<Who, Set<Loss>>, val leaving: Map<Who, Loss?> = emptyMap(), val cut: Boolean = false, val later: Map<Who, Set<Loss>> = emptyMap())

    private class Program(val pkgs: Set<String>, val members: List<Set<String>>, val ping: Boolean = false)
    private class Name(val name: String?, val members: List<String>)
    private class Address(val ip: Long, val home: Boolean, val local: Boolean)
    /**
     * Two steps and what the second loses; [later]: the steps of routes waiting for their config, as once it is added.
     * [then]: those, compared on their own, beside the steps now.
     */
    private class Duo(val was: Step, val now: Step, val losses: Set<Loss>, val later: Boolean = false, val then: Duo? = null) {
        val phases get() = listOfNotNull(this, then)
    }

    /** The first of [base], then [base] numbered, that [taken] refuses: a probe no rule names. */
    private fun fresh(base: (Int) -> String, taken: (String) -> Boolean) = generateSequence(0) { it + 1 }.map(base).first { !taken(it) }

    /**
     * What restoring [next] over [current] makes weaker, class by class, in every [situations]; null when a config
     * can't be built or walked. [kin]: the apps sharing an Android user id with an app (Android and the engine decide by
     * user id). Paid from [budget]: what it can't pay for is left out and [Found.cut] set.
     */
    fun found(current: Setup, next: Setup, kin: (String) -> Set<String> = { emptySet() }, budget: Budget = Budget()): Found? {
        val waiting = listOf(current, next).any { s -> s.routes.any { it.keyless } || s.servers.any { it.keyless } }
        val sits = situations(current, next)
        // Too large to hold and walk: said so before anything is built.
        if (maxOf(size(current), size(next)) > MAX_ENTRIES) return Found(emptyMap(), cut = true)
        class Side(val was: Config, val now: Config, val wasGiven: Config?, val nowGiven: Config?) { val all = listOfNotNull(was, now, wasGiven, nowGiven) }
        val keys = (current.apps.keys + next.apps.keys + current.prefs.filter.apps.keys + next.prefs.filter.apps.keys).toSortedSet()
        // Whose a name is: a website's, the local network's, the tailnet's, or else one of Tailscale's sign-in pages.
        val sites = listOf(current, next).flatMap { s -> sits.flatMap { Sites.applied(s, reported = it.suffix).keys } }.toSet()
        val tailnet = listOf(current, next).flatMap { Compiler.tsDomains(it.home, it.home.tsSuffixAllowed) }
        val place = { m: String, home: Boolean -> Sites.match(m, sites)?.let { Who.Site(it) } ?: if (Compiler.LOCAL_NAMES.any { Sites.under(m, it) }) Who.Local
            else if (home || tailnet.any { Sites.under(m, it) }) Who.Home else Who.SignIn }
        val lost = HashMap<Who, MutableSet<Loss>>()
        val later = HashMap<Who, MutableSet<Loss>>()
        val leaving = HashMap<Who, Loss?>()
        var cut = false
        // One situation at a time, so only its configs are held.
        sides@ for (x in sits) {
            if (!budget.take((if (waiting) 2L else 1L) * (size(current) + size(next)))) { cut = true; break }
            val side = Side(compile(current, kin, at = x) ?: return null, compile(next, kin, at = x) ?: return null,
                if (waiting) compile(current, kin, true, x) ?: return null else null, if (waiting) compile(next, kin, true, x) ?: return null else null)
            val all = side.all
            if (all.any { !it.known }) return null

            // ---- the classes, read from the configs ----
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
            // A name under a rule's name meets the same rules as that name: every list the compiler writes takes a name with the
            // names under it, but the sign-in host while Tailscale waits, whose names are walked without that rule too.
            val candidates = listOf(top) + roots
            val names = listOf(Name(null, emptyList())) + candidates.groupBy(nameSig).values.map { Name(it.first(), it) }.sortedBy { it.name != top }

            // Range lists once each, tested by binary search: one address between every two boundaries, those alike everywhere walked once.
            val parse = { l: Collection<String> -> l.mapNotNull(Compiler::ipv4) }
            val homeRanges = Ranges(parse(all.flatMapTo(HashSet()) { it.home }))
            val localRanges = Ranges(parse(all.flatMapTo(HashSet()) { it.local }))
            val cidrLists = all.flatMap { c -> c.atoms("ip_cidr") + listOf(c.included.toSet(), c.excluded.toSet()) }.distinct().map(parse)
            val pool = Compiler.FAKE_RANGES.mapNotNull(Compiler::ipv4)
            val bounds = (cidrLists.flatten().distinct() + parse(all.flatMap { it.home + it.local }.distinct()) + pool).flatMap { (a, l) -> val lo = if (l == 0) 0L else a and (0xFFFFFFFFL shl (32 - l)) and 0xFFFFFFFFL; listOf(lo, lo + (1L shl (32 - l))) }
                .plus(listOf(0L, 1L shl 32, ip(PUBLIC), ip(PUBLIC) + 1)).toSortedSet().toList()
            val lists = cidrLists.map(::Ranges)
            val poolRanges = Ranges(pool)
            // The stand-in pool (a benchmark range) is no real destination: only lookups' stand-ins point there.
            // Paid before it is done: every address against every range list, and against each config's routes and exclusions.
            if (!budget.take(bounds.size * (cidrLists.size + all.sumOf { it.included.size + it.excluded.size } + 1L))) { cut = true; break@sides }
            val addresses = bounds.zipWithNext().filter { (a, _) -> !poolRanges.has(a) }.map { (a, b) -> Address(if (b - a > 2) a + 1 else a, homeRanges.has(a), localRanges.has(a)) }
                .groupBy { a -> "${a.home}${a.local}" + lists.map { it.has(a.ip) } + all.map { it.inside(a.ip) } }.values
                .map { g -> g.firstOrNull { it.ip == ip(PUBLIC) } ?: g.first() }
            val public = addresses.first { it.ip == ip(PUBLIC) }

            val typeLists = all.flatMap { it.atoms("query_type") }
            val other2 = fresh({ listOf("TXT", "NULL", "CAA", "NAPTR")[it % 4] + if (it < 4) "" else "$it" }) { t -> typeLists.any { t in it } }
            val types = (typeLists.flatten() + "HTTPS" + other2).distinct().groupBy { t -> typeLists.map { t in it }.toString() }.values.map { it.first() }
                .filter { t -> typeLists.map { t in it } != typeLists.map { "A" in it } }
            // UDP goes as TCP does but where an exit carries TCP only.
            val nets = if (all.any { it.tcpOnly.isNotEmpty() }) listOf("tcp", "udp") else listOf("tcp")

            // Now, and once the routes waiting for their config get it: two comparisons, each on its own.
            fun duo(lookup: Boolean = false, f: (Config) -> Step?): Duo? {
                val a = f(side.was) ?: return null
                val b = f(side.now) ?: return null
                val then = if (side.wasGiven == null || side.nowGiven == null) null else {
                    val c = f(side.wasGiven) ?: return null
                    val e = f(side.nowGiven) ?: return null
                    Duo(c, e, losses(c.out, e.out, lookup), later = true)
                }
                return Duo(a, b, losses(a.out, b.out, lookup), then = then)
            }
            val say = { w: Set<Who>, d: Duo, l: Loss -> w.forEach { (if (d.later) later else lost).getOrPut(it) { mutableSetOf() } += l } }
            // Each phase of [d] said against the same phase of the program's own traffic [own], for [pe] of that phase.
            fun told(d: Duo, own: Duo, pe: List<Set<Who>>, w: (Duo, Duo, Set<Who>, Loss) -> Set<Who>?) =
                d.phases.zip(own.phases).forEachIndexed { i, (x, o) -> x.losses.forEach { l -> w(x, o, pe[i], l)?.let { say(it, x, l) } } }
            // The default program's own traffic and lookups: what traffic no app is named for is judged against.
            var dflt: Duo? = null
            val dfltLookups = HashMap<String, Duo>()
            for ((pi, p) in programs.withIndex()) {
                // Paid before it is done: the rules this program can meet, times every walk it may take.
                if (!budget.take(side.all.sumOf { it.rules.size + it.dnsRules.size }.toLong())) { cut = true; break@sides }
                val rules = side.all.sumOf { c -> c.reach(false, p.pkgs).size + c.reach(true, p.pkgs).size }.toLong()
                val protos = if (p.ping) listOf("icmp") else nets
                val walks = { n: Int -> n * protos.size * (1L + addresses.size) + (if (p.ping) 0L else types.size * (n + 1L)) + protos.size }
                val whole = budget.take(rules * walks(names.size))
                if (!whole) { cut = true; if (!budget.take(rules * walks(2))) break@sides }
                val ownName = if (p.ping) null else top
                val own = duo { walk(it, p.pkgs, ownName, public.ip, protos[0], null) } ?: return null
                if (pi == 0) dflt = own
                // What its own traffic is said for, in each phase, and what it loses there.
                val mine = { o: Duo, d: Duo? -> o.phases.mapIndexed { i, x -> entity(p, x, keys, d?.phases?.getOrNull(i)).also { e -> x.losses.forEach { say(e, x, it) } } } }
                val pe = mine(own, dflt)
                val left = { s: Step -> s.by == By.AppFirst && s.out.exit == DIRECT_TAG }
                if (left(own.now) && !left(own.was)) pe[0].filterIsInstance<Who.App>().forEach { leaving[it] = own.losses.minOrNull() }
                // Its own lookups of each question type, which its other lookups are judged against.
                val ownLookups = if (p.ping) emptyMap() else types.associateWith { q -> duo(true) { lookup(it, p.pkgs, top, q) } ?: return null }
                if (pi == 0) dfltLookups.putAll(ownLookups)
                val lookupEntities = ownLookups.mapValues { (q, o) -> mine(o, dfltLookups[q]) }
                for (n in names) {
                    if (p.ping && n.name != null) continue
                    // Beyond the budget an app meets only its own traffic; the sheet says so.
                    if (!whole && n.name != null && n.name != top) continue
                    for (net in protos) {
                        val t = Touch()
                        val first = duo { walk(it, p.pkgs, n.name, addresses[0].ip, net, t) } ?: return null
                        for ((ai, a) in addresses.withIndex()) {
                            val d = if (ai == 0 || !t.hit) first else duo { walk(it, p.pkgs, n.name, a.ip, net, null) } ?: return null
                            if (net == protos[0] && n.name == ownName && a === public) continue
                            told(d, own, pe) { x, o, e, l -> if (opensNearby(x, l)) null else who(n, a, x, o, e, place, l) }
                        }
                    }
                    if (n.name != null && n.name != top && !p.ping) for (q in types) {
                        val d = duo(true) { lookup(it, p.pkgs, n.name, q) } ?: return null
                        told(d, ownLookups.getValue(q), lookupEntities.getValue(q)) { x, o, e, l -> who(n, null, x, o, e, place, l) }
                    }
                }
            }
        }
        return Found(lost, leaving.filterKeys { it in lost }, cut, later)
    }

    /** What an app's own traffic is said for: the app (and the apps sharing its user id) when its own rule decides it on either side, else the default route. */
    private fun entity(p: Program, own: Duo, keys: Set<String>, default: Duo?): Set<Who> {
        val by = setOf(own.was.by, own.now.by, own.was.rby, own.now.rby)
        return when {
            // Traffic with no app goes as the default route's when it leaves as that does; a ping, which has no name, by its exits alone.
            p.pkgs.isEmpty() -> if (default != null && !alike(own, default, if (p.ping) Loss.ToDirect else own.losses.minOrNull())) setOf(Who.NoApp) else setOf(Who.Default)
            p.members.none { g -> g.any { it in keys } } -> setOf(Who.Default)
            by.any { it == By.App || it == By.AppFirst } -> p.members.filter { g -> g.any { it in keys } }
                .flatMapTo(HashSet()) { g -> val k = g.first { it in keys }; g.map { Who.App(it, if (it in keys) it else k) } }
            else -> setOf(Who.Default)
        }
    }

    /** Whether [a] goes as [b] does, for loss [l]: by its exits for a lower exit, by the way its questions go out for those, by its whole outcomes for what answered. */
    private fun alike(a: Duo, b: Duo, l: Loss?): Boolean {
        val part = { o: Outcome -> when (l) { Loss.Unblocked, Loss.ToDirect, Loss.Udp -> o.exit; Loss.Lookups -> o.via ?: o.exit; else -> o } }
        return part(a.was.out) == part(b.was.out) && part(a.now.out) == part(b.now.out)
    }

    /** A refusal lifted to the local or home network by an address range: said as opened, not as gone Direct. */
    private fun opensNearby(d: Duo, l: Loss) = l == Loss.ToDirect && Loss.Unblocked in d.losses && d.now.by == By.Address

    /** What loss [l] of a class other than an app's own traffic is said for ([RestoreTraffic] point 5); [place]: whose name a name is. */
    private fun who(n: Name, a: Address?, d: Duo, own: Duo, pe: Set<Who>, place: (String, Boolean) -> Who, loss: Loss): Set<Who>? {
        // Held off Direct only for want of an app to tie it to: said for that traffic; the local and home networks are
        // left as they are for every app without a hold.
        if (d.was.out.exit == HELD) return if (a?.home == true || a?.local == true) null else pe
        // A refusal lifted to Direct is said for what refused it, both ways.
        val l = if (loss == Loss.ToDirect && Loss.Unblocked in d.losses) Loss.Unblocked else loss
        // Going as the app's own traffic goes, or as an app decided before anything else: the app's.
        if (alike(d, own, l)) return pe
        // Left out by Android or blocked now, the app's own rule decides all it does: the app's.
        if (d.now.by == By.AppFirst && d.now.out.exit == own.now.out.exit) return pe
        val at = a?.let { if (it.home) Who.Home else if (it.local) Who.Local else null }
        // A website's names are its own; others the local network's, the tailnet's or Tailscale's sign-in pages.
        val home = d.was.out.resolver == Resolver.Home || d.now.out.resolver == Resolver.Home
        val names = { n.members.mapTo(HashSet()) { place(it, home) } }
        // A refusal lifted is said for what refused it.
        if (l == Loss.Unblocked) return when (d.was.by) {
            By.Name -> if (n.name != null) names() else pe
            By.App, By.AppFirst -> if (l in own.losses) pe else at?.let(::setOf) ?: pe
            By.Address -> at?.let(::setOf) ?: pe
            By.None -> pe
        }
        // What answered a name is decided by what chose the resolver; a lower exit by what chose the exit.
        val answered = l in ANSWERED
        val was = if (answered) d.was.rby else d.was.by
        val now = if (answered) d.now.rby else d.now.by
        if (l in own.losses && was == By.AppFirst && d.was.out.exit == own.was.out.exit) return pe
        val range = at.takeIf { !answered }
        // An address range decides when the name was resolved alike on both sides; else what resolved it does.
        if ((was == By.Address || now == By.Address) && range != null && d.was.rby == d.now.rby) return setOf(range)
        if (n.name != null && By.Name in listOf(was, now, d.was.rby, d.now.rby)) return names()
        return range?.let(::setOf) ?: pe
    }

    // ---- in the sheet's words ----

    const val UNKNOWN = "What this restore changes for each app and website couldn't be worked out."
    const val CUT = "This setup is too large to check in full, so not everything that changes may be said here."

    /** A sentence of the sheet, with the loss and what it is said for when it comes from the walk. */
    data class Note(val text: String, val loss: Loss? = null, val who: Set<Who> = emptySet())

    /** [check]'s sentences. Slow for a large setup: never on the main thread; [tick] may throw to stop it. */
    fun notes(current: Setup, next: Setup, kin: (String) -> Set<String> = { emptySet() }, tick: () -> Unit = {}): List<String> = check(current, next, kin, Budget(tick = tick)).map { it.text }

    /** What the phone runs on a network of [kind] coming from [at] (a profile's name, null: the setup in use): the profile its rules switch to there by themselves, or [at]. */
    private fun on(s: Setup, kind: String, at: String?): String? =
        s.onNet[kind]?.takeIf { p -> s.profiles.any { it.name == p && it.ready } }.let { p -> if (p == null) at else p.takeIf { it != s.profile } }

    /**
     * Every other pair of setups the phone can run after the restore without this sheet, each with the sentence that
     * says when: after any order of networks, the profiles the rules of each switch to, a network with no rule keeping
     * the one before; then each ready profile both have by name, picked by hand or from a tile.
     */
    fun pairs(current: Setup, next: Setup): List<Triple<String, Setup, Setup>> {
        val path = linkedMapOf<Pair<String?, String?>, List<String>>((null to null) to emptyList())
        val queue = ArrayDeque(listOf<Pair<String?, String?>>(null to null))
        while (queue.isNotEmpty()) {
            val st = queue.removeFirst()
            for (k in NET_KINDS.sorted()) (on(current, k, st.first) to on(next, k, st.second)).let { nx -> if (nx !in path) { path[nx] = path.getValue(st) + k; queue += nx } }
        }
        val where = { k: String -> if (k == WIFI) "Wi-Fi" else "mobile data" }
        val runs = { s: Setup, p: String?, restored: Boolean -> (p ?: s.profile)?.let { "the profile ${Backup.safe(it, 60)}" } ?: if (restored) "the restored setup" else "your current setup" }
        val out = mutableListOf<Triple<String, Setup, Setup>>()
        val seen = hashSetOf(current to next)
        for ((st, ks) in path) {
            val a = st.first?.let(current::viewAs) ?: current
            val b = st.second?.let(next::viewAs) ?: next
            if (a == b || !seen.add(a to b)) continue
            val x = runs(current, st.first, false); val y = runs(next, st.second, true)
            out += Triple("On ${ks.reversed().joinToString(" after ") { where(it) }} unbraid runs ${if (x == y) "the restored ${y.removePrefix("the ")}" else "$y instead of $x"}. Compared with now there:", a, b)
        }
        val ready = { s: Setup -> s.profiles.filter { it.ready }.map { it.name } }
        for (n in ready(current).filter { it in ready(next) }) {
            val a = current.viewAs(n); val b = next.viewAs(n)
            if (a != b && seen.add(a to b)) out += Triple(Backup.safe(n, 60).let { "When you switch to the profile $it, compared with $it now:" }, a, b)
        }
        return out
    }

    /**
     * What restoring [next] over [current] makes weaker, in a few sentences: settings that are not per connection compared
     * directly (the filter, providers' blocking choices, own servers and the home tunnel by every address they connect
     * to), then what each connection gets ([found]); then the same for every other pair of setups the phone can run
     * ([pairs]), under the sentence that says when. All of it paid from one [budget].
     */
    fun check(current: Setup, next: Setup, kin: (String) -> Set<String> = { emptySet() }, budget: Budget = Budget()): List<Note> {
        val out = mutableListOf<Note>()
        val was = current.prefs.filter.server()
        val now = next.prefs.filter.server()
        val filterOff = was != null && now == null
        if (was != null && now != null && was != now) out += Note("The DNS filter changes to another server.")
        exitChanges(current, next).forEach { if (out.none { n -> n.text == it }) out += Note(it) }
        // A new install's placeholder isn't the user's: there is no traffic to compare.
        if (current.untouched) return listOfNotNull(Note(FILTER_OFF).takeIf { filterOff }) + out
        out += providerWeakens(current, next).map(::Note)
        val first = found(current, next, kin, budget)
        var cut = first?.cut == true
        val traffic = first?.let { trafficNotes(it, current, filterOff) } ?: (listOfNotNull(Note(FILTER_OFF).takeIf { filterOff }) + Note(UNKNOWN))
        out.addAll(0, traffic.filter { it.text == FILTER_OFF })
        out += traffic.filter { it.text != FILTER_OFF && it.text != CUT }
        for ((header, a, b) in pairs(current, next)) {
            val f = found(a, b, kin, budget)
            cut = cut || f?.cut == true
            val said = (f?.let { trafficNotes(it, a, filterOff) } ?: listOf(Note(UNKNOWN))).filter { it.text != FILTER_OFF && it.text != CUT }
            if (said.isNotEmpty()) { out += Note(header); out += said }
        }
        if (cut) out += Note(CUT)
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
            val who = if (n.key == "home") "Your home WireGuard" else "\"${shown(Backup.safe(n.name, 256))}\""
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
        val out = mutableListOf<String>()
        for (id in next.routes.filter { !it.imported }.map { it.loc.provider }.distinct().sorted()) {
            val p = Providers.get(id) ?: continue
            val off = { k: String -> k in p.defaults && current.opt(id, k) == "on" && next.opt(id, k) != "on" }
            // Blocking choices count only where a route of that provider blocks now.
            if (current.routes.any { !it.imported && it.loc.provider == id && current.protects(it) }) {
                EXTRA.filterKeys(off).values.toList().takeIf { it.isNotEmpty() }?.let { out += "${p.name} stops blocking ${list(it)}." }
                p.defaults["list"]?.let { d -> if (current.opt(id, "list") != d && next.opt(id, "list") == d) out += "${p.name}'s block list goes back to the standard one." }
            }
            if (off("owned")) out += "${p.name} no longer keeps to the servers it owns."
        }
        return out
    }

    /** How a loss is said of one thing and of several. */
    private val VERB = mapOf(
        Loss.ToDirect to ("goes from a VPN route to Direct" to "go from a VPN route to Direct"),
        Loss.Lookups to ("sends some DNS lookups Direct that don't go out Direct now" to "send some DNS lookups Direct that don't go out Direct now"),
        Loss.Filter to ("loses the DNS filter" to "lose the DNS filter"),
        Loss.ProviderBlocking to ("loses the provider's ad and tracker blocking it has now" to "lose the provider's ad and tracker blocking they have now"),
        Loss.Blocking to ("gets DNS blocking that blocks fewer kinds of sites than now" to "get DNS blocking that blocks fewer kinds of sites than now"),
        Loss.Udp to ("sends UDP traffic through a VPN route, where it is refused now" to "send UDP traffic through a VPN route, where it is refused now"),
    )

    /**
     * The losses in the sheet's sentences: for each kind of loss, one sentence for each kind of thing it is said for, the
     * default route, the home and the local network in their own words, apps, websites and the rest counted together;
     * then what gets weaker once a waiting route gets its config.
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
            ("Tailscale's sign-in pages" to true).takeIf { Who.SignIn in w },
            ("the local network" to false).takeIf { Who.Local in w },
            ("the home network" to false).takeIf { Who.Home in w },
        )
        fun say(w: Set<Who>, l: Loss, before: String = "", verb: (Boolean) -> String = { if (it) VERB.getValue(l).second else VERB.getValue(l).first }) {
            val p = parts(w).takeIf { it.isNotEmpty() } ?: return
            out += Note((before + list(p.map { it.first })).replaceFirstChar(Char::uppercase) + " " + verb(p.size > 1 || p[0].second) + ".", l, w)
        }
        val direct = with(Loss.ToDirect)
        if (Who.Default in direct) out += Note("The default route goes from ${current.route(current.defaultRoute)?.name ?: "a VPN"} to Direct.", Loss.ToDirect, setOf(Who.Default))
        say(direct.filterTo(HashSet()) { it is Who.App || it is Who.Site || it == Who.NoApp }, Loss.ToDirect)
        if (Who.SignIn in direct) out += Note("While Tailscale waits for you to sign in, its sign-in pages go Direct.", Loss.ToDirect, setOf(Who.SignIn))
        if (Who.Home in direct) out += Note("Part of the home network goes Direct instead of through the home tunnel.", Loss.ToDirect, setOf(Who.Home))
        if (Who.Local in direct) out += Note("Apps can reach the local network again.", Loss.ToDirect, setOf(Who.Local))

        // Refused now because its route waits for its server's config, rather than blocked by choice: said apart.
        val applied = Sites.applied(current)
        val waits = { id: String? -> current.route(id)?.let { r -> r.keyless || current.servers.any { it.id == r.own && it.keyless } } == true }
        val opened = with(Loss.Unblocked)
        // Said Direct where it then leaves outside any VPN.
        val leavesDirect = { w: Who -> Loss.ToDirect in found.losses[w].orEmpty() }
        val goes = if (leavesDirect(Who.Default)) "goes out Direct" else "goes out"
        if (Who.Default in opened) out += Note(if (waits(current.defaultRoute)) "The default route can't connect now, because its route waits for a server's config. After the restore its traffic $goes."
            else "The default route can't connect now. After the restore its traffic $goes.", Loss.Unblocked, setOf(Who.Default))
        val waiting = opened.filterTo(HashSet()) { w -> (w is Who.App && waits(current.routeOf(w.of))) || (w is Who.Site && waits(Sites.match(w.name, applied.keys)?.let(applied::get))) }
        val blockedApps = apps(opened).filterNot { it in waiting }
        val blockedSites = sites(opened).filterNot { it in waiting }
        for ((what, all) in listOf("app" to blockedApps, "website" to blockedSites)) for (d in listOf(true, false)) all.filter { leavesDirect(it) == d }.takeIf { it.isNotEmpty() }?.let { w ->
            out += Note(if (w.size == 1) "1 $what that is blocked now ${if (d) "goes Direct" else "won't be"}." else "${w.size} ${what}s that are blocked now ${if (d) "go Direct" else "won't be"}.", Loss.Unblocked, w.toSet())
        }
        for (d in listOf(true, false)) say(waiting.filterTo(HashSet()) { leavesDirect(it) == d }, Loss.Unblocked) { many ->
            "that can't connect now, because ${if (many) "their routes wait" else "its route waits"} for a server's config, ${if (many) "go" else "goes"} out${if (d) " Direct" else ""} without it" }
        // Each in a sentence of its own, said Direct where it then leaves outside any VPN.
        for (w in listOf(Who.NoApp, Who.SignIn)) if (w in opened) say(setOf(w), Loss.Unblocked) { many ->
            (if (many) "are no longer refused" else "is no longer refused") + if (!leavesDirect(w)) "" else if (many) " and go Direct" else " and goes Direct" }
        if (Who.Local in opened) out += Note("Blocked apps can reach the local network.", Loss.Unblocked, setOf(Who.Local))
        if (Who.Home in opened) out += Note("Part of the home network that is refused now won't be.", Loss.Unblocked, setOf(Who.Home))

        say(with(Loss.Lookups), Loss.Lookups)
        // Said once for all when the filter is turned off.
        val filter = with(Loss.Filter)
        if (filterOff) out += Note(FILTER_OFF, Loss.Filter, filter) else say(filter, Loss.Filter)
        for (l in listOf(Loss.ProviderBlocking, Loss.Blocking, Loss.Udp)) say(with(l), l)
        // What gets weaker once a route waiting for its config gets it, where it is worse than what is said for now.
        val later = found.later.filterValues { it.isNotEmpty() }
        val then = later.mapValues { (_, l) -> l.minBy { it.ordinal } }.filter { (w, l) -> found.losses[w].orEmpty().none { it <= l } }
        val again = "once a server's config is added again, "
        for (d in listOf(true, false)) say(then.filter { (w, l) -> l == Loss.Unblocked && (Loss.ToDirect in later.getValue(w)) == d }.keys, Loss.Unblocked, again) { many ->
            (if (many) "are no longer blocked" else "is no longer blocked") + if (!d) "" else if (many) " and go Direct" else " and goes Direct" }
        for (l in VERB.keys) say(then.filterValues { it == l }.keys, l, again)
        if (found.cut) out += Note(CUT)
        return out
    }
}
