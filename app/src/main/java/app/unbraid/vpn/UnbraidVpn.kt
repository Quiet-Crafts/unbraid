package app.unbraid.vpn

import app.unbraid.APP
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Process
import android.provider.Settings
import android.util.Log
import app.unbraid.MainActivity
import app.unbraid.R
import app.unbraid.Unbraid
import app.unbraid.data.Socks5Profile
import app.unbraid.data.AppIdentity
import app.unbraid.data.permitsApp
import app.unbraid.data.InstallState
import app.unbraid.data.deadline
import app.unbraid.data.waitAdmits
import app.unbraid.data.resolveWaiting
import app.unbraid.data.checkedApps
import app.unbraid.data.AuthException
import app.unbraid.data.Link
import app.unbraid.data.Providers
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.stats.SpeedTest
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TailscaleStatusHandler
import io.nekohasekai.libbox.TailscaleStatusSubscription
import io.nekohasekai.libbox.TailscaleStatusUpdate
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

/** A Tailscale exit node; [inUse]: Tailscale reports it as the exit it sends through now. */
data class TsNode(val name: String, val ip: String, val online: Boolean, val id: String = "", val inUse: Boolean = false)

/** What the UI shows. Lives in-process; the service is the only writer. */
data class VpnState(
    val phase: Phase = Phase.Off,
    val since: Long = 0,
    val servers: Map<String, Server> = emptyMap(),
    val error: String? = null,
    val tsLoginUrl: String? = null,
    val tsState: String? = null,
    /** The tailnet's DNS suffix the running config was compiled with: what the lists and widgets judge websites by. */
    val tsSuffix: String? = null,
    /** The DNS suffix the tailnet reports now (usable form); whether apps may use it is [app.unbraid.data.Home.tsSuffixAllowed]. */
    val tsAnnounced: String? = null,
    /** Tailnet peers that offer themselves as exit nodes. */
    val tsExitNodes: List<TsNode> = emptyList(),
    /** While paused: when the connection comes back (epoch ms), 0 = only when resumed by hand. */
    val pausedUntil: Long = 0,
    /** While paused: when the pause began (epoch ms), for the countdown ring. */
    val pausedAt: Long = 0,
    /** While paused: Android may resume it somewhat after [pausedUntil] (no exact alarms). */
    val pausedAbout: Boolean = false,
    /** Android's "Block connections without VPN": apps are offline while paused. */
    val lockdown: Boolean = false,
    /** Android's "Always-on VPN" for this app; both are only known while the service runs. */
    val alwaysOn: Boolean = false,
    /** Providers whose routes have no tunnel right now: provider id → whether it refused the account (the user must sign in again). */
    val stuck: Map<String, Boolean> = emptyMap(),
    /** Routes whose tunnel keeps dropping ([app.unbraid.vpn.Health.dropping]). */
    val dropping: Set<String> = emptySet(),
    /** Provider routes moved to other servers again and again that still don't work: not moved again on this network ([Health.doesntWork]). */
    val stalled: Set<String> = emptySet(),
    /** Own-server routes whose internet check failed; never moved. */
    val notAnswering: Set<String> = emptySet(),
    /** Routes whose latest check answered; they count as working only on the engine and network named ([Health.Passed]). */
    val passed: Health.Passed? = null,
    /** Counts engine starts, each of which restarts every tunnel: what was observed of an earlier one no longer holds. */
    val engine: Int = 0,
    /** [Setup.hashCode] of the setup the engine runs in full (no route still waiting for a server): a profile switch is done. */
    val applied: Int = 0,
    /** [Setup.hashCode] of the last setup the engine refused (the one before it still runs): a profile switch is undone. */
    val refused: Int = 0,
) {
    enum class Phase { Off, Connecting, On, Paused }

    /** After every engine start, numbered [engine]: nothing checked through the old engine holds for this one. */
    fun restarted(engine: Int) = copy(engine = engine, passed = null, notAnswering = emptySet())
}

/** An app gets its own DNS allocation budget only when its OS owner is unambiguous. */
internal fun individualDnsOwner(isApp: Boolean, uid: Int, serviceUid: Int, names: List<String>, routingNames: List<String>): Boolean =
    isApp && uid != serviceUid && names.size == 1 && names[0].isNotEmpty() && routingNames == names

class UnbraidVpn : VpnService(), PlatformInterface, CommandServerHandler {
    companion object {
        val state = MutableStateFlow(VpnState())
        /** Runs [block] under the running service's lifecycle lock, so no engine starts meanwhile; null when none runs. Blocks: never on the main thread nor under that lock. */
        fun <T> underLock(block: () -> T): T? = kotlinx.coroutines.runBlocking { lock.withLock { current?.takeIf { !it.destroyed }?.let { block() } } }
        const val RESTORE_REFUSED = "The restored setup couldn't start. Check your routes."
        /** Route id → engine tunnel tag of the running setup (speed tests dial through it). */
        @Volatile var tags: Map<String, String> = emptyMap()
        /** Tunnel tag → the DNS server that tunnel's own lookups use (health checks look their servers up there). */
        @Volatile var tunnelDns: Map<String, String> = emptyMap()
        /** Tunnel tags that carry TCP only ([Compiler.tcpOnly]): health checks send no UDP through them. */
        @Volatile var tcpOnly: Set<String> = emptySet()
        /** The setup the engine runs (not the one being edited): stats attribute traffic against it. */
        @Volatile var running: Setup? = null
        /** Loopback port and password of the app's own way into each exit ([Compiler.PROBE]); see [Probe]. */
        @Volatile var probe: Pair<Int, String>? = null
        /** The phone's own network (under the VPN) is metered: mobile data, or Wi-Fi marked so. */
        @Volatile var metered = false
        /** Servers ("provider|ip") that failed the last health check: the next connect, even a manual one, avoids them. */
        @Volatile var failing: Set<String> = emptySet()
        /**
         * The engine's cache: fake IP → domain, kept so connections survive a reload. It names every site the phone
         * looked up, so it never outlives a connection: deleted on disconnect, pause, connect and app start (for a killed
         * run). Each new table hands out fake IPs afresh, from the next block of the pool ([Compiler.FAKE_RANGES]).
         * Never deleted under a running engine: its next reload would start an empty table in the same block.
         */
        fun forgetLookups(ctx: Context) = LookupTable.forget(ctx.filesDir)
        /** The fake-IP block of the open or last table; the store only carries it across process restarts. */
        private var lastBlock: Int? = null
        /** When [lastBlock] last changed ([SystemClock.elapsedRealtime]): a start or a move ([FakePool]). */
        private var blockSince: Long? = null
        private fun openTable(open: Boolean) = LookupTable.open(open)
        private const val ACTION_STOP = "app.unbraid.STOP"
        private const val ACTION_PAUSE = "app.unbraid.PAUSE"
        private const val ACTION_FLAGS = "app.unbraid.FLAGS"
        private const val EXTRA_MINUTES = "minutes"
        /** On the timed resume: which pause it ends ([pauses]). */
        private const val EXTRA_PAUSE = "pause"
        /** Pauses in this process; a timed resume for an older one, or after a disconnect, does nothing. */
        @Volatile private var pauses = 0
        private const val CHANNEL = "vpn"
        /** The foreground notice while [hold] keeps apps offline. */
        private const val UNREADABLE = "$APP can't open your settings, so apps are offline. Open $APP."
        private const val TAG = "Unbraid"

        /** Starting a foreground service that can't go foreground crashes the app, so consent is checked first. */
        fun start(ctx: Context) {
            if (prepare(ctx) != null) {
                // Paused while another VPN app took over: the paused service ends itself on this start (alarm, notification).
                if (state.value.phase == VpnState.Phase.Paused) runCatching { ctx.startService(Intent(ctx, UnbraidVpn::class.java)) }
                state.value = VpnState(error = "Open $APP to allow it as your VPN")
                // From the widget nothing else would show: say so, and redraw it so its button opens the app.
                alert(ctx, "Open $APP to allow it as your VPN")
                Widgets.update(ctx)
                return
            }
            ask(true) { ctx.startForegroundService(Intent(ctx, UnbraidVpn::class.java).putExtra(EXTRA_ASK, it)) }
        }
        /** "unbraid is off" with [text]; a tap opens the app. */
        private fun alert(ctx: Context, text: String) = problem(ctx, text, "$APP is off", 2)

        /** A notice on the Problems channel: [text] under [title]; a tap opens the app. */
        fun problem(ctx: Context, text: String, title: String = "Profile not switched", id: Int = 3) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("alerts", "Problems", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(id, Notification.Builder(ctx, "alerts").setSmallIcon(R.drawable.ic_tile).setContentTitle(title)
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build())
        }
        /**
         * Checks for a new release (at most once a day, only with checks on) and shows a notification for one not
         * announced yet; a tap opens the download page. Runs while connected, so a new version is seen also when the app
         * isn't opened.
         */
        suspend fun announceUpdate(ctx: Context, store: app.unbraid.data.Store) {
            if (!app.unbraid.BuildConfig.UPDATE_CHECK) return
            // Nothing is asked before the terms are accepted, as in the app.
            app.unbraid.ui.Onboarding.init(ctx)
            if (!app.unbraid.ui.Onboarding.termsOk) return
            val code = app.unbraid.BuildConfig.VERSION_CODE
            val r = app.unbraid.data.Updates.toAnnounce(app.unbraid.data.Updates.check(store, code), code) ?: return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("updates", "Updates", NotificationManager.IMPORTANCE_DEFAULT))
            // Not marked as told while notifications or this channel are off: a later round tries again.
            if (!nm.areNotificationsEnabled() || nm.getNotificationChannel("updates")?.importance == NotificationManager.IMPORTANCE_NONE) return
            val open = PendingIntent.getActivity(ctx, 0, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(app.unbraid.data.Updates.DOWNLOAD)), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(5, Notification.Builder(ctx, "updates").setSmallIcon(R.drawable.ic_tile).setContentTitle("$APP ${r.versionName} is out")
                .setContentText("Tap to download it.").setContentIntent(open).setAutoCancel(true).build())
            synchronized(store) { store.updates = app.unbraid.data.Updates.notified(store.updates, r) }
        }
        /** The start after the app was replaced ([AfterUpdate]): if it fails, the notice says so and offers a reconnect. */
        fun startAfterUpdate(ctx: Context) = ask(true) { ctx.startForegroundService(Intent(ctx, UnbraidVpn::class.java).putExtra(EXTRA_ASK, it).putExtra(EXTRA_UPDATED, true)) }
        private const val EXTRA_UPDATED = "updated"
        fun stop(ctx: Context) = ask(false) { ctx.startService(Intent(ctx, UnbraidVpn::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ASK, it)) }
        /** Drops the tunnel for [minutes] (0 = until resumed); a start while paused resumes. */
        /** Reads Android's Always-on and Block-connections settings again (they can change while connected). Only while running. */
        fun refreshFlags(ctx: Context) { if (state.value.phase == VpnState.Phase.On || state.value.phase == VpnState.Phase.Paused) runCatching { ctx.startService(Intent(ctx, UnbraidVpn::class.java).setAction(ACTION_FLAGS)) } }

        fun pause(ctx: Context, minutes: Int) = ask(false) { ctx.startService(Intent(ctx, UnbraidVpn::class.java).setAction(ACTION_PAUSE).putExtra(EXTRA_MINUTES, minutes).putExtra(EXTRA_ASK, it)) }

        /** The newest start, stop or pause the service hasn't handled yet; see [vpnOn]. */
        val asked = MutableStateFlow<Ask?>(null)
        private val asks = java.util.concurrent.atomic.AtomicInteger()
        private const val EXTRA_ASK = "ask"
        private fun ask(on: Boolean, send: (Int) -> Unit) {
            val a = Ask(asks.incrementAndGet(), on)
            asked.value = a
            try { send(a.n) } catch (e: RuntimeException) { asked.compareAndSet(a, null); throw e }
        }
        /** The service handled request [n] (or turned it away), and with it every earlier one. */
        private fun handled(intent: Intent?) = intent?.getIntExtra(EXTRA_ASK, 0)?.takeIf { it != 0 }?.let { n -> asked.update { a -> a?.takeIf { it.n > n } } }

        private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private var engineReady = false

        /**
         * Counts engine starts in this process, across service instances: each restarts every tunnel, so health checks
         * and exit checks about an earlier one no longer hold ([VpnState.engine]).
         */
        @Volatile private var starts = 0

        /** Serializes connect, reload, pause and teardown, across service instances too (an old one may still be stopping). */
        private val lock = Mutex()
        /** The live service instance, whose waiting commands a reset must not run ahead of. */
        @Volatile private var current: UnbraidVpn? = null
        /** The last destroyed instance's cleanup: a new instance handles nothing before it is done. */
        @Volatile private var cleanup: Job? = null

        /** Runs [f] only while the VPN is off with no command waiting, holding the lifecycle lock so none can start meanwhile. */
        suspend fun <T> whileStopped(f: () -> T): T? = lock.withLock {
            if (state.value.phase == VpnState.Phase.Off && (current?.waiting?.get() ?: 0) == 0) f() else null
        }

