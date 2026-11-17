package app.unbraid

import app.unbraid.data.MOBILE
import app.unbraid.data.WIFI
import app.unbraid.vpn.AutoSwitch
import app.unbraid.vpn.AutoSwitch.Net
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Hostile sequences, seeded so a failure can be replayed: what a hand pick and a switch by network do through rule
 * edits, renames, deletes, restarts, network flaps, picks while offline, and events that come between a decision and
 * its save. Each property is stated on its own, not as a copy of the code. The widget counts are in [WidgetSequencesTest].
 */
class NetSequencesTest {
    /** A phone moving between networks, with the rules and the profiles the user edits, and a [run] that decides. */
    private class World(seed: Int) {
        val rnd = Random(seed)
        var run = AutoSwitch.Run()
        var nextId = 100L
        /** The network the phone is on; null offline. A return after time offline is a new connection: a new id. */
        var here: Net? = null
        /** The VPN is on, so the network monitor runs. */
        var on = true
        val names = mutableListOf("Home", "Trip", "Work", "Strict")
        val rules = mutableMapOf(WIFI to "Home", MOBILE to "Trip")

        // What the properties need, kept in their own terms.
        /** A pick holds: made on this network, not left since, its rule not changed since, no restart. */
        var holding = false
        /** Nothing decided on this network since the phone came to it, a restart, or a pick made offline. */
        var fresh = true
        /** The profile this network's rule named when it was last decided or picked on, under its name of the day. */
        var seen: String? = null

        init { run.seen(null) }
        fun join(kind: String) { here = Net(kind, nextId++); if (on) run.seen(here); holding = false; fresh = true }
        fun offline() { here = null; if (on) run.seen(null); holding = false; fresh = true }
        fun vpn(up: Boolean) { on = up; if (up) run.seen(here) else run.unwatched() }
        fun rule(kind: String, name: String?) { if (name == null) rules.remove(kind) else rules[kind] = name }
        fun rename(old: String, new: String) {
            names[names.indexOf(old)] = new
            rules.replaceAll { _, v -> if (v == old) new else v }
            if (seen == old) seen = new
            run.renamed(old, new)
        }
        fun delete(name: String) { names.remove(name); rules.values.removeAll { it == name } }
        fun pick() {
            run.pick(rules, here)
            val h = here
            if (h == null) { holding = false; fresh = true } else { holding = true; fresh = false; seen = rules[h.kind] }
        }
        fun restart() { run = AutoSwitch.Run(); if (on) run.seen(here); holding = false; fresh = true }
    }

    /** A settle and its save, with nothing in between: the profile switched to, or null. */
    private fun AutoSwitch.Run.decide(n: Net, rules: Map<String, String>) = settle(n, rules)?.takeIf { commit(it, rules, shown = false) }?.profile

    /** One decision on the network the phone is on, checked against every property that applies. */
    private fun World.settle(step: String) {
        val h = here ?: return
        if (!on) return // nothing settles while the VPN is off
        val want = rules[h.kind]
        val got = run.decide(h, rules)
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
                val e = w.rnd.nextInt(15)
                log.append(" $e")
                when (e) {
                    0, 1 -> w.settle("$log")
                    // The VPN off and on again on the same connection: the same network.
                    2 -> { w.vpn(false); w.vpn(true); w.settle("$log") }
                    3 -> w.join(w.rnd.nextKind())
                    4 -> w.offline() // nothing is decided until a network comes
                    5 -> if (w.here == null) w.join(w.rnd.nextKind())
                    6, 7 -> w.pick()
                    8 -> w.rule(w.rnd.nextKind(), w.names.randomOrNull(w.rnd))
                    9 -> w.rule(w.rnd.nextKind(), null)
                    10 -> w.names.randomOrNull(w.rnd)?.let { w.rename(it, "$it'") }
                    11 -> if (w.names.size > 1) w.delete(w.names.random(w.rnd))
                    12 -> w.restart()
                    13 -> repeat(3) { w.settle("$log") } // a burst of settles: decided once
                    // The VPN off: picks then belong to the phone's own network; on again, on this or another network.
                    14 -> { w.vpn(false); if (w.rnd.nextBoolean()) w.pick(); if (w.rnd.nextBoolean()) w.join(w.rnd.nextKind()); w.vpn(true) }
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
            var here = Net(WIFI, 1); var id = 2L
            plain.seen(here); renamed.seen(here)
            fun shown(r: Map<String, String>) = r.mapValues { alias.getValue(it.value) }
            repeat(50) {
                when (rnd.nextInt(6)) {
                    0, 1 -> assertEquals("seed $seed", plain.decide(here, rules)?.let(alias::getValue), renamed.decide(here, shown(rules)))
                    2 -> { val p = alias.keys.random(rnd); val n = alias.getValue(p) + "x"; renamed.renamed(alias.getValue(p), n); alias[p] = n }
                    3 -> { plain.pick(rules); renamed.pick(shown(rules)) }
                    4 -> { here = Net(rnd.nextKind(), id++); plain.seen(here); renamed.seen(here) }
                    5 -> rules[rnd.nextKind()] = alias.keys.random(rnd)
                }
            }
        }
    }

