package app.unbraid.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.unbraid.BuildConfig
import app.unbraid.Unbraid
import app.unbraid.data.Backup
import app.unbraid.data.PREVIOUS
import app.unbraid.data.Providers
import app.unbraid.data.Setup
import app.unbraid.data.restoredFrom
import app.unbraid.vpn.Profiles
import app.unbraid.vpn.UnbraidVpn
import app.unbraid.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val KEYS_OFF = "Off: own servers are saved without keys or passwords, and home WireGuard is left out. On another phone, add each server's config again; until then its apps stay offline."
private const val KEYS_ON = "The file holds your own servers' keys and passwords, so it needs a strong passphrase."
private const val KEYS_NONE = "You have no own servers or home WireGuard, so there are no keys or passwords to include."
private const val KEEP_NO_ROOM = "There's no room for another profile. Turn off keeping your current setup, or cancel."

/** What a backup file holds, for the captions. */
const val BACKUP_HOLDS = "Your routes, app choices, favourites, profiles, own servers and your settings. Keys and passwords of your own servers and home WireGuard only if you choose."
const val BACKUP_LEAVES = "Provider sign-ins are never saved in a backup: after restoring, sign in to each provider again. Tailscale is left out too. Statistics stay on this phone."

/** The keys switch for a setup: whether it can be turned on (only with something to include), and its caption. */
fun keysSwitch(secrets: Boolean, on: Boolean): Pair<Boolean, String> = secrets to when { !secrets -> KEYS_NONE; on -> KEYS_ON; else -> KEYS_OFF }

/**
 * Back up and restore, held by [Ui] so a rotation keeps every step. In memory only: the passphrase, the file and what
 * it opens to never reach the disk, the clipboard or the log.
 */
@Stable
class BackupFlow(private val ui: Ui) {
    /** Null = closed; "save" = asking a passphrase to save; "open" = asking the file's passphrase; "confirm"; "done". */
    var step by mutableStateOf<String?>(null)
        private set
    var pass by mutableStateOf("")
    var pass2 by mutableStateOf("")
    var keep by mutableStateOf(true)
    /** Save own servers' keys and passwords too; off unless the user turns it on. */
    var keys by mutableStateOf(false)
    var busy by mutableStateOf(false)
        private set
    /** Why the last try failed (the sheet stays open), or what happened ([step] "done"). */
    var message by mutableStateOf<String?>(null)
        private set
    var ok by mutableStateOf(false)
        private set
    private var file: ByteArray? = null
    var opened by mutableStateOf<Backup.Contents?>(null)
        private set
    /** Set by [BackupHost]: the document pickers. */
    internal var pickSave: (String) -> Unit = {}
    internal var pickOpen: () -> Unit = {}
    private val app get() = ui.getApplication<android.app.Application>()

    fun close() { if (!busy) { step = null; pass = ""; pass2 = ""; file = null; opened = null; message = null; keep = true; keys = false } }

    fun startSave() { close(); step = "save" }

    /** The passphrases match: choose where the file goes. */
    fun chooseFile() {
        message = null
        pickSave("unbraid-backup-${java.time.LocalDate.now()}.unbraid")
    }

