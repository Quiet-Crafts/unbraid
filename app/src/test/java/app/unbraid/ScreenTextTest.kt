package app.unbraid

import app.unbraid.data.DIRECT
import app.unbraid.data.Location
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.ui.FOLLOW
import app.unbraid.ui.defaultLabel
import app.unbraid.ui.dropText
import app.unbraid.ui.keepName
import app.unbraid.ui.blockingToApply
import app.unbraid.ui.pendingNote
import app.unbraid.ui.importTarget
import app.unbraid.ui.emptyApps
import app.unbraid.ui.noLocations
import app.unbraid.ui.routeWarning
import app.unbraid.ui.missingAppsText
import app.unbraid.ui.summary
import app.unbraid.ui.pausedLabel
import app.unbraid.ui.serverId
import app.unbraid.data.Server
import app.unbraid.data.WgPeer
import app.unbraid.ui.Fetch
import app.unbraid.data.PREVIOUS
import app.unbraid.data.Profile
import app.unbraid.ui.removeText
import app.unbraid.ui.moveText
import app.unbraid.ui.routeState
import app.unbraid.ui.tickedTile
import app.unbraid.ui.followSub
import app.unbraid.ui.outsideNote
import app.unbraid.data.Filter
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the main screens say and tick, from the setup alone. */
class ScreenTextTest {
    private val nl = Route("nl", Location("nord", 153, "NL", "Netherlands"))
    private val us = Route("us", Location("nord", 228, "US", "United States"))
    private val setup = Setup(routes = listOf(nl, us), defaultRoute = "nl", apps = mapOf("chose.nl" to "nl", "chose.us" to "us", "chose.direct" to DIRECT))

    /** An app that chose the default is told apart from one that follows it, in the menu and for TalkBack. */
    @Test fun chosenAndFollowingAppsAreToldApart() {
        assertEquals(FOLLOW, tickedTile(setup, "follows"))
        assertEquals("nl", tickedTile(setup, "chose.nl"))
        assertEquals("us", tickedTile(setup, "chose.us"))
        assertEquals("Netherlands, follows default", routeState(setup, "follows"))
        assertEquals("Netherlands, chosen", routeState(setup, "chose.nl"))
        assertEquals("Direct, chosen", routeState(setup, "chose.direct"))
        assertEquals("A route that no longer exists, chosen", routeState(setup.copy(apps = mapOf("x" to "gone")), "x"))
    }

    @Test fun defaultCardCountsChosenApart() {
        assertEquals("All other apps", defaultLabel(null, 0)) // the app list isn't read yet: no made-up 0
        assertEquals("All other apps · 12", defaultLabel(12, 0))
        assertEquals("12 apps · 2 chosen", defaultLabel(12, 2))
        assertEquals("1 app · 1 chosen", defaultLabel(1, 1))
    }

    /** Removing a route says what is lost before it goes. */
    @Test fun removalSaysWhatIsLost() {
        assertEquals("No app chose it.", removeText(0, emptyList()))
        assertEquals(
            "2 apps chose it and follow the default route from now on. It also goes from Work, Trip. You can add the route again, but not those choices.",
            removeText(2, listOf("Work", "Trip")),
        )
        assertEquals("No app in Work chose it. The route stays for other profiles.", dropText("Work", 0))
        assertEquals("No app in Work chose it. No other profile uses it.", dropText("Work", 0, elsewhere = false))
        assertEquals("In Work, 1 app that chose it follows Work's default route from now on. The route stays for other profiles.", dropText("Work", 1))
    }

    /** "Save your current setup first?": blank keeps it as Previous; a taken name or the target itself is refused with a reason. */
    @Test fun setupKeptBeforeASwitch() {
        val s = setup.copy(profiles = listOf(Profile("Work", "us"), Profile(PREVIOUS, "us")))
        assertEquals(PREVIOUS to null, keepName(s, "  ", "Work"))
        assertEquals("Home" to null, keepName(s, " Home ", "Work"))
        assertEquals(null, keepName(s, "work", "Work").first)
        assertEquals(null, keepName(s, "", PREVIOUS).first)
        // What the switch then does with that name: the setup in use is kept and the target is in use.
        val after = s.use("Work", keepName(s, "Home", "Work").first)
        assertEquals("Work", after.profile)
        assertEquals(setup.apps, after.profiles.first { it.name == "Home" }.apps)
    }

    /** Picker: protocol and blocking wait for a location tap, which applies only a real change; Close drops them. */
    @Test fun pickerAppliesPendingChoicesOnlyOnATap() {
        assertEquals(null, blockingToApply(null, true)) // nothing chosen here
        assertEquals(null, blockingToApply(true, true)) // chosen back to what the route has
        assertEquals(false, blockingToApply(false, true))
        assertEquals(null, pendingNote(null, null))
        assertEquals("OpenVPN TCP: tap a location to use it. Close keeps the route as it is.", pendingNote("OpenVPN TCP", null))
        assertEquals(
            "OpenVPN TCP and NordVPN blocking off: tap a location to use them. Close keeps the route as it is.",
            pendingNote("OpenVPN TCP", "NordVPN" to false),
        )
    }

