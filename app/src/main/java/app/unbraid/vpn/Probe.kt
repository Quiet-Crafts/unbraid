package app.unbraid.vpn

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The app's own connections out through one chosen exit ([Compiler.PROBE]): an HTTP CONNECT to the engine's loopback
 * proxy, whose user name picks the tunnel, then TLS with the system's certificate and host-name checks.
 */
object Probe {
    class Conn(val socket: SSLSocket) {
        val input: InputStream = BufferedInputStream(socket.inputStream, 1 shl 16)
        val output = socket.outputStream
        fun close() = runCatching { socket.close() }.let {}
    }

    /**
     * A TLS connection to [host] through the tunnel [tag]; throws if the VPN isn't on or the exit doesn't answer.
     * Closing [raw] from another thread ends a blocked attempt at once.
     */
    fun open(tag: String, host: String, port: Int = 443, timeoutMs: Int = 10_000, raw: Socket = Socket()): Conn {
        val (proxyPort, password) = UnbraidVpn.probe ?: error("Connect first")
        try {
            raw.connect(InetSocketAddress("127.0.0.1", proxyPort), timeoutMs)
            raw.soTimeout = timeoutMs
            val auth = Base64.getEncoder().encodeToString("$tag:$password".toByteArray())
            raw.outputStream.write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\nProxy-Authorization: Basic $auth\r\n\r\n".toByteArray())
            val status = head(raw.inputStream).firstOrNull().orEmpty()
            if (status.split(' ').getOrNull(1) != "200") error("The exit didn't connect")
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
            tls.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)) { tls.close(); error("Certificate doesn't match $host") }
            return Conn(tls)
        } catch (e: Exception) { runCatching { raw.close() }; throw e }
    }

    /** A small HTTPS GET through [tag]; the body as text, at most [max] bytes (the server is trusted no further). */
    fun get(tag: String, host: String, path: String, max: Int = 256 * 1024): String {
        val c = open(tag, host)
        try {
            c.output.write("GET $path HTTP/1.0\r\nHost: $host\r\nUser-Agent: unbraid\r\nAccept: application/json\r\n\r\n".toByteArray())
            val status = head(c.input).firstOrNull().orEmpty()
            if (status.split(' ').getOrNull(1) != "200") error("HTTP ${status.substringAfter(' ')}")
            val body = c.input.readNBytes(max + 1)
            if (body.size > max) throw java.io.IOException("Response too large")
            return body.decodeToString()
        } finally { c.close() }
    }

    /**
     * One TCP handshake with [host] through [tag], in ms: the engine's SOCKS5 side answers only once its own connection
     * to the server is up, so the wait is one round trip through the tunnel (Ookla's "ping"). No data, no TLS.
     */
    fun handshakeMs(tag: String, host: String, port: Int = 443): Long {
        val (proxyPort, password) = UnbraidVpn.probe ?: error("Connect first")
        Socket().use { s ->
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", proxyPort), 5_000); s.soTimeout = 5_000
            val o = s.outputStream; val i = java.io.DataInputStream(s.inputStream)
            o.write(byteArrayOf(5, 1, 2)) // SOCKS5, user name and password (RFC 1929)
            if (i.readUnsignedByte() != 5 || i.readUnsignedByte() != 2) error("Proxy refused")
            val u = tag.toByteArray(); val p = password.toByteArray()
            o.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
            if (i.readUnsignedByte() != 1 || i.readUnsignedByte() != 0) error("Proxy refused")
            val h = host.toByteArray()
            val start = System.nanoTime()
            o.write(byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h + byteArrayOf((port shr 8).toByte(), port.toByte()))
            i.readUnsignedByte()
            if (i.readUnsignedByte() != 0) error("The server didn't answer")
            return (System.nanoTime() - start + 500_000) / 1_000_000
        }
    }

    /** Reads an HTTP head byte by byte (never past it), as its lines. */
    fun head(input: InputStream): List<String> {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0 || sb.length > 16_384) break
            sb.append(b.toChar())
        }
        return sb.split("\r\n").filter { it.isNotEmpty() }
    }
}
