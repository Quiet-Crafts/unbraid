package app.unbraid

import app.unbraid.stats.SpeedTest
import app.unbraid.vpn.TsNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedTestTest {
    private val nodes = listOf(TsNode("nas", "100.64.0.5", true, "nStable1"), TsNode("pi", "100.64.0.6", true, "nStable2"))

    /** After testing another node, Tailscale goes back to the node it used, else the routed one, else to no exit at all. */
    @Test fun theExitNodeComesBackOrNoneIsUsed() {
        assertEquals("", SpeedTest.restoreTo(null, "100.64.0.5\nnStable1", nodes)) // no route on Tailscale's exit any more
        // The route's setting now, by address, ID or a unique name.
        assertEquals("nStable2", SpeedTest.restoreTo("100.64.0.6", "", nodes))
        assertEquals("nStable1", SpeedTest.restoreTo("NAS", null, nodes))
        assertEquals("nStable2", SpeedTest.restoreTo("pi.tail1234.ts.net.", "", nodes))
        // The route changed during the test: the new setting wins over the note from the start.
        assertEquals("nStable2", SpeedTest.restoreTo("100.64.0.6", "100.64.0.5\nnStable1", nodes))
        // The node isn't listed now: the note helps only for the same setting.
        assertEquals("nStable1", SpeedTest.restoreTo("nas", "nas\nnStable1", emptyList()))
        assertNull(SpeedTest.restoreTo("nas", "pi\nnStable2", emptyList()))
        // A name two nodes share is no match; not known at all: no exit node meanwhile (null), never the tested one.
        assertNull(SpeedTest.restoreTo("nas", "", nodes + TsNode("nas", "100.64.0.9", true, "nStable3")))
        assertNull(SpeedTest.restoreTo("nas", "nas\nnStable1", nodes + TsNode("nas", "100.64.0.9", true, "nStable3")))
        assertNull(SpeedTest.restoreTo("gone", "", nodes))
    }
}
