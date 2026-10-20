package app.unbraid.data

import app.unbraid.APP
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AirVPN over WireGuard. AirVPN issues the key per *device* in the Client Area → Config Generator, together with a
 * device-specific tunnel address and a preshared key, so sign-in needs the whole .conf (bare keys are refused).
 * One device config works on every server (all servers share one WireGuard key, the .conf's [Peer] PublicKey, kept
 * in the account and used for every server).
 * Servers: AirVPN's own status API `GET airvpn.org/api/status/` (no login; the gluetun updater reads it): healthy
 * servers' `ip_v4_in1` is the WireGuard entry, hostname `<cc>.vpn.airdns.org`. Fallback: gluetun's catalogue, for
 * addresses only: the key is always the account's own (from the .conf), never the catalogue's.
 * Port 1637 (gluetun's default; 47107 is the other one it allows) and DNS 10.128.0.1 (the in-tunnel DNS in AirVPN's generated
 * configs; a pasted .conf overrides both).
 * Sources: gluetun and generated configs quoted in AirVPN's forum
 * (https://airvpn.org/forums/topic/58214-airvpn-wireguard-through-gluetun/). Two simultaneous tunnels to the *same*
 * server need two devices' keys.
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
        val health: String = "",
        @SerialName("currentload") val load: Int? = null,
    )

    @Serializable
    internal class Status(val servers: List<Node> = emptyList())

    private const val API = "https://airvpn.org/api/status/"

    override val id = "airvpn"
    override val name = "AirVPN"
    override val fields = listOf(Field("key", "WireGuard .conf", hint = "Paste the whole generated file", file = true))
    override val help = "In the AirVPN Client Area open Config Generator (airvpn.org/generator), choose WireGuard, pick a device, any server, then generate and download the .conf. Paste the whole file: it carries the private key, the device's address and the preshared key. One device config works on every server; use a separate device for each other app that connects at the same time."

    /** `currentload` of the status API is the server's load in percent. */
    override val hasLoad = true

    override val defaultAddress: List<String>? = null
    override val defaultDns = listOf("10.128.0.1")
    override val defaultPort = 1637
    override val presharedRequired = true

    /** AmneziaWG obfuscation keys: Unbraid's engine would drop them silently, so such a config is refused. */
    private val AMNEZIA = Regex("(?im)^\\s*(jc|jmin|jmax|s[1-4]|h[1-4]|i[1-5])\\s*=")

    override fun signIn(input: Map<String, String>): String {
        require(!AMNEZIA.containsMatchIn(input["key"].orEmpty())) {
            "This config uses Amnezia obfuscation (Jc, S1, H1 and similar lines), which $APP can't apply. Generate a standard WireGuard config in the AirVPN Config Generator."
        }
        return super.signIn(input)
    }

    override fun entries(a: Account): List<Catalog.Entry> = try {
        parse(status(), a.publicKey.orEmpty()).ifEmpty { throw java.io.IOException("Empty AirVPN server list") }
    } catch (e: java.io.IOException) {
        val key = a.publicKey ?: throw e
        catalogue().map { it.copy(wgpubkey = key) }
    }

    /** Every AirVPN server shares the account's [Peer] key: no list, pinned server or saved tunnel can change it. */
    override fun peerKey(sv: Server, a: Account) = a.publicKey ?: sv.publicKey

    internal var catalogue: () -> List<Catalog.Entry> = { Catalog.fetch("airvpn", "wireguard") }
    internal var status: () -> String = { Http.get(API) }

    internal fun parse(body: String, serverKey: String): List<Catalog.Entry> =
        Http.parsed("AirVPN") { NordApi.json.decodeFromString(Status.serializer(), body) }.servers
            .filter { it.health == "ok" && '.' in it.ip && it.country.isNotBlank() && it.code.isNotBlank() }
            .map {
                val city = it.location.replace(", ", " ").replace(",", "").takeIf(String::isNotBlank)
                Catalog.Entry("wireguard", it.country, it.continent, city, it.name, "${it.code}.vpn.airdns.org", serverKey, listOf(it.ip), load = it.load?.coerceIn(0, 100) ?: -1)
            }
}
