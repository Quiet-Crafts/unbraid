package app.unbraid.vpn

import android.content.Context
import app.unbraid.APP
import app.unbraid.Unbraid
import app.unbraid.data.Limits
import app.unbraid.data.PREVIOUS
import app.unbraid.data.Providers
import app.unbraid.data.Setup
import app.unbraid.vpn.VpnState.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Switches to the profile set for the kind of network the phone is on ([Setup.onNet]), the way a quick-settings tile
 * does. Only while connected, and never while the app is on screen: what an open page edits doesn't change under it.
 */
object AutoSwitch {
    /** How long a kind of network must last before a switch, so a handover or a short drop passes without one. */
    const val SETTLE_MS = 5_000L

    /** The kind of the network under the VPN ([app.unbraid.data.WIFI] or [app.unbraid.data.MOBILE]); null with none, or while nothing watches it. */
    val kind = MutableStateFlow<String?>(null)

    /** The kind and the rules last decided on: nothing more happens until one of them changes, so a profile picked by hand stays. */
    @Volatile private var acted: Pair<String, Map<String, String>>? = null
    @Volatile private var byHand = false

    /** A profile was picked by hand: the network it was picked on counts as decided (the next one seen, while none is known). */
    fun pickedByHand() { kind.value?.let { acted = it to Unbraid.store.setup.value.onNet } ?: run { byHand = true } }

    /** The rules changed: they apply to the network the phone is on now. */
    fun rulesChanged() { byHand = false; acted = null }

    /** What to do on [kind]: switch [to] a profile, or say why not ([blocked]); neither = nothing. */
    class Step(val to: String? = null, val blocked: String? = null)

    /**
     * On [kind] of network with setup [s]; [acted] is what was last decided on, [over] whether the switch takes a provider
     * over its connection limit (the app would ask first, which nothing here can).
     */
    fun decide(s: Setup, kind: String, acted: Pair<String, Map<String, String>>?, over: (Setup, Setup) -> Boolean): Step {
        val name = s.onNet[kind]?.takeIf { acted != kind to s.onNet && it != s.profile } ?: return Step()
        // A profile without a default route yet can't be used; the Profiles page says so on its row.
        if (s.profiles.none { it.name == name && it.ready }) return Step()
        val next = s.use(name, PREVIOUS)
        return when {
            next.profile != name -> Step(blocked = "Not switched to $name: there is no room to keep your current setup as a profile. Delete a profile, or switch in $APP.")
            over(s, next) -> Step(blocked = "Not switched to $name: it needs more connections than a provider allows. Switch in $APP to choose what to do.")
            else -> Step(to = name)
        }
    }

    fun watch(scope: CoroutineScope, ctx: Context) = scope.launch {
        val store = Unbraid.store
        combine(UnbraidVpn.state.map { it.phase == Phase.On }.distinctUntilChanged(), kind, Unbraid.foreground, store.setup.map { it.onNet }.distinctUntilChanged()) { on, k, shown, rules ->
            k.takeIf { on && !shown && rules.isNotEmpty() }
        }.collectLatest { k ->
            k ?: return@collectLatest
            delay(SETTLE_MS)
            // Settings that won't open are not changed, and nothing is decided on them.
            if (store.unreadable.value) return@collectLatest
            val s = store.setup.value
            val was = acted
            acted = k to s.onNet
            if (byHand) { byHand = false; return@collectLatest }
            val acc = store.accounts.value
            val step = decide(s, k, was) { old, new -> Limits.over(old, new) { p -> acc[p]?.let { a -> Providers.get(p)?.limit(a) } } != null }
            step.blocked?.let { UnbraidVpn.problem(ctx, it) }
            val to = step.to ?: return@collectLatest
            // Saved off the main thread and never cut short: once the setup in use changed, the switch is followed (and
            // put back if the engine refuses it), whether or not the file could be written or the app came on screen.
            withContext(NonCancellable + Dispatchers.IO) { store.update { it.use(to, PREVIOUS) }; if (store.setup.value.profile == to) Profiles.started(to) }
            if (store.setup.value.profile != to) return@collectLatest
            // Refused by the engine: what it still runs is back in use ([Profiles.watch]); here it is only said.
            Profiles.switching.first { it != to }
            Profiles.refused.value?.takeIf { it.to == to }?.let { UnbraidVpn.problem(ctx, "Couldn't switch to $to. ${it.kept ?: "Your setup"} is still in use.") }
        }
    }
}
