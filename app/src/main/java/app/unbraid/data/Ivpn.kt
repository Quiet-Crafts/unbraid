package app.unbraid.data

import app.unbraid.APP
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.math.roundToInt

/**
 * IVPN over WireGuard. Account blob: account ID, session token, WireGuard private key and the tunnel address IVPN assigned.
 *
 * Sign-in follows the official client (github.com/ivpn/desktop-app, daemon/api): `POST https://api.ivpn.net/v4/session/new`
 * with `{username, force, wg_public_key}`. The reply carries its own `status` (200, 401 bad ID, 602 session limit,
 * 702 inactive account, 70001 captcha, 70011 2FA), `token` and `wireguard.ip_address`. One session and one key serve every host.
 * Server list: `GET /v5/servers.json`. Port 2049 and DNS = the host's in-tunnel `local_ip` (172.16.0.1) as in IVPN's WireGuard FAQ
 * and the client's DefaultDNS. `ip_address` is taken as a bare IPv4 (a mask is stripped if present). A 2FA code or a
 * captcha IVPN asks for is answered in the sign-in screen.
 *
 * Quantum-safe: every sign-in also sends a Round-3 Kyber-1024 public key (`kem_public_key1`, with IVPN's own
 * `kem_library_version`); the reply's `wireguard.kem_cipher1` decapsulates to a secret whose SHA-256 is the WireGuard
 * preshared key, as in IVPN's own apps. A reply without it ends the sign-in (the new session is signed out again):
 * the server then refuses the key without the preshared key anyway, and unbraid never falls back to a plain tunnel.
 *
 * Sign-out frees the session: `POST /v4/session/delete {"session_token"}`. Limitation: IVPN has no API to list sessions,
 * so a `session/new` whose reply is lost in transit leaves an orphaned session (one of the account's device slots) that
 * only the user can remove in the IVPN client area; it cannot be reconciled here.
 */
object Ivpn : Provider {
    @Serializable
    data class Account(
        val accountId: String, val sessionToken: String, val privateKey: String, val ipAddress: String,
        /** The name IVPN gave this session, as its client area lists it, and the plan's device limit (0 = unknown). */
        val device: String = "", val limit: Int = 0,
        /** The quantum-safe preshared key; null for accounts signed in before it existed. */
        val psk: String? = null,
        /** When the key was registered (ms since 1970); 0 = before this was kept. */
        val keyAt: Long = 0,
        /** A new key saved before IVPN is told about it, so a lost reply is retried with the same key. */
        val pending: Pending? = null,
    )

    @Serializable
    data class Pending(val privateKey: String, val publicKey: String, val kemPublic: String, val kemPrivate: String)

    @Serializable
    private class SetReply(val status: Int? = null, @SerialName("ip_address") val ipAddress: String? = null, @SerialName("kem_cipher1") val kemCipher: String? = null)

    @Serializable
    private class WgReply(@SerialName("ip_address") val ipAddress: String? = null, @SerialName("kem_cipher1") val kemCipher: String? = null)

    /** Round-3 Kyber-1024 (not ML-KEM, which IVPN's servers don't accept), from the engine. */
    internal interface Kyber {
        /** A new key pair: public and private key, base64. */
        fun keyPair(): Pair<String, String>
        /** base64(SHA-256(secret)) of [cipher] for [private]; throws on a malformed key or ciphertext. */
        fun psk(private: String, cipher: String): String
    }

    internal var kyber: Kyber = object : Kyber {
        override fun keyPair() = io.nekohasekai.libbox.Libbox.kyber1024KeyPair().split('|').let { it[0] to it[1] }
        override fun psk(private: String, cipher: String): String = io.nekohasekai.libbox.Libbox.kyber1024PSK(private, cipher)
    }
    private const val KEM_VERSION = "0.10.0" // what IVPN's Android app sends
    /** A session IVPN opened without a usable quantum-safe key; [token] lets the sign-in close it again. */
    internal class NoPq(val token: String) : app.unbraid.vpn.Fail(NO_PQ)
    internal const val NO_PQ = "IVPN didn't send a quantum-safe key, so $APP didn't finish signing in. Try again later"

