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

    /** The account's line on a provider page: [n] is what unbraid uses now, [limit] null when unknown. */
    fun line(name: String, limit: Int?, unit: String, n: Int) =
        if (limit != null) "$name allows ${units(limit, unit)}. $APP is using $n. Your other devices aren't counted."
        else "$APP is using ${units(n, unit).replaceFirst(" ", " $name ")}. Check how many your plan allows: over the limit, routes can stop working without an error."

    /** Before a change that would take [n] over [limit]. */
    fun warning(name: String, limit: Int, unit: String, n: Int) =
        "$name allows ${units(limit, unit)}. This would make it $n. Over the limit, routes can stop working without an error."

    /** A sign-in the provider refused because every slot is taken. */
    fun full(name: String, limit: Int?, unit: String) =
        "All ${if (limit != null) "$limit " else ""}$name ${unit}s are in use. Sign out another device, then try again."

    /** A route that fails on and off. */
    fun dropping(route: String, name: String, unit: String) = "$route keeps dropping. $name may be over its $unit limit."

    /** The limit that applies: the user's own ([LIMIT_OPT]) before the provider's. */
    fun effective(p: Limit, own: String?) = own?.toIntOrNull()?.takeIf { it in 1..999 } ?: p.value
}
