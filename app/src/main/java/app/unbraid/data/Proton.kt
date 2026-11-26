package app.unbraid.data

import app.unbraid.APP
import app.unbraid.vpn.Fail
import java.util.Locale

/** Proton manual WireGuard configs authorize one peer. A directory can suggest an address for an older config. */
open class ProtonVpn(private val free: Boolean) : KeyProvider() {
    override val id = if (free) "proton-free" else "proton"
    override val name = if (free) "Proton VPN Free" else "Proton VPN"
    override val fields = listOf(Field("key", "WireGuard config", hint = "Paste the whole downloaded .conf file", file = true))
    override val help = "At account.protonvpn.com, open Downloads, WireGuard configuration. Select the server you want and download its .conf file. " +
        "Paste or open the whole file. This account uses the server in that file. For extra servers, import their configs under Your own servers."
    internal val reimport = "Sign out of $name in Settings, then sign in with the whole WireGuard .conf downloaded from Proton for this server"
    override val blockingNote = if (free) "None on the Free plan. Use $APP's Protection filter instead"
        else "NetShield is chosen when you make a config at Proton. Or use $APP's Protection filter instead"

    /** Proton's plans: Free one connection at a time, Plus and Unlimited ten; a manual key counts per tunnel (tested live). */
    override fun limit(account: String) = Limit("connection", perTunnel = true, if (free) 1 else 10)

    /** The options Proton writes into each config it makes, as comments; changing one takes a new config. */
    override val configFacts = listOf("VPN Accelerator", "NetShield", "Moderate NAT", "NAT-PMP (Port Forwarding)")
    override fun fact(key: String, value: String) = when (key) {
        "NetShield" -> "NetShield " + when (value) { "0" -> "off"; "1" -> "malware"; "2" -> "malware, ads and trackers"; else -> value }
        "NAT-PMP (Port Forwarding)" -> "Port forwarding $value"
        else -> "$key $value"
    }
    override val configHelp = "Proton configs expire. Extend yours at account.protonvpn.com, Downloads" to "https://account.protonvpn.com/downloads"

    override val defaultAddress = listOf("10.2.0.2/32")
    override val defaultDns = listOf("10.2.0.1")
    override val defaultPort = 51820

    override fun signIn(input: Map<String, String>): String {
        val text = input["key"].orEmpty().trim()
        require('[' in text) { "Choose the whole WireGuard .conf from Proton. It contains the server's public key and endpoint." }
        return super.signIn(input)
    }

    override fun fromConf(text: String, p: WgPeer): Account {
        val ip = p.endpoint.substringBeforeLast(':')
        require(Addr.server(ip)) { "This Proton account needs a public IPv4 endpoint. For a hostname or IPv6 endpoint, import the config under Your own servers." }
        val peerSection = text.lineSequence().dropWhile { !it.substringBefore('#').trim().equals("[Peer]", true) }.drop(1)
            .takeWhile { !it.trimStart().startsWith('[') }
        val label = peerSection.mapNotNull { peerName.matchEntire(it.trim())?.groupValues?.get(1) }.distinct().singleOrNull()
        return super.fromConf(text, p).copy(configIp = ip, configName = label)
    }

    private val peerName = Regex("#\\s*([A-Z]{2}(?:-[A-Z0-9]+)*#[0-9]+(?:-TOR)?)")

    private fun key(a: Account) = a.publicKey?.takeIf(::validWgKey) ?: throw Unsupported(reimport)

    // A list is used only to find the address of the authenticated key of an older config on an unrestricted route.
    override fun entries(a: Account) = filtered(Catalog.fetch("protonvpn", "wireguard"))
    internal fun filtered(all: List<Catalog.Entry>) = all.filter { e ->
        val n = e.serverName.orEmpty()
        (e.free || !free) && !secureCore.containsMatchIn(n) && !n.endsWith("-TOR")
    }
    private val secureCore = Regex("^(CH|IS|SE)-[A-Z]{2}#")

