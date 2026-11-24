package app.unbraid

import app.unbraid.vpn.Socks5Bridge
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class Socks5BridgeTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val connect = byteArrayOf(5, 1, 0, 3, 4, 116, 101, 115, 116, 0, 80)
    private val ok = byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)
    private val udpMagic = "sp.v2.udp-over-tcp.arpa".encodeToByteArray()
    private val udpConnect = byteArrayOf(5, 1, 0, 3, udpMagic.size.toByte()) + udpMagic + byteArrayOf(0, 0)
    private fun Socket.read(count: Int) = ByteArray(count).also { DataInputStream(getInputStream()).readFully(it) }
    private fun Socket.write(bytes: ByteArray) = getOutputStream().write(bytes)
    private fun server() = ServerSocket(0, 4, loopback).apply { soTimeout = 2_000 }
    private fun client(bridge: Socks5Bridge) = Socket(loopback, bridge.port).apply { soTimeout = 3_000 }
    private fun udp(port: Int = 0) = DatagramSocket(InetSocketAddress(loopback, port)).apply { soTimeout = 2_000 }
    private fun portBytes(port: Int) = byteArrayOf((port ushr 8).toByte(), port.toByte())
    private fun ByteArray.portAtEnd() = ((this[size - 2].toInt() and 255) shl 8) or (last().toInt() and 255)
    private fun DatagramSocket.send(bytes: ByteArray, port: Int) =
        send(DatagramPacket(bytes, bytes.size, loopback, port))
    private fun DatagramSocket.receive() = DatagramPacket(ByteArray(1024), 1024).also(::receive)

    private fun upstreamUdpHandshake(peer: Socket, passwordAccepted: Boolean = true): Int {
        peer.soTimeout = 2_000
        assertArrayEquals(byteArrayOf(5, 1, 2), peer.read(3))
        peer.write(byteArrayOf(5, 2))
        assertArrayEquals(byteArrayOf(1, 1, 117, 1, 112), peer.read(5))
        peer.write(byteArrayOf(1, if (passwordAccepted) 0 else 1))
        if (!passwordAccepted) return 0
        val request = peer.read(10)
        assertArrayEquals(byteArrayOf(5, 3, 0, 1) + loopback.address, request.copyOf(8))
        return request.portAtEnd().also { assertTrue(it > 0) }
    }

    private fun authenticate(client: Socket, bridge: Socks5Bridge, password: String = bridge.password): Int {
        client.write(byteArrayOf(5, 2, 0, 2))
        assertArrayEquals(byteArrayOf(5, 2), client.read(2))
        client.write(byteArrayOf(1, bridge.username.length.toByte()) + bridge.username.toByteArray() +
            byteArrayOf(password.length.toByte()) + password.toByteArray())
        return client.read(2)[1].toInt()
    }

    @Test fun verifiedSameConnectionRelaysDataInBothDirectionsIncludingAfterHalfClose() {
        server().use { target ->
            val observedPorts = mutableListOf<Int>()
            val attempts = AtomicInteger()
            Socks5Bridge.start("127.0.0.1", target.localPort, "user", "pass") {
                synchronized(observedPorts) { observedPorts.add(it.localPort) }
                attempts.incrementAndGet() >= 3
            }.use { bridge ->
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val remote = executor.submit {
                        target.accept().use { socket ->
                            socket.soTimeout = 3_000
                            assertArrayEquals(byteArrayOf(5, 1, 2), socket.read(3))
                            assertTrue(synchronized(observedPorts) { observedPorts.all { it == socket.port } })
                            socket.write(byteArrayOf(5, 2))
                            assertArrayEquals(byteArrayOf(1, 4) + "user".toByteArray() + byteArrayOf(4) + "pass".toByteArray(), socket.read(11))
                            socket.write(byteArrayOf(1, 0))
                            assertArrayEquals(connect, socket.read(connect.size))
                            socket.write(ok)
                            assertEquals("hello", socket.getInputStream().readBytes().decodeToString())
                            socket.write("reply".toByteArray())
                        }
                    }
                    client(bridge).use { socket ->
                        assertEquals(0, authenticate(socket, bridge))
                        socket.write(connect)
                        assertArrayEquals(ok, socket.read(ok.size))
                        socket.write("hello".toByteArray())
                        socket.shutdownOutput()
                        assertEquals("reply", socket.getInputStream().readBytes().decodeToString())
                    }
                    remote.get(3, TimeUnit.SECONDS)
                    assertTrue(attempts.get() >= 5)
                } finally { executor.shutdownNow() }
            }
        }
    }

    @Test fun unverifiedListenerGetsNoGreetingNoCredentialsAndNoDestination() {
        server().use { target ->
            val retries = AtomicInteger()
            Socks5Bridge.start("127.0.0.1", target.localPort, "user", "pass") { retries.incrementAndGet(); false }.use { bridge ->
                client(bridge).use { socket ->
                    assertEquals(0, authenticate(socket, bridge))
                    socket.write(connect)
                    target.accept().use { other ->
                        other.soTimeout = 2_000
                        assertEquals(-1, other.getInputStream().read())
                    }
                    assertEquals(1, socket.read(10)[1].toInt())
                    assertTrue(retries.get() > 1)
                }
            }
        }
    }

    @Test fun rejectsWrongEnginePasswordAndUnsupportedCommandsBeforeContactingProxy() {
        server().use { target ->
            Socks5Bridge.start("127.0.0.1", target.localPort, "", "") { true }.use { bridge ->
                client(bridge).use { assertEquals(1, authenticate(it, bridge, "wrong")) }
                for (command in listOf(2, 3)) {
                    client(bridge).use {
                        assertEquals(0, authenticate(it, bridge))
                        it.write(connect.copyOf().apply { this[1] = command.toByte() })
                        assertEquals(7, it.read(10)[1].toInt())
                    }
                }
                client(bridge).use {
                    assertEquals(0, authenticate(it, bridge))
                    it.write(udpConnect)
                    assertEquals(7, it.read(10)[1].toInt())
                }
                target.soTimeout = 100
                assertThrows(SocketTimeoutException::class.java) { target.accept() }
            }
        }
    }

    @Test fun requiredLoginCannotBeDowngradedAndWrongPasswordNeverSendsADestination() {
        server().use { target ->
            Socks5Bridge.start("127.0.0.1", target.localPort, "u", "p") { true }.use { bridge ->
                for (method in listOf(0, 2)) {
                    client(bridge).use { socket ->
                        assertEquals(0, authenticate(socket, bridge))
                        socket.write(connect)
                        target.accept().use { peer ->
                            peer.soTimeout = 2_000
                            assertArrayEquals(byteArrayOf(5, 1, 2), peer.read(3))
                            peer.write(byteArrayOf(5, method.toByte()))
                            if (method == 2) {
                                assertArrayEquals(byteArrayOf(1, 1, 117, 1, 112), peer.read(5))
                                peer.write(byteArrayOf(1, 1))
                            }
                            assertEquals(-1, peer.getInputStream().read())
                        }
                        assertEquals(1, socket.read(10)[1].toInt())
                    }
                }
            }
        }
    }

    @Test fun ownerInvalidationDuringNegotiationBlocksCredentials() {
        server().use { target ->
            val current = java.util.concurrent.atomic.AtomicBoolean(true)
            Socks5Bridge.start("127.0.0.1", target.localPort, "user", "pass") { current.get() }.use { bridge ->
                client(bridge).use { socket ->
                    assertEquals(0, authenticate(socket, bridge))
                    socket.write(connect)
                    target.accept().use { peer ->
                        peer.soTimeout = 2_000
                        assertArrayEquals(byteArrayOf(5, 1, 2), peer.read(3))
                        current.set(false)
                        peer.write(byteArrayOf(5, 2))
                        assertEquals(-1, peer.getInputStream().read())
                    }
                    assertEquals(1, socket.read(10)[1].toInt())
                }
            }
        }
    }

    @Test fun revocationClosesActiveAnonymousTunnelAndKeepsThePortUntilClosed() {
        server().use { target ->
            Socks5Bridge.start("127.0.0.1", target.localPort, "", "") { true }.use { bridge ->
                client(bridge).use { socket ->
                    assertEquals(0, authenticate(socket, bridge))
                    socket.write(connect)
                    target.accept().use { peer ->
                        peer.soTimeout = 2_000
                        assertArrayEquals(byteArrayOf(5, 1, 0), peer.read(3))
                        peer.write(byteArrayOf(5, 0))
                        assertArrayEquals(connect, peer.read(connect.size))
                        peer.write(ok)
                        assertArrayEquals(ok, socket.read(ok.size))
                        bridge.revoke()
                        client(bridge).use { assertEquals(-1, it.getInputStream().read()) }
                        assertThrows(java.net.BindException::class.java) { ServerSocket(bridge.port, 4, loopback).close() }
                        assertEquals(-1, peer.getInputStream().read())
                        assertEquals(-1, socket.getInputStream().read())
                    }
                }
            }
        }
    }

    @Test fun udpOptInEchoesTaggedDatagramsThroughAnAuthenticatedSamePortProxyAndRevokes() {
        for (connected in listOf(true, false)) {
            server().use { target ->
                udp(target.localPort).use { relay ->
                    udp().use { echo ->
                        val relayVerified = AtomicInteger()
                        val bridge = Socks5Bridge.start(
                            "127.0.0.1", target.localPort, "u", "p", udpEnabled = true,
                            udpRelayOwnerVerified = { local, remote ->
                                relayVerified.incrementAndGet()
                                local.address == loopback && remote == relay.localSocketAddress
                            },
                        ) { true }
                        bridge.use {
                            val executor = Executors.newCachedThreadPool()
                            try {
                                val address = loopback.address + portBytes(echo.localPort)
                                val destination = byteArrayOf(1) + address
                                val header = ByteArray(3) + destination
                                val tag = "tagged-udp-echo".toByteArray()
                                val packet = header + tag
                                val bounced = executor.submit {
                                    val request = echo.receive()
                                    assertArrayEquals(tag, request.data.copyOf(request.length))
                                    echo.send(DatagramPacket(request.data, request.length, request.socketAddress))
                                }
                                val peerClosed = executor.submit {
                                    target.accept().use { peer ->
                                        val sourcePort = upstreamUdpHandshake(peer)
                                        peer.write(ok.copyOf(8) + portBytes(relay.localPort))
                                        val request = relay.receive()
                                        assertEquals(sourcePort, request.port)
                                        assertArrayEquals(packet, request.data.copyOf(request.length))
                                        relay.send(tag, echo.localPort)
                                        val response = relay.receive()
                                        assertEquals(echo.localPort, response.port)
                                        relay.send(packet.copyOf().apply { this[2] = 1 }, sourcePort)
                                        val responseHeader = if (connected) {
                                            val wrongTag = "discard-this".toByteArray()
                                            relay.send(header.copyOf().apply { this[4] = 126 } + wrongTag, sourcePort)
                                            relay.send(header.copyOf(8) + portBytes(1) + wrongTag, sourcePort)
                                            byteArrayOf(0, 0, 0, 4) + ByteArray(10) + byteArrayOf(-1, -1) + address
                                        } else header
                                        relay.send(responseHeader + response.data.copyOf(response.length), sourcePort)
                                        assertEquals(-1, peer.getInputStream().read())
                                    }
                                }
                                client(bridge).use { control ->
                                    assertEquals(0, authenticate(control, bridge))
                                    control.write(udpConnect)
                                    assertArrayEquals(ok, control.read(10))
                                    val frameAddress = if (connected) byteArrayOf() else byteArrayOf(0) + address
                                    control.write(byteArrayOf(if (connected) 1 else 0) + destination)
                                    control.write(frameAddress + portBytes(65535) + ByteArray(65535))
                                    val frame = frameAddress + portBytes(tag.size) + tag
                                    control.write(frame)
                                    assertArrayEquals(frame, control.read(frame.size))
                                    assertTrue(relayVerified.get() >= 3)
                                    bridge.revoke()
                                    peerClosed.get(3, TimeUnit.SECONDS)
                                    bounced.get(3, TimeUnit.SECONDS)
                                    assertEquals(-1, control.getInputStream().read())
                                    assertThrows(java.net.BindException::class.java) { ServerSocket(bridge.port, 4, loopback).close() }
                                    relay.soTimeout = 100
                                    assertThrows(SocketTimeoutException::class.java) { relay.receive() }
                                }
                            } finally { executor.shutdownNow() }
                        }
                    }
                }
            }
        }
    }

    /** Every packet is checked: an owner that changes on a running association gets nothing more, and gets nothing in. */
    @Test fun udpOwnerChangingOnARunningAssociationStopsTheNextPacketEachWay() {
        for (outbound in listOf(true, false)) server().use { target ->
            udp(target.localPort).use { relay ->
                val allowed = java.util.concurrent.atomic.AtomicBoolean(true)
                Socks5Bridge.start("127.0.0.1", target.localPort, "u", "p", udpEnabled = true,
                    udpRelayOwnerVerified = { _, _ -> allowed.get() }) { true }.use { bridge ->
                    client(bridge).use { control ->
                        assertEquals(0, authenticate(control, bridge))
                        control.write(udpConnect)
                        target.accept().use { peer ->
                            val sourcePort = upstreamUdpHandshake(peer)
                            peer.write(ok.copyOf(8) + portBytes(relay.localPort))
                            assertArrayEquals(ok, control.read(10))
                            val destination = byteArrayOf(1, 192.toByte(), 0, 2, 9) + portBytes(99)
                            control.write(byteArrayOf(1) + destination)
                            val frame = portBytes(2) + "ok".toByteArray()
                            val reply = ByteArray(3) + destination + "ok".toByteArray()
                            repeat(2) {
                                control.write(frame)
                                assertEquals(reply.size, relay.receive().length)
                                relay.send(reply, sourcePort)
                                assertArrayEquals(frame, control.read(frame.size))
                            }
                            allowed.set(false)
                            if (outbound) control.write(frame + frame) else relay.send(reply, sourcePort)
                            assertEquals(-1, control.getInputStream().read())
                            assertEquals(-1, peer.getInputStream().read())
                            relay.soTimeout = 100
                            assertThrows(SocketTimeoutException::class.java) { relay.receive() }
                        }
                    }
                }
            }
        }
    }

    @Test fun udpRejectsBadProxyLoginInvalidRelayAndWrongRelayOwner() {
        server().use { target ->
            udp().use { relay ->
                val verified = AtomicInteger()
                Socks5Bridge.start(
                    "127.0.0.1", target.localPort, "u", "p", udpEnabled = true,
                    udpRelayOwnerVerified = { _, _ -> verified.incrementAndGet(); false },
                ) { true }.use { bridge ->
                    val unsupported = byteArrayOf(5, 0, 0, 1, 192.toByte(), 0, 2, 5) + portBytes(relay.localPort)
                    val replies = listOf(null, unsupported, ok, ok.copyOf(8) + portBytes(relay.localPort))
                    for (response in replies) {
                        client(bridge).use { control ->
                            assertEquals(0, authenticate(control, bridge))
                            control.write(udpConnect)
                            target.accept().use { peer ->
                                upstreamUdpHandshake(peer, passwordAccepted = response != null)
                                response?.let { peer.write(it) }
                                assertEquals(-1, peer.getInputStream().read())
                            }
                            assertEquals(1, control.read(10)[1].toInt())
                        }
                    }
                    assertEquals(1, verified.get())
                    relay.soTimeout = 100
                    assertThrows(SocketTimeoutException::class.java) { relay.receive() }
                }
            }
        }
    }

    @Test fun udpFailsClosedWhenCompanionIsOffline() {
        server().use { target ->
            val port = target.localPort
            target.close()
            Socks5Bridge.start(
                "127.0.0.1", port, null, null, udpEnabled = true,
                udpRelayOwnerVerified = { _, _ -> true },
            ) { true }.use { bridge ->
                client(bridge).use { control ->
                    assertEquals(0, authenticate(control, bridge))
                    control.write(udpConnect)
                    assertEquals(1, control.read(10)[1].toInt())
                }
            }
        }
    }

    private inner class CapacityProxy : AutoCloseable {
        val listener = ServerSocket(0, 256, loopback)
        val relay = udp()
        val peers = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        val relayPorts = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val executor = Executors.newCachedThreadPool()
        val pauseOwner = java.util.concurrent.atomic.AtomicBoolean(false)
        val ownerEntered = java.util.concurrent.CountDownLatch(1)
        val ownerResume = java.util.concurrent.CountDownLatch(1)
        val replacementPort = AtomicInteger()
        val clock = java.util.concurrent.atomic.AtomicLong(1)
        fun later(seconds: Long) { clock.addAndGet(TimeUnit.SECONDS.toNanos(seconds)) }
        val bridge = Socks5Bridge.start(
            "127.0.0.1", listener.localPort, null, null, udpEnabled = true, now = { clock.get() },
            udpRelayOwnerVerified = { local, remote ->
                if (pauseOwner.get()) {
                    replacementPort.set(local.port)
                    ownerEntered.countDown()
                    ownerResume.await(5, TimeUnit.SECONDS)
                }
                remote == relay.localSocketAddress
            },
        ) { true }
        val clients = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        val destination = byteArrayOf(1) + loopback.address + portBytes(12345)
        val frame = byteArrayOf(0, 1, 42)
        val started = executor.submit {
            while (!listener.isClosed) {
                val peer = try { listener.accept() } catch (_: Exception) { break }
                peers.add(peer)
                try {
                    assertArrayEquals(byteArrayOf(5, 1, 0), peer.read(3))
                    peer.write(byteArrayOf(5, 0))
                    val request = peer.read(4)
                    val type = request[3].toInt()
                    val address = when (type) {
                        1 -> peer.read(6)
                        3 -> peer.read(peer.getInputStream().read() + 2)
                        4 -> peer.read(18)
                        else -> error("unexpected address")
                    }
                    if (request[1].toInt() == 3) relayPorts.add(address.portAtEnd())
                    peer.write(if (request[1].toInt() == 3) ok.copyOf(8) + portBytes(relay.localPort) else ok)
                } catch (_: Exception) { peer.close() }
            }
        }
        val echo = executor.submit {
            while (!relay.isClosed) {
                try {
                    val packet = relay.receive()
                    relay.send(DatagramPacket(packet.data, packet.length, packet.socketAddress))
                } catch (_: SocketTimeoutException) {
                } catch (_: java.net.SocketException) { break }
            }
        }

        fun open(udpRequest: Boolean): Pair<Socket, Int> {
            val socket = client(bridge)
            clients.add(socket)
            assertEquals(0, authenticate(socket, bridge))
            socket.write(if (udpRequest) udpConnect else connect)
            val status = socket.read(10)[1].toInt()
            if (status == 0 && udpRequest) socket.write(byteArrayOf(1) + destination)
            return socket to status
        }

        fun pulse(socket: Socket) {
            socket.write(frame)
            assertArrayEquals(frame, socket.read(frame.size))
        }

        override fun close() {
            ownerResume.countDown()
            bridge.close()
            clients.forEach { runCatching { it.close() } }
            listener.close()
            relay.close()
            synchronized(peers) { peers.forEach { runCatching { it.close() } } }
            executor.shutdownNow()
            started.get(5, TimeUnit.SECONDS)
            echo.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun capacityEvictsOldestIdleAndRefusesWhenAllAreBusy() {
        CapacityProxy().use { proxy ->
            val all = (0 until 128).map { proxy.open(true).also { assertEquals(0, it.second) }.first }
            all.forEach(proxy::pulse)
            assertEquals(1, proxy.open(true).second) // each was used just now
            proxy.later(4)
            assertEquals(1, proxy.open(true).second)
            // The one that goes is the longest idle, not the first that came.
            val idle = all[70]
            (all - idle).forEach(proxy::pulse)
            proxy.later(2)
            val replacement = proxy.open(true)
            assertEquals(0, replacement.second)
            proxy.pulse(replacement.first)
            assertEquals(-1, idle.getInputStream().read())
            (all - idle).forEach(proxy::pulse) // every other still works
            assertEquals(1, proxy.open(true).second)
        }
    }

    @Test fun independentLimitsAndSilentHandshakes() {
        CapacityProxy().use { proxy ->
            val udpClients = (0 until 128).map { proxy.open(true).also { assertEquals(0, it.second) }.first }
            val tcpClients = (0 until 256).map { proxy.open(false).also { assertEquals(0, it.second) }.first }
            assertEquals(1, proxy.open(false).second)
            // Close one UDP control and keep retrying until its slot has actually been returned.
            udpClients.first().close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var reopened: Pair<Socket, Int>
            do { reopened = proxy.open(true) } while (reopened.second != 0 && System.nanoTime() < deadline)
            assertEquals(0, reopened.second)
            proxy.pulse(reopened.first)
            tcpClients.forEach { it.close() }
        }
        CapacityProxy().use { proxy ->
            val silent = (0 until 100).map {
                client(proxy.bridge).also {
                    it.write(byteArrayOf(5, 1, 2))
                    assertArrayEquals(byteArrayOf(5, 2), it.read(2))
                }
            }
            try {
                assertEquals(0, proxy.open(true).second)
                assertEquals(0, proxy.open(false).second)
            } finally { silent.forEach { it.close() } }
        }
    }

    @Test fun slotsReturnRepeatedlyAndRevokeClosesEvictedAndReplacementSockets() {
        CapacityProxy().use { proxy ->
            repeat(300) {
                val udp = proxy.open(true)
                assertEquals(0, udp.second)
                proxy.pulse(udp.first)
                udp.first.close()
                val tcp = proxy.open(false)
                assertEquals(0, tcp.second)
                tcp.first.shutdownOutput()
                synchronized(proxy.peers) { proxy.peers.last().close() }
                assertEquals(-1, tcp.first.getInputStream().read())
                tcp.first.close()
            }
        }
        CapacityProxy().use { proxy ->
            val associations = (0 until 128).map { proxy.open(true).also { assertEquals(0, it.second) }.first }
            val evictedPort = proxy.relayPorts[proxy.relayPorts.size - 128]
            val time = System.nanoTime()
            proxy.later(6)
            proxy.pauseOwner.set(true)
            val replacement = proxy.executor.submit { runCatching { proxy.open(true) } }
            assertTrue(proxy.ownerEntered.await(3, TimeUnit.SECONDS))
            udp(evictedPort).close()
            proxy.bridge.revoke()
            udp(proxy.replacementPort.get()).close()
            proxy.ownerResume.countDown()
            replacement.get(5, TimeUnit.SECONDS)
            associations.forEach { assertEquals(-1, it.getInputStream().read()) }
            synchronized(proxy.peers) {
                proxy.peers.filterNot { it.isClosed }.forEach { it.soTimeout = 3000; assertEquals(-1, it.getInputStream().read()) }
            }
        }
    }

}
