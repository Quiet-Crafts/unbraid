package app.unbraid

import app.unbraid.data.BLOCK
import app.unbraid.data.Home
import app.unbraid.data.Location
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.data.VERSION
import app.unbraid.vpn.Compiler
import app.unbraid.vpn.FakePool
import app.unbraid.vpn.FakePool.Step
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A compiled config refuses the fake IPs of every block but its own [block], before any rule can send a connection
 * anywhere: an address an app kept from an older table names nothing now, so it must never be routed by its address.
 * Called on every config the compiler tests build.
 */
fun assertRefusesOtherBlocks(c: JsonObject, block: Int = 0) {
    val rules = c["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    val refuse = rules.indexOfFirst { it["ip_cidr"]?.jsonArray?.map { r -> r.jsonPrimitive.content } == Compiler.FAKE_RANGES - Compiler.FAKE_RANGES[block] }
    assertTrue("no rule refuses the other blocks", refuse >= 0)
    assertEquals("reject", rules[refuse]["action"]!!.jsonPrimitive.content)
    assertEquals(setOf("action", "ip_cidr", "no_drop"), rules[refuse].keys) // nothing narrows it to some apps or networks
    val fake = c["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }.single { it["type"]?.jsonPrimitive?.content == "fakeip" }
    assertEquals(Compiler.FAKE_RANGES[block], fake["inet4_range"]!!.jsonPrimitive.content)
    assertTrue(rules.take(refuse).all { it["action"]?.jsonPrimitive?.content in setOf("hijack-dns", "reject") })
}

class FakePoolTest {
    private val min = 60_000L

    @Test fun capacityIsWhatTheEngineHandsOutOfABlock() {
        // A /18 holds 16,384 addresses; the engine skips the network address and the one after it, and never hands out
        // the broadcast address.
        assertTrue(Compiler.FAKE_RANGES.all { it.endsWith("/18") })
        assertEquals(16_381, FakePool.CAPACITY)
        assertEquals(4_095, FakePool.LOW)
    }

    @Test fun plentyLeftOrNoEngineDoesNothing() {
        assertEquals(Step.Nothing, FakePool.step(FakePool.CAPACITY, 1_000, false))
        assertEquals(Step.Nothing, FakePool.step(FakePool.LOW, 60 * min, false))
        assertEquals(Step.Nothing, FakePool.step(-1, null, false))
        assertEquals(Step.Nothing, FakePool.step(-1, 60 * min, false))
    }

    @Test fun runningLowMovesOnceTenMinutesHavePassed() {
        assertEquals(Step.Rotate, FakePool.step(FakePool.LOW - 1, 10 * min, false))
        assertEquals(Step.Rotate, FakePool.step(0, 10 * min + 1, false))
        assertEquals(Step.Rotate, FakePool.step(10, null, false))
    }

    @Test fun runningLowTooSoonWaitsAndSaysWhenLookupsFail() {
        assertEquals(Step.Wait, FakePool.step(FakePool.LOW - 1, 10 * min - 1, false))
        assertEquals(Step.Wait, FakePool.step(1, 0, false))
        assertEquals(Step.Exhausted, FakePool.step(0, 30_000, false))
    }

    /** The clock the spacing is measured on never goes back; if it did, the move must not wait for it forever. */
    @Test fun aClockThatWentBackNeverHoldsTheMoveForever() {
        assertEquals(Step.Rotate, FakePool.step(0, -1, false))
        assertEquals(Step.Rotate, FakePool.step(FakePool.LOW - 1, Long.MIN_VALUE, false))
        assertEquals(Step.Rotate, FakePool.step(FakePool.LOW - 1, Long.MAX_VALUE, false))
    }

    @Test fun anEngineDownAfterAFailedRestartIsAlwaysRetried() {
        assertEquals(Step.Retry, FakePool.step(-1, 0, true))
        assertEquals(Step.Retry, FakePool.step(-1, null, true))
        // An engine being retired may still be closing and count its own block: what it says doesn't matter.
        assertEquals(Step.Retry, FakePool.step(FakePool.CAPACITY, 0, true))
        assertEquals(Step.Retry, FakePool.step(FakePool.LOW - 1, 10 * min, true))
        assertEquals(Step.Retry, FakePool.step(0, 30_000, true))
        // A restore replaced the setup since what ran: that one exists nowhere else, so the connection ends instead.
        assertEquals(Step.Stop, FakePool.step(-1, 0, true, restored = true))
        assertEquals(Step.Nothing, FakePool.step(FakePool.CAPACITY, 0, false, restored = true))
        // No engine, but none failed (a stop under way): not the pool's to restart.
        assertEquals(Step.Nothing, FakePool.step(-1, 0, false))
    }

    /**
     * A move from block 3 whose restart fails leaves no engine: the next check retries, and any reload that fails and
     * brings the last good config back does so on block 4, never on 3, which the move may already have wiped.
     */
    @Test fun aFailedMoveNeverGoesBackToTheOldBlock() {
        val ran = 3
        val spent = Compiler.nextBlock(ran, false)
        assertEquals(4, spent)
        assertEquals(Step.Retry, FakePool.step(-1, 15_000, true))
        assertEquals(spent, FakePool.restoreBlock(ran, spent))
        assertEquals(spent, restore(input(ran), null, spent).fakeBlock)
        assertEquals(6, restore(input(ran), 6, spent).fakeBlock) // a move names its block
        // Without a move both are the same block; a process that never spent one keeps what ran.
        assertEquals(5, FakePool.restoreBlock(5, 5))
        assertEquals(5, FakePool.restoreBlock(5, null))
        assertEquals(5, restore(input(5), null, null).fakeBlock)
    }

    private val setup = Setup(routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands"))), defaultRoute = "nl",
        apps = mapOf("com.b" to BLOCK), version = VERSION, home = Home(tsSuffixAllowed = "old.ts.net"))
    private fun input(block: Int) = Compiler.Input(setup, emptyMap(), "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", "old.ts.net",
        1 to "old", block, setOf("nl"), tsLoginPending = true, blockedKin = setOf("com.b"))
    private fun restore(last: Compiler.Input, block: Int?, spent: Int?, lockdown: Boolean = false, pending: Boolean = true, kin: (Setup) -> Set<String> = { setOf("com.b") }) =
        FakePool.restoreInput(last, block, spent, 2 to "new", null, "new.ts.net", lockdown, pending, kin)

    /**
     * A restore (also every move) runs what ran, with what may have changed since read again: a sibling of the blocked
     * app installed since is never left outside the VPN with it, Android's Block setting is today's, and tailnet DNS and
     * the sign-in way around the exit can only have narrowed.
     */
    @Test fun aRestoreReadsAgainWhatMayHaveChanged() {
        var askedFor: Setup? = null
        val r = restore(input(3), null, 3, lockdown = true, pending = false) { askedFor = it; setOf("com.b", "com.b.sibling") }
        assertEquals(setOf("com.b", "com.b.sibling"), r.blockedKin)
        assertEquals(r.setup, askedFor) // of the setup that runs, not of whatever is being edited
        assertTrue(r.lockdown)
        assertEquals(null, r.setup.home.tsSuffixAllowed)
        assertEquals("new.ts.net", r.tsSuffix)
        assertEquals(false, r.tsLoginPending)
        assertEquals(false, restore(input(3).let { Compiler.Input(it.setup, it.links, it.selfPackage, it.lockdown, it.tsDir, it.cacheFile, fakeBlock = 3) }, null, 3, pending = true).tsLoginPending)
        assertEquals(2 to "new", r.probe)
        // What ran stays: its routes, its links, which routes were blocked.
        assertEquals(setup.routes, r.setup.routes)
        assertEquals(setOf("nl"), r.blocked)
    }

    /**
     * A restart saves a new block before anything is given up: a failed save keeps the running engine, its probe and
     * its clock (the next check tries again), and only then does the old engine go and the new one start.
     */
    @Test fun aRestartGivesNothingUpBeforeTheBlockIsSaved() {
        val log = mutableListOf<String>()
        fun run(block: Int, spent: Int?, saveFails: Boolean = false, startFails: Boolean = false) = FakePool.restart(block, spent,
            save = { log += "save $it"; if (saveFails) error("disk full") }, commit = { log += "commit" }) { log += "start"; if (startFails) error("no") }
        assertEquals(FakePool.Outcome.Kept, run(4, 3, saveFails = true))
        assertEquals(listOf("save 4"), log)
        log.clear()
        assertEquals(FakePool.Outcome.Down, run(4, 3, startFails = true))
        assertEquals(listOf("save 4", "commit", "start"), log)
        log.clear()
        assertEquals(FakePool.Outcome.Ran, run(4, 3))
        assertEquals(listOf("save 4", "commit", "start"), log)
        log.clear()
        // The open table's own block was saved when it started: nothing to save, and a save failure can't stop it.
        assertEquals(FakePool.Outcome.Ran, run(4, 4, saveFails = true))
        assertEquals(listOf("commit", "start"), log)
    }

    /**
     * The service's pool watcher and failed-reload path as a table over random events, run through [FakePool]'s own
     * decisions with an engine and a store that fail on demand. After every event: an engine runs exactly when none is
     * down, only on the newest spent block, which was saved before it started, and no block comes back before seven
     * others were used; a move that never got past saving its block gave up nothing.
     */
    @Test fun thePoolStateMachineHoldsItsRulesOverRandomEvents() = repeat(200) { seed ->
        val rnd = java.util.Random(seed.toLong())
        var saved: Int? = 3 // store.fakeBlock
        var lastBlock: Int? = 3
        var lastInput = input(3)
        var ran: Int? = 3 // the block the engine runs, null when none does
        var down = false
        var probe = 1
        var live: Int? = 1 // the probe pair the app's own tests use
        var since = 0L
        var now = 0L
        val used = mutableListOf(3) // blocks in the order tables were opened on them
        fun restoreLastGood(block: Int?, saveFails: Boolean, startFails: Boolean): FakePool.Outcome {
            val input = FakePool.restoreInput(lastInput, block, lastBlock, ++probe to "p$probe", null, null, false, false) { emptySet() }
            val outcome = FakePool.restart(input.fakeBlock, lastBlock, save = { if (saveFails) error("disk full"); saved = it }, commit = {
                if (input.fakeBlock != lastBlock) { since = now; used += input.fakeBlock }
                lastBlock = input.fakeBlock; lastInput = input; down = true; live = null
            }) {
                ran = null // libbox stops the old engine first
                if (startFails) error("no start")
                assertEquals("started on a block not saved", saved, input.fakeBlock)
                ran = input.fakeBlock
            }
            if (outcome == FakePool.Outcome.Ran) { down = false; live = input.probe!!.first }
            return outcome
        }
        repeat(300) {
            now += FakePool.EVERY
            val before = Triple(ran, live, since)
            when (rnd.nextInt(4)) {
                0, 1 -> { // a pool check
                    val remaining = if (ran == null) -1 else listOf(0, 1, FakePool.LOW - 1, FakePool.CAPACITY)[rnd.nextInt(4)]
                    when (FakePool.step(remaining, now - since, down)) {
                        Step.Rotate -> if (restoreLastGood(Compiler.nextBlock(lastBlock!!, false), rnd.nextInt(4) == 0, rnd.nextInt(4) == 0) == FakePool.Outcome.Kept)
                            assertEquals("a move that wasn't started gave something up", before, Triple(ran, live, since))
                        Step.Retry -> { assertTrue(down); restoreLastGood(null, rnd.nextInt(4) == 0, rnd.nextInt(3) == 0) }
                        Step.Stop -> error("no restore happens here")
                        else -> assertTrue(!down)
                    }
                }
                2 -> { // a reload: the open table restarts on its own block; a failed start brings the last good config back
                    val restarting = rnd.nextInt(3) != 0 || down
                    if (restarting) {
                        ran = null
                        if (rnd.nextInt(3) == 0) { ran = lastBlock; down = false }
                        else if (restoreLastGood(null, false, rnd.nextInt(3) == 0) != FakePool.Outcome.Ran) down = true
                    }
                }
                else -> {} // time passes
            }
            assertEquals("an engine runs exactly when none is down", down, ran == null)
            ran?.let { assertEquals("runs a block that isn't the newest spent", lastBlock, it) }
            assertEquals("the block in use was never saved", lastBlock, saved)
            // A block is opened again only after the seven others.
            used.windowed(Compiler.FAKE_RANGES.size).forEach { assertEquals(it.toString(), Compiler.FAKE_RANGES.size, it.toSet().size) }
        }
    }

    /** At most one move per ten minutes, however long a flood keeps the block low, and a move always takes the next block. */
    @Test fun aFloodMovesAtMostOncePerSpacing() {
        var since: Long? = null
        var block = 3
        var moves = 0
        for (t in 0L until 60 * min step FakePool.EVERY) {
            if (FakePool.step(0, since?.let { t - it }, false) == Step.Rotate) { since = t; block = Compiler.nextBlock(block, false); moves++ }
        }
        assertEquals(6, moves)
        assertEquals(1, block) // 3 → 4 → 5 → 6 → 7 → 0 → 1
    }

    @Test fun everyBlockRefusesTheOthersInTheBlockEverythingConfig() = Compiler.FAKE_RANGES.indices.forEach { b ->
        assertRefusesOtherBlocks(kotlinx.serialization.json.Json.parseToJsonElement(Compiler.blockAll("app.unbraid", "/tmp/cache.db", b)).jsonObject, b)
    }
}
