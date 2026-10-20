package app.unbraid.vpn

import app.unbraid.APP
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.VpnService
import android.widget.RemoteViews
import app.unbraid.MainActivity
import app.unbraid.R
import app.unbraid.vpn.VpnState.Phase

/** 1x1 status card: a badge that shows on, paused or off at a glance, and one action. The card itself opens Unbraid. */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) = m.updateAppWidget(ids, views(ctx))

    /** Not exported: only the widget's own PendingIntents reach it, so no other app can switch the VPN off. */
    class Action : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getStringExtra("do")) {
                "pause" -> UnbraidVpn.pause(ctx, 15)
                "connect" -> UnbraidVpn.start(ctx)
            }
        }
    }

    private class Look(val badge: Int, val icon: Int, val action: String, val pill: Int, val text: Int, val does: String?, val status: String)

    companion object {
        // Ink tokens.
        private const val ACCENT = 0xFFFFA66B.toInt()
        private const val ON_ACCENT = 0xFF2A1A0F.toInt()
        private const val GOOD = 0xFF8EE0A8.toInt()
        private const val BAD = 0xFFFF8A80.toInt()
        private const val TOP = 0xFF2C3439.toInt()
        private const val TEXT = 0xFFECEFF1.toInt()
        private const val GROUND = 0xFF0F1214.toInt()

        fun update(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            val ids = m.getAppWidgetIds(ComponentName(ctx, StatusWidget::class.java))
            if (ids.isNotEmpty()) m.updateAppWidget(ids, views(ctx))
        }

        private fun views(ctx: Context): RemoteViews {
            val l = when (UnbraidVpn.state.value.phase) {
                Phase.On -> Look(GOOD, R.drawable.ic_tile, "Pause", TOP, TEXT, "pause", "protected")
                Phase.Connecting -> Look(TOP, R.drawable.ic_tile, "Connecting", TOP, TEXT, null, "connecting")
                Phase.Paused -> Look(ACCENT, R.drawable.ic_w_pause, "Resume", ACCENT, ON_ACCENT, "connect", "paused")
                Phase.Off -> Look(BAD, R.drawable.ic_w_off, "Connect", ACCENT, ON_ACCENT, "connect", "not protected")
            }
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            // Without VPN consent only the app can ask for it, so the button opens it too.
            val act = l.does?.takeIf { VpnService.prepare(ctx) == null }?.let { d ->
                PendingIntent.getBroadcast(ctx, d.hashCode(), Intent(ctx, Action::class.java).putExtra("do", d), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            } ?: open
            return RemoteViews(ctx.packageName, R.layout.widget_status).apply {
                setColorStateList(R.id.badge, "setBackgroundTintList", ColorStateList.valueOf(l.badge))
                setImageViewResource(R.id.badgeIcon, l.icon)
                setColorStateList(R.id.badgeIcon, "setImageTintList", ColorStateList.valueOf(if (l.badge == TOP) TEXT else GROUND))
                setTextViewText(R.id.action, l.action)
                setTextColor(R.id.action, l.text)
                setColorStateList(R.id.action, "setBackgroundTintList", ColorStateList.valueOf(l.pill))
                setContentDescription(android.R.id.background, "$APP: ${l.status}. Open $APP")
                setOnClickPendingIntent(android.R.id.background, open)
                setOnClickPendingIntent(R.id.actionArea, act)
            }
        }
    }
}
