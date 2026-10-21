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
import app.unbraid.Unbraid
import app.unbraid.data.DIRECT
import app.unbraid.stats.Stats
import app.unbraid.data.Home
import app.unbraid.data.Location
import app.unbraid.data.OwnServer
import app.unbraid.data.favKey
import app.unbraid.data.ident
import app.unbraid.data.AuthException
import app.unbraid.data.Nord
import app.unbraid.data.Provider
import app.unbraid.data.Providers
import app.unbraid.data.Prefs
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.data.WgConf
import app.unbraid.vpn.UnbraidVpn
import app.unbraid.vpn.VpnState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(val pkg: String, val label: String, val system: Boolean)

/** The single screen-facing model. Thin: state lives in [app.unbraid.data.Store] and [UnbraidVpn.state]. */
class Ui(app: Application) : AndroidViewModel(app) {
    private val store = Unbraid.store
    private val pm = app.packageManager

    val setup = store.setup
    val vpn = UnbraidVpn.state
    /** Signed in to at least one provider, or holding an imported config. */
    val accounts = store.accounts
    private val _signedIn = MutableStateFlow(store.accounts.value.isNotEmpty() || store.setup.value.routes.any { it.imported } || store.setup.value.servers.isNotEmpty() || store.setup.value.home.tailscale)
    val signedIn = _signedIn.asStateFlow()
    private val _countries = MutableStateFlow(Providers.all.associate { it.id to store.countries(it.id) })
    /** Server catalog per provider id. */
    val countries = _countries.asStateFlow()
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps = _apps.asStateFlow()
    /** Store tips (Play build); null in the GitHub/F-Droid build. */
    val tips: TipJar? = Tips.jar(app)

    override fun onCleared() { tips?.close() }

    private val uiPrefs = app.getSharedPreferences("unbraid_ui", Context.MODE_PRIVATE)
    /** Play's prominent disclosure: the installed-app list is read only once the user accepted it. */
    val disclosed = MutableStateFlow(uiPrefs.getBoolean("disclosure_accepted", false))

    fun acceptDisclosure() {
        uiPrefs.edit { putBoolean("disclosure_accepted", true) }
        if (!disclosed.value) { disclosed.value = true; loadApps() }
    }

    /** Something sign-out couldn't finish and the user must do by hand; shown in Settings, cleared by setting null. */
    val notice = MutableStateFlow<String?>(null)

    init {
        // Catalogs are multi-MB: refreshed once a day, not on every launch (the picker refreshes an empty one itself).
        store.accounts.value.keys.filterNot(store::countriesFresh).forEach(::refreshCountries)
        if (disclosed.value) loadApps()
        saveImportedServers()
    }

    /** Sign-in form text, "provider:field" → value. In memory only (it holds secrets), so it survives rotation but not the process. */
    val form = mutableStateMapOf<String, String>()
    /** Last sign-in error per provider id. */
    val formError = mutableStateMapOf<String, String>()