    @Serializable
    internal class Plan(val name: String = "", @SerialName("device_limit") val deviceLimit: Int = 0)

    @Serializable
    internal class Status(
        @SerialName("current_plan") val plan: String = "", @SerialName("available_plans") val plans: List<Plan> = emptyList(),
        val limit: Int = 0,
    ) {
        /** The device limit: the 602 reply's own, else the current plan's ("IVPN Standard" is plan "Standard"). */
        val deviceLimit get() = limit.takeIf { it > 0 } ?: plans.filter { it.name.isNotBlank() && plan.contains(it.name, true) }.maxByOrNull { it.name.length }?.deviceLimit ?: 0
    }

    @Serializable
    private class SessionReply(
        val status: Int? = null, val token: String? = null, val wireguard: WgReply? = null,
        @SerialName("device_name") val device: String? = null, @SerialName("service_status") val service: Status? = null, val data: Status? = null,
        @SerialName("captcha_id") val captchaId: String? = null, @SerialName("captcha_image") val captchaImage: String? = null,
    )

    @Serializable
    internal data class Host(
        val hostname: String,
        val host: String,
        @SerialName("public_key") val publicKey: String,
        @SerialName("local_ip") val localIp: String,
        /** The host's V2Ray address, for the disguise; empty when it has none. */
        val v2ray: String = "",
        /** The port this host takes multi-hop tunnels on, from IVPN's other hosts. */
        @SerialName("multihop_port") val multihopPort: Int = 0,
        val load: Double = 0.0,
        /** The hosting company, shown in the server list. */
        val isp: String = "",
    )

    @Serializable
    internal data class Gateway(
        val gateway: String,
        @SerialName("country_code") val countryCode: String,
        val country: String,
        val city: String,
        val hosts: List<Host> = emptyList(),
    )

    @Serializable
    internal data class Port(val type: String = "", val port: Int? = null)

    @Serializable
    internal data class Ports(val wireguard: List<Port> = emptyList(), val v2ray: V2ray = V2ray())

    /** IVPN's V2Ray: the account-wide VMess user id, and the WireGuard port its servers forward to. */
    @Serializable
    internal data class V2ray(val id: String = "", val wireguard: List<Port> = emptyList())

    @Serializable
    internal data class Blocklist(@SerialName("Description") val name: String = "", @SerialName("Normal") val normal: String = "", @SerialName("Hardcore") val hardcore: String = "")

    @Serializable
    internal data class AntiTracker(@SerialName("DnsServers") val lists: List<Blocklist> = emptyList())

    @Serializable
    internal data class Config(val ports: Ports = Ports(), @SerialName("antitracker_plus") val antitracker: AntiTracker = AntiTracker())

    @Serializable
    internal data class ServerList(
        val wireguard: List<Gateway> = emptyList(), val config: Config = Config(),
        /** Hostnames IVPN's status list marks inactive or in maintenance ([down]); picked only when nothing else is left. */
        @kotlinx.serialization.Transient val down: Set<String> = emptySet(),
    ) {
        /** The single UDP ports IVPN's WireGuard servers take (its list also has ranges, left out here). */
        val ports get() = config.ports.wireguard.filter { it.type.equals("UDP", true) }.mapNotNull { it.port }.distinct()
        /** Hosts best first: up before down, then by load. */
        val best get() = compareBy<Pair<Gateway, Host>>({ it.second.hostname in down }, { it.second.load })
    }

    @Serializable
    internal class StatHost(val hostname: String = "")

    @Serializable
    internal class StatHosts(val wireguard: StatHost? = null)

    @Serializable
    internal class StatServer(@SerialName("is_active") val active: Boolean = true, @SerialName("in_maintenance") val maintenance: Boolean = false, val hosts: StatHosts = StatHosts())

    @Serializable
    internal class Stats(val servers: List<StatServer> = emptyList())

