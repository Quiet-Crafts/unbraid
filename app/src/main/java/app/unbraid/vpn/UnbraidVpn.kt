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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

data class TsNode(val name: String, val ip: String, val online: Boolean, val id: String = "")

/** What the UI shows. Lives in-process; the service is the only writer. */
data class VpnState(
    val phase: Phase = Phase.Off,
    val since: Long = 0,
    val servers: Map<String, Server> = emptyMap(),
    val error: String? = null,
    val tsLoginUrl: String? = null,
    val tsState: String? = null,
    /** The DNS suffix the tailnet reports (usable form); whether apps may use it is [app.unbraid.data.Home.tsSuffixAllowed]. */
    val tsSuffix: String? = null,
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
) {
    enum class Phase { Off, Connecting, On, Paused }
}

class UnbraidVpn : VpnService(), PlatformInterface, CommandServerHandler {
    companion object {
        val state = MutableStateFlow(VpnState())
        /** Route id → engine tunnel tag of the running setup (speed tests dial through it). */
        @Volatile var tags: Map<String, String> = emptyMap()
        /** Tunnel tag → the DNS server that tunnel's own lookups use (health checks look their servers up there). */
        @Volatile var tunnelDns: Map<String, String> = emptyMap()
        /** The setup the engine runs (not the one being edited): stats attribute traffic against it. */
        @Volatile var running: Setup? = null
        /** Loopback port and password of the app's own way into each exit ([Compiler.PROBE]); see [Probe]. */
        @Volatile var probe: Pair<Int, String>? = null
        /** Servers ("provider|ip") that failed the last health check: the next connect, even a manual one, avoids them. */
        @Volatile var failing: Set<String> = emptySet()
        /**
         * The engine's cache: fake IP → domain, kept so connections survive a reload. It names every site the phone
         * looked up, so it never outlives a connection: deleted on disconnect, pause, connect and app start (for a killed
         * run). Each new table hands out fake IPs afresh, from the next block of the pool ([Compiler.FAKE_RANGES]).
         * Never deleted under a running engine: its next reload would start an empty table in the same block.
         */
        fun forgetLookups(ctx: Context) = synchronized(this) { !tableOpen && File(ctx.filesDir, "cache.db").delete() }
        /** An engine has cache.db open (see [forgetLookups]); set before it starts, cleared once it has stopped. */
        private var tableOpen = false
        /** The fake-IP block of the open or last table; the store only carries it across process restarts. */
        private var lastBlock: Int? = null
        private fun openTable(open: Boolean) = synchronized(this) { tableOpen = open }
        private const val ACTION_STOP = "app.unbraid.STOP"
        private const val ACTION_PAUSE = "app.unbraid.PAUSE"
        private const val ACTION_FLAGS = "app.unbraid.FLAGS"
        private const val EXTRA_MINUTES = "minutes"
        /** On the timed resume: which pause it ends ([pauses]). */
        private const val EXTRA_PAUSE = "pause"
        /** Pauses in this process; a timed resume for an older one, or after a disconnect, does nothing. */
        @Volatile private var pauses = 0
        private const val CHANNEL = "vpn"
        private const val TAG = "Unbraid"

        /** Starting a foreground service that can't go foreground crashes the app, so consent is checked first. */
        fun start(ctx: Context) {
            if (prepare(ctx) != null) {
                // Paused while another VPN app took over: the paused service ends itself on this start (alarm, notification).
                if (state.value.phase == VpnState.Phase.Paused) runCatching { ctx.startService(Intent(ctx, UnbraidVpn::class.java)) }
                state.value = VpnState(error = "Open $APP to allow it as your VPN")
                return
            }
            ctx.startForegroundService(Intent(ctx, UnbraidVpn::class.java))
        }
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, UnbraidVpn::class.java).setAction(ACTION_STOP))
        /** Drops the tunnel for [minutes] (0 = until resumed); a start while paused resumes. */
        /** Reads Android's Always-on and Block-connections settings again (they can change while connected). Only while running. */
        fun refreshFlags(ctx: Context) { if (state.value.phase == VpnState.Phase.On || state.value.phase == VpnState.Phase.Paused) runCatching { ctx.startService(Intent(ctx, UnbraidVpn::class.java).setAction(ACTION_FLAGS)) } }

        fun pause(ctx: Context, minutes: Int) = ctx.startService(Intent(ctx, UnbraidVpn::class.java).setAction(ACTION_PAUSE).putExtra(EXTRA_MINUTES, minutes))

        private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private var engineReady = false

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
        engineSetup(this)
        cm = getSystemService(ConnectivityManager::class.java)
        net = NetMonitor(cm)
        current = this
        scope.launch {
            cleanup?.join()
            for ((intent, locked) in inbox) {
                lock.withLock { waiting.decrementAndGet(); handle(intent, locked) }
                kotlinx.coroutines.withContext(Dispatchers.Main) { pending--; stopIfIdle() }
            }
        }
    }

    private fun stopIfIdle() {
        if (pending > 0 || !stopWanted || destroyed) return
        if (stopSelfResult(lastStartId)) { stopWanted = false; stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Disconnecting and pausing drop protection: never from a locked phone, whoever holds the PendingIntent
        // (a notification listener, the widget host). The in-app controls are only reachable unlocked anyway.
        lastStartId = startId
        val locked = getSystemService(KeyguardManager::class.java).isDeviceLocked
        // A timed resume reaching a paused service keeps the paused notice: the pause may be a newer one it doesn't end.
        val timedWhilePaused = (intent?.getIntExtra(EXTRA_PAUSE, 0) ?: 0) != 0 && state.value.phase == VpnState.Phase.Paused
        if (intent?.action == null && !timedWhilePaused) {
            // Another VPN app may have taken the slot since: Android then refuses this service type. Never crash on it.
            val allowed = prepare(this) == null && runCatching {
                // The systemExempted type exists from Android 14; on 13 a foreground service needs no type.
                if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(1, notification("Connecting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
                else startForeground(1, notification("Connecting…"))
            }.isSuccess
            // Refused while off or paused, so no engine runs: stop now, as Android requires without startForeground.
            if (!allowed && (state.value.phase == VpnState.Phase.Off || state.value.phase == VpnState.Phase.Paused)) {
                cancelResume()
                state.value = VpnState(error = "Open $APP to allow it as your VPN")
                stopSelf()
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
                    if (state.value.phase == VpnState.Phase.On) notify(store.setup.value) else stopWanted = state.value.phase == VpnState.Phase.Off
                    return
                }
                connect()
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

    @OptIn(FlowPreview::class)
    private fun connect() {
        if (state.value.phase == VpnState.Phase.On) { // a repeated start (Always-on, tile)
            readFlags()
            if (state.value.phase == VpnState.Phase.On) notify(store.setup.value) // a failed reload may have ended it
            return
        }
        cancelResume()
        state.value = VpnState(VpnState.Phase.Connecting)
        try {
            net.start()
            val setup = store.setup.value
            val accounts = store.accounts.value
            // The app's own tests get a fresh way in with each engine start (see apply); none until it runs.
            probe = null
            val picked = pickLinks(setup, emptyMap(), accounts)
            if (destroyed) return // the service went away during the provider calls: start no engine for it
            forgetLookups(this)
            val srv = server ?: CommandServer(this, this).also { it.start(); server = it }
            apply(srv, setup, picked, accounts)
            state.value = VpnState(VpnState.Phase.On, System.currentTimeMillis(), picked.links.mapValues { it.value.server }, blockedText(setup, picked.blocked), lockdown = isLockdownEnabled, alwaysOn = isAlwaysOn, stuck = stuck(setup, picked.blocked))
            stopWanted = false
            notify(setup)
            if (setup.tailscale) watchTailscale()
            // Anything edited while connecting is picked up here: only the applied setup is skipped.
            // Setup edits and sign-ins/outs both change what the engine must run.
            health = scope.launch { watchHealth() }
            watch = combine(store.setup, store.accounts) { s, a -> s to a }.debounce(400).onEach { (s, a) ->
                // Not awaited: a slow provider must never hold up the next edit (which may have to block an app at once).
                if (lock.withLock { (s != applied || a != appliedAccounts) && (s to a) != rejected }) scope.launch { reload() }
            }.launchIn(scope)
        } catch (e: Exception) {
            Log.e(TAG, "connect: ${Redact.log(e)}")
            teardown(Redact.message(e, "Could not connect"))
        }
    }

    /** Validates first, so a bad config never replaces a working engine. */
    /** [accounts] is the snapshot [links] were picked with: a sign-out after it differs, so the watcher reloads again. */
    private fun apply(srv: CommandServer, setup: Setup, picked: LinkPicker.Result, accounts: Map<String, String>) {
        generation++
        val links = picked.links
        val blocked = picked.blocked.keys
        var input = input(setup, links, blocked)
        var json = Compiler.compile(input)
        // A favourite, a rename or a second sign-in changes the setup but not what the engine runs: keep every tunnel up.
        val fresh = json != lastGood
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
            if (input.fakeBlock != lastBlock) engine("$APP couldn't save its state. Try again.") { store.fakeBlock = input.fakeBlock }
            lastBlock = input.fakeBlock
            restarting = true
            openTable(true)
            engine("The connection engine couldn't start.") { srv.startOrReloadService(json, OverrideOptions()) }
            restarting = false
            probe = next
            lastInput = input
            // The new engine numbers its connections afresh; stats read the new servers when they first see them.
            state.update { it.copy(servers = links.mapValues { l -> l.value.server }) }
        }
        lastGood = json
        tunnelDns = runCatching { Compiler.probeDns(json) }.getOrDefault(emptyMap())
        tags = Compiler.shared(setup, LinkPicker.ported(setup, links, portStep), blocked) + setup.activeRoutes.filter { it.tsExit != null }.associate { it.id to Compiler.TS_TAG }
        val was = running
        running = setup
        // After tags and running, which stats label new connections by; also when only the setup changed (a city that
        // resolves to the same server is still another place in the history).
        if (fresh || was != setup) app.unbraid.stats.Stats.post { app.unbraid.stats.Collector.retimeNow() }
        applied = setup
        appliedAccounts = accounts
        rejected = null
        this.links = links
        retryBlocked(picked.blocked)
    }

    private var retry: Job? = null
    private var retries = 0

    /**
     * Routes blocked for a passing reason (no network, provider unreachable) try again after 5 s, 15 s, 30 s, 1 min,
     * then every 5 min; signed-out or refused ones wait for a sign-in, which reloads by itself.
     */
    private fun retryBlocked(blocked: Map<String, Exception>) {
        retry?.cancel(); retry = null
        if (blocked.values.all { it is AuthException }) { retries = 0; net.onChange = null; return }
        val wait = listOf(5, 15, 30, 60, 300)[minOf(retries++, 4)] * 1000L
        retry = scope.launch {
            kotlinx.coroutines.delay(wait)
            reload()
        }
        // A new network is the likeliest fix: try at once, with the backoff kept for what follows.
        net.onChange = { scope.launch { lock.withLock { retry?.cancel(); retry = null }; reload() } }
    }

    /** Providers of [blocked] routes, and whether one refused its account. */
    private fun stuck(setup: Setup, blocked: Map<String, Exception>) = blocked.entries.mapNotNull { (id, e) -> setup.route(id)?.takeIf { !it.imported }?.let { it.loc.provider to (e is AuthException) } }
        .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.any { it } }

    /** One line for Routes: which routes' apps are blocked and why. */
    private fun blockedText(setup: Setup, blocked: Map<String, Exception>): String? = blocked.entries.firstOrNull()?.let { (_, e) ->
        "Apps on ${blocked.keys.joinToString { setup.route(it)?.name ?: it }} are blocked: ${Redact.message(e, "no connection")}"
    }

    /**
     * What ran before a failed reload, back on the engine exactly as it was compiled, with a fresh probe pair; false if it
     * won't start. Until then the app's own tests have no way in.
     */
    private fun restoreLastGood(srv: CommandServer): Boolean = runCatching {
        probe = null
        val last = lastInput ?: return false
        val next = newProbe()
        // What ran, but never with tailnet DNS authority or a sign-in way around the exit that was taken back since.
        val now = store.setup.value.home
        val input = Compiler.Input(last.setup.copy(home = last.setup.home.copy(tsSuffixAllowed = now.tsSuffixAllowed)), last.links, last.selfPackage, last.lockdown,
            last.tsDir, last.cacheFile, store.tsSuffix, next, last.fakeBlock, last.blocked, last.tsLoginPending && tsLoginPending)
        val json = Compiler.compile(input)
        srv.startOrReloadService(json, OverrideOptions())
        lastBlock = input.fakeBlock
        probe = next
        lastGood = json
        lastInput = input
        true
    }.getOrDefault(false)

    /** Engine errors can quote the config, keys included: log them cleaned, show a fixed text, and drop the cause. */
    private inline fun engine(failure: String, f: () -> Unit) {
        try { f() } catch (e: Exception) {
            Log.e(TAG, "engine: ${Redact.log(e)}")
            throw Fail(failure)
        }
    }

    /**
     * Applies the newest setup without dropping the tunnel; routes whose location is unchanged keep their server. Takes
     * [lock] itself and lets go of it while providers are asked for servers, so a newer change (one that must block an
     * app at once) never waits for them; what they answer is applied only if nothing changed meanwhile.
     */
    private suspend fun reload(keep: Map<String, Link>? = null, avoid: Set<String> = emptySet(), keepFrom: Int = generation) {
        class Ask(val srv: CommandServer, val setup: Setup, val accounts: Map<String, String>, val ready: LinkPicker.Result, val applied: Setup?,
                  val appliedAccounts: Map<String, String>, val refused: Map<String, Exception>, val last: Map<String, Link>, val generation: Int)
        val ask = lock.withLock {
            if (state.value.phase != VpnState.Phase.On) return
            val srv = server ?: return
            val setup = store.setup.value
            val accounts = store.accounts.value
            // [keep] was taken without the lock: only valid if nothing was installed since ([keepFrom]).
            val ready = pickLinks(setup, keep?.takeIf { keepFrom == generation } ?: links, accounts, avoid, offline = true)
            if (ready.blocked.values.none { it is LinkPicker.Waiting }) { install(srv, setup, ready, accounts); return }
            // While a provider is asked for a server, apps moving off Direct already leave through their new route, or
            // wait blocked if that is the one still waiting: never on their real address for the time it takes. A tunnel
            // whose account was signed out or refused stops now too, not once the others are found.
            val stopNow = ready.blocked.any { (id, e) -> e is AuthException && id in links }
            if ((stopNow || LinkPicker.leavesDirect(applied, setup, setup.activeRoutes.mapTo(HashSet()) { it.id })) && !install(srv, setup, ready, accounts)) return
            Ask(srv, setup, accounts, ready, applied, appliedAccounts, refusedFor(setup, accounts), store.lastLinks, generation)
        }
        // A route without a tunnel (signed out, refused, unreachable) is blocked; the others keep running. Tunnels come
        // from the offline pass, which already dropped any a changed location or account can't keep.
        val picked = LinkPicker.pick(ask.setup, ask.ready.links, ask.applied, ask.appliedAccounts, ask.accounts, ask.last, avoid + failing, ask.refused, false, Providers::get)
        lock.withLock {
            // Stopped, reloaded or edited during the provider calls: the newer state wins (the watcher reloads an edit).
            if (state.value.phase != VpnState.Phase.On || generation != ask.generation || store.setup.value != ask.setup || store.accounts.value != ask.accounts) return
            noteRefusals(ask.setup, ask.accounts, picked)
            runCatching { store.rememberLinks(picked.remember) }.onFailure { Log.w(TAG, "last servers not saved") }
            install(ask.srv, ask.setup, picked, ask.accounts)
        }
    }

    /** Runs [picked] on the engine and shows it; on failure brings back what ran. False if it didn't apply. Caller holds [lock]. */
    private fun install(srv: CommandServer, setup: Setup, picked: LinkPicker.Result, accounts: Map<String, String>): Boolean = try {
        apply(srv, setup, picked, accounts)
        state.update { it.copy(servers = picked.links.mapValues { l -> l.value.server }, error = blockedText(setup, picked.blocked), stuck = stuck(setup, picked.blocked)) }
        notify(setup)
        if (setup.tailscale && ts == null) watchTailscale()
        true
    } catch (e: Exception) {
        Log.e(TAG, "reload: ${Redact.log(e)}")
        rejected = setup to accounts // don't retry the same broken pair in a loop; [applied] stays what runs
        // libbox stops the old engine before starting the new one: only then bring the last good config back. It comes
        // back with a new probe pair too: the old password may have been seen while the old engine's port was free.
        val restored = !restarting || restoreLastGood(srv)
        restarting = false
        if (restored) app.unbraid.stats.Stats.post { app.unbraid.stats.Collector.retimeNow() } // a new engine, numbered afresh
        if (restored) state.update { it.copy(error = Redact.message(e, "Change not applied")) } else teardown(Redact.message(e, "Connection lost"))
        false
    }

    private var health: Job? = null

    /**
     * Every minute, checks each provider tunnel the app can move ([Health]); one that fails three checks in a row
     * gets another server of its location. A round where every tunnel fails while the phone's own network does too is
     * most likely that network: no server is blamed, and checks slow down (up to every ~8 min) until one works again.
     */
    private suspend fun watchHealth() {
        val fails = HashMap<String, Int>()
        var slow = 1
        var last: Pair<Map<String, Link>, android.net.Network?>? = null // the tunnels and network the streaks are about
        while (true) {
            kotlinx.coroutines.delay((48_000L + kotlin.random.Random.nextLong(24_000)) * slow) // ~1 min, jittered
            if (state.value.phase != VpnState.Phase.On || net.network == null || SpeedTest.testing) continue
            var servers = emptyMap<String?, List<String>>() // tag → its servers as checked, before a reload can swap them
            var seen = links // what was checked: results about other tunnels or another network are thrown away
            val network = net.network
            // Ports Automatic learned on another network don't hold here: back to each provider's first port.
            if (network != portNet) { portNet = network; if (portStep.isNotEmpty()) { portStep = emptyMap(); reload(); continue } }
            val byTag = lock.withLock {
                seen = links
                val s = running ?: return@withLock emptyMap()
                // Pinned and Dedicated IP routes are checked too (to notice a removed device), but never moved.
                links.keys.filter { id -> s.route(id)?.imported == false }
                    .groupBy { tags[it] }.filterKeys { it != null && it != Compiler.TS_TAG }
                    .also { m -> servers = m.mapValues { (_, ids) -> ids.mapNotNull { id -> s.route(id)?.let { r -> links[id]?.let { LinkPicker.serverKey(r, it) } } } } }
            }
            if (byTag.isEmpty()) continue
            // A provider whose new key is still reaching its servers isn't judged yet.
            val settingUp = Providers.settingUp()
            val checked = byTag.filter { (_, ids) -> ids.none { running?.route(it)?.loc?.provider in settingUp } }.keys.filterNotNull().toSet()
            if (checked.isEmpty()) continue
            val down = Health.blame(checked.filter { !Health.reachable(it) }.toSet(), checked) { Health.direct(network) }
            val move = lock.withLock {
                // Stopped, reloaded or moved to another network meanwhile: start counting afresh.
                if (state.value.phase != VpnState.Phase.On || links !== seen || net.network != network) { fails.clear(); failing = emptySet(); last = null; return@withLock null }
                // Other tunnels or another network than the last round's: their streaks and blame don't carry over.
                if (last != (seen to network)) { fails.clear(); failing = emptySet(); slow = 1; last = seen to network }
                if (down == null) { fails.clear(); failing = emptySet(); slow = minOf(slow * 2, 8); return@withLock null }
                slow = 1
                // The next connect, even a manual one, avoids them.
                failing = down.flatMap { servers[it].orEmpty() }.toSet()
                checked.forEach { t -> fails[t] = if (t in down) (fails[t] ?: 0) + 1 else 0 }
                val dead = byTag.filterKeys { (fails[it] ?: 0) >= 3 }.values.flatten()
                if (dead.isEmpty()) return@withLock null
                dead.forEach { fails.remove(tags[it]) }
                val s = running ?: return@withLock null
                // A dead tunnel on Automatic also tries its provider's next port: a failed check can't tell a blocked port from a dead server.
                val stepped = dead.mapNotNull { id -> s.route(id)?.loc?.provider?.takeIf { p -> (links[id] as? app.unbraid.data.WgLink)?.ports.orEmpty().size > 1 && s.opt(p, app.unbraid.data.PORT_OPT) == app.unbraid.data.AUTO } }.toSet()
                portStep = portStep + stepped.associateWith { (portStep[it] ?: 0) + 1 }
                val movable = dead.filter { id -> s.route(id)?.loc?.let { it.pinned == null && it.group != app.unbraid.data.Nord.DEDICATED_IP } == true }
                Log.w(TAG, "health: ${dead.size} route(s) stopped answering, ${movable.size} to move to another server")
                Move(links - movable.toSet(), movable.mapNotNull { id -> s.route(id)?.let { r -> links[id]?.let { LinkPicker.serverKey(r, it) } } }.toSet(), generation,
                    dead.mapNotNull { id -> s.route(id)?.loc?.provider }.toSet(), movable.isNotEmpty() || stepped.isNotEmpty())
            } ?: continue
            // A dead tunnel can mean the provider dropped this phone (its device removed on the website): then its routes
            // wait blocked for a new sign-in instead of trying server after server.
            val accounts = store.accounts.value
            val dropped = move.providers.filter { p -> accounts[p]?.let { Providers.get(p)?.revoked(it) } == true }
            if (dropped.isNotEmpty()) lock.withLock {
                dropped.forEach { p -> refusals = refusals + (p to (accounts[p] to AuthException("${Providers.get(p)?.name ?: p} no longer has this phone (it was removed from your account). Sign in again"))) }
            }
            // The newest setup, not the running one: a change still waiting to apply must never be undone here.
            if (move.anyMovable || dropped.isNotEmpty()) reload(move.keep, move.avoid, move.generation)
        }
    }

    private class Move(val keep: Map<String, Link>, val avoid: Set<String>, val generation: Int, val providers: Set<String>, val anyMovable: Boolean)

    /** Tunnels the engine runs now, per route id. */
    private var links: Map<String, Link> = emptyMap()

    /** Caller holds [lock]. */
    private fun pickLinks(setup: Setup, keep: Map<String, Link>, accounts: Map<String, String>, avoid: Set<String> = emptySet(), offline: Boolean = false): LinkPicker.Result {
        val r = LinkPicker.pick(setup, keep, applied, appliedAccounts, accounts, store.lastLinks, avoid + failing, refusedFor(setup, accounts), offline, Providers::get)
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
        return setup.activeRoutes.filter { !it.imported }.mapNotNull { r ->
            (refusals[r.loc.provider]?.second ?: r.loc.pinned?.let { gone["${r.loc.provider}|${it.host}"] })?.let { r.id to it }
        }.toMap()
    }

    private fun noteRefusals(setup: Setup, accounts: Map<String, String>, r: LinkPicker.Result) = r.blocked.forEach { (id, e) ->
        setup.route(id)?.takeIf { !it.imported }?.let {
            if (e is AuthException) refusals = refusals + (it.loc.provider to (accounts[it.loc.provider] to e))
            if (e is app.unbraid.data.NoLongerOffered) it.loc.pinned?.let { p -> gone = gone + ("${it.loc.provider}|${p.host}" to e) }
        }
    }

    /** Pinned servers ("provider|host") their provider's list no longer has, while connected. */
    private var gone: Map<String, Exception> = emptyMap()

    /** Tailscale gave a sign-in link and waits for the user ([Compiler.Input.tsLoginPending]). */
    @Volatile private var tsLoginPending = false

    /** Providers that refused an account, with that account and why: their routes stay blocked until it changes. */
    private var refusals: Map<String, Pair<String?, Exception>> = emptyMap()

    /** Provider id → how many of its ports Automatic has moved past on this network ([LinkPicker.ported]). */
    @Volatile private var portStep: Map<String, Int> = emptyMap()
    /** The network [portStep] was learned on. */
    private var portNet: android.net.Network? = null

    private fun input(setup: Setup, links: Map<String, Link>, blocked: Set<String>, p: Pair<Int, String>? = probe) =
        Compiler.Input(setup, LinkPicker.ported(setup, links, portStep), packageName, isLockdownEnabled, File(filesDir, "tailscale").path, File(filesDir, "cache.db").path, store.tsSuffix, p, fakeBlock(), blocked, tsLoginPending)

    /** The open table's fake-IP block for a reload; a new table takes the next one, refusing the rest. */
    private fun fakeBlock() = Compiler.nextBlock(lastBlock ?: store.fakeBlock, synchronized(Companion) { tableOpen })


    /** The app's own way into each exit ([Compiler.PROBE]): a free loopback port and a 24-byte random password. */
    private fun newProbe(): Pair<Int, String> =
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort } to
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also { java.security.SecureRandom().nextBytes(it) })

    private fun watchTailscale() {
        ts = runCatching {
            Libbox.newStandaloneCommandClient().subscribeTailscaleStatus(object : TailscaleStatusHandler {
                override fun onError(message: String?) { Log.w(TAG, "tailscale: ${Redact.clean(message)}") }
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
                                if (p.exitNodeOption && ips.hasNext()) add(TsNode(p.hostName.ifEmpty { p.dnsName.substringBefore('.') }, ips.next(), p.online, p.stableID))
                            }
                        }
                    }.sortedBy { it.name.lowercase() }
                    val suffix = Compiler.tsSuffix(ep.magicDNSSuffix)
                    val reported = if (ep.backendState == "Running") suffix else store.tsSuffix
                    state.update { s -> s.copy(tsLoginUrl = ep.authURL.ifEmpty { null }, tsState = ep.backendState, tsExitNodes = nodes, tsSuffix = reported) }
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
        }.onFailure { Log.w(TAG, "tailscale status: ${Redact.log(it)}") }.getOrNull()
    }

    private suspend fun shutdown(error: String?) = lock.withLock { teardown(error) }

    /** Stops the engine and the tunnel; the service itself stays. Caller holds [lock]. */
    private fun stopEngine() {
        watch?.cancel(); watch = null
        health?.cancel(); health = null
        generation++
        retry?.cancel(); retry = null; retries = 0; net.onChange = null; refusals = emptyMap(); gone = emptyMap(); failing = emptySet()
        runCatching { ts?.close() }; ts = null
        runCatching { server?.closeService() }
        runCatching { tun?.close() }; tun = null
        openTable(false)
        forgetLookups(this)
        net.stop()
        applied = null; rejected = null; lastGood = null; lastInput = null; probe = null; links = emptyMap(); appliedAccounts = emptyMap()
    }

    /** Caller holds [lock]. */
    private fun teardown(error: String?) {
        cancelResume()
        stopEngine()
        state.value = VpnState(error = error)
        // A failure in the background (resume after a pause, a revoked account) must not pass unnoticed.
        if (error != null) alert(error)
        // The service goes once no command is pending: a connect after this one keeps it and its notification.
        stopWanted = true
        main.post(::stopIfIdle)
    }

    private val resumeIntent get() = PendingIntent.getService(this, 4, Intent(this, UnbraidVpn::class.java).putExtra(EXTRA_PAUSE, pauses), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun cancelResume() {
        resume?.cancel(); resume = null
        getSystemService(AlarmManager::class.java).cancel(resumeIntent)
    }

    /** Traffic leaves without the VPN until resumed, by hand or after [minutes]. Caller holds [lock]. */
    private fun pause(minutes: Int) {
        if (state.value.phase != VpnState.Phase.On) return
        // Read now: what the pause notice promises (apps offline or not) depends on them.
        val lockdown = isLockdownEnabled
        val alwaysOn = isAlwaysOn
        stopEngine()
        stopWanted = false
        val pause = ++pauses
        val until = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        // Short steps against the elapsed clock: a single delay doesn't count while the phone sleeps.
        var exact = true
        if (until > 0) {
            // One deadline on the clock that counts sleep and ignores clock changes, for the coroutine and the alarm alike.
            val wake = SystemClock.elapsedRealtime() + minutes * 60_000L
            resume = scope.launch {
                while (SystemClock.elapsedRealtime() < wake) kotlinx.coroutines.delay((wake - SystemClock.elapsedRealtime()).coerceIn(1, 15_000))
                lock.withLock { if (state.value.phase == VpnState.Phase.Paused && pauses == pause) connect() }
            }
            // The coroutine stalls while the phone sleeps; this wakes it so background traffic doesn't stay unprotected.
            // On time when Android allows exact alarms; otherwise it may come somewhat later, and the notice says so.
            val am = getSystemService(AlarmManager::class.java)
            exact = am.canScheduleExactAlarms() && runCatching { am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, wake, resumeIntent) }.isSuccess
            if (!exact) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, wake, resumeIntent)
        }
        val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(until))
        getSystemService(NotificationManager::class.java).notify(1, notification(if (until <= 0) "Paused" else if (exact) "Paused until $at" else "Paused until about $at", paused = true))
        // Last: whoever sees Paused (a resume refused for lack of consent) finds the alarm and notice to cancel.
        state.value = VpnState(VpnState.Phase.Paused, pausedUntil = until, pausedAt = System.currentTimeMillis(), pausedAbout = !exact, lockdown = lockdown, alwaysOn = alwaysOn)
    }

    override fun onRevoke() { scope.launch { shutdown(null) } }

    override fun onDestroy() {
        // Killed without teardown: never leave a tunnel or an "On" state behind. Cleanup waits for whatever holds the
        // lifecycle lock (a connect finishing its provider calls), so nothing it starts afterwards survives.
        destroyed = true
        if (current === this) current = null
        cancelResume()
        scope.cancel()
        val before = cleanup
        cleanup = bg.launch {
            before?.join() // an older instance's cleanup first, so it can never undo a newer engine's files
            lock.withLock {
                // Closing the command server alone leaves a running engine (and its own copy of the tunnel) alive.
                runCatching { server?.closeService() }
                runCatching { server?.close() }
                server = null
                runCatching { tun?.close() }; tun = null
                net.quit()
                openTable(false)
                forgetLookups(this@UnbraidVpn)
                failing = emptySet()
                cancelResume() // again: a pause that was still running when the service went may have set an alarm
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

    private fun alert(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("alerts", "Problems", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, Notification.Builder(this, "alerts").setSmallIcon(R.drawable.ic_tile).setContentTitle("$APP is off")
            .setContentText(text).setContentIntent(open).setAutoCancel(true).build())
    }

    private fun notify(setup: Setup) {
        getSystemService(NotificationManager::class.java).cancel(2)
        val text = setup.activeRoutes.joinToString("  ·  ") { r ->
            if (r.id == setup.defaultRoute) "${r.code} default" else "${r.code} ${setup.apps.count { it.value == r.id }}"
        }.ifEmpty { "Only selected apps" }
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }

    // ---- PlatformInterface ----------------------------------------------------------------------

    override fun openTun(o: TunOptions): Int {
        if (prepare(this) != null) error("VPN permission missing")
        val b = Builder().setSession("$APP").setMtu(o.mtu).setMetered(false)
        o.inet4Address.forEach { b.addAddress(it.address(), it.prefix()) }
        o.inet6Address.forEach { b.addAddress(it.address(), it.prefix()) }
        if (o.autoRoute) {
            o.dnsServerAddress.forEachString { b.addDnsServer(it) }
            // Short tailnet names ("nas") expand to the MagicDNS domain, as with the Tailscale app.
            Compiler.trustedSuffix(store.tsSuffix, store.setup.value.home)?.takeIf { store.setup.value.home.tailscale }?.let { b.addSearchDomain(it) }
            val r4 = o.inet4RouteAddress.toList()
            if (r4.isEmpty()) b.addRoute("0.0.0.0", 0) else r4.forEach { b.addRoute(it.ipPrefix()) }
            o.inet6RouteAddress.forEach { b.addRoute(it.ipPrefix()) }
            o.inet4RouteExcludeAddress.forEach { b.excludeRoute(it.ipPrefix()) }
            o.inet6RouteExcludeAddress.forEach { b.excludeRoute(it.ipPrefix()) }
            o.includePackage.forEachString { runCatching { b.addAllowedApplication(it) } }
            o.excludePackage.forEachString { runCatching { b.addDisallowedApplication(it) } }
        }
        val pfd = b.establish() ?: error("VPN permission revoked")
        tun?.close()
        tun = pfd
        return pfd.fd
    }

    override fun autoDetectInterfaceControl(fd: Int) { protect(fd) }
    override fun usePlatformAutoDetectInterfaceControl() = true
    override fun useProcFS() = false

    override fun findConnectionOwner(proto: Int, src: String, sport: Int, dst: String, dport: Int): ConnectionOwner {
        val uid = cm.getConnectionOwnerUid(proto, InetSocketAddress(src, sport), InetSocketAddress(dst, dport))
        if (uid == Process.INVALID_UID) error("owner not found")
        val pkgs = packageManager.getPackagesForUid(uid).orEmpty().toList()
        return ConnectionOwner().apply {
            userId = uid
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

    override fun serviceStop() { scope.launch { shutdown(null) } }
    override fun serviceReload() { scope.launch { reload() } }
    override fun getSystemProxyStatus() = null
    override fun setSystemProxyEnabled(enabled: Boolean) {}
    override fun connectSSHAgent() = error("unsupported")
    override fun triggerNativeCrash() {}
    override fun writeDebugMessage(message: String?) { Log.d(TAG, Redact.clean(message)) }
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
