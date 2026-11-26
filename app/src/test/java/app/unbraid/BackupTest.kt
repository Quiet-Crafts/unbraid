package app.unbraid

import app.unbraid.data.BLOCK
import app.unbraid.data.Backup
import app.unbraid.data.Backup.Reason
import app.unbraid.data.InstallState
import app.unbraid.data.DIRECT
import app.unbraid.data.Filter
import app.unbraid.data.Home
import app.unbraid.data.Location
import app.unbraid.data.MAX_PROFILES
import app.unbraid.data.Manual
import app.unbraid.data.OwnServer
import app.unbraid.data.PREVIOUS
import app.unbraid.data.Prefs
import app.unbraid.data.Profile
import app.unbraid.data.Route
import app.unbraid.data.Scrypt
import app.unbraid.data.Setup
import app.unbraid.data.VERSION
import app.unbraid.data.WAIT_MS
import app.unbraid.data.WgConf
import app.unbraid.data.newSetup
import app.unbraid.data.Providers
import app.unbraid.data.refilled
import app.unbraid.data.restoredFrom
import app.unbraid.vpn.LinkPicker
import app.unbraid.ui.BACKUP_HOLDS
import app.unbraid.ui.BACKUP_LEAVES
import app.unbraid.ui.RestoreGate
import app.unbraid.ui.keepKnown
import app.unbraid.ui.keysSwitch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom

class BackupTest {
    private val key = "A".repeat(42) + "E="
    private val wgText = "[Interface]\nPrivateKey = $key\nAddress = 10.0.0.2/32\nDNS = 10.0.0.1\n[Peer]\nPublicKey = ${"B".repeat(42)}E=\nEndpoint = 203.0.113.1:51820\nAllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 15\n"
    private val wg = WgConf.parse(wgText, "Home box")
    private val ovpn = Manual.parseOvpn("client\ndev tun\nremote 203.0.113.2 1194\nremote-cert-tls server\n<ca>\n-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n</ca>\n", "Office", "user-secret", "pass-secret")
    private val ss = Manual.parseSs("ss://chacha20-ietf-poly1305:p%40ss%2Bw%20rd@[2001:db8::1]:8388/?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dexample.com", "Outline é")

    /** A setup using every part a backup carries. */
    private fun full() = Setup(
        routes = listOf(
            Route("nl", Location("nord", 153, "NL", "Netherlands")),
            Route("w1", wg = wg, own = "s1"), Route("o1", ovpn = ovpn, own = "s2"), Route("x1", ss = ss, own = "s3"),
            Route("m1", Location("mullvad", countryCode = "SE", country = "Sweden")),
        ),
        defaultRoute = "nl", apps = mapOf("org.example.a" to "w1", "org.example.b" to DIRECT, "org.example.c" to "m1"),
        servers = listOf(OwnServer("s1", wg = wg), OwnServer("s2", ovpn = ovpn), OwnServer("s3", ss = ss)),
        home = Home(tailscale = true, tsAuthKey = "tskey-auth-SECRET", tsControlUrl = "https://hs.example", subnets = listOf("192.168.77.0/24"), wg = wg.copy(name = "LAN", allowedIps = listOf("192.168.1.0/24"))),
        prefs = Prefs(stats = true, lan = false, filter = Filter("adguard", routes = true)),
        opts = mapOf("nord" to mapOf("protect" to "off")),
        profiles = listOf(Profile("Work", "o1", mapOf("org.example.a" to "x1")), Profile("Home", "nl", mapOf("org.example.c" to "m1"))),
        profile = "Home", version = VERSION,
    )

    private fun fast(plain: ByteArray, pass: String = "correct horse") = Backup.seal(plain, pass, logN = 10, r = 8, p = 1)
    private fun reason(f: () -> Unit) = try { f(); fail("accepted"); null } catch (e: Backup.Failed) { e.reason }
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun scryptMatchesRfc7914() {
        assertArrayEquals(hex("fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b3731622eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640"),
            Scrypt.derive("password".toByteArray(), "NaCl".toByteArray(), 1024, 8, 16, 64))
        assertArrayEquals(hex("7023bdcb3afd7348461c06cd81fd38ebfda8fbba904f8e3ea9b543f6545da1f2d5432955613f0fcf62d49705242a9af9e61e85dc0d651e40dfcf017b45575887"),
            Scrypt.derive("pleaseletmein".toByteArray(), "SodiumChloride".toByteArray(), 16384, 8, 1, 64))
    }

    @Test fun roundTripWithTheDefaultSettings() {
        val s = full()
        val t = System.nanoTime()
        val file = Backup.seal(Backup.export(s, "2.9.1", 1_700_000_000_000), "correct horse battery staple")
        val back = Backup.read(Backup.open(file, "correct horse battery staple"))
        println("seal + open at the default settings: ${(System.nanoTime() - t) / 1_000_000} ms")
        assertEquals("2.9.1", back.app)
        assertEquals(Backup.contents(s), back.setup)
        // A different passphrase spelling of the same text (decomposed accent) opens it too.
        val nfd = Backup.seal("x".toByteArray(), "café", logN = 10, r = 8, p = 1)
        assertArrayEquals("x".toByteArray(), Backup.open(nfd, "café"))
    }

    @Test fun eachSaveIsDifferent() {
        val plain = "same".toByteArray()
        assertFalse(fast(plain).contentEquals(fast(plain)))
    }

    @Test fun wrongPassphraseIsToldApart() {
        val f = fast("x".toByteArray())
        assertEquals(Reason.Passphrase, reason { Backup.open(f, "correct horsE") })
        assertEquals(Reason.Passphrase, reason { Backup.open(f, "") })
    }

