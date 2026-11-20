package app.unbraid.data

import app.unbraid.APP
import kotlinx.serialization.Serializable
import java.io.IOException

/** Credentials were refused; the user must sign in again. [url]: where the user can fix it (a device list, say). */
open class AuthException(message: String, val url: String? = null) : IOException(message)

/**
 * Every device slot is taken, and the provider can free one by signing out all the account's other devices: [confirm]
 * says so to the user, who must agree first; signing in again with [retry] added to the form does it.
 */
class SlotsFull(message: String, url: String?, val confirm: String, val retry: Map<String, String>) : AuthException(message, url)

/**
 * A sign-in needs one more answer (a two-factor code, the characters in a captcha [image]): asked with [field], then
 * the same sign-in is sent again with it and the hidden [keep] values.
 */
class Challenge(message: String, val field: Field, val image: ByteArray? = null, val keep: Map<String, String> = emptyMap()) : IOException(message)

/** A route asks for something this account can't do (a protocol it has no sign-in for): final, so no cached tunnel stands in. */
class Unsupported(message: String) : app.unbraid.vpn.Fail(message)

/** The provider's list was read and no longer has a pinned server: final, so no older copy of it is used instead. */
class NoLongerOffered(server: Server) : app.unbraid.vpn.Fail("${server.name} is no longer offered. Pick another server")

/** This pinned server as [list] (the provider's current one) has it; as saved when there is no list; gone if it lacks it. */
fun Server.asListed(list: List<Server>?) = list?.let { l -> l.firstOrNull { it.host == host } ?: throw NoLongerOffered(this) } ?: this

/** One sign-in form field. */
/** [file] = can hold a whole config file: multi-line, with a "Choose file" action. [qr] = can be filled by scanning a QR code. */
data class Field(val key: String, val label: String, val secret: Boolean = true, val hint: String = "", val file: Boolean = false, val qr: Boolean = false)

/** Key of a provider's own blocking switch on its page ("on"/"off"); a route can override it ([Route.filter]). */
const val PROTECT = "protect"

/** Key of a WireGuard provider's port setting: [AUTO] or a port number. */
const val PORT_OPT = "port"

/** A route protocol: WireGuard, switching to OpenVPN TCP when WireGuard doesn't get through ([AutoProtocol]). */
const val AUTO_PROTOCOL = "auto"

/**
 * Routes on [AUTO_PROTOCOL] whose WireGuard failed on every port on the current network, so they connect over OpenVPN
 * TCP until the network changes or the VPN starts again. In memory only, by route id.
 */
object AutoProtocol {
    private val tcp = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun onTcp(route: String) = route in tcp
    fun toTcp(route: String) = tcp.add(route)
    fun reset() = tcp.clear()

    /** The protocol [r] connects with now: Automatic resolved to WireGuard (null) or OpenVPN TCP. */
    fun of(r: Route) = if (r.loc.protocol == AUTO_PROTOCOL) "openvpn_tcp".takeIf { onTcp(r.id) } else r.loc.protocol

    /** Whether [l], kept or cached, was made with the protocol [r] uses now. */
    fun matches(r: Route, l: Link) = r.loc.protocol != AUTO_PROTOCOL || (l is OvpnLink) == onTcp(r.id)
}

/** Provider page setting: "v2ray" carries the provider's WireGuard tunnels inside V2Ray ([WgLink.disguise]); "off". */
const val DISGUISE_OPT = "disguise"

const val AUTO = "auto"
/** Key of every WireGuard provider's MTU setting: [AUTO] is 1280, as in Mullvad's Android app; else up to 1420. */
const val MTU_OPT = "mtu"
const val MTU_MIN = 1280
const val MTU_MAX = 1420
/** Settings every provider page has, with their defaults. */
val COMMON_DEFAULTS = mapOf(MTU_OPT to AUTO, JUNK_OPT to "off")
/** Key of every WireGuard provider's junk-packet switch ([JUNK]). */
const val JUNK_OPT = "junk"
/** Random packets before each handshake ([WgPeer.junk]) when [JUNK_OPT] is on: AmneziaWG's compatible mode, five of 30 to 100 bytes. */
const val JUNK_COUNT = 5
val JUNK = Opt(JUNK_OPT, "Junk packets", "Sends a few random packets before each connection starts, which gets past some VPN blocking. The servers ignore them", advanced = true)
val MTU = Opt(MTU_OPT, "MTU", "Default works on every network. Larger can be faster on Wi-Fi but can stall uploads on mobile data",
    listOf(AUTO to "Default ($MTU_MIN)") + listOf(1320, 1360, 1380, 1400, MTU_MAX).map { "$it" to "$it" }, advanced = true)

