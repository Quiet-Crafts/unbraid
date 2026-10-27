package app.unbraid.stats

import android.os.SystemClock
import app.unbraid.BuildConfig
import app.unbraid.data.NordApi
import app.unbraid.vpn.Probe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

/**
 * M-Lab's ndt7 speed test (measurementlab.net; protocol: github.com/m-lab/ndt-server, spec/ndt7-protocol.md) through
 * one exit: locate a server, measure [ping], then ten seconds of download and ten of upload over a WebSocket.
 */
object Ndt7 {
    /** [others]: Locate's other candidates, tried in turn when this one turns the test away. */
    data class Server(val machine: String, val city: String, val country: String, val download: String, val upload: String, val others: List<Server> = emptyList())

    /** Locate answered 204: M-Lab limits tests per address, and this exit's (often a VPN server's, shared) is used up. */
    class Limited : Exception()

    /** The test server turned the WebSocket away; another of Locate's candidates may not. */
    class Refused : java.io.IOException("The test server refused the test")

    private const val SECONDS = 10
    private const val LOCATE = "locate.measurementlab.net"

    /**
     * The M-Lab server nearest the exit's own address, inside [country] when given; null if M-Lab has none there.
     * Throws [Limited] when M-Lab is rate-limiting the exit's address.
     */
    fun locate(tag: String, country: String?): Server? {
        val q = CLIENT + (country?.let { "&country=$it&strict=true" } ?: "")
        val body = try { Probe.get(tag, LOCATE, "/v2/nearest/ndt/ndt7?$q") }
        catch (e: IllegalStateException) {
            val m = e.message.orEmpty()
            if (m.startsWith("HTTP 204")) throw Limited()
            if (country != null && m.startsWith("HTTP")) return null else throw e
        }
        val all = NordApi.json.parseToJsonElement(body).jsonObject["results"]?.jsonArray?.map { it.jsonObject }.orEmpty().map { r ->
            val loc = r["location"]?.jsonObject
            val urls = r.getValue("urls").jsonObject
            Server(r.str("machine"), loc?.str("city").orEmpty(), loc?.str("country").orEmpty(), urls.str("wss:///ndt/v7/download"), urls.str("wss:///ndt/v7/upload"))
        }
        return all.firstOrNull()?.copy(others = all.drop(1))
    }

    /** Who is testing, kept by the server with the result; a combined run adds how many exits shared the line. */
    private val CLIENT = "client_name=unbraid&client_version=${BuildConfig.VERSION_NAME}"

    /** Ping in ms: the median of five, and the lowest and highest. */
    data class Ping(val median: Int, val low: Int, val high: Int)

    /**
     * Ookla-style ping: five TCP handshakes with the test server through the exit, after one untimed connection that
     * resolves its name. Measured by the phone: many M-Lab sites sit behind a cloud proxy, so the server's own
     * round-trip figure only sees the proxy next to it. The median shrugs off one slow handshake, unlike the mean.
     */
    fun ping(tag: String, server: Server): Ping {
        val host = URI(server.download).host
        Probe.handshakeMs(tag, host)
        val ms = List(5) { Probe.handshakeMs(tag, host).toInt().coerceAtLeast(1) }.sorted()
        return Ping(ms[2], ms.first(), ms.last())
    }

    private fun JsonObject.str(k: String) = get(k)?.jsonPrimitive?.content.orEmpty()

