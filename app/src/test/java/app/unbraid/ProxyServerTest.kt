package app.unbraid

import app.unbraid.data.*
import app.unbraid.ui.AppInfo
import app.unbraid.ui.ProxyDirect
import app.unbraid.ui.directText
import app.unbraid.ui.goingDirect
import app.unbraid.ui.proxyApps
import app.unbraid.ui.proxyDirect
import app.unbraid.ui.proxyStatus
import app.unbraid.vpn.Compiler
import app.unbraid.vpn.Health
import app.unbraid.vpn.VpnState
import org.junit.Assert.*
import org.junit.Test

class ProxyServerTest {
    private val pin = "aa".repeat(32)
    private val app = AppInfo("org.example.proxy", "Proxy", false, 1, AppIdentity(pin, emptySet()))
    private val proxy = Socks5Profile("Proxy", "127.0.0.1", 1080, app.pkg, pin, "user", "pass", allowUdp = true)
    private val server = OwnServer("p", socks5 = proxy)
    private val setup = Setup(servers = listOf(server), routes = listOf(Route("r", own = "p", socks5 = proxy)), defaultRoute = DIRECT)

    @Test fun directDecisions() {
        assertEquals(ProxyDirect.Grant(false, DIRECT), setup.proxyDirect(null, app, false))
        val direct = setup.copy(apps = mapOf(app.pkg to DIRECT), appSigners = mapOf(app.pkg to pin))
        assertEquals(ProxyDirect.Already, direct.proxyDirect(null, app, false))
        assertTrue(setup.copy(apps = mapOf(app.pkg to BLOCK)).proxyDirect(null, app, false) is ProxyDirect.Refuse)
        assertTrue(direct.proxyDirect(null, app.copy(identity = AppIdentity("bb".repeat(32), emptySet())), false) is ProxyDirect.Refuse)
        assertTrue(direct.copy(waiting = setOf(app.pkg), waitingUntil = Long.MAX_VALUE).proxyDirect(null, app, false) is ProxyDirect.Refuse)
        assertTrue(direct.proxyDirect(null, app, true) is ProxyDirect.Refuse)
        assertTrue(setup.proxyDirect("missing", app, false) is ProxyDirect.Refuse)
        val filtered = direct.copy(prefs = direct.prefs.copy(filter = Filter(id = "own", value = "9.9.9.9", directApps = true)))
        assertEquals(ProxyDirect.Grant(true, DIRECT), filtered.proxyDirect(null, app, false))
        assertTrue(filtered.copy(profiles = listOf(Profile("Other", DIRECT, apps = mapOf(app.pkg to DIRECT))))
            .proxyDirect(null, app, false) is ProxyDirect.Refuse)
    }

    @Test fun udpUpdatesAllCopiesAndRejectsStaleOrUnconfirmedEnable() {
        val off = setup.setProxyUdp(server, false)
        assertFalse(off.servers.single().socks5!!.allowUdp)
        assertFalse(off.routes.single().socks5!!.allowUdp)
        assertEquals(off, off.setProxyUdp(server, true))
        val on = off.setProxyUdp(off.servers.single(), true)
        assertTrue(on.servers.single().socks5!!.allowUdp)
        assertTrue(on.routes.single().socks5!!.allowUdp)
        assertFalse(on.setProxyUdp(off.servers.single(), false).servers.single().socks5!!.allowUdp)
        val waiting = off.copy(servers = listOf(off.servers.single().copy(keyless = true)))
        assertEquals(waiting, waiting.setProxyUdp(waiting.servers.single(), true))
        val unconfirmed = off.copy(servers = listOf(off.servers.single().copy(socks5 = proxy.copy(needsConfirmation = true))))
        assertEquals(unconfirmed, unconfirmed.setProxyUdp(unconfirmed.servers.single(), true))
    }

