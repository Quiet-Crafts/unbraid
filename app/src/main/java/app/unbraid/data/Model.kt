@file:OptIn(SavedSites::class)

package app.unbraid.data

import kotlinx.serialization.Serializable

const val DIRECT = "direct"
/**
 * In a route's place for an app or a website: nothing of it goes anywhere while the VPN is connected. No connection,
 * no lookup, on no route, Direct included. Never a default route.
 */
const val BLOCK = "block"
/** [id] is a choice that is no route: [DIRECT] or [BLOCK]. */
fun builtIn(id: String) = id == DIRECT || id == BLOCK

/** Where a route exits. Only [provider] set = that provider's fastest server anywhere. */
@Serializable
data class Location(
    val provider: String = "nord",
    val countryId: Int? = null,
    val countryCode: String? = null,
    val country: String? = null,
    val cityId: Int? = null,
    val city: String? = null,
    /** Provider-specific region/city id (e.g. Mullvad "se-got", PIA "de-frankfurt"). */
    val region: String? = null,
    val group: String? = null,
    /** NordVPN only: null = NordLynx, else openvpn_udp / openvpn_tcp. Double VPN and Onion always use OpenVPN. */
    val protocol: String? = null,
    val pinned: Server? = null,
) {
    val label get() = pinned?.name ?: city?.let { "$country · $it" } ?: country ?: "Fastest"
    val code get() = countryCode ?: "··"
}

@Serializable
data class Server(val name: String, val host: String, val ip: String, val publicKey: String, val load: Int, val city: String = "", val country: String = "")

/** A plain WireGuard peer, e.g. a FRITZ!Box export. */
@Serializable
data class WgPeer(
    val name: String,
    val privateKey: String,
    val address: List<String>,
    val publicKey: String,
    val presharedKey: String? = null,
    val endpoint: String,
    val allowedIps: List<String>,
    val dns: List<String> = emptyList(),
    val mtu: Int = 1420,
    /** Seconds between keepalives as an imported config asks (0 = none); null = unbraid's 25. */
    val keepalive: Int? = null,
)

/** An imported OpenVPN profile (ExpressVPN, CyberGhost…): the raw .ovpn plus its login. */
@Serializable
data class OvpnProfile(val name: String, val config: String, val username: String = "", val password: String = "")

/** A Shadowsocks server (e.g. an Outline access key). */
@Serializable
data class SsProfile(val name: String, val server: String, val port: Int, val method: String, val password: String, val plugin: String? = null, val pluginOpts: String? = null)

/**
 * One exit. Exactly one source: a provider [loc], an imported [wg]/[ovpn]/[ss] server, or [tsExit] — a Tailscale
 * exit node (name or 100.x address) reached through the built-in Tailscale client.
 */
@Serializable
data class Route(
    val id: String,
    val loc: Location = Location(),
    val wg: WgPeer? = null,
    val ovpn: OvpnProfile? = null,
    val ss: SsProfile? = null,
    val tsExit: String? = null,
    /** Display name of the [tsExit] node; [tsExit] itself holds its tailnet IP so similarly named nodes can't be confused. */
    val tsName: String? = null,
    val hue: Int = 0,
    /**
     * The provider's own blocking (NordVPN Threat Protection Lite, IVPN AntiTracker…) for this route only: null follows
     * the provider's page ([Setup.protects]).
     */
    val filter: Boolean? = null,
    /** The saved [OwnServer] whose config [wg]/[ovpn]/[ss] is a copy of, so renaming or removing it reaches this route. */
    val own: String? = null,
    /** Whether unbraid checks that this own server still reaches the internet; null follows the config. */
    val checkInternet: Boolean? = null,
    /** Restored from a backup saved without keys: blocked until its own server gets its config again ([OwnServer.keyless]). */
    val keyless: Boolean = false,
) {
    val name get() = wg?.name ?: ovpn?.name ?: ss?.name ?: tsName ?: tsExit ?: loc.country ?: "Fastest"
    val code get() = when { wg != null -> "WG"; ovpn != null -> "OV"; ss != null -> "SS"; tsExit != null -> "TS"; else -> loc.code }
    /** Not tied to a provider account. */
    val imported get() = wg != null || ovpn != null || ss != null || tsExit != null
    val checksInternet: Boolean get() = when {
        tsExit != null || !imported -> false
        checkInternet != null -> checkInternet
        ss != null -> true
        wg != null -> wg.allowedIps.any { it == "0.0.0.0/0" || it == "::/0" }
        else -> ovpn?.config?.lineSequence()?.any { it.trim().startsWith("redirect-gateway", ignoreCase = true) } == true
    }
    /** Who carries this route: the provider, or the kind of own server. */
    val source get() = when { wg != null -> "WireGuard"; ovpn != null -> "OpenVPN"; ss != null -> "Shadowsocks"; tsExit != null -> "Tailscale"; else -> Providers.short(loc.provider) }
}

