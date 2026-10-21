package app.unbraid.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import kotlin.random.Random

/**
 * Windscribe over WireGuard. Windscribe has no API to register a key, so the user generates one config at
 * windscribe.com (Config generators → WireGuard, a paid-plan feature) and pastes it once. Account blob: that config's
 * private key, tunnel address, preshared key, DNS, MTU and port.
 *
 * One config serves every server: the private key, address and preshared key are "specific by user and the same for all
 * servers" (gluetun wiki, setup/providers/windscribe.md, which also says the preshared key MUST be set). A
 * Build-a-Plan account can only use the locations it paid for (windscribe.com/knowledge-base/articles/where-do-i-access-my-wireguard-configs).
 *
 * Servers: Windscribe's public server list, the one gluetun's updater reads without a login
 * (internal/provider/windscribe/updater/api.go: `https://assets.windscribe.com/serverlist/mob-v2/1/<unix time>`).
 * Per gluetun's updater/servers.go, a node's WireGuard endpoint is its third address `ip3` and the server's public key
 * is its group's `wg_pubkey`. Port: the one in the pasted config when it is one gluetun allows for Windscribe
 * (53, 80, 123, 443, 1194, 65142; internal/configuration/settings/wireguardselection.go), else 1194, gluetun's default
 * (internal/provider/windscribe/connection.go: `NewConnectionDefaults(443, 1194, 1194)`). DNS is the pasted config's.
 * `health` in the server list is read as a load figure (lower = better) and only used to order servers.
 */
object Windscribe : Provider {
    @Serializable
    internal data class Account(
        val privateKey: String,
        val address: List<String>,
        val presharedKey: String,
        val dns: List<String> = emptyList(),
        val mtu: Int = 1420,
        val port: Int = DEFAULT_PORT,
    )

    @Serializable
    internal data class Node(val hostname: String = "", val ip3: String = "", val health: Int = 0)

    @Serializable
    internal data class Group(
        val city: String = "", @SerialName("wg_pubkey") val publicKey: String = "", val nodes: List<Node> = emptyList(),
        /** The host Windscribe's Config Generator writes as Endpoint for this location. */
        @SerialName("wg_endpoint") val wgEndpoint: String = "",
    )

    @Serializable
    internal data class Region(val name: String = "", @SerialName("country_code") val code: String = "", val status: Int = 1, val groups: List<Group> = emptyList())

    @Serializable
    internal data class ServerList(val data: List<Region> = emptyList())

    /** One WireGuard-capable node with its place. */
    internal class Row(val region: String, val code: String, val city: String, val node: Node, val publicKey: String)

    private const val LIST = "https://assets.windscribe.com/serverlist/mob-v2/1/"
    private const val DEFAULT_PORT = 1194
    private val PORTS = setOf(53, 80, 123, 443, 1194, 65142)
    private val IPV4 = Regex("(\\d{1,3}\\.){3}\\d{1,3}")
    private val HOST = Regex("[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+")

    override val id = "windscribe"
    override val name = "Windscribe"
    override val fields = listOf(Field("config", "WireGuard config", hint = "Paste the whole file", file = true))
    override val help = "A paid plan is needed. Sign in at windscribe.com, open My Account, Config Generator, WireGuard tab, pick any location " +
        "and a port (443 if unsure), keep \"Generate new keypair\", then Download Config. Open the file in a text editor and paste all of it here. " +
        "The key works for every location your plan covers. Build-a-Plan accounts can only connect to the locations they paid for. " +
        "Static IP configs are tied to one server: import those under \"Your own servers\" instead."

    /**
     * Host names of the ordinary locations (each group's WireGuard host, as generated configs name it, and its nodes),
     * also of locations down right now; null if the list can't be read (then only the endpoint's form is checked).
     */
    internal var nodeHosts: () -> Set<String>? = { runCatching { hosts(parse(Http.get(LIST + System.currentTimeMillis() / 1000))) }.getOrNull() }

    internal fun hosts(l: ServerList) = l.data.flatMap { r -> r.groups.flatMap { g -> g.nodes.map { it.hostname } + g.wgEndpoint } }
        .filter { it.isNotBlank() }.mapTo(HashSet()) { it.lowercase() }

