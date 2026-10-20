package app.unbraid.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/** Manually configured servers: OpenVPN (.ovpn) and Shadowsocks (ss:// links) → sing-box JSON. */
object Manual {
    private const val MAX_OVPN = 256 * 1024
    private val DEFAULT_CIPHERS = listOf("AES-256-GCM", "AES-128-GCM", "CHACHA20-POLY1305")
    private val CIPHERS = Regex("AES-(128|192|256)-(CBC|GCM)|CHACHA20-POLY1305|(CAMELLIA|ARIA)-(128|192|256)-CBC")
    private val DIGESTS = setOf("SHA1", "SHA224", "SHA256", "SHA384", "SHA512")
    private val PEM = Regex("-----BEGIN ([A-Za-z0-9 ]+)-----[\\s\\S]*?-----END \\1-----")
    private val TLS_VERSIONS = setOf("1.0", "1.1", "1.2", "1.3")
    private val PROFILES = setOf("legacy", "preferred", "insecure", "suiteb")
    private const val MAX_SS_LINK = 8 * 1024
    private const val MAX_SS_USERINFO = 1024
    private const val MAX_SS_NAME = 128
    private const val MAX_HOST = 253

    /** The TLS 1.2 suites sing-openvpn's `tls.cipher` accepts, by OpenSSL and by IANA name. */
    private val TLS_CIPHERS = (
        "RC4-SHA DES-CBC3-SHA AES128-SHA AES256-SHA AES128-SHA256 AES128-GCM-SHA256 AES256-GCM-SHA384 " +
            "ECDHE-ECDSA-AES128-SHA ECDHE-ECDSA-AES256-SHA ECDHE-ECDSA-RC4-SHA ECDHE-RSA-RC4-SHA ECDHE-RSA-DES-CBC3-SHA " +
            "ECDHE-RSA-AES128-SHA ECDHE-RSA-AES256-SHA ECDHE-ECDSA-AES128-SHA256 ECDHE-RSA-AES128-SHA256 " +
            "ECDHE-ECDSA-AES128-GCM-SHA256 ECDHE-RSA-AES128-GCM-SHA256 ECDHE-ECDSA-AES256-GCM-SHA384 ECDHE-RSA-AES256-GCM-SHA384 " +
            "ECDHE-ECDSA-CHACHA20-POLY1305 ECDHE-RSA-CHACHA20-POLY1305 " +
            "TLS_RSA_WITH_RC4_128_SHA TLS_RSA_WITH_3DES_EDE_CBC_SHA TLS_RSA_WITH_AES_128_CBC_SHA TLS_RSA_WITH_AES_256_CBC_SHA " +
            "TLS_RSA_WITH_AES_128_CBC_SHA256 TLS_RSA_WITH_AES_128_GCM_SHA256 TLS_RSA_WITH_AES_256_GCM_SHA384 " +
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA TLS_ECDHE_ECDSA_WITH_RC4_128_SHA " +
            "TLS_ECDHE_RSA_WITH_RC4_128_SHA TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA " +
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256 TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256 " +
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384 " +
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384 TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256 TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
        ).split(' ').toSet()

    private val USED = setOf(
        "client", "tls-client", "pull", "remote", "remote-random", "port", "rport", "proto", "dev", "dev-type", "ca", "cert", "key",
        "tls-auth", "tls-crypt", "tls-crypt-v2", "key-direction", "auth-user-pass", "auth-retry", "cipher", "data-ciphers", "ncp-ciphers",
        "data-ciphers-fallback", "ncp-disable", "auth", "remote-cert-tls", "remote-cert-eku", "remote-cert-ku", "ns-cert-type",
        "verify-x509-name", "tls-version-min", "tls-version-max", "tls-cert-profile", "comp-lzo", "compress", "allow-compression",
        "mssfix", "fragment", "tun-mtu", "reneg-sec", "reneg-bytes", "reneg-pkts", "ping", "ping-restart", "keepalive", "tls-timeout",
        "hand-window", "explicit-exit-notify", "replay-window", "peer-fingerprint", "key-method", "tls-cipher", "pull-filter", "compat-mode",
    )