@Serializable
data class Home(
    val tailscale: Boolean = false,
    val tsAuthKey: String? = null,
    /** Self-hosted coordination server (Headscale); null = Tailscale's. */
    val tsControlUrl: String? = null,
    /** LANs behind a Tailscale subnet router, e.g. 192.168.178.0/24. Reached through Tailscale even on other Wi-Fi. */
    val subnets: List<String> = emptyList(),
    val wg: WgPeer? = null,
    /** A Headscale server's DNS suffix the user allowed to answer every app's lookups under it; Tailscale's own needs none. */
    val tsSuffixAllowed: String? = null,
)

@Serializable
data class Prefs(
    /** Moved to NordVPN's page ([Setup.opts]); read only to carry an older choice over ([Setup.migrated]). */
    val threatProtection: Boolean = true, val customDns: List<String> = emptyList(), val lan: Boolean = true,
    /** Local statistics; off until the user agrees, and nothing is recorded while off. */
    val stats: Boolean = false,
    /** unbraid's own DNS filter and where it applies. [customDns] is read only to carry an older choice over. */
    val filter: Filter = Filter(),
    /**
     * An app on [BLOCK] is cut off from the local network too: while one is, the LAN stays inside the VPN instead of
     * bypassing it ([lan]), so that app can't reach the devices around it. Off: the LAN bypasses as usual, for every app.
     */
    val blockLan: Boolean = true,
)

/** One of the user's own servers, added in Settings and picked for any route (which keeps a copy of the config). */
@Serializable
data class OwnServer(
    val id: String, val wg: WgPeer? = null, val ovpn: OvpnProfile? = null, val ss: SsProfile? = null,
    /** Restored from a backup saved without keys ([withoutSecrets]): its routes are blocked until the config is added again. */
    val keyless: Boolean = false,
) {
    val name get() = wg?.name ?: ovpn?.name ?: ss?.name.orEmpty()
    val kind get() = when { wg != null -> "WireGuard"; ovpn != null -> "OpenVPN"; else -> "Shadowsocks" }
    val code get() = when { wg != null -> "WG"; ovpn != null -> "OVPN"; else -> "SS" }
    /** The same server as [o] by what can be told without keys: kind, name and, where known, address and peer key. */
    fun sameAs(o: OwnServer) = kind == o.kind && name == o.name && (wg == null || (wg.endpoint == o.wg?.endpoint && wg.publicKey == o.wg.publicKey)) &&
        (ss == null || (ss.server == o.ss?.server && ss.port == o.ss.port && ss.method == o.ss.method))
}

/** [wg]'s private and preshared keys left out. */
fun WgPeer.withoutSecrets() = copy(privateKey = "", presharedKey = null)
/** An OpenVPN profile holds its keys inside the config: only the name stays. */
fun OvpnProfile.withoutSecrets() = OvpnProfile(name, "")
fun SsProfile.withoutSecrets() = copy(password = "", pluginOpts = null)
fun OwnServer.withoutSecrets() = copy(wg = wg?.withoutSecrets(), ovpn = ovpn?.withoutSecrets(), ss = ss?.withoutSecrets(), keyless = true)
fun Route.withoutSecrets() = if (!imported || tsExit != null) this else copy(wg = wg?.withoutSecrets(), ovpn = ovpn?.withoutSecrets(), ss = ss?.withoutSecrets(), keyless = true)

/**
 * A saved setup: a default exit and the apps' chosen routes. An app with an entry in [apps] chose that route, also when it
 * is the default, and keeps it when the default changes; an app without one follows the default. Routes are shared by every profile. An empty profile has no
 * [defaultRoute] yet ("") and can't be used until it gets one.
 */
