package app.unbraid.vpn

import app.unbraid.data.Server
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What one exit reported when asked: its public address as Cloudflare saw it, or [ip] null when it couldn't be asked.
 * [engine] and [server] are what ran then, so a later restart or server change retires it.
 */
data class ExitReport(val ip: String?, val at: Long, val engine: Int, val server: Server?)

/**
 * Asks Cloudflare's trace page, through each exit (the app's own way in, [Probe]), which address it sees. While Routes
 * is on screen, after a connect or a server change; one request per tunnel, never for Direct. Only the address is
 * shown: location databases disagree (a provider's virtual locations most of all), so a country would mislead.
 */
object ExitCheck {
    /** Cloudflare's own sites, tried in turn: a network or a site rule that stops one rarely stops both. */
    val HOSTS = listOf("www.cloudflare.com", "one.one.one.one")
    private val IPV4 = Regex("""(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}""")

    /** The trace's address (`ip=`), or null without a well-formed one. */
    fun parse(trace: String): String? =
        trace.lineSequence().firstOrNull { it.startsWith("ip=") }?.substringAfter('=')?.trim()?.takeIf { IPV4.matches(it) || ipv6(it) }

    /** A numeric IPv6 address: eight groups of up to four hex digits, or fewer around one "::". Never looked up. */
    fun ipv6(a: String): Boolean {
        if (a.length > 39 || a.count { it == ':' } < 2 || ":::" in a) return false
        val parts = a.split("::")
        if (parts.size > 2) return false
        val groups = parts.map { p -> if (p.isEmpty()) emptyList() else p.split(':') }
        if (groups.flatten().any { g -> g.length !in 1..4 || !g.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' } }) return false
        val n = groups.sumOf { it.size }
        return if (parts.size == 2) n <= 7 else n == 8
    }

    /** One trace through [tag] from [host]; null when it fails, logged by kind only (messages can hold addresses). */
    private suspend fun trace(tag: String, host: String) = try {
        Probe.cancellable { raw -> Probe.get(tag, host, "/cdn-cgi/trace", max = 4096, accept = "text/plain", raw = raw) }.let { parse(it) ?: error("unexpected answer") }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Exception) { android.util.Log.w("Unbraid", "exit check $tag via $host: ${e.javaClass.simpleName}"); null }

    /** Each tunnel's answer by tag, four at a time; null for one that didn't answer within 20 s. */
    suspend fun ask(tags: Collection<String>): Map<String, String?> = coroutineScope {
        val gate = Semaphore(4)
        tags.distinct().map { t ->
            async {
                t to gate.withPermit {
                    withTimeoutOrNull(20_000) { HOSTS.firstNotNullOfOrNull { h -> trace(t, h) } }
                }
            }
        }.awaitAll().toMap()
    }
}
