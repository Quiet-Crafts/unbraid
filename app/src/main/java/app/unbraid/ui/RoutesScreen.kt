package app.unbraid.ui

import app.unbraid.APP

import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import android.provider.Settings
import android.content.Intent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ripple
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.graphics.shapes.Morph
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import app.unbraid.data.DIRECT
import app.unbraid.data.MAX_PROFILES
import app.unbraid.data.Home
import app.unbraid.data.Nord
import app.unbraid.data.Providers
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.vpn.ExitReport
import app.unbraid.vpn.VpnState
import app.unbraid.vpn.VpnState.Phase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class Stop(
    val id: String, val code: String, val name: String, val detail: String, val color: Color,
    val pkgs: List<String> = emptyList(),
)


private fun count(n: Int) = if (n == 1) "1 app" else "$n apps"

/** Server name and, where the provider publishes it, its load: "nl1256 · 9% load". */
private fun serverId(r: Route, s: Server): String = listOfNotNull(
    if (r.loc.provider == "nord") s.host.substringBefore('.') else s.name,
    "${s.load}% load".takeIf { s.load >= 0 && Providers.get(r.loc.provider)?.hasLoad == true },
).joinToString(" · ")

private fun place(r: Route, live: Map<String, Server>) = when {
    r.wg != null -> "WireGuard"
    r.ovpn != null -> "OpenVPN"
    r.ss != null -> "Shadowsocks"
    r.tsExit != null -> "Tailscale exit"
    else -> listOfNotNull(
        r.source,
        NordGroups.firstOrNull { it.first != null && it.first == r.loc.group }?.second.takeIf { r.loc.provider == "nord" },
        r.loc.pinned?.name ?: r.loc.city ?: live[r.id]?.city?.ifBlank { null } ?: "Fastest",
        when (Nord.tech(r.loc).takeIf { r.loc.provider == "nord" }) {
            "openvpn_udp" -> "OpenVPN"
            "openvpn_tcp" -> "OpenVPN TCP"
            else -> null
        },
    ).joinToString(" · ")
}

