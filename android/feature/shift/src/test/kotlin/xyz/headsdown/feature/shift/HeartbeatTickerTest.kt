package xyz.headsdown.feature.shift

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.keys.CounterStore
import xyz.headsdown.core.keys.DerSigner
import xyz.headsdown.core.keys.HeartbeatPreimage
import xyz.headsdown.core.keys.P256
import xyz.headsdown.core.keys.RigCounter
import xyz.headsdown.core.keys.RigMessageKind
import xyz.headsdown.core.keys.RigMessageSigner
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.core.keys.SignedRigMessage
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // TestScope.currentTime
class HeartbeatTickerTest {

    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val derSigner = DerSigner { msg -> Signature.getInstance("SHA256withECDSA").run { initSign(keyPair.private); update(msg); sign() } }
    private val compressed = P256.compress(keyPair.public as ECPublicKey)

    private var counterValue = 0uL
    private val counter = RigCounter(object : CounterStore {
        override fun load() = counterValue
        override fun store(value: ULong): Boolean { counterValue = value; return true }
    })
    private val signer = RigMessageSigner(derSigner, compressed, counter)
    private val binding = RigBinding(ByteArray(32) { 1 }, ByteArray(32) { 2 })
    private val spec = ShiftSpec(shiftId = 11, mode = ShiftMode.NIGHT)
    private val delivered = mutableListOf<SignedRigMessage<*>>()

    /** The precompile's predicate: ECDSA-P256 over SHA-256 of the 32-byte message. */
    private fun verify(m: SignedRigMessage<*>): Boolean = Signature.getInstance("SHA256withECDSA").run {
        initVerify(keyPair.public)
        update(m.message)
        verify(P256.rawToDer(m.signature))
    }

    private fun ticker(
        eligible: () -> ShiftSpec? = { spec },
        sink: HeartbeatSink = HeartbeatSink { delivered += it },
        lease: Int = 1,
        rounds: OreRoundSource = OreRoundSource { kotlinx.coroutines.flow.emptyFlow() },
        messageSigner: RigMessageSigner = signer,
    ) = HeartbeatTicker(rounds, eligible, { binding }, messageSigner, sink, lease)

    @Test
    fun `signs a bound HEARTBEAT digest when eligible`() = runTest {
        val result = ticker().tick(OreRound(id = 5_000uL, observedAtMillis = 0))
        val hb = (result as TickResult.Signed).heartbeat
        val m = hb.payload
        assertEquals(RigMessageKind.HEARTBEAT, m.kind)
        assertEquals(5_000uL, m.roundId)
        assertEquals(1, m.leaseRounds)
        assertEquals(11uL, m.shiftId)
        assertEquals(1uL, m.counter)
        assertArrayEquals(binding.programId, m.programId)
        assertArrayEquals(binding.rigAddress, m.rig)
        assertEquals(94, m.preimage().size)
        assertArrayEquals(m.digest(), hb.message)
        assertTrue(P256.isLowS(hb.signature))
        assertTrue(verify(hb))
        assertEquals(listOf<SignedRigMessage<*>>(hb), delivered)
    }

    @Test
    fun `no heartbeat when not eligible, and the counter is not consumed`() = runTest {
        val result = ticker(eligible = { null }).tick(OreRound(1uL, 0))
        assertEquals(TickResult.NotEligible, result)
        assertTrue(delivered.isEmpty())
        assertEquals(0uL, counterValue)
    }

    @Test
    fun `only hot rounds are signed and counters strictly increase`() = runTest {
        val hotRounds = setOf(1uL, 2uL, 4uL, 7uL)
        var current = 0uL
        val t = ticker(eligible = { if (current in hotRounds) spec else null })
        for (id in 1uL..8uL) {
            current = id
            t.tick(OreRound(id, 0))
        }
        val heartbeats = delivered.map { it.payload as HeartbeatPreimage }
        assertEquals(hotRounds.toList(), heartbeats.map { it.roundId })
        val counters = heartbeats.map { it.counter }
        assertEquals(counters.sorted(), counters)
        assertEquals(counters.size, counters.toSet().size)
        assertTrue(delivered.all(::verify))
    }

