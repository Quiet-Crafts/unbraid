package app.unbraid

import app.unbraid.data.AuthException
import app.unbraid.data.DIRECT
import app.unbraid.data.Location
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.vpn.blockedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceTextTest {
    private val nl = Route("nl", Location("nord", 153, "NL", "Netherlands"))
    private val setup = Setup(routes = listOf(nl), defaultRoute = "nl")

    /** A default route that is gone is refused before any provider is asked; Direct as the default is a real choice. */
    @Test fun missingDefaultIsFoundBeforeConnecting() {
        assertFalse(setup.defaultMissing)
        assertFalse(setup.copy(defaultRoute = DIRECT).defaultMissing)
        assertTrue(setup.copy(defaultRoute = "gone").defaultMissing)
        assertTrue(setup.copy(routes = emptyList()).defaultMissing)
    }

    /** Apps on a route that no longer exists are said to be blocked, beside any route waiting for a server. */
    @Test fun appsOnAMissingRouteAreNamed() {
        val gone = "Apps on a route that no longer exists are blocked. Choose a route for them in unbraid."
        assertNull(blockedText(setup, emptyMap()))
        assertEquals(gone, blockedText(setup.copy(apps = mapOf("com.a" to "gone")), emptyMap()))
        assertNull(blockedText(setup.copy(apps = mapOf("com.a" to DIRECT, "com.b" to "nl")), emptyMap()))
        val both = blockedText(setup.copy(apps = mapOf("com.a" to "gone")), mapOf("nl" to AuthException("Sign in again")))!!
        assertTrue(both.startsWith("Apps on ${nl.name} are blocked: Sign in again"))
        assertTrue(both.endsWith(gone))
    }

    /** Release logs keep only the class of a foreign error, which can echo a key with no name; the app's own text stays. */
    @Test fun releaseLogsNoForeignErrorText() {
        val key = "aGVsbG8gd29ybGQgdGhpcyBpcyBhIGtleQ"
        val foreign = IllegalStateException("bad value $key")
        assertEquals("IllegalStateException", app.unbraid.vpn.logLine(foreign, debug = false))
        assertFalse(app.unbraid.vpn.logText("parse $key", debug = false).contains(key))
        assertEquals("Fail: The connection engine couldn't start.", app.unbraid.vpn.logLine(app.unbraid.vpn.Fail("The connection engine couldn't start."), debug = false))
        assertTrue(app.unbraid.vpn.logLine(foreign, debug = true).startsWith("IllegalStateException: bad value"))
    }
}