private fun stops(s: Setup, live: Map<String, Server>, byRoute: Map<String, List<String>>): List<Stop> {
    fun pkgs(id: String) = byRoute[id].orEmpty()
    val routes = s.routes.map { r -> Stop(r.id, r.code, r.name, place(r, live), Ink.route(r.hue), pkgs(r.id)) }
    val direct = Stop(DIRECT, "··", "Direct", "Skips the VPN", Ink.direct, pkgs = pkgs(DIRECT))
    return routes + direct
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutesScreen(ui: Ui, connect: () -> Unit, leakSetup: () -> Unit, openPicker: (String) -> Unit) {
    val reveal = firstShow("routes")
    val context = LocalContext.current
    val setup by ui.setup.collectAsStateWithLifecycle()
    val tsLogin = rememberTsLogin(setup.home.tsControlUrl)
    val vpn by ui.vpn.collectAsStateWithLifecycle()
    val apps by ui.apps.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    // Profiles: the Manage page, the sheet ("" = new, else the name being edited), the note shown at the limit.
    var managing by rememberSaveable { mutableStateOf(false) }
    var profileSheet by rememberSaveable { mutableStateOf<String?>(null) }
    var full by rememberSaveable { mutableStateOf(false) }
    val switching by ui.switching.collectAsStateWithLifecycle()
    val failed by ui.switchFailed.collectAsStateWithLifecycle()
    val addProfile = { if (setup.profiles.size < MAX_PROFILES) { full = false; profileSheet = "" } else full = true }
    var newName by rememberSaveable { mutableStateOf("") }
    val on = vpn.phase == Phase.On
    val byRoute = remember(setup) { setup.apps.entries.groupBy({ it.value }, { it.key }) }
    val live = if (on) vpn.servers else emptyMap()
    val list = remember(setup, live, byRoute) { stops(setup, live, byRoute) }
    val exceptions = list.filter { it.id != setup.defaultRoute }
    val labels = remember(apps) { apps.associate { it.pkg to it.label } }
    // The default carries every app not sent elsewhere; its preview shows user apps, not system ones.
    val defaultPkgs = remember(apps, setup) { apps.filter { setup.routeOf(it.pkg) == setup.defaultRoute }.sortedBy { it.system }.map { it.pkg } }
    val server = vpn.servers[setup.defaultRoute]
    val exits by ui.exits.collectAsStateWithLifecycle()
    // A route's report holds only for the engine and server it was taken on.
    val report = { id: String -> exits[id]?.takeIf { on && it.engine == vpn.engine && it.server == vpn.servers[id] } }
    // Each exit is asked once its tunnels are up after a connect or a restart (a new server restarts them), then again
    // for any that didn't answer yet: a tunnel can take some seconds to carry traffic. Only while Routes is on screen;
    // leaving it stops the asking.
    val activeIds = remember(setup) { setup.activeRoutes.map { it.id } }
    var asking by remember(on, vpn.engine, activeIds) { mutableStateOf(on && ui.exitTriesLeft()) }
    LaunchedEffect(on, vpn.engine, activeIds) {
        if (!on) return@LaunchedEffect
        delay(3_000)
        for (wait in listOf(10_000L, 20_000L, 40_000L)) { if (ui.checkExits() || !ui.exitTriesLeft()) break; delay(wait) }
        if (!ui.checkExits()) asking = false
    }
    // A route with a tunnel and no answer yet says it is being checked, so its card keeps its size meanwhile; it says it
    // couldn't be checked only once every attempt has failed.
    val exitOf = { id: String -> report(id) ?: ExitReport(null, 0, vpn.engine, null).takeIf { on && (id in vpn.servers || setup.route(id)?.tsExit != null) } }
    val pendingOf = { r: ExitReport? -> r != null && r.ip == null && (asking || r.at == 0L) }
    // Right after a sign-in, a provider's new key may not reach all its servers for a minute: say so instead of nothing.
    val accounts by ui.accounts.collectAsStateWithLifecycle()
    val settingUp by produceState(emptySet<String>(), on, setup, accounts) {
        while (true) {
            value = if (on) Providers.settingUp().filterTo(HashSet()) { p -> setup.activeRoutes.any { !it.imported && it.loc.provider == p } } else emptySet()
            if (value.isEmpty()) break
            delay(5_000)
        }
    }
    var resumed by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val privateDns = remember(resumed) { context.privateDnsHost() }

    // The snooze ends while the screen may stay open: wake at its end, and look again on every return to the app.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Onboarding.leakUntil) { (Onboarding.leakUntil - System.currentTimeMillis()).takeIf { it > 0 }?.let { delay(it); tick++ } }
    val leakDue = remember(resumed, tick, Onboarding.leak, Onboarding.leakUntil) { Onboarding.leakDue }

    val power = { if (vpn.phase == Phase.On || vpn.phase == Phase.Connecting) sheet = "power" else connect() }
    val scroll = rememberLazyListState()
    OnReselect(Tab.Routes) { if (managing) managing = false else scroll.animateScrollToItem(0) }
    BackHandler(managing) { managing = false }
    if (managing) {
        ProfilesPage(ui, setup, onBack = { managing = false }, onAdd = addProfile, onEdit = { profileSheet = it })
    } else Screen(
        state = scroll,
        overline = "",
        overlineContent = { Status(vpn, switching != null) },
        title = if (switching != null) "Switching" else when (vpn.phase) {
            Phase.On -> if (setup.directOnly) "Connected" else "Protected"
            Phase.Paused -> "Paused"; Phase.Connecting -> "Connecting"; Phase.Off -> "Unprotected"
        },
        subColor = if (vpn.error != null) Ink.bad else Ink.dim,
        sub = switching?.let { "To $it · apps reconnect for a moment" } ?: vpn.error ?: settingUp.takeIf { it.isNotEmpty() }?.let { ps -> "${ps.joinToString(" and ") { Providers.get(it)?.name ?: it }} is setting up your key. Its routes can take a minute to work" } ?: when (vpn.phase) {
            Phase.On -> "Tap to pause or disconnect"
            Phase.Paused -> if (vpn.lockdown) "Apps are offline (Block connections without VPN) · tap to resume" else "Apps skip the VPN · tap to resume"
            Phase.Connecting -> "Setting up your routes"
            Phase.Off -> "Tap to connect"
        },
        // Connected: offer pause or disconnect. Paused or off: one tap connects. The status text says "tap to …",
        // so tapping it does the same as the button.
        action = { PowerButton(vpn, busy = switching != null) { power() } },
        onHeader = { power() },
        headerLabel = when (vpn.phase) { Phase.On, Phase.Connecting -> "Pause or disconnect"; Phase.Paused -> "Resume"; Phase.Off -> "Connect" },
    ) {
        privateDns?.let { host ->
            item(key = "privateDns") {
                Text(
                    "Android Private DNS ($host) does DNS itself, so apps' lookups skip their routes. Set Private DNS to Automatic.",
                    style = Type.caption, color = Ink.accent,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).tap({ runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } }),
                )
            }
        }
        // A switch the engine couldn't run: the last profile is back, said once, with Try again.
        failed?.let { f ->
            item(key = "switchFailed") {
                Notice(Icons.error, Ink.bad, "Couldn't switch to ${f.to}.${f.kept?.let { " $it is still in use." }.orEmpty()}", "Try again", dismiss = { ui.switchFailed.value = null }, live = true) { ui.useProfile(f.to) }
            }
        }
        item(key = "profileLabel") {
            SectionLabel("Profile", tall = true) {
                if (setup.profiles.size >= MAX_PROFILES) Text("$MAX_PROFILES of $MAX_PROFILES", style = Type.caption, color = Ink.dim, modifier = Modifier.padding(end = 4.dp))
                if (setup.profiles.isNotEmpty()) Manage { managing = true }
            }
        }
        item(key = "profiles") {
            if (setup.profiles.isEmpty()) Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = Gutter - 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Save this setup to switch back to it in one tap.", style = Type.sub, color = Ink.dim, modifier = Modifier.weight(1f))
                AddButton(Icons.layers, "Add profile", onClick = addProfile)
            } else ProfileStrip(setup, switching, { ui.useProfile(it) }, addProfile)
        }
        if (full && setup.profiles.size >= MAX_PROFILES) item(key = "full") {
            Notice(Icons.layers, Ink.dim, "$MAX_PROFILES profiles is the most. Delete one to add another.", "Manage", dismiss = { full = false }, live = true) { full = false; managing = true }
        }
        item(key = "label") {
            SectionLabel("Default route", tall = true) {
                AddButton(Icons.routes, "Add route", Modifier.then(sharedRoute("", RoundedCornerShape(20.dp))).then(tourAnchor(TourStop.AddRoute))) { openPicker("") }
            }
        }
        item(key = "default") {
            val route = setup.route(setup.defaultRoute)
            Rise("routes", 0, reveal) { DefaultTile(
                key = setup.defaultRoute,
                apps = defaultPkgs.size, code = route?.code, chip = if (on && route != null) Ink.route(route.hue) else Ink.faint, direct = route == null,
                title = route?.name ?: "Direct", meta = route?.let { place(it, live) } ?: "No VPN for these apps",
                extra = if (on && route != null && server != null) serverId(route, server) else null,
                border = (route?.let { Ink.route(it.hue) } ?: Ink.line).copy(alpha = if (on) 0.45f else 0.25f),
                exit = exitOf(setup.defaultRoute).let { r -> r?.let { it to pendingOf(it) } },
            ) { sheet = setup.defaultRoute } }
        }
        // Android refused the VPN: most often another VPN app holds Always-on.
        if (Onboarding.consentFailed && vpn.phase == Phase.Off) item(key = "consent") {
            Notice(Icons.block, Ink.bad, "Android didn't start $APP's VPN. If another VPN app is set to Always-on, turn that off in Android's VPN settings.", "Open Android VPN settings") {
                runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            }
        }
        // The leak-proof setup was snoozed: one quiet reminder once the snooze is over; closing it snoozes it again.
        if (leakDue && on && !(vpn.alwaysOn && vpn.lockdown)) item(key = "leak") {
            Notice(Icons.shield, Ink.accent, "Apps go online without $APP when it's off", "Set up", dismiss = { Onboarding.snoozeLeak() }, dismissLabel = "Not now", onAction = leakSetup)
        }
        if (exceptions.isNotEmpty()) item(key = "exLabel") { SectionLabel("Exceptions", tall = true) }
        itemsIndexed(exceptions, key = { _, st -> st.id }) { i, st ->
            val direct = st.id == DIRECT
            Rise("routes", i + 1, reveal) { RouteRow(
                ui, st.pkgs, labels, appsLabel(st.pkgs, labels),
                code = st.code, chip = if (on || direct) st.color else Ink.faint, direct = direct,
                title = st.name, meta = st.detail,
                shape = groupShape(i, exceptions.size), fill = if (direct) Color.Transparent else Ink.surface,
                border = null, dashed = direct, minHeight = 72.dp,
                modifier = Modifier.padding(vertical = 1.dp), key = st.id.takeUnless { direct },
                exit = if (direct) null else exitOf(st.id)?.let { it to pendingOf(it) },
            ) { sheet = st.id } }
        }
        // Nothing set apart yet: say what that means instead of leaving the page empty.
        if (setup.apps.isEmpty()) item(key = "allDefault") {
            Text("All apps use the default route. Add a route to send some apps through another country.", style = Type.sub, color = Ink.dim, modifier = Modifier.padding(start = 24.dp, end = Gutter, top = 12.dp))
        }
        if (setup.home.tailscale || setup.home.wg != null) item(key = "home") {
            HomeRow(setup.home, lit = on, loginUrl = vpn.tsLoginUrl.takeIf { setup.home.tailscale }) { tsLogin(it) }
        }
        if (setup.activeRoutes.count { !it.imported && it.loc.provider == "nord" } > 6) item(key = "note") {
            Text(
                "Nord allows 10 connections per account; each active location uses one.",
                style = Type.caption, color = Ink.faint, modifier = Modifier.padding(start = 24.dp, end = Gutter, top = 12.dp),
            )
        }
    }

    sheet?.let { id ->
        val state = rememberModalBottomSheetState()
        val scope = rememberCoroutineScope()
        fun act(f: () -> Unit = {}) { f(); scope.launch { state.hide() }.invokeOnCompletion { sheet = null } }
        ModalBottomSheet({ sheet = null }, sheetState = state, containerColor = Ink.surface, contentColor = Ink.text) {
            Column(Modifier.padding(bottom = 16.dp)) {
                Text(if (id == "power") "Pause or disconnect" else list.firstOrNull { it.id == id }?.name.orEmpty(), style = Type.title, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                if (id == "power") {
                    Text(
                        if (vpn.lockdown) "Blocking is on: every app, Direct apps included, is offline while paused."
                        else "While paused, apps use your normal connection.",
                        style = Type.sub, color = Ink.dim, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    val what = if (vpn.lockdown) "Pause internet for" else "Pause"
                    listOf(5 to "5 minutes", 15 to "15 minutes", 60 to "1 hour").forEach { (m, label) -> SheetAction("$what $label") { act { ui.pause(m) } } }
                    // With Always-on, Android brings the VPN straight back: only its own page can turn it off.
                    if (vpn.alwaysOn) {
                        SheetAction("Turn off in Android settings") { act { runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } } }
                        Text("Always-on is set, so Android keeps $APP running; it can only be turned off there.", style = Type.caption, color = Ink.dim, modifier = Modifier.padding(horizontal = 24.dp))
                    } else SheetAction("Disconnect", Ink.bad) { act { ui.disconnect() } }
                } else if (id == DIRECT) {
                    Text("Apps here skip the VPN. Assign them in Apps.", style = Type.sub, color = Ink.dim, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    if (setup.defaultRoute != DIRECT) SheetAction("Make default") { act { ui.setDefault(DIRECT) } }
                } else {
                    SheetAction("Change location") { act { openPicker(id) } }
                    setup.route(id)?.takeIf { it.imported }?.let { r -> SheetAction("Rename") { act { newName = r.name; renaming = id } } }
                    if (id != setup.defaultRoute) {
                        SheetAction("Make default") { act { ui.setDefault(id) } }
                        SheetAction("Remove route", Ink.bad) { act { ui.removeRoute(id) } }
                        val others = setup.saved.filter { p -> p.name != setup.profile && (p.defaultRoute == id || id in p.apps.values) }.map { it.name }
                        Text(
                            "Its apps will use the default route." + if (others.isEmpty()) "" else " ${others.joinToString()} ${if (others.size == 1) "uses" else "use"} it too and will change the same way.",
                            style = Type.caption, color = Ink.dim, modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                }
            }
        }
    }
    profileSheet?.let { editing ->
        ProfileSheet(setup, editing.ifEmpty { null },
            onSave = { n -> if (editing.isEmpty()) ui.saveProfile(n) else ui.renameProfile(editing, n); profileSheet = null },
            onDelete = { ui.forgetProfile(editing); profileSheet = null }, onDismiss = { profileSheet = null })
    }
    renaming?.let { id ->
        Prompt("Rename", "Save", { ui.renameRoute(id, newName); renaming = null }, { renaming = null }, enabled = newName.isNotBlank()) {
            DialogField(newName, { newName = it.take(40) }, "Name", "e.g. Home VPS")
        }
    }
}

/** A card on Routes that asks for one thing: [action] does it, [dismiss] (when given) puts it away. */
@Composable
private fun Notice(
    icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, text: String, action: String,
    dismiss: (() -> Unit)? = null, dismissLabel: String = "Dismiss", live: Boolean = false, onAction: () -> Unit,
) = Row(
    // [live]: TalkBack reads it out when it appears (a failure the user didn't tap for).
    Modifier.then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier).padding(horizontal = Gutter, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Ink.surface)
        .border(1.dp, Ink.line, RoundedCornerShape(20.dp)).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
) {
    // At large text the action moves under the text, which keeps the card's width.
    val large = LocalDensity.current.fontScale >= 1.5f
    Icon(icon, null, Modifier.size(22.dp), tint = tint)
    Column(Modifier.weight(1f).padding(vertical = 8.dp), horizontalAlignment = Alignment.End) {
        Text(text, style = Type.sub, color = Ink.text, modifier = Modifier.fillMaxWidth())
        if (large) Secondary(action, onAction)
    }
    if (!large) Secondary(action, onAction)
    dismiss?.let { IconTap(Icons.close, dismissLabel, it, size = 40.dp, tint = Ink.dim) }
}

@Composable
private fun SheetAction(text: String, color: Color = Ink.text, onClick: () -> Unit) = Box(
    Modifier.fillMaxWidth().heightIn(min = 56.dp).tap(onClick).padding(horizontal = 24.dp),
    contentAlignment = Alignment.CenterStart,
) { Text(text, style = Type.body, color = color) }

/** Header overline. Owns the once-a-second clock so that tick recomposes only this line, not the whole screen. */
@Composable
private fun Status(vpn: VpnState, switching: Boolean = false) {
    val on = vpn.phase == Phase.On
    val now by produceState(System.currentTimeMillis(), on) { while (on) { value = System.currentTimeMillis(); delay(1000) } }
    val elapsed = ((now - vpn.since) / 1000).coerceAtLeast(0).let { "%02d:%02d:%02d".format(it / 3600, it / 60 % 60, it % 60) }
    val label = if (switching) "Switching profile" else when (vpn.phase) {
        Phase.On -> "Connected · $elapsed"
        Phase.Connecting -> "Connecting"
        Phase.Off -> "Not connected"
        Phase.Paused -> if (vpn.pausedUntil > 0) "Paused · resumes ${if (vpn.pausedAbout) "about " else ""}${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(vpn.pausedUntil))}" else "Paused"
    }
    Text(label.uppercase(), style = Type.overline, color = if (switching) Ink.dim else stateColor(vpn.phase), maxLines = 1)
}

/** Green protected, orange paused, red unprotected: the button and the status line say the same thing. */
private fun stateColor(phase: Phase) = when (phase) { Phase.On -> Ink.good; Phase.Paused -> Ink.accent; Phase.Off -> Ink.bad; Phase.Connecting -> Ink.dim }

/**
 * The expressive power button. A cookie that turns slowly while protected; while connecting it relaxes
 * into a circle around a loader, then springs back and blooms once when protection is up. A pause shows its time
 * draining as a still, wavy ring.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PowerButton(vpn: VpnState, busy: Boolean = false, onClick: () -> Unit) {
    // A profile switch reconnects every app: the button shows it as it shows a connect.
    val phase = if (busy && vpn.phase == Phase.On) Phase.Connecting else vpn.phase
    val haptic = LocalHapticFeedback.current
    val motion = MaterialTheme.motionScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, motion.fastSpatialSpec(), label = "press")
    val fill by animateColorAsState(if (phase == Phase.Connecting) Ink.surfaceTop else stateColor(phase), motion.fastEffectsSpec(), label = "fill")
    val round by animateFloatAsState(if (phase == Phase.Connecting) 1f else 0f, motion.defaultSpatialSpec(), label = "morph")
    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Circle) }
    // Bloom and a Confirm tick only when protection comes up after connecting, not when the screen opens on it.
    val bloom = remember { Animatable(1f) }
    var was by remember { mutableStateOf(phase) }
    LaunchedEffect(phase) {
        val before = was
        was = phase
        if (phase == Phase.On && before == Phase.Connecting) {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            bloom.snapTo(0f)
            bloom.animateTo(1f, motion.slowEffectsSpec())
        } else if (phase != Phase.On) bloom.snapTo(1f) // an interrupted bloom never lingers
    }
    // Only a live connection turns, slowly.
    val turn = if (phase == Phase.On) rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(40_000, easing = LinearEasing)), label = "turn") else null
    val left by produceState(1f, phase, vpn.pausedUntil) {
        while (phase == Phase.Paused && vpn.pausedUntil > vpn.pausedAt) {
            value = ((vpn.pausedUntil - System.currentTimeMillis()).toFloat() / (vpn.pausedUntil - vpn.pausedAt)).coerceIn(0f, 1f)
            delay(1000)
        }
    }
    Box(
        Modifier.size(104.dp).then(tourAnchor(TourStop.Power)).graphicsLayer { scaleX = scale; scaleY = scale }
            // The header carries the status and the same action for TalkBack ([Screen] onHeader): one focus stop, not two.
            .semantics { hideFromAccessibility() }
            .clickable(source, ripple(bounded = false, radius = 52.dp), role = Role.Button, onClick = onClick), // Confirm comes when protection is up
        contentAlignment = Alignment.Center,
    ) {
        if (phase == Phase.Paused && vpn.pausedUntil > 0) {
            CircularWavyProgressIndicator(
                progress = { left }, modifier = Modifier.size(104.dp), color = Ink.accent, trackColor = Ink.surfaceTop,
                stroke = Stroke(3.dp.value * LocalDensity.current.density, cap = StrokeCap.Round), trackStroke = Stroke(3.dp.value * LocalDensity.current.density),
                amplitude = { 1f }, waveSpeed = 0.dp, // wavy for the whole pause, and still
            )
        } else Box(Modifier.size(104.dp).border(1.dp, fill.copy(alpha = 0.25f), CircleShape))
        if (phase == Phase.On && bloom.value < 1f) Box(
            Modifier.size(104.dp).graphicsLayer { val b = bloom.value; scaleX = 1f + 0.22f * b; scaleY = scaleX; alpha = 0.7f * (1f - b) }
                .border(2.dp, Ink.good, CircleShape),
        )
        Box(Modifier.size(if (phase == Phase.Paused && vpn.pausedUntil > 0) 88.dp else 96.dp).graphicsLayer { rotationZ = turn?.value ?: 0f }.background(fill, MorphShape(morph, round)))
        if (phase == Phase.Connecting) LoadingIndicator(Modifier.size(52.dp), color = Ink.good)
        else Icon(Icons.power, null, tint = Ink.ground, modifier = Modifier.size(32.dp))
    }
}

/** App labels for a small group ("Signal", "Bank A, Bank B"), the app count for a larger one ("7 apps"). */
private fun appsLabel(pkgs: List<String>, labels: Map<String, String>) = when {
    pkgs.isEmpty() -> "No apps assigned"
    pkgs.size <= 3 -> pkgs.joinToString(", ") { labels[it] ?: it.substringAfterLast('.') }
    else -> count(pkgs.size)
}

/** The default route as the hero: where everything else exits, large, and how many apps that is. No icons: it is "the rest". */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DefaultTile(
    key: String, apps: Int, code: String?, chip: Color, direct: Boolean, title: String, meta: String, extra: String?, border: Color,
    exit: Pair<ExitReport, Boolean>? = null, onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // Press: corners tighten 28 → 16 dp and the card squishes, then both spring back.
    val corner by animateDpAsState(if (pressed) 16.dp else 28.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "corner")
    val press = squish(source)
    val shape = RoundedCornerShape(corner)
    Column(
        Modifier.padding(horizontal = Gutter).then(tourAnchor(TourStop.DefaultRoute)).graphicsLayer { scaleX = press.value; scaleY = press.value }.then(sharedRoute(key, shape))
            .fillMaxWidth().clip(shape).background(Ink.surfaceTop).border(1.dp, border, shape)
            .clickable(source, ripple(), onClick = onClick).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("All other apps · $apps".uppercase(), style = Type.overline, color = Ink.dim, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RouteMark(code, chip, direct = direct)
            Text(title, style = Type.title.copy(fontSize = 24.sp, lineHeight = 30.sp), color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // At large text the place and the server each take a line, instead of the place being cut short.
        if (LocalDensity.current.fontScale >= 1.5f) {
            Text(meta, style = Type.caption, color = Ink.dim)
            extra?.let { Text(it, style = Type.code, color = Ink.dim) }
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(meta, style = Type.caption, color = Ink.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            extra?.let { Text(it, style = Type.code, color = Ink.dim, maxLines = 1) }
        }
        exit?.let { (r, pending) -> ExitLine(r, pending) }
    }
}

/** One route: its apps on the left (five icons at most, then +N), where they exit on the right. */
@Composable
private fun RouteRow(
    ui: Ui, pkgs: List<String>, labels: Map<String, String>, appsLabel: String,
    code: String?, chip: Color, direct: Boolean, title: String, meta: String,
    shape: Shape, fill: Color, border: Color?, minHeight: Dp, modifier: Modifier = Modifier,
    dashed: Boolean = false, key: String? = null, exit: Pair<ExitReport, Boolean>? = null, onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val press = squish(source)
    // Everything about the exit reads together on the right, its address under its server. A long (IPv6) address takes
    // a line of its own under the row; at large text the row stacks, apps above the exit, so nothing is cut away.
    val wide = exit?.first?.ip?.contains(':') == true
    val stacked = LocalDensity.current.fontScale >= 1.5f
    val apps = @Composable {
        AppStack(ui, pkgs, labels, edge = if (fill == Color.Transparent) Ink.ground else fill)
        Text(appsLabel, style = Type.caption, color = if (pkgs.isEmpty()) Ink.dim else Ink.text, maxLines = if (stacked) 2 else 1, overflow = TextOverflow.Ellipsis)
    }
    val where = @Composable { end: Boolean ->
        Text(title, style = Type.title, color = Ink.text, maxLines = if (stacked) 2 else 1, overflow = TextOverflow.Ellipsis, textAlign = if (end) TextAlign.End else null)
        Text(meta, style = Type.caption, color = Ink.dim, maxLines = if (stacked) 2 else 1, overflow = TextOverflow.Ellipsis, textAlign = if (end) TextAlign.End else null)
        if (!wide) exit?.let { (r, pending) -> ExitLine(r, pending) }
    }
    Column(
    modifier.padding(horizontal = Gutter).graphicsLayer { scaleX = press.value; scaleY = press.value }
        .then(if (key != null) sharedRoute(key, shape) else Modifier)
        .fillMaxWidth().heightIn(min = minHeight).clip(shape).background(fill)
        .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
        .then(if (dashed) Modifier.dashed(shape) else Modifier)
        .clickable(source, ripple(), onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(if (stacked) 10.dp else 6.dp, Alignment.CenterVertically),
) {
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { apps() }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { where(false) }
            RouteMark(code, chip, direct = direct)
        }
    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f, fill = false).widthIn(max = 136.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { apps() }
        // The exit reads right-aligned beside its chip, which closes the row on the right.
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) { where(true) }
        RouteMark(code, chip, direct = direct)
    }
    exit?.takeIf { wide }?.let { (r, pending) -> Box(Modifier.fillMaxWidth().padding(end = 42.dp), contentAlignment = Alignment.CenterEnd) { ExitLine(r, pending) } }
}
}

/**
 * The address a route's exit reported ([app.unbraid.vpn.ExitCheck]), in the size of the server line above it. A check
 * that failed is said quietly, never in red: the route may well be working.
 */
@Composable
private fun ExitLine(r: ExitReport, pending: Boolean) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    if (r.ip == null && !pending) Icon(Icons.info, null, Modifier.size(14.dp), tint = Ink.dim)
    Text(
        r.ip ?: if (pending) "Checking the exit…" else "Couldn't check this exit's address",
        style = if (r.ip != null) Type.code else Type.caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
        color = Ink.dim,
    )
}

/** Up to five overlapping icons, then a "+N" pill; an empty slot when no app is assigned. */
@Composable
private fun AppStack(ui: Ui, pkgs: List<String>, labels: Map<String, String>, edge: Color) = Row(
    Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((-6).dp),
) {
    if (pkgs.isEmpty()) Box(Modifier.size(24.dp).dashed(RoundedCornerShape(8.dp)))
    pkgs.take(5).forEach { pkg ->
        Box(Modifier.border(1.5.dp, edge, RoundedCornerShape(8.dp))) { AppIcon(ui, pkg, labels[pkg] ?: pkg, 24.dp) }
    }
    if (pkgs.size > 5) Text(
        "+${pkgs.size - 5}", style = Type.code.copy(fontSize = 11.sp), color = Ink.text, maxLines = 1,
        modifier = Modifier.padding(start = 10.dp).height(24.dp).widthIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).background(Ink.surfaceTop)
            .padding(horizontal = 7.dp).wrapContentHeight(Alignment.CenterVertically),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun HomeRow(home: Home, lit: Boolean, loginUrl: String?, signIn: (String) -> Unit) {
    val via = if (home.tailscale) "Tailscale" else "WireGuard · ${home.wg?.endpoint?.substringBeforeLast(':').orEmpty()}"
    Column(
        Modifier.padding(start = Gutter, end = Gutter, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Ink.homeSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(140.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(Ink.surfaceTop), Alignment.Center) { Icon(Icons.apps, null, Modifier.size(16.dp), tint = Ink.text) }
                Text("Every app", style = Type.caption, color = Ink.text, maxLines = 1)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Code("HM", if (lit) Ink.route(3) else Ink.faint)
                    Text("Home network", style = Type.title.copy(fontSize = 16.sp), color = Ink.text, maxLines = 1)
                }
                Text(listOfNotNull(via, home.subnets.joinToString(", ").ifBlank { null }).joinToString(" · "), style = Type.caption, color = Ink.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        loginUrl?.let { Secondary("Sign in to Tailscale", { signIn(it) }, Modifier.offset(x = (-16).dp)) }
    }
}

/** Dashed outline: rows and slots that are outside the VPN or still empty. */
private fun Modifier.dashed(shape: Shape) = drawBehind {
    val w = 1.5.dp.toPx()
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(outline, Ink.line, style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))))
}
