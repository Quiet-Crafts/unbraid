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
    data class Account(val accountId: String, val sessionToken: String, val privateKey: String, val ipAddress: String)

    @Serializable
    private class WgReply(@SerialName("ip_address") val ipAddress: String? = null)

    @Serializable
    private class SessionReply(val status: Int? = null, val token: String? = null, val wireguard: WgReply? = null)

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
    internal data class ServerList(val wireguard: List<Gateway> = emptyList())

    private const val API = "https://api.ivpn.net"
    private const val PORT = 2049
    private const val DNS = "172.16.0.1"

    override val id = "ivpn"
    /** Every host in IVPN's list carries its load, and the app picks the lowest. */
    override val hasLoad = true
    override val name = "IVPN"
    override val fields = listOf(Field("account", "Account ID", hint = "i-XXXX-XXXX-XXXX"))
    override val help = "Your account ID (i-XXXX-XXXX-XXXX, or ivpn plus 8 characters on older accounts): in the IVPN client area, or on any device already logged in. $APP uses one of your device sessions."

    override fun signIn(input: Map<String, String>): String {
        val acct = input["account"].orEmpty().filterNot(Char::isWhitespace)
        require(acct.matches(Regex("i-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}|ivpn[A-Za-z0-9]{7,8}"))) { "Account ID must look like i-XXXX-XXXX-XXXX or ivpnXXXXXXXX" }
        val keys = WgKeys.generate()
        return session(postJsonRaw("$API/v4/session/new", """{"username":"$acct","force":false,"wg_public_key":"${keys.public}"}"""), acct, keys.private)
    }

    override fun signOut(account: String): Boolean = try {
        postJsonRaw("$API/v4/session/delete", buildJsonObject { put("session_token", account(account).sessionToken) }.toString()).first in 200..299
    } catch (e: IOException) {
        false
    }

    internal fun session(reply: Pair<Int, String>, account: String, privateKey: String): String {
        val (code, body) = reply
        val o = Http.parsed("IVPN") { NordApi.json.decodeFromString(SessionReply.serializer(), body) }
        when (val status = o.status ?: code) {
            200 -> Unit
            401 -> throw AuthException("IVPN doesn't recognise that account ID")
            602 -> throw AuthException("This IVPN account has reached its device limit. Remove a device in the IVPN client area (or log one out) and sign in again.")
            702 -> throw AuthException("This IVPN account isn't active. Renew it first.")
            70001, 70002 -> throw AuthException("IVPN is asking for a captcha. Log in once on ivpn.net or in the IVPN app, then try again.")
            70011, 70012 -> throw AuthException("This IVPN account uses two-factor authentication, which $APP can't complete yet.")
            else -> throw IOException("IVPN error $status")
        }
        val ip = o.wireguard?.ipAddress ?: throw IOException("IVPN didn't assign a WireGuard address")
        val token = o.token ?: throw IOException("IVPN didn't return a session")
        return NordApi.json.encodeToString(Account.serializer(), Account(account, token, privateKey, ip.substringBefore('/')))
    }

    fun account(blob: String) = Http.parsed("IVPN") { NordApi.json.decodeFromString(Account.serializer(), blob) }

    override fun countries(account: String) = countries(fetch())

    override fun connect(loc: Location, account: String): Link = link(if (loc.pinned == null) fetch() else null, loc, account(account))

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
        val (sv, dns) = if (pinned != null) pinned to DNS else {
            val (g, h) = l!!.match(loc).minByOrNull { it.second.load } ?: throw IOException("No IVPN server for ${loc.label}")
            server(g, h) to h.localIp.substringBefore('/')
        }
        return WgLink(
            sv,
            WgPeer(
                name = sv.name, privateKey = a.privateKey, address = listOf("${a.ipAddress.substringBefore('/')}/32"),
                publicKey = sv.publicKey, endpoint = "${sv.ip}:$PORT", allowedIps = listOf("0.0.0.0/0"), dns = listOf(dns),
            ),
        )
    }

    private fun server(g: Gateway, h: Host) = Server(h.hostname, h.hostname, h.host, h.publicKey, h.load.roundToInt(), g.city)
}
