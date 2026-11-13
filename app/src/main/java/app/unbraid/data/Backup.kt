package app.unbraid.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A backup file: the user's setup sealed under a passphrase. Layout (version 1), all integers big-endian:
 *
 *     magic "UNBRAID-BACKUP\n" (15) | format 1 (1) | kdf 1 = scrypt (1) | log2 N, r, p (1 each) | salt (16) |
 *     nonce (12) | check (16) | length of what follows (4) | AES-256-GCM ciphertext + tag | SHA-256 of all before (32)
 *
 * scrypt turns the passphrase (NFC, UTF-8) into 64 bytes: the first 32 are the AES key, the last 32 key an HMAC whose
 * first 16 bytes are the check, so a wrong passphrase is told apart from a damaged file. The header is the cipher's
 * additional data. The plain SHA-256 at the end tells a damaged or cut file apart before any key is derived.
 */
object Backup {
    private val MAGIC = "UNBRAID-BACKUP\n".toByteArray(Charsets.US_ASCII)
    const val FORMAT = 1
    private const val SCRYPT = 1
    /** 64 MiB and two passes: an OWASP-listed scrypt setting, within any phone's memory for one app. */
    const val LOG_N = 16
    const val R = 8
    const val P = 2
    private const val SALT = 16
    private const val NONCE = 12
    private const val CHECK = 16
    private const val SUM = 32
    private const val TAG = 16
    private val HEAD = MAGIC.size + 5 + SALT + NONCE + CHECK + 4
    /** No setup comes near this; a larger file is refused before it is read whole. */
    const val MAX = 4 shl 20

    enum class Reason(val text: String) {
        NotBackup("This file isn't an unbraid backup."),
        TooLarge("This file is too large to be an unbraid backup."),
        Newer("This backup was made by a newer unbraid. Update unbraid, then restore it."),
        Damaged("This backup is damaged or incomplete. Use another copy of it."),
        Passphrase("That passphrase doesn't open this backup. Check it and try again."),
        Memory("There isn't enough free memory to do this now. Close other apps and try again."),
        Invalid("This backup can't be restored."),
        TooBig("Your setup is too large for one backup file. Remove some own servers, then try again."),
    }

    class Failed(val reason: Reason, detail: String? = null) : Exception(detail?.let { "${reason.text.removeSuffix(".")}: $it" } ?: reason.text)

    private fun fail(r: Reason, detail: String? = null): Nothing = throw Failed(r, detail)

    /** Starts like a backup: worth asking for its passphrase. */
    fun looksLike(b: ByteArray) = b.size >= MAGIC.size && MAGIC.indices.all { b[it] == MAGIC[it] }

    /** What can be said of [b] without the passphrase: null when it may open, else why it can't. */
    fun precheck(b: ByteArray): Reason? = when {
        b.size > MAX -> Reason.TooLarge
        !looksLike(b) -> Reason.NotBackup
        b.size <= MAGIC.size -> Reason.Damaged
        (b[MAGIC.size].toInt() and 0xff) > FORMAT -> Reason.Newer
        b[MAGIC.size].toInt() != FORMAT || b.size < HEAD + TAG + SUM -> Reason.Damaged
        !MessageDigest.isEqual(sha256(b, b.size - SUM), b.copyOfRange(b.size - SUM, b.size)) -> Reason.Damaged
        else -> null
    }

    /** Settings a file may ask for: at most 128 MiB and twice the default's work, so a hostile file can't exhaust the phone. */
    internal fun allowed(logN: Int, r: Int, p: Int) = logN in 10..20 && r in 1..16 && p in 1..4 &&
        (r.toLong() shl logN) * 128 <= 128L shl 20 && p.toLong() * (r.toLong() shl logN) <= 1L shl 21

    fun seal(plain: ByteArray, pass: String, logN: Int = LOG_N, r: Int = R, p: Int = P, rnd: SecureRandom = SecureRandom()): ByteArray {
        require(allowed(logN, r, p))
        val salt = ByteArray(SALT).also(rnd::nextBytes)
        val nonce = ByteArray(NONCE).also(rnd::nextBytes)
        val k = derive(pass, salt, logN, r, p)
        try {
            val head = ByteBuffer.allocate(HEAD).put(MAGIC).put(FORMAT.toByte()).put(SCRYPT.toByte())
                .put(logN.toByte()).put(r.toByte()).put(p.toByte()).put(salt).put(nonce).put(check(k)).putInt(plain.size + TAG).array()
            val body = head + gcm(Cipher.ENCRYPT_MODE, k, nonce, head).doFinal(plain)
            // Never a file that restoring would refuse.
            if (body.size + SUM > MAX) fail(Reason.TooBig)
            return body + sha256(body, body.size)
        } finally { k.fill(0) }
    }