    /** Directives that only affect the host OS, routing, logging or scripts; sing-box decides routing itself, scripts never run. */
    private val IGNORED = setOf(
        "nobind", "persist-key", "persist-tun", "persist-local-ip", "persist-remote-ip", "resolv-retry", "verb", "mute", "mute-replay-warnings",
        "connect-retry", "connect-retry-max", "connect-timeout", "server-poll-timeout", "auth-nocache", "fast-io", "float", "sndbuf", "rcvbuf",
        "socket-flags", "tcp-nodelay", "tcp-queue-limit", "txqueuelen", "user", "group", "daemon", "log", "log-append", "syslog", "status",
        "suppress-timestamps", "script-security", "up", "down", "up-restart", "down-pre", "route-up", "route-pre-down", "ipchange", "setenv",
        "setenv-safe", "route", "route-ipv6", "route-gateway", "route-metric", "route-delay", "route-method", "route-noexec", "route-nopull",
        "redirect-gateway", "redirect-private", "block-outside-dns", "dhcp-option", "dhcp-renew", "dhcp-release", "register-dns",
        "ifconfig-noexec", "ifconfig-nowarn", "push-peer-info", "disable-occ", "ignore-unknown-option", "remote-random-hostname",
        "tls-exit", "ping-exit", "ping-timer-rem", "inactive", "lport", "local", "bind", "topology",
        "tun-ipv6", "mlock", "mtu-disc", "mtu-test", "link-mtu", "tun-mtu-extra", "dev-node", "ip-win32", "win-sys", "keysize",
        "x509-username-field", "opt-verify", "dns", "allow-pull-fqdn", "tran-window", "auth-token", "show-ciphers",
    )

    private val REJECTED = mapOf(
        "pkcs12" to "PKCS#12 bundles aren't supported; use a .ovpn with inline <ca>, <cert> and <key> blocks",
        "secret" to "Static-key (secret) profiles aren't supported",
        "mode" to "Only client profiles are supported",
        "tls-server" to "Only client profiles are supported",
        "server" to "Only client profiles are supported",
        "http-proxy" to "OpenVPN over an HTTP proxy isn't supported",
        "socks-proxy" to "OpenVPN over a SOCKS proxy isn't supported",
        "plugin" to "OpenVPN plugins aren't supported",
        "crl-verify" to "crl-verify isn't supported",
        "static-challenge" to "Interactive (2FA) logins aren't supported",
        "auth-user-pass-verify" to "Only client profiles are supported",
        "ifconfig" to "Static tunnel addresses aren't supported",
        "connection" to "<connection> blocks aren't supported",
        "askpass" to "Encrypted private keys aren't supported",
        "capath" to "capath isn't supported; embed the CA in a <ca> block",
        "cryptoapicert" to "Windows certificate store isn't supported",
        "management" to "Management interface isn't supported",
        "tls-ciphersuites" to "tls-ciphersuites (a TLS 1.3 cipher restriction) can't be enforced; remove it from the profile",
    )

