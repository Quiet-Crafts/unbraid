@file:OptIn(app.unbraid.data.SavedSites::class)
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import app.unbraid.data.RestoreTraffic
import app.unbraid.data.Setup
import app.unbraid.data.restoredFrom
import app.unbraid.vpn.Profiles
import app.unbraid.vpn.UnbraidVpn
import app.unbraid.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val KEYS_OFF = "Off: own servers are saved without keys or passwords, and home WireGuard is left out. On another phone, add each server's config again; until then its apps stay offline."
private const val KEYS_ON = "The file holds your own servers' keys and passwords, so it needs a strong passphrase."
private const val KEYS_NONE = "You have no own servers or home WireGuard, so there are no keys or passwords to include."
private const val KEEP_NO_ROOM = "There's no room for another profile. Turn off keeping your current setup, or cancel."

/** What a backup file holds, for the captions. */
const val BACKUP_HOLDS = "Your routes, app and website choices, favourites, profiles, own servers and your settings. Keys and passwords of your own servers and home WireGuard only if you choose."
const val BACKUP_LEAVES = "Provider sign-ins are never saved in a backup: after restoring, sign in to each provider again. Your Tailscale sign-in is left out too. Statistics stay on this phone."

/**
 * Why passphrase [pass] (typed again as [again]) can't save yet: null when it can, "" while there is nothing to say.
 * Every passphrase needs [Backup.MIN_PASS] characters; one guarding server [keys] must be rated hard.
 */
fun passProblem(pass: String, again: String, keys: Boolean): String? = when {
    keys && pass.isNotEmpty() && Backup.strength(pass).first < 2 -> "With keys and passwords included, choose a passphrase that is harder to guess."
    pass.isEmpty() -> ""
    app.unbraid.data.graphemes(pass) < Backup.MIN_PASS -> "A passphrase needs at least ${Backup.MIN_PASS} characters."
    again.isNotEmpty() && again != pass -> "The two passphrases are different."
    again.isEmpty() -> ""
    else -> null
}

/** The keys switch for a setup: whether it can be turned on (only with something to include), and its caption. */
fun keysSwitch(secrets: Boolean, on: Boolean): Pair<Boolean, String> = secrets to when { !secrets -> KEYS_NONE; on -> KEYS_ON; else -> KEYS_OFF }

/**
 * When Restore may be tapped, apart from Android so it can be tested: a check counts only for the very backup it was
 * made for, the setup here and the keep choice; weak servers are accepted for one backup only. A newer check, another
 * backup or another choice drops what came before.
 */
class RestoreGate {
    /** What the confirm sheet says of a backup: its sentences, its weak servers, whether the setup here can be kept, and own servers left without config. */
    class Result(val notes: List<String>, val weak: List<String>, val keepable: Boolean, val missing: Int)
    /** One check asked: of [backup] over [here], keeping the setup here as a profile or not. */
    class Ask(val here: Setup, val backup: Backup.Contents, val keep: Boolean)

    private var backup: Backup.Contents? = null
    private var asked: Ask? = null
    var result: Result? = null
        private set

    /** Backup [c] opened (or none): nothing checked or accepted for another one carries over. */
    fun open(c: Backup.Contents?) { backup = c; asked = null; result = null; acceptWeak = false }

    /** The check to start for [here] and [keep]; null when one for them is under way or in. */
    fun ask(here: Setup, keep: Boolean): Ask? {
        val c = backup ?: return null
        if (asked?.let { it.here == here && it.backup === c && it.keep == keep } == true) return null
        return Ask(here, c, keep).also { asked = it; result = null }
    }

    /** [r] for check [a]; dropped unless [a] is still the one asked. */
    fun answer(a: Ask, r: Result) { if (asked === a) result = r }

    /** Weak servers accepted, for the backup open now only: [open] clears it. */
    var acceptWeak = false

