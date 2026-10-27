package app.unbraid.vpn

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile as QsTile
import app.unbraid.MainActivity

class Tile : LiveTile<VpnState>({ UnbraidVpn.state }) {
    override fun render(value: VpnState) = updateTile {
        state = if (running(value.phase)) QsTile.STATE_ACTIVE else QsTile.STATE_INACTIVE
        subtitle = value.phase.name // "On", "Connecting", "Off", "Paused"
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
