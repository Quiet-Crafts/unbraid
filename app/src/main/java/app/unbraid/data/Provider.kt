package app.unbraid.data

import kotlinx.serialization.Serializable
import java.io.IOException

/** Credentials were refused; the user must sign in again. */
class AuthException(message: String) : IOException(message)

/** The provider's list was read and no longer has a pinned server: final, so no older copy of it is used instead. */
class NoLongerOffered(server: Server) : app.unbraid.vpn.Fail("${server.name} is no longer offered. Pick another server")

/** This pinned server as [list] (the provider's current one) has it; as saved when there is no list; gone if it lacks it. */
fun Server.asListed(list: List<Server>?) = list?.let { l -> l.firstOrNull { it.host == host } ?: throw NoLongerOffered(this) } ?: this

/** One sign-in form field. */
/** [file] = can hold a whole config file: multi-line, with a "Choose file" action. */
data class Field(val key: String, val label: String, val secret: Boolean = true, val hint: String = "", val file: Boolean = false)

/** A ready tunnel for one exit, produced at connect time. */
@Serializable
sealed interface Link {
    val server: Server
}

@Serializable
data class WgLink(override val server: Server, val peer: WgPeer) : Link

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
}

/** Providers that only offer downloadable configs: the user imports one file per location. */
data class ImportGuide(val id: String, val name: String, val openVpn: Boolean, val where: String, val url: String)

object Providers {
    val all: List<Provider> by lazy { listOf(Nord, Mullvad, Pia, Ivpn, Proton, ProtonFree, Surfshark, ExpressVpn, Windscribe, AirVpn) }
    fun get(id: String) = all.firstOrNull { it.id == id }

    /** Name short enough for a route label: "Nord", "PIA", "Proton". */
    fun short(id: String) = mapOf("nord" to "Nord", "pia" to "PIA", "proton" to "Proton", "proton-free" to "Proton Free")[id] ?: get(id)?.name ?: id

    val imports = listOf(
        ImportGuide("purevpn", "PureVPN", false, "Member Area → Manual configuration → WireGuard", "https://my.purevpn.com/"),
        ImportGuide("cyberghost", "CyberGhost", true, "My Devices → Configure new device → OpenVPN", "https://my.cyberghostvpn.com/"),
    )
}
