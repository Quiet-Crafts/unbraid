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
    class Waiting : Fail("waiting for a server")

    /** A route restored without its keys: blocked, never retried, until its own server gets its config again. */
    class KeyLeftOut(name: String) : AuthException("Add the config for $name again in Settings, Providers & servers")

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
            // Also a route given a server still waiting for its config, however it got there.
            if (r.keyless || setup.servers.any { it.id == r.own && it.keyless }) { blocked[r.id] = KeyLeftOut(r.name); continue }
            // A Via that can't be used stops the route before its provider is asked anything (PIA's set-up would reach the exit).
            setup.viaProblem(r)?.let { blocked[r.id] = Fail(it); continue }
            val link = try { when {
                r.wg != null -> WgLink(Server(r.wg.name, r.wg.endpoint.substringBeforeLast(':'), "", r.wg.publicKey, 0), r.wg)
                r.ovpn != null -> OvpnLink(Server(r.ovpn.name, r.ovpn.name, "", "", 0), r.ovpn)
                r.ss != null -> SsLink(Server(r.ss.name, r.ss.server, r.ss.server, "", 0), r.ss)
                r.tsExit != null -> TsLink(Server(r.name, r.tsExit, "", "", 0))
                else -> keep[r.id]?.takeIf {
                    applied?.route(r.id)?.let { a -> !a.imported && a.loc == r.loc } == true && appliedAccounts[r.loc.provider] == accounts[r.loc.provider] &&
                        trusted(it, r, accounts, providers) && Addr.usable(it) && app.unbraid.data.AutoProtocol.matches(r, it) && disguised(setup, r, it) && (it as? WgLink)?.hopEntry == null
                } ?: if (offline) throw if (accounts[r.loc.provider] == null) AuthException("Sign in to ${providers(r.loc.provider)?.name ?: r.loc.provider} for ${r.name}") else Waiting()
                    else fresh(r, accounts, last, used, providers).also {
                        if (!Addr.usable(it)) throw Addr.refused(it.server.name)
                        if (!disguised(setup, r, it)) throw Fail("${r.name}: ${it.server.name} can't be reached through V2Ray right now. Pick another server, or turn Disguise off on the ${providers(r.loc.provider)?.name} page")
                    }
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
        // The provider's own multi-hop: the exit's tunnel goes to its forwarding port at the entry's server. The entry needs
        // no tunnel of its own: its server is asked for when no app uses it. Anything missing blocks the route.
        val bases = HashMap<String, Link>() // the exits as picked, for the fallback cache: a multi-hop is never reused
        setup.activeRoutes.filter { it.viaNative && it.id in out }.forEach { r ->
            try {
                val e = setup.route(r.via!!)!!
                val l = out[r.id] as? WgLink ?: throw Fail("${r.name}: multi-hop works for WireGuard routes only")
                val port = l.hopPort ?: throw Fail("${r.name}: ${l.server.name} doesn't say how to reach it by multi-hop right now. Try again later, or use two tunnels")
                // Asked for only online: an offline pass blocks the route until then, never leaves it straight.
                val entry = (out[e.id] as? WgLink)?.server ?: if (offline) throw Waiting() else try {
                    providers(e.loc.provider)!!.connect(e.loc, accounts[e.loc.provider]!!).server
                } catch (x: app.unbraid.data.NoLongerOffered) { throw Fail("${r.name}: its entry ${e.name} is on a server that's no longer offered. Choose another for ${e.name}") }
                if (entry.ip == l.server.ip) throw Fail("${r.name} and its entry are on the same server")
                val countries = listOf(entry.country.ifEmpty { e.loc.countryCode.orEmpty() }, l.server.country.ifEmpty { r.loc.countryCode.orEmpty() })
                if (countries.any { it.isEmpty() } || countries[0].equals(countries[1], true)) throw Fail("${r.name} and its entry must be in two different countries. Pick servers in two countries")
                bases[r.id] = l
                out[r.id] = l.copy(peer = l.peer.copy(endpoint = "${entry.ip}:$port"), hopEntry = entry.ip)
            } catch (x: Exception) { if (x is AuthException) throw x; out.remove(r.id); blocked[r.id] = x }
        }
        // A tunnel that would clash with another one's identity is blocked, never started beside it.
        Compiler.clashes(setup, out).forEach { id -> out.remove(id); blocked[id] = Fail(Compiler.CLASH) }
        // Via, once every entry is settled: a route whose entry can't be used or isn't connected is blocked, never sent
        // straight to its server instead; only a WireGuard tunnel can go through another.
        setup.activeRoutes.filter { it.via != null && it.id in out }.forEach { r ->
            val why = setup.viaProblem(r) ?: "Via works for WireGuard routes only".takeIf { out[r.id] !is WgLink }
                // Not tried together yet: blocked rather than sent without the disguise or the Via.
                ?: listOfNotNull(r, setup.route(r.via!!)).firstOrNull { setup.opt(it.loc.provider, app.unbraid.data.DISGUISE_OPT) == "v2ray" }
                    ?.let { "${r.name}: Disguise doesn't work with Via yet. Turn Disguise off on the ${providers(it.loc.provider)?.name} page, or remove the Via" }
                ?: setup.route(r.via!!)?.takeIf { !r.viaNative && it.id !in out }?.let { "${r.name} waits for ${it.name}, the route it goes through" }
            if (why != null) { out.remove(r.id); blocked[r.id] = Fail(why) }
        }
        val remember = setup.activeRoutes.filter { !it.imported }.mapNotNull { r -> (bases[r.id]?.takeIf { r.id in out } ?: out[r.id])?.let { cacheKey(r) to it } }.toMap()
        return Result(out, remember, blocked)
    }

    /**
     * [links] with each provider WireGuard tunnel on the entry address, port and MTU its provider page asks for: a fixed port,
     * or with Automatic the [step]-th of the server's ports (a step per provider moves on when its tunnels stop answering).
     * A port the server takes on one entry only ([WgLink.portEntry]) goes to that entry; a tunnel without it skips the port.
     * Imported configs keep their own MTU, at most [app.unbraid.data.MTU_MIN].
     */
    fun ported(setup: Setup, links: Map<String, Link>, step: Map<String, Int>): Map<String, Link> =
        portedAll(setup, links, step).mapValues { (id, l) -> if (l is WgLink && setup.route(id)?.let { it.via != null && !it.viaNative } == true) l.copy(peer = l.peer.copy(mtu = minOf(l.peer.mtu, VIA_MTU))) else l }

    /** A tunnel inside another one loses its outer headers' room: 1280 less WireGuard's 80. */
    const val VIA_MTU = 1200

    private fun portedAll(setup: Setup, links: Map<String, Link>, step: Map<String, Int>): Map<String, Link> {
        // The provider's multi-hop has its own forwarding port at the entry: no port or entry setting moves it.
        val hops = links.filterValues { it is WgLink && it.hopEntry != null }
        if (hops.isNotEmpty()) return portedAll(setup, links - hops.keys, step) + hops.mapValues { (id, l) ->
            val p = setup.route(id)?.loc?.provider
            (l as WgLink).copy(peer = l.peer.copy(mtu = app.unbraid.data.mtu(p?.let { setup.opt(it, app.unbraid.data.MTU_OPT) }), junk = p?.let { junk(setup, it) } ?: 0))
        }
        val provider = { id: String -> setup.route(id)?.takeIf { !it.imported }?.loc?.provider }
        // One list per provider, every tunnel's ports in route order, so a tunnel saved without one (offline) still takes the
        // same port as its provider's others, and no route's order hides a port another tunnel knows.
        val lists = links.entries.groupBy({ provider(it.key) }, { (it.value as? WgLink)?.ports.orEmpty() }).mapValues { (_, l) -> l.flatten().distinct() }
        val only = links.entries.groupBy({ provider(it.key) }, { (it.value as? WgLink)?.portEntry.orEmpty() }).mapValues { (_, l) -> l.fold(emptyMap<Int, Int>()) { a, m -> m + a } }
        return links.mapValues { (id, l) -> portedOne(setup, provider(id), l, lists, only, step) }
    }

    private fun portedOne(setup: Setup, p: String?, l: Link, lists: Map<String?, List<Int>>, only: Map<String?, Map<Int, Int>>, step: Map<String, Int>): Link {
        if (l !is WgLink) return l
        if (p == null) return l.copy(peer = l.peer.copy(mtu = minOf(l.peer.mtu, app.unbraid.data.MTU_MIN)))
        // Disguised: the tunnel goes inside V2Ray to the server's own WireGuard port behind it; the port setting doesn't apply.
        l.disguise?.takeIf { setup.opt(p, app.unbraid.data.DISGUISE_OPT) == "v2ray" }?.let { d ->
            return l.copy(peer = l.peer.copy(endpoint = "${l.peer.endpoint.substringBeforeLast(':')}:${d.wgPort}", vmess = d.vmess, mtu = app.unbraid.data.MTU_MIN))
        }
        val picked = setup.opt(p, app.unbraid.data.PORT_OPT)?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: lists[p]?.takeIf { it.isNotEmpty() }?.let { it[(step[p] ?: 0) % it.size] }
        val one = only[p].orEmpty()
        val needs = picked?.let { one[it] }
        // A one-entry port on a tunnel that doesn't know that entry would go nowhere: it takes the first port that works at any entry.
        val fits = needs == null || l.entries.getOrNull(needs - 1) != null
        val port = picked?.takeIf { fits } ?: lists[p].orEmpty().firstOrNull { it !in one } ?: l.peer.endpoint.substringAfterLast(':')
        val entry = needs?.takeIf { fits }
        val host = (entry ?: setup.opt(p, app.unbraid.data.ENTRY_OPT)?.toIntOrNull())?.let { l.entries.getOrNull(it - 1) } ?: l.peer.endpoint.substringBeforeLast(':')
        return l.copy(peer = l.peer.copy(endpoint = "$host:$port", mtu = app.unbraid.data.mtu(setup.opt(p, app.unbraid.data.MTU_OPT)), junk = junk(setup, p)))
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

    /** The junk packets [p]'s page asks for before each handshake ([app.unbraid.data.JUNK_OPT]). */
    private fun junk(setup: Setup, p: String) = if (setup.opt(p, app.unbraid.data.JUNK_OPT) == "on") app.unbraid.data.JUNK_COUNT else 0

    /** With Disguise on for its provider, a WireGuard tunnel must come with its V2Ray hop: never a plain one instead. */
    private fun disguised(setup: Setup, r: Route, l: Link) =
        setup.opt(r.loc.provider, app.unbraid.data.DISGUISE_OPT) != "v2ray" || l !is WgLink || l.disguise != null

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
        val loc = (pinned?.let { pin -> last[cacheKey(r)]?.server?.takeIf { it.host == pin.host }?.let { r.loc.copy(pinned = it) } } ?: r.loc)
            .let { it.copy(protocol = app.unbraid.data.AutoProtocol.of(r)) }
        val l = try {
            again(p, r, acc, last, used) ?: p.connect(loc, acc)
        } catch (e: Exception) {
            if (e is AuthException || e is app.unbraid.data.NoLongerOffered || e is app.unbraid.data.Unsupported || e is Addr.Refused) throw e
            val cached = last[cacheKey(r)]?.takeIf { pinned == null || it.server.host == pinned.host }?.takeIf { trusted(it, r, accounts, providers) && app.unbraid.data.AutoProtocol.matches(r, it) && (it as? WgLink)?.hopEntry == null }
            // A cached tunnel at an address that can't be used is no fallback: the user is told to pick the server again.
            if (cached != null && !Addr.usable(cached)) throw Addr.refused(cached.server.name)
            cached ?: throw if (pinned != null) Fail("${r.name}: ${pinned.name} is unreachable") else e
        }
        if (pinned != null || "${p.id}|${serverId(l.server)}" !in used) return l
        val other = runCatching {
            p.servers(loc, acc, 10).firstOrNull { "${p.id}|${serverId(it)}" !in used }?.let { p.connect(loc.copy(pinned = it), acc) }
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