    /** The plaintext of backup [b]; throws [Failed] with why it won't open. */
    fun open(b: ByteArray, pass: String): ByteArray {
        precheck(b)?.let { fail(it) }
        val buf = ByteBuffer.wrap(b, MAGIC.size + 1, HEAD - MAGIC.size - 1)
        val kdf = buf.get().toInt()
        val logN = buf.get().toInt() and 0xff
        val r = buf.get().toInt() and 0xff
        val p = buf.get().toInt() and 0xff
        if (kdf != SCRYPT || !allowed(logN, r, p)) fail(Reason.Damaged)
        val salt = ByteArray(SALT).also { buf.get(it) }
        val nonce = ByteArray(NONCE).also { buf.get(it) }
        val chk = ByteArray(CHECK).also { buf.get(it) }
        val len = buf.getInt()
        if (len != b.size - SUM - HEAD) fail(Reason.Damaged)
        val k = derive(pass, salt, logN, r, p)
        try {
            if (!MessageDigest.isEqual(check(k), chk)) fail(Reason.Passphrase)
            return runCatching { gcm(Cipher.DECRYPT_MODE, k, nonce, b.copyOfRange(0, HEAD)).doFinal(b, HEAD, len) }.getOrElse { fail(Reason.Damaged) }
        } finally { k.fill(0) }
    }

    private fun derive(pass: String, salt: ByteArray, logN: Int, r: Int, p: Int): ByteArray {
        val pw = Normalizer.normalize(pass, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        return try { Scrypt.derive(pw, salt, 1 shl logN, r, p, 64) } catch (_: OutOfMemoryError) { fail(Reason.Memory) } finally { pw.fill(0) }
    }

    private fun check(k: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(k, 32, 32, "HmacSHA256")); doFinal("unbraid backup check".toByteArray()).copyOf(CHECK)
    }

