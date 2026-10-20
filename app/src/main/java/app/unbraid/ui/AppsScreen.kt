package app.unbraid.ui

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import app.unbraid.APP
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.unbraid.data.DIRECT
import app.unbraid.data.Setup

private fun Setup.codeOf(id: String) = route(id)?.code ?: "··"
private fun Setup.colorOf(id: String) = route(id)?.let { Ink.route(it.hue) } ?: Ink.direct

@Composable
fun AppsScreen(ui: Ui, disclose: () -> Unit, openPicker: (String) -> Unit) {
    val disclosed by ui.disclosed.collectAsStateWithLifecycle()
    val reveal = firstShow("apps")
    val setup by ui.setup.collectAsStateWithLifecycle()
    val apps by ui.apps.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    // The exit the open app had when it was opened: it keeps its place (and filter) until closed, then regroups.
    var openedAs by rememberSaveable { mutableStateOf<String?>(null) }
    fun placeOf(pkg: String) = openedAs?.takeIf { pkg == open } ?: setup.routeOf(pkg)
    // A closed row that changed exit lands in its new group; follow it there if that is off-screen.
    val list = rememberLazyListState()
    var land by remember { mutableStateOf<String?>(null) }

    val query = q.trim()
    val motion = MaterialTheme.motionScheme
    // Grouped by exit: the other exits A to Z by name (Albania, Bulgaria, United States), then Direct, then the default.
    val rank = remember(setup) {
        (setup.routes.sortedBy { it.name.lowercase() }.map { it.id } + DIRECT).filter { it != setup.defaultRoute }
            .withIndex().associate { (i, id) -> id to i }
    }
    // `apps` is already sorted by label, and sortedBy is stable, so each group stays in label order.
    val shown = remember(apps, setup, query, filter, showSystem, open, openedAs) {
        apps.filter { a ->
            (showSystem || !a.system || a.pkg in setup.apps) &&
                (filter == null || placeOf(a.pkg) == filter) &&
                (query.isEmpty() || a.label.contains(query, true) || a.pkg.contains(query, true))
        }.sortedBy { rank[placeOf(it.pkg)] ?: Int.MAX_VALUE }
    }
    val direct = apps.count { it.pkg in setup.apps && setup.apps[it.pkg] == DIRECT }
    val routed = apps.count { it.pkg in setup.apps } - direct

    LaunchedEffect(shown, land) {
        val key = land ?: return@LaunchedEffect
        land = null
        val at = shown.indexOfFirst { it.pkg == key }
        if (at >= 0 && list.layoutInfo.visibleItemsInfo.none { it.key == key }) list.animateScrollToItem(at)
    }

    Screen(
        state = list,
        overline = "${apps.size} installed", title = "Apps", sub = "$routed routed · $direct direct",
        search = { SearchField(q, { q = it }, "Search apps") },
        belowSearch = {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("All", Ink.text, filter == null) { filter = null }
                // Two routes with one country code (NordVPN NL, Mullvad NL) are told apart by provider.
                setup.routes.forEach { r ->
                    val label = if (setup.routes.count { it.code == r.code } > 1) "${r.code} · ${r.source}" else r.code
                    FilterChip(label, Ink.route(r.hue), filter == r.id) { filter = r.id }
                }
                FilterChip("Direct", Ink.direct, filter == DIRECT, direct = true) { filter = DIRECT }
                FilterChip("System", null, showSystem, independent = true) { showSystem = !showSystem }
            }
        },
    ) {
        if (!disclosed) item("disclose") {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp).then(tourAnchor(TourStop.AppsList)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$APP needs the list of installed apps to route each one. It stays on this phone.", style = Type.sub, color = Ink.dim)
                Secondary("Show my apps", disclose)
            }
        } else if (shown.isEmpty()) item("empty") {
            Text("No apps match", style = Type.sub, color = Ink.dim, modifier = Modifier.padding(24.dp))
        }
        itemsIndexed(shown, key = { _, a -> a.pkg }) { i, a ->
            // Regrouping slides rows to their new place instead of jumping.
            Box(Modifier.animateItem(fadeInSpec = null, placementSpec = motion.defaultSpatialSpec(), fadeOutSpec = null).then(if (i == 0) tourAnchor(TourStop.AppsList) else Modifier)) {
                Rise("apps", i, reveal) {
                    AppRow(ui, a, setup, expanded = open == a.pkg, onToggle = {
                        if (open == a.pkg) { if (openedAs != setup.routeOf(a.pkg)) land = a.pkg; open = null; openedAs = null }
                        else { open = a.pkg; openedAs = setup.routeOf(a.pkg) }
                    }, openPicker)
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, dot: Color?, on: Boolean, direct: Boolean = false, independent: Boolean = false, onClick: () -> Unit) = Box(
    // Exit filters are one choice among many; "System" is an independent on/off filter.
    Modifier.height(48.dp).then(if (independent) Modifier.toggleable(on, role = Role.Checkbox) { onClick() } else Modifier.selectable(on, role = Role.RadioButton, onClick = onClick)),
    contentAlignment = Alignment.Center,
) {
    val shape = RoundedCornerShape(12.dp)
    // Selection fades in with the effects spring, like the other selectable pills.
    val bg by animateColorAsState(if (on) Ink.surfaceTop else Color.Transparent, MaterialTheme.motionScheme.fastEffectsSpec(), label = "chip")
    val edge by animateColorAsState(if (on) Ink.surfaceTop else Ink.line, MaterialTheme.motionScheme.fastEffectsSpec(), label = "chipEdge")
    Row(
        Modifier.height(36.dp).clip(shape).background(bg).border(1.dp, edge, shape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (direct) Icon(Icons.direct, null, Modifier.size(14.dp), tint = Ink.direct)
        else dot?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(it)) }
        Text(label, style = Type.sub.copy(fontWeight = FontWeight.Medium), color = if (on) Ink.text else Ink.dim, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppRow(ui: Ui, a: AppInfo, setup: Setup, expanded: Boolean, onToggle: () -> Unit, openPicker: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val explicit = setup.apps[a.pkg]
    val current = setup.routeOf(a.pkg)
    val motion = MaterialTheme.motionScheme
    val ground by animateColorAsState(if (expanded) Ink.surface else Color.Transparent, motion.fastEffectsSpec(), label = "row")
    Column(Modifier.padding(horizontal = 8.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(ground)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).tap(onToggle).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppIcon(ui, a.pkg, a.label)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(a.label, style = Type.body, color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.pkg, style = Type.small, color = Ink.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RouteMark(setup.codeOf(current), setup.colorOf(current), filled = explicit != null, direct = current == DIRECT)
        }
        // The exits spring open under the row and fade in; closing reverses it.
        AnimatedVisibility(
            expanded,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            val tiles = setup.routes.sortedByDescending { it.id == setup.defaultRoute }.map {
                Tile(it.id, if (it.imported) it.name else it.loc.city ?: it.name, if (it.tsExit != null) Icons.home else Icons.tunnel, Ink.route(it.hue), sub = it.source.takeIf { s -> s != it.name })
            } + Tile(DIRECT, "Direct", Icons.direct, Ink.direct, tinted = false) + Tile("", "More", Icons.more, Ink.dim, tinted = false)
            Row(
                Modifier.padding(start = 68.dp, end = 14.dp, bottom = 14.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                tiles.forEachIndexed { i, t ->
                    val on = t.id == current
                    ToggleButton(
                        checked = on,
                        onCheckedChange = {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            if (t.id.isEmpty()) openPicker("app:${a.pkg}") else ui.assign(a.pkg, t.id)
                        },
                        modifier = Modifier.width(96.dp).height(72.dp), // one size for every route, whatever its name
                        // Selection changes the colour only; a press squishes and springs back.
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            tiles.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        }.let { ToggleButtonShapes(it.shape, it.pressedShape, it.shape) },
                        colors = ToggleButtonDefaults.colors(
                            containerColor = Ink.surfaceTop, contentColor = Ink.dim,
                            checkedContainerColor = t.color.takeIf { t.id.isNotEmpty() } ?: Ink.surfaceTop, checkedContentColor = Ink.ground,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(t.icon, null, Modifier.size(16.dp), tint = if (!on && t.tinted) t.color else LocalContentColor.current)
                            Text(
                                t.label, style = Type.caption.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
                                color = if (!on && t.tinted) Ink.text else LocalContentColor.current, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            t.sub?.let {
                                Text(
                                    it, style = Type.caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
                                    color = if (!on) Ink.dim else LocalContentColor.current, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private class Tile(val id: String, val label: String, val icon: ImageVector, val color: Color, val tinted: Boolean = true, val sub: String? = null)
