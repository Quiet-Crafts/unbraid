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
 * Servers: AirVPN's own status API `GET airvpn.org/api/status/` (no login; the gluetun updater reads it): servers that
 * take connections (`ok`, or `warning` with a named problem; not `error`); `ip_v4_in1` is the WireGuard entry and `ip_v4_in3` the alternative one, hostname `<cc>.vpn.airdns.org`.
 * Fallback: gluetun's catalogue, for addresses only: the key is always the account's own, never the catalogue's.
 * WireGuard answers at entries 1 and 3 on ports 1637 and 47107; entry 3 also takes 51820, which always goes there
 * ([portEntry]); entries 2 and 4 have no WireGuard. DNS 10.128.0.1 (the in-tunnel DNS in AirVPN's configs; a pasted
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
        /** Why a server isn't `ok`, such as "Low packet loss". */
        val warning: String = "",
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
    internal class UserInfo(val user: User? = null, val sessions: List<kotlinx.serialization.json.JsonElement>? = null, val error: String? = null)

    private const val API = "https://airvpn.org/api/"
    private const val DEVICES = "https://airvpn.org/devices/"
    private const val KEYS = "https://airvpn.org/apisettings/"
    private const val KEY_REFUSED = "AirVPN didn't accept that API key. Check it in the Client Area under API"
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
    override val ports = listOf(1637, 47107, 51820)
    /** AirVPN takes WireGuard on 51820 at entry 3 only (the page's Alternative entry). */
    override val portEntry = mapOf(51820 to 2)

    /** Entry 1, then entry 3 ([parse] keeps both only when the status API lists both). */
    override fun addresses(e: Catalog.Entry) = e.ips.takeIf { it.size == 2 }.orEmpty()

    override val defaults = mapOf(PORT_OPT to AUTO, ENTRY_OPT to "1", ROTATE_OPT to "off")
    override fun options(account: String) = listOfNotNull(
        Opt(PORT_OPT, "Port", "Automatic tries each in turn when a network blocks one", listOf(AUTO to "Automatic") +
            ports.map { "$it" to "UDP $it" + if (it in portEntry) " (Alternative entry)" else "" }, advanced = true),
        Opt(ENTRY_OPT, "Entry address", "Alternative reaches each server on another address, for networks that block the standard one", listOf("1" to "Standard", "2" to "Alternative"), advanced = true),
        // Only the device $APP made: renewing one of the user's own would cut off their other apps.
        Opt(ROTATE_OPT, "New key", "How often AirVPN gives $APP's device a new key and tunnel address, when $APP connects", ROTATE_CHOICES, advanced = true)
            .takeIf { runCatching { account(account).let { it.ownDevice && it.apiKey != null && it.deviceId != null } }.getOrDefault(false) },
    )

    /**
     * OpenVPN from AirVPN's generator, made for this device when a route connects: UDP 443 on the standard entry
     * (tls-auth), TCP 443 on the alternative one (tls-crypt, which hides even the OpenVPN start). Needs the API key.
     */
    override val protocols = listOf<Triple<String?, String, String>>(
        Triple(null, "WireGuard", "Fastest"),
        Triple("openvpn_udp", "OpenVPN UDP", "Wider compatibility. Needs the API key sign-in"),
        Triple("openvpn_tcp", "OpenVPN TCP", "For networks that block VPNs. Needs the API key sign-in"),
        Triple(AUTO_PROTOCOL, "Automatic", "WireGuard, then OpenVPN TCP when a network blocks it. TCP needs the API key sign-in"),
    )
    /** Generator protocol per route protocol: entry number (1 standard, 2 alternative in [WgLink.entries] order), transport, wrapper. */
    private class Ovpn(val generator: String, val entry: Int, val transport: String, val wrap: String)
    private val OPENVPN = mapOf("openvpn_udp" to Ovpn("openvpn_1_udp_443", 1, "udp", "tls-auth"), "openvpn_tcp" to Ovpn("openvpn_3_tcp_443", 2, "tcp", "tls-crypt"))
    /** Lines that say where or how OpenVPN connects (with or without the command-line dashes). */
    private val WHERE = Regex("(?m)^\\s*(?:--)?(remote|proto|port|rport)(?:[ \\t]+(.*))?[ \\t]*$")
    private const val NO_KEY = "AirVPN's OpenVPN needs a sign-in with an API key. Sign in again with one, or choose WireGuard for this route"

    /** A WireGuard route as [KeyProvider]; an OpenVPN one takes the same server, with a profile the generator makes for this device. */
    override fun connect(loc: Location, account: String): Link {
        // Automatic is resolved per route before connecting ([AutoProtocol.of]); anything else asking gets WireGuard.
        if (loc.protocol == AUTO_PROTOCOL) return connect(loc.copy(protocol = null), account)
        val o = loc.protocol?.let { OPENVPN[it] ?: throw app.unbraid.data.Unsupported("AirVPN doesn't offer that protocol. Choose another in the route") } ?: return super.connect(loc, account)
        val a = account(account)
        val key = a.apiKey ?: throw app.unbraid.data.Unsupported(NO_KEY)
        a.device ?: throw app.unbraid.data.Unsupported(NO_KEY)
        // The device by its id (or name and key): a renamed one gets its new name, and the generator never falls back to Default.
        val device = devices(key).firstOrNull { (a.deviceId?.let { id -> it.id == id } ?: (it.name == a.device)) && it.key == a.deviceKey }
            ?: throw AuthException("$APP's AirVPN device is gone or was renewed. Sign in to AirVPN again", DEVICES)
        val wg = super.connect(loc, account) as WgLink
        val sv = wg.server
        val at = if (o.entry == 1) wg.entries.getOrNull(0) ?: sv.ip else wg.entries.getOrNull(o.entry - 1)
            ?: throw app.unbraid.vpn.Fail("AirVPN's server list can't be read right now, and OpenVPN TCP needs it to check ${sv.name}. Try again later")
        val text = api("generator", key, listOf(
            "protocols" to o.generator, "servers" to sv.name, "device" to device.name, "system" to "linux",
            "resolve" to "on", "iplayer_entry" to "ipv4", "iplayer_exit" to "ipv4",
        ))
        if (text.trimStart().startsWith("{")) { failed(text); throw app.unbraid.vpn.Fail("AirVPN didn't make an OpenVPN profile for ${sv.name}. Try again later") }
        if (!ovpnFits(text, at, o)) throw app.unbraid.vpn.Fail("AirVPN's OpenVPN profile doesn't match ${sv.name}. Try again")
        return OvpnLink(sv, Manual.parseOvpn(text, sv.name, "", ""))
    }

    /** Every place the profile connects to is [at], port 443, over [o]'s transport, with its wrapper; nothing else can be dialled. */
    private fun ovpnFits(text: String, at: String, o: Ovpn): Boolean {
        if (Regex("(?i)<connection>").containsMatchIn(text) || "<${o.wrap}>" !in text) return false
        val lines = WHERE.findAll(text).map { it.groupValues[1] to it.groupValues[2].trim().split(Regex("\\s+")).filter(String::isNotEmpty) }.toList()
        val remotes = lines.filter { it.first == "remote" }
        // Port and transport must be stated: left out, OpenVPN would fall back to UDP 1194.
        val port = remotes.all { it.second.getOrNull(1) != null } || lines.any { it.first == "port" || it.first == "rport" }
        val transport = remotes.all { it.second.getOrNull(2) != null } || lines.any { it.first == "proto" }
        return remotes.isNotEmpty() && port && transport && lines.all { (k, args) ->
            when (k) {
                "remote" -> args.getOrNull(0) == at && args.getOrNull(1).let { it == null || it == "443" } && args.getOrNull(2).let { it == null || it.startsWith(o.transport) }
                "proto" -> args.getOrNull(0)?.startsWith(o.transport) == true
                else -> args.getOrNull(0) == "443"
            }
        }
    }

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

    /** Connections the account has open now, every app's ([UserInfo.sessions]); unknown without an API key, or when AirVPN refuses or fails. */
    override fun inUse(account: String): Int? {
        val key = runCatching { account(account).apiKey }.getOrNull() ?: return null
        return runCatching { userInfo(key) }.getOrNull()?.takeIf { it.error == null }?.sessions?.size
    }

    override fun plan(account: String): String? {
        val key = runCatching { account(account).apiKey }.getOrNull() ?: return null
        val u = userInfo(key).takeIf { it.error == null }?.user ?: return null
        return Plans.line(u.expires.takeIf { u.premium && it > 0 })
    }

    private fun userInfo(key: String) = Http.parsed("AirVPN") { NordApi.json.decodeFromString(UserInfo.serializer(), api("userinfo", key, emptyList())) }

    /** With an API key: the device was deleted, or renewed (a new key), in the Client Area; not while $APP renews it. */
    override fun revoked(account: String): Boolean = try {
        val a = account(account)
        val key = a.apiKey
        key != null && devices(key).let { l -> if (a.renewing > 0) l.none { it.id == a.deviceId && it.status != "deleted" } else l.none { (a.deviceId?.let { id -> it.id == id } ?: (it.name == a.device)) && it.key == a.deviceKey } }
    } catch (e: java.io.IOException) { false }

    /**
     * A new key for $APP's own device (`devices?action=renew`, which AirVPN queues, dropping the old key at once). The
     * time is saved just before asking, so a renewal cut short is finished, never asked for twice: a later check only
     * looks for the new key and fetches its config like at sign-in. One that shows no new key within [LOST] was lost or
     * refused, and the old key, still listed, stays.
     */
    override fun rotate(account: String, days: Int?, now: Long, finishOnly: Boolean, save: (String) -> Boolean): Boolean {
        var a = account(account)
        val key = a.apiKey ?: return false
        val id = a.deviceId?.takeIf { a.ownDevice } ?: return false
        if (a.renewing == 0L && (finishOnly || days == null || now - a.keyAt < days * 86_400_000L)) return false
        fun mine() = devices(key).firstOrNull { it.id == id && it.status != "deleted" } ?: throw AuthException("$APP's AirVPN device is gone. Sign in again", DEVICES)
        if (a.renewing == 0L) {
            if (mine().key != a.deviceKey) return false // renewed elsewhere: [revoked] tells the user
            a = a.copy(renewing = now); if (!save(encode(a))) return false
            val r = reply(api("devices", key, listOf("action" to "renew", "id" to id)))
            // A refusal may answer a resent copy of a request that went through: kept, and settled by what the list shows.
            if (r.error != null) throw app.unbraid.vpn.Fail("AirVPN didn't renew the key. It is checked again")
        }
        // Health rounds look once; a start waits a little for AirVPN's queue.
        val tries = if (finishOnly) 1 else 11 // a start looks for 20 s
        for (i in 1..tries) {
            val d = mine()
            if (d.status == "ready" && !d.key.isNullOrBlank() && d.key != a.deviceKey) {
                return save(NordApi.json.encodeToString(Account.serializer(), account(signed(key, d, own = true)).copy(keyAt = now)))
            }
            if (d.status == "ready" && d.key == a.deviceKey && now - a.renewing > LOST) { save(encode(a.copy(renewing = 0))); return false }
            if (i < tries) pause(2_000)
        }
        throw app.unbraid.vpn.Fail("AirVPN is still making the new key. It is tried again")
    }

    private const val LOST = 3_600_000L

    private fun encode(a: Account) = NordApi.json.encodeToString(Account.serializer(), a)

    /** Removes the device $APP made; one of the user's own stays. A device already gone counts as removed. */
    override fun signOut(account: String): Boolean = try {
        val a = account(account)
        if (!a.ownDevice || a.apiKey == null || a.deviceId == null) true
        else remove(a.apiKey, a.deviceId)
    } catch (e: java.io.IOException) { false }

    /** Only a device the app made is removed; a pasted config doesn't say which device it uses. */
    override fun otherDevice(old: String, current: String): Boolean {
        val o = account(old); val id = account(current).deviceId
        return o.ownDevice && o.deviceId != null && id != null && id != o.deviceId
    }

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
        val all = Http.signingIn(KEY_REFUSED, KEYS) { devices(key) }
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
        return NordApi.json.encodeToString(Account.serializer(), a.copy(apiKey = key, device = d.name, deviceKey = d.key, deviceId = d.id.ifEmpty { null }, ownDevice = own, keyAt = System.currentTimeMillis()))
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
        if (error.contains("authoriz", true)) throw AuthException(KEY_REFUSED, KEYS)
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
            // `error` refuses connections; `warning` works, with a problem it names: kept, picked after the healthy ones.
            .filter { (it.health == "ok" || it.health == "warning") && Addr.server(it.ip) && it.country.isNotBlank() && it.code.isNotBlank() }
            .map {
                val city = it.location.replace(", ", " ").replace(",", "").takeIf(String::isNotBlank)
                Catalog.Entry("wireguard", it.country, it.continent, city, it.name, "${it.code}.vpn.airdns.org", serverKey, listOfNotNull(it.ip, it.ip3.takeIf(Addr::server)),
                    load = it.load?.coerceIn(0, 100) ?: -1, warning = it.warning.trim().ifEmpty { "Warning" }.takeIf { _ -> it.health == "warning" })
            }
}
