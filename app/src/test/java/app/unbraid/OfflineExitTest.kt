package app.unbraid

import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.ui.offlineExit
import app.unbraid.vpn.TsNode
import app.unbraid.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A route through a Tailscale exit device says so when that device is offline, and only then. */
class OfflineExitTest {
    private val ip = "100.64.0.9"
    private val route = Route("ts", tsExit = ip)
    private fun vpn(online: Boolean, state: String = "Running", host: String = ip) = VpnState(
        tsState = state, servers = mapOf("ts" to Server("home-mac", host, host, "", 0)), tsExitNodes = listOf(TsNode("home-mac", ip, online)),
    )

    @Test fun anOfflineDeviceIsNamed() = assertEquals("home-mac", offlineExit(vpn(online = false), route)?.name)

    @Test fun onlyWhenItIsReallyOffline() {
        assertNull(offlineExit(vpn(online = true), route))
        // Tailscale not running yet (signing in, starting): it knows nothing about the device, so nothing is claimed.
        assertNull(offlineExit(vpn(online = false, state = "Starting"), route))
        // The route doesn't use that device now (it was just changed and not applied yet).
        assertNull(offlineExit(vpn(online = false, host = "100.64.0.10"), route))
        // A route of a VPN provider, or none.
        assertNull(offlineExit(vpn(online = false), Route("it")))
        assertNull(offlineExit(vpn(online = false), null))
    }
}
