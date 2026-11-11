package app.unbraid

import android.app.Application
import app.unbraid.data.Store
import app.unbraid.stats.Stats
import app.unbraid.vpn.VpnState.Phase
import kotlinx.coroutines.flow.combine
import app.unbraid.vpn.UnbraidVpn
import app.unbraid.vpn.Profiles
import app.unbraid.vpn.StatusWidget
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import app.unbraid.support.NudgeState
import app.unbraid.support.Nudges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** The app's name, always lowercase: launcher label (@string/app_name), widget, notifications, running text. Change them together. */
const val APP = "unbraid"

/** The logo, only inside the app (Settings header, About, sign-in), its slash always in the accent: see [app.unbraid.ui.wordmarked]. */
const val WORDMARK = "un/braid"

class Unbraid : Application() {
    companion object {
        lateinit var store: Store
            private set
        /** The support card's rules state; null until read from disk. */
        val nudges = MutableStateFlow<NudgeState?>(null)
        private val nudgeLock = Any()

        /** Changes the nudge state and saves it (disk: call off the main thread). Nothing happens before it was read. */
        fun nudge(f: (NudgeState) -> NudgeState) = synchronized(nudgeLock) {
            nudges.value?.let { f(it).also { n -> nudges.value = n; store.nudges = n } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        app.unbraid.data.Catalog.published = store::catalogDate
        app.unbraid.data.Providers.opt = { p, k -> store.setup.value.opt(p, k) }
        // A new process runs no engine yet (the VPN service lives in this one): what a crash or a reboot left goes now,
        // the domain table and the netmap cache older versions wrote.
        UnbraidVpn.forgetLookups(this)
        java.io.File(filesDir, "tailscale/profile-data").deleteRecursively()
        // Files other Tailscale devices sent before receiving them was turned off: the engine no longer accepts any.
        if (!java.io.File(filesDir, "box/Taildrop").deleteRecursively()) android.util.Log.w("Unbraid", "old received files not deleted")
        app.unbraid.data.Snapshot.dir = filesDir
        val scope = MainScope()
        // The home-screen widget mirrors every connection change, and whether running protects anything.
        combine(UnbraidVpn.state.map { it.phase }, store.setup.map { it.directOnly }) { p, d -> p to d }.distinctUntilChanged().onEach { StatusWidget.update(this) }.launchIn(scope)
        // The launcher shortcuts and quick-settings tiles follow the first three profiles and the one in use.
        // Not while the saved settings won't open (defaults would remove the user's shortcuts); again once they do.
        combine(store.setup.map { s -> s.profiles.take(Profiles.SLOTS).map { it.name } to s.profile }, store.unreadable) { p, u -> p to u }
            .distinctUntilChanged().onEach { (_, u) -> if (!u) Profiles.refresh(this) }.launchIn(scope)
        Profiles.watch(scope)
        // Statistics follow the connection while they're on: sessions from the phase, traffic from the running engine.
        Stats.init(this)
        combine(UnbraidVpn.state.map { it.phase }, store.setup.map { it.prefs.stats }) { p, on -> p to on }.distinctUntilChanged().onEach { (p, on) ->
            Stats.phase(if (on) p else Phase.Off)
            if (on && p == Phase.On) Stats.start() else Stats.stop()
        }.launchIn(scope)
        // Days the VPN was connected, for the support card: read once, then noted while connected, also over midnight.
        scope.launch(Dispatchers.IO) {
            val first = runCatching { packageManager.getPackageInfo(packageName, 0).firstInstallTime }.getOrDefault(System.currentTimeMillis())
            synchronized(nudgeLock) { nudges.value = store.nudges.let { if (it.installedAtMs == 0L) it.copy(installedAtMs = first).also { n -> store.nudges = n } else it } }
            UnbraidVpn.state.map { it.phase == Phase.On }.distinctUntilChanged().collectLatest { on ->
                while (on && (nudges.value?.connectedDays?.size ?: 0) < 3) {
                    nudge { Nudges.connectedDay(it, LocalDate.now().toEpochDay()) }
                    delay(ChronoUnit.MILLIS.between(LocalDateTime.now(), LocalDate.now().plusDays(1).atStartOfDay()) + 1_000)
                }
            }
        }
    }
}
