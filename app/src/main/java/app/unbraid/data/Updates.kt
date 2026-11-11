package app.unbraid.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL as NetURL

@Serializable
data class Release(val versionCode: Int, val versionName: String)

@Serializable
data class UpdateState(
    val enabled: Boolean = false, val lastAttemptMs: Long = 0, val dismissedCode: Int = 0, val latest: Release? = null,
    /** Whether the user has been asked to turn checks on, at welcome or later. */
    val asked: Boolean = false,
)

object Updates {
    const val URL = "https://unbraid.app/version/android.json"
    const val DOWNLOAD = "https://github.com/quiet-crafts/unbraid/releases/latest"
    /** The newest known release if it is newer than [installed], else null. */
    fun newer(s: UpdateState, installed: Int): Release? = s.latest?.takeIf { it.versionCode > installed }
    /** Whether the Routes card shows: a newer release that wasn't dismissed. */
    fun showCard(s: UpdateState, installed: Int): Boolean = newer(s, installed)?.let { it.versionCode > s.dismissedCode } ?: false
    private val mutex = Mutex()
    fun parse(text: String): Release? = runCatching {
        val obj = Json.parseToJsonElement(text) as? JsonObject ?: return null
        val code = obj["versionCode"] as? JsonPrimitive ?: return null
        val name = obj["versionName"] as? JsonPrimitive ?: return null
        if (code.isString || !name.isString) return null
        val n = code.intOrNull ?: return null
        val v = name.content
        if (n <= 0 || !v.matches(Regex("""\d{1,3}(\.\d{1,3}){1,3}"""))) null else Release(n, v)
    }.getOrNull()

    fun due(s: UpdateState, now: Long) = s.enabled && (now < s.lastAttemptMs || now - s.lastAttemptMs >= 86_400_000L)

    private suspend fun fetchLatest(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = NetURL(URL).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 5_000; c.readTimeout = 5_000
                c.useCaches = false
                if (c.responseCode != 200) return@runCatching null
                val bytes = c.inputStream.use { it.readNBytes(4097) }
                if (bytes.size > 4096) null else bytes.toString(Charsets.UTF_8)
            } finally { c.disconnect() }
        }.getOrNull()
    }

    suspend fun check(store: Store, installed: Int, now: Long = System.currentTimeMillis(), fetch: suspend () -> String? = { fetchLatest() }): UpdateState = mutex.withLock {
        withContext(Dispatchers.IO) {
            synchronized(store) {
                val initial = store.updates
                if (due(initial, now)) initial.copy(lastAttemptMs = now).also { store.updates = it } else null
            } ?: return@withContext store.updates
            val release = try { fetch()?.takeIf { it.toByteArray(Charsets.UTF_8).size <= 4096 }?.let(::parse) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            synchronized(store) {
                val current = store.updates
                if (!current.enabled) current else current.copy(latest = release ?: current.latest).also { store.updates = it }
            }
        }
    }
}
