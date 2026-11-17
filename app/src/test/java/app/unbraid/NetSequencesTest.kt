package app.unbraid

import app.unbraid.data.Location
import app.unbraid.data.MOBILE
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.data.WIFI
import app.unbraid.vpn.AutoSwitch
import app.unbraid.vpn.Health
import app.unbraid.vpn.VpnState
import app.unbraid.vpn.VpnState.Phase
import app.unbraid.vpn.glance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Hostile sequences, seeded so a failure can be replayed: what a hand pick and a switch by network do through rule
 * edits, renames, deletes, restarts, network flaps and picks while offline; and what the widgets count when check
 * results arrive for an old engine or network. Each property is stated on its own, not as a copy of the code.
 */
class NetSequencesTest {
    private val kinds = listOf(WIFI, MOBILE)

    /** A phone moving between networks, with the rules and the profiles the user edits, and a [run] that decides. */
    private class World(seed: Int) {
        val rnd = Random(seed)
        var run = AutoSwitch.Run()
        var nextId = 100L
        /** The network the phone is on; null offline. A return after time offline is a new connection: a new id. */
        var here: AutoSwitch.Net? = null
        val names = mutableListOf("Home", "Trip", "Work", "Strict")
        val rules = mutableMapOf(WIFI to "Home", MOBILE to "Trip")

        // What the properties need, kept in their own terms.
        /** A pick holds: made on this network, not left since, its rule not changed since, no restart. */
        var holding = false
        /** Nothing decided on this network since the phone came to it, a restart, or a pick made offline. */
        var fresh = true
        /** The profile this network's rule named when it was last decided or picked on, under its name of the day. */
        var seen: String? = null

        fun join(kind: String) { here = AutoSwitch.Net(kind, nextId++); holding = false; fresh = true }
        fun rule(kind: String, name: String?) { if (name == null) rules.remove(kind) else rules[kind] = name }
        fun rename(old: String, new: String) {
            names[names.indexOf(old)] = new
            rules.replaceAll { _, v -> if (v == old) new else v }
            if (seen == old) seen = new
            run.renamed(old, new)
        }
        fun delete(name: String) { names.remove(name); rules.values.removeAll { it == name } }
        fun pick() {
            run.pick(here, rules)
            val h = here
            if (h == null) { holding = false; fresh = true } else { holding = true; fresh = false; seen = rules[h.kind] }
        }
        fun restart() { run = AutoSwitch.Run(); holding = false; fresh = true }
    }

    /** One decision on the network the phone is on, checked against every property that applies. */
    private fun World.settle(step: String) {
        val h = here ?: return
        val want = rules[h.kind]
        val got = run.settle(h, rules)
        // This network's rule names another profile than when it was last decided or picked on (an edit or a delete, never a rename).
        val changed = !fresh && seen != want
        when {
            // A changed rule for this network applies, over a pick too.
            changed -> assertEquals(step, want, got)
            // A pick holds on the network it was made on, through edits elsewhere, renames, flaps and reconnects of the VPN.
            holding -> assertNull(step, got)
            // A network not decided on yet gets its rule: a move to another Wi-Fi, a return after time offline, a restart.
            fresh -> assertEquals(step, want, got)
            // Decided already: nothing again.
            else -> assertNull(step, got)
        }
        holding = holding && !changed
        fresh = false; seen = want
    }

    @Test fun aHandPickHoldsUntilThePhoneLeavesItsNetwork() {
        repeat(3_000) { seed ->
            val w = World(seed)
            w.join(w.rnd.nextKind())
            val log = StringBuilder("seed $seed:")
            repeat(60) { i ->
                val e = w.rnd.nextInt(14)
                log.append(" $e")
                when (e) {
                    0, 1 -> w.settle("$log")
                    // The VPN off and on again, or a moment offline: the same connection, so the same network.
                    2 -> w.settle("$log")
                    3 -> w.join(w.rnd.nextKind())
                    4 -> { w.here = null } // offline: nothing is decided until a network comes
                    5 -> if (w.here == null) w.join(w.rnd.nextKind())
                    6, 7 -> w.pick()
                    8 -> w.rule(w.rnd.nextKind(), w.names.randomOrNull(w.rnd))
                    9 -> w.rule(w.rnd.nextKind(), null)
                    10 -> w.names.randomOrNull(w.rnd)?.let { w.rename(it, "$it'") }
                    11 -> if (w.names.size > 1) w.delete(w.names.random(w.rnd))
                    12 -> w.restart()
                    13 -> repeat(3) { w.settle("$log") } // a burst of settles: decided once
                }
            }
        }
    }