    @Test fun namedHostileCases() {
        val home = Net(WIFI, 1)
        val rules = mapOf(WIFI to "Home", MOBILE to "Trip")
        fun run(vararg seen: Net?) = AutoSwitch.Run().apply { seen.forEach { seen(it) } }
        // Picked with the VPN off and no network known: for no network, so the rule applies on connecting.
        run().run { pick(rules, null); seen(home); assertEquals("Home", decide(home, rules)) }
        // Picked with the VPN off at home (the phone's own network known): connecting at home keeps it.
        run().run { pick(rules, home); seen(home); assertNull(decide(home, rules)) }
        // ... and the next day on mobile data: the rule applies.
        run().run { pick(rules, home); val m = Net(MOBILE, 9); seen(m); assertEquals("Trip", decide(m, rules)) }
        // Back home after any time offline is a new connection: the hand pick is over.
        run(home).run { decide(home, rules); pick(rules); val again = Net(WIFI, 2); seen(null); seen(again); assertEquals("Home", decide(again, rules)) }
        // A flap between Wi-Fi and mobile data: each network gets its rule; the pick doesn't come back.
        run(home).run {
            decide(home, rules); pick(rules)
            Net(MOBILE, 3).let { seen(it); assertEquals("Trip", decide(it, rules)) }
            Net(WIFI, 4).let { seen(it); assertEquals("Home", decide(it, rules)); assertNull(decide(it, rules)) }
        }
        // The profile in use picked by hand: it holds as any pick does.
        run(home).run { pick(rules); assertNull(decide(home, rules)); assertNull(decide(home, rules + (MOBILE to "Work"))) }
        // The rule's profile renamed, then the old name used again for another profile: still the renamed one.
        run(home).run {
            decide(home, rules); pick(rules)
            renamed("Home", "House")
            assertNull(decide(home, mapOf(WIFI to "House", MOBILE to "Trip")))
            assertEquals("Home", decide(home, mapOf(WIFI to "Home", MOBILE to "Trip"))) // a new rule naming a new "Home"
        }
        // The rule's profile deleted (its rule goes): nothing to switch to, and nothing switches later without a new rule.
        run(home).run {
            decide(home, rules); pick(rules)
            assertNull(decide(home, mapOf(MOBILE to "Trip")))
            assertNull(decide(home, mapOf(MOBILE to "Trip")))
            assertEquals("Work", decide(home, mapOf(WIFI to "Work", MOBILE to "Trip")))
        }
        // A restart forgets the pick: the rule applies again (the page says so).
        run(home).run { pick(rules) }; assertEquals("Home", run(home).decide(home, rules))
    }

