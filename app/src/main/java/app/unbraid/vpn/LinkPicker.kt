package app.unbraid.vpn

import app.unbraid.data.Addr
import app.unbraid.data.AuthException
import app.unbraid.data.BLOCK
import app.unbraid.data.DIRECT
import app.unbraid.data.Link
import app.unbraid.data.OvpnLink
import app.unbraid.data.Provider
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.data.Sites
import app.unbraid.data.SsLink
import app.unbraid.data.TsLink
import app.unbraid.data.WgLink

/** Chooses the tunnel each active route runs. Kept free of Android so it can be tested. */
object LinkPicker {
    /** An offline pick's route that needs its provider for a tunnel. */
    open class Waiting : Fail("waiting for a server")

    /** A running tunnel a provider setting now rules out (Mullvad-owned only): its apps wait blocked, not on it. */
    class Unsuited : Waiting()

    /** A route restored without its keys: blocked, never retried, until its own server gets its config again. */
    class KeyLeftOut(name: String) : AuthException("Add the config for $name again in Settings, Providers & servers")

    /** [blocked]: routes that got no tunnel, with why; their apps are blocked until one is found ([Compiler.Input.blocked]). */
    class Result(val links: Map<String, Link>, val remember: Map<String, Link>, val blocked: Map<String, Exception> = emptyMap())

    /** Fallback-cache key: a pinned route is its own entry, so it can never inherit another server's tunnel. */
    fun serverKey(r: Route, l: Link) = "${r.loc.provider}|${serverId(l.server)}"

    fun cacheKey(r: Route) = "${r.loc.provider}|${r.loc.copy(pinned = null)}|${r.loc.pinned?.let(::pinId).orEmpty()}"

    private fun serverId(s: Server) = s.ip.ifEmpty { s.host }

    /** A pin restored without its address is its name too: AirVPN gives every server of a country one host name. */
    private fun pinId(s: Server) = if (s.ip.isEmpty()) "${s.host}|${s.name}" else s.ip

    /** A pinned server as its provider finds it again: by host name, and on AirVPN, whose servers share one per country, by name too. */
    fun pinKey(provider: String, s: Server) = "$provider|${s.host}" + if (provider == "airvpn") "|${s.name}" else ""

    /** A pinned server no longer offered, for its protocol: Nord's lists differ by protocol, so one list without it rules out only routes on it. */
    fun goneKey(l: app.unbraid.data.Location) = l.pinned?.let { pinKey(l.provider, it) + "|" + if (l.provider == "nord") app.unbraid.data.Nord.tech(l) else l.protocol.orEmpty() }

    /**
     * The route's saved tunnel. A pin's is its own server's only ([pinKey]), also under the key 3.1.1 saved a pin restored
     * without its address with (its host name alone), until a pick saves it under its own.
     */
    private fun saved(r: Route, last: Map<String, Link>): Link? {
        val pin = r.loc.pinned ?: return last[cacheKey(r)]
        val older = "${r.loc.provider}|${r.loc.copy(pinned = null)}|${serverId(pin)}"
        // Not for a pin without its address: under 3.1.1 that key could hold another server of the same host name.
        return listOfNotNull(cacheKey(r), older.takeIf { pin.ip.isNotEmpty() }).firstNotNullOfOrNull { k -> last[k]?.takeIf { pinKey(r.loc.provider, it.server) == pinKey(r.loc.provider, pin) } }
    }

