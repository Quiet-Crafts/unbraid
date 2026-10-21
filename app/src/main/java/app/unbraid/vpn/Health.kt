package app.unbraid.vpn

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.STUNTestHandler
import io.nekohasekai.libbox.STUNTestProgress
import io.nekohasekai.libbox.STUNTestResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether a tunnel still carries traffic: one STUN binding round trip (a ~100-byte UDP query) through it, then a
 * second STUN server, then HTTPS. A WireGuard tunnel whose server stopped answering (a key now used by another device
 * on that server, a server taken down) otherwise stays up but silent, and its apps just time out.
 */
object Health {
    private val SERVERS = listOf("stun.l.google.com:19302", "stun.cloudflare.com:3478")

    /** Some networks and providers block STUN: a tunnel is dead only if an HTTPS round trip through it fails too. */
    suspend fun reachable(tag: String) = SERVERS.any { binding(it, tag) } || https(tag)

    /** A TLS handshake with a well-known site through [tag], over the app's own way into that exit ([Probe]). */
    private suspend fun https(tag: String) = withContext(Dispatchers.IO) {
        runCatching { Probe.open(tag, "www.google.com", timeoutMs = 10_000).close(); true }.getOrDefault(false)
    }

    /**
     * Tunnels a round's failures may be blamed on: none when every checked tunnel failed and so did a plain HTTPS
     * request over the phone's own network ([direct]), since then it is that network that is down, not the servers.
     */
    suspend fun blame(down: Set<String>, checked: Set<String>, direct: suspend () -> Boolean): Set<String>? =
        if (down.isNotEmpty() && down == checked && !direct()) null else down

    /** One HTTPS request over [network] itself, past every tunnel. */
    suspend fun direct(network: android.net.Network?) = withContext(Dispatchers.IO) {
        runCatching {
            (network!!.openConnection(java.net.URL("https://www.google.com/generate_204")) as java.net.HttpURLConnection).run {
                connectTimeout = 10_000; readTimeout = 10_000
                try { responseCode in 200..399 } finally { disconnect() }
            }
        }.getOrDefault(false)
    }

    private suspend fun binding(server: String, tag: String): Boolean {
        val answer = CompletableDeferred<Boolean>()
        // Callbacks only hand the answer over: closing the session from inside one would wait for itself.
        val handler = object : STUNTestHandler {
            override fun onProgress(p: STUNTestProgress) { if (p.externalAddr.isNotEmpty()) answer.complete(true) } // the NAT-type phases after it aren't needed
            override fun onResult(r: STUNTestResult) { answer.complete(true) }
            override fun onError(message: String?) { answer.complete(false) }
        }
        val session = withContext(Dispatchers.IO) { runCatching { Libbox.newStandaloneCommandClient().startSTUNTest(server, tag, handler) }.getOrNull() } ?: return false
        return try { withTimeoutOrNull(8_000) { answer.await() } ?: false }
        finally { withContext(NonCancellable + Dispatchers.IO) { runCatching { session.close() } } }
    }
}
