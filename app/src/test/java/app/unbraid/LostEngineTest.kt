package app.unbraid

import app.unbraid.data.AppIdentity
import app.unbraid.data.BLOCK
import app.unbraid.data.DIRECT
import app.unbraid.data.Filter
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.vpn.Compiler
import app.unbraid.vpn.FakePool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** With no engine left, apps wait offline: only those on Direct by choice stay out of the tunnel. */
class LostEngineTest {
    private val pin = "aa".repeat(32)
    private val route = Route("r", tsExit = "100.64.0.1")
    private val setup = Setup(routes = listOf(route), defaultRoute = DIRECT, apps = mapOf("d" to DIRECT, "v" to "r", "b" to BLOCK, "gone" to "x"),
        appSigners = mapOf("d" to pin, "v" to pin, "gone" to pin))
    private fun out(s: Setup, lockdown: Boolean = false, kin: (String) -> Set<String>? = { setOf(it) }, identity: (String) -> AppIdentity? = { AppIdentity(pin, emptySet()) }) =
        Compiler.mayStayOut(s, lockdown, kin, identity)

    @Test fun onlyVerifiedDirectAppsStayOut() {
        assertEquals(setOf("d"), out(setup)) // not the app on a route, on Block, on a route that is gone, nor any that follows a Direct default
        assertTrue(out(setup, lockdown = true).isEmpty())
        assertTrue(out(setup, identity = { AppIdentity("bb".repeat(32), emptySet()) }).isEmpty())
        assertTrue(out(setup, identity = { null }).isEmpty())
        assertTrue(out(setup, kin = { null }).isEmpty())
        assertTrue(out(setup, kin = { setOf(it, "v") }).isEmpty()) // shares its user id with an app on a route
        assertTrue(out(setup.copy(prefs = setup.prefs.copy(filter = Filter(id = "own", value = "9.9.9.9", directApps = true)))).isEmpty())
    }

    @Test fun anAppMovedOffDirectIsInsideAtOnce() {
        for (to in listOf("r", BLOCK)) assertTrue(out(setup.copy(apps = setup.apps + ("d" to to))).isEmpty())
    }

    @Test fun triesSlowDownAndNeverStop() {
        val waits = (0..20).map(FakePool::lostWait)
        assertEquals(5_000L, waits.first())
        assertEquals(waits.sorted(), waits)
        assertEquals(600_000L, waits.last())
        assertEquals(600_000L, FakePool.lostWait(Int.MAX_VALUE))
    }
}
