package app.unbraid.vpn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import app.unbraid.APP
import app.unbraid.MainActivity
import app.unbraid.R
import app.unbraid.Unbraid
import app.unbraid.data.DIRECT
import app.unbraid.data.Setup
import app.unbraid.vpn.VpnState.Phase

/** One route as a widget shows it. [live] false = saved but carrying nothing now (off, paused, connecting). */
data class WidgetRoute(val code: String, val name: String, val hue: Int, val apps: Int, val direct: Boolean, val live: Boolean, val failing: Boolean,
    /** Websites with this route as their own: a count only, never an address. */
    val sites: Int = 0)

/**
 * What the home-screen widgets show, worked out from the connection and the setup alone: nothing is measured for them.
 * Only what a bystander may see: no addresses, server names or app names.
 */
data class Glance(
    val phase: Phase = Phase.Off,
    /** False when every app goes Direct: running then connects, it doesn't protect. */
    val protects: Boolean = true,
    /** VPN routes in use that work, of [total]; Direct counts in neither. */
    val ok: Int = 0,
    val total: Int = 0,
    val profile: String? = null,
    val ts: Ts = Ts.None,
    /** While paused: when it resumes (epoch ms), 0 = by hand. */
    val until: Long = 0,
    val default: WidgetRoute? = null,
    /** The other routes in use: failing ones first (never hidden below the fold), then exits A to Z, Direct last. */
    val others: List<WidgetRoute> = emptyList(),
) {
    /** Tailscale as the tag shows it; [None] = not set up, no tag. */
    enum class Ts { None, Up, Down, Idle }
    val down get() = total - ok
}

/** What a route carries, in a widget's words: its apps, its websites, or both. */
fun usesLabel(apps: Int, sites: Int) = listOfNotNull(
    (if (apps == 1) "1 app" else "$apps apps").takeIf { apps > 0 || sites == 0 },
    (if (sites == 1) "1 website" else "$sites websites").takeIf { sites > 0 },
).joinToString(" · ")

/** [vpn] and [setup] as the widgets show them. */
fun glance(vpn: VpnState, setup: Setup): Glance {
    val on = vpn.phase == Phase.On
    val tsDown = vpn.tsState in setOf("NeedsLogin", "NeedsMachineAuth", "InUseOtherUser", "Stopped")
    // No tunnel (its provider gave no server, or its own server has no keys), a failed check, or a Tailscale exit without Tailscale.
    fun failing(id: String) = on && (id !in vpn.servers || id in vpn.stalled || id in vpn.notAnswering || id in vpn.dropping ||
        tsDown && setup.route(id)?.tsExit != null)
    fun row(id: String): WidgetRoute? {
        val apps = setup.apps.count { it.value == id }
        val sites = setup.sites.count { it.value == id }
        val r = setup.route(id) ?: return if (id == DIRECT) WidgetRoute("", "Direct", 0, apps, direct = true, live = on, failing = false, sites = sites) else null
        // The name of an own server or a Tailscale device is the user's own: only its kind shows on a home screen.
        return WidgetRoute(r.code, if (r.imported) r.source else r.name, r.hue, apps, direct = false, live = on, failing = failing(id), sites = sites)
    }
    val default = row(setup.defaultRoute)
    // A route is in use through its apps or through websites alone.
    val others = (setup.apps.values.toSet() + setup.sites.values - setup.defaultRoute).mapNotNull(::row).sortedWith(compareBy({ !it.failing }, { it.direct }, { it.name.lowercase() }))
    val tunnels = (listOfNotNull(default) + others).filter { !it.direct }
    return Glance(
        vpn.phase, !setup.directOnly, tunnels.count { !it.failing }, tunnels.size, setup.profile,
        when {
            !setup.tailscale -> Glance.Ts.None
            !on -> Glance.Ts.Idle
            vpn.tsState == "Running" -> Glance.Ts.Up
            tsDown -> Glance.Ts.Down
            else -> Glance.Ts.Idle
        },
        if (vpn.phase == Phase.Paused) vpn.pausedUntil else 0, default, others,
    )
}

