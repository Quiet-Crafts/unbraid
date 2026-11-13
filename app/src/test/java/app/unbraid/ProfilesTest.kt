package app.unbraid

import app.unbraid.data.CarriesDefault
import app.unbraid.data.DIRECT
import app.unbraid.data.MAX_PROFILES
import app.unbraid.data.PREVIOUS
import app.unbraid.data.Location
import app.unbraid.data.Profile
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.data.graphemes
import app.unbraid.data.newSetup
import app.unbraid.data.takeGraphemes
import app.unbraid.vpn.switchDone
import app.unbraid.vpn.VpnState
import app.unbraid.vpn.VpnState.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfilesTest {
    private val nl = Route("nl", Location("nord", 153, "NL", "Netherlands"))
    private val us = Route("us", Location("nord", 228, "US", "United States"))
    private val it = Route("it", Location("nord", 106, "IT", "Italy"))
    private val base = Setup(routes = listOf(nl, us, it), defaultRoute = "nl", apps = mapOf("netflix" to "us"))

    /** Saving puts the new profile in use; switching keeps what the old one had, edits included, and loads the other. */
    @Test fun saveSwitchAndFollowEdits() {
        val home = base.saveAs("Home")
        assertEquals("Home", home.profile)
        val edited = home.copy(apps = home.apps + ("bank" to DIRECT)) // an edit while Home is in use
        val travel = edited.saveAs("Travel").copy(defaultRoute = "us", apps = emptyMap())
        val back = travel.use("Home")
        assertEquals("nl", back.defaultRoute)
        assertEquals(mapOf("netflix" to "us", "bank" to DIRECT), back.apps)
        assertEquals(Profile("Travel", "us", added = listOf("it")), back.profiles[1])
        assertEquals(back, back.use("Nowhere")) // an unknown name changes nothing
    }

    /** A route no profile lists any more, after deleting one or taking the route out of one, goes; one still listed stays. */
    @Test fun aRouteNoProfileListsGoes() {
        // Home uses nl and us; Trip (not in use) only lists it.
        val s = base.saveAs("Home").add("Trip", true).editing("Trip") { p -> p.copy(defaultRoute = "nl", added = listOf("it")) }
            .editing(null) { p -> p.copy(added = p.added - "it") }
        assertEquals(emptySet<String>(), s.unlisted(setOf("it", "nl"))) // Trip still lists it
        val forgot = s.forget("Trip")
        assertEquals(listOf("nl", "us"), forgot.routes.map { r -> r.id })
        // Out of Trip, its last listing: it goes; out of a profile while another still lists it: it stays.
        val dropped = s.editing("Trip") { p -> p.copy(added = p.added - "it") }
        assertEquals(setOf("it"), dropped.unlisted(setOf("it")))
        assertEquals(listOf("nl", "us"), dropped.withoutUnlisted(setOf("it")).routes.map { r -> r.id })
        assertEquals(emptySet<String>(), dropped.unlisted(setOf("nl")))
        // Without profiles every route shows: nothing goes.
        val none = base.saveAs("Home").forget("Home")
        assertEquals(base.routes, none.routes)
        assertEquals(emptySet<String>(), base.unlisted(setOf("it")))
    }

    /** A route's blocking chosen with its location is part of that one change; one equal to the provider page's follows the page. */
    @Test fun blockingFollowsThePageWhenEqual() {
        val on = base.blocking("us", true)
        assertEquals(null, on.route("us")!!.filter) // Nord's page has it on by default
        assertEquals(false, base.blocking("us", false).route("us")!!.filter)
        assertEquals(true, base.copy(opts = mapOf("nord" to mapOf(app.unbraid.data.PROTECT to "off"))).blocking("us", true).route("us")!!.filter)
    }

    /** Deleting the profile in use leaves the routes as they are; renaming it keeps it in use. */
    @Test fun forgetAndRename() {
        val s = base.saveAs("Home").saveAs("Travel")
        assertEquals("Trip", s.rename("Travel", "Trip").profile)
        val gone = s.forget("Travel")
        assertNull(gone.profile)
        assertEquals(base.apps, gone.apps)
        assertEquals(listOf("Home"), gone.profiles.map { p -> p.name })
    }

    @Test fun reorder() {
        val s = base.saveAs("A").saveAs("B").saveAs("C")
        assertEquals(listOf("C", "A", "B"), s.move("C", -5).profiles.map { p -> p.name })
        assertEquals(listOf("B", "C", "A"), s.move("A", 2).profiles.map { p -> p.name })
    }

    /** Twelve characters people see, trimmed, unique regardless of case; renaming may keep its own name. */
    @Test fun names() {
        val s = base.saveAs("Home")
        assertEquals("Geschäftlich", s.profileName(" Geschäftlich "))
        assertNull(s.profileName("Thirteen chrs"))
        assertNull(s.profileName("   "))
        assertNull(s.profileName("home"))
        assertEquals("HOME", s.profileName("HOME", except = "Home"))
        assertEquals(12, graphemes("👨‍👩‍👧 Casa al ma"))
        assertEquals("ét", "été".takeGraphemes(2)) // never splits an accent from its letter
    }

    /** A removed route: profiles' apps on it follow their default; a default is never moved to another route. */
    @Test fun tidyAfterRouteRemoved() {
        val s = base.saveAs("Home").copy(profiles = listOf(Profile("Home", "nl", mapOf("netflix" to "us")), Profile("Trip", "us", mapOf("rai" to "it"))))
        val t = s.copy(routes = listOf(nl, it)).tidy(setOf("us"))
        assertEquals(Profile("Home", "nl", emptyMap()), t.profiles[0])
        assertEquals(Profile("Trip", "us", mapOf("rai" to "it")), t.profiles[1])
        assertEquals(s, s.tidy(emptySet())) // nothing to mend: the same setup
        assertEquals(s, s.tidy(setOf("gone"))) // nothing used it
    }

    /** Removing what carries a default, live or saved, is refused with its name; anything else may go. */
    @Test fun removingADefaultIsRefused() {
        val s = base.saveAs("Home").add("Trip", false).editing("Trip") { p -> p.copy(defaultRoute = "us") }
        assertEquals(CarriesDefault("Home VPS", true, emptyList()), s.refusesRemoving(setOf("nl", "x"), "Home VPS"))
        assertEquals("Home VPS carries your default route. Choose another default route first.", s.refusesRemoving(setOf("nl"), "Home VPS")!!.text)
        val trip = s.refusesRemoving(setOf("us"), "United States")!!
        assertEquals(CarriesDefault("United States", false, listOf("Trip")), trip)
        assertEquals("United States carries the default route of Trip. Choose another default route there first.", trip.text)
        assertNull(s.refusesRemoving(setOf("it"), "Italy"))
        // The profile in use counts by its live default, not a stale saved copy.
        assertNull(s.copy(defaultRoute = "it").refusesRemoving(setOf("nl"), "Netherlands"))
    }

    /** Only a new install's setup is untouched (a sign-in or an import may replace its placeholder), never one read from a file. */
    @Test fun untouchedSetup() {
        assertEquals(true, newSetup().untouched)
        assertEquals(false, Setup().untouched) // the same shape read from an older file: set up by its user
        assertEquals(false, newSetup().copy(apps = mapOf("a" to "nl")).untouched)
        assertEquals(false, newSetup().copy(routes = Setup().routes.map { r -> r.copy(loc = r.loc.copy(provider = "mullvad")) }).untouched)
        assertEquals(false, base.copy(pristine = true).untouched)
        // Any change to what the user sets up ends it; a preference or a speed test's routes don't.
        val n = newSetup()
        assertEquals(true, n.copy(prefs = n.prefs.copy(stats = true)).after(n).pristine)
        assertEquals(true, n.copy(testing = setOf("nl")).after(n).pristine)
        assertEquals(false, n.copy(home = n.home.copy(tailscale = true)).after(n).pristine)
        assertEquals(false, n.copy(servers = listOf(app.unbraid.data.OwnServer("s1"))).after(n).pristine)
        assertEquals(false, n.copy(defaultRoute = DIRECT).after(n).pristine)
    }

    /** A switch is done once the engine runs that exact setup in full, undone once it refused it, and waits otherwise. */
    @Test fun switchOutcome() {
        val h = base.saveAs("Home").hashCode()
        assertEquals(true, switchDone(VpnState(Phase.On, applied = h), h))
        assertEquals(false, switchDone(VpnState(Phase.On, applied = 1, refused = h), h))
        assertNull(switchDone(VpnState(Phase.On, applied = 1, refused = 2), h)) // still waiting (e.g. a server being found)
        assertNull(switchDone(VpnState(Phase.On), h))
        assertEquals(true, switchDone(VpnState(Phase.Paused), h)) // nothing runs: nothing to wait for
        assertEquals(true, switchDone(VpnState(Phase.Off, refused = h), h))
    }

    /** The hash a switch waits for survives saving and loading (it compares a fresh copy with the one in memory). */
    @Test fun hashSurvivesSaving() {
        val s = base.saveAs("Home").saveAs("Travel")
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        assertEquals(s.hashCode(), json.decodeFromString(Setup.serializer(), json.encodeToString(Setup.serializer(), s)).hashCode())
    }

    /** A refused switch goes back to what the engine still runs, even if that profile was renamed or a route removed meanwhile. */
    @Test fun backToWhatRuns() {
        val ran = base.saveAs("Home") // the engine runs Home: netflix on us
        val trip = ran.saveAs("Trip").copy(defaultRoute = "it", apps = mapOf("rai" to DIRECT))
        val back = trip.back(ran)
        assertEquals("Home", back.profile)
        assertEquals("nl", back.defaultRoute)
        assertEquals(mapOf("netflix" to "us"), back.apps)
        assertEquals(Profile("Trip", "it", mapOf("rai" to DIRECT), listOf("it")), back.profiles[1]) // the refused one keeps what it had
        val renamed = trip.rename("Home", "House").copy(routes = listOf(nl, it))
        val b2 = renamed.back(ran)
        assertNull(b2.profile) // its profile is gone by that name: none selected, the routes still as they ran
        assertEquals(emptyMap<String, String>(), b2.apps) // netflix's route was removed meanwhile
    }
    @Test fun readinessAndSwitchingEmptyProfiles() {
        assertEquals(false, Profile("Empty", "").ready)
        assertEquals(true, Profile("Direct", DIRECT).ready)
        assertEquals(true, Profile("Home", "nl").ready)
        val s = base.saveAs("Home").add("Empty", true).add("Trip", false)
            .copy(defaultRoute = "it", apps = mapOf("rai" to DIRECT))
        assertEquals(s, s.use("Empty"))
        val trip = s.use("Trip")
        assertEquals("Trip", trip.profile)
        assertEquals("nl", trip.defaultRoute)
        assertEquals(base.apps, trip.apps)
        assertEquals(Profile("Home", "it", mapOf("rai" to DIRECT), listOf("it")), trip.profiles[0])
    }

    @Test fun addCopiesLiveStateOrCreatesEmptyWithoutSwitching() {
        val live = base.saveAs("Home").copy(defaultRoute = "it", apps = mapOf("rai" to DIRECT))
        val copied = live.add("Copy", false)
        assertEquals("Home", copied.profile)
        assertEquals("it", copied.defaultRoute)
        assertEquals(live.apps, copied.apps)
        assertEquals(Profile("Home", "it", live.apps, listOf("it")), copied.profiles[0])
        assertEquals(Profile("Copy", "it", live.apps, listOf("it")), copied.profiles[1])
        val empty = live.add("Empty", true)
        assertEquals("Home", empty.profile)
        assertEquals("it", empty.defaultRoute)
        assertEquals(live.apps, empty.apps)
        assertEquals(Profile("Home", "it", live.apps, listOf("it")), empty.profiles[0])
        assertEquals(Profile("Empty", ""), empty.profiles[1])
    }

    @Test fun viewDraftAndFallbackToLiveSetup() {
        val s = base.saveAs("Home").add("Trip", true)
            .editing("Trip") { it.copy(defaultRoute = "it", apps = mapOf("rai" to DIRECT)) }
            .copy(defaultRoute = "us", apps = mapOf("bank" to DIRECT))
        assertEquals(s.copy(defaultRoute = "it", apps = mapOf("rai" to DIRECT), added = emptyList()), s.viewAs("Trip"))
        assertEquals(s, s.viewAs(null))
        assertEquals(s, s.viewAs("Home"))
        assertEquals(s, s.viewAs("Missing"))
        assertEquals("", s.add("Empty", true).viewAs("Empty").defaultRoute)
    }

    @Test fun editDraftThenUseIt() {
        val s = base.saveAs("Home").add("Trip", true)
            .copy(defaultRoute = "us", apps = mapOf("bank" to DIRECT))
        val draft = s.editing("Trip") { it.copy(defaultRoute = "it", apps = mapOf("rai" to "nl")) }
        assertEquals(s.defaultRoute, draft.defaultRoute)
        assertEquals(s.apps, draft.apps)
        assertEquals(s.profiles[0], draft.profiles[0])
        assertEquals(Profile("Trip", "it", mapOf("rai" to "nl")), draft.profiles[1])
        val switched = draft.use("Trip")
        assertEquals("Trip", switched.profile)
        assertEquals("it", switched.defaultRoute)
        assertEquals(mapOf("rai" to "nl"), switched.apps)
        assertEquals(Profile("Home", "us", s.apps, listOf("it")), switched.profiles[0])
    }

    @Test fun editLiveForNullCurrentAndUnknownNames() {
        val s = base.saveAs("Home").add("Trip", true)
        for (name in listOf(null, "Home", "Missing")) {
            val edited = s.editing(name) { it.copy(defaultRoute = "it", apps = mapOf("rai" to DIRECT)) }
            assertEquals("Home", edited.profile)
            assertEquals("it", edited.defaultRoute)
            assertEquals(mapOf("rai" to DIRECT), edited.apps)
            assertEquals(s.profiles, edited.profiles)
            assertEquals(Profile("Home", "it", edited.apps, listOf("it")), edited.saved[0])
        }
    }

    @Test fun tidyPreservesEmptyAndDefaultsAndMendsRemovedRoutes() {
        val s = base.copy(routes = listOf(nl, it), profiles = listOf(
            Profile("Empty", ""),
            Profile("Draft", "", mapOf("lost" to "us", "kept" to "it")),
            Profile("Trip", "us", mapOf("lost" to "us", "kept" to "it")),
        ))
        val tidy = s.tidy(setOf("us"))
        assertEquals(Profile("Empty", ""), tidy.profiles[0])
        assertEquals(Profile("Draft", "", mapOf("kept" to "it")), tidy.profiles[1])
        assertEquals(Profile("Trip", "us", mapOf("kept" to "it")), tidy.profiles[2]) // its default stays: refused before removal, refused at connect if missing
        assertEquals(tidy, tidy.tidy(setOf("us")))
        assertEquals(s, s.tidy(emptySet())) // a route missing since the settings were read is left named
    }

    @Test fun backRestoresRunningProfileWithEmptyDraftPresent() {
        val ran = base.saveAs("Home").add("Empty", true)
        val refused = ran.add("Trip", false).use("Trip")
            .copy(defaultRoute = "it", apps = mapOf("rai" to DIRECT))
        val back = refused.back(ran)
        assertEquals("Home", back.profile)
        assertEquals("nl", back.defaultRoute)
        assertEquals(base.apps, back.apps)
        assertEquals(Profile("Empty", ""), back.profiles[1])
        assertEquals(Profile("Trip", "it", mapOf("rai" to DIRECT), listOf("it")), back.profiles[2])
    }

    /** A refused switch keeps edits made meanwhile to the profile it goes back to: the routes run as before, unprofiled. */
    @Test fun backKeepsEditsMadeMeanwhile() {
        val ran = base.saveAs("Home").saveAs("Trip") // the engine runs Trip
        val edited = ran.use("Home").editing("Trip") { it.copy(apps = mapOf("rai" to "it")) } // Trip edited while switching to Home
        val back = edited.back(ran)
        assertNull(back.profile)
        assertEquals(ran.apps, back.apps)
        assertEquals(mapOf("rai" to "it"), back.profiles.first { p -> p.name == "Trip" }.apps)
    }

    /** Names can't hide control or direction-override characters (they show in tiles and shortcuts). */
    @Test fun namesRefuseControls() {
        val s = base
        assertNull(s.profileName("Ho\u202Eme"))
        assertNull(s.profileName("Ho\nme"))
        assertNull(s.profileName("Ho\u0000me"))
        assertEquals("👨‍👩‍👧 Casa", s.profileName("👨‍👩‍👧 Casa")) // a joined emoji (zero-width joiners) stays allowed
    }

    /** Each profile shows only its own routes; without profiles all routes show. */
    @Test fun hiddenRoutes() {
        val spare = Route("spare", Location("nord", 81, "DE", "Germany"))
        val s = base.copy(routes = listOf(nl, us, it, spare)).saveAs("Home")
            .copy(added = listOf("spare"))
            .add("Trip", empty = true).editing("Trip") { p -> p.copy(defaultRoute = "it", added = listOf("us")) }
        assertEquals(setOf("it"), s.hiddenRoutes(null))
        assertEquals(setOf("it"), s.hiddenRoutes("Home"))
        assertEquals(setOf("nl", "spare"), s.hiddenRoutes("Trip"))
        assertEquals(emptySet<String>(), base.hiddenRoutes(null))
        assertEquals(setOf("it"), s.forget("Home").hiddenRoutes(null)) // live setup remains without a profile
    }

    @Test fun addedOnSaveCopyAndEmpty() {
        val first = base.saveAs("Home")
        assertEquals(listOf("it"), first.added)
        assertEquals(listOf("it"), first.profiles.single().added)
        assertEquals(emptySet<String>(), first.hiddenRoutes(null))
        val live = first.copy(added = listOf("us"))
        val later = live.saveAs("Later")
        assertEquals(listOf("us"), later.added)
        assertEquals(listOf("us"), later.profiles.last().added)
        assertEquals(listOf("us"), live.add("Copy", false).profiles.last().added)
        assertEquals(emptyList<String>(), live.add("Empty", true).profiles.last().added)
    }

    @Test fun firstSaveKeepsPreviouslyAddedOrphan() {
        val first = base.copy(added = listOf("it")).saveAs("Home")
        assertEquals(emptySet<String>(), first.hiddenRoutes(null))
    }

    @Test fun addedFollowsSwitchesAndDraftEdits() {
        val home = base.saveAs("Home").copy(added = listOf("it"))
        val s = home.add("Trip", true).editing("Trip") { it.copy(defaultRoute = "us", added = listOf("nl")) }
        assertEquals(listOf("nl"), s.viewAs("Trip").added)
        assertEquals(listOf("it"), s.added)
        val trip = s.use("Trip")
        assertEquals(listOf("nl"), trip.added)
        assertEquals(listOf("it"), trip.profiles.first().added)
        val edited = trip.editing(null) { it.copy(added = listOf("us")) }
        val back = edited.use("Home")
        assertEquals(listOf("it"), back.added)
        assertEquals(listOf("us"), back.profiles.last().added)
    }

    @Test fun useCurrentProfileKeepsLiveAdded() {
        val s = base.saveAs("Home").copy(added = listOf("us"))
        assertEquals(listOf("us"), s.use("Home").added)
    }

    @Test fun tidyRemovesAddedFromLiveAndSavedProfiles() {
        val s = base.saveAs("Home").copy(added = listOf("us", "it"))
            .add("Trip", true).editing("Trip") { it.copy(added = listOf("us", "nl")) }
        val t = s.copy(routes = listOf(nl, it)).tidy(setOf("us"))
        assertEquals(listOf("it"), t.added)
        assertEquals(listOf("it"), t.profiles.first().added)
        assertEquals(listOf("nl"), t.profiles.last().added)
    }

    @Test fun backRestoresAddedAndSavesRefusedAdded() {
        val ran = base.saveAs("Home").copy(added = listOf("it"))
        val refused = ran.add("Trip", false).use("Trip").copy(added = listOf("us"))
        val back = refused.back(ran)
        assertEquals("Home", back.profile)
        assertEquals(listOf("it"), back.added)
        assertEquals(listOf("us"), back.profiles.last().added)
    }

    @Test fun backKeepsAddedEditsMadeMeanwhile() {
        val ran = base.saveAs("Home").saveAs("Trip")
        val edited = ran.use("Home").editing("Trip") { it.copy(added = listOf("us")) }
        val back = edited.back(ran)
        assertNull(back.profile)
        assertEquals(listOf("us"), back.saved.last().added)
    }

    /** An app put on the default route keeps it: a later default change moves only the apps that follow. */
    @Test fun assignToDefaultSticksAcrossDefaultChange() {
        val chosen = base.editing(null) { p -> p.assigned("signal", "nl") }
        assertEquals("nl", chosen.apps["signal"])
        val direct = chosen.editing(null) { p -> p.copy(defaultRoute = DIRECT) }
        assertEquals("nl", direct.routeOf("signal")) // chosen: stays on the VPN route
        assertEquals(DIRECT, direct.routeOf("maps")) // follows: moves with the default
        assertEquals(1, direct.followers(listOf("signal", "maps", "netflix")))
        val back = direct.editing(null) { p -> p.assigned("signal", null) }
        assertEquals(true, back.follows("signal"))
        assertEquals(DIRECT, back.routeOf("signal"))
    }

    /** Mending after a route removal keeps apps that chose their profile's default. */
    @Test fun tidyKeepsEntriesEqualToDefault() {
        val s = base.saveAs("Home").copy(profiles = listOf(Profile("Home", "nl", mapOf("bank" to "nl", "netflix" to "us")), Profile("Trip", "it", mapOf("rai" to "it", "news" to "us"))))
        val t = s.copy(routes = listOf(nl, it)).tidy(setOf("us"))
        assertEquals(mapOf("bank" to "nl"), t.profiles[0].apps)
        assertEquals(mapOf("rai" to "it"), t.profiles[1].apps)
    }

    /** A choice equal to the default survives switching profiles and saving. */
    @Test fun chosenDefaultSurvivesSwitchAndSaving() {
        val home = base.saveAs("Home").editing(null) { p -> p.assigned("bank", "nl") }
        val trip = home.add("Trip", false).use("Trip").editing(null) { p -> p.copy(defaultRoute = "it") }
        assertEquals("nl", trip.routeOf("bank"))
        val again = trip.use("Home")
        assertEquals(mapOf("netflix" to "us", "bank" to "nl"), again.apps)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val read = json.decodeFromString(Setup.serializer(), json.encodeToString(Setup.serializer(), again.copy(version = app.unbraid.data.VERSION))).migrated()
        assertEquals(again.apps, read.apps)
        assertEquals(again.profiles, read.profiles)
    }

    /** Data from before choices stuck starts clean: nothing is pinned, and every app goes where it went. */
    /** An older file keeps every app entry as stored, those equal to their default too: only apps without one follow it. */
    @Test fun olderDataKeepsEveryEntry() {
        val old = base.copy(version = 1, apps = mapOf("netflix" to "us", "odd" to "nl", "bank" to DIRECT), profiles = listOf(Profile("Trip", "us", mapOf("netflix" to "us", "rai" to "it"))))
        val now = old.migrated()
        assertEquals(old.copy(version = app.unbraid.data.VERSION), now)
        assertEquals(false, now.follows("odd"))
        assertEquals(true, now.follows("other"))
        assertEquals(now, now.migrated())
    }

    /** Signing in points only a new install's placeholder at the provider; a route the user set up, and the default, stay. */
    @Test fun signInLeavesTheDefaultRoute() {
        val mullvad = Setup(routes = listOf(Route("nl", Location("mullvad", countryCode = "NL", country = "Netherlands", region = "nl-ams"))), defaultRoute = "nl")
        assertEquals(mullvad, mullvad.signedIn("proton", emptySet())) // signed out of Mullvad, then into Proton
        assertEquals(base, base.signedIn("proton", setOf("nord")))
        val fresh = newSetup().signedIn("proton", emptySet())
        assertEquals(listOf(Route("nl", Location("proton"))), fresh.routes)
        assertEquals("nl", fresh.defaultRoute)
        assertEquals(fresh, fresh.signedIn("mullvad", emptySet())) // signed out of Proton, then into Mullvad: it stays
        // The starting Netherlands route used with NordVPN, signed out of, then another provider: it stays where it was.
        val used = newSetup().signedIn("nord", emptySet())
        assertEquals(newSetup().copy(pristine = false), used)
        assertEquals(used, used.signedIn("proton", emptySet()))
        assertEquals(newSetup().copy(pristine = false), newSetup().signedIn("proton", setOf("nord")))
        assertEquals(Setup(), Setup().signedIn("proton", emptySet())) // the same shape from an older file: set up by its user
        // Tailscale only (no route, Direct the default), then a provider: Direct stays the default, no route appears.
        val none = Setup(routes = emptyList(), defaultRoute = DIRECT)
        assertEquals(none, none.signedIn("ivpn", emptySet()))
    }

    /** An import with no provider signed in replaces only a new install's placeholder, and never deletes the route it made. */
    @Test fun importKeepsTheRouteItMade() {
        val own = { id: String -> Route(id, wg = app.unbraid.data.WgPeer("VPS", "k", listOf("10.0.0.2/32"), "p", endpoint = "192.0.2.1:51820", allowedIps = listOf("0.0.0.0/0")), own = "s1") }
        // Over the placeholder itself: it becomes the own server, and stays the default.
        val over = newSetup().imported("nl", own("nl"), alone = true)
        assertEquals(listOf(own("nl")), over.routes)
        assertEquals("nl", over.defaultRoute)
        assertEquals(false, over.pristine)
        // A new route: the placeholder goes and the new one is the default.
        val added = newSetup().imported("r1", own("r1"), alone = true)
        assertEquals(listOf(own("r1")), added.routes)
        assertEquals("r1", added.defaultRoute)
        // Signed in to Nord once, an own server saved, signed out: replacing "nl" keeps it, and the default with it.
        val used = newSetup().signedIn("nord", emptySet()).copy(servers = listOf(app.unbraid.data.OwnServer("s1")))
        val replaced = used.imported("nl", own("nl"), alone = true)
        assertEquals(listOf(own("nl")), replaced.routes)
        assertEquals("nl", replaced.defaultRoute)
        val beside = used.imported("r1", own("r1"), alone = true)
        assertEquals(listOf("nl", "r1"), beside.routes.map { r -> r.id })
        assertEquals("nl", beside.defaultRoute)
        // A provider signed in: never.
        assertEquals(listOf("nl", "r1"), newSetup().imported("r1", own("r1"), alone = false).routes.map { r -> r.id })
    }

    /** With no profile in use, a switch can keep the setup in use as a profile first; "Previous" is replaced, never the target. */
    @Test fun useFromNoProfileKeepsLiveSetup() {
        val live = base.add("Travel", true).editing("Travel") { p -> p.copy(defaultRoute = "us") }
        assertEquals(true, live.loose)
        assertEquals(listOf("Travel"), live.use("Travel").profiles.map { p -> p.name }) // without keepAs it is lost, as asked
        val kept = live.use("Travel", PREVIOUS)
        assertEquals("Travel", kept.profile)
        assertEquals("us", kept.defaultRoute)
        assertEquals(Profile(PREVIOUS, "nl", base.apps), kept.profiles[1])
        assertEquals(base.apps, kept.use(PREVIOUS).apps) // and it can be used again
        // Later, from no profile again: the older "Previous" is replaced, not added twice.
        val later = kept.forget("Travel").copy(apps = mapOf("rai" to "it")).add("Trip", true).editing("Trip") { p -> p.copy(defaultRoute = "it") }
        val again = later.use("Trip", PREVIOUS)
        assertEquals(listOf(PREVIOUS, "Trip"), again.profiles.map { p -> p.name })
        assertEquals(mapOf("rai" to "it"), again.profiles[0].apps)
        assertEquals(later, later.use(PREVIOUS, PREVIOUS)) // the profile to use is never written over
        // Nothing to keep: the setup is in a profile already, or one holds it.
        assertEquals(false, base.saveAs("Home").forget("Home").add("Home", false).loose)
        val full = base.copy(profiles = (1..MAX_PROFILES).map { n -> Profile("P$n", "nl") })
        assertEquals(full, full.use("P1", PREVIOUS)) // no room: nothing changes, the app asks instead
    }

    /** A setup naming routes that don't exist is reported, never repaired to Direct or to another route. */
    @Test fun missingRoutesAreReported() {
        val s = base.copy(defaultRoute = "gone", apps = mapOf("netflix" to "us", "mail" to "lost", "bank" to DIRECT), profiles = listOf(Profile("Trip", "away", mapOf("rai" to "x")), Profile("Empty", "")))
        assertEquals(true, s.defaultMissing)
        assertEquals(setOf("mail"), s.appsOnMissing)
        assertEquals(listOf("Trip"), s.profilesMissingDefault)
        assertEquals(false, base.defaultMissing)
        assertEquals(false, base.copy(defaultRoute = DIRECT).defaultMissing)
        assertEquals(emptySet<String>(), base.appsOnMissing)
        assertEquals(emptyList<String>(), base.profilesMissingDefault)
    }

    /** A saved server already carried by a route is reused, the one the profile shows first; apps are offered only the profile's routes. */
    @Test fun savedServerRouteIsReusedAndOnlyTheProfilesRoutesAreOffered() {
        val peer = app.unbraid.data.WgPeer("Home VPS", "k", listOf("10.0.0.2/32"), "p", endpoint = "192.0.2.1:51820", allowedIps = listOf("0.0.0.0/0"))
        val server = app.unbraid.data.OwnServer("s1", wg = peer)
        val a = Route("a", wg = peer, own = "s1")
        val b = Route("b", wg = peer, own = "s1")
        val s = base.copy(routes = listOf(nl, us, it, a, b)).saveAs("Home").copy(added = listOf("b"))
            .add("Trip", true).editing("Trip") { p -> p.copy(defaultRoute = "it", added = listOf("a")) }
        assertEquals("b", s.serverRoute(server, null))
        assertEquals("a", s.serverRoute(server, "Trip"))
        assertNull(s.serverRoute(server.copy(id = "s2"), null))
        assertNull(s.serverRoute(server.copy(wg = peer.copy(endpoint = "192.0.2.9:51820")), null)) // a copy no longer like it
        assertEquals(listOf("it", "a"), s.offered("Trip").map { r -> r.id })
        assertEquals(base.routes, base.offered(null)) // no profiles: every route
    }
}
