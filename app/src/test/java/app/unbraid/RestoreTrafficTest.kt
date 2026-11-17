package app.unbraid

import app.unbraid.data.BLOCK
import app.unbraid.data.Backup
import app.unbraid.data.DIRECT
import app.unbraid.data.Filter
import app.unbraid.data.Home
import app.unbraid.data.Location
import app.unbraid.data.Manual
import app.unbraid.data.OwnServer
import app.unbraid.data.Prefs
import app.unbraid.data.RestoreTraffic
import app.unbraid.data.RestoreTraffic.Loss
import app.unbraid.data.RestoreTraffic.Outcome
import app.unbraid.data.RestoreTraffic.REJECT
import app.unbraid.data.RestoreTraffic.Resolver
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.data.Sites
import app.unbraid.data.VERSION
import app.unbraid.data.WgConf
import app.unbraid.data.WgPeer
import app.unbraid.data.restoredFrom
import app.unbraid.data.withoutSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** What a restore makes weaker, asked of the compiled configs: the walk against the compiler, the traps of earlier designs, and random pairs against an independent model. */
class RestoreTrafficTest {
    private val OTHER = "org.example.other"
    private val OTHER_NAME = "unbraid-check.example"
    private fun wg(n: Int, allowed: String = "0.0.0.0/0", dns: String? = null) = WgConf.parse("[Interface]\nPrivateKey = ${('A' + n).toString().repeat(42)}E=\nAddress = 10.0.$n.2/32\n" +
        (dns?.let { "DNS = $it\n" } ?: "") + "[Peer]\nPublicKey = ${('B' + n).toString().repeat(42)}E=\nEndpoint = 203.0.113.$n:51820\nAllowedIPs = $allowed\n", "Box $n")
    private val nl = Route("nl", Location("nord", 153, "NL", "Netherlands"))
    private val m1 = Route("m1", Location("mullvad", countryCode = "SE", country = "Sweden"))
    private val keyless = OwnServer("k", wg = wg(5).withoutSecrets(), keyless = true)
    private val base = Setup(routes = listOf(nl, m1, Route("w", wg = wg(1), own = "s"), Route("k1", wg = keyless.wg, own = "k", keyless = true)), defaultRoute = "nl",
        servers = listOf(OwnServer("s", wg = wg(1)), keyless), opts = mapOf("nord" to mapOf("protect" to "on")), version = VERSION)

    /** Where a connection goes and who answers its name (the other parts are tested apart). */
    private fun walk(s: Setup, pkg: String?, name: String?, address: String = "203.0.113.80", kin: (String) -> Set<String> = { emptySet() }): Outcome =
        RestoreTraffic.connect(RestoreTraffic.compile(s, kin)!!, (pkg?.let { kin(it) + it } ?: setOf(OTHER)), name, address)!!.let { Outcome(it.exit, it.resolver) }
    private fun notes(a: Setup, b: Setup, kin: (String) -> Set<String> = { emptySet() }) = RestoreTraffic.notes(a, b, kin)

    @Test fun theWalkAgreesWithTheCompilersOwnRules() {
        val s = base.copy(apps = mapOf("org.b" to BLOCK, "org.d" to DIRECT, "org.f" to DIRECT, "org.w" to "w", "org.m" to "m1", "org.k" to "k1"),
            sites = mapOf("tracker.example" to BLOCK, "example.com" to DIRECT, "work.example" to "w"),
            prefs = Prefs(filter = Filter("adguard", apps = mapOf("org.f" to true)), blockLan = false),
            home = Home(wg = wg(7, allowed = "10.20.0.0/16").copy(name = "Home")))
        assertEquals(Outcome("r-nl", Resolver.Provider), walk(s, null, OTHER_NAME))
        assertEquals(Outcome(REJECT), walk(s, "org.b", OTHER_NAME))
        assertEquals(Outcome(REJECT), walk(s, "org.b", "example.com"))
        assertEquals(Outcome("direct", Resolver.Local), walk(s, "org.d", OTHER_NAME))
        assertEquals(Outcome("direct", Resolver.Filter), walk(s, "org.f", OTHER_NAME))
        assertEquals(Outcome("r-w", Resolver.Route), walk(s, "org.w", OTHER_NAME))
        assertEquals(Outcome("r-m1", Resolver.Route), walk(s, "org.m", OTHER_NAME))
        // A route waiting for its config refuses its apps.
        assertEquals(Outcome(REJECT), walk(s, "org.k", OTHER_NAME))
        // Websites: a block holds for everyone and under it; a website's route beats its app's.
        assertEquals(Outcome(REJECT), walk(s, null, "x.tracker.example"))
        assertEquals(Outcome("direct", Resolver.Filter), walk(s, null, "example.com"))
        assertEquals(Outcome("r-w", Resolver.Route), walk(s, "org.m", "a.work.example"))
        // An app left outside unbraid never meets a website rule.
        assertEquals(Outcome("direct", Resolver.Local), walk(s, "org.d", "work.example"))
        // Addresses: the local network straight out, the home ranges through the home tunnel.
        assertEquals("direct", walk(s, null, null, "192.168.1.20").exit)
        assertEquals("home-wg", walk(s, null, null, "10.20.0.1").exit)
        assertEquals("direct", walk(s, null, null, "10.21.0.1").exit)
        assertEquals("r-nl", walk(s.copy(prefs = s.prefs.copy(lan = false)), null, null, "192.168.1.20").exit)
        // A blocked app keeps the local network inside unbraid unless the user lets it be: then it reaches it.
        assertEquals("direct", walk(s, "org.b", null, "192.168.1.20").exit)
        assertEquals(REJECT, walk(s.copy(prefs = s.prefs.copy(blockLan = true)), "org.b", null, "192.168.1.20").exit)
        // An app sharing its user id with a blocked one is refused with it, and not left outside.
        val kin = { p: String -> if (p == "org.b" || p == "org.d") setOf("org.b", "org.d") else emptySet() }
        assertEquals(Outcome(REJECT), walk(s, "org.d", OTHER_NAME, kin = kin))
        // A Direct default with apps on a VPN: the default program goes Direct.
        assertEquals("direct", walk(s.copy(defaultRoute = DIRECT), null, OTHER_NAME).exit)
    }

