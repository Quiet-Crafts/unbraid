package app.unbraid.ui

import app.unbraid.WORDMARK
import app.unbraid.APP
import app.unbraid.stats.Stats
import app.unbraid.support.Tips
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.unbraid.vpn.UnbraidVpn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.unbraid.BuildConfig
import app.unbraid.data.Providers
import app.unbraid.vpn.VpnState.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PRIVACY = "https://unbraid.app/privacy"
const val TERMS_URL = "https://unbraid.app/terms"
private const val SOURCE = "https://github.com/quiet-crafts/unbraid"

/** What the APK re-uses, by name, licence and holder; the full texts are tools/notices.sh's assets/notices.txt. */
private val credits = listOfNotNull(
    "sing-box 1.14.3" to "GPL-3.0-or-later · nekohasekai (SagerNet). The VPN engine, with a small patch",
    "Tailscale" to "BSD-3-Clause · Tailscale Inc. and contributors",
    "wireguard-go" to "MIT · WireGuard LLC",
    "gVisor" to "Apache-2.0 · The gVisor Authors",
    "Go and about 90 more modules" to "BSD, MIT, Apache-2.0, ISC and MPL-2.0, each listed in the full texts",
    "AndroidX, Jetpack Compose, Kotlin" to "Apache-2.0 · Google, JetBrains",
    "ZXing" to "Apache-2.0 · ZXing authors. Reads QR codes at sign-in",
    "Material Symbols" to "Apache-2.0 · Google",
    "Big Shoulders Display, Onest, Martian Mono" to "SIL Open Font License 1.1 · their project authors",
    "gluetun" to "MIT · Quentin McGaw. Server lists, and the ExpressVPN profile",
    "PIA manual-connections" to "MIT · Private Internet Access, Inc.",
    ("Google Play Billing Library" to "Android SDK License · Google. Tips only").takeIf { BuildConfig.FLAVOR == "play" },
    "Measurement Lab (M-Lab)" to "Speed-test servers, used under M-Lab's acceptable use policy",
)

private const val TRADEMARKS = "NordVPN, Mullvad, Proton VPN, Surfshark, ExpressVPN, Private Internet Access, IVPN, Windscribe, AirVPN, " +
    "Tailscale, WireGuard, sing-box and M-Lab are names or trademarks of their owners, used only to say what $APP works with. " +
    "$APP is independent: none of them made, endorse or support it. Your provider accounts stay under their own terms. " +
    "\"WireGuard\" and the \"WireGuard\" logo are registered trademarks of Jason A. Donenfeld."

private class Item(
    val title: String, val sub: String?, val onClick: (() -> Unit)? = null, val cta: Boolean = false,
    /** A switch row: the row toggles, its [Toggle] only shows the state. */
    val checked: Boolean? = null,
    /** A leading icon in a squircle (hub and top-level rows). */
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    /** One of a choice (a radio row). */
    val selected: Boolean? = null,
    /** The tour stop this row is the anchor of. */
    val anchor: TourStop? = null,
    /** Read out after the title, e.g. whether a collapsible group is open. */
    val state: String? = null,
    val trailing: (@Composable () -> Unit)? = null,
)

private const val IDLE = "Reach your tailnet from every app"

private fun tailscaleSub(enabled: Boolean, connected: Boolean, state: String?) = when {
    !enabled -> IDLE
    !connected -> "Connects with $APP"
    state == "NeedsLogin" -> "Sign-in needed"
    state == "NeedsMachineAuth" -> "Waiting for approval in the Tailscale admin console"
    state == "Starting" || state == "NoState" -> "Starting"
    state == "Running" -> "Connected"
    state == "Stopped" -> "Stopped"
    else -> IDLE
}

/** [rise]: the first rows' index on a page that plays the first-load reveal (the hub), or null. */
private fun LazyListScope.group(title: String?, items: List<Item>, rise: Pair<Boolean, Int>? = null) {
    item { if (title != null) SectionLabel(title) else Gap(16.dp) }
    items.forEachIndexed { i, r ->
        item {
          Rise("settings", if (rise == null) 99 else rise.second + i, rise?.first == true) {
            GroupRow(
                i, items.size, r.title, r.sub, if (r.danger) Ink.bad else if (r.cta) Ink.accent else Ink.text, r.onClick, checked = r.checked, selected = r.selected,
                leading = r.icon?.let { ic -> { Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(Ink.surfaceTop), Alignment.Center) { Icon(ic, null, Modifier.size(22.dp), tint = Ink.text) } } },
                trailing = r.trailing, modifier = (r.anchor?.let { tourAnchor(it) } ?: Modifier).then(r.state?.let { st -> Modifier.semantics { stateDescription = st } } ?: Modifier),
            )
          }
        }
    }
}

/** A failed action's message (a provider that couldn't release its device, a control server…), on whichever page is open. */
private fun LazyListScope.notice(ui: Ui, text: String?) = text?.let { n ->
    item(key = "notice") {
        Row(Modifier.padding(start = 24.dp, end = Gutter, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(n, style = Type.caption, color = Ink.bad, modifier = Modifier.weight(1f))
            Secondary("Dismiss", { ui.notice.value = null }, color = Ink.dim)
        }
    }
}

private fun LazyListScope.caption(text: String) = item { Caption(text, modifier = Modifier.padding(top = 4.dp)) }

/**
 * The same collapsible "Advanced settings" group on every page that has settings most people never need, just above the
 * page's sign-out: closed it sums up what's set ([summary]), open it lists [rows] under a warning.
 */
private fun LazyListScope.advanced(open: Boolean, toggle: () -> Unit, summary: String, rows: List<Item>) {
    if (rows.isEmpty()) return
    group(null, listOf(Item("Advanced settings", if (open) "For when something doesn't work. A wrong choice can stop connections" else summary, toggle, icon = Icons.sliders, state = if (open) "Expanded" else "Collapsed") {
        val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, androidx.compose.material3.MaterialTheme.motionScheme.fastSpatialSpec(), label = "advanced")
        Icon(Icons.expand, null, Modifier.size(24.dp).rotate(turn), tint = Ink.dim)
    }) + if (open) rows else emptyList())
}

