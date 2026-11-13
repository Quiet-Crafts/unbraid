package app.unbraid

import app.unbraid.vpn.Health
import app.unbraid.data.Route
import app.unbraid.data.WgPeer
import app.unbraid.data.OvpnProfile
import app.unbraid.data.SsProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTest {
    private fun blame(down: Set<String>, checked: Set<String>, direct: Boolean): Set<String>? {
        var asked = false
        return runBlocking { Health.blame(down, checked) { asked = true; direct } }.also {
            // The phone's own network is only checked when every tunnel failed.
            assertEquals(down.isNotEmpty() && down == checked, asked)
        }
    }

    @Test fun everyTunnelDownWithTheNetworkDownBlamesNoServer() {
        assertNull(blame(setOf("a"), setOf("a"), direct = false))
        assertNull(blame(setOf("a", "b"), setOf("a", "b"), direct = false))
    }

    @Test fun everyTunnelDownWhileTheNetworkWorksBlamesThem() = assertEquals(setOf("a"), blame(setOf("a"), setOf("a"), direct = true))

    @Test fun somePartialOrNoFailuresNeedNoNetworkCheck() {
        assertEquals(setOf("a"), blame(setOf("a"), setOf("a", "b"), direct = false))
        assertEquals(emptySet<String>(), blame(emptySet(), setOf("a"), direct = false))
    }

    /** On and off counts once three of the last six checks failed, and clears after three answers in a row. */
    @Test fun keepsDropping() {
        fun h(vararg ok: Boolean) = ok.fold(emptyList<Boolean>()) { acc, b -> Health.record(acc, b) }
        assertEquals(false, Health.dropping(h(true, false, true, false, true)))
        assertEquals(true, Health.dropping(h(true, false, true, false, true, false)))
        assertEquals(true, Health.dropping(h(false, true, false, true, false)))
        assertEquals(false, Health.dropping(h(false, true, false, true, false, true, true, true)))
        assertEquals(true, Health.dropping(h(false, false, false))) // dead too: moved by the other rule, flagged meanwhile
        assertEquals(6, h(true, true, true, true, true, true, true, false).size)
    }

    /** A failing tunnel: checked again after 10 and 20 s, then a minute after its first failure; dead only then. */
    @Test fun aFailingTunnelGetsAMinuteOnItsOwnServer() {
        var s = Health.Streak()
        assertNull(Health.due(s))
        s = Health.next(s, ok = false, now = 1_000)
        assertEquals(11_000L, Health.due(s))
        s = Health.next(s, ok = false, now = 11_000)
        assertEquals(21_000L, Health.due(s))
        s = Health.next(s, ok = false, now = 21_000)
        assertEquals(61_000L, Health.due(s))
        assertFalse(Health.dead(s, 61_000))
        s = Health.next(s, ok = false, now = 61_000)
        assertTrue(Health.dead(s, 61_000))
        // One answer ends the streak.
        assertEquals(Health.Streak(), Health.next(s, ok = true, now = 62_000))
    }

    /** A regular round before the minute is up counts, but doesn't end the minute early. */
    @Test fun aRegularRoundDoesntShortenTheMinute() {
        var s = Health.Streak()
        listOf(0L, 10_000L, 20_000L, 48_000L).forEach { s = Health.next(s, ok = false, now = it) }
        assertFalse(Health.dead(s, 48_000))
        assertEquals(60_000L, Health.due(s))
        s = Health.next(s, ok = false, now = 60_000)
        assertTrue(Health.dead(s, 60_000))
        // One just before the minute doesn't push the deadline back.
        var late = Health.Streak()
        listOf(0L, 10_000L, 20_000L, 58_000L).forEach { late = Health.next(late, ok = false, now = it) }
        assertEquals(60_000L, Health.due(late))
    }

    /** A route handled moments ago waits for its cooldown, and isn't checked on its own before then. */
    @Test fun aHeldRouteWaitsForItsCooldown() {
        var s = Health.Streak()
        listOf(0L, 10_000L, 20_000L, 60_000L).forEach { s = Health.next(s, ok = false, now = it) }
        val hold = 60_000L + Health.COOLDOWN
        assertFalse(Health.dead(s, 70_000, hold))
        assertEquals(hold, Health.due(s, hold))
        assertTrue(Health.dead(Health.next(s, ok = false, now = hold), hold, hold))
        assertEquals(hold, Health.due(Health.next(Health.Streak(), ok = false, now = 0), hold)) // quick re-checks wait too
    }
    /**
     * A route whose new servers never work: moved at once, then after waits of 2, 5, 15 and 30 minutes, then left alone
     * as not working on this network. Three answers in a row end the run, so a later failure is moved at once again.
     */
    @Test fun aRouteThatNeverWorksBacksOffThenStops() {
        val min = 60_000L
        var clock = 0L
        var m = Health.Moves()
        var s = Health.Streak()
        val movedAt = mutableListOf<Long>()
        // Checked every 10 s: dead after a minute of failures, once its hold has passed.
        while (clock < 6 * 60 * min) {
            s = Health.next(s, ok = false, now = clock)
            if (Health.dead(s, clock, m.hold)) {
                m = Health.moved(m, clock)
                if (!Health.doesntWork(m)) movedAt += clock
                s = Health.Streak() // the engine restart starts its checks again
            }
            clock += 10_000
        }
        // The first move after a minute of failures, then never sooner than each wait after the one before.
        assertTrue(m.count >= 6)
        assertTrue(Health.doesntWork(m))
        assertTrue(m.hold < 7 * 60 * min) // still handled now and then, to check for a removed device
        assertEquals(5, movedAt.size)
        listOf(2 * min, 5 * min, 15 * min, 30 * min).forEachIndexed { i, wait -> assertTrue(movedAt[i + 1] - movedAt[i] >= wait) }
        assertTrue(movedAt.last() < 60 * min)
        // Not before: four moves in, it still gets its fifth.
        assertFalse(Health.doesntWork(Health.Moves(5, 0)))
        assertEquals(Health.COOLDOWN, Health.BACKOFF.first())
    }

    /** Three regular answers in a row end a run of moves; on and off doesn't. */
    @Test fun settledAfterThreeAnswers() {
        assertFalse(Health.settled(listOf(true, true)))
        assertFalse(Health.settled(listOf(false, true, true, false, true, true)))
        assertTrue(Health.settled(listOf(false, false, true, true, true)))
    }

    /**
     * Given up on, a route that answers three regular checks on the same network stays given up: failing again, it gets
     * no new run of moves. A route still being moved ends its run the same way.
     */
    @Test fun givenUpStaysUntilTheNetworkChanges() {
        val min = 60_000L
        var clock = 0L
        var m: Health.Moves? = null
        var hold = 0L
        var s = Health.Streak()
        var history = emptyList<Boolean>()
        var moves = 0
        // As the service does: checked every 10 s, regular checks counted in the history once a minute.
        fun check(ok: Boolean) {
            s = Health.next(s, ok, clock)
            if (clock % min == 0L) history = Health.record(history, ok).also { h -> if (Health.ends(m, h)) { m = null; hold = 0 } }
            if (Health.dead(s, clock, hold)) {
                m = Health.moved(m ?: Health.Moves(), clock).also { hold = it.hold }
                if (!Health.doesntWork(m!!)) moves++
                s = Health.Streak()
            }
            clock += 10_000
        }
        while (m?.let(Health::doesntWork) != true) check(ok = false)
        assertEquals(5, moves)
        repeat(4 * 6) { check(ok = true) } // four minutes of answers
        assertTrue(Health.settled(history))
        assertTrue(Health.doesntWork(m!!))
        repeat(6 * 60 * 6) { check(ok = false) } // then six hours of failures
        assertEquals(5, moves)
        assertTrue(Health.doesntWork(m!!))
        // Still being moved: three answers end the run, so a later failure starts from the first wait again.
        assertTrue(Health.ends(Health.Moves(3, 0), listOf(true, true, true)))
        assertTrue(Health.ends(null, listOf(true, true, true)))
        assertFalse(Health.ends(Health.Moves(3, 0), listOf(true, false, true)))
    }

    /**
     * Given up on and still failing, on the same network: never moved again, but its provider is still asked whether it
     * removed this phone, at most once every [Health.COOLDOWN] while it stays dead.
     */
    @Test fun givenUpIsStillCheckedForARemovedDevice() {
        val min = 60_000L
        var clock = 0L
        var m = Health.Moves()
        var s = Health.Streak()
        var moves = 0
        val asked = mutableListOf<Long>() // the rounds whose move would ask the provider about this phone
        while (clock < 6 * 60 * min) {
            s = Health.next(s, ok = false, now = clock)
            if (Health.dead(s, clock, m.hold)) {
                val given = Health.doesntWork(m)
                m = Health.moved(m, clock)
                if (given || Health.doesntWork(m)) asked += clock else moves++
                if (!Health.doesntWork(m)) s = Health.Streak() // only a real move restarts the engine and its checks
            }
            clock += 10_000
        }
        assertEquals(5, moves)
        assertTrue(asked.size > 100)
        asked.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= Health.COOLDOWN) }
        assertTrue(asked.zipWithNext().all { (a, b) -> b - a <= Health.COOLDOWN + 10_000 })
    }

    /**
     * An imported route that keeps failing spends no move budget (nothing moves it): after an unrelated engine restart,
     * which clears its warning, its failures bring the warning back on the same network.
     */
    @Test fun importedRouteKeepsItsWarning() {
        val min = 60_000L
        var clock = 0L
        var m: Health.Moves? = null
        var hold = 0L
        var s = Health.Streak()
        var warned = false
        fun round(): Boolean {
            s = Health.next(s, ok = false, now = clock)
            val dead = Health.dead(s, clock, hold)
            if (dead) Health.handle(m, clock, acts = false).let { (next, h) -> m = next; hold = h }
            clock += 10_000
            return dead
        }
        repeat(2 * 60 * 6) { if (round()) warned = true } // two hours of failures
        assertTrue(warned)
        assertNull(m)
        // An engine restart (another route's move, a sleep) clears the warning and the checks, not the hold.
        s = Health.Streak(); warned = false
        repeat(5 * 6) { if (round()) warned = true }
        assertTrue(warned)
        assertNull(m)
        // A route the app does move spends the budget.
        assertEquals(Health.Moves(1, 1_000 + Health.COOLDOWN), Health.handle(null, 1_000, acts = true).first)
    }

    /** A tunnel that carries TCP only is checked over HTTPS alone: no STUN query goes to its server past the plugin. */
    @Test fun tcpOnlyTunnelGetsNoStun() = runBlocking {
        val stun = java.util.concurrent.atomic.AtomicInteger()
        assertTrue(Health.reachable(udp = false, stun = { stun.incrementAndGet(); true }) { true })
        assertEquals(0, stun.get())
        assertFalse(Health.reachable(udp = false, stun = { stun.incrementAndGet(); true }) { false })
        assertEquals(0, stun.get())
        // Any other tunnel still tries STUN on both servers first.
        assertTrue(Health.reachable(udp = true, stun = { stun.incrementAndGet(); false }) { true })
        assertEquals(2, stun.get())
    }

    @Test fun routeEligibilityAndMovement() {
        val wg = WgPeer("wg", "private", listOf("10.0.0.2/32"), "public", endpoint = "host:1", allowedIps = listOf("10.0.0.0/8"))
        val provider = Route("provider")
        val privateWg = Route("wg", wg = wg)
        val publicWg = privateWg.copy(wg = wg.copy(allowedIps = listOf("::/0")))
        val ovpn = Route("ovpn", ovpn = OvpnProfile("vpn", "client\nredirect-gateway def1"))
        val ss = Route("ss", ss = SsProfile("ss", "host", 1, "method", "secret"))
        val ts = Route("ts", tsExit = "node", checkInternet = true)
        assertTrue(Health.eligible(provider)); assertTrue(Health.movable(provider))
        assertFalse(Health.eligible(privateWg)); assertTrue(Health.eligible(publicWg))
        assertTrue(Health.eligible(ovpn)); assertFalse(Health.eligible(ovpn.copy(ovpn = OvpnProfile("vpn", "client"))))
        assertTrue(Health.eligible(ss)); assertFalse(Health.eligible(ts))
        assertTrue(Health.eligible(privateWg.copy(checkInternet = true)))
        assertFalse(Health.eligible(publicWg.copy(checkInternet = false)))
        listOf(privateWg, publicWg, ovpn, ss, ts).forEach { assertFalse(Health.movable(it)) }
    }

    @Test fun marksConfirmedFailuresAndClearsPassingAndIneligibleRoutes() {
        val eligible = setOf("a", "b")
        assertEquals(setOf("a"), Health.answering(emptySet(), eligible, emptySet(), setOf("a")))
        assertEquals(setOf("b"), Health.answering(setOf("a", "b", "removed"), eligible, setOf("a"), emptySet()))
        assertEquals(emptySet<String>(), Health.answering(setOf("a"), emptySet(), emptySet(), setOf("a")))
    }

    /**
     * A STUN check already past its TCP-only test when a reload turns its route into a Shadowsocks plugin route: the
     * reload's marking closes it before the new engine starts, and no new one starts through that route.
     */
    @Test fun reloadClosesQueuedStunOnATunnelTurningTcpOnly() {
        val gate = Health.StunGate()
        var tcpOnly = emptySet<String>()
        val closed = mutableListOf<String>()
        val refused = { t: String -> t in tcpOnly }
        val a = gate.start("r-a", refused, { "session-a" }) { closed += it }
        val b = gate.start("r-b", refused, { "session-b" }) { closed += it }
        assertEquals("session-a", a)
        gate.udpless(setOf("r-a")) { tcpOnly = tcpOnly + "r-a" } // route a replaced by a plugin route, same tag
        assertEquals(listOf("session-a"), closed) // b carries UDP still: left alone
        assertNull(gate.start("r-a", refused, { "late" }) { closed += it })
        gate.done(b!!)
        gate.udpless(setOf("r-b")) { tcpOnly = tcpOnly + "r-b" }
        assertEquals(listOf("session-a"), closed) // b had ended already: nothing to close
    }
}
