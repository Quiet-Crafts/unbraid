package app.unbraid.ui

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.unbraid.data.AppIdentity
import app.unbraid.data.Socks5Profile
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import app.unbraid.data.Manual
import app.unbraid.data.OwnServer
import app.unbraid.data.WgConf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CAP = 64 * 1024

/** The text behind [uri], or null when it can't be read or is over 64 KB; never reads more than the cap plus one byte. */
internal fun readCapped(cr: ContentResolver, uri: Uri): String? =
    runCatching { cr.openInputStream(uri)?.use { it.readNBytes(CAP + 1) } }.getOrNull()?.takeIf { it.size <= CAP }?.toString(Charsets.UTF_8)

/** Import prompts waiting for input. Held by [Ui] so rotation keeps them; in memory only, since they hold passwords and keys. */
@Stable
class ImportDraft {
    /** OpenVPN config text and name awaiting a login; null = no prompt. */
    var ovpn by mutableStateOf<Pair<String, String>?>(null)
    var user by mutableStateOf("")
    var pass by mutableStateOf("")
    /** The Shadowsocks link being typed; null = no prompt. */
    var link by mutableStateOf<String?>(null)
    var socksOpen by mutableStateOf(false)
    internal var proxyApp by mutableStateOf<AppInfo?>(null)
    var port by mutableStateOf("")
    var allowUdp by mutableStateOf(false)
    internal var proxyRefill: OwnServer? = null
    /** A valid WireGuard config waiting for its name; null = no prompt. */
    var wg by mutableStateOf<String?>(null)
    /** What the user calls this server, prefilled from the file name. */
    var name by mutableStateOf("")
    /** Why the open prompt's last import failed. */
    var error by mutableStateOf<String?>(null)
    /** The weak encryption the user was warned about; tapping Import again with the same warning imports anyway. */
    var warning by mutableStateOf<String?>(null)
    /** What the file picker was opened for, so a result that arrives after rotation is read the same way. */
    var kind = "any"
    var prefix: String? = null
    /** The own server, restored without keys, that this import gives its config back to; null = a new server. */
    var refill: String? = null
    /** Counts [clear]s: a parse started before the prompt was closed or replaced finds it changed and is dropped. */
    var generation = 0
        private set

    internal fun proxyPort(value: String) {
        if (value != port) allowUdp = false
        port = value
    }

    internal fun proxyChoice(item: AppInfo) {
        val prior = proxyApp
        val saved = proxyRefill?.socks5
        val same = if (prior != null) item.pkg == prior.pkg && item.identity?.accepts(prior.identity?.current) == true
            else saved != null && item.pkg == saved.packageName && item.identity?.accepts(saved.identity) == true
        if (!same) allowUdp = false
        proxyApp = item
    }

    fun clear() { ovpn = null; user = ""; pass = ""; link = null; socksOpen = false; proxyApp = null; proxyRefill = null; port = ""; allowUdp = false; wg = null; name = ""; error = null; warning = null; generation++ }
}

/**
 * Runs [work] (a parse, off the main thread), then [apply] with its outcome, unless the prompt was closed or replaced
 * while it ran: then nothing is saved and nothing is shown. Returns whether [apply] ran.
 */