    @Test fun everyKindOfDamageIsCaught() {
        val f = fast(Backup.export(full(), "2.9.1", 0))
        for (i in f.indices) {
            val d = f.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            val r = reason { Backup.open(d, "correct horse") }
            // The magic itself: not a backup; the version byte made larger: newer; everything else: damaged.
            val want = when { i < 15 -> Reason.NotBackup; i == 15 -> Reason.Damaged.takeIf { d[15].toInt() < 1 } ?: Reason.Newer; else -> Reason.Damaged }
            assertEquals("byte $i", want, r)
        }
        // Damage that also fixes the checksum: the cipher's own check still catches it, and the header is bound to it.
        for (i in listOf(30, f.size - 40, 90)) {
            val d = f.copyOf().also { it[i] = (it[i].toInt() xor 0x40).toByte() }
            refreshSum(d)
            assertTrue("byte $i", reason { Backup.open(d, "correct horse") } in setOf(Reason.Damaged, Reason.Passphrase))
        }
        for (n in listOf(0, 5, 15, 16, 40, 100, f.size - 33, f.size - 1)) {
            val r = reason { Backup.open(f.copyOf(n), "correct horse") }
            assertTrue("cut at $n: $r", r == Reason.Damaged || (n < 15 && r == Reason.NotBackup))
        }
        assertEquals(Reason.Damaged, reason { Backup.open(f + 0, "correct horse") })
        assertEquals(Reason.NotBackup, reason { Backup.open("[Interface]\nPrivateKey = x".toByteArray(), "p") })
    }

    private fun refreshSum(d: ByteArray) =
        java.security.MessageDigest.getInstance("SHA-256").apply { update(d, 0, d.size - 32) }.digest().copyInto(d, d.size - 32)

    @Test fun aNewerFormatIsRefusedPlainly() {
        val f = fast("x".toByteArray()).also { it[15] = 2; refreshSum(it) }
        assertEquals(Reason.Newer, Backup.precheck(f))
        assertEquals(Reason.Newer, reason { Backup.open(f, "correct horse") })
        assertEquals("This backup was made by a newer unbraid. Update unbraid, then restore it.", Reason.Newer.text)
        // A newer setup inside a file this version reads: refused as newer too, before anything is checked.
        val newer = Backup.export(full(), "3.0", 0).toString(Charsets.UTF_8).replace("\"version\":${full().stamped.version}", "\"version\":${VERSION + 1}").toByteArray()
        assertEquals(Reason.Newer, reason { Backup.read(newer) })
        val newerFormat = Backup.export(full(), "3.0", 0).toString(Charsets.UTF_8).replaceFirst("\"format\":1", "\"format\":3").toByteArray()
        assertEquals(Reason.Newer, reason { Backup.read(newerFormat) })
    }

    @Test fun hostileFilesAreBoundedInMemoryAndTime() {
        val f = fast("x".toByteArray())
        // Settings asking for gigabytes or hours are refused before any key is derived.
        for ((at, v) in listOf(17 to 30, 17 to 21, 18 to 255, 19 to 255, 16 to 2, 17 to 0)) {
            val d = f.copyOf().also { it[at] = v.toByte(); refreshSum(it) }
            val t = System.nanoTime()
            assertEquals("byte $at = $v", Reason.Damaged, reason { Backup.open(d, "correct horse") })
            assertTrue((System.nanoTime() - t) < 500_000_000)
        }
        assertFalse(Backup.allowed(20, 16, 4))
        assertTrue(Backup.allowed(Backup.LOG_N, Backup.R, Backup.P))
        assertEquals(Reason.TooLarge, Backup.precheck(ByteArray(Backup.MAX + 1)))
        // Contents that aren't a setup, or a huge one, are refused without a crash.
        for (junk in listOf("", "{", "[]", "{\"setup\":5}", "{\"setup\":{\"routes\":[{}]}}", "\u0000".repeat(1000), "{\"setup\":{\"routes\":" + "[".repeat(5000))) {
            assertEquals(junk.take(20), Reason.Invalid, reason { Backup.read(junk.toByteArray()) })
        }
        val many = full().copy(routes = (1..600).map { Route("r$it", Location("nord")) })
        assertEquals(Reason.Invalid, reason { Backup.read(Backup.export(many, "", 0)) })
    }

    @Test fun noSignInOrTailscaleSecretGetsIn() {
        val plain = Backup.export(full(), "2.9.1", 0).toString(Charsets.UTF_8)
        for (secret in listOf("tskey-auth-SECRET", "hs.example", "192.168.77.0/24", "\"tailscale\":true", "\"stats\":true")) assertFalse(secret, secret in plain)
        // Own servers are what the file is for, so their keys and passwords are in it (encrypted).
        for (own in listOf(key, "user-secret", "pass-secret")) assertTrue(own, own in plain)
        // Sign-ins don't live in the setup at all: no field of it may look like one.
        assertTrue("\"tsAuthKey\":null" in plain)
        val keys = Regex("\"([A-Za-z]+)\":").findAll(plain).map { it.groupValues[1].lowercase() }.toSet() - "tsauthkey"
        assertTrue(keys.toString(), keys.none { k -> listOf("token", "account", "authkey", "session", "cookie", "blob").any { it in k } })
    }