    @Test
    fun `lease rounds are carried in the preimage`() = runTest {
        val hb = (ticker(lease = 3).tick(OreRound(100uL, 0)) as TickResult.Signed).heartbeat
        assertEquals(3, hb.payload.leaseRounds)
        assertEquals(100uL, hb.payload.roundId)
    }

    @Test
    fun `delivery failure is reported and the next round still works`() = runTest {
        var fail = true
        val t = ticker(sink = { if (fail) throw IOException("offline") else delivered += it })
        val first = t.tick(OreRound(1uL, 0))
        assertTrue(first is TickResult.DeliveryFailed)
        fail = false
        assertTrue(t.tick(OreRound(2uL, 0)) is TickResult.Signed)
        // The undelivered heartbeat burned counter 1; the next one must not reuse it.
        assertEquals(2uL, delivered.single().payload.counter)
    }

    @Test
    fun `keystore failure produces no heartbeat`() = runTest {
        val broken = RigMessageSigner({ throw IllegalStateException("key invalidated") }, compressed, counter)
        assertTrue(ticker(messageSigner = broken).tick(OreRound(1uL, 0)) is TickResult.SigningFailed)
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `BREAK and FREEZE share the heartbeat counter and carry INTERFACE reasons`() = runTest {
        val t = ticker()
        t.tick(OreRound(42uL, 0))
        val brk = t.signBreak(11, BreakReason.LIFTED.wireReason)
        assertEquals(RigMessageKind.BREAK, brk.payload.kind)
        assertEquals(ShiftEndReason.PICKUP, brk.payload.reason)
        assertEquals(11uL, brk.payload.shiftId)
        assertEquals(86, brk.payload.preimage().size)
        val frz = t.signFreeze(null)
        assertEquals(RigMessageKind.FREEZE, frz.payload.kind)
        assertEquals(ShiftEndReason.FREEZE, frz.payload.reason)
        assertEquals(0uL, frz.payload.shiftId)
        assertEquals(listOf(1uL, 2uL, 3uL), listOf(1uL, brk.payload.counter, frz.payload.counter))
        assertTrue(verify(brk) && verify(frz))
    }

    @Test
    fun `device break reasons map onto ShiftLog codes`() {
        assertEquals(ShiftEndReason.PICKUP, BreakReason.LIFTED.wireReason)
        assertEquals(ShiftEndReason.SCREEN_ON, BreakReason.SCREEN_ON.wireReason)
        assertEquals(ShiftEndReason.SCREEN_ON, BreakReason.UNLOCKED.wireReason)
        assertEquals(ShiftEndReason.MANUAL, BreakReason.UNPLUGGED.wireReason)
    }

    @Test
    fun `stub round source ticks every 78 seconds with increasing ids`() = runTest {
        val clock = MonotonicClock { currentTime }
        val rounds = StubOreRoundSource(clock, firstRoundId = 10uL).rounds().take(3).toList()
        assertEquals(listOf(10uL, 11uL, 12uL), rounds.map { it.id })
        assertEquals(listOf(0L, 78_000L, 156_000L), rounds.map { it.observedAtMillis })
    }

    @Test
    fun `run drives ticks from the round source`() = runTest {
        val clock = MonotonicClock { currentTime }
        val source = OreRoundSource { StubOreRoundSource(clock).rounds().take(4) }
        val results = mutableListOf<TickResult>()
        HeartbeatTicker(source, { spec }, { binding }, signer, { delivered += it }, onResult = { _, r -> results += r }).run()
        assertEquals(4, delivered.size)
        assertTrue(results.all { it is TickResult.Signed })
    }

    @Test
    fun `unregistered binding is all zeros`() {
        assertTrue(!RigBinding.UNREGISTERED.isRegistered)
        assertTrue(binding.isRegistered)
    }
}
