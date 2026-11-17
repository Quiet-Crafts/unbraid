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

    /** The network under the VPN; null with none (gone for more than a moment), or while nothing watches it. */
    val net = MutableStateFlow<Net?>(null)

    /** The last switch by network that didn't happen: its profile and why. Kept for the Routes page, as a notice may be off or replaced. */
    val notSwitched = MutableStateFlow<Pair<String, String>?>(null)

    /** For a pick made while nothing watches the network (the VPN off): the phone's own network then. */
    @Volatile private var cm: ConnectivityManager? = null

    private val run = Run()

    /**
     * What was decided and picked by hand, in memory only: after a restart the rules apply again.
     *
     * A decision is the network and the profile its rule named then, so editing the rules changes nothing unless this
     * network's profile changed, and a rename is followed ([renamed]). A profile picked by hand belongs to the network
     * the phone is on when it is picked and holds until the phone is on another one; only a new rule for that network
     * applies over it. Picked with no network, it is for none: the next network's rule applies.
     */
    class Run {
        private var acted: Pair<Long, String?>? = null
        private var picked: Long? = null
        /** Counts picks: a switch decided before the latest pick is not made. */
        var picks = 0
            private set

        @Synchronized fun pick(here: Net?, rules: Map<String, String>) { picks++; picked = here?.id; acted = here?.let { it.id to rules[it.kind] } }

        /** Profile [old] is now called [new]: what was decided follows it. */
        @Synchronized fun renamed(old: String, new: String) { acted = acted?.let { (n, p) -> n to (if (p == old) new else p) } }

        /** Settled on [here] under [rules]: the profile to switch to, or null (decided already, or a pick holds). */
        @Synchronized fun settle(here: Net, rules: Map<String, String>): String? {
            val was = acted
            acted = here.id to rules[here.kind]
            if (picked == here.id && was == acted) return null
            picked = null
            return rules[here.kind].takeIf { was != acted }
        }
    }

    /** A profile was picked by hand, the one in use too: it holds on the network the phone is on now. */
    fun pickedByHand() {
        notSwitched.value = null
        run.pick(net.value ?: cm?.let(::underlying), Unbraid.store.setup.value.onNet)
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
        combine(UnbraidVpn.state.map { it.phase == Phase.On }.distinctUntilChanged(), net, Unbraid.foreground, store.setup.map { it.onNet }.distinctUntilChanged()) { on, n, shown, rules ->
            n.takeIf { on && !shown && rules.isNotEmpty() }
        }.collectLatest { n ->
            n ?: return@collectLatest
            delay(SETTLE_MS)
            // Settings that won't open are not changed, and nothing is decided on them.
            if (store.unreadable.value) return@collectLatest
            val s = store.setup.value
            val picks = run.picks
            val acc = store.accounts.value
            val want = run.settle(n, s.onNet)
            val step = decide(s, want) { old, new -> Limits.over(old, new) { p -> acc[p]?.let { a -> Providers.get(p)?.limit(a) } } != null }
            step.blocked?.let { notSwitched.value = want.orEmpty() to it; UnbraidVpn.problem(ctx, it) }
            val to = step.to ?: return@collectLatest
            // Saved off the main thread and never cut short: once the setup in use changed, the switch is followed (and
            // put back if the engine refuses it), whether or not the file could be written or the app came on screen.
            // A profile picked by hand meanwhile wins.
            withContext(NonCancellable + Dispatchers.IO) { store.update { if (run.picks == picks) it.use(to, PREVIOUS) else it }; if (store.setup.value.profile == to) { notSwitched.value = null; Profiles.started(to) } }
            if (store.setup.value.profile != to) return@collectLatest
            // Refused by the engine: what it still runs is back in use ([Profiles.watch]); here it is only said.
            Profiles.switching.first { it != to }
            Profiles.refused.value?.takeIf { it.to == to }?.let { UnbraidVpn.problem(ctx, "Couldn't switch to $to. ${it.kept ?: "Your setup"} is still in use.") }
        }
    }
}