    private val BLOCKS = setOf("ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "dh", "extra-certs", "auth-user-pass", "peer-fingerprint")

    private val PULL_ACTIONS = setOf("accept", "ignore", "reject")
    private val SERVER_EKU = setOf("serverauth", "tls web server authentication", "1.3.6.1.5.5.7.3.1")
    private val CLIENT_EKU = setOf("clientauth", "tls web client authentication", "1.3.6.1.5.5.7.3.2")

    /** [seq] keeps every directive in file order, for options where a later one overrides an earlier one of another name. */
    private class Cfg(val d: Map<String, List<List<String>>>, val blocks: Map<String, String>, val seq: List<Pair<String, List<String>>>) {
        fun has(k: String) = k in d
        fun arg(k: String, i: Int = 0) = d[k]?.last()?.getOrNull(i)
        fun last(vararg k: String) = seq.lastOrNull { it.first in k }
    }

    private fun tokens(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var active = false
        var i = 0
        while (i < line.length) {
            val c = line[i++]
            when {
                quote != null -> if (c == quote) quote = null else if (c == '\\' && i < line.length) cur.append(line[i++]) else cur.append(c)
                c == '"' || c == '\'' -> { quote = c; active = true }
                c.isWhitespace() -> if (active) { out += cur.toString(); cur.clear(); active = false }
                !active && (c == '#' || c == ';') -> return out
                c == '\\' && i < line.length -> { cur.append(line[i++]); active = true }
                else -> { cur.append(c); active = true }
            }
        }
        if (active) out += cur.toString()
        return out
    }

    private fun read(text: String): Cfg {
        val d = LinkedHashMap<String, MutableList<List<String>>>()
        val blocks = HashMap<String, String>()
        val seq = mutableListOf<Pair<String, List<String>>>()
        var open: String? = null
        val buf = StringBuilder()
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            if (open != null) {
                if (line == "</$open>") { blocks[open] = buf.toString(); buf.clear(); open = null }
                else buf.append(if (open == "auth-user-pass") raw else line).append('\n')
                continue
            }
            val t = tokens(line)
            if (t.isEmpty()) continue
            if (t.size == 1 && t[0].startsWith("<") && t[0].endsWith(">") && !t[0].startsWith("</")) { open = t[0].substring(1, t[0].length - 1); continue }
            val name = t[0].removePrefix("--")
            d.getOrPut(name) { mutableListOf() } += t.drop(1)
            seq += name to t.drop(1)
        }
        require(open == null) { "Unterminated <$open> block" }
        return Cfg(d, blocks, seq)
    }

    private fun pems(name: String, block: String): String {
        require(!Regex("ENCRYPTED|Proc-Type").containsMatchIn(block)) { "Encrypted private keys aren't supported" }
        val found = PEM.findAll(block).map { it.value.lines().joinToString("\n") { l -> l.trim() } }.toList()
        require(found.isNotEmpty()) { "The <$name> block is empty or malformed" }
        return found.joinToString("\n")
    }

    private fun network(s: String): String {
        val n = s.lowercase().removeSuffix("-client")
        require(n in setOf("udp", "udp4", "udp6", "tcp", "tcp4", "tcp6")) { "Unsupported protocol: $s" }
        return n
    }

    private fun seconds(k: String, v: String?): String {
        val n = v?.toIntOrNull()
        require(n != null && n >= 0) { "Invalid $k value" }
        return "${n}s"
    }

    private fun ciphers(k: String, v: String): List<String> = v.uppercase().split(':').filter { it.isNotEmpty() }.onEach {
        require(CIPHERS.matches(it)) { "Unsupported cipher in $k: $it (use AES-GCM, ChaCha20 or an AES/Camellia/ARIA CBC cipher)" }
    }

    private fun JsonObjectBuilder.put(key: String, v: List<String>) = putJsonArray(key) { v.forEach { add(it) } }

    /** Validates a .ovpn and keeps it with its login. Throws IllegalArgumentException with a readable reason. */
    fun parseOvpn(text: String, name: String, username: String, password: String): OvpnProfile =
        OvpnProfile(name, text, username, password).also { build("check", it) }

    private const val RISK = " Someone between you and this server may be able to read or change your traffic. Import it only if the server can't be updated."

    /** What weakens [p]'s encryption, or null. Old servers can need it, so the user is warned and may import anyway. */
    fun weakness(p: OvpnProfile): String? {
        val c = read(p.config)
        val issues = listOfNotNull(
            "allows TLS 1.0 or 1.1".takeIf { c.arg("tls-version-min") in setOf("1.0", "1.1") },
            "allows the broken RC4 or 3DES ciphers".takeIf { c.arg("tls-cipher").orEmpty().split(':').any { "RC4" in it || "3DES" in it || "DES-CBC3" in it } },
            "accepts weak server certificates (tls-cert-profile insecure)".takeIf { c.arg("tls-cert-profile") == "insecure" },
        )
        return if (issues.isEmpty()) null else "This config ${issues.joinToString(", ")}.$RISK"
    }

