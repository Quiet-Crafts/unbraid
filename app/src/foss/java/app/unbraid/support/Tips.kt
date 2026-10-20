package app.unbraid.support

import android.content.Context

/** GitHub and F-Droid build: no store billing (and no proprietary billing library). */
object Tips {
    fun jar(ctx: Context): TipJar? = null
    /** Where to support the project instead; only this build carries the link. */
    val sponsors: String? = "https://unbraid.app/donate/"
}
