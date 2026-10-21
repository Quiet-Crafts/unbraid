package app.unbraid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.material3.LoadingIndicator
import androidx.compose.foundation.shape.CircleShape
import androidx.graphics.shapes.Morph
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.State
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.unbraid.APP
import app.unbraid.BuildConfig
import app.unbraid.Unbraid
import app.unbraid.data.DIRECT
import app.unbraid.stats.SpeedTest
import app.unbraid.stats.Stats
import app.unbraid.vpn.UnbraidVpn
import app.unbraid.vpn.VpnState.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DayStyle
import java.util.Locale

// The Stats tab: a hub of five tiles, each opening its section; the speed test and its history hang off
// Live and Tunnels. Everything is read from the local stats file; nothing exists while stats are off.

private enum class Period(val label: String) { Day("Day"), Week("Week"), Month("Month") }

/** An exit as stats show it: the route's current look if it still exists, else the label it had when used. */
private data class Ex(val id: String, val code: String, val name: String, val via: String, val color: Color)

/** Everything the hub and the sections read for one period, loaded off the main thread. */
private class View(
    val from: Long, val to: Long, val exits: Map<String, Ex>, val byExit: List<Pair<String, Stats.Bytes>>, val total: Stats.Bytes,
    val columns: List<Map<String, Stats.Bytes>>, val labels: List<String>, val selected: Int,
    val apps: List<Triple<String, String, Long>>,
    /** Each exit's fastest speed test in the period (by download). */
    val tests: Map<String, Stats.Test>,
    val week: List<Float>, val weekMs: Long, val heat: List<Float>, val heatStart: LocalDate,
)

private const val GB = 1_000_000_000.0

private fun bytes(b: Long): Pair<String, String> = when {
    b >= GB -> String.format(Locale.US, "%.1f", b / GB) to "GB"
    b >= 1_000_000 -> String.format(Locale.US, "%.0f", b / 1e6) to "MB"
    else -> String.format(Locale.US, "%.0f", b / 1e3) to "kB"
}

/**
 * Who carried an exit's data: its provider's name, "Own servers" for imported configs (only their protocol is known),
 * Tailscale, or the home network. Drawn in neutral tones: route colours mean exits only.
 */
private fun providerOf(id: String, ex: Ex?): String = when {
    id == DIRECT -> "Direct, no VPN" // only counted under lockdown, when Direct goes through the app
    id == Stats.HOME -> "Home network"
    ex == null || ex.via.isEmpty() -> "Other"
    ex.via in setOf("WireGuard", "OpenVPN", "Shadowsocks") -> "Own servers"
    else -> app.unbraid.data.Providers.all.firstOrNull { app.unbraid.data.Providers.short(it.id) == ex.via }?.name ?: ex.via
}

/** An exit's speed in the period: its best speed test. Speeds seen in use are not shown. */
private fun View.fastestDown(e: String) = tests[e]?.down ?: 0

private fun View.byProvider(): List<Pair<String, Long>> =
    byExit.groupBy({ providerOf(it.first, exits[it.first]) }, { it.second.total }).mapValues { it.value.sum() }.entries.sortedByDescending { it.value }.map { it.key to it.value }

private fun bytesText(b: Long) = bytes(b).let { "${it.first} ${it.second}" }
/** A live speed: kbps under 1 Mbps so small transfers don't read as zero. */
private fun rate(bps: Long): Pair<String, String> = when {
    bps >= 1_000_000 -> mbps(bps) to "Mbps"
    bps >= 100 -> String.format(Locale.US, "%.0f", bps / 1e3).let { if (it == "0") "0.1" else it } to "kbps"
    else -> "0" to "kbps"
}
private fun mbps(bps: Long) = (bps / 1e6).let { if (it < 100) String.format(Locale.US, "%.1f", it) else it.toInt().toString() }
private fun hm(ms: Long) = (ms / 60_000).let { if (it >= 60) "${it / 60} h ${it % 60} m" else "$it m" }

private fun range(p: Period, today: LocalDate): Pair<LocalDate, LocalDate> = when (p) {
    Period.Day -> today to today
    Period.Week -> today.with(DayOfWeek.MONDAY) to today.with(DayOfWeek.MONDAY).plusDays(6)
    Period.Month -> today.withDayOfMonth(1) to today.withDayOfMonth(today.lengthOfMonth())
}

private fun rangeLabel(p: Period, today: LocalDate): String {
    val (a, b) = range(p, today)
    val f = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    return when (p) { Period.Day -> "Today · ${today.format(f)}"; else -> "${a.dayOfMonth}–${b.format(f)}" }.uppercase()
}

