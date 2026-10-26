package app.unbraid.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.unbraid.data.DIRECT
import app.unbraid.data.MAX_PROFILES
import app.unbraid.data.PROFILE_NAME
import app.unbraid.data.Profile
import app.unbraid.data.Setup
import app.unbraid.data.graphemes
import app.unbraid.data.takeGraphemes

/** [glyph] with a small + badge: the mark of Add route and Add profile. */
@Composable
fun AddMark(glyph: ImageVector, ink: Color, badge: Color, plus: Color, ring: Color) = Box(Modifier.size(24.dp)) {
    Icon(glyph, null, Modifier.size(24.dp), tint = ink)
    Box(Modifier.align(Alignment.BottomEnd).offset(5.dp, 4.dp).size(15.dp).clip(CircleShape).background(ring).padding(2.dp).clip(CircleShape).background(badge), contentAlignment = Alignment.Center) {
        Icon(Icons.plus, null, Modifier.size(10.dp), tint = plus)
    }
}

/**
 * Add route and Add profile are the same control: a 52 × 40 dp accent pill in a 48 dp target, its glyph badged with +.
 * Not [lit], it greys but stays tappable, so it can say why nothing more can be added.
 */
@Composable
fun AddButton(glyph: ImageVector, label: String, modifier: Modifier = Modifier, lit: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val press = squish(source)
    val fill = if (lit) Ink.accent else Ink.surfaceHigh
    Box(
        modifier.size(56.dp, 48.dp).clickable(source, null, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.graphicsLayer { scaleX = press.value; scaleY = press.value }.size(52.dp, 40.dp).clip(RoundedCornerShape(20.dp)).background(fill).indication(source, ripple()),
            contentAlignment = Alignment.Center,
        ) { if (lit) AddMark(glyph, Ink.onAccent, Ink.onAccent, Ink.accent, fill) else AddMark(glyph, Ink.faint, fill, Ink.faint, fill) }
    }
}

/** "Manage ›": quiet, in the text colour, so the accent stays with the add actions. */
@Composable
fun Manage(onClick: () -> Unit) = Row(
    Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = onClick).padding(start = 8.dp, end = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text("Manage", style = Type.title.copy(fontSize = Type.sub.fontSize), color = Ink.text)
    Icon(Icons.chevron, null, Modifier.size(20.dp), tint = Ink.dim)
}

/**
 * The profiles as a connected button group that scrolls sideways, Add profile pinned on the right. The one in use (or
 * being switched to, with a small loader in place of its check) is always scrolled fully into view.
 */
@Composable
fun ProfileStrip(setup: Setup, switching: String?, onUse: (String) -> Unit, onAdd: () -> Unit) {
    val names = setup.profiles.map { it.name }
    val selected = switching ?: setup.profile
    val list = rememberLazyListState()
    val clear = with(LocalDensity.current) { 28.dp.toPx() } // clear of the fade, with a neighbour peeking
    LaunchedEffect(selected, names) {
        val i = names.indexOf(selected).takeIf { it >= 0 } ?: return@LaunchedEffect
        if (list.layoutInfo.visibleItemsInfo.none { it.index == i }) list.scrollToItem(i)
        val seen = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i } ?: return@LaunchedEffect
        val end = list.layoutInfo.viewportEndOffset - clear
        val by = when {
            seen.offset < clear && i > 0 -> seen.offset - clear
            seen.offset + seen.size > end && i < names.lastIndex -> seen.offset + seen.size - end
            else -> 0f
        }
        if (by != 0f) list.animateScrollBy(by)
    }
    Row(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter - 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.weight(1f).height(48.dp)) {
            LazyRow(Modifier.fillMaxSize().selectableGroup(), state = list, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                itemsIndexed(names, key = { _, n -> n }) { i, n -> Segment(n, i, names.size, n == selected, busy = n == switching) { onUse(n) } }
            }
            // A fade where more profiles are hidden.
            val fade = { start: Boolean -> Brush.horizontalGradient(if (start) listOf(Ink.ground, Color.Transparent) else listOf(Color.Transparent, Ink.ground)) }
            if (list.canScrollBackward) Box(Modifier.align(Alignment.CenterStart).width(20.dp).fillMaxHeight().background(fade(true)))
            if (list.canScrollForward) Box(Modifier.align(Alignment.CenterEnd).width(20.dp).fillMaxHeight().background(fade(false)))
        }
        AddButton(Icons.layers, if (names.size < MAX_PROFILES) "Add profile" else "Profile limit reached", lit = names.size < MAX_PROFILES, onClick = onAdd)
    }
}

