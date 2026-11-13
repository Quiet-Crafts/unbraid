package app.unbraid.data

import app.unbraid.vpn.Compiler

/**
 * Checks on the addresses a provider's reply hands us, before they are saved or used. A malformed value would only
 * fail later, when the engine reads it, on every connect; a host name or a local address as a server would send the
 * handshake to a lookup on the phone's own network or to a device on it.
 */
object Addr {
    /**
     * Ranges no VPN server is at: this network, private, carrier-grade NAT, loopback, link-local, the IETF block,
     * multicast and reserved (with broadcast). Documentation ranges lead nowhere and are left to fail on their own.
     */
    private val LOCAL = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
        "192.0.0.0/24", "192.168.0.0/16", "224.0.0.0/3",
    ).map { Compiler.ipv4(it)!! }

    /** A bare dotted IPv4 address (no prefix, no leading zeros) a server on the internet can have. */
    fun server(ip: String): Boolean {
        if ('/' in ip || ip != ip.trim()) return false
        val (a, _) = Compiler.ipv4(ip) ?: return false
        return LOCAL.none { (b, m) -> (a xor b) shr (32 - m) == 0L }
    }

    /** The tunnel address a provider assigns this phone, without its mask; null unless an IPv4 address (with or without one). */
    fun assigned(ip: String): String? = ip.takeIf { it == it.trim() && Compiler.ipv4(it) != null }?.substringBefore('/')

    /**
     * [s] when its address may be connected to. A server saved before addresses were checked, used when the provider's
     * list can't be read, can hold one that may not.
     */
    fun usable(s: Server): Server = if (server(s.ip)) s else throw refused(s.name)

    /** A saved server's address is refused: not a passing failure, so no cached tunnel stands in and the user is told. */
    class Refused(message: String) : app.unbraid.vpn.Fail(message)

    fun refused(name: String) = Refused("$name has a saved address that can't be used. Pick the server again")

    /** A provider's tunnel [l] connects only to addresses a server on the internet can have, alternate entries too. */
    fun usable(l: Link) = server(l.server.ip) && (l !is WgLink || (server(l.peer.endpoint.substringBeforeLast(':')) && l.entries.all(::server)))

    /** The error for a provider reply that holds a malformed address or key. */
    fun unexpected(provider: String) = app.unbraid.vpn.Fail("Unexpected reply from $provider. Try again later")
}