    /** Ground truth for a random setup: where each app and website was set to go. */
    private fun expected(s: Setup, app: String?, name: String?): String {
        val routeExit = { id: String -> when {
            id == BLOCK -> REJECT
            id == DIRECT -> "direct"
            s.route(id)!!.keyless -> REJECT
            else -> "tunnel"
        } }
        val byApp = app?.let { s.apps[it] } ?: s.defaultRoute
        if (byApp == BLOCK) return REJECT
        if (app != null && s.outside(app)) return "direct"
        if (name != null) {
            if (s.sites.any { (k, v) -> v == BLOCK && (name == k || name.endsWith(".$k")) }) return REJECT
            Sites.match(name, s.sites.keys)?.let { return routeExit(s.sites.getValue(it)) }
        }
        return routeExit(byApp)
    }

    private val siteNames = listOf("n.example", "b.n.example", "c.b.n.example", "o.example", "x.o.example", "s.example")
    private val appKeys = (0 until 6).map { "org.example.a$it" }
    private val ids = listOf("nl", "m1", "w", "k1")

    private fun random(g: Random): Setup {
        fun <T> pick(l: List<T>) = l[g.nextInt(l.size)]
        val filter = if (g.nextBoolean()) Filter() else Filter(pick(listOf("quad9", "adguard")), routes = g.nextBoolean(), direct = g.nextBoolean(), directApps = g.nextBoolean(),
            apps = appKeys.filter { g.nextInt(10) < 3 }.associateWith { g.nextBoolean() })
        return base.copy(
            routes = base.routes.map { if (it.id == "nl" || it.id == "m1") it.copy(filter = pick(listOf(null, true, false))) else it },
            defaultRoute = pick(listOf("nl", "m1", "w", DIRECT, DIRECT)),
            apps = appKeys.filter { g.nextInt(10) < 6 }.associateWith { pick(ids + DIRECT + BLOCK) },
            sites = siteNames.filter { g.nextBoolean() }.associateWith { pick(ids + DIRECT + BLOCK + BLOCK) },
            prefs = Prefs(filter = filter, lan = g.nextInt(10) < 7, blockLan = g.nextBoolean()),
            opts = mapOf("nord" to mapOf("protect" to pick(listOf("on", "off"))), "mullvad" to mapOf("protect" to pick(listOf("on", "off")))),
        )
    }

    @Test fun theWalkFollowsEveryChoice() {
        val g = Random(0x5EED)
        repeat(500) { i ->
            val s = random(g)
            val c = RestoreTraffic.compile(s, { emptySet() })!!
            for (app in appKeys + listOf<String?>(null)) for (name in siteNames.flatMap { listOf(it, "zz.$it") } + OTHER_NAME) {
                val got = RestoreTraffic.connect(c, setOf(app ?: OTHER), name)!!.exit.let { if (it.startsWith("r-")) "tunnel" else it }
                assertEquals("$i: $app to $name in ${s.apps} ${s.sites} default ${s.defaultRoute}", expected(s, app, name), got)
            }
        }
    }

    // ---- an independent model of what each connection gets, read from the compiler's comments, not from the walk ----

    private data class Treat(val leave: String, val filter: Boolean = false, val provider: Boolean = false)

    private fun model(s: Setup, app: String?, name: String?): Treat {
        val f = s.prefs.filter
        val server = f.server() != null
        val through = s.apps.any { (k, v) -> v == DIRECT && f.through(k) }
        val vpn = { id: String -> s.route(id)!!.let { r -> if (r.keyless) Treat("refused") else Treat("vpn:$id", filter = server && f.routes, provider = !(server && f.routes) && s.protects(r)) } }
        val def = if (s.defaultRoute == DIRECT) Treat("direct", filter = server && f.direct) else vpn(s.defaultRoute)
        val forApp = when (val id = app?.let { s.apps[it] }) {
            null -> def
            BLOCK -> return Treat("blocked")
            DIRECT -> if (f.through(app)) Treat("direct", filter = true) else return Treat("direct")
            else -> vpn(id)
        }
        if (name == null) return forApp
        if (s.sites.any { (k, v) -> v == BLOCK && (name == k || name.endsWith(".$k")) }) return Treat("blocked")
        val id = Sites.match(name, s.sites.keys)?.let(s.sites::getValue) ?: return forApp
        return if (id == DIRECT) Treat("direct", filter = server && ((f.direct && s.defaultRoute == DIRECT) || through)) else vpn(id)
    }

    private fun weaker(was: Treat, now: Treat): Boolean {
        val rank = { t: Treat -> when { t.leave == "blocked" -> 3; t.leave == "direct" -> 0; else -> 2 } }
        if (was.leave == "blocked") return now.leave != "blocked"
        if (now.leave == "refused" || now.leave == "blocked") return false
        if (rank(now) < rank(was)) return true
        if (rank(was) == rank(now)) {
            if (was.filter && !now.filter) return true
            if (was.leave.startsWith("vpn") && now.leave.startsWith("vpn") && was.provider && !now.provider && !now.filter) return true
        }
        return false
    }

