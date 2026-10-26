package app.unbraid

import app.unbraid.stats.SpeedTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedSpeedTest {
    private fun leg(start: Long, end: Long, bytesPerMs: Long, step: Long = 20) =
        (start..end step step).map { it to (it - start) * bytesPerMs }

    /** A sample is returned as is, a time between two samples is interpolated, and outside the samples the nearest one holds. */
    @Test fun bytesAreInterpolatedAndClampedAtTheEnds() {
        val s = listOf(1000L to 100L, 2000L to 300L)
        assertEquals(100L, SpeedTest.bytesAt(s, 1000)); assertEquals(300L, SpeedTest.bytesAt(s, 2000))
        assertEquals(200L, SpeedTest.bytesAt(s, 1500)); assertEquals(150L, SpeedTest.bytesAt(s, 1250))
        assertEquals(100L, SpeedTest.bytesAt(s, 500)); assertEquals(300L, SpeedTest.bytesAt(s, 3000))
    }

    /** Only the stretch both legs ran counts, so a leg that was fast early on does not inflate the sum. */
    @Test fun onlyTheOverlapCounts() {
        // A: 2000 B/ms until 2 s, then 250 B/ms until 10 s. B: 100 B/ms from 2 s to 8 s. Overlap 2 s..8 s = 6000 ms.
        // A moves 5_500_000 - 4_000_000 = 1_500_000 B, B moves 600_000 B: 2_100_000 * 8000 / 6000 = 2_800_000 bit/s.
        val a = listOf(0L to 0L, 2000L to 4_000_000L, 10_000L to 6_000_000L)
        val b = listOf(2000L to 0L, 8000L to 600_000L)
        val r = SpeedTest.combined(listOf(a, b))
        assertEquals(2_800_000L to 6000L, r)
        // Summing each leg's whole-run average would give 4_800_000 + 800_000 = 5_600_000.
        assertNotEquals(5_600_000L, r!!.first)
    }

    /** An overlap under 3 s is too short to report; exactly 3 s is enough. */
    @Test fun aShortOverlapGivesNothing() {
        val a = leg(0, 5000, 10)
        assertNull(SpeedTest.combined(listOf(a, leg(2001, 5000, 10))))
        assertEquals(3000L, SpeedTest.combined(listOf(a, leg(2000, 5000, 10)))?.second)
    }

    /** No legs, or a leg that never got a sample, gives nothing. */
    @Test fun noLegsOrAnEmptyLegGivesNothing() {
        assertNull(SpeedTest.combined(emptyList()))
        assertNull(SpeedTest.combined(listOf(leg(0, 5000, 10), emptyList())))
    }

    /** Three exits at 400, 200 and 100 Mbit/s that started a little apart add up to about 700 Mbit/s. */
    @Test fun threeStaggeredLegsAddUp() {
        val r = SpeedTest.combined(listOf(leg(0, 10_000, 50_000), leg(150, 10_150, 25_000), leg(300, 10_300, 12_500)))
        assertNotNull(r)
        assertEquals(9700L, r!!.second)
        assertTrue(r.first in 693_000_000L..707_000_000L)
    }
}
