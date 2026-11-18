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
    // The table lives in the cache file: without it the engine keeps the block with a table in memory, which starts
    // empty on every reload and hands out addresses apps still hold.
    val cache = c["experimental"]!!.jsonObject["cache_file"]!!.jsonObject
    assertEquals("true", cache["enabled"]?.jsonPrimitive?.content)
    assertEquals("true", cache["store_fakeip"]?.jsonPrimitive?.content)
}

class FakePoolTest {
    private val min = 60_000L

    @Test fun capacityIsWhatTheEngineHandsOutOfABlock() {
        // A /18 holds 16,384 addresses; the engine skips the network address and the one after it, and never hands out
        // the broadcast address.
        assertTrue(Compiler.FAKE_RANGES.all { it.endsWith("/18") })
        assertEquals(16_381, FakePool.CAPACITY)
    }

    /** Less than a quarter left moves: 4,095 of 16,381 is just under a quarter, 4,096 just over. */
    @Test fun lessThanAQuarterLeftIsLow() {
        assertEquals(Step.Rotate, FakePool.step(4_095, 10 * min))
        assertEquals(Step.Nothing, FakePool.step(4_096, 10 * min))
    }

    @Test fun plentyLeftOrNoEngineDoesNothing() {
        assertEquals(Step.Nothing, FakePool.step(FakePool.CAPACITY, 1_000))
        assertEquals(Step.Nothing, FakePool.step(4_096, 60 * min))
        assertEquals(Step.Nothing, FakePool.step(-1, null))
        assertEquals(Step.Nothing, FakePool.step(-1, 60 * min))
    }

    @Test fun runningLowMovesOnceTenMinutesHavePassed() {
        assertEquals(Step.Rotate, FakePool.step(4_095, 10 * min))
        assertEquals(Step.Rotate, FakePool.step(0, 10 * min + 1))
        assertEquals(Step.Rotate, FakePool.step(10, null))
    }

    @Test fun runningLowTooSoonWaitsAndSaysWhenLookupsFail() {
        assertEquals(Step.Wait, FakePool.step(4_095, 10 * min - 1))
        assertEquals(Step.Wait, FakePool.step(1, 0))
        assertEquals(Step.Exhausted, FakePool.step(0, 30_000))
    }

    /** The clock the spacing is measured on never goes back; if it did, the move must not wait for it forever. */
    @Test fun aClockThatWentBackNeverHoldsTheMoveForever() {
        assertEquals(Step.Rotate, FakePool.step(0, -1))
        assertEquals(Step.Rotate, FakePool.step(4_095, Long.MIN_VALUE))
        assertEquals(Step.Rotate, FakePool.step(4_095, Long.MAX_VALUE))
    }