    @Test fun randomRestoresAgainstAnIndependentModel() {
        val g = Random(0xB4CC)
        fun <T> pick(l: List<T>) = l[g.nextInt(l.size)]
        val missed = mutableListOf<String>(); val spurious = mutableListOf<String>()
        var weakerCount = 0; var sameCount = 0
        val t = System.nanoTime()
        repeat(3000) { i ->
            val here = random(g)
            var next = here
            val effective = g.nextBoolean()
            repeat(1 + g.nextInt(4)) {
                next = if (effective) when (g.nextInt(8)) {
                    0 -> next.copy(defaultRoute = pick(listOf("nl", "m1", "w", DIRECT)))
                    1 -> pick(appKeys).let { k -> pick(listOf("nl", "m1", "w", "k1", DIRECT, BLOCK, null)).let { v -> next.copy(apps = if (v == null) next.apps - k else next.apps + (k to v)) } }
                    2 -> pick(siteNames).let { n -> pick(listOf("nl", "m1", "w", "k1", DIRECT, BLOCK, null)).let { v -> next.copy(sites = if (v == null) next.sites - n else next.sites + (n to v)) } }
                    3 -> next.copy(prefs = next.prefs.copy(filter = next.prefs.filter.copy(directApps = !next.prefs.filter.directApps, direct = g.nextBoolean(), routes = g.nextBoolean())))
                    4 -> pick(appKeys).let { k -> pick(listOf(true, false, null)).let { v -> next.copy(prefs = next.prefs.copy(filter = next.prefs.filter.copy(apps = if (v == null) next.prefs.filter.apps - k else next.prefs.filter.apps + (k to v)))) } }
                    5 -> next.copy(opts = mapOf("nord" to mapOf("protect" to pick(listOf("on", "off"))), "mullvad" to mapOf("protect" to pick(listOf("on", "off")))))
                    6 -> pick(listOf("nl", "m1")).let { id -> next.copy(routes = next.routes.map { if (it.id == id) it.copy(filter = pick(listOf(null, true, false))) else it }) }
                    else -> next.copy(prefs = next.prefs.copy(filter = Filter()))
                } else when (g.nextInt(4)) {
                    // Changes that change nothing a connection gets (checked below, not assumed).
                    0 -> next.sites.entries.toList().takeIf { it.isNotEmpty() }?.let { pick(it) }?.let { (n, v) -> next.copy(sites = next.sites + ("deep.zz.$n" to v)) } ?: next
                    1 -> next.copy(favorites = next.favorites + Location("nord", 81, "DE", "Germany"))
                    2 -> next.copy(routes = next.routes.map { if (it.id == "nl") it.copy(hue = it.hue + 1) else it })
                    else -> pick(appKeys).takeIf { it !in next.apps && next.defaultRoute != DIRECT }?.let { next.copy(apps = next.apps + (it to next.defaultRoute)) } ?: next
                }
            }
            val apps = (appKeys + here.apps.keys + next.apps.keys).distinct()
            val names = (here.sites.keys + next.sites.keys).flatMap { listOf(it, "zz-check.$it") }.distinct() + OTHER_NAME
            val programs = listOf<String?>(null) + apps
            val lost = programs.flatMap { p -> names.mapNotNull { n -> "$p to $n: ${model(here, p, n)} -> ${model(next, p, n)}".takeIf { weaker(model(here, p, n), model(next, p, n)) } } }
            val said = notes(here, next)
            if (lost.isNotEmpty()) { weakerCount++; if (said.isEmpty()) missed += "$i: ${lost.take(3)} | here d=${here.defaultRoute} ${here.apps} ${here.sites} ${here.prefs} | next d=${next.defaultRoute} ${next.apps} ${next.sites} ${next.prefs}" }
            val sameTraffic = programs.all { p -> names.all { n -> model(here, p, n) == model(next, p, n) } }
            if (sameTraffic && here.prefs == next.prefs && here.opts == next.opts && here.routes.map { it.copy(hue = 0) } == next.routes.map { it.copy(hue = 0) }) {
                sameCount++
                if (said.isNotEmpty()) spurious += "$i: says $said: ${here.sites} -> ${next.sites}, ${here.apps} -> ${next.apps}"
            }
        }
        println("RESTORE-CHECK pairs: weaker=$weakerCount same=$sameCount missed=${missed.size} spurious=${spurious.size} in ${(System.nanoTime() - t) / 1_000_000} ms")
        assertEquals(missed.take(4).joinToString("\n"), 0, missed.size)
        assertEquals(spurious.take(4).joinToString("\n"), 0, spurious.size)
        assertTrue("$weakerCount, $sameCount", weakerCount > 400 && sameCount > 400)
    }

    // ---- the traps earlier designs fell into, one each ----

    @Test fun aWebsiteRuleAddedUnderAnotherOrRemovedIsSaid() {
        val here = base.copy(sites = mapOf("example.com" to "w", "ads.example.com" to BLOCK))
        assertTrue(notes(here, here.copy(sites = mapOf("example.com" to "w", "ads.example.com" to BLOCK, "cdn.example.com" to DIRECT))).contains("1 website goes from a VPN route to Direct."))
        assertTrue(notes(here, here.copy(sites = mapOf("example.com" to "w"))).contains("1 website that is blocked now won't be."))
        // A rule under a blocked website never opens it: Block comes first.
        assertEquals(emptyList<String>(), notes(here, here.copy(sites = here.sites + ("x.ads.example.com" to DIRECT))))
    }