    @Test fun refillRetainsUdpOnlyForTheSameConfirmedListenerAndLogin() {
        val unchanged = setup.refilled(server.id, server, server)!!
        assertTrue(unchanged.servers.single().socks5!!.allowUdp)
        // The page that edits it doesn't carry the choice: it is kept as saved, and never turned on.
        assertTrue(setup.refilled(server.id, server.copy(socks5 = proxy.copy(allowUdp = false)), server)!!.servers.single().socks5!!.allowUdp)
        val off = setup.setProxyUdp(server, false)
        assertFalse(off.refilled(server.id, server, off.servers.single())!!.servers.single().socks5!!.allowUdp)
        for (changed in listOf(proxy.copy(port = 1081), proxy.copy(packageName = "org.example.other"),
            proxy.copy(identity = "bb".repeat(32)), proxy.copy(username = "other"), proxy.copy(password = "other"))) {
            val result = setup.refilled(server.id, server.copy(socks5 = changed), server)!!
            assertFalse(result.servers.single().socks5!!.allowUdp)
            assertFalse(result.routes.single().socks5!!.allowUdp)
        }
        val waitingServer = server.copy(socks5 = proxy.copy(needsConfirmation = true))
        val waiting = setup.copy(servers = listOf(waitingServer))
        assertFalse(waiting.refilled(server.id, server, waitingServer)!!.servers.single().socks5!!.allowUdp)
    }

    @Test fun goingDirectChangesOnlyThisAppAndMeetsWhatTheRouteNeeds() {
        val filter = Filter(id = "own", value = "9.9.9.9", directApps = true)
        val s = setup.copy(apps = mapOf("org.example.other" to DIRECT), prefs = setup.prefs.copy(filter = filter))
        val next = s.goingDirect(null, app.pkg, app.identity!!)
        assertTrue(next.outside(app.pkg))
        assertEquals(pin, next.appSigners[app.pkg])
        assertEquals(DIRECT, next.apps["org.example.other"])
        assertFalse(next.outside("org.example.other"))
        assertNull(Compiler.socksRouteProblem(next, next.routes.single()))
        assertEquals(ProxyDirect.Already, next.proxyDirect(null, app, false))
        // No filter set: no override is left behind.
        assertEquals(setup.prefs, setup.goingDirect(null, app.pkg, app.identity!!).prefs)
    }

    @Test fun aProfileBeingEditedNeverChangesTheOneInUse() {
        val s = setup.copy(profile = "Home", profiles = listOf(Profile("Home", DIRECT), Profile("Travel", DIRECT)))
        val next = s.goingDirect("Travel", app.pkg, app.identity!!)
        assertFalse(app.pkg in next.apps)
        assertEquals(DIRECT, next.profiles.single { it.name == "Travel" }.apps[app.pkg])
    }

    @Test fun anInactiveProfileNeverTakesTheFilterFromTheOneInUse() {
        val filter = Filter(id = "own", value = "9.9.9.9", directApps = true)
        val s = setup.copy(profile = "Home", apps = mapOf(app.pkg to DIRECT), appSigners = mapOf(app.pkg to pin), prefs = setup.prefs.copy(filter = filter),
            profiles = listOf(Profile("Home", DIRECT, apps = mapOf(app.pkg to DIRECT)), Profile("Travel", DIRECT)))
        assertTrue(s.proxyDirect("Travel", app, false) is ProxyDirect.Refuse)
        // Alone in the profile in use, it is that profile's own change.
        assertTrue(s.proxyDirect(null, app, false) is ProxyDirect.Grant)
        assertEquals(s.proxyDirect(null, app, false), s.proxyDirect("Home", app, false))
    }

    @Test fun whatWasShownIsNotWhatAnotherRouteWouldLeave() {
        val s = setup.copy(routes = setup.routes + Route("n", tsExit = "100.64.0.1"))
        assertNotEquals(s.proxyDirect(null, app, false), s.copy(defaultRoute = "n").proxyDirect(null, app, false))
        assertNotEquals(s.proxyDirect(null, app, false), s.copy(apps = mapOf(app.pkg to "n"), appSigners = mapOf(app.pkg to pin)).proxyDirect(null, app, false))
    }

