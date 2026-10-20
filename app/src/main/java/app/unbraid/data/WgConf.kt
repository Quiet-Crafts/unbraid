package app.unbraid.data

/** Parses a wg-quick style .conf (one peer). Throws IllegalArgumentException with a readable reason. */
object WgConf {
    private val KEY = Regex("[A-Za-z0-9+/]{43}=")
    private val AMNEZIA = setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")

    fun parse(text: String, name: String): WgPeer {
        require(text.length <= 16_384) { "That file is too large for a WireGuard config" }
        require(Regex("(?im)^\\s*\\[peer]").findAll(text).count() == 1) { "Config must have exactly one [Peer]" }
        val kv = mutableMapOf<String, String>()
        var section = ""
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
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
        )
    }

    /** Throws unless [p] sends all IPv4 traffic through its peer, as a route (an app's exit) must; subnet-only profiles belong under Home. */
    fun requireFullTunnel(p: WgPeer) {
        require("0.0.0.0/0" in p.allowedIps) { "This config only reaches ${p.allowedIps.joinToString(", ")}; import it under Settings → Home instead" }
    }
}

/** A WireGuard key: 32 bytes in Base64. */
internal fun validWgKey(k: String) = runCatching { java.util.Base64.getDecoder().decode(k).size == 32 }.getOrDefault(false)
