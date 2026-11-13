package app.unbraid.ui

import androidx.core.content.edit
import app.unbraid.support.TipJar
import app.unbraid.support.Tips
import android.content.Context
import android.app.Application
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.unbraid.BuildConfig
import app.unbraid.Unbraid
import app.unbraid.data.UpdateState
import app.unbraid.data.Updates
import app.unbraid.support.Report
import app.unbraid.data.DIRECT
import app.unbraid.stats.Stats
import app.unbraid.data.Home
import app.unbraid.data.Http
import app.unbraid.data.Location
import app.unbraid.data.Manual
import app.unbraid.data.OwnServer
import app.unbraid.data.favKey
import app.unbraid.data.ident
import app.unbraid.data.Nord
import app.unbraid.data.Provider
import app.unbraid.data.Providers
import app.unbraid.data.Prefs
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Limits
import app.unbraid.data.Setup
import app.unbraid.data.MAX_PROFILES
import app.unbraid.vpn.Profiles
import app.unbraid.data.WgConf
import app.unbraid.vpn.UnbraidVpn
import app.unbraid.vpn.VpnState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where unbraid's website lists VPN providers, for people without an account (foss build only). */
const val PROVIDERS_URL = "https://unbraid.app/providers/#need-a-vpn"

/**
 * A state read from disk or returned by a check [read], published over what the screen holds now [held]: an answer or a
 * dismissal given meanwhile is newer than anything that started before it, so it is never overwritten.
 */
internal fun mergeUpdates(read: UpdateState, held: UpdateState) =
    (if (held.asked) read.copy(enabled = held.enabled, asked = true) else read).copy(dismissedCode = maxOf(read.dismissedCode, held.dismissedCode))

/**
 * Whether turning update checks on ([was] off, [now] on) asks unbraid.app at once: only in a build that checks
 * ([available]) and once the terms are accepted. The welcome page asks before that; its check runs on accepting.
 */
internal fun checkAtOnce(available: Boolean, was: Boolean, now: Boolean, termsOk: Boolean) = available && now && !was && termsOk

/** A provider's server list being fetched, or why the last fetch failed. None: no fetch under way, the list shown is the latest. */
sealed interface Fetch {
    object Loading : Fetch
    /** [reason] is a sentence for the screen ([Http.explain]). */
    data class Failed(val reason: String) : Fetch
}

/** [fetches] with [provider]'s marked as loading, or null while one is under way already (a Retry then starts no second one). */
internal fun startFetch(fetches: Map<String, Fetch>, provider: String) = if (fetches[provider] == Fetch.Loading) null else fetches + (provider to Fetch.Loading)

data class AppInfo(val pkg: String, val label: String, val system: Boolean)

/** Removed routes' apps follow the default. The default itself never moves: check [Setup.refusesRemoving] first. */
private fun Setup.without(gone: Set<String>) = copy(routes = routes.filter { it.id !in gone }, apps = apps.filterValues { it !in gone })

/** [f]'s setup, unless removing routes [gone] of it (named [name]) is refused: then [refused] gets why and nothing changes. */
private inline fun Setup.removing(gone: Set<String>, name: String, refused: (app.unbraid.data.CarriesDefault) -> Unit, f: Setup.() -> Setup) =
    refusesRemoving(gone, name)?.let { refused(it); this } ?: f()

/** The single screen-facing model. Thin: state lives in [app.unbraid.data.Store] and [UnbraidVpn.state]. */
class Ui(app: Application, state: androidx.lifecycle.SavedStateHandle) : AndroidViewModel(app) {
    private companion object {
        /** Outlives the screen: a device slot release finishes even after the app is closed. */
        val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        /** Releases under way in this process, so two screens never ask for the same one at once. */
        val releasing: MutableSet<app.unbraid.data.PendingRelease> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }

    private val store = Unbraid.store
    private val pm = app.packageManager

