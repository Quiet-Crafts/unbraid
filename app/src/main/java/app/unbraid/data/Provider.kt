package app.unbraid.data

import app.unbraid.APP
import kotlinx.serialization.Serializable
import java.io.IOException

/** Credentials were refused; the user must sign in again. [url]: where the user can fix it (a device list, say). */
class AuthException(message: String, val url: String? = null) : IOException(message)

/**
 * A sign-in needs one more answer (a two-factor code, the characters in a captcha [image]): asked with [field], then
 * the same sign-in is sent again with it and the hidden [keep] values.
 */
class Challenge(message: String, val field: Field, val image: ByteArray? = null, val keep: Map<String, String> = emptyMap()) : IOException(message)

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
const val AUTO = "auto"

/**
 * A setting on a provider's page, saved in [Setup.opts]. No [choices] = an on/off switch ("on"/"off"). [advanced]: one
 * most people never need, which can stop routes connecting: listed under the page's Advanced settings.
 */
data class Opt(val key: String, val label: String, val sub: String, val choices: List<Pair<String, String>> = emptyList(), val advanced: Boolean = false)

/** What a provider says about the device slot this phone holds: one line, and where the user sees all their devices. */
data class Device(val line: String, val url: String? = null)

/** A ready tunnel for one exit, produced at connect time. */
@Serializable
sealed interface Link {
    val server: Server
}

/** [ports]: the server's other ports, best first, for a network that blocks the one in [WgPeer.endpoint]. */
@Serializable
data class WgLink(override val server: Server, val peer: WgPeer, val ports: List<Int> = emptyList()) : Link

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
    /** The tunnel protocol its servers are reached with, shown in the picker (NordVPN lets the user choose). */
    val protocol: String get() = "WireGuard"

    /** Releases what [signIn] registered (device slot, session). False if that failed; the account is forgotten regardless. */
    fun signOut(account: String): Boolean = true

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

    /** Its own blocking switch ([PROTECT]), first on its page and per route in the picker. */
    val protection: Opt? get() = null
    /** Without [protection]: what its page says about the provider's own blocking instead of a switch. */
    val blockingNote: String get() = "$APP can't turn it on or off. Use $APP's Protection filter instead"
    /** The other settings on this provider's page; may block on the network for values from its server list. */
    fun options(account: String): List<Opt> = emptyList()
    /** Every [options] key's value until the user picks one. */
    val defaults: Map<String, String> get() = emptyMap()
    /** The in-tunnel blocking resolver for these page settings, when [PROTECT] is on; null = the provider has none. */
    fun blockingDns(opts: (String) -> String?): String? = null
    /** This phone's device slot, if the provider reports one; may block on the network. */
    fun device(account: String): Device? = null
    /** Simultaneous connections the account allows, counted per tunnel, when the provider limits those rather than devices (0 = neither). */
    val connections: Int get() = 0
    /** After a sign-in, how long the provider may take to accept the new key on every server (ms). */
    val keySetup: Long get() = 0
}

/** Providers that only offer downloadable configs: the user imports one file per location. */
data class ImportGuide(val id: String, val name: String, val openVpn: Boolean, val where: String, val url: String)

object Providers {
    /** Provider id → when it was last signed in to (ms), in this process: its new key may still be spreading ([Provider.keySetup]). */
    val signedInAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Providers whose key is still being set up at [now]. */
    fun settingUp(now: Long = System.currentTimeMillis()) = signedInAt.filter { (id, t) -> now - t < (get(id)?.keySetup ?: 0) }.keys

    val all: List<Provider> by lazy { listOf(Nord, Mullvad, Pia, Ivpn, Proton, ProtonFree, Surfshark, ExpressVpn, Windscribe, AirVpn) }
    fun get(id: String) = all.firstOrNull { it.id == id }

    /** Name short enough for a route label: "Nord", "PIA", "Proton". */
    fun short(id: String) = mapOf("nord" to "Nord", "pia" to "PIA", "proton" to "Proton", "proton-free" to "Proton Free")[id] ?: get(id)?.name ?: id

    val imports = listOf(
        ImportGuide("purevpn", "PureVPN", false, "Member Area → Manual configuration → WireGuard", "https://my.purevpn.com/"),
        ImportGuide("cyberghost", "CyberGhost", true, "My Devices → Configure new device → OpenVPN", "https://my.cyberghostvpn.com/"),
    )
}
