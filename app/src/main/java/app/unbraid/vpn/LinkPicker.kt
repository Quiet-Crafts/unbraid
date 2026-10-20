package app.unbraid.vpn

import app.unbraid.data.AuthException
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
    class Result(val links: Map<String, Link>, val remember: Map<String, Link>)

    /** Fallback-cache key: a pinned route is its own entry, so it can never inherit another server's tunnel. */
    fun serverKey(r: Route, l: Link) = "${r.loc.provider}|${serverId(l.server)}"

    fun cacheKey(r: Route) = "${r.loc.provider}|${r.loc.copy(pinned = null)}|${r.loc.pinned?.let(::serverId).orEmpty()}"

    private fun serverId(s: Server) = s.ip.ifEmpty { s.host }

    /**
     * Imported configs as they are; provider routes keep the current server if the route's location didn't change, else
     * get their last one while it is still among the best (one-key providers, [again]), else the best now, falling back
     * to the last good one if the API is down. An unpinned route that lands on a server another route already uses is moved to a different server of
     * its location. A pinned route is never moved: it shares that server's tunnel (see [Compiler]) or fails.
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
        providers: (String) -> Provider?,
    ): Result {
        val used = avoid.toMutableSet()
        val out = linkedMapOf<String, Link>()
        for (r in setup.activeRoutes) {
            val link = when {
                r.wg != null -> WgLink(Server(r.wg.name, r.wg.endpoint.substringBeforeLast(':'), "", r.wg.publicKey, 0), r.wg)
                r.ovpn != null -> OvpnLink(Server(r.ovpn.name, r.ovpn.name, "", "", 0), r.ovpn)
                r.ss != null -> SsLink(Server(r.ss.name, r.ss.server, r.ss.server, "", 0), r.ss)
                r.tsExit != null -> TsLink(Server(r.name, r.tsExit, "", "", 0))
                else -> keep[r.id]?.takeIf {
                    applied?.route(r.id)?.let { a -> !a.imported && a.loc == r.loc } == true && appliedAccounts[r.loc.provider] == accounts[r.loc.provider] &&
                        trusted(it, r, accounts, providers)
                } ?: fresh(r, accounts, last, used, providers)
            }
            if (!r.imported) used += "${r.loc.provider}|${serverId(link.server)}"
            out[r.id] = link
        }
        val remember = setup.activeRoutes.filter { !it.imported }.mapNotNull { r -> out[r.id]?.let { cacheKey(r) to it } }.toMap()
        return Result(out, remember)
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
        val l = try {
            again(p, r, acc, last, used) ?: p.connect(r.loc, acc)
        } catch (e: Exception) {
            if (e is AuthException) throw e
            last[cacheKey(r)]?.takeIf { trusted(it, r, accounts, providers) } ?: throw if (pinned != null) Fail("${r.name}: ${pinned.name} is unreachable") else e
        }
        if (pinned != null || "${p.id}|${serverId(l.server)}" !in used) return l
        val other = runCatching {
            p.servers(r.loc, acc, 10).firstOrNull { "${p.id}|${serverId(it)}" !in used }?.let { p.connect(r.loc.copy(pinned = it), acc) }
        }.getOrNull()
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
