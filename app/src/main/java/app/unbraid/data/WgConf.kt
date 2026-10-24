package app.unbraid.data

/** Parses a wg-quick style .conf (one peer). Throws IllegalArgumentException with a readable reason. */
object WgConf {
    private val KEY = Regex("[A-Za-z0-9+/]{43}=")
    private val AMNEZIA = setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")

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
        fun key(k: String) = need(k).also { require(KEY.matches(it)) { "Invalid key in the config" } }
        val endpoint = need("[peer].endpoint")
        require(endpoint.substringAfterLast(':').toIntOrNull() in 1..65535) { "Endpoint needs host:port" }
        return WgPeer(
            name = name,
            privateKey = key("[interface].privatekey"),
            // A bare "10.0.0.2" is a host address; the engine needs the prefix.
            address = list("[interface].address").filter { ':' !in it }.map { if ('/' in it) it else "$it/32" }
                .ifEmpty { throw IllegalArgumentException("Config has no IPv4 address") },
            publicKey = key("[peer].publickey"),
            presharedKey = kv["[peer].presharedkey"]?.also { require(KEY.matches(it)) { "Invalid key in the config" } },
            endpoint = endpoint,
            allowedIps = list("[peer].allowedips").filter { ':' !in it }.ifEmpty { throw IllegalArgumentException("Config has no IPv4 AllowedIPs") },
            dns = list("[interface].dns").filter { it.first().isDigit() && ':' !in it },
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
        p.allowedIps.map { app.unbraid.vpn.Compiler.ipv4(it) ?: throw IllegalArgumentException("AllowedIPs has an invalid range: $it") }.map { (a, len) -> val start = a and (0xFFFFFFFFL shl (32 - len) and 0xFFFFFFFFL); start to start + (1L shl (32 - len)) }
            .sortedBy { it.first }.forEach { (start, end) -> if (start <= covered) covered = maxOf(covered, end) }
        require(covered >= 1L shl 32) {
            if (p.allowedIps.any { it.startsWith("0.0.0.0/") || it.startsWith("1.0.0.0/") }) "This config leaves out some addresses (private ranges, for example). Use a config whose AllowedIPs is 0.0.0.0/0 for an app's exit"
            else "This config only reaches ${p.allowedIps.joinToString(", ")}; import it under Settings → Home instead"
        }
    }
}

/** A WireGuard key: 32 bytes in Base64. */
internal fun validWgKey(k: String) = runCatching { java.util.Base64.getDecoder().decode(k).size == 32 }.getOrDefault(false)
