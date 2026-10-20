package app.unbraid.ui

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    fun clear() { ovpn = null; user = ""; pass = ""; link = null; wg = null; name = ""; error = null; warning = null }
}

/** Imports a WireGuard/OpenVPN file or a Shadowsocks link as a saved server, also used by route [target] unless null (see [Ui.importServer]). */
@Stable
class Importer(
    private val ui: Ui, private val target: String?, private val context: Context, private val scope: CoroutineScope, private val done: () -> Unit,
) {
    private val draft = ui.importDraft
    var error by mutableStateOf<String?>(null)
    internal var launcher: () -> Unit = {}

    /** Opens the file picker. [kind] is "wg", "ovpn" or "any"; [prefix] names the route "<prefix> · <file>". */
    fun file(kind: String = "any", prefix: String? = null) {
        error = null; draft.kind = kind; draft.prefix = prefix
        launcher()
    }

    fun shadowsocks() { error = null; draft.clear(); draft.link = "" }

    /** The name typed in the open prompt, or [fallback] when it was left empty. */
    private fun named(fallback: String) = draft.name.trim().take(40).ifEmpty { fallback }

    internal fun picked(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            val (text, file) = withContext(Dispatchers.IO) { read(uri) }
            if (text == null) { error = "Couldn't read that file. Config files must be under 64 KB."; return@launch }
            val name = draft.prefix?.let { "$it · $file" } ?: file
            draft.clear(); draft.name = name
            // A broken config fails here, before the user is asked to name it.
            if (draft.kind == "wg" || (draft.kind == "any" && text.contains("[Interface]", ignoreCase = true))) {
                error = runCatching { WgConf.parse(text, name).also(WgConf::requireFullTunnel) }.exceptionOrNull()?.let { it.message ?: "Couldn't read that config" }
                if (error == null) draft.wg = text
            } else {
                // Placeholder login: everything but the login is checked before the prompt asks for a name and login.
                error = runCatching { Manual.parseOvpn(text, name, "check", "check") }.exceptionOrNull()?.let { it.message ?: "Couldn't read that config" }
                if (error == null) draft.ovpn = text to name
            }
        }
    }

    private fun read(uri: Uri): Pair<String?, String> {
        val cr = context.contentResolver
        val file = runCatching { cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()
        return readCapped(cr, uri) to (file?.substringBeforeLast('.')?.take(40)?.ifBlank { null } ?: "Imported")
    }

    /**
     * Runs [block] (true = saved, null = stay open for a warning); on success closes any prompt and the screen, else returns
     * the reason. [secrets] leave the clipboard only once saved: if the write failed, the clipboard may be the user's only copy.
     */
    private fun attempt(secrets: List<String>, block: () -> Boolean?): String? = try {
        val saved = block() ?: return null
        if (saved) secrets.forEach(ui::clearClipboardIf)
        draft.clear(); done(); null
    } catch (e: Exception) { e.message?.ifBlank { null } ?: "Couldn't read that config" }

    /** False the first time [weakness] is seen: the prompt shows it and the next tap goes ahead. */
    private fun accepted(weakness: String?) = weakness == null || weakness == draft.warning || run { draft.warning = weakness; false }

    private fun wg(text: String) {
        draft.error = attempt(listOf(text)) { val p = WgConf.parse(text, named("WireGuard")); ui.importServer(target, OwnServer("", wg = p)) }
    }

    private fun importOvpn(text: String, name: String) {
        draft.error = attempt(listOf(text, draft.pass)) {
            val p = Manual.parseOvpn(text, named(name), draft.user.trim(), draft.pass)
            if (accepted(Manual.weakness(p))) ui.importServer(target, OwnServer("", ovpn = p)) else null
        }
    }

    private fun importSs(link: String) {
        draft.error = attempt(listOf(link)) {
            val p = Manual.parseSs(link.trim()).let { it.copy(name = named(it.name.ifBlank { it.server })) }
            if (accepted(Manual.weakness(p))) ui.importServer(target, OwnServer("", ss = p)) else null
        }
    }

    private fun importLabel() = if (draft.warning != null) "Import anyway" else "Import"

    @Composable
    private fun Problems() {
        draft.warning?.let { Text("Weak security: $it", style = Type.caption, color = Ink.bad) }
        draft.error?.let { Text(it, style = Type.caption, color = Ink.bad) }
    }

    /** Both prompts stay open on a bad input and show why, keeping what was typed. */
    @Composable
    fun Dialogs() {
        draft.wg?.let { text ->
            Prompt("Name this server", "Import", { wg(text) }, { draft.clear() }) {
                Text("Shown on your routes and in Apps. You can rename it later.", style = Type.sub)
                DialogField(draft.name, { draft.name = it; draft.error = null }, "Name", "e.g. Home VPS")
                draft.error?.let { Text(it, style = Type.caption, color = Ink.bad) }
            }
        }
        draft.ovpn?.let { (text, name) ->
            SecureWindow()
            Prompt("OpenVPN server", importLabel(), { importOvpn(text, name) }, { draft.clear() }, danger = draft.warning != null, secure = true) {
                DialogField(draft.name, { draft.name = it; draft.error = null }, "Name", "e.g. Office")
                Text("Leave the login empty if the config has its own.", style = Type.sub)
                DialogField(draft.user, { draft.user = it; draft.error = null }, "Username")
                DialogField(draft.pass, { draft.pass = it; draft.error = null }, "Password", secret = true)
                Problems()
            }
        }
        draft.link?.let { link ->
            SecureWindow()
            Prompt("Shadowsocks link", importLabel(), { importSs(link) }, { draft.clear() }, enabled = link.isNotBlank(), danger = draft.warning != null, secure = true) {
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