    @Test fun anAppMovedOutOfAFilterScopeIsSaid() {
        val here = base.copy(apps = mapOf("org.a" to DIRECT), prefs = Prefs(filter = Filter("quad9", directApps = true)))
        assertEquals(listOf("1 app loses the DNS filter."), notes(here, here.copy(prefs = Prefs(filter = Filter("quad9", directApps = true, apps = mapOf("org.a" to false))))))
        // Its own filter switch moved to the routes' scope: the app is on Direct, so it loses it too; and the default route's
        // Nord blocking (ads and malicious domains) gives way to Quad9, which blocks malware only.
        assertEquals(listOf("1 app loses the DNS filter.", "The default route gets DNS blocking that blocks fewer kinds of sites than now."),
            notes(here, here.copy(prefs = Prefs(filter = Filter("quad9", routes = true)))))
    }

    @Test fun removingADirectWebsiteWhoseLookupsTheFilterAnsweredIsSaid() {
        val here = base.copy(defaultRoute = DIRECT, sites = mapOf("example.com" to DIRECT), prefs = Prefs(filter = Filter("quad9", direct = true)))
        // The website now follows its app, here a Direct default that the filter covers too: only its HTTPS and SVCB
        // questions, answered on the phone now, go out.
        assertEquals(listOf("1 website sends some DNS lookups Direct that don't go out Direct now."), notes(here, here.copy(sites = emptyMap())))
        // With the filter on Direct apps only, a Direct app's website loses it.
        val apps = here.copy(defaultRoute = "nl", apps = mapOf("org.a" to DIRECT), prefs = Prefs(filter = Filter("quad9", directApps = true)))
        assertTrue(notes(apps, apps.copy(sites = emptyMap())).toString(), notes(apps, apps.copy(sites = emptyMap())).isNotEmpty())
    }

    @Test fun appsSharingAUserIdWithABlockedAppAreJudgedTogether() {
        val kin = { p: String -> if (p == "org.b" || p == "org.c") setOf("org.b", "org.c") else emptySet() }
        val here = base.copy(apps = mapOf("org.b" to BLOCK, "org.c" to DIRECT))
        // org.c loses nothing while org.b stays blocked: the block refuses the whole user id.
        assertEquals(emptyList<String>(), notes(here, here.copy(apps = mapOf("org.b" to BLOCK)), kin))
        assertEquals(listOf("2 apps that are blocked now go Direct."), notes(here, here.copy(apps = mapOf("org.c" to DIRECT)), kin))
        // Without knowing they share one, only org.b would seem to open.
        assertEquals(listOf("1 app that is blocked now won't be."), notes(here, here.copy(apps = mapOf("org.c" to DIRECT))))
    }

    @Test fun trafficRefusedNowThatWouldGoOutIsSaid() {
        val here = base.copy(apps = mapOf("org.a" to "k1"), sites = mapOf("example.com" to "k1"))
        assertEquals(listOf("1 app and 1 website that can't connect now, because their routes wait for a server's config, go out Direct without it."),
            notes(here, here.copy(apps = mapOf("org.a" to DIRECT), sites = mapOf("example.com" to DIRECT))))
        val def = base.copy(defaultRoute = "k1")
        assertEquals(listOf("The default route can't connect now, because its route waits for a server's config. After the restore its traffic goes out."), notes(def, def.copy(defaultRoute = "nl")))
    }

    @Test fun aNarrowerHomeRangeIsSaidAtEitherEnd() {
        val here = base.copy(home = Home(wg = wg(7, allowed = "10.0.0.0/8").copy(name = "Home")))
        val narrower = here.copy(home = Home(wg = here.home.wg!!.copy(allowedIps = listOf("10.20.0.0/16"))))
        assertTrue(notes(here, narrower).contains("Part of the home network goes Direct instead of through the home tunnel."))
        // Cut at its far end only: the first address stays covered.
        val far = here.copy(home = Home(wg = here.home.wg!!.copy(allowedIps = listOf("10.0.0.0/9"))))
        assertTrue(notes(here, far).contains("Part of the home network goes Direct instead of through the home tunnel."))
        // Wider says nothing.
        assertTrue(notes(narrower, here).none { it.startsWith("Part of") })
    }

    @Test fun ownServersAreComparedByEveryAddressTheyConnectTo() {
        val cfg = { second: String -> "client\ndev tun\nremote 203.0.113.2 1194\nremote $second 1194\nremote-cert-tls server\n<ca>\n-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n</ca>\n" }
        val o = Manual.parseOvpn(cfg("203.0.113.3"), "Office", "u", "p")
        val here = base.copy(routes = base.routes + Route("o", ovpn = o, own = "so"), servers = base.servers + OwnServer("so", ovpn = o))
        val moved = Manual.parseOvpn(cfg("198.51.100.9"), "Office", "u", "p")
        val next = here.copy(routes = here.routes.map { if (it.id == "o") it.copy(ovpn = moved) else it }, servers = here.servers.map { if (it.id == "so") it.copy(ovpn = moved) else it })
        assertEquals(listOf("\"Office\" connects to another server at 198.51.100.9."), notes(here, next))
        // Hosts are compared whole: two long names that differ only at their start are two servers.
        val long = "a".repeat(100) + ".example.net"
        val w1 = here.copy(servers = here.servers.map { if (it.id == "s") it.copy(wg = wg(1).copy(endpoint = "x$long:51820")) else it })
        val w2 = here.copy(servers = here.servers.map { if (it.id == "s") it.copy(wg = wg(1).copy(endpoint = "y$long:51820")) else it })
        assertTrue(notes(w1, w2).toString(), notes(w1, w2).any { it.startsWith("\"Box 1\" connects to another server at …") && it.length < 120 })
        // The home tunnel by its address, key and DNS servers.
        val home = base.copy(home = Home(wg = wg(7, allowed = "10.20.0.0/16", dns = "10.20.0.1").copy(name = "Home")))
        assertEquals(listOf("Your home WireGuard uses other DNS servers: 9.9.9.9."), notes(home, home.copy(home = Home(wg = home.home.wg!!.copy(dns = listOf("9.9.9.9"))))))
        assertEquals(listOf("Your home WireGuard connects to another server at 203.0.113.7."), notes(home, home.copy(home = Home(wg = home.home.wg!!.copy(publicKey = "C".repeat(42) + "E=")))))
    }

