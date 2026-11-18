package app.unbraid

import app.unbraid.data.MOBILE
import app.unbraid.data.WIFI
import app.unbraid.vpn.AutoSwitch
import app.unbraid.vpn.AutoSwitch.Net
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** For [NetSequencesTest]'s interleavings: no handover back to a network that stayed connected. */
internal const val RETURNS = -2

/** Network [id] before Android reported its kind. */
private fun pending(id: Long) = Net(null, id)

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
        /** The user sets, changes or removes the rule for [kind]: on the network the phone is on, what was acted on is forgotten. */
        fun rule(kind: String, name: String?) {
            if (name == null) rules.remove(kind) else rules[kind] = name
            run.ruleEdited(kind)
            if (here?.kind == kind) { holding = false; fresh = true }
        }
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
        /** Joined a network whose kind Android reports a moment later; [pickFirst]: a pick in between. */
        fun joinLate(kind: String, pickFirst: Boolean) {
            val n = Net(kind, nextId++)
            here = n; if (on) run.seen(pending(n.id)); holding = false; fresh = true
            if (pickFirst) pick()
            if (on) run.seen(n)
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
                val e = w.rnd.nextInt(16)
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
                    15 -> w.joinLate(w.rnd.nextKind(), w.rnd.nextBoolean())
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
        // A settle for a network already left decides nothing, not even for the network the phone is on now (it hasn't lasted yet).
        AutoSwitch.Run().run { seen(home); seen(cell); assertNull(settle(home, rules + (MOBILE to "Trip"))); assertEquals("Trip", decide(cell, rules + (MOBILE to "Trip"))) }
        // A move to another Wi-Fi during the write: not saved, though that Wi-Fi's rule names the same profile; it decides
        // for itself once it lasted.
        AutoSwitch.Run().run { seen(home); val d = settle(home, rules)!!; val next = Net(WIFI, 5); seen(next); assertFalse(commit(d, rules, false)); assertEquals("Home", decide(next, rules)) }
        // Wi-Fi lost just before its settle (airplane mode): the engine still waits for it, the switch by network doesn't.
        AutoSwitch.Run().run { seen(home); lost(home.id); assertNull(state.here); assertNull(settle(home, rules)) }
        // ... and lost during the write: not saved.
        AutoSwitch.Run().run { seen(home); val d = settle(home, rules)!!; lost(home.id); assertFalse(commit(d, rules, false)) }
        // Mobile A, Wi-Fi B, back to A, which stayed connected (the same handle): A's decision is void, A decides again.
        AutoSwitch.Run().run {
            val r = rules + (MOBILE to "Trip")
            seen(cell); val d = settle(cell, r)!!; seen(home); seen(cell)
            assertFalse(commit(d, r, false))
            assertEquals("Trip", decide(cell, r))
        }
        // A network whose kind isn't reported yet: a pick on it holds once its kind comes; no rule applies before.
        AutoSwitch.Run().run {
            val r = rules + (MOBILE to "Trip")
            seen(home); decide(home, r); seen(Net(null, 8)); assertNull(settle(Net(null, 8), r)); pick(r)
            seen(Net(MOBILE, 8)); assertNull(decide(Net(MOBILE, 8), r))
        }
        // The rule's profile renamed while its switch waits to be saved: the save fails, and the renamed rule decides
        // again (the switch is not used up).
        AutoSwitch.Run().run {
            seen(home); val d = settle(home, rules)!!; renamed("Home", "House")
            val house = mapOf(WIFI to "House")
            assertFalse(commit(d, house, false))
            assertEquals("House", decide(home, house))
        }
        // ... and another profile made under the old name, with the rule set to it, before the save: the decision was for
        // the renamed profile, so it doesn't switch to the new one.
        AutoSwitch.Run().run { seen(home); val d = settle(home, rules)!!; renamed("Home", "House"); assertFalse(commit(d, rules, false)) }
        // A settle timer started in a stay that ended: the phone left the network and came back to the same handle (the
        // monitor's flow conflates the two), so the old timer must not decide for the new stay, which has not lasted yet.
        AutoSwitch.Run().run {
            seen(home); val old = state.stay
            seen(cell); seen(home)
            assertNull(settle(home, rules, old)); assertEquals("Home", decide(home, rules))
        }
        AutoSwitch.Run().run { seen(home); val old = state.stay; lost(home.id); seen(home); assertNull(settle(home, rules, old)); assertNotNull(settle(home, rules)) }
        AutoSwitch.Run().run { seen(home); val old = state.stay; unwatched(); seen(home); assertNull(settle(home, rules, old)) }
        // A profile deleted and another made under its name: the old decision, pending or picked, is not the new one's.
        AutoSwitch.Run().run { seen(home); val d = settle(home, rules)!!; deleted("Home"); assertFalse(commit(d, rules, false)); assertEquals("Home", decide(home, rules)) }
        AutoSwitch.Run().run { seen(home); pick(rules + (WIFI to "Work")); deleted("Work"); assertEquals("Home", decide(home, rules)) }
        AutoSwitch.Run().run { seen(home); pick(rules); deleted("Home"); assertNull(decide(home, emptyMap())); assertEquals("Home", decide(home, rules)) }
        // A decision made again for the same network and profile is another one: the older save is not it.
        AutoSwitch.Run().run { seen(home); val d = settle(home, rules)!!; seen(cell); seen(home); val again = settle(home, rules)!!; assertFalse(commit(d, rules, false)); assertTrue(commit(again, rules, false)) }
    }

    /** The seeds that found the round-two findings before the model was fixed, each with the events that make it shortest. */
    @Test fun theSeedsThatFoundTheFindings() {
        interleave(8, setOf(5, 8, 9, 11, RETURNS)) // a decision made on a network already lost
        interleave(82, setOf(5, 6, 7, 8, 9, 11)) // a decision saved after the phone left and came back
        interleave(12, setOf(5, 6, 7, 11, RETURNS)) // a pick made before the network's kind was reported
        interleave(17, setOf(5, 6, 7, 8, 9, RETURNS)) // a switch used up by a rename before its save
        interleave(68) // a settle timer from before the VPN went off and on decides for the new stay
        interleave(361) // a rule removed and set again on the same network: decided afresh, not held as acted on
        interleave(428) // a profile deleted and another made under its name, which the old decision then applies to
    }

    /**
     * Decisions saved late and out of order, with every event the glue can deliver in between: handovers, a return to a
     * network that stayed connected (Android keeps its handle), a loss the engine still waits out, a network whose kind
     * isn't reported yet, callbacks that repeat, picks, rule edits, renames, the VPN off and on, the app on screen. The
     * oracle is the rule stated on its own: a decision is saved only while it is the latest for the same stay on its
     * network (not left since, even for a moment), no pick or rename of its profile came since, the rule still names it
     * and the app is not on screen; a decision not saved is decided again, never used up.
     */
    @Test fun randomInterleavingsOfDecisionsAndSaves() {
        repeat(5_000) { seed -> interleave(seed) }
    }

    /** One seeded sequence; events in [skip] are left out ([RETURNS]: handovers only to networks not seen before). */
    private fun interleave(seed: Int, skip: Set<Int> = emptySet()) {
        val rnd = Random(seed)
        val run = AutoSwitch.Run()
        fun kindOf(id: Long) = if (id % 2 == 0L) MOBILE else WIFI
        var nextId = 10L
        var here: Net? = Net(WIFI, 1) // the network the phone is on, as it is
        var known = true // its kind reported yet
        var on = true; var shown = false; var grace = false
        var told: Long? = 1L // the network the model was last told of
        var stay = 0 // a new stay each time the model is told of another network, or of none
        var visit = 0 // a new visit as well each time the VPN comes on again: what a settle timer is armed for
        val names = mutableListOf("Home", "Trip", "Work")
        val rules = mutableMapOf(WIFI to "Home", MOBILE to "Trip")
        var done: Pair<Int, Map<String, String>>? = null // saved, picked, or found without a rule on a stay, under the rules then
        var timer: Triple<Net, Int, Int>? = null // a settle timer armed on a network: the model's stay and the oracle's
        val live = mutableListOf<Pair<AutoSwitch.Decision, Int>>() // decisions the model may still save, with their stay
        val void = mutableListOf<AutoSwitch.Decision>() // decisions that must never be saved
        fun voidAll() { void += live.map { it.first }; live.clear() }
        fun tell() { val id = here?.id?.takeIf { it != 0L }; if (id != told) { stay++; visit++; voidAll(); told = id } }
        fun seenAs() = here?.let { if (known) it else pending(it.id) }
        /** The user sets, changes or removes the rule for [kind]: what was acted on for that kind is forgotten, whatever it is set to. */
        fun edit(kind: String, name: String?) {
            if (name == null) rules.remove(kind) else rules[kind] = name
            done = done?.let { (st, r) -> st to (r - kind) }
            run.ruleEdited(kind)
        }
        run.seen(here)
        val log = StringBuilder("seed $seed:")
        repeat(80) {
            val e = rnd.nextInt(17).takeIf { it !in skip } ?: return@repeat
            log.append(" $e")
            when (e) {
                // The glue settles on the network the model publishes.
                // Its timer may have started in an earlier stay on the same handle (the monitor's flow conflates a departure and return).
                0, 1 -> (timer?.takeIf { rnd.nextBoolean() } ?: run.state.here?.let { Triple(it, run.state.stay, visit) })?.let { (n, modelStay, ownVisit) ->
                    timer = null
                    val truth = here?.takeIf { on && known && it.id != 0L && it.id == n.id && ownVisit == visit }
                    val want = truth?.let { rules[it.kind] }
                    val expect = when {
                        truth == null -> null
                        done?.first == stay && done!!.second[truth.kind] == want -> null
                        want == null -> { done = stay to rules.toMap(); null }
                        live.any { it.second == stay && it.first.profile == want } -> null
                        else -> want
                    }
                    val d = run.settle(n, rules, modelStay)
                    assertEquals("$log", expect, d?.profile)
                    if (d != null) { assertEquals("$log", truth!!.id, d.net); voidAll(); live += d to stay }
                }
                // A handover: to another network, to one that stayed connected, or to handle 0.
                2 -> {
                    val id = when (rnd.nextInt(6)) { 0 -> 0L; 1, 2 -> if (RETURNS in skip) nextId++ else rnd.nextLong(1, 3); else -> nextId++ }
                    here = Net(kindOf(id), id); known = true; grace = false
                    if (on) { tell(); run.seen(here) }
                }
                // With the VPN off, a pick is for the phone's own network as Android reports it: one of a kind not reported is none.
                3 -> {
                    run.pick(rules, here?.takeIf { known })
                    if (!on) tell()
                    voidAll()
                    done = here?.takeIf { it.id != 0L && (on || known) }?.let { stay to rules.toMap() }
                }
                4 -> edit(rnd.nextKind(), if (rnd.nextBoolean()) names.random(rnd) else null)
                5 -> if (on) { on = false; grace = false; run.unwatched(); voidAll() } else { on = true; visit++; tell(); run.seen(seenAs()) }
                // The network lost: the engine waits a moment for it to come back; the switch by network must not.
                6 -> here?.takeIf { on }?.let { n -> here = null; grace = true; tell(); run.lost(n.id) }
                7 -> if (grace) { grace = false; if (on && here == null) run.seen(null) }
                // A network whose kind isn't reported yet, and its kind arriving.
                8 -> { val id = nextId++; here = Net(kindOf(id), id); known = false; grace = false; if (on) { tell(); run.seen(seenAs()) } }
                9 -> if (!known) { known = true; if (on) run.seen(here) }
                10 -> if (on && known) run.seen(here) // a callback that changes nothing
                11 -> {
                    val old = names.random(rnd); val new = "$old'".takeIf { it !in names } ?: return@repeat
                    names[names.indexOf(old)] = new
                    rules.replaceAll { _, v -> if (v == old) new else v }
                    done = done?.let { (st, r) -> st to r.mapValues { (_, v) -> if (v == old) new else v } }
                    live.filter { it.first.profile == old }.forEach { live.remove(it); void += it.first }
                    run.renamed(old, new)
                    // Sometimes another profile is made under the old name, and a rule may name it: a decision for the
                    // renamed one is not for it.
                    if (rnd.nextBoolean()) { names += old; if (rnd.nextBoolean()) edit(rnd.nextKind(), old) }
                }
                12 -> shown = !shown
                15 -> run.state.here?.takeIf { it.kind != null }?.let { timer = Triple(it, run.state.stay, visit) }
                // A profile deleted, and sometimes another made under its name with a rule pointing at it: not the one decided on.
                16 -> if (names.size > 1) {
                    val old = names.random(rnd)
                    names.remove(old); rules.values.removeAll { it == old }
                    done = done?.let { (st, r) -> st to r.filterValues { it != old } }
                    live.filter { it.first.profile == old }.forEach { live.remove(it); void += it.first }
                    run.deleted(old)
                    if (rnd.nextBoolean()) { names += old; if (rnd.nextBoolean()) edit(rnd.nextKind(), old) }
                }
                13, 14 -> if (live.isNotEmpty() || void.isNotEmpty()) {
                    val i = rnd.nextInt(live.size + void.size)
                    val (d, st) = if (i < live.size) live.removeAt(i) else void.removeAt(i - live.size) to -1
                    val n = here
                    val holds = st == stay && on && known && n != null && n.id == d.net && rules[n.kind] == d.profile && !shown
                    assertEquals("$log", holds, run.commit(d, rules, shown))
                    if (holds) done = stay to rules.toMap()
                }
            }
        }
    }

    /**
     * The model in the glue: a test can't reach Android's network callbacks, so it checks that the monitor tells the model
     * of a new network in the callback that moves the monitor itself, of a loss, and of stopping; and that the save is
     * checked inside the store's own update.
     */
    @Test fun theGlueFeedsTheModelWhereItActs() {
        val net = java.io.File("src/main/java/app/unbraid/vpn/Net.kt").readText()
        assertTrue(Regex("""override fun onAvailable\(n: Network\) \{[^\n]*network = n;[^\n]*AutoSwitch\.seen\(""").containsMatchIn(net))
        assertTrue(Regex("""private val lose = Runnable \{[^\n]*AutoSwitch\.seen\(null\)""").containsMatchIn(net))
        // A loss reaches the model at once, before the engine's wait for the network to come back.
        assertTrue(Regex("""override fun onLost\(n: Network\) \{ if \(n == network\) \{[^\n]*AutoSwitch\.lost\(n\.networkHandle\);[^\n]*postDelayed\(lose""").containsMatchIn(net))
        // A network without its capabilities yet is seen as itself, of a kind not known yet, not as no network.
        assertTrue(net.contains("AutoSwitch.seen(net(n, cm.getNetworkCapabilities(n)))"))
        assertTrue(Regex("""fun stop\(\) \{[^\n]*AutoSwitch\.unwatched\(\)""").containsMatchIn(net))
        assertFalse(net.contains("AutoSwitch.net.value ="))
        val watch = java.io.File("src/main/java/app/unbraid/vpn/AutoSwitch.kt").readText().substringAfter("fun watch(")
        assertTrue(Regex("""store\.update \{ if \(run\.commit\(d, it\.onNet, Unbraid\.foreground\.value\)\) it\.use\(to, PREVIOUS\) else it \}""").containsMatchIn(watch))
        // A rule edit reaches the model, and the card is re-judged when the setup or the accounts change.
        val ui = java.io.File("src/main/java/app/unbraid/ui/Ui.kt").readText()
        assertTrue(Regex("""fun setOnNet\([^\n]*store\.edit \{[^\n]*\}; app\.unbraid\.vpn\.AutoSwitch\.ruleEdited\(kind\)""").containsMatchIn(ui))
        assertTrue(Regex("""combine\(notSwitched, store\.setup, store\.accounts, model\)[^\n]*\.current\(s, m, acc\)""").containsMatchIn(watch))
    }

    private fun Random.nextKind() = if (nextBoolean()) WIFI else MOBILE
}
