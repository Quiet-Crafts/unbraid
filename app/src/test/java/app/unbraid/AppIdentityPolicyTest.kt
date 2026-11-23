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

    @Test fun onlyTheExpectedTimelyHolderCanConsumeAnInstallationWait() {
        val waiting = setup().copy(waiting = setOf(pkg), waitingUntil = 100)
        assertEquals(6, waiting.stamped.version)
        val missing = listOf(InstallState.Unknown, InstallState.Present(null, current), InstallState.Present(0, current),
            InstallState.Present(999, current), InstallState.Present(50, null), InstallState.Present(50, replacement))
        for (state in missing) {
            val saved = waiting.resolveWaiting(200, { state })
            assertTrue(pkg in saved.waiting)
            assertEquals(DIRECT, saved.apps[pkg])
            assertEquals(BLOCK, saved.resolveWaiting(200, { state }, effective = true).apps[pkg])
        }
        assertTrue(waiting.resolveWaiting(200, { InstallState.Absent }).apps.isEmpty())
        assertTrue(waiting.resolveWaiting(200, { InstallState.Present(100, current) }).appSigners.isEmpty())
        assertTrue(waiting.resolveWaiting(99, { InstallState.Present(null, current) }).waiting.isEmpty())
        assertTrue(pkg in waiting.resolveWaiting(99, { InstallState.Present(null, current, observedAt = 100) }).waiting)
        val timely = waiting.resolveWaiting(200, { InstallState.Present(99, rotated) })
        assertEquals(DIRECT, timely.apps[pkg])
        assertEquals(b, timely.appSigners[pkg])
        assertEquals(5, timely.stamped.version)
        assertTrue(timely.waiting.isEmpty())
        assertEquals(BLOCK, timely.checkedApps { current }.apps[pkg])
        assertEquals(timely, timely.resolveWaiting(500, { error("no pending package") }))
    }

    @Test fun identicalChoicesInTwoProfilesKeepTheirOwnDeadlineAndIdentity() {
        val old = setup().copy(waiting = setOf(pkg), waitingUntil = 100)
        val incoming = setup().copy(appSigners = mapOf(pkg to c))
        val restored = old.restoredWithInstalls(incoming, BEFORE_RESTORE, 50) { InstallState.Absent }!!
        val kept = restored.profiles.single()
        assertEquals(100L, restored.deadline(kept, pkg))
        assertEquals(50 + WAIT_MS, restored.deadline(restored.live, pkg))
        var reads = 0
        val settled = restored.resolveWaiting(201, {
            reads++
            InstallState.Present(80, current)
        })
        assertEquals(1, reads)
        assertNull(settled.deadline(settled.profiles.single(), pkg))
        assertNotNull(settled.deadline(settled.live, pkg))
        assertEquals(DIRECT, settled.use(kept.name).resolveWaiting(201, { InstallState.Present(80, current) }, true).apps[pkg])
        assertEquals(BLOCK, settled.resolveWaiting(201, { InstallState.Present(80, current) }, true).apps[pkg])
        val renamed = settled.rename(kept.name, "Old")
        assertNull(renamed.deadline(renamed.profiles.single(), pkg))
        val late = restored.resolveWaiting(201, { InstallState.Present(100, replacement) })
        assertFalse(pkg in late.profiles.single().apps)
        assertNull(late.deadline(late.live, pkg))
        assertEquals(DIRECT, late.apps[pkg])
        assertTrue(late.waiting.isEmpty())
    }

    @Test fun importedWaitMarkersCannotExemptTheNewSetupOrAnInactiveProfile() {
        val donor = setup().copy(waitOverrides = mapOf(pkg to Long.MIN_VALUE),
            profiles = listOf(Profile("Home", DIRECT).assigned(pkg, DIRECT, a).copy(waitOverrides = mapOf(pkg to Long.MIN_VALUE))))
        val fresh = Setup().restoredWithInstalls(donor, null, 90) { InstallState.Absent }!!
        assertEquals(90 + WAIT_MS, fresh.deadline(fresh.live, pkg))
        assertEquals(90 + WAIT_MS, fresh.deadline(fresh.profiles.single(), pkg))
        val portable = Backup.contents(fresh)
        assertTrue(portable.waiting.isEmpty() && portable.waitOverrides.isEmpty() && portable.profiles.single().waitOverrides.isEmpty())
        val late = fresh.resolveWaiting(100 + WAIT_MS, { InstallState.Present(90 + WAIT_MS, current) })
        assertTrue(late.apps.isEmpty() && late.profiles.single().apps.isEmpty())
    }

    @Test fun knownLateInstallClearsTheDirectFilterOnlyAfterItsLastGrantAndRollbackKeepsExpiry() {
        val local = setup().copy(prefs = Prefs(filter = Filter(apps = mapOf(pkg to false))), waiting = setOf(pkg), waitingUntil = 100).saveAs("Old")
        val late = local.resolveWaiting(200, { InstallState.Present(150, current) })
        assertFalse(pkg in late.prefs.filter.apps)
        assertFalse(pkg in late.back(local).apps)
        val two = local.copy(profiles = local.profiles + Profile("Later", DIRECT).assigned(pkg, DIRECT, a).copy(waitOverrides = mapOf(pkg to 190)))
        val kept = two.resolveWaiting(200, { InstallState.Present(150, current) })
        assertTrue(pkg in kept.prefs.filter.apps)
        assertTrue(pkg in kept.profiles.last().apps)
    }

    @Test fun headlessSettlementSurvivesARestartAndAMissingPackageIsReadOnlyOnce() {
        val dir = tmp.newFolder()
        val key = SecretKeySpec(ByteArray(32), "AES")
        val store = Store(dir) { key }
        val raw = setup().copy(waiting = setOf(pkg), waitingUntil = 100, profiles = listOf(Profile("P", DIRECT).assigned(pkg, DIRECT, b)))
        assertTrue(store.update { raw })
        var calls = 0
        store.refreshWaiting(200) { calls++; InstallState.Present(99, current) }
        assertEquals(1, calls)
        val read = Store(dir) { key }.setup.value
        assertNull(read.deadline(read.live, pkg))
        assertEquals(100L, read.deadline(read.profiles.single(), pkg))
        val gone = Store(dir) { key }
        gone.refreshWaiting(250) { InstallState.Present(120, rotated) }
        val finished = Store(dir) { key }.setup.value
        assertTrue(finished.waiting.isEmpty())
        assertEquals(DIRECT, finished.apps[pkg])
        assertFalse(pkg in finished.profiles.single().apps)
        var unknownReads = 0
        val kept = raw.restoredWithInstalls(raw, BEFORE_RESTORE, 120) { unknownReads++; InstallState.Unknown }!!
        assertEquals(1, unknownReads)
        assertTrue(pkg in kept.waiting)
    }

    @Test fun normalAndRecoveryConfigsExcludeOnlyATimelyVerifiedDirectGrant() {
        val saved = setup().copy(waiting = setOf(pkg), waitingUntil = 100)
        fun excluded(s: Setup): Set<String> {
            val inbounds = Json.parseToJsonElement(Compiler.compile(input(s))).jsonObject.getValue("inbounds").jsonArray
            return inbounds.map { it.jsonObject }.single { it["type"]?.jsonPrimitive?.content == "tun" }
                .getValue("exclude_package").jsonArray.mapTo(HashSet()) { it.jsonPrimitive.content }
        }
        for ((first, expected) in listOf(99L to true, 100L to false, 101L to false)) {
            val project = { s: Setup -> s.resolveWaiting(200, { InstallState.Present(first, current) }, true).checkedApps { current } }
            val checked = project(saved)
            assertEquals(expected, pkg in excluded(checked))
            assertEquals(expected, Compiler.mayExclude(checked, pkg, setOf(pkg)) { current })
            for (move in listOf(true, false)) {
                val recovery = FakePool.restoreInput(input(saved), null, 2, 3 to "test", null, null, false, false, move, project) { emptySet() }
                assertEquals(expected, pkg in excluded(recovery.setup))
            }
        }
    }

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
