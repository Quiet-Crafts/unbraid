package app.unbraid.vpn

/**
 * When the running engine moves to the next block of fake IPs ([Compiler.FAKE_RANGES]). The engine never hands an
 * address to a second name while its block stays (an app still holding it would have its connections routed as the
 * other name), so a block running low is replaced by the next, at most once per [SPACING]: a flood of lookups can then
 * only make lookups of new names fail until the next move is due, never move anyone's traffic, and a block comes back
 * only after the seven others.
 */
object FakePool {
    enum class Step { Nothing, Rotate, Retry, Wait, Exhausted }

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
     * so the move can't wait forever. [failed]: the last move's engine didn't start, so with none running it is tried
     * again (on the same block) at every check.
     */
    fun step(remaining: Int, since: Long?, failed: Boolean): Step = when {
        remaining < 0 -> if (failed) Step.Retry else Step.Nothing
        remaining >= LOW -> Step.Nothing
        since != null && since in 0L until SPACING -> if (remaining == 0) Step.Exhausted else Step.Wait
        else -> Step.Rotate
    }

    /**
     * The block a failed reload brings the last good config back on: the newest one spent ([spent]), not the one that
     * config [ran] on. After a move whose restart failed they differ, and going back would start the old block afresh
     * (its names may be gone) while apps still hold its addresses: the reuse this pool exists to prevent.
     */
    fun restoreBlock(ran: Int, spent: Int?) = spent ?: ran
}
