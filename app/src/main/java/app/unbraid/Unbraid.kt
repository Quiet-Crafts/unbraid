package app.unbraid

import android.app.Application
import app.unbraid.data.Store
import app.unbraid.stats.Stats
import app.unbraid.vpn.VpnState.Phase
import kotlinx.coroutines.flow.combine
import app.unbraid.vpn.UnbraidVpn
import app.unbraid.vpn.StatusWidget
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/** The app's name, always lowercase: launcher label (@string/app_name), widget, notifications, running text. Change them together. */
const val APP = "unbraid"

/** The logo, only inside the app (Settings header, About, sign-in), its slash always in the accent: see [app.unbraid.ui.wordmarked]. */
const val WORDMARK = "un/braid"

class Unbraid : Application() {
    companion object {
        lateinit var store: Store
            private set
    }

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        app.unbraid.data.Catalog.published = store::catalogDate
        // A new process runs no engine yet (the VPN service lives in this one): what a crash or a reboot left goes now,
        // the domain table and the netmap cache older versions wrote.
        UnbraidVpn.forgetLookups(this)
        java.io.File(filesDir, "tailscale/profile-data").deleteRecursively()
        // Files other Tailscale devices sent before receiving them was turned off: the engine no longer accepts any.
        if (!java.io.File(filesDir, "box/Taildrop").deleteRecursively()) android.util.Log.w("Unbraid", "old received files not deleted")
        app.unbraid.data.Snapshot.dir = filesDir
        // The home-screen widget mirrors every connection change, and whether running protects anything.
        combine(UnbraidVpn.state.map { it.phase }, store.setup.map { it.directOnly }) { p, d -> p to d }.distinctUntilChanged().onEach { StatusWidget.update(this) }.launchIn(MainScope())
        // Statistics follow the connection while they're on: sessions from the phase, traffic from the running engine.
        Stats.init(this)
        combine(UnbraidVpn.state.map { it.phase }, store.setup.map { it.prefs.stats }) { p, on -> p to on }.distinctUntilChanged().onEach { (p, on) ->
            Stats.phase(if (on) p else Phase.Off)
            if (on && p == Phase.On) Stats.start() else Stats.stop()
        }.launchIn(MainScope())
    }
}
