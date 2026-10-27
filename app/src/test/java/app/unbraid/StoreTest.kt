package app.unbraid

import app.unbraid.data.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
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

    /** Signing out of everything also removes the copies kept of files that once failed to open. */
    @Test fun signOutAllRemovesBadCopies() {
        val dir = tmp.newFolder()
        File(dir, "accounts.bin").writeText("not sealed")
        val s = Store(dir) { key }
        assertTrue(File(dir, "accounts.bin.bad").exists())
        s.signOutAll()
        assertFalse(File(dir, "accounts.bin.bad").exists())
        // The undecodable file is replaced too: reopening finds no account and makes no new copy.
        assertEquals(emptyMap<String, String>(), Store(dir) { key }.accounts.value)
        assertFalse(File(dir, "accounts.bin.bad").exists())
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
}
