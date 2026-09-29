package xyz.headsdown.core.keys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.keys.P256.toFixed32
import xyz.headsdown.core.keys.TestCrypto.hex
import xyz.headsdown.core.keys.TestCrypto.toHex
import java.math.BigInteger
import java.security.interfaces.ECPublicKey

class P256Test {

    // RFC 6979 §A.2.5 (ECDSA, P-256, SHA-256) deterministic test vectors.
    private val rfcUx = BigInteger("60FED4BA255A9D31C961EB74C6356D68C049B8923B61FA6CE669622E60F29FB6", 16)
    private val rfcUy = BigInteger("7903FE1008B8BC99A41AE9E95628BC64F2F1B20C2D7E9F5177A3C294D4462299", 16)
    private val sampleR = "EFD48B2AACB6A8FD1140DD9CD45E81D69D2C877B56AAF991C34D0EA84EAF3716"
    private val sampleS = "F7CB1C942D657C41D436C7A1B6E29F65F3E900DBB9AFF4064DC4AB2F843ACDA8" // high-S
    private val testR = "F1ABB023518351CD71D881567B1EA663ED3EFCF6C5132B354F28D3B0B7D38367"
    private val testS = "019F4113742A2B14BD25926B49C649155F267E60D3814B4C0CC84250E46F0083" // low-S

    private fun raw(r: String, s: String) = hex(r + s)

    private fun der(vararg parts: String) = hex(parts.joinToString(""))

    // ------------------------------------------------------------------ constants

    @Test
    fun `group order and half order match the P-256 spec`() {
        assertEquals("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", P256.N.toString(16))
        assertEquals("7fffffff800000007fffffffffffffffde737d56d38bcf4279dce5617e3192a8", P256.HALF_N.toString(16))
        assertEquals(P256.N, P256.HALF_N.shiftLeft(1).add(BigInteger.ONE))
    }

    // ------------------------------------------------------------------ DER -> raw

    @Test
    fun `derToRaw strips the sign pad from r and left-pads a short s`() {
        // r: 33 bytes (0x00 pad because the top bit of 0xEF is set); s: 31 bytes.
        // Content length = (2 + 33) + (2 + 31) = 68 = 0x44.
        val shortS = "7F4113742A2B14BD25926B49C649155F267E60D3814B4C0CC84250E46F0083"
        val sig = der("3044", "0221", "00", sampleR, "021F", shortS)
        val out = P256.derToRaw(sig)
        assertEquals(64, out.size)
        assertEquals(sampleR.lowercase(), out.copyOfRange(0, 32).toHex())
        assertEquals("00" + shortS.lowercase(), out.copyOfRange(32, 64).toHex())
    }

    @Test
    fun `derToRaw and rawToDer round-trip the RFC vector`() {
        val rawSig = raw(sampleR, sampleS)
        val derSig = P256.rawToDer(rawSig)
        // Both scalars have the top bit set, so both carry a 0x00 pad: 2 + 2*(2+33) = 72 bytes.
        assertEquals(72, derSig.size)
        assertArrayEquals(rawSig, P256.derToRaw(derSig))
    }

    @Test
    fun `derToRaw rejects malformed encodings`() {
        val good = P256.rawToDer(raw(testR, testS))
        val cases = mapOf(
            "trailing byte" to good + byteArrayOf(0),
            "wrong outer tag" to good.copyOf().also { it[0] = 0x31 },
            "length mismatch" to good.copyOf().also { it[1] = (it[1] + 1).toByte() },
            "truncated" to good.copyOf(good.size - 1),
            "long-form length" to der("3081", "44", "0220", testR, "0220", testS),
            "negative r" to der("3044", "0220", sampleR, "0220", testS),
            "non-minimal r" to der("3045", "0221", "00", testS, "0220", testS),
            "zero r" to der("3025", "020100", "0220", testS),
            "r equals n" to der("3045", "0221", "00", P256.N.toString(16), "0220", testS),
            "s equals n" to der("3045", "0220", testR.replaceFirst("F1", "71"), "0221", "00", P256.N.toString(16)),
            "INTEGER too long" to der("3046", "0222", "0000", testR, "0220", testS),
            "empty INTEGER" to der("3024", "0200", "0220", testS),
            "too short" to hex("300602010102"),
        )
        for ((name, bytes) in cases) {
            assertThrows(name, P256EncodingException::class.java) { P256.derToRaw(bytes) }
        }
    }

    // ------------------------------------------------------------------ low-S

    @Test
    fun `normalizeLowS maps s above n over 2 to n minus s and leaves r alone`() {
        val high = raw(sampleR, sampleS)
        assertFalse(P256.isLowS(high))
        val low = P256.normalizeLowS(high)
        assertTrue(P256.isLowS(low))
        assertArrayEquals(high.copyOfRange(0, 32), low.copyOfRange(0, 32))
        val expectedS = P256.N.subtract(BigInteger(sampleS, 16))
        assertEquals(expectedS, BigInteger(1, low.copyOfRange(32, 64)))
    }

    @Test
    fun `normalizeLowS boundaries`() {
        fun withS(s: BigInteger) = hex(testR) + s.toFixed32()
        fun sOf(sig: ByteArray) = BigInteger(1, sig.copyOfRange(32, 64))

        // s == n/2 is already low.
        assertEquals(P256.HALF_N, sOf(P256.normalizeLowS(withS(P256.HALF_N))))
        // s == n/2 + 1 is the smallest high value; n - s == n/2 because n is odd.
        assertEquals(P256.HALF_N, sOf(P256.normalizeLowS(withS(P256.HALF_N.add(BigInteger.ONE)))))
        // s == n - 1 -> 1.
        assertEquals(BigInteger.ONE, sOf(P256.normalizeLowS(withS(P256.N.subtract(BigInteger.ONE)))))
    }