    private fun gcm(mode: Int, k: ByteArray, nonce: ByteArray, head: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(k, 0, 32, "AES"), GCMParameterSpec(TAG * 8, nonce)); updateAAD(head)
    }

    private fun sha256(b: ByteArray, n: Int) = MessageDigest.getInstance("SHA-256").apply { update(b, 0, n) }.digest()

    /** How hard [pass] is to guess: 0 weak, 1 fair, 2 strong, and a sentence. A hint only: nothing is refused. */
    fun strength(pass: String): Pair<Int, String> {
        val pool = listOf(26 to pass.any(Char::isLowerCase), 26 to pass.any(Char::isUpperCase), 10 to pass.any(Char::isDigit),
            33 to pass.any { !it.isLetterOrDigit() }).sumOf { if (it.second) it.first else 0 }.coerceAtLeast(1)
        val bits = graphemes(pass) * Math.log(pool.toDouble()) / Math.log(2.0)
        val words = pass.trim().split(Regex("\\s+")).count { it.length >= 3 }
        return when {
            words >= 4 || bits >= 72 -> 2 to "Strong."
            bits >= 50 -> 1 to "Fair. Longer is safer: four or more words work well."
            else -> 0 to "Easy to guess. Use 12 or more characters, or four or more words."
        }
    }

    // ---- what the file holds ----

    /** The plaintext: a format of its own, so the setup inside can change format with the app ([Setup.version]). */
    @Serializable
    data class Contents(
        val format: Int = CONTENTS, val app: String = "", val created: Long = 0, val setup: Setup,
        /** False: saved without own servers' keys and passwords and without the home WireGuard peer ([contents]). */
        val keys: Boolean = true,
    )

    const val CONTENTS = 1
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * What a backup of [s] holds: routes, app choices, favourites, profiles, own servers, the home WireGuard peer,
     * preferences and provider page settings. Never sign-ins (they live outside [Setup]), nor anything of Tailscale
     * (its auth key, server and subnets belong to this phone's sign-in), nor the statistics consent. Without [keys], own
     * servers and the routes through them keep only what isn't secret ([withoutSecrets]), and the home peer is left out.
     */
    fun contents(s: Setup, keys: Boolean = true): Setup {
        val c = s.copy(home = Home(wg = s.home.wg), prefs = s.prefs.copy(stats = false), pristine = false,
            waiting = emptySet(), waitingUntil = 0, version = VERSION, testing = emptySet())
        if (keys) return c
        val linked = linked(c)
        return linked.copy(home = Home(), servers = linked.servers.map { it.withoutSecrets() }, routes = linked.routes.map { it.withoutSecrets() })
    }

    /** [s] with every imported route tied to an own server, so a route saved without keys has one to get them back through. */
    private fun linked(s: Setup): Setup {
        var servers = s.servers
        val routes = s.routes.map { r ->
            if (!r.imported || r.tsExit != null || servers.any { it.id == r.own }) return@map r
            val o = servers.firstOrNull { it.wg == r.wg && it.ovpn == r.ovpn && it.ss == r.ss }
                ?: OwnServer(generateSequence { "s" + java.util.UUID.randomUUID().toString().take(8) }.first { id -> servers.none { it.id == id } }, r.wg, r.ovpn, r.ss)
                    .also { servers = servers + it }
            r.copy(own = o.id)
        }
        return s.copy(routes = routes, servers = servers)
    }

    /** Whether [s] holds anything [contents] leaves out without keys: own servers, imported routes or a home peer. */
    fun hasSecrets(s: Setup) = s.servers.isNotEmpty() || s.home.wg != null || s.routes.any { it.imported && it.tsExit == null }

    fun export(s: Setup, app: String, now: Long, keys: Boolean = true) =
        json.encodeToString(Contents.serializer(), Contents(CONTENTS, app, now, contents(s, keys), keys)).toByteArray()

    /** [plain] read and checked whole ([valid]); throws [Failed] (Newer, Invalid) and changes nothing. */
    fun read(plain: ByteArray): Contents {
        val c = runCatching { json.decodeFromString(Contents.serializer(), String(plain, Charsets.UTF_8)) }.getOrElse { fail(Reason.Invalid, "its contents can't be read") }
        if (c.format > CONTENTS || c.setup.version > VERSION) fail(Reason.Newer)
        return c.copy(setup = valid(c.setup.migrated()))
    }

    private val ID = Regex("[A-Za-z0-9._-]{1,64}")
    private val PKG = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    private fun bad(what: String): Nothing = fail(Reason.Invalid, what)
    private fun need(ok: Boolean, what: String) { if (!ok) bad(what) }

    /** A name as the importers leave it: no control or direction characters, not padded. */
    private fun plainName(n: String) = n.isNotBlank() && n.length <= 256 && Manual.cleanName(n, 256) == n

    /**
     * [s] if every part of it is as unbraid itself would save it: own servers pass the same checks as an import, every
     * reference names something that exists, profiles follow their rules, and the engine can build it. Throws [Failed].
     */
    fun valid(s: Setup): Setup {
        need(s.routes.size <= 500 && s.servers.size <= 500 && s.favorites.size <= 2000 && s.profiles.size <= MAX_PROFILES, "it holds more than unbraid keeps")
        need(s.routes.all { ID.matches(it.id) && it.id != DIRECT } && s.routes.map { it.id }.toSet().size == s.routes.size, "a route has a missing or repeated name")
        need(s.servers.all { ID.matches(it.id) } && s.servers.map { it.id }.toSet().size == s.servers.size, "an own server has a missing or repeated name")
        s.servers.forEach { sv ->
            need(listOfNotNull(sv.wg, sv.ovpn, sv.ss).size == 1, "an own server has no configuration")
            if (sv.keyless) keyless(sv.name, sv.wg, sv.ovpn, sv.ss) else server(sv.name, sv.wg, sv.ovpn, sv.ss)
        }
        s.routes.forEach { r ->
            val n = listOfNotNull(r.wg, r.ovpn, r.ss, r.tsExit).size
            need(n <= 1, "a route has more than one exit")
            if (n == 0) need(Providers.get(r.loc.provider) != null, "a route uses a provider this unbraid doesn't know")
            r.tsExit?.let { need(it.length <= 253 && it.none(Char::isISOControl), "a Tailscale route is damaged") }
            // A route saved without keys gets them back only through its own server, which must be saved without them too.
            val own = s.servers.firstOrNull { it.id == r.own }
            need(r.keyless == (own?.keyless == true) && (!r.keyless || (n == 1 && r.tsExit == null)), "a route and its own server don't match")
            if (r.keyless) keyless(r.name, r.wg, r.ovpn, r.ss) else server(r.name, r.wg, r.ovpn, r.ss)
        }
        s.home.wg?.let { server(it.name, it, null, null) }
        val ok = { id: String -> id == DIRECT || s.route(id) != null }
        val pkgs = { m: Map<String, String> -> m.keys.all { it.length <= 255 && PKG.matches(it) } }
        need(s.defaultRoute.let(ok) && s.apps.values.all(ok) && s.added.all(ok) && pkgs(s.apps), "an app or the default route names a route that isn't there")
        s.profiles.forEach { p ->
            need(s.copy(profiles = s.profiles - p).profileName(p.name) == p.name, "a profile has a name unbraid can't use")
            need((p.defaultRoute.isEmpty() || ok(p.defaultRoute)) && p.apps.values.all(ok) && p.added.all(ok) && pkgs(p.apps), "profile ${p.name} names a route that isn't there")
        }
        need(s.profile == null || s.profiles.any { it.name == s.profile && it.ready }, "the profile in use is missing")
        need(s.prefs.filter.id == null || s.prefs.filter.server() != null, "the Protection filter is damaged")
        need(s.opts.size <= 64 && s.opts.values.all { o -> o.size <= 64 && o.all { (k, v) -> k.length <= 64 && v.length <= 256 } }, "a provider page setting is damaged")
        // Built as the engine would get it with no provider signed in: own servers get their tunnels, the rest is blocked.
        // Every ready profile too: one that can't run would only fail once the user switches to it.
        for ((name, v) in listOf<Pair<String?, Setup>>(null to s) + s.profiles.filter { it.ready && it.name != s.profile }.map { it.name to s.copy(defaultRoute = it.defaultRoute, apps = it.apps, added = it.added) }) runCatching {
            val p = app.unbraid.vpn.LinkPicker.pick(v, emptyMap(), null, emptyMap(), emptyMap(), emptyMap(), offline = true) { Providers.get(it) }
            app.unbraid.vpn.Compiler.compile(app.unbraid.vpn.Compiler.Input(v, p.links, "app.unbraid", false, "", "", blocked = p.blocked.keys))
        }.onFailure { bad("${name?.let { "profile $it" } ?: "its setup"} can't run (${it.message ?: "unknown reason"})") }
        // A route copied from an own server that is gone keeps its config, no longer tied to it.
        return s.copy(routes = s.routes.map { r -> if (r.own != null && s.servers.none { it.id == r.own }) r.copy(own = null) else r })
    }

    /** A server saved without keys holds nothing secret, and the rest of it is as an import would leave it. */
    private fun keyless(name: String, wg: WgPeer?, ovpn: OvpnProfile?, ss: SsProfile?) {
        val ok = plainName(name) && when {
            wg != null -> wg.privateKey.isEmpty() && wg.presharedKey == null && wg.endpoint.length <= 300 && wg.publicKey.length <= 64 &&
                wg.address.size <= 16 && wg.allowedIps.size <= 256 && wg.dns.size <= 16 && (wg.address + wg.allowedIps + wg.dns).all { it.length <= 64 && it.none(Char::isISOControl) }
            ovpn != null -> ovpn == OvpnProfile(ovpn.name, "")
            ss != null -> ss.password.isEmpty() && ss.pluginOpts == null && ss.server.length <= 253 && ss.method.length <= 64 && ss.port in 1..65535 &&
                (ss.plugin?.length ?: 0) <= 64 && (ss.server + ss.method + ss.plugin.orEmpty()).none(Char::isISOControl)
            else -> false
        }
        need(ok, "own server ${name.take(40)} is damaged")
    }

    /** Checks an own server's config as an import would; its reason names the server, never a key or a password. */
    private fun server(name: String, wg: WgPeer?, ovpn: OvpnProfile?, ss: SsProfile?) {
        val why = runCatching {
            wg?.let { require(plainName(it.name) && WgConf.parse(WgConf.text(it), it.name) == it) { "its WireGuard settings are damaged" } }
            ovpn?.let { require(plainName(it.name) && it.config.length <= 256 * 1024 && it.username.length <= 1024 && it.password.length <= 1024) { "it is damaged" }; Manual.parseOvpn(it.config, it.name, it.username, it.password) }
            ss?.let { require(plainName(it.name) && Manual.parseSs(Manual.ssLink(it), it.name) == it) { "its Shadowsocks settings are damaged" } }
        }.exceptionOrNull() ?: return
        bad("own server ${name.take(40)}: ${(why.message ?: "it is damaged").replaceFirstChar(Char::lowercase).removeSuffix(".")}")
    }
}

