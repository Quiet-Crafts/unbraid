package app.unbraid.support

import app.unbraid.data.DIRECT
import app.unbraid.data.Filters
import app.unbraid.data.Providers
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.vpn.VpnState

/**
 * The plain-text report people can paste into a bug report. Built only from fixed labels, counts, yes/no answers and
 * validated codes: nothing the user typed or a server sent (names, keys, addresses, ports, app packages, error text)
 * is ever copied in, so there is nothing to scrub.
 */
object Report {
    private val code = Regex("[A-Za-z]{2}")
    private val tailscale = setOf("Running", "NeedsLogin", "NeedsMachineAuth", "Stopped", "Starting", "NoState", "InUseOtherUser")

    /** Version, Android and model labels come from the system; anything unusual is dropped anyway. */
    private fun plain(s: String) = s.filter { it.isLetterOrDigit() || it in " .-_()" }.take(40)

    private fun kind(r: Route) = when {
        r.wg != null -> "Own WireGuard"; r.ovpn != null -> "Own OpenVPN"; r.ss != null -> "Own Shadowsocks"; r.tsExit != null -> "Tailscale exit"
        else -> Providers.get(r.loc.provider)?.name ?: "Other provider"
    }

    private fun on(b: Boolean) = if (b) "on" else "off"

    /**
     * [setup] is what the user has now, [running] what the engine runs (null = not running); the report describes the
     * running one and says when they differ. [installed] is how many apps are known, null until the user allowed reading them.
     */
    fun build(app: String, flavor: String, android: String, device: String, setup: Setup, vpn: VpnState, running: Setup?, installed: Int?): String {
        val s = running ?: setup
        val used = s.activeRoutes.map { it.id }.toSet()
        fun apps(id: String): String =
            if (id == s.defaultRoute) "the rest of the apps" + installed?.let { " (${(it - s.apps.size).coerceAtLeast(0)})" }.orEmpty()
            else s.apps.count { it.value == id }.let { if (it == 1) "1 app" else "$it apps" }
        val routes = s.routes.mapIndexed { i, r ->
            val status = when { r.id in vpn.notAnswering -> "not answering"; r.id in vpn.dropping -> "dropping"; r.id in used -> "active"; else -> "unused" }
            val place = if (r.imported) "n/a" else r.loc.countryCode?.takeIf(code::matches)?.uppercase() ?: "unknown"
            "${i + 1}. ${kind(r)}, $place, $status, ${apps(r.id)}"
        }
        val direct = listOfNotNull("- Direct, active, ${apps(DIRECT)}".takeIf { s.defaultRoute == DIRECT || DIRECT in s.apps.values })
        val filter = s.prefs.filter
        val filterKind = when (filter.id) { null -> "off"; Filters.OWN -> "own server"; "nextdns" -> "personal profile"; else -> "public resolver" }
        return (listOf(
            "unbraid report",
            "App: ${plain(app)} (${plain(flavor)} build)",
            "Android: ${plain(android)}",
            "Device: ${plain(device)}",
            "VPN: ${vpn.phase.name.lowercase()}",
            "Unapplied changes: " + if (running != null && running != setup.copy(favorites = running.favorites, profiles = running.profiles, profile = running.profile, added = running.added, servers = running.servers, version = running.version, testing = running.testing)) "yes" else "no",
            "",
            "Settings",
            "Local network bypasses the VPN: ${on(s.prefs.lan)}",
            "Protection filter: $filterKind" + if (filter.id == null) "" else " (VPN routes ${on(filter.routes)}, Direct ${on(filter.direct)})",
            "Block connections without VPN: ${on(vpn.lockdown)}",
            "Always-on VPN: ${on(vpn.alwaysOn)}",
            "Tailscale: ${on(s.tailscale)}" + vpn.tsState?.takeIf { it in tailscale }?.let { " ($it)" }.orEmpty(),
            "Home WireGuard: ${on(s.home.wg != null)}",
            "Profiles: ${s.profiles.size}",
            "",
            "Problems",
            "Current error: " + if (vpn.error != null) "yes" else "none",
            "Providers refusing sign-in: ${vpn.stuck.values.count { it }}, without a tunnel: ${vpn.stuck.size}",
            "",
            "Routes (default: " + (if (s.defaultRoute == DIRECT) "Direct" else s.routes.indexOfFirst { it.id == s.defaultRoute }.let { if (it >= 0) "#${it + 1}" else "none" }) + ")",
        ) + routes + direct).joinToString("\n")
    }
}
