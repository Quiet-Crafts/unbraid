package app.unbraid.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/** Minimal blocking HTTPS client shared by providers: size-capped bodies, one retry on a transient DNS failure. */
object Http {
    private const val MAX_BODY = 8 shl 20

    class Status(val code: Int, message: String) : IOException(message)

    /** A provider turned down the credentials a request carried. */
    class Refused(message: String = "Sign-in was refused") : AuthException(message)

    /**
     * Runs a sign-in's first request with what the user entered. A refusal, or a 400, 401 or 403 to it, means the
     * provider didn't accept what was entered, so the user is told [message] (and pointed to [url]), never to try
     * again later; server errors and network failures pass through unchanged.
     */
    fun <T> signingIn(message: String, url: String? = null, f: () -> T): T = try { f() } catch (e: Refused) {
        throw AuthException(message, url)
    } catch (e: Status) {
        if (e.code == 400 || e.code == 401 || e.code == 403) throw AuthException(message, url) else throw e
    }

    /** Opens a connection to a URL; tests put a fake one here. */
    internal var open: (URL) -> URLConnection = { it.openConnection() }

    /**
     * [signed]: the request carries the account's credentials (in a header by default), so a 401 means they were
     * refused. A 401 to a request without them, such as a public server list, is an ordinary failed request.
     */
    fun get(url: String, headers: Map<String, String> = emptyMap(), tls: SSLSocketFactory? = null, verifyHost: String? = null, signed: Boolean = headers.isNotEmpty()) =
        call("GET", url, headers, null, null, tls, verifyHost, signed)

    fun delete(url: String, headers: Map<String, String> = emptyMap()) = call("DELETE", url, headers, null, null, null, null, true)

    /**
     * Parses a provider reply. kotlinx parse errors quote the input, which may hold tokens or keys, so they are
     * replaced by a fixed message with no cause.
     */
    fun <T> parsed(what: String, f: () -> T): T = try { f() } catch (e: kotlinx.serialization.SerializationException) {
        throw app.unbraid.vpn.Fail("Unexpected reply from $what. Try again later")
    }

    /** What a failed sign-in at [provider] tells the user: the reason when the app knows it, never a reply's own text. */
    fun explain(e: Throwable, provider: String): String = when (e) {
        is IllegalArgumentException, is AuthException, is app.unbraid.vpn.Fail -> app.unbraid.vpn.Redact.clean(e.message).ifBlank { "$provider didn't accept these details" }
        is Status -> if (e.code == 429) "$provider has had too many attempts. Wait a few minutes and try again" else "$provider couldn't sign you in right now (error ${e.code}). Try again later"
        is IOException -> "Couldn't reach $provider. Check the connection."
        else -> "Something went wrong signing in to $provider. Try again"
    }

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()) =
        call("POST", url, headers, form.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }, "application/x-www-form-urlencoded", null, null, true)

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * [tls] replaces the trust store (e.g. a provider's pinned CA); [verifyHost] checks the certificate against that
     * name instead of the URL host (needed when connecting to a server by IP).
     */
    private fun call(method: String, url: String, headers: Map<String, String>, body: String?, type: String?, tls: SSLSocketFactory?, verifyHost: String?, signed: Boolean): String =
        try { once(method, url, headers, body, type, tls, verifyHost, signed) } catch (e: UnknownHostException) { Thread.sleep(1500); once(method, url, headers, body, type, tls, verifyHost, signed) }

    private fun once(method: String, url: String, headers: Map<String, String>, body: String?, type: String?, tls: SSLSocketFactory?, verifyHost: String?, signed: Boolean): String {
        val c = open(URL(url)) as HttpURLConnection
        require(c is HttpsURLConnection) { "HTTPS only" }
        // A redirect would take every header along, an API key among them, to whatever host it names; none of the
        // provider APIs needs one, so a 3xx is an error like any other status.
        c.instanceFollowRedirects = false
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
            if (code == 401 && signed) throw Refused()
            if (code !in 200..299) throw Status(code, "HTTP $code")
            return c.inputStream.use { String(it.readNBytes(MAX_BODY + 1).also { b -> if (b.size > MAX_BODY) throw IOException("Response too large") }) }
        } finally {
            c.disconnect()
        }
    }
}