    /** Stream ciphers have no integrity check (see [weakness] for OpenVPN). */
    fun weakness(p: SsProfile): String? =
        if ("gcm" in p.method || "poly1305" in p.method) null else "${p.method} has no integrity check.$RISK"

    /** A sing-box `openvpn-client` endpoint for [p]. */
    fun ovpnEndpoint(tag: String, p: OvpnProfile): JsonObject = build(tag, p)

    /** The login to use: what was typed wins; otherwise the profile's inline `<auth-user-pass>` block (username line, password line). */
    private fun login(p: OvpnProfile, inline: String?): Pair<String, String> {
        if (p.username.isNotBlank() || p.password.isNotEmpty() || inline == null) return p.username to p.password
        val lines = inline.lines().filter { it.isNotBlank() }
        return if (lines.size >= 2) lines[0].trim() to lines[1] else "" to ""
    }

    private fun build(tag: String, p: OvpnProfile): JsonObject {
        require(p.config.length <= MAX_OVPN) { "That file is too large for an OpenVPN config" }
        val c = read(p.config)
        for (k in c.d.keys) {
            REJECTED[k]?.let { throw IllegalArgumentException(it) }
            require(k in USED || k in IGNORED) { "Unsupported OpenVPN option: $k" }
        }
        for (k in c.blocks.keys) {
            REJECTED[k]?.let { throw IllegalArgumentException(it) }
            require(k in BLOCKS) { "Unsupported OpenVPN block: <$k>" }
        }
        require(c.has("client") || (c.has("tls-client") && c.has("pull"))) { "Not a client profile (no 'client' line)" }
        val dev = c.arg("dev")
        val devType = c.arg("dev-type")
        require(devType in listOf(null, "tun") && (dev?.startsWith("tun") ?: (devType == "tun"))) { "Only tun (layer 3) profiles are supported" }
        require(c.arg("key-method") in listOf(null, "2")) { "Unsupported key-method" }

        val defaultPort = c.last("port", "rport")?.second?.firstOrNull()?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Invalid port: $it") } ?: 1194
        val defaultNet = network(c.arg("proto") ?: "udp")
        val remotes = c.d["remote"].orEmpty().map { a ->
            val host = a.getOrNull(0).orEmpty()
            require(host.isNotBlank()) { "Empty remote host" }
            val port = a.getOrNull(1)?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Invalid remote port: $it") } ?: defaultPort
            require(port in 1..65535) { "Remote port out of range: $port" }
            Triple(host, port, a.getOrNull(2)?.let(::network) ?: defaultNet)
        }
        require(remotes.isNotEmpty()) { "The profile has no 'remote' server" }

        require(!c.has("ca") || "ca" in c.blocks) { "The CA must be inline (<ca> block), not a file path" }
        for (k in listOf("cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2")) {
            require(!c.has(k) || k in c.blocks) { "$k must be inline (<$k> block), not a file path" }
        }
        val fingerprints = c.d["peer-fingerprint"].orEmpty().mapNotNull { it.firstOrNull() } +
            c.blocks["peer-fingerprint"].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        require("ca" in c.blocks || fingerprints.isNotEmpty()) { "The profile has no <ca> block" }
        require(("cert" in c.blocks) == ("key" in c.blocks)) { "The profile needs both <cert> and <key>, or neither" }
        val wraps = listOf("tls-auth", "tls-crypt", "tls-crypt-v2").filter { it in c.blocks }
        require(wraps.size <= 1) { "Use only one of tls-auth, tls-crypt and tls-crypt-v2" }

        require("extra-certs" !in c.blocks || "cert" in c.blocks) { "<extra-certs> needs a <cert> block" }

        val needsLogin = c.has("auth-user-pass") || "auth-user-pass" in c.blocks
        val (user, pass) = login(p, c.blocks["auth-user-pass"])
        require(!needsLogin || (user.isNotBlank() && pass.isNotEmpty())) { "This profile needs a username and password" }

        val compat = c.arg("compat-mode")?.let { v ->
            Regex("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})").matchEntire(v)?.destructured?.let { (a, b, p) -> a.toInt() * 10000 + b.toInt() * 100 + p.toInt() }
                ?: throw IllegalArgumentException("Invalid compat-mode: $v")
        } ?: 0
        val before24 = compat in 1 until 20400
        val before25 = compat in 1 until 20500
        fun single(k: String, a: List<String>) = ciphers(k, a.firstOrNull().orEmpty()).singleOrNull() ?: throw IllegalArgumentException("Invalid $k value")
        // OpenVPN stores `cipher` and `data-ciphers-fallback` in one slot, so whichever comes last names the cipher.
        val named = c.seq.filter { it.first == "cipher" || it.first == "data-ciphers-fallback" }.map { (k, a) -> single(k, a) }.lastOrNull()
        val explicit = (c.arg("data-ciphers") ?: c.arg("ncp-ciphers"))?.let { ciphers("data-ciphers", it) }
        val pinned = if (c.has("ncp-disable")) listOf(requireNotNull(c.arg("cipher")?.let { single("cipher", listOf(it)) }) { "ncp-disable needs a cipher line" }) else null
        require(!before24 || named != null) { "compat-mode below 2.4.0 needs a cipher line (the old BF-CBC default isn't supported)" }
        // OpenVPN 2.6 negotiates only from data-ciphers; a lone `cipher` is the fallback only for compat-mode < 2.4.0 or data-ciphers-fallback.
        val fallback = if (pinned != null) pinned[0] else named.takeIf { c.has("data-ciphers-fallback") || before24 }
        val base = explicit ?: DEFAULT_CIPHERS
        val dataCiphers = pinned ?: (if (before25 && named != null && named !in base) base + named else base).takeIf { explicit != null || it != DEFAULT_CIPHERS }
        val tlsCipher = c.arg("tls-cipher")?.also { v ->
            v.split(':').firstOrNull { it !in TLS_CIPHERS }?.let { throw IllegalArgumentException("Unsupported tls-cipher: $it (only the TLS 1.2 suites sing-box implements, by OpenSSL or IANA name)") }
        }
        val auth = c.arg("auth")?.uppercase()?.also { require(it in DIGESTS) { "Unsupported auth digest: $it" } }

        val lzo = if (c.has("comp-lzo")) (c.arg("comp-lzo") ?: "adaptive").lowercase().also {
            require(it in setOf("yes", "no", "adaptive", "asym")) { "Unsupported comp-lzo value: $it" }
        } else null
        val compress = if (c.has("compress")) (c.arg("compress") ?: "stub").lowercase().also {
            require(it in setOf("stub", "stub-v2", "lz4", "lz4-v2")) { "Unsupported compression: compress $it" }
        } else null
        val compressing = (lzo != null && lzo != "no") || compress?.startsWith("lz4") == true
        val allow = c.arg("allow-compression")?.also { require(it in setOf("no", "asym", "yes")) { "Invalid allow-compression" } }
        require(!compressing || allow != "no") { "allow-compression no conflicts with the compression in this profile" }

        val tcp = remotes.any { it.third.startsWith("tcp") }
        val fragment = c.arg("fragment")?.let { f -> f.toIntOrNull()?.takeIf { it >= 68 } ?: throw IllegalArgumentException("Invalid fragment value") }
        require(fragment == null || !tcp) { "fragment only works over UDP" }
        val mtu = c.arg("tun-mtu")?.let { m -> m.toIntOrNull()?.takeIf { it in 576..9000 } ?: throw IllegalArgumentException("Invalid tun-mtu value") }

        return buildJsonObject {
            put("type", "openvpn-client")
            put("tag", tag)
            if (remotes.size == 1) {
                put("server", remotes[0].first); put("server_port", remotes[0].second); put("network", remotes[0].third)
            } else {
                putJsonArray("servers") {
                    remotes.forEach { (h, port, net) -> add(buildJsonObject { put("server", h); put("server_port", port); put("network", net) }) }
                }
                if (c.has("remote-random")) put("remote_random", true)
            }
            if (needsLogin) { put("username", user); put("password", pass) }
            c.arg("auth-retry")?.let { r -> put("auth_retry", r.also { require(it in setOf("none", "nointeract", "interact")) { "Invalid auth-retry" } }) }
            putJsonObject("tls") {
                c.d["verify-x509-name"]?.last()?.let { a ->
                    put("server_name", a[0])
                    put("server_name_type", (a.getOrNull(1) ?: "subject").also { require(it in setOf("subject", "name", "name-prefix")) { "Invalid verify-x509-name type: $it" } })
                }
                c.blocks["ca"]?.let { put("certificate", pems("ca", it)) }
                // A client certificate chain is the leaf followed by its intermediates, which is how <extra-certs> is meant.
                c.blocks["cert"]?.let { put("client_certificate", listOfNotNull(pems("cert", it), c.blocks["extra-certs"]?.let { x -> pems("extra-certs", x) }).joinToString("\n")) }
                c.blocks["key"]?.let { put("client_key", pems("key", it)) }
                if (fingerprints.isNotEmpty()) put("peer_fingerprint", fingerprints.map { f ->
                    f.replace(":", "").lowercase().also { require(Regex("[0-9a-f]{64}").matches(it)) { "Invalid peer-fingerprint" } }
                })
                val ku = c.d["remote-cert-ku"]?.last()
                ku?.takeIf { it.isNotEmpty() }?.let { put("remote_certificate_ku", it.onEach { v -> require(Regex("(0x)?[0-9a-fA-F]+").matches(v)) { "Invalid remote-cert-ku value" } }) }
                // The last of remote-cert-tls / remote-cert-eku sets the EKU; remote-cert-tls and a bare remote-cert-ku also require a KU extension.
                c.last("remote-cert-eku", "remote-cert-tls")?.let { (k, a) ->
                    val v = a.singleOrNull() ?: throw IllegalArgumentException("Invalid $k")
                    val needsKu = (ku != null && ku.isEmpty()) || c.has("remote-cert-tls")
                    val shorthand = if (k == "remote-cert-tls") v else when (v.lowercase()) {
                        in SERVER_EKU -> "server".takeIf { needsKu && ku.isNullOrEmpty() }
                        in CLIENT_EKU -> "client".takeIf { needsKu && ku.isNullOrEmpty() }
                        else -> null
                    }
                    when {
                        shorthand != null -> put("remote_certificate_tls", shorthand.also { require(it == "server" || it == "client") { "Invalid remote-cert-tls" } })
                        needsKu && ku.isNullOrEmpty() -> throw IllegalArgumentException(
                            "remote-cert-eku $v can't be combined with remote-cert-tls or a bare remote-cert-ku (which require a key-usage extension); list the key usages (remote-cert-ku a0 88) or drop one",
                        )
                        else -> put("remote_certificate_eku", v)
                    }
                }
                // Alone, a bare remote-cert-ku only demands that a key-usage extension exist: no engine field says that.
                if (ku != null && ku.isEmpty() && c.last("remote-cert-eku", "remote-cert-tls") == null) throw IllegalArgumentException(
                    "A bare remote-cert-ku isn't supported on its own; list the key usages (remote-cert-ku a0 88) or add remote-cert-tls server",
                )
                c.arg("ns-cert-type")?.let { put("ns_certificate_type", it.also { v -> require(v == "server" || v == "client") { "Invalid ns-cert-type" } }) }
                c.arg("tls-version-min")?.let { put("version_min", it.also { v -> require(v in TLS_VERSIONS) { "Invalid tls-version-min" } }) }
                c.arg("tls-version-max")?.let { put("version_max", it.also { v -> require(v in TLS_VERSIONS) { "Invalid tls-version-max" } }) }
                tlsCipher?.let { put("cipher", it) }
                c.arg("tls-cert-profile")?.let { put("certificate_profile", it.also { v -> require(v in PROFILES) { "Unsupported tls-cert-profile: $v" } }) }
                wraps.singleOrNull()?.let { w ->
                    putJsonObject("control_wrap") {
                        put("type", w.replace('-', '_'))
                        put("key", pems(w, c.blocks.getValue(w)))
                        if (w == "tls-auth") (c.arg("tls-auth", 1)?.takeIf { it != "[inline]" } ?: c.arg("key-direction"))?.let {
                            put("direction", when (it) { "0" -> "server"; "1" -> "client"; else -> throw IllegalArgumentException("Invalid key-direction: $it") })
                        }
                    }
                }
            }
            dataCiphers?.let { put("data_ciphers", it) }
            fallback?.let { put("data_ciphers_fallback", it) }
            auth?.let { put("auth", it) }
            lzo?.let { put("compression_lzo", it) }
            compress?.let { put("compression", it) }
            if (compressing || allow == "yes" || allow == "asym") put("allow_compression", "asym")
            c.d["mssfix"]?.last()?.let { a ->
                val n = a.getOrNull(0)?.let { v -> v.toIntOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("Invalid mssfix value") }
                if (n == 0) put("mss_fix_disabled", true)
                else if (n != null) {
                    put("mss_fix", n)
                    a.getOrNull(1)?.let { put("mss_fix_mode", it.also { m -> require(m == "mtu" || m == "fixed") { "Invalid mssfix mode" } }) }
                }
            }
            fragment?.let { put("fragment", it) }
            mtu?.let { put("mtu", it) }
            c.arg("reneg-sec")?.let { if (it.toIntOrNull() == 0) put("renegotiate_disabled", true) else put("renegotiate_interval", seconds("reneg-sec", it)) }
            c.arg("reneg-bytes")?.let { put("renegotiate_bytes", it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw IllegalArgumentException("Invalid reneg-bytes")) }
            c.arg("reneg-pkts")?.let { put("renegotiate_packets", it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw IllegalArgumentException("Invalid reneg-pkts")) }
            val ka = c.d["keepalive"]?.last()
            (c.arg("ping") ?: ka?.getOrNull(0))?.takeIf { it != "0" }?.let { put("ping_interval", seconds("ping", it)) }
            (c.arg("ping-restart") ?: ka?.getOrNull(1))?.let {
                if (it.toIntOrNull() == 0) put("ping_restart_disabled", true) else put("ping_restart", seconds("ping-restart", it))
            }
            c.arg("tls-timeout")?.let { put("tls_timeout", seconds("tls-timeout", it)) }
            c.arg("hand-window")?.let { put("handshake_window", seconds("hand-window", it)) }
            if (c.has("explicit-exit-notify")) put("explicit_exit_notify", (c.arg("explicit-exit-notify") ?: "1").toIntOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("Invalid explicit-exit-notify"))
            c.arg("replay-window")?.let { w ->
                put("replay_window", w.toIntOrNull()?.takeIf { it in 1..65536 } ?: throw IllegalArgumentException("Invalid replay-window"))
                c.arg("replay-window", 1)?.let { put("replay_window_time", seconds("replay-window", it)) }
            }
            c.d["pull-filter"]?.takeIf { it.isNotEmpty() }?.let { filters ->
                putJsonArray("pull_filters") {
                    filters.forEach { a ->
                        require(a.size == 2 && a[0] in PULL_ACTIONS && a[1].isNotEmpty()) { "Invalid pull-filter: expected accept, ignore or reject and one text" }
                        add(buildJsonObject { put("action", a[0]); put("text", a[1]) })
                    }
                }
            }
            put("route_no_pull", true)
        }
    }

