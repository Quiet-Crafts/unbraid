package app.unbraid.ui

import androidx.compose.foundation.background
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import app.unbraid.data.OwnServer
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.unbraid.data.City
import app.unbraid.data.Country
import app.unbraid.data.Location
import app.unbraid.data.DIRECT
import app.unbraid.data.favKey
import app.unbraid.data.ident
import app.unbraid.data.Nord
import app.unbraid.data.Providers
import app.unbraid.data.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val NordGroups = listOf<Pair<String?, String>>(
    null to "Standard", "legacy_p2p" to "P2P", "legacy_dedicated_ip" to "Dedicated IP",
    "legacy_double_vpn" to "Double VPN", "legacy_onion_over_vpn" to "Onion",
)
private val NordProtocols = listOf<Triple<String?, String, String>>(
    Triple(null, "NordLynx", "Fastest. Built on WireGuard"),
    Triple("openvpn_udp", "OpenVPN UDP", "Wider compatibility"),
    Triple("openvpn_tcp", "OpenVPN TCP", "For networks that block VPNs"),
)

/** A city saved before regions were stored: Mullvad, IVPN and PIA can't tell which city it was, so it must be picked again. */
// Also a pinned favourite saved without its server's address and key (before favourites kept them): star it again.
private fun stale(l: Location) = l.provider != "nord" && l.city != null && l.region == null || l.pinned?.host?.isEmpty() == true

private class Pick(
    val provider: String, val current: Location?, val group: String?, val protocol: String?, val tint: Color, val hasLoad: Boolean, val choose: (Location) -> Unit,
) {
    fun on(l: Location) = current != null && !stale(l) && current.ident() == l.ident()
}

private val nodeName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,62}")
private val dotted = Regex("[0-9.]+")

/** A node name, or a Tailscale address: 100.64.0.0/10, so the second octet is 64 to 127. */
private fun validNode(s: String) = nodeName.matches(s) && (!dotted.matches(s) || s.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }.let { o ->
    o.size == 4 && s.count { it == '.' } == 3 && o[0] == 100 && o[1] in 64..127
})

/** Most servers a manual list shows; NordVPN's comes from its recommendations, best 20 only. */
private const val SERVER_LIMIT = 200

private suspend fun serverList(ui: Ui, loc: Location): List<Server> = withContext(Dispatchers.IO) {
    val p = Providers.get(loc.provider)
    val account = p?.let { ui.accounts.value[it.id] } ?: return@withContext emptyList()
    runCatching { p.servers(loc.copy(pinned = null), account, if (p.id == "nord") 20 else SERVER_LIMIT) }.getOrDefault(emptyList())
}

