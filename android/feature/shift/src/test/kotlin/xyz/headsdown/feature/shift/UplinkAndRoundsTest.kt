package xyz.headsdown.feature.shift

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.uplink.AckReason
import xyz.headsdown.core.chain.uplink.MessageUplink
import xyz.headsdown.core.keys.CounterStore
import xyz.headsdown.core.keys.DerSigner
import xyz.headsdown.core.keys.P256
import xyz.headsdown.core.keys.RigCounter
import xyz.headsdown.core.keys.RigMessageKind
import xyz.headsdown.core.keys.RigMessageSigner
import xyz.headsdown.core.keys.ShiftEndReason
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UplinkAndRoundsTest {

    private class FakeUplink : MessageUplink {
        var connected = false
        var started = 0
        var stopped = 0
        val sent = mutableListOf<String>()
        lateinit var onConnected: () -> Unit
        lateinit var onText: (String) -> Unit

        override fun start() { started++ }
        override fun stop() { stopped++; connected = false }
        override fun send(text: String): Boolean = if (connected) sent.add(text) else false

        fun connect() { connected = true; onConnected() }
    }

    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private var counter = 0uL
    private val signer = RigMessageSigner(
        DerSigner { m -> Signature.getInstance("SHA256withECDSA").run { initSign(keyPair.private); update(m); sign() } },
        P256.compress(keyPair.public as ECPublicKey),
        RigCounter(object : CounterStore {
            override fun load() = counter
            override fun store(value: ULong): Boolean { counter = value; return true }
        }),
    )
    private val programId = ByteArray(32) { 1 }
    private val rig = ByteArray(32) { 2 }
    private fun heartbeat(round: ULong, lease: Int = 1) = signer.heartbeat(programId, rig, 3uL, round, lease)

    // ------------------------------------------------------------------ sink

    @Test
    fun `connected uplink receives the contract JSON and the log keeps a copy`() = runTest {
        val fake = FakeUplink()
        val log = mutableListOf<String>()
        val sink = CrankHeartbeatSink(UplinkFactory { cb, text -> fake.also { it.onConnected = cb; it.onText = text } }, { log += it }, { currentTime }, backgroundScope)
        sink.open()
        assertEquals(1, fake.started)
        fake.connect()
        sink.deliver(heartbeat(round = 100uL))
        assertEquals(1, fake.sent.size)
        assertEquals(fake.sent, log)
        val o = Json.parseToJsonElement(fake.sent.single()).jsonObject
        assertEquals(setOf("type", "rig", "counter", "shift_id", "round_id", "lease_rounds", "sig64"), o.keys)
        assertEquals("\"heartbeat\"", o["type"].toString())
    }

    @Test
    fun `break and freeze go out as contract A frames the crank lands`() = runTest {
        val fake = FakeUplink().also { it.connected = true }
        val sink = CrankHeartbeatSink(UplinkFactory { cb, text -> fake.also { it.onConnected = cb; it.onText = text } }, { }, { currentTime }, backgroundScope)
        sink.deliver(signer.shiftSignal(programId, rig, RigMessageKind.BREAK, 3uL, ShiftEndReason.UNLOCKED))
        sink.deliver(signer.shiftSignal(programId, rig, RigMessageKind.FREEZE, 3uL, ShiftEndReason.FREEZE))
        val (brk, frz) = fake.sent.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals("\"break\"", brk["type"].toString())
        assertEquals("8", brk["reason"].toString())
        assertEquals("\"freeze\"", frz["type"].toString())
        assertEquals("3", frz["reason"].toString())
        assertEquals(setOf("type", "rig", "counter", "shift_id", "reason", "sig64"), brk.keys)
    }

    @Test
    fun `acks are matched to sent frames and refusals surface without secrets`() = runTest {
        val fake = FakeUplink().also { it.connected = true }
        val monitor = CrankLinkMonitor()
        val logs = mutableListOf<String>()
        var resyncs = 0
        val sink = CrankHeartbeatSink(
            UplinkFactory { cb, text -> fake.also { it.onConnected = cb; it.onText = text } }, { }, { currentTime }, backgroundScope,
            monitor = monitor, wallClock = { 1_000L }, onStaleCounter = { resyncs++ }, debugLog = { logs += it },
        )
        assertTrue(monitor.status.value.configured)
        val hb = heartbeat(round = 7uL)
        sink.deliver(hb)
        val c = hb.payload.counter
        fake.onText("""{"type":"ack","counter":$c,"ok":true,"reason":"accepted"}""")
        assertEquals(1, monitor.status.value.accepted)
        assertFalse(monitor.status.value.refusing)
        assertEquals(null, monitor.status.value.refusalLine())

        val hb2 = heartbeat(round = 8uL)
        sink.deliver(hb2)
        fake.onText("""{"type":"ack","counter":"${hb2.payload.counter}","ok":false,"reason":"stale_counter"}""")
        assertTrue(monitor.status.value.refusing)
        assertEquals(AckReason.STALE_COUNTER, monitor.status.value.lastRejection!!.reason)
        assertEquals("heartbeat", monitor.status.value.lastRejection!!.frame)
        assertEquals(1, resyncs)
        assertTrue(monitor.status.value.refusalLine()!!.contains("already used"))

        val brk = signer.shiftSignal(programId, rig, RigMessageKind.BREAK, 3uL, ShiftEndReason.PICKUP)
        sink.deliver(brk)
        fake.onText("""{"type":"ack","counter":${brk.payload.counter},"ok":false,"reason":"bad_signature"}""")
        assertEquals("break", monitor.status.value.lastRejection!!.frame)
        assertTrue(monitor.status.value.refusalLine()!!.contains("does not match the rig key"))
        assertEquals(1, resyncs)

        // An ack for a counter this phone never sent, a status frame and garbage change nothing.
        val before = monitor.status.value
        fake.onText("""{"type":"ack","counter":999999,"ok":true,"reason":"accepted"}""")
        fake.onText("""{"type":"status","round_id":1}""")
        fake.onText("{not json")
        assertEquals(before, monitor.status.value)
        // Debug lines carry counters and codes only: never the frame, the rig or the signature.
        assertTrue(logs.any { "stale_counter" in it } && logs.any { "bad_signature" in it })
        val sig64 = java.util.Base64.getEncoder().encodeToString(brk.signature)
        assertTrue(logs.none { sig64 in it || "sig64" in it || "{" in it })
        // Every refusal line is honest copy.
        val banned = Regex("(?i)\\b(earn|yield|stak|passive income|proof of focus)")
        AckReason.entries.forEach { reason ->
            val line = before.copy(lastAck = CrankAck(1uL, "heartbeat", reason == AckReason.ACCEPTED, reason, 0L)).refusalLine()
            if (line != null) assertFalse(line, banned.containsMatchIn(line))
        }
    }

    @Test
    fun `offline delivery fails fast, queues, and flushes only fresh messages on reconnect`() = runTest {
        val fake = FakeUplink()
        val log = mutableListOf<String>()
        val sink = CrankHeartbeatSink(UplinkFactory { cb, text -> fake.also { it.onConnected = cb; it.onText = text } }, { log += it }, { currentTime }, backgroundScope)
        sink.open()
        // Offline: the tick is undelivered, never blocked, never a crash.
        assertThrows(UplinkUnavailableException::class.java) { kotlinx.coroutines.runBlocking { sink.deliver(heartbeat(1uL, lease = 1)) } }
        assertThrows(UplinkUnavailableException::class.java) { kotlinx.coroutines.runBlocking { sink.deliver(heartbeat(2uL, lease = 3)) } }
        assertEquals(2, sink.pendingCount)
        assertEquals("local fallback kept both", 2, log.size)
        // Two rounds later the lease-1 heartbeat is stale; the lease-3 one is still useful.
        advanceTimeBy(2 * StubOreRoundSource.ORE_ROUND_MILLIS)
        fake.connect()
        assertEquals(1, fake.sent.size)
        assertEquals("2", Json.parseToJsonElement(fake.sent.single()).jsonObject["round_id"].toString())
        assertEquals(0, sink.pendingCount)
    }

    @Test
    fun `the queue is bounded and drops the oldest`() = runTest {
        val fake = FakeUplink()
        val sink = CrankHeartbeatSink(UplinkFactory { cb, text -> fake.also { it.onConnected = cb; it.onText = text } }, { }, { currentTime }, backgroundScope, maxPending = 3)
        repeat(5) { i -> runCatching { sink.deliver(heartbeat(i.toULong() + 1uL, lease = 3)) } }
        assertEquals(3, sink.pendingCount)
        fake.connect()
        assertEquals(listOf("3", "4", "5"), fake.sent.map { Json.parseToJsonElement(it).jsonObject["round_id"].toString() })
    }

    @Test
    fun `close is graceful and a quick re-open cancels it`() = runTest {
        val fake = FakeUplink()
        val sink = CrankHeartbeatSink(UplinkFactory { cb, text -> fake.also { it.onConnected = cb; it.onText = text } }, { }, { currentTime }, backgroundScope, closeGraceMillis = 5_000)
        sink.open()
        fake.connect()
        sink.close()
        // Still open during the grace period: a BREAK relayed at shift end goes out.
        sink.deliver(signer.shiftSignal(programId, rig, RigMessageKind.BREAK, 3uL, ShiftEndReason.PICKUP))
        assertEquals(1, fake.sent.size)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(1, fake.stopped)
        // Re-open inside the grace window keeps the uplink.
        sink.open()
        sink.close()
        advanceTimeBy(1_000)
        sink.open()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, fake.stopped)
    }

    @Test
    fun `no crank configured means local only and undelivered`() = runTest {
        val log = mutableListOf<String>()
        val sink = CrankHeartbeatSink(null, { log += it }, { currentTime }, backgroundScope)
        sink.open()
        sink.close()
        assertThrows(UplinkUnavailableException::class.java) { kotlinx.coroutines.runBlocking { sink.deliver(heartbeat(9uL)) } }
        assertEquals(1, log.size)
        assertEquals(0, sink.pendingCount)
    }

    @Test
    fun `a failing local log never blocks delivery`() = runTest {
        val fake = FakeUplink().also { it.connected = true }
        val sink = CrankHeartbeatSink(UplinkFactory { cb, text -> fake.also { it.onConnected = cb; it.onText = text } }, { throw IOException("disk full") }, { currentTime }, backgroundScope)
        sink.deliver(heartbeat(1uL))
        assertEquals(1, fake.sent.size)
    }

    // ------------------------------------------------------------------ Board round source

    @Test
    fun `emits each new Board round once and never goes backwards`() = runTest {
        val reads = ArrayDeque(listOf(100uL, 100uL, 101uL, 99uL, 101uL, 102uL))
        val source = BoardRoundSource({ reads.removeFirst() }, { currentTime }, pollMillis = 5_000, maxErrorDelayMillis = 60_000)
        val rounds = source.rounds().take(3).toList()
        assertEquals(listOf(100uL, 101uL, 102uL), rounds.map { it.id })
        // 100 at t=0, 101 at the third read (t=10 s), 102 at the sixth (t=25 s).
        assertEquals(listOf(0L, 10_000L, 25_000L), rounds.map { it.observedAtMillis })
    }

    @Test
    fun `one implausible answer is neither signed for nor latched`() = runTest {
        // The audit's sequence: a single bogus 500000 used to be emitted (a heartbeat for a round
        // far in the future) and then wedged the feed for the rest of the shift.
        val reads = ArrayDeque(listOf(422_700uL, 422_701uL, 500_000uL, 422_702uL, 422_703uL))
        val source = BoardRoundSource({ reads.removeFirst() }, { currentTime }, pollMillis = 5_000, maxErrorDelayMillis = 60_000)
        assertEquals(listOf(422_700uL, 422_701uL, 422_702uL, 422_703uL), source.rounds().take(4).toList().map { it.id })
    }

    @Test
    fun `a jump is believed at the pace ORE allows, or when three answers in a row agree`() = runTest {
        // After a gap in reads (the phone was offline for five minutes) several rounds have passed.
        var calls = 0
        val offline = BoardRoundSource(
            readRoundId = {
                calls++
                when {
                    calls == 1 -> 100uL
                    calls <= 6 -> throw IOException("offline")
                    else -> 104uL
                }
            },
            clock = { currentTime },
            pollMillis = 5_000,
            maxErrorDelayMillis = 60_000,
        )
        assertEquals(listOf(100uL, 104uL), offline.rounds().take(2).toList().map { it.id })

        // A first answer far too high (nothing to compare it with) is corrected once three honest
        // answers agree with each other, instead of stalling the feed for the whole shift.
        val reads = ArrayDeque(listOf(900_000uL, 422_700uL, 422_700uL, 422_701uL, 422_701uL, 422_702uL))
        val wedged = BoardRoundSource({ reads.removeFirst() }, { currentTime }, pollMillis = 5_000, maxErrorDelayMillis = 60_000)
        val t0 = currentTime
        val rounds = wedged.rounds().take(3).toList()
        assertEquals(listOf(900_000uL, 422_701uL, 422_702uL), rounds.map { it.id })
        assertEquals(listOf(0L, 15_000L, 25_000L), rounds.map { it.observedAtMillis - t0 })

        // A far-ahead answer that keeps being repeated is accepted after three reads: a single
        // client cannot tell a node that lies consistently from the chain.
        val insistent = ArrayDeque(listOf(10uL, 5_000uL, 5_000uL, 5_000uL))
        val moved = BoardRoundSource({ insistent.removeFirst() }, { currentTime }, pollMillis = 5_000, maxErrorDelayMillis = 60_000)
        assertEquals(listOf(10uL, 5_000uL), moved.rounds().take(2).toList().map { it.id })
    }

    @Test
    fun `read failures back off and emit nothing, then recover`() = runTest {
        var calls = 0
        val source = BoardRoundSource(
            readRoundId = {
                calls++
                if (calls <= 3) throw IOException("rpc down") else 7uL
            },
            clock = { currentTime },
            pollMillis = 1_000,
            maxErrorDelayMillis = 4_000,
        )
        val first = source.rounds().take(1).toList().single()
        assertEquals(7uL, first.id)
        // Backoff 2 s, 4 s, then capped at 4 s: the good read happens at t = 10 s.
        assertEquals(10_000L, first.observedAtMillis)
        assertFalse(calls > 4)
        assertTrue(calls == 4)
    }
}