internal suspend fun <T> ImportDraft.unlessReplaced(work: suspend () -> T, apply: (Result<T>) -> Unit): Boolean {
    val started = generation
    val r = try { Result.success(work()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    if (generation != started) return false
    apply(r)
    return true
}

/** What to show for a config that failed to parse: our own reason, cleaned of anything that looks like a secret. */
internal fun problem(e: Throwable): String = app.unbraid.vpn.Redact.message(e, "Couldn't read that config")

/** Imports a WireGuard/OpenVPN file or a Shadowsocks link as a saved server, also used by route [target] unless null (see [Ui.importServer]). */
@Stable
class Importer(
    private val ui: Ui, private val target: String?, private val context: Context, private val scope: CoroutineScope, private val done: () -> Unit,
) {
    private val draft = ui.importDraft
    var error by mutableStateOf<String?>(null)
    internal var launcher: () -> Unit = {}
    private var busy = false
    private var pending: Job? = null

    /** Opens the file picker. [kind] is "wg", "ovpn" or "any"; [prefix] names the route "<prefix> · <file>". */
    fun file(kind: String = "any", prefix: String? = null, refill: String? = null) {
        error = null; draft.kind = kind; draft.prefix = prefix; draft.refill = refill
        launcher()
    }

    fun shadowsocks(refill: String? = null) {
        error = null; draft.clear(); draft.refill = refill
        draft.name = refill?.let { id -> ui.setup.value.servers.firstOrNull { it.id == id }?.name }.orEmpty()
        draft.link = ""
    }

    fun socks5(refill: String? = null) {
        error = null
        draft.clear()
        draft.refill = refill
        draft.proxyRefill = ui.setup.value.servers.firstOrNull { it.id == refill }
        val previous = draft.proxyRefill?.socks5
        draft.name = previous?.name.orEmpty()
        draft.port = previous?.port?.toString().orEmpty()
        draft.allowUdp = previous?.allowUdp == true && !previous.needsConfirmation && draft.proxyRefill?.keyless != true
        draft.socksOpen = true
        ui.refreshApps()
    }

    private fun importSocks() {
        val app = draft.proxyApp ?: return
        val port = draft.port.toIntOrNull() ?: 0
        val name = named("SOCKS5")
        val user = draft.user
        val password = draft.pass
        val prior = draft.proxyRefill?.socks5
        val allowUdp = draft.allowUdp
        attempt(listOf(password).filter { it.isNotEmpty() }, {
            val signer = verifiedAppChoice(ui.disclosed.value, app.identity?.current) {
                AppIdentity.read(context.packageManager, app.pkg)
            } ?: throw IllegalStateException("The proxy app changed. Choose it again.")
            require(prior?.needsConfirmation != true || !prior.authRequired || user.isNotEmpty()) { "Re-enter the username and password for this proxy." }
            Socks5Profile(name, "127.0.0.1", port, app.pkg, signer.current, user, password, allowUdp = allowUdp).checked()
        }) { profile -> save(OwnServer("", socks5 = profile)) }
    }

    /** Saves [server]: as the config of the server being given its keys back ([ImportDraft.refill]), else as a new one. */
    private fun save(server: OwnServer) = draft.refill?.let { ui.refillServer(it, server, draft.proxyRefill) } ?: ui.importServer(target, server)

    /** The name typed in the open prompt, or [fallback] when it was left empty. */
    private fun named(fallback: String) = Manual.cleanName(draft.name, 40).ifEmpty { fallback }

    internal fun picked(uri: Uri?) {
        if (uri == null) return
        val kind = draft.kind; val prefix = draft.prefix; val refill = draft.refill // as the picker was opened, whatever is opened meanwhile
        scope.launch {
            // A backup picked here is restored, not imported.
            if (withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { app.unbraid.data.Backup.looksLike(it.readNBytes(32)) } }.getOrNull() } == true) {
                ui.backup.restoreFrom(uri); return@launch
            }
            val (text, file) = withContext(Dispatchers.IO) { read(uri) }
            if (text == null) { error = "Couldn't read that file. Config files must be under 64 KB."; return@launch }
            // Giving a restored server its config back keeps the name it has.
            val name = refill?.let { id -> ui.setup.value.servers.firstOrNull { it.id == id }?.name } ?: prefix?.let { "$it · $file" } ?: file
            draft.clear(); draft.name = name; draft.refill = refill
            // A broken config fails here, before the user is asked to name it. Parsing runs off the main thread; a later
            // pick or prompt opened meanwhile wins.
            if (kind == "wg" || (kind == "any" && text.contains("[Interface]", ignoreCase = true))) {
                draft.unlessReplaced({ withContext(Dispatchers.Default) { WgConf.parse(text, name).also(WgConf::requireFullTunnel) } }) { r ->
                    error = r.exceptionOrNull()?.let(::problem)
                    if (error == null) draft.wg = text
                }
            } else {
                // Placeholder login: everything but the login is checked before the prompt asks for a name and login.
                draft.unlessReplaced({ withContext(Dispatchers.Default) { Manual.parseOvpn(text, name, "check", "check") } }) { r ->
                    error = r.exceptionOrNull()?.let(::problem)
                    if (error == null) draft.ovpn = text to name
                }
            }
        }
    }

    private fun read(uri: Uri): Pair<String?, String> {
        val cr = context.contentResolver
        val file = runCatching { cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()
        return readCapped(cr, uri) to (file?.substringBeforeLast('.')?.let { Manual.cleanName(it, 40) }?.ifBlank { null } ?: "Imported")
    }

    /**
     * Parses off the main thread with [parse], then runs [save] (true = saved, null = stay open for a warning); on success closes
     * any prompt and the screen, else shows the reason. [secrets] leave the clipboard only once saved: if the write failed, the
     * clipboard may be the user's only copy. A tap while an import is running is ignored.
     */
    private fun <T> attempt(secrets: List<String>, parse: () -> T, save: (T) -> Boolean?) {
        if (busy) return
        busy = true
        pending = scope.launch {
            try {
                // Closing or replacing the prompt meanwhile drops the result: nothing is saved, no route changes.
                draft.unlessReplaced({ withContext(Dispatchers.Default) { parse() } }) { r ->
                    draft.error = try {
                        val saved = save(r.getOrThrow())
                        if (saved != null) { if (saved) secrets.forEach(ui::clearClipboardIf); draft.clear(); done() }
                        null
                    } catch (e: Exception) { problem(e) }
                }
            } finally { busy = false }
        }
    }

    /** Closes the open prompt and drops an import still being parsed for it. */
    private fun dismiss() { pending?.cancel(); pending = null; draft.clear() }

    /** False the first time [weakness] is seen: the prompt shows it and the next tap goes ahead. */
    private fun accepted(weakness: String?) = weakness == null || weakness == draft.warning || run { draft.warning = weakness; false }

    // The prompt's fields are read here, on the main thread, before the parse runs elsewhere.
    private fun wg(text: String) {
        val n = named("WireGuard")
        attempt(listOf(text), { WgConf.parse(text, n) }) { p -> save(OwnServer("", wg = p)) }
    }

    private fun importOvpn(text: String, name: String) {
        val n = named(name); val user = draft.user.trim(); val pass = draft.pass
        attempt(listOf(text, pass), { Manual.parseOvpn(text, n, user, pass) }) { p ->
            if (accepted(Manual.weakness(p))) save(OwnServer("", ovpn = p)) else null
        }
    }

    private fun importSs(link: String) {
        val typed = Manual.cleanName(draft.name, 40)
        attempt(listOf(link), { Manual.parseSs(link.trim()).let { it.copy(name = typed.ifEmpty { it.name.ifBlank { it.server } }) } }) { p ->
            if (accepted(Manual.weakness(p))) save(OwnServer("", ss = p)) else null
        }
    }

    private fun importLabel() = if (draft.warning != null) "Import anyway" else "Import"

    @Composable
    private fun Problems() {
        draft.warning?.let { Text("Weak security: $it", style = Type.caption, color = Ink.bad) }
        draft.error?.let { Text(it, style = Type.caption, color = Ink.bad) }
    }

    @Composable
    private fun SocksForm() {
        SecureWindow()
        val disclosed by ui.disclosed.collectAsStateWithLifecycle()
        if (!disclosed) {
            AppsDisclosure({ ui.acceptDisclosure() }, { dismiss() })
            return
        }
        val apps by ui.apps.collectAsStateWithLifecycle()
        var picking by remember { mutableStateOf(draft.proxyApp == null) }
        if (picking) {
            var search by remember { mutableStateOf("") }
            val choices = remember(apps, search) {
                val text = search.trim()
                apps.filter { it.identity != null && (it.label.contains(text, true) || it.pkg.contains(text, true)) }
            }
            Prompt("Choose the proxy app", "Continue", { picking = false }, { dismiss() }, enabled = draft.proxyApp != null, secure = true) {
                DialogField(search, { search = it }, "Find an installed app")
                if (choices.isEmpty()) Text("No matching apps. Install the app that runs your proxy, then try again.", style = Type.caption)
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    itemsIndexed(choices, key = { _, item -> item.pkg }) { i, item ->
                        GroupRow(i, choices.size, item.label, item.pkg, selected = item == draft.proxyApp, onClick = {
                            if (draft.proxyApp?.let { it.pkg == item.pkg && item.identity?.accepts(it.identity?.current) == true } != true) { draft.user = ""; draft.pass = "" }
                            draft.proxyChoice(item)
                            draft.error = null
                        })
                    }
                }
            }
            return
        }
        val proxy = draft.proxyApp
        val setup by ui.setup.collectAsStateWithLifecycle()
        val accounts by ui.accounts.collectAsStateWithLifecycle()
        Prompt("Local SOCKS5 proxy", "Save", { importSocks() }, { dismiss() }, enabled = proxy != null && draft.port.isNotEmpty(), secure = true) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Start the proxy on 127.0.0.1 in this phone’s proxy app. Give that app its own Direct route with filtering off in Apps.", style = Type.sub)
                Secondary(proxy?.label ?: "Choose proxy app", { picking = true })
                DialogField(draft.name, { draft.name = it; draft.error = null }, "Name (optional)", "e.g. My proxy")
                DialogField(draft.port, { draft.proxyPort(it); draft.error = null }, "Port", "e.g. 1080")
                if (draft.proxyRefill != null) Text("Saving updates every route using this proxy. Re-enter the login if the proxy requires one.", style = Type.caption)
                DialogField(draft.user, { draft.user = it; draft.error = null }, "Username (if required)")
                DialogField(draft.pass, { draft.pass = it; draft.error = null }, "Password (if required)", secret = true)
                Row(Modifier.fillMaxWidth().toggleable(draft.allowUdp, role = Role.Switch) { draft.allowUdp = it }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Allow UDP", style = Type.body)
                        Text("Only turn this on if you trust other apps on this phone. unbraid checks which app owns the proxy’s separate UDP port. Another app could take it over between a check and delivery, receive your traffic or send fake replies.", style = Type.caption)
                    }
                    Toggle(draft.allowUdp)
                }
                if (setup.untouched && accounts.isEmpty()) Text("On a new setup, also choose a working default in Routes for your other apps before connecting.", style = Type.caption)
                Text("Assign individual apps to this route. unbraid checks the proxy app before sending TCP. UDP is blocked unless you allow it above. Android’s “Block connections without VPN” keeps this route offline. Website and home-network rules still apply.", style = Type.caption)
                Problems()
            }
        }
    }

    /** Both prompts stay open on a bad input and show why, keeping what was typed. */
    @Composable
    fun Dialogs() {
        if (draft.socksOpen) SocksForm()
        draft.wg?.let { text ->
            Prompt("Name this server", "Import", { wg(text) }, { dismiss() }) {
                Text("Shown on your routes and in Apps. You can rename it later.", style = Type.sub)
                DialogField(draft.name, { draft.name = it; draft.error = null }, "Name", "e.g. Home VPS")
                draft.error?.let { Text(it, style = Type.caption, color = Ink.bad) }
            }
        }
        draft.ovpn?.let { (text, name) ->
            SecureWindow()
            Prompt("OpenVPN server", importLabel(), { importOvpn(text, name) }, { dismiss() }, danger = draft.warning != null, secure = true) {
                DialogField(draft.name, { draft.name = it; draft.error = null }, "Name", "e.g. Office")
                Text("Leave the login empty if the config has its own.", style = Type.sub)
                DialogField(draft.user, { draft.user = it; draft.error = null }, "Username")
                DialogField(draft.pass, { draft.pass = it; draft.error = null }, "Password", secret = true)
                Problems()
            }
        }
        draft.link?.let { link ->
            SecureWindow()
            Prompt("Shadowsocks link", importLabel(), { importSs(link) }, { dismiss() }, enabled = link.isNotBlank(), danger = draft.warning != null, secure = true) {
                DialogField(link, { draft.link = it; draft.error = null; draft.warning = null }, "Link", "ss://…", secret = true)
                DialogField(draft.name, { draft.name = it; draft.error = null }, "Name (optional)", "e.g. Outline")
                Problems()
            }
        }
    }
}

@Composable
fun rememberImporter(ui: Ui, target: String?, done: () -> Unit): Importer {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(done)
    val importer = remember(target) { Importer(ui, target, context, scope) { latest() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), importer::picked)
    importer.launcher = { picker.launch(arrayOf("*/*")) }
    return importer
}
