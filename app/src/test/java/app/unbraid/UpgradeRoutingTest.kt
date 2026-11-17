package app.unbraid

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * After an update, every saved website goes exactly where version 3.0.2 sent it: its lookups and its connections, for
 * each setup of [UpgradeTable]. The expected lines were recorded by running the same table and probe on 3.0.2's own
 * compiler, not written by hand.
 */
class UpgradeRoutingTest {
    @Test fun savedWebsitesRouteAsIn302() {
        val before = javaClass.classLoader!!.getResource("upgrade-routing-3.0.2.txt")!!.readText().lines().filter { it.isNotBlank() }
        val now = UpgradeTable.lines()
        assertEquals(before.size, now.size)
        assertEquals(emptyList<String>(), before.zip(now).filter { (a, b) -> a != b }.map { (a, b) -> "3.0.2: $a\n  now: $b" })
    }
}
