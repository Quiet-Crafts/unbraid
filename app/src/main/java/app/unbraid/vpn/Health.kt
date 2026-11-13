package app.unbraid.vpn

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.STUNTestHandler
import io.nekohasekai.libbox.STUNTestProgress
import io.nekohasekai.libbox.STUNTestResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether a tunnel still carries traffic: a STUN binding round trip (a ~100-byte UDP query) through it to either of
 * two servers, or HTTPS. A WireGuard tunnel whose server stopped answering (a key now used by another device on that
 * server, a server taken down) otherwise stays up but silent, and its apps just time out.
 */
object Health {
    fun eligible(route: app.unbraid.data.Route) = !route.imported || route.checksInternet
    fun movable(route: app.unbraid.data.Route) = !route.imported && route.loc.pinned == null && route.loc.group != app.unbraid.data.Nord.DEDICATED_IP
    fun answering(marked: Set<String>, eligible: Set<String>, passed: Set<String>, dead: Set<String>): Set<String> =
        (marked intersect eligible) - passed + (dead intersect eligible)

    private val SERVERS = listOf("stun.l.google.com:19302", "stun.cloudflare.com:3478")

    /** One check's whole budget; a slower answer counts as none. */
    const val BUDGET = 8_000L
    /** A failing tunnel is checked again this soon, twice, before waiting for [RECOVER]. */
    const val QUICK = 10_000L
    /** How long a failing tunnel gets to come back on its own server (WireGuard re-handshakes, OpenVPN restarts). */
    const val RECOVER = 60_000L
    /** After a dead tunnel was handled, how long before the same route is handled again. */
    const val COOLDOWN = 120_000L
    /** No checks this long after a new network, an engine start or a wake from sleep: tunnels are still settling. */
    const val GRACE = 30_000L
    /** The phone's awake time falling this far behind real time means it slept (Doze, screen off) in between. */
    const val SLEPT = 6_000L

    /**
     * Some networks and providers block STUN, so HTTPS is tried too, from 3 s on; the first answer wins and stops the
     * others. Never takes much longer than [BUDGET]. A tunnel that carries TCP only (a Shadowsocks plugin) gets HTTPS
     * alone: the engine would send STUN straight to its server, past the plugin.
     */
    suspend fun reachable(tag: String) = reachable(tag !in UnbraidVpn.tcpOnly, { binding(it, tag) }) { https(tag) }

    internal suspend fun reachable(udp: Boolean, stun: suspend (String) -> Boolean, https: suspend () -> Boolean) = withTimeoutOrNull(BUDGET) {
        channelFlow {
            if (udp) SERVERS.forEach { launch { send(stun(it)) } }
            launch { if (udp) delay(3_000); send(https()) }
        }.firstOrNull { it }
    } == true

    /** A TLS handshake with a well-known site through [tag], over the app's own way into that exit ([Probe]). */
    private suspend fun https(tag: String) = runCatching { Probe.cancellable { raw -> Probe.open(tag, "www.google.com", timeoutMs = 5_000, raw = raw).close(); true } }.getOrDefault(false)

    /**
     * Tunnels a round's failures may be blamed on: none when every checked tunnel failed and so did a plain HTTPS
     * request over the phone's own network ([direct]), since then it is that network that is down, not the servers.
     */
    suspend fun blame(down: Set<String>, checked: Set<String>, direct: suspend () -> Boolean): Set<String>? =
        if (down.isNotEmpty() && down == checked && !direct()) null else down

    /**
     * A tunnel's latest checks, oldest first ([true] = answered), as kept for [dropping]: the last six.
     */
    fun record(history: List<Boolean>, ok: Boolean) = (history + ok).takeLast(6)

    /**
     * A tunnel that keeps dropping: three or more of its last six checks failed and it hasn't answered three in a row
     * since. One that stays down is moved to another server instead; this catches the ones that come and go, like two
     * tunnels taking turns on an account with one connection.
     */
    fun dropping(history: List<Boolean>) = history.count { !it } >= 3 && !(history.size >= 3 && history.takeLast(3).all { it })

    /** A tunnel's run of failed checks: how many, since when and the latest (elapsed-realtime ms). None = all well. */
    data class Streak(val fails: Int = 0, val since: Long = 0, val last: Long = 0)

    fun next(s: Streak, ok: Boolean, now: Long) = if (ok) Streak() else Streak(s.fails + 1, if (s.fails == 0) now else s.since, now)

    /**
     * When a failing tunnel is checked again on its own: twice [QUICK] apart, then once [RECOVER] after its first
     * failure, never before [hold]. Null = only with the regular rounds.
     */
    fun due(s: Streak, hold: Long = 0) = when {
        s.fails == 0 -> null
        s.fails < 3 -> maxOf(s.last + QUICK, hold)
        else -> maxOf(s.since + RECOVER, hold)
    }

    /** Dead: four failures over at least [RECOVER], so it had its chance to come back on the same server. */
    fun dead(s: Streak, now: Long, hold: Long = 0) = s.fails >= 4 && now - s.since >= RECOVER && now >= hold

    /** After a dead tunnel was moved and its new server doesn't work either, the waits before the next move: 2, 5, 15, 30 min. */
    val BACKOFF = listOf(COOLDOWN, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L)