    /**
     * Imported configs as they are; provider routes keep the current server if the route's location didn't change, else
     * get their last one while it is still among the best (one-key providers, [again]), else the best now, falling back
     * to the last good one if the API is down. An unpinned route that lands on a server another route already uses is moved to a different server of
     * its location. A pinned route is never moved: it shares that server's tunnel (see [Compiler]) or fails. A route that
     * fails (signed out, refused, unreachable) is [Result.blocked]: the others still run.
     */
    fun pick(
        setup: Setup,
        keep: Map<String, Link>,
        applied: Setup?,
        appliedAccounts: Map<String, String>,
        accounts: Map<String, String>,
        last: Map<String, Link>,
        /** Servers ("provider|ip") to stay away from: ones that just stopped answering. */
        avoid: Set<String> = emptySet(),
        /** Routes whose provider refused the account it still has: blocked as they are, never given a cached tunnel. */
        refused: Map<String, Exception> = emptyMap(),
        /** No provider calls: a route without a tunnel it can keep is blocked, for a policy to install before them. */
        offline: Boolean = false,
        providers: (String) -> Provider?,
    ): Result {
        val used = avoid.toMutableSet()
        val out = linkedMapOf<String, Link>()
        val blocked = linkedMapOf<String, Exception>()
        for (r in setup.activeRoutes) {
            refused[r.id]?.let { blocked[r.id] = it; continue }
            // Also a route given a server still waiting for its config, however it got there.
            if (r.keyless || setup.servers.any { it.id == r.own && it.keyless }) { blocked[r.id] = KeyLeftOut(r.name); continue }
            val link = try { when {
                r.wg != null -> WgLink(Server(r.wg.name, r.wg.endpoint.substringBeforeLast(':'), "", r.wg.publicKey, 0), r.wg)
                r.ovpn != null -> OvpnLink(Server(r.ovpn.name, r.ovpn.name, "", "", 0), r.ovpn)
                r.ss != null -> SsLink(Server(r.ss.name, r.ss.server, r.ss.server, "", 0), r.ss)
                r.tsExit != null -> TsLink(Server(r.name, r.tsExit, "", "", 0))
                else -> keep[r.id]?.takeIf {
                    applied?.route(r.id)?.let { a -> !a.imported && a.loc == r.loc } == true && appliedAccounts[r.loc.provider] == accounts[r.loc.provider] &&
                        trusted(it, r, accounts, providers) && Addr.usable(it)
                } ?: if (offline) throw if (accounts[r.loc.provider] == null) AuthException("Sign in to ${providers(r.loc.provider)?.name ?: r.loc.provider} for ${r.name}")
                        else if (keep[r.id]?.let { suits(it, r, providers) } == false) Unsuited() else Waiting()
                    else fresh(r, accounts, last, used, providers).also { if (!Addr.usable(it)) throw Addr.refused(it.server.name) }
            } } catch (e: Exception) { blocked[r.id] = e; continue }
            if (!r.imported) used += "${r.loc.provider}|${serverId(link.server)}"
            out[r.id] = link
        }
        // A server no longer offered is gone for every route pinned to it, not only the one that asked.
        val gone = blocked.entries.filter { it.value is app.unbraid.data.NoLongerOffered }.mapNotNull { (id, e) -> setup.route(id)?.loc?.let(::goneKey)?.let { it to e } }.toMap()
        setup.activeRoutes.filter { !it.imported && it.id in out }.forEach { r -> goneKey(r.loc)?.let { gone[it] }?.let { blocked[r.id] = it; out.remove(r.id) } }
        // A provider that refused its account refuses it for every route: none of them keeps a tunnel on it.
        val refusedBy = blocked.entries.filter { it.value is AuthException }.mapNotNull { (id, e) -> setup.route(id)?.takeIf { !it.imported }?.let { it.loc.provider to e } }.toMap()
        setup.activeRoutes.filter { !it.imported && it.id in out }.forEach { r -> refusedBy[r.loc.provider]?.let { blocked[r.id] = it; out.remove(r.id) } }
        // A list read for a later route can rule out an earlier one's server: every unpinned route is checked against the last.
        setup.activeRoutes.filter { !it.imported && it.loc.pinned == null && out[it.id]?.let { l -> suits(l, it, providers) } == false }
            .forEach { r -> out.remove(r.id); blocked[r.id] = unsuited(providers(r.loc.provider)?.name ?: r.loc.provider, r) }
        // A tunnel that would clash with another one's identity is blocked, never started beside it.
        Compiler.clashes(setup, out).forEach { id -> out.remove(id); blocked[id] = Fail(Compiler.CLASH) }
        val remember = setup.activeRoutes.filter { !it.imported }.mapNotNull { r -> out[r.id]?.let { cacheKey(r) to it } }.toMap()
        return Result(out, remember, blocked)
    }

