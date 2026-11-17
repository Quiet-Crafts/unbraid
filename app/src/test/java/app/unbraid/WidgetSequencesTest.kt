package app.unbraid

import app.unbraid.data.Location
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.vpn.Health
import app.unbraid.vpn.TsNode
import app.unbraid.vpn.VpnState
import app.unbraid.vpn.VpnState.Phase
import app.unbraid.vpn.glance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Hostile sequences for the widget counts, seeded so a failure can be replayed: check results that arrive for an old
 * engine, network or server, rounds blamed on the phone's own network, and routes saved with another server meanwhile.
 * Each property is stated on its own, not as a copy of the code.
 */
class WidgetSequencesTest {
    private val srv = Server("s", "h", "1.1.1.1", "k", 1)
    private fun nord(id: String, n: Int, code: String, country: String) = Route(id, Location("nord", n, code, country))
    private val setup = Setup(
        routes = listOf(nord("nl", 1, "NL", "Netherlands"), nord("us", 2, "US", "United States"), nord("it", 3, "IT", "Italy")),
        defaultRoute = "nl", apps = mapOf("a" to "us", "b" to "it"),
    )
    private val ids = listOf("nl", "us", "it")
    private fun ran(s: Setup): (String) -> Route? = { s.route(it) }

    /**
     * Engines restart, networks change, and routes are saved with another server while checks are under way; results come
     * back in any order, some for an engine or network already gone, some blamed on the phone's own network. A route
     * counts as working only when its latest result on the engine and network now, for the server saved now, was an
     * answer; one never checked there, or whose latest check there failed or was blamed on the network, never does.
     */
    @Test fun widgetsCountOnlyAnswersForTheEngineNetworkAndServerNow() {
        repeat(3_000) { seed ->
            val rnd = Random(seed)
            var engine = 1; var net = 50L
            var saved = setup // what the store holds
            var running = setup // what the engine ran when a round started
            var state = VpnState(Phase.On, servers = ids.associateWith { srv }, engine = engine)
            // Each route's latest result per engine, network and the route as checked.
            val latest = HashMap<Triple<Int, Long, String>, Pair<Route, Boolean>>()
            val pending = mutableListOf<Triple<Int, Long, Pair<Setup, Map<String, Boolean>?>>>() // rounds started, not yet back; null = blamed
            repeat(40) {
                when (rnd.nextInt(10)) {
                    0 -> { engine++; running = saved; state = state.copy(engine = engine) } // a reload, a move, a fresh address block
                    1 -> net = if (rnd.nextBoolean()) net + 1 else net // another network, or the same one seen again
                    2, 3 -> pending += Triple(engine, net, running to ids.filter { rnd.nextBoolean() }.associateWith { rnd.nextBoolean() })
                    4 -> pending += Triple(engine, net, running to null)
                    5, 6 -> if (pending.isNotEmpty()) {
                        val (e, n, round) = pending.removeAt(rnd.nextInt(pending.size)) // late and out of order
                        val (s, results) = round
                        val checked = (results?.keys ?: ids.filter { rnd.nextBoolean() }.toSet()).associateWith { listOf(it) }
                        val down = results?.filterValues { !it }?.keys
                        state = state.copy(passed = Health.round(state.passed, e, n, checked, down, ran(s)))
                        checked.keys.forEach { id -> latest[Triple(e, n, id)] = s.route(id)!! to (down != null && id !in down) }
                    }
                    7 -> state = state.copy(passed = null) // a wake: forgotten
                    // A route saved with another server, not yet running (the engine restarts once its server is known).
                    8 -> saved = saved.copy(routes = saved.routes.map { r -> if (rnd.nextInt(3) == 0) r.copy(loc = r.loc.copy(countryId = rnd.nextInt(1, 5))) else r })
                    9 -> saved = saved.copy(routes = saved.routes.map { it.copy(hue = rnd.nextInt(7)) }) // only colours: nothing to check again
                }
                val g = glance(state, saved, net)
                val shown = (listOfNotNull(g.default) + g.others).filter { it.working }.map { it.code.lowercase() }.toSet()
                shown.forEach { id ->
                    val (r, ok) = latest[Triple(engine, net, id)] ?: error("seed $seed: $id working, never checked here")
                    assertTrue("seed $seed: $id", ok)
                    assertEquals("seed $seed: $id checked on another server", saved.route(id)!!.copy(hue = 0), r.copy(hue = 0))
                }
                assertEquals("seed $seed", g.all, 3)
                assertTrue("seed $seed", g.ok <= g.total)
                if (state.passed == null) assertTrue("seed $seed", shown.isEmpty())
            }
        }
    }

