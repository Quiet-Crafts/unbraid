package app.unbraid.vpn

import android.content.Context
import android.net.ConnectivityManager
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
    /** How long a network must last before a switch, so a handover or a short drop passes without one. */
    const val SETTLE_MS = 5_000L

    /**
     * A network the phone is on: its [kind] ([app.unbraid.data.WIFI] or [app.unbraid.data.MOBILE]) and [id], Android's
     * handle for this one connection to it. Joining another network, or the same one again, gives a new [id].
     */
    data class Net(val kind: String, val id: Long)

    /** The network under the VPN; null with none (gone for more than a moment), or while nothing watches it. Follows [Run]. */
    val net = MutableStateFlow<Net?>(null)

    /**
     * A switch by network that didn't happen: to [profile], set for [kind] of network, decided on network [net], and
     * [why]. Kept for the Routes page, as a notice may be off or replaced.
     */
    data class NotSwitched(val profile: String, val kind: String, val net: Long, val why: String) {
        /**
         * Still true of setup [s] with the phone on [here] (null: not known now): the rule for its kind of network still
         * names this profile, which exists and isn't in use, and the phone hasn't left that network. A profile renamed or
         * deleted, then another made under the old name, fails the rule check, so the card never acts on that other one.
         */
        fun holds(s: Setup, here: Net?) = s.onNet[kind] == profile && s.profile != profile && s.profiles.any { it.name == profile } && (here == null || here.id == net)
    }

    val notSwitched = MutableStateFlow<NotSwitched?>(null)

    /** For a pick made while nothing watches the network (the VPN off): the phone's own network then. */
    @Volatile private var cm: ConnectivityManager? = null

    /*
     * The model, all in one place:
     * - The network now (kind and id) is [State.here], set only by [Event.Seen] from the network monitor, in the step that
     *   moves the monitor (and with it the engine and the health checks) to it. [net] is published from it.
     * - Every input is an [Event], applied one at a time by [step] ([Run] holds the lock), which gives the new state and
     *   at most one [Decision].
     * - A decision is made only once a network lasted [SETTLE_MS] ([Event.Settled]). Saving it ([Event.Commit]) checks
     *   again, at the moment of writing, that the phone is still on that network, no pick came since, its rule still names
     *   the profile and the app is not on screen. Otherwise nothing is written and the next settle decides again.
     * - Leaving a network ends its pick and its decision at once, whether or not anything settles or is on screen.
     */

    /** What the switch by network knows. Kept in memory only: after a restart the rules apply again. */
    data class State(
        /** The monitor runs (the VPN is on), so [here] is known. */
        val watched: Boolean = false,
        val here: Net? = null,
        /** The network and the profile its rule named when it was last decided or picked on. */
        val acted: Pair<Long, String?>? = null,
        /** The network a hand pick was made on: it holds there. */
        val picked: Long? = null,
        /** Counts hand picks: a decision made before the latest pick is not saved. */
        val picks: Int = 0,
    )

    sealed interface Event {
        /** The monitor's network now; null = none. */
        data class Seen(val net: Net?) : Event
        /** The monitor stopped (the VPN off): no departure is seen until it runs again. */
        data object Unwatched : Event
        /** A profile picked by hand under [rules]; [own] is the phone's own network read from Android, for a pick made while unwatched. */
        data class Picked(val rules: Map<String, String>, val own: Net? = null) : Event
        data class Renamed(val old: String, val new: String) : Event
        /** [net] lasted long enough, with the VPN on and the app not on screen. */
        data class Settled(val net: Net, val rules: Map<String, String>) : Event
        /** About to save [d] under [rules], with the app on screen or not ([shown]). */
        data class Commit(val d: Decision, val rules: Map<String, String>, val shown: Boolean) : Event
    }

    /** Switch to [profile] on network [net], decided after [picks] hand picks. */
    data class Decision(val net: Long, val profile: String, val picks: Int)

    /** The state after an event, a decision to carry out, and for [Event.Commit] whether to save it. */
    data class Out(val state: State, val decision: Decision? = null, val save: Boolean = false)

    /** One input to the model. Pure: the same state and event always give the same answer. */
    fun step(s: State, e: Event): Out = when (e) {
        is Event.Seen -> {
            // Android's handle 0 names no network.
            val n = e.net?.takeIf { it.id != 0L }
            Out(State(true, n, s.acted?.takeIf { it.first == n?.id }, s.picked?.takeIf { it == n?.id }, s.picks))
        }
        Event.Unwatched -> Out(s.copy(watched = false, here = null))
        is Event.Picked -> {
            // Picked with no network known, it is for none: the next network's rule applies.
            val at = (if (s.watched) s.here else e.own)?.takeIf { it.id != 0L }
            Out(s.copy(picked = at?.id, acted = at?.let { it.id to e.rules[it.kind] }, picks = s.picks + 1))
        }
        // What was decided follows a rename, so it is no change.
        is Event.Renamed -> Out(s.copy(acted = s.acted?.let { (n, p) -> n to (if (p == e.old) e.new else p) }))
        is Event.Settled -> {
            val here = s.here
            val now = here?.let { it.id to e.rules[it.kind] }
            when {
                !s.watched || here != e.net -> Out(s) // settled on a network already left
                s.acted == now -> Out(s) // decided already, or a pick holds: only a new rule for this network applies over it
                else -> Out(s.copy(acted = now, picked = null), now?.second?.let { Decision(here.id, it, s.picks) })
            }
        }
        is Event.Commit -> {
            val here = s.here
            val ok = s.watched && here?.id == e.d.net && s.picks == e.d.picks && e.rules[here.kind] == e.d.profile && !e.shown
            // Not saved and nothing took the decision's place: the next settle decides again.
            Out(if (!ok && s.picks == e.d.picks && s.acted == (e.d.net to e.d.profile)) s.copy(acted = null) else s, save = ok)
        }
    }

    /** [step] applied one event at a time; [publish] gets the network after each. */
    class Run(private val publish: (Net?) -> Unit = {}) {
        var state = State()
            private set

        @Synchronized fun on(e: Event): Out = step(state, e).also { state = it.state; publish(it.state.here) }

        fun seen(n: Net?) = on(Event.Seen(n))
        fun unwatched() = on(Event.Unwatched)
        fun pick(rules: Map<String, String>, own: Net? = null) = on(Event.Picked(rules, own))
        fun renamed(old: String, new: String) = on(Event.Renamed(old, new))
        fun settle(n: Net, rules: Map<String, String>) = on(Event.Settled(n, rules)).decision
        fun commit(d: Decision, rules: Map<String, String>, shown: Boolean) = on(Event.Commit(d, rules, shown)).save
    }

    private val run = Run { net.value = it }

    /** The network monitor's network now (null = none), from the step that moves the monitor to it. */
    fun seen(n: Net?) = run.seen(n)

    /** The network monitor stopped. */
    fun unwatched() = run.unwatched()

    /**
     * The one place a profile picked by hand is noted, the one in use too: it holds on the network the phone is on now.
     * The profile tabs, Use, the switch question, the Not switched card and the tiles all end here.
     */
    fun pickedByHand() {
        notSwitched.value = null
        run.pick(Unbraid.store.setup.value.onNet, if (run.state.watched) null else cm?.let(::underlying))
    }

    /** Profile [old] was renamed [new]. */
    fun renamed(old: String, new: String) = run.renamed(old, new)

    /** What to do: switch [to] a profile, or say why not ([blocked]); neither = nothing. */
    class Step(val to: String? = null, val blocked: String? = null)

    /**
     * Switching setup [s] to profile [name] (null = none named); [over] says whether the switch takes a provider over its
     * connection limit (the app would ask first, which nothing here can).
     */
    fun decide(s: Setup, name: String?, over: (Setup, Setup) -> Boolean): Step {
        if (name == null || name == s.profile) return Step()
        // A profile without a default route yet can't be used; the Profiles page says so on its row.
        if (s.profiles.none { it.name == name && it.ready }) return Step()
        val next = s.use(name, PREVIOUS)
        return when {
            next.profile != name -> Step(blocked = "Not switched to $name: there is no room to keep your current setup as a profile. Delete a profile, or choose a profile yourself.")
            over(s, next) -> Step(blocked = "Not switched to $name: it needs more connections than a provider allows. Choose it yourself to decide what to do.")
            else -> Step(to = name)
        }
    }

    fun watch(scope: CoroutineScope, ctx: Context) = scope.launch {
        cm = ctx.getSystemService(ConnectivityManager::class.java)
        val store = Unbraid.store
        // The card for a switch that didn't happen goes once it no longer holds, and doesn't come back.
        launch { combine(notSwitched, store.setup, net) { ns, s, n -> ns?.takeIf { !it.holds(s, n) } }.collect { gone -> if (gone != null) notSwitched.compareAndSet(gone, null) } }
        combine(UnbraidVpn.state.map { it.phase == Phase.On }.distinctUntilChanged(), net, Unbraid.foreground, store.setup.map { it.onNet }.distinctUntilChanged()) { on, n, shown, rules ->
            n.takeIf { on && !shown && rules.isNotEmpty() }
        }.collectLatest { n ->
            n ?: return@collectLatest
            delay(SETTLE_MS)
            // Settings that won't open are not changed, and nothing is decided on them.
            if (store.unreadable.value) return@collectLatest
            val s = store.setup.value
            val d = run.settle(n, s.onNet) ?: return@collectLatest
            val acc = store.accounts.value
            val step = decide(s, d.profile) { old, new -> Limits.over(old, new) { p -> acc[p]?.let { a -> Providers.get(p)?.limit(a) } } != null }
            val to = step.to
            if (to == null) {
                step.blocked?.takeIf { run.commit(d, s.onNet, Unbraid.foreground.value) }?.let { notSwitched.value = NotSwitched(d.profile, n.kind, n.id, it); UnbraidVpn.problem(ctx, it) }
                return@collectLatest
            }
            // Saved off the main thread and never cut short: once the setup in use changed, the switch is followed (and
            // put back if the engine refuses it), whether or not the file could be written. Checked again inside the
            // store's own update, so a network change, a pick, a rule edit or the app coming on screen meanwhile wins.
            withContext(NonCancellable + Dispatchers.IO) { store.update { if (run.commit(d, it.onNet, Unbraid.foreground.value)) it.use(to, PREVIOUS) else it }; if (store.setup.value.profile == to) { notSwitched.value = null; Profiles.started(to) } }
            if (store.setup.value.profile != to) return@collectLatest
            // Refused by the engine: what it still runs is back in use ([Profiles.watch]); here it is only said.
            Profiles.switching.first { it != to }
            Profiles.refused.value?.takeIf { it.to == to }?.let { UnbraidVpn.problem(ctx, "Couldn't switch to $to. ${it.kept ?: "Your setup"} is still in use.") }
        }
    }
}