    private val SS_METHODS = setOf(
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
        "aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
        "aes-128-ctr", "aes-192-ctr", "aes-256-ctr", "aes-128-cfb", "aes-192-cfb", "aes-256-cfb", "rc4-md5", "chacha20-ietf", "xchacha20",
    )
    private val SS_PLUGINS = mapOf("obfs-local" to "obfs-local", "simple-obfs" to "obfs-local", "v2ray-plugin" to "v2ray-plugin")

    private fun unpercent(s: String): String =
        runCatching { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrElse { throw IllegalArgumentException("The link has a malformed %-escape") }

    private fun b64(s: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(s.replace('+', '-').replace('/', '_').trimEnd('=')), Charsets.UTF_8)
    }.getOrNull()

    /** Parses a SIP002 (`ss://base64(method:password)@host:port/?plugin=…#name`, or with plain userinfo) or legacy `ss://base64(method:password@host:port)#name` link. */
    fun parseSs(link: String, name: String = ""): SsProfile {
        require(link.length <= MAX_SS_LINK) { "That link is too long to be a Shadowsocks key" }
        val s = link.trim()
        require(!s.startsWith("ssconf://", ignoreCase = true)) { "Dynamic ssconf:// keys aren't supported; use the ss:// key" }
        require(s.startsWith("ss://", ignoreCase = true)) { "Not a Shadowsocks link (expected ss://…)" }
        val rest = s.substring(5)
        val tag = rest.substringAfter('#', "").let(::unpercent).trim()
        val noFrag = rest.substringBefore('#')
        val query = noFrag.substringAfter('?', "")
        val body = noFrag.substringBefore('?').trimEnd('/')

        val userinfo: String
        val hostPort: String
        if ('@' in body) {
            userinfo = unpercent(body.substringBeforeLast('@')).let { if (':' in it) it else b64(it) ?: throw IllegalArgumentException("Can't decode the link's credentials") }
            hostPort = body.substringAfterLast('@')
        } else {
            val decoded = b64(unpercent(body)) ?: throw IllegalArgumentException("Can't decode the link")
            require('@' in decoded) { "The link has no server address" }
            userinfo = decoded.substringBeforeLast('@')
            hostPort = decoded.substringAfterLast('@')
        }
        require(userinfo.length <= MAX_SS_USERINFO) { "The link's credentials are too long" }
        require(':' in userinfo) { "The link has no method or password" }
        val method = userinfo.substringBefore(':').lowercase()
        val password = userinfo.substringAfter(':')
        require(method in SS_METHODS) { "Unsupported Shadowsocks cipher: $method" }
        require(password.isNotEmpty()) { "The link has an empty password" }

        val host = if (hostPort.startsWith("[")) hostPort.substringAfter('[').substringBefore(']') else hostPort.substringBeforeLast(':')
        val port = hostPort.substringAfterLast(':', "").toIntOrNull()
        require(host.isNotBlank() && host.none { it.isWhitespace() || it == '/' }) { "The link has no server host" }
        require(host.length <= MAX_HOST) { "The link's server host is too long" }
        require(port != null && port in 1..65535) { "The link has an invalid port" }

        val params = query.split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to unpercent(it.substringAfter('=', "")) }
        require("prefix" !in params) { "Outline connection prefixes aren't supported" }
        val spec = params["plugin"]?.takeIf { it.isNotBlank() }
        val pluginName = spec?.substringBefore(';')?.let { SS_PLUGINS[it] ?: throw IllegalArgumentException("Unsupported Shadowsocks plugin: $it (only obfs-local and v2ray-plugin)") }
        val label = name.ifBlank { tag }.ifBlank { host }
        require(label.length <= MAX_SS_NAME || label == host) { "The server name is too long (at most $MAX_SS_NAME characters)" }
        val opts = spec?.substringAfter(';', "")?.ifEmpty { null }
        // v2ray-plugin's cert= names a file on the phone, which the engine would read: only an inline certificate is taken.
        // SIP003 escapes with backslashes; they're dropped first so "c\ert=" can't slip past.
        require(opts?.replace("\\", "")?.split(';')?.none { it.substringBefore('=').trim() == "cert" } != false) { "The plugin's cert= option reads a file on this phone; paste the certificate as certRaw= instead" }
        return SsProfile(label, host, port, method, password, pluginName, opts)
    }

    /** A sing-box `shadowsocks` outbound for [p]. */
    fun ssOutbound(tag: String, p: SsProfile): JsonObject = buildJsonObject {
        put("type", "shadowsocks")
        put("tag", tag)
        put("server", p.server)
        put("server_port", p.port)
        put("method", p.method)
        put("password", p.password)
        p.plugin?.let { put("plugin", it) }
        p.pluginOpts?.let { put("plugin_opts", it) }
    }
}