/** The MTU a provider's tunnels use, from its page's [MTU_OPT]. */
fun mtu(value: String?) = value?.toIntOrNull()?.takeIf { it in MTU_MIN..MTU_MAX } ?: MTU_MIN

/** Key of a provider's entry address setting: the number of one of a server's [WgLink.entries], from 1. */
const val ENTRY_OPT = "entry"

/** The key-rotation setting on a provider page: days between new keys, or "off". */
const val ROTATE_OPT = "rotate"
val ROTATE_CHOICES = listOf("1" to "Every day", "3" to "Every 3 days", "7" to "Every week", "14" to "Every 2 weeks", "30" to "Every month", "off" to "Never")

/**
 * A server's delay (ms) and packet loss (%) as its provider measured them, oldest first; NaN where it has no sample.
 * [days] the period they cover.
 */
class Quality(val delay: FloatArray, val loss: FloatArray, val days: Int) {
    private fun FloatArray.known() = filter { !it.isNaN() }
    val delayAvg get() = delay.known().average().takeIf { !it.isNaN() }
    val delayRange get() = delay.known().let { if (it.isEmpty()) null else it.min() to it.max() }
    val lossAvg get() = loss.known().average().takeIf { !it.isNaN() }
    val lossMax get() = loss.known().maxOrNull()

    /** One plain line: "Delay 2.1 ms on average (1.8 to 4.0), packet loss 0% (at most 0.2%)". */
    fun line(): String = listOfNotNull(
        delayAvg?.let { a -> "Delay ${fmt(a)} ms on average" + (delayRange?.let { (lo, hi) -> " (${fmt(lo.toDouble())} to ${fmt(hi.toDouble())})" } ?: "") },
        lossAvg?.let { a -> "packet loss ${fmt(a)}%" + (lossMax?.takeIf { it > 0 }?.let { " (at most ${fmt(it.toDouble())}%)" } ?: "") },
    ).joinToString(", ").replaceFirstChar(Char::uppercase).ifEmpty { "No measurements in this period" }

    private fun fmt(v: Double) = if (v >= 10) "%.0f".format(java.util.Locale.US, v) else "%.1f".format(java.util.Locale.US, v).removeSuffix(".0")
}

/**
 * A setting on a provider's page, saved in [Setup.opts]. No [choices] = an on/off switch ("on"/"off"). [advanced]: one
 * most people never need, which can stop routes connecting: listed under the page's Advanced settings. [custom]: number
 * ranges the user may type instead of a choice (a port, say).
 */
data class Opt(val key: String, val label: String, val sub: String, val choices: List<Pair<String, String>> = emptyList(), val advanced: Boolean = false, val custom: List<IntRange> = emptyList()) {
    fun allows(typed: String) = typed.toIntOrNull()?.let { n -> custom.any { n in it } } == true
}

/** What a provider says about the device slot this phone holds: one line, where the user sees all their devices, and how many the account has in use, if it says. */
data class Device(val line: String, val url: String? = null, val used: Int? = null)

/** A ready tunnel for one exit, produced at connect time. */
@Serializable
sealed interface Link {
    val server: Server
}

/**
 * [ports]: the server's other ports, best first, for a network that blocks the one in [WgPeer.endpoint]. [entries]: the
 * server's entry addresses in the order its provider page numbers them ([ENTRY_OPT]); empty = only the endpoint's.
 * [portEntry]: a port the server takes on one entry address only, mapped to that entry's number.
 */
@Serializable
data class WgLink(
    override val server: Server, val peer: WgPeer, val ports: List<Int> = emptyList(), val entries: List<String> = emptyList(),
    val portEntry: Map<Int, Int> = emptyMap(), val disguise: Disguise? = null,
    /** The port this server takes forwarded tunnels on from the provider's other servers (its multi-hop), if it does. */
    val hopPort: Int? = null,
    /** Set once this tunnel goes to [hopPort] through this entry server's address (the provider's multi-hop). */
    val hopEntry: String? = null,
) : Link