    /**
     * Forgets a provider's half-filled sign-in form, on an explicit Back or Close. Leaving the
     * app keeps it, so the user can fetch a key from the browser and come back.
     */
    fun discardForm(id: String) {
        if (id in signing.value) return // a sign-in in flight keeps its form, for a retry if it fails
        form.keys.filter { it.startsWith("$id:") }.forEach { form.remove(it) }
        formError.remove(id)
    }
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
        formError.remove(id)
        _signing.update { it + id }
        viewModelScope.launch(Dispatchers.IO) {
            val error = runCatching {
                val clean = provider.fields.associate { f -> f.key to input[f.key].orEmpty().let { if (f.secret) it else it.trim() } }
                val saved = store.account(id, provider.signIn(clean))
                // First provider on a fresh install: point the default route at it.
                store.update { s ->
                    val d = s.route(s.defaultRoute)
                    when {
                        s.routes.isEmpty() -> s.copy(routes = listOf(Route("main", Location(id))), defaultRoute = "main")
                        d != null && !d.imported && d.loc.provider !in store.accounts.value -> s.copy(routes = s.routes.map { if (it.id == d.id) it.copy(loc = Location(id)) else it })
                        else -> s
                    }
                }
                _signedIn.value = true
                if (saved) input.values.forEach(::clearClipboardIf)
                form.keys.filter { it.startsWith("$id:") }.forEach { form.remove(it) }
                refreshCountries(id)
                null
            }.getOrElse {
                when (it) {
                    is IllegalArgumentException, is AuthException -> it.message ?: "${provider.name} didn't accept these details"
                    else -> "Couldn't reach ${provider.name}. Check the connection."
                }
            }
            error?.let { formError[id] = it }
            _signing.update { it - id }
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

    fun signOut(provider: String) {
        val blob = store.accounts.value[provider]
        store.account(provider, null)
        resetIfEmpty()
        if (blob != null) viewModelScope.launch(Dispatchers.IO) {
            if (!release(provider, blob)) unreleased(listOf(Providers.get(provider)?.name ?: provider))
        }
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
        stopVpn(false)
        var saved = false
        // The new server is saved before the lock opens: a connect waiting for it starts with the new node, never the old one.
        if (!whileStopped("switch servers") {
            File(getApplication<Application>().filesDir, "tailscale").deleteRecursively()
            store.tsSuffix = null
            saved = store.update { it.copy(home = it.home.copy(tsControlUrl = url, tsAuthKey = authKey ?: it.home.tsAuthKey.takeIf { _ -> url == it.home.tsControlUrl })) }
        }) return@launch
        if (saved) authKey?.let(::clearClipboardIf)
    }

    /** Turns Tailscale off, drops its exit routes (their apps follow the default) and logs this device out of its tailnet. */
    fun signOutTailscale() {
        val had = setup.value.tailscale
        viewModelScope.launch(Dispatchers.IO) {
            val loggedOut = stopVpn(had)
            if (!whileStopped("turn Tailscale off") {
                store.update { s ->
                    val gone = s.routes.filter { it.tsExit != null }.map { it.id }.toSet()
                    val routes = s.routes.filter { it.id !in gone }
                    s.copy(
                        routes = routes, home = s.home.copy(tailscale = false, tsAuthKey = null),
                        defaultRoute = if (s.defaultRoute in gone) routes.firstOrNull()?.id ?: DIRECT else s.defaultRoute,
                        apps = s.apps.filterValues { it !in gone },
                    )
                }
                File(getApplication<Application>().filesDir, "tailscale").deleteRecursively()
                store.tsSuffix = null
            }) return@launch
            tailscaleLeft(loggedOut)
            resetIfEmpty()
        }
    }

    fun signOut() {
        val accounts = store.accounts.value
        val tailscale = setup.value.tailscale
        viewModelScope.launch(Dispatchers.IO) {
            val loggedOut = stopVpn(tailscale)
            if (!whileStopped("sign out", store::signOutAll)) return@launch
            // Released only now: a running tunnel would lose its key while still carrying traffic.
            val revoked = accounts.map { (id, blob) -> async { id to release(id, blob) } }
            form.clear(); formError.clear(); importDraft.clear()
            _signedIn.value = false
            tailscaleLeft(loggedOut)
            revoked.awaitAll().filter { !it.second }.map { Providers.get(it.first)?.name ?: it.first }.takeIf { it.isNotEmpty() }?.let(::unreleased)
        }
    }

    fun refreshCountries(provider: String = "nord") = viewModelScope.launch(Dispatchers.IO) {
        val p = Providers.get(provider) ?: return@launch
        val acc = store.accounts.value[provider] ?: return@launch
        runCatching { p.countries(acc) }.onSuccess { c ->
            store.countries(provider, c)
            _countries.update { it + (provider to c) }
        }
    }

    private val groupCountries = mutableMapOf<String, Set<String>>()

    /** Countries offering a Nord specialty group; null if unknown (offline). */
    suspend fun nordGroupCountries(group: String): Set<String>? = groupCountries[group] ?: withContext(Dispatchers.IO) {
        runCatching { Nord.groupCountries(group) }.getOrNull()?.also { groupCountries[group] = it }
    }


    private fun loadApps() = viewModelScope.launch(Dispatchers.IO) {
        val self = getApplication<Application>().packageName
        val all = pm.getInstalledApplications(0)
        // One query for every app allowed online, not one permission check per app.
        val online = pm.getPackagesHoldingPermissions(arrayOf(android.Manifest.permission.INTERNET), 0).mapTo(HashSet()) { it.packageName }
        _apps.value = all.filter { it.packageName != self && it.packageName in online }
            .map { AppInfo(it.packageName, it.loadLabel(pm).toString(), it.flags and ApplicationInfo.FLAG_SYSTEM != 0 && pm.getLaunchIntentForPackage(it.packageName) == null) }
            .sortedBy { it.label.lowercase() }
        // An uninstalled app must not keep its route (and a tunnel) alive.
        val installed = all.mapTo(HashSet()) { it.packageName }
        if (store.setup.value.apps.keys.any { it !in installed }) store.update { s -> s.copy(apps = s.apps.filterKeys { it in installed }) }
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

    /** Adds a route for [loc] (or reuses one already exiting there) and returns its id. */
    fun addRoute(loc: Location): String {
        setup.value.routes.firstOrNull { !it.imported && it.loc.ident() == loc.ident() }?.let { return it.id }
        val id = newId()
        store.update { it.copy(routes = it.routes + Route(id, loc, hue = nextHue(it))) }
        return id
    }

    /** Unique even for two routes added in the same millisecond (ids key the route lists). */
    private fun newId() = generateSequence { "r" + java.util.UUID.randomUUID().toString().take(8) }.first { id -> setup.value.routes.none { it.id == id } }
    private fun nextHue(s: Setup) = (0..6).firstOrNull { h -> s.routes.none { it.hue == h } } ?: s.routes.size

    /** Points route [id] at a provider location, dropping any imported source it had. */
    fun setLocation(id: String, loc: Location) = store.update { s ->
        s.copy(routes = s.routes.map { if (it.id == id) it.copy(loc = loc, wg = null, ovpn = null, ss = null, tsExit = null, tsName = null, own = null) else it })
    }

    // ---- own servers ----

    /** Before saved servers existed, an imported config lived only in its route: save each one once and link it. */
    private fun saveImportedServers() {
        if (setup.value.routes.none { (it.wg != null || it.ovpn != null || it.ss != null) && it.own == null }) return
        store.update { s ->
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

    /** Route [target] exits through saved server [s]; returns the route id. */
    fun useServer(target: String, s: OwnServer) = addImported(target) { it.copy(wg = s.wg, ovpn = s.ovpn, ss = s.ss, own = s.id) }

    /** Renames a saved server and every route using it. */
    fun renameServer(id: String, name: String) = name.trim().take(40).takeIf { it.isNotEmpty() }?.let { n ->
        fun OwnServer.named() = copy(wg = wg?.copy(name = n), ovpn = ovpn?.copy(name = n), ss = ss?.copy(name = n))
        store.update { s ->
            s.copy(
                servers = s.servers.map { if (it.id == id) it.named() else it },
                routes = s.routes.map { r -> if (r.own != id) r else r.copy(wg = r.wg?.copy(name = n), ovpn = r.ovpn?.copy(name = n), ss = r.ss?.copy(name = n)) },
            )
        }
    }

    /** Removes a saved server and the routes using it; their apps follow the default route (or go Direct if that was one). */
    fun removeServer(id: String) {
        dropServer(id)
        resetIfEmpty()
    }

    private fun dropServer(id: String) = store.update { s ->
        val gone = s.routes.filter { it.own == id }.map { it.id }.toSet()
        val routes = s.routes.filter { it.id !in gone }
        s.copy(
            servers = s.servers.filter { it.id != id }, routes = routes,
            defaultRoute = if (s.defaultRoute in gone) routes.firstOrNull()?.id ?: DIRECT else s.defaultRoute,
            apps = s.apps.filterValues { it !in gone },
        )
    }

    /**
     * Gives an imported source to a route and returns its id. [target] is "" (new route), "app:<pkg>" (new route for
     * that app) or a route id (replace that route's source). [source] receives the blank route and sets wg/ovpn/ss/tsExit.
     * With no provider signed in, the unusable provider routes are dropped and the new route becomes the default.
     */
    fun addImported(target: String, source: (Route) -> Route): String {
        val id = target.takeIf { setup.value.route(it) != null } ?: newId()
        store.update { s ->
            val blank = s.route(id)?.copy(loc = Location(), wg = null, ovpn = null, ss = null, tsExit = null, tsName = null, own = null) ?: Route(id, hue = nextHue(s))
            val r = source(blank)
            var next = s.copy(routes = if (s.route(id) != null) s.routes.map { if (it.id == id) r else it } else s.routes + r)
            if (store.accounts.value.isEmpty()) {
                val dead = next.routes.filter { !it.imported }.map { it.id }.toSet()
                next = next.copy(
                    routes = next.routes.filter { it.id !in dead },
                    defaultRoute = if (next.defaultRoute in dead) id else next.defaultRoute,
                    apps = next.apps.filterValues { it !in dead },
                )
            }
            next
        }
        if (target.startsWith("app:")) assign(target.removePrefix("app:"), id)
        _signedIn.value = true
        return id
    }

    /**
     * Tailscale runs one exit node at a time: an existing exit route is retargeted instead of adding another. True if
     * one was. [ip] is the node's tailnet address (what Tailscale is told, unambiguous); [name] is only for display.
     */
    fun addTsExit(target: String, ip: String, name: String? = null): Boolean {
        val old = setup.value.routes.firstOrNull { it.tsExit != null }
        store.update { s -> s.copy(routes = s.routes.map { if (it.tsExit != null) it.copy(tsExit = ip, tsName = name) else it }) }
        val t = setup.value.route(target)
        when {
            t != null && t.tsExit == null -> addImported(target) { it.copy(tsExit = ip, tsName = name) }
            old != null -> if (target.startsWith("app:")) assign(target.removePrefix("app:"), old.id)
            else -> addImported(target) { it.copy(tsExit = ip, tsName = name) }
        }
        return old != null
    }

    /** Skips providers: Tailscale becomes the only network feature, and the unusable provider route goes. */
    fun useTailscaleOnly() {
        store.update { s ->
            val routes = if (store.accounts.value.isEmpty()) s.routes.filter { it.imported } else s.routes
            s.copy(
                routes = routes, home = s.home.copy(tailscale = true),
                defaultRoute = if (routes.any { it.id == s.defaultRoute }) s.defaultRoute else routes.firstOrNull()?.id ?: DIRECT,
                apps = s.apps.filterValues { v -> v == DIRECT || routes.any { it.id == v } },
            )
        }
        _signedIn.value = true
    }

    /** Renames one of the user's own servers (WireGuard, OpenVPN, Shadowsocks, Tailscale exit); a saved one everywhere. */
    fun renameRoute(id: String, name: String) {
        setup.value.route(id)?.own?.let { renameServer(it, name); return }
        val n = name.trim().take(40).ifEmpty { return }
        store.update { s ->
            s.copy(routes = s.routes.map { r ->
                if (r.id != id) r else r.copy(wg = r.wg?.copy(name = n), ovpn = r.ovpn?.copy(name = n), ss = r.ss?.copy(name = n), tsName = r.tsExit?.let { n })
            })
        }
    }

    fun removeRoute(id: String) = store.update { s ->
        if (s.routes.size <= 1 && s.defaultRoute == id) return@update s
        val routes = s.routes.filter { it.id != id }
        s.copy(
            routes = routes,
            defaultRoute = if (s.defaultRoute == id) routes.firstOrNull()?.id ?: DIRECT else s.defaultRoute,
            apps = s.apps.filterValues { it != id },
        )
    }

    fun setDefault(id: String) = store.update { s -> s.copy(defaultRoute = id, apps = s.apps.filterValues { it != id }) }

    /** [routeId] null or equal to the default → app follows the default. */
    fun assign(pkg: String, routeId: String?) = store.update { s ->
        s.copy(apps = if (routeId == null || routeId == s.defaultRoute) s.apps - pkg else s.apps + (pkg to routeId))
    }

    fun setFilter(id: String, on: Boolean) = store.update { s -> s.copy(routes = s.routes.map { if (it.id == id) it.copy(filter = on) else it }) }

    fun toggleFavorite(loc: Location) = store.update { s ->
        val l = loc.favKey()
        // Matched by identity, so favourites saved with older ids are found (and dropped) too.
        val had = s.favorites.filter { it.favKey() == l }
        s.copy(favorites = if (had.isNotEmpty()) s.favorites - had.toSet() else s.favorites + l)
    }

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
