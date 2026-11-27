package app.unbraid

import app.unbraid.data.DIRECT
import app.unbraid.data.Ivpn
import app.unbraid.data.NordApi
import app.unbraid.ui.ivpnKeyNote
import app.unbraid.ui.viaLabels
import app.unbraid.ui.viaEntries
import app.unbraid.ui.ivpnKeyPending
import app.unbraid.vpn.VpnState
import app.unbraid.data.Location
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.ui.FOLLOW
import app.unbraid.ui.defaultLabel
import app.unbraid.ui.dropText
import app.unbraid.ui.dropLabel
import app.unbraid.ui.keepName
import app.unbraid.ui.blockingToApply
import app.unbraid.ui.blockingFor
import app.unbraid.ui.pendingNote
import app.unbraid.ui.importTarget
import app.unbraid.ui.emptyApps
import app.unbraid.ui.systemLine
import app.unbraid.ui.noLocations
import app.unbraid.ui.nordLabels
import app.unbraid.ui.nordOpening
import app.unbraid.ui.ticked
import app.unbraid.ui.toSave
import app.unbraid.ui.routeWarning
import app.unbraid.ui.connectedLabel
import app.unbraid.ui.missingAppsText
import app.unbraid.ui.summary
import app.unbraid.ui.pausedLabel
import app.unbraid.ui.serverId
import app.unbraid.ui.place
import app.unbraid.ui.viaSpoken
import app.unbraid.ui.viaTag
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
import app.unbraid.ui.keptWith
import app.unbraid.ui.lookupsSub
import app.unbraid.ui.AppInfo
import app.unbraid.data.Filter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        // No other profile lists it: the route itself goes, and the action says so.
        assertEquals("No app in Work chose it. No other profile lists this route, so it is removed.", dropText("Work", 0, elsewhere = false, removes = true))
        assertEquals("Remove route", dropLabel("Work", removes = true))
        assertEquals("Remove from Work", dropLabel("Work", removes = false))
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
        // Sent with the location only while the route stays with its provider.
        assertEquals(false, blockingFor(nl, Location("nord", 81, "DE", "Germany"), false, true))
        assertEquals(null, blockingFor(nl, Location("mullvad", 81, "DE", "Germany"), false, true))
        assertEquals(null, blockingFor(nl.copy(tsExit = "100.64.0.9"), nl.loc, false, true))
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
    @Test fun systemLineSaysHowManyAndFlips() {
        assertEquals("14 system apps hidden" to "Show", systemLine(14, shown = false))
        assertEquals("Showing 14 system apps" to "Hide", systemLine(14, shown = true))
        assertEquals("1 system app hidden" to "Show", systemLine(1, shown = false))
        assertEquals(null, systemLine(0, shown = false))
        assertEquals(null, systemLine(0, shown = true))
    }

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

    /** A restored Nord route naming NordLynx or the standard servers as Nord's API does opens on them; anything else shows as Other. */
    @Test fun pickerOpensOnRestoredNordChoices() {
        val restored = nordOpening(Location("nord", 153, "NL", "Netherlands", group = "legacy_standard", protocol = "wireguard_udp"))
        assertEquals(null to null, restored)
        assertEquals("Standard" to "NordLynx", nordLabels(restored.first, restored.second))
        assertEquals("legacy_p2p" to "openvpn_tcp", nordOpening(Location("nord", group = "legacy_p2p", protocol = "openvpn_tcp")))
        assertEquals("Other" to "Other", nordLabels("legacy_obfuscated_servers", "openvpn_xor_udp"))
        assertEquals(null to null, nordOpening(null))
        // The place stays ticked however the file names the standard servers.
        assertTrue(ticked(Location("nord", 153, "NL", "Netherlands", group = "legacy_standard", protocol = "wireguard_udp"), Location("nord", 153, "NL", "Netherlands")))
        assertFalse(ticked(Location("nord", 153, "NL", "Netherlands", group = "legacy_p2p"), Location("nord", 153, "NL", "Netherlands")))
        // A restored favourite's pin (no address or key yet) is kept under NordLynx; a listed server without a NordLynx key is not.
        val restoredPin = Server("Germany #1", "de1.nordvpn.com", "", "", -1)
        assertEquals(restoredPin, toSave(Location("nord", 81, "DE", "Germany", pinned = restoredPin)).pinned)
        assertEquals(null, toSave(Location("nord", 81, "DE", "Germany", pinned = restoredPin.copy(ip = "89.187.175.235", load = 3))).pinned)
    }

    @Test fun pendingIvpnKeyHasItsOwnRouteMessage() {
        val route = Route("managed", Location("ivpn"))
        val vpn = app.unbraid.vpn.VpnState(phase = app.unbraid.vpn.VpnState.Phase.On)
        // Compare the pending caption to what older builds displayed for the same route: only a health warning.
        val presenter = Class.forName("app.unbraid.ui.RoutesScreenKt").declaredMethods
            .firstOrNull { it.name == "ivpnKeyNote" }
        val note = presenter?.invoke(null, route, true, true, vpn) ?: routeWarning(false, false)
        assertEquals("IVPN key change pending. unbraid will retry if needed.", note)
    }

    @Test fun pendingKeyNoteFollowsOnlyActiveManagedIvpnRoutes() {
        val managed = Route("managed", Location("ivpn"))
        val server = Server("one", "one.example", "", "", 0)
        val on = VpnState(phase = VpnState.Phase.On, servers = mapOf(managed.id to server))
        fun note(route: Route? = managed, active: Boolean = true, pending: Boolean = true, vpn: VpnState = on) =
            ivpnKeyNote(route, active, pending, vpn)
        assertEquals("IVPN key change pending. unbraid will retry if needed.", note())
        assertEquals(note(), note(vpn = on.copy(stuck = mapOf("ivpn" to false))))
        assertEquals(null, note(vpn = on.copy(stuck = mapOf("ivpn" to false), servers = emptyMap())))
        assertEquals(null, note(vpn = on.copy(stuck = mapOf("ivpn" to true))))
        assertEquals(null, note(vpn = on.copy(stalled = setOf(managed.id))))
        assertEquals(null, note(vpn = on.copy(notAnswering = setOf(managed.id))))
        assertEquals(null, note(vpn = on.copy(phase = VpnState.Phase.Paused)))
        assertEquals(null, note(active = false))
        assertEquals(null, note(pending = false))
        assertEquals(null, note(route = null))
        assertEquals(null, note(route = managed.copy(loc = Location("mullvad"))))
        assertEquals(null, note(route = managed.copy(tsExit = "100.64.0.1")))
        assertEquals(null, note(route = managed.copy(wg = WgPeer("import", "private", listOf("10.0.0.1/32"), "peer", endpoint = "localhost:1", allowedIps = listOf("0.0.0.0/0")))))
    }

    @Test fun keyStatusReadsOnlyAnUnfinishedSavedIvpnChange() {
        val pending = Ivpn.Pending("new", "public", "kemPub", "kemPriv")
        val account = Ivpn.Account("account", "token", "old", "10.0.0.1", psk = "current")
        fun encoded(a: Ivpn.Account) = NordApi.json.encodeToString(Ivpn.Account.serializer(), a)
        assertTrue(ivpnKeyPending(encoded(account.copy(pending = pending))))
        assertFalse(ivpnKeyPending(encoded(account)))
        assertFalse(ivpnKeyPending(encoded(account.copy(psk = null, keyAt = 0))))
        assertFalse(ivpnKeyPending("""{"pending":true}"""))
        assertFalse(ivpnKeyPending(null))
    }

    @Test fun routeCardWarnings() {
        assertEquals("Doesn't work on this network", routeWarning(stalled = true, notAnswering = true))
        assertEquals("Not answering. Check that its proxy app is running.", routeWarning(stalled = false, notAnswering = true, proxy = true))
        assertEquals("Not answering. If this file came from a provider, it may have expired: download a new one.", routeWarning(stalled = false, notAnswering = true))
        assertEquals(null, routeWarning(stalled = false, notAnswering = false))
        assertEquals("home-mac is offline: its apps have no internet. Tap to choose another exit.", routeWarning(stalled = false, notAnswering = false, offline = "home-mac"))
        assertEquals("Connected · 00:01:02", connectedLabel("00:01:02", 0))
        assertEquals("Connected · 1 route waiting", connectedLabel("00:01:02", 1))
        assertEquals("Connected · 2 routes waiting", connectedLabel("00:01:02", 2))
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
        // Sharing its Android user id with an app on a VPN route, it stays inside with it (also under lockdown, where
        // its own Direct rule would take that app along): said, naming the app. With another Direct app it is outside.
        val kept = "Shares its Android user id with US app, so it can't be Direct on its own: it goes as that app does."
        val usApp = listOf(AppInfo("chose.us", "US app", false, 10123))
        assertEquals(listOf("US app"), keptWith(direct, "chose.direct", usApp))
        assertEquals(kept, outsideNote(direct, "chose.direct", lockdown = false, keptBy = keptWith(direct, "chose.direct", usApp)))
        assertEquals(kept, outsideNote(direct, "chose.direct", lockdown = true, keptBy = keptWith(direct, "chose.direct", usApp)))
        assertEquals(emptyList<String>(), keptWith(direct, "chose.us", listOf(AppInfo("chose.direct", "Files", false, 10123))))
        val twoDirect = direct.copy(apps = direct.apps + ("other.direct" to DIRECT))
        assertEquals(emptyList<String>(), keptWith(twoDirect, "chose.direct", listOf(AppInfo("other.direct", "Files", false, 10123))))
        // With its lookups filtered it is inside anyway, but still goes as the app on a route: its filter switch says so too.
        assertEquals(listOf("US app"), keptWith(through, "chose.direct", usApp))
        assertEquals("Not used now: its lookups go as US app's do", lookupsSub(true, "NextDNS", listOf("US app")))
        assertEquals("Outside unbraid, with the phone's DNS", lookupsSub(false, "NextDNS"))
    }

    /** A Via route's chip has two slashes and its line carries the entry route's code as a tag, never "via" words. */
    @Test fun viaRouteShowsEntryTag() {
        val v = us.copy(via = "nl")
        val unknown = Route("zz", Location("nord"))
        val bolt = unknown.copy(id = "e")
        assertEquals(1, nl.hops)
        assertEquals(2, v.hops)
        assertEquals(2, v.copy(viaNative = true).hops)
        assertEquals(null, viaTag(nl, setup.routes))
        assertEquals("NL", viaTag(v, setup.routes))
        assertEquals("··", viaTag(v.copy(via = "e"), setup.routes + bolt))
        assertEquals("?", viaTag(v.copy(via = "gone"), setup.routes))
        assertEquals("Entry route removed", place(v.copy(via = "gone"), emptyMap(), setup.routes))
        assertFalse(place(v, emptyMap(), setup.routes).contains("via"))
        assertEquals(", via Netherlands", viaSpoken(v, setup.routes))
        assertEquals(", via Netherlands, one tunnel", viaSpoken(v.copy(viaNative = true), setup.routes))
        assertEquals("", viaSpoken(nl, setup.routes))
    }

    @Test fun twoEntriesWithOneNameAreToldApart() {
        val ams = Route("a", Location("nord", countryCode = "NL", country = "Netherlands", city = "Amsterdam"))
        val fast = Route("f", Location("nord", countryCode = "NL", country = "Netherlands"))
        val ita = Route("i", Location("nord", countryCode = "IT", country = "Italy", city = "Milan"))
        val names = viaLabels(listOf(ams, fast, ita))
        assertEquals("Netherlands · Nord · Amsterdam", names["a"])
        assertEquals("Netherlands · Nord · ${fast.loc.automaticLabel}", names["f"])
        assertEquals("Italy · Nord", names["i"])
        assertEquals("Netherlands · Nord", viaLabels(listOf(ams, ita))["a"])
        // Same place too: the profiles that list them tell them apart.
        val fast2 = fast.copy(id = "g")
        val both = viaLabels(listOf(fast, fast2, ita), listOf(Profile("Home", "f"), Profile("Mobile", "x", added = listOf("g"))))
        assertEquals("Netherlands · Nord · Home", both["f"])
        assertEquals("Netherlands · Nord · Mobile", both["g"])
        assertEquals("Italy · Nord", both["i"])
    }

    @Test fun viaOffersOnlyRoutesAProfileLists() {
        // As on the owner's phone: one Netherlands route in Home and Mobile, a second one no profile lists.
        val shared = Route("n1", Location("nord", 153, "NL", "Netherlands"))
        val orphan = Route("n2", Location("nord", 153, "NL", "Netherlands"))
        val af = Route("af", Location("nord", 3, "AF", "Afghanistan"), via = "n1")
        val home = Profile("Home", DIRECT, apps = mapOf("sym" to "n1"), added = listOf("af"))
        val s = Setup(routes = listOf(shared, orphan, af), defaultRoute = DIRECT, apps = home.apps, added = home.added,
            profile = "Home", profiles = listOf(home, Profile("Mobile", "n1")))
        val entries = viaEntries(s, af)
        assertEquals(listOf("n1"), entries.map { it.id })
        assertEquals("Netherlands · Nord", viaLabels(entries, s.saved)["n1"])
        // A route already going through an unlisted one keeps it on offer, so the menu can show and clear it.
        assertEquals(listOf("n1", "n2"), viaEntries(s, af.copy(via = "n2")).map { it.id })
        // The profile in use deleted: the routes the setup in use lists stay on offer.
        val loose = s.copy(profile = null, profiles = listOf(Profile("Mobile", "x")))
        assertEquals(listOf("n1"), viaEntries(loose, af.copy(via = null)).map { it.id })
        // Without profiles every route is offered.
        assertEquals(listOf("n1", "n2"), viaEntries(Setup(routes = listOf(shared, orphan, af)), af).map { it.id })
    }
}
