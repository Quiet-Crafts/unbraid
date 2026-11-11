package app.unbraid

import app.unbraid.data.Store
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

}
