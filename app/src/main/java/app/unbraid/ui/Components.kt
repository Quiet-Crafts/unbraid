package app.unbraid.ui

import app.unbraid.WORDMARK
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.contentDescription
import app.unbraid.APP
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight

// ---------------------------------------------------------------------------------------------
// Screen skeleton. Every screen uses this, so header, title baseline and search sit at the same
// place everywhere: 24dp top inset, 16dp overline, 6dp, 44dp headline, 6dp, 20dp sub, 12dp.
// ---------------------------------------------------------------------------------------------

val HeaderHeight = 128.dp
val Gutter = 16.dp

@Composable
fun Screen(
    overline: String,
    title: String,
    sub: String,
    overlineColor: Color = Ink.dim,
    overlineContent: (@Composable () -> Unit)? = null,
    titleColor: Color = Ink.text,
    subColor: Color = Ink.dim,
    action: (@Composable () -> Unit)? = null,
    /** Back or Close: Material's navigation icon, top-leading above the title. */
    navigation: (@Composable () -> Unit)? = null,
    search: (@Composable () -> Unit)? = null,
    belowSearch: (@Composable () -> Unit)? = null,
    bottom: (@Composable () -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
    /** The title block's own tap, when its text says "tap to …" (Routes): the same action as [action]. */
    onHeader: (() -> Unit)? = null,
    headerLabel: String? = null,
    content: LazyListScope.() -> Unit,
) = Column(Modifier.fillMaxSize().background(Ink.ground).windowInsetsPadding(WindowInsets.statusBars)) {
    navigation?.let { Box(Modifier.padding(start = 4.dp, top = 4.dp).height(56.dp), contentAlignment = Alignment.CenterStart) { it() } }
    // Grows with large text instead of clipping it.
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (navigation != null) HeaderHeight - 40.dp else HeaderHeight)
            .padding(start = 24.dp, end = Gutter, top = if (navigation != null) 0.dp else 24.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f).then(onHeader?.let { Modifier.tap(it, headerLabel, Role.Button) } ?: Modifier), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (overlineContent != null) overlineContent()
            else Text(wordmarked(overline.uppercase().replace(WORDMARK.uppercase(), WORDMARK)), style = Type.overline, color = overlineColor, maxLines = 1)
            Text(
                buildAnnotatedString {
                    // Titles are set in capitals; the wordmark stays lowercase, its slash in the accent.
                    (if (title == WORDMARK) title else title.uppercase()).split('/').forEachIndexed { i, part -> if (i > 0) withStyle(SpanStyle(color = Ink.accent)) { append('/') }; append(part) }
                },
                style = Type.headline, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (sub.isNotEmpty()) Text(sub, style = Type.sub, color = subColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        action?.invoke()
    }
    search?.invoke()
    belowSearch?.invoke()
    LazyColumn(Modifier.weight(1f), state = state, contentPadding = PaddingValues(bottom = 24.dp), content = content)
    bottom?.invoke()
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) = Row(
    Modifier.padding(horizontal = Gutter).fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(Ink.surfaceHigh).padding(horizontal = 20.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    Icon(Icons.search, null, tint = Ink.dim, modifier = Modifier.size(24.dp))
    Box(Modifier.weight(1f)) {
        if (value.isEmpty()) Text(placeholder, style = Type.body.copy(color = Ink.dim)) // faint fails 4.5:1 on this surface
        BasicTextField(
            value, onChange, singleLine = true, textStyle = Type.body.copy(color = Ink.text), cursorBrush = SolidColor(Ink.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), modifier = Modifier.fillMaxWidth(),
        )
    }
    if (value.isNotEmpty()) IconTap(Icons.close, "Clear search", { onChange("") }, size = 40.dp)
}

// ---------------------------------------------------------------------------------------------

enum class Tab(val label: String, val icon: ImageVector) { Routes("Routes", Icons.routes), Apps("Apps", Icons.apps), Stats("Stats", Icons.stats), Settings("Settings", Icons.sliders) }

/** A tap on the tab already shown: its screen goes back to its main page, or a main page scrolls to the top. */
val reselected = MutableSharedFlow<Tab>(extraBufferCapacity = 1)

/** Runs [action] each time [tab] is tapped again while it is shown. */
@Composable
fun OnReselect(tab: Tab, action: suspend () -> Unit) {
    val act by rememberUpdatedState(action)
    LaunchedEffect(tab) { reselected.collect { if (it == tab) act() } }
}

@Composable
fun NavBar(tab: Tab, onTab: (Tab) -> Unit) = ShortNavigationBar(containerColor = Ink.bar, contentColor = Ink.dim) {
    Tab.entries.forEach { t ->
        ShortNavigationBarItem(
            selected = t == tab, onClick = { onTab(t) },
            icon = { Icon(t.icon, null) }, label = { Text(t.label, style = Type.caption.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp)) },
            colors = ShortNavigationBarItemDefaults.colors(
                selectedIconColor = Ink.accent, selectedTextColorTopIconPosition = Ink.text, selectedIndicatorColor = Ink.accentContainer,
                unselectedIconColor = Ink.dim, unselectedTextColor = Ink.dim,
            ),
        )
    }
}

/** Route code chip: "NL", "US", "··". Filled for an explicit choice, outlined when inherited. */
@Composable
fun Code(code: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = true) = Text(
    code, style = Type.code, color = if (filled) Ink.ground else Ink.faint, maxLines = 1,
    modifier = modifier.clip(RoundedCornerShape(8.dp))
        .then(if (filled) Modifier.background(color) else Modifier.border(1.dp, Ink.line, RoundedCornerShape(8.dp)))
        .padding(horizontal = 7.dp, vertical = 5.dp),
)

/** A route chip: one slash per VPN hop, then the exit's code ("/NL"), in one size for every place; Direct keeps its small bypass glyph. */
@Composable
fun RouteMark(code: String?, color: Color, modifier: Modifier = Modifier, filled: Boolean = true, direct: Boolean = false, hops: Int = 1) {
    val shape = RoundedCornerShape(8.dp)
    val ink = if (filled) Ink.ground else Ink.faint
    Row(
        modifier.size(if (direct) 30.dp else 36.dp, 26.dp).clip(shape)
            .then(if (filled) Modifier.background(color) else Modifier.border(1.dp, Ink.line, shape)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        // Decorative marks keep their size while the exit's name follows the text scale.
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
            if (direct) Icon(Icons.direct, null, Modifier.size(14.dp), tint = ink)
            else {
                // Slash in the logo's face at the code's size, as in the wordmark; both faces share their metrics, so baselines meet.
                if (hops > 0) Text("/".repeat(hops), style = Type.headline.copy(fontSize = Type.code.fontSize, lineHeight = Type.code.lineHeight), color = ink, maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics {})
                if (code == "··") Icon(Icons.bolt, null, Modifier.size(16.dp), tint = ink)
                else Text(code.orEmpty(), style = Type.code, color = ink, maxLines = 1)
            }
        }
    }
}

/** M3 Expressive segmented list: big outer corners, small inner ones, 2dp gaps. */
/** The logo, large: "un/braid" in the display face, lowercase, its slash in the accent. On the Settings hub, top-trailing. */
@Composable
fun Wordmark(size: Int = 34) = Text(wordmarked(WORDMARK), style = Type.headline.copy(fontSize = size.sp, lineHeight = size.sp), color = Ink.text, maxLines = 1,
    modifier = Modifier.semantics { contentDescription = APP })

/** [text] with the logo's slash in the accent wherever the wordmark appears ("un/braid 2.0" in Settings). */
fun wordmarked(text: String) = androidx.compose.ui.text.buildAnnotatedString {
    text.split(WORDMARK).forEachIndexed { i, part ->
        if (i > 0) { append("un"); withStyle(SpanStyle(color = Ink.accent)) { append("/") }; append("braid") }
        append(part)
    }
}

fun groupShape(i: Int, n: Int): Shape = when {
    n == 1 -> RoundedCornerShape(22.dp)
    i == 0 -> RoundedCornerShape(22.dp, 22.dp, 6.dp, 6.dp)
    i == n - 1 -> RoundedCornerShape(6.dp, 6.dp, 22.dp, 22.dp)
    else -> RoundedCornerShape(6.dp)
}

@Composable
fun SectionLabel(text: String, tall: Boolean = false, trailing: (@Composable RowScope.() -> Unit)? = null) = Row(
    // [tall]: every label on a page in one 48 dp row (room for its button), 4 dp from what it labels, so sections keep one rhythm.
    Modifier.fillMaxWidth().padding(start = 24.dp, end = if (tall) Gutter - 4.dp else Gutter, top = if (tall) 16.dp else 20.dp, bottom = if (tall) 4.dp else 8.dp).heightIn(min = if (tall) 48.dp else 24.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(text.uppercase(), style = Type.overline, color = Ink.dim, modifier = Modifier.weight(1f))
    trailing?.invoke(this)
}

/** One row of a segmented group. */
@Composable
fun GroupRow(
    i: Int, n: Int, title: String, sub: String? = null,
    titleColor: Color = Ink.text,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    /** Set for a row that is a switch: the whole row toggles and is announced as one switch (its [Toggle] stays passive). */
    checked: Boolean? = null,
    /** Set for a row that is one of a choice: announced as a radio button. */
    selected: Boolean? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // A tappable row presses like the cards: the shared squish, ripple kept.
    val source = remember { MutableInteractionSource() }
    val press = squish(source)
    Row(
        Modifier.padding(horizontal = Gutter, vertical = 1.dp).then(modifier).graphicsLayer { scaleX = press.value; scaleY = press.value }
            .fillMaxWidth().heightIn(min = 64.dp).clip(groupShape(i, n)).background(Ink.surface)
            .then(when {
                checked != null -> Modifier.toggleable(checked, source, ripple(), enabled = onClick != null, role = Role.Switch) { onClick?.invoke() }
                selected != null -> Modifier.selectable(selected, source, ripple(), enabled = onClick != null, role = Role.RadioButton) { onClick?.invoke() }
                onClick != null -> Modifier.clickable(source, ripple(), onClick = onClick)
                else -> Modifier
            }).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(wordmarked(title), style = Type.body, color = titleColor)
            sub?.let { Text(it, style = Type.caption, color = Ink.dim, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.invoke()
    }
}

@Composable
fun Toggle(on: Boolean, enabled: Boolean = true, onChange: ((Boolean) -> Unit)? = null) = Switch(
    checked = on, onCheckedChange = onChange, enabled = enabled,
    thumbContent = if (on) { { Icon(Icons.check, null, Modifier.size(16.dp), tint = Ink.accent) } } else null,
    colors = SwitchDefaults.colors(
        checkedThumbColor = Ink.onAccent, checkedTrackColor = Ink.accent, checkedBorderColor = Ink.accent,
        uncheckedThumbColor = Ink.faint, uncheckedTrackColor = Ink.surfaceTop, uncheckedBorderColor = Ink.faint,
    ),
)

@Composable
fun IconTap(icon: ImageVector, label: String, onClick: () -> Unit, size: Dp = 48.dp, tint: Color = Ink.text, background: Color = Color.Transparent) = Box(
    // A filled icon button is an M3E squircle; a bare one keeps the round ripple.
    Modifier.size(size).clip(if (background == Color.Transparent) CircleShape else RoundedCornerShape(16.dp)).background(background).tap(onClick, label, Role.Button),
    contentAlignment = Alignment.Center,
) { Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp)) }

/** App icon, loaded off the main thread and cached. Falls back to an initial on a tinted tile. */
@Composable
fun AppIcon(ui: Ui, pkg: String, label: String, size: Dp = 40.dp) {
    val bmp by produceState<android.graphics.Bitmap?>(null, pkg) { value = ui.icon(pkg) }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(Ink.surfaceTop), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
            ?: Text(label.take(1).uppercase(), style = Type.title, color = Ink.dim)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Primary(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) = Box(
    modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(if (enabled) Ink.accent else Ink.surfaceTop)
        .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
        .semantics { if (busy) stateDescription = "Working" },
    contentAlignment = Alignment.Center,
) {
    if (busy) LoadingIndicator(Modifier.size(40.dp), color = Ink.onAccent)
    else Text(text, style = Type.title, color = if (enabled) Ink.onAccent else Ink.faint)
}

@Composable
fun Secondary(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Ink.accent, enabled: Boolean = true) = Box(
    modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
    contentAlignment = Alignment.Center,
) { Text(text, style = Type.title.copy(fontWeight = FontWeight.Medium), color = color) }

fun Modifier.tap(onClick: () -> Unit, label: String? = null, role: Role? = null) = clickable(onClickLabel = label, role = role, onClick = onClick)

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h).width(h))

// ---------------------------------------------------------------------------------------------
// Icons: Material Symbols Rounded (weight 400, grade 0, 24 px; Apache 2.0), copied as vectors so no icon font ships.
// Outlined at rest; the *Filled / checkCircle variants mark a selected state.
// ---------------------------------------------------------------------------------------------

object Icons {
    private fun symbol(name: String, d: String) = ImageVector.Builder(name, 24.dp, 24.dp, 960f, 960f).apply {
        addGroup(translationY = 960f)
        addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.White))
        clearGroup()
    }.build()

    val routes = symbol("routes", "M440-120v-160q0-56-17-83t-45-53l57-57q12 11 23 23.5t22 26.5q14-19 28.5-33.5T538-485q38-35 69-81t33-161l-35 35q-11 11-27.5 11T549-692q-12-12-12-28.5t12-28.5l103-103q6-6 13-8.5t15-2.5q8 0 15 2.5t13 8.5l104 104q11 11 11.5 27.5T812-692q-11 11-28 11t-28-11l-36-35q-2 143-44 203.5T592-425q-32 29-52 56.5T520-280v160q0 17-11.5 28.5T480-80q-17 0-28.5-11.5T440-120ZM248-633q-4-20-5.5-44t-2.5-50l-36 36q-11 11-27.5 11T148-692q-11-11-11-28t11-28l104-104q6-6 13-8.5t15-2.5q8 0 15 2.5t13 8.5l104 104q12 12 11.5 28T411-692q-12 11-28 11t-28-11l-35-34q0 21 2 39.5t4 34.5l-78 19Zm86 176q-20-21-38.5-49T263-575l77-19q10 27 23 46t28 34l-57 57Z")
    val apps = symbol("apps", "M240-160q-33 0-56.5-23.5T160-240q0-33 23.5-56.5T240-320q33 0 56.5 23.5T320-240q0 33-23.5 56.5T240-160Zm240 0q-33 0-56.5-23.5T400-240q0-33 23.5-56.5T480-320q33 0 56.5 23.5T560-240q0 33-23.5 56.5T480-160Zm240 0q-33 0-56.5-23.5T640-240q0-33 23.5-56.5T720-320q33 0 56.5 23.5T800-240q0 33-23.5 56.5T720-160ZM240-400q-33 0-56.5-23.5T160-480q0-33 23.5-56.5T240-560q33 0 56.5 23.5T320-480q0 33-23.5 56.5T240-400Zm240 0q-33 0-56.5-23.5T400-480q0-33 23.5-56.5T480-560q33 0 56.5 23.5T560-480q0 33-23.5 56.5T480-400Zm240 0q-33 0-56.5-23.5T640-480q0-33 23.5-56.5T720-560q33 0 56.5 23.5T800-480q0 33-23.5 56.5T720-400ZM240-640q-33 0-56.5-23.5T160-720q0-33 23.5-56.5T240-800q33 0 56.5 23.5T320-720q0 33-23.5 56.5T240-640Zm240 0q-33 0-56.5-23.5T400-720q0-33 23.5-56.5T480-800q33 0 56.5 23.5T560-720q0 33-23.5 56.5T480-640Zm240 0q-33 0-56.5-23.5T640-720q0-33 23.5-56.5T720-800q33 0 56.5 23.5T800-720q0 33-23.5 56.5T720-640Z")
    val sliders = symbol("sliders", "M480-120q-17 0-28.5-11.5T440-160v-160q0-17 11.5-28.5T480-360q17 0 28.5 11.5T520-320v40h280q17 0 28.5 11.5T840-240q0 17-11.5 28.5T800-200H520v40q0 17-11.5 28.5T480-120Zm-320-80q-17 0-28.5-11.5T120-240q0-17 11.5-28.5T160-280h160q17 0 28.5 11.5T360-240q0 17-11.5 28.5T320-200H160Zm160-160q-17 0-28.5-11.5T280-400v-40H160q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520h120v-40q0-17 11.5-28.5T320-600q17 0 28.5 11.5T360-560v160q0 17-11.5 28.5T320-360Zm160-80q-17 0-28.5-11.5T440-480q0-17 11.5-28.5T480-520h320q17 0 28.5 11.5T840-480q0 17-11.5 28.5T800-440H480Zm160-160q-17 0-28.5-11.5T600-640v-160q0-17 11.5-28.5T640-840q17 0 28.5 11.5T680-800v40h120q17 0 28.5 11.5T840-720q0 17-11.5 28.5T800-680H680v40q0 17-11.5 28.5T640-600Zm-480-80q-17 0-28.5-11.5T120-720q0-17 11.5-28.5T160-760h320q17 0 28.5 11.5T520-720q0 17-11.5 28.5T480-680H160Z")
    val search = symbol("search", "M380-320q-109 0-184.5-75.5T120-580q0-109 75.5-184.5T380-840q109 0 184.5 75.5T640-580q0 44-14 83t-38 69l224 224q11 11 11 28t-11 28q-11 11-28 11t-28-11L532-372q-30 24-69 38t-83 14Zm0-80q75 0 127.5-52.5T560-580q0-75-52.5-127.5T380-760q-75 0-127.5 52.5T200-580q0 75 52.5 127.5T380-400Z")
    val close = symbol("close", "M480-424 284-228q-11 11-28 11t-28-11q-11-11-11-28t11-28l196-196-196-196q-11-11-11-28t11-28q11-11 28-11t28 11l196 196 196-196q11-11 28-11t28 11q11 11 11 28t-11 28L536-480l196 196q11 11 11 28t-11 28q-11 11-28 11t-28-11L480-424Z")
    val check = symbol("check", "m382-354 339-339q12-12 28-12t28 12q12 12 12 28.5T777-636L410-268q-12 12-28 12t-28-12L182-440q-12-12-11.5-28.5T183-497q12-12 28.5-12t28.5 12l142 143Z")
    val checkCircle = symbol("checkCircle", "m424-408-86-86q-11-11-28-11t-28 11q-11 11-11 28t11 28l114 114q12 12 28 12t28-12l226-226q11-11 11-28t-11-28q-11-11-28-11t-28 11L424-408Zm56 328q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Z")
    val power = symbol("power", "M440-480v-320q0-17 11.5-28.5T480-840q17 0 28.5 11.5T520-800v320q0 17-11.5 28.5T480-440q-17 0-28.5-11.5T440-480Zm40 360q-74 0-139.5-28.5T226-226q-49-49-77.5-114.5T120-480q0-69 25.5-132.5T217-726q11-12 28-12t29 12q11 11 10 27.5T272-669q-34 38-53 86.5T200-480q0 116 82 198t198 82q117 0 198.5-82T760-480q0-54-19-103.5T687-671q-11-12-11.5-28t10.5-27q12-12 29-12t28 12q47 50 72 113.5T840-480q0 74-28.5 139.5t-77 114.5q-48.5 49-114 77.5T480-120Z")
    val chevron = symbol("chevron", "M504-480 348-636q-11-11-11-28t11-28q11-11 28-11t28 11l184 184q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L404-268q-11 11-28 11t-28-11q-11-11-11-28t11-28l156-156Z")
    val expand = symbol("expand", "M480-362q-8 0-15-2.5t-13-8.5L268-557q-11-11-11-28t11-28q11-11 28-11t28 11l156 156 156-156q11-11 28-11t28 11q11 11 11 28t-11 28L508-373q-6 6-13 8.5t-15 2.5Z")
    val dropDown = symbol("dropDown", "M459-381 314-526q-3-3-4.5-6.5T308-540q0-8 5.5-14t14.5-6h304q9 0 14.5 6t5.5 14q0 2-6 14L501-381q-5 5-10 7t-11 2q-6 0-11-2t-10-7Z")
    val add = symbol("plus", "M440-440H240q-17 0-28.5-11.5T200-480q0-17 11.5-28.5T240-520h200v-200q0-17 11.5-28.5T480-760q17 0 28.5 11.5T520-720v200h200q17 0 28.5 11.5T760-480q0 17-11.5 28.5T720-440H520v200q0 17-11.5 28.5T480-200q-17 0-28.5-11.5T440-240v-200Z")
    val bolt = symbol("bolt", "M360-360H236q-24 0-35.5-21.5T203-423l299-430q10-14 26-19.5t33 .5q17 6 25 21t6 32l-32 259h155q26 0 36.5 23t-6.5 43L416-100q-11 13-27 17t-31-3q-15-7-23.5-21.5T328-139l32-221Z")
    val star = symbol("star", "m354-287 126-76 126 77-33-144 111-96-146-13-58-136-58 135-146 13 111 97-33 143Zm126 18L314-169q-11 7-23 6t-21-8q-9-7-14-17.5t-2-23.5l44-189-147-127q-10-9-12.5-20.5T140-571q4-11 12-18t22-9l194-17 75-178q5-12 15.5-18t21.5-6q11 0 21.5 6t15.5 18l75 178 194 17q14 2 22 9t12 18q4 11 1.5 22.5T809-528L662-401l44 189q3 13-2 23.5T690-171q-9 7-21 8t-23-6L480-269Zm0-201Z")
    val starFilled = symbol("starFilled", "M480-269 314-169q-11 7-23 6t-21-8q-9-7-14-17.5t-2-23.5l44-189-147-127q-10-9-12.5-20.5T140-571q4-11 12-18t22-9l194-17 75-178q5-12 15.5-18t21.5-6q11 0 21.5 6t15.5 18l75 178 194 17q14 2 22 9t12 18q4 11 1.5 22.5T809-528L662-401l44 189q3 13-2 23.5T690-171q-9 7-21 8t-23-6L480-269Z")
    val home = symbol("home", "M240-200h120v-200q0-17 11.5-28.5T400-440h160q17 0 28.5 11.5T600-400v200h120v-360L480-740 240-560v360Zm-80 0v-360q0-19 8.5-36t23.5-28l240-180q21-16 48-16t48 16l240 180q15 11 23.5 28t8.5 36v360q0 33-23.5 56.5T720-120H560q-17 0-28.5-11.5T520-160v-200h-80v200q0 17-11.5 28.5T400-120H240q-33 0-56.5-23.5T160-200Zm320-270Z")
    val back = symbol("back", "m313-440 196 196q12 12 11.5 28T508-188q-12 11-28 11.5T452-188L188-452q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l264-264q11-11 27.5-11t28.5 11q12 12 12 28.5T508-715L313-520h447q17 0 28.5 11.5T800-480q0 17-11.5 28.5T760-440H313Z")
    val eye = symbol("eye", "M480-320q75 0 127.5-52.5T660-500q0-75-52.5-127.5T480-680q-75 0-127.5 52.5T300-500q0 75 52.5 127.5T480-320Zm0-72q-45 0-76.5-31.5T372-500q0-45 31.5-76.5T480-608q45 0 76.5 31.5T588-500q0 45-31.5 76.5T480-392Zm0 192q-134 0-244.5-72T61-462q-5-9-7.5-18.5T51-500q0-10 2.5-19.5T61-538q64-118 174.5-190T480-800q134 0 244.5 72T899-538q5 9 7.5 18.5T909-500q0 10-2.5 19.5T899-462q-64 118-174.5 190T480-200Zm0-300Zm0 220q113 0 207.5-59.5T832-500q-50-101-144.5-160.5T480-720q-113 0-207.5 59.5T128-500q50 101 144.5 160.5T480-280Z")
    val eyeOff = symbol("eyeOff", "M607-627q29 29 42.5 66t9.5 76q0 15-11 25.5T622-449q-15 0-25.5-10.5T586-485q5-26-3-50t-25-41q-17-17-41-26t-51-4q-15 0-25.5-11T430-643q0-15 10.5-25.5T466-679q38-4 75 9.5t66 42.5Zm-127-93q-19 0-37 1.5t-36 5.5q-17 3-30.5-5T358-742q-5-16 3.5-31t24.5-18q23-5 46.5-7t47.5-2q137 0 250.5 72T904-534q4 8 6 16.5t2 17.5q0 9-1.5 17.5T905-466q-18 40-44.5 75T802-327q-12 11-28 9t-26-16q-10-14-8.5-30.5T753-392q24-23 44-50t35-58q-50-101-144.5-160.5T480-720Zm0 520q-134 0-245-72.5T60-463q-5-8-7.5-17.5T50-500q0-10 2-19t7-18q20-40 46.5-76.5T166-680l-83-84q-11-12-10.5-28.5T84-820q11-11 28-11t28 11l680 680q11 11 11.5 27.5T820-84q-11 11-28 11t-28-11L624-222q-35 11-71 16.5t-73 5.5ZM222-624q-29 26-53 57t-41 67q50 101 144.5 160.5T480-280q20 0 39-2.5t39-5.5l-36-38q-11 3-21 4.5t-21 1.5q-75 0-127.5-52.5T300-500q0-11 1.5-21t4.5-21l-84-82Zm319 93Zm-151 75Z")
    val tunnel = symbol("tunnel", "M480-80q-82 0-155-31.5t-127.5-86Q143-252 111.5-325T80-480q0-83 31.5-155.5t86-127Q252-817 325-848.5T480-880q23 0 44.5 2t42.5 7q15 3 24 15t9 28v68q0 33-23.5 56.5T520-680h-80v80q0 17-11.5 28.5T400-560h-80v80h240q17 0 28.5 11.5T600-440v120h40q27 0 47.5 16t28.5 40q39-44 61.5-98.5T800-480q0-16 10.5-28t28.5-12q17 0 29 11t12 28q0 83-31.5 156t-86 127.5q-54.5 54.5-127 86T480-80Zm-40-82v-78q-33 0-56.5-23.5T360-320v-40L168-552q-3 18-5.5 36t-2.5 36q0 124 80.5 213.5T440-162Zm280-438q-17 0-28.5-11.5T680-640v-120q0-17 11.5-28.5T720-800v-40q0-33 23.5-56.5T800-920q33 0 56.5 23.5T880-840v40q17 0 28.5 11.5T920-760v120q0 17-11.5 28.5T880-600H720Zm40-200h80v-40q0-17-11.5-28.5T800-880q-17 0-28.5 11.5T760-840v40Z")
    val direct = symbol("direct", "M480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm-40-82v-78q-33 0-56.5-23.5T360-320v-40L168-552q-3 18-5.5 36t-2.5 36q0 121 79.5 212T440-162Zm276-102q20-22 36-47.5t26.5-53q10.5-27.5 16-56.5t5.5-59q0-98-54.5-179T600-776v16q0 33-23.5 56.5T520-680h-80v80q0 17-11.5 28.5T400-560h-80v80h240q17 0 28.5 11.5T600-440v120h40q26 0 47 15.5t29 40.5Z")
    val more = symbol("more", "M240-400q-33 0-56.5-23.5T160-480q0-33 23.5-56.5T240-560q33 0 56.5 23.5T320-480q0 33-23.5 56.5T240-400Zm240 0q-33 0-56.5-23.5T400-480q0-33 23.5-56.5T480-560q33 0 56.5 23.5T560-480q0 33-23.5 56.5T480-400Zm240 0q-33 0-56.5-23.5T640-480q0-33 23.5-56.5T720-560q33 0 56.5 23.5T800-480q0 33-23.5 56.5T720-400Z")
    val external = symbol("external", "M200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h240q17 0 28.5 11.5T480-800q0 17-11.5 28.5T440-760H200v560h560v-240q0-17 11.5-28.5T800-480q17 0 28.5 11.5T840-440v240q0 33-23.5 56.5T760-120H200Zm560-584L416-360q-11 11-28 11t-28-11q-11-11-11-28t11-28l344-344H600q-17 0-28.5-11.5T560-800q0-17 11.5-28.5T600-840h200q17 0 28.5 11.5T840-800v200q0 17-11.5 28.5T800-560q-17 0-28.5-11.5T760-600v-104Z")
    val pause = symbol("pause", "M600-200q-33 0-56.5-23.5T520-280v-400q0-33 23.5-56.5T600-760h80q33 0 56.5 23.5T760-680v400q0 33-23.5 56.5T680-200h-80Zm-320 0q-33 0-56.5-23.5T200-280v-400q0-33 23.5-56.5T280-760h80q33 0 56.5 23.5T440-680v400q0 33-23.5 56.5T360-200h-80Zm320-80h80v-400h-80v400Zm-320 0h80v-400h-80v400Zm0-400v400-400Zm320 0v400-400Z")
    val stats = symbol("stats", "M160-120q-17 0-28.5-11.5T120-160v-40q0-17 11.5-28.5T160-240q17 0 28.5 11.5T200-200v40q0 17-11.5 28.5T160-120Zm160 0q-17 0-28.5-11.5T280-160v-220q0-17 11.5-28.5T320-420q17 0 28.5 11.5T360-380v220q0 17-11.5 28.5T320-120Zm160 0q-17 0-28.5-11.5T440-160v-140q0-17 11.5-28.5T480-340q17 0 28.5 11.5T520-300v140q0 17-11.5 28.5T480-120Zm160 0q-17 0-28.5-11.5T600-160v-200q0-17 11.5-28.5T640-400q17 0 28.5 11.5T680-360v200q0 17-11.5 28.5T640-120Zm160 0q-17 0-28.5-11.5T760-160v-360q0-17 11.5-28.5T800-560q17 0 28.5 11.5T840-520v360q0 17-11.5 28.5T800-120ZM560-481q-16 0-30.5-6T503-504L400-607 188-395q-12 12-28.5 11.5T131-396q-11-12-10.5-28.5T132-452l211-211q12-12 26.5-17.5T400-686q16 0 31 5.5t26 17.5l103 103 212-212q12-12 28.5-11.5T829-771q11 12 10.5 28.5T828-715L617-504q-11 11-26 17t-31 6Z")
    val usage = symbol("usage", "M480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-130 75-234t199-145q29-10 53.5 7t24.5 46q0 20-11.5 36.5T391-747q-86 27-138.5 100.5T200-480q0 117 81.5 198.5T480-200q52 0 100.5-18t86.5-52q15-14 36.5-14t36.5 14q23 21 24 47.5T742-176q-54 47-120.5 71.5T480-80Zm280-400q0-92-53-165.5T568-747q-18-6-29.5-22.5T527-806q0-29 24.5-46t53.5-7q125 42 200 146t75 233q0 18-1.5 36.5T873-403q-5 29-29.5 41.5T790-360q-19-7-29.5-25.5T754-424q3-17 4.5-30t1.5-26Z")
    val speed = symbol("speed", "M418-340q24 24 62 23.5t56-27.5l169-253q9-14-2.5-25.5T677-625L424-456q-27 18-28.5 55t22.5 61Zm62-460q36 0 71 6t68 19q16 6 34 22.5t10 31.5q-8 15-36 20t-45-1q-25-9-50.5-13.5T480-720q-133 0-226.5 93.5T160-400q0 42 11.5 83t32.5 77h552q23-38 33.5-79t10.5-85q0-26-4.5-51T782-504q-6-17-2-33t18-27q13-10 28.5-6t21.5 18q15 35 23 71.5t9 74.5q1 57-13 109t-41 99q-11 18-30 28t-40 10H204q-21 0-40-10t-30-28q-26-45-40-95.5T80-400q0-83 31.5-155.5t86-127Q252-737 325-768.5T480-800Zm7 313Z")
    val flows = symbol("flows", "M600-200v-40h-80q-33 0-56.5-23.5T440-320v-320h-80v40q0 33-23.5 56.5T280-520H160q-33 0-56.5-23.5T80-600v-160q0-33 23.5-56.5T160-840h120q33 0 56.5 23.5T360-760v40h240v-40q0-33 23.5-56.5T680-840h120q33 0 56.5 23.5T880-760v160q0 33-23.5 56.5T800-520H680q-33 0-56.5-23.5T600-600v-40h-80v320h80v-40q0-33 23.5-56.5T680-440h120q33 0 56.5 23.5T880-360v160q0 33-23.5 56.5T800-120H680q-33 0-56.5-23.5T600-200ZM160-760v160-160Zm520 400v160-160Zm0-400v160-160Zm0 160h120v-160H680v160Zm0 400h120v-160H680v160ZM160-600h120v-160H160v160Z")
    val calendar = symbol("calendar", "M200-80q-33 0-56.5-23.5T120-160v-560q0-33 23.5-56.5T200-800h40v-40q0-17 11.5-28.5T280-880q17 0 28.5 11.5T320-840v40h320v-40q0-17 11.5-28.5T680-880q17 0 28.5 11.5T720-840v40h40q33 0 56.5 23.5T840-720v560q0 33-23.5 56.5T760-80H200Zm0-80h560v-400H200v400Zm0-480h560v-80H200v80Zm0 0v-80 80Zm280 240q-17 0-28.5-11.5T440-440q0-17 11.5-28.5T480-480q17 0 28.5 11.5T520-440q0 17-11.5 28.5T480-400Zm-160 0q-17 0-28.5-11.5T280-440q0-17 11.5-28.5T320-480q17 0 28.5 11.5T360-440q0 17-11.5 28.5T320-400Zm320 0q-17 0-28.5-11.5T600-440q0-17 11.5-28.5T640-480q17 0 28.5 11.5T680-440q0 17-11.5 28.5T640-400ZM480-240q-17 0-28.5-11.5T440-280q0-17 11.5-28.5T480-320q17 0 28.5 11.5T520-280q0 17-11.5 28.5T480-240Zm-160 0q-17 0-28.5-11.5T280-280q0-17 11.5-28.5T320-320q17 0 28.5 11.5T360-280q0 17-11.5 28.5T320-240Zm320 0q-17 0-28.5-11.5T600-280q0-17 11.5-28.5T640-320q17 0 28.5 11.5T680-280q0 17-11.5 28.5T640-240Z")
    val history = symbol("history", "M480-120q-126 0-223-76.5T131-392q-4-15 6-27.5t27-14.5q16-2 29 6t18 24q24 90 99 147t170 57q117 0 198.5-81.5T760-480q0-117-81.5-198.5T480-760q-69 0-129 32t-101 88h70q17 0 28.5 11.5T360-600q0 17-11.5 28.5T320-560H160q-17 0-28.5-11.5T120-600v-160q0-17 11.5-28.5T160-800q17 0 28.5 11.5T200-760v54q51-64 124.5-99T480-840q75 0 140.5 28.5t114 77q48.5 48.5 77 114T840-480q0 75-28.5 140.5t-77 114q-48.5 48.5-114 77T480-120Zm40-376 100 100q11 11 11 28t-11 28q-11 11-28 11t-28-11L452-452q-6-6-9-13.5t-3-15.5v-159q0-17 11.5-28.5T480-680q17 0 28.5 11.5T520-640v144Z")
    val down = symbol("down", "M480-337q-8 0-15-2.5t-13-8.5L308-492q-12-12-11.5-28t11.5-28q12-12 28.5-12.5T365-549l75 75v-286q0-17 11.5-28.5T480-800q17 0 28.5 11.5T520-760v286l75-75q12-12 28.5-11.5T652-548q11 12 11.5 28T652-492L508-348q-6 6-13 8.5t-15 2.5ZM240-160q-33 0-56.5-23.5T160-240v-80q0-17 11.5-28.5T200-360q17 0 28.5 11.5T240-320v80h480v-80q0-17 11.5-28.5T760-360q17 0 28.5 11.5T800-320v80q0 33-23.5 56.5T720-160H240Z")
    val up = symbol("up", "M240-160q-33 0-56.5-23.5T160-240v-80q0-17 11.5-28.5T200-360q17 0 28.5 11.5T240-320v80h480v-80q0-17 11.5-28.5T760-360q17 0 28.5 11.5T800-320v80q0 33-23.5 56.5T720-160H240Zm200-486-75 75q-12 12-28.5 11.5T308-572q-11-12-11.5-28t11.5-28l144-144q6-6 13-8.5t15-2.5q8 0 15 2.5t13 8.5l144 144q12 12 11.5 28T652-572q-12 12-28.5 12.5T595-571l-75-75v286q0 17-11.5 28.5T480-320q-17 0-28.5-11.5T440-360v-286Z")
    val clock = symbol("clock", "M520-496v-144q0-17-11.5-28.5T480-680q-17 0-28.5 11.5T440-640v159q0 8 3 15.5t9 13.5l132 132q11 11 28 11t28-11q11-11 11-28t-11-28L520-496ZM480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-400Zm0 320q133 0 226.5-93.5T800-480q0-133-93.5-226.5T480-800q-133 0-226.5 93.5T160-480q0 133 93.5 226.5T480-160Z")
    val deleteForever = symbol("deleteForever", "M280-120q-33 0-56.5-23.5T200-200v-520q-17 0-28.5-11.5T160-760q0-17 11.5-28.5T200-800h160q0-17 11.5-28.5T400-840h160q17 0 28.5 11.5T600-800h160q17 0 28.5 11.5T800-760q0 17-11.5 28.5T760-720v520q0 33-23.5 56.5T680-120H280Zm400-600H280v520h400v-520Zm-400 0v520-520Zm200 316 76 76q11 11 28 11t28-11q11-11 11-28t-11-28l-76-76 76-76q11-11 11-28t-11-28q-11-11-28-11t-28 11l-76 76-76-76q-11-11-28-11t-28 11q-11 11-11 28t11 28l76 76-76 76q-11 11-11 28t11 28q11 11 28 11t28-11l76-76Z")
    val phone = symbol("phone", "M420-160h120q8 0 14-6t6-14q0-8-6-14t-14-6H420q-8 0-14 6t-6 14q0 8 6 14t14 6ZM280-40q-33 0-56.5-23.5T200-120v-720q0-33 23.5-56.5T280-920h400q33 0 56.5 23.5T760-840v720q0 33-23.5 56.5T680-40H280Zm0-200v120h400v-120H280Zm0-80h400v-400H280v400Zm0-480h400v-40H280v40Zm0 560v120-120Zm0-560v-40 40Z")
    val block = symbol("block", "M480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q54 0 104-17.5t92-50.5L228-676q-33 42-50.5 92T160-480q0 134 93 227t227 93Zm252-124q33-42 50.5-92T800-480q0-134-93-227t-227-93q-54 0-104 17.5T284-732l448 448Z")
    val vpnKey = symbol("vpn_key", "M280-240q-100 0-170-70T40-480q0-100 70-170t170-70q66 0 121 33t87 87h352q33 0 56.5 23.5T920-520v80q0 33-23.5 56.5T840-360v40q0 33-23.5 56.5T760-240h-80q-33 0-56.5-23.5T600-320v-40H488q-32 54-87 87t-121 33Zm0-80q66 0 106-40.5t48-79.5h246v120h80v-120h80v-80H434q-8-39-48-79.5T280-640q-66 0-113 47t-47 113q0 66 47 113t113 47Zm0-80q33 0 56.5-23.5T360-480q0-33-23.5-56.5T280-560q-33 0-56.5 23.5T200-480q0 33 23.5 56.5T280-400Zm0-80Z")
    val hub = symbol("hub", "M155-75q-35-35-35-85t35-85q35-35 85-35 14 0 26 3t23 8l57-71q-28-31-39-70t-5-78l-81-27q-17 25-43 40t-58 15q-50 0-85-35T0-580q0-50 35-85t85-35q50 0 85 35t35 85v8l81 28q20-36 53.5-61t75.5-32v-87q-39-11-64.5-42.5T360-840q0-50 35-85t85-35q50 0 85 35t35 85q0 42-26 73.5T510-724v87q42 7 75.5 32t53.5 61l81-28v-8q0-50 35-85t85-35q50 0 85 35t35 85q0 50-35 85t-85 35q-32 0-58.5-15T739-515l-81 27q6 39-5 77.5T614-340l57 70q11-5 23-7.5t26-2.5q50 0 85 35t35 85q0 50-35 85t-85 35q-50 0-85-35t-35-85q0-20 6.5-38.5T624-232l-57-71q-41 23-87.5 23T392-303l-56 71q11 15 17.5 33.5T360-160q0 50-35 85t-85 35q-50 0-85-35Zm-35-465q17 0 28.5-11.5T160-580q0-17-11.5-28.5T120-620q-17 0-28.5 11.5T80-580q0 17 11.5 28.5T120-540Zm148.5 408.5Q280-143 280-160t-11.5-28.5Q257-200 240-200t-28.5 11.5Q200-177 200-160t11.5 28.5Q223-120 240-120t28.5-11.5Zm240-680Q520-823 520-840t-11.5-28.5Q497-880 480-880t-28.5 11.5Q440-857 440-840t11.5 28.5Q463-800 480-800t28.5-11.5ZM480-360q42 0 71-29t29-71q0-42-29-71t-71-29q-42 0-71 29t-29 71q0 42 29 71t71 29Zm268.5 228.5Q760-143 760-160t-11.5-28.5Q737-200 720-200t-28.5 11.5Q680-177 680-160t11.5 28.5Q703-120 720-120t28.5-11.5Zm120-420Q880-563 880-580t-11.5-28.5Q857-620 840-620t-28.5 11.5Q800-597 800-580t11.5 28.5Q823-540 840-540t28.5-11.5ZM480-840ZM120-580Zm360 120Zm360-120ZM240-160Zm480 0Z")
    val shield = symbol("shield", "M467-85q-6-1-12-3-135-45-215-166.5T160-516v-189q0-25 14.5-45t37.5-29l240-90q14-5 28-5t28 5l240 90q23 9 37.5 29t14.5 45v189q0 140-80 261.5T505-88q-6 2-12 3t-13 1q-7 0-13-1Zm13-79q104-33 172-132t68-220v-189l-240-90-240 90v189q0 121 68 220t172 132Zm0-316Z")
    val dns = symbol("dns", "M300-720q-25 0-42.5 17.5T240-660q0 25 17.5 42.5T300-600q25 0 42.5-17.5T360-660q0-25-17.5-42.5T300-720Zm0 400q-25 0-42.5 17.5T240-260q0 25 17.5 42.5T300-200q25 0 42.5-17.5T360-260q0-25-17.5-42.5T300-320ZM160-840h640q17 0 28.5 11.5T840-800v280q0 17-11.5 28.5T800-480H160q-17 0-28.5-11.5T120-520v-280q0-17 11.5-28.5T160-840Zm40 80v200h560v-200H200Zm-40 320h640q17 0 28.5 11.5T840-400v280q0 17-11.5 28.5T800-80H160q-17 0-28.5-11.5T120-120v-280q0-17 11.5-28.5T160-440Zm40 80v200h560v-200H200Zm0-400v200-200Zm0 400v200-200Z")
    val info = symbol("info", "M508.5-291.5Q520-303 520-320v-160q0-17-11.5-28.5T480-520q-17 0-28.5 11.5T440-480v160q0 17 11.5 28.5T480-280q17 0 28.5-11.5Zm0-320Q520-623 520-640t-11.5-28.5Q497-680 480-680t-28.5 11.5Q440-657 440-640t11.5 28.5Q463-600 480-600t28.5-11.5ZM480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z")
    val lan = symbol("lan", "M120-160v-120q0-33 23.5-56.5T200-360h40v-80q0-33 23.5-56.5T320-520h120v-80h-40q-33 0-56.5-23.5T320-680v-120q0-33 23.5-56.5T400-880h160q33 0 56.5 23.5T640-800v120q0 33-23.5 56.5T560-600h-40v80h120q33 0 56.5 23.5T720-440v80h40q33 0 56.5 23.5T840-280v120q0 33-23.5 56.5T760-80H600q-33 0-56.5-23.5T520-160v-120q0-33 23.5-56.5T600-360h40v-80H320v80h40q33 0 56.5 23.5T440-280v120q0 33-23.5 56.5T360-80H200q-33 0-56.5-23.5T120-160Zm280-520h160v-120H400v120ZM200-160h160v-120H200v120Zm400 0h160v-120H600v120ZM480-680ZM360-280Zm240 0Z")
    val router = symbol("router", "M200-120q-33 0-56.5-23.5T120-200v-160q0-33 23.5-56.5T200-440h400v-120q0-17 11.5-28.5T640-600q17 0 28.5 11.5T680-560v120h80q33 0 56.5 23.5T840-360v160q0 33-23.5 56.5T760-120H200Zm0-80h560v-160H200v160Zm108.5-51.5Q320-263 320-280t-11.5-28.5Q297-320 280-320t-28.5 11.5Q240-297 240-280t11.5 28.5Q263-240 280-240t28.5-11.5Zm140 0Q460-263 460-280t-11.5-28.5Q437-320 420-320t-28.5 11.5Q380-297 380-280t11.5 28.5Q403-240 420-240t28.5-11.5Zm140 0Q600-263 600-280t-11.5-28.5Q577-320 560-320t-28.5 11.5Q520-297 520-280t11.5 28.5Q543-240 560-240t28.5-11.5ZM640-660q-11 0-20 2t-18 6q-16 7-32.5 6T541-659q-12-12-11.5-29t14.5-25q21-13 45.5-20t50.5-7q27 0 51 7t45 20q14 8 14.5 25T739-659q-12 12-29 13t-33-6q-8-4-17.5-6t-19.5-2Zm-74.5-128.5Q530-777 500-755q-14 10-30.5 9T442-758q-12-12-12-28t13-26q41-32 91-50t106-18q56 0 106 18t91 50q13 10 13 26t-12 28q-11 11-27.5 12t-30.5-9q-30-22-65.5-33.5T640-800q-39 0-74.5 11.5ZM200-200v-160 160Z")
    val file = symbol("description", "M360-240h240q17 0 28.5-11.5T640-280q0-17-11.5-28.5T600-320H360q-17 0-28.5 11.5T320-280q0 17 11.5 28.5T360-240Zm0-160h240q17 0 28.5-11.5T640-440q0-17-11.5-28.5T600-480H360q-17 0-28.5 11.5T320-440q0 17 11.5 28.5T360-400ZM240-80q-33 0-56.5-23.5T160-160v-640q0-33 23.5-56.5T240-880h287q16 0 30.5 6t25.5 17l194 194q11 11 17 25.5t6 30.5v447q0 33-23.5 56.5T720-80H240Zm280-560v-160H240v640h480v-440H560q-17 0-28.5-11.5T520-640ZM240-800v200-200 640-640Z")
    val link = symbol("link", "M280-280q-83 0-141.5-58.5T80-480q0-83 58.5-141.5T280-680h120q17 0 28.5 11.5T440-640q0 17-11.5 28.5T400-600H280q-50 0-85 35t-35 85q0 50 35 85t85 35h120q17 0 28.5 11.5T440-320q0 17-11.5 28.5T400-280H280Zm80-160q-17 0-28.5-11.5T320-480q0-17 11.5-28.5T360-520h240q17 0 28.5 11.5T640-480q0 17-11.5 28.5T600-440H360Zm200 160q-17 0-28.5-11.5T520-320q0-17 11.5-28.5T560-360h120q50 0 85-35t35-85q0-50-35-85t-85-35H560q-17 0-28.5-11.5T520-640q0-17 11.5-28.5T560-680h120q83 0 141.5 58.5T880-480q0 83-58.5 141.5T680-280H560Z")
    val layers = symbol("layers", "M161-366q-16-12-15.5-31.5T162-429q11-8 24-8t24 8l270 209 270-209q11-8 24-8t24 8q16 12 16.5 31.5T799-366L529-156q-22 17-49 17t-49-17L161-366Zm270 8L201-537q-31-24-31-63t31-63l230-179q22-17 49-17t49 17l230 179q31 24 31 63t-31 63L529-358q-22 17-49 17t-49-17Zm49-64 230-178-230-178-230 178 230 178Zm0-178Z")
    val drag = symbol("drag", "M360-160q-33 0-56.5-23.5T280-240q0-33 23.5-56.5T360-320q33 0 56.5 23.5T440-240q0 33-23.5 56.5T360-160Zm240 0q-33 0-56.5-23.5T520-240q0-33 23.5-56.5T600-320q33 0 56.5 23.5T680-240q0 33-23.5 56.5T600-160ZM360-400q-33 0-56.5-23.5T280-480q0-33 23.5-56.5T360-560q33 0 56.5 23.5T440-480q0 33-23.5 56.5T360-400Zm240 0q-33 0-56.5-23.5T520-480q0-33 23.5-56.5T600-560q33 0 56.5 23.5T680-480q0 33-23.5 56.5T600-400ZM360-640q-33 0-56.5-23.5T280-720q0-33 23.5-56.5T360-800q33 0 56.5 23.5T440-720q0 33-23.5 56.5T360-640Zm240 0q-33 0-56.5-23.5T520-720q0-33 23.5-56.5T600-800q33 0 56.5 23.5T680-720q0 33-23.5 56.5T600-640Z")
    val edit = symbol("edit", "M200-200h57l391-391-57-57-391 391v57Zm-40 80q-17 0-28.5-11.5T120-160v-97q0-16 6-30.5t17-25.5l505-504q12-11 26.5-17t30.5-6q16 0 31 6t26 18l55 56q12 11 17.5 26t5.5 30q0 16-5.5 30.5T817-647L313-143q-11 11-25.5 17t-30.5 6h-97Zm600-584-56-56 56 56Zm-141 85-28-29 57 57-29-28Z")
    val error = symbol("error", "M480-280q17 0 28.5-11.5T520-320q0-17-11.5-28.5T480-360q-17 0-28.5 11.5T440-320q0 17 11.5 28.5T480-280Zm0-160q17 0 28.5-11.5T520-480v-160q0-17-11.5-28.5T480-680q-17 0-28.5 11.5T440-640v160q0 17 11.5 28.5T480-440Zm0 360q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z")
    val chevronLeft = symbol("chevronLeft", "m432-480 156 156q11 11 11 28t-11 28q-11 11-28 11t-28-11L348-452q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l184-184q11-11 28-11t28 11q11 11 11 28t-11 28L432-480Z")
}
