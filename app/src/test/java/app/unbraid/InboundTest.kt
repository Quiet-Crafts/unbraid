package app.unbraid

import app.unbraid.data.BLOCK
import app.unbraid.data.DIRECT
import app.unbraid.data.Location
import app.unbraid.data.Nord
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.data.VERSION
import app.unbraid.data.WgLink
import app.unbraid.data.TailnetDevice
import app.unbraid.ui.ago
import app.unbraid.ui.eventDevice
import app.unbraid.ui.eventOutcome
import app.unbraid.ui.eventTitle
import app.unbraid.ui.reachEvents
import app.unbraid.ui.reachRefusal
import app.unbraid.vpn.TsNode
import app.unbraid.ui.reachSub
import app.unbraid.vpn.Compiler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Inbound from the tailnet: what the engine is told, and when it is told nothing. */
class InboundTest {
    private val wgKey = "aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8tMTI="
    private fun sv(n: String) = Server("$n #1", "$n.nordvpn.com", "10.9.0.${n.hashCode() and 0x7f}", "5p4RkybdRU5uaDi90eu4KZPTFif0lKCg4Qp6t1c4F30=", 10)

    private val base = Setup(
        routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands"))), defaultRoute = "nl",
        apps = mapOf("org.kde.kdeconnect_tp" to "nl", "com.blocked" to BLOCK, "com.outside" to DIRECT), version = VERSION,
    ).let { it.copy(home = it.home.copy(tailscale = true)) }
    private val wanted = Compiler.Inbound("100.64.0.5", listOf("1714:1764"), listOf("org.kde.kdeconnect_tp", "com.outside", "com.blocked"))