    /** A Static IP config points at one purchased address or host that is not in the node list. */
    private fun isStatic(endpoint: String): Boolean {
        val host = endpoint.substringBeforeLast(':')
        return IPV4.matches(host) || !HOST.matches(host) || "static" in host.lowercase() || nodeHosts()?.let { host.lowercase() !in it } == true
    }

    override fun signIn(input: Map<String, String>): String {
        val p = WgConf.parse(input["config"].orEmpty().trim(), name)
        require(!isStatic(p.endpoint)) { "This looks like a Static IP config, which is tied to one server. Import it under \"Your own servers\" instead." }
        val psk = requireNotNull(p.presharedKey) { "This config has no PresharedKey. Generate a WireGuard config at windscribe.com and paste that" }
        val port = p.endpoint.substringAfterLast(':').toIntOrNull()?.takeIf { it in PORTS } ?: DEFAULT_PORT
        return NordApi.json.encodeToString(Account.serializer(), Account(p.privateKey, p.address, psk, p.dns, p.mtu, port))
    }

    internal fun account(blob: String) = Http.parsed("Windscribe") { NordApi.json.decodeFromString(Account.serializer(), blob) }

    override fun countries(account: String) = countries(fetch())

    // A pinned node is looked up in the current list (its address and group key may have changed); the saved one only offline.
    override fun connect(loc: Location, account: String): Link = link(if (loc.pinned == null) fetch() else runCatching { fetch() }.getOrNull(), loc, account(account))

    override fun servers(loc: Location, account: String, limit: Int): List<Server> =
        fetch().match(loc).sortedWith(compareBy<Row> { it.node.health }.thenBy { it.node.hostname }).take(limit).map(::server)

    private fun fetch() = rows(parse(Http.get(LIST + System.currentTimeMillis() / 1000)))

    internal fun parse(body: String) = Http.parsed("Windscribe") { NordApi.json.decodeFromString(ServerList.serializer(), body) }

    /** Nodes that have a WireGuard address and a valid group key, in regions that are up. */
    internal fun rows(l: ServerList): List<Row> = l.data.filter { it.status == 1 && it.name.isNotBlank() }.flatMap { r ->
        r.groups.filter { validWgKey(it.publicKey) && it.city.isNotBlank() }.flatMap { g ->
            g.nodes.filter { IPV4.matches(it.ip3) && it.hostname.isNotBlank() }.map { Row(r.name, r.code.uppercase(), g.city, it, g.publicKey) }
        }
    }

    /** Windscribe's own locations: "US East", "Canada West", "Japan"…, with the cities in them. */
    internal fun countries(rows: List<Row>): List<Country> = rows.groupBy { it.region }.entries.sortedBy { it.key }.map { (region, rs) ->
        val cities = rs.map { it.city }.distinct().sorted()
        Country(stableId(region), region, rs.first().code, rs.size, cities.map { c -> City(stableId(c), c, c) })
    }

    /** Nodes for [loc]: a location by name (several share one country code), else by country code; [Location.region] narrows to a city. */
    internal fun List<Row>.match(loc: Location) = filter { r ->
        (loc.country == null && loc.countryCode == null || (loc.country?.let { r.region == it } ?: r.code.equals(loc.countryCode, true))) &&
            (loc.region == null || r.city == loc.region)
    }

    internal fun link(rows: List<Row>?, loc: Location, a: Account, rnd: Random = Random): WgLink {
        val pinned = loc.pinned
        val sv = if (rows == null) pinned!! else if (pinned != null) rows.firstOrNull { it.node.hostname == pinned.host }?.let(::server) ?: throw NoLongerOffered(pinned) else {
            val rs = rows.match(loc)
            if (rs.isEmpty()) throw IOException("No Windscribe server for ${loc.label}")
            server(rs.shuffled(rnd).minBy { it.node.health })
        }
        return WgLink(
            sv,
            WgPeer(
                name = sv.name, privateKey = a.privateKey, address = a.address, publicKey = sv.publicKey, presharedKey = a.presharedKey,
                endpoint = "${sv.ip}:${a.port}", allowedIps = listOf("0.0.0.0/0"), dns = a.dns, mtu = a.mtu,
            ),
        )
    }

    private fun server(r: Row) = Server(r.node.hostname.substringBefore('.'), r.node.hostname, r.node.ip3, r.publicKey, r.node.health, r.city, r.code)
}