    fun ready(here: Setup, keep: Boolean): Boolean {
        val r = result ?: return false
        return asked?.let { it.here == here && it.backup === backup && it.keep == keep } == true && (r.weak.isEmpty() || acceptWeak)
    }

    companion object {
        /** Works [a] out: slow for a large setup, never on the main thread. [kin]: the apps sharing a user id with an app; [tick] may throw to stop it. */
        fun check(a: Ask, kin: (String) -> Set<String> = { emptySet() }, tick: () -> Unit = {}): Result {
            val c = a.backup
            val plain = a.here.restoredFrom(c.setup, null, 0, c.keys)!!
            val keepable = !a.here.untouched && a.here.restoredFrom(c.setup, PREVIOUS, 0, c.keys) != null
            val next = if (a.keep && keepable) a.here.restoredFrom(c.setup, PREVIOUS, 0, c.keys)!! else plain
            return Result(RestoreTraffic.notes(a.here, next, kin, tick), Backup.weak(plain, a.here), keepable, plain.servers.count { it.keyless })
        }
    }
}

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
    private var judging: Job? = null
    private val gate = RestoreGate()
    /** Bumped on every change of [gate], so the sheet follows it. */
    private var seen by mutableIntStateOf(0)
    /** What restoring [opened] makes weaker ([RestoreTraffic.notes]); null while it is worked out. */
    val notes: List<String>? get() = seen.let { gate.result?.notes }
    /** Servers in [opened] an import would warn about, not already on this phone ([Backup.weak]); worked out with [notes]. */
    val weak: List<String> get() = seen.let { gate.result?.weak.orEmpty() }
    /** Whether the setup here can be kept as a profile beside [opened]'s; null while it is worked out. */
    val keepable: Boolean? get() = seen.let { gate.result?.keepable }
    /** Own servers [opened] leaves without their config here: the ones this phone doesn't already have. */
    val missing: Int get() = seen.let { gate.result?.missing ?: 0 }
    /** The user accepts the weak security of servers in [opened] ([Backup.weak]): Restore waits for it. */
    var acceptWeak: Boolean
        get() = seen.let { gate.acceptWeak }
        set(v) { gate.acceptWeak = v; seen++ }
    /** Set by [BackupHost]: the document pickers. */
    internal var pickSave: (String) -> Unit = {}
    internal var pickOpen: () -> Unit = {}
    private val app get() = ui.getApplication<android.app.Application>()

    fun close() { if (!busy) { step = null; pass = ""; pass2 = ""; file = null; opened(null); message = null; keep = true; keys = false } }

    /** A new backup shown (or none): nothing worked out or accepted for another one carries over. */
    private fun opened(c: Backup.Contents?) { opened = c; judging?.cancel(); judging = null; gate.open(c); seen++ }

    /**
     * Works out, away from the main thread, what restoring [opened] over [here] with the keep choice makes weaker, and
     * what the confirm sheet says of it; a result for a setup, backup or choice that changed meanwhile is dropped. Apps
     * that share their user id with another are told to the walk, as Android and the engine decide by user id.
     */
    fun judge(here: Setup) {
        val ask = gate.ask(here, keep) ?: return
        seen++
        judging?.cancel()
        val pm = app.packageManager
        judging = ui.viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                // The installed apps by user id, read once.
                val all = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
                val uid = all.associate { it.packageName to it.uid }
                val byUid = all.groupBy({ it.uid }, { it.packageName })
                // Stopped as soon as the sheet is closed or the check asked again.
                RestoreGate.check(ask, { pkg -> uid[pkg]?.let(byUid::get).orEmpty().toSet() }) { ensureActive() }
            }
            gate.answer(ask, r); seen++
        }
    }

    /** Restore may go: the check is in for this backup, this setup and this keep choice, and weak servers are accepted. */
    fun ready(here: Setup) = seen.let { gate.ready(here, keep) }

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
                    // Read back first: a backup that couldn't be restored is said now, while the setup can still be mended.
                    try { Backup.readBack(plain); Backup.seal(plain, p) } finally { plain.fill(0) }
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
            r.onSuccess { opened(it); pass = ""; step = "confirm"; keep = true }
                .onFailure { e -> message = (e as? Backup.Failed)?.message ?: Backup.Reason.Damaged.text; if ((e as? Backup.Failed)?.reason != Backup.Reason.Passphrase) { step = "done"; ok = false } }
        }
    }

    /** Replaces the setup with [opened]'s, all at once; the VPN, if on, reconnects with it. */
    fun apply() {
        val c = opened ?: return
        val here = Unbraid.store.setup.value
        // Only over the setup and keep choice the sheet's check was made for, and with weak servers accepted.
        if (!ready(here)) return
        var target: Setup? = null
        val keepAs = PREVIOUS.takeIf { keep && keepable == true }
        // Counted first: the running engine's setup is gone once the store changes, so a refused reload must stop the VPN.
        UnbraidVpn.restores.incrementAndGet()
        var moved = false
        val saved = Unbraid.store.update { s -> if (s !== here) s.also { moved = true } else s.restoredFrom(c.setup, keepAs, System.currentTimeMillis(), c.keys)?.also { target = it } ?: s }
        val t = target
        if (t == null || Unbraid.store.setup.value != t) {
            finish(false, if (t == null && !moved) KEEP_NO_ROOM else "Couldn't restore: your settings are busy. Try again.")
            return
        }
        ui.restored()
        Profiles.refresh(app)
        val kept = t.profiles.size > c.setup.profiles.size
        finish(true, listOfNotNull(
            if (saved) "Settings restored." else "Settings restored, but they couldn't be saved yet. Keep unbraid open and try again from the notice.",
            if (kept) "Your current setup is kept as the profile ${t.profiles.last().name}." else null,
            t.servers.filter { it.keyless }.takeIf { it.isNotEmpty() }?.let { "Add the config again for ${it.joinToString { s -> Backup.safe(s.name, 60) }} in Settings, Providers & servers. Until then, ${if (it.size == 1) "its" else "their"} apps stay offline." },
            signIns(t)?.let { "Sign in to $it to use their routes." },
            "unbraid is reconnecting with them.".takeIf { UnbraidVpn.state.value.phase == VpnState.Phase.On },
        ).joinToString(" "))
    }

    /** Providers [s]'s routes use that this phone isn't signed in to, by name; null when none. */
    fun signIns(s: Setup) = s.routes.filter { !it.imported }.map { it.loc.provider }.distinct().filter { it !in Unbraid.store.accounts.value }
        .map { Providers.get(it)?.name ?: it }.takeIf { it.isNotEmpty() }?.joinToString(", ")

    private fun finish(success: Boolean, text: String) { pass = ""; pass2 = ""; file = null; opened(null); ok = success; message = text; step = "done" }
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
                    val problem = passProblem(flow.pass, flow.pass2, flow.keys && secrets)
                    if (flow.pass.isNotEmpty()) Backup.strength(flow.pass).let { (n, text) -> Text(text, style = Type.caption, color = if (n == 0) Ink.bad else Ink.dim) }
                    problem?.takeIf { it.isNotEmpty() }?.let { Text(it, style = Type.caption, color = Ink.bad) }
                    Text("You need this passphrase to restore. unbraid can't recover it.", style = Type.caption, color = Ink.dim)
                    if (flow.busy) Text("Saving the backup. This takes a few seconds.", style = Type.caption, color = Ink.dim)
                    flow.message?.let { Text(it, style = Type.caption, color = Ink.bad) }
                    Buttons(flow, "Save backup", enabled = problem == null) { flow.chooseFile() }
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
    val canKeep = flow.keepable == true
    Text("Restore this backup?", style = Type.title)
    Text("Saved on $date${c.app.takeIf { it.isNotEmpty() }?.let { " by unbraid $it" }.orEmpty()}: ${count(b.routes.size, "route")}, " +
        "${count(b.apps.size, "app choice")}, ${websites(b)}${count(b.profiles.size, "profile")}, ${count(b.servers.size, "own server")}.", style = Type.sub)
    // With the setup in use kept, its routes and own servers stay: what goes is the rest.
    if (!here.untouched) Text(if (canKeep && flow.keep) "It replaces your profiles, favourites and settings on this phone." else "It replaces what is on this phone now: ${count(here.routes.size, "route")}, " +
        "${count(here.apps.size, "app choice")}, ${websites(here)}${count(here.profiles.size, "profile")}, ${count(here.servers.size, "own server")}, your favourites and settings.", style = Type.sub)
    Text("Your provider sign-ins and Tailscale stay as they are." + (flow.signIns(b)?.let { " Routes on $it stay blocked until you sign in." } ?: ""), style = Type.caption, color = Ink.dim)
    val missing = flow.missing
    if (missing > 0) Text("Saved without server keys: ${count(missing, "own server")} will need ${if (missing == 1) "its" else "their"} config added again. Until then, ${if (missing == 1) "its" else "their"} apps stay offline.", style = Type.caption, color = Ink.bad)
    if (here.home.wg != null && !(c.keys && b.home.wg == here.home.wg)) Text(homeNote(c.keys, b.home.wg != null), style = Type.caption, color = if (c.keys && b.home.wg == null) Ink.bad else Ink.dim)
    // What gets less protected, worked out for this backup, this setup and the keep choice; Restore waits for it.
    LaunchedEffect(c, here, flow.keep) { flow.judge(here) }
    val notes = flow.notes
    if (notes == null) Text("Checking what the restore changes…", style = Type.caption, color = Ink.dim)
    else if (notes.isNotEmpty()) Text(notes.joinToString("\n"), style = Type.caption, color = Ink.bad)
    if (notes != null && flow.weak.isNotEmpty()) Switch(WEAK_TITLE, weakCaption(flow.weak), flow.acceptWeak) { flow.acceptWeak = it }
    if (vpn.phase == VpnState.Phase.On) Text("unbraid is connected. It reconnects with the restored setup, and apps may be offline for a few seconds.", style = Type.caption, color = Ink.dim)
    if (canKeep) Switch("Keep my current setup", "Saved as the profile $PREVIOUS, to switch back later. Your other profiles here are replaced.", flow.keep) { flow.keep = it }
    Buttons(flow, "Restore", enabled = flow.ready(here)) { flow.apply() }
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

