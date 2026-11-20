package app.unbraid.vpn

import android.util.Log
import app.unbraid.data.Providers
import app.unbraid.data.ROTATE_OPT
import app.unbraid.data.Setup

/**
 * New keys at connect, before any tunnel uses them: for each provider a route uses, when its key is due (its page's
 * setting), unfinished or lacks the quantum-safe key. A failure only means the old key stays: it never stops the
 * connection; one whose reply was lost is finished later ([finishOnly], from the health rounds), since the provider may
 * already take only the new key. A provider with a new key is given the time its servers need to learn it ([Providers.signedInAt]).
 */
internal object Rotation {
    @Synchronized // one change at a time: a start and a health round never send the same new key twice
    fun run(setup: Setup, accounts: () -> Map<String, String>, replace: (String, String, String) -> Boolean, now: Long = System.currentTimeMillis(), finishOnly: Boolean = false) {
        val used = setup.activeRoutes.filter { !it.imported }.mapTo(HashSet()) { it.loc.provider }
        for ((id, blob) in accounts()) {
            val p = Providers.get(id)?.takeIf { id in used } ?: continue
            var expect = blob
            try {
                if (p.rotate(blob, setup.opt(id, ROTATE_OPT)?.toIntOrNull(), now, finishOnly) { b -> replace(id, expect, b).also { if (it) expect = b } }) Providers.signedInAt[id] = now
            } catch (e: Exception) {
                Log.w("Unbraid", "new key for $id: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Runs [block] once no key change is under way, and none starts meanwhile: a device release or a sign-in never
     * meets a renewal that has already been sent, which could renew a device being released, or the one a sign-in takes.
     * Blocks: never on the main thread.
     */
    fun <T> excluded(block: () -> T): T = synchronized(this) { block() }
}