    @Test
    fun `normalizeLowS is idempotent and returns a copy`() {
        val low = raw(testR, testS)
        val out = P256.normalizeLowS(low)
        assertArrayEquals(low, out)
        assertNotSame(low, out)
        val twice = P256.normalizeLowS(P256.normalizeLowS(raw(sampleR, sampleS)))
        assertArrayEquals(P256.normalizeLowS(raw(sampleR, sampleS)), twice)
    }

    @Test
    fun `raw helpers reject wrong lengths`() {
        assertThrows(P256EncodingException::class.java) { P256.isLowS(ByteArray(63)) }
        assertThrows(P256EncodingException::class.java) { P256.normalizeLowS(ByteArray(65)) }
        assertThrows(P256EncodingException::class.java) { P256.rawToDer(ByteArray(0)) }
    }

    // ------------------------------------------------------------------ against a real verifier

    @Test
    fun `RFC 6979 vectors verify before and after low-S normalization`() {
        val pub = TestCrypto.publicKey(rfcUx, rfcUy)
        val sample = "sample".toByteArray()
        val test = "test".toByteArray()

        val high = raw(sampleR, sampleS)
        assertTrue("RFC vector must verify as published", TestCrypto.verifyRaw(pub, sample, high))
        assertTrue("(r, n-s) must verify too", TestCrypto.verifyRaw(pub, sample, P256.normalizeLowS(high)))
        assertTrue(TestCrypto.verifyRaw(pub, test, raw(testR, testS)))

        // Negative controls: wrong message, flipped bit in r.
        assertFalse(TestCrypto.verifyRaw(pub, test, P256.normalizeLowS(high)))
        val tampered = P256.normalizeLowS(high).also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertFalse(TestCrypto.verifyRaw(pub, sample, tampered))
    }

    @Test
    fun `every keystore-style DER signature normalizes to a valid low-S signature`() {
        val keyPair = TestCrypto.newKeyPair(seed = 1234L)
        val signer = TestCrypto.derSigner(keyPair)
        var sawHighS = 0
        repeat(256) { i ->
            val message = "heartbeat-$i".toByteArray()
            val rawSig = P256.derToRaw(signer.signDer(message))
            if (!P256.isLowS(rawSig)) sawHighS++
            val low = P256.normalizeLowS(rawSig)
            assertTrue(P256.isLowS(low))
            assertTrue("sig $i must verify", TestCrypto.verifyRaw(keyPair.public, message, low))
        }
        // JCA does not normalize: roughly half of all signatures are high-S. P(no high-S) = 2^-256.
        assertTrue("expected some high-S signatures from JCA, saw $sawHighS", sawHighS > 0)
    }

    // ------------------------------------------------------------------ public keys

    @Test
    fun `compress the P-256 generator point`() {
        val gx = BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16)
        val gy = BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16)
        assertEquals(
            "036b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296",
            P256.compress(gx, gy).toHex(),
        )
        // -G has the even y and the 0x02 prefix.
        assertEquals(0x02, P256.compress(gx, P256.P.subtract(gy))[0].toInt())
    }

    @Test
    fun `compress and decompress round-trip, including the RFC key`() {
        val rfc = P256.compress(rfcUx, rfcUy)
        assertEquals(0x03, rfc[0].toInt()) // Uy ends in 0x99: odd
        assertEquals(rfcUx to rfcUy, P256.decompress(rfc))

        repeat(32) {
            val pub = TestCrypto.newKeyPair().public as ECPublicKey
            val compressed = P256.compress(pub)
            assertEquals(33, compressed.size)
            assertEquals(pub.w.affineX to pub.w.affineY, P256.decompress(compressed))
            val uncompressed = byteArrayOf(0x04) + pub.w.affineX.toFixed32() + pub.w.affineY.toFixed32()
            assertArrayEquals(compressed, P256.compressUncompressed(uncompressed))
        }
    }

    @Test
    fun `invalid points are rejected`() {
        assertThrows(P256EncodingException::class.java) { P256.compress(rfcUx, rfcUy.add(BigInteger.ONE)) }
        assertThrows(P256EncodingException::class.java) { P256.compress(P256.P, BigInteger.ONE) }
        val good = P256.compress(rfcUx, rfcUy)
        assertThrows(P256EncodingException::class.java) { P256.decompress(good.copyOf().also { it[0] = 0x04 }) }
        assertThrows(P256EncodingException::class.java) { P256.decompress(good.copyOf(32)) }
        // x = p is not a field element.
        assertThrows(P256EncodingException::class.java) {
            P256.decompress(byteArrayOf(0x02) + P256.P.toFixed32())
        }
        // Find an x with no square root on the curve: decompression must refuse it.
        var x = BigInteger.ONE
        while (true) {
            val candidate = byteArrayOf(0x02) + x.toFixed32()
            val onCurve = runCatching { P256.decompress(candidate) }.isSuccess
            if (!onCurve) break
            x = x.add(BigInteger.ONE)
        }
        assertThrows(P256EncodingException::class.java) { P256.decompress(byteArrayOf(0x02) + x.toFixed32()) }
        assertThrows(P256EncodingException::class.java) { P256.compressUncompressed(ByteArray(65)) }
    }
}