    private const val API = "https://api.ivpn.net"
    /** Besides its listed ports, IVPN's WireGuard servers answer on any UDP port in these ranges (IVPN's port guide). */
    internal val PORT_RANGES = listOf(5500..19999, 30000..65000)
    private const val PORT = 2049
    /** Automatic port: the default first, then the ones restrictive networks most often let through. Only those IVPN lists. */
    private val FALLBACK = listOf(PORT, 443, 53, 80, 123, 2050, 1194, 500, 4500)
    private const val DNS = "172.16.0.1"

    override val id = "ivpn"
    /** Every host in IVPN's list carries its load, and the app picks the lowest. */
    override val hasLoad = true
    override val name = "IVPN"
    override val protection = Opt(PROTECT, "AntiTracker", "Ads and trackers")
    // AntiTracker's block list until one is picked: OISD Big, the one IVPN's plain AntiTracker uses.
    private const val OISD = "10.0.254.2|10.0.254.3"
    private const val LIST = "list"
    private const val HARDCORE = "hardcore"
    private const val CLIENT_AREA = "https://www.ivpn.net/account/"
    // On multi-hop AntiTracker answers at each list's usual address; the list's "multihop-ip" doesn't answer there.
    override val nativeHop = true

    override val defaults = mapOf(PROTECT to "off", LIST to OISD, HARDCORE to "off", PORT_OPT to AUTO, ROTATE_OPT to "7", DISGUISE_OPT to "off")

    /** One session serves every IVPN route, so this phone is always one device. */
    override fun device(account: String): Device {
        val a = account(account)
        // The status check names the session and plan as they are now; offline, as at sign-in.
        val now = runCatching { NordApi.json.decodeFromString(SessionReply.serializer(), postJsonRaw("$API/v4/session/status", buildJsonObject { put("session_token", a.sessionToken) }.toString()).second) }
            .getOrNull()?.takeIf { it.status == 200 }
        val name = now?.device ?: a.device
        return Device("${if (name.isNotBlank()) "Named $name. " else ""}See all your devices in the IVPN client area", CLIENT_AREA)
    }
    /** The plan's device limit as read at sign-in; unbraid always holds one session. */
    /** A cached tunnel stands in only if it was made for this sign-in: its key and quantum-safe key both match. */
    override fun trusts(link: Link, account: String): Boolean =
        link !is WgLink || runCatching { account(account).let { link.peer.privateKey == it.privateKey && link.peer.presharedKey == it.psk } }.getOrDefault(false)

    /**
     * A new WireGuard and quantum-safe key on the same session (`POST /v4/session/wg/set`, as IVPN's apps do with no
     * tunnel up). The new key is saved before IVPN hears of it and retried as is after a lost reply. IVPN answering
     * with an error leaves the old key in place; a reply without a usable quantum-safe key can't be used, so the
     * page then asks for a new sign-in.
     */
    override fun rotate(account: String, days: Int?, now: Long, finishOnly: Boolean, save: (String) -> Boolean): Boolean {
        var a = account(account)
        if (!due(a, days, now) || (finishOnly && a.pending == null)) return false
        val p = a.pending ?: WgKeys.generate().let { k -> kyber.keyPair().let { (pub, priv) -> Pending(k.private, k.public, pub, priv) } }
        if (a.pending == null) { a = a.copy(pending = p); if (!save(encode(a))) return false }
        val reply = postJsonRaw("$API/v4/session/wg/set", buildJsonObject {
            put("session_token", a.sessionToken); put("public_key", p.publicKey); put("connected_public_key", "")
            put("kem_public_key1", p.kemPublic); put("kem_library_version", KEM_VERSION)
        }.toString())
        val next = try { rekeyed(reply, a, now) } catch (e: Rejected) { save(encode(a.copy(pending = null))); throw e }
        return save(encode(next))
    }

