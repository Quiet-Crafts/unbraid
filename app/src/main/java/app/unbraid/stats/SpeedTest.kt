package app.unbraid.stats

import app.unbraid.Unbraid
import app.unbraid.vpn.Compiler
import app.unbraid.vpn.TsNode
import app.unbraid.vpn.UnbraidVpn
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * On-demand speed test through one exit with M-Lab's ndt7 ([Ndt7]), on a server in the exit's own country. Results go
 * to [Stats] with the exit, provider and VPN server they were taken on.
 *
 * A Tailscale exit node that no route uses is tested by switching Tailscale to it for the test and back afterwards
 * (Tailscale has one exit node at a time); [app.unbraid.data.Store.tsTestNode] remembers a switch a killed app couldn't undo.
 */
object SpeedTest {
    enum class Phase { Preparing, Download, Upload, Done, Failed }

    /** Exit id of a Tailscale exit node tested without a route: "ts:" and its tailnet IP. */
    const val NODE = "ts:"

    /** M-Lab's limit for interactive users (measurementlab.net/aup). */
    const val PER_DAY = 40

    /**
     * Speeds in bits/s, [ping] in ms (median, low, high); null = not measured yet. [server] is the M-Lab server once located; [missing]
     * names the exit's country when M-Lab has no server there (the user may then test with the nearest one).
     */
    data class Run(
        val exit: String, val phase: Phase = Phase.Preparing, val now: Long = 0, val down: Long? = null, val up: Long? = null,
        val ping: Ndt7.Ping? = null, val elapsedMs: Long = 0, val server: Ndt7.Server? = null, val missing: String? = null, val error: String? = null,
    )

    /** Ten seconds each way. */
    const val MAX_SECONDS = 20
    private val _run = MutableStateFlow<Run?>(null)
    val run = _run.asStateFlow()
    @Volatile private var attempt = 0
    /** Tailscale exit-node switches, in order: a restore never overtakes the next test's switch. */
    private val tsq = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun client() = Libbox.newStandaloneCommandClient()

    /** Tailscale exit nodes the test can switch to: online, and not already carrying a route (those test as the route). */
    fun nodes(): List<TsNode> {
        val s = UnbraidVpn.state.value
        if (Compiler.TS_TAG !in UnbraidVpn.tags.values && !Unbraid.store.setup.value.tailscale) return emptyList()
        val routed = Unbraid.store.setup.value.tsExit
        return s.tsExitNodes.filter { it.online && it.id.isNotEmpty() && it.ip != routed }
    }

