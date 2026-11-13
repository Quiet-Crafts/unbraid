package app.unbraid

import app.unbraid.data.Filter
import app.unbraid.data.Home
import app.unbraid.data.Location
import app.unbraid.data.NordApi
import app.unbraid.data.OwnServer
import app.unbraid.data.Prefs
import app.unbraid.data.Profile
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.data.Store
import app.unbraid.data.VERSION
import app.unbraid.data.WgPeer
import app.unbraid.data.UpdateState
import app.unbraid.data.Release
import app.unbraid.support.NudgeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class StoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun unreadableLinkCacheIsEmpty() {
        val dir = tmp.newFolder()
        val s = Store(dir) { key }
        File(dir, "links.bin").mkdir()
        assertTrue(s.lastLinks.isEmpty())
    }

    @Test fun catalogDateRetriesAfterFailedWrite() {
        val dir = tmp.newFolder()
        val s = Store(dir) { key }
        val target = File(dir, "catalog-dates.json").apply { mkdir(); resolve("block").writeText("x") }
        s.catalogDate("provider", 123)
        assertTrue(s.catalogDates.value.isEmpty())
        target.deleteRecursively()
        s.catalogDate("provider", 123)
        assertEquals(123L, s.catalogDates.value["provider"])
        assertEquals(123L, Store(dir) { key }.catalogDates.value["provider"])
    }

    @Test fun sealedFilesRoundTripAndNoTempFileStays() {
        val dir = tmp.newFolder()
        Store(dir) { key }.apply { assertTrue(account("nord", "blob-1")); assertTrue(update { it.copy(defaultRoute = "x") }) }
        val again = Store(dir) { key }
        assertEquals(mapOf("nord" to "blob-1"), again.accounts.value)
        assertEquals("x", again.setup.value.defaultRoute)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertFalse(File(dir, "accounts.bin").readText(Charsets.ISO_8859_1).contains("blob-1")) // sealed, not plain
    }

    /** A tap's edit applies at once and reaches the disk on the writer thread; the newest setup is the one saved. */
    @Test fun editsApplyAtOnceAndTheLastOneIsSaved() {
        val dir = tmp.newFolder()
        val s = Store(dir) { key }
        s.edit { it.copy(defaultRoute = "a") }
        assertEquals("a", s.setup.value.defaultRoute)
        repeat(50) { i -> s.edit { it.copy(defaultRoute = "r$i") } }
        s.flush()
        assertEquals("r49", Store(dir) { key }.setup.value.defaultRoute)
        assertFalse(s.unsaved.value)
        // A failed background write says so, as a failed update does, and a later one that works clears it.
        dir.setWritable(false)
        s.edit { it.copy(defaultRoute = "b") }
        s.flush()
        assertTrue(s.unsaved.value)
        dir.setWritable(true)
        s.edit { it.copy(defaultRoute = "c") }
        s.flush()
        assertFalse(s.unsaved.value)
        assertEquals("c", Store(dir) { key }.setup.value.defaultRoute)
    }

    /** A failed write keeps the change in memory (a new sign-in's device slot lives only there), says so, and retries. */
    @Test fun aFailedSaveIsKeptShownAndRetried() {
        val dir = tmp.newFolder()
        val s = Store(dir) { key }
        assertTrue(s.account("a", "one"))
        // Make the next write fail: the directory refuses new files.
        dir.setWritable(false)
        assertFalse(s.account("b", "two"))
        assertTrue(s.unsaved.value)
        assertEquals(setOf("a", "b"), s.accounts.value.keys)
        // A later setup change that saves doesn't hide the unsaved account.
        dir.setWritable(true)
        assertTrue(s.update { it.copy(defaultRoute = "y") })
        assertTrue(s.unsaved.value)
        assertTrue(s.saveAgain())
        assertFalse(s.unsaved.value)
        assertEquals(setOf("a", "b"), Store(dir) { key }.accounts.value.keys)
        // The link cache never throws.
        dir.setWritable(false); s.rememberLinks(emptyMap()); dir.setWritable(true)
    }

    /** Signing out of everything also removes the copies older versions kept of files that failed to open. */
    @Test fun signOutAllRemovesBadCopies() {
        val dir = tmp.newFolder()
        File(dir, "accounts.bin.bad").writeText("old copy")
        Store(dir) { key }.signOutAll()
        assertFalse(File(dir, "accounts.bin.bad").exists())
    }

    private fun saved(dir: File) = Store(dir) { key }.apply { assertTrue(account("nord", "blob")); assertTrue(update { it.copy(defaultRoute = "x") }) }
    private fun sealed(text: String) = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }.let { it.iv + it.doFinal(text.toByteArray()) }

    /** A key that fails once (a Keystore hiccup) is asked again: the data opens and nothing is marked. */
    @Test fun aKeyThatFailsOnceIsAskedAgain() {
        val dir = tmp.newFolder().also(::saved)
        var calls = 0
        val s = Store(dir) { if (calls++ == 0) error("hiccup") else key }
        assertFalse(s.unreadable.value)
        assertEquals("x", s.setup.value.defaultRoute)
        assertEquals(mapOf("nord" to "blob"), s.accounts.value)
    }

    /** Files that won't open change nothing, in memory or on disk, until they open again. */
    @Test fun unreadableFilesChangeNothingUntilReopened() {
        val dir = tmp.newFolder().also(::saved)
        val names = listOf("setup.bin", "accounts.bin")
        val bytes = names.map { File(dir, it).readBytes().toList() }
        var broken = true
        val s = Store(dir) { if (broken) error("no key") else key }
        assertTrue(s.unreadable.value)
        val before = s.setup.value
        assertFalse(s.update { it.copy(defaultRoute = "y") })
        s.edit { it.copy(defaultRoute = "z") }; s.flush()
        assertFalse(s.account("other", "b"))
        s.signOutAll()
        assertEquals(before, s.setup.value)
        assertTrue(s.accounts.value.isEmpty())
        assertFalse(s.unsaved.value)
        assertEquals(bytes, names.map { File(dir, it).readBytes().toList() })
        assertFalse(File(dir, "setup.bin.bad").exists())
        assertFalse(s.reopen()) // still no key
        broken = false
        assertTrue(s.reopen())
        assertFalse(s.unreadable.value)
        assertEquals("x", s.setup.value.defaultRoute)
        assertEquals(mapOf("nord" to "blob"), s.accounts.value)
        assertTrue(s.update { it.copy(defaultRoute = "y") })
        assertEquals("y", Store(dir) { key }.setup.value.defaultRoute)
    }

    /** One file opens, the other is cut short: the readable one loads, and still nothing changes. */
    @Test fun oneCorruptFileMarksTheStore() {
        val dir = tmp.newFolder().also(::saved)
        File(dir, "accounts.bin").writeBytes(byteArrayOf(1, 2, 3))
        val s = Store(dir) { key }
        assertTrue(s.unreadable.value)
        assertEquals("x", s.setup.value.defaultRoute)
        assertFalse(s.update { it.copy(defaultRoute = "y") })
        assertFalse(s.reopen())
        assertEquals(listOf<Byte>(1, 2, 3), File(dir, "accounts.bin").readBytes().toList())
        assertEquals("x", Store(dir) { key }.setup.value.defaultRoute)
    }

    /**
     * Start fresh while an engine keeps apps offline over unreadable settings: that engine's domain table stays until it
     * has stopped, which then deletes it, and the next table takes the next block of fake addresses.
     */
    @Test fun startFreshLeavesARunningEnginesTable() {
        val dir = tmp.newFolder().also(::saved)
        File(dir, "setup.bin").writeText("garbage")
        val table = File(dir, "cache.db").apply { writeText("lookups") }
        val s = Store(dir) { key }
        assertTrue(s.unreadable.value)
        val block = 3
        app.unbraid.vpn.LookupTable.open(true) // the engine holding apps offline runs with the table in block 3
        try {
            assertTrue(s.startFresh())
            assertTrue(table.exists())
            assertFalse(app.unbraid.vpn.LookupTable.forget(dir))
            // Leaving the hold: the engine stops first, then its table goes.
            app.unbraid.vpn.LookupTable.open(false)
            assertTrue(app.unbraid.vpn.LookupTable.forget(dir))
            assertFalse(table.exists())
            assertEquals(4, app.unbraid.vpn.Compiler.nextBlock(block, app.unbraid.vpn.LookupTable.isOpen()))
        } finally { app.unbraid.vpn.LookupTable.open(false) }
        // With no engine running, Start fresh deletes the table itself.
        val off = tmp.newFolder().also(::saved)
        File(off, "setup.bin").writeText("garbage")
        File(off, "cache.db").writeText("lookups")
        assertTrue(Store(off) { key }.startFresh())
        assertFalse(File(off, "cache.db").exists())
    }

    /** Start fresh removes settings, sign-ins and old copies; saving works again. */
    @Test fun startFreshThenSavingWorks() {
        val dir = tmp.newFolder().also(::saved)
        File(dir, "setup.bin").writeText("garbage")
        File(dir, "tailscale").mkdir(); File(dir, "tailscale/state.bin").writeText("x"); File(dir, "token.bin").writeText("x")
        val s = Store(dir) { key }
        assertTrue(s.unreadable.value)
        assertTrue(s.startFresh())
        assertFalse(s.unreadable.value)
        assertTrue(listOf("setup.bin", "accounts.bin", "tailscale", "token.bin").none { File(dir, it).exists() })
        assertTrue(s.accounts.value.isEmpty())
        assertTrue(s.update { it.copy(defaultRoute = "new") })
        assertTrue(s.account("nord", "b2"))
        Store(dir) { key }.apply { assertEquals("new", setup.value.defaultRoute); assertEquals(mapOf("nord" to "b2"), accounts.value) }
        // With no key ever found, the first save after it may create one.
        val gone = tmp.newFolder().also(::saved)
        val creates = mutableListOf<Boolean>()
        val t = Store(gone) { creates += it; if (it) key else error("no key") }
        assertTrue(t.unreadable.value)
        assertTrue(t.startFresh())
        assertTrue(t.update { it.copy(defaultRoute = "new") })
        assertEquals(false, creates.first())
        assertEquals(true, creates.last())
    }

    /** A reset confirmed after the files opened again (a VPN start reopens them) deletes nothing. */
    @Test fun startFreshAfterRecoveryKeepsTheData() {
        val dir = tmp.newFolder().also(::saved)
        var broken = true
        val s = Store(dir) { if (broken) error("no key") else key }
        assertTrue(s.unreadable.value)
        broken = false
        assertTrue(s.reopen())
        assertTrue(s.startFresh())
        assertEquals("x", s.setup.value.defaultRoute)
        Store(dir) { key }.apply { assertEquals("x", setup.value.defaultRoute); assertEquals(mapOf("nord" to "blob"), accounts.value) }
    }

    /** A Tailscale folder that won't go fails the reset before anything else is deleted; the mark stays. */
    @Test fun startFreshFailsWhileTailscaleStays() {
        val dir = tmp.newFolder().also(::saved)
        File(dir, "setup.bin").writeText("garbage")
        val ts = File(dir, "tailscale").apply { mkdir(); resolve("state.bin").writeText("x"); setWritable(false) }
        val s = Store(dir) { key }
        try {
            assertFalse(s.startFresh())
            assertTrue(s.unreadable.value)
            assertTrue(File(dir, "setup.bin").exists() && File(dir, "accounts.bin").exists())
        } finally { ts.setWritable(true) }
        assertTrue(s.startFresh())
        assertFalse(ts.exists())
    }

    /** The key may be created only while no sealed file exists. */
    @Test fun noNewKeyBesideSealedFiles() {
        val creates = mutableListOf<Boolean>()
        Store(tmp.newFolder()) { creates += it; key }.update { it.copy(defaultRoute = "x") }
        assertEquals(listOf(true), creates)
        val dir = tmp.newFolder().also(::saved)
        creates.clear()
        Store(dir) { creates += it; key }
        assertEquals(listOf(false), creates)
        File(dir, "setup.bin").delete(); File(dir, "accounts.bin").delete()
        File(dir, "tailscale").mkdir(); File(dir, "tailscale/state.bin").writeText("x")
        creates.clear()
        Store(dir) { creates += it; key }.update { it }
        assertEquals(listOf(false), creates)
    }

    /** The preview builds' Nord files move only into a missing accounts file, never over one that won't open. */
    @Test fun oldNordFilesAreNotMigratedOverAnUnreadableAccountsFile() {
        val dir = tmp.newFolder()
        File(dir, "token.bin").writeBytes(sealed("t")); File(dir, "nlk.bin").writeBytes(sealed("k"))
        File(dir, "accounts.bin").writeText("garbage")
        val s = Store(dir) { key }
        assertTrue(s.unreadable.value)
        assertTrue(s.accounts.value.isEmpty())
        assertEquals("garbage", File(dir, "accounts.bin").readText())
        assertTrue(File(dir, "token.bin").exists() && File(dir, "nlk.bin").exists())
        File(dir, "accounts.bin").delete()
        assertEquals(setOf("nord"), Store(dir) { key }.accounts.value.keys)
        assertFalse(File(dir, "token.bin").exists())
    }

    /** A Tailscale node that won't open throws a clear error and isn't written over; the store itself isn't marked. */
    @Test fun unreadableTailscaleStateThrowsAndStays() {
        val dir = tmp.newFolder()
        val f = File(dir, "tailscale/state.bin").apply { parentFile!!.mkdirs(); writeText("garbage") }
        File(dir, "tailscale/tailscaled.state").writeText("{\"old\":1}")
        val s = Store(dir) { key }
        assertFalse(s.unreadable.value)
        val e = runCatching { s.tsState }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.startsWith("Couldn't open the saved Tailscale sign-in"))
        assertTrue(runCatching { s.tsState = "{\"new\":1}" }.isFailure)
        assertEquals("garbage", f.readText())
        // Signed out of Tailscale (its folder gone), a new node is saved.
        File(dir, "tailscale").deleteRecursively()
        assertEquals("", s.tsState)
        File(dir, "tailscale").mkdir() // the engine makes its folder again
        s.tsState = "{\"new\":1}"
        assertEquals("{\"new\":1}", Store(dir) { key }.tsState)
    }

    /** Old plaintext Tailscale state is sealed, then deleted; never deleted before it is sealed. */
    @Test fun tailscaleStateMigratesOnlyOnceSealed() {
        val dir = tmp.newFolder()
        val plain = File(dir, "tailscale/tailscaled.state").apply { parentFile!!.mkdirs(); writeText("{\"node\":1}") }
        val s = Store(dir) { key }
        File(dir, "tailscale").setWritable(false)
        assertEquals("{\"node\":1}", s.tsState)
        assertTrue(plain.exists())
        assertTrue(s.unsaved.value) // shown until it is sealed
        File(dir, "tailscale").setWritable(true)
        assertTrue(s.saveAgain())
        assertFalse(plain.exists())
        assertFalse(s.unsaved.value)
        assertEquals("{\"node\":1}", Store(dir) { key }.tsState)
    }
    @Test fun updateAndNudgeSettingsRoundTripAndDefaultOnCorruption() {
        val dir = tmp.newFolder()
        val s = Store(dir) { key }
        assertEquals(UpdateState(), s.updates); assertFalse(s.updates.enabled); assertFalse(s.updates.asked); assertEquals(NudgeState(), s.nudges)
        val updates = UpdateState(true, 123, 4, Release(5, "1.2"), asked = true)
        val nudges = NudgeState(10, listOf(1, 2), listOf(20), 30, true)
        s.updates = updates; s.nudges = nudges
        val again = Store(dir) { key }
        assertEquals(updates, again.updates); assertEquals(nudges, again.nudges)
        File(dir, "updates.json").writeText("bad"); File(dir, "nudges.json").writeText("bad")
        assertEquals(UpdateState(), again.updates); assertEquals(NudgeState(), again.nudges)
    }
    @Test fun updateAndNudgeWritesTolerateIoFailure() {
        val dir = tmp.newFolder()
        val s = Store(dir) { key }
        File(dir, "updates.json.tmp").mkdir()
        File(dir, "nudges.json.tmp").mkdir()
        s.updates = UpdateState(enabled = true)
        s.nudges = NudgeState(disabled = true)
        assertFalse(s.updates.enabled)
        assertFalse(s.nudges.disabled)
    }


    private fun opened(f: File) = f.readBytes().let { b -> String(Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b, 0, 12)) }.doFinal(b, 12, b.size - 12)) }

    /** A file as earlier versions wrote it (values equal to a default left out) loads to the same setup; saved again, every value is written. */
    @Test fun olderFormatLoadsTheSameAndSavesEveryValue() {
        val dir = tmp.newFolder()
        // Verbatim shape of a version 1 file: no "provider":"nord", no "defaultRoute":"nl", no "lan":true, no "hue":0.
        val v1 = """{"routes":[{"id":"nl","loc":{"countryId":153,"countryCode":"NL","country":"Netherlands"}},""" +
            """{"id":"us","loc":{"provider":"mullvad","countryCode":"US","country":"United States","region":"us-nyc"},"hue":1}],""" +
            """"apps":{"org.example.mail":"us"},"version":1,"profiles":[{"name":"Home","defaultRoute":"nl","apps":{"org.example.mail":"us"},"added":["us"]}],""" +
            """"added":["us"],"profile":"Home"}"""
        File(dir, "setup.bin").writeBytes(sealed(v1))
        val expected = Setup(
            routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands")), Route("us", Location("mullvad", countryCode = "US", country = "United States", region = "us-nyc"), hue = 1)),
            defaultRoute = "nl", apps = mapOf("org.example.mail" to "us"), version = VERSION,
            profiles = listOf(Profile("Home", "nl", mapOf("org.example.mail" to "us"), listOf("us"))), added = listOf("us"), profile = "Home",
        )
        val s = Store(dir) { key }
        assertEquals(expected, s.setup.value)
        assertTrue(s.update { it })
        val text = opened(File(dir, "setup.bin"))
        for (k in listOf("\"provider\":\"nord\"", "\"defaultRoute\":\"nl\"", "\"lan\":true", "\"threatProtection\":true", "\"hue\":0", "\"stats\":false", "\"version\":$VERSION"))
            assertTrue(k, k in text)
        assertEquals(expected, Store(dir) { key }.setup.value)

        // A fuller setup written by the earlier encoder (defaults left out) reads back whole, only its version moves on.
        val full = Setup(
            routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands")), Route("w", wg = WgPeer("Home VPS", "k", listOf("10.0.0.2/32"), "p", endpoint = "192.0.2.1:51820", allowedIps = listOf("0.0.0.0/0")), own = "s1", filter = false)),
            defaultRoute = "w", apps = mapOf("a" to "nl", "b" to "direct"), home = Home(tailscale = true, subnets = listOf("192.168.1.0/24")),
            prefs = Prefs(lan = false, stats = true, filter = Filter("adguard", routes = true)), servers = listOf(OwnServer("s1", wg = WgPeer("Home VPS", "k", listOf("10.0.0.2/32"), "p", endpoint = "192.0.2.1:51820", allowedIps = listOf("0.0.0.0/0")))),
            version = 1, opts = mapOf("nord" to mapOf("protect" to "off")),
        )
        val old = tmp.newFolder()
        File(old, "setup.bin").writeBytes(sealed(NordApi.json.encodeToString(Setup.serializer(), full)))
        assertEquals(full.copy(version = VERSION), Store(old) { key }.setup.value)
    }

    /** Settings that name a missing route load as they are and stay so through later edits: nothing goes to Direct or elsewhere. */
    @Test fun missingRoutesStayNamedAfterLoadAndEdits() {
        val dir = tmp.newFolder()
        val damaged = Setup(routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands"))), defaultRoute = "gone",
            apps = mapOf("mail" to "lost", "maps" to "nl"), profiles = listOf(Profile("Trip", "away", mapOf("rai" to "x"))), version = VERSION)
        File(dir, "setup.bin").writeBytes(sealed(NordApi.json.encodeToString(Setup.serializer(), damaged)))
        val s = Store(dir) { key }
        assertEquals(damaged, s.setup.value)
        assertTrue(s.update { it.copy(favorites = emptyList()) }) // an unrelated edit
        assertEquals(damaged.copy(favorites = emptyList()), s.setup.value)
        assertTrue(s.setup.value.defaultMissing)
        // Removing a route mends only what used that route.
        assertTrue(s.update { it.copy(routes = emptyList()) })
        assertEquals(mapOf("mail" to "lost"), s.setup.value.apps)
        assertEquals(Profile("Trip", "away", mapOf("rai" to "x")), s.setup.value.profiles.single())
        assertEquals("gone", Store(dir) { key }.setup.value.defaultRoute)
    }

    /** A sign-out keeps the account as a pending release until the provider lets go; a later success clears it. */
    @Test fun signOutKeepsAPendingReleaseUntilReleased() {
        val dir = tmp.newFolder()
        val s = Store(dir) { key }
        assertTrue(s.account("mullvad", "blob-m")); assertTrue(s.account("ivpn", "blob-i"))
        val out = s.signOut("mullvad", now = 1_000)!!
        assertEquals("blob-m", out.blob); assertTrue(out.kept)
        assertFalse(out.toString().contains("blob-m"))
        assertEquals(setOf("ivpn"), s.accounts.value.keys) // signed out at once
        assertEquals(null, s.signOut("mullvad")) // nothing left to sign out
        // The release failed (or the app closed): it survives a restart, sealed, and the account stays out.
        val again = Store(dir) { key }
        assertEquals(listOf(app.unbraid.data.PendingRelease("mullvad", "blob-m", since = 1_000)), again.pendingReleases)
        assertEquals(setOf("ivpn"), again.accounts.value.keys)
        assertFalse(File(dir, "releases.bin").readText(Charsets.ISO_8859_1).contains("blob-m"))
        assertFalse(again.pendingReleases.toString().contains("blob-m"))
        // Two providers' slots wait side by side.
        again.signOut("ivpn")
        assertEquals(listOf("blob-m", "blob-i"), again.pendingReleases.map { it.blob })
        again.released(again.pendingReleases.first())
        assertEquals(listOf("blob-i"), Store(dir) { key }.pendingReleases.map { it.blob })
        again.released(again.pendingReleases.single())
        assertFalse(File(dir, "releases.bin").exists())
    }

    /** A sign-out cut short after its release was kept but before the account went is finished at the next start. */
    @Test fun anInterruptedSignOutIsFinished() {
        val dir = tmp.newFolder()
        Store(dir) { key }.apply { assertTrue(account("airvpn", "blob-a")); assertTrue(account("nord", "blob-n")) }
        // As if the app closed right after the release was written: the account file still lists it.
        File(dir, "releases.bin").writeBytes(sealed("""[{"provider":"airvpn","blob":"blob-a"},{"provider":"nord","blob":"an older one"}]"""))
        val s = Store(dir) { key }
        assertEquals(setOf("nord"), s.accounts.value.keys) // nord signed in again since: its newer account stays
        assertEquals(setOf("nord"), Store(dir) { key }.accounts.value.keys)
        assertEquals(2, s.pendingReleases.size)
    }

    /** A sign-out whose release can't be written still signs out, and says so: the caller releases at once. */
    @Test fun aReleaseThatCantBeKeptIsReleasedAtOnce() {
        val dir = tmp.newFolder()
        val s = Store(dir) { key }
        assertTrue(s.account("airvpn", "blob-a"))
        File(dir, "releases.bin.tmp").mkdir() // the journal can't be written
        val out = s.signOut("airvpn")!!
        assertFalse(out.kept)
        assertEquals("blob-a", out.blob)
        assertEquals(emptyMap<String, String>(), Store(dir) { key }.accounts.value) // signed out all the same, on disk
    }

    /**
     * Released while the account's removal couldn't be written, then the app closed: a record stays until the removal
     * is on disk, so the old file never brings the account back.
     */
    @Test fun aReleaseIsForgottenOnlyOnceTheSignOutIsOnDisk() {
        val dir = tmp.newFolder()
        val s = Store(dir) { key }
        assertTrue(s.account("mullvad", "blob-m"))
        File(dir, "accounts.bin.tmp").mkdir() // accounts can't be written
        val out = s.signOut("mullvad")!!
        assertTrue(out.kept)
        s.released(s.pendingReleases.single())
        assertEquals(emptyList<Any>(), s.pendingReleases) // not tried again
        assertTrue(File(dir, "releases.bin").exists()) // but its record stays
        File(dir, "accounts.bin.tmp").delete()
        val next = Store(dir) { key } // the app closed before a retry wrote the accounts
        assertEquals(emptyMap<String, String>(), next.accounts.value)
        assertEquals(emptyMap<String, String>(), Store(dir) { key }.accounts.value)
        assertFalse(File(dir, "releases.bin").exists()) // the sign-out is on disk now: nothing left to keep
    }

    /** A list of releases that won't open is kept aside unchanged, reported, and merged back once it opens. */
    @Test fun anUnreadableReleaseListIsKeptAside() {
        val dir = tmp.newFolder()
        Store(dir) { key }.apply { assertTrue(account("ivpn", "blob-i")) }
        val junk = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17)
        File(dir, "releases.bin").writeBytes(junk)
        val s = Store(dir) { key }
        assertTrue(s.releasesUnreadable)
        assertEquals(emptyList<Any>(), s.pendingReleases)
        assertTrue(s.signOut("ivpn")!!.kept) // a new list starts beside it
        assertTrue(junk.contentEquals(File(dir, "releases.bin.bad").readBytes())) // never written over
        assertEquals(listOf("blob-i"), Store(dir) { key }.pendingReleases.map { it.blob })
        // The one kept aside opens again (it was a passing failure): its releases join the list.
        File(dir, "releases.bin.bad").writeBytes(sealed("""[{"provider":"mullvad","blob":"blob-m"}]"""))
        val back = Store(dir) { key }
        assertEquals(setOf("blob-i", "blob-m"), back.pendingReleases.map { it.blob }.toSet())
        assertFalse(back.releasesUnreadable)
        assertFalse(File(dir, "releases.bin.bad").exists())
        // Told and dismissed: an unreadable one is deleted.
        File(dir, "releases.bin.bad").writeBytes(junk)
        val told = Store(dir) { key }
        assertTrue(told.releasesUnreadable)
        told.forgetUnreadableReleases()
        assertFalse(told.releasesUnreadable)
        assertEquals(setOf("blob-i", "blob-m"), told.pendingReleases.map { it.blob }.toSet())
    }

    /**
     * Signed out of AirVPN with the release failing, then signed in again (maybe adopting that very device by name): the
     * earlier release is dropped, so no later start removes the device the new sign-in uses.
     */
    @Test fun aNewSignInDropsTheProvidersEarlierRelease() {
        val dir = tmp.newFolder()
        val s = Store(dir) { key }
        assertTrue(s.account("airvpn", "blob-old")); assertTrue(s.account("ivpn", "blob-i"))
        s.signOut("airvpn"); s.signOut("ivpn")
        assertTrue(s.account("airvpn", "blob-adopted"))
        assertEquals(listOf("ivpn"), Store(dir) { key }.pendingReleases.map { it.provider })
    }

    /**
     * Signed out with the release pending, the list then kept aside as unreadable, signed in again: once the list opens
     * again, that earlier release doesn't come back (it could remove the new sign-in's device). Others do.
     */
    @Test fun aListKeptAsideDoesntBringBackAReleaseFromBeforeASignIn() {
        val dir = tmp.newFolder()
        val s = Store(dir) { key }
        assertTrue(s.account("airvpn", "blob-old")); assertTrue(s.account("ivpn", "blob-i"))
        s.signOut("airvpn"); s.signOut("ivpn")
        val real = File(dir, "releases.bin").readBytes()
        File(dir, "releases.bin").delete()
        File(dir, "releases.bin.bad").writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17)) // won't open now
        val later = Store(dir) { key }
        assertTrue(later.releasesUnreadable)
        assertTrue(later.account("airvpn", "blob-adopted"))
        File(dir, "releases.bin.bad").writeBytes(real) // opens again
        val back = Store(dir) { key }
        assertEquals(listOf("ivpn"), back.pendingReleases.map { it.provider })
        assertFalse(File(dir, "releases.bin.bad").exists())
        assertEquals("blob-adopted", back.accounts.value["airvpn"])
    }

    /** A failing release waits longer each time and is given up after a bounded number of tries or time: its account erased. */
    @Test fun failingReleasesBackOffAndStop() {
        val dir = tmp.newFolder()
        val s = Store(dir) { key }
        assertTrue(s.account("airvpn", "blob-a"))
        s.signOut("airvpn", now = 1_000)
        var now = 1_000L
        repeat(app.unbraid.data.RELEASE_TRIES - 1) { n ->
            val r = s.pendingReleases.single()
            assertTrue(s.due(r, now))
            assertFalse(s.failedRelease(r, now))
            val next = s.pendingReleases.single()
            assertEquals(n + 1, next.tries)
            assertEquals(now + app.unbraid.data.RELEASE_WAITS[n], next.next)
            assertFalse(s.due(next, now + 1)) // not before its time, nor at the next start
            now = next.next
        }
        assertTrue(Store(dir) { key }.pendingReleases.single().tries == app.unbraid.data.RELEASE_TRIES - 1) // kept across restarts
        assertTrue(s.failedRelease(s.pendingReleases.single(), now)) // the last try: given up
        assertFalse(File(dir, "releases.bin").exists())
        // Or two weeks after the first failure, however few tries.
        val r = app.unbraid.data.PendingRelease("ivpn", "b", since = 1_000, tries = 1)
        assertEquals(null, r.failedAt(1_000 + app.unbraid.data.RELEASE_FOR))
        assertEquals(2, r.failedAt(1_000 + 60_000)!!.tries)
    }

    /** While a removal is under way (Tailscale's log-out), no edit can make one of its routes a default. */
    @Test fun aReservedRouteCantBecomeADefault() {
        val dir = tmp.newFolder()
        val s = Store(dir) { key }
        val ts = Route("t", tsExit = "100.64.0.1")
        assertTrue(s.update { Setup(routes = listOf(ts, Route("nl")), defaultRoute = "nl", apps = mapOf("app" to "t")) })
        val gone = { x: Setup -> x.routes.filter { it.tsExit != null }.map { it.id }.toSet() }
        assertEquals(null, s.reserve(gone, "Tailscale")) // only an app's route: may go
        s.update { it.copy(defaultRoute = "t") } // e.g. a tile or Make default, meanwhile
        s.update { it.add("Trip", false).editing("Trip") { p -> p.copy(defaultRoute = "t") } }
        assertEquals("nl", s.setup.value.defaultRoute)
        assertEquals(emptyList<Any>(), s.setup.value.profiles)
        s.update { it.copy(apps = it.apps + ("other" to "t")) } // anything else still applies
        assertEquals("t", s.setup.value.apps["other"])
        s.unreserve(gone)
        s.update { it.copy(defaultRoute = "t") }
        assertEquals("t", s.setup.value.defaultRoute)
        assertEquals("Tailscale", s.reserve(gone, "Tailscale")!!.name) // now a default: refused, nothing held
        s.update { it.copy(defaultRoute = "nl") }
        s.update { it.copy(defaultRoute = "t") }
        assertEquals("t", s.setup.value.defaultRoute)
    }

    /** A new install's setup is pristine until its user changes it; an older file's never is. */
    @Test fun aNewSetupIsPristineUntilChanged() {
        val dir = tmp.newFolder()
        val s = Store(dir) { key }
        assertTrue(s.setup.value.pristine)
        assertTrue(s.update { it.copy(prefs = it.prefs.copy(stats = true)) })
        assertTrue(Store(dir) { key }.setup.value.pristine)
        assertTrue(s.update { it.copy(home = it.home.copy(tailscale = true)) })
        assertFalse(Store(dir) { key }.setup.value.pristine)
    }

    /** A catalogue counts as fresh for a day; one dated in the future (the clock was ahead) is refreshed. */
    @Test fun catalogueFromTheFutureIsNotFresh() {
        val dir = tmp.newFolder()
        val s = Store(dir) { key }
        assertFalse(s.countriesFresh("nord"))
        assertTrue(s.countries("nord", emptyList()))
        val f = File(dir, "countries-nord.json")
        val now = f.lastModified()
        assertTrue(s.countriesFresh("nord", now + 60_000))
        assertFalse(s.countriesFresh("nord", now + 86_400_000))
        assertFalse(s.countriesFresh("nord", now - 60_000)) // written a minute from now
        assertFalse(s.countriesFresh("nord", now - 86_400_000))
    }
}
