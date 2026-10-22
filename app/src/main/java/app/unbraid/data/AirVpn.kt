package app.unbraid.data

import app.unbraid.APP
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AirVPN over WireGuard. AirVPN issues the key per *device* (Client Area → Devices), together with a device-specific
 * tunnel address and a preshared key; one device's key, address and preshared key serve every server, and every
 * server shares one [Peer] key (kept in the account and used for every server).
 *
 * Sign-in takes either an API key or a whole generated .conf (bare keys are refused). With an API key (Client Area → API,
 * sent as the `API-KEY` header) Unbraid makes a device of its own, like other providers' device slots:
 * `GET airvpn.org/api/devices/?action=add` (any name is ignored, it starts as "New device"), `action=modify` names it,
 * `list` shows it `ready` with its key and addresses after about 20 s; sign-out removes it (`action=delete`). Only when
 * AirVPN refuses a new device (all 10 in use, say) does the user pick one of theirs. Then
 * `GET /api/generator/?protocols=wireguard_1_udp_1637&servers=<name>&device=<name>` returns that device's .conf. The
 * generator falls back to the Default device for an unknown name, so the name is always one from the list and the
 * config's address must be the device's own. API errors come back as HTTP 200 with `{"error": …}`.
 * Servers: AirVPN's own status API `GET airvpn.org/api/status/` (no login; the gluetun updater reads it): healthy
 * servers' `ip_v4_in1` is the WireGuard entry and `ip_v4_in3` the alternative one, hostname `<cc>.vpn.airdns.org`.
 * Fallback: gluetun's catalogue, for addresses only: the key is always the account's own, never the catalogue's.
 * WireGuard answers at entries 1 and 3 on ports 1637 and 47107; entry 3 also takes 51820, left out so every port works
 * at either entry (entries 2 and 4 have no WireGuard). DNS 10.128.0.1 (the in-tunnel DNS in AirVPN's configs; a pasted
 * .conf overrides it and the port). Every plan allows 5 connections at once, each to a different server: two tunnels to
 * the *same* server need two devices' keys.
 */
object AirVpn : KeyProvider() {
    @Serializable
    internal class Node(
        @SerialName("public_name") val name: String = "",
        @SerialName("country_name") val country: String = "",
        @SerialName("country_code") val code: String = "",
        val location: String = "",
        val continent: String = "",
        @SerialName("ip_v4_in1") val ip: String = "",
        @SerialName("ip_v4_in3") val ip3: String = "",
        val health: String = "",
        @SerialName("currentload") val load: Int? = null,
    )

    @Serializable
    internal class Status(val servers: List<Node> = emptyList())

    @Serializable
    internal class DeviceInfo(
        val id: String = "", val name: String = "", val status: String = "",
        /** Null while AirVPN is still making the device. */
        @SerialName("wireguard_public_key") val key: String? = null, @SerialName("wireguard_ipv4") val ipv4: String? = null,
    )

    /** The `devices` reply: the list, or the [id] of a device just added, or an [error]. */
    @Serializable
    internal class Devices(val devices: List<DeviceInfo>? = null, val id: String? = null, val error: String? = null)

    @Serializable
    internal class User(val premium: Boolean = false, @SerialName("expiration_unix") val expires: Long = 0)

    @Serializable
    internal class UserInfo(val user: User? = null)

    private const val API = "https://airvpn.org/api/"
    private const val DEVICES = "https://airvpn.org/devices/"
    private const val KEYS = "https://airvpn.org/apisettings/"
    private const val DNS_LISTS = "https://airvpn.org/dns/"
    private const val DEVICE = "device"
    private val API_KEY = Regex("[0-9a-fA-F]{64}")
    private val deviceField = Field(DEVICE, "Device name", secret = false, hint = "As listed in the Client Area")

