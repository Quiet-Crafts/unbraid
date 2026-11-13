package app.unbraid

import app.unbraid.data.Backup
import app.unbraid.data.Backup.Reason
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
import app.unbraid.ui.keepKnown
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
        val newer = Backup.export(full(), "3.0", 0).toString(Charsets.UTF_8).replace("\"version\":$VERSION", "\"version\":${VERSION + 1}").toByteArray()
        assertEquals(Reason.Newer, reason { Backup.read(newer) })
        val newerFormat = Backup.export(full(), "3.0", 0).toString(Charsets.UTF_8).replaceFirst("\"format\":1", "\"format\":2").toByteArray()
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
        val old = Backup.export(full(), "", 0).toString(Charsets.UTF_8).replace("\"version\":$VERSION", "\"version\":0").toByteArray()
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

    @Test fun restoringWithoutKeysOnTheSamePhoneKeepsItsKeys() {
        val here = full()
        val b = Backup.read(Backup.export(here, "", 0, keys = false))
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
