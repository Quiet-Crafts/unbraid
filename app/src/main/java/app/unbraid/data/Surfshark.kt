package app.unbraid.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.IOException

/**
 * Surfshark over WireGuard. Sign-in is local: the key pair is generated (or registered) at my.surfshark.com → VPN →
 * Manual setup → Desktop or mobile → WireGuard, and one registered key works on every location.
 * Servers: Surfshark's own public list `GET api.surfshark.com/v4/server/clusters/generic` (country, city, hostname and
 * WireGuard key per cluster, no login; the updater of gluetun reads the same URL). It carries no IPs: gluetun's catalogue
 * supplies the addresses for the same host names ([addresses]), never keys (a swapped catalogue key would make another
 * server the trusted one), minus the Static-IP `-stNNN` servers a normal key may not be registered on. No host name is
 * looked up on the phone's own network, whose resolver would see which city each route uses. Keys only ever come from
 * Surfshark's list: if it can't be read, the last copy read ([snapshot]) stands in.
 * Parameters from Surfshark's manual Linux guide (support.surfshark.com/hc/en-us/articles/19262406140306): address
 * 10.14.0.2/16, DNS 162.252.172.57 and 149.154.159.92, endpoint `<host>:51820`, MTU 1280. A pasted .conf overrides them.
 * Surfshark assigns the address per key, so pasting the whole .conf is the safe route; the preshared key is not used.
 */
object Surfshark : KeyProvider() {
    @Serializable
    internal class Cluster(val country: String = "", val region: String? = null, val location: String? = null, val connectionName: String = "", val pubKey: String? = null, val load: Int? = null)

    private const val API = "https://api.surfshark.com/v4/server/clusters/generic"
    private val STATIC_IP = Regex("-st\\d+\\.")
    private val KEY = Regex("[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=")

    override val id = "surfshark"
    override val name = "Surfshark"
    override val fields = listOf(Field("key", "WireGuard key or .conf", hint = "Paste the whole file, or the private key", file = true))
    override val help = "Sign in at my.surfshark.com → VPN → Manual setup → Desktop or mobile → WireGuard → \"I don't have a key pair\" → name it → Generate a new key pair. The private key is shown only once, so also pick any location and Download the .conf, then paste that whole file here (it carries your private key and address). The same key works on every Surfshark location."

    /** Each cluster in the live list carries its load in percent; the catalogue fallback has none. */
    override val hasLoad = true

    override val defaultAddress = listOf("10.14.0.2/16")
    override val defaultDns = listOf("162.252.172.57", "149.154.159.92")
    override val defaultPort = 51820
    override val defaultMtu = 1280

    internal var catalogue: () -> List<Catalog.Entry> = { Catalog.fetch("surfshark", "wireguard") }
        set(v) { field = v; synchronized(this) { addressList = null; triedAt = 0 } } // another source: read it afresh

    /** The last address list read, kept for when the catalogue can't be read. */
    internal var addressBook: Snapshot = Snapshot.file("addresses-surfshark.json")
    private val book = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    private var addressList: Map<String, List<String>>? = null
    private var addressesAt = 0L
    private var triedAt = 0L

    /**
     * Host name → IPv4 addresses from gluetun's catalogue, read at most once a day; the last copy when it can't be read,
     * then asked again only after 15 minutes. Only literal addresses count: a name here would be looked up after all.
     */
    @Synchronized
    internal fun addresses(now: Long = System.currentTimeMillis()): Map<String, List<String>> {
        addressList?.takeIf { now - addressesAt < 86_400_000 }?.let { return it }
        // A failed read waits 15 minutes before the next, also with nothing kept to fall back on.
        if (addressList == null && now - triedAt < 15 * 60_000) throw IOException("Surfshark's server addresses can't be read right now")
        triedAt = now
        val fresh = try { literal(fallback(catalogue()).associate { it.hostname to it.ips }) } catch (_: IOException) { null }
        if (!fresh.isNullOrEmpty()) { addressList = fresh; addressesAt = now; addressBook.write(NordApi.json.encodeToString(book, fresh)) }
        else {
            addressList = addressList ?: addressBook.read()?.let { runCatching { literal(NordApi.json.decodeFromString(book, it)) }.getOrNull() }
            addressesAt = now - 86_400_000 + 15 * 60_000
        }
        return addressList ?: throw IOException("Surfshark's server addresses can't be read right now")
    }

    private fun literal(m: Map<String, List<String>>) = m.mapValues { (_, ips) -> ips.filter { '/' !in it && app.unbraid.vpn.Compiler.ipv4(it) != null } }.filterValues { it.isNotEmpty() }

    /** The last list read from Surfshark's own API: its keys still apply when the API can't be reached. */
    internal var snapshot: Snapshot = Snapshot.file("servers-surfshark.json")

    internal var live: () -> String = { Http.get(API) }

    /**
     * A saved or pinned key is reused only if Surfshark's own list (the last copy read) still has it, or it is the server
     * key of the user's own downloaded .conf (it came from Surfshark too).
     */
    override fun trustsKey(key: String, a: Account) = key == a.publicKey ||
        snapshot.read()?.let { runCatching { parse(it) }.getOrNull() }?.any { it.wgpubkey == key } == true

    override fun entries(a: Account): List<Catalog.Entry> {
        val live = try { live().let { body -> parse(body).ifEmpty { null }?.also { snapshot.write(body) } } } catch (e: IOException) { null }
        return live ?: snapshot.read()?.let { runCatching { parse(it) }.getOrNull() }?.ifEmpty { null }
            ?: throw IOException("Surfshark's server list can't be reached right now")
    }

    /** Each of the location's hosts once, with its catalogue addresses and Surfshark's own key; hosts without one are skipped. */
    override fun usable(all: List<Catalog.Entry>, loc: Location): Sequence<Catalog.Entry> {
        val picked = Catalog.pick(all, loc)
        if (all.any { it.ipv4 != null }) return picked.asSequence() // already has addresses
        return sequence { val ips = addresses(); yieldAll(picked.mapNotNull { e -> ips[e.hostname]?.let { e.copy(ips = it) } }) }
    }

    internal fun parse(body: String): List<Catalog.Entry> =
        Http.parsed("Surfshark") { NordApi.json.decodeFromString(ListSerializer(Cluster.serializer()), body) }
            .filter { it.country.isNotBlank() && it.connectionName.isNotBlank() && it.pubKey != null && KEY.matches(it.pubKey) }
            .map { Catalog.Entry("wireguard", it.country, it.region, it.location?.takeIf(String::isNotBlank), hostname = it.connectionName, wgpubkey = it.pubKey, load = it.load?.coerceIn(0, 100) ?: -1) }

    internal fun fallback(all: List<Catalog.Entry>) = all.filterNot { STATIC_IP.containsMatchIn(it.hostname) }
}
