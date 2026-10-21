package app.unbraid.ui

import app.unbraid.APP

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.core.content.edit
import kotlin.math.roundToInt

/** First-run state the UI owns: terms, the VPN disclosure, the tour and the leak-proof setup. */
object Onboarding {
    /** Raise when the terms change in a way people must accept again. */
    private const val TERMS = 1
    private lateinit var prefs: SharedPreferences
    var termsOk by mutableStateOf(false); private set
    var vpnOk by mutableStateOf(false); private set
    var tourPending by mutableStateOf(false); private set
    /**
     * Leak-proof setup: 0 not offered yet, 1 snoozed (the Routes reminder comes back at [leakUntil]), 3 never remind.
     * Being leak-proof is read live from Android, never stored, so switching blocking off later resumes the schedule.
     */
    var leak by mutableIntStateOf(0); private set
    var leakUntil by mutableLongStateOf(0L); private set
    private var snoozes = 0
    /** Android refused the VPN (another app may hold Always-on); cleared once a connection starts. */
    var consentFailed by mutableStateOf(false)

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("unbraid_ui", Context.MODE_PRIVATE)
        termsOk = prefs.getInt("terms", 0) >= TERMS
        // Until 2.4 one dialog covered both the VPN and the app list: accepting it covered the VPN too. Settled once,
        // before the apps-only Agree can write the old key.
        if (!prefs.contains("vpn_disclosure")) prefs.edit { putBoolean("vpn_disclosure", prefs.getBoolean("disclosure_accepted", false)) }
        vpnOk = prefs.getBoolean("vpn_disclosure", false)
        tourPending = prefs.getBoolean("tour_pending", false)
        leak = prefs.getInt("leak", 0)
        leakUntil = prefs.getLong("leak_until", 0L)
        snoozes = prefs.getInt("leak_snoozes", 0)
    }

    /** [fresh]: a new install, which then gets the tour once it is set up. */
    fun acceptTerms(fresh: Boolean) { termsOk = true; tourPending = fresh; prefs.edit { putInt("terms", TERMS); putBoolean("tour_pending", fresh) } }
    fun acceptVpn() { vpnOk = true; prefs.edit { putBoolean("vpn_disclosure", true) } }
    fun tourSeen() { tourPending = false; prefs.edit { putBoolean("tour_pending", false) } }
    fun leak(v: Int) { if (leak != v) { leak = v; prefs.edit { putInt("leak", v) } } }

    /** Not now: back in 3 days, then a week, then every two weeks for good. */
    fun snoozeLeak() {
        leakUntil = System.currentTimeMillis() + longArrayOf(3, 7, 14)[minOf(snoozes, 2)] * 86_400_000L
        snoozes++; leak = 1
        prefs.edit { putInt("leak", 1); putLong("leak_until", leakUntil); putInt("leak_snoozes", snoozes) }
    }

    val leakDue get() = leak == 1 && System.currentTimeMillis() >= leakUntil
}

/** The tour's stops: each names its tab and the concept its anchor marks, never a position on screen. */
enum class TourStop(val tab: Tab, val title: String, val body: String) {
    Power(Tab.Routes, "Connect, pause, stop", "Tap to connect. Tap again to pause or disconnect. Green is protected, orange paused, red off."),
    DefaultRoute(Tab.Routes, "The default exit", "Every app goes here unless you give it an exception. Tap it to change the location."),
    AddRoute(Tab.Routes, "Add an exit", "Add route opens the picker: your provider's countries, or your own servers and Tailscale."),
    AppsList(Tab.Apps, "Choose per app", "Tap any app to pick its exit; Direct skips the VPN. Moved apps show on Routes as exceptions."),
    StatsIntro(Tab.Stats, "Your numbers, if you want them", "Data per app and exit, flows and speed tests. Off until you turn it on. Statistics stay on this phone; M-Lab publishes speed test results."),
    Connections(Tab.Settings, "Add connections", "Sign in to more providers, or import WireGuard, OpenVPN and Shadowsocks servers."),
    LocalHome(Tab.Settings, "Home and local", "Printers and casting stay reachable. Reach home through Tailscale or your router's WireGuard."),
    About(Tab.Settings, "You're set", "The Quick Settings tile and the widget work without opening $APP. Replay this tour here, in About & support."),
}