    @Test fun validationReusesTheImportChecks() {
        val ok = Backup.read(Backup.export(full(), "", 0)).setup
        assertEquals(Backup.contents(full()), ok)
        fun refused(s: Setup) = try { Backup.valid(s); fail("accepted"); "" } catch (e: Backup.Failed) { assertEquals(Reason.Invalid, e.reason); e.message.orEmpty() }
        val f = full()
        refused(f.copy(servers = f.servers.map { if (it.id == "s1") it.copy(wg = wg.copy(privateKey = "short")) else it })).let {
            assertTrue(it, "own server Home box" in it); assertFalse(it, "short" in it)
        }
        refused(f.copy(routes = f.routes.map { if (it.id == "x1") it.copy(ss = ss.copy(method = "none")) else it }))
        refused(f.copy(routes = f.routes.map { if (it.id == "o1") it.copy(ovpn = ovpn.copy(config = "dev tun\n")) else it }))
        refused(f.copy(defaultRoute = "gone"))
        refused(f.copy(apps = f.apps + ("org.example.d" to "gone")))
        refused(f.copy(apps = f.apps + ("not a package" to DIRECT)))
        refused(f.copy(routes = f.routes + Route("nl", Location("nord"))))
        refused(f.copy(routes = f.routes + Route("zz", Location("nobody"))))
        refused(f.copy(routes = f.routes + Route("zz", wg = wg, ss = ss)))
        refused(f.copy(profiles = f.profiles + Profile("work", "nl")))
        refused(f.copy(profiles = (1..MAX_PROFILES + 1).map { Profile("P$it", "nl") }, profile = null))
        refused(f.copy(profile = "Gone"))
        refused(f.copy(profiles = f.profiles + Profile("Bad", "gone")))
        refused(f.copy(prefs = f.prefs.copy(filter = Filter("no-such-filter"))))
        refused(f.copy(home = f.home.copy(wg = wg.copy(endpoint = "nowhere"))))
        // A route still naming an own server that is gone keeps its config, no longer tied to it.
        assertNull(Backup.valid(f.copy(servers = f.servers.filter { it.id != "s1" })).route("w1")!!.own)
    }

    @Test fun olderSetupsAreMigrated() {
        val old = Backup.export(full(), "", 0).toString(Charsets.UTF_8).replace("\"version\":${full().stamped.version}", "\"version\":0").toByteArray()
        assertTrue("\"version\":0" in String(old))
        assertEquals(VERSION, Backup.read(old).setup.version)
    }

    @Test fun restoreReplacesButKeepsThisPhonesTailscaleAndConsent() {
        val here = Setup(routes = listOf(Route("de", Location("nord", 81, "DE", "Germany"))), defaultRoute = "de",
            home = Home(tailscale = true, tsAuthKey = "here-key", tsControlUrl = "https://mine.example"), prefs = Prefs(stats = false), version = VERSION)
        val b = Backup.read(Backup.export(full(), "", 0)).setup
        val r = here.restoredFrom(b, null, 1000)!!
        assertEquals(b.routes, r.routes); assertEquals(b.apps, r.apps); assertEquals(b.profiles, r.profiles); assertEquals(b.servers, r.servers)
        assertEquals(here.home.copy(wg = b.home.wg), r.home)
        assertFalse(r.prefs.stats)
        assertFalse(r.pristine)
        assertEquals(setOf("org.example.a", "org.example.b", "org.example.c"), r.waiting)
        assertEquals(1000 + WAIT_MS, r.waitingUntil)
    }

    @Test fun restoreCanKeepTheSetupInUseAsAProfile() {
        val mine = Route("w1", wg = wg.copy(name = "Mine"), own = "s1") // same names as the backup's, other content
        val here = Setup(routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands")), mine, Route("only", Location("nord", 228, "US", "United States"))),
            defaultRoute = "w1", apps = mapOf("org.example.z" to "only", "org.example.y" to "gone"), servers = listOf(OwnServer("s1", wg = wg.copy(name = "Mine"))), version = VERSION)
        val b = Backup.read(Backup.export(full(), "", 0)).setup
        val r = here.restoredFrom(b, PREVIOUS, 0)!!
        Backup.valid(r)
        val kept = r.profiles.single { it.name == PREVIOUS }
        // The route the backup also names "w1" got a new name; "nl" is the same route in both and is shared.
        val moved = r.route(kept.defaultRoute)!!
        assertTrue(moved.id != "w1"); assertEquals("Mine", moved.name)
        assertEquals("Home box", r.route("w1")!!.name)
        assertEquals(r.servers.single { it.id == moved.own }.wg, mine.wg)
        assertEquals(mapOf("org.example.z" to "only"), kept.apps)
        assertEquals(b.routes.size + 2, r.routes.size)
        assertEquals("Home", r.profile)
        // A backup's own "Previous" stays; this one gets a number.
        val b2 = b.copy(profiles = b.profiles + Profile(PREVIOUS, "nl"))
        assertEquals("$PREVIOUS 2", here.restoredFrom(b2, PREVIOUS, 0)!!.profiles.last().name)
        // No room: nothing is restored by this call.
        assertNull(here.restoredFrom(b.copy(profiles = (1..MAX_PROFILES).map { Profile("P$it", "nl") }, profile = null), PREVIOUS, 0))
        // A backup without profiles: its routes stay listed once the kept one makes the first profile.
        val noProfiles = b.copy(profiles = emptyList(), profile = null)
        val r3 = here.restoredFrom(noProfiles, PREVIOUS, 0)!!
        assertTrue(r3.hiddenRoutes(null).intersect(b.routes.map { it.id }.toSet()).isEmpty())
        // A new install has nothing to keep.
        assertEquals(newSetup().restoredFrom(b, null, 0), newSetup().restoredFrom(b, PREVIOUS, 0))
    }