    private fun configured(a: Account): Server? = a.configIp?.let { ip ->
        val label = a.configName
        val prefix = label?.substringBefore('#')?.substringAfterLast('-')?.takeIf { secureCore.containsMatchIn(label) }
            ?: label?.take(2).orEmpty()
        Server(label ?: "Configured server", ip, ip, key(a), -1, country = if (prefix == "UK") "GB" else prefix)
    }

    override fun countries(account: String): List<Country> {
        val a = account(account)
        key(a)
        val country = configured(a)?.country?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val place = countryName(country)
        return listOf(Country(stableId(place), place, country, 1))
    }

    override fun servers(loc: Location, account: String, limit: Int): List<Server> {
        val a = account(account)
        key(a)
        return listOfNotNull(configured(a)?.takeIf { matches(loc, it) }).take(limit)
    }

    private fun countryName(code: String) = Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH)

    private fun matches(loc: Location, s: Server?): Boolean {
        // Only the imported file can establish geography. Proton's normal configs name a country, not a city.
        val country = s?.country.orEmpty()
        val place = if (country.isEmpty()) "" else countryName(country)
        return (loc.countryCode == null || country.isNotEmpty() && loc.countryCode == country) &&
            (loc.country == null || country.isNotEmpty() && (loc.country == place || Catalog.code(loc.country) == country)) &&
            (loc.countryId == null || country.isNotEmpty() && loc.countryId == stableId(loc.country ?: place)) &&
            loc.city == null && loc.cityId == null && loc.region == null && loc.group == null
    }

    private fun samePin(pin: Server, server: Server): Boolean {
        if (pin.name != server.name || pin.ip.isNotEmpty() && pin.ip != server.ip ||
            pin.publicKey.isNotEmpty() && pin.publicKey != server.publicKey) return false
        // A restored pin keeps only its host and name. A server label from the imported file can identify it again.
        return pin.host == server.host || server.host == server.ip && server.name != "Configured server"
    }

    override fun trusts(link: Link, account: String): Boolean = runCatching {
        val a = account(account)
        link is WgLink && verifiedLink(link.server, a) == link
    }.getOrDefault(false)

    internal fun trustsRoute(link: Link, account: String, loc: Location): Boolean = runCatching {
        val a = account(account)
        val original = configured(a)
        link is WgLink && verifiedLink(link.server, a) == link && matches(loc, original) &&
            (loc.pinned?.let { samePin(it, original ?: link.server) &&
                (original != null || it.publicKey == key(a) && it.ip == link.server.ip) } ?: true)
    }.getOrDefault(false)

    private fun verifiedLink(s: Server, a: Account): WgLink {
        val trusted = key(a)
        if (s.publicKey != trusted || configured(a)?.let { it != s } == true) throw Unsupported(reimport)
        Addr.usable(s)
        return WgLink(s, WgPeer(s.name, a.privateKey, a.address, trusted, presharedKey = a.presharedKey,
            endpoint = "${s.ip}:${a.port}", allowedIps = listOf("0.0.0.0/0"), dns = a.dns, mtu = a.mtu))
    }

    override fun link(loc: Location, a: Account, entries: () -> List<Catalog.Entry>): WgLink {
        val trusted = key(a)
        val pin = loc.pinned
        val original = configured(a)
        if (matches(loc, original)) {
            if (original != null && (pin == null || samePin(pin, original))) return verifiedLink(original, a)
            if (original == null && pin != null && pin.publicKey == trusted && Addr.server(pin.ip)) return verifiedLink(pin, a)
        }
        if (original == null && pin == null && matches(loc, null)) {
            val row = try { entries() } catch (e: java.io.IOException) { throw Fail("The server address for this older $name config is unavailable. $reimport") }
                .firstOrNull { it.wgpubkey == trusted && it.ipv4?.let(Addr::server) == true }
            if (row != null) return verifiedLink(Server("Configured server", row.ipv4!!, row.ipv4!!, trusted, -1), a)
        }
        throw Unsupported("This $name config cannot verify ${loc.label}. Choose Configured server for this route, or sign in again with the intended server's .conf")
    }
}

/** A downloaded config for a paid Proton VPN account. */
object Proton : ProtonVpn(false)

/** A downloaded config for a free Proton VPN account. */
object ProtonFree : ProtonVpn(true)
