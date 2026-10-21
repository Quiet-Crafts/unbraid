package app.unbraid

import app.unbraid.data.AuthException
import app.unbraid.vpn.Fail
import app.unbraid.vpn.Redact
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RedactTest {
    private val key = "aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8tMTI="

    /** Sign-in failures say what happened, in the app's own words; a provider's reply text never reaches the screen. */
    @Test fun signInFailuresAreExplained() {
        fun x(e: Throwable) = app.unbraid.data.Http.explain(e, "Mullvad")
        assertEquals("Mullvad has had too many attempts. Wait a few minutes and try again", x(app.unbraid.data.Http.Status(429, "HTTP 429")))
        assertEquals("Mullvad couldn't sign you in right now (error 503). Try again later", x(app.unbraid.data.Http.Status(503, "token=abcdef secret body")))
        assertEquals("Couldn't reach Mullvad. Check the connection.", x(java.net.UnknownHostException("api.mullvad.net")))
        assertEquals("Couldn't reach Mullvad. Check the connection.", x(java.net.SocketTimeoutException("timeout")))
        assertEquals("Sign-in was refused", x(app.unbraid.data.AuthException("Sign-in was refused")))
        val parse = runCatching { app.unbraid.data.Http.parsed("Mullvad") { kotlinx.serialization.json.Json.decodeFromString<Map<String, Int>>("{\"token\":\"secret-value\"}") } }.exceptionOrNull()!!
        assertEquals("Unexpected reply from Mullvad. Try again later", x(parse))
        assertEquals("Something went wrong signing in to Mullvad. Try again", x(NullPointerException("o[k] secret-value")))
        assertEquals("That account number isn't valid", x(IllegalArgumentException("That account number isn't valid")))
    }

    @Test fun keysAndConfigFragmentsAreRemoved() {
        val raw = "decode config: {\"type\":\"wireguard\",\"private_key\":\"$key\",\"peers\":[{\"public_key\":\"$key\"}]} password=hunter2"
        val out = Redact.clean(raw)
        assertFalse(out.contains(key))
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("private_key\":\"a"))
        assertTrue(out.startsWith("decode config"))
    }

    @Test fun quotedSecretsGoWhole() {
        val out = Redact.clean("auth failed: password=\"alpha beta gamma\" for user x")
        assertFalse(out.contains("beta"))
        assertFalse(out.contains("gamma"))
        assertTrue(out.endsWith("for user x"))
    }

    @Test fun escapedQuotesDontEndTheSecret() {
        val out = Redact.clean("bad: password=\"alpha\\\"bravo\\\\charlie\" ok")
        assertFalse(out.contains("bravo"))
        assertFalse(out.contains("charlie"))
        assertTrue(out.endsWith(" ok"))
    }

    @Test fun longBlobsGoButOrdinaryWordsStay() {
        assertEquals("[hidden]", Redact.clean("a".repeat(40)))
        assertEquals("Sign in to Mullvad for Sweden", Redact.clean("Sign in to Mullvad for Sweden"))
    }

    @Test fun outputIsShortAndSingleLine() {
        val out = Redact.clean("line one\nline two " + "word ".repeat(100))
        assertTrue(out.length <= 160)
        assertFalse(out.contains('\n'))
    }

    @Test fun messageChoosesAFixedTextForUnknownClasses() {
        assertEquals("Sign in to Nord", Redact.message(AuthException("Sign in to Nord"), "x"))
        assertEquals("Netherlands: de1 is unreachable", Redact.message(Fail("Netherlands: de1 is unreachable"), "x"))
        assertEquals("Invalid WireGuard key for Sweden", Redact.message(IllegalArgumentException("Invalid WireGuard key for Sweden"), "x"))
        assertEquals("fallback", Redact.message(SerializationException("Unexpected JSON token near \"private_key\":\"$key\""), "fallback"))
        assertEquals("fallback", Redact.message(RuntimeException("private_key=$key"), "fallback"))
        assertEquals("Couldn't reach the server. Check the connection.", Redact.message(IOException("GET https://x/?token=$key"), "fallback"))
    }
}
