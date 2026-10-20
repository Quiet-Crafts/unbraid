package app.unbraid.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Surfshark over WireGuard. Sign-in is local: the key pair is generated (or registered) at my.surfshark.com → VPN →
 * Manual setup → Desktop or mobile → WireGuard, and one registered key works on every location.
 * Servers: Surfshark's own public list `GET api.surfshark.com/v4/server/clusters/generic` (country, city, hostname and
 * WireGuard key per cluster, no login; the updater of gluetun reads the same URL). It carries no IPs, so the chosen
 * hostname is resolved at connect time (within 5 s in all). Keys only ever come from that list: if it can't be read, the
 * last copy read ([snapshot]) stands in. If none of the chosen location's hosts resolves, gluetun's catalogue supplies
 * addresses for the same host names, never keys (a swapped catalogue key would make another server the trusted one),
 * minus the Static-IP `-stNNN` servers a normal key may not be registered on.
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
    private val TOTAL_NS = TimeUnit.SECONDS.toNanos(5)
    private val PER_HOST_NS = TimeUnit.SECONDS.toNanos(2)

    /** Daemon threads: a lookup that hangs can't be interrupted, so it must not hold up the next one or the process. */
    private val dns = Executors.newCachedThreadPool { r -> Thread(r, "surfshark-dns").apply { isDaemon = true } }

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

    internal var resolve: (String) -> List<String> = { host -> InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress } }

    internal var catalogue: () -> List<Catalog.Entry> = { Catalog.fetch("surfshark", "wireguard") }

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

    override fun usable(all: List<Catalog.Entry>, loc: Location): Sequence<Catalog.Entry> {
        val end = System.nanoTime() + TOTAL_NS
        val live = Catalog.pick(all, loc).asSequence().mapNotNull { e ->
            if (e.ipv4 != null) e else lookup(e.hostname, end)?.let { e.copy(ips = it) }
        }
        if (all.any { it.ipv4 != null }) return live // already has addresses
        // Hosts that don't resolve: gluetun's addresses for the same host names, with Surfshark's own key.
        return live + sequence {
            val ips = try { fallback(catalogue()).associate { it.hostname to it.ips } } catch (_: IOException) { emptyMap() }
            yieldAll(Catalog.pick(all, loc).mapNotNull { e -> ips[e.hostname]?.takeIf { it.any { ip -> '.' in ip } }?.let { e.copy(ips = it) } })
        }
    }

    /** The first IPv4 addresses of [host], or null if it doesn't resolve before the [end] deadline (nanoTime) or its own 2 s. */
    private fun lookup(host: String, end: Long): List<String>? {
        val left = end - System.nanoTime()
        if (left <= 0) return null
        val f = dns.submit<List<String>> { resolve(host) }
        return try {
            f.get(minOf(left, PER_HOST_NS), TimeUnit.NANOSECONDS).takeIf { it.isNotEmpty() }
        } catch (_: ExecutionException) {
            null
        } catch (_: TimeoutException) {
            f.cancel(true); null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); throw InterruptedIOException("Interrupted while resolving a $name server")
        }
    }

    internal fun parse(body: String): List<Catalog.Entry> =
        Http.parsed("Surfshark") { NordApi.json.decodeFromString(ListSerializer(Cluster.serializer()), body) }
            .filter { it.country.isNotBlank() && it.connectionName.isNotBlank() && it.pubKey != null && KEY.matches(it.pubKey) }
            .map { Catalog.Entry("wireguard", it.country, it.region, it.location?.takeIf(String::isNotBlank), hostname = it.connectionName, wgpubkey = it.pubKey, load = it.load?.coerceIn(0, 100) ?: -1) }

    internal fun fallback(all: List<Catalog.Entry>) = all.filterNot { STATIC_IP.containsMatchIn(it.hostname) }
}