    override val id = "airvpn"
    override val name = "AirVPN"
    override val fields = listOf(Field("key", "API key or WireGuard .conf", hint = "The API key, or the whole generated file", file = true))
    override val help = "Easiest: in the AirVPN Client Area open API (airvpn.org/apisettings), add an API key and paste it here. $APP then adds a device of its own to your account, " +
        "which takes about half a minute, and removes it when you sign out. Or open Config Generator, choose WireGuard, a device and any server, then generate the .conf and paste the whole file."

    /** `currentload` of the status API is the server's load in percent. */
    override val hasLoad = true

    override val defaultAddress: List<String>? = null
    override val defaultDns = listOf("10.128.0.1")
    override val defaultPort = 1637
    override val presharedRequired = true
    override val ports = listOf(1637, 47107)

    /** Entry 1, then entry 3 ([parse] keeps both only when the status API lists both). */
    override fun addresses(e: Catalog.Entry) = e.ips.takeIf { it.size == 2 }.orEmpty()

    override val defaults = mapOf(PORT_OPT to AUTO, ENTRY_OPT to "1")
    override fun options(account: String) = listOf(
        Opt(PORT_OPT, "Port", "Automatic tries one, then the other when a network blocks it", listOf(AUTO to "Automatic") + ports.map { "$it" to "UDP $it" }, advanced = true),
        Opt(ENTRY_OPT, "Entry address", "Alternative reaches each server on another address, for networks that block the standard one", listOf("1" to "Standard", "2" to "Alternative"), advanced = true),
    )

    /** Shown in the Client Area for every plan. */
    override fun limit(account: String) = Limit("connection", perTunnel = true, 5)

    /** Block lists and anti-geolocation are set only in the Client Area and work through AirVPN's own DNS. */
    override val blockingNote = "Set in the AirVPN Client Area. It works through AirVPN's DNS, so not while $APP's filter is on"
    override val blockingUrl = DNS_LISTS
    override val configHelp = "Forwarded ports and dynamic DNS are set in the AirVPN Client Area and apply on their own" to "https://airvpn.org/ports/"

    /** The device an API sign-in uses; a pasted .conf doesn't say which one it is. */
    override fun device(account: String) = runCatching { account(account) }.getOrNull()?.takeIf { it.device != null }?.let {
        Device("${if (it.ownDevice) "Its own device, ${it.device}, removed when you sign out" else "Uses your device ${it.device}"}. See all your devices in the AirVPN Client Area", DEVICES)
    }

    override fun plan(account: String): String? {
        val key = runCatching { account(account).apiKey }.getOrNull() ?: return null
        val u = Http.parsed("AirVPN") { NordApi.json.decodeFromString(UserInfo.serializer(), api("userinfo", key, emptyList())) }.user ?: return null
        return Plans.line(u.expires.takeIf { u.premium && it > 0 })
    }

    /** With an API key: the device was deleted, or renewed (a new key), in the Client Area. */
    override fun revoked(account: String): Boolean = try {
        val a = account(account)
        val key = a.apiKey
        key != null && devices(key).none { (a.deviceId?.let { id -> it.id == id } ?: (it.name == a.device)) && it.key == a.deviceKey }
    } catch (e: java.io.IOException) { false }

    /** Removes the device $APP made; one of the user's own stays. A device already gone counts as removed. */
    override fun signOut(account: String): Boolean = try {
        val a = account(account)
        if (!a.ownDevice || a.apiKey == null || a.deviceId == null) true
        else remove(a.apiKey, a.deviceId)
    } catch (e: java.io.IOException) { false }

    /** AmneziaWG obfuscation keys: Unbraid's engine would drop them silently, so such a config is refused. */
    private val AMNEZIA = Regex("(?im)^\\s*(jc|jmin|jmax|s[1-4]|h[1-4]|i[1-5])\\s*=")

    override fun signIn(input: Map<String, String>): String {
        val text = input["key"].orEmpty().trim()
        if (API_KEY.matches(text)) return fromApi(text, input[DEVICE]?.trim())
        require(!AMNEZIA.containsMatchIn(text)) {
            "This config uses Amnezia obfuscation (Jc, S1, H1 and similar lines), which $APP can't apply. Generate a standard WireGuard config in the AirVPN Config Generator."
        }
        return super.signIn(input)
    }

