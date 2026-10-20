package app.unbraid.support

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A one-time tip, priced by the store in the user's own currency. */
data class Tip(val id: String, val price: String)

/**
 * Tips through the store this build is published on. [Tips.jar] gives null where the store allows none of its own
 * (the F-Droid/GitHub build, which links to GitHub Sponsors instead).
 */
interface TipJar {
    /** Offered tips, cheapest first; empty until the store answers, or while none are set up in the store. */
    val tips: StateFlow<List<Tip>>
    /** True once a tip went through; the screen thanks the user and clears it. */
    val thanked: MutableStateFlow<Boolean>
    fun buy(activity: Activity, id: String)
    /** Connects to the store on first call, then settles tips that completed while away; call when the tip screen shows. */
    fun refresh()
    fun close()
}

/** Product ids of the tips: one-time, consumable in-app products in Play Console. */
val TIP_IDS = listOf("tip_small", "tip_medium", "tip_large")
