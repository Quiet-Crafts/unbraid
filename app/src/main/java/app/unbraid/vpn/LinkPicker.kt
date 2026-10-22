package app.unbraid.vpn

import app.unbraid.data.AuthException
import app.unbraid.data.DIRECT
import app.unbraid.data.Link
import app.unbraid.data.OvpnLink
import app.unbraid.data.Provider
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.data.SsLink
import app.unbraid.data.TsLink
import app.unbraid.data.WgLink

/** Chooses the tunnel each active route runs. Kept free of Android so it can be tested. */
object LinkPicker {
    /** An offline pick's route that needs its provider for a tunnel. */
    class Waiting : Fail("waiting for a server")

    /** [blocked]: routes that got no tunnel, with why; their apps are blocked until one is found ([Compiler.Input.blocked]). */
    class Result(val links: Map<String, Link>, val remember: Map<String, Link>, val blocked: Map<String, Exception> = emptyMap())

    /** Fallback-cache key: a pinned route is its own entry, so it can never inherit another server's tunnel. */
    fun serverKey(r: Route, l: Link) = "${r.loc.provider}|${serverId(l.server)}"

    fun cacheKey(r: Route) = "${r.loc.provider}|${r.loc.copy(pinned = null)}|${r.loc.pinned?.let(::serverId).orEmpty()}"

