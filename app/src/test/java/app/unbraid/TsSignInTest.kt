package app.unbraid

import app.unbraid.data.Store
import app.unbraid.ui.tailscaleSub
import app.unbraid.ui.tsBrowserSub
import app.unbraid.ui.tsKeySlow
import app.unbraid.ui.tsServerDropsRoutes
import app.unbraid.ui.tsServerName
import app.unbraid.ui.tsSignedIn
import app.unbraid.ui.tsShownSignIn
import app.unbraid.vpn.TsLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

/** Issue 7: signing in to Tailscale or an own server, with the browser or an auth key, and saying which. */
class TsSignInTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The way just tried wins; with none (an older sign-in, or the app closed meanwhile) a saved key means the key. */
    @Test fun theMethodIsDecidedOnceTheNodeRuns() {
        assertEquals(TsLogin.BROWSER, TsLogin.signIn("Running", null, TsLogin.BROWSER, keySaved = true))
        assertEquals(TsLogin.KEY, TsLogin.signIn("Running", null, TsLogin.KEY, keySaved = false))
        assertEquals(TsLogin.KEY, TsLogin.signIn("Running", null, null, keySaved = true))
        assertEquals(TsLogin.BROWSER, TsLogin.signIn("Running", null, null, keySaved = false))
        // Decided once: a later run never changes it.
        assertEquals(TsLogin.KEY, TsLogin.signIn("Running", TsLogin.KEY, TsLogin.BROWSER, keySaved = false))
        // Seen asking to sign in, then running: decided afresh.
        assertEquals(TsLogin.BROWSER, TsLogin.signIn("Running", TsLogin.NONE, TsLogin.BROWSER, keySaved = true))
    }

    /** Needing to sign in again forgets it; starting, stopping or waiting for approval keeps what was known. */
    @Test fun theMethodIsForgottenOnlyWhenSignInIsNeeded() {
        assertEquals(TsLogin.NONE, TsLogin.signIn("NeedsLogin", TsLogin.KEY, null, keySaved = true))
        for (s in listOf(null, "", "NoState", "Starting", "Stopped", "NeedsMachineAuth")) {
            assertEquals(s, TsLogin.KEY, TsLogin.signIn(s, TsLogin.KEY, TsLogin.BROWSER, keySaved = false))
            assertNull(s, TsLogin.signIn(s, null, TsLogin.KEY, keySaved = true))
        }
    }

    /** Kept with the node: every reset of the node (sign-out, new server or key) forgets it too. */
    @Test fun theSavedMethodGoesWithTheNode() {
        val dir = tmp.newFolder()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val s = Store(dir) { key }
        s.tsSignIn = TsLogin.KEY
        assertNull(s.tsSignIn) // no node yet: nothing to describe
        File(dir, "tailscale").mkdir()
        s.tsState = "{\"node\":1}"
        s.tsSignIn = TsLogin.KEY
        assertEquals(TsLogin.KEY, Store(dir) { key }.tsSignIn)
        File(dir, "tailscale").deleteRecursively()
        assertNull(s.tsSignIn)
        File(dir, "tailscale").mkdir()
        s.tsSignIn = TsLogin.BROWSER
        s.tsSignIn = null
        assertNull(s.tsSignIn)
    }

    /**
     * Review finding 1: a phone signed in on an older version has a node but no saved method. It shows as signed in (how
     * unknown), never as signed out: the sign-in rows there would delete a node that works.
     */
    @Test fun anOlderSignInShowsAsSignedIn() {
        assertEquals("", tsShownSignIn(stored = true, saved = null, asksNow = false))
        assertEquals("Signed in" to "Tailscale", tsSignedIn("", null))
        assertEquals(TsLogin.KEY, tsShownSignIn(stored = true, saved = TsLogin.KEY, asksNow = false))
        // Not signed in: no node, a node seen asking, or one asking right now.
        assertNull(tsShownSignIn(stored = false, saved = null, asksNow = false))
        assertNull(tsShownSignIn(stored = true, saved = TsLogin.NONE, asksNow = false))
        assertNull(tsShownSignIn(stored = true, saved = TsLogin.KEY, asksNow = true))
        assertNull(tsShownSignIn(stored = true, saved = null, asksNow = true))
    }

    @Test fun rowsNameTheServerAndWhatATapDoes() {
        assertEquals("Tailscale", tsServerName(null))
        assertEquals("headscale.example.com", tsServerName("https://headscale.example.com:8443/"))
        assertEquals("Signed in with an auth key" to "Tailscale · the key was erased after sign-in", tsSignedIn(TsLogin.KEY, null))
        assertEquals("Signed in with your browser" to "headscale.example.com", tsSignedIn(TsLogin.BROWSER, "https://headscale.example.com"))
        assertEquals("Turns on unbraid, then opens the sign-in page", tsBrowserSub(null, off = true, waiting = false))
        assertEquals("Getting the sign-in page…", tsBrowserSub(null, off = true, waiting = true))
        assertEquals("Opens the sign-in page of Tailscale", tsBrowserSub(null, off = false, waiting = false))
        assertEquals("Follow the steps on hs.example.org. Whoever runs it may need to finish it.", tsBrowserSub("https://hs.example.org", off = false, waiting = false))
    }

    /** A key saved while unbraid is off says so; once connected the engine's own state speaks again. */
    @Test fun aKeySavedWhileOffSaysSo() {
        assertEquals("Key saved · turn on unbraid to sign in", tailscaleSub(enabled = true, connected = false, state = null, keySaved = true))
        assertEquals("Connects with unbraid", tailscaleSub(enabled = true, connected = false, state = null))
        assertEquals("Sign-in needed", tailscaleSub(enabled = true, connected = true, state = "NeedsLogin", keySaved = true))
        assertEquals("Waiting for your administrator to approve this phone", tailscaleSub(enabled = true, connected = true, state = "NeedsMachineAuth"))
        assertEquals("Reach your tailnet from every app", tailscaleSub(enabled = false, connected = false, state = null, keySaved = true))
    }

    /** The 20 s notice is for a key try that still asks to sign in: nothing else. */
    @Test fun onlyAKeyThatStillAsksIsSlow() {
        assertEquals(true, tsKeySlow(TsLogin.KEY, true, "NeedsLogin"))
        assertEquals(false, tsKeySlow(TsLogin.BROWSER, true, "NeedsLogin"))
        assertEquals(false, tsKeySlow(null, true, "NeedsLogin"))
        assertEquals(false, tsKeySlow(TsLogin.KEY, false, "NeedsLogin"))
        for (s in listOf(null, "NoState", "Starting", "NeedsMachineAuth", "Running")) assertEquals(s, false, tsKeySlow(TsLogin.KEY, true, s))
    }

    /** A key try is not cleared on NeedsLogin, so it still decides the method once the node runs. */
    @Test fun aKeyTryOutlivesNeedsLogin() {
        assertEquals(TsLogin.NONE, TsLogin.signIn("NeedsLogin", null, TsLogin.KEY, keySaved = false))
        assertEquals(TsLogin.KEY, TsLogin.signIn("Running", TsLogin.NONE, TsLogin.KEY, keySaved = false))
        assertEquals(TsLogin.KEY, TsLogin.signIn("Running", null, TsLogin.KEY, keySaved = false))
    }

    /** Only a real change of server, with exit routes of the old one, drops routes; a key change on the same server keeps them. */
    @Test fun onlyARealServerChangeDropsRoutes() {
        val routes = setOf("r1")
        assertEquals(true, tsServerDropsRoutes("https://hs.example.com", null, routes))
        assertEquals(true, tsServerDropsRoutes(null, "https://hs.example.com", routes))
        assertEquals(false, tsServerDropsRoutes("https://hs.example.com", "https://hs.example.com", routes))
        assertEquals(false, tsServerDropsRoutes(null, null, routes))
        assertEquals(false, tsServerDropsRoutes("https://hs.example.com", null, emptySet()))
    }
}
