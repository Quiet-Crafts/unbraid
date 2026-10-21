package app.unbraid

import app.unbraid.data.Location
import app.unbraid.data.OvpnProfile
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.SsProfile
import app.unbraid.data.WgPeer
import app.unbraid.stats.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsIdentityTest {
    private val nl = Route("r1", Location("nord", 153, "nl", "Netherlands"))

    @Test fun providerAndPlaceMakeANewHistory() {
        val id = Stats.idOf(nl)
        assertTrue(id.startsWith("r1@"))
        assertNotEquals(id, Stats.idOf(nl.copy(loc = nl.loc.copy(provider = "mullvad"))))
        assertNotEquals(id, Stats.idOf(nl.copy(loc = nl.loc.copy(countryCode = "de", countryId = 81))))
        assertNotEquals(id, Stats.idOf(nl.copy(loc = nl.loc.copy(cityId = 1, city = "Amsterdam"))))
        assertNotEquals(id, Stats.idOf(nl.copy(loc = nl.loc.copy(pinned = Server("nl1", "nl1.example", "1.2.3.4", "k", 0)))))
        assertNotEquals(id, Stats.idOf(nl.copy(id = "r2")))
    }

    @Test fun cosmeticsKeepTheHistory() {
        assertEquals(Stats.idOf(nl), Stats.idOf(nl.copy(hue = 5, filter = true)))
    }

    @Test fun importedServersByAddressNotSecret() {
        val wg = Route("r1", wg = WgPeer("home", "PRIVATE", listOf("10.0.0.2/32"), "PUB", null, "1.2.3.4:51820", listOf("0.0.0.0/0")))
        assertEquals(Stats.idOf(wg), Stats.idOf(wg.copy(wg = wg.wg!!.copy(privateKey = "OTHER", name = "renamed"))))
        assertNotEquals(Stats.idOf(wg), Stats.idOf(wg.copy(wg = wg.wg!!.copy(endpoint = "5.6.7.8:51820"))))
        val ov = Route("r1", ovpn = OvpnProfile("x", "client\nremote a.example 1194\n<auth-user-pass>\nremote x\nSECRET\n</auth-user-pass>", "u", "p"))
        assertEquals(Stats.idOf(ov), Stats.idOf(ov.copy(ovpn = ov.ovpn!!.copy(name = "renamed", config = "client\nremote a.example 1194\n<auth-user-pass>\nremote y\nNEW\n</auth-user-pass>", password = "q"))))
        assertNotEquals(Stats.idOf(ov), Stats.idOf(ov.copy(ovpn = ov.ovpn!!.copy(config = "client\nremote b.example 1194"))))
        assertNotEquals(Stats.idOf(ov), Stats.idOf(ov.copy(ovpn = ov.ovpn!!.copy(config = "client\nremote a.example 443"))))
        assertNotEquals(Stats.idOf(ov), Stats.idOf(ov.copy(ovpn = ov.ovpn!!.copy(config = "client\nremote a.example 1194\nproto tcp"))))
        val ss = Route("r1", ss = SsProfile("s", "9.9.9.9", 8388, "aes", "pw"))
        assertEquals(Stats.idOf(ss), Stats.idOf(ss.copy(ss = ss.ss!!.copy(password = "other"))))
        assertNotEquals(Stats.idOf(ss), Stats.idOf(Route("r1", tsExit = "100.64.0.1")))
        listOf(wg, ov, ss).forEach { r -> listOf("PRIVATE", "SECRET", "pw", "PUB").forEach { assertFalse(it in Stats.idOf(r)) } }
    }

    @Test fun olderRowsAreLegacy() {
        assertTrue(Stats.legacy("r1"))
        assertFalse(Stats.legacy(Stats.idOf(nl)))
        assertFalse(Stats.legacy(Stats.HOME))
        assertFalse(Stats.legacy("direct"))
        assertFalse(Stats.legacy("ts:100.64.0.1"))
    }
}