/** Draws every home-screen widget from one [Glance]. The cards open the app; only Connect, Resume and Pause act from the home screen. */
object Widgets {
    // Ink tokens.
    private const val ACCENT = 0xFFFFA66B.toInt()
    private const val ON_ACCENT = 0xFF2A1A0F.toInt()
    private const val TINT = 0xFF3A2A1F.toInt()
    private const val GOOD = 0xFF8EE0A8.toInt()
    private const val BAD = 0xFFFF8A80.toInt()
    private const val BAD_TINT = 0xFF3A2222.toInt()
    private const val TOP = 0xFF2C3439.toInt()
    private const val SURFACE = 0xFF171B1E.toInt()
    private const val TEXT = 0xFFECEFF1.toInt()
    private const val DIM = 0xFF9AA5AD.toInt()
    private const val GROUND = 0xFF0F1214.toInt()
    private val HUES = intArrayOf(0xFFFFA66B.toInt(), 0xFF7CC4FF.toInt(), 0xFFC9A7FF.toInt(), 0xFF8EE0A8.toInt(), 0xFFFFD36B.toInt(), 0xFFFF9EBB.toInt(), 0xFF6BE3DC.toInt())

    /** Minutes the Pause button pauses for. */
    const val PAUSE = 15

    private val all = listOf(StatusWidget::class.java to ::small, HealthWidget::class.java to ::health, RoutesWidget::class.java to ::routes, BoardWidget::class.java to ::board)

    fun now() = glance(UnbraidVpn.state.value, Unbraid.store.setup.value)

    /** Redraws every placed widget. */
    fun update(ctx: Context) {
        val m = AppWidgetManager.getInstance(ctx)
        val g = now()
        for ((cls, draw) in all) m.getAppWidgetIds(ComponentName(ctx, cls)).takeIf { it.isNotEmpty() }?.let { m.updateAppWidget(it, draw(ctx, g)) }
    }

    /** What the widget picker shows for each widget: a typical setup, not the user's (Android 15 and later; earlier ones show the plain layout). */
    fun previews(ctx: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        val m = AppWidgetManager.getInstance(ctx)
        fun r(code: String, name: String, hue: Int, apps: Int) = WidgetRoute(code, name, hue, apps, direct = false, live = true, failing = false)
        val g = Glance(Phase.On, true, 3, 3, "Home", default = r("NL", "Netherlands", 0, 0), others = listOf(r("IT", "Italy", 2, 2), r("US", "United States", 1, 3)))
        // Android limits how often previews may be set; a refusal only keeps the plain layout.
        for ((cls, draw) in all) runCatching { m.setWidgetPreview(ComponentName(ctx, cls), android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, draw(ctx, g)) }
    }

    private val GHOSTS = intArrayOf(R.drawable.widget_chip_ghost_0, R.drawable.widget_chip_ghost_1, R.drawable.widget_chip_ghost_2, R.drawable.widget_chip_ghost_3, R.drawable.widget_chip_ghost_4, R.drawable.widget_chip_ghost_5, R.drawable.widget_chip_ghost_6)

    // A null colour clears the tint: a launcher reuses the views, so one set earlier would stay.
    private fun RemoteViews.bg(id: Int, color: Int?) = setColorStateList(id, "setBackgroundTintList", color?.let(ColorStateList::valueOf))
    private fun RemoteViews.tint(id: Int, color: Int?) = setColorStateList(id, "setImageTintList", color?.let(ColorStateList::valueOf))
    /** A status dot: filled in [color], or hollow (the ring carries its own grey). */
    private fun RemoteViews.dot(id: Int, color: Int?) { setImageViewResource(id, if (color == null) R.drawable.widget_ring else R.drawable.widget_dot); tint(id, color) }
    private fun RemoteViews.show(id: Int, on: Boolean) = setViewVisibility(id, if (on) View.VISIBLE else View.GONE)

    private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    /** The button: what it says ([short] on the 1x1), its icon and colours, and what it sends ([does] null = it opens the app). */
    private class Act(val label: String, val short: String, val icon: Int, val fill: Int, val ink: Int, val does: String?)

    private fun act(g: Glance) = when {
        g.phase == Phase.On && g.down > 0 -> Act("View route", "${g.down} down", R.drawable.ic_w_error, BAD_TINT, BAD, null)
        g.phase == Phase.On -> Act("Pause $PAUSE min", "Pause", R.drawable.ic_w_pause, TOP, TEXT, "pause")
        g.phase == Phase.Connecting -> Act("Connecting", "Connecting", R.drawable.ic_w_power, TOP, DIM, null)
        g.phase == Phase.Paused -> Act("Resume", "Resume", R.drawable.ic_w_power, ACCENT, ON_ACCENT, "connect")
        else -> Act("Connect", "Connect", R.drawable.ic_w_power, ACCENT, ON_ACCENT, "connect")
    }

    private fun headline(g: Glance) = when (g.phase) {
        Phase.On -> if (g.protects) "Protected" else "Connected"
        Phase.Connecting -> "Connecting"
        Phase.Paused -> "Paused"
        Phase.Off -> if (g.protects) "Not protected" else "Off"
    }