    /** Renames change no decision: the same sequence with and without them gives the same answers, under the names of the day. */
    @Test fun renamesAreInvisibleToDecisions() {
        repeat(2_000) { seed ->
            val rnd = Random(seed)
            val plain = AutoSwitch.Run(); val renamed = AutoSwitch.Run()
            val rules = mutableMapOf(WIFI to "Home", MOBILE to "Trip")
            val alias = mutableMapOf("Home" to "Home", "Trip" to "Trip", "Work" to "Work") // plain name → current name
            var here = AutoSwitch.Net(WIFI, 1); var id = 2L
            fun shown(r: Map<String, String>) = r.mapValues { alias.getValue(it.value) }
            repeat(50) {
                when (rnd.nextInt(6)) {
                    0, 1 -> assertEquals("seed $seed", plain.settle(here, rules)?.let(alias::getValue), renamed.settle(here, shown(rules)))
                    2 -> { val p = alias.keys.random(rnd); val n = alias.getValue(p) + "x"; renamed.renamed(alias.getValue(p), n); alias[p] = n }
                    3 -> { plain.pick(here, rules); renamed.pick(here, shown(rules)) }
                    4 -> here = AutoSwitch.Net(rnd.nextKind(), id++)
                    5 -> rules[rnd.nextKind()] = alias.keys.random(rnd)
                }
            }
        }
    }

    @Test fun namedHostileCases() {
        val home = AutoSwitch.Net(WIFI, 1)
        val rules = mapOf(WIFI to "Home", MOBILE to "Trip")
        // Picked with the VPN off and no network known: for no network, so the rule applies on connecting.
        AutoSwitch.Run().run { pick(null, rules); assertEquals("Home", settle(home, rules)) }
        // Picked with the VPN off at home (the phone's own network known): connecting at home keeps it.
        AutoSwitch.Run().run { pick(home, rules); assertNull(settle(home, rules)) }
        // ... and the next day on mobile data: the rule applies.
        AutoSwitch.Run().run { pick(home, rules); assertEquals("Trip", settle(AutoSwitch.Net(MOBILE, 9), rules)) }
        // Back home after any time offline is a new connection: the hand pick is over.
        AutoSwitch.Run().run { settle(home, rules); pick(home, rules); assertEquals("Home", settle(AutoSwitch.Net(WIFI, 2), rules)) }
        // A flap between Wi-Fi and mobile data: each network gets its rule; the pick doesn't come back.
        AutoSwitch.Run().run {
            settle(home, rules); pick(home, rules)
            assertEquals("Trip", settle(AutoSwitch.Net(MOBILE, 3), rules))
            assertEquals("Home", settle(AutoSwitch.Net(WIFI, 4), rules))
            assertNull(settle(AutoSwitch.Net(WIFI, 4), rules))
        }
        // The profile in use picked by hand: it holds as any pick does.
        AutoSwitch.Run().run { pick(home, rules); assertNull(settle(home, rules)); assertNull(settle(home, rules + (MOBILE to "Work"))) }
        // The rule's profile renamed, then the old name used again for another profile: still the renamed one.
        AutoSwitch.Run().run {
            settle(home, rules); pick(home, rules)
            renamed("Home", "House")
            assertNull(settle(home, mapOf(WIFI to "House", MOBILE to "Trip")))
            assertEquals("Home", settle(home, mapOf(WIFI to "Home", MOBILE to "Trip"))) // a new rule naming a new "Home"
        }
        // The rule's profile deleted (its rule goes): nothing to switch to, and nothing switches later without a new rule.
        AutoSwitch.Run().run {
            settle(home, rules); pick(home, rules)
            assertNull(settle(home, mapOf(MOBILE to "Trip")))
            assertNull(settle(home, mapOf(MOBILE to "Trip")))
            assertEquals("Work", settle(home, mapOf(WIFI to "Work", MOBILE to "Trip")))
        }
        // A rule edited and edited back before anything was decided: no change, the pick holds.
        AutoSwitch.Run().run { pick(home, rules); assertNull(settle(home, rules)) }
        // A restart forgets the pick: the rule applies again (the page says so).
        AutoSwitch.Run().run { pick(home, rules) }; assertEquals("Home", AutoSwitch.Run().settle(home, rules))
        // A pick counts: a switch decided before it is not made ([AutoSwitch.watch] compares this count).
        AutoSwitch.Run().run { val before = picks; pick(home, rules); assertTrue(picks != before) }
    }

