package app.unbraid.vpn

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile as QsTile
import app.unbraid.MainActivity
import app.unbraid.vpn.VpnState.Phase
import kotlinx.coroutines.flow.combine

class Tile : LiveTile<VpnState>({ combine(UnbraidVpn.state, UnbraidVpn.asked) { s, _ -> s } }) {
    override fun render(value: VpnState) = updateTile {
        state = if (running(value.phase)) QsTile.STATE_ACTIVE else QsTile.STATE_INACTIVE
        subtitle = tileSubtitle(value.phase, UnbraidVpn.asked.value)
    }

    override fun onClick() = unlockAndRun(::toggle)

    private fun toggle() {
        when {
            running() -> UnbraidVpn.stop(this)
            VpnService.prepare(this) == null -> UnbraidVpn.start(this)
            else -> openApp(Intent(this, MainActivity::class.java))
        }
    }
}

/**
 * "On", "Connecting", "Off", "Paused", agreeing with the tile's on or off look ([vpnOn]): a start not yet under way
 * already reads as connecting, a stop or pause not yet done as disconnecting.
 */
fun tileSubtitle(phase: Phase, ask: Ask?): String = when {
    stopping(phase, ask) -> "Disconnecting"
    vpnOn(phase, ask) && phase != Phase.On -> Phase.Connecting.name
    else -> phase.name
}
