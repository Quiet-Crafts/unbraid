package app.unbraid.vpn

import app.unbraid.data.Setup

/**
 * When the running engine moves to the next block of fake IPs ([Compiler.FAKE_RANGES]). The engine never hands an
 * address to a second name while its block stays (an app still holding it would have its connections routed as the
 * other name), so a block running low is replaced by the next, at most once per [SPACING]: a flood of lookups can then
 * only make lookups of new names fail until the next move is due, never move anyone's traffic, and a block comes back
 * only after the seven others. The engine also counts a table it had trouble with as used up (0 left).
 */
object FakePool {
    enum class Step { Nothing, Rotate, Wait, Exhausted }

    /** Addresses one block hands out: all but the network address, the engine's starting point and the broadcast address. */
    val CAPACITY = (1 shl (32 - Compiler.FAKE_RANGES[0].substringAfter('/').toInt())) - 3
    const val SPACING = 600_000L
    /** How often the engine is asked how many are left. */
    const val EVERY = 15_000L

    /**
     * [remaining]: what the engine can still hand out, negative while it runs none. [since]: milliseconds since the
     * block last changed (a start or a move), null if unknown; a negative one (the clock moved back) counts as unknown,
     * so the move can't wait forever.
     */
    fun step(remaining: Int, since: Long?): Step = when {
        remaining < 0 || 4L * remaining >= CAPACITY -> Step.Nothing // a quarter or more left
        since != null && since in 0L until SPACING -> if (remaining == 0) Step.Exhausted else Step.Wait
        else -> Step.Rotate
    }

    /**
     * Milliseconds to the next check after [step] at [since]: [EVERY], or less when a low block waits for the spacing,
     * so the move comes as soon as it is due.
     */
    fun nextCheck(step: Step, since: Long?) =
        if ((step == Step.Wait || step == Step.Exhausted) && since != null && since >= 0) (SPACING - since).coerceIn(1, EVERY) else EVERY

    /**
     * One check: [step] and [nextCheck] on what is left ([remaining]) and the time since the block changed ([since]),
     * read after the count: a slow count (it can wait behind a database write) never adds a wait past the deadline.
     */
    inline fun poll(remaining: () -> Int, since: () -> Long?): Pair<Step, Long> {
        val left = remaining()
        val s = since()
        val step = step(left, s)
        return step to nextCheck(step, s)
    }

    /**
     * A reload keeps the open table's block only while its file is there ([bytes] > 0; a missing file has 0): one that
     * went missing or empty under a running engine would start an empty table on addresses apps still hold.
     */
    fun keeps(open: Boolean, bytes: Long) = open && bytes > 0

    /**
     * The block a failed reload brings the last good config back on: the newest one spent ([spent]), not the one that
     * config [ran] on. After a move whose start failed they differ, and going back would start the old block afresh
     * while apps still hold its addresses: the reuse this pool exists to prevent.
     */
    fun restoreBlock(ran: Int, spent: Int?) = spent ?: ran

    /**
     * What a restore or a move runs: what last ran ([last]) as it was compiled, on [block] (a move) or else
     * [restoreBlock], with a new probe pair, the sign-in way around the exit only ever narrowed, and the apps kept inside
     * for another app of their user id: those that ran plus [kin], asked of the setup that runs (one installed since must not
     * be left outside, and a lookup that fails or finds fewer never lets one that was kept inside out).
     * After a failed change ([move] false) what may have changed since is read again as [Compiler.Input] reads it now:
     * tailnet DNS authority as saved ([tsSuffixAllowed]), the tailnet's DNS suffix and Android's Block setting. A move
     * and its retry keep what runs: a change the engine refused before the old one was given up stays refused.
     */
    fun restoreInput(last: Compiler.Input, block: Int?, spent: Int?, probe: Pair<Int, String>, tsSuffixAllowed: String?, tsSuffix: String?,
                     lockdown: Boolean, tsLoginPending: Boolean, move: Boolean, verify: (Setup) -> Setup = { it }, kin: (Setup) -> Set<String>): Compiler.Input {
        val checked = verify(last.setup)
        val setup = if (move) checked else checked.copy(home = checked.home.copy(tsSuffixAllowed = tsSuffixAllowed))
        return Compiler.Input(setup, last.links, last.selfPackage, if (move) last.lockdown else lockdown, last.tsDir, last.cacheFile, if (move) last.tsSuffix else tsSuffix, probe,
            block ?: restoreBlock(last.fakeBlock, spent), last.blocked, last.tsLoginPending && tsLoginPending, last.blockedKin + runCatching { kin(setup) }.getOrDefault(emptySet()))
    }