    /** The picker's answer for saving: sealed and written, or nothing left behind. */
    fun saveTo(uri: Uri?) {
        if (uri == null) return
        if (step != "save" || pass.isEmpty()) {
            // The app was closed while the picker was open: the passphrase is gone (it is never saved), so the empty file goes.
            ui.viewModelScope.launch(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) } }
            finish(false, "The backup wasn't saved because unbraid was closed meanwhile. Start it again.")
            return
        }
        val p = pass
        val setup = Unbraid.store.setup.value
        val withKeys = keys
        val left = !withKeys && Backup.hasSecrets(setup)
        busy = true
        ui.viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                runCatching {
                    val plain = Backup.export(setup, BuildConfig.VERSION_NAME, System.currentTimeMillis(), withKeys)
                    try { Backup.seal(plain, p) } finally { plain.fill(0) }
                }
            }
            val wrote = r.getOrNull()?.let { b -> withContext(Dispatchers.IO) { runCatching { app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(b) } }.isSuccess } } == true
            if (!wrote) withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) } }
            busy = false
            finish(wrote, if (wrote) "Backup saved${if (left) " without server keys and passwords" else ""}. Keep the file and your passphrase: without the passphrase nobody can open it, not even you."
                else (r.exceptionOrNull() as? Backup.Failed)?.message ?: "Couldn't save the backup there. Try another place.")
        }
    }

    fun startRestore() { close(); pickOpen() }

    /** A file picked to restore (from here, or the import picker): what can be said without the passphrase is said now. */
    fun restoreFrom(uri: Uri?) {
        if (uri == null) return
        close()
        busy = true
        ui.viewModelScope.launch {
            val b = withContext(Dispatchers.IO) { runCatching { app.contentResolver.openInputStream(uri)?.use { it.readNBytes(Backup.MAX + 1) } }.getOrNull() }
            busy = false
            val why = if (b == null) "Couldn't read that file." else Backup.precheck(b)?.text
            if (why != null) finish(false, why) else { file = b; step = "open" }
        }
    }

    /** Opens the file with [pass] and checks all of it; nothing changes yet. */
    fun open() {
        val b = file ?: return
        val p = pass
        busy = true; message = null
        ui.viewModelScope.launch {
            val r = withContext(Dispatchers.Default) { runCatching { Backup.open(b, p).let { plain -> try { Backup.read(plain) } finally { plain.fill(0) } } } }
            busy = false
            r.onSuccess { opened = it; pass = ""; step = "confirm"; keep = canKeep(Unbraid.store.setup.value) }
                .onFailure { e -> message = (e as? Backup.Failed)?.message ?: Backup.Reason.Damaged.text; if ((e as? Backup.Failed)?.reason != Backup.Reason.Passphrase) { step = "done"; ok = false } }
        }
    }

    /** Whether the setup here can be kept as a profile beside [opened]'s. */
    fun canKeep(here: Setup) = opened?.let { !here.untouched && here.restoredFrom(it.setup, PREVIOUS, 0, it.keys) != null } == true

    /** Own servers [opened] leaves without their config here: the ones this phone doesn't already have. */
    fun needKeys(here: Setup) = opened?.let { c -> here.restoredFrom(c.setup, null, 0, c.keys)?.servers?.count { it.keyless } } ?: 0

    /** Replaces the setup with [opened]'s, all at once; the VPN, if on, reconnects with it. */
    fun apply() {
        val c = opened ?: return
        var target: Setup? = null
        val keepAs = PREVIOUS.takeIf { keep }
        if (Unbraid.store.setup.value.restoredFrom(c.setup, keepAs, 0, c.keys) == null) { finish(false, KEEP_NO_ROOM); return }
        // Counted first: the running engine's setup is gone once the store changes, so a refused reload must stop the VPN.
        UnbraidVpn.restores.incrementAndGet()
        val saved = Unbraid.store.update { s -> s.restoredFrom(c.setup, keepAs, System.currentTimeMillis(), c.keys)?.also { target = it } ?: s }
        val t = target
        if (t == null || Unbraid.store.setup.value != t) {
            finish(false, if (t == null) KEEP_NO_ROOM else "Couldn't restore: your settings are busy. Try again.")
            return
        }
        ui.restored()
        Profiles.refresh(app)
        val kept = t.profiles.size > c.setup.profiles.size
        finish(true, listOfNotNull(
            if (saved) "Settings restored." else "Settings restored, but they couldn't be saved yet. Keep unbraid open and try again from the notice.",
            if (kept) "Your current setup is kept as the profile ${t.profiles.last().name}." else null,
            t.servers.filter { it.keyless }.takeIf { it.isNotEmpty() }?.let { "Add the config again for ${it.joinToString { s -> s.name }} in Settings, Providers & servers. Until then, ${if (it.size == 1) "its" else "their"} apps stay offline." },
            signIns(t)?.let { "Sign in to $it to use their routes." },
            "unbraid is reconnecting with them.".takeIf { UnbraidVpn.state.value.phase == VpnState.Phase.On },
        ).joinToString(" "))
    }

    /** Providers [s]'s routes use that this phone isn't signed in to, by name; null when none. */
    fun signIns(s: Setup) = s.routes.filter { !it.imported }.map { it.loc.provider }.distinct().filter { it !in Unbraid.store.accounts.value }
        .map { Providers.get(it)?.name ?: it }.takeIf { it.isNotEmpty() }?.joinToString(", ")

    private fun finish(success: Boolean, text: String) { pass = ""; pass2 = ""; file = null; opened = null; ok = success; message = text; step = "done" }
}