/**
 * Location picker. Two tabs: Providers (provider and protocol dropdowns, then their locations) and My servers (Tailscale
 * devices and the servers saved in Settings). The country list starts collapsed; route options sit within thumb reach.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PickerScreen(ui: Ui, target: String, close: () -> Unit, settings: () -> Unit, addProvider: () -> Unit) {
    val context = LocalContext.current
    val setup by ui.setup.collectAsStateWithLifecycle()
    val catalogs by ui.countries.collectAsStateWithLifecycle()
    val accounts by ui.accounts.collectAsStateWithLifecycle()
    val vpn by ui.vpn.collectAsStateWithLifecycle()
    val route = setup.route(target)
    val current = route?.takeIf { !it.imported }?.loc
    val signed = Providers.all.filter { it.id in accounts }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val provider = chosen?.takeIf { c -> signed.any { it.id == c } } ?: current?.provider?.takeIf { c -> signed.any { it.id == c } } ?: signed.firstOrNull()?.id
    val catalog = provider?.let { catalogs[it] }.orEmpty()
    var q by rememberSaveable { mutableStateOf("") }
    var groupChoice by rememberSaveable { mutableStateOf(current?.group) }
    val group = groupChoice.takeIf { provider == "nord" }
    var protocolChoice by rememberSaveable { mutableStateOf(current?.protocol) }
    // Double VPN and Onion only run over OpenVPN: UDP unless TCP was chosen.
    val protocol = protocolChoice.takeIf { provider == "nord" }?.takeIf { group !in Nord.openVpnOnly || it?.startsWith("openvpn") == true }
        ?: "openvpn_udp".takeIf { provider == "nord" && group in Nord.openVpnOnly }
    // A route that already uses a Tailscale exit or a saved server opens on "My servers".
    var mine by rememberSaveable { mutableStateOf(route?.imported == true || provider == null && (setup.tailscale || setup.servers.isNotEmpty())) }
    var allOpen by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) } // "options" or "c:<country id>"
    // Specialty groups exist in a few countries only: list just those.
    val allowed by produceState<Set<String>?>(null, provider, group) {
        value = if (provider == "nord" && group != null && group != "legacy_standard") ui.nordGroupCountries(group) else null
    }
    val countries = remember(catalog, allowed) { allowed?.let { a -> catalog.filter { it.code in a } } ?: catalog }
    val tint = Ink.route(route?.hue ?: 0)
    LaunchedEffect(provider, countries.isEmpty()) { if (provider != null && countries.isEmpty()) ui.refreshCountries(provider) }
    val hasLoad = Providers.get(provider.orEmpty())?.hasLoad == true
    val pick = Pick(provider.orEmpty(), current, group, protocol, tint, hasLoad) { picked ->
        // A Nord server found over OpenVPN may have no NordLynx key: under NordLynx it is picked again instead.
        val loc = picked.copy(pinned = picked.pinned?.takeIf { picked.provider != "nord" || picked.protocol != null || it.publicKey.isNotEmpty() })
        when {
            target.isEmpty() -> ui.addRoute(loc)
            target == "default" -> ui.chooseDefault(loc)
            target.startsWith("app:") -> ui.addRouteFor(target.removePrefix("app:"), loc)
            else -> ui.setLocation(target, loc)
        }
        close()
    }
    val query = q.trim()
    fun hit(s: String) = query.isEmpty() || s.contains(query, ignoreCase = true)
    val ownFavs = setup.favorites.filter { it.provider == provider && !stale(it) }.distinctBy { it.favKey() }
    val nodes = vpn.tsExitNodes.sortedByDescending { it.online }
    var tsNote by remember { mutableStateOf<String?>(null) }
    val exitRoute = setup.routes.firstOrNull { it.tsExit != null }
    val exitNode = exitRoute?.let { it.tsName ?: it.tsExit }
    fun exit(ip: String, name: String) {
        ui.tsLock(ip)?.let { r ->
            tsNote = "${setup.profile ?: "The setup in use"} uses the Tailscale exit ${r.tsName ?: r.tsExit}. Tailscale uses one exit node at a time: pick that one here, or this one once this profile is in use."
            return
        }
        ui.addTsExit(target, ip, name)
        close()
    }
    // Protocol applies to this route at once, keeping its location; a server type narrows the list for the next pick.
    fun setProtocol(p: String?) {
        protocolChoice = p
        // A pinned server found over OpenVPN may have no NordLynx key: let the server be picked again then.
        // NordLynx can't carry Double VPN or Onion, so choosing it drops that server type from the route.
        if (current?.provider == "nord" && route?.id == target) ui.setLocation(target, current.copy(
            protocol = p, group = current.group.takeIf { p != null || it !in Nord.openVpnOnly },
            pinned = current.pinned?.takeIf { p != null || it.publicKey.isNotEmpty() },
        ))
    }
    val groupLabel = NordGroups.first { it.first == group }.second
    val protocolLabel = NordProtocols.first { it.first == protocol }.second

    fun LazyListScope.countryRow(c: Country) = item(key = "c:${c.id}") {
        val all = Location(pick.provider, c.id, c.code, c.name, group = group, protocol = protocol)
        PickRow({ pick.choose(all) }, pick.on(all), tint, Modifier.padding(horizontal = 8.dp)) {
            Box(Modifier.width(36.dp), Alignment.Center) { Code(c.code, Ink.dim, filled = false) }
            Title(c.name)
            Trailing(pick.on(all), tint)
            IconTap(Icons.more, "Cities and servers in ${c.name}", { sheet = "c:${c.id}" }, tint = Ink.dim)
        }
    }

    fun LazyListScope.favorites(list: List<Location>) {
        if (list.isEmpty()) return
        item(key = "favorites") { SectionLabel("Favorites") }
        items(list, key = { "f:${it.favKey().hashCode()}" }) { f ->
            val l = f.copy(group = group, protocol = protocol)
            PickRow({ pick.choose(l) }, pick.on(l), tint, Modifier.padding(horizontal = 8.dp)) {
                Box(Modifier.width(36.dp), Alignment.Center) { Code(f.code, Ink.dim, filled = false) }
                Title(f.label)
                Trailing(pick.on(l), tint)
                Star(true) { ui.toggleFavorite(f) }
            }
        }
    }

    // An empty profile's default can also be Direct (Routes lists Direct only once an app uses it): on either tab, and
    // with no provider signed in too.
    fun LazyListScope.direct() {
        if (target == "default") item(key = "direct") {
            PickRow({ ui.setDefault(DIRECT); close() }, false, tint, Modifier.padding(horizontal = 8.dp)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Ink.surfaceTop), Alignment.Center) {
                    Icon(Icons.direct, null, tint = Ink.dim, modifier = Modifier.size(20.dp))
                }
                Title("Direct", "No VPN: apps use your normal connection")
            }
        }
    }

    fun LazyListScope.locations() {
        if (provider == null) return
        if (countries.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                    Text("Server list unavailable. Check the connection.", style = Type.sub, color = Ink.dim)
                    Secondary("Retry", { ui.refreshCountries(provider) }, Modifier.padding(top = 8.dp))
                }
            }
            return
        }
        item(key = "fastest") {
            val any = Location(provider, group = group, protocol = protocol)
            PickRow({ pick.choose(any) }, pick.on(any), tint, Modifier.padding(horizontal = 8.dp)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Ink.surfaceTop), Alignment.Center) {
                    Icon(Icons.bolt, null, tint = Ink.accent, modifier = Modifier.size(20.dp))
                }
                Title(if (hasLoad) "Fastest anywhere" else "Automatic", "Any country, picked on connect")
                Trailing(pick.on(any), tint)
            }
        }
        favorites(ownFavs)
        item(key = "all") {
            PickRow({ allOpen = !allOpen }, false, tint, Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Box(Modifier.width(36.dp), Alignment.Center) { Text("${countries.size}", style = Type.code, color = Ink.dim) }
                Title("All countries", if (allOpen) null else "Tap a country to use its fastest server")
                val turn by animateFloatAsState(if (allOpen) 180f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "chevron")
                Icon(Icons.expand, null, tint = Ink.dim, modifier = Modifier.size(20.dp).rotate(turn))
            }
        }
        if (allOpen) countries.forEach { countryRow(it) }
    }

    fun LazyListScope.tailscale(list: List<app.unbraid.vpn.TsNode>, searching: Boolean = false, typed: String? = null) {
        if (!setup.tailscale) return
        item(key = "tsLabel") { SectionLabel("Tailscale exit node") }
        val note = listOfNotNull(
            "Connect and sign in to Tailscale to see your devices.".takeIf { vpn.tsExitNodes.isEmpty() },
            "One exit node at a time. Choosing another replaces $exitNode.".takeIf { exitNode != null },
        ).joinToString(" ")
        if (note.isNotEmpty() && !searching) item(key = "tsNote") { Caption(note) }
        itemsIndexed(list, key = { _, n -> "ts:${n.ip}" }) { i, n ->
            GroupRow(
                i, list.size, n.name, "${n.ip} · ${if (n.online) "online" else "offline"}", if (n.online) Ink.text else Ink.dim, { exit(n.ip, n.name) },
                leading = { Box(Modifier.size(8.dp).clip(CircleShape).background(if (n.online) Ink.good else Ink.faint)) },
                trailing = { if (route?.tsExit == n.ip) Icon(Icons.checkCircle, "Selected", tint = tint, modifier = Modifier.size(22.dp)) },
            )
        }
        // A device the tailnet hasn't listed yet (not connected, or just added) can still be named.
        typed?.let { t -> item(key = "tsTyped") { GroupRow(0, 1, "Use $t", "As this route's Tailscale exit node", onClick = { exit(t, t) }, trailing = { Chevron() }) } }
    }

    fun LazyListScope.saved(list: List<OwnServer>) {
        if (list.isEmpty()) return
        item(key = "savedLabel") { SectionLabel("Saved servers") }
        itemsIndexed(list, key = { _, s -> "own:${s.id}" }) { i, s ->
            GroupRow(
                i, list.size, s.name, s.kind, onClick = { ui.useServer(target, s).let { id -> if (target == "default") ui.setDefault(id) }; close() },
                leading = { Code(s.code, Ink.dim, filled = false) },
                trailing = { if (route?.own == s.id) Icon(Icons.checkCircle, "Selected", tint = tint, modifier = Modifier.size(22.dp)) },
            )
        }
    }

    // Setting servers up happens in Settings; this tab only picks among them.
    fun LazyListScope.settingsNote(empty: Boolean) = item(key = "settingsNote") {
        Row(
            Modifier.padding(horizontal = Gutter).padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, Ink.line, RoundedCornerShape(20.dp))
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (empty) "No servers yet. Add a WireGuard, OpenVPN or Shadowsocks server, or turn on Tailscale, in Settings."
                else "Add or manage servers in Settings.",
                style = Type.caption, color = Ink.dim, modifier = Modifier.weight(1f),
            )
            Secondary("Open Settings", settings)
        }
    }

    fun LazyListScope.myServers() {
        tsNote?.let { item(key = "tsLockNote") { Caption(it, color = Ink.text) } }
        tailscale(nodes)
        saved(setup.servers)
        settingsNote(empty = !setup.tailscale && setup.servers.isEmpty())
    }

    fun LazyListScope.noProvider() = item(key = "noProvider") {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No providers signed in. Sign in to browse their locations.", style = Type.sub, color = Ink.dim)
            Secondary("Add a provider", addProvider)
        }
    }

    // Search covers the open tab: favourites, countries and cities, or devices and saved servers.
    fun LazyListScope.results() {
        if (mine) {
            val devices = nodes.filter { hit(it.name) || hit(it.ip) }
            val own = setup.servers.filter { hit(it.name) || hit(it.kind) }
            val typed = query.takeIf { setup.tailscale && validNode(it) && vpn.tsExitNodes.none { n -> n.ip == it || n.name.equals(it, true) } }
            if (devices.isEmpty() && own.isEmpty() && typed == null) item(key = "noMatches") { Text("No matches", style = Type.sub, color = Ink.dim, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) }
            if (devices.isNotEmpty() || typed != null) tailscale(devices, searching = true, typed = typed)
            saved(own)
            return
        }
        val favs = ownFavs.filter { hit(it.label) }
        val byName = countries.filter { hit(it.name) || hit(it.code) }
        val cities = countries.flatMap { c -> c.cities.filter { hit(it.name) }.map { c to it } }.filter { (c, _) -> c !in byName }
        if (favs.isEmpty() && byName.isEmpty() && cities.isEmpty()) {
            item(key = "noMatches") { Text("No matches", style = Type.sub, color = Ink.dim, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) }
            return
        }
        favorites(favs)
        if (byName.isNotEmpty() || cities.isNotEmpty()) item(key = "countries") { SectionLabel("Locations") }
        byName.forEach { countryRow(it) }
        items(cities, key = { (c, city) -> "city:${c.id}:${city.id}" }) { (c, city) ->
            val l = Location(pick.provider, c.id, c.code, c.name, city.id, city.name, city.key, group = group, protocol = protocol)
            PickRow({ pick.choose(l) }, pick.on(l), tint, Modifier.padding(horizontal = 8.dp)) {
                Box(Modifier.width(36.dp), Alignment.Center) { Code(c.code, Ink.dim, filled = false) }
                Title(city.name, c.name)
                Trailing(pick.on(l), tint)
            }
        }
    }

    Screen(
        overline = if (route != null) "${route.code} route · location" else if (target == "default") "Default route" else "New route",
        title = route?.name ?: "Choose a location",
        sub = when {
            route?.tsExit != null -> "Tailscale exit node"
            route?.imported == true -> "Your own server"
            current == null -> "Pick where this route exits"
            else -> listOfNotNull(route?.source, current.pinned?.name ?: current.city, (if (hasLoad) "fastest server" else "automatic server").takeIf { current.pinned == null }).joinToString(" · ")
        },
        navigation = { IconTap(Icons.close, "Close", close) },
        // Tabs, then for providers the two dropdowns, then search: each tab searches only itself.
        search = {
            Tabs(listOf("Providers", "My servers"), if (mine) 1 else 0, tint) { mine = it == 1; q = "" }
            if (!mine && provider != null) Row(Modifier.padding(horizontal = Gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dropdown("Provider", Providers.get(provider)?.name.orEmpty()) { done ->
                    signed.forEach { p -> MenuRow(p.name, selected = p.id == provider, tint = tint) { chosen = p.id; allOpen = false; done() } }
                    MenuRow("Add a provider", color = Ink.accent) { done(); addProvider() }
                }
                Dropdown("Protocol", if (provider == "nord") protocolLabel else Providers.get(provider)?.protocol.orEmpty(), enabled = provider == "nord") { done ->
                    NordProtocols.forEach { (key, label, note) ->
                        val ok = key != null || group !in Nord.openVpnOnly
                        MenuRow(label, if (ok) note else "Double VPN and Onion use OpenVPN", selected = protocol == key, enabled = ok, tint = tint) { setProtocol(key); done() }
                    }
                }
            }
            if (!mine && provider == null) Gap(8.dp)
            SearchField(q, { q = it }, if (mine) "Server or device" else "Country or city")
            Gap(4.dp)
        },
        bottom = {
            if (!mine && query.isEmpty() && provider != null) Row(
                Modifier.fillMaxWidth().background(Ink.ground).navigationBarsPadding().padding(horizontal = Gutter, vertical = 8.dp),
            ) {
                BarButton("Route options", when {
                    provider == "nord" -> groupLabel
                    Providers.get(provider.orEmpty())?.protection != null && route != null && current?.provider == provider -> if (setup.protects(route) && !(setup.prefs.filter.routes && setup.prefs.filter.server() != null)) "Protection on" else "Protection off"
                    else -> "App settings"
                }, Modifier.weight(1f)) { sheet = "options" }
            }
        },
    ) {
        if (query.isEmpty()) direct()
        when {
            query.isNotEmpty() -> results()
            mine -> myServers()
            provider == null -> noProvider()
            else -> locations()
        }
    }

    if (sheet != null) ModalBottomSheet({ sheet = null }, containerColor = Ink.surface, contentColor = Ink.text) {
        val s = sheet.orEmpty()
        LazyColumn(Modifier.padding(bottom = 16.dp)) {
            when {
                s == "options" -> {
                    item { SheetTitle("Route options") }
                    if (provider == "nord") {
                        item { SectionLabel("Server type") }
                        item {
                            FlowRow(Modifier.padding(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                NordGroups.forEach { (key, label) -> Chip(label, group == key, tint) { groupChoice = key } }
                            }
                        }
                        item { Caption("Lists only the countries that offer it. Pick a location to use it.") }
                        item { Gap(16.dp) }
                    }
                    val blocker = route?.takeIf { current?.provider == provider }?.let { r -> Providers.get(provider.orEmpty())?.protection?.let { r to it } }
                    blocker?.let { (r, o) ->
                        // unbraid's filter on VPN routes replaces the provider's blocking resolver: shown off, the choice kept.
                        val custom = setup.prefs.filter.routes && setup.prefs.filter.server() != null
                        val on = setup.protects(r)
                        item { SectionLabel("Protection") }
                        item {
                            val name = Providers.get(provider.orEmpty())?.name
                            GroupRow(0, 1, "$name blocking", if (custom) "Off while unbraid's filter is on for VPN routes" else "${o.label} · " + if (r.filter == null) "as set for $name in Settings" else "for this route only",
                                onClick = { ui.setFilter(r.id, !on); Unit }.takeUnless { custom }, checked = on && !custom, trailing = { Toggle(on && !custom, enabled = !custom) })
                        }
                        item { Gap(16.dp) }
                    }
                    item { GroupRow(0, 1, "App settings", "DNS, local network, Tailscale, kill switch", onClick = { sheet = null; settings() }, trailing = { Chevron() }) }
                }
                s.startsWith("c:") -> countries.firstOrNull { "c:${it.id}" == s }?.let { c -> countrySheet(ui, c, ownFavs, pick, group, protocol) }
            }
        }
    }
}

/** A country's cities and, on request, its servers. */
private fun LazyListScope.countrySheet(ui: Ui, c: Country, favs: List<Location>, p: Pick, group: String?, protocol: String?) {
    val all = Location(p.provider, c.id, c.code, c.name, group = group, protocol = protocol)
    item { SheetTitle(c.name) }
    item {
        FavoriteLocationRow(ui, all, p, favs, if (p.hasLoad) "Fastest in ${c.name}" else "Automatic in ${c.name}")
    }
    if (c.cities.size > 1) {
        item { SectionLabel("Cities") }
        items(c.cities, key = { "city:${it.id}" }) { city: City ->
            val l = all.copy(cityId = city.id, city = city.name, region = city.key)
            FavoriteLocationRow(ui, l, p, favs, city.name)
        }
    }
    item { ServerList(ui, all, p, favs) }
}