    @Test fun aWebsiteOnDirectTakesAVpnAppDirectThereToo() {
        val here = base.copy(defaultRoute = DIRECT, apps = mapOf("org.browser" to "nl"))
        assertEquals(listOf("1 website goes from a VPN route to Direct."), notes(here, here.copy(sites = mapOf("example.com" to DIRECT))))
    }

    @Test fun everyLossHasASentence() {
        val here = base.copy(apps = mapOf("org.a" to "nl", "org.b" to BLOCK), sites = mapOf("a.example" to "nl", "b.example" to BLOCK),
            prefs = Prefs(filter = Filter("quad9", directApps = true)), home = Home(wg = wg(7, allowed = "10.20.0.0/16").copy(name = "Home")))
        val next = here.copy(defaultRoute = DIRECT, apps = mapOf("org.a" to DIRECT, "org.b" to DIRECT), sites = mapOf("a.example" to DIRECT, "b.example" to DIRECT),
            home = Home(wg = here.home.wg!!.copy(allowedIps = listOf("10.20.0.0/24"))))
        val said = notes(here, next)
        // a.example goes Direct as the default does: said once, for the default. Its own rule picks who answers it,
        // Quad9 for Direct traffic that passes through, which blocks less than Nord's blocking now: said for it.
        assertEquals(listOf("The default route goes from Netherlands to Direct.", "1 app goes from a VPN route to Direct.", "Part of the home network goes Direct instead of through the home tunnel.",
            "1 app that is blocked now goes Direct.", "1 website that is blocked now goes Direct.", "1 website gets DNS blocking that blocks fewer kinds of sites than now."), said)
        assertEquals(listOf("1 website goes from a VPN route to Direct."), notes(here, here.copy(sites = here.sites + ("a.example" to DIRECT))))
        val provider = here.copy(apps = emptyMap(), sites = emptyMap(), prefs = Prefs())
        assertEquals(listOf("The default route loses the provider's ad and tracker blocking it has now."), notes(provider, provider.copy(opts = mapOf("nord" to mapOf("protect" to "off")))))
        val filtered = provider.copy(prefs = Prefs(filter = Filter("quad9", routes = true)))
        assertEquals(listOf("The DNS filter is turned off."), notes(filtered, filtered.copy(prefs = Prefs())))
        assertEquals(listOf("The default route loses the DNS filter."), notes(filtered, filtered.copy(prefs = Prefs(filter = Filter("quad9")))))
        // Another filter that blocks other kinds of sites: AdGuard blocks ads and trackers, not the malware Quad9 blocks.
        assertEquals(listOf("The DNS filter changes to another server.", "The default route gets DNS blocking that blocks fewer kinds of sites than now."),
            notes(filtered, filtered.copy(prefs = Prefs(filter = Filter("adguard", routes = true)))))
        assertEquals(listOf("Apps can reach the local network again."), notes(provider.copy(prefs = Prefs(lan = false)), provider))
        val mullvad = provider.copy(defaultRoute = "m1", opts = mapOf("mullvad" to mapOf("protect" to "on", "adult" to "on", "gambling" to "on", "owned" to "on")))
        // The provider's choices by name, and what they reach: the default route's lookups.
        assertEquals(listOf("Mullvad stops blocking adult sites and gambling sites.", "Mullvad no longer keeps to the servers it owns.", "The default route gets DNS blocking that blocks fewer kinds of sites than now."),
            notes(mullvad, mullvad.copy(opts = mapOf("mullvad" to mapOf("protect" to "on")))))
    }

    @Test fun aSetupThatCanNotBeCompiledIsSaidSo() {
        val two = base.copy(routes = base.routes + Route("t1", tsExit = "100.64.0.1") + Route("t2", tsExit = "100.64.0.2"), apps = mapOf("org.a" to "t1", "org.b" to "t2"))
        assertEquals(listOf(RestoreTraffic.UNKNOWN), notes(two, base))
    }

    @Test fun theLargestSetupIsComparedQuickly() {
        val many = (0 until 5000).associate { "org.example.app$it" to listOf("nl", "m1", "w", DIRECT, BLOCK)[it % 5] }
        val sites = (0 until Sites.MAX).associate { "s$it.example" to listOf("nl", DIRECT, BLOCK)[it % 3] }
        val here = base.copy(apps = many, sites = sites)
        val next = here.copy(apps = many.mapValues { (k, v) -> if (k.hashCode() % 3 == 0) DIRECT else v }, sites = sites.mapValues { DIRECT })
        val t = System.nanoTime()
        val said = notes(here, next)
        val ms = (System.nanoTime() - t) / 1_000_000
        println("RESTORE-CHECK largest setup: $ms ms, $said")
        assertTrue(said.isNotEmpty())
        assertTrue("$ms ms", ms < 3000)
    }

    /** The walk takes its stand-in address from the config, so it follows the engine wherever its address pool stands. */
    @Test fun theWalkFollowsTheFakeAddressBlock() = app.unbraid.vpn.Compiler.FAKE_RANGES.indices.forEach { b ->
        val s = base.copy(apps = mapOf("org.w" to "w"))
        val links = mapOf("nl" to app.unbraid.data.WgLink(app.unbraid.data.Server("x", "198.51.100.1", "198.51.100.1", wg(2).publicKey, 0), wg(2)), "w" to app.unbraid.data.WgLink(app.unbraid.data.Server("w", "203.0.113.1", "", wg(1).publicKey, 0), wg(1)))
        val c = RestoreTraffic.Config(app.unbraid.vpn.Compiler.compile(app.unbraid.vpn.Compiler.Input(s, links, "app.unbraid", false, "", "", fakeBlock = b)), s)
        assertEquals("block $b", "r-w", RestoreTraffic.connect(c, setOf("org.w"), OTHER_NAME)!!.exit)
    }