    /**
     * What comes between a decision and its save. The save goes ahead only if, at that moment, the VPN is on, the phone is
     * still on the network decided on, no pick came since, that network's rule still names the profile and the app is
     * not on screen. Every interleaving the glue allows: callbacks during the write, handovers, picks in between, handle
     * reuse, handle 0, the VPN off.
     */
    @Test fun aSaveHappensOnlyWhileItsDecisionStillHolds() {
        val home = Net(WIFI, 1); val cell = Net(MOBILE, 2)
        val rules = mapOf(WIFI to "Home")
        // A network callback during the write: Wi-Fi settles on Home, the phone moves to mobile data (no rule) before
        // the save. Nothing is saved, and mobile data then decides nothing: Home never lands on mobile data.
        AutoSwitch.Run().run {
            seen(home); val d = settle(home, rules)!!
            seen(cell)
            assertFalse(commit(d, rules, false))
            assertNull(settle(cell, rules))
        }
        // A handover A to B with a tile pick in between: the pick belongs to B, the network the monitor moved to.
        AutoSwitch.Run().run { seen(home); decide(home, rules + (MOBILE to "Trip")); seen(cell); pick(rules + (MOBILE to "Trip")); assertNull(decide(cell, rules + (MOBILE to "Trip"))) }
        // ... and a pick just before the handover was made on A: B's rule applies.
        AutoSwitch.Run().run { seen(home); decide(home, rules + (MOBILE to "Trip")); pick(rules + (MOBILE to "Trip")); seen(cell); assertEquals("Trip", decide(cell, rules + (MOBILE to "Trip"))) }
        // Picked on mobile data; with the app open (nothing settles), Wi-Fi comes and goes; back on the same mobile
        // connection, the pick is over: leaving ends it, whether or not anything settled there.
        AutoSwitch.Run().run {
            val r = mapOf(WIFI to "Home", MOBILE to "Trip")
            seen(cell); decide(cell, r); pick(r)
            seen(home); seen(cell)
            assertEquals("Trip", decide(cell, r))
        }
        // A handle used again for another network later: the old pick doesn't come back with it.
        AutoSwitch.Run().run { seen(home); decide(home, rules); pick(rules); seen(cell); seen(Net(WIFI, 1)); assertEquals("Home", decide(Net(WIFI, 1), rules)) }
        // Handle 0 names no network: no pick belongs to it, and nothing is decided on it.
        AutoSwitch.Run().run { seen(Net(WIFI, 0)); assertNull(state.here); pick(rules); assertNull(state.acted); assertNull(settle(Net(WIFI, 0), rules)) }
        // The VPN goes off during the write: nothing is saved; on again on the same network, the rule applies then.
        AutoSwitch.Run().run { seen(home); val d = settle(home, rules)!!; unwatched(); assertFalse(commit(d, rules, false)); seen(home); assertEquals("Home", decide(home, rules)) }
        // The app comes on screen during the write: not saved; once it is off screen the same switch is decided again.
        AutoSwitch.Run().run { seen(home); val d = settle(home, rules)!!; assertFalse(commit(d, rules, true)); assertEquals("Home", decide(home, rules)) }
        // A pick during the write wins, and keeps holding.
        AutoSwitch.Run().run { seen(home); val d = settle(home, rules)!!; pick(rules); assertFalse(commit(d, rules, false)); assertNull(decide(home, rules)) }
        // The rule edited during the write: not saved; the new rule is decided next.
        AutoSwitch.Run().run { seen(home); val d = settle(home, rules)!!; assertFalse(commit(d, mapOf(WIFI to "Work"), false)); assertEquals("Work", decide(home, mapOf(WIFI to "Work"))) }
        // A settle for a network already left decides nothing.
        AutoSwitch.Run().run { seen(home); seen(cell); assertNull(settle(home, rules)) }
    }

    /** Decisions saved late and out of order, with events of every kind in between, checked against the rule above. */
    @Test fun randomInterleavingsOfDecisionsAndSaves() {
        repeat(3_000) { seed ->
            val rnd = Random(seed)
            val run = AutoSwitch.Run()
            var here: Net? = Net(WIFI, 1); var on = true; var shown = false; var picks = 0
            val ids = listOf(0L, 1L, 2L, 3L) // few handles, so they come back; 0 among them
            val rules = mutableMapOf(WIFI to "Home", MOBILE to "Trip")
            run.seen(here)
            val pending = mutableListOf<Pair<AutoSwitch.Decision, Int>>() // a decision and the picks made before it
            val log = StringBuilder("seed $seed:")
            repeat(60) {
                val e = rnd.nextInt(9)
                log.append(" $e")
                when (e) {
                    0, 1 -> here?.takeIf { on }?.let { n -> run.settle(n, rules)?.let { d ->
                        assertEquals("$log", n.id, d.net); assertEquals("$log", rules[n.kind], d.profile)
                        pending += d to picks
                    } }
                    2 -> { here = if (rnd.nextInt(4) == 0) null else Net(rnd.nextKind(), ids.random(rnd)); if (on) run.seen(here) }
                    3 -> { run.pick(rules, here); picks++ }
                    4 -> rules[rnd.nextKind()] = listOf("Home", "Trip", "Work").random(rnd)
                    5 -> { on = !on; if (on) run.seen(here) else run.unwatched() }
                    6 -> shown = !shown
                    7, 8 -> if (pending.isNotEmpty()) {
                        val (d, before) = pending.removeAt(rnd.nextInt(pending.size))
                        val n = here
                        val holds = on && n != null && n.id != 0L && n.id == d.net && before == picks && rules[n.kind] == d.profile && !shown
                        assertEquals("$log", holds, run.commit(d, rules, shown))
                    }
                }
            }
        }
    }

    private fun Random.nextKind() = if (nextBoolean()) WIFI else MOBILE
}
