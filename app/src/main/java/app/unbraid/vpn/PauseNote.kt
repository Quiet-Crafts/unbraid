package app.unbraid.vpn

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * The end of a pause the user set, noted so a start of Android's own (after it killed the app, or Always-on) pauses on
 * instead of reconnecting early. It never lengthens a pause: it counts only on the same boot and until its own end, so a
 * note that couldn't be cleared just runs out. One that couldn't be written leaves the old way: such a start reconnects.
 */
object PauseNote {
    private const val FILE = "pause"
    private const val MAX = 24 * 3_600_000L

    /** Notes a pause ending at [wake] on the elapsed clock. */
    fun note(c: Context, wake: Long) {
        runCatching { prefs(c).edit().putLong("wake", wake).putInt("boot", boot(c)).putBoolean("on", true).commit() }
    }

    fun clear(c: Context) {
        runCatching { prefs(c).edit().clear().commit() }
    }

    /** How long the noted pause has left in ms, or null when there is none to keep. */
    fun left(c: Context): Long? = runCatching {
        val p = prefs(c)
        if (!p.getBoolean("on", false)) return null
        val b = boot(c)
        if (b < 0 || p.getInt("boot", -2) != b) return null
        val wake = p.getLong("wake", -1)
        (wake - SystemClock.elapsedRealtime()).takeIf { wake > 0 && it in 1..MAX }
    }.getOrNull()

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun boot(c: Context) = Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1)
}
