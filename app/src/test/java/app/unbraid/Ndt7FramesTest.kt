package app.unbraid

import app.unbraid.stats.Ndt7
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException

class Ndt7FramesTest {
    private fun header(vararg b: Int) = Ndt7.header(DataInputStream(b.map { it.toByte() }.toByteArray().inputStream()))

    /** The opcode and the 7, 16 or 64-bit length of a server frame; a masked frame's key is skipped; the end of the stream is null. */
    @Test fun headersCarryOpcodeAndLength() {
        assertEquals(2 to 5L, header(0x82, 5))
        assertEquals(1 to 300L, header(0x81, 126, 0x01, 0x2c))
        assertEquals(2 to 70_000L, header(0x82, 127, 0, 0, 0, 0, 0, 1, 0x11, 0x70))
        assertEquals(1 to 3L, header(0x81, 0x83, 9, 9, 9, 9))
        assertNull(header())
    }

    /** The length comes from the server: text and control frames are held in memory, so they are capped far below data frames. */
    @Test fun oversizedFramesAreRefused() {
        assertEquals(2 to (1L shl 25), header(0x82, 127, 0, 0, 0, 0, 2, 0, 0, 0))
        assertEquals(1 to (1L shl 20), header(0x81, 127, 0, 0, 0, 0, 0, 0x10, 0, 0))
        listOf(
            intArrayOf(0x82, 127, 0, 0, 0, 0, 2, 0, 0, 1), // data over 32 MiB
            intArrayOf(0x81, 127, 0, 0, 0, 0, 0, 0x10, 0, 1), // text over 1 MiB
            intArrayOf(0x89, 127, 0, 0, 0, 0, 0, 0x10, 0, 1), // ping over 1 MiB
            intArrayOf(0x82, 127, 0x80, 0, 0, 0, 0, 0, 0, 0), // a negative 64-bit length
        ).forEach { b -> try { header(*b); fail("accepted ${b.toList()}") } catch (_: IOException) {} }
    }

    private fun sent(op: Int, payload: ByteArray, len: Int = payload.size) = ByteArrayOutputStream().also { Ndt7.frame(it, op, payload, len) }.toByteArray()

    /** Client frames are final and masked, with the length in the shortest form; control frames carry their payload under the mask. */
    @Test fun framesAreMaskedAndSized() {
        val ping = "hello".toByteArray()
        val f = sent(10, ping)
        assertEquals(0x8a, f[0].toInt() and 0xff); assertEquals(0x80 or 5, f[1].toInt() and 0xff)
        val mask = f.copyOfRange(2, 6)
        assertArrayEquals(ping, ByteArray(5) { (f[6 + it].toInt() xor mask[it % 4].toInt()).toByte() })
        assertEquals(6 + 5, f.size)

        val close = sent(8, ByteArray(0), 0)
        assertEquals(listOf(0x88, 0x80), close.take(2).map { it.toInt() and 0xff }); assertEquals(6, close.size)

        val mid = sent(2, ByteArray(300))
        assertEquals(listOf(0x82, 0x80 or 126, 1, 0x2c), mid.take(4).map { it.toInt() and 0xff }); assertEquals(4 + 4 + 300, mid.size)

        val big = sent(2, ByteArray(70_000))
        assertEquals(listOf(0x82, 0x80 or 127, 0, 0, 0, 0, 0, 1, 0x11, 0x70), big.take(10).map { it.toInt() and 0xff }); assertEquals(10 + 4 + 70_000, big.size)
    }

    /** Only the first [len] bytes of a buffer are sent: the upload reuses one buffer for growing messages. */
    @Test fun onlyLenBytesAreSent() = assertEquals(2 + 4 + 16, sent(2, ByteArray(1 shl 20), 16).size)
}
