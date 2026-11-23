package app.unbraid

import app.unbraid.data.*
import app.unbraid.vpn.Compiler
import app.unbraid.vpn.FakePool
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.spec.SecretKeySpec

class AppIdentityPolicyTest {
    @get:Rule val tmp = TemporaryFolder()
    private val pkg = "org.example.mail"
    private val other = "org.example.shared"
    private val a = "a1".repeat(32)
    private val b = "b2".repeat(32)
    private val c = "c3".repeat(32)
    private val current = AppIdentity(a, emptySet())
    private val rotated = AppIdentity(b, setOf(a))
    private val replacement = AppIdentity(c, emptySet())
    private fun setup(route: String = DIRECT) = Setup(defaultRoute = DIRECT, apps = mapOf(pkg to route), appSigners = mapOf(pkg to a))
    private fun input(s: Setup) = Compiler.Input(s, emptyMap(), "app.unbraid", false, "", "")

    @Test fun grantsRequireTheBoundSignerEvenWhenDefaultIsDirect() {
        for (route in listOf(DIRECT, "nl")) {
            val saved = setup(route)
            assertEquals(route, saved.checkedApps { current }.apps[pkg])
            assertEquals(route, saved.checkedApps { rotated }.apps[pkg])
            assertEquals(BLOCK, saved.checkedApps { replacement }.apps[pkg])
            assertEquals(BLOCK, saved.checkedApps { null }.apps[pkg])
            assertEquals(route, saved.apps[pkg])
            assertEquals(BLOCK, saved.copy(appSigners = emptyMap()).checkedApps { error("unsigned") }.apps[pkg])
        }
        assertEquals(BLOCK, setup(BLOCK).checkedApps { error("blocked apps need no check") }.apps[pkg])
        assertTrue(setup().copy(apps = emptyMap()).checkedApps { error("no new permission") }.apps.isEmpty())
    }

    @Test fun signerSetsMustMatchExactly() {
        val both = AppIdentity("$a,$b", setOf(c))
        assertTrue(both.accepts("$a,$b"))
        for (partial in listOf(a, b, c, "$a,$c", "$b,$a", "")) assertFalse(both.accepts(partial))
        assertFalse(AppIdentity(c, setOf("$a,$b")).accepts("$a,$b"))
    }

    @Test fun aVerifiedRotationIsPersistedInEveryMatchingProfileAndRetriedAfterWriteFailure() {
        val dir = tmp.newFolder()
        val key = SecretKeySpec(ByteArray(32), "AES")
        val store = Store(dir) { key }
        val otherProfile = Profile("Other", DIRECT).assigned(pkg, DIRECT, c)
        val inactive = Profile("Same", DIRECT).assigned(pkg, "nl", a)
        val unsigned = Profile("Legacy", DIRECT).assigned(pkg, DIRECT)
        assertTrue(store.update { setup().copy(profiles = listOf(otherProfile, inactive, unsigned)) })
        val temporary = dir.resolve("setup.bin.tmp").apply { mkdir(); resolve("unavailable").writeText("x") }
        store.refreshAppSigners { rotated }
        assertTrue(store.unsaved.value)
        assertEquals(b, store.setup.value.appSigners[pkg])
        assertTrue(temporary.deleteRecursively())
        store.refreshAppSigners { rotated }
        assertFalse(store.unsaved.value)
        val saved = Store(dir) { key }.setup.value
        assertEquals(b, saved.appSigners[pkg])
        assertEquals(listOf(c, b, null), saved.profiles.map { it.appSigners[pkg] })
        assertEquals(BLOCK, saved.checkedApps { current }.apps[pkg])
        assertEquals(BLOCK, saved.checkedApps { AppIdentity(c, setOf(a)) }.apps[pkg])
        assertEquals(DIRECT, saved.checkedApps { AppIdentity(c, setOf(a, b)) }.apps[pkg])
    }

    @Test fun backupRestoresGrantsOnlyForTheirOwnSignerIncludingAbsentAppsAndKeptProfiles() {
        val incoming = setup().copy(appSigners = mapOf(pkg to b))
        val exported = Backup.read(Backup.export(incoming, "", 0, keys = false)).setup
        val restored = setup().restoredFrom(exported, BEFORE_RESTORE, 0)!!
        assertEquals(BLOCK, restored.checkedApps { null }.apps[pkg])
        assertEquals(BLOCK, restored.checkedApps { current }.apps[pkg])
        assertEquals(DIRECT, restored.checkedApps { rotated }.apps[pkg])
        val kept = restored.profiles.single { it.name == BEFORE_RESTORE }
        assertEquals(a, kept.appSigners[pkg])
        assertEquals(DIRECT, kept.checkedApps { current }.apps[pkg])
        assertEquals(b, restored.appSigners[pkg])
        val rollback = incoming.back(setup())
        assertEquals(a, rollback.appSigners[pkg])
        assertEquals(BLOCK, rollback.checkedApps { replacement }.apps[pkg])
    }

