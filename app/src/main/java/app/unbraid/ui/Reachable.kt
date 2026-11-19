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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.unbraid.APP
import app.unbraid.data.BLOCK
import app.unbraid.data.Setup
import app.unbraid.data.TailnetDevice
import app.unbraid.vpn.TsNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Reachable on your tailnet: tailnet devices open connections to chosen apps on this phone (experimental). */
internal const val REACH_TITLE = "Reachable on your tailnet"
internal const val REACH_SWITCH = "Allow incoming tailnet connections"
internal const val REACH_PROMISE = "Devices your Tailscale access rules allow can open connections to the apps you choose, " +
    "and each app sees which device a connection comes from. $APP holds all data until Android names the app receiving it, " +
    "and refuses connections to apps it can't name, didn't choose or are on Block."
internal const val REACH_NEEDS = "Turn on Reach your tailnet to allow incoming connections."
internal const val REACH_ALL = "All devices your Tailscale access rules allow"
internal const val REACH_ONLY = "Only devices you choose"
internal const val REACH_ONLY_SUB = "Your Tailscale access rules still apply. Other devices get a connection refused response."
internal const val REACH_RECENT_NOTE = "The last 50 decisions stay in memory until the VPN stops. They're never saved, logged or included in reports."
internal const val KDE_CONNECT = "org.kde.kdeconnect_tp"
internal const val KDE_TIP = "Use KDE Connect 1.36 or newer on this phone and give it the Nearby devices permission. " +
    "Then, in KDE Connect, add your PC with Add devices by IP and its tailnet address. If it doesn't reconnect after the VPN " +
    "restarts, use Refresh in KDE Connect's menu."

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
        s.home.reachableOnly && s.home.reachableDevices.isEmpty() -> "No devices chosen · No device can connect"
        n == 0 -> "No apps selected"
        tsWaiting -> "Paused · Tailscale isn't connected"
        else -> if (n == 1) "1 app allowed" else "$n apps allowed"
    }
}

/** One decision of the engine on a connection a tailnet device opened (kept in memory only, cleared on stop). */
@Serializable
internal data class ReachEvent(
    val time: Long, val peer: String, val node: String = "", val port: Int = 0,
    val packages: List<String> = emptyList(), val allowed: Boolean = false, val reason: String = "",
)

private val eventJson = Json { ignoreUnknownKeys = true }

/** The engine's recent decisions, newest first; anything unreadable gives none. */
internal fun reachEvents(json: String?): List<ReachEvent> =
    runCatching { eventJson.decodeFromString(ListSerializer(ReachEvent.serializer()), json!!) }.getOrDefault(emptyList()).asReversed()

/** The device a decision was about: its name now, the name it was chosen under, or its address. */
internal fun eventDevice(e: ReachEvent, peers: List<TsNode>, chosen: List<TailnetDevice>) =
    peers.firstOrNull { it.id == e.node && it.id.isNotEmpty() }?.name ?: chosen.firstOrNull { it.id == e.node }?.name ?: e.peer

/** A decision's line: the device, and the app when one was named ([label] names an app by its package). */
internal fun eventTitle(device: String, e: ReachEvent, label: (String) -> String) =
    e.packages.firstOrNull()?.let { "$device to ${label(it)}" } ?: device

/** Why a decision went as it did, as the engine recorded it then. */
internal fun eventOutcome(e: ReachEvent): String = when {
    e.allowed -> "Allowed"
    e.reason == "device" -> "Refused · Device wasn't chosen"
    e.reason == "blocked" -> "Refused · App was on Block"
    e.reason == "app" -> "Refused · App wasn't chosen"
    else -> "Refused · Android couldn't identify the app"
}

/** How long ago, in short words. */
internal fun ago(ms: Long): String = when {
    ms < 60_000 -> "just now"
    ms < 3_600_000 -> "${ms / 60_000} min ago"
    ms < 86_400_000 -> "${ms / 3_600_000} h ago"
    else -> "${ms / 86_400_000} d ago"
}

@Composable
internal fun ExperimentalTag() = Text(
    "EXPERIMENTAL", style = Type.overline, color = Ink.accent,
    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Ink.accentContainer).padding(horizontal = 8.dp, vertical = 3.dp),
)

/** A row of a [ChooseSheet]: [refusal] lists it without a way to pick it. */
/** [hidden] rows show only when searched for (system apps). */
internal class Choice(val key: String, val title: String, val sub: String, val refusal: String? = null, val bad: Boolean = false, val hidden: Boolean = false, val leading: (@Composable () -> Unit)? = null)

