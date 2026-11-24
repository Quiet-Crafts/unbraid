package app.unbraid.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.unbraid.data.BLOCK
import app.unbraid.data.DIRECT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Adds, edits or confirms a proxy server as a provider is signed in to: choose its app, then its details. */
@Composable
fun ProxyScreen(ui: Ui) {
    val draft = ui.importDraft
    val disclosed by ui.disclosed.collectAsStateWithLifecycle()
    val close = { draft.clear() }
    var choosing by rememberSaveable { mutableStateOf(false) }
    // Leaving the details drops a check still running: nothing is saved behind the list.
    val choose = { draft.proxyEdited(); choosing = true }
    when {
        !disclosed -> {
            Box(Modifier.fillMaxSize().background(Ink.ground))
            AppsDisclosure({ ui.acceptDisclosure() }, close)
        }
        draft.proxyApp == null || choosing -> {
            BackHandler(choosing) { choosing = false }
            ChooseApp(ui, if (choosing) ({ choosing = false }) else close) { draft.proxyChoice(it); choosing = false }
        }
        else -> {
            // Back to the list when adding; editing starts here, so Back leaves.
            BackHandler(draft.refill == null, choose)
            Details(ui, draft, if (draft.refill == null) choose else close, choose)
        }
    }
}

@Composable
private fun ChooseApp(ui: Ui, close: () -> Unit, pick: (AppInfo) -> Unit) {
    val apps by ui.apps.collectAsStateWithLifecycle()
    val pm = LocalContext.current.packageManager
    // Apps that can run a VPN: proxy apps nearly always can. Read app by app, as a search skips services that
    // aren't exported; the list waits for it, or the likely ones would appear above what is in view.
    val vpns by produceState<Set<String>?>(null, apps) {
        if (apps.isNotEmpty()) value = withContext(Dispatchers.IO) { apps.filter { a -> !a.system && runCatching {
            pm.getPackageInfo(a.pkg, PackageManager.GET_SERVICES).services?.any { it.permission == Manifest.permission.BIND_VPN_SERVICE }
        }.getOrNull() == true }.map { it.pkg }.toSet() }
    }
    var q by rememberSaveable { mutableStateOf("") }
    val (likely, others) = remember(apps, vpns, q.trim()) { vpns?.let { proxyApps(apps, it, q.trim()) } ?: (emptyList<AppInfo>() to emptyList()) }
    Screen(
        overline = "Providers & servers", title = "Add proxy server", sub = "Choose the app that runs it",
        navigation = { IconTap(Icons.close, "Close", close) }, search = { SearchField(q, { q = it }, "Find an installed app") },
    ) {
        fun LazyListScope.list(title: String, list: List<AppInfo>, sub: String?) {
            if (list.isEmpty()) return
            item(key = title) { SectionLabel(title) }
            itemsIndexed(list, key = { _, a -> a.pkg }) { i, a ->
                GroupRow(i, list.size, a.label, sub, onClick = { pick(a) }, leading = { AppIcon(ui, a.pkg, a.label) }, trailing = { Chevron() })
            }
        }
        list("Likely", likely, "VPN or proxy app")
        list("Other apps", others, null)
        if (vpns != null && likely.isEmpty() && others.isEmpty()) item { Caption("No matching apps. Install the app that runs your proxy, then try again.") }
    }
}

/** The apps to choose from, those that can run a VPN first; system apps only when searched for. */
internal fun proxyApps(apps: List<AppInfo>, vpns: Set<String>, query: String): Pair<List<AppInfo>, List<AppInfo>> = apps
    .filter { it.identity != null && (if (query.isEmpty()) !it.system else it.label.contains(query, true) || it.pkg.contains(query, true)) }
    .partition { it.pkg in vpns }

