package app.unbraid.stats

import android.os.SystemClock
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
 *
 * [startAll] tests several routed exits at the same time instead ([All]); one test or one combined run at a time.
 */
object SpeedTest {
    /** [Deciding]: a combined run waits for the person (an exit without a server at home, or a metered network). */
    enum class Phase { Preparing, Deciding, Download, Upload, Done, Failed }

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
    /** Guards [attempt] with [_run] and [_all]: a cancelled test can't publish once a newer one (or none) took over. */
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

    /** Counts [n] tests against M-Lab's daily allowance, unless a newer test took over or they don't fit. */
    private fun reserve(n: Int, mine: Int) = synchronized(lock) {
        attempt == mine && Unbraid.store.testsToday + n <= PER_DAY && run { Unbraid.store.testsToday += n; true }
    }

    /** Runs [f] on the located server, then on Locate's other candidates while they turn the test away. */
    private fun <T> anyOf(s: Ndt7.Server, f: (Ndt7.Server) -> T): Pair<Ndt7.Server, T> =
        try { s to f(s) } catch (e: Ndt7.Refused) { anyOf(s.others.firstOrNull()?.copy(others = s.others.drop(1)) ?: throw e, f) }

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
                val located = Ndt7.locate(tag, country)
                if (stop()) return@Runnable
                if (located == null) { set { it.copy(phase = Phase.Failed, missing = route?.loc?.country ?: country) }; return@Runnable }
                if (!reserve(1, mine)) { set { it.copy(phase = Phase.Failed, error = "M-Lab allows $PER_DAY tests a day. Try again tomorrow.") }; return@Runnable }
                set { it.copy(server = located) }
                val ping = Ndt7.ping(tag, located)
                if (stop()) return@Runnable
                set { it.copy(ping = ping, phase = Phase.Download) }
                val t0 = SystemClock.elapsedRealtime()
                val elapsed = { SystemClock.elapsedRealtime() - t0 }
                val (server, down) = anyOf(located) { s -> Ndt7.download(tag, s.download, stop) { bps -> set { it.copy(now = bps, elapsedMs = elapsed()) } } }
                if (stop()) return@Runnable
                set { it.copy(phase = Phase.Upload, down = down, now = 0, server = server, ping = it.ping.takeIf { server == located }) }
                val up = Ndt7.upload(tag, server.upload, stop) { bps -> set { it.copy(now = bps, elapsedMs = elapsed()) } }
                if (stop()) return@Runnable
                // Moved to another provider or server meanwhile: the numbers belong to neither.
                if (Stats.idOf(exit, UnbraidVpn.running ?: setup) != recordAs) { set { it.copy(phase = Phase.Failed, error = "The route changed during the test. Try again.") }; return@Runnable }
                set { it.copy(phase = Phase.Done, up = up, now = 0, elapsedMs = MAX_SECONDS * 1000L) }
                val done = _run.value ?: return@Runnable
                Stats.saveTests(listOf(Stats.Test(System.currentTimeMillis(), recordAs, via, name, ip, down, up, done.ping?.median ?: 0, 0, 0)), epoch)
            } catch (e: Ndt7.Limited) {
                set { it.copy(phase = Phase.Failed, error = "M-Lab is limiting tests from this exit's address right now (VPN servers share theirs). Try again later.") }
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

    // ---- every exit at once ----------------------------------------------------------------------------------------

    /** Most exits in one combined run: a few people's worth of tests, well inside what M-Lab's terms expect. */
    const val MAX_TOGETHER = 10
    /** A combined figure needs the exits to have run together at least this long. */
    const val MIN_OVERLAP_MS = 3000L
    /** The exit id of a combined run's own row in [Stats]. */
    const val ALL = "all"

    /**
     * One exit's part in a combined run. [missing]: M-Lab has no server in its country ([server] is then the nearest
     * one, if any); [limited]: M-Lab turned its address away; [error]: it can't take part, or stopped.
     */
    data class Leg(
        val exit: String, val server: Ndt7.Server? = null, val missing: Boolean = false, val limited: Boolean = false,
        val ping: Ndt7.Ping? = null, val now: Long = 0, val down: Long? = null, val up: Long? = null, val error: String? = null,
    ) { val usable get() = server != null && !limited && error == null }

    /**
     * Several exits measured at the same time. [down] and [up] count only the stretch every remaining exit was running
     * ([windowMs], the shorter of the two); [now] is the exits' live rates added up. [metered]: the sheet said so.
     */
    data class All(
        val id: Long, val legs: List<Leg>, val metered: Boolean, val phase: Phase = Phase.Preparing, val now: Long = 0,
        val down: Long? = null, val up: Long? = null, val elapsedMs: Long = 0, val windowMs: Long = 0, val error: String? = null,
    )

    private val _all = MutableStateFlow<All?>(null)
    val all = _all.asStateFlow()
    /** What [go] starts, bound to the run it belongs to: a cancelled run's never starts a newer one's measuring. */
    private var proceed: Pair<Int, (Set<String>) -> Unit>? = null

    /** Starts the measuring once the person decided: which exits without a server at home go via the nearest one. */
    fun go(nearest: Set<String>) = synchronized(lock) { proceed?.takeIf { it.first == attempt }.also { proceed = null } }?.second?.invoke(nearest)

    /** Runs [f] for every exit at once and waits for all of them. */
    private fun parallel(exits: List<String>, f: (String) -> Unit) =
        exits.map { e -> Thread({ runCatching { f(e) } }, "speedtest").apply { start() } }.forEach { it.join() }

    /** Routes connected only for a combined run go down again (the engine reloads without them). */
    private fun release() { if (Unbraid.store.setup.value.testing.isNotEmpty()) Unbraid.store.update { it.copy(testing = emptySet()) } }

    /**
     * Tests [exits] (route ids) at the same time. Routes without apps are connected for the run, as far as their
     * provider's connection limit allows; a Tailscale route can join only if Tailscale's one exit node is free or its.
     * Every exit first finds its M-Lab server; then, after [go], all download together and then all upload together.
     */
    fun startAll(exits: List<String>, metered: Boolean): Boolean {
        val setup = Unbraid.store.setup.value
        val legs = exits.distinct().filter { setup.route(it) != null }.take(MAX_TOGETHER)
        if (legs.size < 2 || UnbraidVpn.probe == null) return false
        cancel()
        val id = System.currentTimeMillis()
        val epoch = Stats.epoch
        val recordAs = legs.associateWith { Stats.note(it, setup) }
        val mine = synchronized(lock) { _all.value = All(id, legs.map { Leg(it) }, metered); ++attempt }
        val stop = { attempt != mine }
        val set = { f: (All) -> All -> synchronized(lock) { if (!stop()) _all.value?.let { _all.value = f(it) } } }
        val leg = { exit: String, f: (Leg) -> Leg -> set { a -> a.legs.map { if (it.exit == exit) f(it) else it }.let { l -> a.copy(legs = l, now = l.sumOf { it.now }) } } }
        val tag = { exit: String -> UnbraidVpn.tags[exit] ?: error("The route isn't connected") }
        val failed = { why: String -> set { it.copy(phase = Phase.Failed, now = 0, error = why) } }
        val armed = { f: (Set<String>) -> Unit -> synchronized(lock) { if (!stop()) proceed = mine to f } }
        /** Starts the measuring on its own thread, never on the caller's (a tap on the sheet). Set below. */
        var start: (Set<String>) -> Unit = {}

        /** One phase through every exit: their own results, and each one's (time, bytes) samples. */
        fun phase(p: Phase, exits: List<String>, t0: Long, f: (String, (Long, Long) -> Unit, (Long) -> Unit) -> Long): Map<String, List<Pair<Long, Long>>> {
            set { it.copy(phase = p) }
            val samples = exits.associateWith { java.util.Collections.synchronizedList(mutableListOf<Pair<Long, Long>>()) }
            parallel(exits) { e ->
                try {
                    val r = f(e, { t, b -> samples.getValue(e) += t to b }, { bps -> leg(e) { it.copy(now = bps) }; set { it.copy(elapsedMs = SystemClock.elapsedRealtime() - t0) } })
                    leg(e) { if (p == Phase.Download) it.copy(down = r, now = 0) else it.copy(up = r, now = 0) }
                } catch (x: Exception) {
                    android.util.Log.w("Unbraid", "speed test: ${p.name.lowercase()} through one exit stopped: ${x.javaClass.simpleName}")
                    leg(e) { it.copy(now = 0, error = "Stopped during the ${p.name.lowercase()}: the exit stopped answering. Not in the totals.") }
                }
            }
            return samples.mapValues { it.value.toList() }
        }

        fun measure(nearest: Set<String>) {
            // The network turned metered after GO: the person hasn't agreed to that yet.
            if (UnbraidVpn.metered && _all.value?.metered == false) { set { it.copy(metered = true, phase = Phase.Deciding) }; armed(start); return }
            val going = _all.value?.legs.orEmpty().filter { it.usable && (!it.missing || it.exit in nearest) }
            if (going.size < 2) return failed("At least two exits need a test server to run together.")
            if (!reserve(going.size, mine)) return failed("M-Lab allows $PER_DAY tests a day; this run needs ${going.size} of them. Try fewer exits, or tomorrow.")
            set { it.copy(legs = going, phase = Phase.Preparing) }
            parallel(going.map { it.exit }) { e -> val p = Ndt7.ping(tag(e), going.first { it.exit == e }.server!!); leg(e) { it.copy(ping = p) } }
            if (stop()) return
            // The server keeps this with each result, so M-Lab's data shows the line was shared. Nothing that ties
            // the exits' addresses to each other (no run id): they are published, and some are the person's own.
            val extra = "concurrent=${going.size}"
            val used = java.util.concurrent.ConcurrentHashMap<String, Ndt7.Server>()
            val t0 = SystemClock.elapsedRealtime()
            val ok = { _all.value?.legs.orEmpty().filter { it.error == null }.map { it.exit } }
            val down = phase(Phase.Download, going.map { it.exit }, t0) { e, sample, rate ->
                val (s, r) = anyOf(going.first { it.exit == e }.server!!) { s -> Ndt7.download(tag(e), s.download, stop, extra, sample, rate) }
                // Another of Locate's servers took the test: its ping wasn't measured, and the card names it.
                if (s != going.first { it.exit == e }.server) leg(e) { it.copy(server = s, ping = null) }
                used[e] = s; r
            }
            if (stop()) return
            combined(ok().takeIf { it.size >= 2 }.orEmpty().map { down.getValue(it) })?.let { (bps, ms) -> set { it.copy(down = bps, windowMs = ms) } } ?: return failed(apart(ok().size))
            val up = phase(Phase.Upload, ok(), t0) { e, sample, rate -> Ndt7.upload(tag(e), used.getValue(e).upload, stop, extra, sample, rate) }
            if (stop()) return
            // An exit moved to another provider or server meanwhile: its numbers belong to neither.
            val running = UnbraidVpn.running ?: setup
            set { a -> a.copy(legs = a.legs.map { if (it.error == null && Stats.idOf(it.exit, running) != recordAs[it.exit]) it.copy(error = "The route changed during the test. Not counted.") else it }) }
            // Both totals over the exits that made it through both phases, so the run adds up to the rows it lists.
            val last = ok()
            val d = combined(last.map { down.getValue(it) }); val u = combined(last.map { up.getValue(it) })
            if (last.size < 2 || d == null || u == null) return failed(apart(last.size))
            set { it.copy(phase = Phase.Done, down = d.first, up = u.first, windowMs = minOf(d.second, u.second), now = 0, elapsedMs = MAX_SECONDS * 1000L) }
            val done = _all.value?.takeIf { it.phase == Phase.Done } ?: return
            val servers = UnbraidVpn.state.value.servers
            Stats.saveTests(listOf(Stats.Test(id, ALL, "", "", "", d.first, u.first, 0, 0, 0, id, last.size)) + done.legs.filter { it.error == null }.mapIndexed { i, l ->
                val r = setup.route(l.exit)
                Stats.Test(id + 1 + i, recordAs.getValue(l.exit), r?.source.orEmpty(), servers[l.exit]?.name ?: r?.name.orEmpty(), servers[l.exit]?.ip.orEmpty(),
                    l.down ?: 0, l.up ?: 0, l.ping?.median ?: 0, 0, 0, id, last.size)
            }, epoch)
        }

        Thread({
            try {
                // Routes without apps come up for the run, one at a time while each provider's limit allows.
                val acc = Unbraid.store.accounts.value
                val limit = { p: String -> acc[p]?.let { a -> app.unbraid.data.Providers.get(p)?.limit(a) } }
                var up = emptySet<String>()
                legs.filter { e -> setup.activeRoutes.none { it.id == e } }.forEach { e ->
                    val r = setup.route(e)!!
                    val over = app.unbraid.data.Limits.over(setup.copy(testing = up), setup.copy(testing = up + e), limit)
                    val ts = setup.copy(testing = up).tsExit
                    when {
                        r.tsExit != null && ts != null && ts != r.tsExit -> leg(e) { it.copy(error = "Tailscale has one exit node at a time, and another route uses it.") }
                        over != null -> leg(e) { it.copy(error = "${app.unbraid.data.Providers.get(over.provider)?.name ?: over.provider} allows ${app.unbraid.data.Limits.units(over.limit, over.unit)}, all in use.") }
                        else -> up = up + e
                    }
                }
                if (up.isNotEmpty()) {
                    // With the attempt check, so a cancel either comes first (nothing goes up) or takes them down again.
                    if (!synchronized(lock) { !stop() && Unbraid.store.update { it.copy(testing = up) }.let { true } }) return@Thread
                    val until = SystemClock.elapsedRealtime() + 30_000
                    while (!stop() && up.any { UnbraidVpn.tags[it] == null } && SystemClock.elapsedRealtime() < until) Thread.sleep(250)
                    up.filter { UnbraidVpn.tags[it] == null }.forEach { e -> leg(e) { it.copy(error = "It didn't connect for the test.") } }
                }
                // A cut-off test of another node may have left Tailscale on it: the routed node first, as for a single test.
                if (Unbraid.store.tsTestFrom != null && legs.any { UnbraidVpn.tags[it] == Compiler.TS_TAG }) runCatching { tsq.submit(::switchBack).get() }
                parallel(_all.value?.legs.orEmpty().filter { it.error == null }.map { it.exit }) { e ->
                    val country = setup.route(e)?.takeIf { !it.imported && it.tsExit == null }?.loc?.countryCode?.uppercase()
                    try {
                        val s = Ndt7.locate(tag(e), country)
                        // Outside the lock: a slow answer mustn't hold up a cancel on the main thread.
                        val near = if (s == null) runCatching { Ndt7.locate(tag(e), null) }.getOrNull() else null
                        leg(e) { if (s != null) it.copy(server = s) else it.copy(missing = true, server = near) }
                    } catch (x: Ndt7.Limited) { leg(e) { it.copy(limited = true) } }
                    catch (x: Exception) { leg(e) { it.copy(error = "M-Lab couldn't be reached through it.") } }
                }
                val a = _all.value
                if (stop() || a == null) return@Thread
                start = { nearest -> Thread({ try { measure(nearest) } catch (x: Exception) { failed("The test couldn't finish. Check the connection and try again.") } finally { if (_all.value?.phase != Phase.Deciding) done(mine) } }, "speedtest").start() }
                if (metered || UnbraidVpn.metered || a.legs.any { it.missing || !it.usable }) { set { it.copy(metered = metered || UnbraidVpn.metered, phase = Phase.Deciding) }; armed(start) }
                else start(emptySet())
            } catch (x: Exception) { failed("The test couldn't start. Check the connection and try again."); done(mine) }
        }, "speedtest").start()
        return true
    }

    /** A combined run [mine] ended: routes up only for it go down, unless a newer run took over. */
    private fun done(mine: Int) = synchronized(lock) { if (attempt == mine) release() }

    private fun apart(n: Int) = if (n < 2) "Only one exit kept going, so there's nothing to add up. Try again." else "The exits didn't run together long enough to add them up. Try again."

    /**
     * bits/s through several exits counted only while all of them ran, and that stretch in ms: from the latest first
     * sample to the earliest last one, each exit's bytes read off its (time, bytes) samples at both ends, linearly
     * between samples. Null when they overlapped less than [MIN_OVERLAP_MS].
     */
    internal fun combined(legs: List<List<Pair<Long, Long>>>): Pair<Long, Long>? {
        if (legs.isEmpty() || legs.any { it.isEmpty() }) return null
        val from = legs.maxOf { it.first().first }; val to = legs.minOf { it.last().first }
        if (to - from < MIN_OVERLAP_MS) return null
        return legs.sumOf { bytesAt(it, to) - bytesAt(it, from) } * 8000 / (to - from) to to - from
    }

    internal fun bytesAt(s: List<Pair<Long, Long>>, t: Long): Long {
        val i = s.indexOfFirst { it.first >= t }
        if (i < 0) return s.last().second
        if (i == 0) return s[0].second
        val (a, b) = s[i - 1] to s[i]
        return a.second + (b.second - a.second) * (t - a.first) / (b.first - a.first).coerceAtLeast(1)
    }

    /** The exit a running test measures through tunnel [tag], so its traffic is counted there; null = none. */
    fun exitOn(tag: String?): String? = if (!testing) null
        else _run.value?.exit?.takeIf { e -> tag == if (e.startsWith(NODE)) Compiler.TS_TAG else UnbraidVpn.tags[e] }
            ?: _all.value?.legs?.firstOrNull { UnbraidVpn.tags[it.exit] == tag }?.exit

    private val Phase?.busy get() = this == Phase.Preparing || this == Phase.Download || this == Phase.Upload

    /** A test is under way (Tailscale may be on its test node on purpose). */
    val testing get() = _run.value?.phase.busy || _all.value?.phase.busy

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

    /** Stops a running test or combined run (or one waiting for a decision); its sockets close as soon as they notice. */
    fun cancel() = synchronized(lock) {
        val wasNode = _run.value?.exit?.startsWith(NODE) == true
        attempt++
        proceed = null
        if (testing || _all.value?.phase == Phase.Deciding) {
            Ndt7.abort()
            if (_run.value?.phase.busy) _run.value = null
            _all.value = null
            if (wasNode) restoreNode()
        }
        release()
    }

    /** Leaving the result screen forgets the last run (it's in History if stats are on). */
    fun clear() = synchronized(lock) { if (!testing) { _run.value = null; if (_all.value?.phase != Phase.Deciding) _all.value = null } }
}
