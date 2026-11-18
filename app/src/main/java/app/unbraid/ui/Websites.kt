@file:OptIn(app.unbraid.data.SavedSites::class) // the list, its rows and the Add sheet show every saved website

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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import app.unbraid.data.BLOCK
import app.unbraid.data.DIRECT
import app.unbraid.data.builtIn
import app.unbraid.data.Route
import app.unbraid.data.SavedSites
import app.unbraid.data.Setup
import app.unbraid.data.Sites

/** The website tile that takes a website's own route away. */
internal const val NO_ROUTE = "none"

/**
 * Where website [site] goes, for its row and for TalkBack, with the tailnet domain Tailscale [reported]. An address saved
 * by an older version that no browser opens says so.
 */
internal fun siteState(s: Setup, site: String, reported: String? = null): String {
    val id = s.sites[site]
    val off = Sites.off(site, id, s, reported = reported)
    return when {
        id == BLOCK -> "Blocked, no traffic"
        // Block reaches every address under a blocked website, whatever route one of them has.
        off?.by == Sites.Off.By.BLOCK -> "Blocked: ${off.why}"
        off != null -> "Not applied: ${off.why}"
        id == DIRECT -> "Direct, skips the VPN"
        else -> s.route(id)?.name ?: "A route that no longer exists"
    } + if (!Sites.opens(site)) " · not an address browsers open" else ""
}

/** The website rows of a Routes page: each route's websites whose rule applies, A to Z. */
internal fun sitesBy(s: Setup, reported: String? = null) = Sites.applied(s, reported = reported).entries.groupBy({ it.value }, { it.key }).mapValues { it.value.sorted() }

/** A profile's websites in a summary (" · 3 websites"), with how many of them don't apply now; empty for none. */
internal fun websitesText(s: Setup, sites: Map<String, String>, reported: String? = null): String {
    val off = sites.size - Sites.applied(s, sites, reported).size
    return if (sites.isEmpty()) "" else (if (sites.size == 1) " · 1 website" else " · ${sites.size} websites") + if (off > 0) ", $off not applied" else ""
}

/** Said in the Add website sheet under the address: what the rule covers, and why it won't apply now when a lookup rule comes first. */
internal fun siteHint(s: Setup, site: String, to: String, reported: String? = null) =
    "Covers ${Sites.shown(site)} and every address under it" + (Sites.off(site, to, s, reported = reported)?.let { ". ${if (it.by == Sites.Off.By.BLOCK) "Blocked" else "Not applied"} now: ${it.why}" } ?: "")

/** [name] cut in the middle when it is too long for two lines of a row, so its start and its end both show. */
internal fun shortName(name: String) = if (name.length <= 56) name else name.take(26) + "…" + name.takeLast(26)

/** Said under a website's choices when it is on Direct or on Block: what that means for every app. */
internal fun siteNote(id: String) = when (id) {
    DIRECT -> "Direct: skips the VPN in every app that passes through $APP."
    BLOCK -> "Block: apps that pass through $APP can't look this website up or reach it by name while it is connected. An app on Direct, or one using its own secure DNS, is not covered."
    else -> null
}

/** Why [raw] can't be added as a website of [s], or null when it can (or nothing is typed yet). */
internal fun siteError(s: Setup, raw: String): String? = if (raw.isBlank()) null else Sites.refusal(raw) ?: when {
    Sites.clean(raw) in s.sites -> "This website is already on the list"
    s.sites.size >= Sites.MAX -> "A profile holds up to ${Sites.MAX} websites"
    else -> null
}

/** Said under the websites: what a rule covers and what it can't reach. [outside] = some app is left outside unbraid by Android. */
internal fun sitesNote(outside: Boolean) = "A website's route beats its app's, and covers every address under it (example.com and www.example.com). " +
    "A service that uses other addresses too needs each added. A browser set to its own secure DNS keeps its app's route." + if (outside) " Apps set to Direct are outside $APP: websites opened in them aren't re-routed." else ""

/**
 * The websites the list shows: those on route [filter] (null = all) whose address has [query] in it, grouped like the
 * apps: the other exits A to Z by name, then Direct, then the default; addresses A to Z inside each.
 */
internal fun shownSites(s: Setup, query: String, filter: String?): List<String> {
    val rank = (s.routes.sortedBy { it.name.lowercase() }.map { it.id } + DIRECT + BLOCK).filter { it != s.defaultRoute }.withIndex().associate { (i, id) -> id to i }
    return s.sites.filter { (site, id) -> (filter == null || id == filter) && (query.isEmpty() || Sites.readings(site).any { it.contains(query, true) }) }.keys
        .sortedWith(compareBy({ rank[s.sites[it]] ?: Int.MAX_VALUE }, { it }))
}

/** The Websites list when nothing shows: why, and whether "Show all" brings websites back (a route filter hides them). */
internal fun emptySites(any: Boolean, query: String, filter: String?): Pair<String, Boolean> = when {
    !any -> "No websites yet. Add one to send it through a route of its own, in any browser or app." to false
    filter != null -> (if (query.isEmpty()) "No websites use $filter." else "No websites on $filter match \"$query\".") to true
    else -> "No websites match \"$query\"." to false
}

