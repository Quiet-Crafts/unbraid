package app.unbraid

import app.unbraid.ui.ImportDraft
import app.unbraid.ui.unlessReplaced
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An import whose parse is still running when its prompt is closed or replaced saves nothing and shows nothing. */
class ImportDraftTest {
    private fun run(between: (ImportDraft) -> Unit): Pair<ImportDraft, List<Result<String>>> {
        val d = ImportDraft().apply { wg = "[Interface]" }
        val applied = mutableListOf<Result<String>>()
        val parsing = CompletableDeferred<Unit>()
        runBlocking {
            val job = launch { d.unlessReplaced({ parsing.await(); "parsed" }) { applied += it } }
            yield() // the parse is now waiting
            between(d)
            parsing.complete(Unit)
            job.join()
        }
        return d to applied
    }

    @Test fun dismissedPromptGetsNoResult() {
        val (d, applied) = run { it.clear() }
        assertTrue(applied.isEmpty())
        assertNull(d.wg)
    }

    @Test fun replacedPromptKeepsTheNewOne() {
        val (d, applied) = run { it.clear(); it.link = "" }
        assertTrue(applied.isEmpty())
        assertEquals("", d.link) // the new Shadowsocks prompt is not cleared by the old import
        assertNull(d.error)
    }

    @Test fun anOpenPromptGetsItsResultOrError() {
        val (_, applied) = run { it.name = "edited" }
        assertEquals("parsed", applied.single().getOrThrow())
        val d = ImportDraft()
        var failure: Throwable? = null
        val ran = runBlocking { d.unlessReplaced<String>({ throw IllegalArgumentException("bad") }) { failure = it.exceptionOrNull() } }
        assertTrue(ran)
        assertEquals("bad", failure?.message)
        assertFalse(runBlocking { d.unlessReplaced({ d.clear(); "late" }) { failure = null } })
    }
    @Test fun aNewCameraCaptureCannotChangeAnEarlierOpenPicture() {
        val cache = java.nio.file.Files.createTempDirectory("camera-scan").toFile()
        val context = object : android.content.ContextWrapper(null) { override fun getCacheDir() = cache }
        try {
            val first = app.unbraid.ui.qrPhoto(context)
            first.parentFile!!.mkdirs()
            first.writeBytes(byteArrayOf(42))
            first.inputStream().use { earlier ->
                val next = app.unbraid.ui.qrPhoto(context)
                next.writeBytes(byteArrayOf(99))
                assertEquals(42, earlier.read())
                org.junit.Assert.assertArrayEquals(byteArrayOf(99), next.readBytes())
                assertEquals(1, next.parentFile!!.listFiles()!!.size)
            }
        } finally { cache.deleteRecursively() }
    }

    @Test fun qrSnapshotsUseOneBoundedCopyAndLeaveNoNamedContents() {
        val cache = java.nio.file.Files.createTempDirectory("qr-snapshot").toFile()
        try {
            val data = byteArrayOf(1, 2, 3, 4)
            val input = object : java.io.ByteArrayInputStream(data) { override fun available() = 0 }
            val second = app.unbraid.ui.qrSnapshot(cache, input, data.size) { copy ->
                assertTrue(cache.list().orEmpty().isEmpty())
                assertEquals(1, copy.read())
                data[0] = 42 // the source changes after the first pass
                copy.seek(0)
                copy.read()
            }
            assertEquals(1, second)
            val over = java.io.ByteArrayInputStream(ByteArray(64))
            assertNull(app.unbraid.ui.qrSnapshot(cache, over, 4) { error("oversized image reached the decoder") })
            assertEquals(59, over.available())
            val refused = runCatching { app.unbraid.ui.qrSnapshot(cache, data.inputStream(), 4) { error("decode failed") } }
            assertTrue(refused.isFailure)
            assertTrue(cache.list().orEmpty().isEmpty())
        } finally { cache.deleteRecursively() }
    }

    @Test fun qrPhotosAreSampledInBothOrientationsBeforeAllocatingPixels() {
        for ((width, height, factor) in listOf(Triple(2000, 2000, 1), Triple(4032, 3024, 4),
                Triple(3024, 4032, 4), Triple(4001, 20, 4), Triple(8000, 6000, 4), Triple(16320, 12240, 16))) {
            assertEquals(factor, app.unbraid.ui.qrSample(width, height))
        }
        assertNull(app.unbraid.ui.qrSample(-1, 1000))
        assertNull(app.unbraid.ui.qrSample(Int.MAX_VALUE, 1))
        assertNull(app.unbraid.ui.qrSample(16384, 16384))
        assertEquals(4_000_000, app.unbraid.ui.qrPixelCount(2000, 2000))
        assertNull(app.unbraid.ui.qrPixelCount(2001, 1))
        assertNull(app.unbraid.ui.qrPixelCount(0, 100))
        assertNull(app.unbraid.ui.qrPixelCount(65536, 65536))
    }
}
