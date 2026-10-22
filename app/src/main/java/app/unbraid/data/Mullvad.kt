package app.unbraid.data

import app.unbraid.APP
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException
import java.net.URL
import java.security.KeyPairGenerator
import java.security.NoSuchAlgorithmException
import java.security.spec.NamedParameterSpec
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import kotlin.random.Random

/** Curve25519 key pairs in WireGuard's base64 form, for providers that register our own public key. */
internal object WgKeys {
    class Keys(val private: String, val public: String)

    /** Android's Conscrypt registers the "X25519" alias only from Android 15; Android 13 and 14 have just "XDH". */
    private fun generator(): KeyPairGenerator = try {
        KeyPairGenerator.getInstance("X25519")
    } catch (e: NoSuchAlgorithmException) {
        xdh()
    }

    /** Conscrypt's XDH only does X25519 and rejects parameter specs (checked on Android 14); the JDK needs the spec. */
    fun xdh(): KeyPairGenerator = KeyPairGenerator.getInstance("XDH").apply { runCatching { initialize(NamedParameterSpec.X25519) } }

    fun generate(gen: KeyPairGenerator = generator()): Keys {
        val kp = gen.generateKeyPair()
        val priv = raw(kp.private.encoded)
        priv[0] = (priv[0].toInt() and 248).toByte()
        priv[31] = ((priv[31].toInt() and 127) or 64).toByte()
        return Keys(b64(priv), b64(raw(kp.public.encoded)))
    }

    /** PKCS#8 / X.509 wrappers both end with the 32 raw key bytes. */
    private fun raw(encoded: ByteArray) = encoded.copyOfRange(encoded.size - 32, encoded.size)
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
}

/**
 * POSTs JSON and returns status + body without throwing on 4xx: Mullvad and IVPN report why a sign-in failed in the
 * error body, which [Http] discards. The body is streamed with a fixed length, which stops HttpURLConnection from
 * silently replaying a registration whose reply was lost; any [IOException] therefore means no HTTP status was seen.
 */
internal fun postJsonRaw(url: String, body: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
    val bytes = body.toByteArray()
    val c = URL(url).openConnection() as HttpsURLConnection
    try {
        c.requestMethod = "POST"
        c.setFixedLengthStreamingMode(bytes.size)
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.doOutput = true
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Content-Type", "application/json")
        headers.forEach(c::setRequestProperty)
        c.outputStream.use { it.write(bytes) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readNBytes(1 shl 20)) }.orEmpty()
        return code to text
    } finally {
        c.disconnect()
    }
}

/**
 * Mullvad over WireGuard. Account blob: account number, WireGuard private key, assigned tunnel address and device id.
 *
 * Sign-in follows mullvad-api (github.com/mullvad/mullvadvpn-app): `POST auth/v1/token {account_number}` gives an
 * `access_token`; `POST accounts/v1/devices {pubkey, hijack_dns}` with `Authorization: Bearer` returns 201 with the
 * device `id` and `ipv4_address`. One device (one of the account's 5) serves every relay.
 * Server list: `GET /public/relays/wireguard/v2`; port 51820 and DNS 10.64.0.1 as in Mullvad's own mullvad-wg.sh.
 * Sign-out frees the slot: `DELETE accounts/v1/devices/{id}` (204) with a fresh token. A device reply lost in transit
 * would orphan a slot, so a transport failure on the POST is reconciled by listing `GET accounts/v1/devices` and adopting
 * the entry with our public key.
 * INVALID_ACCOUNT / MAX_DEVICES_REACHED are matched on the response body, not the HTTP status.
 */
object Mullvad : Provider {
    @Serializable
    data class Account(val account: String, val privateKey: String, val ipv4Address: String, val deviceId: String)

    @Serializable
    private class TokenReply(@SerialName("access_token") val accessToken: String)

    @Serializable
    internal class DeviceReply(val id: String, val pubkey: String = "", @SerialName("ipv4_address") val ipv4Address: String)

    @Serializable
    internal data class Loc(val country: String, val city: String)

