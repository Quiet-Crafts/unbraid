package app.unbraid

import app.unbraid.vpn.FakePool
import app.unbraid.vpn.Life
import app.unbraid.vpn.Life.Act
import app.unbraid.vpn.Life.Event
import app.unbraid.vpn.Life.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeTest {
    private fun step(s: State, e: Event) = Life.step(s, e, 8)

    @Test fun aFailedRestartIsTriedOnceMoreAtOnceOnANewBlockThenHeld() {
        val running = State.Running(3, 0, "A")
        val (down, acts) = step(running, Event.Failed(3, "B", 100))
        assertEquals(listOf(Act.Start(4, true)), acts) // nothing published yet: the retry may well start
        val (held, said) = step(down, Event.Failed(4, "B", 100))
        assertEquals(listOf(Act.Down, Act.Refused("B")), said) // B was the user's change: a switch to it is put back
        assertEquals(4, (held as State.Down).block)
        assertEquals(3, held.held)
        val (up, back) = step(held, Event.Started(5, "A", 20_000))
        assertEquals(listOf(Act.Up), back)
        assertEquals(State.Running(5, 20_000, "A"), up)
    }

    @Test fun aRetryWaitsLongerEachTimeAndNeverComesBackToTheBlockAppsHeld() {
        var s: State = State.Down(3, held = 4, ran = "A", tries = 1, last = 0, shown = true)
        assertEquals(emptyList<Act>(), step(s, Event.Tick(14_999, -1)).second)
        assertEquals(listOf(Act.Start(5, true)), step(s, Event.Tick(15_000, -1)).second) // 4 skipped: apps held it
        val again = step(s, Event.Failed(5, "A", 15_000)).first // the 2nd failure in a row waits 30 s
        assertEquals(emptyList<Act>(), step(again, Event.Tick(44_999, -1)).second)
        assertEquals(listOf(Act.Start(6, true)), step(again, Event.Tick(45_000, -1)).second)
        s = State.Down(5, 4, "A", 6, 0, shown = true)
        assertEquals(emptyList<Act>(), step(s, Event.Tick(FakePool.SPACING - 1, -1)).second)
        assertEquals(listOf(Act.Start(6, true)), step(s, Event.Tick(FakePool.SPACING, -1)).second)
    }

    @Test fun aChangeOrANetworkWhileDownStartsSoonButNeverFasterThanTheGap() {
        val s = State.Down(3, 2, "A", 5, last = 1_000, shown = true)
        val (soon, none) = step(s, Event.Changed("B", 2_000))
        assertEquals(emptyList<Act>(), none)
        assertEquals(listOf(Act.Start(4, true)), step(soon, Event.Tick(1_000 + Life.GAP, -1)).second)
        assertEquals(listOf(Act.Start(4, true)), step(s, Event.Network(1_000 + Life.GAP)).second)
        // A restore while down is a change like any other: it never ends the connection.
        assertEquals(listOf(Act.Start(4, true)), step(s, Event.Changed("restored", 1_000 + Life.GAP)).second)
    }

    @Test fun aLowPoolMovesTheRunningEngineAndAnEmptyOneSaysSoOnce() {
        val s = State.Running(3, 0, "A")
        assertEquals(listOf(Act.Start(4, false)), step(s, Event.Tick(FakePool.SPACING, 0)).second)
        val (said, first) = step(s, Event.Tick(1_000, 0))
        assertEquals(listOf(Act.Exhausted), first)
        assertEquals(emptyList<Act>(), step(said, Event.Tick(2_000, 0)).second)
        assertEquals(emptyList<Act>(), step(s, Event.Tick(1_000, FakePool.CAPACITY)).second)
        // A reload on the same block keeps its clock; a new block starts it again.
        assertEquals(0L, (step(s, Event.Started(3, "B", 5_000)).first as State.Running).since)
        assertEquals(5_000L, (step(s, Event.Started(4, "B", 5_000)).first as State.Running).since)
    }

    @Test fun stopAndHoldEndEverything() {
        listOf(State.Running(1, 0, "A"), State.Down(1, 0, "A", 3, 0, shown = true), State.Holding).forEach {
            assertEquals(State.Stopped, step(it, Event.Stop).first)
            assertEquals(emptyList<Act>(), step(State.Stopped, Event.Tick(1_000_000, -1)).second)
        }
        assertEquals(State.Holding, step(State.Stopped, Event.Hold).first)
    }

    /**
     * The service as a model over random events: edits, sign-outs and restores (each a new want), pool checks, network
     * changes, starts that fail on demand. After every event: what runs was built from the want current when it started;
     * after a failed start the next one takes a new table on a block that is neither the failed one nor the one apps held;
     * a block an engine ran on comes back only after the seven others; while none runs at most one start per [Life.GAP]; the outage is published
     * exactly while none runs after the retry; a change that couldn't start is reported; and from any state, starts that
     * succeed reach Running with the current want.
     */
    @Test fun theLifeRulesHoldOverRandomEvents() = repeat(300) { seed ->
        val rnd = java.util.Random(seed.toLong())
        var s: State = State.Running(0, 0, 0)
        var want = 0
        var now = 0L
        var runs: Pair<Int, Int>? = 0 to 0 // block and want of the engine that runs
        var spent = 0
        var held = 0
        var failedBlock: Int? = null
        var shown = false
        val opened = mutableListOf(0 to true) // blocks in the order tables were opened on them, and whether an engine ran there
        var lastStart = Long.MIN_VALUE / 2
        var failRate = rnd.nextInt(4)
        fun run(e: Event) {
            val (next, acts) = step(s, e)
            s = next
            for (a in acts) when (a) {
                is Act.Start -> {
                    if (failedBlock != null) {
                        assertTrue("same table after a failed start", a.fresh)
                        assertTrue("block ${a.block} after a failed start on $failedBlock", a.block != failedBlock && a.block != held)
                    }
                    if (s is State.Down) assertTrue("starts faster than the gap while down", now - lastStart >= Life.GAP || failedBlock != null && lastStart == now)
                    if (!a.fresh) assertTrue("a move onto the running block", s is State.Running && a.block != (s as State.Running).block)
                    if (a.fresh || a.block != runs?.first) opened += a.block to false
                    lastStart = now
                    runs = null
                    spent = a.block
                    if (rnd.nextInt(4) < failRate) { failedBlock = a.block; run(Event.Failed(spent, want, now)) }
                    else { runs = a.block to want; failedBlock = null; opened[opened.size - 1] = a.block to true; run(Event.Started(a.block, want, now)) }
                }
                is Act.Refused -> assertTrue("reported what ran", a.want != (s as State.Down).ran)
                Act.Down -> { assertTrue(runs == null); shown = true }
                Act.Up -> { assertTrue(shown); shown = false }
                Act.Exhausted -> assertTrue(s is State.Running)
            }
        }
        repeat(400) {
            now += rnd.nextInt(20_000)
            if (rnd.nextInt(30) == 0) failRate = rnd.nextInt(5)
            when (rnd.nextInt(5)) {
                0 -> { want++; if (s is State.Down) run(Event.Changed(want, now)) } // running: the service reloads it
                1 -> run(Event.Network(now))
                2 -> {
                    // A reload of the running engine on its own block, as the service's watcher does.
                    val r = s as? State.Running
                    if (r != null && runs != null) {
                        if (rnd.nextInt(4) < failRate) { runs = null; failedBlock = r.block; lastStart = now; run(Event.Failed(r.block, want, now)) }
                        else { runs = r.block to want; run(Event.Started(r.block, want, now)) }
                    }
                }
                else -> run(Event.Tick(now, if (s is State.Running) listOf(0, 1, FakePool.LOW - 1, FakePool.CAPACITY)[rnd.nextInt(4)] else -1))
            }
            runs?.let { (_, w) -> if (s is State.Running && (s as State.Running).ran == w) Unit else error("runs $w, state $s") }
            if (s is State.Running) { held = (s as State.Running).block; assertEquals(runs?.first, held) }
            assertEquals("an engine runs exactly while Running", s is State.Running, runs != null)
            assertEquals("the outage is shown exactly while held after the retry", s is State.Down && (s as State.Down).shown, shown)
            // A block an engine ran on (apps may hold its addresses) opens again only after the seven others.
            opened.windowed(8, partialWindows = true).filter { it[0].second }.forEach { w -> assertTrue("block ${w[0].first} back too soon: $opened", w.drop(1).none { it.first == w[0].first }) }
        }
        // Never stuck: once starts succeed, Running with what the user has now.
        failRate = 0
        want++
        run(Event.Changed(want, now))
        repeat(60) { now += FakePool.EVERY; run(Event.Tick(now, if (s is State.Running) FakePool.CAPACITY else -1)) }
        if (s is State.Running && (s as State.Running).ran != want) run(Event.Started((s as State.Running).block, want, now)) // the watcher's reload
        assertEquals(want, (s as State.Running).ran)
    }
}