    /** A route's dead tunnels handled in a row on this network, and when it may be handled again (elapsed-realtime ms). */
    data class Moves(val count: Int = 0, val hold: Long = 0)

    /**
     * A dead tunnel handled at [now]. Each move waits the next of [BACKOFF] before another; the move after the last wait
     * is judged as soon as it is dead again; dead after that too, it is never moved again on this network ([doesntWork]),
     * but still handled every [COOLDOWN] while dead, which only checks whether its provider removed this phone.
     */
    fun moved(m: Moves, now: Long): Moves {
        val count = m.count + 1
        return Moves(count, when { count <= BACKOFF.size -> now + BACKOFF[count - 1]; count == BACKOFF.size + 1 -> now; else -> now + COOLDOWN })
    }

    /**
     * A dead tunnel handled at [now]: its run of moves and when it may be handled again. Only one the app [acts] on (moves
     * it, or tries another port: each restarts every tunnel) spends the move budget; any other (imported, pinned on a set
     * port) is judged again after [COOLDOWN], so its warning keeps coming back while it fails.
     */
    fun handle(m: Moves?, now: Long, acts: Boolean): Pair<Moves?, Long> =
        if (acts) moved(m ?: Moves(), now).let { it to it.hold } else m to now + COOLDOWN

    /** Moved after every wait and still dead: it doesn't work on this network, so it stays where it is. */
    fun doesntWork(m: Moves) = m.count > BACKOFF.size + 1

    /** Answered its last three regular checks: the moves before no longer count. */
    fun settled(history: List<Boolean>) = history.size >= 3 && history.takeLast(3).all { it }

    /**
     * Whether [history] ends a route's run of moves [m]. One it gave up on ([doesntWork]) stays given up until the network
     * changes or the user reconnects, however well it answers meanwhile: otherwise short recoveries would restart the moves.
     */
    fun ends(m: Moves?, history: List<Boolean>) = settled(history) && m?.let(::doesntWork) != true

    /** One HTTPS request over [network] itself, past every tunnel. */
    suspend fun direct(network: android.net.Network?) = withContext(Dispatchers.IO) {
        runCatching {
            (network!!.openConnection(java.net.URL("https://www.google.com/generate_204")) as java.net.HttpURLConnection).run {
                connectTimeout = 10_000; readTimeout = 10_000
                try { responseCode in 200..399 } finally { disconnect() }
            }
        }.getOrDefault(false)
    }

    /**
     * STUN checks open now. One already past its TCP-only test must not reach a tunnel that a reload turns TCP-only (a
     * Shadowsocks plugin): the engine would send it straight to the server, past the plugin. So starting one and marking
     * tunnels TCP-only exclude each other, and the marking closes every check already open through those tunnels.
     */
    internal class StunGate {
        private val open = HashMap<Any, Pair<String, () -> Unit>>()

        /** Starts a check through [tag] with [start], unless [refused] (TCP only now); [close] ends it early. */
        @Synchronized fun <S : Any> start(tag: String, refused: (String) -> Boolean, start: () -> S?, close: (S) -> Unit): S? =
            if (refused(tag)) null else start()?.also { s -> open[s] = tag to { close(s) } }

        @Synchronized fun done(session: Any) { open.remove(session) }

        /** Runs [mark] (tunnels [tags] are TCP-only from now) and closes the checks open through any of them. */
        @Synchronized fun udpless(tags: Set<String>, mark: () -> Unit) {
            mark()
            open.entries.filter { it.value.first in tags }.toList().forEach { (s, v) -> open.remove(s); runCatching { v.second() } }
        }
    }
    internal val stun = StunGate()

    private suspend fun binding(stun: String, tag: String): Boolean {
        // The STUN server's name is looked up through the tunnel's own DNS server, never the phone's network (an engine
        // patch reads "<dns server>|<host:port>"); a tunnel whose DNS server isn't known relies on HTTPS.
        val server = UnbraidVpn.tunnelDns[tag]?.let { "$it|$stun" } ?: return false
        val answer = CompletableDeferred<Boolean>()
        // Callbacks only hand the answer over: closing the session from inside one would wait for itself.
        val handler = object : STUNTestHandler {
            override fun onProgress(p: STUNTestProgress) { if (p.externalAddr.isNotEmpty()) answer.complete(true) } // the NAT-type phases after it aren't needed
            override fun onResult(r: STUNTestResult) { answer.complete(true) }
            override fun onError(message: String?) { answer.complete(false) }
        }
        // Opened and closed in one context, so a cancelled check can't lose a session it already has.
        return withContext(Dispatchers.IO) {
            val session = runCatching {
                this@Health.stun.start(tag, { it in UnbraidVpn.tcpOnly }, { Libbox.newStandaloneCommandClient().startSTUNTest(server, tag, handler) }) { answer.complete(false); it.close() }
            }.getOrNull() ?: return@withContext false
            try { withTimeoutOrNull(BUDGET) { answer.await() } ?: false }
            finally { withContext(NonCancellable) { this@Health.stun.done(session); runCatching { session.close() } } }
        }
    }
}