    /** An unfinished change, a missing quantum-safe key, or a key [days] old (null = never by age). */
    internal fun due(a: Account, days: Int?, now: Long) = a.pending != null || a.psk == null || (days != null && now - a.keyAt >= days * 86_400_000L)

    private val BUSY = setOf(408, 429)
    private val REFUSED = (400..499).filterNot { it in BUSY || it == 401 }.toSet()
    private val again get() = app.unbraid.vpn.Fail("IVPN didn't answer the key change. It is tried again")

    /** IVPN refused the new key: the old one still works. */
    internal class Rejected(message: String) : app.unbraid.vpn.Fail(message)

    internal fun rekeyed(reply: Pair<Int, String>, a: Account, now: Long): Account {
        val p = requireNotNull(a.pending)
        if (reply.first == 401) throw AuthException("IVPN signed this phone out. Sign in again")
        val o = Http.parsed("IVPN") { NordApi.json.decodeFromString(SetReply.serializer(), reply.second) }
        when (o.status ?: reply.first) {
            200 -> Unit
            401, 601 -> throw AuthException("IVPN signed this phone out. Sign in again")
            // A plain refusal of the request: the old key stays. Anything else (busy, server error) may hide an earlier
            // change that went through, so the new key is kept and tried again.
            in REFUSED -> if (reply.first < 500 && reply.first !in BUSY) throw Rejected("IVPN didn't take the new key; the current one stays") else throw again
            else -> throw again
        }
        val psk = o.kemCipher?.let { c -> runCatching { kyber.psk(p.kemPrivate, c) }.getOrNull()?.takeIf(::isKey) }
            ?: throw app.unbraid.vpn.Fail("IVPN didn't send a quantum-safe key for the new key. Sign in to IVPN again")
        val ip = o.ipAddress?.let { Addr.assigned(it) ?: throw Addr.unexpected("IVPN") } ?: a.ipAddress
        return a.copy(privateKey = p.privateKey, psk = psk, ipAddress = ip, keyAt = now, pending = null)
    }

    private fun isKey(k: String) = runCatching { java.util.Base64.getDecoder().decode(k).size == 32 }.getOrDefault(false)
    private fun encode(a: Account) = NordApi.json.encodeToString(Account.serializer(), a)

    override fun quantumSafe(account: String) = runCatching { account(account).psk != null }.getOrDefault(false)

    override fun limit(account: String) = Limit("device", perTunnel = false, runCatching { account(account).limit }.getOrDefault(0).takeIf { it > 0 })
    /** A block list is saved as its two resolvers, "normal|hardcore", so a route needs no server list to use it. */
    override fun blockingDns(opts: (String) -> String?) =
        (opts(LIST) ?: OISD).split('|').getOrNull(if (opts(HARDCORE) == "on") 1 else 0)?.takeIf { app.unbraid.vpn.Compiler.ipv4(it)?.second == 32 && '/' !in it } ?: OISD.substringBefore('|')
    /** A new key reaches every IVPN server within about a minute of the sign-in. */
    override val keySetup = 90_000L

    override fun options(account: String) = options(fetch())

    internal fun options(l: ServerList) = listOfNotNull(
        Opt(LIST, "Block list", "What AntiTracker blocks, from IVPN's own lists", l.config.antitracker.lists.map { "${it.normal}|${it.hardcore}" to it.name })
            .takeIf { it.choices.isNotEmpty() },
        Opt(HARDCORE, "Hardcore mode", "Also blocks Google and Facebook, and sites that need them", advanced = true),
        Opt(ROTATE_OPT, "New key", "How often $APP gives this phone a new WireGuard and quantum-safe key, when it connects",
            ROTATE_CHOICES, advanced = true),
        Opt(DISGUISE_OPT, "Disguise", "V2Ray wraps the tunnel so it looks like ordinary web traffic, for networks that block VPNs. A little slower",
            listOf("off" to "Off", "v2ray" to "V2Ray"), advanced = true).takeIf { l.config.ports.v2ray.id.isNotBlank() },
        Opt(PORT_OPT, "Port", "Automatic tries 2049 first, then other ports when a network blocks it",
            listOf(AUTO to "Automatic") + l.ports.sorted().map { "$it" to "UDP $it" }, advanced = true, custom = PORT_RANGES),
    )
    override val fields = listOf(Field("account", "Account ID", hint = "i-XXXX-XXXX-XXXX", qr = true))
    override val help = "Your account ID (i-XXXX-XXXX-XXXX, or ivpn plus 8 characters on older accounts): in the IVPN client area, or on any device already logged in. " +
        "Signed up with an email? Log in at ivpn.net with it: the account ID is at the top of the client area, with a QR code you can scan. $APP uses one of your devices."