/** The pickers and the sheet, wherever the app is: a backup can also be picked from the import of a config file. */
@Composable
fun BackupHost(ui: Ui) {
    val flow = ui.backup
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { flow.saveTo(it) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { flow.restoreFrom(it) }
    flow.pickSave = { name -> runCatching { save.launch(name) } }
    flow.pickOpen = { runCatching { open.launch(arrayOf("*/*")) } }
    if (flow.step != null) BackupSheet(ui, flow)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupSheet(ui: Ui, flow: BackupFlow) {
    val step = flow.step ?: return
    ModalBottomSheet(
        { flow.close() }, sheetState = fullSheet(), containerColor = Ink.surface, contentColor = Ink.text,
        // A passphrase and an opened setup stay out of screenshots and the recents preview.
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        SecureWindow()
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (step) {
                "save" -> {
                    Text("Back up settings", style = Type.title)
                    Text(BACKUP_HOLDS, style = Type.sub)
                    val here by ui.setup.collectAsStateWithLifecycle()
                    val secrets = Backup.hasSecrets(here)
                    val (canInclude, keysText) = keysSwitch(secrets, flow.keys)
                    Switch("Include server keys and passwords", keysText, flow.keys && canInclude, canInclude) { flow.keys = it }
                    Text(BACKUP_LEAVES, style = Type.caption, color = Ink.dim)
                    DialogField(flow.pass, { flow.pass = it }, "Passphrase", secret = true)
                    DialogField(flow.pass2, { flow.pass2 = it }, "Passphrase again", secret = true)
                    val weak = flow.pass.isNotEmpty() && Backup.strength(flow.pass).first == 0
                    if (flow.pass.isNotEmpty()) Backup.strength(flow.pass).let { (n, text) -> Text(text, style = Type.caption, color = if (n == 0) Ink.bad else Ink.dim) }
                    val tooWeak = weak && flow.keys && secrets
                    if (tooWeak) Text("With keys and passwords included, choose a stronger passphrase.", style = Type.caption, color = Ink.bad)
                    val differ = flow.pass2.isNotEmpty() && flow.pass != flow.pass2
                    if (differ) Text("The two passphrases are different.", style = Type.caption, color = Ink.bad)
                    Text("You need this passphrase to restore. unbraid can't recover it.", style = Type.caption, color = Ink.dim)
                    if (flow.busy) Text("Saving the backup. This takes a few seconds.", style = Type.caption, color = Ink.dim)
                    flow.message?.let { Text(it, style = Type.caption, color = Ink.bad) }
                    Buttons(flow, "Save backup", enabled = flow.pass.isNotEmpty() && flow.pass == flow.pass2 && !tooWeak) { flow.chooseFile() }
                }
                "open" -> {
                    Text("Restore settings", style = Type.title)
                    Text("Type the passphrase this backup was saved with. Nothing changes until you confirm.", style = Type.sub)
                    DialogField(flow.pass, { flow.pass = it }, "Passphrase", secret = true)
                    if (flow.busy) Text("Opening the backup. This takes a few seconds.", style = Type.caption, color = Ink.dim)
                    flow.message?.let { Text(it, style = Type.caption, color = Ink.bad) }
                    Buttons(flow, "Open", enabled = flow.pass.isNotEmpty()) { flow.open() }
                }
                "confirm" -> Confirm(ui, flow)
                else -> {
                    Text(if (flow.ok) "Done" else "Not done", style = Type.title)
                    Text(flow.message.orEmpty(), style = Type.sub, color = if (flow.ok) Ink.text else Ink.bad)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Primary("Close", { flow.close() }) }
                }
            }
        }
    }
}

