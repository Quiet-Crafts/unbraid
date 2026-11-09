package app.unbraid.vpn

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile as QsTile
import app.unbraid.MainActivity
import app.unbraid.vpn.VpnState.Phase
import kotlinx.coroutines.flow.combine

class Tile : LiveTile<VpnState>({ combine(UnbraidVpn.state, UnbraidVpn.asked) { s, _ -> s } }) {
    override fun render(value: VpnState) = updateTile {
        val on = running(value.phase)
        state = if (on) QsTile.STATE_ACTIVE else QsTile.STATE_INACTIVE
        // "On", "Connecting", "Off", "Paused"; a start not yet under way already reads as one.
        subtitle = if (on && value.phase != Phase.On) Phase.Connecting.name else value.phase.name
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
