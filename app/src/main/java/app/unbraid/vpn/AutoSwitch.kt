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
     * A network the phone is on: its [kind] ([app.unbraid.data.WIFI] or [app.unbraid.data.MOBILE]; null until Android
     * reports it) and [id], Android's handle for this one connection to it. Joining another network, or the same one
     * again, gives a new [id].
     */
    data class Net(val kind: String?, val id: Long)

    /** The network under the VPN; null with none (lost), or while nothing watches it. Follows [Run]. */
    val net = MutableStateFlow<Net?>(null)

    /** The whole model as last stepped: tells "nothing watches the network" ([State.watched] false) from "no network". */
    val model = MutableStateFlow(State())

    /**
     * A switch by network that didn't happen: to [profile], set for [kind] of network, decided on network [net], and
     * [why]. Kept for the Routes page, as a notice may be off or replaced.
     */
    data class NotSwitched(val profile: String, val kind: String, val net: Long, val why: String) {
        /**
         * Still true of setup [s] with the switch by network at [m]: the rule for its kind of network still names this
         * profile, which exists and isn't in use, and the phone hasn't left that network. Only while nothing watches the
         * network (the VPN off) is it not known whether the phone left; a network seen lost has been left. A profile
         * renamed or deleted, then another made under the old name, fails the rule check, so the card never acts on that
         * other one.
         */
        fun holds(s: Setup, m: State) = s.onNet[kind] == profile && s.profile != profile && s.profiles.any { it.name == profile } && (!m.watched || m.here?.id == net)
    }

    val notSwitched = MutableStateFlow<NotSwitched?>(null)

    /** For a pick made while nothing watches the network (the VPN off): the phone's own network then. */
    @Volatile private var cm: ConnectivityManager? = null

    /*
     * The model, all in one place (the table of states and events is in the tests' terms in NetSequencesTest):
     * - The network now (kind and id) is [State.here], set only by the network monitor, in the step that moves the
     *   monitor (and with it the engine and the health checks) to it ([Event.Seen]), or at once when Android says it is
     *   lost ([Event.Lost]; the engine alone waits a moment for it to come back). [net] is published from it.
     * - A network whose kind isn't reported yet is a state of its own: picks made on it hold, and no rule applies to it.
     * - Every input is an [Event], applied one at a time by [step] ([Run] holds the lock).
     * - A decision is made only once a network lasted [SETTLE_MS] ([Event.Settled]) and is [State.pending] until saved.
     *   It is void as soon as the phone leaves that network (even to come back to it), a pick or the VPN going off comes,
     *   or its profile is renamed: saving it ([Event.Commit]) checks at the moment of writing that it is still the pending
     *   one, its rule still names the profile and the app is not on screen. A decision not saved is decided again at the
     *   next settle, never used up; only a save, a pick or a network without a rule is [State.acted] on.
     */

    /** What the switch by network knows. Kept in memory only: after a restart the rules apply again. */
    data class State(
        /** The monitor runs (the VPN is on), so [here] is known. */
        val watched: Boolean = false,
        val here: Net? = null,
        /**
         * The network last acted on (a save, a pick, or nothing to do) and the rules then. A hand pick holds because it
         * sets this too: only another network, or a new rule for this one's kind, differs from it.
         */
        val acted: Pair<Long, Map<String, String>>? = null,
        /** The decision waiting for its save; none once saved, void or replaced. */
        val pending: Decision? = null,
        /** Numbers the decisions, so one made again for the same network and profile is another decision. */
        val made: Int = 0,
    )

    sealed interface Event {
        /** The monitor's network now; null = none. */
        data class Seen(val net: Net?) : Event
        /** Android lost network [id]: the phone has left it, whether or not it comes back. */
        data class Lost(val id: Long) : Event
        /** The monitor stopped (the VPN off): no departure is seen until it runs again. */
        data object Unwatched : Event
        /** A profile picked by hand under [rules]; [own] is the phone's own network read from Android, for a pick made while unwatched. */
        data class Picked(val rules: Map<String, String>, val own: Net? = null) : Event
        data class Renamed(val old: String, val new: String) : Event
        /** [net] lasted long enough, with the VPN on and the app not on screen. */
        data class Settled(val net: Net, val rules: Map<String, String>) : Event
        /** About to save [d] (or, with nothing to switch, to note it done) under [rules], with the app on screen or not ([shown]). */
        data class Commit(val d: Decision, val rules: Map<String, String>, val shown: Boolean) : Event
    }

    /** Switch to [profile] on network [net]; [n] tells it from a decision made again for the same ones. */
    data class Decision(val net: Long, val profile: String, val n: Int)

    /** The state after an event, a decision to carry out, and for [Event.Commit] whether to save it. */
    data class Out(val state: State, val decision: Decision? = null, val save: Boolean = false)

    private fun Map<String, String>.renamed(old: String, new: String) = mapValues { (_, v) -> if (v == old) new else v }

    /** One input to the model. Pure: the same state and event always give the same answer. */
    fun step(s: State, e: Event): Out = when (e) {
        is Event.Seen -> {
            // Android's handle 0 names no network. The same network again (its kind reported, a repeat) is no move.
            val n = e.net?.takeIf { it.id != 0L }
            if (s.watched && n != null && n.id == s.here?.id) Out(s.copy(here = n))
            else Out(State(true, n, s.acted?.takeIf { it.first == n?.id }, made = s.made))
        }
        is Event.Lost -> Out(if (s.watched && s.here?.id == e.id) State(true, made = s.made) else s)
        Event.Unwatched -> Out(s.copy(watched = false, here = null, pending = null))
        is Event.Picked -> {
            // Picked with no network known, it is for none: the next network's rule applies.
            val at = (if (s.watched) s.here else e.own)?.takeIf { it.id != 0L }
            Out(s.copy(acted = at?.let { it.id to e.rules.toMap() }, pending = null))
        }
        // What was acted on follows a rename, so it is no change; a decision not saved yet is made again under the new name.
        is Event.Renamed -> Out(s.copy(acted = s.acted?.let { (n, r) -> n to r.renamed(e.old, e.new) }, pending = s.pending?.takeIf { it.profile != e.old }))
        is Event.Settled -> {
            val here = s.here
            val kind = here?.kind
            val rule = kind?.let { e.rules[it] }
            when {
                // Settled on a network already left, or whose kind isn't known yet.
                !s.watched || here != e.net || kind == null -> Out(s)
                // Acted on already, or a pick holds: only a new rule for this network applies over it.
                s.acted?.let { it.first == here.id && it.second[kind] == rule } == true -> Out(s)
                s.pending?.let { it.net == here.id && it.profile == rule } == true -> Out(s) // being saved
                rule == null -> Out(s.copy(acted = here.id to e.rules.toMap()))
                else -> Decision(here.id, rule, s.made + 1).let { Out(s.copy(pending = it, made = it.n), it) }
            }
        }
        is Event.Commit -> {
            val here = s.here
            val ok = s.watched && s.pending == e.d && here?.id == e.d.net && here.kind?.let { e.rules[it] } == e.d.profile && !e.shown
            // Not saved: the decision is dropped and the next settle decides again.
            Out(if (ok) s.copy(acted = e.d.net to e.rules.toMap(), pending = null) else if (s.pending == e.d) s.copy(pending = null) else s, save = ok)
        }
    }

    /** [step] applied one event at a time; [publish] gets the state after each. */
    class Run(private val publish: (State) -> Unit = {}) {
        var state = State()
            private set

        @Synchronized fun on(e: Event): Out = step(state, e).also { state = it.state; publish(it.state) }

        fun seen(n: Net?) = on(Event.Seen(n))
        fun lost(id: Long) = on(Event.Lost(id))
        fun unwatched() = on(Event.Unwatched)
        fun pick(rules: Map<String, String>, own: Net? = null) = on(Event.Picked(rules, own))
        fun renamed(old: String, new: String) = on(Event.Renamed(old, new))
        fun settle(n: Net, rules: Map<String, String>) = on(Event.Settled(n, rules)).decision
        fun commit(d: Decision, rules: Map<String, String>, shown: Boolean) = on(Event.Commit(d, rules, shown)).save
    }

    private val run = Run { model.value = it; net.value = it.here }

    /** The network monitor's network now (null = none), from the step that moves the monitor to it. */
    fun seen(n: Net?) = run.seen(n)

    /** Android lost network [id] (its handle). */
    fun lost(id: Long) = run.lost(id)

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
        launch { combine(notSwitched, store.setup, model) { ns, s, m -> ns?.takeIf { !it.holds(s, m) } }.collect { gone -> if (gone != null) notSwitched.compareAndSet(gone, null) } }
        combine(UnbraidVpn.state.map { it.phase == Phase.On }.distinctUntilChanged(), net, Unbraid.foreground, store.setup.map { it.onNet }.distinctUntilChanged()) { on, n, shown, rules ->
            n.takeIf { on && !shown && rules.isNotEmpty() && n?.kind != null }
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
            // Nothing to switch (already in use, or blocked): noted as done, and said only if it still holds.
            if (to == null) {
                if (run.commit(d, s.onNet, Unbraid.foreground.value)) step.blocked?.let { notSwitched.value = NotSwitched(d.profile, n.kind!!, n.id, it); UnbraidVpn.problem(ctx, it) }
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
