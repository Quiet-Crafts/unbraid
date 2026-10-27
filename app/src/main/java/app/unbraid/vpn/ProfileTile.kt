package app.unbraid.vpn

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile as QsTile
import android.service.quicksettings.TileService
import app.unbraid.MainActivity
import app.unbraid.R
import app.unbraid.Unbraid
import app.unbraid.data.Limits
import app.unbraid.data.Providers
import app.unbraid.data.Setup
import app.unbraid.vpn.VpnState.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** For a profile switch to the setup hashed [h]: true once the engine runs it (or runs nothing), false if it refused it, null meanwhile. */
fun switchDone(v: VpnState, h: Int) = when { v.phase != Phase.On || v.applied == h -> true; v.refused == h -> false; else -> null }

/** Profile switches, from the app, a tile or a shortcut alike; and the launcher shortcuts and quick-settings tiles of the first three. */
object Profiles {
    const val SLOTS = 3
    const val ACTION = "app.unbraid.PROFILE"

    /** A switch to profile [to] the engine refused; [kept] is the profile still in use. */
    class Refused(val to: String, val kept: String?)
    /** The profile being switched to, until the engine runs it. */
    val switching = MutableStateFlow<String?>(null)
    val refused = MutableStateFlow<Refused?>(null)

    /** A switch to [to] was saved: followed while the engine runs. */
    fun started(to: String) {
        refused.value = null
        if (UnbraidVpn.state.value.phase == Phase.On) switching.value = to
    }

    /** Done once the engine runs the saved setup; refused, the setup it still runs comes back into the app. */
    fun watch(scope: CoroutineScope) = combine(UnbraidVpn.state, Unbraid.store.setup, switching) { v, s, to -> Triple(v, s, to) }.onEach { (v, s, to) ->
        to ?: return@onEach
        val done = switchDone(v, s.hashCode()) ?: return@onEach
        switching.value = null
        if (done) return@onEach
        UnbraidVpn.running?.let { ran -> Unbraid.store.update { it.back(ran) } }
        refused.value = Refused(to, Unbraid.store.setup.value.profile)
    }.launchIn(scope)

    /** Opens the app on Routes with profile [name] shown; Use there switches. */
    fun intent(ctx: Context, name: String) = Intent(ctx, MainActivity::class.java).setAction(ACTION).putExtra("name", name)

    /** Brings the shortcuts and the tiles in line with the saved profiles. */
    fun refresh(ctx: Context) {
        val s = Unbraid.store.setup.value
        // Keyed by profile, not by place: a pinned shortcut never comes to open another profile after a reorder.
        val shortcuts = s.profiles.take(SLOTS).mapIndexed { i, p ->
            ShortcutInfo.Builder(ctx, "profile:${p.name}").setShortLabel(p.name).setLongLabel("Open ${p.name}")
                .setIcon(Icon.createWithResource(ctx, R.drawable.ic_profile)).setIntent(intent(ctx, p.name)).setRank(i).build()
        }
        runCatching {
            val m = ctx.getSystemService(ShortcutManager::class.java)
            m.enableShortcuts(shortcuts.map { it.id }) // one pinned before, disabled while it wasn't among the first three
            m.setDynamicShortcuts(shortcuts)
            // A pinned shortcut of a profile now gone (deleted, renamed or not among the first three) stops working.
            val gone = m.pinnedShortcuts.map { it.id }.filter { it.startsWith("profile:") && shortcuts.none { sc -> sc.id == it } }
            if (gone.isNotEmpty()) m.disableShortcuts(gone, "This profile isn't among the first three any more")
        }
        listOf(ProfileTile1::class.java, ProfileTile2::class.java, ProfileTile3::class.java).forEach {
            TileService.requestListeningState(ctx, ComponentName(ctx, it))
        }
    }
}

/** A quick-settings tile that redraws on every value of [changes] while it is shown, and can open the app. */
abstract class LiveTile<T>(private val changes: () -> Flow<T>) : TileService() {
    private var job: Job? = null

    protected abstract fun render(value: T)
    protected fun updateTile(render: QsTile.() -> Unit) { qsTile?.apply { render(); updateTile() } }
    protected fun running(phase: Phase = UnbraidVpn.state.value.phase) = phase == Phase.On || phase == Phase.Connecting

    override fun onStartListening() {
        job = changes().onEach(::render).launchIn(CoroutineScope(Dispatchers.Main))
    }

    override fun onStopListening() { job?.cancel() }

    // Android 13 (minSdk) only has the Intent overload; 14+ takes the PendingIntent.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    protected fun openApp(open: Intent, code: Int = 0, flags: Int = PendingIntent.FLAG_IMMUTABLE) {
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, code, open, flags))
        else @Suppress("DEPRECATION") startActivityAndCollapse(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** A tile for the profile in [slot]: shows whether it is in use, switches to it (connecting if needed), or pauses when it is. */
abstract class ProfileTile(private val slot: Int) : LiveTile<Setup>({ combine(UnbraidVpn.state, Unbraid.store.setup) { _, s -> s } }) {
    override fun render(value: Setup) {
        val p = value.profiles.getOrNull(slot)
        val lit = p != null && value.profile == p.name && running()
        updateTile {
            label = p?.name ?: "Profile ${slot + 1}"
            state = if (p == null) QsTile.STATE_UNAVAILABLE else if (lit) QsTile.STATE_ACTIVE else QsTile.STATE_INACTIVE
            subtitle = if (lit) "In use" else null
        }
    }

    override fun onClick() = unlockAndRun(::switch)

    private fun switch() {
        val old = Unbraid.store.setup.value
        val profile = old.profiles.getOrNull(slot) ?: return
        val name = profile.name
        if (old.profile == name && running()) { UnbraidVpn.pause(this, 15); return }
        // Over a provider's connection limit the app asks first, and Android's VPN consent needs the app too.
        val acc = Unbraid.store.accounts.value
        val ask = Limits.over(old, old.use(name)) { p -> acc[p]?.let { a -> Providers.get(p)?.limit(a) } } != null
        when {
            // An empty profile has no default route yet: the app shows it, to be given one.
            ask || !profile.ready || VpnService.prepare(this) != null -> openApp(Profiles.intent(this, name), slot, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            else -> {
                Unbraid.store.update { it.use(name) }
                Profiles.started(name)
                if (!running()) UnbraidVpn.start(this)
            }
        }
    }
}

class ProfileTile1 : ProfileTile(0)
class ProfileTile2 : ProfileTile(1)
class ProfileTile3 : ProfileTile(2)
