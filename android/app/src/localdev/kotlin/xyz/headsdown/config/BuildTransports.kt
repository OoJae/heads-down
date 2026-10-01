package xyz.headsdown.config

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import xyz.headsdown.core.chain.http.HttpExchange
import xyz.headsdown.core.chain.http.HttpReply
import xyz.headsdown.core.chain.http.JsonHttp
import xyz.headsdown.core.chain.rpc.JsonRpcTransport
import xyz.headsdown.core.chain.rpc.RpcException
import xyz.headsdown.core.chain.rpc.RpcHttpException
import xyz.headsdown.core.chain.rpc.RpcResponseTooLargeException
import xyz.headsdown.core.chain.uplink.Backoff
import xyz.headsdown.core.chain.uplink.CrankReply
import xyz.headsdown.core.chain.uplink.MessageUplink
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * LOCALDEV BUILD TYPE ONLY (src/localdev). `http://127.0.0.1` / `http://localhost` RPC and
 * `ws://127.0.0.1` crank for a devstack on the laptop, reached through `adb reverse`. Every
 * other endpoint goes through the same HTTPS/WSS transports as release. The network security
 * config of this build type permits cleartext to those two hosts and nothing else.
 */
object BuildTransports {
    val transports: EndpointTransports = LoopbackAwareTransports
}

object LoopbackAwareTransports : EndpointTransports {
    override fun rpc(url: String, client: OkHttpClient): JsonRpcTransport =
        if (isLoopbackCleartext(EndpointKind.RPC, url)) LoopbackJsonRpcTransport(url, client) else SecureTransports.rpc(url, client)

    override fun http(url: String, client: OkHttpClient): JsonHttp =
        if (isLoopbackCleartext(EndpointKind.INDEXER, url)) LoopbackJsonHttp(url, client) else SecureTransports.http(url, client)

    override fun uplink(
        url: String,
        client: OkHttpClient,
        scope: CoroutineScope,
        onConnected: () -> Unit,
        onText: (String) -> Unit,
    ): MessageUplink =
        if (isLoopbackCleartext(EndpointKind.CRANK, url)) {
            LoopbackUplink(url, client, scope, onConnected, onText)
        } else {
            SecureTransports.uplink(url, client, scope, onConnected, onText)
        }

    private fun isLoopbackCleartext(kind: EndpointKind, url: String) =
        EndpointPolicy.check(kind, url, allowLoopbackCleartext = true) == EndpointVerdict.LoopbackCleartext
}

/** JSON-RPC over plain HTTP to a loopback validator. Bounded responses, no redirects. */
class LoopbackJsonRpcTransport(endpoint: String, client: OkHttpClient) : JsonRpcTransport {
    init {
        require(EndpointPolicy.check(EndpointKind.RPC, endpoint, true) == EndpointVerdict.LoopbackCleartext) {
            "loopback RPC must be http://127.0.0.1 or http://localhost"
        }
    }

    private val url = endpoint
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun post(body: String): String {
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json").post(body.toRequestBody(JSON)).build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) =
                    cont.resumeWithException(RpcException("RPC transport failure: ${e.javaClass.simpleName}"))

                override fun onResponse(call: Call, response: Response) {
                    runCatching { response.use(::read) }.fold(cont::resume, cont::resumeWithException)
                }
            })
        }
    }

    private fun read(response: Response): String {
        if (!response.isSuccessful) throw RpcHttpException(response.code)
        val source = response.body.source()
        val buffer = Buffer()
        while (source.read(buffer, 8_192) != -1L) {
            if (buffer.size > MAX_BYTES) throw RpcResponseTooLargeException(MAX_BYTES)
        }
        return buffer.readUtf8()
    }

    private companion object {
        const val MAX_BYTES = 4L * 1024 * 1024
        val JSON = "application/json".toMediaType()
    }
}

/** Registrar / indexer JSON over plain HTTP to a loopback devstack. Same bounds and checks as HTTPS. */
class LoopbackJsonHttp(endpoint: String, client: OkHttpClient) : JsonHttp {
    init {
        require(EndpointPolicy.check(EndpointKind.INDEXER, endpoint, true) == EndpointVerdict.LoopbackCleartext) {
            "loopback service must be http://127.0.0.1 or http://localhost"
        }
    }

    private val base = endpoint.toHttpUrl()
    private val client = HttpExchange.noRedirects(client)

    override suspend fun get(path: String, bearer: String?): HttpReply =
        HttpExchange.execute(client, HttpExchange.request(HttpExchange.resolve(base, path), null, bearer))

    override suspend fun post(path: String, json: String, bearer: String?): HttpReply =
        HttpExchange.execute(client, HttpExchange.request(HttpExchange.resolve(base, path), json, bearer))
}

/**
 * The crank uplink over `ws://127.0.0.1`: reconnects with backoff, never blocks or throws. The
 * crank's text frames (acks) go to [onText], bounded like the WSS uplink.
 */
class LoopbackUplink(
    endpoint: String,
    client: OkHttpClient,
    private val scope: CoroutineScope,
    private val onConnected: () -> Unit,
    private val onText: (String) -> Unit = {},
    private val backoff: Backoff = Backoff(baseMillis = 500, maxMillis = 10_000),
) : MessageUplink {
    init {
        require(EndpointPolicy.check(EndpointKind.CRANK, endpoint, true) == EndpointVerdict.LoopbackCleartext) {
            "loopback crank must be ws://127.0.0.1 or ws://localhost"
        }
    }

    // OkHttp maps ws:// to http:// for the upgrade request.
    private val request = Request.Builder().url(endpoint).build()
    private val client = client.newBuilder().pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).build()

    @Volatile private var socket: WebSocket? = null
    private var loop: Job? = null

    @Synchronized
    override fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { connectLoop() }
    }

    @Synchronized
    override fun stop() {
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        loop?.cancel()
        loop = null
    }

    override fun send(text: String): Boolean {
        val s = socket ?: return false
        return try {
            s.send(text)
        } catch (_: RuntimeException) {
            false
        }
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val opened = CompletableDeferred<Boolean>()
            val closed = CompletableDeferred<Unit>()
            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    socket = webSocket
                    opened.complete(true)
                    runCatching(onConnected)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length <= CrankReply.MAX_FRAME_CHARS) runCatching { onText(text) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended(webSocket)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = ended(webSocket)

                private fun ended(webSocket: WebSocket) {
                    if (socket === webSocket) socket = null
                    opened.complete(false)
                    closed.complete(Unit)
                }
            })
            var wasOpen = false
            try {
                wasOpen = opened.await()
                if (wasOpen) attempt = 0
                closed.await()
            } finally {
                if (wasOpen) ws.close(NORMAL_CLOSURE, null) else ws.cancel()
                if (socket === ws) socket = null
            }
            delay(backoff.delayMillis(attempt))
            if (attempt < 30) attempt++
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