private val octet = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
private val cidr = Regex("$octet(\\.$octet){3}/([12]?\\d|3[0-2])")
private fun split(s: String) = s.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }

/**
 * Settings pages: the hub (empty stack), then one page per subject; a provider's page is "provider:<id>". The path
 * outlives the screen, so coming back from Add provider lands where you left.
 */
private var path: List<String> = emptyList()

/** The tour shows the hub: the next time Settings opens, it opens there. */
fun settingsHub() { path = emptyList() }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(ui: Ui, addProvider: () -> Unit, tour: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setup by ui.setup.collectAsStateWithLifecycle()
    val tsLogin = rememberTsLogin(setup.home.tsControlUrl)
    val vpn by ui.vpn.collectAsStateWithLifecycle()
    val accounts by ui.accounts.collectAsStateWithLifecycle()
    val notice by ui.notice.collectAsStateWithLifecycle()
    val refreshed by ui.refreshed.collectAsStateWithLifecycle()
    val signedIn = Providers.all.filter { it.id in accounts }
    val prefs = setup.prefs
    val home = setup.home
    var stack by rememberSaveable { mutableStateOf(path) } // survives the app being killed behind a file picker
    val page = stack.lastOrNull()
    val go = { p: String -> stack = stack + p; path = stack }
    val back = { stack = stack.dropLast(1); path = stack }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    // Each page's Advanced settings start closed.
    var advOpen by rememberSaveable(page) { mutableStateOf(false) }
    var wgError by remember { mutableStateOf<String?>(null) }
    BackHandler(page != null) { back() }
    // Own servers are added here and only picked in the location picker.
    val importer = rememberImporter(ui, null) { dialog = null }

    fun start(intent: Intent) = runCatching { context.startActivity(intent) }
    val vpnSettings = { start(Intent(Settings.ACTION_VPN_SETTINGS)); Unit }

    val pickConfig = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { readCapped(context.contentResolver, uri) }
            wgError = if (text == null) "Couldn't read that file. Config files must be under 64 KB." else ui.importHomeWireGuard(text)
        }
    }

    // Android 17 local network access. Re-checked on resume: it can also be changed in Android's settings.
    val activity = LocalActivity.current
    var lanCheck by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { lanCheck++ }
    val lockdownLan = vpn.lockdown && prefs.lan
    val needsLan = remember(setup, lanCheck, lockdownLan) { context.needsLocalNetwork(setup, lockdownLan) }
    val privateDns = remember(lanCheck) { context.privateDnsHost() }
    val askLan = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        lanCheck++
        // No dialog was shown (denied twice before): Android's app page is the only way left.
        if (!granted && activity?.shouldShowRequestPermissionRationale(LOCAL_NETWORK) == false) {
            start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)))
        }
    }
    fun setTailscale(on: Boolean) {
        ui.setHome { it.copy(tailscale = on) }
        if (on && context.needsLocalNetwork(setup.copy(home = home.copy(tailscale = true)))) askLan.launch(LOCAL_NETWORK)
    }

    val tips = ui.tips?.tips?.collectAsStateWithLifecycle()?.value.orEmpty()
    val thanked = ui.tips?.thanked?.collectAsStateWithLifecycle()?.value == true
    LaunchedEffect(thanked) { if (thanked) { delay(6_000); ui.tips?.thanked?.value = false } }
    // unbraid asks the store for prices and open purchases when this page opens or comes back.
    LifecycleResumeEffect(page) { if (page == "about") ui.tips?.refresh(); onPauseOrDispose {} }

    val filter = prefs.filter
    val filterDns = filter.server()
    // unbraid's filter on VPN routes replaces each provider's own blocking: those switches show off, their choice kept.
    val customDns = filterDns != null && filter.routes
    val setFilter = { f: (app.unbraid.data.Filter) -> app.unbraid.data.Filter -> ui.setPrefs { it.copy(filter = f(it.filter)) } }
    // The open provider page's settings and device slot: some come from the provider's server list, so read off the main thread.
    val shown = page?.removePrefix("provider:")?.takeIf { page.startsWith("provider:") }?.let(Providers::get)
    val shownAcc = shown?.let { accounts[it.id] }
    val opts by androidx.compose.runtime.produceState(emptyList<app.unbraid.data.Opt>(), shown, shownAcc) {
        value = shownAcc?.let { a -> withContext(Dispatchers.IO) { listOfNotNull(shown.protection) + runCatching { shown.options(a) }.getOrDefault(emptyList()) } }.orEmpty()
    }
    val device by androidx.compose.runtime.produceState<app.unbraid.data.Device?>(null, shown, shownAcc) {
        value = shownAcc?.let { a -> withContext(Dispatchers.IO) { runCatching { shown.device(a) }.getOrNull() } }
    }
    var statsSize by remember { mutableStateOf(Stats.size()) }
    // Deleting runs on the stats thread: read the size again once it has had time to finish.
    LaunchedEffect(prefs.stats, dialog, page) { statsSize = Stats.size(); delay(800); statsSize = Stats.size() }
    val running = vpn.phase == Phase.On || vpn.phase == Phase.Paused
    val onOff = { b: Boolean -> if (b) "on" else "off" }
    val tsStatus = tailscaleSub(home.tailscale, vpn.phase == Phase.On, vpn.tsState)
    val homeVia = listOfNotNull("Tailscale".takeIf { home.tailscale }, "WireGuard".takeIf { home.wg != null }).joinToString(" and ").ifEmpty { null }

    // A key's setup minute ends while Providers & servers may stay open.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(accounts, page) { while (page == "providers" && app.unbraid.data.Providers.settingUp().isNotEmpty()) { delay(5_000); tick++ } }
    val play = firstShow("settings") // the hub's first rows rise in once per session, as on Routes and Stats
    val motion = androidx.compose.material3.MaterialTheme.motionScheme
    AnimatedContent(page, transitionSpec = { fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) }, label = "settings") { p ->
        when {
            p == null -> Screen(overline = "Version ${BuildConfig.VERSION_NAME}", title = "Settings", sub = "", action = { Wordmark() }) {
                notice(ui, notice)
                val names = signedIn.map { it.name.removeSuffix(" VPN") }
                group("Connections", rise = play to 0, items = listOf(
                    Item("Providers & servers", listOfNotNull(
                        (names.take(2).joinToString(", ") + if (names.size > 2) " +${names.size - 2}" else "").ifEmpty { "No provider yet" },
                        setup.servers.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "server" else "servers"}" },
                    ).joinToString(" · "), { go("providers") }, icon = Icons.vpnKey, anchor = TourStop.Connections) { Chevron() },
                    Item("Tailscale", if (setup.tailscale) tsStatus else "Off", { go("tailscale") }, icon = Icons.hub) { Chevron() },
                    Item("Local & home access", "Local network ${onOff(prefs.lan)} · ${homeVia?.let { "home via $it" } ?: "no home access"}", { go("local") }, icon = Icons.home, anchor = TourStop.LocalHome) { Chevron() },
                ))
                group("Safety", rise = play to 3, items = listOf(
                    // Not leak-proof: the one warning left once the reminders are off.
                    Item("VPN behaviour", when {
                        running && !(vpn.alwaysOn && vpn.lockdown) -> "Not leak-proof: apps go online when $APP is off"
                        running -> "Always-on on · blocking on"
                        else -> "Always-on and blocking, set in Android"
                    }, { go("behaviour") }, icon = Icons.tunnel, cta = running && !(vpn.alwaysOn && vpn.lockdown)) { Chevron() },
                    Item("Protection", filterDns?.let { d -> listOfNotNull(d.name, "VPN routes".takeIf { filter.routes }, "Direct".takeIf { filter.direct || filter.directApps || filter.apps.containsValue(true) }).joinToString(" · ") } ?: "Each provider's own blocking", { go("dns") }, icon = Icons.dns) { Chevron() },
                ))
                group("This app", listOf(
                    Item("Statistics", if (prefs.stats) "Recording · ${"%.1f".format(statsSize / 1e6)} MB" else if (statsSize > 0) "Off · ${"%.1f".format(statsSize / 1e6)} MB saved" else "Off", { go("stats") }, icon = Icons.stats) { Chevron() },
                    Item("About & support", "Version ${BuildConfig.VERSION_NAME} · open source", { go("about") }, icon = Icons.info, anchor = TourStop.About) { Chevron() },
                    Item("Legal", "Licences, terms and credits", { go("legal") }, icon = Icons.file) { Chevron() },
                ))
                item { Gap(24.dp) }
            }

            p == "providers" -> Page("Providers & servers", back) {
                tick.let { }
                notice(ui, notice)
                group("VPN providers", signedIn.map { pr ->
                    val n = setup.routes.count { !it.imported && it.loc.provider == pr.id }
                    val active = setup.activeRoutes.filter { !it.imported && it.loc.provider == pr.id }
                    val on = vpn.phase == Phase.On && active.isNotEmpty()
                    val (tone, words) = providerStatus(
                        pr.name, n > 0, vpn.stuck[pr.id] == true, on && pr.id in app.unbraid.data.Providers.settingUp(), on && vpn.stuck[pr.id] == false,
                        refreshed.let { app.unbraid.Unbraid.store.countriesFresh(pr.id) },
                        // Only the setup the engine runs counts: a route just moved to this provider isn't connected yet.
                        on && active.all { r -> r.id in vpn.servers && UnbraidVpn.running?.route(r.id)?.loc == r.loc },
                    )
                    Item(pr.name, words + if (n > 0) " · used by $n ${if (n == 1) "route" else "routes"}" else "", { go("provider:${pr.id}") }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(when (tone) { Tone.Good -> Ink.good; Tone.Warn -> Ink.accent; Tone.Bad -> Ink.bad; Tone.Idle -> Ink.faint }))
                            Gap(8.dp); Chevron()
                        }
                    }
                }.ifEmpty { listOf(Item("No provider yet", "Sign in to one with Add connection", null)) })
                if (setup.servers.isNotEmpty()) group("My servers", setup.servers.map { sv ->
                    val n = setup.routes.count { it.own == sv.id }
                    Item(sv.name, "${sv.kind} · ${if (n == 0) "not used" else "used by $n ${if (n == 1) "route" else "routes"}"}", { dialog = "server:${sv.id}" }) { Chevron() }
                })
                item { Box(Modifier.padding(horizontal = Gutter, vertical = 16.dp)) { Tonal("Add connection", Icons.plus) { importer.error = null; dialog = "add" } } }
                importer.error?.let { e -> item { Text(e, style = Type.caption, color = Ink.bad, modifier = Modifier.padding(start = 24.dp, end = Gutter)) } }
                caption("A provider sign-in, a WireGuard or OpenVPN file, or a Shadowsocks link. Tailscale exit nodes are under Tailscale.")
            }

            p.startsWith("provider:") -> {
                val pr = Providers.get(p.removePrefix("provider:"))
                if (pr == null || pr.id !in accounts) { LaunchedEffect(Unit) { back() }; return@AnimatedContent }
                val routes = setup.routes.filter { !it.imported && it.loc.provider == pr.id }
                Page(pr.name, back, over = "Providers & servers") {
                    notice(ui, notice)
                    // Providers that count connections, not devices: each separate tunnel to one of its servers is one.
                    val tunnels = if (running) UnbraidVpn.tags.filterKeys { id -> UnbraidVpn.running?.route(id)?.let { !it.imported && it.loc.provider == pr.id } == true }.values.toSet().size else 0
                    // The same line for every provider: its limit (the user's own first), and what unbraid uses of it.
                    val lim = pr.limit(accounts.getValue(pr.id))
                    val counted = Item(if (lim.unit == "device") "Devices" else "Connections", app.unbraid.data.Limits.line(pr.name, app.unbraid.data.Limits.effective(lim, setup.opt(pr.id, app.unbraid.data.LIMIT_OPT)), lim.unit, if (lim.perTunnel) tunnels else 1),
                        { dialog = "limit:${pr.id}" }, icon = Icons.phone) { Chevron() }
                    group(null, listOfNotNull(Item("Account", "Signed in", null, icon = Icons.vpnKey), counted, device?.let { d ->
                        Item("This phone", d.line, d.url?.let { u -> { context.openUrl(u) } }, icon = Icons.info) { if (d.url != null) Icon(Icons.external, null, Modifier.size(20.dp), tint = Ink.dim) }
                    }))
                    val protect = pr.protection
                    val optItem = { o: app.unbraid.data.Opt ->
                        val v = setup.opt(pr.id, o.key)
                        if (o.choices.isEmpty()) Item(o.label, o.sub, { ui.setOpt(pr.id, o.key, if (v == "on") "off" else "on"); Unit }, checked = v == "on") { Toggle(v == "on") }
                        else Item(o.label, o.choices.firstOrNull { it.first == v }?.second ?: o.sub, { dialog = "opt:${pr.id}:${o.key}" }) { Chevron() }
                    }
                    // Every provider's own blocking under one name and in one place; unbraid's filter on VPN routes replaces it
                    // (shown off, the choice kept). Without a switch the row leads to the filter.
                    val on = protect != null && setup.opt(pr.id, app.unbraid.data.PROTECT) == "on" && !customDns
                    val blocking = when {
                        protect != null -> Item("${pr.name} blocking", if (customDns) "Off while $APP's filter is on for VPN routes" else "${protect.label}: ${protect.sub.replaceFirstChar(Char::lowercase)}",
                            { ui.setOpt(pr.id, app.unbraid.data.PROTECT, if (on) "off" else "on"); Unit }.takeUnless { customDns }, checked = on, icon = Icons.shield) { Toggle(on, enabled = !customDns) }
                        else -> Item("${pr.name} blocking", if (customDns) "$APP's filter is on for VPN routes" else pr.blockingNote, { go("dns") }, icon = Icons.shield) { Chevron() }
                    }
                    val shownOpts = opts.filter { it.key != app.unbraid.data.PROTECT }
                    group("${pr.name} settings", listOf(blocking) + shownOpts.filter { !it.advanced }.map(optItem))
                    if (routes.isNotEmpty()) group("Used by", routes.map { r ->
                        val apps = setup.apps.count { it.value == r.id }
                        // A route that turned the provider's blocking on or off for itself says so.
                        val own = r.filter?.takeIf { protect != null }?.let { "${protect!!.label} ${if (it) "on" else "off"} here" }
                        Item(r.name, listOfNotNull(if (r.id == setup.defaultRoute) "Default route" else "$apps ${if (apps == 1) "app" else "apps"}", vpn.servers[r.id]?.name, own).joinToString(" · ")) { Code(r.code, Ink.route(r.hue)) }
                    })
                    val adv = shownOpts.filter { it.advanced }
                    advanced(advOpen, { advOpen = !advOpen }, adv.joinToString(" · ") { o -> setup.opt(pr.id, o.key).let { v -> "${o.label}: ${o.choices.firstOrNull { it.first == v }?.second ?: v}" } }, adv.map(optItem))
                    group(null, listOf(Item("Sign out of ${pr.name}", when {
                        accounts.size == 1 && setup.servers.isEmpty() && setup.routes.none { it.imported } && !home.tailscale -> "It's your only connection: you'll be back at sign-in"
                        routes.isEmpty() -> "No route uses it"
                        else -> "Apps on its routes are blocked until you sign in again or move them; other routes keep running"
                    }, { dialog = "signout:${pr.id}" }, danger = true)))
                }
            }

            p == "tailscale" -> Page("Tailscale", back) {
                notice(ui, notice)
                group(null, listOfNotNull(
                    Item("Reach your tailnet", if (home.tailscale) tsStatus else if (setup.tsExit != null) "Off · Tailscale still runs for your exit-node route" else "From apps routed through unbraid", { setTailscale(!home.tailscale) }, checked = home.tailscale, icon = Icons.hub) { Toggle(home.tailscale) },
                    vpn.tsLoginUrl?.let { url -> Item("Sign in to Tailscale", "Opens your browser", cta = true, onClick = { tsLogin(url) }) { Icon(Icons.external, null, Modifier.size(20.dp), tint = Ink.dim) } },
                ))
                if (setup.tailscale && vpn.tsExitNodes.isNotEmpty()) group("Exit nodes", vpn.tsExitNodes.sortedByDescending { it.online }.map { n ->
                    val used = setup.routes.count { it.tsExit == n.ip }
                    Item(n.name, "${if (n.online) "Online" else "Offline"} · ${if (used == 0) "not used" else "used by $used ${if (used == 1) "route" else "routes"}"}")
                })
                if (setup.tailscale) caption("Pick an exit node for a route in Routes. Reaching your home network through Tailscale is under Local & home access.")
                // A Headscale server can name any domain: its devices' names answer apps only once the user allows that suffix.
                val sfx = vpn.tsSuffix?.takeIf { home.tailscale && !app.unbraid.vpn.Compiler.officialTs(home) }
                if (setup.tailscale) advanced(advOpen, { advOpen = !advOpen }, "Control server: ${home.tsControlUrl ?: "Tailscale"}", listOfNotNull(
                    Item("Control server", home.tsControlUrl ?: "Tailscale (default)", { dialog = "tsserver" }, icon = Icons.dns) { Chevron() },
                    sfx?.let { d ->
                        val on = home.tsSuffixAllowed == d
                        Item("Device names under $d", if (on) "On · your server answers every lookup under $d" else "Off · allow to reach devices by name; your server then answers every lookup under $d",
                            { ui.setHome { it.copy(tsSuffixAllowed = if (on) null else d) } }, checked = on) { Toggle(on) }
                    },
                ))
                if (setup.tailscale && vpn.tsState == "Running") group(null, listOf(Item("Sign out of Tailscale", "Its exit routes are removed; their apps follow the default route", { dialog = "tsout" }, danger = true)))
            }

            p == "local" -> Page("Local & home access", back) {
                notice(ui, notice)
                group("This network", listOfNotNull(
                    Item("Local network bypasses the VPN", "Printers, casting and NAS on this network stay reachable", { ui.setPrefs { it.copy(lan = !it.lan) } }, checked = prefs.lan, icon = Icons.lan) { Toggle(prefs.lan) },
                    Item(
                        "Allow Nearby devices",
                        when {
                            setup.tailscale -> "Lets Tailscale reach devices on this network directly, not through a slower relay."
                            home.wg != null -> "Needed to reach a home tunnel on this network."
                            lockdownLan -> "Printers, casting and NAS need it while Block connections without VPN is on."
                            else -> "Needed to reach your own server on this network."
                        },
                        { askLan.launch(LOCAL_NETWORK) }, cta = true,
                    ) { Chevron() }.takeIf { needsLan },
                ))
                group("Home, from anywhere", listOf(
                    Item("Through Tailscale", if (home.tailscale) tsStatus else "Off", { go("tailscale") }, icon = Icons.hub) { Chevron() },
                    Item("Home WireGuard tunnel", home.wg?.let { "${it.name} · ${it.endpoint}" } ?: "Not set up · import your router's .conf", { pickConfig.launch(arrayOf("*/*")) }, icon = Icons.tunnel) {
                        if (home.wg != null) Secondary("Remove", { wgError = null; ui.setHome { it.copy(wg = null) } }, color = Ink.bad) else Chevron()
                    },
                ))
                wgError?.let { e -> item { Text(e, style = Type.caption, color = Ink.bad, modifier = Modifier.padding(start = 24.dp, end = Gutter, top = 8.dp)) } }
                caption("Reach home from anywhere through Tailscale, or a WireGuard tunnel to your router (e.g. a FRITZ!Box).")
                advanced(advOpen, { advOpen = !advOpen }, "Home subnets through Tailscale: ${home.subnets.joinToString(", ").ifEmpty { "none" }}", listOf(
                    Item("Home subnets through Tailscale", home.subnets.joinToString(", ").ifEmpty { "None" } + " · a WireGuard tunnel uses its own config's ranges", { dialog = "subnets" }, icon = Icons.router) { Chevron() },
                ))
            }

            p == "behaviour" -> Page("VPN behaviour", back) {
                notice(ui, notice)
                item {
                    Column(Modifier.padding(horizontal = Gutter).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Ink.surfaceTop).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        val done = listOf(vpn.alwaysOn, vpn.lockdown).count { it }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("LEAK-PROOF", style = Type.overline, color = Ink.dim, modifier = Modifier.weight(1f))
                            Text(if (!running) "CONNECT TO CHECK" else "$done OF 2", style = Type.code, color = if (!running) Ink.dim else if (done == 2) Ink.good else Ink.accent)
                        }
                        Text("Two switches in Android keep apps offline whenever $APP isn't running. Android decides both; $APP can only open its page.", style = Type.body.copy(fontWeight = FontWeight.Normal), color = Ink.text)
                        FlagRow(Icons.power, "Always-on VPN", "Starts $APP at boot and brings it back", vpn.alwaysOn.takeIf { running })
                        FlagRow(Icons.block, "Block connections without VPN", "No internet without $APP", vpn.lockdown.takeIf { running })
                        Primary("Open Android VPN settings", vpnSettings)
                    }
                }
                caption((if (running) "" else "Connect to see how they're set. ") + "Blocking off: before the first connect, while paused and if $APP stops, apps use your normal connection. $LEAK_TRADEOFF While $APP is on, Direct apps always use your normal connection.")
            }

            p == "dns" -> Page("Protection", back) {
                notice(ui, notice)
                // Each filter once, alphabetical, with what it blocks in its own words; none is suggested.
                group("unbraid's filter", listOf(
                    Item("None", "Each provider's own blocking, set on its page. Direct apps use the phone's DNS", { setFilter { it.copy(id = null) } }, selected = filter.id == null) { Radio(filter.id == null) },
                ) + app.unbraid.data.Filters.all.map { d ->
                    val on = filter.id == d.id
                    Item(d.name, d.blocks + if (d.id == "nextdns") " · profile ${if (on) filter.value.ifBlank { "not set" } else "needed"}" else "",
                        { if (d.id == "nextdns") dialog = "filter:nextdns" else setFilter { it.copy(id = d.id) } }, selected = on) { Radio(on) }
                } + Item("Your own server", if (filter.id == app.unbraid.data.Filters.OWN) filter.value + if (filterDns?.tls == null && filterDns?.path == null) " · not encrypted" else "" else "https://… (DNS over HTTPS) or tls://… (DNS over TLS)",
                    { dialog = "filter:own" }, selected = filter.id == app.unbraid.data.Filters.OWN) { Radio(filter.id == app.unbraid.data.Filters.OWN) })
                if (filterDns != null) {
                    val providers = signedIn.mapNotNull { pr -> pr.protection?.takeIf { setup.opt(pr.id, app.unbraid.data.PROTECT) == "on" }?.label }
                    group("Where it applies", listOf(
                        Item("VPN routes", if (filter.routes) "Replaces each provider's own blocking" + (if (providers.isNotEmpty()) " (${providers.joinToString()})" else "")
                            else "Off · each route uses its provider's own DNS and blocking", { setFilter { it.copy(routes = !it.routes) } }, checked = filter.routes) { Toggle(filter.routes) },
                        Item("Direct traffic through $APP", "Apps that follow a Direct default route", { setFilter { it.copy(direct = !it.direct) } }, checked = filter.direct) { Toggle(filter.direct) },
                    ))
                    group("Apps set to Direct", listOf(
                        Item("Outside $APP", "They never touch the VPN and use the phone's DNS", { setFilter { it.copy(directApps = false) } }, selected = !filter.directApps) { Radio(!filter.directApps) },
                        Item("Through $APP", "Traffic still goes straight out; lookups use this filter",
                            { setFilter { it.copy(directApps = true) } }, selected = filter.directApps) { Radio(filter.directApps) },
                    ))
                    caption(listOfNotNull(
                        "On VPN routes the filter is asked inside each route's own tunnel.".takeIf { filter.routes },
                        "Apps that pass through can see a VPN is on, which some banking and streaming apps refuse.".takeIf { filter.directApps },
                        if (vpn.lockdown && running) "Block connections without VPN is on, so every app passes through $APP: apps kept outside use the phone's DNS until it is off." else "Each Direct app can be set on its own in Apps.",
                    ).joinToString(" "))
                    filterDns.privateDns?.takeIf { !filter.directApps }?.let { host ->
                        group(null, listOf(Item("Private DNS for apps outside $APP", "Android can send them to $host. It then applies to every app, and apps on routes skip their routes' DNS. Tap to copy it and open Android settings", {
                            context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Private DNS", host))
                            start(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                        }) { Icon(Icons.external, null, Modifier.size(20.dp), tint = Ink.dim) }))
                    }
                }
                privateDns?.let { caption("Android's Private DNS ($it) does lookups itself, for every app: apps on routes skip their routes' DNS. Set it to Automatic in Android settings.") }
            }

            p == "stats" -> Page("Statistics", back) {
                notice(ui, notice)
                group(null, listOfNotNull(
                    Item("Record usage statistics", "Only on this phone, never uploaded. Turning it off keeps what's saved.", { if (prefs.stats) ui.setStats(false) else dialog = "statsConsent" }, checked = prefs.stats, icon = Icons.stats) { Toggle(prefs.stats) },
                    Item("Saved history", "${"%.1f".format(statsSize / 1e6)} MB · hourly for a week, daily for about a year", null).takeIf { statsSize > 0 },
                    Item("Delete saved history", "Erase everything recorded so far", { dialog = "statsDelete" }, danger = true) { Icon(Icons.deleteForever, null, Modifier.size(24.dp), tint = Ink.bad) }.takeIf { statsSize > 0 },
                ))
            }

            p == "legal" -> Page("Legal", back) {
                val link = @Composable { Icon(Icons.external, null, Modifier.size(20.dp), tint = Ink.dim) }
                group(null, listOf(
                    Item("Licence", "GNU GPL v3.0 or later: free to use, share and change. No warranty.", { go("notices") }, icon = Icons.file) { Chevron() },
                    Item("Source code", SOURCE.removePrefix("https://"), { context.openUrl(SOURCE) }, trailing = link),
                    Item("Terms of use", TERMS_URL.removePrefix("https://"), { context.openUrl(TERMS_URL) }, trailing = link),
                    Item("Privacy policy", PRIVACY.removePrefix("https://"), { context.openUrl(PRIVACY) }, trailing = link),
                ))
                group("Built with", credits.map { (name, sub) -> Item(name, sub) } + Item("All licence texts", "In full, as the licences require", { go("notices") }) { Chevron() })
                caption(TRADEMARKS)
            }

            p == "notices" -> {
                // The texts are long (about 200 KB): read off the main thread, one item per notice.
                val notices by androidx.compose.runtime.produceState(emptyList<String>()) {
                    value = withContext(Dispatchers.IO) { context.assets.open("notices.txt").bufferedReader().use { it.readText() } }.split("\n=== ").map { it.removePrefix("=== ") }
                }
                Page("Licences", back, over = "Legal") {
                    notices.forEach { n ->
                        item {
                            SectionLabel(n.substringBefore('\n'))
                            // Licence files are hard-wrapped near 80 columns: reflow each paragraph to the screen, keeping list items apart.
                            Text(n.substringAfter('\n').trim().replace(Regex("""[ \t]*(?<!\n)\n(?!\n|[ \t]*([*•-]|\d+[.)]|\([a-z0-9]+\))\s)[ \t]*"""), " "), style = Type.caption, color = Ink.dim, modifier = Modifier.padding(horizontal = 24.dp))
                        }
                    }
                }
            }

            p == "about" -> Page("About & support", back) {
                notice(ui, notice)
                // Play build: tips through Play Billing (Play allows no other way to take money, nor links to one).
                // GitHub/F-Droid build: a plain link to the donation page.
                val jar = ui.tips
                group(null, listOf(
                    Item("Take the tour", "How $APP works, in eight stops", tour, icon = Icons.routes) { Chevron() },
                    Item("$WORDMARK ${BuildConfig.VERSION_NAME}", "sing-box 1.14 · GPL-3.0", icon = Icons.info),
                    Item("Privacy policy", "No analytics, no ads, no account with us", { context.openUrl(PRIVACY) }) { Icon(Icons.external, null, Modifier.size(20.dp), tint = Ink.dim) },
                    Item("Terms of use", TERMS_URL.removePrefix("https://"), { context.openUrl(TERMS_URL) }) { Icon(Icons.external, null, Modifier.size(20.dp), tint = Ink.dim) },
                ))
                group("Support", listOf(
                    when {
                        jar == null && Tips.sponsors != null -> Item("Free and open source", "Made by one person. A donation helps keep it going.", { context.openUrl(Tips.sponsors) }) {
                            Icon(Icons.external, null, Modifier.size(20.dp), tint = Ink.dim)
                        }
                        jar == null || tips.isEmpty() -> Item("Free and open source", "Made by one person in spare time.")
                        else -> Item("Support $APP", if (thanked) "Thank you for the tip!" else "Free and made by one person. A tip keeps it going.")
                    },
                ))
                if (jar != null && tips.isNotEmpty()) item {
                    Row(Modifier.padding(horizontal = Gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tips.forEach { t ->
                            Box(
                                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(Ink.surfaceTop)
                                    .tap({ activity?.let { jar.buy(it, t.id) } }, "Tip ${t.price}"),
                                contentAlignment = Alignment.Center,
                            ) { Text(t.price, style = Type.title, color = Ink.text) }
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        "filter:nextdns", "filter:own" -> {
            val own = dialog == "filter:own"
            var text by remember { mutableStateOf(filter.value.takeIf { filter.id == if (own) app.unbraid.data.Filters.OWN else "nextdns" }.orEmpty()) }
            val ok = if (own) app.unbraid.data.Filters.own(text) != null else app.unbraid.data.Filter("nextdns", text).server() != null
            Prompt(if (own) "Your own server" else "NextDNS profile", "Save", { dialog = null; setFilter { it.copy(id = if (own) app.unbraid.data.Filters.OWN else "nextdns", value = text.trim()) } }, { dialog = null }, enabled = ok) {
                Text(if (own) "A DNS over HTTPS address (https://dns.example.com/dns-query), DNS over TLS (tls://dns.example.com), or a plain IPv4 address, which isn't encrypted." else "The ID of your profile, as on my.nextdns.io (6 characters).", style = Type.sub)
                DialogField(text, { text = it.take(200) }, if (own) "Server" else "Profile ID", if (own) "https://dns.example.com/dns-query" else "abc123")
            }
        }
        "tsout" -> Prompt("Sign out of Tailscale?", "Sign out", { dialog = null; ui.signOutTailscale() }, { dialog = null }, danger = true) {
            Text("Tailscale exit routes are removed. Their apps follow the default route.", style = Type.sub)
        }
        "tsserver" -> {
            var url by remember { mutableStateOf(home.tsControlUrl.orEmpty()) }
            var key by remember { mutableStateOf("") }
            val ok = url.isBlank() || Regex("https://[A-Za-z0-9.-]+(:\\d{1,5})?(/.*)?").matches(url.trim())
            Prompt("Control server", "Save", { dialog = null; ui.setTailscaleServer(url.trim().ifEmpty { null }, key.trim().ifEmpty { null }) }, { dialog = null }, enabled = ok, secure = true) {
                Text("For a self-hosted Headscale. Changing it signs this phone out of the current tailnet.", style = Type.sub)
                DialogField(url, { url = it }, "Server URL", "https://headscale.example.com")
                DialogField(key, { key = it }, "Auth key (optional)", "Signs in without a browser", secret = true)
                if (!ok) Text("Use a full https:// address", style = Type.caption, color = Ink.bad)
            }
        }
        "subnets" -> ListDialog(
            "Home subnets", "192.168.178.0/24", home.subnets, { cidr.matches(it) && app.unbraid.vpn.Compiler.isPrivate(it) }, "Private ranges only, in CIDR notation, e.g. 192.168.178.0/24",
            note = "Separate several with commas.", onSave = { l -> ui.setHome { it.copy(subnets = l.mapNotNull(app.unbraid.vpn.Compiler::canonical).distinct()) } }, onDismiss = { dialog = null },
        )
        "statsConsent" -> StatsConsent({ dialog = null }) { dialog = null; ui.setStats(true) }
        "statsDelete" -> Prompt("Delete statistics?", "Delete statistics", { dialog = null; ui.deleteStats() }, { dialog = null }, danger = true) {
            Text("All recorded data, sessions and speed tests are erased from this phone. This can't be undone." + if (prefs.stats) " Recording stays on." else "", style = Type.sub)
        }
        "add" -> ModalBottomSheet({ dialog = null }, containerColor = Ink.surface, contentColor = Ink.text) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                Text("Add connection", style = Type.title, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                val opts = listOf(
                    Triple("Sign in to a provider", Providers.all.filter { it.id !in accounts }.take(4).joinToString(", ") { it.name } + " and more") { dialog = null; addProvider() },
                    Triple("WireGuard or OpenVPN file", ".conf or .ovpn, from any provider or your own server") { dialog = null; importer.file("any") },
                    Triple("Shadowsocks link", "Paste an ss:// access key") { dialog = null; importer.shadowsocks() },
                    Triple("Provider without an app here?", Providers.imports.joinToString(", ") { it.name } + ": download, then import") { dialog = "guides" },
                )
                opts.forEachIndexed { i, (t, sub, go) -> GroupRow(i, opts.size, t, sub, onClick = go, trailing = { Chevron() }) }
            }
        }
        "guides" -> ModalBottomSheet({ dialog = null }, containerColor = Ink.surface, contentColor = Ink.text) {
            Column(Modifier.padding(bottom = 16.dp)) {
                Text("From another provider", style = Type.title, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                Providers.imports.forEachIndexed { i, g -> GroupRow(i, Providers.imports.size, g.name, g.where, onClick = { dialog = "guide:${g.id}" }, trailing = { Chevron() }) }
            }
        }
        else -> {
            val d = dialog.orEmpty()
            val arg = d.substringAfter(':')
            when {
                d.startsWith("opt:") -> opts.firstOrNull { "opt:${shown?.id}:${it.key}" == d }?.let { o ->
                    ModalBottomSheet({ dialog = null }, containerColor = Ink.surface, contentColor = Ink.text) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                            Text(o.label, style = Type.title, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                            Text(o.sub, style = Type.sub, color = Ink.dim, modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp))
                            val v = shown?.let { setup.opt(it.id, o.key) }
                            o.choices.forEachIndexed { i, (value, label) ->
                                GroupRow(i, o.choices.size, label, null, onClick = { shown?.let { ui.setOpt(it.id, o.key, value) }; dialog = null; Unit }, selected = v == value, trailing = { Radio(v == value) })
                            }
                        }
                    }
                }
                d.startsWith("limit:") -> Providers.get(arg)?.takeIf { it.id in accounts }?.let { pr ->
                    val lim = pr.limit(accounts.getValue(pr.id))
                    var text by rememberSaveable(arg) { mutableStateOf(setup.opt(pr.id, app.unbraid.data.LIMIT_OPT).orEmpty()) }
                    Prompt("Your plan's limit", "Save", { dialog = null; ui.setOpt(pr.id, app.unbraid.data.LIMIT_OPT, text) }, { dialog = null }) {
                        Text("How many ${lim.unit}s your ${pr.name} plan allows at once. $APP warns before a change would go over it. " +
                            (lim.value?.let { "Leave empty for ${pr.name}'s own figure, $it." } ?: "${pr.name} doesn't tell $APP, so leave empty if you don't know."), style = Type.sub)
                        DialogField(text, { t -> text = t.filter(Char::isDigit).take(3) }, "${lim.unit.replaceFirstChar(Char::uppercase)}s", lim.value?.toString() ?: "e.g. 5")
                    }
                }
                d.startsWith("signout:") -> Prompt("Sign out of ${Providers.get(arg)?.name ?: arg}?", "Sign out", { dialog = null; ui.signOut(arg) }, { dialog = null }, danger = true) {
                    Text("Routes and app choices stay on this phone. While connected, apps on its routes are blocked until you sign in again or move them to another route.", style = Type.sub)
                }
                d.startsWith("guide:") -> Providers.imports.firstOrNull { it.id == arg }?.let { g ->
                    ModalBottomSheet({ dialog = null }, containerColor = Ink.surface, contentColor = Ink.text) {
                        Column(Modifier.padding(horizontal = Gutter).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(g.name, style = Type.title, modifier = Modifier.padding(horizontal = 8.dp))
                            Text(g.where, style = Type.sub, color = Ink.dim, modifier = Modifier.padding(horizontal = 8.dp))
                            Primary("Download the config from ${g.name}", { context.openUrl(g.url) })
                            Secondary("Import file", { importer.file(if (g.openVpn) "ovpn" else "wg", g.name) }, Modifier.fillMaxWidth())
                            importer.error?.let { Text(it, style = Type.caption, color = Ink.bad, modifier = Modifier.padding(horizontal = 8.dp)) }
                        }
                    }
                }
                d.startsWith("server:") -> setup.servers.firstOrNull { it.id == arg }?.let { s ->
                    var name by rememberSaveable(s.id) { mutableStateOf(s.name) }
                    val used = setup.routes.count { it.own == s.id }
                    Prompt(s.name, "Save", { ui.renameServer(s.id, name); dialog = null }, { dialog = null }, enabled = name.isNotBlank()) {
                        Text("${s.kind}${if (used > 0) " · used by ${if (used == 1) "1 route" else "$used routes"}" else ""}", style = Type.sub)
                        DialogField(name, { name = it.take(40) }, "Name", "e.g. Home VPS")
                        Secondary("Remove server", { dialog = "remove:${s.id}" }, color = Ink.bad)
                    }
                }
                d.startsWith("remove:") -> setup.servers.firstOrNull { it.id == arg }?.let { s ->
                    val used = setup.routes.count { it.own == s.id }
                    Prompt("Remove ${s.name}?", "Remove", { dialog = null; ui.removeServer(s.id) }, { dialog = null }, danger = true) {
                        Text(if (used == 0) "It isn't used by any route." else "The routes using it are removed too; their apps follow the default route.", style = Type.sub)
                    }
                }
            }
        }
    }
    importer.Dialogs()
}

/** A sub-page of Settings: back at the top-leading corner, the parent's name above the title. */
@Composable
private fun Page(title: String, back: () -> Unit, over: String = "Settings", content: LazyListScope.() -> Unit) =
    Screen(overline = over, title = title, sub = "", navigation = { IconTap(Icons.back, "Back", back) }) { content(); item { Gap(24.dp) } }

/** The tonal M3E button for a page's main action (Add connection). */
@Composable
private fun Tonal(text: String, icon: ImageVector, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(Ink.accentContainer).clickable(role = Role.Button, onClick = onClick),
    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
) { Icon(icon, null, Modifier.size(22.dp), tint = Ink.accent); Text("  $text", style = Type.body, color = Ink.text) }

/** A radio mark for a one-of-two row (the row itself is the target). */
@Composable
private fun Radio(on: Boolean) = Box(Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).border(if (on) 7.dp else 2.dp, if (on) Ink.accent else Ink.dim, RoundedCornerShape(12.dp)))

@Composable
private fun ListDialog(
    title: String, placeholder: String, current: List<String>, valid: (String) -> Boolean, invalid: String, note: String,
    onSave: (List<String>) -> Unit, onDismiss: () -> Unit, max: Int = Int.MAX_VALUE,
) {
    var text by remember { mutableStateOf(current.joinToString(", ")) }
    val bad = split(text).let { it.size > max || it.any { v -> !valid(v) } }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ink.surface, titleContentColor = Ink.text, textContentColor = Ink.dim,
        title = { Text(title, style = Type.title) },
        text = {
            OutlinedTextField(
                text, { text = it }, isError = bad, textStyle = Type.body.copy(fontFamily = Mono, fontSize = 14.sp),
                placeholder = { Text(placeholder, style = Type.body.copy(fontFamily = Mono, fontSize = 14.sp)) },
                supportingText = { Text(if (bad) invalid else note, style = Type.caption) },
                colors = inkFieldColors(),
            )
        },
        confirmButton = { Secondary("Save", { if (!bad) { onSave(split(text)); onDismiss() } }, color = if (bad) Ink.faint else Ink.accent) },
        dismissButton = { Secondary("Cancel", onDismiss, color = Ink.dim) },
    )
}