    /**
     * A device of $APP's own; when AirVPN won't add one, the device [wanted] (the user's answer, null before asking) or the
     * only one. The config comes from the generator, kept with the API key that made it.
     */
    private fun fromApi(key: String, wanted: String?): String {
        val all = devices(key)
        if (wanted == null) own(key, all)?.let { return it }
        val names = all.joinToString(", ") { it.name.take(40) }
        val d = when {
            !wanted.isNullOrEmpty() -> all.firstOrNull { it.name.equals(wanted, true) } ?: throw Challenge("You have no AirVPN device named ${wanted.take(40)}. Yours: $names.", deviceField)
            all.size == 1 -> all.single()
            all.isEmpty() -> throw AuthException("AirVPN didn't add a device for $APP and your account has none. Add one in the Client Area, then try again", DEVICES)
            else -> throw Challenge("AirVPN didn't add a device for $APP, maybe because all yours are in use. Which one should it use? Yours: $names. Or remove one in the Client Area and try again.", deviceField)
        }
        return signed(key, d, own = false)
    }

    /**
     * Adds a device, names it and waits until it is ready; null when AirVPN refuses to add one. Only a device AirVPN's
     * reply names is $APP's: after a lost reply the new one in the list could be another app's, so none is taken over.
     * One that doesn't become ready is removed again ([remove]), or the user is told it is left.
     */
    private fun own(key: String, before: List<DeviceInfo>): String? {
        val id = try {
            val r = reply(api("devices", key, listOf("action" to "add")))
            if (r.error != null) return null
            r.id?.takeIf(String::isNotBlank) ?: throw java.io.IOException("No device id from AirVPN")
        } catch (e: java.io.IOException) {
            if (e is AuthException) throw e
            throw app.unbraid.vpn.Fail("AirVPN didn't answer while adding a device. If a new device appeared in the Client Area, remove it, then try again")
        }
        var name = "New device"
        try {
            val free = generateSequence(1) { it + 1 }.map { if (it == 1) APP else "$APP $it" }.first { n -> before.none { it.name.equals(n, true) } }
            failed(api("devices", key, listOf("action" to "modify", "id" to id, "name" to free, "description" to "Removed when you sign out of $APP")))
            name = free
            for (i in 1..30) {
                pause(2_000)
                devices(key).firstOrNull { it.id == id && it.name == name && it.status == "ready" && !it.key.isNullOrBlank() }?.let { return signed(key, it, own = true) }
            }
            throw app.unbraid.vpn.Fail("AirVPN is still setting up the new device. Try again in a minute")
        } catch (e: Exception) {
            if (runCatching { remove(key, id) }.getOrDefault(false)) throw e
            throw app.unbraid.vpn.Fail("Signing in didn't finish, and $APP couldn't remove the device it added, $name. Remove it in the AirVPN Client Area, then try again")
        }
    }

    /**
     * Removes device [id], once it is no longer being made (AirVPN can undo a delete sent while it still is). True when
     * it is gone or going; false when it couldn't be removed.
     */
    private fun remove(key: String, id: String): Boolean {
        for (i in 1..30) {
            val d = devices(key).firstOrNull { it.id == id } ?: return true
            if (d.status == "deleted") return true
            if (d.status != "create") return reply(api("devices", key, listOf("action" to "delete", "id" to id))).let { it.error == null || it.error.contains("unknown", true) }
            pause(2_000)
        }
        return false
    }