/** Own server [id], restored without keys, with [server]'s config and name, and every route through it unblocked. */
fun Setup.refilled(id: String, server: OwnServer): Setup? {
    if (servers.none { it.id == id && it.keyless }) return null
    val sv = server.copy(id = id, keyless = false)
    return copy(servers = servers.map { if (it.id == id) sv else it },
        routes = routes.map { r -> if (r.own != id) r else r.copy(wg = sv.wg, ovpn = sv.ovpn, ss = sv.ss, keyless = false) })
}

/** Apps restored but not on this phone keep their route this long, to be installed meanwhile. */
const val WAIT_MS = 30 * 86_400_000L

/**
 * This phone's setup replaced by backup [b] ([Backup.valid]). Tailscale and the statistics consent stay as they are
 * here. Apps in it wait for [WAIT_MS] to be installed. With [keepAs] (and something set up here), the setup in use is
 * kept as a profile of that name (or that name and a number), with its routes and own servers: a route or server
 * whose name the backup uses for something else gets a new one. Null when there is no room for that profile.
 */
fun Setup.restoredFrom(backup: Setup, keepAs: String?, now: Long, keys: Boolean = true): Setup? {
    // Saved without keys: an own server this phone still has gets its config back here, and the home peer stays as it is.
    val full = backup.servers.filter { it.keyless }.mapNotNull { k -> servers.firstOrNull { !it.keyless && it.id == k.id && k.sameAs(it) }?.let { k.id to it } }.toMap()
    val b = if (full.isEmpty()) backup else backup.copy(
        servers = backup.servers.map { full[it.id] ?: it },
        routes = backup.routes.map { x -> full[x.own]?.takeIf { x.keyless }?.let { x.copy(wg = it.wg, ovpn = it.ovpn, ss = it.ss, keyless = false) } ?: x },
    )
    val pkgs = b.apps.keys + b.profiles.flatMap { it.apps.keys }
    var r = b.copy(home = home.copy(wg = if (keys) b.home.wg else home.wg), prefs = b.prefs.copy(stats = prefs.stats), pristine = false, version = VERSION,
        waiting = pkgs, waitingUntil = if (pkgs.isEmpty()) 0 else now + WAIT_MS, testing = emptySet())
    if (keepAs == null || untouched) return r
    if (r.profiles.size >= MAX_PROFILES) return null
    val name = (listOf(keepAs) + (2..MAX_PROFILES + 1).map { "$keepAs $it" }).firstNotNullOfOrNull { r.profileName(it) } ?: return null
    val fresh = { prefix: String, taken: Set<String> -> generateSequence { prefix + java.util.UUID.randomUUID().toString().take(8) }.first { it !in taken } }
    val ok = { id: String -> id == DIRECT || route(id) != null }
    val apps = apps.filterValues(ok)
    // Without profiles every route here shows, those no app uses too: all of them are kept, as saving a first profile does.
    val listed = if (profiles.isEmpty()) (added + (routes.map { it.id } - (setOf(defaultRoute) + apps.values + added))).distinct() else added
    val used = (setOf(defaultRoute) + apps.values + listed).mapNotNull(::route)
    val serverIds = r.servers.map { it.id }.toMutableSet()
    val sv = HashMap<String, String>()
    val addServers = servers.filter { s -> used.any { it.own == s.id } }.mapNotNull { s ->
        val there = r.servers.firstOrNull { it.id == s.id }
        val id = if (there == null) s.id else if (there == s) { sv[s.id] = s.id; return@mapNotNull null } else fresh("s", serverIds)
        sv[s.id] = id; serverIds += id; s.copy(id = id)
    }
    val routeIds = r.routes.map { it.id }.toMutableSet()
    val rt = HashMap<String, String>()
    val addRoutes = used.mapNotNull { x ->
        val c = x.copy(own = x.own?.let { sv[it] ?: it })
        val there = r.route(x.id)
        val id = if (there == null) x.id else if (there == c) { rt[x.id] = x.id; return@mapNotNull null } else fresh("r", routeIds)
        rt[x.id] = id; routeIds += id; c.copy(id = id)
    }
    val m = { id: String -> rt[id] ?: id }
    // The first profile: every route shown until now stays listed, as when the first profile is saved ([Setup.saveAs]).
    if (r.profiles.isEmpty()) r = r.copy(added = (r.added + (r.routes.map { it.id } - (setOf(r.defaultRoute) + r.apps.values + r.added))).distinct())
    val kept = Profile(name, if (ok(defaultRoute) && defaultRoute.isNotEmpty()) m(defaultRoute) else "", apps.mapValues { m(it.value) }, listed.filter(ok).map(m))
    return r.copy(routes = r.routes + addRoutes, servers = r.servers + addServers, profiles = r.profiles + kept)
}