/** A sheet to pick several of [choices] at once, with a search; [preface] stands in for the list while it can't show. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooseSheet(
    title: String, sub: String, icon: ImageVector, search: String, choices: List<Choice>, empty: (Boolean) -> String,
    preface: (@Composable () -> Unit)?, onAdd: (List<String>) -> Unit, onDismiss: () -> Unit,
) {
    var q by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    val query = q.trim()
    val shown = remember(choices, query) {
        choices.filter { if (query.isEmpty()) !it.hidden else it.title.contains(query, true) || it.sub.contains(query, true) }
            .sortedBy { it.refusal != null } // the ones that can be picked first
    }
    ModalBottomSheet(onDismiss, sheetState = fullSheet(), containerColor = Ink.surface, contentColor = Ink.text) {
        Column(Modifier.imePadding().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Ink.accentContainer), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(24.dp), tint = Ink.accent) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = Type.title.copy(fontSize = Type.headline.fontSize * 0.5f))
                    Text(sub, style = Type.caption, color = Ink.dim)
                }
            }
            if (preface != null) preface() else {
                Box(Modifier.padding(horizontal = 16.dp)) { SearchField(q, { q = it }, search) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    if (shown.isEmpty()) item { Text(empty(query.isEmpty()), style = Type.sub, color = Ink.dim, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) }
                    items(shown, key = { it.key }) { c ->
                        val on = c.key in picked
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp)
                                .toggleable(on, enabled = c.refusal == null, role = Role.Checkbox) { picked = if (on) picked - c.key else picked + c.key }
                                .padding(horizontal = 24.dp, vertical = 8.dp).alpha(if (c.refusal == null) 1f else 0.6f),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            c.leading?.invoke()
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(c.title, style = Type.body, color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(c.refusal ?: c.sub, style = Type.small, color = if (c.bad) Ink.bad else Ink.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (c.refusal == null) Box(
                                Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(if (on) Ink.accent else Ink.surface).border(if (on) 0.dp else 2.dp, Ink.dim, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center,
                            ) { if (on) Icon(Icons.check, null, Modifier.size(18.dp), tint = Ink.onAccent) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Secondary("Cancel", onDismiss, color = Ink.dim)
                Primary(if (picked.size > 1) "Add ${picked.size}" else "Add", { onAdd(picked) }, enabled = picked.isNotEmpty())
            }
        }
    }
}

/**
 * Adding apps tailnet devices may reach: a search over the installed apps, several at once. Apps outside the VPN or on
 * Block are listed but can't be picked, with the reason.
 */
@Composable
internal fun ReachSheet(ui: Ui, setup: Setup, lockdown: Boolean, disclose: () -> Unit, onAdd: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val disclosed by ui.disclosed.collectAsStateWithLifecycle()
    val apps by ui.apps.collectAsStateWithLifecycle()
    val self = androidx.compose.ui.platform.LocalContext.current.packageName
    val choices = remember(apps, setup, lockdown) {
        apps.filter { a -> a.pkg != self && a.pkg !in setup.home.reachableApps }.map { a ->
            Choice(a.pkg, a.label, a.pkg, reachRefusal(setup, a.pkg, lockdown), setup.apps[a.pkg] == BLOCK, a.system && a.pkg !in setup.apps) { AppIcon(ui, a.pkg, a.label) }
        }
    }
    ChooseSheet("Add apps", "Tailnet devices will be able to open the apps you add", Icons.lan, "Search apps", choices,
        { all -> if (all) "Every app is added already" else "No app matches" },
        if (disclosed) null else { {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$APP needs the list of installed apps to offer them here. It stays on this phone.", style = Type.sub, color = Ink.dim)
                Secondary("Show my apps", disclose)
            }
        } }, onAdd, onDismiss)
}

/** Choosing the tailnet devices that may connect: the tailnet's devices, known only while connected. */
@Composable
internal fun DeviceSheet(peers: List<TsNode>, chosen: List<TailnetDevice>, onAdd: (List<TailnetDevice>) -> Unit, onDismiss: () -> Unit) {
    val offered = peers.filter { p -> p.id.isNotEmpty() && chosen.none { it.id == p.id } }.distinctBy { it.id }
    val choices = offered.map { Choice(it.id, it.name, (if (it.online) "Online · " else "Offline · ") + it.ip) }
    ChooseSheet("Add devices", "Only the devices you add can connect", Icons.lan, "Search devices", choices,
        { all -> if (all) "Every device is added already" else "No device matches" },
        if (peers.isNotEmpty()) null else { {
            Text("Connect with Tailscale on to see your tailnet's devices.", style = Type.sub, color = Ink.dim, modifier = Modifier.padding(horizontal = 24.dp))
        } },
        { ids -> onAdd(offered.filter { it.id in ids }.map { TailnetDevice(it.id, it.name) }) }, onDismiss)
}