    @Test fun textNamesTheRouteTheAppLeaves() {
        val s = setup.copy(routes = setup.routes + Route("n", wg = null, tsExit = "100.64.0.1", tsName = "Netherlands"), defaultRoute = "n")
        assertTrue(directText(s, app.pkg, "Harbor", s.proxyDirect(null, app, false)).startsWith("Harbor now follows the default, Netherlands. It will go Direct, without a VPN,"))
        assertTrue("or the DNS filter" in directText(s.copy(apps = mapOf(app.pkg to "n")), app.pkg, "Harbor", ProxyDirect.Grant(true, "n")))
        assertTrue("isn't changed here because it is on Block" in directText(s, app.pkg, "Harbor", s.copy(apps = mapOf(app.pkg to BLOCK)).proxyDirect(null, app, false)))
    }

    @Test fun statusSaysNoMoreThanIsKnown() {
        val ok = setup.copy(apps = mapOf(app.pkg to DIRECT, "org.example.browser" to "r"))
        val on = VpnState(VpnState.Phase.On, engine = 3)
        val answered = on.copy(passed = Health.Passed(3, 1, mapOf("r" to Health.checkedAs(ok.route("r")!!))))
        fun words(s: Setup, vpn: VpnState, running: Set<String>? = setOf("r"), silent: Boolean = false) = proxyStatus(s, s.servers.single(), vpn, silent, running, 1).second
        assertEquals("Not connected", words(ok, VpnState(), null))
        // Saved in a route no app uses: not a fault, whether the VPN runs or not.
        val idle = ok.copy(apps = mapOf(app.pkg to DIRECT))
        assertEquals("No app uses it yet", words(idle, VpnState(), null))
        assertEquals("No app uses it yet", words(idle, answered, emptySet()))
        assertEquals("Not connected", words(ok, answered, emptySet()))
        assertEquals("Checking", words(ok, on)) // a wrong login looks like this until its first check fails
        assertEquals("Connected", words(ok, answered))
        assertEquals("Checking", words(ok, answered.copy(engine = 4)))
        assertEquals("Not answering", words(ok, answered.copy(notAnswering = setOf("r"))))
        assertEquals("Its proxy isn't running", words(ok, answered, silent = true))
        assertEquals("Its proxy isn't running", words(ok, VpnState(), null, silent = true))
        val unchecked = ok.copy(routes = ok.routes.map { it.copy(checkInternet = false) })
        assertEquals("Running, checks off", words(unchecked, answered))
        for (silent in listOf(false, true)) assertTrue(words(ok, on.copy(lockdown = true), emptySet(), silent).startsWith("Offline: Block connections"))
        for (net in listOf(null, 0L, 2L)) assertEquals("Checking", proxyStatus(ok, server, answered, false, setOf("r"), net).second) // another network's check
        assertEquals("Its app needs Direct", words(ok.copy(apps = ok.apps - app.pkg), answered))
        assertEquals("Not used", words(ok.copy(routes = emptyList(), apps = mapOf(app.pkg to DIRECT)), VpnState(), null))
        assertEquals("Confirm its app", words(ok.copy(servers = listOf(server.copy(socks5 = proxy.copy(needsConfirmation = true)))), answered))
    }

    @Test fun textNeverSaysThatAnAppOnDirectWillMove() {
        val text = directText(setup, app.pkg, "Harbor", setup.proxyDirect(null, app, false))
        assertTrue(text, text.startsWith("Harbor goes Direct like your other apps"))
        val none = setup.copy(defaultRoute = "")
        assertTrue(directText(none, app.pkg, "Harbor", none.proxyDirect(null, app, false)).startsWith("Harbor will go Direct, without a VPN,"))
    }

    @Test fun likelyAppsComeFirstAndSystemAppsOnlyWhenSearched() {
        val system = app.copy(pkg = "com.example.system", label = "Car", system = true)
        val other = app.copy(pkg = "org.example.notes", label = "Notes")
        val unverified = app.copy(pkg = "org.example.gone", identity = null)
        val all = listOf(app, system, other, unverified)
        assertEquals(listOf(app) to listOf(other), proxyApps(all, setOf(app.pkg), ""))
        assertEquals(emptyList<AppInfo>() to listOf(system), proxyApps(all, setOf(app.pkg), "car"))
    }
}