    override fun signIn(input: Map<String, String>): String {
        val acct = input["account"].orEmpty().filterNot(Char::isWhitespace)
        require(acct.matches(Regex("i-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}|ivpn[A-Za-z0-9]{7,8}"))) { "Account ID must look like i-XXXX-XXXX-XXXX or ivpnXXXXXXXX" }
        val keys = WgKeys.generate()
        val (kemPublic, kemPrivate) = kyber.keyPair()
        return try {
            session(postJsonRaw("$API/v4/session/new", sessionBody(acct, keys.public, kemPublic, input).toString()), acct, keys.private, kemPrivate, forced(acct, input))
        } catch (e: NoPq) {
            // Free the slot this sign-in just took: without the key this session is of no use.
            runCatching { postJsonRaw("$API/v4/session/delete", buildJsonObject { put("session_token", e.token) }.toString()) }
            throw e
        }
    }

    /** Forced only for the very account the user agreed for: the agreement carries its ID, so a changed field never inherits it. */
    private fun forced(acct: String, input: Map<String, String>) = input[FORCE]?.let { it.isNotEmpty() && it == acct } == true

    /** The sign-in request. `force` only after the user agreed: IVPN then signs out every other device on the account ([SlotsFull]). */
    internal fun sessionBody(acct: String, publicKey: String, kemPublic: String, input: Map<String, String>) = buildJsonObject {
        put("username", acct); put("force", forced(acct, input)); put("wg_public_key", publicKey)
        put("kem_public_key1", kemPublic); put("kem_library_version", KEM_VERSION)
        // The answers to an earlier reply's two-factor or captcha question.
        for (k in listOf(CODE, CAPTCHA, CAPTCHA_ID)) input[k]?.filterNot(Char::isWhitespace)?.takeIf { it.isNotEmpty() }?.let { put(k, it) }
    }

    override fun signOut(account: String): Boolean = try {
        postJsonRaw("$API/v4/session/delete", buildJsonObject { put("session_token", account(account).sessionToken) }.toString()).first in 200..299
    } catch (e: IOException) {
        false
    }

    override fun otherDevice(old: String, current: String) =
        account(old).sessionToken.let { it.isNotBlank() && it != account(current).sessionToken }

    /** IVPN's session status answers 601 (session not found) once the session was logged out elsewhere. */
    override fun revoked(account: String): Boolean = try {
        sessionGone(postJsonRaw("$API/v4/session/status", buildJsonObject { put("session_token", account(account).sessionToken) }.toString()))
    } catch (e: IOException) { false }

    internal fun sessionGone(reply: Pair<Int, String>) =
        (runCatching { NordApi.json.decodeFromString(SessionReply.serializer(), reply.second).status }.getOrNull() ?: reply.first).let { it == 601 || it == 401 }

    private const val FORCE = "force"
    private const val CODE = "confirmation"
    private const val CAPTCHA = "captcha"
    private const val CAPTCHA_ID = "captcha_id"
    private val codeField = Field(CODE, "Two-factor code", secret = false, hint = "6 digits")
    private val captchaField = Field(CAPTCHA, "Characters in the picture", secret = false)

