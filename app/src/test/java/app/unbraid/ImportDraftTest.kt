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
}
