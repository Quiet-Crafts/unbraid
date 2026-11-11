package app.unbraid

import app.unbraid.support.NudgeState
import app.unbraid.support.Nudges
import app.unbraid.support.Nudges.DAY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NudgesTest {
    private val now = 1_000 * DAY
    private val ready = NudgeState(installedAtMs = now - 7 * DAY, connectedDays = listOf(1, 2, 3))

    @Test fun installedSevenDays() {
        assertTrue(Nudges.due(ready, now, true))
        assertFalse(Nudges.due(ready.copy(installedAtMs = now - 7 * DAY + 1), now, true))
    }

    @Test fun threeConnectedDays() {
        assertFalse(Nudges.due(ready.copy(connectedDays = listOf(1, 2)), now, true))
        assertTrue(Nudges.due(ready.copy(connectedDays = listOf(1, 2, 3)), now, true))
    }

    @Test fun sameDayCountsOnceAndThreeAreKept() {
        var s = NudgeState()
        s = Nudges.connectedDay(Nudges.connectedDay(s, 10), 10)
        assertEquals(listOf(10L), s.connectedDays)
        s = Nudges.connectedDay(Nudges.connectedDay(s, 11), 12)
        assertEquals(listOf(10L, 11L, 12L), s.connectedDays)
        assertEquals(s, Nudges.connectedDay(s, 13))
    }

    @Test fun midnightMakesANewDay() {
        val s = Nudges.connectedDay(Nudges.connectedDay(NudgeState(), 19_000), 19_001)
        assertEquals(2, s.connectedDays.size)
    }

    @Test fun ninetyDaysApart() {
        val s = Nudges.shown(ready, now)
        assertEquals(now + 90 * DAY, s.snoozeUntilMs)
        assertFalse(Nudges.due(s, now + 90 * DAY - 1, true))
        assertTrue(Nudges.due(s, now + 90 * DAY, true))
        // Even with the snooze cleared, a recent show holds the next one back.
        assertFalse(Nudges.due(s.copy(snoozeUntilMs = 0), now + 89 * DAY, true))
    }

    @Test fun fourInARollingYear() {
        val shows = listOf(0L, 90, 180, 270).map { now + it * DAY }
        val s = ready.copy(shownAtMs = shows, snoozeUntilMs = 0)
        assertFalse(Nudges.due(s, now + 360 * DAY, true))
        assertTrue(Nudges.due(s, now + 365 * DAY, true))
        assertEquals(4, Nudges.shown(s, now + 400 * DAY).shownAtMs.size)
        assertEquals(now + 90 * DAY, Nudges.shown(s, now + 400 * DAY).shownAtMs.first())
    }

    @Test fun disabledAndNotCalm() {
        assertFalse(Nudges.due(Nudges.disable(ready), now, true))
        assertFalse(Nudges.due(ready, now, false))
    }
}
