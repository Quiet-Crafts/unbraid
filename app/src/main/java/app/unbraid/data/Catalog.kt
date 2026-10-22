package app.unbraid.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Server catalogues for providers without a public server API (or as a fallback), from gluetun's
 * monthly-updated, MIT-licensed data: https://github.com/qdm12/gluetun-servers
 */
object Catalog {
    private const val BASE = "https://raw.githubusercontent.com/qdm12/gluetun-servers/main/pkg/servers/"

    @Serializable
    data class Entry(
        val vpn: String = "",
        val country: String? = null,
        val region: String? = null,
        val city: String? = null,
        @SerialName("server_name") val serverName: String? = null,
        val hostname: String = "",
        val wgpubkey: String? = null,
        val ips: List<String> = emptyList(),
        val udp: Boolean = false,
        val tcp: Boolean = false,
        /** Proton: usable on the free plan. */
        val free: Boolean = false,
        /** Live load in percent from a provider's own list; -1 = none (the gluetun catalogue has no load figures). */
        val load: Int = -1,
    ) {
        /** Some providers (Windscribe) only fill `region` with the country. */
        val place get() = country ?: region.orEmpty()
        val ipv4 get() = ips.firstOrNull { '.' in it }
    }

    @Serializable
    private data class File(val timestamp: Long = 0, val servers: List<Entry> = emptyList())

    /** Told when a catalogue was published (its `timestamp`, epoch seconds) each time one is read, to keep the date. */
    @Volatile var published: (name: String, epochSeconds: Long) -> Unit = { _, _ -> }

    /** [name] is the gluetun file name, e.g. "protonvpn". [vpn] keeps "wireguard" or "openvpn" servers with an IPv4. */
    fun fetch(name: String, vpn: String): List<Entry> =
        file(Http.get(BASE + Http.enc(name) + ".json")).also { f ->
            // Only a time that can be true is kept: after 2020, before tomorrow.
            if (f.timestamp in 1_577_836_800..System.currentTimeMillis() / 1000 + 86_400) published(name, f.timestamp)
        }.servers.usable(vpn)

    internal fun parse(body: String, vpn: String): List<Entry> = file(body).servers.usable(vpn)
    internal fun timestamp(body: String) = file(body).timestamp

    private fun file(body: String) = Http.parsed("the server catalogue") { NordApi.json.decodeFromString(File.serializer(), body) }
    private fun List<Entry>.usable(vpn: String) = filter { it.vpn == vpn && it.ipv4 != null && it.place.isNotEmpty() && (vpn != "wireguard" || !it.wgpubkey.isNullOrEmpty()) }

    /** Countries with their cities; ids are stable indexes, City.key = city name. */
    fun countries(entries: List<Entry>): List<Country> = entries.groupBy { it.place }.entries.sortedBy { it.key }
        .map { (place, list) ->
            val cities = list.mapNotNull { it.city }.distinct().sorted()
            Country(stableId(place), place, code(place), list.size, cities.map { c -> City(stableId(c), c, c) })
        }

    /** Servers for a location, shuffled so equals share the traffic; entries that carry a live [Entry.load] come lowest load first. */
    fun pick(entries: List<Entry>, loc: Location): List<Entry> = entries
        .filter { e -> loc.country?.let { it == e.place } ?: loc.countryCode?.let { it == code(e.place) } ?: true }
        .filter { loc.region == null || it.city == loc.region }
        .shuffled()
        .sortedBy { if (it.load < 0) Int.MAX_VALUE else it.load }

    /** Java's English names, normalised ("Hong Kong SAR China" → "hong kong", "Myanmar (Burma)" → "myanmar"). */
    private fun norm(name: String) = name.lowercase().replace(" & ", " and ").replace("ô", "o").substringBefore(" (").substringBefore(", ")
        .removeSuffix(" sar china").removeSuffix(" darussalam").removePrefix("the ").trim()

    private val codes by lazy { Locale.getISOCountries().associateBy { @Suppress("DEPRECATION") norm(Locale("", it).getDisplayCountry(Locale.ENGLISH)) } }

    private val aliases = mapOf(
        "usa" to "US", "united states of america" to "US", "uk" to "GB", "great britain" to "GB", "south korea" to "KR",
        "turkey" to "TR", "czech republic" to "CZ", "macau" to "MO", "macedonia" to "MK", "ivory coast" to "CI",
        "russia" to "RU", "vietnam" to "VN", "uae" to "AE", "bosnia" to "BA", "cape verde" to "CV", "eswatini" to "SZ",
        "democratic republic of the congo" to "CD", "dr congo" to "CD", "republic of the congo" to "CG", "cote d'ivoire" to "CI",
        "palestine" to "PS", "brunei" to "BN", "marocco" to "MA", "north korea" to "KP", "syria" to "SY", "iran" to "IR", "laos" to "LA",
    )

    /** ISO code for a catalogue country name; "··" when unknown rather than a plausible-looking wrong code. */
    fun code(country: String): String = norm(country).let { n -> codes[n] ?: aliases[n] } ?: "··"

    fun server(e: Entry, name: String = e.serverName ?: e.hostname.substringBefore('.')) =
        Server(name, e.hostname, e.ipv4!!, e.wgpubkey.orEmpty(), e.load, e.city.orEmpty(), code(e.place))
}