@Serializable
data class Profile(
    val name: String, val defaultRoute: String, val apps: Map<String, String> = emptyMap(),
    /** Routes added to this profile, which it lists even while no app uses them. */
    val added: List<String> = emptyList(),
    /** Websites with a route of their own ([Sites]): address → route id or [DIRECT]. A website's route beats its app's. */
    @SavedSites val sites: Map<String, String> = emptyMap(),
) {
    val ready get() = defaultRoute.isNotEmpty()
    /** App [pkg] on route [routeId], its default included: a choice that stays when the default changes. Null = it follows the default. */
    fun assigned(pkg: String, routeId: String?) = copy(apps = if (routeId == null) apps - pkg else apps + (pkg to routeId))
    /** The routes this profile lists: its default, those its apps and websites use, and those added to it. */
    val own get() = setOf(defaultRoute) + apps.values + sites.values + added
}

/** The kinds of network a profile can be set to follow ([Setup.onNet]): Wi-Fi (a cable too) and mobile data. */
const val WIFI = "wifi"
const val MOBILE = "mobile"
val NET_KINDS = setOf(WIFI, MOBILE)

const val MAX_PROFILES = 20
/** The profile a quick-settings tile saves a setup in no profile as before switching, as it can't ask (an older one is replaced). */
const val PREVIOUS = "Previous"
/** A profile name's length in characters people see: an emoji or an accented letter counts once. */
const val PROFILE_NAME = 12

private fun chars(s: String) = java.text.BreakIterator.getCharacterInstance().apply { setText(s) }

/** Bidirectional controls and line separators, refused in profile names. */
private const val BIDI = "\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\u061C\u2028\u2029"

/** Characters people see in [s] ([PROFILE_NAME] counts these, not UTF-16 units). */
fun graphemes(s: String): Int = chars(s).let { b -> var n = 0; while (b.next() != java.text.BreakIterator.DONE) n++; n }

/** The first [n] characters people see of [s], never splitting one. */
fun String.takeGraphemes(n: Int): String = chars(this).let { b -> var end = 0; repeat(n) { b.next().takeIf { it != java.text.BreakIterator.DONE }?.let { end = it } }; take(end) }

/**
 * The newest [Setup.version]. 2: every value is written, defaults too (older files left out those equal to a default).
 * 3: website routes, which an older build would drop without a word; only a setup that has one is saved as 3 ([Setup.stamped]).
 */
const val VERSION = 3

