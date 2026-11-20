package app.unbraid

import app.unbraid.data.BLOCK
import app.unbraid.data.Backup
import app.unbraid.data.Backup.Reason
import app.unbraid.data.DIRECT
import app.unbraid.data.Filter
import app.unbraid.data.Home
import app.unbraid.data.Location
import app.unbraid.data.Manual
import app.unbraid.data.OwnServer
import app.unbraid.data.PREVIOUS
import app.unbraid.data.Prefs
import app.unbraid.data.Profile
import app.unbraid.data.RestoreTraffic
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.data.VERSION
import app.unbraid.data.WgConf
import app.unbraid.data.favKey
import app.unbraid.data.ident
import app.unbraid.data.restoredFrom
import app.unbraid.ui.WEAK_TITLE
import app.unbraid.ui.homeNote
import app.unbraid.ui.passProblem
import app.unbraid.ui.saveInto
import app.unbraid.ui.weakCaption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/** Hostile and odd backups, made up from a seed: what is read, what is restored, and how passphrases are judged. */
class BackupAdversarialTest {
    private val wg = WgConf.parse("[Interface]\nPrivateKey = ${"A".repeat(42)}E=\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = ${"B".repeat(42)}E=\nEndpoint = 203.0.113.1:51820\nAllowedIPs = 0.0.0.0/0\n", "Box")
    private val evil = Server("se-got-wg-001", "se-got-wg-001.relays.mullvad.net", "198.51.100.66", "C".repeat(42) + "E=", 3, "Gothenburg", "Sweden")
    private fun reason(f: () -> Unit) = try { f(); fail("accepted"); null } catch (e: Backup.Failed) { e.reason }
    private fun text(s: Setup, app: String = "3.0.2") = Backup.export(s, app, 0).toString(Charsets.UTF_8)
    private fun forKeys(p: String) = passProblem(p, p, keys = true) == null

    /** This phone: a VPN default with its provider's blocking, a filter, a blocked app and website, an own server and a home tunnel, LAN closed. */
    private val phone = Setup(
        routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands")), Route("w", wg = wg, own = "s")), defaultRoute = "nl",
        apps = mapOf("org.example.mail" to "w", "org.example.game" to BLOCK, "org.example.bank" to DIRECT), sites = mapOf("tracker.example" to BLOCK, "work.example" to "w"),
        servers = listOf(OwnServer("s", wg = wg)), home = Home(wg = wg.copy(name = "Home", endpoint = "203.0.113.7:51820", publicKey = "D".repeat(42) + "E=", allowedIps = listOf("192.168.50.0/24"))),
        prefs = Prefs(lan = false, filter = Filter("quad9", routes = true, apps = mapOf("org.example.bank" to true))), opts = mapOf("nord" to mapOf("protect" to "on")), version = VERSION,
    )

    /** What the sheet would say for backup [b] opened on [phone]: the backup goes through the file and the reader first. */
    private fun sheet(b: Setup, keys: Boolean = true): List<String> {
        val c = Backup.read(Backup.export(b, "3.0.2", 0, keys))
        return RestoreTraffic.notes(phone, phone.restoredFrom(c.setup, null, 0, c.keys)!!)
    }

    /** Each weakening alone gives exactly its sentences, nothing missing and nothing more; together, counts join and nothing is lost. */
    @Test fun eachWeakeningAloneAndAllTogetherIsSaid() {
        assertEquals(emptyList<String>(), sheet(phone))
        val leaves = "1 app on Direct no longer passes through unbraid, so the DNS filter, website rules and the home tunnel don't reach it."
        val kinds = listOf<Pair<List<String>, (Setup) -> Setup>>(
            listOf("The default route goes from Netherlands to Direct.") to { it.copy(defaultRoute = DIRECT) },
            // The bank app on Direct passed through unbraid only for the filter: without it, it leaves unbraid, its website and home rules too.
            listOf("The DNS filter is turned off.", leaves) to { it.copy(prefs = it.prefs.copy(filter = Filter())) },
            listOf(leaves) to { it.copy(prefs = it.prefs.copy(filter = it.prefs.filter.copy(apps = emptyMap()))) },
            listOf("Apps can reach the local network again.") to { it.copy(prefs = it.prefs.copy(lan = true)) },
            listOf("1 app that is blocked now won't be.") to { it.copy(apps = it.apps - "org.example.game") },
            listOf("1 website that is blocked now goes Direct.") to { it.copy(sites = it.sites - "tracker.example") },
            // Left outside unbraid: its blocked website and home tunnel go with it, said once as the app's.
            listOf("1 app goes from a VPN route to Direct.") to { it.copy(apps = it.apps + ("org.example.mail" to DIRECT)) },
            listOf("1 website goes from a VPN route to Direct.") to { it.copy(sites = it.sites + ("work.example" to DIRECT)) },
            listOf("\"Box\" connects to 198.51.100.66 instead of 203.0.113.1.") to { s -> wg.copy(endpoint = "198.51.100.66:51820").let { e -> s.copy(servers = listOf(OwnServer("s", wg = e)), routes = s.routes.map { if (it.id == "w") it.copy(wg = e) else it }) } },
            listOf("Your home WireGuard uses other DNS servers: 198.51.100.53.") to { it.copy(home = it.home.copy(wg = it.home.wg!!.copy(dns = listOf("198.51.100.53")))) },
            // With the local network closed the rest of a home range goes through the VPN, not Direct: open here.
            listOf("Part of the home network goes Direct instead of through the home tunnel.", "Apps can reach the local network again.") to
                { it.copy(prefs = it.prefs.copy(lan = true), home = it.home.copy(wg = it.home.wg!!.copy(allowedIps = listOf("192.168.50.0/25")))) },
        )
        for ((said, change) in kinds) assertEquals(said, sheet(change(phone)))
        // The provider's own blocking: said once the filter no longer stands in for it.
        val noFilter = phone.copy(prefs = phone.prefs.copy(filter = Filter()))
        assertEquals(listOf("The default route loses the provider's ad and tracker blocking it has now."),
            RestoreTraffic.notes(noFilter, noFilter.copy(opts = mapOf("nord" to mapOf("protect" to "off")))))
        assertEquals(listOf("The DNS filter is turned off.", "\"Box\" connects to 198.51.100.66 instead of 203.0.113.1.", "Your home WireGuard uses other DNS servers: 198.51.100.53.", leaves,
            "The default route goes from Netherlands to Direct.", "1 app and 1 website go from a VPN route to Direct.", "Part of the home network goes Direct instead of through the home tunnel.",
            "Apps can reach the local network again.", "1 app that is blocked now goes Direct.", "1 website that is blocked now goes Direct.",
            // The game no longer blocked and the default on Direct: what Android ties to no app is no longer held off Direct.
            "Traffic Android can't tie to an app is no longer refused and goes Direct."), sheet(kinds.fold(phone) { s, (_, change) -> change(s) }))
    }