    @Test fun aRestoreOverTheSameSetupSaysNothing() {
        val s = base.copy(apps = mapOf("org.a" to "w", "org.b" to BLOCK), sites = mapOf("example.com" to DIRECT), prefs = Prefs(filter = Filter("quad9", routes = true, directApps = true)),
            home = Home(wg = wg(7, allowed = "10.20.0.0/16").copy(name = "Home")))
        val c = Backup.read(Backup.export(s, "3.0.2", 0))
        assertEquals(emptyList<String>(), notes(s, s.restoredFrom(c.setup, null, 0, c.keys)!!))
        // Saved without keys on the same phone: own servers get their config back, nothing changes.
        val k = Backup.read(Backup.export(s, "3.0.2", 0, keys = false))
        assertEquals(emptyList<String>(), notes(s, s.restoredFrom(k.setup, null, 0, k.keys)!!))
        assertNotNull(RestoreTraffic.compile(s, { emptySet() }))
    }

    // ---- probes, lockdown, ranges, profiles and the budget, one trap each ----

    /** A website or app named like a made-up probe can't stand in for the default route: the probes are chosen against every rule. */
    @Test fun aRuleCanNotCaptureTheProbes() {
        val next = base.copy(defaultRoute = DIRECT, sites = mapOf("unbraid-check.example" to "nl"), apps = mapOf("app.unbraid.check.other" to "nl"))
        assertEquals(listOf("The default route goes from Netherlands to Direct."), notes(base, next))
    }

    /** Under Android's lockdown a Direct app stays inside unbraid, where websites' routes reach it: a website's loss there is said. */
    @Test fun aLossUnderLockdownIsSaid() {
        val here = base.copy(apps = mapOf("org.browser" to DIRECT), sites = mapOf("work.example" to "nl"))
        assertEquals(listOf("1 website goes from a VPN route to Direct."), notes(here, here.copy(sites = emptyMap())))
        // Blocked apps' local network: only said when it opens, not when the local network stays closed anyway.
        val closed = base.copy(apps = mapOf("org.game" to BLOCK), prefs = Prefs(lan = false, blockLan = true))
        assertEquals(emptyList<String>(), notes(closed, closed.copy(prefs = Prefs(lan = false, blockLan = false))))
        val open = closed.copy(prefs = Prefs(lan = true, blockLan = true))
        assertEquals(listOf("Blocked apps can reach the local network."), notes(open, open.copy(prefs = Prefs(lan = true, blockLan = false))))
    }

    /** A hole in the middle of a home range: one address between every two range boundaries is walked. */
    @Test fun aHoleInAHomeRangeIsSaid() {
        val here = base.copy(home = Home(wg = wg(7, allowed = "10.0.0.0/8").copy(name = "Home")))
        val split = here.copy(home = Home(wg = here.home.wg!!.copy(allowedIps = listOf("10.0.0.0/9", "10.192.0.0/10"))))
        assertEquals(listOf("Part of the home network goes Direct instead of through the home tunnel."), notes(here, split))
        assertEquals(emptyList<String>(), notes(split, here))
    }

    /** A website with a route of its own that goes Direct with the default is said for itself, not folded into the default. */
    @Test fun aWebsiteLeavingItsOwnRouteWithTheDefaultIsSaid() {
        val here = base.copy(sites = mapOf("bank.example" to "m1"))
        assertEquals(listOf("The default route goes from Netherlands to Direct.", "1 website goes from a VPN route to Direct."),
            notes(here, here.copy(defaultRoute = DIRECT, sites = mapOf("bank.example" to DIRECT))))
    }

    /** A restored profile the rules switch to on a kind of network runs without anyone choosing it: judged too, through a real file. */
    @Test fun aProfileTheRestoredRulesSwitchToIsJudged() {
        val here = base.copy(apps = mapOf("org.bank" to "nl"))
        val backup = here.copy(profiles = listOf(app.unbraid.data.Profile("Mobile", DIRECT, mapOf("org.bank" to DIRECT))), onNet = mapOf(app.unbraid.data.MOBILE to "Mobile"))
        val c = Backup.read(Backup.export(backup, "3.0.2", 0))
        assertEquals(listOf("On mobile data unbraid runs the profile Mobile instead of your current setup. Compared with now there:", "The default route goes from Netherlands to Direct.", "1 app goes from a VPN route to Direct."),
            notes(here, here.restoredFrom(c.setup, null, 0, c.keys)!!))
        // The same rule on this phone already: nothing changes there.
        assertEquals(emptyList<String>(), notes(backup, backup.restoredFrom(c.setup, null, 0, c.keys)!!))
        // A rule this phone has that the backup drops: on that network the phone stays on the restored setup.
        val phone = base.copy(defaultRoute = DIRECT, profiles = listOf(app.unbraid.data.Profile("Mobile", "nl")), onNet = mapOf(app.unbraid.data.MOBILE to "Mobile"))
        assertEquals(listOf("On mobile data unbraid runs the restored setup instead of the profile Mobile. Compared with now there:", "The default route goes from Netherlands to Direct."),
            notes(phone, phone.copy(profiles = emptyList(), onNet = emptyMap())))
    }