private val anchors = mutableStateMapOf<TourStop, Rect>()

/** Marks the real element a stop points at. Move or restyle it and the spotlight follows; remove it and the stop shows centred. */
@SuppressLint("ModifierFactoryExtensionFunction", "ComposableModifierFactory")
@Composable
fun tourAnchor(stop: TourStop): Modifier {
    DisposableEffect(stop) { onDispose { anchors.remove(stop) } }
    return Modifier.onGloballyPositioned { c -> c.boundsInRoot().let { if (anchors[stop] != it) anchors[stop] = it } }
}

/** One stop: the app dimmed around its anchor, a margin note beside it. Look only. */
@Composable
fun TourOverlay(step: Int, appsShown: Boolean, back: () -> Unit, next: () -> Unit, close: () -> Unit) {
    val stop = TourStop.entries[step]
    val last = step == TourStop.entries.lastIndex
    val target = anchors[stop]
    val motion = MaterialTheme.motionScheme
    val d = LocalDensity.current
    fun Float.px() = this * d.density
    val hole by animateRectAsState(target?.inflate(6f.px()) ?: Rect.Zero, motion.defaultSpatialSpec(), label = "spot")
    var noteH by remember { mutableIntStateOf(0) }
    BackHandler { if (step > 0) back() else close() }
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }.semantics { paneTitle = "Tour" }) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val noteW = minOf(w - 32f.px(), 300f.px())
        val wide = target != null && target.width > w * 0.7f
        val left = when {
            target == null -> (w - noteW) / 2
            wide -> 24f.px()
            else -> (target.center.x - noteW / 2).coerceIn(16f.px(), w - 16f.px() - noteW)
        }
        val gap = 22f.px()
        val below = target != null && target.bottom + gap + noteH < h - 96f.px()
        val top = when {
            target == null -> (h - noteH) / 2
            below -> target.bottom + 6f.px() + gap
            else -> (target.top - 6f.px() - gap - noteH).coerceAtLeast(16f.px())
        }
        val x by animateFloatAsState(left, motion.defaultSpatialSpec(), label = "noteX")
        val y by animateFloatAsState(top, motion.defaultSpatialSpec(), label = "noteY")
        val leaderX = if (wide) x + 44f.px() else hole.center.x.coerceIn(x + 28f.px(), x + noteW - 28f.px())
        val round = if (stop == TourStop.Power) 999f else 26f.px()
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Color(0x8C060809))
            if (target == null) return@Canvas
            val r = CornerRadius(minOf(round, hole.minDimension / 2))
            drawRoundRect(Color.Transparent, hole.topLeft, hole.size, r, blendMode = BlendMode.Clear)
            drawRoundRect(Ink.accent, hole.topLeft, hole.size, r, style = Stroke(2f.px()))
            val from = if (below) hole.bottom else hole.top
            val to = if (below) y else y + noteH
            drawLine(Ink.accent, Offset(leaderX, from), Offset(leaderX, to), 2f.px())
            drawCircle(Ink.accent, 4f.px(), Offset(leaderX, from))
        }
        Column(
            Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.width(with(d) { noteW.toDp() }).onSizeChanged { noteH = it.height }
                .clip(RoundedCornerShape(20.dp)).background(Ink.surfaceHigh).border(1.dp, Ink.surfaceTop, RoundedCornerShape(20.dp))
                .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${stop.tab.label.uppercase()} · ${step + 1} / ${TourStop.entries.size}", style = Type.overline.copy(fontSize = 11.sp), color = Ink.dim, modifier = Modifier.weight(1f))
                IconTap(Icons.close, "Close the tour", close, size = 44.dp, tint = Ink.dim)
            }
            Text(stop.title, style = Type.title, color = Ink.text)
            Text(
                if (stop == TourStop.AppsList && !appsShown) "Show my apps lists them here. Tap any app to pick its exit; Direct skips the VPN." else stop.body,
                style = Type.sub, color = Ink.dim,
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Progress(TourStop.entries.size, step, Modifier.weight(1f))
                if (step > 0) IconTap(Icons.back, "Back", back, size = 44.dp, background = Ink.surfaceTop)
                NextPill(if (last) "Done" else "Next", next)
            }
        }
    }
}

