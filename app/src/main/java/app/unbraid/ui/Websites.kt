package app.unbraid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.unbraid.APP
import app.unbraid.data.DIRECT
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.data.Sites

/** The website tile that takes a website's own route away. */
internal const val NO_ROUTE = "none"

/** Where website [site] goes, for its row and for TalkBack. */
internal fun siteState(s: Setup, site: String): String = when (val id = s.sites[site]) {
    DIRECT -> "Direct, skips the VPN"
    else -> s.route(id)?.name ?: "A route that no longer exists"
}

/** Why [raw] can't be added as a website of [s], or null when it can (or nothing is typed yet). */
internal fun siteError(s: Setup, raw: String): String? = when {
    raw.isBlank() -> null
    Sites.clean(raw) == null -> "That isn't a website address. Try one like example.com"
    Sites.clean(raw) in s.sites -> "This website is already on the list"
    s.sites.size >= Sites.MAX -> "A profile holds up to ${Sites.MAX} websites"
    else -> null
}

/** Said under the websites: what a rule covers and what it can't reach. [outside] = some app is left outside unbraid by Android. */
internal fun sitesNote(outside: Boolean) = "A website's route beats its app's, and covers every address under it (example.com and www.example.com). " +
    "A service that uses other addresses too needs each added. A browser set to its own secure DNS keeps its app's route." + if (outside) " Apps set to Direct are outside $APP: websites opened in them aren't re-routed." else ""

/** The Apps page's two lists as a connected pair: apps, and websites ([count] of them). */
@Composable
internal fun AppsOrSites(sites: Boolean, count: Int, onPick: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(false to "Apps", true to if (count == 0) "Websites" else "Websites · $count").forEachIndexed { i, (s, label) ->
            val on = sites == s
            val bg by animateColorAsState(if (on) Ink.accentContainer else Ink.surfaceHigh, MaterialTheme.motionScheme.fastEffectsSpec(), label = "list")
            Row(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(segmentShape(i, 2)).background(bg).selectable(on, role = Role.Tab) { onPick(s) }.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                if (on) Icon(Icons.check, null, Modifier.size(18.dp), tint = Ink.accent)
                Text(label, style = Label, color = if (on) Ink.accent else Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

/** The Websites list: each address with its route, opening the same route tiles as an app; then Add website and what a rule covers. */
internal fun LazyListScope.websites(ui: Ui, setup: Setup, offered: List<Route>, open: String?, onOpen: (String?) -> Unit, onAdd: () -> Unit, outside: Boolean) {
    if (setup.sites.isEmpty()) item("sites-empty") {
        Text("No websites yet. Add one to send it through a route of its own, in any browser or app.", style = Type.sub, color = Ink.dim, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
    }
    items(setup.sites.keys.sorted(), key = { "site:$it" }) { site -> SiteRow(ui, site, setup, offered, open == site) { onOpen(site.takeIf { open != site }) } }
    item("sites-add") {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Secondary("Add website", onAdd, enabled = setup.sites.size < Sites.MAX)
            Text(sitesNote(outside), style = Type.caption, color = Ink.dim)
        }
    }
}

@Composable
private fun SiteRow(ui: Ui, site: String, setup: Setup, offered: List<Route>, expanded: Boolean, onToggle: () -> Unit) {
    val motion = MaterialTheme.motionScheme
    val id = setup.sites[site].orEmpty()
    val route = setup.route(id)
    val ground by animateColorAsState(if (expanded) Ink.surface else Color.Transparent, motion.fastEffectsSpec(), label = "row")
    Column(Modifier.padding(horizontal = 8.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(ground)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).tap(onToggle, if (expanded) "Close" else "Choose a route")
                .semantics { stateDescription = siteState(setup, site) + if (expanded) ", open" else "" }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(Ink.surfaceTop), contentAlignment = Alignment.Center) { Icon(Icons.link, null, Modifier.size(22.dp), tint = Ink.text) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(site, style = Type.body, color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(siteState(setup, site), style = Type.caption, color = if (id != DIRECT && route == null) Ink.bad else Ink.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RouteMark(route?.code ?: "··", route?.let { Ink.route(it.hue) } ?: Ink.direct, Modifier.clearAndSetSemantics {}, direct = id == DIRECT)
        }
        AnimatedVisibility(
            expanded,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            RouteTiles(routeTiles(setup, offered) + Tile(NO_ROUTE, "Remove", Icons.close, Ink.bad, tinted = false), id, "Direct: skips the VPN in every app that passes through $APP.".takeIf { id == DIRECT }) {
                ui.site(site, it.takeIf { t -> t != NO_ROUTE })
            }
        }
    }
}

/** Adding a website: its address (a pasted link works), then where it goes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SiteSheet(setup: Setup, offered: List<Route>, onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var raw by rememberSaveable { mutableStateOf("") }
    // The first route that isn't the default: a website on the default's own route changes little.
    var to by rememberSaveable { mutableStateOf(offered.firstOrNull { it.id != setup.defaultRoute }?.id ?: offered.firstOrNull()?.id ?: DIRECT) }
    val site = Sites.clean(raw)
    val error = siteError(setup, raw)
    val add = { if (site != null && error == null) onAdd(site, to) }
    ModalBottomSheet(onDismiss, sheetState = fullSheet(), containerColor = Ink.surface, contentColor = Ink.text) {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Ink.accentContainer), contentAlignment = Alignment.Center) { Icon(Icons.link, null, Modifier.size(24.dp), tint = Ink.accent) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Add website", style = Type.title.copy(fontSize = Type.headline.fontSize * 0.5f))
                    Text("It goes through its own route, whatever app opens it", style = Type.caption, color = Ink.dim)
                }
            }
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    raw, { raw = it.take(300) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Address", style = Type.caption) },
                    placeholder = { Text("example.com", style = Type.body, color = Ink.dim) }, textStyle = Type.body, isError = error != null, colors = inkFieldColors(), shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                )
                Text(error ?: site?.let { "Covers $it and every address under it" } ?: "Type the address or paste a link", style = Type.caption, color = if (error != null) Ink.bad else Ink.dim, modifier = Modifier.padding(horizontal = 4.dp))
            }
            Text("GOES THROUGH", style = Type.overline, color = Ink.dim, modifier = Modifier.padding(horizontal = 24.dp))
            Box(Modifier.padding(start = 10.dp)) { RouteTiles(routeTiles(setup, offered), to, "Direct: skips the VPN in every app that passes through $APP.".takeIf { to == DIRECT }, indent = false) { to = it } }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Secondary("Cancel", onDismiss, color = Ink.dim)
                Primary("Add", add, enabled = site != null && error == null)
            }
        }
    }
}
