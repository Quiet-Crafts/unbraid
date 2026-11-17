package app.unbraid

import app.unbraid.data.BLOCK
import app.unbraid.data.DIRECT
import app.unbraid.data.Home
import app.unbraid.data.Location
import app.unbraid.data.OwnServer
import app.unbraid.data.Prefs
import app.unbraid.data.RestoreTraffic
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.data.VERSION
import app.unbraid.data.WgConf
import app.unbraid.vpn.Compiler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * The restore check's matching of names and address ranges against the engine itself: every name and range list of
 * compiled configs, asked of sing-box's own rule matcher (`sing-box rule-set match`) for the probes the walk uses, and the
 * configs themselves validated (`sing-box check`). Runs only where a sing-box binary is named in SING_BOX.
 */
class RestoreEngineTest {
    private val box = System.getenv("SING_BOX")?.let(::File)?.takeIf { it.canExecute() }

    private fun wg(n: Int, allowed: String = "0.0.0.0/0") = WgConf.parse("[Interface]\nPrivateKey = ${('A' + n).toString().repeat(42)}E=\nAddress = 10.9.$n.2/32\n" +
        "[Peer]\nPublicKey = ${('B' + n).toString().repeat(42)}E=\nEndpoint = 203.0.113.$n:51820\nAllowedIPs = $allowed\n", "Box $n")

    private fun run(vararg args: String): String {
        val p = ProcessBuilder(box!!.path, *args).redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }

    private fun strings(e: kotlinx.serialization.json.JsonElement) = (e as JsonArray).map { (it as JsonPrimitive).content }

    @Test fun namesAndRangesMatchAsTheEngineMatchesThem() {
        assumeTrue("SING_BOX not set", box != null)
        val dir = kotlin.io.path.createTempDirectory("restore-engine").toFile()
        val g = Random(0xE461E)
        var asked = 0
        repeat(12) {
            val sites = listOf("n.example", "b.n.example", "o.example", "deep.x.o.example", "s.example").filter { g.nextBoolean() }
                .associateWith { listOf("nl", "w", DIRECT, BLOCK)[g.nextInt(4)] }
            val s = Setup(routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands")), Route("w", wg = wg(1), own = "s")), defaultRoute = listOf("nl", DIRECT)[g.nextInt(2)],
                servers = listOf(OwnServer("s", wg = wg(1))), apps = mapOf("org.a" to "w", "org.b" to BLOCK), sites = sites, version = VERSION,
                prefs = Prefs(lan = g.nextBoolean(), blockLan = g.nextBoolean()), home = if (g.nextBoolean()) Home(wg = wg(7, "10.0.0.0/9, 10.192.0.0/10").copy(name = "Home")) else Home())
            val c = RestoreTraffic.compile(s, { emptySet() }, at = RestoreTraffic.Situation(g.nextBoolean()))!!
            val text = File(dir, "config.json")
            // The config itself, as the engine reads it (the tun inbound and endpoints are only checked, not started).
            val compiled = Json.parseToJsonElement(Compiler.compile(Compiler.Input(s, mapOf("nl" to app.unbraid.data.WgLink(app.unbraid.data.Server("x", "198.51.100.1", "198.51.100.1", wg(2).publicKey, 0), wg(2)),
                "w" to app.unbraid.data.WgLink(app.unbraid.data.Server("w", "203.0.113.1", "", wg(1).publicKey, 0), wg(1))), "app.unbraid", false, "", File(dir, "cache.db").path))).jsonObject
            // This host build has no WireGuard: each tunnel is checked as a plain outbound of the same tag.
            val tunnels = (compiled["endpoints"] as JsonArray).map { JsonObject(mapOf("type" to JsonPrimitive("direct"), "tag" to it.jsonObject["tag"]!!)) }
            text.writeText(JsonObject(compiled - "endpoints" + ("outbounds" to JsonArray((compiled["outbounds"] as JsonArray) + tunnels))).toString())
            assertEquals("", run("check", "-c", text.path).trim())
            val rules = (c.dnsRules + c.rules).flatMap { r -> listOf(r) + (r["rules"] as? JsonArray).orEmpty().map { it.jsonObject } }
            for (r in rules) {
                val names = listOfNotNull(r["domain"], r["domain_suffix"])
                val ranges = r["ip_cidr"]
                if (names.isEmpty() && ranges == null) continue
                val set = File(dir, "rules.json")
                set.writeText(JsonObject(mapOf("version" to JsonPrimitive(3), "rules" to JsonArray(listOf(JsonObject(r.filterKeys { it == "domain" || it == "domain_suffix" || it == "ip_cidr" }))))).toString())
                val words = c.set(r["domain"]) + c.set(r["domain_suffix"]).map { it.removePrefix(".") }
                val probes = if (names.isNotEmpty()) words.flatMap { listOf(it, "zz-check.$it", "x$it") } + "unbraid-check.example"
                    else strings(ranges!!).mapNotNull(Compiler::ipv4).flatMap { (a, l) -> listOf(a, a + (1L shl (32 - l)) - 1, a - 1, a + (1L shl (32 - l))) }.filter { it in 0..0xFFFFFFFFL }
                        .map { v -> (3 downTo 0).joinToString(".") { (v shr (it * 8) and 255).toString() } }
                for (p in probes.distinct()) {
                    val engine = run("rule-set", "match", set.path, p).contains("match rules")
                    val walk = if (names.isNotEmpty()) c.named(p, r["domain"], r["domain_suffix"]) else c.ranges(ranges!!).has(Compiler.ipv4(p)!!.first)
                    assertEquals("$p against ${r.filterKeys { it == "domain" || it == "domain_suffix" || it == "ip_cidr" }}", engine, walk)
                    asked++
                }
            }
        }
        println("RESTORE-ENGINE asked the engine $asked times")
        dir.deleteRecursively()
    }
}