    /** Tests [exit]; [anywhere] = with the nearest M-Lab server even outside the exit's country. */
    fun start(exit: String, anywhere: Boolean = false): Boolean {
        val node = if (exit.startsWith(NODE)) nodes().firstOrNull { NODE + it.ip == exit } ?: return false else null
        val tag = if (node != null) Compiler.TS_TAG else UnbraidVpn.tags[exit] ?: return false
        cancel()
        if (Unbraid.store.testsToday >= PER_DAY) { _run.value = Run(exit, Phase.Failed, error = "M-Lab allows $PER_DAY tests a day. Try again tomorrow."); return false }
        val route = Unbraid.store.setup.value.route(exit)
        val vpnServer = UnbraidVpn.state.value.servers[exit]
        val via = node?.let { "Tailscale" } ?: route?.source.orEmpty()
        val name = node?.name ?: vpnServer?.name ?: route?.name.orEmpty()
        val ip = node?.ip ?: vpnServer?.ip.orEmpty()
        // Provider routes know their country; imported servers and Tailscale nodes are located by their address alone.
        val country = route?.takeIf { !it.imported && it.tsExit == null }?.loc?.countryCode?.uppercase()?.takeIf { !anywhere }
        val epoch = Stats.epoch
        val mine = ++attempt
        val stop = { attempt != mine }
        val set = { f: (Run) -> Run -> if (!stop()) _run.value?.let { _run.value = f(it) } }
        _run.value = Run(exit)
        val test = Runnable {
            try {
                val server = Ndt7.locate(tag, country)
                if (stop()) return@Runnable
                if (server == null) { set { it.copy(phase = Phase.Failed, missing = route?.loc?.country ?: country) }; return@Runnable }
                Unbraid.store.testsToday = Unbraid.store.testsToday + 1
                set { it.copy(server = server) }
                val ping = Ndt7.ping(tag, server)
                if (stop()) return@Runnable
                set { it.copy(ping = ping, phase = Phase.Download) }
                val t0 = android.os.SystemClock.elapsedRealtime()
                val elapsed = { android.os.SystemClock.elapsedRealtime() - t0 }
                val down = Ndt7.download(tag, server.download, stop) { bps -> set { it.copy(now = bps, elapsedMs = elapsed()) } }
                if (stop()) return@Runnable
                set { it.copy(phase = Phase.Upload, down = down, now = 0) }
                val up = Ndt7.upload(tag, server.upload, stop) { bps -> set { it.copy(now = bps, elapsedMs = elapsed()) } }
                if (stop()) return@Runnable
                set { it.copy(phase = Phase.Done, up = up, now = 0, elapsedMs = MAX_SECONDS * 1000L) }
                val done = _run.value ?: return@Runnable
                Stats.saveTest(Stats.Test(System.currentTimeMillis(), exit, via, name, ip, down, up, done.ping?.median ?: 0, 0, 0), epoch)
            } catch (e: Exception) {
                set { it.copy(phase = Phase.Failed, error = "The test couldn't finish. Check the connection and try again.") }
            } finally {
                if (node != null) restoreNode()
            }
        }
        if (node == null) Thread(test, "speedtest").start()
        else tsq.execute {
            // Switching is a blocking call; give Tailscale a moment to reach the new node before measuring.
            Unbraid.store.tsTestNode = true
            val ok = runCatching { client().setTailscaleExitNode(Compiler.TS_TAG, node.id) }.isSuccess
            if (ok) Thread.sleep(1500)
            if (stop()) return@execute
            if (ok) Thread(test, "speedtest").start() else { _run.value = Run(exit, Phase.Failed, error = "Tailscale couldn't switch to ${node.name}."); restoreNode() }
        }
        return true
    }

    /** The exit a running test measures through tunnel [tag], so its traffic is counted there; null = none. */
    fun exitOn(tag: String?): String? = _run.value?.exit?.takeIf { e -> testing && tag == if (e.startsWith(NODE)) Compiler.TS_TAG else UnbraidVpn.tags[e] }

    /** A test is under way (Tailscale may be on its test node on purpose). */
    val testing get() = _run.value?.phase.let { it == Phase.Preparing || it == Phase.Download || it == Phase.Upload }
    @Volatile private var restoring = false

    /** Puts Tailscale back on the exit node the routes use (or none). Safe to call any time; runs off the caller's thread. */
    fun restoreNode() { if (restoring) return; restoring = true; tsq.execute {
        val s = UnbraidVpn.state.value
        val routed = Unbraid.store.setup.value.tsExit
        // The routed node not in the list yet: keep the flag, the next Tailscale status retries.
        val want = if (routed == null) "" else s.tsExitNodes.firstOrNull { it.ip == routed }?.id ?: run { restoring = false; return@execute }
        if (runCatching { client().setTailscaleExitNode(Compiler.TS_TAG, want) }.isSuccess) Unbraid.store.tsTestNode = false
        restoring = false
    } }

    /** Stops a running test; its sockets close as soon as they notice. */
    fun cancel() {
        val wasNode = _run.value?.exit?.startsWith(NODE) == true
        attempt++
        if (testing) {
            Ndt7.abort()
            _run.value = null
            if (wasNode) restoreNode()
        }
    }

    /** Leaving the result screen forgets the last run (it's in History if stats are on). */
    fun clear() { if (!testing) _run.value = null }
}
