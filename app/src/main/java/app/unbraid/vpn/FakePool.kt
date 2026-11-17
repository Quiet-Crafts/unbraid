package app.unbraid.vpn

/**
 * When the running engine moves to the next block of fake IPs ([Compiler.FAKE_RANGES]). The engine never hands an
 * address to a second name while its block stays (an app still holding it would have its connections routed as the
 * other name), so a block running low is replaced by the next, at most once per [SPACING]: a flood of lookups can then
 * only make lookups of new names fail until the next move is due, never move anyone's traffic, and a block comes back
 * only after the seven others. The engine also counts a block whose table it can't trust as used up (0 left). [Life]
 * carries the move out.
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
     * block last changed (a start or a move); a negative one (the clock moved back) counts as unknown, so the move
     * can't wait forever.
     */
    fun step(remaining: Int, since: Long?): Step = when {
        remaining < 0 || remaining >= LOW -> Step.Nothing
        since != null && since in 0L until SPACING -> if (remaining == 0) Step.Exhausted else Step.Wait
        else -> Step.Rotate
    }
}