private fun load(p: Period, ui: Ui): View {
    val today = LocalDate.now()
    val (a, b) = range(p, today)
    val from = a.toEpochDay(); val to = b.toEpochDay()
    val setup = ui.setup.value
    val stored = Stats.exits()
    // History is keyed by what carried it: today's routes label only their own ids, older ones keep their stored label.
    val current = (setup.routes + UnbraidVpn.running?.routes.orEmpty()).associateBy { Stats.idOf(it) }
    val exits = (stored.keys + current.keys + DIRECT).associateWith { id ->
        val r = current[id]; val s = stored[id]
        when {
            r != null -> Ex(id, r.code, r.name, r.source, Ink.route(r.hue))
            id == DIRECT -> Ex(id, "··", "Direct", "outside the VPN", Ink.direct)
            s != null -> Ex(id, s.code, s.name, if (Stats.legacy(id)) "Unknown (older data)" else s.via, if (s.hue < 0) Ink.text else Ink.route(s.hue))
            else -> Ex(id, "?", id, "", Ink.faint)
        }
    }
    val days = Stats.perDay(from, to)
    val byExit = HashMap<String, Stats.Bytes>()
    days.values.forEach { m -> m.forEach { (e, v) -> byExit.merge(e, v) { x, y -> Stats.Bytes(x.up + y.up, x.down + y.down) } } }
    val columns: List<Map<String, Stats.Bytes>>
    val labels: List<String>
    if (p == Period.Day) {
        val hours = Stats.perHour(from)
        columns = (0 until 24).map { hours[it.toLong()].orEmpty() }
        labels = (0 until 24).map { if (it % 6 == 0) "%02d".format(it) else "" }
    } else {
        columns = (from..to).map { days[it].orEmpty() }
        labels = (from..to).map { d -> LocalDate.ofEpochDay(d).let { if (p == Period.Week) it.dayOfWeek.getDisplayName(DayStyle.NARROW, Locale.getDefault()) else if (it.dayOfMonth % 7 == 1) "${it.dayOfMonth}" else "" } }
    }
    val zoneMs = { d: Long -> LocalDate.ofEpochDay(d).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val tests = Stats.tests().filter { it.at in zoneMs(from) until zoneMs(to + 1) }.groupBy { it.exit }.mapValues { (_, t) -> t.maxBy { it.down } }
    // Hours connected per day, the last 13 weeks ending this Sunday; this week's days for the hub.
    val heatStart = today.with(DayOfWeek.MONDAY).minusWeeks(12)
    val zone = java.time.ZoneId.systemDefault()
    val ms = { d: LocalDate -> d.atStartOfDay(zone).toInstant().toEpochMilli() }
    val heat = (0 until 91).map { i ->
        val d = heatStart.plusDays(i.toLong())
        if (d.isAfter(today)) -1f else Stats.spans(ms(d), ms(d.plusDays(1))).filter { !it.third }.sumOf { it.second - it.first } / 3_600_000f
    }
    val week = heat.takeLast(7)
    val weekMs = (week.filter { it > 0 }.sum() * 3_600_000).toLong()
    return View(
        from, to, exits, byExit.entries.sortedByDescending { it.value.total }.map { it.key to it.value },
        byExit.values.fold(Stats.Bytes(0, 0)) { x, y -> Stats.Bytes(x.up + y.up, x.down + y.down) }, columns, labels,
        if (p == Period.Day) java.time.LocalTime.now().hour else (today.toEpochDay() - from).toInt(),
        Stats.apps(from, to), tests, week, weekMs, heat, heatStart,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatsScreen(ui: Ui) {
    val setup by ui.setup.collectAsStateWithLifecycle()
    var viewSaved by rememberSaveable { mutableStateOf(false) }
    var asking by rememberSaveable { mutableStateOf(false) }
    if (asking) StatsConsent({ asking = false }) { asking = false; ui.setStats(true) }
    if (!setup.prefs.stats && !viewSaved) return StatsOff(Stats.size() > 0, turnOn = { asking = true }, view = { viewSaved = true })

    var section by rememberSaveable { mutableStateOf<String?>(null) }
    var period by rememberSaveable { mutableIntStateOf(Period.Week.ordinal) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }
    // Peaks and the live chart need one-second samples: only while Stats is on screen and the app in front.
    LifecycleStartEffect(Unit) { Stats.watching = true; onStopOrDispose { Stats.watching = false } }
    val view by produceState<View?>(null, period, tick, section) { value = withContext(Dispatchers.IO) { Stats.read { load(Period.entries[period], ui) } } }
    BackHandler(section != null) {
        section = when { section == "history" -> "speed"; section?.startsWith("tunnel:") == true -> "tunnels"; section == "speed" -> "live"; else -> null }
    }
    val motion = MaterialTheme.motionScheme
    AnimatedContent(section, transitionSpec = { fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) }, label = "stats") { s ->
        CompositionLocalProvider(LocalScreen provides this) {
            val v = view
            val p = Period.entries[period]
            val go = { to: String? -> section = to }
            // Pinned under the header while the section scrolls, on the page's ground so nothing shows through.
            val periodPicker: LazyListScope.() -> Unit = { stickyHeader(key = "period") { Box(Modifier.background(Ink.ground).padding(bottom = 4.dp)) { PeriodPicker(p) { period = it.ordinal } } } }
            when {
                s == null -> Hub(ui, v, p, recording = setup.prefs.stats, periodPicker, go)
                v == null -> Unit
                s == "usage" -> Usage(v, p, periodPicker, go)
                s == "live" -> Live(v, go)
                s == "flows" -> Flows(ui, v, p, periodPicker, go)
                s == "journal" -> Journal(v, go)
                s == "tunnels" -> Tunnels(v, p, periodPicker, go)
                s.startsWith("tunnel:") -> Tunnel(v, p, s.removePrefix("tunnel:"), periodPicker, go)
                s == "speed" -> Speed(ui, v, go)
                s == "history" -> History(v, go)
            }
        }
    }
}

// ---- shared pieces ---------------------------------------------------------------------------------------------

/** The UI's locale, observed: day and month names follow a language change. */
@Composable
private fun locale(): Locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]

@Composable
private fun Back(onClick: () -> Unit) = IconTap(Icons.back, "Back", onClick)

@Composable
private fun PeriodPicker(p: Period, pick: (Period) -> Unit) = Row(
    Modifier.padding(horizontal = Gutter, vertical = 4.dp).fillMaxWidth().selectableGroup(),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
) {
    Period.entries.forEachIndexed { i, x ->
        val on = x == p
        val shape = when (i) { 0 -> RoundedCornerShape(24.dp, 8.dp, 8.dp, 24.dp); Period.entries.lastIndex -> RoundedCornerShape(8.dp, 24.dp, 24.dp, 8.dp); else -> RoundedCornerShape(8.dp) }
        Row(
            Modifier.weight(1f).height(48.dp).clip(shape).background(if (on) Ink.accentContainer else Ink.surfaceHigh).selectable(on, role = Role.RadioButton) { pick(x) },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (on) Icon(Icons.check, null, Modifier.padding(end = 6.dp).size(18.dp), tint = Ink.text)
            Text(x.label, style = Type.sub.copy(fontWeight = FontWeight.Medium), color = if (on) Ink.text else Ink.dim)
        }
    }
}

/** A measurement: mono digits, the unit in its own case and smaller. */
@Composable
private fun Num(value: String, unit: String, size: Int = 32) = Num(listOf(value to unit), size)

/** A measurement in several parts, e.g. 3 h 20 m: each number large, each unit small. */
@Composable
private fun Num(parts: List<Pair<String, String>>, size: Int = 32) = Text(
    buildAnnotatedString {
        parts.forEachIndexed { i, (v, u) ->
            if (i > 0) append(" ")
            append(v)
            if (u.isNotEmpty()) withStyle(SpanStyle(fontSize = maxOf(12f, size * 0.42f).sp, color = Ink.dim)) { append(" $u") }
        }
    },
    style = Type.code.copy(fontSize = size.sp, lineHeight = (size + 4).sp, fontWeight = FontWeight.Medium), color = Ink.text, maxLines = 1,
)

/** A duration as measurement parts: 3 h 20 m, or 45 m under an hour. */
private fun hmParts(ms: Long): List<Pair<String, String>> = (ms / 60_000).let { if (it >= 60) listOf("${it / 60}" to "h", "${it % 60}" to "m") else listOf("$it" to "m") }

/** The first card of a section: its key number, then whatever explains it. */
@Composable
private fun Hero(over: String, value: String, unit: String, sub: String?, trailing: String? = null, parts: List<Pair<String, String>>? = null, content: @Composable ColumnScope.() -> Unit = {}) = Column(
    Modifier.padding(horizontal = Gutter).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Ink.surfaceTop).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(over.uppercase(), style = Type.overline, color = Ink.dim, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = Type.code.copy(fontSize = 14.sp), color = Ink.text) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Num(parts ?: listOf(value to unit), 48)
        sub?.let { Text(it, style = Type.sub, color = Ink.dim) }
    }
    content()
}

@Composable
private fun Chip(ex: Ex?) = Code(ex?.code ?: "?", ex?.color ?: Ink.faint)

@Composable
private fun LinkButton(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) = Row(
    Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, Ink.line, RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = onClick).padding(start = 12.dp, end = 16.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
) { Icon(icon, null, Modifier.size(20.dp), tint = Ink.text); Text(text, style = Type.sub.copy(fontWeight = FontWeight.Medium), color = Ink.text) }

@Composable
private fun Empty(text: String) = Text(text, style = Type.sub, color = Ink.dim, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))