/** The Apps page's two lists as a connected pair. Selection is the colour alone. */
@Composable
internal fun AppsOrSites(sites: Boolean, onPick: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp).then(tourAnchor(TourStop.Websites)).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(false to "Apps", true to "Websites").forEachIndexed { i, (s, label) ->
            val on = sites == s
            val bg by animateColorAsState(if (on) Ink.accentContainer else Ink.surfaceHigh, MaterialTheme.motionScheme.fastEffectsSpec(), label = "list")
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(segmentShape(i, 2)).background(bg).selectable(on, role = Role.Tab) { onPick(s) }.padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = Label, color = if (on) Ink.accent else Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }

/** Add website, under the list: the same square as the add button on Routes. Greyed at the most a profile holds. */
@Composable
internal fun AddSiteBar(room: Boolean, onAdd: () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        if (!room) Text("${Sites.MAX} websites is the most. Remove one to add another.", style = Type.caption, color = Ink.dim, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(if (room) Ink.accent else Ink.surfaceHigh).tap({ if (room) onAdd() }, role = Role.Button)
                .semantics { contentDescription = "Add website" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.add, null, Modifier.size(24.dp), tint = if (room) Ink.onAccent else Ink.faint) }
    }

/** The Websites list: [shown] of the setup's addresses, each opening the same route tiles as an app; then what a rule covers. */
internal fun LazyListScope.websites(
    ui: Ui, setup: Setup, offered: List<Route>, shown: List<String>, query: String, filter: String?, showAll: () -> Unit,
    open: String?, onOpen: (String?) -> Unit, outside: Boolean,
) {
    if (shown.isEmpty()) item("sites-empty") {
        val name = when (filter) { null -> null; DIRECT -> "Direct"; BLOCK -> "Block"; else -> setup.route(filter)?.name ?: "this route" }
        val (text, all) = emptySites(setup.sites.isNotEmpty(), query, name)
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = Type.sub, color = Ink.dim)
            if (all) Secondary("Show all", showAll)
        }
    }
    items(shown, key = { "site:$it" }) { site -> Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) { SiteRow(ui, site, setup, offered, open == site) { onOpen(site.takeIf { open != site }) } } }
    item("sites-note") { Text(sitesNote(outside), style = Type.caption, color = Ink.dim, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) }
}

@Composable
private fun SiteRow(ui: Ui, site: String, setup: Setup, offered: List<Route>, expanded: Boolean, onToggle: () -> Unit) {
    val motion = MaterialTheme.motionScheme
    val id = setup.sites[site].orEmpty()
    val route = setup.route(id)
    val state = siteState(setup, site, ui.vpn.collectAsStateWithLifecycle().value.tsSuffix)
    val ground by animateColorAsState(if (expanded) Ink.surface else Color.Transparent, motion.fastEffectsSpec(), label = "row")
    Column(Modifier.padding(horizontal = 8.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(ground)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).tap(onToggle, if (expanded) "Close" else "Choose a route")
                .semantics { stateDescription = state + if (expanded) ", open" else "" }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(Ink.surfaceTop), contentAlignment = Alignment.Center) { Icon(Icons.link, null, Modifier.size(22.dp), tint = Ink.text) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // A long name keeps both ends in view, which tell it apart; open, it shows whole. TalkBack always reads it whole.
                val name = Sites.shown(site)
                Text(if (expanded) name else shortName(name), style = Type.body, color = Ink.text, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { contentDescription = name })
                Text(state, style = Type.caption, color = if (!builtIn(id) && route == null) Ink.bad else Ink.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RouteMark(route?.code ?: "··", route?.let { Ink.route(it.hue) } ?: if (id == BLOCK) Ink.bad else Ink.direct, Modifier.clearAndSetSemantics {}, direct = id == DIRECT, block = id == BLOCK)
        }
        AnimatedVisibility(
            expanded,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            RouteTiles(routeTiles(setup, offered) + Tile(NO_ROUTE, "Remove", Icons.close, Ink.bad, tinted = false), id, siteNote(id)) {
                ui.site(site, it.takeIf { t -> t != NO_ROUTE })
            }
        }
    }
}

/** Adding a website: its address (a pasted link works), then where it goes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SiteSheet(setup: Setup, offered: List<Route>, reported: String?, onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
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
                    raw, { raw = it.take(Sites.PASTE + 1) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Address", style = Type.caption) },
                    placeholder = { Text("example.com", style = Type.body, color = Ink.dim) }, textStyle = Type.body, isError = error != null, colors = inkFieldColors(), shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                )
                Text(error ?: site?.let { siteHint(setup, it, to, reported) } ?: "Type the address or paste a link", style = Type.caption, color = if (error != null) Ink.bad else Ink.dim, modifier = Modifier.padding(horizontal = 4.dp))
            }
            Text("GOES THROUGH", style = Type.overline, color = Ink.dim, modifier = Modifier.padding(horizontal = 24.dp))
            Box(Modifier.padding(start = 10.dp)) { RouteTiles(routeTiles(setup, offered), to, siteNote(to), indent = false) { to = it } }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Secondary("Cancel", onDismiss, color = Ink.dim)
                Primary("Add", add, enabled = site != null && error == null)
            }
        }
    }
}