    /** The line above the headline and its colour; [short] where the profile and Tailscale share the line. */
    private fun status(ctx: Context, g: Glance, short: Boolean): Pair<String, Int> = when (g.phase) {
        Phase.On -> when {
            g.total == 0 -> "No VPN routes" to DIM
            short -> "${g.ok}/${g.total} OK" to if (g.down == 0) GOOD else BAD
            else -> "${g.ok}/${g.total} routes OK" to if (g.down == 0) GOOD else BAD
        }
        Phase.Connecting -> (if (short) "Starting" else "Starting routes") to DIM
        Phase.Paused -> (if (g.until == 0L) "Until resumed" else (if (short) "To " else "Back on at ") + android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(g.until))) to ACCENT
        Phase.Off -> (if (short) "Off" else "VPN is off") to BAD
    }

    /** What every widget shares: the card's tap, the cookie, Tailscale and the button (its text is each layout's own). */
    private fun RemoteViews.common(ctx: Context, g: Glance, a: Act) {
        val (color, icon) = when (g.phase) {
            Phase.On -> GOOD to R.drawable.ic_tile
            Phase.Connecting -> TOP to R.drawable.ic_tile
            Phase.Paused -> ACCENT to R.drawable.ic_w_pause
            Phase.Off -> BAD to R.drawable.ic_w_off
        }
        tint(R.id.cookie, color)
        setImageViewResource(R.id.cookieIcon, icon)
        tint(R.id.cookieIcon, if (color == TOP) TEXT else GROUND)
        show(R.id.ts, g.ts != Glance.Ts.None)
        dot(R.id.tsDot, when (g.ts) { Glance.Ts.Up -> GOOD; Glance.Ts.Down -> BAD; else -> null })
        setTextColor(R.id.tsText, when (g.ts) { Glance.Ts.Up -> TEXT; Glance.Ts.Down -> BAD; else -> DIM })
        val ts = when (g.ts) { Glance.Ts.Up -> ", Tailscale connected"; Glance.Ts.Down -> ", Tailscale not connected"; else -> "" }
        val routes = if (g.phase == Phase.On && g.total > 0) ", ${g.ok} of ${g.total} routes working" else ""
        setContentDescription(android.R.id.background, "$APP: ${headline(g).lowercase()}$routes${g.profile?.let { ", profile $it" }.orEmpty()}$ts. Open $APP")
        val open = open(ctx)
        setOnClickPendingIntent(android.R.id.background, open)
        // Without VPN consent only the app can ask for it, so the button opens it too.
        setOnClickPendingIntent(R.id.actionArea, a.does?.takeIf { VpnService.prepare(ctx) == null }?.let { d ->
            PendingIntent.getBroadcast(ctx, d.hashCode(), Intent(ctx, StatusWidget.Action::class.java).putExtra("do", d), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } ?: open)
    }

    /** The header of the larger widgets: the state line, the headline, the profile tag and the labelled button. */
    private fun RemoteViews.head(ctx: Context, g: Glance, a: Act, short: Boolean, tag: Boolean) {
        common(ctx, g, a)
        val (line, color) = status(ctx, g, short)
        setTextViewText(R.id.over, line)
        setTextColor(R.id.over, color)
        setTextViewText(R.id.head, headline(g))
        show(R.id.profile, tag && g.profile != null)
        setTextViewText(R.id.profile, g.profile.orEmpty())
        // Off: the profile Connect will use, not one that is running.
        bg(R.id.profile, if (g.phase == Phase.Off) TOP else TINT)
        setTextColor(R.id.profile, if (g.phase == Phase.Off) DIM else ACCENT)
        bg(R.id.actionArea, a.fill)
        setImageViewResource(R.id.actionIcon, a.icon)
        tint(R.id.actionIcon, a.ink)
        setTextViewText(R.id.action, a.label)
        setTextColor(R.id.action, a.ink)
    }

    /** A route's chip: solid in its colour when live, red when failing, an outline when only saved, a dashed globe for Direct. */
    private fun RemoteViews.chip(r: WidgetRoute) {
        show(R.id.chipIcon, r.direct)
        show(R.id.chipSlash, !r.direct)
        show(R.id.chipCode, !r.direct)
        val hue = r.hue.mod(HUES.size)
        val color = if (r.failing) BAD else HUES[hue]
        setInt(R.id.chip, "setBackgroundResource", if (r.direct) R.drawable.widget_chip_direct else if (r.live) R.drawable.widget_chip else GHOSTS[hue])
        bg(R.id.chip, color.takeIf { r.live && !r.direct })
        if (r.direct) return
        setTextViewText(R.id.chipCode, r.code)
        for (id in intArrayOf(R.id.chipSlash, R.id.chipCode)) setTextColor(id, if (r.live) GROUND else color)
    }

    /** A route's name, chip and whether it works, into the views every route layout names alike. [word] spells the working state out. */
    private fun RemoteViews.route(g: Glance, r: WidgetRoute, word: Boolean) {
        chip(r)
        setTextViewText(R.id.name, r.name)
        val (text, color) = when {
            r.failing -> "Not answering" to BAD
            !r.live -> (when (g.phase) { Phase.Paused -> "Paused"; Phase.Connecting -> "Starting"; else -> "Off" }) to DIM
            r.direct -> "No VPN" to DIM
            else -> (if (word) "Working" else "") to DIM
        }
        setTextViewText(R.id.health, text)
        setTextColor(R.id.health, color)
        dot(R.id.dot, when { r.failing -> BAD; r.live && !r.direct -> GOOD; else -> null })
    }

    private fun uses(r: WidgetRoute) = usesLabel(r.apps, r.sites)

    private fun small(ctx: Context, g: Glance) = RemoteViews(ctx.packageName, R.layout.widget_status).apply {
        val a = act(g)
        common(ctx, g, a)
        show(R.id.profile, g.profile != null)
        setTextViewText(R.id.profile, g.profile.orEmpty())
        setTextColor(R.id.profile, if (g.phase == Phase.Off) DIM else ACCENT)
        bg(R.id.action, a.fill)
        setTextViewText(R.id.action, a.short)
        setTextColor(R.id.action, a.ink)
    }

    private fun health(ctx: Context, g: Glance) = RemoteViews(ctx.packageName, R.layout.widget_health).apply { head(ctx, g, act(g), short = false, tag = true) }

    private fun routes(ctx: Context, g: Glance) = RemoteViews(ctx.packageName, R.layout.widget_routes).apply {
        head(ctx, g, act(g), short = true, tag = true)
        removeAllViews(R.id.tiles)
        val shown = (listOfNotNull(g.default) + g.others).take(3)
        shown.forEachIndexed { i, r ->
            addView(R.id.tiles, RemoteViews(ctx.packageName, R.layout.widget_tile).apply {
                route(g, r, word = true)
                val default = i == 0 && g.default != null
                setTextViewText(R.id.label, if (default) "Default" else uses(r))
                setInt(R.id.tile, "setBackgroundResource", when { default -> R.drawable.widget_seg_hero; i == 0 -> R.drawable.widget_seg_l; i == shown.lastIndex -> R.drawable.widget_seg_r; else -> R.drawable.widget_seg_m })
            })
        }
    }

    private fun board(ctx: Context, g: Glance) = RemoteViews(ctx.packageName, R.layout.widget_board).apply {
        head(ctx, g, act(g), short = false, tag = false)
        route(g, g.default ?: WidgetRoute("", "Not set", 0, 0, direct = true, live = false, failing = false), word = false)
        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
        g.others.forEachIndexed { i, r ->
            items.addItem(i.toLong(), RemoteViews(ctx.packageName, R.layout.widget_row).apply {
                route(g, r, word = false)
                setTextViewText(R.id.label, uses(r))
                setOnClickFillInIntent(R.id.row, Intent())
            })
        }
        setRemoteAdapter(R.id.list, items.build())
        setPendingIntentTemplate(R.id.list, PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        setTextViewText(R.id.profileName, g.profile ?: "None")
        val off = g.phase == Phase.Off || g.profile == null
        bg(R.id.profileRow, if (off) SURFACE else TINT)
        setTextColor(R.id.profileName, if (off) TEXT else ACCENT)
    }
}

/** A home-screen widget that mirrors the connection: drawn by [draw] from the current [Glance]. */
abstract class GlanceWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) = Widgets.update(ctx)
}

/** 1x1: the status badge, the profile in use, and one action. */
class StatusWidget : GlanceWidget() {
    /** Not exported: only the widgets' own PendingIntents reach it, so no other app can switch the VPN off. */
    class Action : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getStringExtra("do")) {
                "pause" -> UnbraidVpn.pause(ctx, Widgets.PAUSE)
                "connect" -> UnbraidVpn.start(ctx)
            }
        }
    }
}

/** 2x2: how many routes work, with the profile in use and Tailscale. */
class HealthWidget : GlanceWidget()

/** 4x2: the default route and the next two, each with whether it works. */
class RoutesWidget : GlanceWidget()

/** 4x3: every route in a scrolling list, and the profile in use. */
class BoardWidget : GlanceWidget()
