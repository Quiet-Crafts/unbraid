package app.unbraid

import app.unbraid.ui.leavesPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsFixesTest {
    /** Turning the phone (or dark mode, or the font size) recreates the screen; only a real leave cancels a running test. */
    @Test fun aConfigurationChangeDoesNotCancelTheSpeedTest() {
        assertFalse(leavesPage(changingConfigurations = true))
        assertTrue(leavesPage(changingConfigurations = false))
    }
}
