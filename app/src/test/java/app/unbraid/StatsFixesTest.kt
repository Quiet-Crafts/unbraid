package app.unbraid

import app.unbraid.stats.SpeedTest
import app.unbraid.stats.Stats
import app.unbraid.ui.appLabel
import app.unbraid.ui.dataSummary
import app.unbraid.ui.heatAlpha
import app.unbraid.ui.heatSummary
import app.unbraid.ui.weekSummary
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import app.unbraid.ui.leavesPage
import app.unbraid.ui.sharePct
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

    /** While another Tailscale node is tested, the apps on Tailscale's tunnel are counted to that node too. */
    @Test fun nodeTestTrafficIsCountedToTheTestedNode() {
        val tag = app.unbraid.vpn.Compiler.TS_TAG
        val node = SpeedTest.Run(SpeedTest.NODE + "100.64.0.5", SpeedTest.Phase.Download)
        assertEquals("ts:100.64.0.5", SpeedTest.nodeExit(node, tag))
        assertNull(SpeedTest.nodeExit(node, "direct")) // other tunnels are not Tailscale's
        assertNull(SpeedTest.nodeExit(node.copy(phase = SpeedTest.Phase.Done), tag)) // test over
        assertNull(SpeedTest.nodeExit(SpeedTest.Run("r1", SpeedTest.Phase.Download), tag)) // a route test, not a node test
        assertNull(SpeedTest.nodeExit(null, tag))
    }

    /** 6 GB through one exit and 2 GB direct: the exit is 75% everywhere; shares round to the nearest percent. */
    @Test fun sharesDivideBySameTotalAndRound() {
        assertEquals(75, sharePct(6_000_000_000, 8_000_000_000))
        assertEquals(67, sharePct(2, 3)) // 66.7, not 66
        assertEquals(0, sharePct(0, 0))
    }

    @Test fun removedUidOnlyAndSharedAppsAreNamedPlainly() {
        val saved = Stats.AppName("Spotify", false)
        assertEquals("Spotify", appLabel("com.spotify.music", "Spotify", saved))
        assertEquals("Removed app (Spotify)", appLabel("com.spotify.music", null, saved))
        assertEquals("Removed app", appLabel("com.spotify.music", null, null)) // never "music"
        assertEquals("Other system traffic", appLabel(Stats.OTHER, null, null))
        assertEquals("Unknown app", appLabel("?", null, null))
        assertEquals("Mail and others", appLabel("a.mail", "Mail", Stats.AppName("Mail", true)))
    }

    @Test fun uidOnlyTrafficFoldsIntoOneRowAndSharedUidsAreFlagged() {
        val rows = listOf(Triple("uid:10234", "r1", 5L), Triple("a.b", "r1", 9L), Triple("uid:10555", "r1", 7L), Triple("uid:10234", "r2", 1L))
        assertEquals(listOf(Triple("uid:", "r1", 12L), Triple("a.b", "r1", 9L), Triple("uid:", "r2", 1L)), Stats.foldUid(rows))
        assertEquals(Stats.AppId("uid:42", false), Stats.resolveApp(null, 42, emptyList()))
        assertEquals(Stats.AppId("a.one", true), Stats.resolveApp(null, 42, listOf("b.two", "a.one")))
        assertEquals(Stats.AppId("x.pkg", false), Stats.resolveApp("x.pkg", 42, listOf("x.pkg")))
        assertEquals(Stats.AppId("?", false), Stats.resolveApp(null, -1, null))
    }

    /** Every calendar level is told apart, and an empty day is drawn outlined (no alpha). */
    @Test fun calendarLevelsDiffer() {
        assertNull(heatAlpha(0f))
        val levels = listOf(0.5f, 2f, 4f, 8f).map { heatAlpha(it)!! }
        assertEquals(levels.sorted(), levels)
        assertEquals(levels.size, levels.toSet().size)
        assertTrue(levels.zipWithNext().all { (a, b) -> b - a >= 0.15f })
    }

    @Test fun chartDescriptionsCarryFigures() {
        val d = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.US)
        val heat = listOf(1f, 11.5f, 3f, -1f)
        val text = heatSummary(heat, LocalDate.of(2026, 11, 2), 2, d)
        assertTrue(text, "Tuesday 3 November, 11.5 hours" in text)
        assertTrue("3.0 hours" in text)
        assertTrue("Total 3 MB" in dataSummary("day", listOf(1_000_000L, 2_000_000L), 1, { "${it / 1_000_000} MB" }))
        assertTrue("Mon 3.0 h" in weekSummary(listOf("Mon", "Tue"), listOf(3f, -1f)))
        assertTrue("Tue 0.0 h" in weekSummary(listOf("Mon", "Tue"), listOf(3f, -1f)))
    }
}
