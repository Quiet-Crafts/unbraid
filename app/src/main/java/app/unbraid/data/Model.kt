package app.unbraid.data

import kotlinx.serialization.Serializable

const val DIRECT = "direct"

/** Where a route exits. Only [provider] set = that provider's fastest server anywhere. */
@Serializable
data class Location(
    val provider: String = "nord",
    val countryId: Int? = null,
    val countryCode: String? = null,
    val country: String? = null,
    val cityId: Int? = null,
    val city: String? = null,
    /** Provider-specific region/city id (e.g. Mullvad "se-got", PIA "de-frankfurt"). */
    val region: String? = null,
    val group: String? = null,
    /** NordVPN only: null = NordLynx, else openvpn_udp / openvpn_tcp. Double VPN and Onion always use OpenVPN. */
    val protocol: String? = null,
    val pinned: Server? = null,
) {
    val label get() = pinned?.name ?: city?.let { "$country · $it" } ?: country ?: "Fastest"
    val code get() = countryCode ?: "··"
}

@Serializable
data class Server(val name: String, val host: String, val ip: String, val publicKey: String, val load: Int, val city: String = "", val country: String = "")

/** A plain WireGuard peer, e.g. a FRITZ!Box export. */
@Serializable
data class WgPeer(
    val name: String,
    val privateKey: String,
    val address: List<String>,
    val publicKey: String,
    val presharedKey: String? = null,
    val endpoint: String,
    val allowedIps: List<String>,
    val dns: List<String> = emptyList(),
    val mtu: Int = 1420,
)

/** An imported OpenVPN profile (ExpressVPN, CyberGhost…): the raw .ovpn plus its login. */
@Serializable
data class OvpnProfile(val name: String, val config: String, val username: String = "", val password: String = "")

/** A Shadowsocks server (e.g. an Outline access key). */
@Serializable
data class SsProfile(val name: String, val server: String, val port: Int, val method: String, val password: String, val plugin: String? = null, val pluginOpts: String? = null)

/**
 * One exit. Exactly one source: a provider [loc], an imported [wg]/[ovpn]/[ss] server, or [tsExit] — a Tailscale
 * exit node (name or 100.x address) reached through the built-in Tailscale client.
 */
@Serializable
data class Route(
    val id: String,
    val loc: Location = Location(),
    val wg: WgPeer? = null,
    val ovpn: OvpnProfile? = null,
    val ss: SsProfile? = null,
    val tsExit: String? = null,
    /** Display name of the [tsExit] node; [tsExit] itself holds its tailnet IP so similarly named nodes can't be confused. */
    val tsName: String? = null,
    val hue: Int = 0,
    /**
     * The provider's own blocking (NordVPN Threat Protection Lite, IVPN AntiTracker…) for this route only: null follows
     * the provider's page ([Setup.protects]).
     */
    val filter: Boolean? = null,
    /** The saved [OwnServer] whose config [wg]/[ovpn]/[ss] is a copy of, so renaming or removing it reaches this route. */
    val own: String? = null,
) {
    val name get() = wg?.name ?: ovpn?.name ?: ss?.name ?: tsName ?: tsExit ?: loc.country ?: "Fastest"
    val code get() = when { wg != null -> "WG"; ovpn != null -> "OV"; ss != null -> "SS"; tsExit != null -> "TS"; else -> loc.code }
    /** Not tied to a provider account. */
    val imported get() = wg != null || ovpn != null || ss != null || tsExit != null
    /** Who carries this route: the provider, or the kind of own server. */
    val source get() = when { wg != null -> "WireGuard"; ovpn != null -> "OpenVPN"; ss != null -> "Shadowsocks"; tsExit != null -> "Tailscale"; else -> Providers.short(loc.provider) }
}

@Serializable
data class Home(
    val tailscale: Boolean = false,
    val tsAuthKey: String? = null,
    /** Self-hosted coordination server (Headscale); null = Tailscale's. */
    val tsControlUrl: String? = null,
    /** LANs behind a Tailscale subnet router, e.g. 192.168.178.0/24. Reached through Tailscale even on other Wi-Fi. */
    val subnets: List<String> = emptyList(),
    val wg: WgPeer? = null,
    /** A Headscale server's DNS suffix the user allowed to answer every app's lookups under it; Tailscale's own needs none. */
    val tsSuffixAllowed: String? = null,
)

@Serializable
data class Prefs(
    /** Moved to NordVPN's page ([Setup.opts]); read only to carry an older choice over ([Setup.migrated]). */
    val threatProtection: Boolean = true, val customDns: List<String> = emptyList(), val lan: Boolean = true,
    /** Local statistics; off until the user agrees, and nothing is recorded while off. */
    val stats: Boolean = false,
    /** unbraid's own DNS filter and where it applies. [customDns] is read only to carry an older choice over. */
    val filter: Filter = Filter(),
)