    /** A setup too large to walk every app against every website says so; one within the budget is walked whole. */
    @Test fun aCutCheckIsSaidAndTheBudgetCoversARealSetup() {
        val servers = (0 until 200).map { OwnServer("s$it", wg = wg(1).copy(endpoint = "203.0.113.${it % 250}:${51000 + it}", publicKey = ('C' + it % 20).toString().repeat(42) + "E=", privateKey = ('D' + it % 20).toString().repeat(41) + "${it % 10}E=")) }
        val routes = servers.map { Route("r" + it.id, wg = it.wg, own = it.id) }
        val here = base.copy(defaultRoute = DIRECT, routes = base.routes + routes, servers = base.servers + servers, apps = routes.withIndex().associate { (i, r) -> "org.example.a$i" to r.id },
            sites = (0 until 49).associate { "s$it.example" to DIRECT })
        val t = System.nanoTime()
        val said = notes(here, here.copy(sites = here.sites + ("work.example" to DIRECT)))
        println("RESTORE-CHECK 200 exits, 50 websites: ${(System.nanoTime() - t) / 1_000_000} ms, $said")
        assertEquals(listOf("1 website goes from a VPN route to Direct."), said)
        assertEquals(false, RestoreTraffic.found(here, here.copy(sites = here.sites + ("work.example" to DIRECT)))!!.cut)
        // Every website on an exit of its own and every app on another: too many pairs, and the sheet says the check was cut.
        val apart = here.copy(sites = (0 until 49).associate { "s$it.example" to routes[it].id }, apps = (0 until 5000).associate { "org.example.a$it" to routes[it % 200].id })
        val t2 = System.nanoTime()
        val cut = notes(apart, apart.copy(sites = apart.sites + ("work.example" to DIRECT)))
        println("RESTORE-CHECK 200 exits, 49 websites apart, 5000 apps: ${(System.nanoTime() - t2) / 1_000_000} ms, $cut")
        assertEquals(listOf("1 website goes from a VPN route to Direct.", RestoreTraffic.CUT), cut)
    }

    // ---- situations the phone can be in, and the cost of the check ----

    private val desk = app.unbraid.data.Profile("Desk", DIRECT)
    private val work = app.unbraid.data.Profile("Work", "nl")

    /** A network with no rule keeps the profile the last one switched to: the pair after Wi-Fi then mobile data is judged too. */
    @Test fun aNetworkWithNoRuleKeepsTheProfileBefore() {
        val here = base.copy(defaultRoute = DIRECT, profiles = listOf(desk, work), profile = "Desk", onNet = mapOf(app.unbraid.data.WIFI to "Work"))
        val next = here.copy(onNet = here.onNet + (app.unbraid.data.MOBILE to "Desk"))
        assertEquals(listOf("On mobile data after Wi-Fi unbraid runs the profile Desk instead of the profile Work. Compared with now there:", "The default route goes from Netherlands to Direct."),
            notes(here, next))
    }

    /** A profile of the same name replaced by the backup's runs once picked by hand or from its tile: judged against the one here. */
    @Test fun aProfileReplacedUnderItsNameIsJudged() {
        val here = base.copy(profiles = listOf(work))
        assertEquals(listOf("When you switch to the profile Work, compared with Work now:", "The default route goes from Netherlands to Direct."),
            notes(here, here.copy(profiles = listOf(work.copy(defaultRoute = DIRECT)))))
        assertEquals(emptyList<String>(), notes(here, here))
    }

    /** Traffic Android names no app for meets a website's rule before the hold that keeps it off Direct: walked as its own program. */
    @Test fun trafficWithNoAppIsWalkedOnItsOwn() {
        val here = base.copy(defaultRoute = "k1", prefs = Prefs(lan = false))
        val said = notes(here, here.copy(defaultRoute = DIRECT, apps = mapOf("org.vpn" to "nl"), sites = mapOf("bank.example" to DIRECT)))
        assertTrue(said.toString(), "Traffic Android can't tie to an app is no longer refused." in said)
    }

    /** What could not leave before and leaves Direct after is said as going Direct, not only as no longer refused. */
    @Test fun somethingUnreachableThatGoesDirectIsSaidSo() {
        val blocked = base.copy(sites = mapOf("bank.example" to BLOCK))
        assertEquals(listOf("1 website that is blocked now goes Direct."), notes(blocked, blocked.copy(sites = mapOf("bank.example" to DIRECT))))
        assertEquals(listOf("1 website that is blocked now won't be."), notes(blocked, blocked.copy(sites = mapOf("bank.example" to "m1"))))
        val waiting = base.copy(sites = mapOf("bank.example" to "k1"))
        assertEquals(listOf("1 website that can't connect now, because its route waits for a server's config, goes out Direct without it."),
            notes(waiting, waiting.copy(sites = mapOf("bank.example" to DIRECT))))
        val def = base.copy(defaultRoute = "k1")
        assertEquals(listOf("The default route can't connect now, because its route waits for a server's config. After the restore its traffic goes out Direct."),
            notes(def, def.copy(defaultRoute = DIRECT)))
    }

    private val headscale = Home(tailscale = true, tsControlUrl = "https://hs.example.com", tsSuffixAllowed = "corp.example")

