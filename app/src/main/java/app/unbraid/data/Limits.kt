package app.unbraid.data

import app.unbraid.APP

/**
 * How many [unit]s ("connection" or "device") a provider account may use at once, and how unbraid counts its own:
 * one per separate tunnel ([perTunnel]), or one for the whole account. [value] null = unknown.
 */
data class Limit(val unit: String, val perTunnel: Boolean, val value: Int? = null)

/** Page setting with the user's own limit for a provider, a whole number; unset = the provider's. */
const val LIMIT_OPT = "limit"

/** The connection-limit texts, the same for every provider: only the name, numbers and unit change. */
object Limits {
    fun units(n: Int, unit: String) = "$n $unit${if (n == 1) "" else "s"}"

    /**
     * The account's line on a provider page: [n] is what unbraid uses now, [limit] null when unknown, [all] what the provider
     * counts in use, never fewer than unbraid's own (a provider's count can lag a moment behind a new connection).
     */
    fun line(name: String, limit: Int?, unit: String, n: Int, all: Int? = null) = all?.coerceAtLeast(n).let { all ->
        if (limit != null && all != null) "$name allows ${units(limit, unit)}. ${if (all == 1) "1 is" else "$all are"} in use, ${if (all == n) "" else "$n of them "}by $APP."
        else if (limit != null) "$name allows ${units(limit, unit)}. $APP is using $n. Your other devices aren't counted."
        else "$APP is using ${units(n, unit).replaceFirst(" ", " $name ")}. Check how many your plan allows: over the limit, routes can stop working without an error."
    }

    /** Before a change that would take [n] over [limit]; [others] of them are other apps' (the provider counted them). */
    fun warning(name: String, limit: Int, unit: String, n: Int, others: Int = 0) =
        "$name allows ${units(limit, unit)}. This would make it $n${if (others > 0) ", ${if (others == 1) "1 of them" else "$others of them"} in your other apps" else ""}. Over the limit, routes can stop working without an error."

    /** A sign-in the provider refused because every slot is taken. */
    fun full(name: String, limit: Int?, unit: String) =
        "All ${if (limit != null) "$limit " else ""}$name ${unit}s are in use. Sign out another device, then try again."

    /** A route that fails on and off. */
    fun dropping(route: String, name: String, unit: String) = "$route keeps dropping. $name may be over its $unit limit."

    /** The limit that applies: the user's own ([LIMIT_OPT]) before the provider's. */
    fun effective(p: Limit, own: String?) = own?.toIntOrNull()?.takeIf { it in 1..999 } ?: p.value

    /** A change that would give [provider] [n] separate tunnels, over its [limit]. */
    data class Over(val provider: String, val limit: Int, val n: Int, val unit: String, val others: Int = 0)

    /**
     * Connections a provider last counted for the account beyond $APP's own (its other apps'), and when, from
     * [Provider.inUse]; trusted for [FRESH] ms, so a change is checked without asking the provider.
     */
    private class Count(val account: String, val others: Int, val at: Long)
    private val counts = java.util.concurrent.ConcurrentHashMap<String, Count>()
    const val FRESH = 30 * 60_000L
    /** Only for the [account] it was counted for: another sign-in starts from nothing. */
    fun others(provider: String, account: String?, now: Long = System.currentTimeMillis()) =
        counts[provider]?.takeIf { it.account == account && now - it.at in 0 until FRESH }?.others ?: 0

    /**
     * Notes what [provider] counts in use for [account] ([all], null = not known) while $APP runs [own] tunnels to it.
     * [ownAfter], $APP's tunnels once the answer came: a different figure makes the count unreliable, and it is dropped.
     */
    fun counted(provider: String, account: String, all: Int?, own: Int, ownAfter: Int = own, now: Long = System.currentTimeMillis()) {
        if (all == null || own != ownAfter) counts.remove(provider) else counts[provider] = Count(account, (all - own).coerceAtLeast(0), now)
    }

    /** Forgets the counts of providers no longer signed in. */
    fun forgetExcept(providers: Set<String>) { counts.keys.retainAll(providers) }

    /**
     * Separate tunnels [s] opens to [provider]: one per active route, since unpinned routes are spread over different
     * servers ([app.unbraid.vpn.LinkPicker.pick]); routes pinned to one server over the same protocol share its tunnel.
     */
    fun tunnels(s: Setup, provider: String) = s.activeRoutes.filter { !it.imported && it.loc.provider == provider }.map { r -> r.loc.pinned?.let { "${it.host}|${r.loc.protocol}" } ?: r.id }.distinct().size

    /**
     * The first provider counted per tunnel that [new] takes over its known limit with more tunnels than [old] had,
     * counting the connections its other apps hold ([others]).
     */
    fun over(old: Setup, new: Setup, others: (String) -> Int = { 0 }, limit: (String) -> Limit?): Over? = new.activeRoutes.filter { !it.imported }.map { it.loc.provider }.distinct().firstNotNullOfOrNull { p ->
        val l = limit(p)?.takeIf { it.perTunnel } ?: return@firstNotNullOfOrNull null
        val max = effective(l, new.opt(p, LIMIT_OPT)) ?: return@firstNotNullOfOrNull null
        val n = tunnels(new, p)
        val o = others(p)
        Over(p, max, n + o, l.unit, o).takeIf { n + o > max && n > tunnels(old, p) }
    }
}
