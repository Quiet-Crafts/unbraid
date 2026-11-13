package app.unbraid

import app.unbraid.vpn.InterfacePush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
