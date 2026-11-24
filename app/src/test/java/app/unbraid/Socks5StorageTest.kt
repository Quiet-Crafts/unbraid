package app.unbraid

import app.unbraid.data.Backup
import app.unbraid.data.DIRECT
import app.unbraid.data.OwnServer
import app.unbraid.data.Profile
import app.unbraid.data.RestoreTraffic
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.data.Socks5Profile
import app.unbraid.data.Store
import app.unbraid.data.newSetup
import app.unbraid.data.refilled
import app.unbraid.data.restoredFrom
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class Socks5StorageTest {
    @get:Rule val tmp = TemporaryFolder()
    private val proxy = Socks5Profile("Local proxy", "127.0.0.1", 1080, "org.example.proxy", "aa".repeat(32), "private-user", "private-pass")
    private val server = OwnServer("p", socks5 = proxy)
    private val route = Route("r", own = server.id, socks5 = proxy)
    private val setup = Setup(routes = listOf(route), servers = listOf(server), defaultRoute = DIRECT, added = listOf(route.id))
    private fun file(keys: Boolean) = Backup.read(Backup.export(setup, "3.2", 0, keys))

    @Test fun onlyVerifiedLoopbackAndCompleteCredentialsAreAccepted() {
        assertEquals(proxy, proxy.checked())
        assertEquals("é".repeat(127), proxy.copy(password = "é".repeat(127)).checked().password)
        for (invalid in listOf(proxy.copy(host = "::1"), proxy.copy(host = "localhost"), proxy.copy(host = "127.0.0.2"),
                proxy.copy(port = 65536), proxy.copy(port = 0), proxy.copy(packageName = "a b"), proxy.copy(identity = "unknown"),
                proxy.copy(password = ""), proxy.copy(username = ""), proxy.copy(password = "é".repeat(128)))) {
            assertThrows(IllegalArgumentException::class.java) { invalid.checked() }
        }
    }

    @Test fun onlyDisplayChangesReuseTheVerifiedConnection() {
        assertTrue(proxy.sameConnection(proxy.copy(name = "Renamed")))
        for (changed in listOf(proxy.copy(port = 1090), proxy.copy(username = "other"), proxy.copy(password = "other"),
            proxy.copy(packageName = "org.example.other"), proxy.copy(identity = "ab".repeat(32)), proxy.copy(needsConfirmation = true))) {
            assertFalse(proxy.sameConnection(changed))
        }
    }

    @Test fun sealedSetupRoundTripsAndOnlyFeaturesRaiseFormat() {
        assertEquals(7, setup.copy(routes = emptyList()).stamped.version)
        assertEquals(7, setup.copy(servers = emptyList()).stamped.version)
        assertEquals(2, newSetup().stamped.version)
        val dir = tmp.newFolder()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = Store(dir) { key }
        assertTrue(store.update { setup })
        val bytes = dir.resolve("setup.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains(proxy.password))
        assertFalse(bytes.contains(proxy.username))
        assertEquals(proxy, Store(dir) { key }.setup.value.route(route.id)?.socks5)
    }

    @Test fun udpConsentPersistsLocallyButMustBeChosenAgainAfterRestore() {
        assertFalse(proxy.allowUdp)
        val allowed = proxy.copy(allowUdp = true)
        assertFalse(proxy.sameConnection(allowed))
        val enabled = setup.copy(routes = listOf(route.copy(socks5 = allowed)), servers = listOf(server.copy(socks5 = allowed)))
        assertEquals(8, enabled.stamped.version)
        val dir = tmp.newFolder()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertTrue(Store(dir) { key }.update { enabled })
        assertTrue(Store(dir) { key }.setup.value.routes.single().socks5!!.allowUdp)
        for (keys in listOf(false, true)) {
            val bytes = Backup.export(enabled, "3.2", 0, keys)
            val saved = Backup.read(bytes).setup
            assertEquals(app.unbraid.data.VERSION, saved.version)
            val restored = setup.restoredFrom(saved, null, 0, keys)!!
            assertFalse(restored.servers.single().socks5!!.allowUdp)
            assertFalse(restored.routes.single().socks5!!.allowUdp)
            assertTrue(restored.servers.single().socks5!!.needsConfirmation)
            val forged = bytes.toString(Charsets.UTF_8).replace("\"version\":8", "\"version\":7")
            assertThrows(Backup.Failed::class.java) { Backup.read(forged.toByteArray()) }
        }
    }

    @Test fun creatingOrAssigningALocalProxyNeverTakesTheDefault() {
        val fresh = newSetup()
        val imported = fresh.imported(route.id, route, alone = true)
        assertEquals(fresh.defaultRoute, imported.defaultRoute)
        assertNotNull(imported.route(route.id))
        val nonProxy = Route("vpn")
        // Chosen by the user, live or in a saved profile, it is a default; older builds then refuse the whole setup.
        for (chosen in listOf(setup.copy(defaultRoute = route.id), setup.copy(profiles = listOf(Profile("Other", route.id))))) {
            assertTrue(chosen.supportsSocks5Assignments())
            assertEquals(9, chosen.stamped.version)
        }
        assertTrue(setup.stamped.version < 9)
        val bad = listOf(
            setup.copy(sites = mapOf("test.example" to route.id)),
            setup.copy(profiles = listOf(Profile("Other", DIRECT, sites = mapOf("test.example" to route.id)))),
            setup.copy(routes = listOf(nonProxy, route.copy(via = nonProxy.id))),
            setup.copy(routes = listOf(route, nonProxy.copy(via = route.id))))
        for (attempt in bad) {
            assertFalse(attempt.supportsSocks5Assignments())
            assertThrows(Backup.Failed::class.java) { Backup.valid(attempt) }
            assertNull(setup.restoredFrom(attempt, null, 0))
        }
        assertTrue(setup.copy(apps = mapOf("org.example.browser" to route.id)).supportsSocks5Assignments())
    }

    /** A proxy default whose own app isn't on Direct there would leave every app on it offline: named, for each profile. */
    @Test fun aDefaultWhoseProxyAppIsNotDirectIsNamed() {
        val pkg = route.socks5!!.packageName
        val ok = setup.copy(defaultRoute = route.id, apps = mapOf(pkg to DIRECT))
        assertEquals(emptyMap<String?, String>(), ok.stuckDefaults())
        assertEquals(mapOf<String?, String>(null to pkg), ok.copy(apps = emptyMap()).stuckDefaults()) // it would follow its own proxy
        assertEquals(mapOf<String?, String>(null to pkg), ok.copy(apps = mapOf(pkg to app.unbraid.data.BLOCK)).stuckDefaults())
        val filtered = ok.copy(prefs = ok.prefs.copy(filter = app.unbraid.data.Filter("quad9", directApps = true)))
        assertEquals(mapOf<String?, String>(null to pkg), filtered.stuckDefaults())
        // Another profile keeps its own app choices: Direct at home doesn't make it Direct on the trip.
        val two = ok.copy(profile = "Home", profiles = listOf(Profile("Home", route.id, mapOf(pkg to DIRECT)), Profile("Trip", route.id)))
        assertEquals(mapOf<String?, String>("Trip" to pkg), two.stuckDefaults())
        assertEquals(emptyMap<String?, String>(), setup.copy(apps = mapOf("org.example.browser" to route.id)).stuckDefaults()) // only a default
    }

    @Test fun everyRestoreRequiresLocalConfirmationEvenOnTheSamePhone() {
        for (keys in listOf(false, true)) {
            val b = file(keys)
            assertEquals(7, b.setup.stamped.version)
            val restored = setup.restoredFrom(b.setup, null, 0, keys)!!
            val paused = restored.servers.single()
            assertTrue(paused.socks5!!.needsConfirmation)
            assertEquals(keys, !paused.keyless)
            assertEquals(if (keys) proxy.password else "", paused.socks5.password)
            assertEquals(if (keys) proxy.username else "", paused.socks5.username)
            assertTrue(paused.socks5.authRequired)
            assertEquals(paused.socks5, restored.routes.single().socks5)
            assertTrue(RestoreTraffic.notes(newSetup(), restored).any { it.contains("confirm the proxy app") })
            assertNull(restored.refilled(server.id, OwnServer("other"), expectedProxy = paused))
            assertNull(restored.refilled(server.id, server))
            assertNull(restored.refilled(server.id, server.copy(socks5 = proxy.copy(username = "", password = "", authRequired = false)), expectedProxy = paused))
            assertEquals(proxy, restored.refilled(server.id, server, expectedProxy = paused)!!.routes.single().socks5)
        }
        val text = Backup.export(setup, "3.2", 0, false).toString(Charsets.UTF_8)
        assertFalse(text.contains(proxy.password))
        assertFalse(text.contains(proxy.username))
        val anonymous = proxy.copy(username = "", password = "", authRequired = false)
        val a = setup.copy(routes = listOf(route.copy(socks5 = anonymous)), servers = listOf(server.copy(socks5 = anonymous)))
        assertTrue(setup.restoredFrom(Backup.read(Backup.export(a, "3.2", 0)).setup, null, 0)!!.routes.single().socks5!!.needsConfirmation)
    }

    @Test fun keepAsKeepsLocalTrustOnlyOnItsOwnCopies() {
        val restored = setup.restoredFrom(file(true).setup, "Keep", 0)!!
        val brought = restored.route(route.id)!!
        val kept = restored.routes.single { it.id != brought.id }
        assertTrue(brought.socks5!!.needsConfirmation)
        assertFalse(kept.socks5!!.needsConfirmation)
        assertNotEquals(kept.own, brought.own)
        assertTrue(restored.servers.single { it.id == brought.own }.socks5!!.needsConfirmation)
        assertFalse(restored.servers.single { it.id == kept.own }.socks5!!.needsConfirmation)
        assertEquals(kept.id, restored.profiles.single { it.name == "Keep" }.added.single())
    }

    @Test fun changingASavedProxyUpdatesAllCopiesOnlyOnTheExpectedRevision() {
        val replacement = server.copy(socks5 = proxy.copy(port = 1085))
        assertNull(setup.refilled(server.id, replacement))
        val changed = setup.refilled(server.id, replacement, expectedProxy = server)!!
        assertEquals(replacement.socks5, changed.route(route.id)?.socks5)
        assertNull(changed.refilled(server.id, server, expectedProxy = server))
        assertNull(changed.refilled(server.id, OwnServer("other"), expectedProxy = replacement))
    }

    @Test fun backupRejectsConflictingProxyCopies() {
        val stale = Backup.export(setup, "3.2", 0).toString(Charsets.UTF_8).replace("\"version\":7", "\"version\":6")
        assertThrows(Backup.Failed::class.java) { Backup.read(stale.toByteArray()) }
        assertThrows(Backup.Failed::class.java) { Backup.valid(setup.copy(routes = listOf(route.copy(socks5 = proxy.copy(port = 5555))))) }
        assertThrows(Backup.Failed::class.java) { Backup.valid(setup.copy(routes = listOf(route.copy(own = null)))) }
    }
}