    val setup = store.setup
    val vpn = UnbraidVpn.state
    /** A Tailscale node is kept on this phone ([app.unbraid.data.Store.tsStored]); refreshed by [checkTailscale]. */
    private val _tsStored = MutableStateFlow(false)
    val tsStored = _tsStored.asStateFlow()
    fun checkTailscale() = viewModelScope.launch(Dispatchers.IO) { _tsStored.value = store.tsStored }
    /** Signed in to at least one provider, or holding an imported config. */
    val accounts = store.accounts
    // A running VPN keeps its controls, also after its last provider signed out (its routes' apps wait blocked).
    private val _signedIn = MutableStateFlow(store.accounts.value.isNotEmpty() || store.setup.value.routes.any { it.imported } || store.setup.value.servers.isNotEmpty() || store.setup.value.home.tailscale || vpn.value.phase != VpnState.Phase.Off)
    val signedIn = _signedIn.asStateFlow()
    private val _countries = MutableStateFlow<Map<String, List<app.unbraid.data.Country>>>(emptyMap())
    /** Server catalog per provider id. */
    val countries = _countries.asStateFlow()
    /** True once the saved catalogs are read: until then an empty one means not read yet, not missing. */
    val catalogsRead = MutableStateFlow(false)
    // Saved catalogs run to megabytes: read off the main thread. A list fetched meanwhile is newer and stays.
    init {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = Providers.all.associate { it.id to store.countries(it.id) }
            _countries.update { saved + it }
            catalogsRead.value = true
        }
    }
    /** Counts catalog refreshes, also ones that brought the same list (its saved copy is fresh again). */
    val refreshed = MutableStateFlow(0)
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps = _apps.asStateFlow()
    /** Store tips (Play build); null in the GitHub/F-Droid build. */
    val tips: TipJar? = Tips.jar(app)

    override fun onCleared() { tips?.close() }

    private val uiPrefs = app.getSharedPreferences("unbraid_ui", Context.MODE_PRIVATE)
    /** Play's prominent disclosure: the installed-app list is read only once the user accepted it. */
    val disclosed = MutableStateFlow(uiPrefs.getBoolean("disclosure_accepted", false))

    /** Exit checks by route id ([app.unbraid.vpn.ExitCheck]): kept in memory only, and dropped when the VPN stops. */
    val exits = MutableStateFlow<Map<String, app.unbraid.vpn.ExitReport>>(emptyMap())

    /** Engine start and attempts asked of it: four at most, also when Routes is opened again. */
    @Volatile private var tried = 0 to 0

    /** Whether the engine that runs now may still be asked. */
    fun exitTriesLeft() = vpn.value.engine.let { e -> tried.first != e || tried.second < 4 }

    // A stop or a restart retires every answer: they were about tunnels that no longer run.
    init { viewModelScope.launch { vpn.collect { v -> if (v.phase != VpnState.Phase.On || exits.value.values.any { it.engine != v.engine }) exits.value = emptyMap() } } }

    /**
     * Asks the exits of running routes that have no answer yet from the engine that runs now; routes sharing a tunnel
     * share one request. Runs in the caller's scope, so leaving the screen stops it. True when every route has an answer.
     */
    suspend fun checkExits(): Boolean = withContext(Dispatchers.IO) {
        val now = vpn.value.takeIf { it.phase == VpnState.Phase.On } ?: return@withContext true
        val running = UnbraidVpn.running ?: return@withContext true
        val answered = exits.value.filterValues { it.engine == now.engine && it.ip != null }
        val tags = UnbraidVpn.tags.filterKeys { id -> id !in answered && running.activeRoutes.any { it.id == id } }
        if (tags.isEmpty()) return@withContext true
        tried.let { (e, n) -> if (e == now.engine && n >= 4) return@withContext false; tried = now.engine to (if (e == now.engine) n + 1 else 1) }
        val seen = app.unbraid.vpn.ExitCheck.ask(tags.values)
        val at = System.currentTimeMillis()
        val reports = tags.mapValues { (id, t) -> app.unbraid.vpn.ExitReport(seen[t], at, now.engine, now.servers[id]) }
        // On the main thread, like the clearing above: a restart while asking means these answers are already stale.
        withContext(Dispatchers.Main) {
            if (vpn.value.phase == VpnState.Phase.On && vpn.value.engine == now.engine) exits.value = exits.value.filterValues { it.engine == now.engine } + reports
        }
        reports.values.all { it.ip != null }
    }

    fun acceptDisclosure() {
        uiPrefs.edit { putBoolean("disclosure_accepted", true) }
        if (!disclosed.value) { disclosed.value = true; loadApps() }
    }

    /** The update notice's state ([app.unbraid.data.Updates]); read and written one at a time, off the main thread. */
    val updates = MutableStateFlow(UpdateState())
    /** True once the stored state is read: until then an empty one means unknown, not no news. */
    val updatesLoaded = MutableStateFlow(false)
    /** Asks for the newest version (checks are throttled and serialized); the stored answer shows meanwhile. */
    fun checkUpdates() { viewModelScope.launch(Dispatchers.IO) { runCheck() } }
    private suspend fun runCheck(force: Boolean = false) {
        updates.update { mergeUpdates(store.updates, it) }
        updatesLoaded.value = true
        val checked = Updates.check(store, BuildConfig.VERSION_CODE, force = force)
        updates.update { mergeUpdates(checked, it) }
    }
    /** Hides the update card until a newer version than [code] comes. */
    fun dismissUpdate(code: Int) = setUpdates { it.copy(dismissedCode = code) }
    /** The answer to the opt-in question (welcome page or the one-time screen): either way it is not asked again. */
    fun answerUpdates(on: Boolean) = setUpdates { it.copy(enabled = on, asked = true) }
    /** Turning it on is an answer to the question too. Takes effect at once; the write happens off the main thread. */
    fun setUpdateCheck(on: Boolean) = setUpdates { it.copy(enabled = on, asked = it.asked || on) }
    private fun setUpdates(f: (UpdateState) -> UpdateState) {
        val was = updates.value.enabled
        updates.update(f)
        viewModelScope.launch(Dispatchers.IO) {
            val now = synchronized(store) { f(store.updates).also { store.updates = it } }
            // Just turned on: the first check runs now, not at the next resume (nor before the terms are accepted).
            if (checkAtOnce(BuildConfig.UPDATE_CHECK, was, now.enabled, Onboarding.termsOk)) runCheck(force = true)
        }
    }

    /** Puts the report for a bug report on the clipboard, marked sensitive so Android hides its preview. */
    fun copyReport() {
        val text = Report.build(
            BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")", BuildConfig.FLAVOR, android.os.Build.VERSION.RELEASE, android.os.Build.MODEL,
            setup.value, vpn.value, UnbraidVpn.running, apps.value.size.takeIf { disclosed.value },
        )
        val clip = android.content.ClipData.newPlainText("unbraid report", text)
        clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getApplication<Application>().getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
    }

    /** Whether Check internet access is on for own-server route [id]: written as an explicit choice. */
    fun setCheckInternet(id: String, on: Boolean) = store.edit { s -> s.copy(routes = s.routes.map { if (it.id == id) it.copy(checkInternet = on) else it }) }

    /** Something sign-out couldn't finish and the user must do by hand; shown in Settings, cleared by setting null. */
    val notice = MutableStateFlow<String?>(null)

    init {
        // Catalogs are multi-MB: refreshed once a day, not on every launch (the picker refreshes an empty one itself).
        store.accounts.value.keys.filterNot(store::countriesFresh).forEach(::refreshCountries)
        saveImportedServers()
        // Device slots a sign-out couldn't release, maybe because the app closed first: tried again on every start.
        releasePending()
    }

    /** Sign-in form text, "provider:field" → value. In memory only (it holds secrets), so it survives rotation but not the process. */
    val form = mutableStateMapOf<String, String>()
    /** Last sign-in error per provider id. */
    val formError = mutableStateMapOf<String, String>()
    /** Where the user can fix that error (a device list), per provider id. */
    val formLink = mutableStateMapOf<String, String>()
    /** A question the provider asked before it signs in (a two-factor code, a captcha), per provider id. */
    val challenge = mutableStateMapOf<String, app.unbraid.data.Challenge>()

    /** Files being read into a form, per provider: closing the form or picking another file makes an older read moot. Main thread. */
    private val reads = HashMap<String, Int>()

    /**
     * Forgets a provider's half-filled sign-in form, on an explicit Back or Close. Leaving the
     * app keeps it, so the user can fetch a key from the browser and come back.
     */
    fun discardForm(id: String) {
        reads.merge(id, 1, Int::plus)
        // A sign-in in flight still finishes (a device slot it registers must be saved), but its form is gone for good.
        if (id in signing.value) dismissed += id
        form.keys.filter { it.startsWith("$id:") }.forEach { form.remove(it) }
        formError.remove(id); formLink.remove(id); challenge.remove(id)
    }
    /** Sign-ins whose form was closed while they ran: a failure leaves no error and brings nothing back. */
    private val dismissed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val _signing = MutableStateFlow<Set<String>>(emptySet())
    /** Provider ids with a sign-in in flight. It runs here, not in the screen, so rotation can't drop or repeat it. */
    val signing = _signing.asStateFlow()
    /** Import prompts waiting for input; same lifetime as [form]. */
    val importDraft = ImportDraft()

    /**
     * Signs in to [provider] using [input] as typed: secrets are never trimmed. The outcome lands in [formError]
     * (or [accounts] on success). Ignored while one is already running for this provider.
     */
    fun signIn(provider: Provider, input: Map<String, String>) {
        val id = provider.id
        if (id in _signing.value) return
        if (id in store.accounts.value) { formError[id] = "Already signed in to ${provider.name}"; return }
        formError.remove(id); formLink.remove(id)
        val asked = challenge[id]
        _signing.update { it + id }
        viewModelScope.launch(Dispatchers.IO) {
            val failure = runCatching {
                val clean = (provider.fields + listOfNotNull(asked?.field)).associate { f -> f.key to input[f.key].orEmpty().let { if (f.secret) it else it.trim() } } + asked?.keep.orEmpty()
                val blob = provider.signIn(clean)
                val before = store.accounts.value.keys
                val saved = store.account(id, blob)
                Providers.signedInAt[id] = System.currentTimeMillis()
                // First provider on a fresh install: its placeholder route goes there. Nothing else moves.
                store.update { it.signedIn(id, before) }
                _signedIn.value = true
                if (saved) input.values.forEach(::clearClipboardIf)
                refreshCountries(id)
            }.exceptionOrNull()
            val error = failure?.let { (it as? app.unbraid.data.Challenge)?.message ?: Http.explain(it, provider.name) }
            // On the main thread, like Back: a form closed now never gets this attempt's error.
            withContext(Dispatchers.Main) {
                if (error == null) { // a file still being read must not refill the form just used
                    reads.merge(id, 1, Int::plus)
                    form.keys.filter { it.startsWith("$id:") }.forEach { form.remove(it) }
                }
                if (!dismissed.remove(id)) {
                    error?.let { formError[id] = it }
                    (failure as? app.unbraid.data.AuthException)?.url?.let { formLink[id] = it }
                    // A new question replaces the last one (its answer is spent); any other outcome ends the questions.
                    val c = failure as? app.unbraid.data.Challenge
                    if (c != null) { challenge[id] = c; form.remove("$id:${c.field.key}") } else challenge.remove(id)
                }
                _signing.update { it - id }
            }
        }
    }

    /** Don't leave a secret on the clipboard for the next app to read: cleared only if the clip is [secret] (surrounding space ignored, as pasted configs carry it). */
    fun clearClipboardIf(secret: String) = runCatching {
        val cb = getApplication<Application>().getSystemService(android.content.ClipboardManager::class.java)
        if (secret.isNotBlank() && cb.primaryClip?.getItemAt(0)?.text?.toString()?.trim() == secret.trim()) cb.clearPrimaryClip()
    }

    private fun warn(text: String) = notice.update { listOfNotNull(it, text).joinToString("\n") }

    /** True if the provider released this phone's slot (or has nothing to release). */
    private fun release(id: String, blob: String) = runCatching { Providers.get(id)?.signOut(blob) ?: true }.getOrDefault(false)

    private fun unreleased(names: List<String>) = names.joinToString(", ").let {
        warn("Couldn't release this phone's device slot at $it. Remove it in your $it account${if (names.size > 1) "s" else ""}.")
    }

    /** Releases that failed while this screen model lived: the user was told to remove them by hand ([dismissNotice]). */
    private val failedReleases: MutableSet<app.unbraid.data.PendingRelease> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Asks each provider with a pending release ([app.unbraid.data.Store.pendingReleases]) to let go of this phone's slot,
     * outside the screen's lifetime. A release that fails stays pending, is tried again at the next start and named in
     * [notice] meanwhile.
     */
    private fun releasePending() = background.launch {
        val failed = store.pendingReleases.filter { releasing.add(it) }.map { r ->
            async {
                try { if (release(r.provider, r.blob)) { store.released(r); null } else r } finally { releasing.remove(r) }
            }
        }.awaitAll().filterNotNull()
        if (failed.isNotEmpty()) { failedReleases += failed; unreleased(failed.map { Providers.get(it.provider)?.name ?: it.provider }.distinct()) }
    }

    /** Clears [notice]. Releases it named stop being retried: the user knows to remove those slots by hand. */
    fun dismissNotice() {
        notice.value = null
        val told = failedReleases.toList().also { failedReleases.removeAll(it.toSet()) }
        if (told.isNotEmpty()) background.launch { told.forEach(store::released) }
    }

    /** Signs out of [provider]: the account goes at once, and its device slot is released, now or at a later start. */
    fun signOut(provider: String) = background.launch {
        store.signOut(provider) ?: return@launch // a failed save shows the unsaved banner; the account is out of memory either way
        resetIfEmpty()
        releasePending()
    }

    /**
     * Stops the VPN and waits for the engine to let go of its files. With [logoutTailscale] the node is first logged
     * out of its tailnet, which only works while the engine runs. Null = not asked; false = couldn't, so the device
     * lingers in the admin console.
     */
    private suspend fun stopVpn(logoutTailscale: Boolean): Boolean? {
        val out = if (logoutTailscale) UnbraidVpn.tailscaleLogout() else null
        UnbraidVpn.stop(getApplication())
        withTimeoutOrNull(5_000) { vpn.first { it.phase == VpnState.Phase.Off } }
        return out
    }

    /** Runs [f] while the VPN is off and can't start, as deleting the engine's files needs; else asks the user to try [what] again. */
    private suspend fun whileStopped(what: String, f: () -> Unit) =
        (UnbraidVpn.whileStopped(f) != null).also { if (!it) notice.value = "Couldn't stop the VPN to $what. Try again." }

    private fun tailscaleLeft(loggedOut: Boolean?) {
        if (loggedOut == false) warn("Couldn't log this phone out of Tailscale. Remove it in your tailnet's admin console.")
    }

    /** Nothing left to route through: back to the first-run screen, but never by stopping a running VPN (its apps stay blocked). */
    private fun resetIfEmpty() {
        if (vpn.value.phase == VpnState.Phase.Off && store.accounts.value.isEmpty() && store.setup.value.routes.none { it.imported } && store.setup.value.servers.isEmpty() && !store.setup.value.home.tailscale) signOut()
    }

    /** A new control server or auth key means a new node: drop the old Tailscale state and reconnect fresh. */
    /** Switches control server: the engine must be fully down before its Tailscale state is deleted. */
    fun setTailscaleServer(url: String?, authKey: String?) = viewModelScope.launch(Dispatchers.IO) {
        if (authKey == null && url == store.setup.value.home.tsControlUrl) return@launch // nothing changed: keep the sign-in
        stopVpn(false)
        var saved = false
        // The new server is saved before the lock opens: a connect waiting for it starts with the new node, never the old one.
        if (!whileStopped("switch servers") {
            File(getApplication<Application>().filesDir, "tailscale").deleteRecursively()
            store.tsSuffix = null
            saved = store.update { it.copy(home = it.home.copy(tsControlUrl = url, tsAuthKey = authKey ?: it.home.tsAuthKey.takeIf { _ -> url == it.home.tsControlUrl }, tsSuffixAllowed = null)) }
        }) return@launch
        if (saved) authKey?.let(::clearClipboardIf)
        checkTailscale()
    }

    private val Setup.tsRoutes get() = routes.filter { it.tsExit != null }.map { it.id }.toSet()

    /** Why Tailscale can't be signed out of now (an exit route of it is a default), or null. */
    fun signOutTailscaleRefused() = setup.value.let { s -> s.refusesRemoving(s.tsRoutes, "Tailscale") }

    /**
     * Turns Tailscale off, drops its exit routes (their apps follow the default) and logs this device out of its tailnet.
     * Refused, changing nothing, while one of those routes is a default ([signOutTailscaleRefused]).
     */
    fun signOutTailscale(): app.unbraid.data.CarriesDefault? {
        // Checked before anything that can't be undone (the log-out, the stop), and held: until the routes are gone, no
        // edit can make one of them a default.
        val gone = { s: Setup -> s.tsRoutes }
        store.reserve(gone, "Tailscale")?.let { return it }
        val had = setup.value.tailscale
        viewModelScope.launch(Dispatchers.IO) {
            val loggedOut = stopVpn(had)
            if (!whileStopped("turn Tailscale off") {
                store.update { s -> s.without(s.tsRoutes).copy(home = s.home.copy(tailscale = false, tsAuthKey = null)) }
                File(getApplication<Application>().filesDir, "tailscale").deleteRecursively()
                store.tsSuffix = null
            }) return@launch
            // Off already (only a node left behind): the engine isn't running, so the tailnet still lists this phone.
            tailscaleLeft(if (had) loggedOut else false)
            checkTailscale()
            resetIfEmpty()
        }.invokeOnCompletion { store.unreserve(gone) } // also when cancelled before it started
        return null
    }

    fun signOut() {
        val tailscale = setup.value.tailscale
        viewModelScope.launch(Dispatchers.IO) {
            val loggedOut = stopVpn(tailscale)
            // Each account is kept as a pending release before it goes, so a slot is released even if the app closes now.
            if (!whileStopped("sign out") { store.accounts.value.keys.toList().forEach(store::signOut); store.signOutAll() }) return@launch
            form.clear(); formError.clear(); importDraft.clear()
            _signedIn.value = false
            tailscaleLeft(loggedOut)
            // Released only now: a running tunnel would lose its key while still carrying traffic.
            releasePending()
        }
    }

    /** Each provider's server-list fetch under way or failed ([Fetch]); the picker says "loading" or why, never both. */
    val fetches = MutableStateFlow<Map<String, Fetch>>(emptyMap())

    fun refreshCountries(provider: String = "nord") = viewModelScope.launch(Dispatchers.IO) {
        val p = Providers.get(provider) ?: return@launch
        val acc = store.accounts.value[provider] ?: return@launch
        var mine = false
        fetches.update { m -> startFetch(m, provider).also { mine = it != null } ?: m }
        if (!mine) return@launch
        runCatching { p.countries(acc) }.onSuccess { c ->
            _countries.update { it + (provider to c) }
            fetches.update { it - provider }
            // A fetched list is shown even when its cache can't be written; only a saved one counts as refreshed.
            if (runCatching { store.countries(provider, c) }.isSuccess) refreshed.update { it + 1 }
        }.onFailure { e -> fetches.update { it + (provider to Fetch.Failed(Http.explain(e, p.name))) } }
    }

    private val groupCountries = mutableMapOf<String, Set<String>>()

    /** Countries offering a Nord specialty group; null if unknown (offline). */
    suspend fun nordGroupCountries(group: String): Set<String>? = groupCountries[group] ?: withContext(Dispatchers.IO) {
        runCatching { Nord.groupCountries(group) }.getOrNull()?.also { groupCountries[group] = it }
    }


    /**
     * Reads the installed apps again (on every return to the app: the VPN keeps this process, and so this list, alive for
     * days), so new apps can be routed and removed ones drop their route. Only after the installed-apps notice.
     */
    fun refreshApps() { if (disclosed.value) loadApps() }
    @Volatile private var appsAsked = 0

    private fun loadApps() = (++appsAsked).let { n -> viewModelScope.launch {
        val self = getApplication<Application>().packageName
        // A failed read keeps the list as it was.
        val (list, installed) = withContext(Dispatchers.IO) {
            runCatching {
                val all = pm.getInstalledApplications(0)
                // One query for every app allowed online, not one permission check per app.
                val online = pm.getPackagesHoldingPermissions(arrayOf(android.Manifest.permission.INTERNET), 0).mapTo(HashSet()) { it.packageName }
                all.filter { it.packageName != self && it.packageName in online }
                    .map { AppInfo(it.packageName, it.loadLabel(pm).toString(), it.flags and ApplicationInfo.FLAG_SYSTEM != 0 && pm.getLaunchIntentForPackage(it.packageName) == null) }
                    .sortedBy { it.label.lowercase() } to all.mapTo(HashSet()) { it.packageName }
            }.getOrNull()
        } ?: return@launch
        if (n != appsAsked) return@launch // a newer read is under way (checked and applied here, on the main thread)
        _apps.value = list
        // An uninstalled app must not keep its route (and a tunnel) alive.
        // Saved profiles too: one used later must not bring an uninstalled app's exception back.
        val gone = { s: Setup -> s.apps.keys.any { it !in installed } || s.profiles.any { p -> p.apps.keys.any { it !in installed } } }
        if (gone(store.setup.value)) store.edit { s ->
            s.copy(apps = s.apps.filterKeys { it in installed }, profiles = s.profiles.map { p -> p.copy(apps = p.apps.filterKeys { it in installed }) })
        }
    } }

    /** A config file picked in a sign-in form, read here so a rotation during a slow read doesn't lose it. */
    fun loadFormFile(uri: android.net.Uri, formKey: String, provider: String) = viewModelScope.launch {
        val mine = reads.merge(provider, 1, Int::plus)!!
        val text = withContext(Dispatchers.IO) { readCapped(getApplication<Application>().contentResolver, uri) }
        if (reads[provider] != mine) return@launch // the form was closed, or another file chosen, meanwhile
        if (text == null) formError[provider] = "Couldn't read that file. Config files must be under 64 KB."
        else { form[formKey] = text; formError.remove(provider) }
    }

    /** A QR code read for a sign-in field, here so a rotation during a slow decode doesn't lose it. */
    fun readQr(uri: android.net.Uri?, formKey: String, provider: String) = viewModelScope.launch {
        val mine = reads.merge(provider, 1, Int::plus)!!
        if (uri == android.net.Uri.EMPTY) { formError[provider] = "No camera app found"; return@launch }
        val text = uri?.let { withContext(Dispatchers.IO) { decodeQr(getApplication(), it) } }
        if (reads[provider] != mine || uri == null) return@launch // closed, cancelled, or another read started meanwhile
        if (text.isNullOrBlank()) formError[provider] = "No QR code found in that picture. Try again closer, or type it in."
        else { form[formKey] = text.trim().take(200); formError.remove(provider); formLink.remove(provider); challenge.remove(provider) }
    }

    private val icons = LruCache<String, Bitmap>(200)

    suspend fun icon(pkg: String): Bitmap? = icons.get(pkg) ?: withContext(Dispatchers.IO) {
        runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96) }.getOrNull()?.also { icons.put(pkg, it) }
    }

    // ---- connection ----

    fun connect() = UnbraidVpn.start(getApplication())
    fun disconnect() = UnbraidVpn.stop(getApplication())
    fun pause(minutes: Int) = UnbraidVpn.pause(getApplication(), minutes)

    // ---- routes ----

    /** Adds a route for [loc] (or reuses one already exiting there) to the profile shown, and returns its id. */
    fun addRoute(loc: Location) = routeAt(loc).also(::keep)

    /** The route exiting at [loc]: an existing one, or a new one. */
    private fun routeAt(loc: Location): String {
        setup.value.routes.firstOrNull { !it.imported && it.loc.ident() == loc.ident() }?.let { return it.id }
        val id = newId()
        store.edit { it.copy(routes = it.routes + Route(id, loc, hue = nextHue(it))) }
        return id
    }

    /** Keeps route [id] on the profile shown, which then lists it even while no app uses it. */
    private fun keep(id: String) = store.edit { it.editing(draft) { p -> if (id in p.added) p else p.copy(added = p.added + id) } }

    /** Unique even for two routes added in the same millisecond (ids key the route lists). */
    private fun newId() = generateSequence { "r" + java.util.UUID.randomUUID().toString().take(8) }.first { id -> setup.value.routes.none { it.id == id } }
    private fun nextHue(s: Setup) = (0..6).firstOrNull { h -> s.routes.none { it.hue == h } } ?: s.routes.size

    /** Points route [id] at a provider location, dropping any imported source it had. */
    fun setLocation(id: String, loc: Location) = guarded { s ->
        // A route's blocking choice belongs to its provider: another provider starts from its own page.
        s.copy(routes = s.routes.map { if (it.id == id) it.copy(loc = loc, wg = null, ovpn = null, ss = null, tsExit = null, tsName = null, own = null, filter = it.filter.takeIf { _ -> it.loc.provider == loc.provider && !it.imported }) else it })
    }

    /** A new route for [loc] (or the one already exiting there) for app [pkg], as one change; in the profile being viewed. */
    fun addRouteFor(pkg: String, loc: Location) {
        val f = { s: Setup ->
            val r = s.routes.firstOrNull { !it.imported && it.loc.ident() == loc.ident() } ?: Route(newId(), loc, hue = nextHue(s))
            // As [assign]: the app chose this route, also when it is the default.
            s.copy(routes = if (r in s.routes) s.routes else s.routes + r).editing(draft) { p -> p.assigned(pkg, r.id) }
        }
        if (draft != null) store.edit(f) else guarded(f = f)
    }

    /** Sets the default route of the profile being viewed to [loc] (a route already exiting there, or a new one). */
    fun chooseDefault(loc: Location) { if (draft != null) setDefault(routeAt(loc)) }

    /** A route change that would take a provider over its connection limit, waiting for the user's answer. */
    class LimitAsk(val text: String, val apply: () -> Unit, val share: Pair<String, () -> Unit>?)
    val limitAsk = MutableStateFlow<LimitAsk?>(null)

    /**
     * Saves [f], unless it takes a provider over its limit ([Limits.over]): then nothing changes until the user answers
     * [limitAsk]. With one route of that provider already on a server, it offers to put both routes on that server.
     * [after] runs once [f] is saved, whether at once or on the user's yes.
     */
    private fun guarded(after: () -> Unit = {}, f: (Setup) -> Setup) {
        val old = setup.value
        val acc = accounts.value
        val o = Limits.over(old, f(old)) { p -> acc[p]?.let { a -> Providers.get(p)?.limit(a) } } ?: run { store.edit(f); after(); return }
        val name = Providers.get(o.provider)?.name ?: o.provider
        // Only a route the engine runs as saved has a server worth sharing; checked again when the user answers.
        val anchor = old.activeRoutes.singleOrNull { !it.imported && it.loc.provider == o.provider }?.takeIf { a -> UnbraidVpn.running?.route(a.id)?.loc == a.loc }
        val server = anchor?.let { vpn.value.servers[it.id] }
        limitAsk.value = LimitAsk(Limits.warning(name, o.limit, o.unit, o.n), { store.edit(f); after() }, server?.let { sv ->
            sv.name to {
                if (setup.value.route(anchor.id)?.loc == anchor.loc && vpn.value.servers[anchor.id] == sv) {
                    store.edit { s ->
                        f(s).let { n -> n.copy(routes = n.routes.map { r -> if (!r.imported && r.loc.provider == o.provider && n.activeRoutes.any { it.id == r.id }) r.copy(loc = anchor.loc.copy(pinned = sv)) else r }) }
                    }
                    after()
                }
            }
        })
    }

    // ---- own servers ----

    /** Before saved servers existed, an imported config lived only in its route: save each one once and link it. */
    private fun saveImportedServers() {
        if (setup.value.routes.none { (it.wg != null || it.ovpn != null || it.ss != null) && it.own == null }) return
        store.edit { s ->
            var servers = s.servers
            s.copy(routes = s.routes.map { r ->
                if ((r.wg == null && r.ovpn == null && r.ss == null) || r.own != null) return@map r
                val o = servers.firstOrNull { it.wg == r.wg && it.ovpn == r.ovpn && it.ss == r.ss }
                    ?: OwnServer(newServerId(servers), r.wg, r.ovpn, r.ss).also { servers = servers + it }
                r.copy(own = o.id)
            }, servers = servers)
        }
    }

    private fun newServerId(taken: List<OwnServer>) = generateSequence { "s" + java.util.UUID.randomUUID().toString().take(8) }.first { id -> taken.none { it.id == id } }

    /** Saves one of the user's own servers; with a [target] ("" new route, "app:<pkg>" or a route id) that route uses it too. False if not written. */
    fun importServer(target: String?, server: OwnServer): Boolean {
        val s = server.copy(id = newServerId(setup.value.servers))
        val saved = store.update { it.copy(servers = it.servers + s) }
        _signedIn.value = true
        target?.let { useServer(it, s) }
        return saved
    }

    /**
     * Route [target] exits through saved server [s]; returns the route id. For a new route ("", "app:<pkg>", "default") a
     * route already through [s] is reused, as a provider location's is, instead of adding another.
     */
    fun useServer(target: String, s: OwnServer): String {
        val reuse = setup.value.takeIf { it.route(target) == null }?.serverRoute(s, draft)
            ?: return addImported(target) { it.copy(wg = s.wg, ovpn = s.ovpn, ss = s.ss, own = s.id) }
        if (target.startsWith("app:")) assign(target.removePrefix("app:"), reuse) else if (target.isEmpty()) keep(reuse)
        return reuse
    }

    /** Renames a saved server and every route using it. */
    fun renameServer(id: String, name: String) = Manual.cleanName(name, 40).takeIf { it.isNotEmpty() }?.let { n ->
        fun OwnServer.named() = copy(wg = wg?.copy(name = n), ovpn = ovpn?.copy(name = n), ss = ss?.copy(name = n))
        store.edit { s ->
            s.copy(
                servers = s.servers.map { if (it.id == id) it.named() else it },
                routes = s.routes.map { r -> if (r.own != id) r else r.copy(wg = r.wg?.copy(name = n), ovpn = r.ovpn?.copy(name = n), ss = r.ss?.copy(name = n)) },
            )
        }
    }

    private fun Setup.serverRoutes(id: String) = routes.filter { it.own == id }.map { it.id }.toSet()
    private fun Setup.serverName(id: String) = servers.firstOrNull { it.id == id }?.name.orEmpty().ifEmpty { "This server" }

    /** Why saved server [id] can't be removed now (a route using it is a default), or null. */
    fun removeServerRefused(id: String) = setup.value.let { s -> s.refusesRemoving(s.serverRoutes(id), s.serverName(id)) }

    /** Removes a saved server and the routes using it; their apps follow the default route. Refused, changing nothing, while one of them is a default. */
    fun removeServer(id: String): app.unbraid.data.CarriesDefault? {
        var refused: app.unbraid.data.CarriesDefault? = null
        store.edit { s -> s.removing(s.serverRoutes(id), s.serverName(id), { refused = it }) { without(serverRoutes(id)).copy(servers = servers.filter { it.id != id }) } }
        if (refused == null) resetIfEmpty()
        return refused
    }

    /**
     * Gives an imported source to a route and returns its id. [target] is "" (new route), "app:<pkg>" (new route for
     * that app) or a route id (replace that route's source). [source] receives the blank route and sets wg/ovpn/ss/tsExit.
     * With no provider signed in, the unusable provider routes are dropped and the new route becomes the default.
     */
    fun addImported(target: String, source: (Route) -> Route): String {
        val id = target.takeIf { setup.value.route(it) != null } ?: newId()
        store.edit { s ->
            val blank = s.route(id)?.copy(loc = Location(), wg = null, ovpn = null, ss = null, tsExit = null, tsName = null, own = null) ?: Route(id, hue = nextHue(s))
            s.imported(id, source(blank), alone = store.accounts.value.isEmpty() && draft == null)
        }
        if (target.startsWith("app:")) assign(target.removePrefix("app:"), id)
        if (target.isEmpty()) keep(id) // a new route, added to the profile shown
        _signedIn.value = true
        return id
    }

    /**
     * Tailscale runs one exit node at a time: an existing exit route is retargeted instead of adding another. True if
     * one was. [ip] is the node's tailnet address (what Tailscale is told, unambiguous); [name] is only for display.
     */
    fun addTsExit(target: String, ip: String, name: String? = null): Boolean {
        if (tsLock(ip) != null) return false
        draft?.let { d -> draftTsExit(d, target, ip, name); return true }
        val old = setup.value.routes.firstOrNull { it.tsExit != null }
        store.edit { s -> s.copy(routes = s.routes.map { if (it.tsExit != null) it.copy(tsExit = ip, tsName = name) else it }) }
        val t = setup.value.route(target)
        when {
            t != null && t.tsExit == null -> addImported(target) { it.copy(tsExit = ip, tsName = name) }
            old != null -> if (target.startsWith("app:")) assign(target.removePrefix("app:"), old.id) else if (target.isEmpty()) keep(old.id)
            else -> addImported(target) { it.copy(tsExit = ip, tsName = name) }
        }
        return old != null
    }

    /** Skips providers: Tailscale becomes the only network feature. A new install's unusable placeholder route goes (Direct is the default then); routes set up before stay. */
    fun useTailscaleOnly() {
        store.edit { s ->
            val on = s.copy(home = s.home.copy(tailscale = true))
            if (store.accounts.value.isEmpty() && s.untouched) on.copy(routes = emptyList(), defaultRoute = DIRECT) else on
        }
        _signedIn.value = true
    }

    /** Renames one of the user's own servers (WireGuard, OpenVPN, Shadowsocks, Tailscale exit); a saved one everywhere. */
    fun renameRoute(id: String, name: String) {
        setup.value.route(id)?.own?.let { renameServer(it, name); return }
        val n = Manual.cleanName(name, 40).ifEmpty { return }
        store.edit { s ->
            s.copy(routes = s.routes.map { r ->
                if (r.id != id) r else r.copy(wg = r.wg?.copy(name = n), ovpn = r.ovpn?.copy(name = n), ss = r.ss?.copy(name = n), tsName = r.tsExit?.let { n })
            })
        }
    }

    private fun Setup.routeName(id: String) = route(id)?.name ?: "This route"

    /** Why route [id] can't be removed now (it is a default, here or in a saved profile), or null. */
    fun removeRouteRefused(id: String) = setup.value.let { s -> s.refusesRemoving(setOf(id), s.routeName(id)) }

    /** Removes route [id]; its apps follow the default. Refused, changing nothing, while it is a default. */
    fun removeRoute(id: String): app.unbraid.data.CarriesDefault? {
        var refused: app.unbraid.data.CarriesDefault? = null
        store.edit { s -> s.removing(setOf(id), s.routeName(id), { refused = it }) { without(setOf(id)) } }
        return refused
    }

    /**
     * Makes route [id] the default of the profile being viewed (saved only, nothing connects) or of the live setup. Apps
     * that chose a route keep it; returns how many installed apps follow the default and so move ([moving], asked first).
     */
    fun setDefault(id: String): Int = moving(id).also { editRoutes { p -> p.copy(defaultRoute = id) } }

    /** How many installed apps would move if route [id] became the default of the profile being viewed: those that follow it. 0 for the same default, or before the app list is read. */
    fun moving(id: String) = setup.value.viewAs(draft).let { s -> if (id == s.defaultRoute) 0 else s.followers(apps.value.map { it.pkg }) }

    /** [f] on the profile being viewed when it isn't the one in use (nothing connects, no limit to check), else live and guarded. */
    private fun editRoutes(f: (app.unbraid.data.Profile) -> app.unbraid.data.Profile) {
        val d = draft
        if (d != null) store.edit { it.editing(d, f) } else guarded { it.editing(null, f) }
    }

    // ---- viewing a profile ----

    /** The profile shown on Routes and Apps when it isn't the one in use; null = the one in use (the live setup). */
    // Kept across process death: a picker or sheet restored for a profile not in use must not act on the live setup.
    val viewing = state.getMutableStateFlow<String?>("viewing", null)
    /** The setup as the viewed profile routes it: what Routes and Apps show. */
    val shown = combine(setup, viewing) { s, v -> s.viewAs(v) }.stateIn(viewModelScope, SharingStarted.Eagerly, setup.value)
    /** The viewed profile, if it exists and isn't the one in use: edits go to it alone. */
    private val draft get() = viewing.value?.takeIf { n -> n != setup.value.profile && setup.value.profiles.any { it.name == n } }

    /**
     * Route [id], ready to be changed for the profile being viewed: if the live setup or another profile uses it too, the
     * viewed one gets its own copy first (same exit, new id), so nothing live or elsewhere changes. Otherwise [id] itself.
     */
    fun ownRoute(id: String): String {
        val d = draft ?: return id
        val s = setup.value
        // Shared with the live setup or another profile: those keep theirs.
        val r = s.route(id)?.takeIf { s.activeRoutes.any { it.id == id } || id in s.added || s.saved.any { p -> p.name != d && id in p.own } } ?: return id
        val copy = newId()
        store.edit { c ->
            c.copy(routes = c.routes + r.copy(id = copy, hue = nextHue(c))).editing(d) { p ->
                p.copy(defaultRoute = if (p.defaultRoute == id) copy else p.defaultRoute, apps = p.apps.mapValues { (_, v) -> if (v == id) copy else v }, added = p.added.map { if (it == id) copy else it })
            }
        }
        return copy
    }

    /** Takes route [id] out of the profile being viewed: its apps there follow that profile's default. The route stays for others. */
    fun dropRoute(id: String) { draft?.let { d -> store.edit { it.editing(d) { p -> p.copy(apps = p.apps.filterValues { v -> v != id }, added = p.added - id) } } } }

    /**
     * Exit node [ip] for profile [d], not in use: only its own routes change, never another profile's. It reuses a route
     * already through that node, else retargets one of its Tailscale routes (copied first if shared), else adds one.
     * Tailscale uses one exit node at a time, so the profile's other Tailscale routes follow to the same node.
     */
    private fun draftTsExit(d: String, target: String, ip: String, name: String?) {
        val s = setup.value
        val own = s.profiles.firstOrNull { it.name == d }?.own.orEmpty()
        // Replaces the route's whole source (a saved server's config goes), in place.
        val via = { id: String -> addImported(id) { it.copy(tsExit = ip, tsName = name) } }
        val id = s.route(target)?.id?.also { via(it) }
            ?: s.routes.firstOrNull { it.tsExit == ip }?.id
            ?: s.routes.firstOrNull { it.tsExit != null && it.id in own }?.id?.let(::ownRoute)?.also { via(it) }
            ?: addImported(target) { it.copy(tsExit = ip, tsName = name) }
        // Read again: a route copied above is the profile's now, the original no longer.
        val now = setup.value
        val mine = now.profiles.firstOrNull { it.name == d }?.own.orEmpty()
        now.routes.filter { it.tsExit != null && it.tsExit != ip && it.id in mine && it.id != id }.forEach { via(ownRoute(it.id)) }
        when {
            target.startsWith("app:") -> assign(target.removePrefix("app:"), id)
            target == "default" -> setDefault(id)
            target.isEmpty() -> keep(id)
        }
    }

    /**
     * While a profile not in use is edited: the live route through another Tailscale exit node, which keeps [ip] from
     * being chosen (Tailscale uses one exit node at a time, and the live one must not change before Use).
     */
    fun tsLock(ip: String) = if (draft == null) null else setup.value.activeRoutes.firstOrNull { it.tsExit != null && it.tsExit != ip }

    /** Shows profile [name] without using it: nothing reconnects until [useProfile]. */
    fun view(name: String?) { viewing.value = name?.takeIf { it != setup.value.profile } }

    // ---- profiles ----

    /** Set by a profile shortcut: the activity on screen shows Routes (where the profile is shown, to be used). */
    val routesWanted = MutableStateFlow(false)
    val switching = Profiles.switching
    val switchFailed = Profiles.refused

    /** Switching to profile [name] now would lose the setup in use (no profile holds it): ask whether to save it first. */
    fun switchLoses(name: String) = setup.value.let { s -> s.loose && s.profiles.any { it.name == name && it.ready } }

    /**
     * Switches to profile [name]; [then] runs once it is saved (at once, or after the user's yes to a provider's limit).
     * With [keepAs] (the user chose Save), a setup in use that no profile holds is first saved as that profile. False,
     * changing nothing, when it can't be: [MAX_PROFILES] already, or [keepAs] names [name] itself.
     */
    fun useProfile(name: String, keepAs: String? = null, then: () -> Unit = {}): Boolean {
        switchFailed.value = null
        val s = setup.value
        val p = s.profiles.firstOrNull { it.name == name } ?: return true
        if (s.profile == name) { viewing.value = null; then(); return true }
        // An empty profile can't be used yet: it is shown, to be given a default route.
        if (!p.ready) { viewing.value = name; return true }
        if (keepAs != null && s.loose && s.use(name, keepAs).profile != name) return false
        guarded({ viewing.value = null; Profiles.started(name); then() }) { it.use(name, keepAs) }
        return true
    }

    /**
     * A new profile [raw]: with none in use, the current setup saved as it and in use (nothing reconnects); otherwise a
     * copy of the current setup, or [empty], shown but not in use.
     */
    fun newProfile(raw: String, empty: Boolean) {
        var added: String? = null
        store.edit { s ->
            val n = s.profileName(raw)?.takeIf { s.profiles.size < MAX_PROFILES } ?: return@edit s
            if (s.profile == null && !empty) s.saveAs(n) else { added = n; s.add(n, empty) }
        }
        viewing.value = added
    }

    fun renameProfile(old: String, name: String) {
        store.edit { s -> s.profileName(name, except = old)?.let { s.rename(old, it) } ?: s }
        // Renamed while shown: keep showing it under its new name.
        if (viewing.value == old && setup.value.profiles.none { it.name == old }) viewing.value = name.trim()
    }

    fun forgetProfile(name: String) { store.edit { it.forget(name) }; if (viewing.value == name) viewing.value = null }
    fun moveProfile(name: String, by: Int) = store.edit { it.move(name, by) }

    /** App [pkg] chose route [routeId] (the default too: it stays there when the default changes); null = it follows the default. */
    fun assign(pkg: String, routeId: String?) = editRoutes { p -> p.assigned(pkg, routeId) }

    /** App [pkg] gives up its own route and follows the default of the profile being viewed. */
    fun followDefault(pkg: String) = assign(pkg, null)

    /** Route [id]'s own blocking choice; one equal to its provider page's is dropped, so the route follows the page again. */
    fun setFilter(id: String, on: Boolean) = store.update { s ->
        s.copy(routes = s.routes.map { r -> if (r.id != id) r else r.copy(filter = on.takeIf { it != (s.opt(r.loc.provider, app.unbraid.data.PROTECT) == "on") }) })
    }

    /** One setting on [provider]'s page. */
    /** A page setting can change which servers a provider lists (Mullvad-owned only), so its countries are read again. */
    fun setOpt(provider: String, key: String, value: String) {
        store.update { s -> s.copy(opts = s.opts + (provider to (s.opts[provider].orEmpty() + (key to value)))) }
        refreshCountries(provider)
    }

    fun toggleFavorite(loc: Location) = store.edit { s ->
        val l = loc.favKey()
        // Matched by identity, so favourites saved with older ids are found (and dropped) too.
        val had = s.favorites.filter { it.favKey() == l }
        // Saved whole (a pinned server keeps its address and key, to connect to it later), matched by identity only.
        s.copy(favorites = if (had.isNotEmpty()) s.favorites - had.toSet() else s.favorites + loc.copy(group = null, protocol = null))
    }

    // Privacy and protection settings wait for the disk, as setHome does: a restart must never bring back an older one.
    fun setPrefs(f: (Prefs) -> Prefs) { store.update { it.copy(prefs = f(it.prefs)) } }

    /** Statistics on or off; while off nothing is recorded (the app's observer starts and stops the collector). */
    fun setStats(on: Boolean) = setPrefs { it.copy(stats = on) }

    /** Erases all statistics and the engine's record of looked-up domains. Recording, if on, carries on from now. */
    fun deleteStats() { Stats.delete(); UnbraidVpn.forgetLookups(getApplication()) }
    fun setHome(f: (Home) -> Home) = store.update { it.copy(home = f(it.home)) }

    fun importHomeWireGuard(text: String): String? = runCatching {
        val p = WgConf.parse(text, "Home")
        if (setHome { it.copy(wg = p) }) clearClipboardIf(text)
        null
    }.getOrElse { it.message ?: "Not a WireGuard config" }
}