    /** A low block waiting for the spacing is checked again when the spacing ends, not up to a whole interval later. */
    @Test fun theNextCheckComesWhenTheMoveIsDue() {
        assertEquals(1_000, FakePool.nextCheck(Step.Wait, 10 * min - 1_000))
        assertEquals(1_000, FakePool.nextCheck(Step.Exhausted, 10 * min - 1_000))
        assertEquals(Step.Rotate, FakePool.step(0, 10 * min - 1_000 + FakePool.nextCheck(Step.Exhausted, 10 * min - 1_000)))
        assertEquals(FakePool.EVERY, FakePool.nextCheck(Step.Wait, 0)) // far off: the usual interval
        assertEquals(FakePool.EVERY, FakePool.nextCheck(Step.Nothing, 10 * min - 1_000))
        assertEquals(FakePool.EVERY, FakePool.nextCheck(Step.Wait, null))
        assertEquals(1, FakePool.nextCheck(Step.Wait, 10 * min)) // never a sleep of 0 or less
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
     * The size a reload decides by: a missing or empty file is 0 (a new block), one whose size can't be read is neither
     * missing nor fine and stops the reload before anything is given up. A delete that fails for any reason but "no
     * such file" fails too.
     */
    @Test fun aTableSizeThatCantBeReadIsAFailure() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            assertEquals(0L, app.unbraid.vpn.LookupTable.bytes(dir))
            java.io.File(dir, "cache.db").writeText("")
            assertEquals(0L, app.unbraid.vpn.LookupTable.bytes(dir))
            assertEquals(4, Compiler.nextBlock(3, FakePool.keeps(true, app.unbraid.vpn.LookupTable.bytes(dir))))
            java.io.File(dir, "cache.db").writeText("tbl")
            assertEquals(3, Compiler.nextBlock(3, FakePool.keeps(true, app.unbraid.vpn.LookupTable.bytes(dir))))
            val notDir = java.io.File(dir, "plain").apply { writeText("x") } // its "cache.db" is neither there nor missing
            assertTrue(runCatching { app.unbraid.vpn.LookupTable.bytes(notDir) }.isFailure)
            assertTrue(runCatching { app.unbraid.vpn.LookupTable.clear(notDir) }.isFailure)
        } finally { dir.deleteRecursively() }
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
    private fun restore(last: Compiler.Input, block: Int?, spent: Int?, lockdown: Boolean = false, pending: Boolean = true, move: Boolean = false, kin: (Setup) -> Set<String> = { setOf("com.b") }) =
        FakePool.restoreInput(last, block, spent, 2 to "new", null, "new.ts.net", lockdown, pending, move, kin)

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
        assertEquals(false, FakePool.restoreInput(input(3, pending = false), null, 3, 2 to "new", null, null, false, true, false) { emptySet() }.tsLoginPending)
    }

    /**
     * A move (and its retry, on no named block) restarts what runs, changed only in its block and probe pair: a DNS
     * permission, tailnet suffix or Block setting the engine refused before the old one was given up stays refused.
     * Only what can only make it safer is read again: the sign-in way around the exit narrows, and a sibling of a
     * blocked app installed since is kept inside.
     */
    @Test fun aMoveKeepsWhatRunsChangedOnlyInItsBlock() {
        for (block in listOf(4, null)) {
            val r = restore(input(3), block, 4, lockdown = true, pending = false, move = true) { setOf("com.b", "com.b.sibling") }
            assertEquals("old.ts.net", r.setup.home.tsSuffixAllowed) // as running, not as saved
            assertEquals(setup, r.setup)
            assertEquals("old.ts.net", r.tsSuffix)
            assertFalse(r.lockdown)
            assertEquals(4, r.fakeBlock)
            assertEquals(2 to "new", r.probe)
            assertEquals(false, r.tsLoginPending)
            assertEquals(setOf("com.b", "com.b.sibling"), r.blockedKin)
            assertEquals(setOf("nl"), r.blocked)
        }
        val locked = input(3).let { Compiler.Input(it.setup, it.links, it.selfPackage, true, it.tsDir, it.cacheFile, it.tsSuffix, it.probe, 3, it.blocked, true, it.blockedKin) }
        assertTrue(restore(locked, 4, 4, lockdown = false, move = true).lockdown)
    }

    /**
     * A move, its retry and a failed change's fallback never lose a sibling of a blocked app: a lookup that fails or
     * finds fewer keeps the running set (a sibling left outside the VPN takes the blocked app's user id with it).
     */
    @Test fun aRestoreNeverLosesASiblingOfABlockedApp() {
        val last = input(3).let { Compiler.Input(it.setup, it.links, it.selfPackage, false, it.tsDir, it.cacheFile, it.tsSuffix, it.probe, 3, it.blocked, true, setOf("com.b", "com.b.sibling")) }
        for (move in listOf(true, false)) for (block in listOf(4, null)) {
            assertEquals(setOf("com.b", "com.b.sibling"), restore(last, block, 4, move = move) { emptySet() }.blockedKin)
            assertEquals(setOf("com.b", "com.b.sibling"), restore(last, block, 4, move = move) { error("package lookup failed") }.blockedKin)
            assertEquals(setOf("com.b", "com.b.sibling", "com.b.new"), restore(last, block, 4, move = move) { setOf("com.b", "com.b.new") }.blockedKin)
        }
        // Compiled: the sibling stays out of the apps Android lets bypass the VPN.
        val app = Setup(routes = setup.routes, defaultRoute = "nl", apps = mapOf("com.b" to BLOCK, "com.b.sibling" to app.unbraid.data.DIRECT), version = VERSION)
        val l = last.let { Compiler.Input(app, it.links, it.selfPackage, false, it.tsDir, it.cacheFile, it.tsSuffix, it.probe, 3, it.blocked, true, it.blockedKin) }
        assertFalse(Compiler.compile(restore(l, 4, 4, move = true) { emptySet() }).contains("\"com.b.sibling\""))
    }

    /**
     * A Headscale DNS permission refused before the old engine was given up stays refused in Android's tunnel too: the
     * search domain comes from the input being started, never from saved settings, and preparing a move reads no store.
     */
    @Test fun theTunnelSearchDomainIsTheStartingInputs() {
        val hs = Home(tailscale = true, tsControlUrl = "https://hs.example", tsSuffixAllowed = null)
        val running = Compiler.Input(setup.copy(home = hs), emptyMap(), "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", "corp.example", 1 to "old", 3, emptySet())
        val saved = hs.copy(tsSuffixAllowed = "corp.example") // the refused edit
        for (block in listOf(4, null)) {
            val r = FakePool.restoreInput(running, block, 4, 2 to "new", saved.tsSuffixAllowed, "corp.example", false, false, true) { emptySet() }
            assertEquals(null, Compiler.searchDomain(r))
        }
        assertEquals("corp.example", Compiler.searchDomain(FakePool.restoreInput(running, null, 4, 2 to "new", saved.tsSuffixAllowed, "corp.example", false, false, false) { emptySet() }))
        assertEquals(null, Compiler.searchDomain(Compiler.Input(setup.copy(home = saved.copy(tailscale = false)), emptyMap(), "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", "corp.example")))
        val vpn = java.io.File("src/main/java/app/unbraid/vpn/UnbraidVpn.kt").readText()
        val tun = vpn.substringAfter("override fun openTun(").substringBefore("override fun ")
        assertFalse("openTun reads saved settings", tun.contains("store."))
        assertTrue(tun.contains("Compiler.searchDomain(tunInput)"))
        assertEquals(2, Regex("""tunInput = input\b""").findAll(vpn).count()) // set by both starts of a compiled input
        assertTrue(vpn.contains("if (move) null else store.setup.value.home.tsSuffixAllowed, if (move) null else store.tsSuffix"))
    }

    /** A slow count (the engine's lock taken by a database write) is read before the clock: no second wait after the deadline. */
    @Test fun aSlowCountNeverAddsAWaitAfterTheDeadline() {
        var now = 590_000L
        val (step, next) = FakePool.poll({ now = 601_000L; 0 }) { now }
        assertEquals(FakePool.Step.Rotate, step)
        assertEquals(FakePool.EVERY, next)
        assertEquals(FakePool.Step.Wait to 10_000L, FakePool.poll({ 1 }) { 590_000L })
        val vpn = java.io.File("src/main/java/app/unbraid/vpn/UnbraidVpn.kt").readText()
        assertTrue(vpn.contains("FakePool.poll(Libbox::fakeIPRemaining) { blockSince?.let { SystemClock.elapsedRealtime() - it } }"))
    }

    /**
     * A restart saves a new block before anything is given up: a failed save keeps the running engine (the next check
     * tries again), and only then does the old engine go and the new one start.
     */
    private val log = mutableListOf<String>()
    private val dir = kotlin.io.path.createTempDirectory().toFile().apply { deleteOnExit() }
    private fun run(block: Int, spent: Int?, gone: Boolean = false, prepareFails: Boolean = false, saveFails: Boolean = false, startFails: Boolean = false) =
        FakePool.restart(gone, spent, dir, prepare = { log += "prepare"; if (prepareFails) error("no probe"); input(block) to "json" },
            note = { log += "save $it"; if (saveFails) error("disk full") }, commit = { log += "commit ${it.fakeBlock}" }) { _, j -> log += "start $j"; if (startFails) error("no") }

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

    /**
     * Every start on a new block (a reload, a move, a restore after a failed one) notes it down and starts from no table
     * file; the block already open keeps its file. One that can't be deleted stops the move before anything is given up.
     */
    @Test fun aNewBlockStartsFromNoTableFile() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val table = java.io.File(dir, "cache.db")
        val noted = mutableListOf<Int>()
        try {
            assertTrue(FakePool.spend(4, 3, dir) { noted += it }) // already gone is fine
            table.writeText("x")
            assertFalse(FakePool.spend(4, 4, dir) { noted += it })
            assertTrue(table.exists())
            assertTrue(FakePool.spend(5, 4, dir) { noted += it })
            assertFalse(table.exists())
            assertEquals(listOf(4, 5), noted)
            java.io.File(dir, "cache.db/inside").apply { parentFile!!.mkdirs(); writeText("x") } // a delete that fails
            assertEquals(FakePool.Outcome.Kept, FakePool.restart(false, 3, dir, prepare = { input(4) to "json" }, note = { noted += it },
                commit = { error("gave the old engine up") }) { _, _ -> error("started") })
            assertEquals("a block saved whose table wasn't deleted", listOf(4, 5), noted)
        } finally { dir.deleteRecursively() }
    }

    /** Every engine start clears what was checked through the old engine: a route that answered it proves nothing now. */
    @Test fun aRestartForgetsWhatTheOldEngineShowed() {
        val s = app.unbraid.vpn.VpnState(notAnswering = setOf("own"), passed = app.unbraid.vpn.Health.Passed(3, 1, emptyMap()), engine = 3)
        assertEquals(app.unbraid.vpn.VpnState(engine = 4), s.restarted(4))
    }

    /**
     * The service can't run in a unit test, so this checks that it takes every restart through the pieces above: each
     * new block through [FakePool.spend] (no table deleted anywhere else), each restore on the block [FakePool.restart]
     * names as spent, and each engine start through [app.unbraid.vpn.VpnState.restarted].
     */
    @Test fun theServiceRestartsOnlyThroughThesePieces() {
        val vpn = java.io.File("src/main/java/app/unbraid/vpn/UnbraidVpn.kt").readText()
        assertFalse(vpn.contains("LookupTable.clear("))
        assertTrue(vpn.contains("FakePool.spend(input.fakeBlock, lastBlock, filesDir) { store.fakeBlock = it }"))
        assertTrue(vpn.contains("FakePool.restart(gone, lastBlock, filesDir, prepare = { spent ->"))
        assertTrue(vpn.contains("FakePool.restoreInput(lastInput ?: error(\"nothing ran\"), block, spent, "))
        assertTrue(vpn.contains("tsLoginPending, move) { blockedKin(it, strict = false) }"))
        // One blocked app whose lookup fails must not cost the other blocked apps their siblings on a restore.
        assertTrue(vpn.contains("catch (e: RuntimeException) { if (strict) throw e else emptyList() }"))
        assertTrue(vpn.contains("restoreLastGood(srv, Compiler.nextBlock(last, false).takeIf { !gone }, gone, move = true)"))
        assertFalse(vpn.contains("\"cache.db\").length()"))
        assertEquals(2, Regex("""\.restarted\(starts\)""").findAll(vpn).count())
        assertFalse(Regex("""copy\(engine = starts|passed = it\.passed""").containsMatchIn(vpn))
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
        var inUse: Int? = 3 // the block the last engine that started ran on: apps may hold its addresses
        val table = java.io.File(dir, "cache.db").apply { writeText("3") } // the table file, holding the block it was started on
        var probe = 1
        var live: Int? = 1 // the probe pair the app's own tests use
        var since = 0L
        var now = 0L
        val used = mutableListOf(3) // blocks in the order tables were opened on them
        fun restoreLastGood(block: Int?, gone: Boolean, prepareFails: Boolean, saveFails: Boolean, startFails: Boolean, move: Boolean): FakePool.Outcome =
            FakePool.restart(gone, lastBlock, dir, prepare = { spent ->
                if (prepareFails) error("no probe")
                FakePool.restoreInput(lastInput, block, spent, ++probe to "p$probe", null, null, false, false, move) { emptySet() } to "json"
            }, note = { if (saveFails) error("disk full"); saved = it }, commit = { input ->
                live = null
                if (input.fakeBlock != lastBlock) { since = now; used += input.fakeBlock; assertFalse("a new block's table not deleted", table.exists()) }
                lastBlock = input.fakeBlock; lastInput = input
            }) { input, _ ->
                // A block in use restarts only on its own file: an empty table would hand its addresses out again.
                if (input.fakeBlock == inUse) assertTrue("an empty table on the block in use", table.exists())
                ran = null // libbox stops the old engine first
                if (startFails) error("no start")
                assertEquals("started on a block not saved", saved, input.fakeBlock)
                // The engine opens the table, making it on its own block when there is none.
                assertEquals("a table opened on another block's file", "${input.fakeBlock}", if (table.exists()) table.readText() else "${input.fakeBlock}")
                table.writeText("${input.fakeBlock}")
                ran = input.fakeBlock; inUse = ran; live = input.probe!!.first
            }
        repeat(300) {
            if (ran == null) return@repeat // torn down: the user connects again, which starts a new run
            now += FakePool.EVERY
            val before = Triple(ran, live, since)
            when (rnd.nextInt(3)) {
                0, 1 -> { // a pool check, as watchPool
                    val remaining = listOf(0, 1, 4_095, FakePool.CAPACITY)[rnd.nextInt(4)]
                    if (FakePool.step(remaining, now - since) == Step.Rotate) {
                        val f = { rnd.nextInt(4) == 0 }
                        when (FakePool.move { gone -> restoreLastGood(Compiler.nextBlock(lastBlock!!, false).takeIf { !gone }, gone, f(), f(), f(), true) }) {
                            FakePool.Outcome.Kept -> assertEquals("a move that wasn't started gave something up", before, Triple(ran, live, since))
                            FakePool.Outcome.Failed -> assertEquals(null, ran)
                            FakePool.Outcome.Ran -> assertTrue(ran != before.first)
                        }
                    }
                }
                else -> { // a reload, as apply: a new block unless the open table's file is there; its start fails, and
                    // the last good config comes back on the newest block
                    val block = Compiler.nextBlock(lastBlock!!, FakePool.keeps(true, app.unbraid.vpn.LookupTable.bytes(dir)))
                    if (runCatching { FakePool.spend(block, lastBlock, dir) { if (rnd.nextInt(5) == 0) error("disk full"); saved = it } }.isSuccess) {
                        if (block != lastBlock) { since = now; used += block }
                        lastBlock = block
                        ran = null; live = null
                        val r = restoreLastGood(null, true, rnd.nextInt(5) == 0, false, rnd.nextInt(3) == 0, false)
                        assertEquals("an engine runs exactly when the restore ran", r == FakePool.Outcome.Ran, ran != null)
                    } else assertEquals("a reload that wasn't started gave something up", before, Triple(ran, live, since))
                }
            }
            ran?.let { assertEquals("runs a block that isn't the newest spent", lastBlock, it) }
            assertEquals("the block in use was never saved", lastBlock, saved)
            used.windowed(Compiler.FAKE_RANGES.size).forEach { assertEquals(it.toString(), Compiler.FAKE_RANGES.size, it.toSet().size) }
        }
    }

    /**
     * Moves failing at every step, a table file that can't be deleted, and the app dying at any point, then starting
     * again on the block after the saved one: a block is saved as spent only as a start on it is about to happen with
     * no table file, so a move that gave nothing up saved nothing, and no start skips a block that never ran (each
     * block's ring position is spent once: a block comes back only after seven others ran).
     */
    @Test fun aBlockIsSavedOnlyAsItsStartIsAboutToHappen() = repeat(300) { seed ->
        class Death : Error()
        val rnd = java.util.Random(seed.toLong())
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val table = java.io.File(dir, "cache.db").apply { writeText("3") }
        var saved = 3
        var lastBlock = 3
        val runs = mutableListOf(3) // blocks started on, in order; a death once the block was saved counts as its start
        fun f() = rnd.nextInt(4) == 0
        try {
            repeat(200) {
                val die = rnd.nextInt(8) // the step the app dies at: 0 prepare, 1 before the save, 2 after it, 3 commit, 4 start
                if (f()) { table.delete(); java.io.File(table, "x").apply { parentFile!!.mkdirs(); writeText("x") } } // can't be deleted
                val before = saved
                val outcome = try {
                    FakePool.restart(false, lastBlock, dir, prepare = { if (die == 0) throw Death(); if (f()) error("no probe"); input(Compiler.nextBlock(it!!, false)) to "json" },
                        note = { if (die == 1) throw Death(); if (f()) error("disk full"); assertFalse("saved with a table file there", table.exists()); saved = it; if (die == 2) throw Death() },
                        commit = { if (die == 3) throw Death() }) { input, _ -> if (die == 4) throw Death(); runs += input.fakeBlock; lastBlock = input.fakeBlock; table.writeText("$lastBlock") }
                } catch (d: Death) {
                    if (saved != before) runs += saved // it died starting the saved block
                    // The next process: the table goes, and the block after the saved one starts.
                    table.deleteRecursively()
                    lastBlock = Compiler.nextBlock(saved, false)
                    saved = lastBlock
                    runs += lastBlock
                    table.writeText("$lastBlock")
                    null
                }
                if (outcome == FakePool.Outcome.Kept) assertEquals("a move that gave nothing up saved a block", before, saved)
                if (table.isDirectory) { table.deleteRecursively(); table.writeText("$lastBlock") } // the running engine's own
            }
            runs.zipWithNext().forEach { (a, b) -> assertTrue("$runs: $b after $a", b == a || b == Compiler.nextBlock(a, false)) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun everyBlockRefusesTheOthersInTheBlockEverythingConfig() = Compiler.FAKE_RANGES.indices.forEach { b ->
        assertRefusesOtherBlocks(kotlinx.serialization.json.Json.parseToJsonElement(Compiler.blockAll("app.unbraid", "/tmp/cache.db", b)).jsonObject, b)
    }
}
