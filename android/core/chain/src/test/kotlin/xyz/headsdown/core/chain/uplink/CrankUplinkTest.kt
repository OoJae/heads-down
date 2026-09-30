package xyz.headsdown.core.chain.uplink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.keys.CounterStore
import xyz.headsdown.core.keys.DerSigner
import xyz.headsdown.core.keys.P256
import xyz.headsdown.core.keys.RigCounter
import xyz.headsdown.core.keys.RigMessageKind
import xyz.headsdown.core.keys.RigMessageSigner
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.core.keys.ShiftPlan
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class CrankUplinkTest {

    // ------------------------------------------------------------------ JSON

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
    private val rig = HeadsDownProgram.rig(Pubkey.fromBase58("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")).address

    @Test
    fun `heartbeat frame carries exactly the contract A fields`() {
        counter = ULong.MAX_VALUE - 1uL
        val hb = signer.heartbeat(HeadsDownProgram.ID.bytes, rig.bytes, 7uL, 422_593uL, 1)
        val o = Json.parseToJsonElement(HeartbeatJson.encode(hb)).jsonObject
        assertEquals(setOf("type", "rig", "counter", "shift_id", "round_id", "lease_rounds", "sig64"), o.keys)
        assertEquals("heartbeat", (o["type"] as JsonPrimitive).content)
        assertEquals(rig.toBase58(), (o["rig"] as JsonPrimitive).content)
        // u64::MAX survives as an exact, unquoted JSON number.
        assertEquals("18446744073709551615", (o["counter"] as JsonPrimitive).content)
        assertFalse((o["counter"] as JsonPrimitive).isString)
        assertEquals("7", (o["shift_id"] as JsonPrimitive).content)
        assertEquals("422593", (o["round_id"] as JsonPrimitive).content)
        assertEquals("1", (o["lease_rounds"] as JsonPrimitive).content)
        val sig = Base64.getDecoder().decode((o["sig64"] as JsonPrimitive).content)
        assertEquals(64, sig.size)
        assertTrue(P256.isLowS(sig))
        assertTrue(sig.contentEquals(hb.signature))
        assertEquals("heartbeat", HeartbeatJson.typeOf(hb))
    }

    @Test
    fun `break and freeze frames carry their type and reason, plans are never sent`() {
        for (reason in listOf(ShiftEndReason.PICKUP, ShiftEndReason.SCREEN_ON, ShiftEndReason.UNPLUGGED, ShiftEndReason.UNLOCKED, ShiftEndReason.MANUAL)) {
            val signed = signer.shiftSignal(HeadsDownProgram.ID.bytes, rig.bytes, RigMessageKind.BREAK, 7uL, reason)
            val brk = Json.parseToJsonElement(HeartbeatJson.encode(signed)).jsonObject
            assertEquals(setOf("type", "rig", "counter", "shift_id", "reason", "sig64"), brk.keys)
            assertEquals("break", (brk["type"] as JsonPrimitive).content)
            assertEquals("${reason.wire}", (brk["reason"] as JsonPrimitive).content)
            assertEquals("7", (brk["shift_id"] as JsonPrimitive).content)
        }
        val frz = Json.parseToJsonElement(HeartbeatJson.encode(signer.shiftSignal(HeadsDownProgram.ID.bytes, rig.bytes, RigMessageKind.FREEZE, 7uL, ShiftEndReason.FREEZE))).jsonObject
        assertEquals(setOf("type", "rig", "counter", "shift_id", "reason", "sig64"), frz.keys)
        assertEquals("freeze", (frz["type"] as JsonPrimitive).content)
        assertEquals("3", (frz["reason"] as JsonPrimitive).content)
        // Reasons the program refuses never leave the phone.
        assertThrows(IllegalArgumentException::class.java) {
            HeartbeatJson.encode(signer.shiftSignal(HeadsDownProgram.ID.bytes, rig.bytes, RigMessageKind.BREAK, 7uL, ShiftEndReason.COMPLETED))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeartbeatJson.encode(signer.shiftSignal(HeadsDownProgram.ID.bytes, rig.bytes, RigMessageKind.FREEZE, 7uL, ShiftEndReason.PICKUP))
        }
        val plan = signer.plan(HeadsDownProgram.ID.bytes, rig.bytes, ShiftPlan(1uL, 1_000_000uL, 1, 0, 1, 0, 1, 2))
        assertThrows(IllegalArgumentException::class.java) { HeartbeatJson.encode(plan) }
    }

    @Test
    fun `crank acks parse per contract A and anything else is ignored`() {
        assertEquals(CrankReply.Ack(42uL, true, AckReason.ACCEPTED), CrankReply.parse("""{"type":"ack","counter":42,"ok":true,"reason":"accepted"}"""))
        // Integers may also be decimal strings; unknown fields are ignored.
        assertEquals(
            CrankReply.Ack(18446744073709551615uL, false, AckReason.STALE_COUNTER),
            CrankReply.parse("""{"type":"ack","counter":"18446744073709551615","ok":false,"reason":"stale_counter","rig":"x","extra":{"a":1}}"""),
        )
        for (code in listOf("bad_signature", "stale_counter", "unknown_rig", "rate_limited", "malformed", "lease_invalid")) {
            val ack = CrankReply.parse("""{"type":"ack","counter":1,"ok":false,"reason":"$code"}""") as CrankReply.Ack
            assertEquals(code, ack.reason.wire)
        }
        // A code from a newer crank, or none at all.
        assertEquals(AckReason.UNKNOWN, (CrankReply.parse("""{"type":"ack","counter":1,"ok":false,"reason":"brand_new"}""") as CrankReply.Ack).reason)
        assertEquals(AckReason.ACCEPTED, (CrankReply.parse("""{"type":"ack","counter":1,"ok":true}""") as CrankReply.Ack).reason)
        assertEquals(CrankReply.Other("status"), CrankReply.parse("""{"type":"status","round_id":422771}"""))
        // Malformed: not JSON, no type, no boolean ok, negative / fractional / oversized counters, too long.
        listOf(
            "not json", "[]", """{"counter":1,"ok":true}""", """{"type":"ack","counter":1,"ok":"true"}""",
            """{"type":"ack","counter":-1,"ok":true}""", """{"type":"ack","counter":1.5,"ok":true}""",
            """{"type":"ack","counter":"18446744073709551616","ok":true}""", """{"type":"ack","ok":true}""",
            """{"type":"ack","counter":1,"ok":true,"pad":"""" + "x".repeat(5_000) + """"}""",
        ).forEach { assertEquals(it.take(40), null, CrankReply.parse(it)) }
    }

    // ------------------------------------------------------------------ backoff

    @Test
    fun `backoff is exponential with equal jitter and a cap`() {
        val b = Backoff(baseMillis = 1_000, maxMillis = 60_000, random = Random(3))
        repeat(200) {
            for (attempt in 0..40) {
                val d = b.delayMillis(attempt)
                val ceiling = minOf(60_000L, 1_000L shl minOf(attempt, 30))
                assertTrue("attempt $attempt: $d", d in ceiling / 2..ceiling)
            }
        }
        // Jitter actually varies.
        assertTrue((0 until 50).map { b.delayMillis(5) }.toSet().size > 10)
        assertThrows(IllegalArgumentException::class.java) { Backoff(0, 1) }
    }

    // ------------------------------------------------------------------ real TLS WebSocket

    private val server = MockWebServer()
    private val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
    private val client = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val received = LinkedBlockingQueue<String>()

    private fun upgrade(closeAfterFirst: Boolean = false, reply: ((String) -> String?)? = null) = MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            received.put(text)
            reply?.invoke(text)?.let { webSocket.send(it) }
            if (closeAfterFirst) webSocket.close(1001, "going away")
        }
        override fun onOpen(webSocket: WebSocket, response: Response) = Unit
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null) // complete the close handshake the client started
        }
    }).build()

    @Before
    fun start() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.start()
    }

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun endpoint() = "wss://localhost:${server.port}/v1/heartbeats"

    private fun uplink(onConnected: () -> Unit = {}, onText: (String) -> Unit = {}) =
        CrankUplink(endpoint(), client, scope, Backoff(baseMillis = 20, maxMillis = 100), onConnected, onText)

    @Test
    fun `refuses cleartext and invalid endpoints`() {
        assertThrows(IllegalArgumentException::class.java) { CrankUplink("ws://crank.example/v1", client, scope) }
        assertThrows(IllegalArgumentException::class.java) { CrankUplink("https://crank.example/v1", client, scope) }
        assertThrows(IllegalArgumentException::class.java) { CrankUplink("wss://", client, scope) }
    }

    @Test
    fun `send is a non-blocking no-op until connected`() {
        val u = uplink()
        val started = System.nanoTime()
        assertFalse(u.send("{}"))
        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(50))
        assertEquals(UplinkState.STOPPED, u.state.value)
    }

    @Test
    fun `connects over TLS, delivers, and reconnects after a failed handshake and a server close`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).build()) // crank down: handshake fails
        server.enqueue(upgrade(closeAfterFirst = true)) // crank up, then restarts
        server.enqueue(upgrade())
        val connects = java.util.concurrent.atomic.AtomicInteger()
        val u = uplink(onConnected = { connects.incrementAndGet() })
        u.start()
        u.start() // idempotent
        withTimeout(10_000) { u.state.first { it == UplinkState.CONNECTED } }
        assertEquals("the failed handshake was retried", 1, connects.get())
        assertTrue(u.send("""{"n":1}"""))
        assertEquals("""{"n":1}""", received.poll(5, TimeUnit.SECONDS))
        // The server closed after the first frame: the uplink backs off and opens a new socket.
        withTimeout(10_000) { while (connects.get() < 2) kotlinx.coroutines.delay(10) }
        assertTrue(u.send("""{"n":2}"""))
        assertEquals("""{"n":2}""", received.poll(5, TimeUnit.SECONDS))
        assertEquals(2, connects.get())
        assertTrue(server.requestCount >= 3)
        u.stop()
        assertEquals(UplinkState.STOPPED, u.state.value)
        assertFalse(u.send("{}"))
    }

    @Test
    fun `crank acks come back through onText, oversized frames and throwing handlers are dropped`() = runBlocking {
        val frames = LinkedBlockingQueue<String>()
        server.enqueue(
            upgrade(reply = { text ->
                val counter = Json.parseToJsonElement(text).jsonObject["counter"].toString()
                when (counter) {
                    "1" -> """{"type":"ack","counter":1,"ok":true,"reason":"accepted"}"""
                    "2" -> "x".repeat(CrankReply.MAX_FRAME_CHARS + 1)
                    else -> """{"type":"ack","counter":$counter,"ok":false,"reason":"bad_signature"}"""
                }
            }),
        )
        var throwOnce = true
        val u = uplink(onText = { text ->
            if (throwOnce && text.contains("accepted")) {
                throwOnce = false
                frames.put(text)
                throw IllegalStateException("handler bug")
            }
            frames.put(text)
        })
        u.start()
        withTimeout(10_000) { u.state.first { it == UplinkState.CONNECTED } }
        assertTrue(u.send("""{"counter":1}"""))
        assertEquals(CrankReply.Ack(1uL, true, AckReason.ACCEPTED), CrankReply.parse(frames.poll(5, TimeUnit.SECONDS)!!))
        assertTrue(u.send("""{"counter":2}""")) // answered with an oversized frame: dropped
        assertTrue(u.send("""{"counter":3}"""))
        assertEquals(CrankReply.Ack(3uL, false, AckReason.BAD_SIGNATURE), CrankReply.parse(frames.poll(5, TimeUnit.SECONDS)!!))
        assertEquals(null, frames.poll(200, TimeUnit.MILLISECONDS))
        assertEquals("the socket survived the throwing handler", UplinkState.CONNECTED, u.state.value)
        u.stop()
    }

    @Test
    fun `stop ends the reconnect loop`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse.Builder().code(503).build()) }
        val u = uplink()
        u.start()
        withTimeout(5_000) { while (server.requestCount < 1) kotlinx.coroutines.delay(5) }
        u.stop()
        val after = server.requestCount
        kotlinx.coroutines.delay(400) // several backoff periods
        assertEquals(after, server.requestCount)
        assertEquals(UplinkState.STOPPED, u.state.value)
    }
}
