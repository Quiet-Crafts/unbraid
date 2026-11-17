package app.unbraid

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

    @Test fun everyBlockRefusesTheOthersInTheBlockEverythingConfig() = Compiler.FAKE_RANGES.indices.forEach { b ->
        assertRefusesOtherBlocks(kotlinx.serialization.json.Json.parseToJsonElement(Compiler.blockAll("app.unbraid", "/tmp/cache.db", b)).jsonObject, b)
    }
}