/** The switch Restore waits for when the backup holds a server an import would warn about. */
const val WEAK_TITLE = "Accept weak security to restore"

fun weakCaption(weak: List<String>) = (weak + "Someone between you and ${if (weak.size == 1) "this server" else "these servers"} may be able to read or change the traffic. Restore waits until you accept it.").joinToString("\n")

/** What happens to this phone's home WireGuard, when it has one: a backup with keys brings its own, or none. */
fun homeNote(keys: Boolean, backupHas: Boolean) = when {
    !keys -> "Your home WireGuard stays as it is on this phone."
    backupHas -> "Your home WireGuard is replaced by the one in this backup."
    else -> "Your home WireGuard on this phone is removed: this backup has none."
}

/** The websites of [s], every profile's too, for a list of what a setup holds: "3 websites, " or nothing. */
private fun websites(s: Setup) = (s.sites.keys + s.profiles.flatMap { it.sites.keys }).size.takeIf { it > 0 }?.let { count(it, "website") + ", " }.orEmpty()

private fun count(n: Int, what: String) = "$n ${if (n == 1) what else what + "s"}"

@Composable
private fun Buttons(flow: BackupFlow, label: String, enabled: Boolean = true, go: () -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        Secondary("Cancel", { flow.close() }, color = Ink.dim, enabled = !flow.busy)
        Primary(label, go, Modifier.widthIn(min = 120.dp), enabled = enabled && !flow.busy, busy = flow.busy)
    }
