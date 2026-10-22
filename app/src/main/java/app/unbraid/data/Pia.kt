package app.unbraid.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/** "US Denver" → "Denver": compiled once, it runs inside sort comparators. */
private val COUNTRY_PREFIX = Regex("^[A-Z]{2} (?=.)")

/**
 * Private Internet Access over WireGuard, following PIA's official scripts
 * (https://github.com/pia-foss/manual-connections): `get_token.sh`, `get_region.sh` and
 * `connect_to_wireguard_with_token.sh`. Account blob: [Account] (login, last token, WireGuard key pair).
 * TLS to a WireGuard server is by IP, pinned to PIA's CA, with the server's `cn` as host name.
 */
object Pia : Provider {
    @Serializable
    data class Account(val username: String, val password: String, val token: String, val tokenTime: Long, val privateKey: String, val publicKey: String)

    @Serializable
    internal data class Wg(val ip: String, val cn: String)

    @Serializable
    internal data class Servers(val wg: List<Wg> = emptyList())

    @Serializable
    internal data class Region(
        val id: String,
        val name: String,
        val country: String,
        val geo: Boolean = false,
        @SerialName("port_forward") val portForward: Boolean = false,
        val offline: Boolean = false,
        val servers: Servers = Servers(),
    ) {
        /** [name] without the country prefix PIA puts on some regions ("DE Berlin" -> "Berlin"). */
        val city get() = name.replace(COUNTRY_PREFIX, "")
    }

    @Serializable
    private class ServerList(val regions: List<Region>)

    internal class AddKey(val serverKey: String, val serverPort: Int, val peerIp: String, val dns: List<String>)

    @Serializable
    private class TokenReply(val token: String? = null)

    @Serializable
    private class AddKeyReply(
        val status: String? = null,
        @SerialName("server_key") val serverKey: String? = null,
        @SerialName("server_port") val serverPort: Int? = null,
        @SerialName("peer_ip") val peerIp: String? = null,
        @SerialName("dns_servers") val dns: List<String> = emptyList(),
    )

    private class Token(val value: String, val at: Long)

    private const val TOKEN_URL = "https://www.privateinternetaccess.com/api/client/v2/token"
    private const val LIST_URL = "https://serverlist.piaservers.net/vpninfo/servers/v6"
    private const val CA_RESOURCE = "/pia_ca_rsa_4096.crt"
    private const val TOKEN_REFRESH_MS = 20 * 3_600_000L
    private const val TRIES = 3

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val tokens = ConcurrentHashMap<String, Token>()

    override val id = "pia"
    override val name = "Private Internet Access"
    override val protection = Opt(PROTECT, "MACE", "Ads, trackers and malware")
    override val defaults = mapOf(PROTECT to "off")
    override fun blockingDns(opts: (String) -> String?) = "10.0.0.244"
    override val fields = listOf(
        Field("username", "Username", secret = false, hint = "p1234567"),
        Field("password", "Password", hint = "PIA password"),
    )

    override val help = "Your PIA login, not your email: a username like p1234567 and its password (PIA website → Account → Client Control Panel)."

    override fun signIn(input: Map<String, String>): String {
        val user = input["username"].orEmpty().trim()
        val pass = input["password"].orEmpty()
        require(user.isNotEmpty() && pass.isNotEmpty()) { "Enter your PIA username and password" }
        val keys = WgKeys.generate()
        val token = fetchToken(user, pass)
        val now = System.currentTimeMillis()
        tokens[user] = Token(token, now)
        return json.encodeToString(Account.serializer(), Account(user, pass, token, now, keys.private, keys.public))
    }

    fun account(blob: String) = Http.parsed("PIA") { json.decodeFromString(Account.serializer(), blob) }

    override fun countries(account: String): List<Country> = countries(regions())

