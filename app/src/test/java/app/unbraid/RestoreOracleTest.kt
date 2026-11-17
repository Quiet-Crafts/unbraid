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
 * websites before apps, the default last; lockdown keeps Direct apps inside), for concrete apps, names, addresses and
 * question types, with lockdown off and on. Then, for random pairs of setups: every connection that gets weaker is said
 * for what it is said for, and nothing is said that no connection backs.
 */
class RestoreOracleTest {
    private fun wg(n: Int, allowed: String = "0.0.0.0/0") = WgConf.parse("[Interface]\nPrivateKey = ${('A' + n).toString().repeat(42)}E=\nAddress = 10.9.$n.2/32\n" +
        "[Peer]\nPublicKey = ${('B' + n).toString().repeat(42)}E=\nEndpoint = 203.0.113.$n:51820\nAllowedIPs = $allowed\n", "Box $n")
    private val keyless = OwnServer("k", wg = wg(5).withoutSecrets(), keyless = true)
    private val base = Setup(routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands")), Route("m1", Location("mullvad", countryCode = "SE", country = "Sweden")),
        Route("w", wg = wg(1), own = "s"), Route("k1", wg = keyless.wg, own = "k", keyless = true)), defaultRoute = "nl",
        servers = listOf(OwnServer("s", wg = wg(1)), keyless), version = VERSION)

    private val appKeys = (0 until 5).map { "org.example.a$it" }
    private val siteNames = listOf("n.example", "b.n.example", "o.example", "x.o.example", "s.example")
    private val homes = listOf(null, listOf("10.20.0.0/16"), listOf("10.0.0.0/8"), listOf("10.0.0.0/9", "10.192.0.0/10"), listOf("192.168.1.0/24"))
    private val lan = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "224.0.0.0/4", "255.255.255.255/32")
    private val localSuffixes = listOf("local", "lan", "home.arpa", "fritz.box")

    // ---- the oracle ----

    private val OTHER = "org.example.none"
    private val OWNERLESS = ""
    private val PING = "ping"
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

        fun connect(p: String, name: String?, ip: String): Step {
            val app = p.takeIf { it != OWNERLESS && it != PING }
            if (app != null && leftOut(app)) return Step(Outcome("direct", Resolver.Local.takeIf { name != null }), By.AppFirst, By.AppFirst)
            var real = name == null
            var res: Resolver? = null
            var rby = By.None
            if (name != null) when {
                app in barred -> return Step(Outcome(RestoreTraffic.REJECT), By.AppFirst, By.AppFirst)
                blockedSite(name) -> return Step(Outcome(RestoreTraffic.REJECT), By.Name, By.Name)
                local(name) -> { real = true; res = Resolver.Local; rby = By.Name }
            }
            val named = name != null && !real
            val out = { tag: String, by: By -> Step(Outcome(tag, if (name == null) null else res ?: Resolver.Local), by, rby) }
            // Android leaves the local network out of the tunnel (its home ranges stay in).
            if (real && lanOut && lan.any { within(ip, it) } && home.none { within(ip, it) }) return out("direct", By.Address)
            if (app in barred) return Step(Outcome(RestoreTraffic.REJECT), By.AppFirst, By.AppFirst)
            if (barred.isNotEmpty() && p == PING) return Step(Outcome(RestoreTraffic.HELD), By.Address, By.Address)
            if (barred.isNotEmpty() && app == null) return Step(Outcome(RestoreTraffic.HELD), By.App, By.App)
            if (real) network(ip)?.let { return out(it, By.Address) }
            // The name's website, then the app's own route, then the default; each looked up, then the address rules.
            val resolve = { r: Resolver, by: By -> if (named) { res = r; rby = by; real = true } }
            val siteId = name?.takeIf { named }?.let(::site)?.let { sites.getValue(it) }
            if (siteId != null) {
                if (refused(siteId)) return Step(Outcome(RestoreTraffic.REJECT), By.Name, By.Name)
                resolve(siteKind(siteId), By.Name)
                network(ip)?.let { return out(it, By.Address) }
                return out(exit(siteId), By.Name)
            }
            app?.let(::group)?.let { g ->
                if (refused(g)) return Step(Outcome(RestoreTraffic.REJECT), By.App, By.App)
                resolve(if (g == "filtered") Resolver.Filter else kind(g), By.App)
                network(ip)?.let { return out(it, By.Address) }
                return out(exit(g), By.App)
            }
            if (defaultBlocked) return Step(Outcome(RestoreTraffic.REJECT), By.None, By.None)
            if (s.defaultRoute == DIRECT && vpnApps && app == null) return Step(Outcome(RestoreTraffic.HELD), By.App, By.App)
            resolve(defaultKind, By.None)
            network(ip)?.let { return out(it, By.Address) }
            if (p == PING && s.defaultRoute == DIRECT && anyGroupNotDirect) return Step(Outcome(RestoreTraffic.HELD), By.Address, By.Address)
            return out(exit(s.defaultRoute), By.None)
        }

        /** A lookup of question [type] alone: refused, answered empty, or by whom through which exit. */
        fun lookup(p: String, name: String, type: String): Step {
            val app = p.takeIf { it != OWNERLESS }
            if (app != null && leftOut(app)) return Step(Outcome("direct", Resolver.Local), By.AppFirst, By.AppFirst)
            val step = { o: Outcome, by: By -> Step(o, by, by) }
            if (app in barred) return step(Outcome(RestoreTraffic.REJECT), By.AppFirst)
            if (blockedSite(name)) return step(Outcome(RestoreTraffic.REJECT), By.Name)
            if (local(name)) return step(Outcome("direct", Resolver.Local), By.Name)
            site(name)?.let { sites.getValue(it) }?.let { id ->
                if (refused(id)) return step(Outcome(RestoreTraffic.REJECT), By.Name)
                return step(if (type == "HTTPS") Outcome(RestoreTraffic.EMPTY) else Outcome(exit(id), siteKind(id)), By.Name)
            }
            app?.let(::group)?.let { g -> return step(if (refused(g)) Outcome(RestoreTraffic.REJECT) else Outcome(exit(g), if (g == "filtered") Resolver.Filter else kind(g)), By.App) }
            if (defaultBlocked) return step(Outcome(RestoreTraffic.REJECT), By.None)
            return Step(Outcome(exit(s.defaultRoute), defaultKind), By.None, By.None)
        }
    }

    private class Duo(val was: Step, val now: Step, val loss: Loss?)

    private fun duo(a: Setup, b: Setup, l: Boolean, f: (Model) -> Step): Duo {
        val x = f(Model(a, l)); val y = f(Model(b, l))
        val waits = listOf(a, b).any { s -> s.routes.any { it.keyless } }
        if (!waits || x.out.exit != RestoreTraffic.REJECT || y.out.exit != RestoreTraffic.REJECT) return Duo(x, y, RestoreTraffic.loss(x.out, y.out))
        val gx = f(Model(a, l, true)); val gy = f(Model(b, l, true))
        return Duo(x, y, Loss.Unblocked.takeIf { gx.out.exit == RestoreTraffic.REJECT && gy.out.exit != RestoreTraffic.REJECT })
    }

    // The spec of what a loss is said for, as the restore sheet's model states it.
    private fun alike(a: Duo, b: Duo, l: Loss?) = if (l == Loss.Unblocked || l == Loss.ToDirect) a.was.out.exit == b.was.out.exit && a.now.out.exit == b.now.out.exit
        else a.was.out == b.was.out && a.now.out == b.now.out

    private fun entity(p: String, own: Duo, dflt: Duo): Set<Who> = when (p) {
        OTHER -> setOf(Who.Default)
        OWNERLESS, PING -> if (alike(own, dflt, own.loss)) setOf(Who.Default) else setOf(Who.NoApp)
        else -> if (setOf(own.was.by, own.now.by, own.was.rby, own.now.rby).any { it == By.App || it == By.AppFirst }) setOf(Who.App(p)) else setOf(Who.Default)
    }

    private fun who(name: String?, place: Who?, d: Duo, own: Duo, pe: Set<Who>, sites: Set<String>): Set<Who> {
        if (alike(d, own, d.loss)) return pe
        if (d.now.by == By.AppFirst && d.now.out.exit == own.now.out.exit) return pe
        val home = d.was.out.resolver == Resolver.Home || d.now.out.resolver == Resolver.Home
        val names = { setOf(Sites.match(name!!, sites)?.let { Who.Site(it) } ?: if (home) Who.Home else Who.Local) }
        if (d.loss == Loss.Unblocked) return when (d.was.by) {
            By.Name -> if (name != null) names() else pe
            By.App, By.AppFirst -> if (d.loss == own.loss) pe else place?.let(::setOf) ?: pe
            By.Address -> place?.let(::setOf) ?: pe
            By.None -> pe
        }
        val resolver = d.loss == Loss.Filter || d.loss == Loss.ProviderBlocking
        val was = if (resolver) d.was.rby else d.was.by
        val now = if (resolver) d.now.rby else d.now.by
        if (d.loss == own.loss && was == By.AppFirst && d.was.out.exit == own.was.out.exit) return pe
        val at = place.takeIf { !resolver }
        if ((was == By.Address || now == By.Address) && at != null && d.was.rby == d.now.rby) return setOf(at)
        if (name != null && By.Name in listOf(was, now, d.was.rby, d.now.rby)) return names()
        return at?.let(::setOf) ?: pe
    }

    private val addresses = listOf("203.0.113.80", "192.168.1.20", "192.168.1.1", "192.168.200.1", "172.16.0.5", "169.254.1.1", "224.0.0.251", "255.255.255.255",
        "10.0.0.1", "10.20.0.1", "10.20.255.254", "10.21.0.1", "10.127.255.254", "10.128.0.1", "10.140.0.7", "10.191.255.254", "10.192.0.1", "10.255.255.254", "100.64.0.1", "8.8.8.8")

    /** Every concrete connection's loss, by what it is said for; and the apps that leave unbraid, with their own loss. */
    private fun expected(a: Setup, b: Setup): Pair<Map<Who, Set<Loss>>, Map<Who, Loss?>> {
        val out = HashMap<Who, MutableSet<Loss>>()
        val leaving = HashMap<Who, Loss?>()
        val add = { w: Set<Who>, l: Loss? -> if (l != null) w.forEach { out.getOrPut(it) { mutableSetOf() } += l } }
        val sites = a.sites.keys + b.sites.keys
        val names = siteNames.flatMap { listOf(it, "zz.$it") } + "free.test" + "printer.lan" + "x.home.arpa"
        val homeRanges = listOf(a, b).flatMap { s -> s.home.wg?.let(Compiler::homeCidrs).orEmpty() }
        val localOn = a.prefs.lan || b.prefs.lan
        val place = { ip: String -> if (homeRanges.any { within(ip, it) }) Who.Home else if (localOn && lan.any { within(ip, it) }) Who.Local else null }
        for (l in listOf(false, true)) {
            val dflt = duo(a, b, l) { it.connect(OTHER, "free.test", "203.0.113.80") }
            val dfltPing = duo(a, b, l) { it.connect(PING, null, "203.0.113.80") }
            val dfltLookup = listOf("TXT", "HTTPS").associateWith { q -> duo(a, b, l) { it.lookup(OTHER, "free.test", q) } }
            for (p in listOf(OTHER, OWNERLESS, PING) + appKeys) {
                val ownName = if (p == PING) null else "free.test"
                val own = duo(a, b, l) { it.connect(p, ownName, "203.0.113.80") }
                val pe = entity(p, own, if (p == PING) dfltPing else dflt)
                add(pe, own.loss)
                val left = { s: Step -> s.by == By.AppFirst && s.out.exit == "direct" }
                if (left(own.now) && !left(own.was)) pe.filterIsInstance<Who.App>().forEach { leaving[it] = own.loss }
                for (n in listOf<String?>(null) + if (p == PING) emptyList() else names) for (ip in addresses) {
                    if (n == ownName && ip == "203.0.113.80") continue
                    val d = duo(a, b, l) { it.connect(p, n, ip) }
                    d.loss?.let { add(who(n, place(ip), d, own, pe, sites), it) }
                }
                if (p != PING) for (q in listOf("TXT", "HTTPS")) {
                    val ownL = duo(a, b, l) { it.lookup(p, "free.test", q) }
                    val peL = entity(p, ownL, dfltLookup.getValue(q))
                    add(peL, ownL.loss)
                    for (n in names - "free.test") duo(a, b, l) { it.lookup(p, n, q) }.let { d -> d.loss?.let { add(who(n, null, d, ownL, peL, sites), it) } }
                }
            }
        }
        return out to leaving.filterKeys { it in out }
    }

    // ---- random setups and restores ----

    private fun random(g: Random): Setup {
        fun <T> pick(l: List<T>) = l[g.nextInt(l.size)]
        val ids = listOf("nl", "m1", "w", "k1", DIRECT, BLOCK)
        val filter = if (g.nextBoolean()) Filter() else Filter(pick(listOf("quad9", "adguard")), routes = g.nextBoolean(), direct = g.nextBoolean(), directApps = g.nextBoolean(),
            apps = appKeys.filter { g.nextInt(10) < 3 }.associateWith { g.nextBoolean() })
        return base.copy(
            routes = base.routes.map { if (it.id == "nl" || it.id == "m1") it.copy(filter = pick(listOf(null, true, false))) else it },
            defaultRoute = pick(listOf("nl", "m1", "w", "k1", DIRECT, DIRECT)),
            apps = appKeys.filter { g.nextInt(10) < 6 }.associateWith { pick(ids) },
            sites = siteNames.filter { g.nextBoolean() }.associateWith { pick(ids + BLOCK) },
            prefs = Prefs(filter = filter, lan = g.nextInt(10) < 6, blockLan = g.nextBoolean()),
            home = pick(homes)?.let { Home(wg = wg(7, it.joinToString(", ")).copy(name = "Home")) } ?: Home(),
            opts = mapOf("nord" to mapOf("protect" to pick(listOf("on", "off"))), "mullvad" to mapOf("protect" to pick(listOf("on", "off")))),
        )
    }

    private fun change(g: Random, s: Setup): Setup {
        fun <T> pick(l: List<T>) = l[g.nextInt(l.size)]
        val ids = listOf("nl", "m1", "w", "k1", DIRECT, BLOCK, null)
        return when (g.nextInt(10)) {
            0 -> s.copy(defaultRoute = pick(listOf("nl", "m1", "w", "k1", DIRECT)))
            1 -> pick(appKeys).let { k -> pick(ids).let { v -> s.copy(apps = if (v == null) s.apps - k else s.apps + (k to v)) } }
            2 -> pick(siteNames).let { n -> pick(ids).let { v -> s.copy(sites = if (v == null) s.sites - n else s.sites + (n to v)) } }
            3 -> s.copy(prefs = s.prefs.copy(filter = s.prefs.filter.copy(directApps = !s.prefs.filter.directApps, direct = g.nextBoolean(), routes = g.nextBoolean())))
            4 -> pick(appKeys).let { k -> pick(listOf(true, false, null)).let { v -> s.copy(prefs = s.prefs.copy(filter = s.prefs.filter.copy(apps = if (v == null) s.prefs.filter.apps - k else s.prefs.filter.apps + (k to v)))) } }
            5 -> s.copy(opts = mapOf("nord" to mapOf("protect" to pick(listOf("on", "off"))), "mullvad" to mapOf("protect" to pick(listOf("on", "off")))))
            6 -> s.copy(prefs = s.prefs.copy(lan = !s.prefs.lan))
            7 -> s.copy(prefs = s.prefs.copy(blockLan = !s.prefs.blockLan))
            8 -> s.copy(home = pick(homes)?.let { Home(wg = wg(7, it.joinToString(", ")).copy(name = "Home")) } ?: Home())
            else -> s.copy(prefs = s.prefs.copy(filter = Filter()))
        }
    }

    @Test fun everyWeakerConnectionIsSaidAndNothingElse() {
        val g = Random(0x0AC1E)
        var weaker = 0; var same = 0
        val t = System.nanoTime()
        repeat(400) { i ->
            val a = random(g)
            var b = a
            repeat(1 + g.nextInt(3)) { b = change(g, b) }
            val (want, leaving) = expected(a, b)
            val got = RestoreTraffic.found(a, b)!!
            val where = "$i: ${a.defaultRoute} ${a.apps} ${a.sites} ${a.prefs} ${a.home.wg?.allowedIps} -> ${b.defaultRoute} ${b.apps} ${b.sites} ${b.prefs} ${b.home.wg?.allowedIps}"
            // Every weaker connection is said for what it is said for, and the walk says nothing no connection backs.
            assertEquals(where, want, got.losses)
            assertEquals(where, leaving, got.leaving)
            // Every thing found has a sentence that names it.
            val notes = RestoreTraffic.trafficNotes(got, a, filterOff = false)
            for (w in got.losses.keys) assertTrue("$where: $w unsaid in $notes", notes.any { w in it.who })
            for (n in notes) assertTrue("$where: ${n.text} backs nothing", n.text == RestoreTraffic.CUT || n.who.isNotEmpty() && n.who.all { it in got.losses })
            if (want.isEmpty()) same++ else weaker++
        }
        println("RESTORE-ORACLE pairs: weaker=$weaker same=$same in ${(System.nanoTime() - t) / 1_000_000} ms")
        assertTrue("$weaker, $same", weaker > 150 && same > 40)
    }
}