    /** A kept Via route keeps its own entry, also one no app uses and whose id the backup has for another route. */
    @Test fun keepingASetupKeepsEachViaEntry() {
        val entry = Route("w1", wg = wg.copy(name = "Entry"), own = "s1") // the backup's "w1" is another route
        val exit = Route("ex", wg = wg.copy(name = "Exit"), own = "s9", via = "w1")
        val lost = Route("lx", wg = wg.copy(name = "Lost"), own = "s8", via = "gone")
        val here = Setup(routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands")), entry, exit, lost), defaultRoute = "nl",
            apps = mapOf("org.example.z" to "ex", "org.example.y" to "lx"), profiles = listOf(Profile("Mine", "nl")), profile = "Mine",
            servers = listOf(OwnServer("s1", wg = entry.wg), OwnServer("s9", wg = exit.wg), OwnServer("s8", wg = lost.wg)), version = VERSION)
        val b = Backup.read(Backup.export(full(), "", 0)).setup
        val r = here.restoredFrom(b, PREVIOUS, 0)!!
        Backup.valid(r)
        val kept = r.profiles.single { it.name == PREVIOUS }
        val x = r.route(kept.apps["org.example.z"])!!
        assertEquals("Exit", x.name)
        assertEquals("Entry", r.route(x.via)!!.name)
        assertEquals("Home box", r.route("w1")!!.name)
        assertNull(r.viaProblem(x))
        // An entry already gone here stays gone: the route stays blocked, never through one of the backup's.
        val l = r.route(kept.apps["org.example.y"])!!
        assertNull(r.route(l.via)); assertNotNull(r.viaProblem(l))
    }

    @Test fun keepingASetupWithoutProfilesKeepsEveryRouteItShows() {
        val spare = Route("spare", wg = wg.copy(name = "Spare"), own = "sp")
        val here = Setup(routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands")), spare), defaultRoute = "nl",
            servers = listOf(OwnServer("sp", wg = wg.copy(name = "Spare"))), version = VERSION)
        val b = Backup.read(Backup.export(full(), "", 0)).setup
        val r = here.restoredFrom(b, PREVIOUS, 0)!!
        Backup.valid(r)
        val kept = r.profiles.single { it.name == PREVIOUS }
        assertTrue(kept.added.toString(), "spare" in kept.added)
        assertEquals("Spare", r.route("spare")!!.name)
        assertTrue(r.servers.any { it.id == "sp" })
    }

