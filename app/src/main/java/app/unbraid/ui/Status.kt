package app.unbraid.ui

/** How a provider stands, in Settings: a colour, always with words. */
enum class Tone { Good, Warn, Bad, Idle }

/**
 * A provider's status from what the app already knows (no extra calls): [refused] its account, its key still [settingUp],
 * its routes [blocked] for a passing reason, a route [dropping] (its warning), its only list published long ago ([oldList],
 * that date), its saved server list over a day old
 * (![listFresh]), its routes [connected].
 */
fun providerStatus(name: String, used: Boolean, refused: Boolean, settingUp: Boolean, blocked: Boolean, listFresh: Boolean, connected: Boolean, dropping: String? = null, oldList: String? = null): Pair<Tone, String> = when {
    refused -> Tone.Bad to "$name refused this phone. Sign in again"
    !used -> Tone.Idle to "Not used"
    settingUp -> Tone.Warn to "Setting up your key"
    blocked -> Tone.Warn to "Can't reach its servers right now"
    dropping != null -> Tone.Warn to dropping
    oldList != null -> Tone.Warn to "Its server list is from $oldList and may be out of date"
    !listFresh -> Tone.Warn to "Using an older server list"
    connected -> Tone.Good to "Connected"
    else -> Tone.Good to "Server list up to date"
}

/** Proxy routes the running engine carries exactly as they are saved; null: not connected. */
fun proxyRunning(s: app.unbraid.data.Setup, vpn: app.unbraid.vpn.VpnState): Set<String>? = if (vpn.phase != app.unbraid.vpn.VpnState.Phase.On) null
    else vpn.servers.keys.filter { id -> s.route(id)?.socks5.let { it != null && it == app.unbraid.vpn.UnbraidVpn.running?.route(id)?.socks5 } }.toSet()

/**
 * How proxy server [sv] stands: what is saved, whether anything accepts connections on its port now ([silent]: nothing
 * does), and what the engine's own checks saw of the routes it runs as saved ([running], null: not connected).
 * Connected only on an answered check.
 */
fun proxyStatus(s: app.unbraid.data.Setup, sv: app.unbraid.data.OwnServer, vpn: app.unbraid.vpn.VpnState, silent: Boolean, running: Set<String>? = proxyRunning(s, vpn)): Pair<Tone, String> {
    val p = sv.socks5 ?: return Tone.Idle to "Not used"
    val active = s.activeRoutes.filter { it.own == sv.id }
    val passed = vpn.passed?.takeIf { it.engine == vpn.engine }
    return when {
        sv.keyless || p.needsConfirmation -> Tone.Bad to "Confirm its app"
        !s.outside(p.packageName) -> Tone.Warn to "Its app needs Direct"
        silent -> Tone.Bad to "Its proxy isn't running"
        s.routes.none { it.own == sv.id } -> Tone.Idle to "Not used"
        vpn.lockdown && running != null -> Tone.Warn to "Offline: Block connections without VPN is on in Android"
        running == null || active.isEmpty() || !running.containsAll(active.map { it.id }) -> Tone.Idle to "Not connected"
        active.any { it.id in vpn.notAnswering } -> Tone.Bad to "Not answering"
        active.none { it.checksInternet } -> Tone.Idle to "Running, checks off"
        active.all { !it.checksInternet || passed != null && app.unbraid.vpn.Health.answered(passed, it) } -> Tone.Good to "Connected"
        else -> Tone.Idle to "Checking"
    }
}

/** A catalogue's publication date ([app.unbraid.data.Catalog.published], epoch seconds), e.g. "6 Aug 2026". */
fun publishedOn(epochSeconds: Long): String = runCatching {
    java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneOffset.UTC).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH))
}.getOrDefault("an unknown date")

/** Older than this, a provider's only server list turns its status amber. */
const val OLD_LIST_MS = 60L * 86_400_000
