package app.unbraid.vpn

import app.unbraid.data.DIRECT
import app.unbraid.data.Home
import app.unbraid.data.Link
import app.unbraid.data.Manual
import app.unbraid.data.OvpnLink
import app.unbraid.data.SsLink
import app.unbraid.data.TsLink
import app.unbraid.data.validWgKey
import app.unbraid.data.WgLink
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.data.WgPeer
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Turns a [Setup] into a sing-box config.
 *
 * Order of decisions for every connection: DNS → home networks → LAN → the app's route → default route.
 * Apps on Direct are excluded from the VPN at OS level, so they never see it; under system lockdown
 * (where Android would cut excluded apps off) they are routed through `direct` instead.
 */
object Compiler {
    const val NORD_DNS = "103.86.96.100"
    const val NORD_TP = "103.86.96.108"
    const val TS_TAG = "home-ts"
    /** In-tunnel blocking resolvers, from each provider's docs: Mullvad ads+trackers+malware, PIA MACE, IVPN AntiTracker. */
    val FILTER_DNS = mapOf("mullvad" to "100.64.0.7", "pia" to "10.0.0.244", "ivpn" to "10.0.254.2")
    private val LAN = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "224.0.0.0/4", "255.255.255.255/32")
    private const val TAILNET = "100.64.0.0/10"
    /**
     * The fake-IP pool (198.18.0.0/15) in eight blocks: each new domain table takes the next one and refuses the other
     * seven ([Input.fakeBlock]), so an address an app kept from an old table names nothing until eight tables later. Each holds 16,381 addresses.
     */
    val FAKE_RANGES = (0 until 8).map { "198.${18 + it / 4}.${it % 4 * 64}.0/18" }

    /** The block a new table takes after [last]; a reload of the [open] table keeps its own. */
    fun nextBlock(last: Int, open: Boolean) = (if (open) last else last + 1).mod(FAKE_RANGES.size)

    /**
     * Older versions alternated two /16 halves: count the last one as its final block, so the next table starts in the
     * other half and nothing an app kept from it is handed out again.
     */
    fun blockAfterHalf(half: Int?) = if (half == 1) FAKE_RANGES.size - 1 else FAKE_RANGES.size / 2 - 1

    class Input(
        val setup: Setup,
        val links: Map<String, Link>,
        val selfPackage: String,
        val lockdown: Boolean,
        val tsDir: String,
        val cacheFile: String,
        /** The tailnet's MagicDNS domain, if known. */
        val tsSuffix: String? = null,
        /** Loopback port and password of the app's own way into each exit ([PROBE]); null = none. */
        val probe: Pair<Int, String>? = null,
        /** Index into [FAKE_RANGES] of the block this engine hands out; the others are refused. */
        val fakeBlock: Int = 0,
        /** Routes with no tunnel right now (signed out, unreachable): their apps' traffic and lookups are refused. */
        val blocked: Set<String> = emptySet(),
        /** Tailscale asks the user to sign in (it gave a login link): only then do its sign-in pages skip the exit. */
        val tsLoginPending: Boolean = false,
    )

    /**
     * Inbound tag of the app's own way into a chosen exit, for speed and health tests: a proxy on 127.0.0.1 whose
     * user name is the exit's tunnel tag. The app itself is excluded from the VPN, so this is its only path into a
     * tunnel; the password is random per connection, so no other app on the phone can use it.
     */
    const val PROBE = "probe"

    /** Route id → the tunnel tag it runs on: routes that resolve to one server with one login share a tunnel. */
    fun shared(s: Setup, links: Map<String, Link>, blocked: Set<String> = emptySet()): Map<String, String> {
        val owners = HashMap<String, String>()
        val routes = s.activeRoutes.filter { it.tsExit == null && it.id !in blocked }
        require(clashes(s, links).isEmpty()) { CLASH } // [LinkPicker] blocks them first
        return routes.associate { r ->
            val link = links[r.id] ?: error("No server for ${r.name}")
            r.id to owners.getOrPut(linkKey(r, link)) { "r-${r.id}" }
        }
    }

    fun compile(i: Input): String {
        val s = i.setup
        // Blocked routes get no tunnel, DNS server or probe; their apps still match, to be refused.
        val active = s.activeRoutes.filter { it.id !in i.blocked }
        val defaultBlocked = s.defaultRoute in i.blocked
        require(active.mapNotNull { it.tsExit }.distinct().size <= 1) { "Tailscale allows one exit node at a time. Use the same node for every Tailscale route." }
        // Tailscale-exit routes have no tunnel of their own: they ride the Tailscale endpoint, whose exit node is set below.
        // Routes that resolve to the same server with the same credentials share one tunnel: two with one key would fight over it.
        val shared = shared(s, i.links, i.blocked)
        val tag = { id: String -> if (id == DIRECT) "direct" else if (s.route(id)?.tsExit != null) TS_TAG else shared[id] ?: "r-$id" }
        val tsDomains = listOfNotNull("ts.net".takeIf { officialTs(s.home) }, trustedSuffix(i.tsSuffix, s.home)).distinct()
        // OpenVPN routes use the DNS their server pushes, except NordVPN's own routes: those keep Nord's
        // (Threat Protection) resolvers through the tunnel.
        val pushedDns = { r: app.unbraid.data.Route -> s.prefs.customDns.isEmpty() && (r.imported || r.loc.provider != "nord") }
        val direct = s.apps.filterValues { it == DIRECT }.keys.sorted()
        val homeWg = s.home.wg
        val ts = s.tailscale
        // An exit keeps the endpoint running, but only the home toggle grants every app tailnet access.
        val homeTs = s.home.tailscale
        val dnsFor = { id: String -> if (id == DIRECT) "local" else "dns-r-$id" }
        val defaultDns = if (s.defaultRoute == DIRECT || defaultBlocked || active.isEmpty()) "local" else dnsFor(s.defaultRoute)
        // Every tunnel the app can test, with the DNS of a route on it; Tailscale even without an exit route (its nodes).
        val probeTags = active.groupBy { tag(it.id) }.mapValues { (_, rs) -> dnsFor(rs.first().id) }.toMutableMap()
            .apply { if (s.tailscale) putIfAbsent(TS_TAG, defaultDns); if (app.unbraid.BuildConfig.DEBUG) put("direct", "local") } // debug: the line itself, for comparisons
        // Apps with their own route, grouped. Direct only appears here under lockdown (otherwise excluded at OS level).
        val perApp = s.apps.entries.groupBy({ it.value }, { it.key }).toSortedMap()
            .filterKeys { id -> id != s.defaultRoute && if (id == DIRECT) i.lockdown else s.activeRoutes.any { it.id == id } }
            .mapValues { (_, p) -> JsonArray(p.sorted().map(::JsonPrimitive)) }
        // Destination rules that beat any app rule. Repeated after every resolve, because fake-IP connections
        // only get a real address there.
        val network = buildList {
            homeWg?.let { w -> homeCidrs(w).takeIf { it.isNotEmpty() }?.let { add(obj("ip_cidr" to JsonArray(it.map(::JsonPrimitive)), "outbound" to "home-wg")) } }
            if (homeTs) add(obj("ip_cidr" to JsonArray((listOf(TAILNET) + homeSubnets(s)).map(::JsonPrimitive)), "outbound" to TS_TAG))
            if (s.prefs.lan) add(obj("ip_cidr" to JsonArray(LAN.map(::JsonPrimitive)), "outbound" to "direct"))
        }

        return buildJsonObject {
            putJsonObject("log") { put("level", "warn"); put("timestamp", false) }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    add(obj("type" to "fakeip", "tag" to "fake", "inet4_range" to FAKE_RANGES[i.fakeBlock]))
                    add(obj("type" to "local", "tag" to "local"))
                    active.forEach { r ->
                        val link = i.links[r.id]
                        if (link is OvpnLink && pushedDns(r)) add(obj("type" to "openvpn", "tag" to "dns-r-${r.id}", "endpoint" to tag(r.id), "accept_default_resolvers" to true))
                        else add(obj("type" to "udp", "tag" to "dns-r-${r.id}", "server" to dnsServer(s, r, link), "detour" to tag(r.id)))
                    }
                    if (homeTs) add(obj("type" to "tailscale", "tag" to "dns-tailnet", "endpoint" to TS_TAG, "accept_default_resolvers" to false))
                }
                putJsonArray("rules") {
                    if (homeTs && tsDomains.isNotEmpty()) add(obj("domain_suffix" to arr(*tsDomains.toTypedArray()), "server" to "dns-tailnet"))
                    if (s.prefs.lan) add(obj("domain_suffix" to arr("local", "lan", "home.arpa", "fritz.box"), "server" to "local"))
                    add(obj("query_type" to arr("A", "AAAA"), "server" to "fake", "rewrite_ttl" to 1))
                    // Other query types (HTTPS, SVCB, TXT…) go to the asking app's own exit, never the ISP.
                    for ((id, pkgs) in perApp) add(if (id in i.blocked) obj("package_name" to pkgs, "action" to "reject") else obj("package_name" to pkgs, "server" to dnsFor(id)))
                    // A blocked default never falls back to another resolver: the phone's network would see the names.
                    if (defaultBlocked) add(obj("action" to "reject"))
                }
                put("final", defaultDns)
                put("strategy", "ipv4_only")
                // A slow or re-handshaking exit must not hold every app's new connections: answer from the cache
                // (stale if need be) and refresh in the background, and give up on a lookup sooner than the 10 s default.
                put("optimistic", buildJsonObject { put("enabled", true); put("timeout", "1h") }) // stale at most an hour, not the 3-day default
                put("timeout", "5s")
                put("cache_capacity", 4096)
            }
            putJsonArray("inbounds") {
                add(buildJsonObject {
                    put("type", "tun"); put("tag", "tun")
                    put("address", arr("172.19.0.1/30"))
                    put("mtu", 9000)
                    put("auto_route", true)
                    put("strict_route", true)
                    // gVisor: on Android 17 the kernel TCP path of "mixed" never answered (Direct and Shadowsocks timed
                    // out on the emulator; cf. sing-tun#98). WireGuard, OpenVPN and Tailscale forward their flows past
                    // the stack either way, at the same speed.
                    put("stack", "gvisor")
                    // Forwarded flows and UDP are forgotten after udp_timeout idle (5 min by default), which silently
                    // kills idle long-lived connections like push (FCM heartbeats run up to ~28 min apart).
                    put("udp_timeout", "30m")
                    // Under system lockdown Android drops anything that bypasses the TUN, so LAN goes through `direct` instead.
                    if (s.prefs.lan && !i.lockdown) {
                        // LAN never enters the tunnel: Android sends it straight to Wi-Fi. Home subnets reached
                        // through a tunnel stay routed, because the more specific route wins.
                        val home = (if (homeTs) homeSubnets(s) else emptyList()) + (homeWg?.let(::homeCidrs) ?: emptyList())
                        put("route_address", JsonArray((listOf("0.0.0.0/0") + home).map(::JsonPrimitive)))
                        // Android applies exclusions after inclusions, so an exclusion equal to a home prefix would win.
                        put("route_exclude_address", JsonArray((LAN - home.toSet()).map(::JsonPrimitive)))
                    }
                    put("exclude_package", JsonArray(((if (i.lockdown) emptyList() else direct) + i.selfPackage).map(::JsonPrimitive)))
                })
                // No tunnel to test, no inbound: sing-box would run a proxy with an empty user list unauthenticated.
                i.probe?.takeIf { probeTags.isNotEmpty() }?.let { (port, password) ->
                    add(buildJsonObject {
                        put("type", "mixed"); put("tag", PROBE); put("listen", "127.0.0.1"); put("listen_port", port)
                        putJsonArray("users") { probeTags.keys.forEach { t -> add(obj("username" to t, "password" to password)) } }
                    })
                }
            }
            putJsonArray("endpoints") {
                active.filter { shared[it.id] == "r-${it.id}" }.forEach { r ->
                    when (val l = i.links.getValue(r.id)) {
                        is WgLink -> add(wg(tag(r.id), l.peer, r.name))
                        is OvpnLink -> Manual.ovpnEndpoint(tag(r.id), l.profile).let { add(if (pushedDns(r)) openVpn(it) else it) }
                        is SsLink, is TsLink -> Unit
                    }
                }
                homeWg?.let { add(wg("home-wg", it, "the home network")) }
                if (ts) add(buildJsonObject {
                    put("type", "tailscale"); put("tag", TS_TAG)
                    put("state_directory", i.tsDir)
                    put("hostname", "unbraid")
                    put("accept_routes", true)
                    s.home.tsAuthKey?.let { put("auth_key", it) }
                    // Over plain HTTP a network attacker could hand Tailscale a fake control-server key.
                    s.home.tsControlUrl?.let { require(it.startsWith("https://")) { "Headscale needs an https:// address. Change it in Settings." }; put("control_url", it) }
                    s.tsExit?.let { put("exit_node", it); put("exit_node_allow_lan_access", s.prefs.lan) }
                })
            }
            putJsonArray("outbounds") {
                add(obj("type" to "direct", "tag" to "direct"))
                active.filter { shared[it.id] == "r-${it.id}" }.forEach { r -> (i.links[r.id] as? SsLink)?.let { add(Manual.ssOutbound(tag(r.id), it.profile)) } }
            }
            putJsonObject("route") {
                putJsonArray("rules") {
                    // The tunnels carry the phone's own connections and their replies, never new ones a server or peer
                    // opens: those would reach the phone, its network and the home network through another route.
                    val tunnels = active.filter { shared[it.id] == "r-${it.id}" && (i.links[it.id] is WgLink || i.links[it.id] is OvpnLink) }.map { tag(it.id) } +
                        listOfNotNull("home-wg".takeIf { homeWg != null }, TS_TAG.takeIf { ts })
                    if (tunnels.isNotEmpty()) add(obj("inbound" to JsonArray(tunnels.map(::JsonPrimitive)), "action" to "reject"))
                    add(obj("inbound" to arr("tun"), "protocol" to "dns", "action" to "hijack-dns"))
                    // UDP only: the engine never completes a hijacked TCP connection, and apps test "am I online?" by
                    // opening TCP to 8.8.8.8:53. That TCP goes out through the app's own exit like any other connection.
                    add(obj("inbound" to arr("tun"), "port" to 53, "network" to "udp", "action" to "hijack-dns"))
                    // Fake IPs an app kept from the previous domain table name nothing now: refused at once, so the app
                    // looks the name up again instead of waiting on a dead address.
                    add(obj("ip_cidr" to JsonArray((FAKE_RANGES - FAKE_RANGES[i.fakeBlock]).map(::JsonPrimitive)), "action" to "reject", "no_drop" to true))
                    // The app's own tests: each proxy user goes out through its exit only, names resolved there too.
                    if (i.probe != null) probeTags.forEach { (t, dns) -> // nothing to add when there is no tunnel
                        add(obj("inbound" to PROBE, "auth_user" to arr(t), "action" to "resolve", "server" to dns))
                        add(obj("inbound" to PROBE, "auth_user" to arr(t), "outbound" to t))
                    }
                    network.forEach { add(it) }
                    // While Tailscale waits for a sign-in, its login pages skip the tailnet for apps on a Tailscale exit: the
                    // exit node can't carry them before the sign-in. Other apps keep their own exits, and once signed in
                    // these sites follow the exit too (the engine's own control traffic never needs the rule).
                    val tsLogin = if (!ts || !i.tsLoginPending) emptyList() else listOfNotNull(
                        ("domain_suffix" to arr("tailscale.com", "tailscale.io")).takeIf { s.tsExit != null && officialTs(s.home) },
                        // An address only when it truly is one (no lookup): "1hs.example.com" is a name.
                        s.home.tsControlUrl?.let { u -> java.net.URI(u).host?.let { h ->
                            when {
                                ipv4(h) != null -> "ip_cidr" to arr("$h/32")
                                h.startsWith("[") -> h.trim('[', ']').takeIf { '%' !in it }?.let { "ip_cidr" to arr("$it/128") } // a zone can't be routed on
                                else -> "domain" to arr(h)
                            }
                        } },
                    )
                    val onTs = { id: String -> s.route(id)?.tsExit != null }
                    for ((id, p) in perApp) {
                        if (id in i.blocked) { add(obj("package_name" to p, "action" to "reject", "no_drop" to true)); continue }
                        // Before its exit's DNS, which an exit node not yet signed in can't answer; Direct resolves locally.
                        if (onTs(id)) tsLogin.forEach { add(obj("package_name" to p, it, "outbound" to "direct")) }
                        add(obj("package_name" to p, "action" to "resolve", "server" to dnsFor(id)))
                        network.forEach { add(it) }
                        add(obj("package_name" to p, "outbound" to tag(id)))
                    }
                    // Everything left follows the default: refused while it has no tunnel, after the LAN and home rules above.
                    if (defaultBlocked) { add(obj("action" to "reject", "no_drop" to true)); return@putJsonArray }
                    if (onTs(s.defaultRoute)) tsLogin.forEach { add(obj(it, "outbound" to "direct")) }
                    add(obj("action" to "resolve", "server" to defaultDns))
                    network.forEach { add(it) }
                    // Ping has no owner the engine can look up, so it never matches an app's rule and lands here: with
                    // Direct as the default, an app kept on a VPN could ping out from the phone's own address. Refused,
                    // after the LAN and home rules (names resolved), so pinging the router or a home box still works.
                    if (s.defaultRoute == DIRECT && perApp.keys.any { it != DIRECT }) add(obj("network" to "icmp", "action" to "reject"))
                }
                put("final", if (active.isEmpty() || defaultBlocked) "direct" else tag(s.defaultRoute)) // blocked: never reached
                put("auto_detect_interface", true)
                put("default_domain_resolver", "local")
            }
            putJsonObject("experimental") {
                putJsonObject("cache_file") { put("enabled", true); put("path", i.cacheFile); put("store_fakeip", true) }
            }
        }.toString()
    }

    /** Private ranges only: a home tunnel must never capture public traffic meant for an exit. */
    private val PRIVATE = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10")

    private val IPV4 = Regex("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?:/(\\d{1,2}))?")

    /** An IPv4 address or CIDR (no prefix = /32) as address and prefix length; null unless strictly that. */
    fun ipv4(cidr: String): Pair<Long, Int>? {
        val g = IPV4.matchEntire(cidr.trim())?.groupValues ?: return null
        val o = g.subList(1, 5).map { it.toInt() }
        val len = g[5].ifEmpty { "32" }.toInt()
        // Leading zeros ("010") read as octal in some parsers and are refused by the engine's.
        if (o.any { it > 255 } || len > 32 || g.subList(1, 6).any { it.length > 1 && it.startsWith('0') }) return null
        return o.fold(0L) { a, v -> a shl 8 or v.toLong() } to len
    }

    /** [cidr] with its host bits cleared ("192.168.1.0/16" → "192.168.0.0/16"), as Android reads it; null if not IPv4. */
    fun canonical(cidr: String): String? = ipv4(cidr)?.let { (a, len) ->
        val v = if (len == 0) 0L else a and (0xFFFFFFFFL shl (32 - len)) and 0xFFFFFFFFL
        (3 downTo 0).joinToString(".") { (v shr (it * 8) and 255).toString() } + "/$len"
    }

    /** Home subnets behind Tailscale, private only, as Android will route them. */
    private fun homeSubnets(s: Setup) = s.home.subnets.mapNotNull(::canonical).filter(::isPrivate).distinct()

    /** True when the whole of [cidr] lies inside a private IPv4 range. */
    fun isPrivate(cidr: String): Boolean {
        val (a, l) = ipv4(cidr) ?: return false
        return PRIVATE.any { r -> val (b, m) = ipv4(r)!!; l >= m && (a xor b) shr (32 - m) == 0L }
    }

    /** Networks to send through the home tunnel: its private AllowedIPs; else the interface's own subnet. */
    fun homeCidrs(p: WgPeer): List<String> = p.allowedIps.mapNotNull(::canonical).filter(::isPrivate).distinct().ifEmpty {
        p.address.mapNotNull(::canonical).filter(::isPrivate).distinct()
    }

    /** Resolver inside a route's tunnel: custom DNS, else the provider's blocking resolver if asked, else the tunnel's own, else Nord's (filtering if TP Lite is on), else Quad9. */
    private fun dnsServer(s: Setup, r: app.unbraid.data.Route, link: Link?): String {
        val provider = r.takeIf { !it.imported }?.loc?.provider
        return s.prefs.customDns.firstOrNull()
            ?: FILTER_DNS[provider]?.takeIf { r.filter }
            ?: (link as? WgLink)?.peer?.dns?.firstOrNull()
            ?: if (provider == "nord") (if (s.prefs.threatProtection) NORD_TP else NORD_DNS) else "9.9.9.9"
    }

    /** The engine's error for a bad key quotes it, so every key is checked here and never named. */
    private fun wg(tag: String, p: WgPeer, name: String) = buildJsonObject {
        require(validWgKey(p.privateKey) && validWgKey(p.publicKey) && p.presharedKey?.let(::validWgKey) != false) { "Invalid WireGuard key for $name" }
        put("type", "wireguard"); put("tag", tag)
        // 1280 fits every mobile path (cellular, 464XLAT); 1420 makes 1480-byte packets that stall big uploads there.
        put("mtu", minOf(p.mtu, 1280))
        put("address", JsonArray(p.address.map(::JsonPrimitive)))
        put("private_key", p.privateKey)
        putJsonArray("peers") {
            add(buildJsonObject {
                val host = p.endpoint.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
                put("address", host)
                put("port", p.endpoint.substringAfterLast(':').toIntOrNull() ?: 51820)
                put("public_key", p.publicKey)
                p.presharedKey?.let { put("pre_shared_key", it) }
                put("allowed_ips", arr("0.0.0.0/0"))
                put("persistent_keepalive_interval", 25)
            })
        }
    }

    private val OPENVPN_ROUTING = listOf("route ", "route-ipv6 ", "route-metric", "redirect-gateway", "redirect-private", "block-ipv6", "block-outside-dns")

    /**
     * Manual builds OpenVPN endpoints with `route_no_pull`, which also drops the pushed DNS. Here the server's routes
     * stay ignored (sing-box's own rules steer traffic) but its DNS is kept, for the `openvpn` DNS server.
     */
    private fun openVpn(e: JsonObject) = JsonObject(
        e.filterKeys { it != "route_no_pull" } + ("pull_filters" to JsonArray(
            // First match wins: the app's ignores go first, so a profile's own accept filter can't let pushed routes through.
            OPENVPN_ROUTING.map { obj("action" to "ignore", "text" to it) } + ((e["pull_filters"] as? JsonArray) ?: JsonArray(emptyList())),
        )),
    )

    /** Two or more DNS labels: letters, digits and inner hyphens, 63 characters each at most. */
    private val DOMAIN = Regex("(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+")

    /** A MagicDNS suffix as Tailscale reports it ("tail1234.ts.net." or a Headscale base domain), or null if unusable. */
    fun tsSuffix(raw: String?): String? = raw?.trim()?.lowercase()?.trim('.')?.takeIf { it.matches(DOMAIN) && it != "ts.net" }

    /** Tailscale's own control server (none set, or one on tailscale.com) rather than a Headscale. */
    fun officialTs(h: Home) = h.tsControlUrl == null || runCatching { java.net.URI(h.tsControlUrl).host.lowercase() }.getOrNull()?.let { it == "tailscale.com" || it.endsWith(".tailscale.com") } == true

    /**
     * The suffix the tailnet's DNS may answer for every app: a tailnet's own under ts.net on Tailscale's server; on
     * Headscale (which can name any domain, even a whole top-level one) only the one the user allowed.
     */
    fun trustedSuffix(raw: String?, h: Home) = tsSuffix(raw)?.takeIf { if (officialTs(h)) it.endsWith(".ts.net") else it == h.tsSuffixAllowed }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Same key = same physical exit with the same credentials, whichever route asked for it. */
    internal fun linkKey(r: Route, l: Link): String = when (l) {
        // Everything the engine's WireGuard device is built from, whichever provider or import it came through.
        is WgLink -> listOf(wgKey(l.peer))
        // Same remotes but another protocol, CA or login is another tunnel: compare the whole profile.
        is OvpnLink -> listOf("ovpn", sha(l.profile.config), l.profile.username, sha(l.profile.password))
        // The plugin and its options (TLS, certificate, host) are part of the tunnel too.
        is SsLink -> listOf("ss", l.profile.server, l.profile.port.toString(), l.profile.method, sha(l.profile.password), l.profile.plugin.orEmpty(), sha(l.profile.pluginOpts.orEmpty()))
        is TsLink -> listOf("ts")
    }.joinToString("|")

    private fun wgIdentity(p: WgPeer) = listOf(p.endpoint.lowercase(), p.publicKey, sha(p.privateKey)).joinToString("|")
    // Address order too: the engine sends from the last IPv4 address.
    private fun wgKey(p: WgPeer) = listOf("wg", wgIdentity(p), sha(p.presharedKey.orEmpty()), p.address.joinToString(","), minOf(p.mtu, 1280)).joinToString("|")

    const val CLASH = "Another of your WireGuard connections uses this key on the same server with other settings. Use one config for both."

    /**
     * Routes whose WireGuard identity (client key on one server) is already taken, with other settings, by an earlier
     * route or the home tunnel: one identity can be one tunnel only, two would knock each other off.
     */
    fun clashes(s: Setup, links: Map<String, Link>): Set<String> {
        val seen = HashMap<String, String>().apply { s.home.wg?.let { put(wgIdentity(it), "home") } }
        return s.activeRoutes.filter { it.tsExit == null }.mapNotNullTo(HashSet()) { r ->
            val p = (links[r.id] as? WgLink)?.peer ?: return@mapNotNullTo null
            r.id.takeIf { seen.getOrPut(wgIdentity(p)) { wgKey(p) }.let { it == "home" || it != wgKey(p) } }
        }
    }

    private fun arr(vararg v: String) = JsonArray(v.map(::JsonPrimitive))

    private fun obj(vararg kv: Pair<String, Any>) = JsonObject(kv.associate { (k, v) ->
        k to when (v) {
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is JsonArray -> v
            else -> error("unsupported $v")
        }
    })
}
