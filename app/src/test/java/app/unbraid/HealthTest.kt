package app.unbraid

import app.unbraid.vpn.Health
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTest {
    private fun blame(down: Set<String>, checked: Set<String>, direct: Boolean): Set<String>? {
        var asked = false
        return runBlocking { Health.blame(down, checked) { asked = true; direct } }.also {
            // The phone's own network is only checked when every tunnel failed.
            assertEquals(down.isNotEmpty() && down == checked, asked)
        }
    }

    @Test fun everyTunnelDownWithTheNetworkDownBlamesNoServer() {
        assertNull(blame(setOf("a"), setOf("a"), direct = false))
        assertNull(blame(setOf("a", "b"), setOf("a", "b"), direct = false))
    }

    @Test fun everyTunnelDownWhileTheNetworkWorksBlamesThem() = assertEquals(setOf("a"), blame(setOf("a"), setOf("a"), direct = true))

    @Test fun somePartialOrNoFailuresNeedNoNetworkCheck() {
        assertEquals(setOf("a"), blame(setOf("a"), setOf("a", "b"), direct = false))
        assertEquals(emptySet<String>(), blame(emptySet(), setOf("a"), direct = false))
    }

    /** On and off counts once three of the last six checks failed, and clears after three answers in a row. */
    @Test fun keepsDropping() {
        fun h(vararg ok: Boolean) = ok.fold(emptyList<Boolean>()) { acc, b -> Health.record(acc, b) }
        assertEquals(false, Health.dropping(h(true, false, true, false, true)))
        assertEquals(true, Health.dropping(h(true, false, true, false, true, false)))
        assertEquals(true, Health.dropping(h(false, true, false, true, false)))
        assertEquals(false, Health.dropping(h(false, true, false, true, false, true, true, true)))
        assertEquals(true, Health.dropping(h(false, false, false))) // dead too: moved by the other rule, flagged meanwhile
        assertEquals(6, h(true, true, true, true, true, true, true, false).size)
    }

    /** A failing tunnel: checked again after 10 and 20 s, then a minute after its first failure; dead only then. */
    @Test fun aFailingTunnelGetsAMinuteOnItsOwnServer() {
        var s = Health.Streak()
        assertNull(Health.due(s))
        s = Health.next(s, ok = false, now = 1_000)
        assertEquals(11_000L, Health.due(s))
        s = Health.next(s, ok = false, now = 11_000)
        assertEquals(21_000L, Health.due(s))
        s = Health.next(s, ok = false, now = 21_000)
        assertEquals(61_000L, Health.due(s))
        assertFalse(Health.dead(s, 61_000))
        s = Health.next(s, ok = false, now = 61_000)
        assertTrue(Health.dead(s, 61_000))
        // One answer ends the streak.
        assertEquals(Health.Streak(), Health.next(s, ok = true, now = 62_000))
    }

    /** A regular round before the minute is up counts, but doesn't end the minute early. */
    @Test fun aRegularRoundDoesntShortenTheMinute() {
        var s = Health.Streak()
        listOf(0L, 10_000L, 20_000L, 48_000L).forEach { s = Health.next(s, ok = false, now = it) }
        assertFalse(Health.dead(s, 48_000))
        assertEquals(60_000L, Health.due(s))
        s = Health.next(s, ok = false, now = 60_000)
        assertTrue(Health.dead(s, 60_000))
        // One just before the minute doesn't push the deadline back.
        var late = Health.Streak()
        listOf(0L, 10_000L, 20_000L, 58_000L).forEach { late = Health.next(late, ok = false, now = it) }
        assertEquals(60_000L, Health.due(late))
    }

    /** A route handled moments ago waits for its cooldown, and isn't checked on its own before then. */
    @Test fun aHeldRouteWaitsForItsCooldown() {
        var s = Health.Streak()
        listOf(0L, 10_000L, 20_000L, 60_000L).forEach { s = Health.next(s, ok = false, now = it) }
        val hold = 60_000L + Health.COOLDOWN
        assertFalse(Health.dead(s, 70_000, hold))
        assertEquals(hold, Health.due(s, hold))
        assertTrue(Health.dead(Health.next(s, ok = false, now = hold), hold, hold))
        assertEquals(hold, Health.due(Health.next(Health.Streak(), ok = false, now = 0), hold)) // quick re-checks wait too
    }
}