@Composable
private fun ServerList(ui: Ui, all: Location, p: Pick, favs: List<Location>) {
    var open by rememberSaveable(all.countryId) { mutableStateOf(false) }
    val servers by produceState<List<Server>?>(null, open, all) { if (open) value = serverList(ui, all).sortedBy { if (it.load < 0) Int.MAX_VALUE else it.load } }
    Column {
        SectionLabel("Servers") {
            Secondary(if (open) "Hide" else "Show", { open = !open }, color = Ink.accent)
        }
        if (!open) return@Column
        when {
            servers == null -> Caption("Loading servers")
            servers.isNullOrEmpty() -> Caption("No servers found")
            else -> servers.orEmpty().forEach { s ->
                val l = all.copy(pinned = s)
                FavoriteLocationRow(ui, l, p, favs, s.name, listOfNotNull(s.city.ifBlank { null }, "${s.load}% load".takeIf { p.hasLoad && s.load >= 0 }).joinToString(" · ").ifEmpty { null }, 16.dp)
            }
        }
    }
}

@Composable
private fun FavoriteLocationRow(ui: Ui, l: Location, p: Pick, favs: List<Location>, title: String, sub: String? = null, radius: Dp = 20.dp) {
    PickRow({ p.choose(l) }, p.on(l), p.tint, Modifier.padding(horizontal = 8.dp), radius = radius) {
        Title(title, sub)
        Trailing(p.on(l), p.tint)
        Star(favs.any { f -> f.favKey() == l.favKey() }) { ui.toggleFavorite(l) }
    }
}