    /** Tailscale's states are inputs the check walks, every one of them, not read from the running VPN. */
    @Test fun everyTailscaleStateIsWalked() {
        val here = base.copy(home = headscale, sites = mapOf("box.corp.example" to "nl"))
        val sits = RestoreTraffic.situations(here, here)
        assertEquals(setOf(null, "corp.example"), sits.map { it.suffix }.toSet())
        assertEquals(setOf(false), sits.map { it.signIn }.toSet())
        // Reported, the tailnet answers the name and the website's rule is left out; not reported, the rule holds.
        val rule = { c: RestoreTraffic.Config -> c.rules.any { "box.corp.example" in c.set(it["domain"]) } }
        assertEquals(false, rule(RestoreTraffic.compile(here, { emptySet() }, at = RestoreTraffic.Situation(false, "corp.example"))!!))
        assertEquals(true, rule(RestoreTraffic.compile(here, { emptySet() })!!))
        // So moving it to Direct is said: until Tailscale reports its domain, that website goes Direct.
        assertEquals(listOf("1 website goes from a VPN route to Direct."), notes(here, here.copy(sites = mapOf("box.corp.example" to DIRECT))))
        // Waiting for a sign-in, a Tailscale exit's sign-in pages skip it: a default moved from WireGuard to that exit says so.
        val exit = Route("t", tsExit = "100.101.102.103")
        val wgDefault = base.copy(routes = base.routes + exit, defaultRoute = "w", home = Home())
        assertEquals(listOf("While Tailscale waits for you to sign in, its sign-in pages go Direct."), notes(wgDefault, wgDefault.copy(defaultRoute = "t")))
        assertEquals(setOf(false, true), RestoreTraffic.situations(wgDefault, wgDefault.copy(defaultRoute = "t")).map { it.signIn }.toSet())
    }

    /** The largest setups a backup may hold, built to cost the most: bounded before anything is walked, and stopped when cancelled. */
    @Test fun aHostileBackupIsBoundedBeforeItIsWalked() {
        val box = { n: Int -> WgConf.parse("[Interface]\nPrivateKey = ${"A".repeat(42)}E=\nAddress = 10.9.0.2/32\n[Peer]\nPublicKey = ${"B".repeat(42)}E=\nEndpoint = 203.0.113.7:51820\nAllowedIPs = " +
            (0 until n).joinToString(", ") { "10.${it / 250}.${it % 250}.1/32" } + "\n", "Home") }
        val k = OwnServer("k", wg = wg(5).withoutSecrets(), keyless = true)
        val routes = (0 until 497).map { Route("p$it", Location("nord", it + 1, "NL", "Netherlands", city = "C$it")) } + Route("t", tsExit = "100.101.102.103") + Route("k1", wg = k.wg, own = "k", keyless = true)
        val apps = routes.withIndex().associate { (i, r) -> "org.example.a$i" to r.id }
        val sites = (0 until Sites.MAX).associate { "s$it.example" to routes[it * 7].id }
        val profiles = (0 until 20).map { app.unbraid.data.Profile("P$it", routes[it].id, apps.mapValues { (_, v) -> if (v.hashCode() % 2 == 0) DIRECT else v }, sites = sites) }
        val hostile = { n: Int -> Backup.valid(Setup(routes = routes, servers = listOf(k), defaultRoute = "p0", apps = apps, sites = sites, profiles = profiles,
            onNet = mapOf(app.unbraid.data.WIFI to "P1", app.unbraid.data.MOBILE to "P2"), home = headscale.copy(wg = box(n)), version = VERSION)) }
        val timed = { s: Setup, budget: RestoreTraffic.Budget -> val t = System.nanoTime(); val said = RestoreTraffic.check(s, s.copy(defaultRoute = DIRECT, onNet = mapOf(app.unbraid.data.WIFI to "P3"),
            profiles = profiles.map { it.copy(defaultRoute = DIRECT) }), budget = budget); said to (System.nanoTime() - t) / 1_000_000 }
        // A thousand home ranges after every one of 500 app groups: too large to hold, said at once.
        var built = 0
        val (huge, hugeMs) = timed(hostile(1000), RestoreTraffic.Budget { built++ })
        // Never built: nothing is paid for, beyond the comparisons' first look.
        assertTrue("$hugeMs ms, $built", hugeMs < 2000 && RestoreTraffic.CUT == huge.last().text && built == 0)
        // The largest that is held: every situation, waiting routes, profiles and networks.
        val big = hostile(150)
        assertTrue(RestoreTraffic.size(big) <= RestoreTraffic.MAX_ENTRIES)
        // The estimate made before building is above what the config holds.
        fun entries(x: kotlinx.serialization.json.JsonElement): Int = when (x) { is kotlinx.serialization.json.JsonArray -> x.sumOf(::entries); is kotlinx.serialization.json.JsonObject -> x.values.sumOf(::entries); else -> 1 }
        val text = app.unbraid.vpn.Compiler.compile(app.unbraid.vpn.Compiler.Input(big, emptyMap(), "app.unbraid", false, "", "", blocked = big.routes.map { it.id }.toSet()))
        val full = RestoreTraffic.compile(big, { emptySet() }, true, RestoreTraffic.Situation(true, "corp.example", true))!!
        assertTrue(RestoreTraffic.size(big) >= entries(kotlinx.serialization.json.Json.parseToJsonElement(text)))
        assertTrue(RestoreTraffic.size(big) >= full.rules.sumOf(::entries) + full.dnsRules.sumOf(::entries))
        val budget = RestoreTraffic.Budget()
        val (said, ms) = timed(big, budget)
        println("RESTORE-CHECK hostile within the cap: $ms ms, ${RestoreTraffic.BUDGET - budget.left} spent, $said")
        assertTrue("$ms ms", ms < 6000 && budget.left >= 0 && said.last().text == RestoreTraffic.CUT)
        // Cancelled: the next tick stops it.
        var ticks = 0
        val t = System.nanoTime()
        val stopped = runCatching { timed(big, RestoreTraffic.Budget { if (++ticks > 20) throw java.util.concurrent.CancellationException() }) }
        assertTrue(stopped.exceptionOrNull() is java.util.concurrent.CancellationException && (System.nanoTime() - t) / 1_000_000 < 1500)
    }
}
