package app.unbraid.data

import kotlinx.serialization.Serializable
import java.io.IOException

/** NordVPN: NordLynx (WireGuard), or OpenVPN UDP/TCP — required for Double VPN and Onion over VPN. */
object Nord : Provider {
    @Serializable
    data class Account(val token: String, val key: String, val user: String = "", val pass: String = "")

    /** Groups only served over OpenVPN. */
    val openVpnOnly = setOf("legacy_double_vpn", "legacy_onion_over_vpn")

    override val id = "nord"
    override val name = "NordVPN"
    override val fields = listOf(Field("token", "Access token", hint = "Paste token"))
    override val help = "Nord Account → NordVPN → Manual setup → Generate new token."

    override fun signIn(input: Map<String, String>): String {
        val t = input["token"].orEmpty().filterNot(Char::isWhitespace)
        require(t.matches(Regex("[A-Za-z0-9]{32,128}"))) { "That doesn't look like a NordVPN token" }
        val (key, user, pass) = NordApi.credentials(t)
        return NordApi.json.encodeToString(Account.serializer(), Account(t, key, user, pass))
    }

    fun account(blob: String) = Http.parsed("the account store") { NordApi.json.decodeFromString(Account.serializer(), blob) }

    override fun countries(account: String) = NordApi.countries().sortedBy { it.name }

    fun tech(loc: Location) = when {
        loc.group in openVpnOnly -> loc.protocol?.takeIf { it.startsWith("openvpn") } ?: "openvpn_udp"
        else -> loc.protocol ?: "wireguard_udp"
    }

    /** Every NordVPN server carries its load, and the recommendations come lowest load first. */
    override val hasLoad = true
    override val oneKey = true

    const val DEDICATED_IP = "legacy_dedicated_ip"

    override fun connect(loc: Location, account: String): Link {
        val a = account(account)
        val tech = tech(loc)
        // A pinned server as Nord lists it now (address and key can change); as saved when the list can't be read.
        val sv = loc.pinned?.let { p ->
            // A Dedicated IP server comes from the account's own list, which a refused login must not hide.
            val list = try { if (loc.group == DEDICATED_IP) NordApi.dedicated(a.token, tech) else loc.countryId?.let { NordApi.inPlace(it, loc.cityId, tech) } } catch (e: AuthException) { throw e } catch (e: Exception) { null }
            p.asListed(list)
        }
            ?: if (loc.group == DEDICATED_IP) dedicated(a, loc, tech).firstOrNull() ?: throw IOException("Your Dedicated IP isn't in ${loc.label}")
            // One of the three least loaded, not always the first: Nord gives an account one WireGuard key for every
            // device, and two devices with one key on one server knock each other off. Every device at the same
            // place (and Nord's own apps) is told the same first choice.
            else NordApi.recommend(loc, tech = tech).randomOrNull() ?: throw IOException("No NordVPN server for ${loc.label}")
        if (tech == "wireguard_udp") return WgLink(sv, peer(sv, a.key))
        // OpenVPN: Nord publishes one profile per server; the login is the account's service credentials.
        val (user, pass) = if (a.user.isNotEmpty()) a.user to a.pass else NordApi.credentials(a.token).let { it.second to it.third }
        val proto = tech.removePrefix("openvpn_")
        require(sv.host.matches(Regex("[a-z0-9-]+\\.nordvpn\\.com"))) { "Unexpected server name" }
        val text = Http.get("https://downloads.nordcdn.com/configs/files/ovpn_$proto/servers/${sv.host}.$proto.ovpn")
        return OvpnLink(sv, Manual.parseOvpn(withLegacyCiphers(text), sv.name, user, pass))
    }

    /** Country codes that have servers in a specialty [group] (P2P, Double VPN, Onion…). */
    fun groupCountries(group: String): Set<String> =
        NordApi.groupCountries(group, if (group in openVpnOnly) "openvpn_udp" else "wireguard_udp")

    /** The servers of the account's own Dedicated IP, narrowed to [loc]'s country; an account without one is told so. */
    private fun dedicated(a: Account, loc: Location, tech: String): List<Server> {
        val mine = NordApi.dedicated(a.token, tech)
        if (mine.isEmpty()) throw IOException("This NordVPN account has no Dedicated IP. Add one in your Nord Account, or pick another server group.")
        return mine.filter { loc.countryCode == null || it.country.equals(loc.countryCode, true) }
    }

    override fun servers(loc: Location, account: String, limit: Int) =
        if (loc.group == DEDICATED_IP) dedicated(account(account), loc, tech(loc)).take(limit) else NordApi.recommend(loc.copy(pinned = null), limit, tech(loc))

    /**
     * Nord's profiles rely on `cipher AES-256-CBC`, which OpenVPN 2.6 (and our engine) ignore for negotiation; Nord's
     * servers still need CBC offered, so the 2.6-style equivalent is spelled out (verified live: without it the
     * tunnel never comes up).
     */
    private const val LEGACY_CIPHERS = "\ndata-ciphers AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305:AES-256-CBC\ndata-ciphers-fallback AES-256-CBC\n"

    internal fun withLegacyCiphers(profile: String) = profile + LEGACY_CIPHERS

    fun peer(sv: Server, key: String) = WgPeer(
        name = sv.name, privateKey = key, address = listOf("10.5.0.2/32"), publicKey = sv.publicKey,
        endpoint = "${sv.ip}:51820", allowedIps = listOf("0.0.0.0/0"),
    )
}
