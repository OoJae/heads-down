package xyz.headsdown.feature.shift

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.keys.DerSigner
import xyz.headsdown.core.keys.HeartbeatSigner
import xyz.headsdown.core.keys.P256
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.core.keys.SignedHeartbeat
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // TestScope.currentTime
class HeartbeatTickerTest {

    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val signer = HeartbeatSigner(
        DerSigner { msg -> Signature.getInstance("SHA256withECDSA").run { initSign(keyPair.private); update(msg); sign() } },
        P256.compress(keyPair.public as ECPublicKey),
    )
    private val binding = RigBinding(ByteArray(32) { 1 }, ByteArray(32) { 2 })
    private val spec = ShiftSpec(shiftId = 11, mode = ShiftMode.NIGHT)

    private var counterValue = 0uL
    private val counter = HeartbeatCounter { ++counterValue }
    private val delivered = mutableListOf<SignedHeartbeat>()

    private fun verify(hb: SignedHeartbeat): Boolean = Signature.getInstance("SHA256withECDSA").run {
        initVerify(keyPair.public)
        update(hb.message)
        verify(P256.rawToDer(hb.signature))
    }

    private fun ticker(
        eligible: () -> ShiftSpec? = { spec },
        sink: HeartbeatSink = HeartbeatSink { delivered += it },
        lease: Int = 1,
        rounds: OreRoundSource = OreRoundSource { kotlinx.coroutines.flow.emptyFlow() },
    ) = HeartbeatTicker(rounds, eligible, { binding }, signer, counter, sink, lease)

    @Test
    fun `signs a bound DOWN heartbeat when eligible`() = runTest {
        val result = ticker().tick(OreRound(id = 5_000uL, observedAtMillis = 0))
        val hb = (result as TickResult.Signed).heartbeat
        val m = hb.decoded
        assertEquals(RigSignalState.DOWN, m.state)
        assertEquals(5_000uL, m.oreRoundId)
        assertEquals(5_000uL, m.leaseEnd)
        assertEquals(11uL, m.shiftId)
        assertEquals(1uL, m.counter)
        assertArrayEquals(binding.programId, m.programId)
        assertArrayEquals(binding.rigAddress, m.rig)
        assertTrue(P256.isLowS(hb.signature))
        assertTrue(verify(hb))
        assertEquals(listOf(hb), delivered)
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
        assertEquals(hotRounds.toList(), delivered.map { it.decoded.oreRoundId })
        val counters = delivered.map { it.decoded.counter }
        assertEquals(counters.sorted(), counters)
        assertEquals(counters.size, counters.toSet().size)
        assertTrue(delivered.all(::verify))
    }

    @Test
    fun `lease covers the configured number of rounds`() = runTest {
        val hb = (ticker(lease = 3).tick(OreRound(100uL, 0)) as TickResult.Signed).heartbeat
        assertEquals(102uL, hb.decoded.leaseEnd)
    }

    @Test
    fun `delivery failure is reported and the next round still works`() = runTest {
        var fail = true
        val t = ticker(sink = { if (fail) throw IOException("offline") else delivered += it })
        val first = t.tick(OreRound(1uL, 0))
        assertTrue(first is TickResult.DeliveryFailed)
        fail = false
        assertTrue(t.tick(OreRound(2uL, 0)) is TickResult.Signed)
        // The failed heartbeat burned counter 1; the next one must not reuse it.
        assertEquals(2uL, delivered.single().decoded.counter)
    }

    @Test
    fun `keystore failure produces no heartbeat`() = runTest {
        val broken = HeartbeatTicker(
            OreRoundSource { kotlinx.coroutines.flow.emptyFlow() }, { spec }, { binding },
            HeartbeatSigner({ throw IllegalStateException("key invalidated") }, P256.compress(keyPair.public as ECPublicKey)),
            counter, { delivered += it },
        )
        assertTrue(broken.tick(OreRound(1uL, 0)) is TickResult.SigningFailed)
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `BREAK and FREEZE signals bind to the latest round`() = runTest {
        val t = ticker()
        assertNull("no round seen yet", t.signSignal(RigSignalState.BROKEN, 11))
        t.tick(OreRound(42uL, 0))
        val brk = t.signSignal(RigSignalState.BROKEN, 11)!!.decoded
        assertEquals(RigSignalState.BROKEN, brk.state)
        assertEquals(42uL, brk.oreRoundId)
        val frz = t.signSignal(RigSignalState.FROZEN, null)!!.decoded
        assertEquals(RigSignalState.FROZEN, frz.state)
        assertEquals(0uL, frz.shiftId)
        assertTrue(frz.counter > brk.counter)
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
        HeartbeatTicker(source, { spec }, { binding }, signer, counter, { delivered += it }, onResult = { _, r -> results += r }).run()
        assertEquals(4, delivered.size)
        assertTrue(results.all { it is TickResult.Signed })
    }

    @Test
    fun `unregistered binding is all zeros`() {
        assertTrue(!RigBinding.UNREGISTERED.isRegistered)
        assertTrue(binding.isRegistered)
    }
}