/** How this server takes a disguised tunnel ([DISGUISE_OPT]): its V2Ray server, and the WireGuard port behind it. */
@Serializable
data class Disguise(val vmess: Vmess, val wgPort: Int)

@Serializable
data class OvpnLink(override val server: Server, val profile: OvpnProfile) : Link

@Serializable
data class SsLink(override val server: Server, val profile: SsProfile) : Link

/** Goes out through the built-in Tailscale client's exit node. */
@Serializable
data class TsLink(override val server: Server) : Link

/**
 * A VPN provider Unbraid can sign in to. Implementations are plain objects; every call may block on the network
 * and runs off the main thread. [account] is the opaque, sealed blob returned by [signIn].
 */
interface Provider {
    val id: String
    val name: String
    val fields: List<Field>
    /** Where the user finds what [fields] ask for. */
    val help: String

    /** Validates credentials (talking to the provider) and returns what [connect] needs later. */
    fun signIn(input: Map<String, String>): String

    /** Countries with cities, sorted by name. City.key / Location.region carry provider-specific ids. */
    fun countries(account: String): List<Country>

    /** Picks the best server for [loc] and returns a ready tunnel. */
    fun connect(loc: Location, account: String): Link

    /** Up to [limit] servers for a manual pick, best first. */
    fun servers(loc: Location, account: String, limit: Int = 20): List<Server> = emptyList()

    /** Whether [Server.load] is real. Providers without load data must not show "lowest load" or 0%. */
    val hasLoad: Boolean get() = false
    /** One WireGuard key per account: moving it to another server can stall the tunnel, so reconnects keep the last server. */
    val oneKey: Boolean get() = false
    /** The tunnel protocol its servers are reached with, shown in the picker when it offers no [protocols]. */
    val protocol: String get() = "WireGuard"
    /** Protocols a route can choose in the picker ([Location.protocol] key, null = the default; label; note); empty = no choice. */
    val protocols: List<Triple<String?, String, String>> get() = emptyList()

    /** Releases what [signIn] registered (device slot, session). False if that failed; the account is forgotten regardless. */
    fun signOut(account: String): Boolean = true

    /**
     * True only when signing out [old] provably can't touch what the signed-in [current] uses: they name different
     * devices or sessions. False when that can't be told.
     */
    fun otherDevice(old: String, current: String): Boolean = false

    /**
     * True only when the provider confirms this phone's device or session is gone (removed on its website, say), so its
     * tunnels can't work until a new sign-in. A check that fails or isn't clear is false. Asked only after a tunnel died.
     */
    fun revoked(account: String): Boolean = false

    /**
     * Whether a saved tunnel ([link], e.g. kept from an earlier connection or cached for when the provider can't be
     * reached) may be reused. Providers whose server keys must come from a trusted source check the saved key here.
     */
    fun trusts(link: Link, account: String): Boolean = true

    /** Offers its own multi-hop ([WgLink.hopPort]), used for a Via between two of its routes when the user picks it. */
    val nativeHop: Boolean get() = false

    /** The ad-blocking resolver to use on its multi-hop instead of [dns] (from [blockingDns]); null = none known, so blocked. */
    fun hopDns(dns: String): String? = null

    /** Setting up a tunnel contacts the server itself (not the provider's API), so it can't go through another route (Via). */
    val viaUnsafe: Boolean get() = false

    /** Its own blocking switch ([PROTECT]), first on its page and per route in the picker. */
    val protection: Opt? get() = null
    /** How the account's key was made, as its config says (Proton's options), for its page; null = nothing to show, "" = not known. */
    fun facts(account: String): String? = null
    /**
     * Gives [account] a new key on the same device when its key is [days] old (null = only to finish an earlier change
     * or to add a missing quantum-safe key), saving each step through [save] (false = not saved: stop). True when the
     * key changed. [finishOnly]: only an earlier change left unfinished. Throws when the provider couldn't be asked or refused.
     */
    fun rotate(account: String, days: Int?, now: Long, finishOnly: Boolean, save: (String) -> Boolean): Boolean = false

    /** Whether the account's key has a quantum-safe preshared key; null = the provider has no quantum-safe exchange. */
    fun quantumSafe(account: String): Boolean? = null

