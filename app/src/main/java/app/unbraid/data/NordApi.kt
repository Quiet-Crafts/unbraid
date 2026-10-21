package app.unbraid.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64

/** The handful of NordVPN endpoints Unbraid needs. All calls block; run them off the main thread. */
object NordApi {
    private const val BASE = "https://api.nordvpn.com/v1"
    private const val MAX_BODY = 8 shl 20
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun get(path: String, token: String? = null): String =
        try { fetch(path, token) } catch (e: java.net.UnknownHostException) { Thread.sleep(1500); fetch(path, token) }

    private fun fetch(path: String, token: String?): String {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        token?.let { c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("token:$it".toByteArray())) }
        try {
            when (c.responseCode) {
                200 -> return c.inputStream.use { String(it.readNBytes(MAX_BODY + 1).also { b -> if (b.size > MAX_BODY) throw IOException("Response too large") }) }
                401 -> throw AuthException("NordVPN rejected the token. Sign in again.")
                else -> throw Http.Status(c.responseCode, "NordVPN API ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    }

    /** NordLynx private key plus the service username/password OpenVPN needs. */
    fun credentials(token: String): Triple<String, String, String> {
        val o = Http.parsed("NordVPN") { json.parseToJsonElement(get("/users/services/credentials", token)).jsonObject }
        fun f(k: String) = o[k]?.jsonPrimitive?.content ?: throw app.unbraid.vpn.Fail("Unexpected reply from NordVPN. Try again later")
        return Triple(f("nordlynx_private_key"), f("username"), f("password"))
    }

    fun countries(): List<Country> = json.decodeFromString(get("/servers/countries"))

    /** Lowest-load servers for a location, best first. [tech]: wireguard_udp, openvpn_udp or openvpn_tcp. */
    fun recommend(loc: Location, limit: Int = 3, tech: String = "wireguard_udp"): List<Server> {
        val q = buildList {
            add("filters[servers_technologies][identifier]" to tech)
            loc.countryId?.let { add("filters[country_id]" to "$it") }
            loc.cityId?.let { add("filters[country_city_id]" to "$it") }
            loc.group?.let { add("filters[servers_groups][identifier]" to it) }
            add("limit" to "$limit")
        }.joinToString("&") { (k, v) -> Http.enc(k) + "=" + v }
        return parseServers(get("/servers/recommendations?$q"), tech == "wireguard_udp")
    }

    private fun query(vararg pairs: Pair<String, String?>) =
        pairs.joinToString("&") { (k, v) -> Http.enc(k) + (v?.let { "=$it" } ?: "") }

    /** The whole `/servers` list of a specialty [group] for [tech], cut down to the given response `fields`. */
    private fun groupServers(group: String, tech: String, vararg fields: String): String = get(
        "/servers?" + query(
            "filters[servers_groups][identifier]" to group, "filters[servers_technologies][identifier]" to tech, "limit" to "20000",
            *fields.map { "fields[servers.$it]" to null }.toTypedArray(),
        ),
    )

    /**
     * A city's servers for [tech], or a country's without a city (the API can't look one up by host name), cut down to
     * what a [Server] needs: a few hundred at most per city, some thousands for a whole large country.
     */
    fun inPlace(countryId: Int, cityId: Int?, tech: String): List<Server> = parseServers(get(
        "/servers?" + query(
            (if (cityId != null) "filters[country_city_id]" to "$cityId" else "filters[country_id]" to "$countryId"),
            "filters[servers_technologies][identifier]" to tech, "limit" to "20000",
            *listOf("name", "hostname", "station", "load", "locations.country.code", "locations.country.city.name", "technologies.identifier", "technologies.metadata")
                .map { "fields[servers.$it]" to null }.toTypedArray(),
        ),
    ), tech == "wireguard_udp")

    /**
     * Country codes with servers in a specialty [group]. `/servers/recommendations` is distance-sorted and cuts a long
     * list short (P2P came back as 4 countries); the full `/servers` list with two fields is complete and small.
     */
    fun groupCountries(group: String, tech: String): Set<String> =
        countryCodes(groupServers(group, tech, "locations.country.code", "hostname"))

    internal fun countryCodes(body: String): Set<String> = json.parseToJsonElement(body).jsonArray.flatMap { e ->
        (e.jsonObject["locations"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject["country"]?.jsonObject?.get("code")?.jsonPrimitive?.content }
    }.toSet()

    /**
     * The servers of the account's active Dedicated IP services, as nordvpn-linux does it (daemon/serverpicker/dip.go):
     * `/users/services` lists them (service id 11, `details.servers[].id`), and the ids are looked up in the Dedicated IP group.
     */
    fun dedicated(token: String, tech: String): List<Server> {
        val ids = dedicatedIds(get("/users/services", token))
        if (ids.isEmpty()) return emptyList()
        val all = groupServers(
            "legacy_dedicated_ip", tech, "id", "name", "hostname", "station", "load", "locations.country.code", "locations.country.city.name",
            "technologies.identifier", "technologies.metadata",
        )
        return withIds(all, ids, tech == "wireguard_udp")
    }

    internal fun withIds(body: String, ids: Set<Long>, needKey: Boolean): List<Server> =
        parseServers(JsonArray(json.parseToJsonElement(body).jsonArray.filter { it.jsonObject["id"]?.jsonPrimitive?.long in ids }).toString(), needKey)

    internal fun dedicatedIds(body: String, now: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)): Set<Long> = json.parseToJsonElement(body).jsonArray
        .map { it.jsonObject }
        .filter { s ->
            s["service"]?.jsonObject?.get("id")?.jsonPrimitive?.long == DEDICATED_IP_SERVICE &&
                runCatching { !LocalDateTime.parse(s["expires_at"]!!.jsonPrimitive.content.replace(' ', 'T')).isBefore(now) }.getOrDefault(true)
        }
        .flatMap { s -> (s["details"]?.jsonObject?.get("servers") as? JsonArray).orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.long } }
        .toSet()

    private const val DEDICATED_IP_SERVICE = 11L

    internal fun parseServers(body: String, needKey: Boolean = true): List<Server> = json.parseToJsonElement(body).jsonArray.mapNotNull { e ->
        val o = e.jsonObject
        val key = (o["technologies"] as? JsonArray)?.map { it.jsonObject }
            ?.firstOrNull { it["identifier"]?.jsonPrimitive?.content == "wireguard_udp" }
            ?.get("metadata")?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.content == "public_key" }
            ?.get("value")?.jsonPrimitive?.content ?: if (needKey) return@mapNotNull null else ""
        val where = ((o["locations"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("country")?.jsonObject
        val city = where?.get("city")?.jsonObject?.get("name")?.jsonPrimitive?.content.orEmpty()
        Server(
            name = o["name"]!!.jsonPrimitive.content,
            host = o["hostname"]!!.jsonPrimitive.content,
            ip = o["station"]!!.jsonPrimitive.content,
            publicKey = key,
            load = o["load"]?.jsonPrimitive?.int ?: 0,
            city = city,
            country = where?.get("code")?.jsonPrimitive?.content.orEmpty(),
        )
    }
}
