package app.unbraid.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import app.unbraid.APP
import app.unbraid.MainActivity
import app.unbraid.R
import androidx.core.content.edit

/**
 * Replacing the app ends its process, and with it a running VPN; unless Android's Always-on is on, nothing brings it
 * back. So the service notes while it runs that it is on (since this boot), and clears that whenever it stops for any
 * reason it can see: the user's Disconnect or Pause, a failure, Android taking the VPN away. Only a VPN that was on when
 * the app was replaced reconnects; one the user had turned off never does, and nothing starts at boot.
 */
object AfterUpdate {
    private const val PREFS = "after_update"
    private const val ON_BOOT = "on_boot"
    private const val TOKEN = "token"
    private const val EXTRA = "after_update"
    const val OFF = "$APP is off after the update. Tap to reconnect."

    enum class Step { None, Start, Tell }

    /** What to do once the app was replaced: [onBoot] is the boot the VPN was noted on in, or null when it wasn't on. */
    fun step(onBoot: Int?, boot: Int, consent: Boolean) = when {
        onBoot == null || onBoot != boot -> Step.None
        consent -> Step.Start
        else -> Step.Tell
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun boot(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)

    /** The VPN runs. Written at once: the process can end any moment after. */
    fun on(ctx: Context) { runCatching { prefs(ctx).edit(commit = true) { putInt(ON_BOOT, boot(ctx)) } } }

    /** The VPN stopped by a way the app sees (disconnect, pause, failure, revoke, service end). */
    fun off(ctx: Context) { runCatching { if (prefs(ctx).contains(ON_BOOT)) prefs(ctx).edit(commit = true) { remove(ON_BOOT) } } }

    /** "unbraid is off after the update" with a tap that reconnects: only that notice's tap carries the token. */
    fun tell(ctx: Context) {
        val token = java.util.UUID.randomUUID().toString()
        runCatching { prefs(ctx).edit(commit = true) { putString(TOKEN, token) } }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("alerts", "Problems", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(ctx, 5, Intent(ctx, MainActivity::class.java).putExtra(EXTRA, token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(2, Notification.Builder(ctx, "alerts").setSmallIcon(R.drawable.ic_tile).setContentTitle(APP)
            .setContentText(OFF).setContentIntent(tap).setAutoCancel(true).build())
    }

    /**
     * True once for the tap on [tell]'s notice: the activity is open to every app, so only the token, which no other app
     * can know, asks it to connect.
     */
    fun claim(ctx: Context, intent: Intent?): Boolean {
        val sent = intent?.getStringExtra(EXTRA) ?: return false
        intent.removeExtra(EXTRA)
        val p = prefs(ctx)
        if (sent != p.getString(TOKEN, null)) return false
        p.edit(commit = true) { remove(TOKEN) }
        return true
    }

    /**
     * The app was replaced. Not exported: only Android's own broadcast to this app reaches it. Android lets an app start
     * a foreground service from this broadcast; if the start is refused anyway, or consent is gone, the user is told.
     */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            val p = prefs(ctx)
            val onBoot = p.getInt(ON_BOOT, Int.MIN_VALUE).takeIf { p.contains(ON_BOOT) }
            off(ctx) // the start below notes it again once connected
            when (step(onBoot, boot(ctx), VpnService.prepare(ctx) == null)) {
                Step.None -> Unit
                Step.Tell -> tell(ctx)
                Step.Start -> runCatching { UnbraidVpn.startAfterUpdate(ctx) }.onFailure { tell(ctx) }
            }
        }
    }
}