/** One profile: tinted with a check when selected (colour, not shape); corners follow its place in the group. */
@Composable
private fun Segment(name: String, i: Int, n: Int, selected: Boolean, busy: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val press = squish(source)
    val fill by animateColorAsState(if (selected) Ink.accentContainer else Ink.surfaceHigh, MaterialTheme.motionScheme.fastEffectsSpec(), label = "segment")
    val scale = LocalDensity.current.fontScale.coerceIn(1f, 2f) // larger text widens the segment rather than cutting the name
    val shape = RoundedCornerShape(if (i == 0) 22.dp else 8.dp, if (i == n - 1) 22.dp else 8.dp, if (i == n - 1) 22.dp else 8.dp, if (i == 0) 22.dp else 8.dp)
    Box(Modifier.height(48.dp).selectable(selected, source, null, role = Role.Tab, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier.graphicsLayer { scaleX = press.value; scaleY = press.value }.heightIn(min = 40.dp).widthIn(72.dp, 156.dp * scale).clip(shape).background(fill)
                .indication(source, ripple()).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = Ink.accent, strokeWidth = 2.dp, trackColor = Ink.accent.copy(alpha = 0.25f))
            else if (selected) Icon(Icons.check, null, Modifier.size(18.dp), tint = Ink.accent)
            Text(name, style = Type.title.copy(fontSize = Type.sub.fontSize, fontWeight = if (selected) Type.title.fontWeight else Type.body.fontWeight),
                color = if (selected) Ink.accent else Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** What a profile keeps, in words: "Netherlands · 3 exceptions". */
fun summary(s: Setup, p: Profile): String {
    val n = p.apps.values.toSet().size
    return "${s.route(p.defaultRoute)?.name ?: "Direct"} · ${if (n == 0) "no exceptions" else if (n == 1) "1 exception" else "$n exceptions"}"
}

/**
 * New profile ([editing] null) or edit one: a name of at most [PROFILE_NAME] characters people see, what it keeps,
 * and, when editing, Delete last.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(setup: Setup, editing: String?, onSave: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(editing.orEmpty()) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val ok = setup.profileName(name, except = editing)
    val taken = name.isNotBlank() && ok == null && graphemes(name.trim()) <= PROFILE_NAME
    val shown = setup.saved.firstOrNull { it.name == editing } ?: Profile("", setup.defaultRoute, setup.apps)
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Ink.surface, contentColor = Ink.text) {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Ink.accentContainer), contentAlignment = Alignment.Center) {
                    if (editing == null) AddMark(Icons.layers, Ink.accent, Ink.accent, Ink.accentContainer, Ink.accentContainer)
                    else Icon(Icons.layers, null, Modifier.size(24.dp), tint = Ink.accent)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (editing == null) "New profile" else "Edit profile", style = Type.title.copy(fontSize = Type.headline.fontSize * 0.5f))
                    Text(if (editing == null) "Saves this setup so you can switch back in one tap" else "Rename it, or delete it", style = Type.caption, color = Ink.dim)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    name, { name = it.takeGraphemes(PROFILE_NAME) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name", style = Type.caption) },
                    textStyle = Type.body, isError = taken, colors = inkFieldColors(), shape = RoundedCornerShape(16.dp),
                )
                Row(Modifier.padding(horizontal = 4.dp)) {
                    Text(if (taken) "You already have a profile with this name" else "Up to $PROFILE_NAME characters", style = Type.caption, color = if (taken) Ink.bad else Ink.dim, modifier = Modifier.weight(1f))
                    Text("${graphemes(name)}/$PROFILE_NAME", style = Type.code, color = Ink.dim)
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Ink.surfaceHigh).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("WHAT IT KEEPS", style = Type.overline, color = Ink.dim)
                Keeps("Default route", listOf(setup.route(shown.defaultRoute)?.name ?: "Direct"))
                val ex = shown.apps.entries.groupBy({ it.value }, { it.key }).map { (id, pkgs) -> "${if (id == DIRECT) "Direct" else setup.route(id)?.name ?: id} · ${if (pkgs.size == 1) "1 app" else "${pkgs.size} apps"}" }
                Keeps("Exceptions", ex.take(3).ifEmpty { listOf("None") } + listOfNotNull(if (ex.size > 3) "+${ex.size - 3} more" else null))
            }
            Text("Routes are shared: changing a route's location changes it in every profile that uses it.", style = Type.caption, color = Ink.dim)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (editing != null) { Secondary("Delete", { deleting = true }, color = Ink.bad); Box(Modifier.weight(1f)) }
                Secondary("Cancel", onDismiss, color = Ink.dim)
                Primary(if (editing == null) "Save profile" else "Save", { ok?.let(onSave) }, Modifier.width(150.dp), enabled = ok != null)
            }
        }
    }
    if (deleting) Prompt("Delete $editing?", "Delete", { deleting = false; onDelete() }, { deleting = false }, danger = true) {
        Text("Your routes stay as they are now.", style = Type.sub)
    }
}

@Composable
private fun Keeps(label: String, lines: List<String>) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(label, style = Type.sub, color = Ink.dim, modifier = Modifier.weight(1f))
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) { lines.forEach { Text(it, style = Type.sub) } }
}

/** Manage profiles: rename or delete behind the pencil, drag the handle (or TalkBack's Move up / down) to reorder. */
@Composable
fun ProfilesPage(ui: Ui, setup: Setup, onBack: () -> Unit, onAdd: () -> Unit, onEdit: (String) -> Unit) {
    val profiles = setup.saved
    var dragging by remember { mutableStateOf<String?>(null) }
    var drift by remember { mutableFloatStateOf(0f) }
    var rowPx by remember { mutableFloatStateOf(1f) }
    Screen(
        overline = "Routes", title = "Profiles",
        sub = "${profiles.size} of $MAX_PROFILES · drag to reorder",
        navigation = { IconTap(Icons.back, "Back", onBack) },
        bottom = {
            // At the limit the button greys and the reason sits beside it.
            Row(Modifier.fillMaxWidth().padding(Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (profiles.size >= MAX_PROFILES) Text("$MAX_PROFILES profiles is the most. Delete one to add another.", style = Type.caption, color = Ink.dim, modifier = Modifier.weight(1f))
                Row(
                    Modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(if (profiles.size < MAX_PROFILES) Ink.accent else Ink.surfaceHigh)
                        .clickable(role = Role.Button, onClick = onAdd).padding(start = 18.dp, end = 22.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val lit = profiles.size < MAX_PROFILES
                    AddMark(Icons.layers, if (lit) Ink.onAccent else Ink.faint, if (lit) Ink.onAccent else Ink.surfaceHigh, if (lit) Ink.accent else Ink.faint, if (lit) Ink.accent else Ink.surfaceHigh)
                    Text("Add profile", style = Type.title, color = if (lit) Ink.onAccent else Ink.faint)
                }
            }
        },
    ) {
        itemsIndexed(profiles, key = { _, p -> "profile:" + p.name }) { i, p ->
            val route = setup.route(p.defaultRoute)
            val up = CustomAccessibilityAction("Move up") { ui.moveProfile(p.name, -1); true }
            val down = CustomAccessibilityAction("Move down") { ui.moveProfile(p.name, 1); true }
            Row(
                Modifier.padding(horizontal = Gutter, vertical = 1.dp).onSizeChanged { rowPx = it.height.toFloat() }
                    .graphicsLayer { translationY = if (dragging == p.name) drift else 0f; shadowElevation = if (dragging == p.name) 8f else 0f }
                    .fillMaxWidth().heightIn(min = 64.dp).clip(groupShape(i, profiles.size)).background(Ink.surface)
                    .semantics { customActions = listOfNotNull(up.takeIf { i > 0 }, down.takeIf { i < profiles.lastIndex }) }
                    .padding(end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The handle: dragging past half a row moves the profile one place, and the drag carries on from there.
                Box(
                    Modifier.size(44.dp, 48.dp).pointerInput(p.name) {
                        detectDragGestures(
                            onDragStart = { dragging = p.name; drift = 0f },
                            onDragEnd = { dragging = null; drift = 0f }, onDragCancel = { dragging = null; drift = 0f },
                        ) { change, d ->
                            change.consume()
                            drift += d.y
                            val now = ui.setup.value.profiles.indexOfFirst { it.name == p.name }
                            if (drift > rowPx / 2 && now < ui.setup.value.profiles.lastIndex) { ui.moveProfile(p.name, 1); drift -= rowPx }
                            else if (drift < -rowPx / 2 && now > 0) { ui.moveProfile(p.name, -1); drift += rowPx }
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.drag, null, Modifier.size(20.dp), tint = Ink.dim) }
                RouteMark(route?.code, if (route != null) Ink.route(route.hue) else Ink.direct, direct = route == null)
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(p.name, style = Type.title, color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (p.name == setup.profile) Row(
                            Modifier.height(24.dp).clip(RoundedCornerShape(12.dp)).background(Ink.accentContainer).padding(start = 6.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) { Icon(Icons.check, null, Modifier.size(14.dp), tint = Ink.accent); Text("In use", style = Type.caption, color = Ink.accent) }
                    }
                    Text(summary(setup, p), style = Type.caption, color = Ink.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconTap(Icons.edit, "Edit ${p.name}", { onEdit(p.name) }, tint = Ink.dim)
            }
        }
        item(key = "note") { Caption("The first three are also Quick Settings tiles and launcher shortcuts. Removing a route moves its apps to each profile's default route.") }
    }
}