    /**
     * Receives until the server ends the test; [rate] gets bits/s every quarter second. [sample] gets the time
     * (elapsedRealtime ms) and running count at most every 20 ms and at the end ([extra] URL parameters tag a combined run).
     */
    fun download(tag: String, url: String, stop: () -> Boolean, extra: String = "", sample: (Long, Long) -> Unit = { _, _ -> }, rate: (Long) -> Unit): Long {
        val c = socket(tag, url, extra)
        val input = DataInputStream(c.input)
        val scratch = ByteArray(1 shl 16)
        val start = SystemClock.elapsedRealtime()
        var got = 0L; var told = start; var noted = start
        val count = { n: Long -> got += n; val now = SystemClock.elapsedRealtime(); if (now - noted >= 20) { noted = now; sample(now, got) } }
        sample(start, 0)
        try {
            while (!stop() && SystemClock.elapsedRealtime() - start < (SECONDS + 3) * 1000) {
                val (op, len) = header(input) ?: break
                when (op) {
                    8 -> break
                    1 -> { count(len); input.skipBytes(len.toInt()) } // the server's own reports; the phone counts what it receives
                    9 -> { val p = ByteArray(len.toInt()).also(input::readFully); frame(c.output, 10, p, p.size) }
                    else -> { var left = len; while (left > 0 && !stop()) { val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt()); if (n < 0) break; left -= n; count(n.toLong()) } }
                }
                val now = SystemClock.elapsedRealtime()
                if (now - told >= 250) { told = now; rate(got * 8000 / (now - start).coerceAtLeast(1)) }
            }
            sample(SystemClock.elapsedRealtime(), got)
            return got * 8000 / (SystemClock.elapsedRealtime() - start).coerceAtLeast(1)
        } finally { c.close(); live -= c }
    }

    /**
     * Sends for ten seconds; the rate is what the server says it received (its TCPInfo), not what left the phone's
     * buffers. The live figure falls back to bytes sent until the server's first report. [sample] gets the server's
     * received count as each report arrives, timed by the server's own clock from the connection's start.
     */
    fun upload(tag: String, url: String, stop: () -> Boolean, extra: String = "", sample: (Long, Long) -> Unit = { _, _ -> }, rate: (Long) -> Unit): Long {
        val c = socket(tag, url, extra)
        val opened = SystemClock.elapsedRealtime()
        sample(opened, 0)
        val input = DataInputStream(c.input)
        val server = java.util.concurrent.atomic.AtomicLong(-1)
        // Only the sending loop writes: the reader leaves the server's pings here for it, so neither ever waits on the other.
        val pong = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
        // The server reports what it has received a few times a second, as text messages; read them alongside.
        val reader = Thread {
            runCatching {
                while (true) {
                    val (op, len) = header(input) ?: break
                    if (op == 0 || op == 2) { skip(input, len); continue } // data isn't expected while uploading: never buffered
                    val p = ByteArray(len.toInt()).also(input::readFully)
                    when (op) {
                        8 -> break
                        9 -> pong.set(p)
                        1 -> p.decodeToString().substringAfter("\"TCPInfo\"", "").let { t ->
                            val got = BYTES_RECEIVED.find(t)?.groupValues?.get(1)?.toLong(); val us = ELAPSED.find(t)?.groupValues?.get(1)?.toLong()
                            if (got != null && us != null && us > 0) { server.set(got * 8_000_000 / us); sample(opened + us / 1000, got) }
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        // Random bytes need no masking work: masking random data with any key leaves it just as random.
        val data = Random.nextBytes(1 shl 20)
        val start = SystemClock.elapsedRealtime()
        var size = 1 shl 13; var sent = 0L; var told = start
        try {
            while (!stop() && SystemClock.elapsedRealtime() - start < SECONDS * 1000) {
                pong.getAndSet(null)?.let { frame(c.output, 10, it, it.size) }
                frame(c.output, 2, data, size)
                sent += size
                if (size < data.size && size < sent / 16) size *= 2 // the spec's message scaling
                val now = SystemClock.elapsedRealtime()
                if (now - told >= 250) { told = now; rate(server.get().takeIf { it > 0 } ?: (sent * 8000 / (now - start).coerceAtLeast(1))) }
            }
            runCatching { frame(c.output, 8, data, 0) }
            reader.join(1500)
            // What left the phone may still sit in buffers: without the server's own count there is no honest result.
            return server.get().takeIf { it > 0 } ?: throw java.io.IOException("The test server didn't report the upload")
        } finally { c.close(); live -= c }
    }

    private val BYTES_RECEIVED = Regex("\"BytesReceived\":(\\d+)")
    private val ELAPSED = Regex("\"ElapsedTime\":(\\d+)")

    /** The tests' open sockets, so a cancel can close them under a blocked read or write. */
    private val live = java.util.concurrent.ConcurrentHashMap.newKeySet<Probe.Conn>()

    /** Ends whatever a test is doing on the network now. */
    fun abort() = live.forEach { it.close() }

    /** Opens the test's WebSocket (RFC 6455) through the exit and checks the server accepted it. */
    private fun socket(tag: String, url: String, extra: String): Probe.Conn {
        val u = URI(url)
        val c = Probe.open(tag, u.host, if (u.port > 0) u.port else 443, 15_000).also { live += it }
        try {
            val key = Base64.getEncoder().encodeToString(Random.nextBytes(16))
            c.output.write(("GET ${u.rawPath}?${listOfNotNull(u.rawQuery, CLIENT, extra.ifEmpty { null }).joinToString("&")} HTTP/1.1\r\nHost: ${u.host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: net.measurementlab.ndt.v7\r\nUser-Agent: unbraid/${BuildConfig.VERSION_NAME}\r\n\r\n").toByteArray())
            val head = Probe.head(c.input)
            val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            if (head.firstOrNull()?.split(' ')?.getOrNull(1) != "101" || head.none { it.startsWith("Sec-WebSocket-Accept:", true) && it.substringAfter(':').trim() == accept })
                throw Refused()
            return c
        } catch (e: Exception) { c.close(); live -= c; throw e }
    }

    /**
     * A frame's opcode and length (servers never mask theirs); null at the end of the stream. The length comes from the
     * server, so it is bounded: data frames are streamed past, but text and control frames are read into memory.
     */
    internal fun header(i: DataInputStream): Pair<Int, Long>? {
        val b0 = i.read(); if (b0 < 0) return null
        val b1 = i.readUnsignedByte()
        val len = when (val l = b1 and 0x7f) { 126 -> i.readUnsignedShort().toLong(); 127 -> i.readLong(); else -> l.toLong() }
        if (b1 and 0x80 != 0) i.skipBytes(4)
        val op = b0 and 0x0f
        if (len < 0 || len > (if (op == 2 || op == 0) MAX_DATA else MAX_MESSAGE)) throw java.io.IOException("Bad frame from the test server")
        return op to len
    }

    /** Reads past [len] bytes in small pieces (InputStream.skipNBytes needs Android 14). */
    private fun skip(input: java.io.InputStream, len: Long) {
        val scratch = ByteArray(16_384); var left = len
        while (left > 0) { val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt()); if (n < 0) break; left -= n }
    }

    private const val MAX_MESSAGE = 1L shl 20 // measurement messages are a few hundred bytes
    private const val MAX_DATA = 1L shl 25    // ndt7 data frames are at most 16 MiB

    /** One final client frame, masked as the spec requires; the key is random, the payload sent as it is (see upload). */
    internal fun frame(o: OutputStream, op: Int, payload: ByteArray, len: Int) {
        val h = java.io.ByteArrayOutputStream(14)
        h.write(0x80 or op)
        when {
            len < 126 -> h.write(0x80 or len)
            len < 65536 -> { h.write(0x80 or 126); h.write(len shr 8); h.write(len and 0xff) }
            else -> { h.write(0x80 or 127); for (s in 56 downTo 0 step 8) h.write(((len.toLong() shr s) and 0xff).toInt()) }
        }
        val mask = Random.nextBytes(4)
        h.write(mask)
        o.write(h.toByteArray())
        if (op == 2) o.write(payload, 0, len)
        else o.write(ByteArray(len) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }) // pongs echo real bytes
        o.flush()
    }
}