// ---- hub -------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Hub(ui: Ui, v: View?, p: Period, recording: Boolean, periodPicker: LazyListScope.() -> Unit, go: (String?) -> Unit) {
    val live by Stats.live.collectAsStateWithLifecycle()
    val vpn by ui.vpn.collectAsStateWithLifecycle()
    val play = firstShow("stats")
    Screen(
        overline = if (recording) rangeLabel(p, LocalDate.now()) else "Recording off · saved statistics", title = "Stats", sub = "",
    ) {
        periodPicker()
        item(key = "usage") {
            Rise("stats", 0, play) {
                Tile("usage", "Usage", Icons.usage, hero = true, onOpen = go) {
                    val t = v?.total?.total ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            bytes(t).let { Num(it.first, it.second, 40) }
                            Text("↓ ${bytesText(v?.total?.down ?: 0)} · ↑ ${bytesText(v?.total?.up ?: 0)}", style = Type.sub, color = Ink.dim)
                        }
                    }
                    // Who carried it, by provider: neutral meters (exits and their colours are App flows' subject).
                    v?.byProvider()?.take(3)?.forEach { (name, b) -> ProviderMeter(name, b, t) }
                }
            }
        }
        item(key = "pairs") {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Tiles in a row share the taller one's height.
                Row(Modifier.padding(horizontal = Gutter).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Rise("stats", 1, play) {
                            Tile("live", "Live", Icons.speed, onOpen = go) {
                                if (vpn.phase == Phase.On) {
                                    Num(listOf(rate(live.downNow)), 28)
                                    Text("download now", style = Type.caption.copy(fontSize = 12.sp), color = Ink.dim)
                                    val total = remember(live) { FloatArray(60) { i -> live.down.values.sumOf { it[i].toDouble() }.toFloat() } }
                                    Spark(total, Ink.text, Modifier.fillMaxWidth())
                                } else Text("Not connected", style = Type.sub, color = Ink.dim)
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Rise("stats", 2, play) {
                            Tile("flows", "App flows", Icons.flows, onOpen = go) {
                                // Where the data went: the share by exit as a ring, in the exits' colours, and how many apps.
                                val exits = v?.byExit?.filter { it.first != DIRECT }.orEmpty()
                                if (v == null || exits.isEmpty()) Text("No data yet", style = Type.sub, color = Ink.dim) else {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Ring(exits.map { (id, b) -> b.total.toFloat() to (v.exits[id]?.color ?: Ink.faint) }, "Share by exit: " + exits.joinToString { (id, b) -> "${v.exits[id]?.name} ${bytesText(b.total)}" }, size = 56.dp, stroke = 8.dp)
                                        Column {
                                            Num(v.apps.filter { it.second != DIRECT }.map { it.first }.distinct().size.toString(), "apps", 26)
                                            Text("into ${exits.size} ${if (exits.size == 1) "exit" else "exits"}", style = Type.caption.copy(fontSize = 12.sp), color = Ink.dim, maxLines = 1)
                                        }
                                    }
                                    exits.take(2).forEach { (id, b) -> Legend(v.exits[id], (100 * b.total / exits.sumOf { it.second.total }.coerceAtLeast(1)).toInt()) }
                                }
                            }
                        }
                    }
                }
                // Tiles in a row share the taller one's height.
                Row(Modifier.padding(horizontal = Gutter).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Rise("stats", 3, play) {
                            Tile("journal", "Journal", Icons.calendar, onOpen = go) {
                                Num(hmParts(v?.weekMs ?: 0), 28)
                                Text("connected this week", style = Type.caption.copy(fontSize = 12.sp), color = Ink.dim)
                                if (v != null) WeekMarks(v.week.map { it.coerceAtLeast(0f) }, DayOfWeek.entries.map { it.getDisplayName(DayStyle.NARROW, locale()) }, LocalDate.now().dayOfWeek.ordinal)
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Rise("stats", 3, play) {
                            Tile("tunnels", "Tunnels", Icons.tunnel, onOpen = go) {
                                // Each exit's fastest download test.
                                val fastest = v?.let { it.tests.keys.associateWith(it::fastestDown).filterValues { b -> b > 0 } }.orEmpty()
                                val best = fastest.maxByOrNull { it.value }
                                if (v == null || best == null) Text("No speed tests yet", style = Type.sub, color = Ink.dim) else {
                                    Num(mbps(best.value), "Mbps", 28)
                                    Text("best test · ${v.exits[best.key]?.name}", style = Type.caption.copy(fontSize = 12.sp), color = Ink.dim, maxLines = 1)
                                    // The two fastest, like App flows' legend: the hub fits one Pixel screen without scrolling.
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        fastest.entries.sortedByDescending { it.value }.take(2).forEach { (id, b) ->
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(v.exits[id]?.code ?: "?", style = Type.code.copy(fontSize = 12.sp), color = Ink.dim, modifier = Modifier.width(28.dp))
                                                Meter(b.toFloat() / best.value, v.exits[id]?.color ?: Ink.faint, Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A provider's share: its name, a neutral meter and the percentage. */
@Composable
private fun ProviderMeter(name: String, bytes: Long, total: Long) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(name, style = Type.caption.copy(fontSize = 12.sp), color = Ink.text, maxLines = 1, modifier = Modifier.width(110.dp))
    Meter(bytes.toFloat() / total.coerceAtLeast(1), Ink.text.copy(alpha = 0.75f), Modifier.weight(1f))
    Text("${100 * bytes / total.coerceAtLeast(1)}%", style = Type.code.copy(fontSize = 12.sp), color = Ink.dim, modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
}

@Composable
private fun Legend(ex: Ex?, pct: Int) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(ex?.color ?: Ink.faint))
    Text("${ex?.code ?: "?"} $pct%", style = Type.caption.copy(fontSize = 12.sp), color = Ink.dim)
}

/** A hub tile: one card, one target, opening its section (container transform from the card). */
@Composable
private fun Tile(key: String, title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, hero: Boolean = false, onOpen: (String?) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // Presses like the route cards: the shared squish; the hero also tightens its corners 28 → 16 dp, as the default route does.
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(if (hero && pressed) 16.dp else if (hero) 28.dp else 24.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "corner")
    val press = squish(source)
    val shape = RoundedCornerShape(corner)
    Column(
        Modifier.then(if (hero) Modifier.padding(horizontal = Gutter) else Modifier).graphicsLayer { scaleX = press.value; scaleY = press.value }
            .fillMaxWidth().fillMaxHeight().then(sharedRoute("stats:$key", shape))
            .clip(shape).background(if (hero) Ink.surfaceTop else Ink.surface).clickable(source, ripple(), onClickLabel = "Open $title") { onOpen(key) }
            .padding(if (hero) 16.dp else 14.dp).heightIn(min = 132.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = Ink.dim)
            Text(title, style = Type.sub.copy(fontWeight = FontWeight.Medium), color = Ink.dim, modifier = Modifier.weight(1f))
            Icon(Icons.chevron, null, Modifier.size(20.dp), tint = Ink.dim)
        }
        content()
    }
}

/** A section page; the matching tile grows into it. */
@Composable
private fun Section(key: String, over: String, title: String, sub: String = "", back: () -> Unit, content: LazyListScope.() -> Unit) =
    Box(Modifier.fillMaxSize().then(sharedRoute("stats:$key", RoundedCornerShape(28.dp))).background(Ink.ground)) {
        Screen(overline = over, title = title, sub = sub, navigation = { Back(back) }, content = { content(); item { Gap(24.dp) } })
    }

// ---- sections --------------------------------------------------------------------------------------------------

@Composable
private fun Usage(v: View, p: Period, periodPicker: LazyListScope.() -> Unit, go: (String?) -> Unit) = Section("usage", rangeLabel(p, LocalDate.now()), "Usage", back = { go(null) }) {
    periodPicker()
    item(key = "hero") {
        val max = v.columns.maxOfOrNull { c -> c.values.sumOf { it.total } } ?: 0L
        val (div, unit) = unitFor(max)
        val cap = niceCeil(max / div)
        val sel = v.columns.getOrNull(v.selected)?.values?.sumOf { it.total } ?: 0L
        bytes(v.total.total).let { (n, u) ->
            Hero("${p.label} · per ${if (p == Period.Day) "hour" else "day"}", n, u, "↓ ${bytesText(v.total.down)} download · ↑ ${bytesText(v.total.up)} upload", if (p == Period.Day) "now ${bytesText(sel)}" else "today ${bytesText(sel)}") {
                // How much, when: neutral bars (which exit is App flows' subject, in the exits' colours).
                StackedBars(
                    v.columns.map { c -> listOf((c.values.sumOf { it.total } / div).toFloat() to Ink.text.copy(alpha = 0.75f)) },
                    v.labels, cap.toFloat(), "${scaleText(cap.toFloat())} $unit", "Data per ${if (p == Period.Day) "hour" else "day"}", v.selected,
                )
            }
        }
    }
    val providers = v.byProvider()
    item(key = "byLabel") { SectionLabel("By provider · share of the ${p.label.lowercase()}") }
    if (providers.isEmpty()) item { Empty("No data in this period yet.") }
    items(providers.size) { i ->
        val (name, b) = providers[i]
        GroupRow(i, providers.size, name, null, trailing = {
            Column(Modifier.width(150.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${bytesText(b)}  ${100 * b / v.total.total.coerceAtLeast(1)}%", style = Type.code.copy(fontSize = 14.sp), color = Ink.text)
                Meter(b.toFloat() / v.total.total.coerceAtLeast(1), Ink.text.copy(alpha = 0.75f), Modifier.fillMaxWidth())
            }
        })
    }
    item(key = "note") { Caption("Data that passes through unbraid. Apps set to Direct skip it and aren't counted, unless connections without VPN are blocked. By exit and app: App flows.") }
    item(key = "links") { Row(Modifier.padding(start = Gutter, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { LinkButton(Icons.flows, "App flows") { go("flows") }; LinkButton(Icons.tunnel, "Tunnels") { go("tunnels") } } }
}

/** The next round scale value at or above [x]: 1, 2, 2.5, 5 times a power of ten. */
private fun niceCeil(x: Double): Double {
    if (x <= 0) return 1.0
    val m = Math.pow(10.0, Math.floor(Math.log10(x)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * m >= x * 0.999 } * m
}

/** The unit a chart of byte counts up to [max] reads best in, with its divisor. */
private fun unitFor(max: Long): Pair<Double, String> = when { max >= GB -> GB to "GB"; max >= 1_000_000 -> 1e6 to "MB"; else -> 1e3 to "kB" }

@Composable
private fun Live(v: View, go: (String?) -> Unit) {
    val live by Stats.live.collectAsStateWithLifecycle()
    val vpn by UnbraidVpn.state.collectAsStateWithLifecycle()
    val ids = live.down.keys.filter { it != DIRECT }.sortedBy { v.exits[it]?.name }
    Section("live", "Now · ${ids.size} ${if (ids.size == 1) "exit" else "exits"}", "Live", back = { go(null) }) {
        if (!Unbraid.store.setup.value.prefs.stats) { item { Empty("Live speeds are measured while statistics are on.") }; return@Section }
        if (vpn.phase != Phase.On) { item { Empty("Connect to see live speeds.") }; return@Section }
        item(key = "hero") {
            val maxTotal = (0 until 60).maxOfOrNull { i -> ids.sumOf { (live.down[it]?.get(i) ?: 0f).toDouble() } } ?: 0.0
            val cap = niceCeil(maxTotal / 1e6).coerceAtLeast(1.0).toFloat()
            Hero("Download · now", rate(live.downNow).first, rate(live.downNow).second, "↑ ${rate(live.upNow).let { "${it.first} ${it.second}" }} upload · every second") {
                LiveArea(ids.map { id -> live.down[id]!!.let { a -> FloatArray(a.size) { a[it] / 1e6f } } to (v.exits[id]?.color ?: Ink.faint) }, cap, "Mbps",
                    "Download per exit, last minute: " + ids.joinToString { "${v.exits[it]?.name} ${mbps(live.down[it]?.last()?.toLong() ?: 0)} Mbps" })
                Row(Modifier.fillMaxWidth().padding(end = 48.dp)) { listOf("−60 s", "−30 s", "now").forEachIndexed { i, t -> Text(t, style = Type.code.copy(fontSize = 12.sp), color = Ink.dim, modifier = Modifier.weight(1f), textAlign = when (i) { 0 -> androidx.compose.ui.text.style.TextAlign.Start; 1 -> androidx.compose.ui.text.style.TextAlign.Center; else -> androidx.compose.ui.text.style.TextAlign.End }) } }
            }
        }
        item(key = "perLabel") { SectionLabel("Per exit · now") }
        items(ids.size) { i ->
            val ex = v.exits[ids[i]]
            GroupRow(i, ids.size, ex?.name ?: ids[i], ex?.via, leading = { Chip(ex) }, trailing = { Num(listOf(rate(live.down[ids[i]]?.last()?.toLong() ?: 0)), 18) })
        }
        item(key = "test") {
            Row(
                Modifier.padding(Gutter).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(Ink.accentContainer).clickable(role = Role.Button) { go("speed") },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) { Icon(Icons.speed, null, Modifier.size(20.dp), tint = Ink.accent); Text("  Test an exit's speed", style = Type.body, color = Ink.text) }
        }
    }
}

@Composable
private fun Flows(ui: Ui, v: View, p: Period, periodPicker: LazyListScope.() -> Unit, go: (String?) -> Unit) {
    val labels = remember { ui.apps.value.associate { it.pkg to it.label } }
    val name = { pkg: String -> when (pkg) { "?" -> "Unknown app"; BuildConfig.APPLICATION_ID -> APP /* its own: speed tests, health checks */; else -> labels[pkg] ?: pkg.substringAfterLast('.') } }
    val apps = v.apps.filter { it.second != DIRECT }
    val exits = v.byExit.map { it.first }.filter { it != DIRECT }
    val total = v.byExit.filter { it.first != DIRECT }.sumOf { it.second.total }.coerceAtLeast(1)
    val biggest = v.byExit.firstOrNull { it.first != DIRECT }?.second?.total?.coerceAtLeast(1) ?: 1
    var only by rememberSaveable { mutableStateOf<String?>(null) }
    var all by rememberSaveable { mutableStateOf(false) }
    Section("flows", rangeLabel(p, LocalDate.now()), "App flows", "${apps.map { it.first }.distinct().size} apps into ${exits.size} exits · Direct traffic not shown", back = { go(null) }) {
        periodPicker()
        if (apps.isEmpty()) { item { Empty("No data in this period yet.") }; return@Section }
        item(key = "lanesLabel") { SectionLabel("Exits · data and their apps") }
        // The exits' shares of all data, as one thin bar.
        item(key = "share") {
            Row(Modifier.padding(horizontal = Gutter).padding(bottom = 10.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                exits.forEach { e -> Box(Modifier.weight(v.byExit.first { it.first == e }.second.total.coerceAtLeast(1).toFloat()).fillMaxHeight().background(v.exits[e]?.color ?: Ink.faint)) }
            }
        }
        items(exits.size, key = { "lane:" + exits[it] }) { i ->
            val e = exits[i]
            Lane(i, exits.size, v.exits[e], apps.filter { it.second == e }, v.byExit.first { it.first == e }.second.total, total, biggest, name, selected = only == e) { only = if (only == e) null else e; all = false }
        }
        // The list: every app by data, filtered by exit; long lists open on request.
        val shown = apps.filter { only == null || it.second == only }
        item(key = "listLabel") {
            SectionLabel("Apps · by data")
            LazyRow(Modifier.fillMaxWidth().selectableGroup(), contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterPill(null, "All", apps.map { it.first }.distinct().size, only == null) { only = null; all = false } }
                items(exits) { e -> FilterPill(v.exits[e], v.exits[e]?.name ?: e, apps.count { it.second == e }, only == e) { only = e; all = false } }
            }
            Gap(10.dp)
        }
        val list = if (all) shown else shown.take(LIST)
        items(list.size, key = { "app:" + list[it].first + list[it].second }) { i ->
            val (pkg, exit, b) = list[i]
            val ex = v.exits[exit]
            GroupRow(i, list.size, name(pkg), null, leading = { AppIcon(ui, pkg, name(pkg), 32.dp) }, trailing = {
                Column(Modifier.width(150.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip(ex); Text(bytesText(b), style = Type.code.copy(fontSize = 14.sp), color = Ink.text) }
                    Meter(b.toFloat() / shown.first().third.coerceAtLeast(1), ex?.color ?: Ink.faint, Modifier.fillMaxWidth())
                }
            })
        }
        if (shown.size > LIST) item(key = "more") {
            Row(Modifier.padding(start = Gutter, top = 12.dp)) { LinkButton(Icons.apps, if (all) "Show fewer" else "Show all ${shown.size}") { all = !all } }
        }
    }
}

private const val LIST = 12

/**
 * One exit as a lane: its data and share, how many apps use it, a bar as long as its data (against the biggest exit)
 * split by its five biggest apps and the rest, and one line naming them that scrolls sideways. Tap to filter the list.
 */
@Composable
private fun Lane(
    i: Int, n: Int, ex: Ex?, apps: List<Triple<String, String, Long>>, bytes: Long, total: Long, biggest: Long,
    name: (String) -> String, selected: Boolean, onClick: () -> Unit,
) {
    val color = ex?.color ?: Ink.faint
    val top = apps.take(5)
    val rest = apps.drop(5)
    val restBytes = rest.sumOf { it.third }
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, spring(dampingRatio = 1f, stiffness = 200f)) }
    val source = remember { MutableInteractionSource() }
    val press = squish(source)
    Column(
        Modifier.padding(horizontal = Gutter, vertical = 1.dp).graphicsLayer { scaleX = press.value; scaleY = press.value }.fillMaxWidth().clip(groupShape(i, n)).background(if (selected) Ink.surfaceHigh else Ink.surface)
            .then(if (selected) Modifier.border(1.5.dp, color, groupShape(i, n)) else Modifier)
            .selectable(selected, source, ripple(), role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip(ex)
            Text(ex?.name ?: "?", style = Type.body, color = Ink.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text("${apps.size} apps", style = Type.caption.copy(fontSize = 12.sp), color = Ink.dim)
            Text(bytesText(bytes), style = Type.code.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = Ink.text)
            Text("${100 * bytes / total}%", style = Type.code.copy(fontSize = 12.sp), color = Ink.dim, modifier = Modifier.width(34.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        // Segments: the five biggest apps, fading, then the rest; the bar's length is the exit's data.
        Row(
            Modifier.fillMaxWidth(bytes.toFloat() / biggest * grow.value).height(14.dp).clip(RoundedCornerShape(7.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            top.forEachIndexed { j, a -> Box(Modifier.weight(a.third.coerceAtLeast(1).toFloat()).fillMaxHeight().background(color.copy(alpha = 0.95f - j * 0.12f))) }
            if (restBytes > 0) Box(Modifier.weight(restBytes.toFloat()).fillMaxHeight().background(color.copy(alpha = 0.25f)))
        }
        // One line: the five apps by name, then the rest in one figure. Scrolls sideways if it doesn't fit.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            top.forEach { a ->
                Text(buildAnnotatedString { append(name(a.first)); withStyle(SpanStyle(color = Ink.dim)) { append(" ${bytesText(a.third)}") } }, style = Type.caption.copy(fontSize = 12.sp), color = Ink.text, maxLines = 1)
            }
            if (rest.isNotEmpty()) Text("+${rest.size} apps ${bytesText(restBytes)}", style = Type.caption.copy(fontSize = 12.sp), color = Ink.dim, maxLines = 1)
        }
    }
}

/** A filter pill over the app list: the exit's code in its colour, its name and app count. */
@Composable
private fun FilterPill(ex: Ex?, text: String, count: Int, on: Boolean, onClick: () -> Unit) {
    val color = ex?.color ?: Ink.accent
    val bg by animateColorAsState(if (on) color else Ink.surfaceHigh, MaterialTheme.motionScheme.fastEffectsSpec(), label = "pill")
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(bg).selectable(on, role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ex?.let { Text(it.code, style = Type.code.copy(fontSize = 12.sp), color = if (on) Ink.ground else it.color) }
        Text("$text $count", style = Type.sub.copy(fontWeight = FontWeight.Medium), color = if (on) Ink.ground else Ink.text, maxLines = 1)
    }
}

@Composable
private fun Journal(v: View, go: (String?) -> Unit) {
    var day by rememberSaveable { mutableIntStateOf(v.heat.indexOfLast { it >= 0 }) }
    val date = v.heatStart.plusDays(day.toLong())
    val zone = java.time.ZoneId.systemDefault()
    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() // 23 or 25 h on DST days
    val spans by produceState(emptyList<Triple<Long, Long, Boolean>>(), day, v) { value = withContext(Dispatchers.IO) { Stats.read { Stats.spans(dayStart, dayEnd) }.orEmpty() } }
    val dayLen = (dayEnd - dayStart).toFloat()
    val on = spans.filter { !it.third }
    val goodDays = v.week.count { it >= 4f }
    Section("journal", "Last 13 weeks", "Journal", back = { go(null) }) {
        item(key = "hero") {
            Hero("This week", "", "", "$goodDays of 7 days with at least 4 h connected", parts = hmParts(v.weekMs)) {
                // A month's name over the week it starts in; the first column only if no month starts in the next two.
                val starts = (0 until 13).filter { w -> v.heatStart.plusWeeks(w.toLong()).dayOfMonth <= 7 }
                val months = (listOf(0).filter { starts.none { s -> s in 1..2 } } + starts).associateWith { w -> v.heatStart.plusWeeks(w.toLong()).plusDays(6).month.getDisplayName(DayStyle.SHORT, locale()) }
                Heatmap(v.heat, day, DayOfWeek.entries.map { it.getDisplayName(DayStyle.NARROW, locale()) }, months, "Hours connected per day, last 13 weeks")
            }
        }
        item(key = "day") {
            Row(Modifier.padding(horizontal = Gutter, vertical = 12.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(Ink.surface), verticalAlignment = Alignment.CenterVertically) {
                IconTap(Icons.chevronLeft, "Previous day", { if (day > 0) day-- })
                Text(date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale())), style = Type.body, color = Ink.text, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconTap(Icons.chevron, "Next day", { if (v.heat.getOrNull(day + 1)?.let { it >= 0 } == true) day++ }, tint = if (v.heat.getOrNull(day + 1)?.let { it >= 0 } == true) Ink.text else Ink.faint)
            }
        }
        item(key = "timeline") {
            Column(Modifier.padding(horizontal = Gutter).clip(RoundedCornerShape(24.dp)).background(Ink.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Timeline(spans.map { (a, b, p) -> Triple((a - dayStart) / dayLen, (b - dayStart) / dayLen, p) },
                    "${hm(on.sumOf { it.second - it.first })} connected, ${spans.count { it.third }} pauses")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("00", "06", "12", "18", "24").forEach { Text(it, style = Type.code.copy(fontSize = 12.sp), color = Ink.dim) } }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { listOf(Ink.good to "Connected", Ink.accent to "Paused", Ink.surfaceTop to "Off").forEach { (c, t) -> Legend2(c, t) } }
            }
        }
        item(key = "facts") {
            Row(Modifier.padding(Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val pauses = spans.filter { it.third }
                listOf(
                    hmParts(on.sumOf { it.second - it.first }) to "connected",
                    hmParts(on.maxOfOrNull { it.second - it.first } ?: 0) to "longest",
                    listOf("${pauses.size}" to "") to if (pauses.isEmpty()) "pauses" else "pauses · ${hm(pauses.sumOf { it.second - it.first })}",
                ).forEach { (a, b) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(Ink.surface).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Num(a, 20)
                        Text(b, style = Type.caption.copy(fontSize = 12.sp), color = Ink.dim, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend2(c: Color, t: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(c)); Text(t, style = Type.caption.copy(fontSize = 12.sp), color = Ink.dim)
}

@Composable
private fun Tunnels(v: View, p: Period, periodPicker: LazyListScope.() -> Unit, go: (String?) -> Unit) {
    // Every exit in use or tested: fastest download test first, the rest by name.
    val fastest = v::fastestDown
    val inUse = UnbraidVpn.running?.let { r -> UnbraidVpn.tags.keys.filter { it != DIRECT }.map { Stats.idOf(it, r) } }.orEmpty()
    val ids = (inUse + v.tests.keys).distinct()
        .sortedWith(compareByDescending<String> { fastest(it) }.thenBy { v.exits[it]?.name ?: it })
    val top = ids.maxOfOrNull(fastest)?.coerceAtLeast(1) ?: 1
    val bestTest = v.tests.values.maxByOrNull { it.down }
    Section("tunnels", rangeLabel(p, LocalDate.now()), "Tunnels", back = { go(null) }) {
        periodPicker()
        item(key = "hero") {
            when {
                bestTest != null -> Hero("Fastest speed test", mbps(bestTest.down), "Mbps", "↓ download · ↑ ${mbps(bestTest.up)} Mbps upload · ${v.exits[bestTest.exit]?.name ?: bestTest.exit}")
                else -> Empty("No speed tests yet. Run one to see what each exit can do.")
            }
        }
        item(key = "label") { SectionLabel("Per exit · Mbps") }
        items(ids.size) { i ->
            val id = ids[i]
            val t = v.tests[id]
            val ex = v.exits[id]
            // The row grows into its tunnel page (container transform), as the hub tiles do into their sections.
            Column(Modifier.then(sharedRoute("stats:tunnel:$id", groupShape(i, ids.size))).clickable(onClickLabel = "Open ${ex?.name}") { go("tunnel:$id") }) {
                GroupRow(i, ids.size, ex?.name ?: id, ex?.via, leading = { Chip(ex) }, trailing = {
                    Column(Modifier.width(168.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (t == null) Text("—", style = Type.code.copy(fontSize = 16.sp), color = Ink.dim)
                        else { SpeedPair("test", t.down, t.up, Ink.text); Meter(fastest(id).toFloat() / top, ex?.color ?: Ink.faint, Modifier.fillMaxWidth()) }
                    }
                })
            }
        }
        item(key = "note") { Caption("Each exit's fastest speed test in this period. Download (↓) and upload (↑) are measured apart.") }
        item(key = "speed") { Row(Modifier.padding(start = Gutter, top = 12.dp)) { LinkButton(Icons.speed, "Speed test") { go("speed") } } }
    }
}

/** "test ↓1080 ↑940": a download and an upload speed side by side, in Mbps; a missing upload shows as a dash. */
@Composable
private fun SpeedPair(label: String, down: Long, up: Long, color: Color) = Text(
    buildAnnotatedString {
        withStyle(SpanStyle(color = Ink.dim, fontSize = 11.sp)) { append("$label ") }
        append("↓${mbps(down)} ↑${if (up > 0) mbps(up) else "—"}")
    },
    style = Type.code.copy(fontSize = 12.sp), color = color, maxLines = 1, softWrap = false,
)

@Composable
private fun Tunnel(v: View, p: Period, id: String, periodPicker: LazyListScope.() -> Unit, go: (String?) -> Unit) {
    val ex = v.exits[id]
    val servers by produceState(emptyList<Pair<String, Long>>(), v) { value = withContext(Dispatchers.IO) { Stats.read { Stats.servers(id, v.from, v.to) }.orEmpty() } }
    val t = v.tests[id]
    Section("tunnel:$id", "${ex?.via ?: ""} · ${rangeLabel(p, LocalDate.now())}", ex?.name ?: id, back = { go("tunnels") }) {
        periodPicker()
        item(key = "hero") {
            if (t == null) Empty("No speed test of this exit in this period yet.")
            else Hero("Fastest speed test", mbps(t.down), "Mbps", "↓ download · ↑ ${if (t.up > 0) mbps(t.up) else "—"} Mbps upload")
        }
        item(key = "label") { SectionLabel("Servers · share of this exit's data") }
        val total = servers.sumOf { it.second }.coerceAtLeast(1)
        items(servers.size) { i ->
            val (name, b) = servers[i]
            GroupRow(i, servers.size, name, null, trailing = {
                Row(Modifier.width(150.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Meter(b.toFloat() / total, ex?.color ?: Ink.faint, Modifier.weight(1f)); Text("${100 * b / total}%", style = Type.code.copy(fontSize = 14.sp), color = Ink.text)
                }
            })
        }
        item(key = "links") { Row(Modifier.padding(start = Gutter, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { LinkButton(Icons.flows, "Apps on this exit") { go("flows") }; LinkButton(Icons.speed, "Speed test") { go("speed") } } }
    }
}

// ---- speed test ------------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Speed(ui: Ui, v: View, go: (String?) -> Unit) {
    val run by SpeedTest.run.collectAsStateWithLifecycle()
    val vpn by ui.vpn.collectAsStateWithLifecycle()
    val setup by ui.setup.collectAsStateWithLifecycle()
    // Routed exits by name, then the tailnet's other exit nodes (tested by switching Tailscale to them for the test).
    val options = remember(vpn.phase, vpn.tsExitNodes, v.exits) {
        // Tests start by route id; the label is that of the route's current history.
        val running = UnbraidVpn.running ?: setup
        UnbraidVpn.tags.keys.filter { it != DIRECT }.map { (v.exits[Stats.idOf(it, running)] ?: Ex(it, "?", it, "", Ink.faint)).copy(id = it) }.sortedBy { it.name } +
            SpeedTest.nodes().map { Ex(SpeedTest.NODE + it.ip, "TS", it.name, "Tailscale", Ink.text) }
    }
    var chosen by rememberSaveable { mutableStateOf(options.firstOrNull()?.id) }
    val ex = options.firstOrNull { it.id == (run?.exit ?: chosen) } ?: options.firstOrNull()
    val exit = ex?.id
    val color = ex?.color ?: Ink.accent
    val node = exit?.startsWith(SpeedTest.NODE) == true
    val busy = run?.phase.let { it == SpeedTest.Phase.Preparing || it == SpeedTest.Phase.Download || it == SpeedTest.Phase.Upload }
    val motion = MaterialTheme.motionScheme
    // Leaving the page, or the app going to the background, stops a running test: it runs only while you watch it.
    LifecycleStartEffect(Unit) { onStopOrDispose { SpeedTest.cancel() } }
    DisposableEffect(Unit) { onDispose { SpeedTest.clear() } }
    val server = if (node) null else vpn.servers[exit]?.name
    Section("speed", listOfNotNull(ex?.name, server ?: ex?.via?.takeIf { node }).joinToString(" · "), "Speed test", back = { go("live") }) {
        if (vpn.phase != Phase.On || options.isEmpty()) { item { Empty("Connect to test an exit's speed.") }; return@Section }
        item(key = "exits") {
            LazyRow(Modifier.fillMaxWidth().selectableGroup(), contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(options, key = { it.id }) { e ->
                    val on = e.id == exit
                    val bg by animateColorAsState(if (on) e.color else Ink.surfaceHigh, MaterialTheme.motionScheme.fastEffectsSpec(), label = "exit")
                    Row(
                        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(bg)
                            .selectable(on, enabled = !busy, role = Role.RadioButton) { chosen = e.id; SpeedTest.clear() }.padding(start = 12.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(e.code, style = Type.code.copy(fontSize = 12.sp), color = if (on) Ink.ground else e.color)
                        Text(e.name, style = Type.sub.copy(fontWeight = FontWeight.Medium), color = if (on) Ink.ground else if (busy) Ink.dim else Ink.text, maxLines = 1)
                    }
                }
            }
        }
        item(key = "cards") {
            // Ping, then the two speeds, as Ookla orders them; the card being measured is outlined in the exit's colour.
            Row(Modifier.padding(horizontal = Gutter, vertical = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple(Icons.clock, "Ping", run?.ping?.let { "${it.median}" } to (run?.ping?.let { "ms · ${it.low}–${it.high}" } ?: "ms")), // median of five, then the range
                    Triple(Icons.down, "Download", run?.down?.let { mbps(it) } to "Mbps"),
                    Triple(Icons.up, "Upload", run?.up?.let { mbps(it) } to "Mbps"),
                ).forEach { (icon, t, value) ->
                    val active = (t == "Download" && run?.phase == SpeedTest.Phase.Download) || (t == "Upload" && run?.phase == SpeedTest.Phase.Upload)
                    val edge by animateColorAsState(if (active) color else Ink.line, MaterialTheme.motionScheme.fastEffectsSpec(), label = "card")
                    Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).border(if (active) 2.dp else 1.dp, edge, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 12.dp).semantics(mergeDescendants = true) {}) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(icon, null, Modifier.size(16.dp), tint = Ink.dim); Text(t, style = Type.caption.copy(fontSize = 12.sp), color = Ink.dim) }
                        // A new value rises into place (spatial spring), the old one fades (effects spring).
                        AnimatedContent(value.first, transitionSpec = {
                            (slideInVertically(motion.defaultSpatialSpec()) { it / 2 } + fadeIn(motion.defaultEffectsSpec())) togetherWith fadeOut(motion.fastEffectsSpec())
                        }, label = "value") { text ->
                            Text(text ?: "—", style = Type.code.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium), color = if (text != null) Ink.text else Ink.dim, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                        }
                        Text(value.second, style = Type.caption.copy(fontSize = 12.sp), color = Ink.dim)
                    }
                }
            }
        }
        item(key = "stage") {
          // GO stays while a server is found (it morphs to a circle, as the power button does when connecting); the dial
          // takes over while measuring, swapping like a container change: it scales up from 92% and fades in.
          val measuring = { r: SpeedTest.Run? -> r?.phase == SpeedTest.Phase.Download || r?.phase == SpeedTest.Phase.Upload }
          // Keyed on the stage, but each side keeps its own run: the leaving dial isn't redrawn with the next state's data.
          AnimatedContent(run, contentKey = measuring, transitionSpec = {
              (scaleIn(motion.defaultSpatialSpec(), initialScale = 0.92f) + fadeIn(motion.defaultEffectsSpec())) togetherWith fadeOut(motion.fastEffectsSpec())
          }, label = "stage") { r ->
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (measuring(r) && r != null) {
                    // Readings arrive four times a second; a critically damped spring glides between them, and the
                    // number counts with the needle.
                    val speed = animateFloatAsState((r.now / 1e6).toFloat(), spring(dampingRatio = 1f, stiffness = 40f), label = "speed")
                    val progress = animateFloatAsState((r.elapsedMs / (SpeedTest.MAX_SECONDS * 1000f)).coerceIn(0f, 1f), tween(300, easing = LinearEasing), label = "progress")
                    val upload = r.phase == SpeedTest.Phase.Upload
                    Box(contentAlignment = Alignment.BottomCenter) {
                        Dial({ speed.value }, color, "${mbps(r.now)} megabits per second, ${if (upload) "upload" else "download"}")
                        Column(Modifier.padding(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Reading(speed)
                            Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (upload) Icons.up else Icons.down, null, Modifier.size(16.dp), tint = color); Text(" Mbps · ${if (upload) "upload" else "download"}", style = Type.caption.copy(fontSize = 12.sp), color = Ink.dim) }
                        }
                    }
                    LinearWavyProgressIndicator(progress = { progress.value }, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp).fillMaxWidth(), color = color, trackColor = Ink.surfaceTop)
                    Row(Modifier.padding(horizontal = 24.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (r.phase == SpeedTest.Phase.Upload) "2/2 · Upload" else "1/2 · Download",
                            style = Type.caption.copy(fontSize = 12.sp), color = Ink.dim, modifier = Modifier.weight(1f),
                        )
                        Secondary("Cancel", { SpeedTest.cancel() }, color = Ink.text)
                    }
                    TestServer(r.server, ex, server)
                } else {
                    r?.error?.let { Text(it, style = Type.sub, color = Ink.bad, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
                    r?.missing?.let { country ->
                        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("M-Lab has no test server in $country yet, so this exit can't be tested in its own country.", style = Type.sub, color = Ink.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            LinkButton(Icons.speed, "Test with the nearest server") { exit?.let { SpeedTest.start(it, anywhere = true) } }
                        }
                    }
                    val done = r?.phase == SpeedTest.Phase.Done
                    val preparing = r?.phase == SpeedTest.Phase.Preparing
                    var asking by rememberSaveable { mutableStateOf(false) }
                    val begin = { exit?.let { SpeedTest.start(it) }; Unit }
                    // Testing another exit node moves the apps on your Tailscale route to it meanwhile: ask first.
                    var confirmNode by rememberSaveable { mutableStateOf(false) }
                    val start = { if (node && (setup.tsExit != null || UnbraidVpn.running?.tsExit != null)) confirmNode = true else begin() }
                    if (asking) MlabConsent({ asking = false }) { asking = false; Unbraid.store.mlabConsent = true; start() }
                    if (confirmNode) Prompt("Test through ${ex?.name}?", "Test", { confirmNode = false; begin() }, { confirmNode = false }) {
                        Text("For about 20 seconds, apps on your Tailscale route leave through ${ex?.name} instead of their usual exit node. Test only through a node you trust with their traffic.", style = Type.sub)
                    }
                    Go(if (done) 150 else 200, color, if (done) "AGAIN" else "GO", "Start speed test through ${ex?.name}", preparing = preparing, bloom = done) {
                        if (!preparing) { if (Unbraid.store.mlabConsent) start() else asking = true }
                    }
                    if (preparing && r != null) {
                        Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (node && r.server == null) "Switching Tailscale" else if (r.server != null) "Measuring ping" else "Finding a test server", style = Type.caption.copy(fontSize = 12.sp), color = Ink.dim, modifier = Modifier.weight(1f))
                            Secondary("Cancel", { SpeedTest.cancel() }, color = Ink.text)
                        }
                        TestServer(r.server, ex, server)
                        return@Column
                    }
                    if (done) TestServer(r.server, ex, server)
                    Text(
                        when {
                            done -> if (setup.prefs.stats) "Test complete · saved with your statistics" else "Test complete · not saved: statistics are off"
                            node -> "Tailscale switches to ${ex?.name} for the test, then back." + (if (setup.tsExit != null) " Apps on your Tailscale route use it meanwhile." else "") +
                                " Tested with M-Lab, about 20 seconds of real data."
                            else -> "Tested with M-Lab on a server in the exit's country, or a nearby one: ten seconds each way, real data."
                        },
                        style = Type.caption.copy(fontSize = 12.sp), color = Ink.dim, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    LinkButton(Icons.history, "History") { go("history") }
                }
            }
          }
        }
    }
}

/** Where the test runs, as Ookla shows it: the provider and its server's place, and the VPN server it goes through. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TestServer(s: app.unbraid.stats.Ndt7.Server?, ex: Ex?, vpnServer: String?) =
    // "Finding one…" gives way to the server it found with the effects crossfade.
    androidx.compose.animation.Crossfade(s, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "server") { found -> TestServerCard(found, ex, vpnServer) }

@Composable
private fun TestServerCard(s: app.unbraid.stats.Ndt7.Server?, ex: Ex?, vpnServer: String?) = Row(
    Modifier.padding(horizontal = Gutter, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Ink.surface).padding(horizontal = 16.dp, vertical = 12.dp)
        .semantics(mergeDescendants = true) {},
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    Icon(Icons.speed, null, Modifier.size(24.dp), tint = Ink.dim)
    Column(Modifier.weight(1f)) {
        Text("TEST SERVER · M-LAB", style = Type.overline, color = Ink.dim)
        Text(s?.let { listOf(it.city, java.util.Locale("", it.country).displayCountry).filter(String::isNotEmpty).joinToString(", ") } ?: "Finding one in ${ex?.name ?: "the exit's country"}…",
            style = Type.body, color = Ink.text)
        Text(listOfNotNull(s?.machine?.substringBefore('.'), listOfNotNull(ex?.via?.takeIf(String::isNotEmpty), vpnServer).joinToString(" ").takeIf(String::isNotEmpty)?.let { "via $it" }).joinToString(" · "),
            style = Type.caption.copy(fontSize = 12.sp), color = Ink.dim)
    }
}

/** M-Lab's terms, agreed once before the first test (measurementlab.net/aup: informed consent, published results). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MlabConsent(dismiss: () -> Unit, accept: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    ModalBottomSheet(dismiss, containerColor = Ink.surface, contentColor = Ink.text) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Speed tests use M-Lab", style = Type.title.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium))
            listOf(
                Icons.speed to "Measurement Lab (M-Lab) is an open platform run by researchers. A test asks for one of its servers in the exit's country; for your own servers and Tailscale nodes M-Lab picks a nearby one. Ten seconds each way.",
                Icons.history to "M-Lab publishes every test's results openly and keeps them, with the public IP address of the exit you test: a VPN server's, or your home's for a home or Tailscale exit.",
                Icons.block to "Also sent: the time, the app's name and version, and the exit's country when it's known. Nothing about your phone or accounts. Up to $SPEED_TESTS_PER_DAY tests a day.",
            ).forEach { (i, t) -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { Icon(i, null, Modifier.size(24.dp), tint = Ink.dim); Text(t, style = Type.sub, color = Ink.text) } }
            LinkButton(Icons.external, "M-Lab privacy policy") { context.openUrl("https://www.measurementlab.net/privacy/") }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).border(1.dp, Ink.line, RoundedCornerShape(26.dp)).clickable(role = Role.Button, onClick = dismiss), Alignment.Center) { Text("Not now", style = Type.body) }
                Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(Ink.accent).clickable(role = Role.Button, onClick = accept), Alignment.Center) { Text("Agree and test", style = Type.body, color = Ink.onAccent) }
            }
        }
    }
}

private const val SPEED_TESTS_PER_DAY = SpeedTest.PER_DAY

/** The live number in the dial's opening; only this recomposes as the reading animates. */
@Composable
private fun Reading(speed: State<Float>) {
    val m = speed.value
    Text(if (m < 100) String.format(Locale.US, "%.1f", m) else m.toInt().toString(), style = Type.code.copy(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium), color = Ink.text)
}

/** The GO button: an M3E cookie in the exit's colour. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Go(size: Int, color: Color, text: String, label: String, preparing: Boolean = false, bloom: Boolean = false, onClick: () -> Unit) {
    // The power button's motion, same specs: press 0.96 (fastSpatial), cookie ↔ circle while working (defaultSpatial)
    // with a loader, and one bloom in the exit's colour when a test succeeds (slowEffects, 1 → 1.22, fading out).
    val motion = MaterialTheme.motionScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, motion.fastSpatialSpec(), label = "press")
    val round by animateFloatAsState(if (preparing) 1f else 0f, motion.defaultSpatialSpec(), label = "morph")
    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Circle) }
    val side by animateDpAsState(size.dp, motion.defaultSpatialSpec(), label = "go") // GO → AGAIN springs smaller
    val ring = remember { Animatable(1f) }
    LaunchedEffect(bloom) { if (bloom) { ring.snapTo(0f); ring.animateTo(1f, motion.slowEffectsSpec()) } else ring.snapTo(1f) }
    Box(Modifier.padding(top = 16.dp).size(side), contentAlignment = Alignment.Center) {
        if (ring.value < 1f) Box(Modifier.fillMaxSize().graphicsLayer { val b = ring.value; scaleX = 1f + 0.22f * b; scaleY = scaleX; alpha = 0.7f * (1f - b) }.background(color, CircleShape))
        Box(
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }.clip(MorphShape(morph, round)).background(color)
                .clickable(source, ripple(), enabled = !preparing, onClickLabel = label, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            if (preparing) LoadingIndicator(Modifier.size(side * 0.4f), color = Ink.ground)
            else Text(text, style = Type.headline.copy(fontSize = (size * if (text.length > 2) 0.2f else 0.3f).sp, lineHeight = (size * 0.3f).sp), color = Ink.ground)
        }
    }
}

@Composable
private fun History(v: View, go: (String?) -> Unit) {
    val tests by produceState(emptyList<Stats.Test>()) { value = withContext(Dispatchers.IO) { Stats.read { Stats.tests() }.orEmpty() } }
    val f = DateTimeFormatter.ofPattern("d MMM · HH:mm", locale())
    Section("history", "${tests.size} tests", "History", back = { go("speed") }) {
        val best = tests.maxByOrNull { it.down }
        item(key = "hero") {
            if (best == null) Empty("No speed tests yet.")
            else Hero("Best download", mbps(best.down), "Mbps", "${v.exits[best.exit]?.name ?: best.exit} · ${java.time.Instant.ofEpochMilli(best.at).atZone(java.time.ZoneId.systemDefault()).format(f)} · download")
        }
        if (tests.isNotEmpty()) item(key = "label") {
            // Column heads over the three numbers, aligned with the rows' first line.
            Row(Modifier.padding(start = 24.dp, end = Gutter + 16.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("ALL TESTS", style = Type.overline, color = Ink.dim, modifier = Modifier.weight(1f))
                HistoryCols({ Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.down, null, Modifier.size(14.dp), tint = Ink.dim); Text("Mbps", style = Type.small, color = Ink.dim) } },
                    { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.up, null, Modifier.size(14.dp), tint = Ink.dim); Text("Mbps", style = Type.small, color = Ink.dim) } },
                    { Text("ping", style = Type.small, color = Ink.dim) })
            }
        }
        items(tests.size) { i ->
            val t = tests[i]
            val ex = v.exits[t.exit]
            // Two lines: when and the numbers, then the whole VPN server name across the row, never cut short.
            Column(
                Modifier.padding(horizontal = Gutter, vertical = 1.dp).fillMaxWidth().clip(groupShape(i, tests.size)).background(Ink.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(ex)
                    Text(java.time.Instant.ofEpochMilli(t.at).atZone(java.time.ZoneId.systemDefault()).format(f), style = Type.body, color = Ink.text, modifier = Modifier.weight(1f), maxLines = 1)
                    HistoryCols({ Text(mbps(t.down), style = Type.code.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium), color = Ink.text) },
                        { Text(mbps(t.up), style = Type.code.copy(fontSize = 14.sp), color = Ink.text) },
                        { Text(if (t.idle > 0) "${t.idle}" else "—", style = Type.code.copy(fontSize = 14.sp), color = Ink.dim) })
                }
                Text(listOf(t.via, t.server.ifEmpty { ex?.name ?: t.exit }).filter(String::isNotEmpty).joinToString(" · "), style = Type.caption.copy(fontSize = 13.sp, lineHeight = 18.sp), color = Ink.dim)
            }
        }
    }
}

/** History's three number columns, right-aligned so heads and values line up. */
@Composable
private fun HistoryCols(down: @Composable () -> Unit, up: @Composable () -> Unit, ms: @Composable () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    listOf(56.dp to down, 56.dp to up, 44.dp to ms).forEach { (w, c) -> Box(Modifier.width(w), Alignment.CenterEnd) { c() } }
}

// ---- off, paused, consent --------------------------------------------------------------------------------------

@Composable
private fun StatsOff(hasData: Boolean, turnOn: () -> Unit, view: () -> Unit) = Screen(
    overline = if (hasData) "Recording off" else "Not set up", title = "Stats", sub = "",
) {
    item {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(Ink.surfaceTop), Alignment.Center) { Icon(Icons.stats, null, Modifier.size(32.dp), tint = Ink.text) }
            Text(if (hasData) "Stats are paused" else "See where your traffic goes", style = Type.title.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium), color = Ink.text,
                modifier = tourAnchor(TourStop.StatsIntro))
            Text(
                if (hasData) "Nothing new is recorded. Statistics kept so far are still on this phone; delete them in Settings."
                else "Data per app and per exit, time connected and speeds. Statistics aren't uploaded, and no browsing destinations are recorded.",
                style = Type.body.copy(fontWeight = FontWeight.Normal, lineHeight = 24.sp), color = Ink.dim,
            )
            listOf(Icons.phone to "Kept only on this phone", Icons.block to "No browsing destinations", Icons.deleteForever to "Delete it any time in Settings").forEach { (i, t) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(i, null, Modifier.size(20.dp), tint = Ink.dim); Text(t, style = Type.sub, color = Ink.text) }
            }
            Row(
                Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Ink.accent).clickable(role = Role.Button, onClick = turnOn).padding(start = 18.dp, end = 24.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { Icon(Icons.stats, null, Modifier.size(20.dp), tint = Ink.onAccent); Text(if (hasData) "Turn back on" else "Turn on stats", style = Type.body, color = Ink.onAccent) }
            if (hasData) LinkButton(Icons.history, "View saved statistics", view)
        }
    }
}

/** What turning stats on means, before anything is recorded. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsConsent(dismiss: () -> Unit, accept: () -> Unit) = ModalBottomSheet(dismiss, containerColor = Ink.surface, contentColor = Ink.text) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Keep statistics?", style = Type.title.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium))
        listOf(
            Icons.phone to "Statistics stay on this phone. They are never uploaded.",
            Icons.clock to "Kept as hourly sums for a week and daily sums for about a year, with your last 100 speed tests.",
            Icons.block to "Only apps, exits, bytes, times, speeds and speed-test exit IPs. No browsing destinations, ever.",
            Icons.deleteForever to "Turn off or delete everything in Settings at any time.",
        ).forEach { (i, t) -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { Icon(i, null, Modifier.size(24.dp), tint = Ink.dim); Text(t, style = Type.sub, color = Ink.text) } }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).border(1.dp, Ink.line, RoundedCornerShape(26.dp)).clickable(role = Role.Button, onClick = dismiss), Alignment.Center) { Text("Not now", style = Type.body) }
            Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(Ink.accent).clickable(role = Role.Button, onClick = accept), Alignment.Center) { Text("Turn on", style = Type.body, color = Ink.onAccent) }
        }
    }
}
