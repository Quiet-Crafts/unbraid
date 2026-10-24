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
     * others. Never takes much longer than [BUDGET].
     */
    suspend fun reachable(tag: String) = withTimeoutOrNull(BUDGET) {
        channelFlow {
            SERVERS.forEach { launch { send(binding(it, tag)) } }
            launch { delay(3_000); send(https(tag)) }
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

    /** One HTTPS request over [network] itself, past every tunnel. */
    suspend fun direct(network: android.net.Network?) = withContext(Dispatchers.IO) {
        runCatching {
            (network!!.openConnection(java.net.URL("https://www.google.com/generate_204")) as java.net.HttpURLConnection).run {
                connectTimeout = 10_000; readTimeout = 10_000
                try { responseCode in 200..399 } finally { disconnect() }
            }
        }.getOrDefault(false)
    }

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
            val session = runCatching { Libbox.newStandaloneCommandClient().startSTUNTest(server, tag, handler) }.getOrNull() ?: return@withContext false
            try { withTimeoutOrNull(BUDGET) { answer.await() } ?: false }
            finally { withContext(NonCancellable) { runCatching { session.close() } } }
        }
    }
}