    @Test fun aFailedSwitchDoesNotRollBackTheSignerOfTheProfileThatRan() {
        val ran = setup().saveAs("Work")
        val switched = ran.copy(profiles = ran.saved.map { it.copy(appSigners = mapOf(pkg to b)) },
            profile = null, apps = emptyMap(), appSigners = emptyMap())
        val back = switched.back(ran)
        assertEquals(b, back.appSigners[pkg])
        assertEquals(BLOCK, back.checkedApps { current }.apps[pkg])
        val different = switched.copy(profiles = switched.profiles.map { it.assigned(pkg, "nl", b) }).back(ran)
        assertNull(different.appSigners[pkg])
    }

    @Test fun largeAndUnsignedBackupsDeclareTheirActualProtectionRequirements() {
        val grants = (0..550).associate { "org.example.app$it" to DIRECT }
        val large = setup().copy(apps = grants, appSigners = grants.mapValues { a })
        assertEquals(large.appSigners, Backup.read(Backup.export(large, "", 0)).setup.appSigners)
        assertEquals(5, large.copy(appSigners = emptyMap()).stamped.version)
        assertEquals(5, Setup(profiles = listOf(Profile("Legacy", DIRECT, grants))).stamped.version)
        assertEquals(2, Setup(version = VERSION).stamped.version)
    }

    @Test fun androidExclusionsRequireThatAllUidMembersCanStillGoOutside() {
        val s = setup().editing(null) { it.assigned(other, DIRECT, b) }
        val kin = setOf(pkg, other)
        assertTrue(Compiler.mayExclude(s, pkg, kin) { if (it == pkg) current else rotated })
        assertFalse(Compiler.mayExclude(s, pkg, kin) { current })
        assertFalse(Compiler.mayExclude(s, pkg, null) { current })
        assertFalse(Compiler.mayExclude(s, pkg, emptySet()) { current })
        assertFalse(Compiler.mayExclude(s, pkg, setOf(other)) { current })
        assertFalse(Compiler.mayExclude(s.copy(apps = s.apps + (other to BLOCK)), pkg, kin) { current })
    }

    @Test fun lateOwnerMismatchRejectsDnsAndConnectionsBeforeAnyDirectDefault() {
        val s = setup().checkedApps { current }
        assertEquals(listOf(pkg, other), Compiler.ownerNames(s, listOf(pkg, other)) { current })
        assertEquals(listOf(other), Compiler.ownerNames(s, listOf(other)) { error("unassigned") })
        val refused = Compiler.ownerNames(s, listOf(other, pkg)) { replacement }
        assertEquals(listOf(Compiler.UNVERIFIED_OWNER), refused)
        val json = Json.parseToJsonElement(Compiler.compile(input(s))).jsonObject
        for (section in listOf("dns", "route")) {
            val first = json.getValue(section).jsonObject.getValue("rules").jsonArray.first().jsonObject
            assertEquals("reject", first.getValue("action").jsonPrimitive.content)
            assertTrue(JsonPrimitive(refused.single()) in first.getValue("package_name").jsonArray)
        }
    }

    @Test fun signedAppsKeepVpnDefaultPingLanAndTailnetDns() {
        val policy = setup("exit").copy(routes = listOf(Route("exit", tsExit = "100.64.0.1")), defaultRoute = "exit",
            home = Home(tailscale = true), prefs = Prefs(lan = true, blockLan = true))
        val signed = Json.parseToJsonElement(Compiler.compile(input(policy.checkedApps { current }))).jsonObject
        val baseline = Json.parseToJsonElement(Compiler.compile(input(policy.copy(appSigners = emptyMap())))).jsonObject
        assertEquals(baseline["inbounds"], signed["inbounds"]) // Android still bypasses the authorized LAN networks
        for (section in listOf("route", "dns")) {
            val rules = signed.getValue(section).jsonObject.getValue("rules").jsonArray
            val ordinary = rules.filterNot { JsonPrimitive(Compiler.UNVERIFIED_OWNER) in it.jsonObject["package_name"]?.jsonArray.orEmpty() }
            assertEquals(baseline.getValue(section).jsonObject.getValue("rules").jsonArray.toList(), ordinary)
        }
        val dns = signed.getValue("dns").jsonObject
        assertEquals("dns-tailnet", RoutingProbe.lookup(dns, "", "peer.ts.net", "TXT"))
        assertEquals("local", RoutingProbe.lookup(dns, "", "printer.lan", "TXT"))
    }

    @Test fun aMoveAndARollbackRevalidateWhatPreviouslyRan() {
        val last = input(setup().checkedApps { current })
        for (move in listOf(false, true)) {
            val held = FakePool.restoreInput(last, null, 2, 3 to "test", null, null, false, false, move,
                { it.checkedApps { replacement } }) { emptySet() }
            assertEquals(BLOCK, held.setup.apps[pkg])
            assertFalse(held.setup.outside(pkg))
        }
    }
}