    /** What the provider itself measured on server [name] over the last days, for Stats; null when it doesn't say. */
    fun quality(name: String): Quality? = null
    /** Whether it publishes such measurements ([quality]), so Stats offers to ask. */
    val reportsQuality: Boolean get() = false
    /** Help about what is managed in the provider's own account (config expiry, forwarded ports) and where, for its page. */
    val configHelp: Pair<String, String>? get() = null
    /** The gluetun catalogue that is its only server list, if any: its page and status show how old it is. */
    val listSource: String? get() = null
    /** Without [protection]: what its page says about the provider's own blocking instead of a switch. */
    val blockingNote: String get() = "$APP can't turn it on or off. Use $APP's Protection filter instead"
    /** Where the provider's own blocking is set when $APP can't switch it: its row opens this page instead of the filter. */
    val blockingUrl: String? get() = null
    /** The other settings on this provider's page; may block on the network for values from its server list. */
    fun options(account: String): List<Opt> = emptyList()
    /** Every [options] key's value until the user picks one. */
    val defaults: Map<String, String> get() = emptyMap()
    /** The in-tunnel blocking resolver for these page settings, when [PROTECT] is on; null = the provider has none. */
    fun blockingDns(opts: (String) -> String?): String? = null
    /** This phone's device slot, if the provider reports one; may block on the network. */
    fun device(account: String): Device? = null
    /** How many connections or devices the account has in use now, every app's, when the provider says apart from [device]; null = not known. May block on the network. */
    fun inUse(account: String): Int? = null
    /** The account's plan as the provider reports it ([Plans.line]); null = not known. May block on the network. */
    fun plan(account: String): String? = null
    /** What the account may use at once ([Limits]); unknown unless the provider says or documents it. */
    fun limit(account: String): Limit = Limit("connection", perTunnel = true)
    /** After a sign-in, how long the provider may take to accept the new key on every server (ms). */
    val keySetup: Long get() = 0
}

object Plans {
    /** "Paid until 22 Nov 2026", "Ended on …" once past, or "No active plan" when [paidUntil] (epoch seconds) is null. */
    fun line(paidUntil: Long?, now: Long = System.currentTimeMillis() / 1000): String {
        if (paidUntil == null) return "No active plan"
        val date = java.time.Instant.ofEpochSecond(paidUntil).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
        return if (paidUntil < now) "Ended on $date" else "Paid until $date"
    }
}

/** Providers that only offer downloadable configs: the user imports one file per location. */
/** How to bring in another provider's config: where to download it, and [note], what to know before downloading. */
data class ImportGuide(val id: String, val name: String, val openVpn: Boolean, val where: String, val url: String, val note: String? = null)

object Providers {
    /** Provider id → when it was last signed in to (ms), in this process: its new key may still be spreading ([Provider.keySetup]). */
    val signedInAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Providers whose key is still being set up at [now]. */
    fun settingUp(now: Long = System.currentTimeMillis()) = signedInAt.filter { (id, t) -> now - t < (get(id)?.keySetup ?: 0) }.keys

    /** A provider page setting's saved value, for choices made outside [Provider.connect]'s arguments; set by the app at start. */
    @Volatile var opt: (provider: String, key: String) -> String? = { p, k -> get(p)?.defaults?.get(k) }

    val all: List<Provider> by lazy { listOf(Nord, Mullvad, Pia, Ivpn, Proton, ProtonFree, Surfshark, ExpressVpn, Windscribe, AirVpn) }
    fun get(id: String) = all.firstOrNull { it.id == id }

    /** Name short enough for a route label: "Nord", "PIA", "Proton". */
    fun short(id: String) = mapOf("nord" to "Nord", "pia" to "PIA", "proton" to "Proton", "proton-free" to "Proton Free")[id] ?: get(id)?.name ?: id

    val imports = listOf(
        ImportGuide("purevpn", "PureVPN", false, "Member Area → Manual configuration → WireGuard", "https://my.purevpn.com/",
            "Import and connect within about 30 minutes of downloading: PureVPN files that aren't used soon stop working."),
        ImportGuide("cyberghost", "CyberGhost", true, "My Devices → Configure new device → OpenVPN", "https://my.cyberghostvpn.com/"),
    )
}
