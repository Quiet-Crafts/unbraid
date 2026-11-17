package app.unbraid.vpn

import app.unbraid.data.Setup

/**
 * When the running engine moves to the next block of fake IPs ([Compiler.FAKE_RANGES]). The engine never hands an
 * address to a second name while its block stays (an app still holding it would have its connections routed as the
 * other name), so a block running low is replaced by the next, at most once per [SPACING]: a flood of lookups can then
 * only make lookups of new names fail until the next move is due, never move anyone's traffic, and a block comes back
 * only after the seven others.
 */
object FakePool {
    /** [Retry]: start the engine again; [Stop]: end the connection ([UnbraidVpn.RESTORE_REFUSED]). */
    enum class Step { Nothing, Rotate, Retry, Wait, Exhausted, Stop }

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
     * so the move can't wait forever. [down]: a restart failed after the old engine was given up, so none runs: it is
     * tried again at every check whatever [remaining] says (an engine being retired may still be closing and count its
     * own block), unless a restore replaced the setup since ([restored]): what ran then exists nowhere else.
     */
    fun step(remaining: Int, since: Long?, down: Boolean, restored: Boolean = false): Step = when {
        down -> if (restored) Step.Stop else Step.Retry
        remaining < 0 || remaining >= LOW -> Step.Nothing
        since != null && since in 0L until SPACING -> if (remaining == 0) Step.Exhausted else Step.Wait
        else -> Step.Rotate
    }

    /**
     * The block a failed reload brings the last good config back on: the newest one spent ([spent]), not the one that
     * config [ran] on. After a move whose restart failed they differ, and going back would start the old block afresh
     * (its names may be gone) while apps still hold its addresses: the reuse this pool exists to prevent.
     */
    fun restoreBlock(ran: Int, spent: Int?) = spent ?: ran

    /**
     * What a restore or a move runs: what last ran ([last]), on [block] (a move) or else [restoreBlock], with a new
     * probe pair, and with what may have changed since it was compiled read again: tailnet DNS authority and the sign-in
     * way around the exit only ever narrowed, Android's Block setting, and the apps sharing a user id with a blocked
     * one ([kin], asked of the setup that runs: one installed since must not be left outside the VPN with it).
     */
    fun restoreInput(last: Compiler.Input, block: Int?, spent: Int?, probe: Pair<Int, String>, tsSuffixAllowed: String?, tsSuffix: String?,
                     lockdown: Boolean, tsLoginPending: Boolean, kin: (Setup) -> Set<String>): Compiler.Input {
        val setup = last.setup.copy(home = last.setup.home.copy(tsSuffixAllowed = tsSuffixAllowed))
        return Compiler.Input(setup, last.links, last.selfPackage, lockdown, last.tsDir, last.cacheFile, tsSuffix, probe,
            block ?: restoreBlock(last.fakeBlock, spent), last.blocked, last.tsLoginPending && tsLoginPending, kin(setup))
    }

    /** How a [restart] ended: [Ran]; [Kept], nothing given up (the engine runs as before); [Down], none runs. */
    enum class Outcome { Ran, Kept, Down }

    /**
     * One engine restart onto [block], in the only safe order. A new block is saved first ([save], only when it isn't
     * [spent] already), before the engine can hand out any of it and before anything is given up: a failure there
     * keeps the running engine, its probe and its block's clock, and the next check tries again. [commit] then gives
     * the old engine up (the point of no return: from here nothing goes back to the old block, whose names the engine
     * may drop), and [start] runs the new one; if that fails, none runs.
     */
    inline fun restart(block: Int, spent: Int?, save: (Int) -> Unit, commit: () -> Unit, start: () -> Unit): Outcome {
        try { if (block != spent) save(block) } catch (e: Exception) { return Outcome.Kept }
        commit()
        return try { start(); Outcome.Ran } catch (e: Exception) { Outcome.Down }
    }
}