    /**
     * [links] with each provider WireGuard tunnel on the entry address, port and MTU its provider page asks for: a fixed port,
     * or with Automatic the [step]-th of the server's ports (a step per provider moves on when its tunnels stop answering).
     * Imported configs keep their own MTU, at most [app.unbraid.data.MTU_MIN].
     */
    fun ported(setup: Setup, links: Map<String, Link>, step: Map<String, Int>): Map<String, Link> {
        val provider = { id: String -> setup.route(id)?.takeIf { !it.imported }?.loc?.provider }
        // One list per provider, so a tunnel saved without one (offline) still takes the same port as its provider's others.
        val lists = links.entries.groupBy({ provider(it.key) }, { (it.value as? WgLink)?.ports.orEmpty() }).mapValues { (_, l) -> l.firstOrNull { it.isNotEmpty() }.orEmpty() }
        return links.mapValues { (id, l) -> portedOne(setup, provider(id), l, lists, step) }
    }

    private fun portedOne(setup: Setup, p: String?, l: Link, lists: Map<String?, List<Int>>, step: Map<String, Int>): Link {
        if (l !is WgLink) return l
        if (p == null) return l.copy(peer = l.peer.copy(mtu = minOf(l.peer.mtu, app.unbraid.data.MTU_MIN)))
        val host = setup.opt(p, app.unbraid.data.ENTRY_OPT)?.toIntOrNull()?.let { l.entries.getOrNull(it - 1) } ?: l.peer.endpoint.substringBeforeLast(':')
        val port = setup.opt(p, app.unbraid.data.PORT_OPT)?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: lists[p]?.takeIf { it.isNotEmpty() }?.let { it[(step[p] ?: 0) % it.size] } ?: l.peer.endpoint.substringAfterLast(':')
        return l.copy(peer = l.peer.copy(endpoint = "$host:$port", mtu = app.unbraid.data.mtu(setup.opt(p, app.unbraid.data.MTU_OPT))))
    }

    /**
     * True if an app that went Direct under [old] goes to one of [routes] under [new] (explicitly or by default). Any change
     * to the websites counts: one added, moved or removed (a Direct one too), or one whose rule starts or stops applying
     * (the local network or the tailnet stops answering its name), can leave traffic Direct that [new] sends to them.
     * [oldSuffix] is the tailnet domain the installed config was compiled with, [newSuffix] the one the new config gets.
     * So does an app newly on Block: what refuses it is installed at once, not after the routes that wait.
     */
    @OptIn(app.unbraid.data.SavedSites::class)
    fun leavesDirect(old: Setup?, new: Setup, routes: Set<String>, oldSuffix: String? = null, newSuffix: String? = null): Boolean = old != null && routes.any { id ->
        (old.apps.keys + new.apps.keys).any { new.routeOf(it) == id && old.routeOf(it) == DIRECT } || (new.defaultRoute == id && old.defaultRoute == DIRECT) ||
            old.sites != new.sites || Sites.applied(old, reported = oldSuffix) != Sites.applied(new, reported = newSuffix) || new.apps.any { (pkg, to) -> to == BLOCK && old.apps[pkg] != BLOCK } // newly blocked: not a moment longer on what it had
    }