    @Serializable
    internal data class Relay(
        val hostname: String,
        val location: String,
        val active: Boolean = true,
        @SerialName("ipv4_addr_in") val ip: String,
        @SerialName("public_key") val publicKey: String,
        @SerialName("include_in_country") val inCountry: Boolean = true,
        val weight: Int = 0,
    )

    @Serializable
    internal data class Wireguard(val relays: List<Relay> = emptyList())

    @Serializable
    internal data class RelayList(val locations: Map<String, Loc> = emptyMap(), val wireguard: Wireguard = Wireguard()) {
        /** Active relays whose location is known. */
        val usable get() = wireguard.relays.filter { it.active && it.location in locations }
    }

    private const val API = "https://api.mullvad.net"
    private const val DNS = "10.64.0.1"
    private const val PORT = 51820

    override val id = "mullvad"
    override val name = "Mullvad"
    override val protection = Opt(PROTECT, "DNS content blocking", "Ads, trackers and malware")
    override val defaults = mapOf(PROTECT to "off")
    override fun blockingDns(opts: (String) -> String?) = "100.64.0.7"
    /** Five device slots per account; unbraid registers one. */
    override fun limit(account: String) = Limit("device", perTunnel = false, 5)
    override val fields = listOf(Field("account", "Account number", hint = "16 digits"))
    override val help = "Your 16-digit account number: shown when you created the account, and at mullvad.net/account after logging in. Each account has 5 device slots; $APP uses one."

    override fun signIn(input: Map<String, String>): String {
        val n = input["account"].orEmpty().filterNot { it.isWhitespace() || it == '-' }
        require(n.matches(Regex("\\d{16}"))) { "A Mullvad account number has 16 digits" }
        val token = accessToken(n)
        val keys = WgKeys.generate()
        val reply = try {
            postJsonRaw("$API/accounts/v1/devices", """{"pubkey":"${keys.public}","hijack_dns":false}""", bearer(token))
        } catch (e: IOException) {
            return adopted(token, n, keys) ?: throw e
        }
        return device(reply, n, keys.private)
    }

    override fun signOut(account: String): Boolean = try {
        val a = account(account)
        Http.delete("$API/accounts/v1/devices/${Http.enc(a.deviceId)}", bearer(accessToken(a.account)))
        true
    } catch (e: IOException) {
        false
    }

    /** Mullvad answers 404 for a device removed from the account. */
    override fun revoked(account: String): Boolean = try {
        val a = account(account)
        Http.get("$API/accounts/v1/devices/${Http.enc(a.deviceId)}", bearer(accessToken(a.account)))
        false
    } catch (e: Http.Status) { e.code == 404 } catch (e: AuthException) { true } catch (e: IOException) { false }

    private fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")

    private fun accessToken(account: String) = token(postJsonRaw("$API/auth/v1/token", """{"account_number":"$account"}"""))

    /** The device Mullvad registered for [keys] although its reply never arrived, if the account lists one. */
    private fun adopted(token: String, account: String, keys: WgKeys.Keys): String? = try {
        adopt(Http.get("$API/accounts/v1/devices", bearer(token)), account, keys)
    } catch (e: IOException) {
        null
    }

    internal fun adopt(list: String, account: String, keys: WgKeys.Keys): String? =
        Http.parsed("Mullvad") { NordApi.json.decodeFromString(ListSerializer(DeviceReply.serializer()), list) }
            .firstOrNull { it.pubkey == keys.public }?.let { blob(account, keys.private, it) }

    internal fun token(reply: Pair<Int, String>): String {
        val (code, body) = reply
        if (code == 400 && "INVALID_ACCOUNT" in body || code == 401 || code == 403) throw AuthException("Mullvad doesn't recognise that account number")
        if (code !in 200..299) throw Http.Status(code, "Mullvad API $code")
        return Http.parsed("Mullvad") { NordApi.json.decodeFromString(TokenReply.serializer(), body) }.accessToken
    }

    internal fun device(reply: Pair<Int, String>, account: String, privateKey: String): String {
        val (code, body) = reply
        if ("MAX_DEVICES_REACHED" in body) throw AuthException(Limits.full(name, 5, "device"))
        if (code == 401 || code == 403) throw AuthException("Mullvad refused the device registration")
        if (code != 201) throw Http.Status(code, "Mullvad API $code")
        return blob(account, privateKey, Http.parsed("Mullvad") { NordApi.json.decodeFromString(DeviceReply.serializer(), body) })
    }

