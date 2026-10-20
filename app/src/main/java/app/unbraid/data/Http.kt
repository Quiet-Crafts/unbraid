package app.unbraid.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/** Minimal blocking HTTPS client shared by providers: size-capped bodies, one retry on a transient DNS failure. */
object Http {
    private const val MAX_BODY = 8 shl 20

    class Status(val code: Int, message: String) : IOException(message)

    fun get(url: String, headers: Map<String, String> = emptyMap(), tls: SSLSocketFactory? = null, verifyHost: String? = null) =
        call("GET", url, headers, null, null, tls, verifyHost)

    fun delete(url: String, headers: Map<String, String> = emptyMap()) = call("DELETE", url, headers, null, null, null, null)

    /**
     * Parses a provider reply. kotlinx parse errors quote the input, which may hold tokens or keys, so they are
     * replaced by a fixed message with no cause.
     */
    fun <T> parsed(what: String, f: () -> T): T = try { f() } catch (e: kotlinx.serialization.SerializationException) {
        throw IOException("Unexpected reply from $what")
    }

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()) =
        call("POST", url, headers, form.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }, "application/x-www-form-urlencoded", null, null)

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * [tls] replaces the trust store (e.g. a provider's pinned CA); [verifyHost] checks the certificate against that
     * name instead of the URL host (needed when connecting to a server by IP).
     */
    private fun call(method: String, url: String, headers: Map<String, String>, body: String?, type: String?, tls: SSLSocketFactory?, verifyHost: String?): String =
        try { once(method, url, headers, body, type, tls, verifyHost) } catch (e: UnknownHostException) { Thread.sleep(1500); once(method, url, headers, body, type, tls, verifyHost) }

    private fun once(method: String, url: String, headers: Map<String, String>, body: String?, type: String?, tls: SSLSocketFactory?, verifyHost: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        require(c is HttpsURLConnection) { "HTTPS only" }
        tls?.let { c.sslSocketFactory = it }
        verifyHost?.let { h -> c.setHostnameVerifier { _, session -> javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(h, session) } }
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/json")
        headers.forEach(c::setRequestProperty)
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", type)
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            if (code == 401) throw AuthException("Sign-in was refused")
            if (code !in 200..299) throw Status(code, "HTTP $code")
            return c.inputStream.use { String(it.readNBytes(MAX_BODY + 1).also { b -> if (b.size > MAX_BODY) throw IOException("Response too large") }) }
        } finally {
            c.disconnect()
        }
    }
}