    /** A kept or cached tunnel is reused only if its provider still vouches for its server key ([Provider.trusts]). */
    private fun trusted(l: Link, r: Route, accounts: Map<String, String>, providers: (String) -> Provider?): Boolean {
        val p = providers(r.loc.provider) ?: return true
        // A page setting can rule out the server of an unpinned route: it is picked again, or blocked, never kept.
        if (!suits(l, r, providers)) return false
        val acc = accounts[p.id] ?: return true
        return p.trusts(l, acc)
    }

    /** A pinned server always suits; an unpinned route's must suit its provider's page settings ([Provider.suits]). */
    /** Whether every provider route's [links] still suits its provider's settings as its latest list says. */
    fun stillSuits(setup: Setup, links: Map<String, Link>, providers: (String) -> Provider?) =
        setup.activeRoutes.all { r -> r.imported || links[r.id]?.let { suits(it, r, providers) } != false }

    private fun suits(l: Link, r: Route, providers: (String) -> Provider?) = r.loc.pinned != null || providers(r.loc.provider)?.suits(l.server) != false

    /** The first of [links] for unpinned route [r] whose server suits [p]'s settings as its last list confirmed; none is refused. */
    private fun suited(p: Provider, r: Route, vararg links: Link?) = links.filterNotNull().firstOrNull { p.suits(it.server) } ?: throw unsuited(p.name, r)

    private fun unsuited(provider: String, r: Route) = Fail("No $provider server for ${r.name} suits your settings right now")

    private fun fresh(r: Route, accounts: Map<String, String>, last: Map<String, Link>, used: Set<String>, providers: (String) -> Provider?): Link {
        val p = providers(r.loc.provider) ?: error("Unknown provider ${r.loc.provider}")
        val acc = accounts[p.id] ?: throw AuthException("Sign in to ${p.name} for ${r.name}")
        val pinned = r.loc.pinned
        // A pinned server starts from its last refreshed copy, so going offline never brings back older details.
        val loc = pinned?.let { saved(r, last)?.server?.let { r.loc.copy(pinned = it) } } ?: r.loc
        val l = try {
            again(p, r, acc, last, used) ?: p.connect(loc, acc)
        } catch (e: Exception) {
            if (e is AuthException || e is app.unbraid.data.NoLongerOffered || e is Addr.Refused) throw e
            val cached = saved(r, last)?.takeIf { trusted(it, r, accounts, providers) }
            // A cached tunnel at an address that can't be used is no fallback: the user is told to pick the server again.
            if (cached != null && !Addr.usable(cached)) throw Addr.refused(cached.server.name)
            cached ?: throw if (pinned != null) Fail("${r.name}: ${pinned.name} is unreachable") else e
        }
        if (pinned != null) return l
        if ("${p.id}|${serverId(l.server)}" !in used) return suited(p, r, l)
        val other = runCatching {
            p.servers(r.loc, acc, 10).firstOrNull { "${p.id}|${serverId(it)}" !in used }?.let { p.connect(r.loc.copy(pinned = it), acc) }
        }.onFailure { if (it is AuthException) throw it }.getOrNull()
        // Another server, else the one already found (shared with the route that has it), whichever still suits.
        return suited(p, r, other, l)
    }

    /**
     * An unpinned route's last WireGuard server once more, while the provider still lists it among its ten best and it isn't
     * avoided: NordVPN gives an account one key ([Provider.oneKey]), and moving it can leave the tunnel dead for minutes.
     * Null, also when that fails for any reason but the account, picks as usual.
     */
    private fun again(p: Provider, r: Route, acc: String, last: Map<String, Link>, used: Set<String>): Link? {
        if (!p.oneKey || r.loc.pinned != null) return null
        val id = (last[cacheKey(r)] as? WgLink)?.server?.let(::serverId)?.takeIf { "${p.id}|$it" !in used } ?: return null
        return try {
            p.servers(r.loc, acc, 10).firstOrNull { serverId(it) == id }?.let { p.connect(r.loc.copy(pinned = it), acc) }
        } catch (e: Exception) {
            if (e is AuthException) throw e
            null
        }
    }
}