@Composable
private fun Confirm(ui: Ui, flow: BackupFlow) {
    val c = flow.opened ?: return
    val here by ui.setup.collectAsStateWithLifecycle()
    val vpn by ui.vpn.collectAsStateWithLifecycle()
    val b = c.setup
    val date = java.time.Instant.ofEpochMilli(c.created).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.UK))
    val canKeep = flow.canKeep(here)
    Text("Restore this backup?", style = Type.title)
    Text("Saved on $date${c.app.takeIf { it.isNotEmpty() }?.let { " by unbraid $it" }.orEmpty()}: ${count(b.routes.size, "route")}, " +
        "${count(b.apps.size, "app choice")}, ${count(b.profiles.size, "profile")}, ${count(b.servers.size, "own server")}.", style = Type.sub)
    if (!here.untouched) Text("It replaces what is on this phone now: ${count(here.routes.size, "route")}, ${count(here.apps.size, "app choice")}, " +
        "${count(here.profiles.size, "profile")}, ${count(here.servers.size, "own server")}, your favourites and settings.", style = Type.sub)
    Text("Your provider sign-ins and Tailscale stay as they are." + (flow.signIns(b)?.let { " Routes on $it stay blocked until you sign in." } ?: ""), style = Type.caption, color = Ink.dim)
    val missing = flow.needKeys(here)
    if (missing > 0) Text("Saved without server keys: ${count(missing, "own server")} will need ${if (missing == 1) "its" else "their"} config added again. Until then, ${if (missing == 1) "its" else "their"} apps stay offline.", style = Type.caption, color = Ink.bad)
    if (!c.keys && here.home.wg != null) Text("Your home WireGuard stays as it is on this phone.", style = Type.caption, color = Ink.dim)
    if (vpn.phase == VpnState.Phase.On) Text("unbraid is connected. It reconnects with the restored setup, and apps may be offline for a few seconds.", style = Type.caption, color = Ink.dim)
    if (canKeep) Switch("Keep my current setup", "Saved as the profile $PREVIOUS, to switch back later. Your other profiles here are replaced.", flow.keep) { flow.keep = it }
    Buttons(flow, "Restore") { flow.apply() }
}

/** A switch row with what it means underneath, as on the confirm sheet. */
@Composable
private fun Switch(title: String, sub: String, on: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) = Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.surfaceHigh).toggleable(on, enabled = enabled, role = Role.Switch, onValueChange = set).padding(16.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    Column(Modifier.weight(1f)) {
        Text(title, style = Type.body, color = if (enabled) Ink.text else Ink.dim)
        Text(sub, style = Type.caption, color = Ink.dim)
    }
    Toggle(on, enabled)
}

private fun count(n: Int, what: String) = "$n ${if (n == 1) what else what + "s"}"

@Composable
private fun Buttons(flow: BackupFlow, label: String, enabled: Boolean = true, go: () -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        Secondary("Cancel", { flow.close() }, color = Ink.dim, enabled = !flow.busy)
        Primary(label, go, Modifier.widthIn(min = 120.dp), enabled = enabled && !flow.busy, busy = flow.busy)
    }
