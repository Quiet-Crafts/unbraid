package app.unbraid

import app.unbraid.stats.SpeedTest
import app.unbraid.ui.leavesPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsFixesTest {
    /** Turning the phone (or dark mode, or the font size) recreates the screen; only a real leave cancels a running test. */
    @Test fun aConfigurationChangeDoesNotCancelTheSpeedTest() {
        assertFalse(leavesPage(changingConfigurations = true))
        assertTrue(leavesPage(changingConfigurations = false))
    }

    /** A test that never got data says no test server can be reached; one that broke off after data keeps the old wording. */
    @Test fun anUnreachableTestServerHasItsOwnMessage() {
        assertEquals("No test server can be reached through this exit.", SpeedTest.failText(flowed = false))
        assertEquals("The test couldn't finish. Check the connection and try again.", SpeedTest.failText(flowed = true))
        assertEquals("M-Lab's test server can't be reached through it. Not in the totals.", SpeedTest.legFailText("download", flowed = false))
        assertEquals("Stopped during the upload: the exit stopped answering. Not in the totals.", SpeedTest.legFailText("upload", flowed = true))
    }

    @Test fun theAllowanceChecksFitWithoutCounting() {
        assertTrue(SpeedTest.fits(30, 10))
        assertFalse(SpeedTest.fits(31, 10))
        assertTrue(SpeedTest.fits(0, 1))
    }

    /** "Test with the nearest server" must not answer with the same "no test server in <country>" again. */
    @Test fun theNearestServerRetryNamesNoCountry() {
        assertEquals("Netherlands", SpeedTest.missingCountry(false, "Netherlands", "NL"))
        assertEquals("NL", SpeedTest.missingCountry(false, null, "NL"))
        assertNull(SpeedTest.missingCountry(true, "Netherlands", null))
        assertNull(SpeedTest.missingCountry(false, "", null))
    }
}