/** One of the user's own servers, added in Settings and picked for any route (which keeps a copy of the config). */
@Serializable
data class OwnServer(val id: String, val wg: WgPeer? = null, val ovpn: OvpnProfile? = null, val ss: SsProfile? = null) {
    val name get() = wg?.name ?: ovpn?.name ?: ss?.name.orEmpty()
    val kind get() = when { wg != null -> "WireGuard"; ovpn != null -> "OpenVPN"; else -> "Shadowsocks" }
    val code get() = when { wg != null -> "WG"; ovpn != null -> "OVPN"; else -> "SS" }
}

/** The current [Setup.version]. */
const val VERSION = 1

@Serializable
data class Setup(
    val routes: List<Route> = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands"))),
    val defaultRoute: String = "nl",
    val apps: Map<String, String> = emptyMap(),
    /** Starred countries, cities or pinned servers, shown first in the picker. */
    val favorites: List<Location> = listOf(
        Location("nord", 228, "US", "United States"),
        Location("nord", 106, "IT", "Italy"),
        Location("nord", 153, "NL", "Netherlands"),
    ),
    val home: Home = Home(),
    val prefs: Prefs = Prefs(),
    val servers: List<OwnServer> = emptyList(),
    /** Saved format: 1 = provider pages and unbraid's filter. New setups start at the current one ([Store]). */
    val version: Int = 0,
    /** Each provider's page settings: provider id → [Opt.key] → value. Unset keys take [Provider.defaults]. */
    val opts: Map<String, Map<String, String>> = emptyMap(),
) {
    fun route(id: String?) = routes.firstOrNull { it.id == id }
    fun routeOf(pkg: String) = apps[pkg] ?: defaultRoute
    /** Routes that carry traffic right now: the default plus any with apps. */
    val activeRoutes get() = routes.filter { r -> r.id == defaultRoute || apps.containsValue(r.id) }
    /** The Tailscale exit node in use, if any active route goes out through one (Tailscale allows one at a time). */
    val tsExit get() = activeRoutes.firstNotNullOfOrNull { it.tsExit }
    /** Tailscale runs for home access or for an exit-node route. */
    val tailscale get() = home.tailscale || tsExit != null
    /** Nothing goes through a VPN (Direct default, no app on a route, no home tunnel): running protects nothing. */
    val directOnly get() = defaultRoute == DIRECT && apps.values.all { it == DIRECT } && home.wg == null && !home.tailscale

    /** [provider]'s page setting [key], or its default. */
    fun opt(provider: String, key: String) = opts[provider]?.get(key) ?: Providers.get(provider)?.defaults?.get(key) ?: app.unbraid.data.COMMON_DEFAULTS[key]

    /** Whether route [r] uses its provider's own blocking: its own choice, else the provider page's. */
    fun protects(r: Route) = !r.imported && (r.filter ?: (opt(r.loc.provider, PROTECT) == "on"))

    /**
     * Older saves kept Threat Protection Lite in [Prefs] (it now lives on NordVPN's page) and a custom DNS server for every
     * route (now unbraid's filter set to that server, on VPN routes).
     */
    fun migrated(): Setup {
        if (version >= VERSION) return this
        // NordVPN routes had no blocking switch of their own: a flag one kept from another provider meant nothing then.
        var s = copy(version = VERSION, routes = routes.map { if (!it.imported && it.loc.provider == "nord") it.copy(filter = null) else it })
        if (!prefs.threatProtection) s = s.copy(opts = opts + ("nord" to (opts["nord"].orEmpty() + (PROTECT to "off"))), prefs = s.prefs.copy(threatProtection = true))
        prefs.customDns.firstOrNull()?.let { s = s.copy(prefs = s.prefs.copy(customDns = emptyList(), filter = s.prefs.filter.takeIf { it.id != null } ?: Filter(Filters.OWN, it, routes = true))) }
        return s
    }
}

@Serializable
data class City(val id: Int, val name: String, val key: String? = null)

/**
 * What makes two locations the same place: Nord ids are Nord's own; other providers' ids are derived and changed once
 * (positions → [stableId]), so those compare by code, city and region instead. Pins compare by server name.
 */
fun Location.ident() = copy(
    countryId = countryId.takeIf { provider == "nord" }, cityId = cityId.takeIf { provider == "nord" },
    pinned = pinned?.let { Server(it.name, "", "", "", -1, "", "") },
)

/** How a location is remembered as a favourite: its [ident], without server type or protocol. */
fun Location.favKey() = ident().copy(group = null, protocol = null)

/** An id that stays the same when a provider adds or drops places, so saved routes and favourites keep matching. */
fun stableId(key: String) = key.lowercase().hashCode() and 0x7fffffff

@Serializable
data class Country(val id: Int, val name: String, val code: String, val serverCount: Int = 0, val cities: List<City> = emptyList())
