package app.unbraid.data

import kotlinx.serialization.Serializable

/**
 * unbraid's own DNS filter, and where it applies. [id] is one of [Filters.all] or [Filters.OWN]; null = none, so every
 * route keeps its provider's own blocking and Direct traffic the phone's DNS.
 */
@Serializable
data class Filter(
    val id: String? = null,
    /** NextDNS: the profile ID. Own server: https://host/path (DoH), tls://host (DoT) or a plain IPv4 address. */
    val value: String = "",
    /** VPN routes ask the filter, through their own tunnel, instead of their provider's resolver or blocking. */
    val routes: Boolean = false,
    /** Apps that follow a Direct default route ask the filter instead of the phone's DNS. */
    val direct: Boolean = false,
    /** Apps set to Direct pass through unbraid (still straight out) so their lookups use the filter; false = outside unbraid. */
    val directApps: Boolean = false,
    /** Apps set to Direct that differ from [directApps]: package → through unbraid. */
    val apps: Map<String, Boolean> = emptyMap(),
) {
    /** Whether Direct app [pkg] passes through unbraid for the filter. */
    fun through(pkg: String) = server() != null && (apps[pkg] ?: directApps)

    /** The resolver to ask, or null when none is set or it can't be used. */
    fun server(): Filters.Dns? = when (id) {
        null -> null
        Filters.OWN -> Filters.own(value)
        "nextdns" -> value.trim().takeIf { it.matches(Regex("[A-Za-z0-9]{1,32}")) }?.let { p -> Filters.get(id)?.let { it.copy(path = "/$p", privateDns = "$p.dns.nextdns.io") } }
        else -> Filters.get(id)
    }
}

/** Public filtering resolvers, alphabetical, each reached by its address with its TLS name, so no lookup is needed to find it. */
object Filters {
    const val OWN = "own"

    /** [ip] port [port]; [path] null = DNS over TLS, else over HTTPS; [tls] null with no path = plain DNS. [privateDns]: Android's Private DNS name. */
    data class Dns(
        val id: String, val name: String, val blocks: String, val ip: String, val tls: String?, val path: String? = "/dns-query",
        val port: Int = if (path != null) 443 else if (tls != null) 853 else 53, val privateDns: String? = tls,
    )

    val all = listOf(
        Dns("adguard", "AdGuard DNS", "Ads and trackers", "94.140.14.14", "dns.adguard-dns.com"),
        Dns("adguard-family", "AdGuard DNS Family", "Ads, trackers and adult sites", "94.140.14.15", "family.adguard-dns.com"),
        Dns("cloudflare-security", "Cloudflare 1.1.1.2", "Malware", "1.1.1.2", "security.cloudflare-dns.com"),
        Dns("cloudflare-family", "Cloudflare 1.1.1.3", "Malware and adult sites", "1.1.1.3", "family.cloudflare-dns.com"),
        Dns("nextdns", "NextDNS", "What your NextDNS profile blocks", "45.90.28.0", "dns.nextdns.io", privateDns = null),
        Dns("quad9", "Quad9", "Malware", "9.9.9.9", "dns.quad9.net"),
    )

    fun get(id: String?) = all.firstOrNull { it.id == id }

    private val IPV4 = Regex("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}")
    private val HOST = Regex("(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+")

    /**
     * The user's own server: "https://host[:port]/path", "tls://host[:port]" or a plain IPv4 address (not encrypted).
     * A host name is looked up through the same route (see the compiler); null when it isn't one of these.
     */
    fun own(text: String): Dns? {
        val t = text.trim()
        if (IPV4.matches(t)) return Dns(OWN, t, "", t, null, null)
        val m = Regex("(https|tls)://([A-Za-z0-9.-]+)(?::(\\d{1,5}))?(/(?:[A-Za-z0-9._~!$&'()*+,;=:@/-]|%[0-9A-Fa-f]{2})*)?").matchEntire(t) ?: return null
        val (scheme, rawHost, port, path) = m.destructured
        val host = rawHost.lowercase().trimEnd('.')
        if (!IPV4.matches(host) && !HOST.matches(host)) return null
        val p = port.ifEmpty { null }?.toInt()?.takeIf { it in 1..65535 } ?: if (port.isNotEmpty()) return null else if (scheme == "https") 443 else 853
        val https = scheme == "https"
        if (!https && path.isNotEmpty()) return null
        return Dns(OWN, t, "", host, host.takeUnless { IPV4.matches(it) && https }, if (https) path.ifEmpty { "/dns-query" } else null, p, host.takeIf { !https && !IPV4.matches(it) })
    }

    /** Whether [d] is reached by name, so it needs a lookup first. */
    fun byName(d: Dns) = !IPV4.matches(d.ip)
}
