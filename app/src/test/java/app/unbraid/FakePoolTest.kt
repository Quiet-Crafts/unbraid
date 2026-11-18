package app.unbraid

import app.unbraid.vpn.Compiler
import app.unbraid.vpn.FakePool
import app.unbraid.vpn.FakePool.Step
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.unbraid.data.BLOCK
import app.unbraid.data.Home
import app.unbraid.data.Location
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.data.VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(Step.Nothing, FakePool.step(FakePool.CAPACITY, 1_000))
        assertEquals(Step.Nothing, FakePool.step(FakePool.LOW, 60 * min))
        assertEquals(Step.Nothing, FakePool.step(-1, null))
        assertEquals(Step.Nothing, FakePool.step(-1, 60 * min))
    }

    @Test fun runningLowMovesOnceTenMinutesHavePassed() {
        assertEquals(Step.Rotate, FakePool.step(FakePool.LOW - 1, 10 * min))
        assertEquals(Step.Rotate, FakePool.step(0, 10 * min + 1))
        assertEquals(Step.Rotate, FakePool.step(10, null))
    }

    @Test fun runningLowTooSoonWaitsAndSaysWhenLookupsFail() {
        assertEquals(Step.Wait, FakePool.step(FakePool.LOW - 1, 10 * min - 1))
        assertEquals(Step.Wait, FakePool.step(1, 0))
        assertEquals(Step.Exhausted, FakePool.step(0, 30_000))
    }

    /** The clock the spacing is measured on never goes back; if it did, the move must not wait for it forever. */
    @Test fun aClockThatWentBackNeverHoldsTheMoveForever() {
        assertEquals(Step.Rotate, FakePool.step(0, -1))
        assertEquals(Step.Rotate, FakePool.step(FakePool.LOW - 1, Long.MIN_VALUE))
        assertEquals(Step.Rotate, FakePool.step(FakePool.LOW - 1, Long.MAX_VALUE))
    }

    /** At most one move per ten minutes, however long a flood keeps the block low, and a move always takes the next block. */
    @Test fun aFloodMovesAtMostOncePerSpacing() {
        var since: Long? = null
        var block = 3
        var moves = 0
        for (t in 0L until 60 * min step FakePool.EVERY) {
            if (FakePool.step(0, since?.let { t - it }) == Step.Rotate) { since = t; block = Compiler.nextBlock(block, false); moves++ }
        }
        assertEquals(6, moves)
        assertEquals(1, block) // 3 → 4 → 5 → 6 → 7 → 0 → 1
    }

    /** A reload reopens the running table only while its file is there; missing (length 0) or empty, a new block. */
    @Test fun aReloadKeepsTheTableOnlyWhileItsFileIsThere() {
        assertTrue(FakePool.keeps(true, 32_768))
        assertFalse(FakePool.keeps(true, 0)) // missing or emptied under the running engine
        assertFalse(FakePool.keeps(false, 32_768)) // no engine has it open: a new table anyway
        assertEquals(4, Compiler.nextBlock(3, FakePool.keeps(true, 0)))
        assertEquals(3, Compiler.nextBlock(3, FakePool.keeps(true, 1)))
    }

    /**
     * A move from block 3 whose start fails brings the last good config back on block 4, never on 3: apps may still hold
     * 3's addresses, and the move already gave its table up.
     */
    @Test fun aFailedMoveNeverGoesBackToTheOldBlock() {
        val spent = Compiler.nextBlock(3, false)
        assertEquals(4, spent)
        assertEquals(spent, FakePool.restoreBlock(3, spent))
        assertEquals(spent, restore(input(3), null, spent).fakeBlock)
        assertEquals(6, restore(input(3), 6, spent).fakeBlock) // a move names its block
        // Without a move both are the same block; a process that never spent one keeps what ran.
        assertEquals(5, FakePool.restoreBlock(5, 5))
        assertEquals(5, FakePool.restoreBlock(5, null))
        assertEquals(5, restore(input(5), null, null).fakeBlock)
    }

    private val setup = Setup(routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands"))), defaultRoute = "nl",
        apps = mapOf("com.b" to BLOCK), version = VERSION, home = Home(tsSuffixAllowed = "old.ts.net"))
    private fun input(block: Int, pending: Boolean = true) = Compiler.Input(setup, emptyMap(), "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", "old.ts.net",
        1 to "old", block, setOf("nl"), tsLoginPending = pending, blockedKin = setOf("com.b"))
    private fun restore(last: Compiler.Input, block: Int?, spent: Int?, lockdown: Boolean = false, pending: Boolean = true, kin: (Setup) -> Set<String> = { setOf("com.b") }) =
        FakePool.restoreInput(last, block, spent, 2 to "new", null, "new.ts.net", lockdown, pending, kin)

    /**
     * A restore (also every move) runs what ran, with what may have changed since read again: a sibling of the blocked
     * app installed since is never left outside the VPN with it, Android's Block setting and tailnet DNS authority are
     * today's, and the sign-in way around the exit can only have narrowed. Everything else is exactly what ran.
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
        assertEquals(2 to "new", r.probe)
        // What ran stays: its routes, its links, which routes were blocked.
        assertEquals(setup.copy(home = Home()), r.setup)
        assertEquals(setOf("nl"), r.blocked)
        assertFalse(restore(input(3), null, 3, lockdown = false).lockdown) // today's, as a reload would compile it
        assertEquals(true, restore(input(3), null, 3).tsLoginPending)
        assertEquals(false, FakePool.restoreInput(input(3, pending = false), null, 3, 2 to "new", null, null, false, true) { emptySet() }.tsLoginPending)
    }

    /**
     * A restart saves a new block before anything is given up: a failed save keeps the running engine (the next check
     * tries again), and only then does the old engine go and the new one start.
     */
    private val log = mutableListOf<String>()
    private fun run(block: Int, spent: Int?, gone: Boolean = false, prepareFails: Boolean = false, saveFails: Boolean = false, startFails: Boolean = false) =
        FakePool.restart(gone, spent, prepare = { log += "prepare"; if (prepareFails) error("no probe"); input(block) to "json" },
            save = { log += "save $it"; if (saveFails) error("disk full") }, commit = { log += "commit ${it.fakeBlock}" }) { _, j -> log += "start $j"; if (startFails) error("no") }

    @Test fun aRestartGivesNothingUpBeforeTheBlockIsSaved() {
        assertEquals(FakePool.Outcome.Kept, run(4, 3, prepareFails = true))
        assertEquals(listOf("prepare"), log)
        log.clear()
        assertEquals(FakePool.Outcome.Kept, run(4, 3, saveFails = true))
        assertEquals(listOf("prepare", "save 4"), log)
        log.clear()
        assertEquals(FakePool.Outcome.Failed, run(4, 3, startFails = true))
        assertEquals(listOf("prepare", "save 4", "commit 4", "start json"), log)
        log.clear()
        assertEquals(FakePool.Outcome.Ran, run(4, 3))
        assertEquals(listOf("prepare", "save 4", "commit 4", "start json"), log)
        log.clear()
        // The open table's own block was saved when it started: nothing to save, and a save failure can't stop it.
        assertEquals(FakePool.Outcome.Ran, run(4, 4, saveFails = true))
        assertEquals(listOf("prepare", "commit 4", "start json"), log)
    }

    /** Once the old engine was given up before the call, nothing runs unless this start does: every failure is Failed. */
    @Test fun afterTheOldEngineIsGoneEveryFailureEndsTheConnection() {
        assertEquals(FakePool.Outcome.Failed, run(4, 4, gone = true, prepareFails = true))
        assertEquals(FakePool.Outcome.Failed, run(5, 4, gone = true, saveFails = true))
        assertEquals(FakePool.Outcome.Failed, run(4, 4, gone = true, startFails = true))
        assertEquals(FakePool.Outcome.Ran, run(4, 4, gone = true))
    }

    /**
     * A move: a first try that gave nothing up is the move's answer; one whose start failed is tried once more as gone,
     * and only that second answer counts.
     */
    @Test fun aMoveTriesOnceMoreOnlyAfterGivingTheOldEngineUp() {
        val Kept = FakePool.Outcome.Kept; val Failed = FakePool.Outcome.Failed; val Ran = FakePool.Outcome.Ran
        for (first in FakePool.Outcome.values()) for (second in FakePool.Outcome.values()) {
            val calls = mutableListOf<Boolean>()
            val r = FakePool.move { gone -> calls += gone; if (gone) second else first }
            if (first == Failed) { assertEquals(listOf(false, true), calls); assertEquals(second, r) }
            else { assertEquals(listOf(false), calls); assertEquals(first, r) }
        }
        // As the service runs it: the retry's preparation fails after the first start gave the old engine up.
        var n = 0
        assertEquals(Failed, FakePool.move { gone -> n++; run(4, 3, gone, prepareFails = gone, startFails = !gone) })
        assertEquals(2, n)
        assertEquals(Kept, FakePool.move { gone -> run(4, 3, gone, saveFails = true) })
        assertEquals(Ran, FakePool.move { gone -> run(4, if (gone) 4 else 3, gone, startFails = !gone) })
    }

    /** A new block starts from no table file: one that can't be deleted stops the move before anything is given up. */
    @Test fun aTableThatCannotBeDeletedStopsTheMove() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            app.unbraid.vpn.LookupTable.clear(dir) // already gone is fine
            java.io.File(dir, "cache.db").writeText("x")
            app.unbraid.vpn.LookupTable.clear(dir)
            assertFalse(java.io.File(dir, "cache.db").exists())
            java.io.File(dir, "cache.db/inside").apply { parentFile!!.mkdirs(); writeText("x") } // a delete that fails
            assertEquals(FakePool.Outcome.Kept, FakePool.restart(false, 3, prepare = { input(4) to "json" }, save = { app.unbraid.vpn.LookupTable.clear(dir) },
                commit = { error("gave the old engine up") }) { _, _ -> error("started") })
        } finally { dir.deleteRecursively() }
    }

    /**
     * The service's pool watcher and failed-reload path over random events, run through [FakePool]'s own decisions with
     * an engine and a store that fail on demand, as the service does: a move whose start fails brings what ran back once,
     * and if that fails too the connection ends. After every event: an engine runs only on the newest spent block, saved
     * before it started; a table on a new block starts from a deleted file; no block comes back before seven others; a
     * move that never got past saving gave nothing up.
     */
    @Test fun thePoolRulesHoldOverRandomEvents() = repeat(300) { seed ->
        val rnd = java.util.Random(seed.toLong())
        var saved: Int? = 3 // store.fakeBlock
        var lastBlock: Int? = 3
        var lastInput = input(3)
        var ran: Int? = 3 // the block the engine runs, null when none does (the connection ended)
        var file = 3 // the block the table file was started on
        var probe = 1
        var live: Int? = 1 // the probe pair the app's own tests use
        var since = 0L
        var now = 0L
        val used = mutableListOf(3) // blocks in the order tables were opened on them
        fun restoreLastGood(block: Int?, gone: Boolean, prepareFails: Boolean, saveFails: Boolean, startFails: Boolean): FakePool.Outcome =
            FakePool.restart(gone, lastBlock, prepare = {
                if (prepareFails) error("no probe")
                FakePool.restoreInput(lastInput, block, lastBlock, ++probe to "p$probe", null, null, false, false) { emptySet() } to "json"
            }, save = { if (saveFails) error("disk full"); saved = it; file = -1 /* deleted */ }, commit = { input ->
                live = null
                if (input.fakeBlock != lastBlock) { since = now; used += input.fakeBlock; assertEquals("a new block's table not deleted", -1, file); file = input.fakeBlock }
                lastBlock = input.fakeBlock; lastInput = input
            }) { input, _ ->
                ran = null // libbox stops the old engine first
                if (startFails) error("no start")
                assertEquals("started on a block not saved", saved, input.fakeBlock)
                assertEquals("a table opened on another block's file", input.fakeBlock, file)
                ran = input.fakeBlock; live = input.probe!!.first
            }
        repeat(300) {
            if (ran == null) return@repeat // torn down: the user connects again, which starts a new run
            now += FakePool.EVERY
            val before = Triple(ran, live, since)
            when (rnd.nextInt(3)) {
                0, 1 -> { // a pool check, as watchPool
                    val remaining = listOf(0, 1, FakePool.LOW - 1, FakePool.CAPACITY)[rnd.nextInt(4)]
                    if (FakePool.step(remaining, now - since) == Step.Rotate) {
                        val f = { rnd.nextInt(4) == 0 }
                        when (FakePool.move { gone -> restoreLastGood(Compiler.nextBlock(lastBlock!!, false).takeIf { !gone }, gone, f(), f(), f()) }) {
                            FakePool.Outcome.Kept -> assertEquals("a move that wasn't started gave something up", before, Triple(ran, live, since))
                            FakePool.Outcome.Failed -> assertEquals(null, ran)
                            FakePool.Outcome.Ran -> assertTrue(ran != before.first)
                        }
                    }
                }
                else -> { // a reload that restarts and fails: the last good config comes back on the newest block
                    ran = null
                    val r = restoreLastGood(null, true, rnd.nextInt(5) == 0, false, rnd.nextInt(3) == 0)
                    assertEquals("an engine runs exactly when the restore ran", r == FakePool.Outcome.Ran, ran != null)
                }
            }
            ran?.let { assertEquals("runs a block that isn't the newest spent", lastBlock, it) }
            assertEquals("the block in use was never saved", lastBlock, saved)
            used.windowed(Compiler.FAKE_RANGES.size).forEach { assertEquals(it.toString(), Compiler.FAKE_RANGES.size, it.toSet().size) }
        }
    }

    @Test fun everyBlockRefusesTheOthersInTheBlockEverythingConfig() = Compiler.FAKE_RANGES.indices.forEach { b ->
        assertRefusesOtherBlocks(kotlinx.serialization.json.Json.parseToJsonElement(Compiler.blockAll("app.unbraid", "/tmp/cache.db", b)).jsonObject, b)
    }
}
