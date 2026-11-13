package app.unbraid.data

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** scrypt (RFC 7914), memory-hard: Android has no Argon2 or scrypt of its own, and a library would be far larger than this. */
internal object Scrypt {
    fun derive(pass: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, len: Int): ByteArray {
        require(n > 1 && n and (n - 1) == 0 && r > 0 && p > 0)
        val b = pbkdf2(pass, salt, p * 128 * r)
        val x = IntArray(32 * r)
        val y = IntArray(32 * r)
        val v = IntArray(32 * r * n)
        try {
            for (i in 0 until p) smix(b, i * 128 * r, r, n, v, x, y)
            return pbkdf2(pass, b, len)
        } finally { b.fill(0); v.fill(0); x.fill(0); y.fill(0) }
    }

    /** PBKDF2-HMAC-SHA256 with one iteration, as scrypt uses it. */
    private fun pbkdf2(pass: ByteArray, salt: ByteArray, len: Int): ByteArray {
        // HMAC pads a short key with zeros, so an empty one is the same as a single zero byte (which the API accepts).
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(pass.takeIf { it.isNotEmpty() } ?: ByteArray(1), "HmacSHA256")) }
        val out = ByteArray(len)
        var i = 1
        while ((i - 1) * 32 < len) {
            mac.update(salt); mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            val t = mac.doFinal()
            t.copyInto(out, (i - 1) * 32, 0, minOf(32, len - (i - 1) * 32))
            i++
        }
        return out
    }

    private fun smix(b: ByteArray, off: Int, r: Int, n: Int, v: IntArray, x: IntArray, y: IntArray) {
        val w = 32 * r
        for (k in 0 until w) x[k] = (b[off + 4 * k].toInt() and 0xff) or ((b[off + 4 * k + 1].toInt() and 0xff) shl 8) or
            ((b[off + 4 * k + 2].toInt() and 0xff) shl 16) or ((b[off + 4 * k + 3].toInt() and 0xff) shl 24)
        val s = IntArray(16)
        val t = IntArray(16)
        for (i in 0 until n) { x.copyInto(v, i * w); blockMix(x, y, r, t, s) }
        for (i in 0 until n) {
            val j = x[(2 * r - 1) * 16] and (n - 1)
            for (k in 0 until w) x[k] = x[k] xor v[j * w + k]
            blockMix(x, y, r, t, s)
        }
        for (k in 0 until w) for (q in 0 until 4) b[off + 4 * k + q] = (x[k] ushr (8 * q)).toByte()
        s.fill(0); t.fill(0)
    }

    private fun blockMix(b: IntArray, y: IntArray, r: Int, x: IntArray, t: IntArray) {
        b.copyInto(x, 0, (2 * r - 1) * 16, 2 * r * 16)
        for (i in 0 until 2 * r) {
            for (k in 0 until 16) x[k] = x[k] xor b[i * 16 + k]
            salsa(x, t)
            x.copyInto(y, i * 16)
        }
        for (i in 0 until r) { y.copyInto(b, i * 16, 2 * i * 16, 2 * i * 16 + 16); y.copyInto(b, (r + i) * 16, (2 * i + 1) * 16, (2 * i + 2) * 16) }
    }

    /** Salsa20/8 core on [b], with [s] as scratch. */
    private fun salsa(b: IntArray, s: IntArray) {
        b.copyInto(s)
        repeat(4) {
            s[4] = s[4] xor (s[0] + s[12]).rotateLeft(7); s[8] = s[8] xor (s[4] + s[0]).rotateLeft(9)
            s[12] = s[12] xor (s[8] + s[4]).rotateLeft(13); s[0] = s[0] xor (s[12] + s[8]).rotateLeft(18)
            s[9] = s[9] xor (s[5] + s[1]).rotateLeft(7); s[13] = s[13] xor (s[9] + s[5]).rotateLeft(9)
            s[1] = s[1] xor (s[13] + s[9]).rotateLeft(13); s[5] = s[5] xor (s[1] + s[13]).rotateLeft(18)
            s[14] = s[14] xor (s[10] + s[6]).rotateLeft(7); s[2] = s[2] xor (s[14] + s[10]).rotateLeft(9)
            s[6] = s[6] xor (s[2] + s[14]).rotateLeft(13); s[10] = s[10] xor (s[6] + s[2]).rotateLeft(18)
            s[3] = s[3] xor (s[15] + s[11]).rotateLeft(7); s[7] = s[7] xor (s[3] + s[15]).rotateLeft(9)
            s[11] = s[11] xor (s[7] + s[3]).rotateLeft(13); s[15] = s[15] xor (s[11] + s[7]).rotateLeft(18)
            s[1] = s[1] xor (s[0] + s[3]).rotateLeft(7); s[2] = s[2] xor (s[1] + s[0]).rotateLeft(9)
            s[3] = s[3] xor (s[2] + s[1]).rotateLeft(13); s[0] = s[0] xor (s[3] + s[2]).rotateLeft(18)
            s[6] = s[6] xor (s[5] + s[4]).rotateLeft(7); s[7] = s[7] xor (s[6] + s[5]).rotateLeft(9)
            s[4] = s[4] xor (s[7] + s[6]).rotateLeft(13); s[5] = s[5] xor (s[4] + s[7]).rotateLeft(18)
            s[11] = s[11] xor (s[10] + s[9]).rotateLeft(7); s[8] = s[8] xor (s[11] + s[10]).rotateLeft(9)
            s[9] = s[9] xor (s[8] + s[11]).rotateLeft(13); s[10] = s[10] xor (s[9] + s[8]).rotateLeft(18)
            s[12] = s[12] xor (s[15] + s[14]).rotateLeft(7); s[13] = s[13] xor (s[12] + s[15]).rotateLeft(9)
            s[14] = s[14] xor (s[13] + s[12]).rotateLeft(13); s[15] = s[15] xor (s[14] + s[13]).rotateLeft(18)
        }
        for (i in 0 until 16) b[i] += s[i]
    }
}
