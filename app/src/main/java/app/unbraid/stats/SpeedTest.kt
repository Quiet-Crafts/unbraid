package app.unbraid.stats

import app.unbraid.Unbraid
import app.unbraid.vpn.Compiler
import app.unbraid.vpn.TsNode
import app.unbraid.vpn.UnbraidVpn
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * On-demand speed test through one exit with M-Lab's ndt7 ([Ndt7]), on a server in the exit's own country when known. Results go
 * to [Stats] with the exit, provider and VPN server they were taken on.
 *
 * A Tailscale exit node that no route uses is tested by switching Tailscale to it for the test and back afterwards
 * (Tailscale has one exit node at a time); [app.unbraid.data.Store.tsTestFrom] remembers a switch a killed app couldn't undo.
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
    /** Guards [attempt] with [_run]: a cancelled test can't publish once a newer one (or none) took over. */
    private val lock = Any()
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
        val setup = UnbraidVpn.running ?: Unbraid.store.setup.value // what the engine runs, for the label and the record
        val route = setup.route(exit)
        val vpnServer = UnbraidVpn.state.value.servers[exit]
        val via = node?.let { "Tailscale" } ?: route?.source.orEmpty()
        val name = node?.name ?: vpnServer?.name ?: route?.name.orEmpty()
        val ip = node?.ip ?: vpnServer?.ip.orEmpty()
        // Provider routes know their country; imported servers and Tailscale nodes are located by their address alone.
        val country = route?.takeIf { !it.imported && it.tsExit == null }?.loc?.countryCode?.uppercase()?.takeIf { !anywhere }
        val epoch = Stats.epoch
        val recordAs = Stats.note(exit, setup)
        val mine = synchronized(lock) { _run.value = Run(exit); ++attempt }
        val stop = { attempt != mine }
        val set = { f: (Run) -> Run -> synchronized(lock) { if (!stop()) _run.value?.let { _run.value = f(it) } } }
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
                // Moved to another provider or server meanwhile: the numbers belong to neither.
                if (Stats.idOf(exit, UnbraidVpn.running ?: setup) != recordAs) { set { it.copy(phase = Phase.Failed, error = "The route changed during the test. Try again.") }; return@Runnable }
                set { it.copy(phase = Phase.Done, up = up, now = 0, elapsedMs = MAX_SECONDS * 1000L) }
                val done = _run.value ?: return@Runnable
                Stats.saveTest(Stats.Test(System.currentTimeMillis(), recordAs, via, name, ip, down, up, done.ping?.median ?: 0, 0, 0), epoch)
            } catch (e: Exception) {
                set { it.copy(phase = Phase.Failed, error = "The test couldn't finish. Check the connection and try again.") }
            } finally {
                if (node != null) restoreNode()
            }
        }
        // A test through Tailscale's routed node first switches back from a tested node, so it never measures that one.
        if (node == null) { if (tag == Compiler.TS_TAG) tsq.execute { if (stop()) return@execute; if (Unbraid.store.tsTestFrom != null) switchBack(); Thread(test, "speedtest").start() } else Thread(test, "speedtest").start() }
        else tsq.execute {
            if (stop()) return@execute // cancelled before its turn: switch nothing
            // Switching is a blocking call; give Tailscale a moment to reach the new node before measuring.
            // The routed node's stable ID (the switch back needs one), noted with the route's setting it belongs to.
            if (Unbraid.store.tsTestFrom == null) Unbraid.store.tsTestFrom = Unbraid.store.setup.value.tsExit?.let { r -> restoreTo(r, null, UnbraidVpn.state.value.tsExitNodes)?.let { "$r\n$it" } }.orEmpty()
            val ok = runCatching { client().setTailscaleExitNode(Compiler.TS_TAG, node.id) }.isSuccess
            if (ok) Thread.sleep(1500)
            if (stop()) return@execute
            if (ok) Thread(test, "speedtest").start() else { set { it.copy(phase = Phase.Failed, error = "Tailscale couldn't switch to ${node.name}.") }; restoreNode() }
        }
        return true
    }

    /** The exit a running test measures through tunnel [tag], so its traffic is counted there; null = none. */
    fun exitOn(tag: String?): String? = _run.value?.exit?.takeIf { e -> testing && tag == if (e.startsWith(NODE)) Compiler.TS_TAG else UnbraidVpn.tags[e] }

    /** A test is under way (Tailscale may be on its test node on purpose). */
    val testing get() = _run.value?.phase.let { it == Phase.Preparing || it == Phase.Download || it == Phase.Upload }

    /**
     * Puts Tailscale back on the exit node the routes use (or none). Safe to call any time; runs off the caller's thread.
     * The node in use before the test comes back by its stable ID; failing that, the routed node by address or name. If
     * neither is known yet Tailscale is left with no exit (apps on its route wait, never using the tested node) and
     * the next Tailscale status tries again.
     */
    fun restoreNode() = tsq.execute {
        if (synchronized(lock) { testing && _run.value?.exit?.startsWith(NODE) == true }) return@execute // that test switches back when it ends
        switchBack()
    }

    /** On [tsq] only. */
    private fun switchBack() {
        val want = restoreTo(Unbraid.store.setup.value.tsExit, Unbraid.store.tsTestFrom, UnbraidVpn.state.value.tsExitNodes)
        // A node Tailscale won't take back (gone, no longer an exit) is no reason to stay on the tested one: no exit then.
        val set = { id: String -> runCatching { client().setTailscaleExitNode(Compiler.TS_TAG, id) }.isSuccess }
        if (set(want.orEmpty()) && want != null) Unbraid.store.tsTestFrom = null else if (want != "") set("")
    }

    /**
     * The stable ID to switch back to, for the route's setting now ([routed], an address, ID or name): "" for no exit
     * node; null when not known yet (then no exit meanwhile). A name counts only if one node has it; the [hint] noted at
     * the test's start ("setting\nID") helps only while the setting is unchanged.
     */
    internal fun restoreTo(routed: String?, hint: String?, nodes: List<app.unbraid.vpn.TsNode>): String? {
        if (routed == null) return ""
        val r = routed.trimEnd('.')
        nodes.firstOrNull { it.ip == r || it.id == r }?.let { return it.id }
        val named = nodes.filter { it.name.equals(r, true) || it.name.equals(r.substringBefore('.'), true) }
        if (named.size > 1) return null // two nodes by that name: neither is surely the one
        named.singleOrNull()?.let { return it.id }
        return hint?.takeIf { it.substringBefore('\n') == routed }?.substringAfter('\n')?.ifEmpty { null }
    }

    /** Stops a running test; its sockets close as soon as they notice. */
    fun cancel() = synchronized(lock) {
        val wasNode = _run.value?.exit?.startsWith(NODE) == true
        attempt++
        if (testing) {
            Ndt7.abort()
            _run.value = null
            if (wasNode) restoreNode()
        }
    }

    /** Leaving the result screen forgets the last run (it's in History if stats are on). */
    fun clear() = synchronized(lock) { if (!testing) _run.value = null }
}