    private fun compile(s: Setup, inbound: Compiler.Inbound?, lockdown: Boolean = false): JsonObject {
        val links = s.activeRoutes.associate { it.id to WgLink(sv(it.id), Nord.peer(sv(it.id), wgKey)) }
        val input = Compiler.Input(s, links, "app.unbraid", lockdown, "/tmp/ts", "/tmp/cache.db", inbound = inbound)
        return Json.parseToJsonElement(Compiler.compile(input)).jsonObject
    }
    private fun JsonObject.tun() = this["inbounds"]!!.jsonArray[0].jsonObject
    private fun JsonObject.ts() = this["endpoints"]!!.jsonArray.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "tailscale" }
    private fun JsonObject.strings(k: String) = this[k]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test fun onTheTunCarriesTheTailnetAddressAndTheEngineGetsTheGate() {
        val c = compile(base, wanted)
        assertEquals(listOf("172.19.0.1/30", "100.64.0.5/32"), c.tun().strings("address"))
        assertEquals("1280", c.tun()["mtu"]!!.jsonPrimitive.content)
        val gate = c.ts()["tun_handoff"]!!.jsonObject
        assertEquals(listOf("1714:1764"), gate.strings("tcp_ports"))
        // An app outside the VPN can't be named nor reached through it: it is not allowed. A blocked one stays listed
        // as allowed here, and the engine refuses it because Block wins.
        assertEquals(listOf("com.blocked", "org.kde.kdeconnect_tp"), gate.strings("allow_packages"))
        assertEquals(listOf("com.blocked"), gate.strings("block_packages"))
    }

    @Test fun unbraidItselfIsNeverAllowed() {
        val gate = compile(base, Compiler.Inbound("100.64.0.5", listOf("1024:65535"), listOf("app.unbraid", "org.kde.kdeconnect_tp")), lockdown = true).ts()["tun_handoff"]!!.jsonObject
        assertEquals(listOf("org.kde.kdeconnect_tp"), gate.strings("allow_packages"))
    }

    @Test fun theSettingSaysWhatHolds() {
        val on = base.copy(home = base.home.copy(reachable = true, reachableApps = listOf("org.kde.kdeconnect_tp", "com.blocked", "com.outside")))
        assertEquals("1 app allowed", reachSub(on, lockdown = false, tsWaiting = false))
        assertEquals("2 apps allowed", reachSub(on, lockdown = true, tsWaiting = false)) // nothing is outside under lockdown
        assertEquals("Paused · Tailscale isn't connected", reachSub(on, lockdown = false, tsWaiting = true))
        assertEquals("Off", reachSub(on.copy(home = on.home.copy(reachable = false)), lockdown = false, tsWaiting = false))
        assertEquals("No apps selected", reachSub(on.copy(home = on.home.copy(reachableApps = listOf("com.blocked"))), lockdown = false, tsWaiting = false))
        assertEquals("Needs Reach your tailnet", reachSub(on.copy(home = on.home.copy(tailscale = false)), lockdown = false, tsWaiting = false))
        assertEquals("Blocked · incoming connections refused", reachRefusal(on, "com.blocked", lockdown = false))
        assertEquals("Outside unbraid · tailnet devices can't reach it", reachRefusal(on, "com.outside", lockdown = false))
        assertNull(reachRefusal(on, "com.outside", lockdown = true))
        assertNull(reachRefusal(on, "org.kde.kdeconnect_tp", lockdown = false))
    }

    @Test fun chosenDevicesReachTheEngineOnlyWhenSet() {
        val gate = compile(base, Compiler.Inbound("100.64.0.5", listOf("1024:65535"), listOf("org.kde.kdeconnect_tp"), listOf("nB", "nA"))).ts()["tun_handoff"]!!.jsonObject
        assertEquals(listOf("nA", "nB"), gate.strings("allow_nodes"))
        assertNull(compile(base, wanted).ts()["tun_handoff"]!!.jsonObject["allow_nodes"])
        val only = base.copy(home = base.home.copy(reachable = true, reachableApps = listOf("org.kde.kdeconnect_tp"), reachableOnly = true))
        assertEquals("No devices chosen · No device can connect", reachSub(only, lockdown = false, tsWaiting = false))
        assertEquals("No devices chosen · No device can connect", reachSub(only.copy(home = only.home.copy(reachableApps = emptyList())), lockdown = false, tsWaiting = false))
        assertEquals("1 app allowed", reachSub(only.copy(home = only.home.copy(reachableDevices = listOf(TailnetDevice("n1", "pc")))), lockdown = false, tsWaiting = false))
    }

    @Test fun recentConnectionsReadAndSay() {
        val ev = reachEvents("""[{"time":1,"peer":"100.64.0.6","node":"n1","port":1716,"allowed":false,"reason":"device","packages":["org.kde.kdeconnect_tp"]},
            {"time":2,"peer":"100.64.0.7","node":"n2","port":1716,"packages":["org.kde.kdeconnect_tp"],"allowed":true,"extra":0}]""")
        assertEquals(listOf(2L, 1L), ev.map { it.time }) // newest first; unknown keys ignored
        assertEquals(emptyList<Any>(), reachEvents("not json")); assertEquals(emptyList<Any>(), reachEvents(null))
        val label = { p: String -> if (p == "org.kde.kdeconnect_tp") "KDE Connect" else p }
        assertEquals("Allowed", eventOutcome(ev[0]))
        assertEquals("Refused · Device wasn't chosen", eventOutcome(ev[1]))
        val refused = { reason: String -> ev[0].copy(allowed = false, reason = reason) }
        assertEquals("Refused · App was on Block", eventOutcome(refused("blocked")))
        assertEquals("Refused · App wasn't chosen", eventOutcome(refused("app")))
        assertEquals("Refused · Android couldn't identify the app", eventOutcome(refused("unknown")))
        assertEquals("desktop to KDE Connect", eventTitle("desktop", ev[0], label))
        assertEquals("desktop", eventTitle("desktop", ev[0].copy(packages = emptyList()), label))
        assertEquals("desktop", eventDevice(ev[0], listOf(TsNode("desktop", "100.64.0.7", true, "n2")), emptyList()))
        assertEquals("old pc", eventDevice(ev[0], emptyList(), listOf(TailnetDevice("n2", "old pc"))))
        assertEquals("100.64.0.7", eventDevice(ev[0], emptyList(), emptyList()))
        assertEquals(listOf("just now", "2 min ago", "3 h ago", "1 d ago"), listOf(5_000L, 120_000L, 3 * 3_600_000L, 90_000_000L).map(::ago))
    }

    @Test fun underLockdownNoAppIsOutsideSoAllStayAllowed() {
        val gate = compile(base, wanted, lockdown = true).ts()["tun_handoff"]!!.jsonObject
        assertEquals(listOf("com.blocked", "com.outside", "org.kde.kdeconnect_tp"), gate.strings("allow_packages"))
    }

    @Test fun offOrWithoutTheTailnetNothingChanges() {
        for ((s, inbound) in listOf(base to null, base.copy(home = base.home.copy(tailscale = false)) to wanted)) {
            val c = compile(s, inbound)
            assertEquals(listOf("172.19.0.1/30"), c.tun().strings("address"))
            assertEquals("9000", c.tun()["mtu"]!!.jsonPrimitive.content)
            c["endpoints"]?.jsonArray?.map { it.jsonObject }?.filter { it["type"]!!.jsonPrimitive.content == "tailscale" }?.forEach { assertNull(it["tun_handoff"]) }
        }
    }
}