    // ---- widgets ----

    private val srv = Server("s", "h", "1.1.1.1", "k", 1)
    private val setup = Setup(
        routes = listOf(Route("nl", Location("nord", 1, "NL", "Netherlands")), Route("us", Location("nord", 2, "US", "United States")), Route("it", Location("nord", 3, "IT", "Italy"))),
        defaultRoute = "nl", apps = mapOf("a" to "us", "b" to "it"),
    )
    private val ids = listOf("nl", "us", "it")

    /**
     * Engines restart and networks change while checks are under way; results come back in any order, some for an engine
     * or network already gone. A route counts as working only when its latest result on the engine and network now was an
     * answer; one never checked there, or whose latest check there failed, never does.
     */
    @Test fun widgetsCountOnlyAnswersForTheEngineAndNetworkNow() {
        repeat(3_000) { seed ->
            val rnd = Random(seed)
            var engine = 1; var net = 50L
            var state = VpnState(Phase.On, servers = ids.associateWith { srv }, engine = engine)
            val latest = HashMap<Triple<Int, Long, String>, Boolean>() // what each route's latest result was, per engine and network
            val pending = mutableListOf<Triple<Int, Long, Map<String, Boolean>>>() // rounds started, not yet back
            repeat(40) {
                when (rnd.nextInt(8)) {
                    0 -> { engine++; state = state.copy(engine = engine) } // a reload, a move, a fresh address block: whoever starts it
                    1 -> net = if (rnd.nextBoolean()) net + 1 else net // another network, or the same one seen again
                    2, 3 -> pending += Triple(engine, net, ids.filter { rnd.nextBoolean() }.associateWith { rnd.nextBoolean() })
                    4, 5, 6 -> if (pending.isNotEmpty()) {
                        val (e, n, results) = pending.removeAt(rnd.nextInt(pending.size)) // late and out of order
                        state = state.copy(passed = Health.passed(state.passed, e, n, results.filterValues { it }.keys, results.filterValues { !it }.keys))
                        results.forEach { (id, ok) -> latest[Triple(e, n, id)] = ok }
                    }
                    7 -> state = state.copy(passed = null) // a wake: forgotten
                }
                val g = glance(state, setup, net)
                val shown = (listOfNotNull(g.default) + g.others).filter { it.working }.map { it.code.lowercase() }.toSet()
                // Never working without its latest result here being an answer.
                shown.forEach { assertEquals("seed $seed: $it", true, latest[Triple(engine, net, it)]) }
                assertEquals("seed $seed", g.total + g.unchecked, 3)
                assertTrue("seed $seed", g.ok <= g.total)
                if (state.passed == null) assertTrue("seed $seed", shown.isEmpty())
            }
        }
    }

    /** In order, every answer on this engine and network counts, and nothing from before a change does. */
    @Test fun answersInOrderCountUntilTheEngineOrNetworkChanges() {
        val on = VpnState(Phase.On, servers = ids.associateWith { srv }, engine = 3)
        fun count(v: VpnState, net: Long) = glance(v, setup, net).let { Triple(it.ok, it.total, it.unchecked) }
        assertEquals(Triple(0, 0, 3), count(on, 9)) // just connected: no count
        val one = on.copy(passed = Health.passed(null, 3, 9, setOf("nl", "us"), emptySet()))
        assertEquals(Triple(2, 2, 1), count(one, 9))
        val two = one.copy(passed = Health.passed(one.passed, 3, 9, setOf("it"), setOf("us")))
        assertEquals(Triple(2, 2, 1), count(two, 9)) // us failed once: not known to work, not yet failing
        assertEquals(Triple(0, 0, 3), count(two, 10)) // another network
        assertEquals(Triple(0, 0, 3), count(two.copy(engine = 4), 9)) // another engine start, the same network
        assertEquals(Triple(0, 0, 3), count(two.copy(passed = null), 9)) // a wake
        // Off, paused or connecting: nothing is known at all.
        for (p in listOf(Phase.Off, Phase.Connecting, Phase.Paused)) assertFalse(glance(two.copy(phase = p), setup, 9).let { g -> (listOfNotNull(g.default) + g.others).any { it.working } })
        // Settings that won't open: apps are offline, whatever was checked.
        assertTrue(glance(two, setup, 9, held = true).let { it.held && it.total == 0 && it.profile == null && it.default == null })
    }

    private fun Random.nextKind() = if (nextBoolean()) WIFI else MOBILE
}