        /**
         * Loads the engine (a 60 MB native library and the Go runtime) once, here rather than at app start: the UI,
         * the widget and the tile never need it, only a running tunnel does.
         */
        @Synchronized
        private fun engineSetup(ctx: Context) {
            if (engineReady) return
            Libbox.setup(io.nekohasekai.libbox.SetupOptions().apply {
                basePath = ctx.filesDir.path
                workingPath = File(ctx.filesDir, "box").apply { mkdirs() }.path
                tempPath = ctx.cacheDir.path
                // The engine keeps its last log lines in memory before any redaction. The app never
                // reads them, so release builds keep none; debug builds keep 500 for diagnosis.
                logMaxLines = if (app.unbraid.BuildConfig.DEBUG) 500 else 0
                debug = app.unbraid.BuildConfig.DEBUG
                appVersion = app.unbraid.BuildConfig.VERSION_CODE.toString()
                appMarketingVersion = app.unbraid.BuildConfig.VERSION_NAME
            })
            engineReady = true
        }

        /** Logs this device out of its tailnet, which needs the engine running. False if it isn't, or the control server didn't confirm. */
        suspend fun tailscaleLogout(): Boolean {
            if (state.value.phase != VpnState.Phase.On) return false
            val call = bg.async { runCatching { Libbox.newStandaloneCommandClient().tailscaleLogout(Compiler.TS_TAG) }.isSuccess }
            return withTimeoutOrNull(8_000) { call.await() } == true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Start commands in arrival order, each with whether the phone was locked when it came. */
    private val inbox = kotlinx.coroutines.channels.Channel<Pair<Intent?, Boolean>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    /** Commands not yet handled; main thread only. The service stops only when none is left ([stopIfIdle]). */
    private var pending = 0
    /** Commands not yet started (they wait for [lock]); see [whileStopped]. */
    private val waiting = java.util.concurrent.atomic.AtomicInteger()
    /** The newest start command: stopping with it never stops a service Android has already handed a later one. */
    private var lastStartId = 0
    /** The last thing handled ended the connection: the service may go once no command is pending. */
    @Volatile private var stopWanted = false
    @Volatile private var destroyed = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val store get() = Unbraid.store
    private lateinit var cm: ConnectivityManager
    private lateinit var net: NetMonitor
    private var server: CommandServer? = null
    private var tun: ParcelFileDescriptor? = null
    private var watch: Job? = null
    private var ts: TailscaleStatusSubscription? = null
    private var resume: Job? = null

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(ConnectivityManager::class.java)
        net = NetMonitor(cm)
        current = this
        registerReceiver(installed, android.content.IntentFilter().apply { addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addDataScheme("package") }, RECEIVER_NOT_EXPORTED)
        scope.launch {
            // Off the main thread: loading the engine takes long enough to freeze the connect animation. Every command
            // that needs it runs below, after it.
            engineSetup(this@UnbraidVpn)
            cleanup?.join()
            for ((intent, locked) in inbox) {
                lock.withLock { waiting.decrementAndGet(); handle(intent, locked) }
                handled(intent)
                kotlinx.coroutines.withContext(Dispatchers.Main) { pending--; stopIfIdle() }
            }
        }
    }