    /** Keeping the setup in use keeps every own server here, its keys too, also one no route uses yet. */
    @Test fun keepingASetupKeepsAnOwnServerNoRouteUses() {
        val spare = OwnServer("sp", wg = wg.copy(name = "Spare"))
        // Its id is taken in the backup by another server: it stays under a new one.
        val clash = OwnServer("s1", ss = ss.copy(name = "Clash"))
        val here = Setup(routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands"))), defaultRoute = "nl", servers = listOf(spare, clash), version = VERSION)
        val b = Backup.read(Backup.export(full(), "", 0)).setup
        val r = here.restoredFrom(b, PREVIOUS, 0)!!
        Backup.valid(r)
        assertEquals(spare, r.servers.single { it.id == "sp" })
        assertEquals(b.servers.single { it.id == "s1" }, r.servers.single { it.id == "s1" })
        assertEquals(1, r.servers.count { it.copy(id = "s1") == clash })
        // Without keeping, the backup's servers replace them, as the sheet says.
        assertEquals(b.servers, here.restoredFrom(b, null, 0)!!.servers)
        // Never more own servers than a setup may hold: then the setup here can't be kept, as with no room for its profile.
        val many = b.copy(routes = b.routes.filter { !it.imported }, servers = (1..Backup.MAX_EACH).map { OwnServer("m$it", wg = wg.copy(name = "M$it")) })
        assertNull(here.restoredFrom(many, PREVIOUS, 0))
        assertEquals(Backup.MAX_EACH, here.restoredFrom(many.copy(servers = many.servers.drop(2)), PREVIOUS, 0)!!.servers.size)
        // Counted as a backup without keys holds it: an imported route with no own server gets one there.
        val loose = many.copy(servers = many.servers.drop(2), routes = many.routes + Route("loose", wg = wg.copy(name = "Loose")))
        assertNull(here.restoredFrom(loose, PREVIOUS, 0))
    }

    @Test fun aSetupTooLargeToRestoreIsNeverSaved() {
        assertEquals(Reason.TooBig, reason { Backup.seal(ByteArray(Backup.MAX), "p", logN = 10, r = 8, p = 1) })
    }

    @Test fun everySavedProfileMustBeAbleToRun() {
        val f = full()
        val two = f.copy(routes = f.routes + Route("t1", tsExit = "100.64.0.1") + Route("t2", tsExit = "100.64.0.2"),
            profiles = f.profiles + Profile("Odd", "t1", mapOf("org.example.a" to "t2")))
        assertEquals(Reason.Invalid, reason { Backup.valid(two) })
        Backup.valid(two.copy(profiles = f.profiles + Profile("Fine", "t1", mapOf("org.example.a" to "t1"))))
    }

    @Test fun olderReadersMustRefuseLocalGrantsWhileTheirInstallWindowIsPending() {
        val pkg = "org.example.pending"
        val pending = Setup(defaultRoute = DIRECT, apps = mapOf(pkg to DIRECT), waiting = setOf(pkg), waitingUntil = 100)
        assertTrue(pending.stamped.version > 5)
        assertEquals(5, Backup.contents(pending).stamped.version)
    }

    @Test fun theGlobalInstallDeadlineUsesTheOriginalInstallTime() {
        val pkg = "org.example.restored"
        val restored = Setup(defaultRoute = DIRECT, apps = mapOf(pkg to DIRECT), waiting = setOf(pkg), waitingUntil = 100)
        assertEquals(DIRECT, keepKnown(restored, setOf(pkg), 200, installedAt = { 99 }).apps[pkg])
        for (first in listOf(100L, 101L)) {
            val settled = keepKnown(restored, setOf(pkg), 200, installedAt = { first })
            assertFalse("expired app still has an explicit grant", pkg in settled.apps)
            assertEquals(DIRECT, settled.routeOf(pkg))
        }
    }

    @Test fun restoredAppsWaitToBeInstalled() {
        val s = full().copy(waiting = setOf("org.example.a", "org.example.c"), waitingUntil = 5000)
        val known = setOf("org.example.b")
        val k = keepKnown(s, known, now = 1000)
        assertEquals(s.apps, k.apps)
        assertEquals(s.waiting, k.waiting)
        // Installed now: it no longer waits, and a later uninstall drops it as usual.
        val k2 = keepKnown(k, known + "org.example.a", now = 2000)
        assertEquals(setOf("org.example.c"), k2.waiting)
        assertFalse("org.example.a" in keepKnown(k2, known, now = 3000).apps)
        // Past the wait, unknown apps go.
        val k3 = keepKnown(s, known, now = 6000)
        assertEquals(setOf("org.example.b"), k3.apps.keys); assertTrue(k3.waiting.isEmpty()); assertEquals(0, k3.waitingUntil)
    }

    /** The profile a restore keeps the setup in use as is a valid name, and never the one a switch by network saves over. */
    @Test fun theKeptSetupHasANameOfItsOwn() {
        assertNotNull(Setup().profileName(app.unbraid.data.BEFORE_RESTORE))
        assertFalse(app.unbraid.data.BEFORE_RESTORE.equals(PREVIOUS, ignoreCase = true))
    }

    /** An app a restore left waiting to be installed keeps its choice, Block too, in the setup a second restore keeps. */
    @Test fun aWaitingAppKeepsItsChoiceInTheKeptSetup() {
        val pkg = "org.example.later"
        val first = newSetup().restoredFrom(Setup(routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands"))), defaultRoute = DIRECT,
            apps = mapOf(pkg to BLOCK), version = VERSION), null, 1000)!!
        assertEquals(setOf(pkg), first.waiting)
        // Another backup, without that app, restored over it with the setup in use kept.
        val second = first.restoredFrom(Backup.read(Backup.export(full(), "", 0)).setup, PREVIOUS, 2000)!!
        val k = keepKnown(second, emptySet(), now = 3000)
        assertEquals(BLOCK, k.profiles.single { it.name == PREVIOUS }.apps[pkg])
        assertEquals(BLOCK, k.use(PREVIOUS).routeOf(pkg))
        // Past its own wait it goes, as any app Android doesn't know, while the backup's apps wait on.
        val late = keepKnown(second, emptySet(), now = 1000 + WAIT_MS)
        assertFalse(pkg in late.profiles.single { it.name == PREVIOUS }.apps)
        assertEquals(setOf("org.example.a", "org.example.b", "org.example.c"), late.waiting)
        // Kept again by a third restore, from Previous in use, it still has its first deadline.
        assertEquals(mapOf(pkg to mapOf(BLOCK to 1000 + WAIT_MS)), k.use(PREVIOUS).restoredFrom(Backup.read(Backup.export(full(), "", 0)).setup, PREVIOUS, 4000)!!.waitingEach)
        // One the new backup has too, with another choice: each choice keeps its own deadline, the kept one's never longer.
        val nl = Route("nl", Location("nord", 153, "NL", "Netherlands"))
        val old = newSetup().restoredFrom(Setup(routes = listOf(nl), defaultRoute = "nl", apps = mapOf(pkg to DIRECT), version = VERSION), null, 1000)!!
        val both = old.restoredFrom(Setup(routes = listOf(nl), defaultRoute = "nl", apps = mapOf(pkg to BLOCK), version = VERSION), PREVIOUS, 2000)!!
        val day30 = keepKnown(both, emptySet(), now = 1000 + WAIT_MS)
        assertEquals(BLOCK, day30.apps[pkg])
        assertFalse(pkg in day30.profiles.single { it.name == PREVIOUS }.apps)
        assertFalse(pkg in keepKnown(both, emptySet(), now = 2000 + WAIT_MS).apps)
        // Found installed only past the kept choice's deadline: that one goes, the backup's stays. Installed in time, both do.
        val installed = keepKnown(both, setOf(pkg), now = 1000 + WAIT_MS, installedAt = { 1000 + WAIT_MS })
        assertEquals(BLOCK, installed.apps[pkg])
        assertFalse(pkg in installed.profiles.single { it.name == PREVIOUS }.apps)
        assertTrue(installed.waiting.isEmpty())
        assertEquals(DIRECT, keepKnown(both, setOf(pkg), now = 3000).profiles.single { it.name == PREVIOUS }.apps[pkg])
        // Installed in time but seen only later: Android's install time counts, not the time of the look.
        assertEquals(DIRECT, keepKnown(both, setOf(pkg), now = 9000 + WAIT_MS, installedAt = { 3000 }).profiles.single { it.name == PREVIOUS }.apps[pkg])
        assertFalse(pkg in keepKnown(both, setOf(pkg), now = 9000 + WAIT_MS, installedAt = { 1500 + WAIT_MS }).profiles.single { it.name == PREVIOUS }.apps)
        // A later restore keeps the choice of one installed in time, though nothing looked at the installed apps since.
        val late2 = first.restoredFrom(Backup.read(Backup.export(full(), "", 0)).setup, PREVIOUS, 9000 + WAIT_MS, installedAt = { 1500 })!!
        assertEquals(BLOCK, late2.profiles.single { it.name == PREVIOUS }.apps[pkg])
        assertFalse(pkg in late2.waitingEach)
        assertFalse(pkg in first.restoredFrom(Backup.read(Backup.export(full(), "", 0)).setup, PREVIOUS, 9000 + WAIT_MS)!!.profiles.single { it.name == PREVIOUS }.apps)
        // The older fields cover the kept choice too, so a version before per-app deadlines keeps it as long.
        assertTrue(pkg in both.waiting)
        assertTrue(both.waitingUntil >= 1000 + WAIT_MS)
        // A wait that is over is not carried, nor its choice kept: the backup's wait for the app doesn't bring it back.
        assertFalse(pkg in first.restoredFrom(Backup.read(Backup.export(full(), "", 0)).setup, PREVIOUS, 1000 + WAIT_MS)!!.waiting)
        val over = old.restoredFrom(Setup(routes = listOf(nl), defaultRoute = "nl", apps = mapOf(pkg to BLOCK), version = VERSION), PREVIOUS, 1000 + WAIT_MS)!!
        assertFalse(pkg in over.profiles.single { it.name == PREVIOUS }.apps)
    }

    /** The confirm sheet works a restore out as of now, as Restore does: a choice whose wait is over isn't counted there either. */
    @Test fun theConfirmSheetCountsWaitsAsOfNow() {
        val pkg = "org.example.later"
        // Route "us" only an app whose wait ended long ago uses: kept, it would be one route too many.
        val here = Setup(routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands")), Route("us", Location("nord", 228, "US", "United States"))),
            defaultRoute = "nl", apps = mapOf(pkg to "us"), profiles = listOf(Profile("Trip", "nl")), waiting = setOf(pkg), waitingUntil = 1, version = VERSION)
        val big = Setup(routes = (1 until Backup.MAX_EACH).map { Route("b$it", Location("nord", 153, "NL", "Netherlands")) }, defaultRoute = "b1", version = VERSION)
        assertNull(here.restoredFrom(big, PREVIOUS, 0))
        val ask = RestoreGate.Ask(here, Backup.Contents(setup = big), true)
        assertTrue(RestoreGate.check(ask, installed = { InstallState.Absent }).keepable)
        assertFalse(RestoreGate.check(ask, installed = { InstallState.Unknown }).keepable)
    }

    @Test fun strengthIsAHintOnly() {
        assertEquals(0, Backup.strength("abc123").first)
        assertEquals(2, Backup.strength("correct horse battery staple").first)
        assertNotNull(Backup.strength("").second)
    }

    @Test fun shadowsocksAndWireguardRoundTrip() {
        assertEquals(ss, Manual.parseSs(Manual.ssLink(ss), ss.name))
        assertEquals(wg, WgConf.parse(WgConf.text(wg), wg.name))
        val r = SecureRandom()
        repeat(200) {
            val pw = String(CharArray(r.nextInt(30) + 1) { (r.nextInt(0x2FF) + 0x21).toChar() })
            val p = ss.copy(password = pw, pluginOpts = "obfs=tls;obfs-host=$it.example")
            assertEquals(pw, p, Manual.parseSs(Manual.ssLink(p), p.name))
        }
    }

    @Test fun sharedWireGuardTemplatesContainOnlyThePublicServerLabel() {
        val peer = wg.copy(address = listOf("10.123.0.4/32"), dns = listOf("10.123.0.1"), allowedIps = listOf("10.123.0.0/24"),
            mtu = 1392, keepalive = 17, junk = 9, presharedKey = key, vmess = app.unbraid.data.Vmess("private.invalid", 3456, "private-user"))
        val phone = Setup(routes = listOf(Route("r1", wg = peer, own = "s"), Route("r2", wg = peer, own = "s")),
            servers = listOf(OwnServer("s", wg = peer)), defaultRoute = DIRECT, profiles = listOf(Profile("Work", "r2")), version = VERSION)
        val first = Backup.export(phone, "3.2.2", 0, keys = false)
        val opened = Backup.read(first)
        val waiting = phone.restoredFrom(opened.setup, null, 0, opened.keys)!!
        for (bytes in listOf(first, Backup.export(waiting, "", 0, keys = false), Backup.export(waiting, "", 0, keys = true))) {
            val doc = kotlinx.serialization.json.Json.parseToJsonElement(String(bytes)) as kotlinx.serialization.json.JsonObject
            assertEquals("2", doc["format"].toString())
            val setup = doc["setup"] as kotlinx.serialization.json.JsonObject
            for (collection in listOf("servers", "routes")) for (item in setup[collection] as kotlinx.serialization.json.JsonArray) {
                val record = item as kotlinx.serialization.json.JsonObject
                val label = record["wgIdentity"] as kotlinx.serialization.json.JsonObject
                assertEquals(setOf("name", "endpoint", "publicKey"), label.keys)
                assertEquals(peer.endpoint, (label["endpoint"] as kotlinx.serialization.json.JsonPrimitive).content)
                assertEquals(peer.name, (label["name"] as kotlinx.serialization.json.JsonPrimitive).content)
                assertEquals(peer.publicKey, (label["publicKey"] as kotlinx.serialization.json.JsonPrimitive).content)
                assertEquals("null", record["wg"].toString())
            }
            for (hidden in listOf("10.123.", "private.invalid", "private-user", key)) assertFalse(String(bytes).contains(hidden))
        }
        assertTrue(waiting.servers.single().keyless && waiting.routes.all { it.keyless })
        assertEquals("r2", waiting.profiles.single().defaultRoute)
        val ready = waiting.refilled("s", OwnServer("", wg = wg))!!
        assertTrue(ready.routes.all { !it.keyless && it.wg == wg })
        assertEquals(wg, ready.servers.single().wg)
        assertEquals("r2", ready.profiles.single().defaultRoute)
    }

    @Test fun conflictingHistoricalWireGuardCopiesCannotBorrowLocalKeys() {
        val saved = wg.copy(privateKey = "", presharedKey = null)
        val incoming = Setup(routes = listOf(Route("r", wg = saved, own = "s", keyless = true),
            Route("q", wg = saved, own = "s", keyless = true)), servers = listOf(OwnServer("s", wg = saved, keyless = true)),
            defaultRoute = "r", profiles = listOf(Profile("Work", "q")), version = VERSION)
        val loose = Setup(routes = listOf(Route("r", wg = wg), Route("q", wg = wg)), defaultRoute = "r", version = VERSION)
        fun restored(here: Setup, file: Setup): Setup {
            val bytes = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(Backup.Contents.serializer(), Backup.Contents(format = 1, setup = file, keys = false)).toByteArray()
            return here.restoredFrom(Backup.read(bytes).setup, null, 0, keys = false)!!
        }
        assertFalse(restored(loose, incoming).servers.single().keyless)
        val shifted = incoming.copy(routes = incoming.routes.map { if (it.id == "q") it.copy(wg = saved.copy(dns = listOf("10.234.0.1"))) else it })
        val ambiguous = loose.copy(routes = loose.routes.map { if (it.id == "q") it.copy(wg = wg.copy(presharedKey = key)) else it })
        for (held in listOf(restored(loose, shifted), restored(ambiguous, incoming))) {
            assertTrue(held.servers.single().keyless)
            assertTrue(held.routes.all { it.keyless })
        }
        val local = loose.copy(servers = listOf(OwnServer("s", wg = wg)))
        assertTrue(restored(local, shifted).servers.single().keyless)
    }

    @Test fun missingLegacyWireGuardFieldsDoNotBecomeEvidenceForRefill() {
        val local = wg.copy(dns = emptyList(), keepalive = null, mtu = 1420)
        val here = Setup(routes = listOf(Route("r", wg = local, own = "s")), servers = listOf(OwnServer("s", wg = local)), defaultRoute = "r", version = VERSION)
        val file = legacyWgTemplate(here, local)
        val complete = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(Backup.Contents.serializer(), file)
        fun restored(text: String) = here.restoredFrom(Backup.read(text.toByteArray()).setup, null, 0, keys = false)!!
        assertFalse(restored(complete).servers.single().keyless)
        assertEquals(Reason.Invalid, reason { restored(complete.replaceFirst("\"format\":1", "\"format\":0")) })
        for (field in listOf("\"dns\":[]", "\"mtu\":1420", "\"keepalive\":null", "\"presharedKey\":null")) {
            val partial = complete.replace("$field,", "")
            assertFalse(complete == partial)
            assertTrue(restored(partial).servers.single().keyless)
            assertTrue(restored(partial).routes.single().keyless)
        }
    }

    @Test fun compactWireGuardKeepsThePreviousProfileSeparateAndRejectsConflictingLabels() {
        val here = Setup(routes = listOf(Route("r", wg = wg, own = "s")), servers = listOf(OwnServer("s", wg = wg)),
            defaultRoute = "r", version = VERSION)
        val compact = Backup.export(here, "", 0, false)
        val raw = String(compact)
        assertEquals(Reason.Invalid, reason { Backup.read(raw.replaceFirst("\"format\":2", "\"format\":1").toByteArray()) })
        assertEquals(Reason.Invalid, reason { Backup.read(raw.replaceFirst("\"keyless\":true", "\"keyless\":false").toByteArray()) })
        val read = Backup.read(compact)
        val kept = here.restoredFrom(read.setup, PREVIOUS, 0, read.keys)!!
        assertTrue(kept.route(kept.defaultRoute)!!.keyless)
        val prior = kept.route(kept.profiles.single().defaultRoute)!!
        assertFalse(prior.keyless)
        assertEquals(wg, prior.wg)
        assertFalse(kept.route(kept.defaultRoute)!!.own == prior.own)
        val stored = kotlinx.serialization.json.Json.encodeToString(Setup.serializer(), kept)
        val reopened = kotlinx.serialization.json.Json.decodeFromString(Setup.serializer(), stored)
        val mixed = Backup.read(Backup.export(reopened, "", 0, true))
        assertTrue(mixed.setup.route(mixed.setup.defaultRoute)!!.keyless)
        assertEquals(wg, mixed.setup.route(prior.id)!!.wg)
    }

    @Test fun withoutKeysNoSecretIsInTheFile() {
        val loose = full().let { it.copy(routes = it.routes + Route("w2", wg = wg.copy(name = "Loose"))) }
        val plain = Backup.export(loose, "", 0, keys = false)
        val text = plain.toString(Charsets.UTF_8)
        listOf(key, "user-secret", "pass-secret", "p@ss+w rd", "example.com", "BEGIN CERTIFICATE", "tskey-auth-SECRET").forEach { assertFalse(it, text.contains(it)) }
        val c = Backup.read(plain)
        assertFalse(c.keys)
        assertTrue(c.setup.servers.all { it.keyless })
        assertEquals(listOf("w1", "o1", "x1", "w2"), c.setup.routes.filter { it.keyless }.map { it.id })
        assertNull(c.setup.home.wg)
        // A route that had no own server gets one, to be given its config back through.
        assertTrue(c.setup.servers.single { it.id == c.setup.route("w2")!!.own }.keyless)
        // Their routes are blocked, never retried or run on the normal connection.
        val used = c.setup.copy(apps = c.setup.apps + mapOf("org.example.d" to "o1", "org.example.e" to "x1", "org.example.f" to "w2"))
        val p = LinkPicker.pick(used, emptyMap(), null, emptyMap(), emptyMap(), emptyMap(), offline = true) { Providers.get(it) }
        listOf("w1", "o1", "x1", "w2").forEach { assertTrue(it, p.blocked[it] is LinkPicker.KeyLeftOut) }
        // A route through a server still waiting for its config is blocked even if the route itself isn't marked.
        val unmarked = used.copy(routes = used.routes.map { if (it.id == "w1") it.copy(keyless = false) else it })
        assertTrue(LinkPicker.pick(unmarked, emptyMap(), null, emptyMap(), emptyMap(), emptyMap(), offline = true) { Providers.get(it) }.blocked["w1"] is LinkPicker.KeyLeftOut)
        assertTrue(Backup.hasSecrets(full())); assertFalse(Backup.hasSecrets(newSetup()))
        assertTrue(Backup.read(Backup.export(full(), "", 0)).keys)
    }

    @Test fun theKeysSwitchIsAlwaysShownAndSaysWhatItCanInclude() {
        // Provider routes only: nothing to include, so the switch is shown off and can't be turned on.
        val providersOnly = Setup(routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands"))), defaultRoute = "nl", version = VERSION)
        val (none, noneText) = keysSwitch(Backup.hasSecrets(providersOnly), on = true)
        assertFalse(none); assertTrue(noneText, noneText.startsWith("You have no own servers"))
        // An own server: the switch works and keeps its on and off captions.
        val (some, offText) = keysSwitch(Backup.hasSecrets(full()), on = false)
        assertTrue(some); assertTrue(offText, offText.startsWith("Off:"))
        assertTrue(keysSwitch(true, on = true).second.contains("strong passphrase"))
        // Provider sign-ins are never offered: the texts say so and never suggest they can be included.
        assertTrue(BACKUP_LEAVES.startsWith("Provider sign-ins are never saved"))
        assertFalse(BACKUP_HOLDS.contains("sign-in", ignoreCase = true))
    }

    @Test fun aServerSavedWithoutKeysWaitsForItsConfig() {
        val b = Backup.read(Backup.export(full(), "", 0, keys = false))
        val r = newSetup().restoredFrom(b.setup, null, 0, b.keys)!!
        Backup.valid(r)
        assertEquals(3, r.servers.count { it.keyless })
        val back = r.refilled("s1", OwnServer("", wg = wg.copy(name = "Renamed")))!!
        Backup.valid(back)
        assertEquals(wg.copy(name = "Renamed"), back.servers.single { it.id == "s1" }.wg)
        assertEquals(wg.copy(name = "Renamed"), back.route("w1")!!.wg)
        assertFalse(back.route("w1")!!.keyless)
        assertTrue(back.route("o1")!!.keyless)
        assertNull(back.refilled("s1", OwnServer("", wg = wg)))
        assertNull(back.refilled("gone", OwnServer("", wg = wg)))
    }

    @Test fun historicalKeylessSettingsCanStillIdentifyAnExactLocalServer() {
        val here = full()
        val b = legacyWgTemplate(here, wg)
        val r = here.restoredFrom(b.setup, null, 0, b.keys)!!
        assertTrue(r.servers.none { it.keyless } && r.routes.none { it.keyless })
        assertEquals(here.servers, r.servers)
        assertEquals(here.route("w1"), r.route("w1"))
        assertEquals(here.home.wg, r.home.wg)
        Backup.valid(r)
        // A server that changed since is not taken for the same one.
        val moved = here.copy(servers = here.servers.map { if (it.id == "s1") it.copy(wg = wg.copy(endpoint = "203.0.113.9:51820")) else it })
        assertEquals(listOf("s1"), moved.restoredFrom(b.setup, null, 0, b.keys)!!.servers.filter { it.keyless }.map { it.id })
    }

    @Test fun aFileSavedWithoutKeysMustHoldNone() {
        val s = Backup.read(Backup.export(full(), "", 0, keys = false)).setup
        assertEquals(Reason.Invalid, reason { Backup.valid(s.copy(servers = s.servers.map { if (it.id == "s1") it.copy(wg = it.wg!!.copy(privateKey = key)) else it })) })
        assertEquals(Reason.Invalid, reason { Backup.valid(s.copy(servers = s.servers.map { if (it.id == "s2") it.copy(ovpn = ovpn) else it })) })
        assertEquals(Reason.Invalid, reason { Backup.valid(s.copy(routes = s.routes.map { if (it.id == "w1") it.copy(keyless = false) else it })) })
        assertEquals(Reason.Invalid, reason { Backup.valid(s.copy(routes = s.routes.map { if (it.id == "nl") it.copy(keyless = true) else it })) })
    }
}

/** A format-one fixture built with the complete WireGuard fields earlier writers actually stored on both copies. */
internal fun legacyWgTemplate(s: Setup, peer: app.unbraid.data.WgPeer): Backup.Contents {
    val linked = Backup.contents(s, keys = false)
    val stripped = peer.copy(privateKey = "", presharedKey = null)
    val old = linked.copy(servers = linked.servers.map { if (it.wg != null) it.copy(wg = stripped) else it },
        routes = linked.routes.map { if (it.wg != null) it.copy(wg = stripped) else it })
    val bytes = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(Backup.Contents.serializer(),
        Backup.Contents(format = 1, app = "3.0.2", setup = old.stamped, keys = false)).toByteArray()
    return Backup.read(bytes)
}
