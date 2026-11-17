package app.unbraid

import app.unbraid.data.BLOCK
import app.unbraid.data.DIRECT
import app.unbraid.data.Filter
import app.unbraid.data.Home
import app.unbraid.data.Location
import app.unbraid.data.OwnServer
import app.unbraid.data.Prefs
import app.unbraid.data.RestoreTraffic
import app.unbraid.data.RestoreTraffic.By
import app.unbraid.data.RestoreTraffic.Loss
import app.unbraid.data.RestoreTraffic.Outcome
import app.unbraid.data.RestoreTraffic.Resolver
import app.unbraid.data.RestoreTraffic.Who
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.data.Sites
import app.unbraid.data.SsProfile
import app.unbraid.data.VERSION
import app.unbraid.data.WgConf
import app.unbraid.data.withoutSecrets
import app.unbraid.vpn.Compiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The restore check against an oracle that never reads a config: what each connection gets is worked out here from the
 * setup, by the rules the compiler states (Block first, Android's left-out apps, the local network and home ranges,
 * websites before apps, the default last; lockdown keeps Direct apps inside), for concrete apps, names, addresses,
 * protocols and question types, with lockdown off and on, routes waiting for their config with and without it. The
 * order of outcomes is this file's own, part by part. Then, for random pairs of setups: every connection that gets
 * weaker is said for what it is said for, and nothing is said that no connection backs.
 */
