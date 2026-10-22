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
 * and the client's DefaultDNS. `ip_address` is taken as a bare IPv4 (a mask is stripped if present). Accounts with 2FA,
 * or ones IVPN answers with a captcha, cannot sign in here.
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
    )

    @Serializable
    private class WgReply(@SerialName("ip_address") val ipAddress: String? = null)

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
        val load: Double = 0.0,
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
    internal data class Ports(val wireguard: List<Port> = emptyList())

    @Serializable
    internal data class Blocklist(@SerialName("Description") val name: String = "", @SerialName("Normal") val normal: String = "", @SerialName("Hardcore") val hardcore: String = "")

    @Serializable
    internal data class AntiTracker(@SerialName("DnsServers") val lists: List<Blocklist> = emptyList())

    @Serializable
    internal data class Config(val ports: Ports = Ports(), @SerialName("antitracker_plus") val antitracker: AntiTracker = AntiTracker())

    @Serializable
    internal data class ServerList(val wireguard: List<Gateway> = emptyList(), val config: Config = Config()) {
        /** The single UDP ports IVPN's WireGuard servers take (its list also has ranges, left out here). */
        val ports get() = config.ports.wireguard.filter { it.type.equals("UDP", true) }.mapNotNull { it.port }.distinct()
    }

    private const val API = "https://api.ivpn.net"
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
    override val defaults = mapOf(PROTECT to "off", LIST to OISD, HARDCORE to "off", PORT_OPT to AUTO)

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
        Opt(PORT_OPT, "Port", "Automatic tries 2049 first, then other ports when a network blocks it",
            listOf(AUTO to "Automatic") + l.ports.sorted().map { "$it" to "UDP $it" }, advanced = true),
    )
    override val fields = listOf(Field("account", "Account ID", hint = "i-XXXX-XXXX-XXXX", qr = true))
    override val help = "Your account ID (i-XXXX-XXXX-XXXX, or ivpn plus 8 characters on older accounts): in the IVPN client area, or on any device already logged in. " +
        "Signed up with an email? Log in at ivpn.net with it: the account ID is at the top of the client area, with a QR code you can scan. $APP uses one of your devices."

    override fun signIn(input: Map<String, String>): String {
        val acct = input["account"].orEmpty().filterNot(Char::isWhitespace)
        require(acct.matches(Regex("i-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}|ivpn[A-Za-z0-9]{7,8}"))) { "Account ID must look like i-XXXX-XXXX-XXXX or ivpnXXXXXXXX" }
        val keys = WgKeys.generate()
        val body = buildJsonObject {
            put("username", acct); put("force", false); put("wg_public_key", keys.public)
            // The answers to an earlier reply's two-factor or captcha question.
            for (k in listOf(CODE, CAPTCHA, CAPTCHA_ID)) input[k]?.filterNot(Char::isWhitespace)?.takeIf { it.isNotEmpty() }?.let { put(k, it) }
        }
        return session(postJsonRaw("$API/v4/session/new", body.toString()), acct, keys.private)
    }

    override fun signOut(account: String): Boolean = try {
        postJsonRaw("$API/v4/session/delete", buildJsonObject { put("session_token", account(account).sessionToken) }.toString()).first in 200..299
    } catch (e: IOException) {
        false
    }

    /** IVPN's session status answers 601 (session not found) once the session was logged out elsewhere. */
    override fun revoked(account: String): Boolean = try {
        sessionGone(postJsonRaw("$API/v4/session/status", buildJsonObject { put("session_token", account(account).sessionToken) }.toString()))
    } catch (e: IOException) { false }

    internal fun sessionGone(reply: Pair<Int, String>) =
        (runCatching { NordApi.json.decodeFromString(SessionReply.serializer(), reply.second).status }.getOrNull() ?: reply.first).let { it == 601 || it == 401 }

    private const val CODE = "confirmation"
    private const val CAPTCHA = "captcha"
    private const val CAPTCHA_ID = "captcha_id"
    private val codeField = Field(CODE, "Two-factor code", secret = false, hint = "6 digits")
    private val captchaField = Field(CAPTCHA, "Characters in the picture", secret = false)

    internal fun session(reply: Pair<Int, String>, account: String, privateKey: String): String {
        val (code, body) = reply
        val o = Http.parsed("IVPN") { NordApi.json.decodeFromString(SessionReply.serializer(), body) }
        when (val status = o.status ?: code) {
            200 -> Unit
            401 -> throw AuthException("IVPN doesn't recognise that account ID")
            602 -> o.data?.deviceLimit?.takeIf { it > 0 }.let { n ->
                throw AuthException(Limits.full(name, n, "device"), CLIENT_AREA)
            }
            702 -> throw AuthException("This IVPN account isn't active. Renew it first.")
            70001, 70002 -> {
                val id = o.captchaId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) } ?: throw AuthException("IVPN is asking for a captcha. Log in once on ivpn.net or in the IVPN app, then try again.")
                val png = runCatching { java.util.Base64.getMimeDecoder().decode(o.captchaImage.orEmpty().substringAfter("base64,")) }.getOrNull()
                throw Challenge(if (status == 70001) "IVPN wants to check you're a person. Type the characters in the picture." else "That didn't match. Try this picture.", captchaField, png, mapOf(CAPTCHA_ID to id))
            }
            70011 -> throw Challenge("Two-factor authentication is on for this account. Enter the code from your authenticator app.", codeField)
            70012 -> throw Challenge("That code didn't work. Enter the current one.", codeField)
            else -> throw Http.Status(status, "IVPN error $status")
        }
        val ip = o.wireguard?.ipAddress ?: throw app.unbraid.vpn.Fail("IVPN didn't assign a WireGuard address. Try again later")
        val token = o.token ?: throw app.unbraid.vpn.Fail("IVPN didn't return a session. Try again later")
        return NordApi.json.encodeToString(Account.serializer(), Account(account, token, privateKey, ip.substringBefore('/'), o.device.orEmpty().take(64), o.service?.deviceLimit ?: 0))
    }

    fun account(blob: String) = Http.parsed("IVPN") { NordApi.json.decodeFromString(Account.serializer(), blob) }

    override fun countries(account: String) = countries(fetch())

    // A pinned server is looked up in the current list (its address and key may have changed); the saved one only offline.
    override fun connect(loc: Location, account: String): Link = link(if (loc.pinned == null) fetch() else runCatching { fetch() }.getOrNull(), loc, account(account))

    override fun servers(loc: Location, account: String, limit: Int): List<Server> =
        fetch().match(loc).sortedBy { it.second.load }.take(limit).map { (g, h) -> server(g, h) }

    private fun fetch() = parse(Http.get("$API/v5/servers.json"))

    internal fun parse(body: String) = Http.parsed("IVPN") { NordApi.json.decodeFromString(ServerList.serializer(), body) }

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
        val (sv, dns) = if (l == null) pinned!! to DNS else {
            val (g, h) = if (pinned != null) l.wireguard.flatMap { g -> g.hosts.map { g to it } }.firstOrNull { it.second.hostname == pinned.host } ?: throw NoLongerOffered(pinned)
                else l.match(loc).minByOrNull { it.second.load } ?: throw IOException("No IVPN server for ${loc.label}")
            server(g, h) to h.localIp.substringBefore('/')
        }
        val ports = l?.ports?.let { listed -> FALLBACK.filter { it in listed } }.orEmpty()
        return WgLink(
            sv,
            WgPeer(
                name = sv.name, privateKey = a.privateKey, address = listOf("${a.ipAddress.substringBefore('/')}/32"),
                publicKey = sv.publicKey, endpoint = "${sv.ip}:$PORT", allowedIps = listOf("0.0.0.0/0"), dns = listOf(dns),
            ),
            ports,
        )
    }

    private fun server(g: Gateway, h: Host) = Server(h.hostname, h.hostname, h.host, h.publicKey, h.load.roundToInt(), g.city)
}
