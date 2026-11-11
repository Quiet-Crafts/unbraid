package app.unbraid

import app.unbraid.data.DIRECT
import app.unbraid.data.Filter
import app.unbraid.data.Home
import app.unbraid.data.Location
import app.unbraid.data.OvpnProfile
import app.unbraid.data.Prefs
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.data.SsProfile
import app.unbraid.data.WgPeer
import app.unbraid.support.Report
import app.unbraid.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {
    private val secrets = listOf(
        "SECRET-NAME", "wgPRIVATEkey", "wgPUBLICkey", "10.66.66.2", "vpn.secret-host.example", "51820", "OVPN-PASS", "ovpn-user", "ss-passw0rd", "ss.secret-host.example",
        "com.secret.app", "100.64.1.9", "tailnet-secret.ts.net", "TS-AUTH-KEY", "1.2.3.4", "dns.secret.example", "headscale.secret.example", "Home-WiFi", "SECRET-ERROR",
        "SECRET-REGION", "secretcity", "nl9999", "profile-secret",
    )

    private val wg = WgPeer("SECRET-NAME wg", "wgPRIVATEkey", listOf("10.66.66.2/32"), "wgPUBLICkey", "wgPRESHARED", "vpn.secret-host.example:51820", listOf("0.0.0.0/0"), listOf("dns.secret.example"))
    private val setup = Setup(
        routes = listOf(
            Route("a", Location("nord", 1, "NL", "Netherlands", city = "secretcity", pinned = Server("nl9999", "nl9999.secret-host.example", "1.2.3.4", "k", 1)), hue = 1),
            Route("b", wg = wg),
            Route("c", ovpn = OvpnProfile("SECRET-NAME ovpn", "remote vpn.secret-host.example 1194", "ovpn-user", "OVPN-PASS")),
            Route("d", ss = SsProfile("SECRET-NAME ss", "ss.secret-host.example", 8388, "aes", "ss-passw0rd")),
            Route("e", tsExit = "100.64.1.9", tsName = "SECRET-NAME ts.tailnet-secret.ts.net"),
            Route("f", Location("secret-provider-id", countryCode = "<script>", region = "SECRET-REGION")),
            Route("g", Location("mullvad", countryCode = "se")),
            Route("h", Location("nord", countryCode = "DE")),
        ),
        defaultRoute = "a",
        apps = mapOf("com.secret.app" to "b", "com.secret.two" to "b", "com.secret.three" to DIRECT, "com.secret.four" to "d"),
        home = Home(tailscale = true, tsAuthKey = "TS-AUTH-KEY", tsControlUrl = "https://headscale.secret.example", subnets = listOf("192.168.1.0/24"), tsSuffixAllowed = "tailnet-secret.ts.net"),
        prefs = Prefs(customDns = listOf("dns.secret.example"), filter = Filter("own", "https://dns.secret.example/dns-query", routes = true)),
        profiles = listOf(app.unbraid.data.Profile("profile-secret", "a")), profile = "profile-secret",
    )
    private val vpn = VpnState(
        phase = VpnState.Phase.On, error = "SECRET-ERROR Home-WiFi 1.2.3.4", tsState = "SECRET-ERROR", stuck = mapOf("nord" to true, "mullvad" to false),
        notAnswering = setOf("c"), dropping = setOf("d"), servers = mapOf("a" to Server("nl9999", "nl9999.secret-host.example", "1.2.3.4", "k", 1)),
    )

    private fun build(installed: Int? = 20, running: Setup? = setup, device: String = "Pixel 11 Pro") =
        Report.build("2.8.4 (52)", "foss", "17", device, setup, vpn, running, installed)

    @Test fun noSecretAppears() {
        val text = build()
        for (s in secrets) assertFalse("leaked $s", text.contains(s, ignoreCase = true))
        // Other values the system supplies are cleaned too.
        assertFalse(Report.build("<b>1.0</b>\nSECRET", "foss", "17", "Phone;rm -rf /", setup, vpn, null, null).let { it.contains("<") || it.contains(";") || it.contains("SECRET\n") })
    }

    @Test fun routesAndStatuses() {
        val lines = build().lines()
        assertEquals("1. NordVPN, NL, active, the rest of the apps (16)", lines.first { it.startsWith("1.") })
        assertEquals("2. Own WireGuard, n/a, active, 2 apps", lines.first { it.startsWith("2.") })
        assertEquals("3. Own OpenVPN, n/a, not answering, 0 apps", lines.first { it.startsWith("3.") })
        assertEquals("4. Own Shadowsocks, n/a, dropping, 1 app", lines.first { it.startsWith("4.") })
        assertEquals("5. Tailscale exit, n/a, unused, 0 apps", lines.first { it.startsWith("5.") })
        assertEquals("6. Other provider, unknown, unused, 0 apps", lines.first { it.startsWith("6.") })
        assertTrue(lines.any { it == "7. Mullvad, SE, unused, 0 apps" })
        assertTrue(lines.any { it == "- Direct, active, 1 app" })
    }

    @Test fun defaultAndDirect() {
        val direct = setup.copy(defaultRoute = DIRECT, apps = emptyMap())
        val text = Report.build("1", "foss", "17", "Pixel", direct, VpnState(), null, null)
        assertTrue(text.contains("Routes (default: Direct)"))
        assertTrue(text.contains("- Direct, active, the rest of the apps"))
        assertTrue(text.contains("1. NordVPN, NL, unused, 0 apps"))
        assertTrue(text.contains("VPN: off"))
        // Without the app list nothing is guessed.
        assertFalse(build(installed = null).contains("the rest of the apps ("))
    }

    @Test fun problemsAndSettings() {
        val text = build()
        assertTrue(text.contains("Current error: yes"))
        assertTrue(text.contains("Providers refusing sign-in: 1, without a tunnel: 2"))
        assertTrue(text.contains("Protection filter: own server (VPN routes on, Direct off)"))
        assertTrue(text.contains("Tailscale: on"))
        assertTrue(text.contains("Profiles: 1"))
        assertTrue(Report.build("1", "foss", "17", "Pixel", setup, VpnState(), null, null).contains("Current error: none"))
    }

    @Test fun unappliedChangesAreSaid() {
        assertTrue(build(running = setup).contains("Unapplied changes: no"))
        assertTrue(build(running = setup.copy(defaultRoute = "b")).contains("Unapplied changes: yes"))
        assertTrue(build(running = null).contains("Unapplied changes: no"))
    }
}
