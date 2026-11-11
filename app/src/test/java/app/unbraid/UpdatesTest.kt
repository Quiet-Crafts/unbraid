package app.unbraid

import app.unbraid.data.Release
import app.unbraid.data.Store
import app.unbraid.data.UpdateState
import app.unbraid.data.Updates
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class UpdatesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun store() = Store(tmp.newFolder()) { key }

    @Test fun parsesOnlyValidReleases() {
        assertEquals(Release(52, "2.8.4"), Updates.parse("""{"versionCode":52,"versionName":"2.8.4"}"""))
        listOf("", "[]", "{}", "null", """{"versionCode":"52","versionName":"2.8"}""",
            """{"versionCode":52,"versionName":2.8}""", """{"versionCode":0,"versionName":"2.8"}""",
            """{"versionCode":1.0,"versionName":"2.8"}""", """{"versionCode":2147483648,"versionName":"2.8"}""",
            """{"versionCode":1,"versionName":"1234.1"}""", """{"versionCode":1,"versionName":"2"}""",
            """{"versionCode":1,"versionName":"2.3.4.5.6"}""").forEach { assertNull(it, Updates.parse(it)) }
    }

    @Test fun comparesAndDismisses() {
        val state = UpdateState(latest = Release(10, "1.0"))
        assertNull(Updates.newer(state, 10)); assertNull(Updates.newer(state, 11))
        assertEquals(state.latest, Updates.newer(state, 9))
        assertTrue(Updates.showCard(state, 9)); assertFalse(Updates.showCard(state.copy(dismissedCode = 10), 9))
        assertTrue(Updates.showCard(state.copy(dismissedCode = 9), 9)); assertFalse(Updates.showCard(state, 10))
    }

    @Test fun throttleAndClockRollback() {
        assertFalse(Updates.due(UpdateState(), 100_000_000))
        val s = UpdateState(enabled = true, lastAttemptMs = 100)
        assertFalse(Updates.due(s, 100 + 86_400_000 - 1))
        assertTrue(Updates.due(s, 100 + 86_400_000))
        val failed = s.copy(failed = true)
        assertFalse(Updates.due(failed, 100 + 3_600_000 - 1))
        assertTrue(Updates.due(failed, 100 + 3_600_000))
        assertTrue(Updates.due(s, 99)); assertFalse(Updates.due(s.copy(enabled = false), 99))
    }

    @Test fun failuresAreRecordedAndKeepLastRelease() = runBlocking {
        val s = store(); val old = Release(3, "1.0")
        s.updates = UpdateState(enabled = true, latest = old)
        val result = Updates.check(s, 1, 100_000_000) { null }
        assertEquals(100_000_000L, result.lastAttemptMs); assertEquals(old, result.latest); assertTrue(result.failed)
        var fetched = false
        Updates.check(s, 1, 100_000_001) { fetched = true; null }
        assertFalse(fetched); assertEquals(result, s.updates)
    }

    @Test fun forceAndFailureRecovery() = runBlocking {
        val s = store()
        s.updates = UpdateState(enabled = true, lastAttemptMs = 100_000_000)
        var fetched = false
        Updates.check(s, 1, 100_000_001) { fetched = true; null }
        assertFalse(fetched)
        val garbage = Updates.check(s, 1, 100_000_002, force = true) { "garbage" }
        assertTrue(garbage.failed); assertEquals(100_000_002L, garbage.lastAttemptMs)
        val success = Updates.check(s, 1, 100_000_003, force = true) { """{"versionCode":4,"versionName":"1.1"}""" }
        assertFalse(success.failed); assertEquals(Release(4, "1.1"), success.latest)
        s.updates = success.copy(enabled = false)
        Updates.check(s, 1, 100_000_004, force = true) { fetched = true; null }
        assertFalse(fetched); assertEquals(success.copy(enabled = false), s.updates)
    }

    @Test fun oversizedResponseIsRejected() = runBlocking {
        val s = store()
        s.updates = UpdateState(enabled = true)
        val text = "{\"versionCode\":4,\"versionName\":\"1.1\",\"extra\":\"" + "x".repeat(4096) + "\"}"
        assertNull(Updates.check(s, 1, 100_000_000) { text }.latest)
    }

    @Test fun disablingDuringFetchDiscardsResult() = runBlocking {
        val s = store(); val old = Release(3, "1.0")
        s.updates = UpdateState(enabled = true, latest = old)
        val result = Updates.check(s, 1, 100_000_000) {
            s.updates = s.updates.copy(enabled = false)
            """{"versionCode":4,"versionName":"1.1"}"""
        }
        assertFalse(result.enabled); assertEquals(old, result.latest); assertFalse(result.failed); assertEquals(result, s.updates)
    }
}