    /** Device [d]'s config from the generator, as an account. */
    private fun signed(key: String, d: DeviceInfo, own: Boolean): String {
        val server = parse(status(), "").minByOrNull { it.load }?.serverName ?: throw java.io.IOException("Empty AirVPN server list")
        val conf = api("generator", key, listOf(
            "protocols" to "wireguard_1_udp_$defaultPort", "servers" to server, "device" to d.name, "system" to "linux",
            "resolve" to "on", "iplayer_entry" to "ipv4", "iplayer_exit" to "ipv4",
        ))
        if (conf.trimStart().startsWith("{")) { failed(conf); throw app.unbraid.vpn.Fail("AirVPN didn't make a config for ${d.name}. Try again later") }
        val a = account(super.signIn(mapOf("key" to conf)))
        // A device AirVPN doesn't know gets the Default device's config: the address must be this device's own.
        if (!d.ipv4.isNullOrEmpty() && a.address.none { it.substringBefore('/') == d.ipv4 }) throw app.unbraid.vpn.Fail("AirVPN sent another device's config. Try again")
        return NordApi.json.encodeToString(Account.serializer(), a.copy(apiKey = key, device = d.name, deviceKey = d.key, deviceId = d.id.ifEmpty { null }, ownDevice = own))
    }

    internal fun devices(key: String): List<DeviceInfo> {
        val body = api("devices", key, emptyList())
        failed(body)
        return reply(body).devices.orEmpty().filter { it.name.isNotBlank() }
    }

    /** A `devices` reply; a refused key throws, any other error is left in [Devices.error]. */
    private fun reply(body: String) = Http.parsed("AirVPN") { NordApi.json.decodeFromString(Devices.serializer(), body) }
        .also { r -> if (r.error?.contains("authoriz", true) == true) failed(body) }

    /** An API reply that is an error: a refused key needs a new one; anything else is a passing failure. */
    private fun failed(body: String) {
        val error = runCatching { NordApi.json.decodeFromString(Devices.serializer(), body).error }.getOrNull() ?: return
        if (error.contains("authoriz", true)) throw AuthException("AirVPN didn't accept that API key. Check it in the Client Area under API", KEYS)
        throw app.unbraid.vpn.Fail("AirVPN couldn't answer right now. Try again later")
    }

    override fun entries(a: Account): List<Catalog.Entry> = try {
        parse(status(), a.publicKey.orEmpty()).ifEmpty { throw java.io.IOException("Empty AirVPN server list") }
    } catch (e: java.io.IOException) {
        val key = a.publicKey ?: throw e
        // Addresses only, and just the first: the catalogue doesn't say which of a server's addresses is which entry.
        catalogue().map { it.copy(wgpubkey = key, ips = listOfNotNull(it.ipv4)) }
    }

    /** Every AirVPN server shares the account's [Peer] key: no list, pinned server or saved tunnel can change it. */
    override fun peerKey(sv: Server, a: Account) = a.publicKey ?: sv.publicKey

    internal var catalogue: () -> List<Catalog.Entry> = { Catalog.fetch("airvpn", "wireguard") }
    internal var status: () -> String = { Http.get(API + "status/") }
    internal var pause: (Long) -> Unit = Thread::sleep
    internal var api: (service: String, key: String, query: List<Pair<String, String>>) -> String = { service, key, query ->
        Http.get(API + service + "/" + query.joinToString("&", "?") { (k, v) -> Http.enc(k) + "=" + Http.enc(v) }.takeIf { query.isNotEmpty() }.orEmpty(), mapOf("API-KEY" to key))
    }

    internal fun parse(body: String, serverKey: String): List<Catalog.Entry> =
        Http.parsed("AirVPN") { NordApi.json.decodeFromString(Status.serializer(), body) }.servers
            .filter { it.health == "ok" && '.' in it.ip && it.country.isNotBlank() && it.code.isNotBlank() }
            .map {
                val city = it.location.replace(", ", " ").replace(",", "").takeIf(String::isNotBlank)
                Catalog.Entry("wireguard", it.country, it.continent, city, it.name, "${it.code}.vpn.airdns.org", serverKey, listOfNotNull(it.ip, it.ip3.takeIf { '.' in it }),
                    load = it.load?.coerceIn(0, 100) ?: -1)
            }
}
