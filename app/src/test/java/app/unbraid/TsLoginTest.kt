package app.unbraid

import app.unbraid.vpn.TsLogin
import app.unbraid.vpn.TsLogin.Verdict.ASK
import app.unbraid.vpn.TsLogin.Verdict.OPEN
import app.unbraid.vpn.TsLogin.Verdict.REFUSE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TsLoginTest {
    private fun v(url: String, control: String? = null) = TsLogin.check(url, control).verdict

    @Test fun tailscaleLinksOpenWithTheDefaultServer() {
        assertEquals(OPEN, v("https://login.tailscale.com/a/1a2b3c"))
        assertEquals(OPEN, v("https://LOGIN.Tailscale.com./a/1a2b3c"))
        assertEquals(OPEN, v("https://login.tailscale.com/a/x", "https://controlplane.tailscale.com"))
        assertEquals(OPEN, v("https://login.tailscale.com/a/x", "  "))
    }

    @Test fun headscaleLinksOpenOnlyOnItsOwnHost() {
        assertEquals(OPEN, v("https://hs.example.net/register/nodekey:abc", "https://hs.example.net"))
        assertEquals(OPEN, v("https://hs.example.net:8443/oidc/register/x", "https://hs.example.net:8443/"))
        assertEquals(ASK, v("https://login.tailscale.com/a/x", "https://hs.example.net"))
        assertEquals(ASK, v("https://idp.example.org/auth", "https://hs.example.net"))
        assertEquals(ASK, v("https://login.tailscale.com/a/x", "https://other.tailscale.com"))
    }

    @Test fun anUnreadableCustomServerTrustsNothing() {
        assertEquals(ASK, v("https://login.tailscale.com/a/x", "hs example net"))
        assertEquals(ASK, v("https://login.tailscale.com/a/x", "not-a-url"))
    }

    @Test fun lookalikeHostsAsk() {
        assertEquals(ASK, v("https://login.tailscale.com.evil.example/a/x"))
        assertEquals(ASK, v("https://eviltailscale.com/a/x"))
        assertEquals("evil.example", TsLogin.check("https://evil.example/a", null).host)
    }

    @Test fun theLaunchedLinkIsTheCleanedOne() {
        val r = TsLogin.check("  HTTPS://login.tailscale.com/a/x?y=1  ", null)
        assertEquals(OPEN, r.verdict)
        assertEquals("https://login.tailscale.com/a/x?y=1", r.url)
    }

    @Test fun nonWebOrMalformedLinksNeverOpen() {
        for (u in listOf(
            "http://login.tailscale.com/a/x", "intent://scan/#Intent;scheme=zxing;package=com.example;end",
            "javascript:alert(1)", "market://details?id=x", "https://user:pw@login.tailscale.com/a/x",
            "https:///a/x", "not a url", "https://login.tailscale.com:65536/a", "https://login.tailscale.com:0/a",
        )) {
            val r = TsLogin.check(u, null)
            assertEquals(u, REFUSE, r.verdict)
            assertNull(u, r.url)
        }
        assertEquals(OPEN, v("https://login.tailscale.com:443/a"))
        assertEquals(OPEN, v("https://login.tailscale.com:1/a"))
        assertEquals(OPEN, v("https://login.tailscale.com:65535/a"))
        // Internationalised hosts fail closed (java.net.URI gives no host); their punycode form works.
        assertEquals(REFUSE, v("https://bücher.example/auth", "https://bücher.example"))
        assertEquals(OPEN, v("https://xn--bcher-kva.example/auth", "https://xn--bcher-kva.example"))
    }
}