    /**
     * An app installed can change what Android leaves out of the VPN, which Android decides by user id when the tunnel is
     * built: one with a choice of its own saved (a Direct one installed again, maybe with a new user id; an app a restore
     * waits for), or one in a user id with other apps (it goes as the user id is left out or not). Each such install is
     * counted ([installs]); a tunnel built before the latest count ([builtInstalls]) is built again, at once, also when the
     * install came while connecting. Counted, not flagged, so no install is ever lost to another.
     */
    private val installed = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (destroyed) return
            val pkg = intent.data?.schemeSpecificPart ?: return
            val uid = intent.getIntExtra(Intent.EXTRA_UID, -1)
            val added = intent.action == Intent.ACTION_PACKAGE_ADDED
            if (!added && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return // recheck the completed update
            scope.launch {
                // Removed: the apps left in its user id may now go otherwise (a Direct one no longer kept inside by it).
                val others = runCatching { packageManager.getPackagesForUid(uid)?.size ?: 0 }.getOrDefault(-1)
                val shared = uid < 0 || others < 0 || if (added) others > 1 else others > 0
                val setup = store.setup.value
                if (pkg in setup.waiting) store.refreshWaiting(System.currentTimeMillis(), ::installation)
                if (added && (pkg in setup.appSigners || setup.profiles.any { pkg in it.appSigners })) store.refreshAppSigners(::identity)
                if (!shared && pkg !in setup.apps && pkg !in tunInput?.setup?.apps.orEmpty() && pkg !in knownIds.keys) return@launch
                installs.incrementAndGet()
                if (state.value.phase == VpnState.Phase.On) reload()
            }
        }
    }
    private val installs = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var retriedInstalls = -1L
    private var installRetry: Job? = null
    /** [installs] as counted when the running tunnel's setup was read; -1 when none runs. */
    @Volatile private var builtInstalls = -1L

    private fun stopIfIdle() {
        if (pending > 0 || !stopWanted || destroyed) return
        if (stopSelfResult(lastStartId)) { stopWanted = false; stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    /** The newest request ([Ask.n]) Android handed this instance. */
    private var delivered = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        delivered = maxOf(delivered, intent?.getIntExtra(EXTRA_ASK, 0) ?: 0)
        // Disconnecting and pausing drop protection: never from a locked phone, whoever holds the PendingIntent
        // (a notification listener, the widget host). The in-app controls are only reachable unlocked anyway.
        lastStartId = startId
        val locked = getSystemService(KeyguardManager::class.java).isDeviceLocked
        // A timed resume reaching a paused service keeps the paused notice: the pause may be a newer one it doesn't end.
        val timedWhilePaused = (intent?.getIntExtra(EXTRA_PAUSE, 0) ?: 0) != 0 && state.value.phase == VpnState.Phase.Paused
        // Android's own start (Always-on) names the VPN interface; it must go foreground like the app's.
        if ((intent?.action == null || intent.action == VpnService.SERVICE_INTERFACE) && !timedWhilePaused) {
            // Another VPN app may have taken the slot since: Android then refuses this service type. Never crash on it.
            // Tried even without consent: a start Android was promised a foreground service for must call it before the
            // service stops, or Android ends the app. It succeeds then on 13 (no type); from 14 the type needs consent.
            val consent = prepare(this) == null
            val allowed = runCatching {
                // The systemExempted type exists from Android 14; on 13 a foreground service needs no type.
                // Android's own start reaching a pause keeps the paused notice: the pause goes on ([handle]).
                val first = if (state.value.phase == VpnState.Phase.Paused && intent?.action == VpnService.SERVICE_INTERFACE) pausedNotice(state.value) else notification("Connecting…")
                if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(1, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
                else startForeground(1, first)
            }.isSuccess && consent
            // Refused while off or paused, so no engine runs: the service goes, once no earlier command is still waiting.
            if (!allowed && (state.value.phase == VpnState.Phase.Off || state.value.phase == VpnState.Phase.Paused)) {
                cancelResume()
                state.value = VpnState(error = "Open $APP to allow it as your VPN")
                AfterUpdate.refused(intent?.getBooleanExtra(EXTRA_UPDATED, false) == true, (intent?.getIntExtra(EXTRA_PAUSE, 0) ?: 0) != 0)?.let { AfterUpdate.tell(this, it) }
                handled(intent)
                stopWanted = true
                stopIfIdle()
                return START_NOT_STICKY
            }
        }
        pending++
        waiting.incrementAndGet()
        inbox.trySend(intent to locked)
        return if (intent?.action == ACTION_STOP && !locked) START_NOT_STICKY else START_STICKY
    }

    /** Android's Always-on and Block connections settings, which can change while connected. Caller holds [lock]. */
    private fun readFlags() {
        state.update { it.copy(alwaysOn = isAlwaysOn, lockdown = isLockdownEnabled) }
        // Lockdown decides which apps and networks the interface excludes: a change needs a rebuilt one.
        if (state.value.phase == VpnState.Phase.On && lastInput?.lockdown != isLockdownEnabled) scope.launch { reload() }
    }

    /** One start command, in arrival order; caller holds [lock]. */
    private fun handle(intent: Intent?, locked: Boolean) {
        when (intent?.action) {
            ACTION_FLAGS -> {
                // Raced a disconnect: don't leave an idle sticky service that a restart would turn into a connect.
                if (state.value.phase == VpnState.Phase.Off) { stopWanted = true; return }
                readFlags()
            }
            // Refused while locked: the tunnel keeps running and keeps its restart policy.
            ACTION_STOP -> if (!locked) teardown(null)
            // Nothing to pause (stale widget or sheet): don't leave an idle sticky service that a restart would turn into a connect.
            ACTION_PAUSE -> if (state.value.phase == VpnState.Phase.Off) stopWanted = true else if (!locked) pause(intent.getIntExtra(EXTRA_MINUTES, 15))
            else -> {
                // A timed resume only ends its own pause. In a new process (killed while paused) it still reconnects.
                val p = intent?.getIntExtra(EXTRA_PAUSE, 0) ?: 0
                if (p != 0 && pauses != 0 && (p != pauses || state.value.phase != VpnState.Phase.Paused)) {
                    // Its start put up "Connecting…" if the pause had already ended: put the running notice back.
                    if (state.value.phase == VpnState.Phase.On) { if (holding) notice(heldNotice) else notify(store.setup.value) } else stopWanted = state.value.phase == VpnState.Phase.Off
                    return
                }
                // Android's own start (after it killed the app, or Always-on) during a pause the user set: paused on until its end.
                if ((intent == null || intent.action == VpnService.SERVICE_INTERFACE) && state.value.phase == VpnState.Phase.Off) PauseNote.wake(this)?.let { pause(0, again = true, wakeAt = it); return }
                // Android's own start reaching a live pause (Always-on settings changed) keeps the pause: only its notice again.
                if (intent?.action == VpnService.SERVICE_INTERFACE && state.value.phase == VpnState.Phase.Paused) {
                    getSystemService(NotificationManager::class.java).notify(1, pausedNotice(state.value)); return
                }
                connect(intent?.getBooleanExtra(EXTRA_UPDATED, false) == true)
            }
        }
    }

    /** Counts installs and stops: a reload's provider answers apply only if none happened since it asked ([reload]). */
    private var generation = 0

    /** Setup and config the engine is running right now. */
    private var applied: Setup? = null
    private var appliedAccounts: Map<String, String> = emptyMap()
    /** A setup + accounts pair the engine refused: not retried until either changes, so a sign-out is never skipped. */
    private var rejected: Pair<Setup, Map<String, String>>? = null
    /** True between stopping the old engine and the new one running: only then is there something to restore. */
    private var restarting = false
    private var lastGood: String? = null
    /** What [lastGood] was compiled from, so a failed reload can bring exactly that back with a new probe pair. */
    @Volatile private var lastInput: Compiler.Input? = null
    /** The input the engine is being started with, which [openTun] builds Android's tunnel for; none while on hold. */
    private class TunPolicy(val input: Compiler.Input?, val restore: Long?)
    @Volatile private var tunPolicy: TunPolicy? = null
    private val tunInput get() = tunPolicy?.input

    @OptIn(FlowPreview::class)
    private fun connect(updated: Boolean = false) {
        val startList = app.unbraid.data.Mullvad.listVersion // before any server is picked
        if (state.value.phase == VpnState.Phase.On) { // a repeated start (Always-on, tile)
            readFlags()
            if (state.value.phase == VpnState.Phase.On) { // a failed reload may have ended it
                if (holding) notice(heldNotice) else notify(store.setup.value)
                AfterUpdate.on(this) // still on: a start after an update may have cleared the note meanwhile
            }
            return
        }
        cancelResume()
        // Automatic routes start on WireGuard again with every connection.
        app.unbraid.data.AutoProtocol.reset()
        state.value = VpnState(VpnState.Phase.Connecting)
        try {
            net.start()
            // Saved settings that won't open would start on defaults, another routing policy: the app offers to recover
            // them. Meanwhile no app goes online at all, rather than on the normal connection.
            if (store.unreadable.value && !store.reopen()) { hold(); return }
            store.refreshWaiting(System.currentTimeMillis(), ::installation)
            store.refreshAppSigners(::identity)
            val (setup, seen) = store.policySnapshot()
            // Refused before any provider is asked for a server: the engine would refuse it anyway.
            if (setup.defaultMissing) throw Compiler.MissingDefault()
            // A saved Tailscale node that won't open fails here with its own message, not as an engine error.
            if (setup.tailscale && store.tsStored) store.tsState
            // Due keys are replaced before any tunnel is built with them.
            Rotation.run(setup, { store.accounts.value }, store::replaceAccount)
            val accounts = store.accounts.value
            // The app's own tests get a fresh way in with each engine start (see apply); none until it runs.
            probe = null
            val picked = prepareSocks(setup, pickLinks(setup, emptyMap(), accounts))
            if (destroyed) return // the service went away during the provider calls: start no engine for it
            forgetLookups(this)
            val srv = server ?: CommandServer(this, this).also { it.start(); server = it }
            apply(srv, setup, picked, accounts, seen)
            requireCurrent(seen)
            ranRestores = seen
            lost = null; losses = 0
            // With the tailnet domain the engine was just compiled with: a reload that changes nothing never sets it again.
            state.value = VpnState(VpnState.Phase.On, System.currentTimeMillis(), picked.links.mapValues { it.value.server }, blockedText(setup, picked.blocked), tsSuffix = lastInput?.tsSuffix, lockdown = isLockdownEnabled, alwaysOn = isAlwaysOn, stuck = stuck(setup, picked.blocked), engine = starts,
                applied = if (picked.blocked.values.none { it is LinkPicker.Waiting }) setup.hashCode() else 0)
            stopWanted = false
            AfterUpdate.on(this)
            notify(setup)
            if (setup.tailscale) watchTailscale()
            // Anything edited while connecting is picked up here: only the applied setup is skipped.
            // Setup edits and sign-ins/outs both change what the engine must run.
            // From the Block setting the tunnel was built with, not Android's now: one turned on or off during the start
            // is a change the watcher still has to apply.
            val built = startingLockdown(lastInput, isLockdownEnabled)
            health = scope.launch { watchHealth(built) }
            pool = scope.launch { watchPool() }
            watch = combine(store.setup, store.accounts, store.restores) { s, a, _ -> s to a }.debounce(400).onEach { (s, a) ->
                val allowed = s.activeRoutes.filter { it.imported && Health.eligible(it) }.map { it.id }.toSet()
                state.update { it.copy(notAnswering = it.notAnswering intersect allowed) }
                // Not awaited: a slow provider must never hold up the next edit (which may have to block an app at once).
                if (lock.withLock {
                    // Moved on from a refused setup (a switch put back): trying that one again later is a fresh attempt.
                    if (rejected != null && rejected?.first != s) { rejected = null; state.update { it.copy(refused = 0) } }
                    ranRestores != store.restoreGeneration || (s != applied || a != appliedAccounts) && (s to a) != rejected
                }) scope.launch { reload() }
            }.launchIn(scope)
            // A Mullvad list read anywhere (a server picker too) that changes which relays it owns: routes checked again.
            app.unbraid.data.Mullvad.listChanged = { if (!destroyed) scope.launch { reload() } }
            // One that came while this was starting (before the hook was set) is checked now.
            if (app.unbraid.data.Mullvad.listVersion != startList) scope.launch { reload() }
            // An app installed while this was starting: judged now, with the VPN on.
            if (installs.get() != builtInstalls) scope.launch { reload() }
        } catch (e: Exception) {
            Log.e(TAG, "connect: ${logLine(e)}")
            // After an update the notice offers the reconnect; a tap shows what fails, in the app.
            if (lost != null) lose(Redact.message(e, "Could not connect")) else if (updated) { teardown(null); AfterUpdate.tell(this) } else teardown(Redact.message(e, "Could not connect"))
        }
    }

    /** The engine runs [Compiler.blockAll] because the saved settings won't open ([hold]); nothing else is applied then. */
    private var holding = false

    /**
     * Why no engine runs although the VPN was on ([lose]), until one runs again or the user stops it; with [losses], how
     * many tries failed. [holding] too while waiting for the next try.
     */
    private var lost: String? = null
    private var losses = 0
    private val heldNotice get() = lost?.let { "Apps are offline: $it" } ?: UNREADABLE

    /** Android's tunnel with no engine behind it: every app inside is offline, only [out] stays out. */
    private fun blockingTun(out: Set<String>) = Builder().setSession("$APP").setMetered(false).addAddress("172.19.0.1", 30).addAddress("fdfe:dcba:9876::1", 126)
        .addRoute("0.0.0.0", 0).addRoute("::", 0).addDnsServer("172.19.0.2")
        .also { b -> (out + packageName).forEach { runCatching { b.addDisallowedApplication(it) } } }.establish()

    /** Puts the blocking tunnel in place, leaving out the apps on Direct as saved now. False: Android lets no tunnel block any more. */
    private fun block(): Boolean {
        fun put(out: Set<String>): Boolean = blockingTun(out)?.let { b -> runCatching { tun?.close() }; tun = b; true } ?: false
        fun may() = store.setup.value.let { s -> Compiler.mayStayOut(verifiedSetup(s), isLockdownEnabled, ::packagesSharing) { verifiedIdentity(s, it) } }
        return runCatching {
            val out = may()
            // Checked again with it up: an app changed meanwhile comes inside.
            put(out).also { if (it) { val still = may(); if (!still.containsAll(out)) put(out intersect still) } }
        }.onFailure { Log.e(TAG, "block: ${logLine(it)}") }.getOrNull()
            ?: runCatching { put(emptySet()) }.getOrNull()
            ?: (tun != null && runCatching { prepare(this) == null }.getOrDefault(false)) // the one there stays, with nothing behind it
    }

    /**
     * The engine is gone and what ran before didn't come back: apps stay offline behind a tunnel of its own, never on
     * the normal connection, while connecting is tried again: after a while, or at once when something is changed.
     * Disconnect and Pause work as always. Caller holds [lock].
     */
    private fun lose(why: String) {
        // Taken before the tunnel is built: what changes from here on starts the next try.
        fun now() = listOf(store.setup.value, store.accounts.value, store.restoreGeneration, installs.get())
        val seen = now()
        if (destroyed || !block()) { teardown(why); return }
        val tries = losses
        stopEngine(keepTun = true)
        lost = why; losses = tries + 1; holding = true
        state.value = VpnState(VpnState.Phase.On, System.currentTimeMillis(), error = "Apps are offline: $why", lockdown = isLockdownEnabled, alwaysOn = isAlwaysOn, engine = starts)
        stopWanted = false
        notice(heldNotice)
        if (tries == 0) alert(heldNotice)
        val mine = session
        watch = scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(FakePool.lostWait(tries)) {
                while (now() == seen) kotlinx.coroutines.delay(500)
            }
            lock.withLock {
                if (session != mine || lost == null || destroyed) return@withLock
                // First the apps that may stay out, as saved now: an app just moved off Direct doesn't wait for the engine.
                block()
                holding = false
                watch = null
                state.value = VpnState(VpnState.Phase.Connecting)
                connect()
            }
        }
    }

    /** Accepted restore generation of the last successful install. */
    private var ranRestores = 0L

    private fun requireCurrent(seen: Long) {
        if (destroyed) error("VPN service is stopping")
        if (store.restoreGeneration != seen) throw Fail(RESTORE_REFUSED)
    }

    /**
     * Runs the engine with every app's connections refused, until the saved settings open (the app's Try again) or the
     * user starts fresh; then connects with them. Disconnect and Pause work as always. Caller holds [lock].
     */
    /** Whether [lastBlock] was noted down as spent; a hold on a block that wasn't answers no lookup ([hold]). */
    private var lastNoted = true

    private fun hold() {
        retireSocksExcept(emptySet())
        forgetLookups(this)
        val srv = server ?: CommandServer(this, this).also { it.start(); server = it }
        generation++
        val block = fakeBlock()
        // A block that couldn't be noted down is no reason to leave apps out: the hold still starts, offline, but answers no
        // lookup at all, as a stand-in address from that block could be handed out again by the next process.
        val noted = block == lastBlock && lastNoted || runCatching { store.fakeBlock = block }.onFailure { Log.e(TAG, "hold: ${logLine(it)}") }.isSuccess
        lastNoted = noted
        val json = Compiler.blockAll(packageName, File(filesDir, "cache.db").path, block, lookups = noted)
        engine("The connection engine rejected this setup.") { Libbox.checkConfig(json) }
        if (block != lastBlock) blockSince = SystemClock.elapsedRealtime()
        lastBlock = block
        openTable(true)
        tunPolicy = TunPolicy(null, null) // the unreadable-store hold only refuses traffic
        engine("The connection engine couldn't start.") { srv.startOrReloadService(json, OverrideOptions()) }
        starts++
        releaseRetiredSocks()
        holding = true
        lost = null; losses = 0
        state.value = VpnState(VpnState.Phase.On, System.currentTimeMillis(), lockdown = isLockdownEnabled, alwaysOn = isAlwaysOn, engine = starts)
        stopWanted = false
        AfterUpdate.on(this)
        notice(UNREADABLE)
        val mine = session
        watch = scope.launch {
            store.unreadable.first { !it }
            lock.withLock {
                if (session != mine || !holding || destroyed) return@withLock
                // The refusing engine stops first and its table goes, so the real setup starts a new one in the next block:
                // an address an app kept from the hold never names another site. Its tunnel stays open meanwhile (the
                // engine had its own copy), so apps stay offline until the new engine replaces it.
                holding = false
                watch = null
                runCatching { server?.closeService() }
                openTable(false)
                forgetLookups(this@UnbraidVpn)
                net.stop()
                state.value = VpnState(VpnState.Phase.Connecting)
                lost = "Could not connect" // offline until now: a start that fails leaves apps so
                connect()
            }
        }
    }

    /** Validates first, so a bad config never replaces a working engine. */
    /** [accounts] is the snapshot [links] were picked with: a sign-out after it differs, so the watcher reloads again. */
    private fun apply(srv: CommandServer, setup: Setup, picked: LinkPicker.Result, accounts: Map<String, String>, seen: Long) {
        requireCurrent(seen)
        generation++
        val counted = installs.get() // before the user ids are read for the setup: one installed after is counted after
        val links = picked.links
        val blocked = picked.blocked.keys
        var input = input(setup, links, blocked)
        var json = Compiler.compile(input)
        // A favourite, a rename or a second sign-in changes the setup but not what the engine runs: keep every tunnel up.
        val fresh = json != lastGood || counted != builtInstalls
        if (fresh) {
            // Every engine start gets a new loopback port and password: while an old engine lets go of its port, a local
            // app could take it over and see that engine's password, which no later engine accepts. The app's own tests
            // get the new pair only once the new engine runs.
            val next = newProbe()
            input = input(setup, links, blocked, next)
            json = Compiler.compile(input)
            engine("The connection engine rejected this setup.") { Libbox.checkConfig(json) }
            // The block is spent before the engine can hand out any of it, also if the start fails or the app dies now:
            // a new table that couldn't be noted down doesn't start, or the next process would hand it out again.
            // A new block starts from a deleted table: never an old file, which a missing or bad one would follow into it.
            requireCurrent(seen)
            if (engine("$APP couldn't save its state. Try again.") { FakePool.spend(input.fakeBlock, lastBlock, filesDir) { store.fakeBlock = it } })
                blockSince = SystemClock.elapsedRealtime()
            lastBlock = input.fakeBlock; lastNoted = true
            // The point of no return: the old engine's way in goes now, as it may be closed by the time the start fails.
            probe = null
            lastGood = null
            restarting = true
            openTable(true)
            // Before the start too, with the running engine's: a check under way during it sends UDP through neither, and
            // one already open through a tunnel that turns TCP-only is closed first.
            val udpless = Compiler.tcpOnly(json)
            Health.stun.udpless(udpless) { tcpOnly = tcpOnly + udpless }
            tunPolicy = TunPolicy(input, seen)
            requireCurrent(seen)
            engine("The connection engine couldn't start.") { srv.startOrReloadService(json, OverrideOptions()) }
            requireCurrent(seen)
            tcpOnly = udpless
            restarting = false
            starts++
            probe = next
            state.update { it.copy(tsSuffix = input.tsSuffix) }
        }
        requireCurrent(seen)
        if (!fresh) tunPolicy = TunPolicy(input, seen)
        lastGood = json
        releaseRetiredSocks()
        builtInstalls = counted
        lastInput = input // also when nothing restarted: a move restarts what is applied now
        tunnelDns = runCatching { Compiler.probeDns(json) }.getOrDefault(emptyMap())
        tags = Compiler.shared(setup, LinkPicker.ported(setup, links, portStep), blocked) + setup.activeRoutes.filter { it.tsExit != null }.associate { it.id to Compiler.TS_TAG }
        val was = running
        running = setup
        val eligibleImports = setup.activeRoutes.filter { it.imported && Health.eligible(it) && it.id in links }.map { it.id }.toSet()
        // Published after tags and running: whoever sees this engine number also sees the tunnels it runs. The new
        // engine numbers its connections afresh; stats read the new servers when they first see them.
        state.update { if (fresh) it.restarted(starts).copy(servers = links.mapValues { l -> l.value.server }) else it.copy(notAnswering = it.notAnswering intersect eligibleImports) }
        // After tags and running, which stats label new connections by; also when only the setup changed (a city that
        // resolves to the same server is still another place in the history).
        if (fresh || was != setup) app.unbraid.stats.Stats.post { app.unbraid.stats.Collector.retimeNow() }
        applied = setup
        appliedAccounts = accounts
        // A refusal is over once a setup runs again: a retry of the same one is judged afresh.
        state.update { it.copy(applied = if (picked.blocked.values.none { it is LinkPicker.Waiting }) setup.hashCode() else it.applied, refused = 0) }
        rejected = null
        this.links = links
        retryBlocked(picked.blocked)
    }

    private var retry: Job? = null
    private var retries = 0
    /** The running setup's blocked routes, which [retry] tries again. */
    private var retrying: Map<String, Exception> = emptyMap()

    /**
     * Routes blocked for a passing reason (no network, provider unreachable) try again after 5 s, 15 s, 30 s, 1 min,
     * then every 5 min; signed-out or refused ones wait for a sign-in, which reloads by itself.
     */
    private val netKicks = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    /** The connection ([session]) a network change asked a try for; -1 when none waits. */
    private val netWanted = java.util.concurrent.atomic.AtomicLong(-1)
    /** The backoff timer set last; only its firing tries ([reload] checks under [lock]). Changed under [lock]. */
    @Volatile private var retryTicket = 0L
    /** The newest timer that fired and wasn't tried yet. */
    private val fired = java.util.concurrent.atomic.AtomicLong()
    private var netWorker: Job? = null

    private fun retryBlocked(blocked: Map<String, Exception>) {
        retry?.cancel(); retry = null
        retrying = blocked
        if (blocked.values.all { it is AuthException }) { retries = 0; net.onChange = null; retryTicket++; return }
        val wait = listOf(5, 15, 30, 60, 300)[minOf(retries++, 4)] * 1000L
        // Every try, timed or for a new network, runs on one worker, one at a time with the newest asked for last: an older
        // try that fails after a newer one worked can't put its blocked result over it. The timer only asks.
        if (netWorker == null) netWorker = scope.launch {
            // A new network always tries; a timer only if it is still the one set ([retryTicket]): an older one's kick that
            // waited in the channel would otherwise try early and move the backoff on.
            // Each wake looks at both: a stale network change must not swallow a timer that fired with it.
            for (k in netKicks) try {
                netWanted.getAndSet(-1).let { s -> if (s >= 0) reload(forSession = s.toInt()) }
                fired.getAndSet(0).takeIf { it != 0L }?.let { reload(timer = it) }
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Log.e(TAG, "retry: ${logLine(e)}") } // one failed try never ends the worker
        }
        val ticket = ++retryTicket
        retry = scope.launch {
            kotlinx.coroutines.delay(wait)
            fired.accumulateAndGet(ticket, ::maxOf) // the newest timer's, whatever order a cancelled older one gets here in
            netKicks.trySend(Unit)
        }
        // A new network is the likeliest fix: try at once, with the backoff kept for what follows.
        val mine = session
        net.onChange = { netWanted.set(mine.toLong()); netKicks.trySend(Unit) }
    }

    /** Providers of [blocked] routes, and whether one refused its account. */
    private fun stuck(setup: Setup, blocked: Map<String, Exception>) = blocked.entries.mapNotNull { (id, e) -> setup.route(id)?.takeIf { !it.imported }?.let { it.loc.provider to (e is AuthException) } }
        .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.any { it } }

    /**
     * What ran before, back on the engine as it was compiled ([FakePool.restoreInput]: a new probe pair, and what may
     * have changed since read again), on [block] (a move to the next fake-IP block) or else the newest block spent. Until
     * it runs the app's own tests have no way in. [gone]: the old engine was already given up ([FakePool.restart]).
     */
    private fun restoreLastGood(srv: CommandServer, block: Int? = null, gone: Boolean = true, move: Boolean = false): FakePool.Outcome = FakePool.restart(gone, lastBlock, filesDir, prepare = { spent ->
        runCatching {
            // A move keeps the routes that ran. An observed restored app may have settled while this engine was active.
            store.refreshWaiting(System.currentTimeMillis(), ::installation)
            val input = FakePool.restoreInput(lastInput ?: error("nothing ran"), block, spent, newProbe(),
                if (move) null else store.setup.value.home.tsSuffixAllowed, if (move) null else store.tsSuffix, isLockdownEnabled, tsLoginPending, move, ::verifiedSetup, socksEndpoints()) { blockedKin(it) }
            input to Compiler.compile(input).also(Libbox::checkConfig)
        }.onFailure { Log.e(TAG, "restore: ${logLine(it)}") }.getOrThrow()
    }, note = { blockSpent ->
        store.fakeBlock = blockSpent
        lastBlock = blockSpent
        blockSince = SystemClock.elapsedRealtime()
        lastNoted = true
    }, commit = { input ->
        probe = null // the old engine's way in goes with it
        lastGood = null
        lastBlock = input.fakeBlock; lastNoted = true
        lastInput = input
        state.update { it.copy(tsSuffix = input.tsSuffix) }
        openTable(true)
    }, current = { store.restoreGeneration == ranRestores }) { input, json ->
        Compiler.tcpOnly(json).let { u -> Health.stun.udpless(u) { tcpOnly = tcpOnly + u } }
        tunPolicy = TunPolicy(input, ranRestores)
        requireCurrent(ranRestores)
        srv.startOrReloadService(json, OverrideOptions())
        requireCurrent(ranRestores)
        tcpOnly = Compiler.tcpOnly(json)
        starts++
        // The restored engine has now released the failed candidate. Its revoked ports can be freed.
        retireSocksExcept(input.socksBridges.keys - input.unavailableSocks)
        releaseRetiredSocks()
        val offline = input.unavailableSocks
        links = links - offline
        tags = tags - offline
        state.update { it.restarted(starts).copy(servers = it.servers - offline) }
        probe = input.probe
        lastGood = json
    }

    private var pool: Job? = null
    /** Said once per block: lookups of new names fail until the next move. */
    private var exhaustedSaid = false

    /**
     * Moves the engine to the next block of fake IPs when its own runs low ([FakePool]): what runs restarts there on a
     * new table while the tunnel stays open; open connections close, and apps holding the old block's addresses are
     * refused ([Compiler.compile]) and look their names up again. A move that fails before the old engine is given up
     * changes nothing and is tried at the next check; one whose start fails is a failed reload: what ran comes back, on
     * the new block, or the connection ends.
     */
    private suspend fun watchPool() {
        var wait = FakePool.EVERY
        while (true) {
            kotlinx.coroutines.delay(wait)
            wait = FakePool.EVERY
            lock.withLock {
                if (destroyed || state.value.phase != VpnState.Phase.On || holding) return@withLock
                val srv = server ?: return@withLock
                val last = lastBlock ?: return@withLock
                val (step, next) = FakePool.poll(Libbox::fakeIPRemaining) { blockSince?.let { SystemClock.elapsedRealtime() - it } }
                wait = next
                when (step) {
                    FakePool.Step.Nothing, FakePool.Step.Wait -> {}
                    FakePool.Step.Exhausted -> if (!exhaustedSaid) { exhaustedSaid = true; Log.w(TAG, "fake IPs: block used up before the next move is due, new lookups fail until then") }
                    FakePool.Step.Rotate -> when (FakePool.move { gone -> restoreLastGood(srv, Compiler.nextBlock(last, false).takeIf { !gone }, gone, move = true) }) {
                        FakePool.Outcome.Ran -> if (ranRestores != store.restoreGeneration) lose(RESTORE_REFUSED) else {
                            exhaustedSaid = false; Log.i(TAG, "fake IPs: moved to the next block"); retime()
                        }
                        FakePool.Outcome.Kept -> Log.w(TAG, "fake IPs: move not started, tried again at the next check")
                        FakePool.Outcome.Failed -> lose("Connection lost")
                        FakePool.Outcome.Superseded -> lose(RESTORE_REFUSED)
                    }
                }
            }
        }
    }

    /** A new engine numbers its connections afresh. */
    private fun retime() = app.unbraid.stats.Stats.post { app.unbraid.stats.Collector.retimeNow() }

    /** Engine errors can quote the config, keys included: log them cleaned, show a fixed text, and drop the cause. */
    private inline fun <T> engine(failure: String, f: () -> T): T =
        try { f() } catch (e: Exception) {
            Log.e(TAG, "engine: ${logLine(e)}")
            throw Fail(failure)
        }

    /**
     * Whether route [id]'s running tunnel lacks the V2Ray disguise its provider page now asks for: its link has none, or
     * the engine runs a setup without it (the link is the same, the disguise is added when it is installed).
     */
    private fun plain(setup: Setup, id: String) = setup.route(id)?.takeIf { !it.imported }?.let { r ->
        val p = r.loc.provider
        setup.opt(p, app.unbraid.data.DISGUISE_OPT) == "v2ray" && (links[id] as? app.unbraid.data.WgLink)?.let { it.disguise == null || applied?.opt(p, app.unbraid.data.DISGUISE_OPT) != "v2ray" } == true
    } == true

    /**
     * Applies the newest setup without dropping the tunnel; routes whose location is unchanged keep their server. Takes
     * [lock] itself and lets go of it while providers are asked for servers, so a newer change (one that must block an
     * app at once) never waits for them; what they answer is applied only if nothing changed meanwhile.
     */
    private suspend fun reload(keep: Map<String, Link>? = null, avoid: Set<String> = emptySet(), keepFrom: Int = generation, forInstall: Boolean = false, timer: Long = 0, forSession: Int = -1) {
        class Ask(val srv: CommandServer, val setup: Setup, val accounts: Map<String, String>, val ready: LinkPicker.Result, val applied: Setup?,
                  val appliedAccounts: Map<String, String>, val refused: Map<String, Exception>, val last: Map<String, Link>, val generation: Int, val seen: Long)
        val ask = lock.withLock {
            // While the settings won't open, what the store holds are defaults, not the user's setup: nothing to apply.
            // A destroyed instance's phase is the next instance's: never act on that connection.
            if (destroyed || state.value.phase != VpnState.Phase.On || holding) return
            // A timer that is no longer the one set (a newer one replaced it, or the retries stopped) tries nothing.
            if (timer != 0L && timer != retryTicket) return
            // Nor does a network change seen on a connection that has since ended (a pause and resume between).
            if (forSession >= 0 && forSession != session) return
            // An install retry that another reload caught up with, or that the VPN stopping cancelled, does nothing.
            if (forInstall && (installs.get() == builtInstalls || !kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.isActive)) return
            val srv = server ?: return
            store.refreshWaiting(System.currentTimeMillis(), ::installation)
            val (setup, seen) = store.policySnapshot()
            val accounts = store.accounts.value
            // [keep] was taken without the lock: only valid if nothing was installed since ([keepFrom]).
            val ready = pickLinks(setup, keep?.takeIf { keepFrom == generation } ?: links, accounts, avoid, offline = true)
            // Without a default route the install is refused at once (the running engine stays and the error says why),
            // before any provider is asked.
            if (ready.blocked.values.none { it is LinkPicker.Waiting } || setup.defaultMissing) { install(srv, setup, ready, accounts, seen); return }
            // While a provider is asked for a server, apps moving off Direct already leave through their new route, or
            // wait blocked if that is the one still waiting: never on their real address for the time it takes. A tunnel
            // whose account was signed out or refused, or that a provider setting now rules out, stops now too, not once the others are found.
            // So does a running tunnel that now has to go through another route (Via): never straight while the entry is fetched.
            // And an app or the default moved onto a Via route still waiting: blocked meanwhile, not left on its old route.
            // And a running tunnel without the disguise its provider now asks for: never plain while a disguised one is fetched.
            val socksChanged = setup.activeRoutes.any { r -> r.socks5 != null && r != applied?.route(r.id) } ||
                localExits.keys.any { it !in ready.links }
            val stopNow = socksChanged || ready.blocked.any { (id, e) -> (e is AuthException || e is LinkPicker.Unsuited) && id in links } ||
                ready.blocked.keys.any { id -> setup.route(id)?.via != null && (id in links || applied?.let { LinkPicker.movedOnto(it, setup, id) } == true) } ||
                links.keys.any { id -> setup.route(id)?.via != applied?.route(id)?.via || plain(setup, id) } ||
                !LinkPicker.stillSuits(setup, links, Providers::get) // also a running tunnel left out of [keep] by a health move
            // So does an app installed into what Android may leave out: applied now, not once the provider answers.
            if ((stopNow || installs.get() != builtInstalls || LinkPicker.leavesDirect(applied, setup, setup.activeRoutes.mapTo(HashSet()) { it.id }, lastInput?.tsSuffix, store.tsSuffix)) && !install(srv, setup, ready, accounts, seen)) return
            Ask(srv, setup, accounts, ready, applied, appliedAccounts, refusedFor(setup, accounts), store.lastLinks, generation, seen)
        }
        // A route without a tunnel (signed out, refused, unreachable) is blocked; the others keep running. Tunnels come
        // from the offline pass, which already dropped any a changed location or account can't keep.
        val picked = LinkPicker.pick(ask.setup, ask.ready.links, ask.applied, ask.appliedAccounts, ask.accounts, ask.last, avoid + failing, ask.refused, false, portStep, Providers::get)
        lock.withLock {
            // Stopped, reloaded or edited during the provider calls: the newer state wins (the watcher reloads an edit).
            if (destroyed || state.value.phase != VpnState.Phase.On || generation != ask.generation || store.setup.value != ask.setup || store.accounts.value != ask.accounts) return
            // A provider list read meanwhile may rule out a server picked from an older one (Mullvad-owned only): picked again.
            if (store.restoreGeneration != ask.seen || !LinkPicker.stillSuits(ask.setup, picked.links, Providers::get)) { scope.launch { reload() }; return }
            noteRefusals(ask.setup, ask.accounts, picked)
            runCatching { store.rememberLinks(picked.remember) }.onFailure { Log.w(TAG, "last servers not saved") }
            install(ask.srv, ask.setup, picked, ask.accounts, ask.seen)
        }
    }

    /** Runs [picked] on the engine and shows it; on failure brings back what ran. False if it didn't apply. Caller holds [lock]. */
    private fun install(srv: CommandServer, setup: Setup, selection: LinkPicker.Result, accounts: Map<String, String>, seen: Long): Boolean = try {
        val picked = prepareSocks(setup, selection)
        apply(srv, setup, picked, accounts, seen)
        requireCurrent(seen)
        ranRestores = seen
        state.update { it.copy(servers = picked.links.mapValues { l -> l.value.server }, error = blockedText(setup, picked.blocked), stuck = stuck(setup, picked.blocked)) }
        notify(setup)
        if (setup.tailscale && ts == null) watchTailscale()
        true
    } catch (e: Exception) {
        Log.e(TAG, "reload: ${logLine(e)}")
        rejected = setup to accounts // don't retry the same broken pair in a loop; [applied] stays what runs
        // libbox stops the old engine before starting the new one: only then bring the last good config back. It comes
        // back with a new probe pair too: the old password may have been seen while the old engine's port was free.
        val restarted = restarting
        // A failed candidate can already own live listeners. Revoke those that never belonged to the last good input.
        retireSocksExcept(FakePool.retainedBridges(lastInput?.socksBridges.orEmpty(), socksEndpoints()).keys)
        val outcome = FakePool.afterFailure(restarted, { ranRestores == store.restoreGeneration }) { restoreLastGood(srv) }
        restarting = false
        if (outcome == FakePool.Outcome.Superseded || ranRestores != store.restoreGeneration) lose(RESTORE_REFUSED)
        else if (outcome == FakePool.Outcome.Failed) lose(Redact.message(e, "Connection lost"))
        else {
            // A retry of the running setup that failed before any engine restart is tried again later; one that restarted
            // every tunnel isn't, so a failing server never takes all apps offline every few minutes (a network change
            // still tries again). A refused edit is never retried this way: it waits for the next edit.
            if (restarted) { retry?.cancel(); retry = null; retryTicket++ }
            else if (setup == applied && accounts == appliedAccounts) retryBlocked(retrying)
            // An install that may change what Android leaves out ([installs]) is not left to the next edit: tried once more.
            val pending = installs.get()
            if (pending != builtInstalls && pending != retriedInstalls) { retriedInstalls = pending; installRetry?.cancel(); installRetry = scope.launch { kotlinx.coroutines.delay(30_000); reload(forInstall = true) } }
            retime()
            state.update { it.copy(error = Redact.message(e, "Change not applied"), refused = setup.hashCode()) }
        }
        false
    }

    private var health: Job? = null

    /**
     * Checks each provider tunnel the app can move ([Health]) about once a minute. One that fails is checked again 10 s
     * and 20 s later, then once more a minute after its first failure: WireGuard and OpenVPN usually come back on their
     * own server within that minute. Still failing, it gets another server of its location. A round where every tunnel
     * fails while the phone's own network does too is most likely that network: no server is blamed, and checks slow
     * down (up to every ~8 min) until one works again. Nothing is judged for a while after a new network, an engine
     * start or a sleep.
     */
    private suspend fun watchHealth(built: Boolean) {
        val streaks = HashMap<String, Health.Streak>() // tag → its failed checks; tags that answered have none
        val history = HashMap<String, List<Boolean>>() // tag → its latest regular checks, for routes that keep dropping
        val holds = HashMap<String, Long>() // tag → when a dead tunnel there may be handled again; survives engine restarts
        val moves = HashMap<String, Health.Moves>() // tag → its dead tunnels handled in a row on this network
        var moved = -1 // the engine start the last move made: its restart keeps the history the move was judged on
        var slow = 1
        var grace = SystemClock.elapsedRealtime() + Health.GRACE // no check before this
        var round = SystemClock.elapsedRealtime() + jitter() // the next regular round
        var basis: Pair<Int, android.net.Network?>? = null // the engine start and network the streaks are about
        var lockdown = built // Android's Block setting as last seen here: at first, the one the tunnel was built with
        fun restart(now: Long, keep: Boolean = false) {
            streaks.clear(); if (!keep) { history.clear(); undrop() }; failing = emptySet(); state.update { it.copy(notAnswering = emptySet(), passed = null) }; grace = now + Health.GRACE; round = grace
        }
        while (true) {
            val wake = maxOf(grace, streaks.entries.mapNotNull { (t, s) -> Health.due(s, holds[t] ?: 0) }.fold(round, ::minOf))
            val slept = asleep()
            // At least 5 s, then in slices: waits don't count deep sleep, and a sleep, an engine start or a new network
            // ends one early.
            kotlinx.coroutines.delay(5_000)
            while (SystemClock.elapsedRealtime() < wake && asleep() - slept < Health.SLEPT && basis == (starts to net.network) && lockdown == isLockdownEnabled)
                kotlinx.coroutines.delay((wake - SystemClock.elapsedRealtime()).coerceIn(1, 15_000))
            // Block turned on or off in Android's settings while connected: excluded apps and networks change at once
            // (Android sends no event for it), not only when the app is opened next. Once per change.
            if (lockdown != isLockdownEnabled) { lockdown = isLockdownEnabled; lock.withLock { if (!destroyed && state.value.phase == VpnState.Phase.On) readFlags() } }
            var now = SystemClock.elapsedRealtime()
            holds.values.removeIf { it <= now }
            // A sleep in between (Doze, screen off) says nothing about the servers.
            if (asleep() - slept >= Health.SLEPT) { restart(now); continue }
            if (basis != null && basis?.second != net.network) state.update { it.copy(notAnswering = emptySet()) }
            if (state.value.phase != VpnState.Phase.On || net.network == null || SpeedTest.testing) { if (now >= round) round = now + jitter(); continue }
            val network = net.network
            // Ports Automatic learned on another network don't hold here: back to each provider's first port.
            if (network != portNet) { portNet = network; if (portStep.isNotEmpty()) { portStep = emptyMap(); reload(); continue } }
            var servers = emptyMap<String?, List<String>>() // tag → its servers as checked, before a reload can swap them
            var engine = 0
            var checkedGeneration = 0
            var ran: app.unbraid.data.Setup? = null // the setup the engine ran when the round started: what was checked
            val byTag = lock.withLock {
                engine = starts
                checkedGeneration = generation
                val s = (running ?: return@withLock emptyMap()).also { ran = it }
                // Pinned and Dedicated IP routes are checked too (to notice a removed device), but never moved.
                links.keys.filter { id -> s.route(id)?.let(Health::eligible) == true }
                    .groupBy { tags[it] }.filterKeys { it != null && it != Compiler.TS_TAG }
                    .also { m -> servers = m.mapValues { (_, ids) -> ids.mapNotNull { id -> s.route(id)?.takeIf { !it.imported }?.let { r -> links[id]?.let { LinkPicker.serverKey(r, it) } } } } }
            }
            // Restarted tunnels or another network: earlier checks don't carry over, and the new ones get time to settle.
            if (basis != (engine to network)) {
                val first = basis == null
                val newNetwork = !first && basis?.second != network
                basis = engine to network
                // What didn't work on the last network may work on this one.
                if (newNetwork) { moves.clear(); holds.clear(); app.unbraid.data.AutoProtocol.reset(); if (state.value.stalled.isNotEmpty()) state.update { it.copy(stalled = emptySet()) } }
                // A move's own restart keeps the history: otherwise a route that never works would never be shown failing.
                if (!first) { slow = 1; restart(now, keep = !newNetwork && engine == moved); continue }
            }
            if (now < grace) continue
            val regular = now >= round
            // A new key whose reply got lost may already be the only one IVPN takes: finish it as soon as it can be.
            if (regular) Rotation.run(store.setup.value, { store.accounts.value }, store::replaceAccount, finishOnly = true)
            if (regular) {
                // An unreadable signing identity can recover without a package broadcast.
                store.refreshWaiting(System.currentTimeMillis(), ::installation)
                val saved = store.setup.value
                val held = lastInput?.setup?.apps.orEmpty()
                if (saved == applied && saved.appSigners.any { (pkg, pin) -> held[pkg] == app.unbraid.data.BLOCK && saved.deadline(saved.live, pkg) == null && identity(pkg)?.accepts(pin) == true }) {
                    store.refreshAppSigners(::identity)
                    reload()
                }
            }
            if (regular) scope.launch { runCatching { announceUpdate(this@UnbraidVpn, store) } }
            // A provider whose new key is still reaching its servers isn't judged yet.
            val settingUp = Providers.settingUp()
            val eligible = byTag.filter { (_, ids) -> ids.none { running?.route(it)?.let { r -> !r.imported && r.loc.provider in settingUp } == true } }.keys.filterNotNull()
            // A regular round checks every tunnel, a quick one only those due again.
            val checked = if (regular) eligible.toSet() else eligible.filterTo(HashSet()) { t -> streaks[t]?.let { Health.due(it, holds[t] ?: 0) }?.let { it <= now } == true }
            if (checked.isEmpty()) { if (regular) round = now + jitter() * slow; continue }
            val gate = Semaphore(4)
            val answered = coroutineScope { checked.map { t -> async { gate.withPermit { t to Health.reachable(t) } } }.awaitAll() }.toMap()
            val down = Health.blame(checked.filterTo(HashSet()) { answered[it] != true }, checked) { Health.direct(network) }
            now = SystemClock.elapsedRealtime()
            if (asleep() - slept >= Health.SLEPT) { restart(now); continue }
            val move = lock.withLock {
                // Stopped, restarted or moved to another network meanwhile: this round says nothing (the next starts afresh).
                if (state.value.phase != VpnState.Phase.On || starts != engine || generation != checkedGeneration || net.network != network) return@withLock null
                // What the widgets count as working, for every round: one blamed on the phone's network included.
                state.update { it.copy(passed = Health.round(it.passed, engine, network?.networkHandle, byTag.filterKeys { t -> t in checked }.mapKeys { (t, _) -> t.orEmpty() }, down) { id -> ran?.route(id) }) }
                if (down == null) {
                    // Most likely the phone's own network: no server is blamed for these checks.
                    if (regular) { streaks.clear(); slow = minOf(slow * 2, 8) } else checked.forEach { streaks.remove(it) }
                    failing = streaks.keys.flatMap { servers[it].orEmpty() }.toSet()
                    return@withLock null
                }
                val current = running ?: return@withLock null
                val pending = store.setup.value
                val allowed = pending.activeRoutes.map { it.id }.toSet()
                val imports = byTag.values.flatten().filter { id -> id in allowed && current.route(id)?.let { it.imported && Health.eligible(it) } == true && pending.route(id)?.let { it.imported && Health.eligible(it) } == true }.toSet()
                val passed = byTag.filterKeys { it in checked && it !in down }.values.flatten().toSet()
                val confirmed = checked.filter { t ->
                    val next = Health.next(streaks[t] ?: Health.Streak(), t !in down, now)
                    Health.dead(next, now, holds[t] ?: 0)
                }.toSet()
                val deadImports = byTag.filterKeys { it in confirmed }.values.flatten().toSet()
                state.update { it.copy(notAnswering = Health.answering(it.notAnswering, imports, passed, deadImports)) }
                if (regular) slow = 1
                checked.forEach { t ->
                    val s = Health.next(streaks[t] ?: Health.Streak(), t !in down, now)
                    if (s.fails == 0) streaks.remove(t) else { streaks[t] = s; Log.i(TAG, "health: $t failed ${s.fails}x, ${(now - s.since) / 1000} s") }
                    // Quick re-checks don't count here: one outage would look like a tunnel that keeps dropping.
                    if (regular) history[t] = Health.record(history[t].orEmpty(), t !in down).also { h -> if (Health.ends(moves[t], h)) { moves.remove(t); holds.remove(t) } }
                }
                // The next connect, even a manual one, avoids them.
                failing = streaks.keys.flatMap { servers[it].orEmpty() }.toSet()
                val dropping = byTag.filterKeys { it != null && Health.dropping(history[it].orEmpty()) }.values.flatten().filter { current.route(it)?.imported == false }.toSet()
                if (dropping != state.value.dropping) state.update { it.copy(dropping = dropping) }
                val deadTags = checked.filter { t -> streaks[t]?.let { Health.dead(it, now, holds[t] ?: 0) } == true }
                // A dead tunnel on Automatic also tries its provider's next port: a failed check can't tell a blocked port from a dead server.
                fun steps(id: String) = current.route(id)?.takeIf { !it.imported }?.loc?.provider?.takeIf { p -> (links[id] as? app.unbraid.data.WgLink)?.ports.orEmpty().size > 1 && current.opt(p, app.unbraid.data.PORT_OPT) == app.unbraid.data.AUTO && current.opt(p, app.unbraid.data.DISGUISE_OPT) != "v2ray" && (links[id] as? app.unbraid.data.WgLink)?.hopEntry == null }
                // Only what a move restarts every tunnel for spends the move budget; imported routes keep their warning.
                deadTags.forEach { t ->
                    val acts = byTag[t].orEmpty().any { id -> current.route(id)?.let(Health::movable) == true || steps(id) != null }
                    Health.handle(moves[t], now, acts).let { (m, hold) -> if (m != null) moves[t] = m; holds[t] = hold }
                }
                val given = moves.filterValues(Health::doesntWork).keys
                val stalled = byTag.filterKeys { it in given }.values.flatten().filter { current.route(it)?.imported == false }.toSet()
                if (stalled != state.value.stalled) state.update { it.copy(stalled = stalled) }
                if (deadTags.isEmpty()) return@withLock null
                val dead = byTag.filterKeys { it in deadTags }.values.flatten().filter { current.route(it)?.imported == false }
                if (dead.isEmpty()) return@withLock null
                val s = running ?: return@withLock null
                // Given up on: still checked for a removed device (each time its hold ends while dead), but neither moved nor
                // port-stepped (each restarts every tunnel).
                val moving = byTag.filterKeys { it in deadTags && it !in given }.values.flatten().filter { it in dead }
                // An Automatic route whose WireGuard failed on every port on this network goes over OpenVPN TCP instead;
                // not one going Via another route, which works over WireGuard only.
                val toTcp = moving.filter { id ->
                    val r = s.route(id)?.takeIf { it.via == null } ?: return@filter false
                    val l = links[id] as? app.unbraid.data.WgLink ?: return@filter false
                    r.loc.protocol == app.unbraid.data.AUTO_PROTOCOL && (steps(id)?.let { LinkPicker.stepOf(portStep, r, l, LinkPicker.alone(s, links, id)) + 1 >= l.ports.size } ?: true) && app.unbraid.data.AutoProtocol.toTcp(id)
                }.toSet()
                // Per tunnel: one dead server never moves the provider's healthy ones to a port this network may block.
                val steppedIds = moving.filter { it !in toTcp && steps(it) != null }
                val stepped = steppedIds.mapNotNull { id -> s.route(id)?.let { r -> links[id]?.let { r to it } } }
                val movable = moving.filter { id -> id in toTcp || s.route(id)?.let(Health::movable) == true }
                // A route that also moves to another server takes its step along: a new server at the first port again
                // would fail the same way on a network that blocks that port.
                // The server moves on from its own step (a route that brought more never pushes the others on it further);
                // the route moves on from the step it used.
                portStep = portStep + stepped.associate { (r, l) -> LinkPicker.serverKey(r, l).let { k -> k to (portStep[k] ?: 0) + 1 } } +
                    stepped.filter { (r, _) -> r.id in movable }.associate { (r, l) -> LinkPicker.routeStepKey(r) to LinkPicker.stepOf(portStep, r, l, LinkPicker.alone(s, links, r.id)) + 1 }
                Log.w(TAG, "health: ${dead.size} route(s) stopped answering, ${movable.size} to move to another server")
                Move(links - movable.toSet(), movable.mapNotNull { id -> s.route(id)?.let { r -> links[id]?.let { LinkPicker.serverKey(r, it) } } }.toSet(), generation,
                    dead.mapNotNull { id -> s.route(id)?.loc?.provider }.toSet(), movable.isNotEmpty() || steppedIds.isNotEmpty())
            }
            if (regular) round = now + jitter() * slow
            if (move == null) continue
            // A dead tunnel can mean the provider dropped this phone (its device removed on the website): then its routes
            // wait blocked for a new sign-in instead of trying server after server.
            val accounts = store.accounts.value
            val dropped = move.providers.filter { p -> accounts[p]?.let { Providers.get(p)?.revoked(it) } == true }
            if (dropped.isNotEmpty()) lock.withLock {
                dropped.forEach { p -> refusals = refusals + (p to (accounts[p] to AuthException("${Providers.get(p)?.name ?: p} no longer has this phone (it was removed from your account). Sign in again"))) }
            }
            // The newest setup, not the running one: a change still waiting to apply must never be undone here.
            if (move.anyMovable || dropped.isNotEmpty()) { reload(move.keep, move.avoid, move.generation); moved = starts }
        }
    }

    /** A regular round's wait: about a minute, spread so tunnels of many phones don't line up. */
    private fun jitter() = 48_000L + kotlin.random.Random.nextLong(24_000)

    /** How long the phone has slept since boot: it grows only while it sleeps. */
    private fun asleep() = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()

    /** Health history starts again: no route is flagged as dropping until new checks say so. */
    private fun undrop() { if (state.value.dropping.isNotEmpty()) state.update { it.copy(dropping = emptySet()) } }

    private class Move(val keep: Map<String, Link>, val avoid: Set<String>, val generation: Int, val providers: Set<String>, val anyMovable: Boolean)

    /** Tunnels the engine runs now, per route id. */
    private var links: Map<String, Link> = emptyMap()

    /** Caller holds [lock]. */
    private fun pickLinks(setup: Setup, keep: Map<String, Link>, accounts: Map<String, String>, avoid: Set<String> = emptySet(), offline: Boolean = false): LinkPicker.Result {
        val r = LinkPicker.pick(setup, keep, applied, appliedAccounts, accounts, store.lastLinks, avoid + failing, refusedFor(setup, accounts), offline, portStep, Providers::get)
        noteRefusals(setup, accounts, r)
        // Only a fallback cache: failing to write it never stops a connection.
        if (!offline) runCatching { store.rememberLinks(r.remember) }.onFailure { Log.w(TAG, "last servers not saved") }
        return r
    }

    /**
     * Routes whose provider refused the account it still has, or whose pinned server it no longer offers. A refusal
     * holds until that account changes (a sign-out or a new sign-in), a server's removal while connected: a later
     * request that fails never brings the saved server back.
     */
    private fun refusedFor(setup: Setup, accounts: Map<String, String>): Map<String, Exception> {
        refusals = refusals.filter { (p, v) -> v.first == accounts[p] }
        // The entry of a one-tunnel multi-hop too: it isn't an active route, but its server is used.
        val routes = setup.activeRoutes.let { a -> (a + a.filter { it.viaNative }.mapNotNull { setup.route(it.via) }).distinct() }
        return routes.filter { !it.imported }.mapNotNull { r ->
            (refusals[r.loc.provider]?.second ?: LinkPicker.goneKey(r.loc)?.let { gone[it] })?.let { r.id to it }
        }.toMap()
    }

    private fun noteRefusals(setup: Setup, accounts: Map<String, String>, r: LinkPicker.Result) = r.blocked.forEach { (id, e) ->
        setup.route(id)?.takeIf { !it.imported }?.let {
            if (e is AuthException) refusals = refusals + (it.loc.provider to (accounts[it.loc.provider] to e))
            if (e is app.unbraid.data.NoLongerOffered) LinkPicker.goneKey(it.loc)?.let { k -> gone = gone + (k to e) }
            if (e is LinkPicker.EntryGone) setup.route(it.via)?.loc?.let(LinkPicker::goneKey)?.let { k -> gone = gone + (k to e.gone) }
        }
    }

    /** Pinned servers ([LinkPicker.pinKey]) their provider's list no longer has, while connected. */
    private var gone: Map<String, Exception> = emptyMap()

    /** Tailscale gave a sign-in link and waits for the user ([Compiler.Input.tsLoginPending]). */
    @Volatile private var tsLoginPending = false

    /** Providers that refused an account, with that account and why: their routes stay blocked until it changes. */
    private var refusals: Map<String, Pair<String?, Exception>> = emptyMap()

    /** Tunnel ([LinkPicker.serverKey]) → how many of its ports Automatic has moved past on this network ([LinkPicker.ported]). */
    @Volatile private var portStep: Map<String, Int> = emptyMap()
    /** The network [portStep] was learned on. */
    private var portNet: android.net.Network? = null

    private fun input(setup: Setup, links: Map<String, Link>, blocked: Set<String>, p: Pair<Int, String>? = probe) =
        verifiedSetup(setup).let { checked ->
            Compiler.Input(checked, LinkPicker.ported(checked, links, portStep), packageName, isLockdownEnabled, File(filesDir, "tailscale").path, File(filesDir, "cache.db").path, store.tsSuffix, p, fakeBlock(), blocked, tsLoginPending, blockedKin(checked), socksBridges = socksEndpoints())
        }

    // Access to the registry is serialized with engine installs. Verification runs on the bridge's
    // I/O threads and reads the live policy and package identity afresh on the connected socket.
    private class LocalExit(val profile: Socks5Profile, val uid: Int, val restore: Long, val bridge: Socks5Bridge)
    private val localExits = mutableMapOf<String, LocalExit>()
    private val retiredExits = mutableListOf<Socks5Bridge>()

    private fun retireSocksExcept(keep: Set<String>) {
        for (id in localExits.keys - keep) {
            val bridge = localExits.remove(id)!!.bridge
            bridge.revoke()
            val published = lastInput?.socksBridges?.get(id)?.port == bridge.port ||
                tunInput?.socksBridges?.get(id)?.port == bridge.port
            if (published) retiredExits += bridge else bridge.close()
        }
    }

    private fun releaseRetiredSocks() {
        retiredExits.forEach { it.close() }
        retiredExits.clear()
    }

    private fun closeSocks() {
        retireSocksExcept(emptySet())
        releaseRetiredSocks()
    }

    private fun socksEndpoints() = localExits.mapValues { (_, exit) ->
        Compiler.SocksBridge(exit.bridge.port, exit.bridge.username, exit.bridge.password, exit.profile.allowUdp)
    }

    private fun companionUid(profile: Socks5Profile, signer: AppIdentity? = identity(profile.packageName)): Int? = runCatching {
        val pkg = profile.packageName
        val uid = packageManager.getPackageUid(pkg, 0)
        uid.takeIf { signer?.accepts(profile.identity) == true &&
            packageManager.getPackagesForUid(uid)?.toSet() == setOf(pkg) }
    }.getOrNull()

    /** Look up the peer's socket, with that socket's endpoint first. UDP ownership is only a snapshot. */
    private fun socksOwner(protocol: Int, peer: InetSocketAddress, local: InetSocketAddress, uid: Int): Boolean =
        runCatching { cm.getConnectionOwnerUid(protocol, peer, local) == uid }.getOrDefault(false)

    private fun prepareSocks(setup: Setup, picked: LinkPicker.Result): LinkPicker.Result {
        val routes = setup.activeRoutes.filter { it.socks5 != null && it.id in picked.links }
        if (routes.isEmpty() && localExits.isEmpty()) return picked
        val blocked = picked.blocked.toMutableMap()
        val restore = store.restoreGeneration
        val checked = verifiedSetup(setup)
        val kin = blockedKin(checked)
        val keep = mutableSetOf<String>()
        for (route in routes) {
            val profile = route.socks5!!
            val uid = companionUid(profile)
            val reason = Compiler.socksPolicyProblem(checked, route, packageName, isLockdownEnabled, kin, store.tsSuffix)
                ?: if (uid == null) "The proxy app could not be verified on this phone" else null
            if (reason != null || uid == null) {
                blocked[route.id] = Fail(reason ?: "Proxy app unavailable")
                continue
            }
            val current = localExits[route.id]
            if (current == null || !profile.sameConnection(current.profile) || current.uid != uid || current.restore != restore) {
                retireSocksExcept(localExits.keys - route.id)
                fun companionStillAllowed(): Boolean {
                    val saved = store.setup.value
                    val pkg = profile.packageName
                    if (destroyed || store.restoreGeneration != restore || isLockdownEnabled || !saved.outside(pkg) ||
                        saved.route(route.id)?.let { profile.sameConnection(it.socks5) && !it.keyless } != true) return false
                    val signer = verifiedIdentity(saved, pkg)
                    return saved.permitsApp(pkg) { signer } && companionUid(profile, signer) == uid
                }
                val bridge = runCatching {
                    Socks5Bridge.start(profile.host, profile.port, profile.username, profile.password, socksPorts(setup),
                        udpEnabled = profile.allowUdp,
                        udpRelayOwnerVerified = { local, remote ->
                            companionStillAllowed() && socksOwner(android.system.OsConstants.IPPROTO_UDP, remote, local, uid)
                        },
                    ) { socket ->
                        companionStillAllowed() && socksOwner(android.system.OsConstants.IPPROTO_TCP,
                            socket.remoteSocketAddress as InetSocketAddress, socket.localSocketAddress as InetSocketAddress, uid)
                    }
                }.getOrNull()
                if (bridge == null || bridge.port == profile.port) {
                    bridge?.close()
                    blocked[route.id] = Fail("Could not open the local proxy connection")
                    continue
                }
                localExits[route.id] = LocalExit(profile, uid, restore, bridge)
            }
            keep += route.id
        }
        retireSocksExcept(keep)
        return LinkPicker.Result(picked.links - blocked.keys, picked.remember, blocked)
    }

    private fun identity(pkg: String) = AppIdentity.read(packageManager, pkg)

    /** An earlier running profile also respects a newer signer observed for one of its grants. */
    private fun verifiedIdentity(setup: Setup, pkg: String, observed: InstallState? = null): AppIdentity? {
        val saved = store.setup.value
        val previous = if (saved.profile == setup.profile) null else saved.profiles.firstOrNull { it.name == setup.profile }
        val pin = if (saved.profile == setup.profile) saved.appSigners[pkg] else previous?.appSigners?.get(pkg)
        val installed = if (pkg in saved.waiting || pkg in setup.waiting) {
            val p = if (setup.profile == saved.profile) saved.live else previous
            val state = observed ?: installation(pkg)
            val now = System.currentTimeMillis()
            if (p != null && !saved.waitAdmits(p, pkg, now, state) || !setup.waitAdmits(setup.live, pkg, now, state)) return null
            (state as? InstallState.Present)?.identity
        } else if (observed != null) (observed as? InstallState.Present)?.identity else identity(pkg)
        return installed?.takeIf { pin == null || it.accepts(pin) }
    }

    private fun installation(pkg: String) = InstallState.read(packageManager, pkg, includeArchived = true)

    private fun verifiedSetup(setup: Setup): Setup {
        val observed = HashMap<String, InstallState>()
        val resolved = setup.resolveWaiting(System.currentTimeMillis(), { pkg -> installation(pkg).also { observed[pkg] = it } }, effective = true)
        return resolved.checkedApps { pkg -> verifiedIdentity(setup, pkg, observed[pkg]) }
    }

    private fun packagesSharing(pkg: String): Set<String>? =
        runCatching { packageManager.getPackagesForUid(packageManager.getPackageUid(pkg, 0))?.toSet() }.getOrNull()

    /**
     * The apps on Direct that another app of their Android user id keeps inside ([Compiler.Input.blockedKin]). An app that
     * isn't installed shares none; any other failed lookup keeps the app inside instead of reading as "none" and leaving
     * it out with a blocked or routed app of its user id. A restore keeps the set that ran ([FakePool.restoreInput]).
     * Asked whenever a setup is applied, but the tunnel is rebuilt only for a new config: an app installed or removed while
     * connected counts once the user reconnects (shortcut: no package watch; upgrade with a reload on install and removal,
     * judged against what runs, not what is saved, that rebuilds the tunnel when the user ids it leaves out change).
     */
    private fun blockedKin(setup: Setup): Set<String> = Compiler.keptIn(setup) { pkg ->
        try { packageManager.getPackagesForUid(packageManager.getPackageUid(pkg, 0))?.toSet() }
        catch (e: android.content.pm.PackageManager.NameNotFoundException) { emptySet() }
        catch (e: RuntimeException) { null }
    }

    /** The open table's fake-IP block for a reload, while its file is there ([FakePool.keeps]); else the next one, refusing the rest. */
    private fun fakeBlock() = Compiler.nextBlock(lastBlock ?: store.fakeBlock, FakePool.keeps(LookupTable.isOpen(), LookupTable.bytes(filesDir)))


    /** The app's own way into each exit ([Compiler.PROBE]): a free loopback port and a 24-byte random password. */
    private fun socksPorts(setup: Setup): Set<Int> =
        (setup.servers.mapNotNull { it.socks5?.port } + setup.routes.mapNotNull { it.socks5?.port }).toSet()

    private fun newProbe(): Pair<Int, String> {
        val excluded = socksPorts(store.setup.value)
        repeat(16) {
            val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            if (port !in excluded) return port to java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(24).also { java.security.SecureRandom().nextBytes(it) })
        }
        throw Fail("Could not allocate a local check port")
    }

    private fun watchTailscale() {
        ts = runCatching {
            Libbox.newStandaloneCommandClient().subscribeTailscaleStatus(object : TailscaleStatusHandler {
                override fun onError(message: String?) { Log.w(TAG, "tailscale: ${logText(message)}") }
                override fun onStatusUpdate(u: TailscaleStatusUpdate) {
                    val it = u.endpoints()
                    if (!it.hasNext()) return
                    val ep = it.next()
                    val nodes = buildList {
                        val groups = ep.userGroups()
                        while (groups.hasNext()) {
                            val peers = groups.next().peers()
                            while (peers.hasNext()) {
                                val p = peers.next()
                                val ips = p.tailscaleIPs()
                                if (p.exitNodeOption && ips.hasNext()) add(TsNode(p.hostName.ifEmpty { p.dnsName.substringBefore('.') }, ips.next(), p.online, p.stableID, p.exitNode))
                            }
                        }
                    }.sortedBy { it.name.lowercase() }
                    val suffix = Compiler.tsSuffix(ep.magicDNSSuffix)
                    val reported = if (ep.backendState == "Running") suffix else store.tsSuffix
                    state.update { s -> s.copy(tsLoginUrl = ep.authURL.ifEmpty { null }, tsState = ep.backendState, tsExitNodes = nodes, tsAnnounced = reported) }
                    // The sign-in pages' way around the exit opens with a sign-in link and closes only once signed in: a
                    // restarted engine reports no link at first, which must not close it (each reload restarts Tailscale).
                    // Compared with what the engine runs, so a reload that failed is tried again on the next status.
                    tsLoginPending = TsLogin.pending(ep.authURL, ep.backendState, tsLoginPending)
                    if (lastInput?.let { it.tsLoginPending != tsLoginPending } == true) scope.launch { reload() }
                    // A speed test that was cut off left Tailscale on its test node: put the configured one back.
                    if (ep.backendState == "Running" && store.tsTestFrom != null && !SpeedTest.testing) SpeedTest.restoreNode()
                    // The tailnet's own domain is only known once signed in: reload once so DNS routes it to Tailscale.
                    if (ep.backendState == "Running" && suffix != store.tsSuffix) {
                        store.tsSuffix = suffix
                        scope.launch { reload() }
                    }
                }
            })
        }.onFailure { Log.w(TAG, "tailscale status: ${logLine(it)}") }.getOrNull()
    }

    /** Counts connections ended ([stopEngine]): a stop from outside applies only to the one it was sent for. */
    @Volatile private var session = 0
    /** Android or the engine ended the connection: said, like a failed connect, unless that one had already ended. */
    private fun stoppedOutside(why: String) {
        val mine = session // taken now: a stop and a new connect may be handled before this gets the lock
        scope.launch { lock.withLock { if (session == mine && state.value.phase != VpnState.Phase.Off) teardown(why) } }
    }

    /** Stops the engine and the tunnel; the service itself stays. Caller holds [lock]. */
    private fun stopEngine(keepTun: Boolean = false) {
        lost = null; losses = 0
        watch?.cancel(); watch = null
        health?.cancel(); health = null
        pool?.cancel(); pool = null; exhaustedSaid = false
        state.update { it.copy(notAnswering = emptySet()) }
        generation++
        session++
        retry?.cancel(); retry = null; retryTicket++; retries = 0; retrying = emptyMap(); net.onChange = null; refusals = emptyMap(); gone = emptyMap(); failing = emptySet()
        runCatching { ts?.close() }; ts = null
        tsLoginPending = false
        holding = false
        tunPolicy = null
        restarting = false
        runCatching { server?.closeService() }
        closeSocks()
        if (!keepTun) { runCatching { tun?.close() }; tun = null }
        openTable(false)
        forgetLookups(this)
        net.stop()
        installRetry?.cancel(); installRetry = null
        app.unbraid.data.Mullvad.listChanged = null
        applied = null; rejected = null; lastGood = null; builtInstalls = -1; lastInput = null; probe = null; links = emptyMap(); appliedAccounts = emptyMap()
        running = null; tags = emptyMap(); tunnelDns = emptyMap(); tcpOnly = emptySet()
    }

    /** Caller holds [lock]. */
    private fun teardown(error: String?) {
        AfterUpdate.off(this)
        cancelResume()
        stopEngine()
        state.value = VpnState(error = error)
        // A failure in the background (resume after a pause, a revoked account) must not pass unnoticed.
        if (error != null) alert(error)
        // The service goes once no command is pending: a connect after this one keeps it and its notification.
        stopWanted = true
        main.post(::stopIfIdle)
    }

    private val resumeIntent get() = PendingIntent.getBroadcast(this, 4, Intent(this, Resume::class.java).putExtra(EXTRA_PAUSE, pauses), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * A pause's timed resume. The alarm wakes this receiver rather than starting the service itself, so the app runs even
     * when Android refuses the start (the process that paused was replaced by an update or ended meanwhile): the user is
     * then told, instead of the VPN staying off unseen.
     */
    class Resume : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val pause = intent.getIntExtra(EXTRA_PAUSE, 0)
            runCatching { ctx.startService(Intent(ctx, UnbraidVpn::class.java).putExtra(EXTRA_PAUSE, pause)) }
                .onFailure { AfterUpdate.tell(ctx, AfterUpdate.RESUME) }
        }
    }

    private fun cancelResume() {
        resume?.cancel(); resume = null
        PauseNote.clear(this)
        getSystemService(AlarmManager::class.java).cancel(resumeIntent)
    }

    /** Traffic leaves without the VPN until resumed, by hand or after [minutes]. Caller holds [lock]. */
    private fun pause(minutes: Int) = pause(if (minutes > 0) minutes * 60_000L else 0L, again = false, wakeAt = null)

    /**
     * Pauses for [ms] (0: until resumed by hand). [again]: a pause Android's own start found noted ([PauseNote]), in a
     * new process with no engine running. Its end is noted and its alarm set before the engine stops, so a kill at any
     * point after leaves a restart that pauses on, or reconnects.
     */
    private fun pause(ms: Long, again: Boolean, wakeAt: Long?) {
        if (state.value.phase != (if (again) VpnState.Phase.Off else VpnState.Phase.On)) return
        AfterUpdate.off(this) // paused by the user: an update never turns it back on
        // Read now: what the pause notice promises (apps offline or not) depends on them.
        val lockdown = isLockdownEnabled
        val alwaysOn = isAlwaysOn
        val pause = ++pauses
        // One deadline on the clock that counts sleep and ignores clock changes, for the coroutine and the alarm alike; a
        // noted one ([wakeAt]) kept as it was, so a pause restored after a kill never ends later.
        val wake = wakeAt ?: if (ms > 0) SystemClock.elapsedRealtime() + ms else 0L
        val until = if (wake > 0) System.currentTimeMillis() + (wake - SystemClock.elapsedRealtime()) else 0L
        // Only a pause with an end is noted: one until resumed by hand, kept by a note that couldn't be cleared, would never end.
        if (wake > 0) PauseNote.note(this, wake) else PauseNote.clear(this)
        // Short steps against the elapsed clock: a single delay doesn't count while the phone sleeps.
        var exact = true
        if (wake > 0) {
            // The coroutine stalls while the phone sleeps; this wakes it so background traffic doesn't stay unprotected.
            // On time when Android allows exact alarms; otherwise it may come somewhat later, and the notice says so.
            val am = getSystemService(AlarmManager::class.java)
            exact = am.canScheduleExactAlarms() && runCatching { am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, wake, resumeIntent) }.isSuccess
            if (!exact) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, wake, resumeIntent)
        }
        if (!again) stopEngine()
        stopWanted = false
        if (wake > 0) resume = scope.launch {
            while (SystemClock.elapsedRealtime() < wake) kotlinx.coroutines.delay((wake - SystemClock.elapsedRealtime()).coerceIn(1, 15_000))
            lock.withLock { if (state.value.phase == VpnState.Phase.Paused && pauses == pause) connect() }
        }
        val paused = VpnState(VpnState.Phase.Paused, pausedUntil = until, pausedAt = System.currentTimeMillis(), pausedAbout = !exact, lockdown = lockdown, alwaysOn = alwaysOn)
        getSystemService(NotificationManager::class.java).notify(1, pausedNotice(paused))
        // Last: whoever sees Paused (a resume refused for lack of consent) finds the alarm and notice to cancel.
        state.value = paused
    }

    /** The notice of pause [p]: until when, on time or about. */
    private fun pausedNotice(p: VpnState): Notification {
        val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(p.pausedUntil))
        return notification(if (p.pausedUntil <= 0) "Paused" else if (!p.pausedAbout) "Paused until $at" else "Paused until about $at", paused = true)
    }

    override fun onRevoke() = stoppedOutside("Android turned off $APP's VPN: another VPN app took over, or it was disconnected in Android's settings.")

    override fun onDestroy() {
        // Killed without teardown: never leave a tunnel or an "On" state behind. Cleanup waits for whatever holds the
        // lifecycle lock (a connect finishing its provider calls), so nothing it starts afterwards survives.
        destroyed = true
        if (current === this) current = null
        runCatching { unregisterReceiver(installed) }
        asked.update { a -> a?.takeIf { it.n > delivered } } // what it was handed it never will; newer ones go to the next
        cancelResume()
        scope.cancel()
        val before = cleanup
        cleanup = bg.launch {
            before?.join() // an older instance's cleanup first, so it can never undo a newer engine's files
            lock.withLock {
                // Whatever this instance still had waiting (provider answers, a stop from outside) is about its own connection.
                generation++
                session++
                // Closing the command server alone leaves a running engine (and its own copy of the tunnel) alive.
                runCatching { server?.closeService() }
                runCatching { server?.close() }
                closeSocks()
                server = null
                runCatching { tun?.close() }; tun = null
                net.quit()
                openTable(false)
                forgetLookups(this@UnbraidVpn)
                failing = emptySet()
                probe = null; links = emptyMap(); running = null; tags = emptyMap(); tunnelDns = emptyMap(); tcpOnly = emptySet()
                cancelResume() // again: a pause that was still running when the service went may have set an alarm
                AfterUpdate.off(this@UnbraidVpn)
                if (state.value.phase != VpnState.Phase.Off) state.value = VpnState()
            }
        }
        super.onDestroy()
    }

    // ---- notification -------------------------------------------------------------------------

    private fun notification(text: String, paused: Boolean = false): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW))
        fun service(code: Int, i: Intent) = PendingIntent.getService(this, code, i.setClass(this, UnbraidVpn::class.java), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // Pausing and disconnecting turn protection off, so neither works from the lock screen; resuming does.
        val action = if (paused) Notification.Action.Builder(null, "Resume", service(2, Intent())).build()
        else Notification.Action.Builder(null, "Pause 15 min", service(3, Intent(ACTION_PAUSE).putExtra(EXTRA_MINUTES, 15))).setAuthenticationRequired(true).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(if (paused) "$APP paused" else APP)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action)
            .addAction(Notification.Action.Builder(null, "Disconnect", service(1, Intent(ACTION_STOP))).setAuthenticationRequired(true).build())
            .build()
    }

    private fun alert(text: String) = alert(this, text)

    private fun notify(setup: Setup) {
        // Only websites whose rule applies, by the tailnet domain the running config was compiled with.
        val reported = lastInput?.tsSuffix
        val sites = app.unbraid.data.Sites.applied(setup, reported = reported)
        // The profile in use first: a switch made by itself ([AutoSwitch]) shows here, without a sound.
        notice(setup.profile?.let { "$it  ·  " }.orEmpty() + setup.activeRoutes.joinToString("  ·  ") { r ->
            if (r.id == setup.defaultRoute) "${r.code} default" else "${r.code} ${setup.apps.count { it.value == r.id } + sites.count { it.value == r.id }}" // its apps and websites
        }.ifEmpty { "Only selected apps" })
    }

    /** The running notice says [text]; a problem alert from before goes. */
    private fun notice(text: String) {
        getSystemService(NotificationManager::class.java).cancel(2)
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }

    // ---- PlatformInterface ----------------------------------------------------------------------

    override fun openTun(o: TunOptions): Int {
        if (prepare(this) != null) error("VPN permission missing")
        val policy = tunPolicy ?: error("VPN service is stopping")
        fun checkPolicy() {
            if (destroyed || tunPolicy !== policy) error("VPN service changed")
            policy.restore?.let(::requireCurrent)
        }
        checkPolicy()
        val requested = buildList { if (o.autoRoute) o.excludePackage.forEachString { add(it) } }
        val setup = policy.input?.setup
        fun allowed(pkg: String) = pkg == packageName || setup != null && pkg !in policy.input?.socksCompanions.orEmpty() &&
            Compiler.mayExclude(setup, pkg, packagesSharing(pkg)) { verifiedIdentity(setup, it) }
        val out = requested.filter(::allowed)
        fun builder(leaveOut: List<String>) = Builder().setSession("$APP").setMtu(o.mtu).setMetered(false).also { b ->
            o.inet4Address.forEach { b.addAddress(it.address(), it.prefix()) }
            o.inet6Address.forEach { b.addAddress(it.address(), it.prefix()) }
            if (o.autoRoute) {
                o.dnsServerAddress.forEachString { b.addDnsServer(it) }
                // From the input being started, not saved settings: a DNS permission the engine refused stays refused here too.
                Compiler.searchDomain(policy.input)?.let { b.addSearchDomain(it) }
                val r4 = o.inet4RouteAddress.toList()
                if (r4.isEmpty()) b.addRoute("0.0.0.0", 0) else r4.forEach { b.addRoute(it.ipPrefix()) }
                o.inet6RouteAddress.forEach { b.addRoute(it.ipPrefix()) }
                o.inet4RouteExcludeAddress.forEach { b.excludeRoute(it.ipPrefix()) }
                o.inet6RouteExcludeAddress.forEach { b.excludeRoute(it.ipPrefix()) }
                o.includePackage.forEachString { runCatching { b.addAllowedApplication(it) } }
                leaveOut.forEach { runCatching { b.addDisallowedApplication(it) } }
            }
        }
        fun establish(excluded: List<String>): android.os.ParcelFileDescriptor {
            val builder = builder(excluded)
            checkPolicy()
            val pfd = builder.establish() ?: error("VPN permission revoked")
            try { checkPolicy() } catch (e: Exception) { pfd.close(); throw e }
            return pfd
        }
        // Android turns the apps to leave out into user ids only when that list changes: with the same names it keeps the
        // old ids, so an app installed again (with a new user id) would stay inside. Then the tunnel is first built with
        // those apps inside (the safe side, for an instant), so the real list is a change and is read afresh.
        // Each app's user id is remembered from every tunnel built, also while it was away: one back with another id is
        // moved, whatever else changed in the list.
        val uidOf = { n: String -> runCatching { packageManager.getPackageUid(n, 0) }.getOrDefault(-1) }
        val ids = out.associateWith(uidOf).filterValues { it >= 0 }
        val moved = ids.filter { (n, id) -> knownIds[n]?.let { it != id } == true || n in unsure }.keys
        val interim = if (tun != null && moved.isNotEmpty()) establish(out - moved).also { tun?.close(); tun = it } else null
        var pfd = establish(out)
        tun?.close()
        tun = pfd
        // Looked up again: an app installed or removed while the tunnel was built may have met the builder with another id.
        val after = out.associateWith(uidOf).filterValues { it >= 0 }
        // One whose id changed in between may have met Android with the old one: not taken as known, built in two steps next.
        val changed = (after.keys + ids.keys).filterTo(HashSet()) { after[it] != ids[it] }
        changed += out.filterNot(::allowed)
        if (changed.isNotEmpty()) {
            val safe = establish(out - changed)
            pfd.close()
            tun = safe
            pfd = safe
        }
        knownIds.keys.retainAll(out.toSet() - changed)
        knownIds.putAll(after - changed)
        unsure = if (changed.isEmpty()) emptySet() else unsure + changed
        if (changed.isNotEmpty()) installs.incrementAndGet().also { scope.launch { reload() } }
        if (interim != null) Log.i(TAG, "tunnel built again: user ids of apps left out changed")
        checkPolicy()
        return pfd.fd
    }
    /** The user id each app left out had when a tunnel was last built with it installed. */
    private val knownIds = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Apps whose user id changed while a tunnel was built: the next one leaves them out in two steps. */
    @Volatile private var unsure: Set<String> = emptySet()

    override fun autoDetectInterfaceControl(fd: Int) { protect(fd) }
    override fun usePlatformAutoDetectInterfaceControl() = true

    override fun findConnectionOwner(proto: Int, src: String, sport: Int, dst: String, dport: Int): ConnectionOwner {
        val uid = cm.getConnectionOwnerUid(proto, InetSocketAddress(src, sport), InetSocketAddress(dst, dport))
        if (uid == Process.INVALID_UID) error("owner not found")
        val names = packageManager.getPackagesForUid(uid).orEmpty().toList()
        val pkgs = tunInput?.setup?.let { setup -> Compiler.ownerNames(setup, names) { verifiedIdentity(setup, it) } } ?: names
        return ConnectionOwner().apply {
            userId = uid
            // Use the kernel UID and the original package list, before routing can substitute a marker.
            individualApp = individualDnsOwner(Process.isApplicationUid(uid), uid, Process.myUid(), names, pkgs)
            userName = pkgs.firstOrNull().orEmpty()
            setAndroidPackageNames(Strings(pkgs))
        }
    }

    override fun startDefaultInterfaceMonitor(l: InterfaceUpdateListener) = net.listen(l)
    override fun closeDefaultInterfaceMonitor(l: InterfaceUpdateListener) = net.listen(null)
    override fun getInterfaces(): io.nekohasekai.libbox.NetworkInterfaceIterator {
        val all = java.net.NetworkInterface.getNetworkInterfaces().toList().associateBy { it.name }
        val list = cm.allNetworks.mapNotNull { n ->
            val lp = cm.getLinkProperties(n) ?: return@mapNotNull null
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
            val ni = all[lp.interfaceName] ?: return@mapNotNull null
            io.nekohasekai.libbox.NetworkInterface().apply {
                name = ni.name
                index = ni.index
                mtu = runCatching { ni.mtu }.getOrDefault(1500)
                addresses = Strings(ni.interfaceAddresses.map { "${it.address.hostAddress?.substringBefore('%')}/${it.networkPrefixLength}" })
                // No DNS list: our local resolver asks Android directly, and the engine treats a changed list (Android reorders it
                // on its own) as a new network, closing every connection.
                dnsServer = Strings(emptyList())
                type = when {
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                flags = android.system.OsConstants.IFF_UP or android.system.OsConstants.IFF_RUNNING
                metered = !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }.iterator()
        return object : io.nekohasekai.libbox.NetworkInterfaceIterator {
            override fun hasNext() = list.hasNext()
            override fun next() = list.next()
        }
    }
    override fun underNetworkExtension() = false
    override fun includeAllNetworks() = false
    override fun clearDNSCache() {}
    override fun readWIFIState() = null
    override fun localDNSTransport() = LocalDns(net)
    override fun sendNotification(n: io.nekohasekai.libbox.Notification) {}
    override fun cancelNotification(id: String, type: Int) {}
    override fun startNeighborMonitor(l: NeighborUpdateListener?) {}
    override fun closeNeighborMonitor(l: NeighborUpdateListener?) {}
    override fun registerMyInterface(name: String?) {}
    override fun usePlatformShell() = false
    override fun checkPlatformShell() = error("unsupported")
    override fun openShellSession(u: io.nekohasekai.libbox.PlatformUser?, c: String?, e: StringIterator?, t: String?, r: Int, cols: Int) = error("unsupported")
    override fun lookupUser(name: String?) = error("unsupported")
    override fun lookupSFTPServer() = error("unsupported")
    override fun readSystemSSHHostKey() = error("unsupported")
    override fun tailscaleHostname(): String = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: "unbraid"
    override fun readTailscaleState() = store.tsState
    override fun writeTailscaleState(state: String) { store.tsState = state }
    override fun usePlatformBridge() = false
    override fun createBridge(o: io.nekohasekai.libbox.BridgeOptions?) = error("unsupported")

    // ---- CommandServerHandler -------------------------------------------------------------------

    override fun serviceStop() = stoppedOutside("$APP's VPN was stopped from outside the app.")
    override fun serviceReload() { scope.launch { reload() } }
    override fun getSystemProxyStatus() = null
    override fun setSystemProxyEnabled(enabled: Boolean) {}
    override fun connectSSHAgent() = error("unsupported")
    override fun triggerNativeCrash() {}
    override fun writeDebugMessage(message: String?) { if (app.unbraid.BuildConfig.DEBUG) Log.d(TAG, Redact.clean(message)) }
}

private class Strings(list: List<String>) : StringIterator {
    private val it = list.iterator()
    private val n = list.size
    override fun len() = n
    override fun hasNext() = it.hasNext()
    override fun next() = it.next()
}

private inline fun io.nekohasekai.libbox.RoutePrefixIterator.forEach(f: (RoutePrefix) -> Unit) { while (hasNext()) f(next()) }
private fun io.nekohasekai.libbox.RoutePrefixIterator.toList(): List<RoutePrefix> = buildList { this@toList.forEach { add(it) } }
private inline fun StringIterator.forEachString(f: (String) -> Unit) { while (hasNext()) f(next()) }
private fun RoutePrefix.ipPrefix() = IpPrefix(InetAddress.getByName(address()), prefix())

/**
 * The engine's domain table on disk (fake IP → domain, cache.db) and whether a running engine has it open; see
 * [UnbraidVpn.forgetLookups].
 */
internal object LookupTable {
    private var open = false
    /** Set before an engine starts with the table, cleared once it has stopped. */
    @Synchronized fun open(v: Boolean) { open = v }
    @Synchronized fun isOpen() = open
    /** Deletes the table in [dir] unless an engine has it open; true if it was deleted. */
    @Synchronized fun forget(dir: File) = !open && File(dir, "cache.db").delete()
    /**
     * Deletes the table in [dir] also while an engine has it open, just before that engine gives way to one on a new
     * block, which must start from no file; fails unless the file is gone (already gone is fine).
     */
    @Synchronized fun clear(dir: File) { java.nio.file.Files.deleteIfExists(File(dir, "cache.db").toPath()) } // throws unless deleted or never there
    /** The table's size in [dir], 0 when there is none; throws when it can't be read (neither missing nor fine). */
    fun bytes(dir: File): Long = try { java.nio.file.Files.size(File(dir, "cache.db").toPath()) } catch (e: java.nio.file.NoSuchFileException) { 0 }
}

/** One line for Routes: which routes' apps are blocked and why, and that apps on a route that is gone are blocked too. */
internal fun blockedText(setup: Setup, blocked: Map<String, Exception>): String? = listOfNotNull(
    blocked.entries.firstOrNull()?.let { (_, e) -> "Apps on ${blocked.keys.joinToString { setup.route(it)?.name ?: it }} are blocked: ${Redact.message(e, "no connection")}" },
    "Apps on a route that no longer exists are blocked. Choose a route for them in $APP.".takeIf { Compiler.missing(setup).isNotEmpty() },
).joinToString(" ").ifEmpty { null }

/** The Block setting the health watcher starts from: the one compiled into the running tunnel ([built]), else Android's. */
internal fun startingLockdown(built: Compiler.Input?, android: Boolean) = built?.lockdown ?: android

/** A start ([on]) or a stop or pause, numbered [n] in the order asked. */
class Ask(val n: Int, val on: Boolean)

/**
 * Whether the VPN is on or about to be, for toggles: the newest request the service hasn't handled yet wins over the
 * phase, so a second quick tap undoes the first instead of repeating it, however long the first one takes.
 */
fun vpnOn(phase: VpnState.Phase, ask: Ask?): Boolean = ask?.on ?: (phase == VpnState.Phase.On || phase == VpnState.Phase.Connecting)

/** A stop or pause the service hasn't handled yet while the tunnel still runs: tiles show it under way, not done. */
fun stopping(phase: VpnState.Phase, ask: Ask?): Boolean = ask?.on == false && (phase == VpnState.Phase.On || phase == VpnState.Phase.Connecting)

/**
 * One logcat line about [e]. Errors from the engine or a library can echo a key with no name the scrubber knows, so
 * release builds keep only their class name; the app's own failures ([Fail]) and debug builds keep the cleaned text.
 */
internal fun logLine(e: Throwable, debug: Boolean = app.unbraid.BuildConfig.DEBUG): String = if (debug || e is Fail) Redact.log(e) else e.javaClass.simpleName

/** Engine text for logcat: cleaned in debug builds, left out in release ones (see [logLine]). */
internal fun logText(text: String?, debug: Boolean = app.unbraid.BuildConfig.DEBUG): String = if (debug) Redact.clean(text) else "(not logged)"
