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

/** In-memory [CounterStore] that records every write and can be told to fail. */
class RecordingCounterStore(initial: ULong = 0uL) : CounterStore {
    var value: ULong = initial
    val writes = mutableListOf<ULong>()
    var failWrites = false

    override fun load(): ULong = value

    override fun store(value: ULong): Boolean {
        if (failWrites) return false
        this.value = value
        writes += value
        return true
    }
}

class RigCounterTest {

    @Test
    fun `next persists before returning and strictly increases`() {
        val store = RecordingCounterStore()
        val counter = RigCounter(store)
        val values = List(5) { counter.next() }
        assertEquals(listOf(1uL, 2uL, 3uL, 4uL, 5uL), values)
        assertEquals("every value was durable before it was handed out", values, store.writes)
    }

    @Test
    fun `a failed write hands out nothing`() {
        val store = RecordingCounterStore(initial = 9uL)
        val counter = RigCounter(store)
        store.failWrites = true
        assertThrows(IllegalStateException::class.java) { counter.next() }
        store.failWrites = false
        assertEquals("the unpersisted value is never reused or skipped past", 10uL, counter.next())
    }

    @Test
    fun `raiseFloor lifts to the on-chain counter and never lowers`() {
        val store = RecordingCounterStore(initial = 3uL)
        val counter = RigCounter(store)
        counter.raiseFloor(onChainHbCounter = 100uL)
        assertEquals(101uL, counter.next())
        counter.raiseFloor(onChainHbCounter = 50uL) // stale chain read: must not go backwards
        assertEquals(102uL, counter.next())
        counter.raiseFloor(onChainHbCounter = 102uL)
        assertEquals(103uL, counter.next())
    }

    @Test
    fun `the u64 counter never wraps`() {
        val counter = RigCounter(RecordingCounterStore(initial = ULong.MAX_VALUE - 1uL))
        assertEquals(ULong.MAX_VALUE, counter.next())
        assertThrows(IllegalStateException::class.java) { counter.next() }
    }
}

class RigMessageSignerTest {

    private val keyPair = TestCrypto.newKeyPair(seed = 42L)
    private val compressed = P256.compress(keyPair.public as ECPublicKey)
    private val store = RecordingCounterStore()
    private val signer = RigMessageSigner(TestCrypto.derSigner(keyPair), compressed, RigCounter(store))
    private val programId = ByteArray(32) { 7 }
    private val rig = ByteArray(32) { 9 }
    private val plan = ShiftPlan(530_000_000uL, 1_000_000uL, 4, 0, 1, 0, 1_790_000_000L, 1_790_028_800L)

    @Test
    fun `heartbeats sign the 32-byte digest into precompile-ready low-S signatures`() {
        repeat(64) { i ->
            val signed = signer.heartbeat(programId, rig, shiftId = 5uL, roundId = 1_000uL + i.toULong(), leaseRounds = 1)
            assertEquals(32, signed.message.size)
            assertArrayEquals(signed.payload.digest(), signed.message)
            assertEquals(64, signed.signature.size)
            assertTrue(P256.isLowS(signed.signature))
            assertArrayEquals(compressed, signed.publicKey)
            // The precompile verifies ECDSA-P256 over SHA-256 of the 32-byte message.
            assertTrue(TestCrypto.verifyRaw(keyPair.public, signed.message, signed.signature))
            // ...and not over the raw preimage (the old format).
            assertFalse(TestCrypto.verifyRaw(keyPair.public, signed.payload.preimage(), signed.signature))
        }
    }

    @Test
    fun `one counter is shared across every kind and persisted before signing`() {
        val hb = signer.heartbeat(programId, rig, 5uL, 100uL, 1)
        val brk = signer.shiftSignal(programId, rig, RigMessageKind.BREAK, 5uL, ShiftEndReason.PICKUP)
        val pln = signer.plan(programId, rig, plan)
        val frz = signer.shiftSignal(programId, rig, RigMessageKind.FREEZE, 5uL, ShiftEndReason.FREEZE)
        assertEquals(listOf(1uL, 2uL, 3uL, 4uL), listOf(hb, brk, pln, frz).map { it.payload.counter })
        assertEquals(listOf(1uL, 2uL, 3uL, 4uL), store.writes)
        for (m in listOf(hb, brk, pln, frz)) assertTrue(TestCrypto.verifyRaw(keyPair.public, m.message, m.signature))
    }

    @Test
    fun `a signature does not transfer to another counter, round or kind`() {
        val signed = signer.heartbeat(programId, rig, 5uL, 100uL, 1)
        val other = listOf(
            HeartbeatPreimage(programId, rig, signed.payload.counter + 1uL, 5uL, 100uL, 1),
            HeartbeatPreimage(programId, rig, signed.payload.counter, 5uL, 101uL, 1),
            ShiftSignalPreimage(programId, rig, RigMessageKind.BREAK, signed.payload.counter, 5uL, ShiftEndReason.COMPLETED),
        )
        for (m in other) assertFalse(TestCrypto.verifyRaw(keyPair.public, m.digest(), signed.signature))
    }

    @Test
    fun `keystore failure produces no signature but burns the counter value`() {
        val broken = RigMessageSigner({ throw IllegalStateException("key invalidated") }, compressed, RigCounter(store))
        assertThrows(IllegalStateException::class.java) { broken.heartbeat(programId, rig, 1uL, 1uL, 1) }
        assertEquals(listOf(1uL), store.writes)
        assertEquals(2uL, signer.heartbeat(programId, rig, 1uL, 2uL, 1).payload.counter)
    }

    @Test
    fun `malformed DER from the keystore fails closed`() {
        val bad = RigMessageSigner({ byteArrayOf(0x30, 0x00) }, compressed, RigCounter(store))
        assertThrows(P256EncodingException::class.java) { bad.heartbeat(programId, rig, 1uL, 1uL, 1) }
    }

    @Test
    fun `rejects a public key that is not a compressed P-256 point`() {
        val notOnCurve = compressed.copyOf().also { it[0] = 0x05 }
        assertThrows(P256EncodingException::class.java) {
            RigMessageSigner(TestCrypto.derSigner(keyPair), notOnCurve, RigCounter(store))
        }
    }

    @Test
    fun `SignedRigMessage refuses high-S and malformed parts`() {
        val payload = HeartbeatPreimage(programId, rig, 1uL, 1uL, 1uL, 1)
        val raw = P256.normalizeLowS(P256.derToRaw(TestCrypto.derSigner(keyPair).signDer(payload.digest())))
        val high = raw.copyOfRange(0, 32) + P256.N.subtract(BigInteger(1, raw.copyOfRange(32, 64))).toFixed32()
        assertFalse(P256.isLowS(high))
        assertThrows(IllegalArgumentException::class.java) { SignedRigMessage(payload, high, compressed) }
        assertThrows(IllegalArgumentException::class.java) { SignedRigMessage(payload, raw.copyOf(63), compressed) }
        assertThrows(IllegalArgumentException::class.java) { SignedRigMessage(payload, raw, compressed.copyOf(32)) }
        val ok = SignedRigMessage(payload, raw, compressed)
        ok.signature.fill(0) // getters return copies
        assertTrue(TestCrypto.verifyRaw(keyPair.public, ok.message, ok.signature))
    }
}
