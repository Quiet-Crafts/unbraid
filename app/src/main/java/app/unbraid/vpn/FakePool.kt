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
    /** Below this many left, the block is replaced. */
    val LOW = CAPACITY / 4
    const val SPACING = 600_000L
    /** How often the engine is asked how many are left. */
    const val EVERY = 15_000L

    /**
     * [remaining]: what the engine can still hand out, negative while it runs none. [since]: milliseconds since the
     * block last changed (a start or a move), null if unknown; a negative one (the clock moved back) counts as unknown,
     * so the move can't wait forever.
     */
    fun step(remaining: Int, since: Long?): Step = when {
        remaining < 0 || remaining >= LOW -> Step.Nothing
        since != null && since in 0L until SPACING -> if (remaining == 0) Step.Exhausted else Step.Wait
        else -> Step.Rotate
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
     * [restoreBlock], with a new probe pair, and with what may have changed since read again as [Compiler.Input] reads it
     * now: tailnet DNS authority as saved ([tsSuffixAllowed]) and the tailnet's DNS suffix, the sign-in way around the
     * exit only ever narrowed, Android's Block setting, and the apps sharing a user id with a blocked one ([kin], asked of
     * the setup that runs: one installed since must not be left outside).
     */
    fun restoreInput(last: Compiler.Input, block: Int?, spent: Int?, probe: Pair<Int, String>, tsSuffixAllowed: String?, tsSuffix: String?,
                     lockdown: Boolean, tsLoginPending: Boolean, kin: (Setup) -> Set<String>): Compiler.Input {
        val setup = last.setup.copy(home = last.setup.home.copy(tsSuffixAllowed = tsSuffixAllowed))
        return Compiler.Input(setup, last.links, last.selfPackage, lockdown, last.tsDir, last.cacheFile, tsSuffix, probe,
            block ?: restoreBlock(last.fakeBlock, spent), last.blocked, last.tsLoginPending && tsLoginPending, kin(setup))
    }

    /**
     * How a [restart] ended: [Ran]; [Kept], nothing given up (the engine runs as before); [Failed], none runs. Once the
     * old engine is gone (`gone`), every failure is [Failed].
     */
    enum class Outcome { Ran, Kept, Failed }

    /**
     * One engine restart, in the only safe order. [prepare] builds what to run (input and its config); a new block is
     * then saved ([save], only when it isn't [spent] already, and failing unless the old table is gone), before the
     * engine can hand out any of it. A failure up to here gives nothing up: [Outcome.Kept], or [Outcome.Failed] when the
     * old engine had already been given up before this call ([gone]). [commit] then gives the old engine up (from here
     * nothing goes back to the old block) and [start] runs the new one.
     */
    inline fun restart(gone: Boolean, spent: Int?, prepare: () -> Pair<Compiler.Input, String>, save: (Int) -> Unit,
                       commit: (Compiler.Input) -> Unit, start: (Compiler.Input, String) -> Unit): Outcome {
        val (input, json) = try { prepare() } catch (e: Exception) { return if (gone) Outcome.Failed else Outcome.Kept }
        try { if (input.fakeBlock != spent) save(input.fakeBlock) } catch (e: Exception) { return if (gone) Outcome.Failed else Outcome.Kept }
        commit(input)
        return try { start(input, json); Outcome.Ran } catch (e: Exception) { Outcome.Failed }
    }

    /**
     * A move to the next block: [attempt] (`gone` false) and, if its start failed after the old engine was given up,
     * once more with what ran (`gone` true), whose every failure ends the connection.
     */
    inline fun move(attempt: (gone: Boolean) -> Outcome) = attempt(false).let { if (it == Outcome.Failed) attempt(true) else it }
}
