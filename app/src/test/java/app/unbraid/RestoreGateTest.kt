package app.unbraid

import app.unbraid.data.Backup
import app.unbraid.data.DIRECT
import app.unbraid.data.Location
import app.unbraid.data.Manual
import app.unbraid.data.OwnServer
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.data.VERSION
import app.unbraid.ui.RestoreGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When Restore can be tapped: only once the check is in for this very backup, this setup and this keep choice. */
class RestoreGateTest {
    private val here = Setup(routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands"))), defaultRoute = "nl", apps = mapOf("org.example.mail" to "nl"), version = VERSION)
    private fun open(s: Setup) = Backup.read(Backup.export(s, "3.0.2", 0))

    @Test fun restoreWaitsForTheCheckOfThisBackupSetupAndChoice() {
        val g = RestoreGate()
        val bytes = Backup.export(here.copy(defaultRoute = DIRECT), "3.0.2", 0)
        val first = Backup.read(bytes)
        g.open(first)
        assertFalse(g.ready(here, true))
        val a = g.ask(here, true)!!
        // The same check under way is not asked again, and Restore waits for it.
        assertNull(g.ask(here, true))
        assertFalse(g.ready(here, true))
        val r = RestoreGate.check(a)
        assertEquals(listOf("The default route goes from Netherlands to Direct."), r.notes)
        assertTrue(r.keepable)
        g.answer(a, r)
        assertTrue(g.ready(here, true))
        // "Keep my current setup" turned off: not before the check for that choice is in, and an answer for the old one doesn't count.
        assertFalse(g.ready(here, false))
        val b = g.ask(here, false)!!
        assertFalse(g.ready(here, true))
        g.answer(a, r)
        assertFalse(g.ready(here, false))
        g.answer(b, RestoreGate.check(b))
        assertTrue(g.ready(here, false))
        // The setup here changed meanwhile.
        assertFalse(g.ready(here.copy(apps = emptyMap()), false))
        // A second file picked, even one with the same contents: nothing carries over, and a late answer for the first is dropped.
        val second = Backup.read(bytes)
        g.open(second)
        assertFalse(g.ready(here, false))
        g.answer(b, RestoreGate.check(b))
        assertFalse(g.ready(here, false))
        val c = g.ask(here, false)!!
        assertFalse(g.ready(here, false))
        g.answer(c, RestoreGate.check(c))
        assertTrue(g.ready(here, false))
        // Closed: nothing to restore.
        g.open(null)
        assertNull(g.ask(here, false))
        assertFalse(g.ready(here, false))
    }

    @Test fun weakServersAreAcceptedForTheBackupTheyWereAcceptedFor() {
        val ss = Manual.parseSs("ss://aes-256-cfb:secret@198.51.100.20:8388", "Old box")
        val weak = here.copy(routes = here.routes + Route("x", ss = ss, own = "sx"), servers = listOf(OwnServer("sx", ss = ss)), apps = mapOf("org.example.mail" to "x"))
        val g = RestoreGate()
        val first = open(weak)
        g.open(first)
        val a = g.ask(here, false)!!
        g.answer(a, RestoreGate.check(a))
        assertEquals(listOf("Old box: aes-256-cfb has no integrity check"), g.result!!.weak)
        assertFalse(g.ready(here, false))
        g.acceptWeak = true
        assertTrue(g.ready(here, false))
        g.acceptWeak = false
        assertFalse(g.ready(here, false))
        g.acceptWeak = true
        // Another file with the same weak server: accepted again or not at all.
        g.open(open(weak))
        assertFalse(g.acceptWeak)
        val b = g.ask(here, false)!!
        g.answer(b, RestoreGate.check(b))
        assertFalse(g.ready(here, false))
        g.acceptWeak = true
        assertTrue(g.ready(here, false))
    }

    /** What the sheet says comes from the check, worked out with the keep choice: a setup that can't be kept says so. */
    @Test fun theCheckSaysWhatTheSheetNeeds() {
        val wg = app.unbraid.data.WgConf.parse("[Interface]\nPrivateKey = ${"A".repeat(42)}E=\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = ${"B".repeat(42)}E=\nEndpoint = 203.0.113.1:51820\nAllowedIPs = 0.0.0.0/0\n", "Box")
        val withServer = here.copy(routes = here.routes + Route("w", wg = wg, own = "s"), servers = listOf(OwnServer("s", wg = wg)))
        val keyless = Backup.read(Backup.export(withServer, "3.0.2", 0, keys = false))
        val fresh = RestoreGate.check(RestoreGate.Ask(Setup(pristine = true), keyless, true))
        assertEquals(1, fresh.missing)
        // A new install's placeholder isn't kept; this phone's setup is, and has the server's config.
        assertFalse(fresh.keepable)
        val same = RestoreGate.check(RestoreGate.Ask(withServer, keyless, true))
        assertTrue(same.keepable)
        assertEquals(0, same.missing)
    }
}
