package app.unbraid.data

/** Parses a wg-quick style .conf (one peer). Throws IllegalArgumentException with a readable reason. */
object WgConf {
    private val KEY = Regex("[A-Za-z0-9+/]{43}=")
    private val AMNEZIA = setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")

    private const val BAD_RANGE = "Invalid AllowedIPs range in the config"
    private val LABEL = Regex("[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?")

    /**
     * Throws unless [endpoint] is `host:port` with a DNS name or an IPv4 address, or `[IPv6]:port`: what the engine
     * reads as an address or a name to look up. Neither part is quoted in the error. Nothing is looked up here.
     */
    internal fun checkEndpoint(endpoint: String) {
        val (host, port) = if (endpoint.startsWith("[")) {
            val close = endpoint.indexOf(']')
            require(close > 0 && endpoint.startsWith("]:", close)) { "Endpoint needs host:port" }
            require(ipv6(endpoint.substring(1, close))) { "Endpoint has an invalid host" }
            "" to endpoint.substring(close + 2)
        } else {
            require(':' in endpoint) { "Endpoint needs host:port" }
            endpoint.substringBeforeLast(':') to endpoint.substringAfterLast(':')
        }
        require(port.isNotEmpty() && port.all { it in '0'..'9' } && port.toIntOrNull() in 1..65535) { "Endpoint needs host:port" }
        require(endpoint.startsWith("[") || hostname(host)) { "Endpoint has an invalid host" }
    }

    /** A dotted IPv4 address, or a DNS name (an all-numeric one is no name, so it must be a valid address). */
    private fun hostname(h: String): Boolean = when {
        h.isEmpty() || h.length > 253 -> false
        h.all { it in '0'..'9' || it == '.' } -> app.unbraid.vpn.Compiler.ipv4(h) != null
        else -> h.removeSuffix(".").split('.').all(LABEL::matches)
    }

    /** An IPv6 address in text form, with or without `::` and a dotted IPv4 tail; no zone. */
    internal fun ipv6(h: String): Boolean {
        val colon = h.lastIndexOf(':')
        if (colon < 0 || h.length > 45) return false
        val tail = h.substring(colon + 1)
        val s = if ('.' in tail) {
            if (tail.any { it !in '0'..'9' && it != '.' } || app.unbraid.vpn.Compiler.ipv4(tail) == null) return false
            h.substring(0, colon + 1) + "0:0"
        } else h
        val halves = s.split("::")
        if (halves.size > 2) return false
        val groups = halves.flatMap { if (it.isEmpty()) emptyList() else it.split(':') }
        if (groups.any { g -> g.length !in 1..4 || g.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' } }) return false
        return if (halves.size == 2) groups.size <= 7 else groups.size == 8
    }

    fun parse(raw: String, name: String): WgPeer {
        val text = raw.removePrefix("\uFEFF") // a byte-order mark some editors write first
        require(text.length <= 16_384) { "That file is too large for a WireGuard config" }
        require(Regex("(?im)^\\s*\\[peer]").findAll(text).count() == 1) { "Config must have exactly one [Peer]" }
        val kv = mutableMapOf<String, String>()
        var section = ""
        for (l in text.lineSequence()) {
            val line = l.substringBefore('#').trim()
            if (line.startsWith("[")) { section = line.lowercase(); continue }
            val k = line.substringBefore('=', "").trim().lowercase()
            if (k.isEmpty()) continue
            require(k !in AMNEZIA) { "Obfuscated (AmneziaWG) configs aren't supported" }
            val key = "$section.$k"
            val v = line.substringAfter('=').trim()
            kv[key] = kv[key]?.let { "$it,$v" } ?: v
        }
        fun need(k: String) = kv[k] ?: throw IllegalArgumentException("Missing ${k.substringAfter('.')} in the config")
        fun list(k: String) = kv[k].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        // A bad key is named by its field and never quoted.
        fun key(k: String, field: String) = need(k).also { require(KEY.matches(it) && validWgKey(it)) { "Invalid $field in the config" } }
        val endpoint = need("[peer].endpoint")
        checkEndpoint(endpoint)
        val address = list("[interface].address").filter { ':' !in it }.map { if ('/' in it) it else "$it/32" }
        // The engine refuses a whole setup over one bad address, so each is checked here (IPv4 only: IPv6 is dropped).
        require(address.all { app.unbraid.vpn.Compiler.ipv4(it) != null }) { "Invalid Address in the config" }
        val allowed = list("[peer].allowedips").filter { ':' !in it }
        // The range is not quoted: what was pasted into the wrong line could be a key or a password.
        allowed.forEach { require(app.unbraid.vpn.Compiler.ipv4(it) != null) { BAD_RANGE } }
        return WgPeer(
            name = name,
            privateKey = key("[interface].privatekey", "PrivateKey"),
            // A bare "10.0.0.2" is a host address; the engine needs the prefix.
            address = address
                .ifEmpty { throw IllegalArgumentException("Config has no IPv4 address") },
            publicKey = key("[peer].publickey", "PublicKey"),
            presharedKey = kv["[peer].presharedkey"]?.also { require(KEY.matches(it) && validWgKey(it)) { "Invalid PresharedKey in the config" } },
            endpoint = endpoint,
            allowedIps = allowed.ifEmpty { throw IllegalArgumentException("Config has no IPv4 AllowedIPs") },
            dns = list("[interface].dns").filter { app.unbraid.vpn.Compiler.ipv4(it)?.second == 32 && '/' !in it },
            mtu = kv["[interface].mtu"]?.toIntOrNull()?.coerceIn(1280, 1500) ?: 1420,
            keepalive = kv["[peer].persistentkeepalive"]?.let { v ->
                if (v.equals("off", ignoreCase = true)) 0 else v.toIntOrNull()?.takeIf { it in 0..65535 } ?: throw IllegalArgumentException("Invalid PersistentKeepalive in the config")
            },
        )
    }

    /**
     * Throws unless [p] sends all IPv4 traffic through its peer, as a route (an app's exit) must: 0.0.0.0/0, or ranges
     * that together cover every address (0.0.0.0/1 and 128.0.0.0/1). Subnet-only profiles belong under Home.
     */
    fun requireFullTunnel(p: WgPeer) {
        var covered = 0L // every address below this is in some range
        p.allowedIps.map { app.unbraid.vpn.Compiler.ipv4(it) ?: throw IllegalArgumentException(BAD_RANGE) }.map { (a, len) -> val start = a and (0xFFFFFFFFL shl (32 - len) and 0xFFFFFFFFL); start to start + (1L shl (32 - len)) }
            .sortedBy { it.first }.forEach { (start, end) -> if (start <= covered) covered = maxOf(covered, end) }
        require(covered >= 1L shl 32) {
            if (p.allowedIps.any { it.startsWith("0.0.0.0/") || it.startsWith("1.0.0.0/") }) "This config leaves out some addresses (private ranges, for example). Use a config whose AllowedIPs is 0.0.0.0/0 for an app's exit"
            else "This config only reaches ${p.allowedIps.joinToString(", ")}; import it under Settings → Home instead"
        }
    }
}

private val WG_KEY = Regex("[A-Za-z0-9+/]{43}=")

/** A WireGuard key as the engine reads it: 32 bytes in padded standard Base64 (it refuses one without the final `=`). */
internal fun validWgKey(k: String) = WG_KEY.matches(k) && runCatching { java.util.Base64.getDecoder().decode(k).size == 32 }.getOrDefault(false)
