package app.unbraid.vpn

/**
 * What the engine does next, as one pure step ([step]) the service only carries out ([Act]).
 *
 * The rules: every start is built from the setup and accounts the user has now, never from an older input, so nothing
 * withdrawn since (a blocked app, a removed route, a signed-out account) comes back by a retry. A start that fails
 * leaves no engine: it is tried once more at once, then the tunnel holds apps offline ([Act.Down]) and starts are
 * tried again, spaced ([wait]). Every start after a failed one takes the next fake-IP block with a new table
 * ([Act.Start.fresh]), never the block that failed nor the one apps last held ([State.Down.held]): the engine's table
 * is trusted only within one block and one cache file. A change the user made that couldn't start is reported
 * ([Act.Refused]) so a profile switch can be put back; a change while down is tried at the next allowed start.
 * Edits while running reload through the service's own watcher, on the running block.
 */
object Life {
    sealed interface State {
        data object Stopped : State
        /** The settings won't open: an engine refuses every app ([UnbraidVpn]'s hold). */
        data object Holding : State
        /** An engine runs [block] since [since], built from [ran]; [said]: its block's exhaustion was logged. */
        data class Running(val block: Int, val since: Long, val ran: Any?, val said: Boolean = false) : State
        /**
         * None runs. [block]: the newest block spent; [held]: the block of the last engine that ran, which apps may still
         * hold addresses of; [tries]: failed starts since it, [last] the time of the newest; [soon]: a change or a new
         * network asked for a start before the wait is over; [shown]: the outage is published.
         */
        data class Down(val block: Int, val held: Int, val ran: Any?, val tries: Int, val last: Long, val soon: Boolean = false, val shown: Boolean = false) : State
    }

    sealed interface Event {
        /** An engine started on [block], built from [want] (setup and accounts), at [now]. */
        data class Started(val block: Int, val want: Any?, val now: Long) : Event
        /** A start of [want] failed after the old engine was given up (or none ran); [block] the newest block spent. */
        data class Failed(val block: Int, val want: Any?, val now: Long) : Event
        /** The setup or the accounts changed (an edit, a sign-out, a restore, a profile switch); now [want]. */
        data class Changed(val want: Any?, val now: Long) : Event
        /** A pool check: [remaining] what the running engine can still hand out (any value while none runs). */
        data class Tick(val now: Long, val remaining: Int) : Event
        data class Network(val now: Long) : Event
        data object Hold : Event
        /** Disconnect, pause, or the service going. */
        data object Stop : Event
    }

    sealed interface Act {
        /** Start on [block], built from what the user has now; [fresh]: none runs, delete the table first. */
        data class Start(val block: Int, val fresh: Boolean) : Act
        /** Say that [want] couldn't start, so a profile switch to it can be put back. */
        data class Refused(val want: Any?) : Act
        /** Say none runs and apps on the VPN are offline; hold the tunnel. */
        data object Down : Act
        /** Say an engine runs again. */
        data object Up : Act
        data object Exhausted : Act
    }

    /** Waits after the 1st, 2nd... failed start in a row before the next; a change or a network waits [GAP] at least. */
    val WAITS = listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L, FakePool.SPACING)
    const val GAP = FakePool.EVERY

    fun wait(tries: Int) = WAITS[(tries - 1).coerceIn(0, WAITS.size - 1)]

    /** The block after [block], skipping [held]: apps may still hold its addresses, a new table there would reuse them. */
    fun after(block: Int, held: Int, blocks: Int) = (block + 1).mod(blocks).let { if (it == held) (it + 1).mod(blocks) else it }

    fun step(s: State, e: Event, blocks: Int = Compiler.FAKE_RANGES.size): Pair<State, List<Act>> = when (e) {
        Event.Stop -> State.Stopped to emptyList()
        Event.Hold -> State.Holding to emptyList()
        is Event.Started -> State.Running(e.block, (s as? State.Running)?.takeIf { it.block == e.block }?.since ?: e.now, e.want,
            (s as? State.Running)?.takeIf { it.block == e.block }?.said ?: false) to listOfNotNull(Act.Up.takeIf { s is State.Down && s.shown })
        is Event.Failed -> when (s) {
            // Once more at once, on a new block.
            is State.Running -> State.Down(e.block, s.block, s.ran, 0, e.now) to listOf(Act.Start(after(e.block, s.block, blocks), true))
            is State.Down -> State.Down(e.block, s.held, s.ran, s.tries + 1, e.now, shown = true) to
                listOfNotNull(Act.Down, Act.Refused(e.want).takeIf { e.want != s.ran })
            else -> s to emptyList()
        }
        is Event.Changed, is Event.Network -> when (s) {
            is State.Down -> {
                val now = (e as? Event.Changed)?.now ?: (e as Event.Network).now
                if (now - s.last >= GAP) s to listOf(Act.Start(after(s.block, s.held, blocks), true)) else s.copy(soon = true) to emptyList()
            }
            else -> s to emptyList()
        }
        is Event.Tick -> when (s) {
            is State.Running -> when (FakePool.step(e.remaining, e.now - s.since)) {
                FakePool.Step.Rotate -> s to listOf(Act.Start(after(s.block, s.block, blocks), false))
                FakePool.Step.Exhausted -> s.copy(said = true) to listOfNotNull(Act.Exhausted.takeIf { !s.said })
                else -> s to emptyList()
            }
            is State.Down -> if (e.now - s.last >= (if (s.soon) GAP else wait(s.tries))) s to listOf(Act.Start(after(s.block, s.held, blocks), true)) else s to emptyList()
            else -> s to emptyList()
        }
    }
}
