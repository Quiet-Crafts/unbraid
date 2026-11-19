package app.unbraid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.unbraid.APP
import app.unbraid.data.BLOCK
import app.unbraid.data.Setup

/** Reachable on your tailnet: tailnet devices open connections to chosen apps on this phone (experimental). */
internal const val REACH_TITLE = "Reachable on your tailnet"
internal const val REACH_SWITCH = "Allow incoming tailnet connections"
internal const val REACH_PROMISE = "Devices your Tailscale access rules allow can open connections to the apps you choose, " +
    "and each app sees which device a connection comes from. $APP holds all data until Android names the app receiving it, " +
    "and refuses connections to apps it can't name, didn't choose or are on Block."
internal const val REACH_NEEDS = "Turn on Reach your tailnet to allow incoming connections."
internal const val KDE_CONNECT = "org.kde.kdeconnect_tp"
internal const val KDE_TIP = "Use KDE Connect 1.36 or newer on this phone and give it the Nearby devices permission. " +
    "Then, in KDE Connect, add your PC with Add devices by IP and its tailnet address."

/** Why tailnet devices can't reach [pkg] even when chosen; null: they can. */
internal fun reachRefusal(s: Setup, pkg: String, lockdown: Boolean): String? = when {
    s.apps[pkg] == BLOCK -> "Blocked · incoming connections refused"
    !lockdown && s.outside(pkg) -> "Outside $APP · tailnet devices can't reach it"
    else -> null
}

/** The chosen apps tailnet devices can reach now. */
internal fun reachAllowed(s: Setup, lockdown: Boolean) = s.home.reachableApps.count { reachRefusal(s, it, lockdown) == null }

/** What the switch's row says. [tsWaiting]: the VPN runs but Tailscale isn't connected. */
internal fun reachSub(s: Setup, lockdown: Boolean, tsWaiting: Boolean): String {
    val n = reachAllowed(s, lockdown)
    return when {
        !s.home.tailscale -> "Needs Reach your tailnet"
        !s.home.reachable -> "Off"
        n == 0 -> "No apps selected"
        tsWaiting -> "Paused · Tailscale isn't connected"
        else -> if (n == 1) "1 app allowed" else "$n apps allowed"
    }
}

@Composable
internal fun ExperimentalTag() = Text(
    "EXPERIMENTAL", style = Type.overline, color = Ink.accent,
    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Ink.accentContainer).padding(horizontal = 8.dp, vertical = 3.dp),
)

/**
 * Adding apps tailnet devices may reach: a search over the installed apps, several at once. Apps outside the VPN or on
 * Block are listed but can't be picked, with the reason.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReachSheet(ui: Ui, setup: Setup, lockdown: Boolean, disclose: () -> Unit, onAdd: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val disclosed by ui.disclosed.collectAsStateWithLifecycle()
    val apps by ui.apps.collectAsStateWithLifecycle()
    val self = androidx.compose.ui.platform.LocalContext.current.packageName
    var q by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    val query = q.trim()
    val shown = remember(apps, setup, query, lockdown) {
        apps.filter { a ->
            a.pkg != self && a.pkg !in setup.home.reachableApps && (!a.system || query.isNotEmpty() || a.pkg in setup.apps) &&
                (query.isEmpty() || a.label.contains(query, true) || a.pkg.contains(query, true))
        }.sortedBy { reachRefusal(setup, it.pkg, lockdown) != null } // the ones that can be picked first, each part A to Z
    }
    ModalBottomSheet(onDismiss, sheetState = fullSheet(), containerColor = Ink.surface, contentColor = Ink.text) {
        Column(Modifier.imePadding().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Ink.accentContainer), contentAlignment = Alignment.Center) { Icon(Icons.lan, null, Modifier.size(24.dp), tint = Ink.accent) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Add apps", style = Type.title.copy(fontSize = Type.headline.fontSize * 0.5f))
                    Text("Tailnet devices will be able to open the apps you add", style = Type.caption, color = Ink.dim)
                }
            }
            if (!disclosed) {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$APP needs the list of installed apps to offer them here. It stays on this phone.", style = Type.sub, color = Ink.dim)
                    Secondary("Show my apps", disclose)
                }
            } else {
                Box(Modifier.padding(horizontal = 16.dp)) { SearchField(q, { q = it }, "Search apps") }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    if (shown.isEmpty()) item { Text(if (query.isEmpty()) "Every app is added already" else "No app matches", style = Type.sub, color = Ink.dim, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) }
                    items(shown, key = { it.pkg }) { a ->
                        val refusal = reachRefusal(setup, a.pkg, lockdown)
                        val on = a.pkg in picked
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp)
                                .toggleable(on, enabled = refusal == null, role = Role.Checkbox) { picked = if (on) picked - a.pkg else picked + a.pkg }
                                .padding(horizontal = 24.dp, vertical = 8.dp).alpha(if (refusal == null) 1f else 0.6f),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            AppIcon(ui, a.pkg, a.label)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(a.label, style = Type.body, color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(refusal ?: a.pkg, style = Type.small, color = if (refusal != null && setup.apps[a.pkg] == BLOCK) Ink.bad else Ink.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (refusal == null) Box(
                                Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(if (on) Ink.accent else Ink.surface).border(if (on) 0.dp else 2.dp, Ink.dim, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center,
                            ) { if (on) Icon(Icons.check, null, Modifier.size(18.dp), tint = Ink.onAccent) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Secondary("Cancel", onDismiss, color = Ink.dim)
                Primary(if (picked.size > 1) "Add ${picked.size} apps" else "Add", { onAdd(picked) }, enabled = picked.isNotEmpty())
            }
        }
    }
}
