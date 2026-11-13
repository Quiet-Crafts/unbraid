package app.unbraid

import androidx.compose.ui.unit.dp
import app.unbraid.ui.tileWidth
import app.unbraid.ui.wordScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutFitTest {
    /** A word that fits keeps its size; a wider one shrinks to fit, never below the floor. */
    @Test fun aWordTooWideShrinksWithinLimits() {
        assertEquals(1f, wordScale(widest = 200f, available = 300f, floor = 0.6f))
        assertEquals(1f, wordScale(widest = 300f, available = 300f, floor = 0.6f))
        val k = wordScale(widest = 400f, available = 300f, floor = 0.6f)
        assertTrue(k < 0.75f && k > 0.7f)
        assertTrue(400f * k <= 300f)
        assertEquals(0.6f, wordScale(widest = 1000f, available = 300f, floor = 0.6f))
        // Nothing measured, or no room known: unchanged.
        assertEquals(1f, wordScale(widest = 0f, available = 300f, floor = 0.6f))
        assertEquals(1f, wordScale(widest = 100f, available = 0f, floor = 0.6f))
    }

    /** Route tiles keep their width at normal text, grow with large text, and never pass the line. */
    @Test fun routeTilesGrowWithLargeText() {
        assertEquals(96.dp, tileWidth(1f, 300.dp))
        assertEquals(96.dp, tileWidth(0.85f, 300.dp))
        assertEquals(192.dp, tileWidth(2f, 300.dp))
        assertEquals(150.dp, tileWidth(2f, 150.dp))
    }
}