    @Test fun weakOwnServersAreNamedUntilAccepted() {
        val ss = Manual.parseSs("ss://aes-256-cfb:secret@198.51.100.20:8388", "Old box")
        val ovpn = Manual.parseOvpn("client\ndev tun\nremote 198.51.100.21 1194\ntls-version-min 1.0\nremote-cert-tls server\n<ca>\n-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n</ca>\n", "Office", "u", "p")
        val b = phone.copy(routes = phone.routes + Route("x", ss = ss, own = "sx") + Route("o", ovpn = ovpn, own = "so"), servers = phone.servers + OwnServer("sx", ss = ss) + OwnServer("so", ovpn = ovpn))
        val c = Backup.read(Backup.export(b, "3.0.2", 0))
        val weak = Backup.weak(phone.restoredFrom(c.setup, null, 0)!!, phone)
        assertEquals(listOf("Old box: aes-256-cfb has no integrity check", "Office: allows TLS 1.0 or 1.1"), weak)
        // Already on this phone, accepted at its import: not asked again.
        val hasBox = phone.copy(servers = phone.servers + OwnServer("sx", ss = ss))
        assertEquals(listOf("Office: allows TLS 1.0 or 1.1"), Backup.weak(hasBox.restoredFrom(c.setup, null, 0)!!, hasBox))
        // Saved without keys: nothing to connect with until the config is added again, through the import's own warning.
        val k = Backup.read(Backup.export(b, "3.0.2", 0, keys = false))
        assertEquals(emptyList<String>(), Backup.weak(phone.restoredFrom(k.setup, null, 0, false)!!, Setup()))
        assertEquals("Accept weak security to restore", WEAK_TITLE)
        assertEquals("Old box: aes-256-cfb has no integrity check\nSomeone between you and this server may be able to read or change the traffic. Restore waits until you accept it.", weakCaption(weak.take(1)))
    }

    @Test fun homeTunnelAndAppChoiceLimits() {
        assertEquals("Your home WireGuard on this phone is removed: this backup has none.", homeNote(keys = true, backupHas = false))
        assertEquals("Your home WireGuard stays as it is on this phone.", homeNote(keys = false, backupHas = false))
        assertEquals("Your home WireGuard is replaced by the one in this backup.", homeNote(keys = true, backupHas = true))
        val s = Setup(routes = listOf(Route("nl", Location("nord"))), defaultRoute = "nl", version = VERSION)
        // More app choices than the restore sheet checks in full still open, as 3.0.2 opened them; the sheet says it was cut.
        val many = (0..Backup.MAX_APPS).associate { "org.example.a$it" to DIRECT }
        Backup.valid(s.copy(apps = many))
        Backup.valid(s.copy(profiles = listOf(Profile("P", "nl", many))))
        assertEquals(RestoreTraffic.CUT, RestoreTraffic.notes(s, s.copy(apps = many)).last())
        assertEquals(RestoreTraffic.CUT, RestoreTraffic.notes(s, s.copy(prefs = s.prefs.copy(filter = s.prefs.filter.copy(apps = many.mapValues { true })))).last())
        assertFalse(RestoreTraffic.CUT in RestoreTraffic.notes(s, s.copy(apps = many - "org.example.a0")))
    }

    /** The sheet's sentences: plain words, no dash used as punctuation. */
    @Test fun restoreTextsUseNoDashes() {
        for (f in listOf("src/main/java/app/unbraid/data/RestoreTraffic.kt", "src/main/java/app/unbraid/ui/Backup.kt")) {
            val texts = Regex("\"([^\"\\\\]|\\\\.)*\"").findAll(java.io.File(f).readText()).map { it.value }.filter { it.length > 12 && ' ' in it }.toList()
            assertTrue(f, texts.size > 10)
            texts.forEach { assertFalse(it, "—" in it || " – " in it || " - " in it) }
        }
    }

    @Test fun aBackupThatNamesAMissingRouteIsRefusedWhole() {
        for (bad in listOf(phone.copy(apps = phone.apps + ("org.example.x" to "gone")), phone.copy(sites = phone.sites + ("x.example" to "gone")), phone.copy(defaultRoute = "gone"),
            phone.copy(profiles = listOf(Profile("P", "gone"))))) {
            val plain = Backup.export(bad, "3.0.2", 0)
            assertEquals(Reason.Invalid, reason { Backup.read(plain) })
            assertEquals(Reason.Unrestorable, reason { Backup.readBack(plain) })
        }
    }

    @Test fun aPinnedServerKeepsOnlyItsNameAndHost() {
        val pinned = Location("mullvad", countryCode = "SE", country = "Sweden", pinned = evil)
        val here = Setup(routes = listOf(Route("se", pinned), Route("nl", Location("nord", 153, "NL", "Netherlands"))), defaultRoute = "se",
            favorites = listOf(pinned), opts = mapOf("ivpn" to mapOf("list" to "198.51.100.53|198.51.100.54", "protect" to "on")), version = VERSION)
        val c = Backup.read(Backup.export(here, "3.0.2", 0))
        val pin = c.setup.route("se")!!.loc.pinned!!
        assertEquals(Server(evil.name, evil.host, "", "", -1, evil.city, evil.country), pin)
        assertEquals(pin, c.setup.favorites.single().pinned)
        // The block list is a pair of resolver addresses: it starts again from the provider's standard one.
        assertEquals(mapOf("protect" to "on"), c.setup.opts["ivpn"])
        // Keeping the setup in use on the same phone shares the pinned route rather than adding a copy of it.
        val r = here.restoredFrom(c.setup, PREVIOUS, 0)!!
        assertEquals(c.setup.routes.size, r.routes.size)
        assertEquals("se", r.profiles.single { it.name == PREVIOUS }.defaultRoute)
        Backup.valid(r)
    }