class RestoreOracleTest {
    private fun wg(n: Int, allowed: String = "0.0.0.0/0") = WgConf.parse("[Interface]\nPrivateKey = ${('A' + n).toString().repeat(42)}E=\nAddress = 10.9.$n.2/32\n" +
        "[Peer]\nPublicKey = ${('B' + n).toString().repeat(42)}E=\nEndpoint = 203.0.113.$n:51820\nAllowedIPs = $allowed\n", "Box $n")
    private val keyless = OwnServer("k", wg = wg(5).withoutSecrets(), keyless = true)
    /** A Shadowsocks server behind a plugin, which carries TCP only. */
    private val plugged = SsProfile("Plugged", "203.0.113.30", 8388, "aes-256-gcm", "pw", "obfs-local", "obfs=http")
    private val base = Setup(routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands")), Route("m1", Location("mullvad", countryCode = "SE", country = "Sweden")),
        Route("w", wg = wg(1), own = "s"), Route("k1", wg = keyless.wg, own = "k", keyless = true), Route("ss", ss = plugged, own = "x")), defaultRoute = "nl",
        servers = listOf(OwnServer("s", wg = wg(1)), keyless, OwnServer("x", ss = plugged)), version = VERSION)

    private val appKeys = (0 until 5).map { "org.example.a$it" }
    /** Shares its Android user id with the first app, and has no choice of its own. */
    private val KIN = "org.example.kin"
    private val kin = { p: String -> if (p == appKeys[0] || p == KIN) setOf(appKeys[0], KIN) else emptySet() }
    private val siteNames = listOf("n.example", "b.n.example", "o.example", "x.o.example", "s.example")
    private val homes = listOf(null, listOf("10.20.0.0/16"), listOf("10.0.0.0/8"), listOf("10.0.0.0/9", "10.192.0.0/10"), listOf("192.168.1.0/24"))
    private val lan = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "224.0.0.0/4", "255.255.255.255/32")
    private val localSuffixes = listOf("local", "lan", "home.arpa", "fritz.box")
    /** What each resolver blocks, as the app describes it. */
    private val covers = mapOf("quad9" to setOf("malware"), "adguard" to setOf("ads", "trackers"), "own" to setOf("?"), "nord" to setOf("ads", "malware"), "mullvad" to setOf("ads", "trackers", "malware"))

    // ---- the oracle ----

    private val OTHER = "org.example.none"
    private val OWNERLESS = ""
    private val PING = "ping"
    private val REJECT = RestoreTraffic.REJECT
    private class Step(val out: Outcome, val by: By, val rby: By)

    private fun within(ip: String, cidr: String) = Compiler.ipv4(cidr)!!.let { (a, l) -> l == 0 || (a xor Compiler.ipv4(ip)!!.first) shr (32 - l) == 0L }
    private fun under(n: String, s: String) = n == s || n.endsWith(".$s")

    /** The setup, read as the compiler states it decides. [given]: routes waiting for their config have it. */
    private inner class Model(val s: Setup, val lockdown: Boolean, val given: Boolean = false) {
        val f = s.prefs.filter
        val filter = f.server() != null
        val barred = s.apps.filterValues { it == BLOCK }.keys
        val refused = { id: String -> id == BLOCK || (!given && s.route(id)?.keyless == true) }
        val through = { app: String -> s.apps[app] == DIRECT && f.through(app) }
        val directFiltered = filter && f.direct && s.defaultRoute == DIRECT
        val defaultBlocked = s.defaultRoute != DIRECT && refused(s.defaultRoute)
        val home = s.home.wg?.let(Compiler::homeCidrs).orEmpty()
        /** A filter at an address of the home network is asked through the home tunnel. */
        val filterVia = f.server()?.ip?.takeIf { a -> Compiler.ipv4(a) != null && home.any { within(a, it) } }?.let { "home-wg" }
        /** An app's own group of rules, null when it has none (it follows the default). */
        fun group(app: String): String? = s.apps[app]?.let { r -> if (through(app)) "filtered" else r }?.takeIf { g -> g != s.defaultRoute && g != BLOCK && (g != DIRECT || lockdown) }
        val vpnApps = s.apps.keys.any { group(it).let { g -> g != null && g != DIRECT && g != "filtered" } }
        val anyGroupNotDirect = s.apps.keys.any { group(it).let { g -> g != null && g != DIRECT } }
        val lanOut = s.prefs.lan && !lockdown && (barred.isEmpty() || !s.prefs.blockLan)
        fun exit(id: String) = if (id == DIRECT || id == "filtered") "direct" else "r-$id"
        fun kind(id: String): Resolver = when {
            id == "filtered" -> Resolver.Filter
            id == DIRECT -> Resolver.Local
            filter && f.routes -> Resolver.Filter
            s.protects(s.route(id)!!) -> Resolver.Provider
            else -> Resolver.Route
        }
        /** Who answers for route [id] as [kind] says: the way its questions go out and what it blocks. */
        fun answer(id: String, k: Resolver): Outcome = when (k) {
            Resolver.Filter -> Outcome(filterVia ?: exit(id), k, blocks = covers.getValue(f.id!!))
            Resolver.Provider -> Outcome(exit(id), k, blocks = covers.getValue(s.route(id)!!.loc.provider))
            Resolver.Local -> Outcome("direct", k)
            else -> Outcome(exit(id), k)
        }
        fun siteKind(id: String) = if (id == DIRECT && filter && (directFiltered || s.apps.keys.any(through))) Resolver.Filter else kind(id)
        val defaultKind = if (directFiltered && !defaultBlocked) Resolver.Filter else if (s.defaultRoute == DIRECT || defaultBlocked) Resolver.Local else kind(s.defaultRoute)
        val sites = Sites.applied(s)
        fun site(n: String) = Sites.match(n, sites.keys.filter { sites[it] != BLOCK })
        fun blockedSite(n: String) = sites.any { (k, v) -> v == BLOCK && under(n, k) }
        fun local(n: String) = s.prefs.lan && localSuffixes.any { under(n, it) }
        fun leftOut(p: String) = !lockdown && s.apps[p] == DIRECT && !through(p)

        /** The address rules in front of every website and app: the home ranges, then the local network. */
        fun network(ip: String): String? = when {
            home.any { within(ip, it) } -> "home-wg"
            s.prefs.lan && lan.any { within(ip, it) } -> "direct"
            else -> null
        }

        /** A connection by [net]: UDP goes as TCP does, but an exit behind a Shadowsocks plugin refuses it. */
        fun connect(p: String, name: String?, ip: String, net: String = "tcp"): Step = tcp(p, name, ip).let { st ->
            if (net == "udp" && st.out.exit == "r-ss" && s.route("ss")!!.ss!!.plugin != null) Step(Outcome(RestoreTraffic.TCP_ONLY), st.by, st.rby) else st
        }

        private fun tcp(p: String, name: String?, ip: String): Step {
            val app = p.takeIf { it != OWNERLESS && it != PING }
            if (app != null && leftOut(app)) return Step(if (name == null) Outcome("direct") else Outcome("direct", Resolver.Local, "direct"), By.AppFirst, By.AppFirst)
            var real = name == null
            var ans: Outcome? = null
            var rby = By.None
            if (name != null) when {
                app in barred -> return Step(Outcome(REJECT), By.AppFirst, By.AppFirst)
                blockedSite(name) -> return Step(Outcome(REJECT), By.Name, By.Name)
                local(name) -> { real = true; ans = Outcome("direct", Resolver.Local); rby = By.Name }
            }
            val named = name != null && !real
            val out = { tag: String, by: By -> Step(Outcome(tag, ans?.resolver ?: if (name == null) null else Resolver.Local, ans?.exit ?: if (name == null) null else "direct", ans?.blocks.orEmpty()), by, rby) }
            // Android leaves the local network out of the tunnel (its home ranges stay in).
            if (real && lanOut && lan.any { within(ip, it) } && home.none { within(ip, it) }) return out("direct", By.Address)
            if (app in barred) return Step(Outcome(REJECT), By.AppFirst, By.AppFirst)
            if (barred.isNotEmpty() && p == PING) return Step(Outcome(RestoreTraffic.HELD), By.Address, By.Address)
            if (barred.isNotEmpty() && app == null) return Step(Outcome(RestoreTraffic.HELD), By.App, By.App)
            if (real) network(ip)?.let { return out(it, By.Address) }
            // The name's website, then the app's own route, then the default; each looked up, then the address rules.
            val resolve = { o: Outcome, by: By -> if (named) { ans = o; rby = by; real = true } }
            val siteId = name?.takeIf { named }?.let(::site)?.let { sites.getValue(it) }
            if (siteId != null) {
                if (refused(siteId)) return Step(Outcome(REJECT), By.Name, By.Name)
                resolve(answer(siteId, siteKind(siteId)), By.Name)
                network(ip)?.let { return out(it, By.Address) }
                return out(exit(siteId), By.Name)
            }
            app?.let(::group)?.let { g ->
                if (refused(g)) return Step(Outcome(REJECT), By.App, By.App)
                resolve(answer(g, kind(g)), By.App)
                network(ip)?.let { return out(it, By.Address) }
                return out(exit(g), By.App)
            }
            if (defaultBlocked) return Step(Outcome(REJECT), By.None, By.None)
            if (s.defaultRoute == DIRECT && vpnApps && app == null) return Step(Outcome(RestoreTraffic.HELD), By.App, By.App)
            resolve(answer(s.defaultRoute, defaultKind), By.None)
            network(ip)?.let { return out(it, By.Address) }
            if (p == PING && s.defaultRoute == DIRECT && anyGroupNotDirect) return Step(Outcome(RestoreTraffic.HELD), By.Address, By.Address)
            return out(exit(s.defaultRoute), By.None)
        }

        /** A lookup of question [type] alone: refused, answered empty, or by whom through which exit. */
        fun lookup(p: String, name: String, type: String): Step {
            val app = p.takeIf { it != OWNERLESS }
            if (app != null && leftOut(app)) return Step(Outcome("direct", Resolver.Local), By.AppFirst, By.AppFirst)
            val step = { o: Outcome, by: By -> Step(o, by, by) }
            if (app in barred) return step(Outcome(REJECT), By.AppFirst)
            if (blockedSite(name)) return step(Outcome(REJECT), By.Name)
            if (local(name)) return step(Outcome("direct", Resolver.Local), By.Name)
            site(name)?.let { sites.getValue(it) }?.let { id ->
                if (refused(id)) return step(Outcome(REJECT), By.Name)
                return step(if (type == "HTTPS") Outcome(RestoreTraffic.EMPTY) else answer(id, siteKind(id)), By.Name)
            }
            app?.let(::group)?.let { g -> return step(if (refused(g)) Outcome(REJECT) else answer(g, kind(g)), By.App) }
            if (defaultBlocked) return step(Outcome(REJECT), By.None)
            return step(answer(s.defaultRoute, defaultKind), By.None)
        }
    }

    // ---- this file's own order of outcomes, part by part ----

    private fun height(exit: String) = if (exit == REJECT || exit == RestoreTraffic.TCP_ONLY || exit == RestoreTraffic.EMPTY) 2 else if (exit == "direct") 0 else 1
    private fun guard(r: Resolver?) = if (r == Resolver.Filter) 2 else if (r == Resolver.Provider) 1 else 0

    private fun weaker(a: Outcome, b: Outcome, lookup: Boolean): Set<Loss> {
        if (RestoreTraffic.HELD in listOf(a.exit, b.exit)) return emptySet()
        // A question answered on the phone loses only by being sent out Direct.
        if (RestoreTraffic.EMPTY in listOf(a.exit, b.exit)) return if (a.exit == RestoreTraffic.EMPTY && b.exit == "direct") setOf(Loss.Lookups) else emptySet()
        val l = mutableSetOf<Loss>()
        if (height(b.exit) < height(a.exit)) l += if (a.exit == RestoreTraffic.TCP_ONLY) { if (b.exit == "direct") Loss.Unblocked else Loss.Udp }
            else if (a.exit == REJECT) Loss.Unblocked else if (lookup) Loss.Lookups else Loss.ToDirect
        // Refused before, out on the phone's own connection after: Direct too.
        if (!lookup && height(a.exit) == 2 && b.exit == "direct") l += Loss.ToDirect
        if (height(a.exit) == 2 || height(b.exit) == 2) return l
        if (!lookup && a.via != null && a.via != "direct" && b.via == "direct") l += Loss.Lookups
        if (guard(b.resolver) < guard(a.resolver)) l += if (a.resolver == Resolver.Filter) Loss.Filter else Loss.ProviderBlocking
        else if (a.blocks.any { it !in b.blocks }) l += Loss.Blocking
        return l
    }

    private class Duo(val was: Step, val now: Step, val losses: Set<Loss>, val later: Boolean = false)

    private fun duo(a: Setup, b: Setup, l: Boolean, lookup: Boolean = false, f: (Model) -> Step): Duo {
        val x = f(Model(a, l)); val y = f(Model(b, l))
        if (x.out.exit != REJECT || y.out.exit != REJECT) return Duo(x, y, weaker(x.out, y.out, lookup))
        val gx = f(Model(a, l, true)); val gy = f(Model(b, l, true))
        if (gx.out.exit == REJECT && gy.out.exit != REJECT) return Duo(x, y, setOf(Loss.Unblocked))
        return Duo(gx, gy, weaker(gx.out, gy.out, lookup), later = true)
    }

    // The spec of what a loss is said for, as the restore sheet's model states it.
    private fun alike(a: Duo, b: Duo, l: Loss?) = when (l) {
        Loss.Unblocked, Loss.ToDirect, Loss.Udp -> a.was.out.exit == b.was.out.exit && a.now.out.exit == b.now.out.exit
        Loss.Lookups -> (a.was.out.via ?: a.was.out.exit) == (b.was.out.via ?: b.was.out.exit) && (a.now.out.via ?: a.now.out.exit) == (b.now.out.via ?: b.now.out.exit)
        else -> a.was.out == b.was.out && a.now.out == b.now.out
    }

    private fun entity(p: String, own: Duo, dflt: Duo): Set<Who> = when (p) {
        OTHER -> setOf(Who.Default)
        OWNERLESS -> if (alike(own, dflt, own.losses.minOrNull())) setOf(Who.Default) else setOf(Who.NoApp)
        PING -> if (own.was.out.exit == dflt.was.out.exit && own.now.out.exit == dflt.now.out.exit) setOf(Who.Default) else setOf(Who.NoApp)
        // The app and the one sharing its user id go together.
        else -> if (setOf(own.was.by, own.now.by, own.was.rby, own.now.rby).any { it == By.App || it == By.AppFirst }) kin(p).map { Who.App(it, p) }.toSet() + Who.App(p) else setOf(Who.Default)
    }

    private fun who(name: String?, place: Who?, d: Duo, own: Duo, pe: Set<Who>, sites: Set<String>, loss: Loss): Set<Who> {
        val l = if (loss == Loss.ToDirect && Loss.Unblocked in d.losses) Loss.Unblocked else loss
        if (alike(d, own, l)) return pe
        if (d.now.by == By.AppFirst && d.now.out.exit == own.now.out.exit) return pe
        val names = { setOf(Sites.match(name!!, sites)?.let { Who.Site(it) } ?: Who.Local) }
        if (l == Loss.Unblocked) return when (d.was.by) {
            By.Name -> if (name != null) names() else pe
            By.App, By.AppFirst -> if (l in own.losses) pe else place?.let(::setOf) ?: pe
            By.Address -> place?.let(::setOf) ?: pe
            By.None -> pe
        }
        val resolver = l in setOf(Loss.Lookups, Loss.Filter, Loss.ProviderBlocking, Loss.Blocking)
        val was = if (resolver) d.was.rby else d.was.by
        val now = if (resolver) d.now.rby else d.now.by
        if (l in own.losses && was == By.AppFirst && d.was.out.exit == own.was.out.exit) return pe
        val at = place.takeIf { !resolver }
        if ((was == By.Address || now == By.Address) && at != null && d.was.rby == d.now.rby) return setOf(at)
        if (name != null && By.Name in listOf(was, now, d.was.rby, d.now.rby)) return names()
        return at?.let(::setOf) ?: pe
    }

    private val addresses = listOf("203.0.113.80", "192.168.1.20", "192.168.1.1", "192.168.1.53", "192.168.200.1", "172.16.0.5", "169.254.1.1", "224.0.0.251", "255.255.255.255",
        "10.0.0.1", "10.20.0.1", "10.20.255.254", "10.21.0.1", "10.127.255.254", "10.128.0.1", "10.140.0.7", "10.191.255.254", "10.192.0.1", "10.255.255.254", "100.64.0.1", "8.8.8.8")

    private class Expected(val losses: Map<Who, Set<Loss>>, val later: Map<Who, Set<Loss>>, val leaving: Map<Who, Loss?>)

    /** Every concrete connection's losses, by what they are said for, now and once waiting routes get their config; and the apps that leave unbraid. */
    private fun expected(a: Setup, b: Setup): Expected {
        val out = HashMap<Who, MutableSet<Loss>>()
        val then = HashMap<Who, MutableSet<Loss>>()
        val leaving = HashMap<Who, Loss?>()
        val add = { w: Set<Who>, d: Duo, l: Loss -> w.forEach { (if (d.later) then else out).getOrPut(it) { mutableSetOf() } += l } }
        val sites = Sites.applied(a).keys + Sites.applied(b).keys
        val names = siteNames.flatMap { listOf(it, "zz.$it") } + "free.test" + "printer.lan" + "x.home.arpa"
        val homeRanges = listOf(a, b).flatMap { s -> s.home.wg?.let(Compiler::homeCidrs).orEmpty() }
        val localOn = a.prefs.lan || b.prefs.lan
        val place = { ip: String -> if (homeRanges.any { within(ip, it) }) Who.Home else if (localOn && lan.any { within(ip, it) }) Who.Local else null }
        for (l in listOf(false, true)) {
            val dflt = duo(a, b, l) { it.connect(OTHER, "free.test", "203.0.113.80") }
            val dfltLookup = listOf("TXT", "HTTPS").associateWith { q -> duo(a, b, l, true) { it.lookup(OTHER, "free.test", q) } }
            for (p in listOf(OTHER, OWNERLESS, PING) + appKeys) {
                val ownName = if (p == PING) null else "free.test"
                val own = duo(a, b, l) { it.connect(p, ownName, "203.0.113.80") }
                val pe = entity(p, own, dflt)
                own.losses.forEach { add(pe, own, it) }
                val left = { s: Step -> s.by == By.AppFirst && s.out.exit == "direct" }
                if (left(own.now) && !left(own.was)) pe.filterIsInstance<Who.App>().forEach { leaving[it] = own.losses.minOrNull() }
                for (net in if (p == PING) listOf("icmp") else listOf("tcp", "udp"))
                    for (n in listOf<String?>(null) + if (p == PING) emptyList() else names) for (ip in addresses) {
                        if (net != "udp" && n == ownName && ip == "203.0.113.80") continue
                        val d = duo(a, b, l) { it.connect(p, n, ip, net) }
                        d.losses.forEach { x -> if (!(x == Loss.ToDirect && Loss.Unblocked in d.losses && d.now.by == By.Address)) add(who(n, place(ip), d, own, pe, sites, x), d, x) }
                    }
                if (p != PING) for (q in listOf("TXT", "HTTPS")) {
                    val ownL = duo(a, b, l, true) { it.lookup(p, "free.test", q) }
                    val peL = entity(p, ownL, dfltLookup.getValue(q))
                    ownL.losses.forEach { add(peL, ownL, it) }
                    for (n in names - "free.test") duo(a, b, l, true) { it.lookup(p, n, q) }.let { d -> d.losses.forEach { x -> add(who(n, null, d, ownL, peL, sites, x), d, x) } }
                }
            }
        }
        return Expected(out, then, leaving.filterKeys { it in out })
    }

    // ---- random setups and restores ----

    private fun random(g: Random): Setup {
        fun <T> pick(l: List<T>) = l[g.nextInt(l.size)]
        val ids = listOf("nl", "m1", "w", "k1", "ss", DIRECT, BLOCK)
        val filter = if (g.nextBoolean()) Filter() else Filter(pick(listOf("quad9", "adguard", "own")), "192.168.1.53", routes = g.nextBoolean(), direct = g.nextBoolean(),
            directApps = g.nextBoolean(), apps = appKeys.filter { g.nextInt(10) < 3 }.associateWith { g.nextBoolean() })
        return base.copy(
            routes = base.routes.map { if (it.id == "nl" || it.id == "m1") it.copy(filter = pick(listOf(null, true, false))) else it },
            defaultRoute = pick(listOf("nl", "m1", "w", "k1", "ss", DIRECT, DIRECT)),
            apps = appKeys.filter { g.nextInt(10) < 6 }.associateWith { pick(ids) },
            sites = siteNames.filter { g.nextBoolean() }.associateWith { pick(ids + BLOCK) },
            prefs = Prefs(filter = filter, lan = g.nextInt(10) < 6, blockLan = g.nextBoolean()),
            home = pick(homes)?.let { Home(wg = wg(7, it.joinToString(", ")).copy(name = "Home")) } ?: Home(),
            opts = mapOf("nord" to mapOf("protect" to pick(listOf("on", "off"))), "mullvad" to mapOf("protect" to pick(listOf("on", "off")))),
        )
    }

    private fun change(g: Random, s: Setup): Setup {
        fun <T> pick(l: List<T>) = l[g.nextInt(l.size)]
        val ids = listOf("nl", "m1", "w", "k1", "ss", DIRECT, BLOCK, null)
        return when (g.nextInt(11)) {
            0 -> s.copy(defaultRoute = pick(listOf("nl", "m1", "w", "k1", "ss", DIRECT)))
            1 -> pick(appKeys).let { k -> pick(ids).let { v -> s.copy(apps = if (v == null) s.apps - k else s.apps + (k to v)) } }
            2 -> pick(siteNames).let { n -> pick(ids).let { v -> s.copy(sites = if (v == null) s.sites - n else s.sites + (n to v)) } }
            3 -> s.copy(prefs = s.prefs.copy(filter = s.prefs.filter.copy(directApps = !s.prefs.filter.directApps, direct = g.nextBoolean(), routes = g.nextBoolean())))
            4 -> pick(appKeys).let { k -> pick(listOf(true, false, null)).let { v -> s.copy(prefs = s.prefs.copy(filter = s.prefs.filter.copy(apps = if (v == null) s.prefs.filter.apps - k else s.prefs.filter.apps + (k to v)))) } }
            5 -> s.copy(opts = mapOf("nord" to mapOf("protect" to pick(listOf("on", "off"))), "mullvad" to mapOf("protect" to pick(listOf("on", "off")))))
            6 -> s.copy(prefs = s.prefs.copy(lan = !s.prefs.lan))
            7 -> s.copy(prefs = s.prefs.copy(blockLan = !s.prefs.blockLan))
            8 -> s.copy(home = pick(homes)?.let { Home(wg = wg(7, it.joinToString(", ")).copy(name = "Home")) } ?: Home())
            9 -> s.copy(prefs = s.prefs.copy(filter = s.prefs.filter.copy(id = pick(listOf("quad9", "adguard", "own")), value = "192.168.1.53")))
            else -> s.copy(prefs = s.prefs.copy(filter = Filter()))
        }
    }

    /** One pair: the walk's findings against the oracle's, and every finding has a sentence that names it. */
    private fun agree(a: Setup, b: Setup, where: String): Boolean {
        val want = expected(a, b)
        val got = RestoreTraffic.found(a, b, kin)!!
        assertEquals(where, want.losses, got.losses)
        assertEquals(where, want.later, got.later)
        assertEquals(where, want.leaving, got.leaving)
        val notes = RestoreTraffic.trafficNotes(got, a, filterOff = false)
        for (w in got.losses.keys + got.later.keys) assertTrue("$where: $w unsaid in $notes", notes.any { w in it.who })
        for (n in notes) assertTrue("$where: ${n.text} backs nothing", n.text == RestoreTraffic.CUT || n.who.isNotEmpty() && n.who.all { it in got.losses || it in got.later })
        return want.losses.isNotEmpty() || want.later.isNotEmpty()
    }

    @Test fun everyWeakerConnectionIsSaidAndNothingElse() {
        val g = Random(0x0AC1E)
        var weaker = 0; var same = 0
        val t = System.nanoTime()
        repeat(400) { i ->
            val a = random(g)
            var b = a
            repeat(1 + g.nextInt(3)) { b = change(g, b) }
            val where = "$i: ${a.defaultRoute} ${a.apps} ${a.sites} ${a.prefs} ${a.home.wg?.allowedIps} -> ${b.defaultRoute} ${b.apps} ${b.sites} ${b.prefs} ${b.home.wg?.allowedIps}"
            if (agree(a, b, where)) weaker++ else same++
        }
        println("RESTORE-ORACLE pairs: weaker=$weaker same=$same in ${(System.nanoTime() - t) / 1_000_000} ms")
        assertTrue("$weaker, $same", weaker > 150 && same > 40)
    }

    /** Each case a round of review found, as the pair the oracle judges: each failed before its fix. */
    @Test fun theCasesReviewFoundAgree() {
        val q = { id: String, routes: Boolean -> Prefs(filter = Filter(id, "192.168.1.53", routes = routes)) }
        val home = Home(wg = wg(7, "192.168.1.0/24").copy(name = "Home"))
        val cases = listOf(
            // Provider blocking replaced by a filter that blocks less.
            base.copy(opts = mapOf("nord" to mapOf("protect" to "on"))) to base.copy(opts = mapOf("nord" to mapOf("protect" to "on")), prefs = q("quad9", true)),
            // A filter lost once a waiting route gets its config.
            base.copy(defaultRoute = "k1", prefs = q("adguard", true)) to base.copy(defaultRoute = "k1", prefs = Prefs(filter = Filter("adguard"))),
            // The filter at home asked Direct once the home tunnel is gone: the lookups, not the default route.
            base.copy(defaultRoute = DIRECT, home = home, prefs = Prefs(filter = Filter("own", "192.168.1.53", direct = true))) to base.copy(defaultRoute = DIRECT, prefs = Prefs(filter = Filter("own", "192.168.1.53", direct = true))),
            // A website's HTTPS questions answered on the phone, then sent out.
            base.copy(defaultRoute = DIRECT, sites = mapOf("s.example" to DIRECT)) to base.copy(defaultRoute = DIRECT),
            // UDP refused by a plugin's exit, then carried.
            base.copy(apps = mapOf(appKeys[1] to "ss")) to base.copy(apps = mapOf(appKeys[1] to "w")),
            // An app sharing its user id goes with the app.
            base.copy() to base.copy(apps = mapOf(appKeys[0] to DIRECT)),
            // A website that could not be reached, blocked or on a route waiting for its config, goes Direct.
            base.copy(sites = mapOf("s.example" to BLOCK)) to base.copy(sites = mapOf("s.example" to DIRECT)),
            base.copy(sites = mapOf("s.example" to "k1")) to base.copy(sites = mapOf("s.example" to DIRECT)),
        )
        val want = listOf(Loss.Blocking, Loss.Filter, Loss.Lookups, Loss.Lookups, Loss.Udp, Loss.ToDirect, Loss.ToDirect, Loss.ToDirect)
        for ((i, c) in cases.withIndex()) {
            assertTrue("case $i", agree(c.first, c.second, "case $i"))
            val got = RestoreTraffic.found(c.first, c.second, kin)!!
            assertTrue("case $i: $got", (got.losses.values + got.later.values).any { want[i] in it })
        }
        assertEquals(setOf(Who.App(appKeys[0]), Who.App(KIN, appKeys[0])), RestoreTraffic.found(cases[5].first, cases[5].second, kin)!!.losses.keys.filterIsInstance<Who.App>().toSet())
    }
}