@Composable
private fun SheetTitle(text: String) = Text(text, style = Type.title, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))

/** Two tabs as one connected pill: the highlight slides under the chosen tab, which also shows a check. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Tabs(labels: List<String>, selected: Int, tint: Color, onPick: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val motion = MaterialTheme.motionScheme
    BoxWithConstraints(
        Modifier.padding(horizontal = Gutter).padding(top = 12.dp, bottom = 4.dp).fillMaxWidth().height(48.dp)
            .clip(RoundedCornerShape(24.dp)).border(1.dp, Ink.line, RoundedCornerShape(24.dp)).padding(4.dp),
    ) {
        val w = maxWidth / labels.size
        val x by animateDpAsState(w * selected, motion.defaultSpatialSpec(), label = "tab")
        Box(Modifier.offset { IntOffset(x.roundToPx(), 0) }.width(w).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(tint.copy(alpha = 0.22f)))
        Row(Modifier.fillMaxSize().selectableGroup()) {
            labels.forEachIndexed { i, label ->
                val on = i == selected
                val color by animateColorAsState(if (on) Ink.text else Ink.dim, motion.fastEffectsSpec(), label = "tabLabel")
                Row(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp))
                        .selectable(on, role = Role.Tab) { if (!on) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick(i) } },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedVisibility(on, enter = fadeIn(motion.fastEffectsSpec()) + expandHorizontally(motion.fastSpatialSpec()), exit = fadeOut(motion.fastEffectsSpec()) + shrinkHorizontally(motion.fastSpatialSpec())) {
                        Icon(Icons.check, null, Modifier.padding(end = 6.dp).size(18.dp), tint = color)
                    }
                    Text(label, style = Type.sub, color = color)
                }
            }
        }
    }
}

/** A labelled field that opens a menu of choices under itself; read-only when there is nothing to choose. */
@Composable
private fun RowScope.Dropdown(label: String, value: String, enabled: Boolean = true, menu: @Composable ColumnScope.(done: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.weight(1f)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp)).background(Ink.surfaceHigh)
                .then(if (enabled) Modifier.tap({ open = true }, "$label: $value") else Modifier).padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label.uppercase(), style = Type.overline.copy(fontSize = 10.sp), color = Ink.dim)
                Text(value, style = Type.body, color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (enabled) Icon(Icons.dropDown, null, tint = Ink.dim, modifier = Modifier.size(24.dp))
        }
        DropdownMenu(open, { open = false }, shape = RoundedCornerShape(18.dp), containerColor = Ink.surfaceTop) { menu { open = false } }
    }
}