    /** Keeping the setup in use beside a backup of this very phone keeps the pin this phone trusts, address and key. */
    @Test fun keepingASharedPinnedRouteKeepsThisPhonesPin() {
        val here = Setup(routes = listOf(Route("se", Location("mullvad", countryCode = "SE", country = "Sweden", pinned = evil))), defaultRoute = "se", version = VERSION)
        val c = Backup.read(Backup.export(here, "3.0.2", 0))
        val kept = here.restoredFrom(c.setup, PREVIOUS, 0)!!
        assertEquals(listOf("se"), kept.routes.map { it.id })
        assertEquals(evil, kept.route("se")!!.loc.pinned)
        assertEquals("se", kept.profiles.single { it.name == PREVIOUS }.defaultRoute)
        // Without keeping, the file's name and host only, as before.
        assertEquals("", here.restoredFrom(c.setup, null, 0)!!.route("se")!!.loc.pinned!!.ip)
    }

    /** The filter's own app choices count as app choices; a Tailscale exit is an address; file text never brings invisible characters. */
    @Test fun fileTextIsBoundedWhereverItIsShown() {
        assertEquals(Reason.Invalid, reason { Backup.read(Backup.export(phone.copy(prefs = phone.prefs.copy(filter = phone.prefs.filter.copy(apps = mapOf("not a package" to true)))), "3.0.2", 0)) })
        val ts = { exit: String -> phone.copy(routes = phone.routes + Route("t", tsExit = exit)) }
        for (ok in listOf("100.101.102.103", "fd7a:115c:a1e0::1")) Backup.read(Backup.export(ts(ok), "3.0.2", 0))
        for (bad in listOf("100.64.0.1‮", "‮100.64.0.1", "node.example", "100.64.0.0/10", "1.2.3", ":::", "::::", "1::2::3", "fd7a::115c::1", "12345::1", " 100.64.0.1", "fe80::1%wlan0"))
            assertEquals(bad, Reason.Invalid, reason { Backup.read(Backup.export(ts(bad), "3.0.2", 0)) })
        // As the exit in use too: the compiler would pass it to Tailscale, which finds no node by that text.
        assertEquals(Reason.Invalid, reason { Backup.read(Backup.export(ts(":::").copy(defaultRoute = "t"), "3.0.2", 0)) })
        Backup.read(Backup.export(ts("::ffff:100.64.0.1").copy(defaultRoute = "t"), "3.0.2", 0))
        // A place with texts 3.0.2 let through still opens, cleaned ([OlderBackupsTest], [displayOnlyTextsAreCleanedOnRead]).
        for (n in listOf("Box\u2028x", "Box\u200B", "Box\u2060x", "Box\uFEFF", "Team \uD83D\uDC69\u200D\uD83D\uDCBB")) Backup.read(Backup.export(phone.copy(routes = phone.routes + Route("x", Location("nord", 1, "NL", n))), "3.0.2", 0))
        // An error that names a server from the file shows it without its direction marks or line breaks.
        val damaged = Backup.export(phone.copy(servers = phone.servers + OwnServer("k", wg = wg.copy(name = "‮evil\nname", privateKey = ""), keyless = true)), "3.0.2", 0)
        val e = try { Backup.read(damaged); null } catch (x: Backup.Failed) { x }!!
        assertTrue(e.message!!, e.message!!.none { it == '‮' || it == '\n' })
        // A profile's name may hold a word joiner; the error that names it never shows it.
        val joined = phone.copy(profiles = listOf(Profile("Wo⁠rk", "gone")))
        val p = try { Backup.valid(joined); null } catch (x: Backup.Failed) { x }!!
        assertTrue(p.message!!, "Work" in p.message!! && '⁠' !in p.message!!)
    }