    /** A file imported from Add provider, opened in an app's or a route's picker, goes to that app or route. */
    @Test fun importFromThePickerKeepsItsTarget() {
        assertEquals("app:org.mozilla.firefox", importTarget("app:org.mozilla.firefox", setup))
        assertEquals("us", importTarget("us", setup))
        assertEquals("", importTarget("default", setup))
        assertEquals("", importTarget("", setup))
        assertEquals("", importTarget(null, setup))
    }

    /** An empty Apps list says why and offers the way back, instead of "No apps match". */
    @Test fun emptyAppsListSaysWhy() {
        assertEquals("2 system apps match. System apps are hidden." to "Show system apps", emptyApps("play", null, 2))
        assertEquals("1 system app matches. System apps are hidden." to "Show system apps", emptyApps("webview", "Direct", 1))
        assertEquals("No apps use Direct." to "Show all", emptyApps("", "Direct", 0))
        assertEquals("No apps on Direct match \"chr\"." to "Show all", emptyApps("chr", "Direct", 0))
        assertEquals("No apps match \"chr\"." to null, emptyApps("chr", null, 0))
    }

    /** The picker says it is loading, or why the list failed (with Retry), or that a server type narrowed it to nothing. */
    @Test fun emptyPickerSaysWhy() {
        assertEquals("Loading locations…" to false, noLocations(false, null, "Standard"))
        assertEquals("Loading locations…" to false, noLocations(false, Fetch.Loading, "Standard"))
        assertEquals("NordVPN didn't answer." to true, noLocations(false, Fetch.Failed("NordVPN didn't answer."), "Standard"))
        assertEquals("No country offers P2P right now. Choose another server type in Route options." to false, noLocations(true, null, "P2P"))
    }

    @Test fun routeCardWarnings() {
        assertEquals("Doesn't work on this network", routeWarning(stalled = true, notAnswering = true))
        assertEquals("Not answering", routeWarning(stalled = false, notAnswering = true))
        assertEquals(null, routeWarning(stalled = false, notAnswering = false))
        assertEquals("1 app uses a route that no longer exists, so it's offline. Choose a route for it.", missingAppsText(1))
        assertEquals("3 apps use a route that no longer exists, so they're offline. Choose a route for them.", missingAppsText(3))
    }

    /** A profile's summary counts apps on other routes, not routes, and names a default that is gone. */
    @Test fun profileSummaryCountsApps() {
        assertEquals("Netherlands · 2 apps elsewhere", summary(setup, Profile("Work", "nl", setup.apps)))
        assertEquals("United States · 1 app elsewhere", summary(setup, Profile("Work", "us", mapOf("a" to "us", "b" to "nl"))))
        assertEquals("Direct · no apps elsewhere", summary(setup, Profile("Work", DIRECT)))
        assertEquals("Default route no longer exists · no apps elsewhere", summary(setup, Profile("Work", "gone")))
    }

    /** The pause's end leads the status line, so a narrow screen cuts nothing that matters. */
    @Test fun pauseLineLeadsWithTheTime() {
        assertEquals("Resumes about 1:00 AM", pausedLabel("1:00 AM", about = true))
        assertEquals("Resumes at 13:00", pausedLabel("13:00", about = false))
        assertEquals("Paused", pausedLabel(null, about = false))
    }

    /** An imported server is not labelled like a provider's ("192 · 0% load"): its route's name says it all. */
    @Test fun ownServerHasNoProviderServerLabel() {
        val live = Server("192.0.2.1", "192.0.2.1", "192.0.2.1", "", 0)
        val wg = Route("w", wg = WgPeer("Home VPS", "k", listOf("10.0.0.2/32"), "p", endpoint = "192.0.2.1:51820", allowedIps = listOf("0.0.0.0/0")))
        assertEquals(null, serverId(wg, live))
        assertEquals("nl1256 · 9% load", serverId(nl, Server("nl1256", "nl1256.nordvpn.com", "", "", 9)))
    }

    @Test fun newDefaultSaysWhoMoves() {
        assertEquals("12 apps that follow the default will use Direct (no VPN).", moveText(12, null))
        assertEquals("1 app that follows the default will move to Italy.", moveText(1, "Italy"))
    }

    /** A chosen Direct app is said to be outside the app (not under lockdown); a Direct default still runs through it. */
    @Test fun directOutsideIsToldApart() {
        val direct = setup.copy(defaultRoute = DIRECT)
        assertEquals("Direct, through unbraid", followSub(direct))
        assertEquals("Netherlands", followSub(setup))
        val note = "Outside unbraid: no filter, no home network"
        assertEquals(note, outsideNote(direct, "chose.direct", lockdown = false))
        assertEquals(null, outsideNote(direct, "chose.direct", lockdown = true))
        assertEquals(null, outsideNote(direct, "follows", lockdown = false))
        // Passed through for the filter: in the app, so not outside.
        val through = direct.copy(prefs = direct.prefs.copy(filter = Filter(id = "nextdns", value = "abc123", directApps = true)))
        assertEquals(null, outsideNote(through, "chose.direct", lockdown = false))
    }
}