    private fun blob(account: String, privateKey: String, d: DeviceReply) =
        NordApi.json.encodeToString(Account.serializer(), Account(account, privateKey, d.ipv4Address, d.id))

    fun account(blob: String) = Http.parsed("Mullvad") { NordApi.json.decodeFromString(Account.serializer(), blob) }

    override fun countries(account: String) = countries(fetch())

    /** A pinned server is looked up in the fresh list so a changed IP or key is picked up; if the list is unreachable the pinned values are used. */
    override fun connect(loc: Location, account: String): Link {
        val list = if (loc.pinned == null) fetch() else try { fetch() } catch (e: IOException) { null }
        return link(list, loc, account(account))
    }

    override fun servers(loc: Location, account: String, limit: Int): List<Server> {
        val l = fetch()
        return l.match(loc).sortedWith(compareByDescending<Relay> { it.weight }.thenBy { it.hostname }).take(limit).map { server(it, l) }
    }

    private fun fetch() = parse(Http.get("$API/public/relays/wireguard/v2"))

    internal fun parse(body: String) = Http.parsed("Mullvad") { NordApi.json.decodeFromString(RelayList.serializer(), body) }

    internal fun countries(l: RelayList): List<Country> {
        val groups = l.usable.groupBy { it.location.substringBefore('-') }.toList().sortedBy { (_, rs) -> l.locations.getValue(rs.first().location).country }
        return groups.map { (cc, rs) ->
            val cities = rs.map { it.location }.distinct().map { it to l.locations.getValue(it).city }.sortedBy { it.second }
            Country(stableId(cc), l.locations.getValue(rs.first().location).country, cc.uppercase(), rs.size, cities.map { (code, city) -> City(stableId(code), city, code) })
        }
    }

    /** Relays for [loc]: a city if [Location.region] is set, else its country; `include_in_country = false` relays only by city. */
    internal fun RelayList.match(loc: Location) = usable.filter { r ->
        when {
            loc.region != null -> r.location == loc.region
            loc.countryCode != null -> r.inCountry && r.location.substringBefore('-').equals(loc.countryCode, true)
            loc.country != null -> r.inCountry && locations[r.location]?.country == loc.country
            else -> r.inCountry
        }
    }

    internal fun link(l: RelayList?, loc: Location, a: Account, rnd: Random = Random): WgLink {
        val sv = loc.pinned?.let { p -> if (l == null) p else current(l, p) } ?: run {
            val rs = l!!.match(loc)
            if (rs.isEmpty()) throw IOException("No active Mullvad server for ${loc.label}")
            server(pick(rs, rnd), l)
        }
        return WgLink(
            sv,
            WgPeer(
                name = sv.name, privateKey = a.privateKey, address = listOf(a.ipv4Address.substringBefore('/') + "/32"),
                publicKey = sv.publicKey, endpoint = "${sv.ip}:$PORT", allowedIps = listOf("0.0.0.0/0"), dns = listOf(DNS),
            ),
        )
    }

    /** [p] as the fresh list has it, found by host name, else by IP. */
    private fun current(l: RelayList, p: Server): Server {
        val r = l.usable.firstOrNull { it.hostname == p.host } ?: l.usable.firstOrNull { it.ip == p.ip }
            ?: throw IOException("Mullvad no longer lists ${p.name}")
        return server(r, l)
    }

    /** Mullvad's `weight` is a selection weight (higher = preferred), not a load; the app picks weighted at random. */
    private fun pick(rs: List<Relay>, rnd: Random): Relay {
        val total = rs.sumOf { it.weight.toLong() }
        if (total <= 0) return rs.random(rnd)
        var t = rnd.nextLong(total)
        return rs.first { t -= it.weight; t < 0 }
    }

    private fun server(r: Relay, l: RelayList) = Server(r.hostname, r.hostname, r.ip, r.publicKey, 0, l.locations[r.location]?.city.orEmpty())
}