/** Progress as route stops: passed ones filled, the current one ringed. */
@Composable
fun Progress(n: Int, at: Int, modifier: Modifier = Modifier) = Row(
    modifier.semantics(mergeDescendants = true) { contentDescription = "Step ${at + 1} of $n" },
    verticalAlignment = Alignment.CenterVertically,
) {
    repeat(n) { i ->
        if (i > 0) Box(Modifier.width(if (n > 4) 4.dp else 12.dp).height(2.dp).background(if (i <= at) Ink.accent else Ink.line))
        Box(
            Modifier.size(if (i <= at) 8.dp else 6.dp).clip(CircleShape)
                .then(if (i < at) Modifier.background(Ink.accent) else if (i == at) Modifier.background(Ink.ground).border(2.dp, Ink.accent, CircleShape) else Modifier.border(2.dp, Ink.line, CircleShape)),
        )
    }
}

@Composable
fun NextPill(text: String, onClick: () -> Unit, enabled: Boolean = true) = Row(
    Modifier.height(44.dp).clip(RoundedCornerShape(16.dp)).background(if (enabled) Ink.accent else Ink.surfaceTop)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(start = 18.dp, end = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(text, style = Type.title.copy(fontSize = 15.sp), color = if (enabled) Ink.onAccent else Ink.faint)
    Icon(Icons.chevron, null, Modifier.size(22.dp), tint = if (enabled) Ink.onAccent else Ink.faint)
}

// ---------------------------------------------------------------------------------------------
// Sheets: each disclosure on its own, right before what it covers, and the leak-proof setup.
// ---------------------------------------------------------------------------------------------

/** A sheet in the app's style: icon, overline and title, then [content], then Not now / the action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(
    icon: ImageVector, over: String, title: String, action: String, onAction: () -> Unit, onDismiss: () -> Unit,
    good: Boolean = false, later: String? = "Not now", foot: String? = null, actionIcon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) = ModalBottomSheet(onDismiss, containerColor = Ink.surface, contentColor = Ink.text) {
    // The text scrolls on a short window or with large type; the buttons stay in reach below it.
    Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
      Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(if (good) Color(0xFF173024) else Ink.accentContainer), Alignment.Center) {
                Icon(icon, null, Modifier.size(24.dp), tint = if (good) Ink.good else Ink.accent)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(over.uppercase(), style = Type.overline, color = Ink.dim)
                Text(title, style = Type.title.copy(fontSize = 22.sp, lineHeight = 28.sp), color = Ink.text)
            }
        }
        content()
        foot?.let { Text(it, style = Type.caption, color = Ink.dim) }
      }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            later?.let { Secondary(it, onDismiss, color = Ink.text) }
            Row(
                Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Ink.accent).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(action, style = Type.title, color = Ink.onAccent)
                actionIcon?.let { Icon(it, null, Modifier.size(18.dp), tint = Ink.onAccent) }
            }
        }
    }
}

@Composable
fun SheetLine(icon: ImageVector, text: String) = Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    Icon(icon, null, Modifier.padding(top = 1.dp).size(20.dp), tint = Ink.dim)
    Text(text, style = Type.sub.copy(fontSize = 15.sp, lineHeight = 22.sp), color = Ink.text)
}

/** The VPN half of the old disclosure, word for word, before the first connect. */
@Composable
fun VpnDisclosure(agree: () -> Unit, dismiss: () -> Unit) = InfoSheet(
    Icons.tunnel, "Before you connect", "How the VPN works", "Agree and continue", agree, dismiss, foot = "Android asks for its own VPN permission next.",
) {
    SheetLine(Icons.routes, "$APP creates a VPN on this phone to route each app through the exit you chose.")
    SheetLine(Icons.dns, "Traffic goes to the exits you chose: VPN providers, your own servers or Tailscale.")
    SheetLine(Icons.direct, "Apps set to Direct and your local network use your normal connection.")
    SheetLine(Icons.block, "$APP has no servers and collects nothing.")
    SheetLine(Icons.shield, "Until blocking is on in Android, apps use your normal connection whenever $APP is off.")
}

/** The installed-apps half, when the list is first asked for. */
@Composable
fun AppsDisclosure(agree: () -> Unit, dismiss: () -> Unit) = InfoSheet(
    Icons.apps, "Before your apps appear", "Your installed apps", "Agree and continue", agree, dismiss, foot = "Not now keeps the list closed; Apps asks again.",
) {
    SheetLine(Icons.apps, "$APP reads the list of installed apps, only on this phone.")
    SheetLine(Icons.routes, "It needs the list to let you give each app its own exit.")
    SheetLine(Icons.phone, "The list never leaves this phone and is not uploaded.")
}

/** Android's two switches, read live while the VPN runs; null = not running, so unknown. */
@Composable
fun FlagRow(icon: ImageVector, title: String, sub: String, on: Boolean?) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Icon(if (on == true) Icons.checkCircle else icon, null, Modifier.size(22.dp), tint = if (on == true) Ink.good else Ink.dim)
    Column(Modifier.weight(1f)) { Text(title, style = Type.sub.copy(fontWeight = FontWeight.Medium), color = Ink.text); Text(sub, style = Type.sub, color = Ink.dim) }
    Text(when (on) { null -> "—"; true -> "ON"; false -> "OFF" }, style = Type.code, color = when (on) { null -> Ink.dim; true -> Ink.good; false -> Ink.accent })
}

