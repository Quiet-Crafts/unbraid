package app.unbraid

import app.unbraid.data.AirVpn
import app.unbraid.data.Http
import app.unbraid.data.Mullvad
import app.unbraid.data.Nord
import app.unbraid.data.Pia
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URL
import java.net.URLConnection

/** What the sign-in form says when a provider answers its first request with an error status. */
class SignInErrorsTest {
    private fun shown(code: Int, provider: String, signIn: () -> Unit): String {
        val original = Http.open
        Http.open = { u: URL -> FakeHttps(u, code, "{}") as URLConnection }
        return try { signIn(); "accepted" } catch (e: Exception) { Http.explain(e, provider) } finally { Http.open = original }
    }

    private val nord = { Nord.signIn(mapOf("token" to "a".repeat(64))); Unit }

    @Test fun aRefusedTokenSaysSoAndWhatToDo() {
        for (code in listOf(400, 401, 403)) assertEquals("$code", Nord.TOKEN_REFUSED, shown(code, "NordVPN", nord))
        assertEquals(
            "NordVPN didn't accept this token. Create a new one in your Nord Account under NordVPN, manual setup, and paste it here.",
            Nord.TOKEN_REFUSED,
        )
    }

    @Test fun serverTroubleStillSaysTryAgainLater() {
        assertEquals("NordVPN couldn't sign you in right now (error 503). Try again later", shown(503, "NordVPN", nord))
        assertEquals("NordVPN has had too many attempts. Wait a few minutes and try again", shown(429, "NordVPN", nord))
    }

    @Test fun otherProvidersSayTheDetailsWereNotAccepted() {
        for (code in listOf(400, 401, 403)) {
            assertEquals("PIA refused that username or password", shown(code, "PIA") { Pia.signIn(mapOf("username" to "p1234567", "password" to "x")) })
            val api = AirVpn.api
            AirVpn.api = { _, _, _ -> if (code == 401) throw Http.Refused() else throw Http.Status(code, "HTTP $code") }
            try {
                assertEquals("AirVPN didn't accept that API key. Check it in the Client Area under API", shown(code, "AirVPN") { AirVpn.signIn(mapOf("key" to "a".repeat(64))) })
            } finally { AirVpn.api = api }
            assertEquals("Mullvad doesn't recognise that account number", shown(code, "Mullvad") { Mullvad.signIn(mapOf("account" to "1234567890123456")) })
        }
        assertEquals("PIA couldn't sign you in right now (error 500). Try again later", shown(500, "PIA") { Pia.signIn(mapOf("username" to "p1234567", "password" to "x")) })
    }
}
