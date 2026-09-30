package xyz.headsdown.core.chain.uplink

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.random.Random

enum class UplinkState { STOPPED, CONNECTING, CONNECTED, BACKING_OFF }

/** The seam the heartbeat sink talks to (a [CrankUplink] in production, a fake in tests). */
interface MessageUplink {
    fun start()
    fun stop()

    /** True if [text] was handed to an open connection. Must not block or throw. */
    fun send(text: String): Boolean
}

/**
 * Exponential backoff with **equal jitter**: attempt `n` waits a uniformly random duration in
 * `[d/2, d]` where `d = min(max, base · 2^n)`. The lower half keeps a floor (no hot reconnect
 * loop when the crank is down); the random half spreads a fleet of phones so they do not
 * reconnect in lockstep after a crank restart.
 */
class Backoff(
    private val baseMillis: Long = 1_000,
    private val maxMillis: Long = 60_000,
    private val random: Random = Random.Default,
) {
    init {
        require(baseMillis > 0 && maxMillis >= baseMillis)
    }

    fun delayMillis(attempt: Int): Long {
        require(attempt >= 0)
        val shift = attempt.coerceAtMost(30)
        val d = minOf(maxMillis, baseMillis shl shift).let { if (it <= 0) maxMillis else it }
        val half = d / 2
        return half + random.nextLong(d - half + 1)
    }
}

/**
 * A long-lived WebSocket to the crank's heartbeat intake (contract A: `/ws`, or its alias
 * `/v1/heartbeats`).
 *
 * - **WSS only**; the endpoint is never logged or put in an exception.
 * - **Never blocks the caller**: [send] hands text to an open socket (OkHttp queues and writes
 *   it on its own thread) and returns false immediately when there is no open socket.
 * - **Reconnects forever** with jittered backoff while started; [stop] ends it.
 * - **Fail-safe**: every failure path is "not connected", which the heartbeat sink reports as
 *   an undelivered heartbeat; nothing here throws into the shift loop.
 * - Incoming text frames of at most [CrankReply.MAX_FRAME_CHARS] characters go to [onText]
 *   (the sink parses only acks, see [CrankReply]); binary and oversized frames are dropped, and
 *   a throwing handler cannot break the socket.
 */
class CrankUplink(
    endpoint: String,
    client: OkHttpClient,
    private val scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
    /** Called on OkHttp's thread each time a socket opens (e.g. to flush fresh pending frames). */
    private val onConnected: () -> Unit = {},
    /** Called on OkHttp's thread with each crank text frame (acks). */
    private val onText: (String) -> Unit = {},
) : MessageUplink {
    private val request: Request
    private val client: OkHttpClient = client.newBuilder()
        .pingInterval(PING_SECONDS, TimeUnit.SECONDS) // detect a dead socket within ~2 pings
        .build()

    init {
        require(endpoint.startsWith("wss://", ignoreCase = true)) { "crank uplink must use wss" }
        val https = try {
            ("https://" + endpoint.substring("wss://".length)).toHttpUrl()
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("crank uplink URL is invalid")
        }
        request = Request.Builder().url(https).build()
    }

    private val _state = MutableStateFlow(UplinkState.STOPPED)
    val state: StateFlow<UplinkState> = _state.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    private var loop: Job? = null

    /** Starts the connect loop (idempotent). */
    @Synchronized
    override fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { connectLoop() }
    }

    /** Stops reconnecting and closes the socket gracefully (idempotent). */
    @Synchronized
    override fun stop() {
        val open = socket
        socket = null // no new sends from here on
        open?.close(NORMAL_CLOSURE, null) // frames already queued are still written
        loop?.cancel()
        loop = null
        _state.value = UplinkState.STOPPED
    }

    /** True if [text] was handed to an open socket. Never blocks, never throws. */
    override fun send(text: String): Boolean {
        val s = socket ?: return false
        return try {
            s.send(text)
        } catch (_: RuntimeException) {
            false
        }
    }

    private suspend fun connectLoop() {
        val job = currentCoroutineContext()[Job]
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _state.value = UplinkState.CONNECTING
            val opened = CompletableDeferred<Boolean>()
            val closed = CompletableDeferred<Unit>()
            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (job?.isActive != true) { // stop() raced the handshake
                        webSocket.close(NORMAL_CLOSURE, null)
                        return
                    }
                    socket = webSocket
                    _state.value = UplinkState.CONNECTED
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
                // An open socket closes gracefully (OkHttp writes queued frames before the close
                // frame, with its own 60 s cap); a handshake still in flight is aborted.
                if (wasOpen) ws.close(NORMAL_CLOSURE, null) else ws.cancel()
                if (socket === ws) socket = null
            }
            _state.value = UplinkState.BACKING_OFF
            delay(backoff.delayMillis(attempt))
            if (attempt < Int.MAX_VALUE) attempt++
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val PING_SECONDS = 20L
    }
}
