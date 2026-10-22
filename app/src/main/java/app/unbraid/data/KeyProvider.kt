package app.unbraid.data

import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * Providers where the user downloads one WireGuard key (or .conf) from the website and that single key serves every
 * server: sign-in is local, with no API to check it against (AirVPN's can also fetch the .conf with an API key). The
 * tunnel settings come from the pasted .conf when there is one, else from the provider's documented defaults; the
 * server list comes from [entries].
 */
abstract class KeyProvider : Provider {
    @Serializable
    data class Account(
        val privateKey: String,
        val address: List<String>,
        val dns: List<String>,
        val port: Int,
        val mtu: Int,
        val presharedKey: String? = null,
        /** The [Peer] key of a pasted .conf; only meaningful for providers whose servers all share one key. */
        val publicKey: String? = null,
        /** Settings the provider wrote into a pasted .conf as comments ([configFacts]); null when only a key was pasted. */
        val facts: Map<String, String>? = null,
        /** For a key the provider's API made: the API key, and the name and public key of the device it belongs to. */
        val apiKey: String? = null,
        val device: String? = null,
        val deviceKey: String? = null,
    )

    /** Comment lines ("# NetShield = 0") a provider writes into its configs that say how the key was made, to show them. */
    protected open val configFacts: List<String> = emptyList()
    /** How one of [configFacts] reads on the page. */
    protected open fun fact(key: String, value: String) = "$key $value"

    override fun facts(account: String): String? = configFacts.takeIf { it.isNotEmpty() }?.let {
        val f = runCatching { account(account).facts }.getOrNull() ?: return ""
        configFacts.mapNotNull { k -> f[k]?.let { fact(k, it) } }.joinToString(" · ").ifEmpty { "None in your config" }
    }

    /** Tunnel address when only a bare key is pasted; null if the provider assigns it per device (the .conf is then required). */
    protected abstract val defaultAddress: List<String>?
    protected abstract val defaultDns: List<String>
    protected abstract val defaultPort: Int
    protected open val defaultMtu = 1420
    protected open val presharedRequired = false
    /** Every port its servers take, best first; the account's own port is always tried first. */
    protected open val ports: List<Int> = emptyList()
    /** A server's entry addresses for [WgLink.entries]; none unless the provider has an entry setting. */
    protected open fun addresses(e: Catalog.Entry): List<String> = emptyList()

    protected abstract fun entries(a: Account): List<Catalog.Entry>

    /** Whether a server key from an earlier list (a pinned or saved server) may still be used. */
    protected open fun trustsKey(key: String, a: Account): Boolean = true

    /** The server key a tunnel uses: the list's, unless the provider takes it from the account. */
    protected open fun peerKey(sv: Server, a: Account): String = sv.publicKey

    override fun trusts(link: Link, account: String): Boolean =
        link !is WgLink || runCatching { link.peer.publicKey == peerKey(link.server, account(account)) && trustsKey(link.peer.publicKey, account(account)) }.getOrDefault(false)

    /**
     * The servers of [all] that suit [loc], best first and lazily, each complete enough to connect to. Providers whose
     * list lacks addresses (Surfshark's live one) override this to resolve them.
     */
    protected open fun usable(all: List<Catalog.Entry>, loc: Location): Sequence<Catalog.Entry> = Catalog.pick(all, loc).asSequence()

    override fun signIn(input: Map<String, String>): String {
        val text = input["key"].orEmpty().trim()
        val a = if ('[' in text) fromConf(text) else fromKey(text.filterNot(Char::isWhitespace))
        return NordApi.json.encodeToString(Account.serializer(), a)
    }

    private fun fromKey(key: String): Account {
        require(KEY.matches(key)) { "That doesn't look like a WireGuard private key" }
        val address = requireNotNull(defaultAddress) { "$name needs the whole downloaded .conf file: it holds this device's address and preshared key" }
        return Account(key, address, defaultDns, defaultPort, defaultMtu)
    }

    private fun fromConf(text: String): Account {
        val p = WgConf.parse(text, name)
        require(!presharedRequired || p.presharedKey != null) { "That $name config has no PresharedKey line" }
        return Account(
            privateKey = p.privateKey,
            address = p.address.map { if ('/' in it) it else "$it/32" },
            dns = p.dns.ifEmpty { defaultDns },
            port = p.endpoint.substringAfterLast(':').toInt(),
            mtu = if (MTU.containsMatchIn(text)) p.mtu else defaultMtu,
            presharedKey = p.presharedKey,
            publicKey = p.publicKey,
            facts = configFacts.takeIf { it.isNotEmpty() }?.let { keys ->
                FACT.findAll(text).mapNotNull { m -> m.groupValues[1].trim().takeIf { it in keys }?.let { it to m.groupValues[2].trim().take(24) } }.toMap()
            },
        )
    }

    fun account(blob: String) = Http.parsed("the account store") { NordApi.json.decodeFromString(Account.serializer(), blob) }

    override fun countries(account: String) = Catalog.countries(entries(account(account)))

    override fun connect(loc: Location, account: String): Link {
        val a = account(account)
        return link(loc, a) { entries(a) }
    }

    override fun servers(loc: Location, account: String, limit: Int): List<Server> =
        usable(entries(account(account)), loc).take(limit).map { Catalog.server(it) }.toList()

    /**
     * The current address and key of a pinned server, found by host name and name (AirVPN shares one host name per
     * country), else by IP. The pinned values stand in only when the list can't be read; a server the list no longer
     * has is an error rather than a stale endpoint.
     */
    private fun refreshed(p: Server, loc: Location, a: Account, entries: () -> List<Catalog.Entry>): Pair<Server, Catalog.Entry?> {
        val all = try { entries() } catch (e: IOException) { if (trustsKey(peerKey(p, a), a)) return p to null else throw e }
        val found = all.filter { it.hostname == p.host && (it.serverName ?: it.hostname.substringBefore('.')) == p.name }
            .ifEmpty { all.filter { p.ip in it.ips } }
        if (found.isEmpty()) throw NoLongerOffered(p)
        // No address for it now: keep the pinned one, but with the list's current key.
        val live = try { usable(found, Location(loc.provider)).firstOrNull { it.hostname == found.first().hostname } } catch (_: IOException) { null }
            ?: return (found.first().wgpubkey?.let { p.copy(publicKey = it) } ?: p) to null
        return Catalog.server(live, p.name) to live
    }

    internal fun link(loc: Location, a: Account, entries: () -> List<Catalog.Entry>): WgLink {
        val (sv, e) = loc.pinned?.let { refreshed(it, loc, a, entries) } ?: usable(entries(), loc).firstOrNull()?.let { Catalog.server(it) to it }
            ?: throw IOException("No $name server for ${loc.label}")
        return WgLink(
            sv,
            WgPeer(
                name = sv.name, privateKey = a.privateKey, address = a.address, publicKey = peerKey(sv, a), presharedKey = a.presharedKey,
                endpoint = "${sv.ip}:${a.port}", allowedIps = listOf("0.0.0.0/0"), dns = a.dns, mtu = a.mtu,
            ),
            if (ports.isEmpty()) emptyList() else (listOf(a.port) + ports).distinct(),
            e?.let(::addresses).orEmpty(),
        )
    }

    private companion object {
        val KEY = Regex("[A-Za-z0-9+/]{43}=")
        val MTU = Regex("(?im)^\\s*mtu\\s*=")
        val FACT = Regex("(?m)^\\s*#\\s*([A-Za-z][A-Za-z ()-]{0,40}?)\\s*=\\s*([A-Za-z0-9 .-]{1,40})\\s*$")
    }
}