    private fun serverId(s: Server) = s.ip.ifEmpty { s.host }

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
            val link = try { when {
                r.wg != null -> WgLink(Server(r.wg.name, r.wg.endpoint.substringBeforeLast(':'), "", r.wg.publicKey, 0), r.wg)
                r.ovpn != null -> OvpnLink(Server(r.ovpn.name, r.ovpn.name, "", "", 0), r.ovpn)
                r.ss != null -> SsLink(Server(r.ss.name, r.ss.server, r.ss.server, "", 0), r.ss)
                r.tsExit != null -> TsLink(Server(r.name, r.tsExit, "", "", 0))
                else -> keep[r.id]?.takeIf {
                    applied?.route(r.id)?.let { a -> !a.imported && a.loc == r.loc } == true && appliedAccounts[r.loc.provider] == accounts[r.loc.provider] &&
                        trusted(it, r, accounts, providers)
                } ?: if (offline) throw if (accounts[r.loc.provider] == null) AuthException("Sign in to ${providers(r.loc.provider)?.name ?: r.loc.provider} for ${r.name}") else Waiting()
                    else fresh(r, accounts, last, used, providers)
            } } catch (e: Exception) { blocked[r.id] = e; continue }
            if (!r.imported) used += "${r.loc.provider}|${serverId(link.server)}"
            out[r.id] = link
        }
        // A server no longer offered is gone for every route pinned to it, not only the one that asked.
        val gone = blocked.entries.filter { it.value is app.unbraid.data.NoLongerOffered }.mapNotNull { (id, e) -> setup.route(id)?.loc?.let { l -> l.pinned?.let { "${l.provider}|${it.host}" to e } } }.toMap()
        setup.activeRoutes.filter { !it.imported && it.id in out }.forEach { r -> r.loc.pinned?.let { gone["${r.loc.provider}|${it.host}"] }?.let { blocked[r.id] = it; out.remove(r.id) } }
        // A provider that refused its account refuses it for every route: none of them keeps a tunnel on it.
        val refusedBy = blocked.entries.filter { it.value is AuthException }.mapNotNull { (id, e) -> setup.route(id)?.takeIf { !it.imported }?.let { it.loc.provider to e } }.toMap()
        setup.activeRoutes.filter { !it.imported && it.id in out }.forEach { r -> refusedBy[r.loc.provider]?.let { blocked[r.id] = it; out.remove(r.id) } }
        // A tunnel that would clash with another one's identity is blocked, never started beside it.
        Compiler.clashes(setup, out).forEach { id -> out.remove(id); blocked[id] = Fail(Compiler.CLASH) }
        val remember = setup.activeRoutes.filter { !it.imported }.mapNotNull { r -> out[r.id]?.let { cacheKey(r) to it } }.toMap()
        return Result(out, remember, blocked)
    }

    /**
     * [links] with each provider WireGuard tunnel on the entry address and port its provider page asks for: a fixed port,
     * or with Automatic the [step]-th of the server's ports (a step per provider moves on when its tunnels stop answering).
     */
    fun ported(setup: Setup, links: Map<String, Link>, step: Map<String, Int>): Map<String, Link> {
        val provider = { id: String -> setup.route(id)?.takeIf { !it.imported }?.loc?.provider }
        // One list per provider, so a tunnel saved without one (offline) still takes the same port as its provider's others.
        val lists = links.entries.groupBy({ provider(it.key) }, { (it.value as? WgLink)?.ports.orEmpty() }).mapValues { (_, l) -> l.firstOrNull { it.isNotEmpty() }.orEmpty() }
        return links.mapValues { (id, l) -> portedOne(setup, provider(id), l, lists, step) }
    }

    private fun portedOne(setup: Setup, p: String?, l: Link, lists: Map<String?, List<Int>>, step: Map<String, Int>): Link {
        if (l !is WgLink || p == null) return l
        val host = setup.opt(p, app.unbraid.data.ENTRY_OPT)?.toIntOrNull()?.let { l.entries.getOrNull(it - 1) } ?: l.peer.endpoint.substringBeforeLast(':')
        val port = setup.opt(p, app.unbraid.data.PORT_OPT)?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: lists[p]?.takeIf { it.isNotEmpty() }?.let { it[(step[p] ?: 0) % it.size] } ?: l.peer.endpoint.substringAfterLast(':')
        return l.copy(peer = l.peer.copy(endpoint = "$host:$port"))
    }

    /** True if an app that went Direct under [old] goes to one of [routes] under [new] (explicitly or by default). */
    fun leavesDirect(old: Setup?, new: Setup, routes: Set<String>): Boolean = old != null && routes.any { id ->
        (old.apps.keys + new.apps.keys).any { new.routeOf(it) == id && old.routeOf(it) == DIRECT } || (new.defaultRoute == id && old.defaultRoute == DIRECT)
    }

    /** A kept or cached tunnel is reused only if its provider still vouches for its server key ([Provider.trusts]). */
    private fun trusted(l: Link, r: Route, accounts: Map<String, String>, providers: (String) -> Provider?): Boolean {
        val p = providers(r.loc.provider) ?: return true
        val acc = accounts[p.id] ?: return true
        return p.trusts(l, acc)
    }

    private fun fresh(r: Route, accounts: Map<String, String>, last: Map<String, Link>, used: Set<String>, providers: (String) -> Provider?): Link {
        val p = providers(r.loc.provider) ?: error("Unknown provider ${r.loc.provider}")
        val acc = accounts[p.id] ?: throw AuthException("Sign in to ${p.name} for ${r.name}")
        val pinned = r.loc.pinned
        // A pinned server starts from its last refreshed copy, so going offline never brings back older details.
        val loc = pinned?.let { pin -> last[cacheKey(r)]?.server?.takeIf { it.host == pin.host }?.let { r.loc.copy(pinned = it) } } ?: r.loc
        val l = try {
            again(p, r, acc, last, used) ?: p.connect(loc, acc)
        } catch (e: Exception) {
            if (e is AuthException || e is app.unbraid.data.NoLongerOffered) throw e
            last[cacheKey(r)]?.takeIf { pinned == null || it.server.host == pinned.host }?.takeIf { trusted(it, r, accounts, providers) } ?: throw if (pinned != null) Fail("${r.name}: ${pinned.name} is unreachable") else e
        }
        if (pinned != null || "${p.id}|${serverId(l.server)}" !in used) return l
        val other = runCatching {
            p.servers(r.loc, acc, 10).firstOrNull { "${p.id}|${serverId(it)}" !in used }?.let { p.connect(r.loc.copy(pinned = it), acc) }
        }.onFailure { if (it is AuthException) throw it }.getOrNull()
        return other ?: l
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
