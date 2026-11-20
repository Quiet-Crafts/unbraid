package app.unbraid

import app.unbraid.vpn.InterfacePush
import app.unbraid.vpn.netMoved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NetPushTest {
    /**
     * The engine attaches while the network changes: its own delivery is stopped after it noted network A as sent, the
     * network thread then sends B, and only then does A reach the engine. The repair sent after it must still end on B.
     */
    @Test fun repairAfterAttachEndsOnTheCurrentNetwork() {
        val a = "wlan0" to 30
        val b = "rmnet0" to 12
        val network = java.util.concurrent.atomic.AtomicReference(a)
        val engine = mutableListOf<Pair<String, Int>>()
        val pusher = InterfacePush { network.get() }
        val noted = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        // The engine's thread: listen() pushes A, forced; it stops between noting A and the engine receiving it.
        val attach = Thread {
            pusher.push(force = true) { now -> noted.countDown(); goOn.await(5, TimeUnit.SECONDS); synchronized(engine) { engine += now } }
        }
        attach.start()
        assertEquals(true, noted.await(5, TimeUnit.SECONDS))
        // The net thread: the phone moves to B, which is sent and reaches the engine first.
        network.set(b)
        assertEquals(b, pusher.push(force = false) { now -> synchronized(engine) { engine += now } })
        goOn.countDown()
        attach.join(5_000)
        assertEquals(listOf(b, a), synchronized(engine) { engine.toList() }) // B, then the stale A
        // An unforced repair finds B noted as sent and stays silent: the engine would stay on A.
        assertNull(pusher.push(force = false) { now -> synchronized(engine) { engine += now } })
        // The repair listen() posts is forced: the engine ends on B.
        pusher.push(force = true) { now -> synchronized(engine) { engine += now } }
        assertEquals(b, synchronized(engine) { engine.last() })
    }

    /** Ordinary callbacks send only real changes. */
    @Test fun onlyChangesAreSent() {
        var network = "wlan0" to 30
        val sent = mutableListOf<Pair<String, Int>>()
        val pusher = InterfacePush { network }
        pusher.push(force = false) { sent += it }
        pusher.push(force = false) { sent += it }
        network = "" to -1
        pusher.push(force = false) { sent += it }
        assertEquals(listOf("wlan0" to 30, "" to -1), sent)
    }

    /**
     * A provider was unreachable, so its retry waits up to five minutes, sooner on a network change. Another Wi-Fi is a
     * new network on the same wlan0 at the same index, which the engine isn't sent again (that would close every
     * connection), and is still a move. A link change on the same network, a loss and the engine's first interface are not.
     */
    @Test fun anotherNetworkOnTheSameInterfaceIsAMove() {
        val wlan = "wlan0" to 30
        var network = wlan
        val sent = mutableListOf<Pair<String, Int>>()
        val pusher = InterfacePush { network }
        pusher.push(force = true) { sent += it } // the engine attaches: forced, never a move
        // Another Wi-Fi: handle 101, then 102, on the same interface.
        val other = pusher.push(force = false) { sent += it }
        assertNull(other)
        assertTrue(netMoved(other, was = 101L, now = 102L))
        assertEquals(listOf(wlan), sent) // the engine keeps its connections
        // A link change on that network, or the same network seen again.
        assertFalse(netMoved(pusher.push(force = false) { sent += it }, was = null, now = null))
        assertFalse(netMoved(null, was = 102L, now = 102L))
        // Mobile data: another interface is a move whatever the handles.
        network = "rmnet0" to 12
        assertTrue(netMoved(pusher.push(force = false) { sent += it }, was = null, now = null))
        // Lost: nothing to try on.
        network = "" to -1
        assertFalse(netMoved(pusher.push(force = false) { sent += it }, was = null, now = null))
        // A network after none: the move is its interface, handed over once it is known.
        assertFalse(netMoved(null, was = null, now = 103L))
        network = wlan
        assertTrue(netMoved(pusher.push(force = false) { sent += it }, was = null, now = 103L))
        // The monitor hands each new network's handle and the one before it.
        val src = java.io.File("src/main/java/app/unbraid/vpn/Net.kt").readText()
        assertTrue(src.contains("val was = network; network = n;"))
        assertTrue(src.contains("push(was = was?.networkHandle, now = n.networkHandle)"))
        assertTrue(src.contains("if (!force && netMoved(sent, was, now)) onChange?.invoke()"))
    }
}