@Composable
private fun MenuRow(text: String, sub: String? = null, selected: Boolean = false, enabled: Boolean = true, tint: Color = Ink.accent, color: Color = Ink.text, onClick: () -> Unit) =
    DropdownMenuItem(
        text = {
            Column {
                Text(text, style = Type.body, color = if (enabled) color else Ink.faint)
                sub?.let { Text(it, style = Type.caption, color = Ink.dim) }
            }
        },
        onClick = onClick, enabled = enabled,
        trailingIcon = if (selected) ({ Icon(Icons.check, "Selected", tint = tint, modifier = Modifier.size(20.dp)) }) else null,
    )

@Composable
private fun BarButton(label: String, value: String, modifier: Modifier, onClick: () -> Unit) = Row(
    modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(20.dp)).background(Ink.surfaceHigh).tap(onClick, label).padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f)) {
        Text(label, style = Type.caption, color = Ink.dim)
        Text(value, style = Type.body, color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Icon(Icons.expand, null, tint = Ink.dim, modifier = Modifier.size(22.dp).rotate(180f))
}

@Composable
private fun Chip(label: String, on: Boolean, tint: Color, onClick: () -> Unit) = FilterChip(
    selected = on, onClick = onClick, label = { Text(label, style = Type.sub.copy(color = Ink.text)) },
    shape = RoundedCornerShape(12.dp),
    colors = FilterChipDefaults.filterChipColors(containerColor = Color.Transparent, selectedContainerColor = tint.copy(alpha = 0.2f)),
    border = FilterChipDefaults.filterChipBorder(true, on, borderColor = Ink.line, selectedBorderColor = tint.copy(alpha = 0.2f)),
)

@Composable
private fun PickRow(onClick: () -> Unit, selected: Boolean, tint: Color, modifier: Modifier = Modifier, radius: Dp = 20.dp, content: @Composable RowScope.() -> Unit) = Row(
    modifier.fillMaxWidth().heightIn(min = if (radius < 20.dp) 48.dp else 56.dp).clip(RoundedCornerShape(radius))
        .background(if (selected) tint.copy(alpha = 0.16f) else Color.Transparent).tap(onClick).padding(horizontal = 16.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
    content = content,
)

@Composable
private fun RowScope.Title(title: String, sub: String? = null) = Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(title, style = Type.body, color = Ink.text, maxLines = 1)
    sub?.let { Text(it, style = Type.caption, color = Ink.dim) }
}

@Composable
private fun Trailing(selected: Boolean, tint: Color) {
    if (selected) Icon(Icons.checkCircle, "Selected", tint = tint, modifier = Modifier.size(22.dp))
}

/** Favourite star: it fills and pops (1 → 1.18 → 1) when starred, with a toggle tick either way. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Star(on: Boolean, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val pop = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(on) }
    LaunchedEffect(on) {
        val starred = on && !seen
        seen = on
        if (starred) pop.snapTo(1.18f)
        pop.animateTo(1f, spec) // also settles a pop cut short by un-starring
    }
    Box(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
        IconTap(
            if (on) Icons.starFilled else Icons.star, if (on) "Remove from favorites" else "Add to favorites",
            { haptic.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn); onClick() },
            tint = if (on) Ink.accent else Ink.faint,
        )
    }
}