@Serializable
data class Setup(
    val routes: List<Route> = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands"))),
    val defaultRoute: String = "nl",
    val apps: Map<String, String> = emptyMap(),
    /** Starred countries, cities or pinned servers, shown first in the picker. */
    val favorites: List<Location> = listOf(
        Location("nord", 228, "US", "United States"),
        Location("nord", 106, "IT", "Italy"),
        Location("nord", 153, "NL", "Netherlands"),
    ),
    val home: Home = Home(),
    val prefs: Prefs = Prefs(),
    val servers: List<OwnServer> = emptyList(),
    /** Saved format: 1 = provider pages and unbraid's filter; 2 = defaults written. New setups start at the current one ([Store]). */
    val version: Int = 0,
    /** Each provider's page settings: provider id → [Opt.key] → value. Unset keys take [Provider.defaults]. */
    val opts: Map<String, Map<String, String>> = emptyMap(),
    val profiles: List<Profile> = emptyList(),
    /** The profile in use's [Profile.added], kept live like [defaultRoute] and [apps]. */
    val added: List<String> = emptyList(),
    /** The profile in use's [Profile.sites], kept live like [defaultRoute] and [apps]. */
    @SavedSites val sites: Map<String, String> = emptyMap(),
    /** The profile to switch to by itself on a kind of network ([app.unbraid.vpn.AutoSwitch]): "wifi" or "mobile" → its name. */
    val onNet: Map<String, String> = emptyMap(),
    /** The profile in use; its saved copy follows every edit, as [defaultRoute] and [apps] hold it ([saved]). Null = none. */
    val profile: String? = null,
    /**
     * A new install's setup that no sign-in, import or edit has changed yet ([newSetup]): only then may a sign-in or an
     * import replace its placeholder route. A file without it (older versions) reads as false: set up by its user.
     */
    val pristine: Boolean = false,
    /** Apps a restore brought that weren't on this phone yet: they keep their route until [waitingUntil] (epoch ms), to be installed. */
    val waiting: Set<String> = emptySet(),
    val waitingUntil: Long = 0,
    /** Routes without apps connected only for a combined speed test; never saved. */
    @kotlinx.serialization.Transient val testing: Set<String> = emptySet(),
) {
    fun route(id: String?) = routes.firstOrNull { it.id == id }

    /** [profiles] as they stand: the one in use with the current default and exceptions. */
    val saved get() = profiles.map { if (it.name == profile) live else it }

    /** This setup routing as [p] does. */
    private fun routedAs(p: Profile) = copy(defaultRoute = p.defaultRoute, apps = p.apps, added = p.added, sites = p.sites)

    /** The live setup as a profile: what is in use. */
    private val live get() = Profile(profile.orEmpty(), defaultRoute, apps, added, sites)

    /** No profile is in use and none holds the setup in use: switching to another would lose it, unless [use] keeps it. */
    val loose get() = profile == null && profiles.none { it.copy(name = "") == live.copy(name = "") }

    /**
     * Switches to profile [name] (not an empty one); the one in use keeps what it has now. With [keepAs], a [loose] setup
     * in use is first saved as that profile (replacing an older one of that name); unchanged if it can't be (no room, or
     * [keepAs] is [name] itself).
     */
    fun use(name: String, keepAs: String? = null): Setup {
        if (name == profile) return this
        val p = profiles.firstOrNull { it.name == name && it.ready } ?: return this
        val from = if (keepAs != null && loose) kept(keepAs, name) ?: return this else this
        return from.routedAs(p).copy(profiles = from.saved, profile = p.name)
    }

    /** The setup in use saved as profile [name], not in use, over an older one of that name (any case) but never over [target]; null without room. */
    private fun kept(name: String, target: String): Setup? {
        val i = profiles.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        if (i >= 0 && profiles[i].name == target) return null
        // Over an older one, under the name it has: what names that profile ([onNet]) still does.
        val mine = live.copy(name = profiles.getOrNull(i)?.name ?: name)
        return if (i >= 0) copy(profiles = profiles.toMutableList().apply { set(i, mine) }) else if (profiles.size < MAX_PROFILES) copy(profiles = profiles + mine) else null
    }

    /** Saves the current setup as profile [name], now in use (nothing changes on the connection). */
    fun saveAs(name: String): Setup {
        // The first profile keeps every route shown until now; a later one, what the setup in use lists.
        val keep = if (profiles.isEmpty()) (added + (routes.map { it.id } - live.own)).distinct() else added
        return copy(profiles = saved + live.copy(name = name, added = keep), profile = name, added = keep)
    }

    /** Adds profile [name], not in use: a copy of the current setup, or [empty]. */
    fun add(name: String, empty: Boolean) = copy(profiles = saved + if (empty) Profile(name, "") else live.copy(name = name))

    /** The setup as profile [name] routes it (its default and exceptions), for showing it; itself for the one in use or null. */
    fun viewAs(name: String?) = profiles.firstOrNull { name != null && it.name == name && name != profile }?.let(::routedAs) ?: this

    /**
     * Routes a page leaves out when showing profile [name] (null = the setup in use): every route that profile doesn't
     * use or keep ([Profile.own]). With no profiles yet, none: every route shows.
     */
    fun hiddenRoutes(name: String?): Set<String> = if (profiles.isEmpty()) emptySet() else routes.map { it.id }.toSet() - viewAs(name).live.own

    /** The routes profile [name] (null = the setup in use) offers its apps: those it lists, never another profile's or one no profile uses. */
    fun offered(name: String?) = hiddenRoutes(name).let { h -> routes.filter { it.id !in h } }

    /** A route already exiting through saved server [s], to reuse rather than add another; one profile [name] shows comes first. */
    fun serverRoute(s: OwnServer, name: String?): String? {
        val rs = routes.filter { it.own == s.id && it.wg == s.wg && it.ovpn == s.ovpn && it.ss == s.ss }
        val hidden = hiddenRoutes(name)
        return (rs.firstOrNull { it.id !in hidden } ?: rs.firstOrNull())?.id
    }

    /** Changes the default and exceptions of profile [name] with [f]: only its saved copy, unless it is the one in use (or null). */
    fun editing(name: String?, f: (Profile) -> Profile): Setup {
        if (name == null || name == profile || profiles.none { it.name == name }) return routedAs(f(live))
        return copy(profiles = profiles.map { if (it.name == name) f(it) else it })
    }

    fun rename(old: String, new: String) = copy(profiles = profiles.map { if (it.name == old) it.copy(name = new) else it }, profile = if (profile == old) new else profile,
        onNet = onNet.mapValues { if (it.value == old) new else it.value })

    /** Deletes profile [name]. The current routes stay as they are, even when it was the one in use. */
    fun forget(name: String) = copy(profiles = profiles.filter { it.name != name }, profile = profile.takeIf { it != name }, onNet = onNet.filterValues { it != name })
        .withoutUnlisted(profiles.firstOrNull { it.name == name }?.own.orEmpty())

    /**
     * Of routes [ids], those no profile lists any more (not the setup in use, nor a saved one): with profiles, a page
     * shows only what a profile lists, so such a route is offered nowhere. Without profiles every route shows: none.
     */
    fun unlisted(ids: Set<String>): Set<String> = if (profiles.isEmpty()) emptySet() else ids - live.own - saved.flatMap { it.own }.toSet()

    /** Routes [ids] that no profile lists any more go with their last listing: none is a default, nor used by an app. */
    fun withoutUnlisted(ids: Set<String>) = unlisted(ids).let { g -> if (g.isEmpty()) this else copy(routes = routes.filter { it.id !in g }) }

    /**
     * Back to what [ran] (the setup the engine still runs after refusing a switch) uses: its profile when still saved, its
     * default and exceptions as far as their routes still exist. The refused profile keeps what it had.
     */
    fun back(ran: Setup): Setup {
        val ok = { id: String -> builtIn(id) || route(id) != null }
        // Its profile again only if still as it ran: one edited meanwhile keeps those edits, the routes run unprofiled.
        val same = profiles.firstOrNull { it.name == ran.profile }?.takeIf { it == ran.live }
        return copy(profiles = saved, profile = same?.name,
            defaultRoute = ran.defaultRoute.takeIf(ok) ?: defaultRoute, apps = ran.apps.filterValues(ok), added = ran.added.filter(ok), sites = ran.sites.filterValues(ok))
    }

    /** Moves profile [name] [by] places (negative = earlier). */
    fun move(name: String, by: Int): Setup {
        val i = profiles.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: return this
        return copy(profiles = profiles.toMutableList().apply { add((i + by).coerceIn(0, lastIndex), removeAt(i)) })
    }

    /** [raw] trimmed, if it can name a profile: not blank, at most [PROFILE_NAME] characters, not taken (but by [except]). */
    fun profileName(raw: String, except: String? = null) = raw.trim().takeIf { n ->
        // No control or direction-override characters: a name shows in tiles and shortcuts, where they could disguise it.
        n.isNotEmpty() && graphemes(n) <= PROFILE_NAME && n.none { it.isISOControl() || it in BIDI } &&
            profiles.none { it.name != except && it.name.equals(n, ignoreCase = true) }
    }

    /**
     * After routes [gone] were removed: apps on them follow their default and websites on them their app, here and in
     * every saved profile, and the lists drop them. A default is never changed here: removing one is refused first ([refusesRemoving]). Only [gone] is
     * mended: a route missing since the settings were read stays named, for [defaultMissing] and [appsOnMissing] to report.
     */
    fun tidy(gone: Set<String>): Setup {
        val ok = { id: String -> id !in gone }
        val whole = { p: Profile -> p.apps.values.all(ok) && p.added.all(ok) && p.sites.values.all(ok) }
        if (gone.isEmpty() || whole(live) && profiles.all(whole)) return this
        val mend = { p: Profile -> p.copy(apps = p.apps.filterValues(ok), added = p.added.filter(ok), sites = p.sites.filterValues(ok)) }
        return routedAs(mend(live)).copy(profiles = profiles.map(mend))
    }

    /** The default of the setup in use names no route (damaged or older settings): the engine refuses to connect until another is chosen. */
    val defaultMissing get() = defaultRoute != DIRECT && route(defaultRoute) == null
    /** Apps whose chosen route no longer exists: blocked until they get another route or follow the default. */
    val appsOnMissing get() = apps.filterValues { !builtIn(it) && route(it) == null }.keys
    /** Websites whose route no longer exists: blocked until they get another route or are removed. */
    val sitesOnMissing get() = sites.filterValues { !builtIn(it) && route(it) == null }.keys
    /** Saved profiles, not in use, whose default names no route: they need another default before they can be used. */
    val profilesMissingDefault get() = profiles.filter { it.name != profile && it.ready && it.defaultRoute != DIRECT && route(it.defaultRoute) == null }.map { it.name }

    /**
     * Why routes [gone] (what the user removes, named [name]) can't go: one is the default of the setup in use or of a
     * saved profile. Null when they may go. Nothing picks another default by itself.
     */
    fun refusesRemoving(gone: Set<String>, name: String): CarriesDefault? {
        val live = defaultRoute in gone
        val saved = profiles.filter { it.name != profile && it.defaultRoute in gone }.map { it.name }
        return if (live || saved.isNotEmpty()) CarriesDefault(name, live, saved) else null
    }

    /**
     * After signing in to [provider] ([before]: the providers signed in until then). A setup with no route gets one there,
     * and a new install's [untouched] placeholder is pointed at it unless its own provider can carry it. Any other setup
     * stays as it is: a sign-in never rewrites a route the user set up, nor the default.
     */
    fun signedIn(provider: String, before: Set<String>): Setup = when {
        !untouched -> this
        routes.single().loc.provider !in before + provider -> copy(routes = routes.map { it.copy(loc = Location(provider)) }, pristine = false)
        else -> copy(pristine = false) // in use from now on: a later sign-in elsewhere leaves it where it is
    }

    /** A new install's setup as it started ([pristine]): only the placeholder route, as the default, nothing chosen or saved. */
    val untouched get() = pristine && Setup().let { routes == it.routes && defaultRoute == it.defaultRoute } && apps.isEmpty() && sites.isEmpty() && profiles.isEmpty() && added.isEmpty()

    /**
     * This setup after a change from [old]: one that changes what the user sets up is no longer [pristine]. A saved
     * server alone routes nothing: an import saves it first, then gives it the route that replaces the placeholder.
     */
    fun after(old: Setup) = if (!pristine || (routes == old.routes && defaultRoute == old.defaultRoute && apps == old.apps && sites == old.sites && profiles == old.profiles &&
        added == old.added && profile == old.profile && home == old.home)) this else copy(pristine = false)

    /**
     * Route [r] (id [id], new or replacing one) given an imported source. With no provider signed in ([alone]) a new
     * install's placeholder goes and [r] is the default; anything set up before stays, its default too.
     */
    fun imported(id: String, r: Route, alone: Boolean): Setup {
        val next = copy(routes = if (route(id) != null) routes.map { if (it.id == id) r else it } else routes + r)
        if (!alone || !untouched) return next
        val gone = routes.map { it.id }.toSet() - id
        return next.copy(routes = next.routes.filter { it.id !in gone }, apps = next.apps.filterValues { it !in gone }, defaultRoute = id, pristine = false)
    }
    /**
     * The route an import of a server for [target] goes to (null: saved only, as from Settings). The first one into a new
     * install's [untouched] setup, with no provider signed in ([alone]), gets a new route that replaces the placeholder,
     * wherever it was imported from: a reset with the VPN running lands on Routes, not on the first-run list.
     */
    fun importFor(target: String?, alone: Boolean) = target ?: "".takeIf { alone && untouched }
    fun routeOf(pkg: String) = apps[pkg] ?: defaultRoute
    /** App [pkg] has no route of its own: it goes where the default goes. */
    fun follows(pkg: String) = pkg !in apps
    /**
     * App [pkg] chose Direct itself and doesn't pass through for the filter: Android leaves it outside unbraid (no filter,
     * no home network), unless lockdown keeps every app in. An app that only follows a Direct default passes through.
     */
    fun outside(pkg: String) = apps[pkg] == DIRECT && !prefs.filter.through(pkg)
    /** How many of [installed] follow the default: the apps a new default would move. */
    fun followers(installed: Collection<String>) = installed.count(::follows)
    /**
     * Routes that carry traffic right now: the default plus any with apps or saved websites (and those up for a speed test).
     * A website counts whether its rule applies or not, so tunnels, clash order and route ownership never depend on what
     * answers a name; a route used only by a website that is "Not applied" keeps its tunnel open.
     */
    val activeRoutes get() = routes.filter { r -> r.id == defaultRoute || r.id in testing || apps.containsValue(r.id) || sites.containsValue(r.id) }
    /** The Tailscale exit node in use, if any active route goes out through one (Tailscale allows one at a time). */
    val tsExit get() = activeRoutes.firstNotNullOfOrNull { it.tsExit }
    /** Tailscale runs for home access or for an exit-node route. */
    val tailscale get() = home.tailscale || tsExit != null
    /** Nothing goes through a VPN (Direct default, no app on a route, no home tunnel): running protects nothing. */
    val directOnly get() = defaultRoute == DIRECT && (apps.values + Sites.applied(this).values).all { it == DIRECT } && home.wg == null && !home.tailscale

    /** Website [site] (as [Sites.clean] gives it) on route [routeId], or with no route of its own (null), in profile [name] ([editing]). */
    fun site(name: String?, site: String, routeId: String?) = editing(name) { it.copy(sites = if (routeId == null) it.sites - site else it.sites + (site to routeId)) }

    /**
     * As saved: format 3 only with a website route or a Block somewhere, so a setup without either still opens in a build
     * from before them (which would drop the websites, and take a blocked app for one on a route that is gone).
     */
    val stamped get() = copy(version = if ((profiles + live).all { it.sites.isEmpty() && BLOCK !in it.apps.values }) minOf(version, 2) else VERSION)

    /** [provider]'s page setting [key], or its default. */
    fun opt(provider: String, key: String) = opts[provider]?.get(key) ?: Providers.get(provider)?.defaults?.get(key) ?: app.unbraid.data.COMMON_DEFAULTS[key]

    /** Whether route [r] uses its provider's own blocking: its own choice, else the provider page's. */
    fun protects(r: Route) = !r.imported && (r.filter ?: (opt(r.loc.provider, PROTECT) == "on"))

    /** Route [id] uses its provider's own blocking ([on]) or not; a choice equal to its provider page's is dropped, so it follows the page. */
    fun blocking(id: String, on: Boolean) = copy(routes = routes.map { r -> if (r.id != id) r else r.copy(filter = on.takeIf { it != (opt(r.loc.provider, PROTECT) == "on") }) })

    /**
     * Older saves kept Threat Protection Lite in [Prefs] (it now lives on NordVPN's page) and a custom DNS server for every
     * route (now unbraid's filter set to that server, on VPN routes).
     */
    fun migrated(): Setup {
        if (version >= VERSION) return this
        var s = copy(version = VERSION)
        if (version < 1) {
            // NordVPN routes had no blocking switch of their own: a flag one kept from another provider meant nothing then.
            s = s.copy(routes = routes.map { if (!it.imported && it.loc.provider == "nord") it.copy(filter = null) else it })
            if (!prefs.threatProtection) s = s.copy(opts = opts + ("nord" to (opts["nord"].orEmpty() + (PROTECT to "off"))), prefs = s.prefs.copy(threatProtection = true))
            prefs.customDns.firstOrNull()?.let { s = s.copy(prefs = s.prefs.copy(customDns = emptyList(), filter = s.prefs.filter.takeIf { it.id != null } ?: Filter(Filters.OWN, it, routes = true))) }
        }
        // 1 to 2: a value missing from an older file reads as the default it had then, and is written from now on. Every
        // stored app entry stays as it is, also one equal to its default (it is a choice now); an app without one follows
        // the default, as before. So no app's routing, DNS or home network changes with the update.
        return s
    }
}

