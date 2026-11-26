package app.unbraid

import app.unbraid.data.BLOCK
import app.unbraid.data.Backup
import app.unbraid.data.DIRECT
import app.unbraid.data.Location
import app.unbraid.data.Nord
import app.unbraid.data.Profile
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.data.VERSION
import app.unbraid.data.WgLink
import app.unbraid.vpn.Compiler
import app.unbraid.vpn.LinkPicker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Block: an app or a website set to it gets nothing, on any route, while the VPN is connected. */
class BlockTest {
    private val wgKey = "aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8tMTI="
    private fun sv(n: String) = Server("$n #1", "$n.nordvpn.com", "10.9.0.${n.hashCode() and 0x7f}", "5p4RkybdRU5uaDi90eu4KZPTFif0lKCg4Qp6t1c4F30=", 10)

    /** NL default, A on US, B blocked, C on Direct; example.com on US, ads.example blocked. */
    private val setup = Setup(
        routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands")), Route("us", Location("nord", 228, "US", "United States"))),
        defaultRoute = "nl", apps = mapOf("com.a" to "us", "com.b" to BLOCK, "com.c" to DIRECT), sites = mapOf("example.com" to "us", "ads.example" to BLOCK), version = VERSION,
    )
    private fun compile(s: Setup, lockdown: Boolean = false): JsonObject {
        val links = s.activeRoutes.associate { it.id to WgLink(sv(it.id), Nord.peer(sv(it.id), wgKey)) }
        return Json.parseToJsonElement(Compiler.compile(Compiler.Input(s, links, "app.unbraid", lockdown, "/tmp/ts", "/tmp/cache.db"))).jsonObject.also { assertRefusesOtherBlocks(it) }
    }
    private fun JsonObject.rules() = this["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.dns() = this["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.strings(k: String) = this[k]?.jsonArray?.map { it.jsonPrimitive.content }
    private fun JsonObject.text(k: String) = this[k]?.jsonPrimitive?.content

    /** A blocked app is refused before every rule that could carry it: the home network, the LAN, a website's route, any app's. */
    @Test fun aBlockedAppIsRefusedBeforeAnyRouteIsChosen() {
        for (lockdown in listOf(false, true)) {
            val rules = compile(setup, lockdown).rules()
            val bar = rules.indexOfFirst { it.strings("package_name") == listOf("com.b") }
            assertEquals("reject", rules[bar].text("action")); assertEquals("true", rules[bar].text("no_drop"))
            // Only one rule comes before it: the refusal of what a tunnel's far end opens. Its lookups aren't even taken over.
            assertTrue(bar <= 1 && rules.take(bar).all { it.text("action") == "reject" && it["inbound"] != null })
            assertTrue(bar < rules.indexOfFirst { it.text("action") == "hijack-dns" })
            // The other apps' lookups are taken over before anything unnamed is refused: blocking one app breaks no one's names.
            assertTrue(rules.indexOfLast { it.text("action") == "hijack-dns" } < rules.indexOfFirst { it.text("type") == "logical" })
            assertTrue(rules.drop(bar + 1).none { it.strings("package_name")?.contains("com.b") == true })
            assertTrue(bar < rules.indexOfFirst { it["domain"] != null } && bar < rules.indexOfFirst { it.strings("ip_cidr")?.contains("192.168.0.0/16") == true })
        }
        // It stays inside the VPN (an app left outside would go online on the normal connection), and no name is looked up for it.
        val c = compile(setup)
        // Only the app on Direct is left outside, never the blocked one.
        assertEquals(listOf("com.c", "app.unbraid"), c["inbounds"]!!.jsonArray[0].jsonObject.strings("exclude_package"))
        assertEquals(listOf("com.b") to "reject", c.dns().first().let { it.strings("package_name") to it.text("action") })
        assertTrue(c.dns().indexOfFirst { it.text("server") == "fake" } > 0)
    }

    /** A blocked website resolves for no one and is refused for every app; its names never reach a route. */
    @Test fun aBlockedWebsiteGoesNowhere() {
        val c = compile(setup)
        val dns = c.dns()
        val look = dns.indexOfFirst { it.strings("domain") == listOf("ads.example") }
        assertEquals("reject", dns[look].text("action")); assertEquals(listOf(".ads.example"), dns[look].strings("domain_suffix"))
        assertTrue(look < dns.indexOfFirst { it.text("server") == "fake" }) // before any address is handed out for it
        val rule = c.rules().single { it.strings("domain") == listOf("ads.example") }
        assertEquals("reject", rule.text("action")); assertEquals("true", rule.text("no_drop"))
        // A name under a blocked website with a route of its own takes that route: the most specific rule wins. Its
        // route comes before the block, and the block's lookup refusal leaves it out.
        val under = setup.copy(sites = mapOf("ads.example" to BLOCK, "cdn.ads.example" to "us"))
        val nested = compile(under).rules()
        val own = nested.indexOfFirst { it.strings("domain") == listOf("cdn.ads.example") && it["outbound"] != null }
        assertEquals("r-us", nested[own].text("outbound"))
        assertTrue(own < nested.indexOfFirst { it.strings("domain") == listOf("ads.example") && it.text("action") == "reject" })
        val (probeDns, probeRoute) = RoutingProbe.compiled(under, null)
        assertEquals("r-us", RoutingProbe.connect(probeRoute, probeDns, "com.a", "cdn.ads.example"))
        assertEquals("r-us", RoutingProbe.connect(probeRoute, probeDns, "com.a", "x.cdn.ads.example"))
        assertEquals("none", RoutingProbe.connect(probeRoute, probeDns, "com.a", "www.ads.example"))
        // The website with a route keeps it.
        assertEquals("r-us", c.rules().single { it.strings("domain") == listOf("example.com") && it["outbound"] != null }.text("outbound"))
    }

    /** The engine accepts a blocked website with a route of its own under it: a lookup refusal that leaves names out. */
    @Test fun theEngineAcceptsABlockWithARouteUnderIt() {
        val bin = java.io.File(System.getenv("SING_BOX_CLI") ?: "${System.getenv("WORK") ?: "${System.getProperty("user.home")}/.cache/unbraid-libbox"}/sing-box-cli")
        org.junit.Assume.assumeTrue("sing-box-cli not found", bin.canExecute())
        val f = java.io.File.createTempFile("block-under", ".json")
        try {
            f.writeText(compile(setup.copy(sites = mapOf("ads.example" to BLOCK, "cdn.ads.example" to "us"))).toString())
            val p = ProcessBuilder(bin.path, "check", "-c", f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue("sing-box check timed out", p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("sing-box check: $out", 0, p.exitValue())
        } finally { f.delete() }
    }

    /** With an app on Block, what can't be told apart from its traffic is refused too: connections of no known app, and pings. */
    @Test fun nothingUnnamedSlipsPastABlock() {
        val c = compile(setup)
        val rules = c.rules()
        val bar = rules.indexOfFirst { it.strings("package_name") == listOf("com.b") }
        // Behind the lookups, before the LAN and the home network: pings and connections of no known app, from the phone's apps only.
        val ping = rules.indexOfFirst { it.text("network") == "icmp" }
        assertTrue(ping > bar && ping < rules.indexOfFirst { it.strings("ip_cidr")?.contains("192.168.0.0/16") == true })
        assertEquals(listOf("tun") to "reject", rules[ping].let { it.strings("inbound") to it.text("action") })
        val unnamed = rules[ping + 1]
        assertEquals("logical" to "reject", unnamed.text("type") to unnamed.text("action"))
        assertEquals(listOf(listOf("tun"), null), unnamed["rules"]!!.jsonArray.map { it.jsonObject.strings("inbound") })
        assertEquals("true", unnamed["rules"]!!.jsonArray[1].jsonObject.text("invert"))
        // The LAN stays inside the VPN while an app is blocked, so that app can't reach the devices around it past these rules.
        val tun = c["inbounds"]!!.jsonArray[0].jsonObject
        assertTrue(tun["route_exclude_address"] == null && setup.prefs.lan)
        assertTrue(rules.any { it.strings("ip_cidr")?.contains("192.168.0.0/16") == true && it.text("outbound") == "direct" })
        // With that switch off the LAN bypasses the VPN as usual; the blocked app is still refused everything else.
        val lax = compile(setup.copy(prefs = setup.prefs.copy(blockLan = false)))
        assertTrue(lax["inbounds"]!!.jsonArray[0].jsonObject["route_exclude_address"] != null)
        assertEquals("reject", lax.rules().first { it.strings("package_name") == listOf("com.b") }.text("action"))
        val none = compile(setup.copy(apps = mapOf("com.a" to "us")))
        // Removing Block removes its blanket owner and ping refusals; distinct VPN assignments still guard data.
        assertTrue(none.rules().none { it["package_name_regex"] != null || it.text("network") == "icmp" || it == unnamed })
        assertTrue(none["inbounds"]!!.jsonArray[0].jsonObject["route_exclude_address"] != null)
    }

    @Test fun blockIsAChoiceNotARoute() {
        // Not a route that is gone, so nothing asks for another route; it needs no tunnel and counts against no limit.
        assertTrue(setup.appsOnMissing.isEmpty() && setup.sitesOnMissing.isEmpty() && Compiler.missing(setup).isEmpty())
        assertEquals(listOf("nl", "us"), setup.activeRoutes.map { it.id })
        // With no route at all (Direct default), a blocked app is still refused first and the engine config still builds.
        val bare = compile(Setup(routes = emptyList(), defaultRoute = DIRECT, apps = mapOf("com.b" to BLOCK), version = VERSION)).rules()
        assertEquals("reject", bare.first { it.strings("package_name") == listOf("com.b") }.text("action"))
        // Running with only Direct and Block still does something: it blocks.
        assertFalse(Setup(routes = emptyList(), defaultRoute = DIRECT, apps = mapOf("com.b" to BLOCK)).directOnly)
        // A newly blocked app is refused at once, also while routes wait for their servers.
        assertTrue(LinkPicker.leavesDirect(setup.copy(apps = mapOf("com.a" to "us")), setup, setOf("us")))
        // Saved in the new format, so a build from before Block refuses the file instead of taking the app for one on a lost route.
        assertEquals(3, Setup(apps = mapOf("com.b" to BLOCK), version = VERSION).stamped.version)
        assertEquals(3, Setup(profiles = listOf(Profile("Kids", "nl", mapOf("com.b" to BLOCK))), version = VERSION).stamped.version)
        assertEquals(5, Setup(apps = mapOf("com.b" to DIRECT), version = VERSION).stamped.version)
        // An app sharing the blocked app's Android user id is never left outside the VPN, where it would take the blocked one along.
        val kin = setup.copy(apps = setup.apps + ("com.kin" to DIRECT) + ("com.free" to DIRECT))
        val links = kin.activeRoutes.associate { it.id to WgLink(sv(it.id), Nord.peer(sv(it.id), wgKey)) }
        fun out(k: Set<String>) = Json.parseToJsonElement(Compiler.compile(Compiler.Input(kin, links, "app.unbraid", false, "/tmp/ts", "/tmp/cache.db", blockedKin = k))).jsonObject["inbounds"]!!.jsonArray[0].jsonObject.strings("exclude_package")
        assertEquals(listOf("com.c", "com.free", "app.unbraid"), out(setOf("com.b", "com.kin")))
        assertEquals(listOf("com.c", "com.free", "com.kin", "app.unbraid"), out(emptySet()))
        // Profiles and backups carry it; a default route can't be Block, nor can a route take its name.
        val s = setup.copy(profiles = listOf(Profile("Kids", "nl", mapOf("com.b" to BLOCK), sites = mapOf("ads.example" to BLOCK))))
        val back = Backup.read(Backup.export(s, "3.0.1", 0)).setup
        assertEquals(s.apps, back.apps); assertEquals(s.sites, back.sites); assertEquals(s.profiles, back.profiles)
        fun refused(bad: Setup) = runCatching { Backup.valid(bad) }.exceptionOrNull() is Backup.Failed
        assertTrue(refused(setup.copy(defaultRoute = BLOCK))); assertTrue(refused(s.copy(profiles = listOf(Profile("Kids", BLOCK)))))
        assertTrue(refused(setup.copy(routes = setup.routes + Route(BLOCK, Location("nord", 81, "DE", "Germany")))))
        assertEquals(s.apps, s.use("Kids").back(s).apps) // a refused switch goes back to what ran, the blocked app still blocked
    }
}