const val LEAK_TRADEOFF = "With blocking on, every app, Direct apps included, loses internet whenever $APP is paused or off."

/** Leak-proof setup: after the first connect, the way to Android's Always-on and Block switches; it says Leak-proof only once Android confirms both. */
@Composable
fun LeakSheet(alwaysOn: Boolean, lockdown: Boolean, open: () -> Unit, later: () -> Unit, never: () -> Unit, done: () -> Unit) {
    if (alwaysOn && lockdown) return InfoSheet(
        Icons.checkCircle, "All set", "Leak-proof", "Done", done, done, good = true, later = null,
    ) {
        Text("Nothing goes online without $APP now. To use the internet without it, turn blocking off in Android.", style = Type.sub.copy(fontSize = 15.sp, lineHeight = 22.sp), color = Ink.dim)
        FlagRow(Icons.power, "Always-on VPN", "Starts $APP at boot and brings it back", true)
        FlagRow(Icons.block, "Block connections without VPN", "No internet without $APP", true)
    }
    InfoSheet(Icons.shield, "One more step", "Stay protected when $APP is off", "Open Android settings", open, later, actionIcon = Icons.external) {
        Text("Right now apps use your normal connection before $APP connects, while it's paused and if it stops. Android can block that.", style = Type.sub.copy(fontSize = 15.sp, lineHeight = 22.sp), color = Ink.dim)
        listOf("In Android's VPN settings, tap the gear next to $APP.", "Turn on Always-on VPN, then Block connections without VPN.").forEachIndexed { i, t ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(24.dp).border(1.5.dp, Ink.dim, CircleShape), Alignment.Center) { Text("${i + 1}", style = Type.code, color = Ink.text) }
                Text(t, style = Type.sub.copy(fontSize = 15.sp, lineHeight = 22.sp), color = Ink.text)
            }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.surfaceTop).padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.block, null, Modifier.size(20.dp), tint = Ink.accent)
            Text("The catch: ${LEAK_TRADEOFF.replaceFirstChar { it.lowercase() }}", style = Type.sub, color = Ink.text)
        }
        var asking by remember { mutableStateOf(false) }
        Secondary("Don't remind me again", { asking = true }, Modifier.padding(start = 0.dp), color = Ink.dim)
        if (asking) Prompt("Stop the reminders?", "Stop reminders", { asking = false; never() }, { asking = false }) {
            Text("Apps will keep using your normal connection whenever $APP is off. Settings, VPN behaviour keeps a warning and the way to turn blocking on.", style = Type.sub, color = Ink.dim)
        }
    }
}

