package app.unbraid

import app.unbraid.vpn.Health
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthTest {
    private fun blame(down: Set<String>, checked: Set<String>, direct: Boolean): Set<String>? {
        var asked = false
        return runBlocking { Health.blame(down, checked) { asked = true; direct } }.also {
            // The phone's own network is only checked when every tunnel failed.
            assertEquals(down.isNotEmpty() && down == checked, asked)
        }
    }

    @Test fun everyTunnelDownWithTheNetworkDownBlamesNoServer() {
        assertNull(blame(setOf("a"), setOf("a"), direct = false))
        assertNull(blame(setOf("a", "b"), setOf("a", "b"), direct = false))
    }

    @Test fun everyTunnelDownWhileTheNetworkWorksBlamesThem() = assertEquals(setOf("a"), blame(setOf("a"), setOf("a"), direct = true))

    @Test fun somePartialOrNoFailuresNeedNoNetworkCheck() {
        assertEquals(setOf("a"), blame(setOf("a"), setOf("a", "b"), direct = false))
        assertEquals(emptySet<String>(), blame(emptySet(), setOf("a"), direct = false))
    }
}
