package app.unbraid

import app.unbraid.data.Nord
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.data.WgLink
import app.unbraid.vpn.Compiler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Where a compiled config sends one app's lookups of a name and its connection to it, read the way the engine reads its
 * rules (first match wins; the fields of one rule must all match, a name matches its `domain` or `domain_suffix`). An
 * address lookup answered with a stand-in address gives a connection that carries the name; one answered by another
 * resolver gives a plain address (the tailnet's, the local network's or a public one) and no name.
 * A rule with a field this reader doesn't know fails the test, so a new kind of rule can't be skipped by mistake.
 */
object RoutingProbe {
    private const val KEY = "aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8tMTI="

    /** One line per app and name: "app name A=... TXT=... go=...". [blocked]: routes without a tunnel. */
    fun outcomes(s: Setup, tsSuffix: String?, names: List<String>, apps: List<String>, blocked: Set<String> = emptySet(), links: Map<String, app.unbraid.data.Link>? = null): List<String> {
        val (dns, route) = compiled(s, tsSuffix, blocked, links)
        return apps.flatMap { app -> names.map { name -> "$app $name A=${lookup(dns, app, name, "A")} TXT=${lookup(dns, app, name, "TXT")} go=${connect(route, dns, app, name)}" } }
    }

    /** The DNS and route sections of [s] compiled with the tailnet domain [tsSuffix], every exit a rule names checked to have its tunnel. */
    fun compiled(s: Setup, tsSuffix: String?, blocked: Set<String> = emptySet(), given: Map<String, app.unbraid.data.Link>? = null): Pair<JsonObject, JsonObject> {
        val links = given ?: s.routes.filter { it.tsExit == null }.associate { r -> Server("${r.id} #1", "${r.id}.nordvpn.com", "10.9.0.${r.id.hashCode() and 0x7f}", "5p4RkybdRU5uaDi90eu4KZPTFif0lKCg4Qp6t1c4F30=", 10).let { r.id to WgLink(it, Nord.peer(it, KEY)) } }
        val c = Json.parseToJsonElement(Compiler.compile(Compiler.Input(s, links, "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", tsSuffix, blocked = blocked))).jsonObject
        val dns = c["dns"]!!.jsonObject
        val route = c["route"]!!.jsonObject
        // Every exit a rule names has its tunnel: the engine would refuse to start otherwise.
        val tags = listOf("endpoints", "outbounds").flatMap { k -> c[k]?.jsonArray.orEmpty().map { it.jsonObject.text("tag")!! } }.toSet()
        val named = route["rules"]!!.jsonArray.flatMap { r -> listOfNotNull(r.jsonObject.text("outbound")) + r.jsonObject["rules"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject.text("outbound") } } + route.text("final")!!
        check(tags.containsAll(named)) { "Rules name exits without a tunnel: ${named - tags}" }
        return dns to route
    }

    private fun JsonObject.list(k: String) = this[k]?.jsonArray?.map { it.jsonPrimitive.content }
    private fun JsonObject.text(k: String) = this[k]?.jsonPrimitive?.content

    private fun named(r: JsonObject, name: String?): Boolean? {
        val exact = r.list("domain"); val suffix = r.list("domain_suffix")
        if (exact == null && suffix == null) return null
        if (name == null) return false
        return exact.orEmpty().any { it == name } || suffix.orEmpty().any { if (it.startsWith(".")) name.endsWith(it) else name == it || name.endsWith(".$it") }
    }

    /** The resolver or action that answers [app]'s lookup of [name] for record [type]. */
    fun lookup(dns: JsonObject, app: String, name: String, type: String): String {
        for (r in dns["rules"]!!.jsonArray.map { it.jsonObject }) {
            val unknown = r.keys - setOf("domain", "domain_suffix", "package_name", "query_type", "server", "action", "rcode", "rewrite_ttl")
            check(unknown.isEmpty()) { "DNS rule field the probe can't read: $unknown" }
            if (named(r, name) == false) continue
            if (r.list("package_name")?.contains(app) == false) continue
            if (r.list("query_type")?.contains(type) == false) continue
            return when (r.text("action")) { null, "route" -> r.text("server")!!; "predefined" -> "empty"; else -> r.text("action")!! }
        }
        return dns.text("final")!!
    }

    private fun ipv4(a: String) = a.split('.').fold(0L) { n, o -> n shl 8 or o.toLong() }
    private fun inCidr(ip: String, cidr: String): Boolean {
        val (net, len) = cidr.split('/').let { it[0] to it[1].toInt() }
        return len == 0 || (ipv4(ip) xor ipv4(net)) shr (32 - len) == 0L
    }
    /** The address a resolver gives for [name]: the tailnet's, the local network's, or a public one. */
    private fun address(server: String, name: String) = when {
        server == "dns-tailnet" -> "100.100.1.1"
        server == "local" && listOf("lan", "local", "home.arpa", "fritz.box", "internal").any { name == it || name.endsWith(".$it") } -> "192.168.1.20"
        else -> "93.184.215.14"
    }

    /**
     * Where [app]'s TCP connection to port 443 of [name] goes: an outbound tag, "reject", or "none" when the lookup was
     * refused. [kept]: the app kept an address from before instead of looking the name up: a stand-in address ("fake",
     * the domain table outlives a reload, so it still carries the name) or a plain one a resolver gave (its server's tag).
     */
    fun connect(route: JsonObject, dns: JsonObject, app: String, name: String, kept: String? = null): String {
        val a = kept ?: lookup(dns, app, name, "A")
        if (a == "reject" || a == "empty") return "none"
        // A stand-in address carries the name; any other answer is a plain address.
        var domain: String? = if (a == "fake") name else null
        var ip = if (a == "fake") "198.18.0.5" else address(a, name)
        fun match(r: JsonObject): Boolean {
            if (r.text("type") == "logical") {
                val all = r["rules"]!!.jsonArray.map { match(it.jsonObject) }
                return (if (r.text("mode") == "and") all.all { it } else all.any { it }) != (r["invert"]?.jsonPrimitive?.content == "true")
            }
            val unknown = r.keys - setOf("domain", "domain_suffix", "package_name", "package_name_regex", "invert", "inbound", "auth_user", "protocol", "port", "network", "ip_cidr", "outbound", "action", "server", "no_drop")
            check(unknown.isEmpty()) { "Route rule field the probe can't read: $unknown" }
            val checks = listOfNotNull(
                named(r, domain),
                r.list("package_name")?.contains(app),
                r.list("package_name_regex")?.let { true }, // the connection names its app
                r.list("inbound")?.contains("tun"),
                r["auth_user"]?.let { false },
                r["protocol"]?.let { false }, // TLS, not DNS
                r["port"]?.jsonPrimitive?.content?.let { it == "443" },
                r.text("network")?.let { it == "tcp" },
                r.list("ip_cidr")?.any { inCidr(ip, it) },
            )
            return checks.all { it } != (r.text("invert") == "true")
        }
        for (r in route["rules"]!!.jsonArray.map { it.jsonObject }) {
            if (!match(r)) continue
            when (r.text("action")) {
                null, "route" -> return r.text("outbound")!!
                "reject" -> return "reject"
                "resolve" -> if (domain != null) ip = address(r.text("server")!!, domain)
                else -> error("Route action the probe can't follow: ${r.text("action")}")
            }
        }
        return route.text("final")!!
    }
}
