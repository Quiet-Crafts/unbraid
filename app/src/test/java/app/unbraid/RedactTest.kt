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

    /** Credential shapes that engine, library and provider texts carry; each marker must be gone after cleaning. */
    @Test fun credentialShapesAreHidden() {
        val p = "SyntheticPass7"
        val cases = listOf(
            "Authorization: Bearer $p", "{\"Authorization\":\"Bearer $p\"}", "Bearer $p", "Proxy-Authorization: Basic $p", "X-Api-Key: $p",
            "Cookie: session=$p; path=/", "Set-Cookie: sid=$p; HttpOnly",
            "api_key=$p", "api-key: $p", "apiKey: $p", "secret_key=$p", "private-key=$p", "passphrase = $p", "pass=$p", "passwd=$p", "pwd=$p",
            "refresh_token $p", "access_token $p", "pre_shared_key $p", "{\"psk\":\"$p\"}", "{\"credentials\":\"$p\"}", "{\"secrets\":[\"$p\"]}",
            "login=fakeuser&pass=$p&x=1", "OvpnProfile(user=fakeuser, pass=$p)", "wrong password \"$p\"", "invalid token $p here", "Invalid key $p",
            "ss://aes-256-gcm:$p@192.0.2.9:8388", "socks5://fakeuser:$p@192.0.2.9:1080", "https://fakeuser:Syn@$p@host.example/path", "trojan://$p@h.example:443",
            "Get \"https://fakeuser:$p@host.example/x\": dial tcp", "https://host.example/api?key=$p", "https://192.0.2.20:1337/addKey?pt=$p&pubkey=x",
            "<tls-crypt>\n-----BEGIN OpenVPN Static key V1-----\n$p\n-----END OpenVPN Static key V1-----\n</tls-crypt>", "<secret>\n$p", "-----BEGIN ENCRYPTED PRIVATE KEY-----\n$p",
            "<auth-user-pass>\nfakeuser\n$p\n</auth-user-pass>", "auth-user-pass fakeuser $p",
        )
        for (c in cases) assertFalse(c, Redact.clean(c).contains(p))
        val ids = mapOf(
            "vless://12345678-1234-1234-1234-1234567890ab@h.example:443" to "1234567890ab", "invalid account 1234567890123456" to "1234567890123456",
            "account 1234 5678 9012 3456 not found" to "9012", "unknown account i-SYNT-1234-5678" to "i-SYNT", "login failed for p1234567" to "p1234567",
            "key c0ffeec0ffeec0ffeec0ffeec0ffeeab bad" to "c0ffeec0ffee", "--auth-key=tskey-auth-kSYNTH1CNTRL-AbCd1234" to "AbCd1234",
            "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJTeW50aGV0aWMifQ.SynthSig" to "SynthSig", "rejected SyntheticNordToken0123456789Abcde" to "0123456789",
            "PrivateKey=$key".dropLast(24) + "\n" + key.takeLast(24) to key.takeLast(24),
        )
        for ((c, mark) in ids) assertFalse(c, Redact.clean(c).contains(mark))
    }

    /** A value of any length or quoting is cleaned in one pass, without overflowing the stack. */
    @Test fun hugeQuotedValuesAreCleanedQuickly() {
        for (s in listOf("password=\"" + "\\\"".repeat(500_000), "token='" + "a".repeat(1_000_000), "{\"token\":\"" + "a".repeat(1_000_000) + "\"}", "password=\"".repeat(100_000))) {
            val t0 = System.nanoTime()
            assertFalse(Redact.clean(s).contains("aaaa"))
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000)
        }
    }

    /** Plain words after a name stay: only values that look like credentials go. */
    /**
     * Texts that make a scan read the same stretch again for every name or tag in it take seconds to minutes when the
     * cost grows with the square of the length; one pass takes milliseconds.
     */
    @Test fun repeatedNamesAndTagsCostOnePass() {
        val n = 400_000
        val cases = mapOf(
            "unhidden password names" to "password word ".repeat(n / 14),
            "common names before unclosed brackets" to "key [".repeat(n / 5),
            "common names before unclosed quotes" to "key \"".repeat(n / 5),
            "every escape depth once" to buildString { var d = 1; while (length < n) { append("key "); append("\\".repeat(d++)); append("\" ") } },
            "tags named in prose" to "both <cert> and <key>, or neither ".repeat(n / 34),
            "unclosed tags" to "<tls-auth> block ".repeat(n / 17),
            "unclosed PEM headers" to "-----BEGIN X----- ".repeat(n / 18),
            "token starts in one run" to "eyJ".repeat(n / 3),
            "URL schemes with no login" to "=x://".repeat(n / 5),
        )
        for ((what, s) in cases) {
            val t0 = System.nanoTime()
            Redact.clean(s)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("$what took $ms ms", ms < 3_000)
        }
    }

    /** The app's own messages that name OpenVPN blocks come out whole: they carry no key, only the instruction. */
    @Test fun ownMessagesNamingBlocksStay() {
        for (s in listOf(
            "The profile needs both <cert> and <key>, or neither",
            "tls-auth must be inline (<tls-auth> block), not a file path",
            "tls-crypt must be inline (<tls-crypt> block), not a file path",
            "key must be inline (<key> block), not a file path",
            "PKCS#12 bundles aren't supported; use a .ovpn with inline <ca>, <cert> and <key> blocks",
            "The <key> block is empty or malformed",
            "The <tls-crypt-v2> block is empty or malformed",
            "<extra-certs> needs a <cert> block",
            "That username or password has characters ExpressVPN's manual-configuration login doesn't use",
            "NordVPN didn't accept this token. Create a new one in your Nord Account under NordVPN, manual setup, and paste it here.",
        )) assertEquals(s, Redact.clean(s))
        // Key material after a tag is still hidden, closed or not, on one line or several.
        assertEquals("<key>[hidden] tail", Redact.clean("<key>\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n</key> tail"))
        assertEquals("<tls-auth>[hidden]", Redact.clean("<tls-auth> 6acef03f62675b4b1bbd03e53b187727"))
        assertEquals("<key>[hidden]", Redact.clean("<key>\nabc"))
        assertEquals("both <key>, then <key>[hidden]", Redact.clean("both <key>, then <key> -----BEGIN PRIVATE KEY----- abc"))
    }

    /** URL logins and tokens are still found after the scans were made linear. */
    @Test fun urlLoginsAndTokensStillHidden() {
        assertEquals("proxy https://[hidden]@host.invalid/x", Redact.clean("proxy https://user:p@ss@host.invalid/x"))
        assertEquals("a://[hidden]@h b://[hidden]@h", Redact.clean("a://u:p1@h b://u:p2@h"))
        assertEquals("https://host.invalid/x y@z", Redact.clean("https://host.invalid/x y@z"))
        assertEquals("Bearer [hidden]", Redact.clean("Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln"))
        assertEquals("[hidden] tail", Redact.clean("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln tail"))
    }

    @Test fun ordinaryTextAfterNamesStays() {
        for (s in listOf("Invalid WireGuard key for Sweden", "Your token has expired. Sign in again", "account number has 16 digits", "login failed", "auth-user-pass"))
            assertEquals(s, Redact.clean(s))
    }
}
