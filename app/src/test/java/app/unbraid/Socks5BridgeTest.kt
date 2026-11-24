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
}
