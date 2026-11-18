package app.unbraid.vpn

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.system.ErrnoException
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Tracks the underlying (non-VPN) default network and tells sing-box which interface to bind to. */
class NetMonitor(private val cm: ConnectivityManager) : ConnectivityManager.NetworkCallback() {
    @Volatile var network: Network? = null
        private set
    @Volatile private var listener: InterfaceUpdateListener? = null
    /** Called when the phone moves to another network (not on the first one the engine gets). */
    @Volatile var onChange: (() -> Unit)? = null
    private val thread = HandlerThread("net").apply { start() }
    private val handler = Handler(thread.looper)

    fun start() {
        network = cm.activeNetwork
        cm.registerBestMatchingNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            this, handler,
        )
    }

    fun stop() { handler.removeCallbacks(lose); runCatching { cm.unregisterNetworkCallback(this) }; AutoSwitch.unwatched() }

    fun quit() { stop(); thread.quitSafely() }

    fun listen(l: InterfaceUpdateListener?) {
        listener = l
        push(force = true) // a new engine has no interface yet
        // The engine's thread runs this while network callbacks push on the net thread: one of them may have delivered a
        // newer network before this delivery, and noted it as sent. Sent again on the net thread, after any such
        // callback and whatever was noted, so the engine ends on the current network (it ignores a repeat of the one it
        // has). Nothing is held across the engine call, so the two threads can't wait on each other.
        if (l != null) handler.post { push(force = true) }
    }

    // Every update the engine sees as a new interface closes all connections, so only real changes go through:
    // a loss waits briefly for the network to come back, and link changes on the same interface are ignored.
    @Volatile private var lost: Network? = null
    private val lose = Runnable { if (network == lost) { network = null; AutoSwitch.seen(null); push() } }
    private val pusher = InterfacePush {
        val name = network?.let { cm.getLinkProperties(it)?.interfaceName }
        val index = name?.let { runCatching { NetworkInterface.getByName(it)?.index }.getOrNull() }
        if (name == null || index == null) "" to -1 else name to index
    }

    // The switch by network learns of a new network in the same step as everything else here, so a pick made meanwhile
    // can't be noted for the network the phone just left. Without its capabilities yet, it is this network of a kind not
    // known yet (they follow in onCapabilitiesChanged).
    override fun onAvailable(n: Network) { handler.removeCallbacks(lose); val was = network; network = n; AutoSwitch.seen(net(n, cm.getNetworkCapabilities(n))); push(was = was?.networkHandle, now = n.networkHandle) }
    override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) { if (n == network) push() }
    // Only the engine waits for a lost network to come back; for the switch by network the phone has left it.
    override fun onLost(n: Network) { if (n == network) { lost = n; AutoSwitch.lost(n.networkHandle); handler.postDelayed(lose, 3_000) } }
    // The VPN itself is never metered (Android would warn about every app); a combined speed test asks first on a metered one.
    override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
        if (n != network) return
        UnbraidVpn.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        AutoSwitch.seen(net(n, caps))
    }

    /** Not [force]d: a real move to another network ([was] → [now], by Android's handle, when known), which may fix blocked routes ([onChange]). */
    private fun push(force: Boolean = false, was: Long? = null, now: Long? = null) {
        val l = listener ?: return
        val sent = pusher.push(force) { l.updateDefaultInterface(it.first, it.second, false, false) }
        if (!force && netMoved(sent, was, now)) onChange?.invoke()
    }
}

/**
 * Network [n] for the profile set to follow it: Wi-Fi or mobile data (a cable counts as Wi-Fi; no [caps] yet: not
 * known), and this connection to it; null for handle 0, which names none.
 */
private fun net(n: Network, caps: NetworkCapabilities?) = n.networkHandle.takeIf { it != 0L }?.let {
    AutoSwitch.Net(caps?.let { c -> if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) app.unbraid.data.MOBILE else app.unbraid.data.WIFI }, it)
}

