package xyz.headsdown.core.chain.rpc

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.headsdown.core.wallet.Commitment
import java.util.concurrent.TimeUnit

/** The real OkHttp path over TLS (a self-signed localhost certificate the test client trusts). */
class OkHttpJsonRpcTransportTest {

    private val server = MockWebServer()
    private val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()

    private fun client(callTimeoutMs: Long = 5_000): OkHttpClient = OkHttpJsonRpcTransport.defaultClient().newBuilder()
        .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    @Before
    fun start() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun endpoint(): String = server.url("/rpc").newBuilder().host("localhost").build().toString()

    @Test
    fun `posts JSON over https and returns the body`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"jsonrpc":"2.0","id":1,"result":123}""").build())
        val rpc = SolanaJsonRpc(OkHttpJsonRpcTransport(endpoint(), client()))
        assertEquals(123L, rpc.getBlockHeight(Commitment.FINALIZED))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.headers["Content-Type"]!!.startsWith("application/json"))
        assertTrue(recorded.body!!.utf8().contains("\"method\":\"getBlockHeight\""))
        assertTrue(recorded.body!!.utf8().contains("\"finalized\""))
        assertTrue("TLS was negotiated", recorded.handshake != null)
    }

    @Test
    fun `cleartext and malformed endpoints are refused before any request`() {
        assertThrows(IllegalArgumentException::class.java) { OkHttpJsonRpcTransport("http://api.devnet.solana.com") }
        assertThrows(IllegalArgumentException::class.java) { OkHttpJsonRpcTransport("ws://api.devnet.solana.com") }
        assertThrows(IllegalArgumentException::class.java) { OkHttpJsonRpcTransport("not a url") }
        val e = runCatching { OkHttpJsonRpcTransport("http://example.com/?api-key=SECRET") }.exceptionOrNull()!!
        assertFalse("the endpoint (which may hold a key) is never echoed", e.message!!.contains("SECRET"))
    }

    @Test
    fun `http errors carry only the status, never the body`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).body("rate limited for key SECRET").build())
        val e = runCatching { OkHttpJsonRpcTransport(endpoint(), client()).post("{}") }.exceptionOrNull()
        assertTrue(e is RpcHttpException)
        assertEquals(429, (e as RpcHttpException).status)
        assertFalse(e.message!!.contains("SECRET"))
    }

    @Test
    fun `redirects are not followed`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://evil.example/rpc").build())
        val e = runCatching { OkHttpJsonRpcTransport(endpoint(), client()).post("{}") }.exceptionOrNull()
        assertEquals(302, (e as RpcHttpException).status)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `oversized responses are refused while streaming`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("x".repeat(4_096)).build())
        val e = runCatching { OkHttpJsonRpcTransport(endpoint(), client(), maxResponseBytes = 1_024).post("{}") }.exceptionOrNull()
        assertTrue("$e", e is RpcResponseTooLargeException)
    }

    @Test
    fun `a stalled server hits the call timeout`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("{}").headersDelay(3, TimeUnit.SECONDS).build())
        val e = runCatching { OkHttpJsonRpcTransport(endpoint(), client(callTimeoutMs = 300)).post("{}") }.exceptionOrNull()
        assertTrue("$e", e is RpcException)
        assertFalse("no host or URL in the message", e!!.message!!.contains("localhost"))
    }

    @Test
    fun `an untrusted certificate is a transport failure`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("{}").build())
        // Default trust store: the self-signed test certificate is not trusted.
        val e = runCatching { OkHttpJsonRpcTransport(endpoint()).post("{}") }.exceptionOrNull()
        assertTrue("$e", e is RpcException)
    }

    @Test
    fun `coroutine cancellation cancels the call`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("{}").headersDelay(5, TimeUnit.SECONDS).build())
        val started = System.nanoTime()
        val e = runCatching {
            withTimeout(200) { OkHttpJsonRpcTransport(endpoint(), client(callTimeoutMs = 10_000)).post("{}") }
        }.exceptionOrNull()
        assertTrue("$e", e is kotlinx.coroutines.TimeoutCancellationException)
        assertTrue("returned promptly", (System.nanoTime() - started) < TimeUnit.SECONDS.toNanos(3))
    }
}