    internal fun session(reply: Pair<Int, String>, account: String, privateKey: String, kemPrivate: String, force: Boolean = false): String {
        val (code, body) = reply
        val o = Http.parsed("IVPN") { NordApi.json.decodeFromString(SessionReply.serializer(), body) }
        // A forced sign-in that IVPN still asks a question (two-factor, captcha) stays forced when it is answered.
        val kept = if (force) mapOf(FORCE to account) else emptyMap()
        when (val status = o.status ?: code) {
            200 -> Unit
            401 -> throw AuthException("IVPN doesn't recognise that account ID")
            602 -> o.data?.deviceLimit?.takeIf { it > 0 }.let { n ->
                // IVPN's own apps offer the same: "force login to logout of all other devices".
                if (force) throw AuthException(Limits.full(name, n, "device"), CLIENT_AREA)
                throw app.unbraid.data.SlotsFull(
                    "All ${n?.let { "$it " }.orEmpty()}IVPN devices are in use.", CLIENT_AREA,
                    "IVPN signs out every other device on this account, including the IVPN app on your computer or other phones. They stay off until someone signs in on them again. Then this phone signs in and takes a free slot.",
                    mapOf(FORCE to account),
                )
            }
            702 -> throw AuthException("This IVPN account isn't active. Renew it first.")
            70001, 70002 -> {
                val id = o.captchaId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) } ?: throw AuthException("IVPN is asking for a captcha. Log in once on ivpn.net or in the IVPN app, then try again.")
                val png = runCatching { java.util.Base64.getMimeDecoder().decode(o.captchaImage.orEmpty().substringAfter("base64,")) }.getOrNull()
                throw Challenge(if (status == 70001) "IVPN wants to check you're a person. Type the characters in the picture." else "That didn't match. Try this picture.", captchaField, png, mapOf(CAPTCHA_ID to id) + kept)
            }
            70011 -> throw Challenge("Two-factor authentication is on for this account. Enter the code from your authenticator app.", codeField, keep = kept)
            70012 -> throw Challenge("That code didn't work. Enter the current one.", codeField, keep = kept)
            else -> throw Http.Status(status, "IVPN error $status")
        }
        val ip = o.wireguard?.ipAddress ?: throw app.unbraid.vpn.Fail("IVPN didn't assign a WireGuard address. Try again later")
        Addr.assigned(ip) ?: throw Addr.unexpected("IVPN")
        val token = o.token ?: throw app.unbraid.vpn.Fail("IVPN didn't return a session. Try again later")
        val psk = o.wireguard?.kemCipher?.let { c -> runCatching { kyber.psk(kemPrivate, c) }.getOrNull()?.takeIf(::isKey) }
            ?: throw NoPq(token)
        return NordApi.json.encodeToString(Account.serializer(), Account(account, token, privateKey, ip.substringBefore('/'), o.device.orEmpty().take(64), o.service?.deviceLimit ?: 0, psk, System.currentTimeMillis()))
    }

    fun account(blob: String) = Http.parsed("IVPN") { NordApi.json.decodeFromString(Account.serializer(), blob) }

    override fun countries(account: String) = countries(fetch())

    // A pinned server is looked up in the current list (its address and key may have changed); the saved one only offline.
    override fun connect(loc: Location, account: String): Link = link(if (loc.pinned == null) fetch(true) else runCatching { fetch() }.getOrNull(), loc, account(account))

    override fun servers(loc: Location, account: String, limit: Int): List<Server> =
        listed(fetch(true), loc, limit)

    /** [loc]'s servers best first, each with its note: out of service (inactive or in maintenance), and the hosting company. */
    internal fun listed(l: ServerList, loc: Location, limit: Int) = l.match(loc).sortedWith(l.best).take(limit).map { (g, h) -> server(g, h).apply {
            val down = h.hostname in l.down
            note = listOfNotNull("Out of service".takeIf { down }, h.isp.trim().take(40).ifEmpty { null }).joinToString(" · "); flagged = down
        } }

    /** The server list; with [status], also which hosts are down, from IVPN's status list (left out if that fails). */
    private fun fetch(status: Boolean = false) = parse(Http.get("$API/v5/servers.json"))
        .let { l -> if (!status) l else l.copy(down = runCatching { down(Http.get("$API/v5/servers/stats")) }.getOrDefault(emptySet())) }

    /** WireGuard hostnames IVPN's status list marks inactive or in maintenance. */
    internal fun down(body: String): Set<String> = Http.parsed("IVPN") { NordApi.json.decodeFromString(Stats.serializer(), body) }.servers
        .filter { !it.active || it.maintenance }.mapNotNullTo(HashSet()) { it.hosts.wireguard?.hostname?.takeIf(String::isNotBlank) }

    /** The list, without hosts at an address no server can have. */
    internal fun parse(body: String) = Http.parsed("IVPN") { NordApi.json.decodeFromString(ServerList.serializer(), body) }
        .let { l -> l.copy(wireguard = l.wireguard.map { g -> g.copy(hosts = g.hosts.filter { Addr.server(it.host) }) }) }

    internal fun countries(l: ServerList): List<Country> =
        l.wireguard.filter { it.hosts.isNotEmpty() }.groupBy { it.countryCode }.values.sortedBy { it.first().country }.map { gs ->
            val cities = gs.sortedBy { it.city }.map { g -> City(stableId(g.gateway), g.city, g.gateway) }
            Country(stableId(gs.first().countryCode), gs.first().country, gs.first().countryCode.uppercase(), gs.sumOf { it.hosts.size }, cities)
        }

    /** Hosts for [loc]: a city (gateway) if [Location.region] is set, else its country, else everything. */
    internal fun ServerList.match(loc: Location) = wireguard.filter { g ->
        when {
            loc.region != null -> g.gateway == loc.region
            loc.countryCode != null -> g.countryCode.equals(loc.countryCode, true)
            loc.country != null -> g.country == loc.country
            else -> true
        }
    }.flatMap { g -> g.hosts.map { g to it } }

    internal fun link(l: ServerList?, loc: Location, a: Account): WgLink {
        val pinned = loc.pinned
        var host: Host? = null
        var hop: Int? = null
        val (sv, dns) = if (l == null) pinned!! to DNS else {
            val (g, h) = if (pinned != null) l.wireguard.flatMap { g -> g.hosts.map { g to it } }.firstOrNull { it.second.hostname == pinned.host } ?: throw NoLongerOffered(pinned)
                else l.match(loc).minWithOrNull(l.best) ?: throw IOException("No IVPN server for ${loc.label}")
            host = h
            hop = h.multihopPort.takeIf { it in 1..65535 }
            server(g, h) to h.localIp.substringBefore('/')
        }
        Addr.usable(sv)
        val ports = l?.ports?.let { listed -> FALLBACK.filter { it in listed } }.orEmpty()
        return WgLink(
            sv,
            WgPeer(
                name = sv.name, privateKey = a.privateKey, address = listOf("${a.ipAddress.substringBefore('/')}/32"),
                publicKey = sv.publicKey, presharedKey = a.psk, endpoint = "${sv.ip}:$PORT", allowedIps = listOf("0.0.0.0/0"), dns = listOf(dns),
            ),
            ports,
            disguise = disguise(l, host),
            hopPort = hop,
        )
    }

    /** How [h] takes a disguised tunnel: its V2Ray address on TCP 80 with the list's user id, to the list's WireGuard port. */
    internal fun disguise(l: ServerList?, h: Host?): Disguise? {
        val v = l?.config?.ports?.v2ray ?: return null
        val port = v.wireguard.firstNotNullOfOrNull { it.port?.takeIf { p -> p in 1..65535 } } ?: return null
        val ip = h?.v2ray?.trim()?.takeIf { Addr.server(it) } ?: return null
        return Disguise(Vmess(ip, 80, v.id.trim().takeIf { UUID.matches(it) } ?: return null), port)
    }

    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    private fun server(g: Gateway, h: Host) = Server(h.hostname, h.hostname, h.host, h.publicKey, h.load.roundToInt(), g.city, g.countryCode)
}
