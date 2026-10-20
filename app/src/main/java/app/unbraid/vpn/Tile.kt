package app.unbraid.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile as QsTile
import android.service.quicksettings.TileService
import app.unbraid.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class Tile : TileService() {
    private var job: Job? = null

    override fun onStartListening() {
        job = UnbraidVpn.state.onEach { s ->
            qsTile?.apply {
                state = when (s.phase) {
                    VpnState.Phase.On -> QsTile.STATE_ACTIVE
                    VpnState.Phase.Connecting -> QsTile.STATE_ACTIVE
                    VpnState.Phase.Off, VpnState.Phase.Paused -> QsTile.STATE_INACTIVE
                }
                subtitle = when (s.phase) {
                    VpnState.Phase.On -> "On"
                    VpnState.Phase.Connecting -> "Connecting"
                    VpnState.Phase.Off -> "Off"
                    VpnState.Phase.Paused -> "Paused"
                }
                updateTile()
            }
        }.launchIn(CoroutineScope(Dispatchers.Main))
    }

    override fun onStopListening() { job?.cancel() }

    override fun onClick() = unlockAndRun(::toggle)

    // Android 13 (minSdk) only has the Intent overload; 14+ takes the PendingIntent branch above it.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun toggle() {
        when {
            UnbraidVpn.state.value.phase.let { it == VpnState.Phase.On || it == VpnState.Phase.Connecting } -> UnbraidVpn.stop(this)
            VpnService.prepare(this) == null -> UnbraidVpn.start(this)
            Build.VERSION.SDK_INT >= 34 -> startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            else -> @Suppress("DEPRECATION") startActivityAndCollapse(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