/** What choosing the phone's own network needs of one network: Android's handle, whether it is a VPN, has internet, is unrestricted, and is mobile data. */
internal data class Link(val handle: Long, val vpn: Boolean, val internet: Boolean, val unrestricted: Boolean, val mobile: Boolean)

/**
 * The network under the phone's default one, never a VPN: the default itself unless it is a VPN (another one, with unbraid
 * off), then the one of the [all] networks the monitor would follow (internet, unrestricted, not a VPN). Null with no
 * default, when none qualifies, or when more than one does: Android may be using any of them under that VPN.
 */
internal fun choose(default: Link?, all: List<Link>): Link? =
    if (default == null || !default.vpn) default
    else all.filter { !it.vpn && it.internet && it.unrestricted }.singleOrNull()

/** The phone's own network now ([net], never a VPN; null with none), and [unknown]: a default network is there but the one under it can't be told. */
data class Own(val net: AutoSwitch.Net?, val unknown: Boolean)

private fun link(cm: ConnectivityManager, n: Network) = cm.getNetworkCapabilities(n)?.let { c ->
    Link(n.networkHandle, c.hasTransport(NetworkCapabilities.TRANSPORT_VPN), c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED), c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
}

/** The phone's own network now. unbraid itself is left outside its VPN, so this holds while connected too. */
@Suppress("DEPRECATION")
fun underlying(cm: ConnectivityManager): Own = runCatching {
    val active = cm.activeNetwork
    val default = active?.let { link(cm, it) }
    own(active != null, default, if (default?.vpn == true) cm.allNetworks.mapNotNull { link(cm, it) } else emptyList())
}.getOrDefault(Own(null, false))

/** [Own] from [choose]: unknown when a default network is there ([active], even one whose capabilities can't be read) but none is chosen. */
internal fun own(active: Boolean, default: Link?, all: List<Link>) = choose(default, all).let { o ->
    Own(o?.takeIf { it.handle != 0L }?.let { AutoSwitch.Net(if (it.mobile) app.unbraid.data.MOBILE else app.unbraid.data.WIFI, it.handle) }, active && o == null)
}

/**
 * Whether a push is a move that may fix blocked routes ([NetMonitor.onChange]): another interface the engine was handed
 * ([sent], never none), or another network straight after one on the same interface ([was] → [now], Android's handles),
 * which the engine isn't told of again: another Wi-Fi on wlan0. A network after none gets its interface handed over.
 */
internal fun netMoved(sent: Pair<String, Int>?, was: Long?, now: Long?) = if (sent != null) sent.second >= 0 else was != null && now != null && now != was

/**
 * Hands the engine the current default interface ([current]: name and index, "" and -1 for none): only a change, unless
 * [push] is forced. Nothing is held across the delivery, so it never waits on the engine.
 */
internal class InterfacePush(private val current: () -> Pair<String, Int>) {
    @Volatile private var pushed: Pair<String, Int>? = null

    /** What it delivered, or null when it was what was sent last. */
    fun push(force: Boolean, deliver: (Pair<String, Int>) -> Unit): Pair<String, Int>? {
        val now = current()
        if (!force && now == pushed) return null
        pushed = now
        deliver(now)
        return now
    }
}

/** sing-box "local" DNS: the system resolver, pinned to the underlying network so it never loops into the VPN. */
class LocalDns(private val net: NetMonitor) : LocalDNSTransport {
    private val exec = Executors.newCachedThreadPool()

    override fun raw() = true

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val n = net.network ?: error("no network")
        val done = CountDownLatch(1)
        val signal = CancellationSignal()
        ctx.onCancel { signal.cancel(); done.countDown() }
        DnsResolver.getInstance().rawQuery(n, message, DnsResolver.FLAG_NO_RETRY, exec, signal, object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(answer: ByteArray, rcode: Int) {
                if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
                done.countDown()
            }
            override fun onError(e: DnsResolver.DnsException) {
                (e.cause as? ErrnoException)?.let { ctx.errnoCode(it.errno) } ?: ctx.errorCode(2)
                done.countDown()
            }
        })
        done.await()
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) = error("raw only")
}