@Composable
private fun Details(ui: Ui, draft: ImportDraft, back: () -> Unit, chooseApp: () -> Unit) {
    SecureWindow()
    val app = draft.proxyApp ?: return
    val setup by ui.setup.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val direct = remember(setup, app) { ui.proxyDirect(app) }
    val ready = draft.port.isNotEmpty() && !draft.proxyChecking
    Box(Modifier.fillMaxSize()) {
        Screen(
            overline = if (draft.refill == null) "Add proxy server" else "Proxy server", title = app.label, sub = "Enter its details",
            navigation = { IconTap(Icons.back, "Back", back) },
        ) {
            item {
                Column(Modifier.padding(start = Gutter, end = Gutter, top = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (draft.refill != null) GroupRow(0, 1, app.label, "The app that runs it", onClick = chooseApp, leading = { AppIcon(ui, app.pkg, app.label) }, trailing = { Chevron() })
                    @Composable
                    fun field(label: String, value: String, hint: String, secret: Boolean = false, numeric: Boolean = false, last: Boolean = false, set: (String) -> Unit) {
                        Text(label, style = Type.sub.copy(fontWeight = FontWeight.Medium), color = Ink.text, modifier = Modifier.padding(horizontal = 8.dp))
                        PillField(value, { set(it); draft.proxyEdited() }, label, placeholder = hint, secret = secret, numeric = numeric,
                            imeAction = if (last) ImeAction.Done else ImeAction.Next, onDone = { if (ready) ui.submitProxy(direct) })
                    }
                    field("SOCKS5 port", draft.port, "e.g. 1080", numeric = true) { draft.port = it.filter(Char::isDigit).take(5) }
                    field("Username", draft.user, "Only if ${app.label} asks for one") { draft.user = it }
                    field("Password", draft.pass, "Only if ${app.label} asks for one", secret = true, last = true) { draft.pass = it }
                    draft.error?.let { Text(it, style = Type.caption, color = Ink.bad, modifier = Modifier.padding(horizontal = 8.dp)) }
                    if (draft.proxySilent) {
                        Text("Nothing answers on port ${draft.port}. Start the proxy in ${app.label}, or check the port.", style = Type.caption, color = Ink.bad, modifier = Modifier.padding(horizontal = 8.dp))
                        context.packageManager.getLaunchIntentForPackage(app.pkg)?.let { open -> Secondary("Open ${app.label}", { runCatching { context.startActivity(open) } }) }
                        Secondary("Save without checking", { ui.submitProxy(direct, check = false) }, color = Ink.dim)
                    }
                    Text(
                        "Start the SOCKS5 proxy in ${app.label}; its settings show the port. ${directText(setup.viewAs(draft.proxyProfile), app.pkg, app.label, direct)} " +
                            (if (setup.untouched && ui.accounts.value.isEmpty()) "On a new setup, also choose a default in Routes for your other apps. " else "") + "Stored encrypted on this phone.",
                        style = Type.caption, color = Ink.dim, modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Ink.ground).imePadding().navigationBarsPadding()
                .padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 16.dp),
        ) { Primary(if (draft.proxySilent) "Try again" else "Continue", { ui.submitProxy(direct) }, enabled = ready, busy = draft.proxyChecking) }
    }
}

/** What becomes of the proxy app's own traffic, in words: [direct] is what saving does. */
internal fun directText(s: app.unbraid.data.Setup, pkg: String, label: String, direct: ProxyDirect): String = when (direct) {
    ProxyDirect.Already -> "$label goes Direct, so its traffic never loops."
    is ProxyDirect.Refuse -> "$label needs Direct to work, and isn't changed here because ${direct.reason}. Set it in Apps."
    is ProxyDirect.Grant -> {
        val now = s.apps[pkg]
        val via = { id: String? -> if (id == DIRECT) "Direct" else s.route(id.orEmpty())?.name ?: "a route that is gone" }
        val before = if (now == DIRECT || now == BLOCK) "" else if (now == null) "$label now follows the default, ${via(s.defaultRoute)}. " else "$label now uses ${via(now)}. "
        before + "It will go Direct, without a VPN" + (if (direct.filterChanges) " or the DNS filter" else "") + ", so its traffic never loops."
    }
}
