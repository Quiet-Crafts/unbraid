package app.unbraid.data

/**
 * Proton VPN over WireGuard. Sign-in is local: the private key (or the whole .conf) of a config downloaded at
 * account.protonvpn.com → Downloads → WireGuard configuration. gluetun's wiki confirms that one key works on every server.
 * Servers: gluetun's catalogue (Proton's own list needs a login). Port 51820 is gluetun's WireGuard port for Proton;
 * address 10.2.0.2/32 and DNS 10.2.0.1 are the defaults of Proton's generated config (their multi-tunnel guide
 * lists 10.3.0.2 / 10.3.0.1 as the *second* config, https://protonvpn.com/support/wireguard-configurations/).
 * Secure Core (`CH-US#1`) and Tor (`FR#13-TOR`) servers are left out: they need a plan feature and a different route.
 * A Free plan's key works only on Proton's free servers (https://protonvpn.com/free-vpn/server; gluetun marks them
 * `"free": true`), so [ProtonFree] lists just those while [Proton] lists everything else plus them.
 * The endpoint port follows gluetun.
 */
open class ProtonVpn(private val free: Boolean) : KeyProvider() {
    override val id = if (free) "proton-free" else "proton"
    override val name = if (free) "Proton VPN Free" else "Proton VPN"
    override val fields = listOf(Field("key", "WireGuard key or .conf", hint = "Paste the whole file, or the PrivateKey", file = true))
    override val help = "Sign in at account.protonvpn.com → Downloads → WireGuard configuration. Name the config, pick any platform and server, then Create and Download. Paste the whole .conf (or just the PrivateKey value). " + if (free) {
        "A Free plan's key works only on Proton's free servers (see protonvpn.com/free-vpn/server), so this list shows just those; with a paid plan choose Proton VPN."
    } else {
        "The same key works on every Proton server. A Free plan's key works only on Proton's free servers: choose Proton VPN Free for those."
    }

    override val defaultAddress = listOf("10.2.0.2/32")
    override val defaultDns = listOf("10.2.0.1")
    override val defaultPort = 51820

    private val SECURE_CORE = Regex("^(CH|IS|SE)-[A-Z]{2}#")

    override fun entries(a: Account) = filtered(Catalog.fetch("protonvpn", "wireguard"))

    internal fun filtered(all: List<Catalog.Entry>) = all.filter { e ->
        val n = e.serverName.orEmpty()
        (e.free || !free) && !SECURE_CORE.containsMatchIn(n) && !n.endsWith("-TOR")
    }
}

/** Proton VPN with a paid plan: every normal server, free ones included. */
object Proton : ProtonVpn(false)

/** Proton VPN with a Free plan: only the servers that plan can use. */
object ProtonFree : ProtonVpn(true)