    /**
     * How a [restart] ended: [Ran]; [Kept], nothing given up (the engine runs as before); [Failed], none runs. Once the
     * old engine is gone (`gone`), every failure is [Failed].
     */
    enum class Outcome { Ran, Kept, Failed, Superseded }

    /**
     * Every start on [block], before the engine can hand out any of it: unless it is [spent] already, the old table in
     * [dir] is deleted, so a new block starts from no file (an old one would carry a missing or bad table into it), and
     * only then is the block noted down as spent ([note]). A block number is saved as spent only when a start on it is
     * about to happen with no table file present: a failed delete saves nothing, so a block that never ran is not
     * skipped by the next start, also after the app dies. A failed save leaves the running engine on its old block with
     * its table deleted, which the next reload takes as a new block ([keeps]). Throws if either fails; true if the
     * block is new.
     */
    fun spend(block: Int, spent: Int?, dir: java.io.File, note: (Int) -> Unit) = (block != spent).also { if (it) { LookupTable.clear(dir); note(block) } }

    /**
     * One engine restart, in the only safe order. [prepare] builds what to run (input and its config) from the newest
     * block [spent]; the block is then spent ([spend]). A failure up to here gives nothing up: [Outcome.Kept], or
     * [Outcome.Failed] when the old engine had already been given up before this call ([gone]). [commit] then gives the
     * old engine up (from here nothing goes back to the old block) and [start] runs the new one.
     */
    inline fun restart(gone: Boolean, spent: Int?, dir: java.io.File, prepare: (spent: Int?) -> Pair<Compiler.Input, String>, noinline note: (Int) -> Unit,
                       commit: (Compiler.Input) -> Unit, current: () -> Boolean = { true }, start: (Compiler.Input, String) -> Unit): Outcome {
        val stale = if (gone) Outcome.Superseded else Outcome.Kept
        if (!current()) return stale
        val (input, json) = try { prepare(spent) } catch (e: Exception) {
            return if (!current()) stale else if (gone) Outcome.Failed else Outcome.Kept
        }
        if (!current()) return stale
        try { spend(input.fakeBlock, spent, dir, note) } catch (e: Exception) {
            return if (!current()) stale else if (gone) Outcome.Failed else Outcome.Kept
        }
        // A new block's table has been cleared and its number saved. It can no longer be treated as an untouched move.
        if (!current()) return if (input.fakeBlock != spent) Outcome.Superseded else stale
        val ran = try {
            commit(input)
            if (!current()) return Outcome.Superseded
            start(input, json)
            true
        } catch (e: Exception) { false }
        return if (!current()) Outcome.Superseded else if (ran) Outcome.Ran else Outcome.Failed
    }

    /** A refused edit can keep or recover the last working policy only within the same accepted restore. */
    inline fun afterFailure(restarting: Boolean, current: () -> Boolean, recover: () -> Outcome): Outcome {
        if (!current()) return Outcome.Superseded
        val result = if (restarting) recover() else Outcome.Kept
        return if (current()) result else Outcome.Superseded
    }

    /**
     * A move to the next block: [attempt] (`gone` false) and, if its start failed after the old engine was given up,
     * once more with what ran (`gone` true), whose every failure ends the connection.
     */
    inline fun move(attempt: (gone: Boolean) -> Outcome) = attempt(false).let { if (it == Outcome.Failed) attempt(true) else it }
}
