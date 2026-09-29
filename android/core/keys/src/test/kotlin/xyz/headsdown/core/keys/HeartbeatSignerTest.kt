package xyz.headsdown.core.keys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.keys.P256.toFixed32
import java.math.BigInteger
import java.security.interfaces.ECPublicKey

class HeartbeatSignerTest {

    private val keyPair = TestCrypto.newKeyPair(seed = 42L)
    private val compressed = P256.compress(keyPair.public as ECPublicKey)
    private val signer = HeartbeatSigner(TestCrypto.derSigner(keyPair), compressed)

    private fun heartbeat(counter: ULong) = HeartbeatMessage(
        programId = ByteArray(32) { 7 },
        rig = ByteArray(32) { 9 },
        oreRoundId = 1_000uL,
        counter = counter,
        state = RigSignalState.DOWN,
        shiftId = 5uL,
        leaseEnd = 1_002uL,
    )

    @Test
    fun `signs the raw message into a precompile-ready low-S signature`() {
        repeat(64) { i ->
            val hb = heartbeat(counter = i.toULong() + 1uL)
            val signed = signer.sign(hb)
            assertArrayEquals(hb.encode(), signed.message)
            assertEquals(64, signed.signature.size)
            assertTrue(P256.isLowS(signed.signature))
            assertArrayEquals(compressed, signed.publicKey)
            // Verifies over the RAW 101-byte message (the precompile hashes it with SHA-256).
            assertTrue(TestCrypto.verifyRaw(keyPair.public, signed.message, signed.signature))
            assertEquals(hb, signed.decoded)
        }
    }

    @Test
    fun `a signature does not transfer to a different counter or round`() {
        val signed = signer.sign(heartbeat(counter = 1uL))
        val replayed = heartbeat(counter = 2uL).encode()
        assertFalse(TestCrypto.verifyRaw(keyPair.public, replayed, signed.signature))
    }

    @Test
    fun `rejects a public key that is not a compressed P-256 point`() {
        val notOnCurve = compressed.copyOf().also { it[0] = 0x05 }
        assertThrows(P256EncodingException::class.java) {
            HeartbeatSigner(TestCrypto.derSigner(keyPair), notOnCurve)
        }
    }

    @Test
    fun `fails closed when the keystore returns malformed DER`() {
        val broken = HeartbeatSigner({ byteArrayOf(0x30, 0x00) }, compressed)
        assertThrows(P256EncodingException::class.java) { broken.sign(heartbeat(1uL)) }
    }

    @Test
    fun `SignedHeartbeat refuses high-S signatures`() {
        val hb = heartbeat(1uL).encode()
        val raw = P256.derToRaw(TestCrypto.derSigner(keyPair).signDer(hb))
        val high = if (P256.isLowS(raw)) {
            // Flip to the high-S twin: (r, n - s).
            val s = BigInteger(1, raw.copyOfRange(32, 64))
            raw.copyOfRange(0, 32) + P256.N.subtract(s).toFixed32()
        } else raw
        assertFalse(P256.isLowS(high))
        assertThrows(IllegalArgumentException::class.java) { SignedHeartbeat(hb, high, compressed) }
    }
}