/** The setup of a new install (or of a reset): the current format, and [Setup.pristine]. */
fun newSetup() = Setup(version = VERSION, pristine = true)

/** A removal refused: [name] carries the default route of the setup in use ([live]) or of the saved [profiles]. */
data class CarriesDefault(val name: String, val live: Boolean, val profiles: List<String>) {
    /** One sentence for the screen. */
    val text get() = if (live) "$name carries your default route. Choose another default route first."
        else "$name carries the default route of ${profiles.joinToString(", ")}. Choose another default route there first."
}

@Serializable
data class City(val id: Int, val name: String, val key: String? = null)

/**
 * What makes two locations the same place: Nord ids are Nord's own; other providers' ids are derived and changed once
 * (positions → [stableId]), so those compare by code, city and region instead. Pins compare by server name.
 */
fun Location.ident() = copy(
    countryId = countryId.takeIf { provider == "nord" }, cityId = cityId.takeIf { provider == "nord" },
    pinned = pinned?.let { Server(it.name, "", "", "", -1, "", "") },
)

/** How a location is remembered as a favourite: its [ident], without server type or protocol. */
fun Location.favKey() = ident().copy(group = null, protocol = null)

/** An id that stays the same when a provider adds or drops places, so saved routes and favourites keep matching. */
fun stableId(key: String) = key.lowercase().hashCode() and 0x7fffffff

@Serializable
data class Country(val id: Int, val name: String, val code: String, val serverCount: Int = 0, val cities: List<City> = emptyList())
