package app.unbraid

import app.unbraid.data.Http
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLConnection
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/** A connection that answers [code] with [body] and records what the client asked of it. */
class FakeHttps(url: URL, private val code: Int, private val body: String = "{}") : HttpsURLConnection(url) {
    val headers = mutableMapOf<String, String>()
    override fun getResponseCode() = code
    override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
    override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
    override fun getOutputStream() = ByteArrayOutputStream()
    override fun setRequestProperty(key: String, value: String?) { headers[key] = value.orEmpty() }
    override fun connect() {}
    override fun disconnect() {}
    override fun usingProxy() = false
    override fun getCipherSuite() = ""
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
}

class HttpTest {
    private val opened = mutableListOf<FakeHttps>()
    private val original = Http.open

    private fun answer(code: Int, body: String = "{}") {
        Http.open = { u: URL -> FakeHttps(u, code, body).also { opened += it } as URLConnection }
    }

    @After fun restore() { Http.open = original }

    /** A redirect is never followed, so a credential header can't travel to the host it names. */
    @Test fun redirectsAreNotFollowed() {
        answer(302)
        try { Http.get("https://api.example/x", mapOf("API-KEY" to "k")); fail() } catch (e: Http.Status) { assertEquals(302, e.code) }
        assertFalse(opened.single().instanceFollowRedirects)
        assertEquals(1, opened.size)
    }

    @Test fun ordinaryRepliesStillWork() {
        answer(200, "ok")
        assertEquals("ok", Http.get("https://api.example/x"))
        assertFalse(opened.single().instanceFollowRedirects)
    }

    @Test fun jsonPostsAndNordCallsDoNotFollowRedirectsEither() {
        answer(307)
        assertEquals(307, app.unbraid.data.postJsonRaw("https://api.example/x", "{}", mapOf("Authorization" to "Bearer t")).first)
        try { app.unbraid.data.NordApi.countries(); fail() } catch (e: Http.Status) { assertEquals(307, e.code) }
        assertEquals(2, opened.size)
        assertTrue(opened.none { it.instanceFollowRedirects })
    }
}
