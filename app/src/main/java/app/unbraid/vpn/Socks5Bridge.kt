package app.unbraid.vpn

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/** An authenticated local SOCKS endpoint for the engine. The upstream socket must
 * belong to the selected app before we send it a SOCKS greeting, credentials or a destination. */
class Socks5Bridge private constructor(
    private val target: InetSocketAddress,
    private val login: ByteArray?,
    private val secret: ByteArray?,
    forbiddenListenerPorts: Set<Int>,
    private val udpRelayOwnerVerified: ((InetSocketAddress, InetSocketAddress) -> Boolean)?,
    private val now: () -> Long,
    private val peerOwnerVerified: (Socket) -> Boolean,
) : Closeable {
    companion object {
        private const val HANDSHAKE_MS = 5_000
        private const val HANDSHAKES = 256
        private const val TCP_STREAMS = 256
        private const val UDP_ASSOCIATIONS = 128
        private const val UDP_EVICT_NANOS = 5_000_000_000L
        private const val UDP_RECEIVE_MS = 2_000
        private const val UDP_IDLE_NANOS = 120_000_000_000L
        private const val UDP_MAX_BYTES = 65_507
        private const val UDP_MAGIC = "sp.v2.udp-over-tcp.arpa"
        private const val LEGACY_UDP_MAGIC = "sp.udp-over-tcp.arpa"
        private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private val random = SecureRandom()

        fun start(
            host: String, port: Int, username: String?, password: String?,
            forbiddenListenerPorts: Set<Int> = emptySet(), udpEnabled: Boolean = false,
            udpRelayOwnerVerified: ((InetSocketAddress, InetSocketAddress) -> Boolean)? = null,
            now: () -> Long = System::nanoTime,
            peerOwnerVerified: (Socket) -> Boolean,
        ): Socks5Bridge {
            val address = when (host) {
                "127.0.0.1" -> InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
                "::1", "0:0:0:0:0:0:0:1" -> InetAddress.getByAddress(ByteArray(16).apply { this[15] = 1 })
                else -> throw IllegalArgumentException("SOCKS proxy must use a loopback address")
            }
            require(port in 1..65535)
            val user = username?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
            val pass = password?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
            require((user == null && pass == null) || (user?.size in 1..255 && pass?.size in 1..255))
            require(!udpEnabled || udpRelayOwnerVerified != null)
            return Socks5Bridge(
                InetSocketAddress(address, port), user, pass, forbiddenListenerPorts + port,
                udpRelayOwnerVerified.takeIf { udpEnabled }, now, peerOwnerVerified,
            )
        }

        private fun bindListener(excluded: Set<Int>): ServerSocket {
            val reserved = mutableListOf<ServerSocket>()
            try {
                repeat(16) {
                    val listener = ServerSocket(0, 128, loopback)
                    if (listener.localPort !in excluded) return listener
                    reserved.add(listener)
                }
                throw IOException("No available bridge port")
            } finally { reserved.forEach { it.close() } }
        }

        // A local client sending one byte at a time must still release its handshake slot.
        private fun handshakeInput(socket: Socket, deadline: Long): DataInputStream {
            val timed = object : FilterInputStream(socket.getInputStream()) {
                fun remaining() {
                    val nanos = deadline - System.nanoTime()
                    if (nanos <= 0) throw SocketTimeoutException("SOCKS handshake timed out")
                    socket.soTimeout = (nanos / 1_000_000 + 1).toInt()
                }
                override fun read(): Int { remaining(); return super.read() }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    remaining()
                    return super.read(buffer, offset, length)
                }
            }
            return DataInputStream(timed)
        }

        private fun token(): String = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        private fun DataInputStream.bytes(count: Int) = ByteArray(count).also(::readFully)

        /** Read the ATYP-dependent address and the port verbatim. Domain names belong at the proxy. */
        private fun address(input: DataInputStream, type: Int): ByteArray = when (type) {
            1 -> input.bytes(4 + 2)
            4 -> input.bytes(16 + 2)
            3 -> {
                val length = input.readUnsignedByte()
                if (length == 0) throw IOException("Empty SOCKS hostname")
                byteArrayOf(length.toByte()) + input.bytes(length + 2)
            }
            else -> throw IOException("Unknown SOCKS address type")
        }
    }

    val username = token()
    val password = token()
    private val listener = bindListener(forbiddenListenerPorts)
    val port: Int = listener.localPort
    private val lock = Any()
    private val sendLock = Any()
    private var closed = false
    private var revoked = false
    private val sockets = mutableSetOf<Closeable>()
    private val handshakes = Semaphore(HANDSHAKES)
    private val tcpSlots = Semaphore(TCP_STREAMS)
    private val associations = mutableSetOf<UdpSlot>()

    private inner class UdpSlot(val client: Socket) {
        val sockets = mutableSetOf<Closeable>(client)
        val activity = AtomicLong(now())
    }
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "SOCKS bridge stream").apply { isDaemon = true } }

    init {
        Thread(::accept, "SOCKS bridge listener").apply { isDaemon = true; start() }
    }

    private fun register(socket: Closeable) = synchronized(lock) { !closed && !revoked && sockets.add(socket) }

    private fun drop(socket: Closeable) {
        runCatching { socket.close() }
        synchronized(lock) { sockets.remove(socket) }
    }

    private fun accept() {
        while (!listener.isClosed) {
            val client = try {
                listener.accept()
            } catch (_: IOException) {
                // A transient failure, e.g. descriptor pressure, must never release the port
                // while an existing engine still has its address and login.
                if (listener.isClosed) return
                Thread.sleep(250)
                continue
            }
            if (!handshakes.tryAcquire()) { client.close(); continue }
            if (!register(client)) { client.close(); handshakes.release(); continue }
            try {
                workers.execute {
                    try { serve(client) } catch (_: Exception) {
                        // Closing the client also signals a failed or incomplete handshake.
                    } finally { drop(client) }
                }
            } catch (_: RejectedExecutionException) { drop(client); handshakes.release(); return
            } catch (_: OutOfMemoryError) { drop(client); handshakes.release() } // no thread left: this one is refused
        }
    }

    private fun authenticate(client: Socket, input: DataInputStream): Boolean {
        val output = client.getOutputStream()
        if (input.readUnsignedByte() != 5) return false
        val methods = input.bytes(input.readUnsignedByte())
        if (2.toByte() !in methods) { output.write(byteArrayOf(5, -1)); return false }
        output.write(byteArrayOf(5, 2))
        if (input.readUnsignedByte() != 1) return false
        val user = input.bytes(input.readUnsignedByte())
        val pass = input.bytes(input.readUnsignedByte())
        val ok = MessageDigest.isEqual(user, username.toByteArray()) and MessageDigest.isEqual(pass, password.toByteArray())
        output.write(byteArrayOf(1, if (ok) 0 else 1))
        return ok
    }

    private fun reply(client: Socket, status: Int) =
        client.getOutputStream().write(byteArrayOf(5, status.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))

    private fun serve(client: Socket) {
        var inHandshake = true
        try {
            val deadline = System.nanoTime() + HANDSHAKE_MS * 1_000_000L
            val input = handshakeInput(client, deadline)
            if (!authenticate(client, input) || input.readUnsignedByte() != 5) return
            val command = input.readUnsignedByte()
            if (input.readUnsignedByte() != 0) return
            if (command != 1) { reply(client, 7); return }
            val type = input.readUnsignedByte()
            if (type !in listOf(1, 3, 4)) { reply(client, 8); return }
            val destination = byteArrayOf(type.toByte()) + address(input, type)
            val host = destination.domain()
            if (host.equals(UDP_MAGIC, ignoreCase = true) || host.equals(LEGACY_UDP_MAGIC, ignoreCase = true)) {
                if (udpRelayOwnerVerified == null || host != UDP_MAGIC || destination.portAt(destination.size - 2) != 0) {
                    reply(client, 7)
                    return
                }
                handshakes.release()
                inHandshake = false
                val slot = UdpSlot(client)
                val evicted: List<Closeable>?
                // A packet being sent for the one that goes is out before its slot is.
                synchronized(sendLock) {
                    synchronized(lock) {
                        if (closed || revoked) return
                        val oldest = associations.minByOrNull { it.activity.get() }
                        if (associations.size >= UDP_ASSOCIATIONS &&
                            (oldest == null || now() - oldest.activity.get() < UDP_EVICT_NANOS)) {
                            evicted = null
                        } else {
                            evicted = if (associations.size >= UDP_ASSOCIATIONS) {
                                associations.remove(oldest!!)
                                oldest.sockets.toList()
                            } else emptyList()
                            associations.add(slot)
                        }
                    }
                }
                if (evicted == null) { reply(client, 1); return }
                evicted.forEach { runCatching { it.close() } }
                try { associate(client, deadline, slot) } finally { synchronized(lock) { associations.remove(slot) } }
                return
            }
            handshakes.release()
            inHandshake = false
            if (!tcpSlots.tryAcquire()) { reply(client, 1); return }
            try {
                val upstream = Socket()
                if (!register(upstream)) { upstream.close(); return }
                try {
                    val response = try {
                        upstream.connect(target, HANDSHAKE_MS)
                        verifyAcceptedPeer(upstream)
                        connectUpstream(upstream, byteArrayOf(5, 1, 0) + destination, deadline)
                    } catch (_: Exception) { reply(client, 1); return }
                    client.getOutputStream().write(response)
                    if (response[1].toInt() != 0) return
                    upstream.soTimeout = 0
                    client.soTimeout = 0
                    // Both directions can half-close independently. Hold the slot until both are done.
                    val downstream = workers.submit { pump(upstream, client) }
                    pump(client, upstream)
                    downstream.get()
                } finally { drop(upstream) }
            } finally { tcpSlots.release() }
        } finally { if (inHandshake) handshakes.release() }
    }

    private fun ByteArray.portAt(offset: Int) =
        ((this[offset].toInt() and 255) shl 8) or (this[offset + 1].toInt() and 255)

    private fun ByteArray.domain(): String? =
        if (first() == 3.toByte()) copyOfRange(2, size - 2).decodeToString().trimEnd('.') else null

    private fun ByteArray.numericAddress(): InetAddress? =
        if (first() == 3.toByte()) null else InetAddress.getByAddress(copyOfRange(1, size - 2))

    private fun sameUdpSource(expected: ByteArray, actual: ByteArray): Boolean {
        if (expected.portAt(expected.size - 2) != actual.portAt(actual.size - 2)) return false
        val hostname = expected.domain()
        return if (hostname != null) hostname.equals(actual.domain(), ignoreCase = true)
        else expected.numericAddress() == actual.numericAddress()
    }

    private fun udpEndpoint(port: Int) = loopback.address + byteArrayOf((port ushr 8).toByte(), port.toByte())

    private fun relayEndpoint(response: ByteArray, upstream: Socket, localPort: Int): InetSocketAddress {
        if (response[3] != 1.toByte()) throw IOException("SOCKS relay must use IPv4 loopback")
        val port = response.portAt(8)
        if (port == 0 || port == localPort) throw IOException("Invalid SOCKS relay port")
        val reported = InetAddress.getByAddress(response.copyOfRange(4, 8))
        val address = if (reported.isAnyLocalAddress) upstream.inetAddress else reported
        if (address != loopback) throw IOException("SOCKS relay must use IPv4 loopback")
        return InetSocketAddress(address, port)
    }

    private fun register(socket: Closeable, slot: UdpSlot) = synchronized(lock) {
        !closed && !revoked && slot in associations && sockets.add(socket).also { if (it) slot.sockets.add(socket) }
    }

    private fun associate(client: Socket, deadline: Long, slot: UdpSlot) {
        val upstream = Socket()
        if (!register(upstream, slot)) { upstream.close(); return }
        try {
            val relay = DatagramSocket(InetSocketAddress(loopback, 0))
            if (!register(relay, slot)) { relay.close(); return }
            try {
                val peer = try {
                    upstream.connect(target, HANDSHAKE_MS)
                    verifyAcceptedPeer(upstream)
                    val request = byteArrayOf(5, 3, 0, 1) + udpEndpoint(relay.localPort)
                    val response = connectUpstream(upstream, request, deadline)
                    if (response[1] != 0.toByte()) { reply(client, response[1].toInt()); return }
                    relayEndpoint(response, upstream, relay.localPort).also {
                        relay.connect(it)
                        if (!ownedRelay(upstream, relay, it)) throw IOException("Unverified SOCKS relay")
                    }
                } catch (_: Exception) { reply(client, 1); return }
                upstream.soTimeout = 0
                // The engine sends the UoT v2 request with its first frame, after CONNECT succeeds.
                reply(client, 0)
                val header = handshakeInput(client, System.nanoTime() + HANDSHAKE_MS * 1_000_000L)
                val connected = when (header.readUnsignedByte()) {
                    0 -> false
                    1 -> true
                    else -> throw IOException("Invalid UDP mode")
                }
                val destination = readUdpAddress(header, packetMode = false)
                client.soTimeout = 0
                Association(client, upstream, relay, peer, destination.takeIf { connected }, slot).run()
            } finally { drop(relay) }
        } finally { drop(upstream) }
    }

    private fun readUdpAddress(input: DataInputStream, packetMode: Boolean): ByteArray {
        val wireType = input.readUnsignedByte()
        val socksType = if (packetMode) when (wireType) {
            0 -> 1
            1 -> 4
            2 -> 3
            else -> throw IOException("Invalid UDP packet address type")
        } else wireType
        return byteArrayOf(socksType.toByte()) + address(input, socksType)
    }

    private fun ownedRelay(upstream: Socket, from: DatagramSocket, peer: InetSocketAddress) =
        !upstream.isClosed &&
            udpRelayOwnerVerified?.invoke(from.localSocketAddress as InetSocketAddress, peer) == true

    private inner class Association(
        private val client: Socket,
        private val upstream: Socket,
        private val relay: DatagramSocket,
        private val peer: InetSocketAddress,
        private val fixedDestination: ByteArray?,
        private val slot: UdpSlot,
    ) {
        private val activity = slot.activity

        fun run() {
            relay.soTimeout = UDP_RECEIVE_MS
            val tasks = mutableListOf<Future<*>>()
            try {
                tasks += workers.submit { whileActive { upstream.getInputStream().read() } }
                tasks += workers.submit { whileActive(::receiveFromRelay) }
                sendToRelay()
            } finally {
                stop()
                tasks.forEach { runCatching { it.get() } }
            }
        }

        private fun stop() {
            relay.close()
            runCatching { upstream.close() }
            runCatching { client.close() }
        }

        private fun whileActive(work: () -> Unit) {
            try { work() } catch (_: Exception) {
                // An ended control, idle relay or failed owner check ends the association.
            } finally { stop() }
        }

        private fun sendToRelay() {
            val input = DataInputStream(client.getInputStream())
            while (!relay.isClosed) {
                val destination = fixedDestination ?: readUdpAddress(input, packetMode = true)
                val payload = input.bytes(input.readUnsignedShort())
                if (payload.size + destination.size + 3 > UDP_MAX_BYTES || !validDestination(destination)) continue
                if (!ownedRelay(upstream, relay, peer)) throw IOException("Unverified SOCKS relay")
                val packet = ByteArray(3) + destination + payload
                synchronized(sendLock) {
                    synchronized(lock) {
                        if (revoked || closed || slot !in associations) throw EOFException()
                        activity.set(now())
                    }
                    relay.send(DatagramPacket(packet, packet.size, peer))
                }
            }
        }

        private fun receiveFromRelay() {
            val buffer = ByteArray(UDP_MAX_BYTES + 1)
            val packet = DatagramPacket(buffer, buffer.size)
            val output = client.getOutputStream()
            while (now() - activity.get() < UDP_IDLE_NANOS) {
                packet.length = buffer.size
                try { relay.receive(packet) } catch (_: SocketTimeoutException) { continue }
                if (packet.length > UDP_MAX_BYTES) continue
                val input = DataInputStream(ByteArrayInputStream(packet.data, 0, packet.length))
                val source = try {
                    // Unsupported fragments and malformed SOCKS UDP headers are dropped.
                    if (input.readUnsignedShort() != 0 || input.readUnsignedByte() != 0) continue
                    readUdpAddress(input, packetMode = false)
                } catch (_: IOException) { continue }
                if (!validDestination(source)) continue
                if (fixedDestination != null && !sameUdpSource(fixedDestination, source)) continue
                if (!ownedRelay(upstream, relay, peer)) throw IOException("Unverified SOCKS relay")
                synchronized(lock) {
                    if (revoked || closed || slot !in associations) throw EOFException()
                    activity.set(now())
                }
                val length = input.available()
                val size = byteArrayOf((length ushr 8).toByte(), length.toByte())
                if (fixedDestination == null) {
                    source[0] = when (source[0].toInt()) {
                        1 -> 0
                        4 -> 1
                        else -> 2
                    }
                    output.write(source + size + input.bytes(length))
                } else {
                    output.write(size + input.bytes(length))
                }
            }
        }

        private fun validDestination(address: ByteArray): Boolean {
            val port = address.portAt(address.size - 2)
            if (port == 0) return false
            val host = address.domain()
            if (host.equals(UDP_MAGIC, ignoreCase = true) || host.equals(LEGACY_UDP_MAGIC, ignoreCase = true)) return false
            return address.numericAddress()?.isLoopbackAddress != true ||
                (port != relay.localPort && port != peer.port)
        }
    }

    /** Android may only resolve the reverse socket tuple after accept. Never reconnect between
     * owner verification and credential use. A failed check never authorizes a write. */
    private fun verifyAcceptedPeer(socket: Socket) {
        repeat(16) { attempt ->
            if (socket.isClosed) throw EOFException()
            if (peerOwnerVerified(socket)) return
            if (attempt < 15) Thread.sleep(20)
        }
        throw IOException("Unverified local proxy")
    }

    private fun connectUpstream(socket: Socket, destination: ByteArray, deadline: Long): ByteArray {
        val output = socket.getOutputStream()
        val input = handshakeInput(socket, deadline)
        val method = if (login == null) 0 else 2
        output.write(byteArrayOf(5, 1, method.toByte()))
        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != method) throw IOException("SOCKS method refused")
        if (login != null && secret != null) {
            if (!peerOwnerVerified(socket)) throw IOException("Unverified local proxy")
            output.write(byteArrayOf(1, login.size.toByte()) + login + byteArrayOf(secret.size.toByte()) + secret)
            if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) throw IOException("SOCKS login refused")
        }
        if (!peerOwnerVerified(socket)) throw IOException("Unverified local proxy")
        output.write(destination)
        val version = input.readUnsignedByte()
        val status = input.readUnsignedByte()
        if (version != 5 || status !in 0..8 || input.readUnsignedByte() != 0) throw IOException("Invalid SOCKS response")
        val type = input.readUnsignedByte()
        return byteArrayOf(5, status.toByte(), 0, type.toByte()) + address(input, type)
    }

    private fun pump(from: Socket, to: Socket) {
        try {
            from.getInputStream().copyTo(to.getOutputStream())
            to.shutdownOutput()
        } catch (_: IOException) {
            // A broken direction must wake the other one even if it is waiting for a read.
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }

    /** Keep the port bound while an old engine might still be dialing it. Revoke is permanent. */
    fun revoke() {
        val open = synchronized(sendLock) {
            synchronized(lock) {
                revoked = true
                sockets.toList()
            }
        }
        open.forEach { runCatching { it.close() } }
    }

    override fun close() {
        val open = synchronized(sendLock) {
            synchronized(lock) {
                if (closed) return
                closed = true
                sockets.toList()
            }
        }
        runCatching { listener.close() }
        open.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}
