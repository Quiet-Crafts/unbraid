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

    fun stop() = runCatching { cm.unregisterNetworkCallback(this) }.let {}

    fun quit() { stop(); thread.quitSafely() }

    fun listen(l: InterfaceUpdateListener?) {
        listener = l
        push(force = true) // a new engine has no interface yet
    }

    // Every update the engine sees as a new interface closes all connections, so only real changes go through:
    // a loss waits briefly for the network to come back, and link changes on the same interface are ignored.
    private val lose = Runnable { network = null; push() }
    @Volatile private var pushed: Pair<String, Int>? = null

    override fun onAvailable(n: Network) { handler.removeCallbacks(lose); network = n; push() }
    override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) { if (n == network) push() }
    override fun onLost(n: Network) { if (n == network) handler.postDelayed(lose, 3_000) }

    private fun push(force: Boolean = false) {
        val l = listener ?: return
        val name = network?.let { cm.getLinkProperties(it)?.interfaceName }
        val index = name?.let { runCatching { NetworkInterface.getByName(it)?.index }.getOrNull() }
        val now = if (name == null || index == null) "" to -1 else name to index
        if (!force && now == pushed) return
        pushed = now
        l.updateDefaultInterface(now.first, now.second, false, false)
        if (!force && now.second >= 0) onChange?.invoke()
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
