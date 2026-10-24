package app.unbraid.vpn

import app.unbraid.data.Nord
import app.unbraid.data.Route
import app.unbraid.data.Server
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What one exit reported when asked: its public address and country as Cloudflare saw them, or [ip] null when it
 * couldn't be asked. [engine] and [server] are what ran then, so a later restart or server change retires it.
 */
data class ExitReport(val ip: String?, val country: String?, val at: Long, val engine: Int, val server: Server?)

/**
 * Asks Cloudflare's trace page, through each exit (the app's own way in, [Probe]), which address and country it sees.
 * Only when the user asks; one request per tunnel. Cloudflare's country comes from its location database, which can
 * disagree with a provider's: a report is what was seen, not proof.
 */
object ExitCheck {
    const val HOST = "www.cloudflare.com"
    private val IPV4 = Regex("""(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}""")
    private val ISO = Locale.getISOCountries().toHashSet()

    /** The trace's address and country (`ip=`, `loc=`), or null without a well-formed address. */
    fun parse(trace: String): Pair<String, String?>? {
        val kv = trace.lineSequence().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        val ip = kv["ip"]?.takeIf { IPV4.matches(it) || ipv6(it) } ?: return null
        return ip to country(kv["loc"])
    }

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

    /** An ISO 3166 country code in capitals, UK as GB; anything else (Cloudflare's XX or T1, a placeholder) is none. */
    fun country(code: String?) = code?.trim()?.uppercase(Locale.ROOT)?.let { if (it == "UK") "GB" else it }?.takeIf { it in ISO }

    fun name(code: String): String = Locale("", code).getDisplayCountry(Locale.ENGLISH).ifBlank { code }

    /**
     * The country a route was set to exit in, when it names one: not Fastest, an own server, a Tailscale exit, or
     * NordVPN's Double VPN and Onion servers, which exit elsewhere by design.
     */
    fun expected(r: Route) = r.loc.countryCode.takeIf { !r.imported && !(r.loc.provider == "nord" && r.loc.group in Nord.openVpnOnly) }?.let(::country)

    /** Each tunnel's answer by tag, four at a time; null for one that didn't answer within 12 s. */
    suspend fun ask(tags: Collection<String>): Map<String, Pair<String, String?>?> = coroutineScope {
        val gate = Semaphore(4)
        tags.distinct().map { t ->
            async {
                t to gate.withPermit {
                    withTimeoutOrNull(12_000) {
                        runCatching { Probe.cancellable { raw -> parse(Probe.get(t, HOST, "/cdn-cgi/trace", max = 4096, accept = "text/plain", raw = raw)) } }.getOrNull()
                    }
                }
            }
        }.awaitAll().toMap()
    }
}