    /** In order, every answer on this engine and network counts, and nothing from before a change does. */
    @Test fun answersInOrderCountUntilTheEngineOrNetworkChanges() {
        val on = VpnState(Phase.On, servers = ids.associateWith { srv }, engine = 3)
        fun count(v: VpnState, net: Long?) = glance(v, setup, net).let { Triple(it.ok, it.total, it.unchecked) }
        fun round(old: Health.Passed?, ok: Set<String>, failed: Set<String>, net: Long = 9) = Health.round(old, 3, net, (ok + failed).associateWith { listOf(it) }, failed, ran(setup))
        assertEquals(Triple(0, 0, 3), count(on, 9)) // just connected: no count
        val one = on.copy(passed = round(null, setOf("nl", "us"), emptySet()))
        assertEquals(Triple(2, 2, 1), count(one, 9))
        val two = one.copy(passed = round(one.passed, setOf("it"), setOf("us")))
        assertEquals(Triple(2, 2, 1), count(two, 9)) // us failed once: not known to work, not yet failing
        assertEquals(Triple(0, 0, 3), count(two, 10)) // another network
        assertEquals(Triple(0, 0, 3), count(two, null)) // no network
        assertEquals(Triple(0, 0, 3), count(two.copy(engine = 4), 9)) // another engine start, the same network
        assertEquals(Triple(0, 0, 3), count(two.copy(passed = null), 9)) // a wake
        // A round blamed on the phone's own network: what it checked is no longer known to work.
        assertEquals(Triple(1, 1, 2), count(two.copy(passed = Health.round(two.passed, 3, 9, mapOf("t" to listOf("nl")), null, ran(setup))), 9))
        // No network, or handle 0: a round keeps nothing.
        assertNull(Health.round(two.passed, 3, null, mapOf("t" to listOf("nl")), emptySet(), ran(setup)))
        assertNull(Health.round(two.passed, 3, 0, mapOf("t" to listOf("nl")), emptySet(), ran(setup)))
        // Off, paused or connecting: nothing is known at all.
        for (p in listOf(Phase.Off, Phase.Connecting, Phase.Paused)) assertFalse(glance(two.copy(phase = p), setup, 9).let { g -> (listOfNotNull(g.default) + g.others).any { it.working } })
        // Settings that won't open: apps are offline, whatever was checked.
        assertTrue(glance(two, setup, 9, held = true).let { it.held && it.total == 0 && it.profile == null && it.default == null })
    }

    /** A Tailscale exit works only while Tailscale runs and reports its device online; offline, it fails; unreported, it isn't known. */
    @Test fun aTailscaleExitNeedsItsDeviceOnline() {
        val s = setup.copy(routes = setup.routes + Route("ts", tsExit = "100.64.0.9", tsName = "nas"), apps = mapOf("a" to "ts"))
        val v = VpnState(Phase.On, servers = mapOf("nl" to srv, "ts" to srv), tsState = "Running")
        fun ts(v: VpnState, net: Long? = 9) = glance(v, s, net).others.single { it.code == "TS" }
        assertTrue(ts(v.copy(tsExitNodes = listOf(TsNode("nas", "100.64.0.9", true)))).working)
        assertTrue(ts(v.copy(tsExitNodes = listOf(TsNode("nas", "100.64.0.9", false)))).let { it.failing && !it.working })
        assertTrue(ts(v.copy(tsExitNodes = listOf(TsNode("other", "100.64.0.8", true)))).let { !it.failing && !it.working })
        assertFalse(ts(v.copy(tsExitNodes = listOf(TsNode("nas", "100.64.0.9", true))), null).working) // no network
    }

    /**
     * The widgets in the glue: a test can't reach the health loop, so it checks that the loop hands every round, the
     * ones blamed on the phone's own network too, to [Health.round] before it branches.
     */
    @Test fun theHealthLoopHandsEveryRoundToTheWidgets() {
        val src = File("src/main/java/app/unbraid/vpn/UnbraidVpn.kt").readText()
        val loop = src.substringAfter("private suspend fun watchHealth(").substringBefore("\n    private ")
        assertEquals(1, Regex("""Health\.round\(""").findAll(loop).count())
        assertTrue(loop.indexOf("Health.round(") in 0 until loop.indexOf("if (down == null)"))
        assertFalse(loop.substringAfter("if (down == null)").contains("copy(passed"))
    }
}