    /**
     * Every name 3.0.2 let through, by its own rule (not blank, at most 256 characters, unchanged by the importers'
     * cleaning), opens in a backup: own servers saved without keys of each kind, a Tailscale route's name and a place, with
     * formatting, direction, zero-width and control characters and long names. Own servers' names are stored as they were
     * and shown without them; a Tailscale route's name, only shown, is stored cleaned.
     */
    @Test fun everyNameAnOlderVersionAcceptedOpens() {
        val accepted = { n: String -> n.isNotBlank() && n.length <= 256 && Manual.cleanName(n, 256) == n }
        val parts = listOf("a", "Z", "0", " ", "-", ".", "é", "ß", "漢", "ا", "👩‍💻", "́", "​", "‌", "‍", "⁠", "⁡", "⁤",
            "﻿", "­", "᠎", "⁪", "⁯", "￹", "󠀁", "‮", "⁦", "‎", "؜", "\u0007", "\u0000", "\n", "\t", " ", "　", " ")
        val g = Random(302)
        val invisible = { n: String -> n.any { Character.getType(it) == Character.FORMAT.toInt() && it != '‌' && it != '‍' } }
        var tried = 0; var hidden = 0
        while (tried < 400) {
            val n = buildString { repeat(if (g.nextInt(4) == 0) 100 + g.nextInt(120) else 1 + g.nextInt(10)) { append(parts[g.nextInt(parts.size)]) } }.let { if (g.nextBoolean()) Manual.cleanName(it, 256) else it }
            if (!accepted(n)) continue
            tried++; if (invisible(n)) hidden++
            val wgK = OwnServer("w", wg = wg.copy(name = n, privateKey = ""), keyless = true)
            val ov = OwnServer("o", ovpn = app.unbraid.data.OvpnProfile(n, ""), keyless = true)
            val ss = OwnServer("x", ss = app.unbraid.data.SsProfile(n, "203.0.113.30", 8388, "aes-256-gcm", ""), keyless = true)
            val s = phone.copy(servers = phone.servers + wgK + ov + ss, routes = phone.routes + Route("t", tsExit = "100.101.102.103", tsName = n) + Route("c", Location("nord", 153, "NL", "Netherlands", city = n)))
            val c = try { Backup.read(Backup.export(s, "3.0.2", 0, keys = false)) } catch (e: Backup.Failed) { fail("${n.map { "%04x".format(it.code) }}: ${e.message}"); return }
            assertEquals(listOf(n, n, n), c.setup.servers.filter { it.id in setOf("w", "o", "x") }.map { it.name })
            assertEquals(Backup.safe(n, Backup.SHOWN), c.setup.route("t")!!.tsName)
            assertTrue(Backup.safe(n, 300).none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() && it != '‌' && it != '‍' })
        }
        assertTrue("$hidden", hidden > 100)
        // The restore sheet names such a server without them.
        val lab = wg.copy(name = "Lab⁠VPN")
        val here = phone.copy(servers = listOf(OwnServer("s", wg = lab)), routes = phone.routes.map { if (it.id == "w") it.copy(wg = lab) else it })
        val moved = here.copy(servers = listOf(OwnServer("s", wg = lab.copy(endpoint = "198.51.100.66:51820"))))
        assertTrue(RestoreTraffic.notes(here, moved).toString(), "\"LabVPN\" connects to 198.51.100.66 instead of 203.0.113.1." in RestoreTraffic.notes(here, moved))
    }

    /**
     * Texts from a file that are only shown (pinned servers' cities and countries, Tailscale names) open, restore and
     * are stored cleaned and bounded; those that also match a provider's list (a place, a pin's name) open and restore
     * as the file has them ([placesFromAFileStillMatchTheirProvider]).
     */
    @Test fun displayOnlyTextsAreCleanedOnRead() {
        val clean = { t: String? -> t == null || (t.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() && it != '‌' && it != '‍' ||
            Character.getType(it) == Character.LINE_SEPARATOR.toInt() || Character.getType(it) == Character.PARAGRAPH_SEPARATOR.toInt() } && app.unbraid.data.graphemes(t) <= Backup.SHOWN) }
        for (n in listOf("Den\u0000Haag\n", "‮Haag⁦", "Ha\u200Bag\uFEFF", "Den\u2028Haag\u2029", "x".repeat(10_000))) {
            val l = Location("mullvad", countryCode = "S$n", country = "Sweden$n", city = n, region = "se-got", pinned = evil.copy(name = n, city = n, country = n))
            val b = phone.copy(routes = phone.routes + Route("p", l) + Route("t", tsExit = "100.101.102.103", tsName = n), favorites = listOf(l))
            for (keys in listOf(true, false)) for (keep in listOf(null, PREVIOUS)) {
                val c = Backup.read(Backup.export(b, "3.0.2", 0, keys))
                val r = phone.restoredFrom(c.setup, keep, 0, c.keys)!!
                val texts = (r.routes.map { it.loc } + r.favorites).flatMap { listOfNotNull(it.pinned).flatMap { p -> listOf(p.city, p.country) } } + r.routes.map { it.tsName }
                assertTrue(texts.filterNot(clean).toString().take(200), texts.all(clean))
                assertEquals(listOf("Sweden$n", n, n), r.route("p")!!.loc.let { listOf(it.country, it.city, it.pinned!!.name) })
                assertEquals("se-got", r.route("p")!!.loc.region)
                assertEquals(evil.host, r.route("p")!!.loc.pinned!!.host)
                Backup.valid(r)
            }
        }
        // A provider's own names are unchanged by the cleaning, so a pinned server's place reads as its provider names it.
        val names = mutableListOf<String>()
        fun walk(e: kotlinx.serialization.json.JsonElement, k: String?) {
            when (e) {
                is kotlinx.serialization.json.JsonObject -> e.forEach { (a, v) -> walk(v, a) }
                is kotlinx.serialization.json.JsonArray -> e.forEach { walk(it, k) }
                is kotlinx.serialization.json.JsonPrimitive -> if (e.isString && k != null && (k.contains("country", true) || k.contains("city", true) || k.lowercase() in setOf("name", "hostname", "region", "place"))) names += e.content
                else -> {}
            }
        }
        for (f in listOf("mullvad_relays", "ivpn_servers", "windscribe_serverlist", "surfshark_clusters", "airvpn_status", "catalog_airvpn", "catalog_expressvpn", "catalog_protonvpn", "catalog_surfshark"))
            walk(kotlinx.serialization.json.Json.parseToJsonElement(javaClass.classLoader!!.getResource("$f.json")!!.readText()), null)
        assertTrue(names.size > 1000)
        assertEquals(emptyList<String>(), names.filter { Backup.safe(it, Backup.SHOWN) != it })
    }

    /**
     * A place or pinned server name from a file still matches its provider's list, its favourite and the same route here
     * whatever it holds: a list that names a place with an invisible character, a decomposed accent, another script or
     * more than [Backup.SHOWN] characters.
     */
    @Test fun placesFromAFileStillMatchTheirProvider() {
        for (place in listOf("Bosnia­and Herzegovina‎", "Côte d'Ivoire", "東京", "Россия", "ایران‌زمین", "Ville ".repeat(20))) {
            val e = app.unbraid.data.Catalog.Entry("wireguard", country = place, city = place, serverName = place, hostname = "a.example.net", wgpubkey = "k", ips = listOf("203.0.113.5"))
            val l = Location("mullvad", countryCode = "SE", country = place, city = place, region = "se-got", pinned = evil.copy(name = place))
            val here = phone.copy(routes = phone.routes + Route("p", l), favorites = listOf(l))
            for (keys in listOf(true, false)) {
                val c = Backup.read(Backup.export(here, "3.0.2", 0, keys))
                val r = here.restoredFrom(c.setup, null, 0, c.keys)!!
                val got = r.route("p")!!.loc
                assertEquals(place, 1, app.unbraid.data.Catalog.pick(listOf(e), got.copy(region = null)).size)
                assertEquals(place, l.ident(), got.ident())
                assertEquals(place, l.favKey(), r.favorites.single().favKey())
                assertEquals(place, Backup.bare(l), got)
            }
        }
    }

    /**
     * A country code and a group go into provider and speed-test requests: from a file they are kept only in a shape
     * that can't carry one (a code that also matches the route to the provider's list stays as saved), so no line break
     * or other text can reach a request; the route still restores.
     */
    /** A saved protocol goes into NordVPN's request: anything that isn't an identifier counts as none saved. */
    @Test fun aProtocolFromAFileNeverCarriesARequest() {
        assertEquals("openvpn_tcp", app.unbraid.data.Nord.tech(Location("nord", protocol = "openvpn_tcp")))
        for (bad in listOf("wireguard_udp&limit=9", "openvpn_udp\r\nX: y", "", "WireGuard UDP")) assertEquals(bad, "wireguard_udp", app.unbraid.data.Nord.tech(Location("nord", protocol = bad)))
    }

    @Test fun codesAndGroupsFromAFileNeverCarryARequest() {
        for ((code, kept) in listOf("SE" to "SE", "se" to "se", "GBR" to "GBR", "S\r\nX-Injected: 1" to null, "SE\n" to null, "" to "", "S" to "S", "SÉ" to "SÉ", "S1" to "S1", "··" to "··", "a&b=" to null, "a&b" to null, "a/b" to null, "a b" to null, "%0A" to null)) {
            for ((group, g) in listOf("legacy_p2p" to "legacy_p2p", "legacy_p2p\r\nX: y" to null, "a&limit=1" to null)) {
                val l = Location("nord", 208, code, "Sweden", group = group)
                val c = Backup.read(Backup.export(phone.copy(routes = phone.routes + Route("p", l), favorites = listOf(l)), "3.0.2", 0))
                assertEquals(code, l.copy(countryCode = kept, group = g), c.setup.route("p")!!.loc)
                assertEquals(code, l.copy(countryCode = kept, group = g), c.setup.favorites.single())
            }
        }
        // At the speed test's own request, whatever the source: only two letters, and no path with a line break.
        assertEquals("SE", app.unbraid.stats.Ndt7.code("se"))
        for (bad in listOf("SWE", "S\r\nX", "S", "", null)) assertNull(app.unbraid.stats.Ndt7.code(bad))
        val e = try { app.unbraid.vpn.Probe.get("none", "example.net", "/a?country=S\r\nX: y"); null } catch (e: IllegalArgumentException) { e }
        assertEquals("Bad request path", e?.message)
    }

    /** A website an older version saved, that the stricter reader of today refuses, keeps the backup restorable: it stays as this phone keeps it. */
    @Test fun anOlderWebsiteNameKeepsTheBackupRestorable() {
        val old = phone.copy(sites = phone.sites + ("x.0xff" to "nl") + ("xn--lcka6bwa0bnl60b.jp" to DIRECT), profiles = listOf(Profile("Work", "nl", sites = mapOf("y.0x1f" to DIRECT))))
        val c = Backup.read(Backup.open(Backup.seal(Backup.export(old, "3.0.2", 0), "pass phrase", logN = 10), "pass phrase"))
        assertEquals("nl", c.setup.sites["x.0xff"])
        assertEquals(DIRECT, c.setup.sites["xn--lcka6bwa0bnl60b.jp"])
        // The restore check builds and walks it as the phone runs it.
        val restored = phone.restoredFrom(c.setup, null, 0)!!
        assertFalse(RestoreTraffic.notes(phone, restored).toString(), RestoreTraffic.UNKNOWN in RestoreTraffic.notes(phone, restored))
        assertEquals(DIRECT, c.setup.profiles.single().sites["y.0x1f"])
        for (bad in listOf("Example.com", "x..com", "-x.com", "x.123", "com", "a b.com")) assertEquals(bad, Reason.Invalid, reason { Backup.read(Backup.export(phone.copy(sites = mapOf(bad to "nl")), "3.0.2", 0)) })
    }

    /** A name with nothing visible in it, joiners and other invisible characters only, is printed as the sheet's fallback; joiners inside a name stay. */
    @Test fun aNameWithNothingVisibleGetsTheFallback() {
        val invisible = listOf("‍", "‌", "⁠", "­", "﻿", "‍‌", " ‍ ", "⁠­﻿‍", "͏️", "ㅤ", "󠀁")
        for (n in invisible) assertEquals(n.map { "%04x".format(it.code) }.toString(), "a VPN", Backup.label(n, "a VPN"))
        for (n in listOf("👩‍💻", "می‌خواهم", "Lab‍VPN")) assertEquals(n, Backup.label(n, "a VPN"))
        assertEquals("LabVPN", Backup.label("⁠Lab­VPN﻿", "a VPN"))
        // The own-server sentences fall back to "An own server" on an empty label.
        assertEquals("", Backup.label("‍‌", "", 256))
        // The sentence the reviewer saw: an own server named only a joiner, the default route restored to Direct.
        val joined = wg.copy(name = "‍")
        val here = phone.copy(routes = phone.routes.map { if (it.id == "w") it.copy(wg = joined) else it }, servers = listOf(OwnServer("s", wg = joined)), defaultRoute = "w")
        val c = Backup.read(Backup.export(here, "3.0.2", 0))
        assertEquals("‍", c.setup.servers.single().name)
        assertEquals("The default route goes from a VPN to Direct.", RestoreTraffic.notes(here, here.copy(defaultRoute = DIRECT)).first())
    }

    /** Counting in another script is a pattern too: one through eight in Chinese numerals is not a hard passphrase. */
    @Test fun countingInAnyScriptIsAPattern() {
        for (p in listOf("一二三四五六七八", "八七六五四三二一", "壹贰叁肆伍陆柒捌", "甲乙丙丁戊己庚辛", "あいうえおかきく", "αβγδεζηθ", "абвгдежз", "ⅠⅡⅢⅣⅤⅥⅦⅧ", "가나다라마바사아", "一二三四五六七八九十"))
            assertFalse("$p ${Backup.strength(p)}", forKeys(p))
        // Decorated digits and letters count as the plain ones they stand for.
        for (p in listOf("①②③④⑤⑥⑦⑧", "⑧⑦⑥⑤④③②①", "➀➁➂➃➄➅➆➇", "❶❷❸❹❺❻❼❽", "ⓐⓑⓒⓓⓔⓕⓖⓗ", "ａｂｃｄｅｆｇｈ", "¹²³⁴⁵⁶⁷⁸", "𝟏𝟐𝟑𝟒𝟓𝟔𝟕𝟖", "⑴⑵⑶⑷⑸⑹⑺⑻", "①②③④⑤⑥⑦⑧⑨⑩⑪⑫", "①2③4⑤6⑦8", "ａbｃdｅfｇh", "①2③4⑤6⑦8ａbｃdｅfｇh"))
            assertFalse("$p ${Backup.strength(p)}", forKeys(p))
        // Han characters that count nothing still count.
        assertEquals(2, Backup.strength("龍一象山水火風雷木金土日月星雲花").first)
    }

    @Test fun aMacBackupIsNamed() {
        val mac = """{"format":1,"platform":"mac","app":"1.4.0","created":0,"keys":true,"mac":{"routes":[],"defaultRoute":"direct"}}"""
        assertEquals(Reason.OtherPlatform, reason { Backup.read(mac.toByteArray()) })
        assertEquals("This backup was made by unbraid for Mac. Moving a setup between Mac and Android isn't supported yet.", Reason.OtherPlatform.text)
        // Not a Mac backup just because a key is called "mac".
        assertEquals(Reason.Invalid, reason { Backup.read("""{"mac":1,"setup":5}""".toByteArray()) })
        for (junk in listOf("[]", "null", "\"mac\"", "{\"mac\":" + "[".repeat(100_000))) assertEquals(junk.take(12), Reason.Invalid, reason { Backup.read(junk.toByteArray()) })
    }

    @Test fun textsFromTheFileAreBoundedAndPlain() {
        val s = Setup(routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands"))), defaultRoute = "nl", version = VERSION)
        // The version that made it: a short version string or nothing.
        assertEquals("3.0.2-beta+1", Backup.read(Backup.export(s, "3.0.2-beta+1", 0)).app)
        for (app in listOf("x".repeat(1 shl 20), "3.0‮", "<b>3</b>", "", " 3.0", "3".repeat(33))) assertEquals("", Backup.read(Backup.export(s, app, 0)).app)
        // Places and Tailscale node names open as 3.0.2 opened them, unchanged; the sheet shows them cleaned.
        val nl = s.routes[0]
        for (odd in listOf(
            nl.copy(loc = nl.loc.copy(country = "Nether‮lands")), nl.copy(loc = nl.loc.copy(city = "x".repeat(300))), nl.copy(loc = nl.loc.copy(region = "a\nb")),
            nl.copy(loc = nl.loc.copy(country = " ")), nl.copy(loc = nl.loc.copy(pinned = evil.copy(name = "⁦evil"))), nl.copy(loc = nl.loc.copy(group = "g\u0000")),
        )) assertEquals(odd, Backup.valid(s.copy(routes = listOf(odd))).routes.single())
        val ts = Route("t", tsExit = "100.64.0.1", tsName = "node‮ ")
        assertEquals(ts, Backup.valid(s.copy(routes = s.routes + ts, favorites = listOf(Location("nord", country = "x".repeat(257))))).route("t"))
        assertEquals("The default route goes from node to Direct.", RestoreTraffic.notes(s.copy(routes = s.routes + ts, defaultRoute = "t"), s.copy(defaultRoute = DIRECT)).first())
    }

    @Test fun aBackupThatCouldNotBeRestoredIsNeverSaved() {
        val s = Setup(routes = listOf(Route("nl", Location("nord"))), defaultRoute = "nl", apps = mapOf("org.example.a" to "gone"), version = VERSION)
        val e = try { Backup.readBack(Backup.export(s, "3.0.2", 0)); null } catch (e: Backup.Failed) { e }
        assertEquals(Reason.Unrestorable, e!!.reason)
        assertEquals("Nothing was saved, because this backup couldn't be restored: an app or the default route names a route that isn't there", e.message)
        Backup.readBack(Backup.export(s.copy(apps = emptyMap()), "3.0.2", 0))
    }

    /** A document the save picker gave; opening it for writing empties it, as mode "wt" does. */
    private class Doc(var bytes: ByteArray, val refused: Boolean = false, val breaks: Boolean = false, val unreadable: Boolean = false) {
        var opened = false
        fun save(b: ByteArray) = saveInto(b, { check(!unreadable); bytes.isEmpty() }) {
            if (refused) throw java.io.IOException("refused")
            opened = true; bytes = ByteArray(0)
            object : java.io.ByteArrayOutputStream() {
                override fun write(x: ByteArray, off: Int, len: Int) { if (breaks) throw java.io.IOException("full") else super.write(x, off, len) }
                override fun close() { bytes = toByteArray() }
            }
        }
    }

    /** A save never touches a file that holds something, as the one the user chose to replace: only an empty one is written to. */
    @Test fun aSaveNeverTouchesTheFileItWasToReplace() {
        val old = "an older backup".toByteArray()
        // Whether the write would work, fail at the start or fail once the file is emptied: it is never opened. Nor one that can't be read.
        for (doc in listOf(Doc(old), Doc(old, refused = true), Doc(old, breaks = true), Doc(ByteArray(0), unreadable = true))) {
            val was = doc.bytes
            assertNull(doc.save(byteArrayOf(1)))
            assertFalse(doc.opened)
            assertArrayEquals(was, doc.bytes)
        }
        // An empty one: written, or still there, empty, when the write fails.
        Doc(ByteArray(0)).let { assertEquals(true, it.save(byteArrayOf(7))); assertArrayEquals(byteArrayOf(7), it.bytes) }
        for (doc in listOf(Doc(ByteArray(0), refused = true), Doc(ByteArray(0), breaks = true))) {
            assertEquals(false, doc.save(byteArrayOf(1)))
            assertArrayEquals(ByteArray(0), doc.bytes)
        }
    }

    @Test fun aRouteWithoutAnOwnServerGetsItsConfigBackOnTheSamePhone() {
        val here = Setup(routes = listOf(Route("w", wg = wg)), defaultRoute = "w", apps = mapOf("org.example.a" to DIRECT), version = VERSION)
        val c = Backup.read(Backup.export(here, "3.0.2", 0, keys = false))
        assertTrue(c.setup.servers.single().keyless)
        val r = here.restoredFrom(c.setup, null, 0, c.keys)!!
        assertTrue(r.servers.none { it.keyless } && r.routes.none { it.keyless })
        assertEquals(wg, r.route("w")!!.wg)
        Backup.valid(r)
        // Keeping the setup in use: the same route, not a second working copy beside it.
        val kept = here.restoredFrom(c.setup, PREVIOUS, 0, c.keys)!!
        assertEquals(1, kept.routes.size)
        assertEquals("w", kept.profiles.single().defaultRoute)
        // Another phone's route of that name with another server is not taken for it.
        val other = here.copy(routes = listOf(Route("w", wg = wg.copy(endpoint = "203.0.113.9:51820"))))
        assertTrue(other.restoredFrom(c.setup, null, 0, c.keys)!!.servers.single().keyless)
        // Nor is one tied to an own server of its own.
        assertTrue(here.copy(routes = listOf(Route("w", wg = wg, own = "x")), servers = listOf(OwnServer("x", wg = wg))).restoredFrom(c.setup, null, 0, c.keys)!!.servers.any { it.keyless })
        // An OpenVPN file holds only a name: a route of that name here, pointing at another server now, is not taken for it.
        val office = { host: String -> app.unbraid.data.Manual.parseOvpn("client\ndev tun\nremote $host 1194\n<ca>\n-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n</ca>\n", "Office", "", "") }
        val then = Setup(routes = listOf(Route("r", ovpn = office("198.51.100.21"))), defaultRoute = "r", version = VERSION)
        val o = Backup.read(Backup.export(then, "3.0.2", 0, keys = false))
        val now = then.copy(routes = listOf(Route("r", ovpn = office("198.51.100.22"))))
        val restored = now.restoredFrom(o.setup, null, 0, o.keys)!!
        assertTrue(restored.route("r")!!.keyless && restored.servers.single().keyless)
        // Everything the file keeps must match: a WireGuard route here with other allowed addresses is not taken for it.
        assertTrue(here.copy(routes = listOf(Route("w", wg = wg.copy(allowedIps = listOf("10.0.0.0/8"))))).restoredFrom(c.setup, null, 0, c.keys)!!.servers.single().keyless)
        // Nor a Shadowsocks route here without the plugin the file names: the route waits for its config, plugin intact.
        val ss = app.unbraid.data.SsProfile("Box", "203.0.113.30", 8388, "aes-256-gcm", "secret", "obfs-local", "obfs=http")
        val sThen = Setup(routes = listOf(Route("s", ss = ss)), defaultRoute = "s", version = VERSION)
        val so = Backup.read(Backup.export(sThen, "3.0.2", 0, keys = false))
        val bare = sThen.copy(routes = listOf(Route("s", ss = ss.copy(plugin = null, pluginOpts = null)))).restoredFrom(so.setup, null, 0, so.keys)!!.route("s")!!
        assertTrue(bare.keyless)
        assertEquals("obfs-local", bare.ss!!.plugin)
        // The same route, plugin and all, still gives it back.
        assertEquals(ss, sThen.restoredFrom(so.setup, null, 0, so.keys)!!.route("s")!!.ss)
    }

    /** An own server saved without keys gets its config back only from this phone's server of that id with everything the file keeps the same. */
    @Test fun anOwnServerWithoutKeysTakesItsConfigBackOnlyFromAnIdenticalOne() {
        val ss = app.unbraid.data.SsProfile("Box", "203.0.113.30", 8388, "aes-256-gcm", "secret", "obfs-local", "obfs=http")
        val then = Setup(routes = listOf(Route("s", ss = ss, own = "x")), defaultRoute = "s", servers = listOf(OwnServer("x", ss = ss)), version = VERSION)
        val c = Backup.read(Backup.export(then, "3.0.2", 0, keys = false))
        // Here the server of that id, name, address, port and method has no plugin: it is not taken for the file's.
        val plain = ss.copy(plugin = null, pluginOpts = null)
        val r = then.copy(routes = listOf(Route("s", ss = plain, own = "x")), servers = listOf(OwnServer("x", ss = plain))).restoredFrom(c.setup, null, 0, c.keys)!!
        assertTrue(r.servers.single().keyless && r.route("s")!!.keyless)
        assertEquals("obfs-local", r.servers.single().ss!!.plugin)
        // Nor one with other allowed addresses, for WireGuard.
        val wThen = Setup(routes = listOf(Route("w", wg = wg, own = "y")), defaultRoute = "w", servers = listOf(OwnServer("y", wg = wg)), version = VERSION)
        val w = Backup.read(Backup.export(wThen, "3.0.2", 0, keys = false))
        val narrow = wg.copy(allowedIps = listOf("10.0.0.0/8"))
        assertTrue(wThen.copy(routes = listOf(Route("w", wg = narrow, own = "y")), servers = listOf(OwnServer("y", wg = narrow))).restoredFrom(w.setup, null, 0, w.keys)!!.servers.single().keyless)
        // The same server, plugin and all, still gives it back.
        val same = then.restoredFrom(c.setup, null, 0, c.keys)!!
        assertEquals(OwnServer("x", ss = ss), same.servers.single())
        assertEquals(ss, same.route("s")!!.ss)
        assertEquals(wg, wThen.restoredFrom(w.setup, null, 0, w.keys)!!.servers.single().wg)
    }

    @Test fun everySaveNeedsEightCharactersAndKeysNeedAHardOne() {
        assertEquals("", passProblem("", "", false))
        assertEquals("A passphrase needs at least 8 characters.", passProblem("a", "a", false))
        assertEquals("A passphrase needs at least 8 characters.", passProblem("😀😀😀😀😀😀😀", "😀😀😀😀😀😀😀", false))
        assertNull(passProblem("abcdefgh", "abcdefgh", false))
        assertEquals("", passProblem("abcdefgh", "", false))
        assertEquals("The two passphrases are different.", passProblem("abcdefgh", "abcdefgx", false))
        assertEquals("With keys and passwords included, choose a passphrase that is harder to guess.", passProblem("abcdefgh", "abcdefgh", true))
        // Fair is not enough for keys.
        val fair = "kittenrain42"
        assertEquals(1, Backup.strength(fair).first)
        assertFalse(forKeys(fair))
        assertTrue(forKeys("correct horse battery staple"))
    }

    @Test fun thePassphraseJudge() {
        val g = Random(0x9A55)
        fun <T> pick(l: List<T>) = l[g.nextInt(l.size)]
        val wrong = mutableListOf<String>()
        val stems = listOf("horse", "pass", "word", "test", "summer", "london", "Pass", "ab12")
        val seps = listOf(" ", "-", "_", ".", ",", "/", "+", "|", ":", ";", "*", "~", "", "  ", "\t", "!", "#", "@", "=", "\\")
        repeat(4000) {
            val stem = pick(stems); val sep = pick(seps); val n = 4 + g.nextInt(5); val counter = g.nextBoolean()
            val p = (1..n).joinToString(sep) { stem + if (counter) "$it" else "" }
            if (forKeys(p)) wrong += "repeat: $p"
        }
        for (w in listOf("qwertyuiop", "asdfghjkl;", "zxcvbnm,./", "1qaz2wsx3edc4rfv", "!QAZ2wsx#EDC4rfv", "zaq1xsw2cde3vfr4", "qazwsxedcrfvtgbyhn",
            "1q2w3e4r5t6y7u8i", "qwertzuiopasdfgh", "azertyuiopqsdfgh", "poiuytrewqlkjhgf", "mnbvcxzlkjhgfdsa", "1234qwerasdfzxcv", "q1w2e3r4t5y6u7i8o9p0",
            "pass1 pass2 pass3 pass4", "word1 word2 word3 word4", "abd1 abd2 abd3 abd4", "test1 test2 test3 test4", "aaaaaaaaaaaaaaaaaaaaaaaa", "abcabcabcabcabcabc",
            "Password1234!", "qwertyqwertyqwerty", "unbraid unbraid unbraid unbraid", "1234567890123456", "abcdefghijklmnopqrstuvwxyz", "zyxwvutsrqponmlkjihgfedcba",
            "Aa1!Aa1!Aa1!Aa1!", "horse horse horse horse", "letmein letmein letmein", "password1 password2 password3 password0", "aaaa aaaa aaaa aaaa",
            (1..10).joinToString(" ") { "password${it % 10}" }, (1..10).joinToString(" ") { "${it}password" }, (1..8).joinToString(" ") { "word$it" },
            (1..6).joinToString("+") { "horse$it" }, (1..7).joinToString("") { "pass$it" })) if (forKeys(w)) wrong += "walk: $w ${Backup.strength(w)}"
        // Digits of any script make runs by their value; a short run of them is easy.
        assertEquals(0, Backup.strength("١٢٣٤٥٦٧٨٩٠").first)
        // Equivalent spellings are judged alike.
        for ((a, b) in listOf("café crème brûlée naïve" to "café crème brûlée naïve",
            "ṩṩṩZebra42!" to "ṩṩṩZebra42!", "ñandú pérez" to "ñandú pérez"))
            if (Backup.strength(a) != Backup.strength(b)) wrong += "apart: $a"
        repeat(500) {
            val base = String(CharArray(14) { 'a' + g.nextInt(26) })
            if (Backup.strength(base.replace("e", "é")) != Backup.strength(base.replace("e", "é"))) wrong += "NFC/NFD: $base"
        }
        // Diceware-style: five to seven words from a list of 300, one repeated: hard.
        val syll = listOf("ba", "ko", "ri", "tem", "lu", "mor", "vash", "pel", "dri", "qua", "zon", "fi", "gut", "hes", "jor", "nix", "sal", "wey")
        val list = HashSet<String>().apply { while (size < 300) add(pick(syll) + pick(syll) + if (g.nextBoolean()) pick(syll) else "") }.sorted()
        var weak = 0
        repeat(1000) {
            val phrase = list.shuffled(g).take(5 + g.nextInt(3)).toMutableList()
            phrase.add(g.nextInt(phrase.size + 1), phrase[g.nextInt(phrase.size)])
            val p = phrase.joinToString(pick(listOf(" ", "-", " ")))
            if (!forKeys(p)) { weak++; if (weak <= 5) wrong += "diceware refused: $p" }
        }
        for (stem in listOf("pass", "horse", "Summer", "word")) for (sep in listOf("", " ", "-", "+", "/"))
            (1..20).joinToString(sep) { "$stem$it" }.let { if (forKeys(it)) wrong += "counter series: $it" }
        // Random text in scripts without letter case and emoji, 14 to 20 characters (letters with a case count as a-z does): good enough for keys.
        val scripts = listOf(0x4E00..0x9FFF, 0x627..0x64A, 0xAC00..0xD7A3, 0x5D0..0x5EA, 0x905..0x939, 0x1F300..0x1F5FF, 0x1F600..0x1F64F)
        var refused = 0
        repeat(1000) {
            val r = pick(scripts)
            val p = buildString { repeat(14 + g.nextInt(7)) { val s = if (g.nextInt(100) < 85) r else pick(scripts); appendCodePoint(s.first + g.nextInt(s.last - s.first + 1)) } }
            if (!forKeys(p)) { refused++; if (refused <= 5) wrong += "random refused: $p ${Backup.strength(p)}" }
        }
        // Sixteen random Han characters are hard; random flags too.
        assertEquals(2, Backup.strength("龍一象山水火風雷木金土日月星雲花").first)
        assertEquals(2, Backup.strength("🇳🇱🇯🇵🇧🇷🇨🇦🇮🇹🇰🇪🇳🇴🇦🇺🇲🇽🇮🇳").first)
        // A long repeat at the start doesn't hide what follows.
        val tail = "Xk9#mQ2\$vL7@pR4!"
        assertEquals(Backup.strength("0$tail"), Backup.strength("0".repeat(300) + tail))
        assertEquals(2, Backup.strength("0".repeat(5000) + tail).first)
        // Long texts are judged quickly, the meter's worst shapes too: repeats, two letters at random, every character once.
        for (p in listOf("correct horse ".repeat(20_000), String(CharArray(5000) { if (g.nextBoolean()) 'a' else 'b' }), String(CharArray(5000) { (33 + g.nextInt(94)).toChar() }))) {
            val t = System.nanoTime()
            Backup.strength(p)
            assertTrue("${p.take(20)}: ${(System.nanoTime() - t) / 1_000_000} ms", (System.nanoTime() - t) < 500_000_000)
        }
        assertEquals(emptyList<String>(), wrong)
    }
}