    override fun connect(loc: Location, account: String): Link {
        val a = account(account)
        val targets = loc.pinned?.let { listOf(Wg(it.ip, it.host) to it) }
            ?: pick(regions(), loc).let { r -> r.servers.wg.take(TRIES).map { it to Server(r.name, it.cn, it.ip, "", 0, r.city) } }
        var failure: IOException? = null
        for ((sv, base) in targets) {
            try {
                val k = register(sv, a)
                val server = base.copy(publicKey = k.serverKey)
                return WgLink(server, peer(server, k, a.privateKey))
            } catch (e: AuthException) {
                throw e
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("PIA has no WireGuard server for this location")
    }

    override fun servers(loc: Location, account: String, limit: Int): List<Server> {
        val list = regions()
        val rs = loc.region?.let { id -> list.filter { it.id == id } }
            ?: loc.countryCode?.let { c -> list.filter { it.country.equals(c, true) } }
            ?: list
        return rs.sortedBy { it.geo }.flatMap { r -> r.servers.wg.map { Server(it.cn, it.cn, it.ip, "", 0, r.city) } }.take(limit)
    }

    private fun peer(sv: Server, k: AddKey, privateKey: String) = WgPeer(
        name = sv.name, privateKey = privateKey, address = listOf("${k.peerIp}/32"), publicKey = k.serverKey,
        endpoint = "${sv.ip}:${k.serverPort}", allowedIps = listOf("0.0.0.0/0"), dns = k.dns,
    )

    private fun regions(): List<Region> = parseRegions(Http.get(LIST_URL))

    private fun pick(list: List<Region>, loc: Location): Region {
        loc.region?.let { id -> return list.firstOrNull { it.id == id } ?: throw IOException("PIA region $id is no longer available") }
        val pool = loc.countryCode?.let { c -> list.filter { it.country.equals(c, true) } } ?: list
        return pool.sortedBy { it.geo }.firstOrNull() ?: throw IOException("PIA has no WireGuard server for ${loc.label}")
    }

    /** The server list is one line of JSON, then a blank line and a signature (not checked here, as in PIA's scripts). */
    internal fun parseRegions(body: String): List<Region> =
        Http.parsed("PIA") { json.decodeFromString(ServerList.serializer(), body.substringBefore('\n')).regions }
            .filter { !it.offline && it.servers.wg.isNotEmpty() }

    internal fun countries(regions: List<Region>): List<Country> = regions.groupBy { it.country.uppercase() }.map { (code, rs) ->
        Country(
            id = code.fold(0) { n, c -> n * 256 + c.code },
            name = Locale("", code).getDisplayCountry(Locale.ENGLISH).ifBlank { code },
            code = code,
            serverCount = rs.sumOf { it.servers.wg.size },
            cities = rs.sortedBy { it.city }.map { r -> City(stableId(r.id), r.city, r.id) },
        )
    }.sortedBy { it.name }

    private fun fetchToken(user: String, pass: String): String {
        val body = try {
            Http.postForm(TOKEN_URL, mapOf("username" to user, "password" to pass))
        } catch (e: AuthException) {
            throw AuthException("PIA refused that username or password")
        }
        val token = Http.parsed("PIA") { json.decodeFromString(TokenReply.serializer(), body) }.token
        return token?.takeIf { it.isNotBlank() } ?: throw AuthException("PIA refused that username or password")
    }

    /** A token younger than ~20 h (the blob's or the newest refreshed one), else a new one. PIA tokens last 24 h. */
    private fun token(a: Account, force: Boolean = false): String {
        val known = listOfNotNull(Token(a.token, a.tokenTime), tokens[a.username]).maxBy { it.at }
        if (!force && System.currentTimeMillis() - known.at < TOKEN_REFRESH_MS) return known.value
        return fetchToken(a.username, a.password).also { tokens[a.username] = Token(it, System.currentTimeMillis()) }
    }

    private fun register(sv: Wg, a: Account): AddKey = try {
        addKey(sv, token(a), a.publicKey)
    } catch (e: AuthException) {
        addKey(sv, token(a, force = true), a.publicKey)
    }

    /** `curl --connect-to cn::ip: --cacert ca.rsa.4096.crt https://cn:1337/addKey?pt=..&pubkey=..` */
    private fun addKey(sv: Wg, token: String, pubKey: String): AddKey {
        require(sv.cn.matches(Regex("[A-Za-z0-9._-]+")) && sv.ip.matches(Regex("[0-9.]+"))) { "Unexpected PIA server ${sv.cn}" }
        return parseAddKey(Http.get("https://${sv.ip}:1337/addKey?pt=${Http.enc(token)}&pubkey=${Http.enc(pubKey)}", tls = tls, verifyHost = sv.cn))
    }

    internal fun parseAddKey(body: String): AddKey {
        val r = Http.parsed("PIA") { json.decodeFromString(AddKeyReply.serializer(), body) }
        if (r.status != "OK") throw IOException("PIA refused the key")
        return AddKey(
            r.serverKey ?: throw IOException("PIA's reply has no server_key"),
            r.serverPort ?: throw IOException("PIA's reply has no server_port"),
            r.peerIp ?: throw IOException("PIA's reply has no peer_ip"),
            r.dns,
        )
    }

    /** TLS trust limited to PIA's own CA, as the scripts do with `--cacert`. */
    internal val tls: SSLSocketFactory by lazy {
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(caStore()) }.trustManagers
        SSLContext.getInstance("TLS").apply { init(null, tm, null) }.socketFactory
    }

    internal fun caStore(): KeyStore {
        val cert = Pia::class.java.getResourceAsStream(CA_RESOURCE)?.use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            ?: throw IOException("PIA CA certificate is missing from the app")
        return KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("pia", cert) }
    }
}
