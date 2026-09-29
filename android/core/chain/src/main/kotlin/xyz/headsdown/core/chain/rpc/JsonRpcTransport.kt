package xyz.headsdown.core.chain.rpc

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Posts one JSON-RPC request body and returns the response body. */
fun interface JsonRpcTransport {
    suspend fun post(body: String): String
}

/** Base of every RPC failure. An [IOException], so callers treat it like any network error. */
open class RpcException(message: String) : IOException(message)

/** Non-2xx HTTP status. The body is never read or kept (it could echo the request). */
class RpcHttpException(val status: Int) : RpcException("RPC HTTP status $status")

/** The server sent more than the client's cap: refused instead of buffering it. */
class RpcResponseTooLargeException(limit: Long) : RpcException("RPC response exceeds $limit bytes")

/** The response was not the JSON-RPC shape the method promises. */
class RpcProtocolException(what: String) : RpcException("malformed RPC response: $what")

/** A JSON-RPC `error` object. [detail] is truncated and must never be logged. */
class RpcErrorException(val code: Long, val detail: String) : RpcException("RPC error $code")

/**
 * JSON-RPC over OkHttp, hardened for a phone:
 * - **HTTPS only**: an `http:` endpoint is refused at construction (the manifest also forbids
 *   cleartext), and redirects are not followed, so a 3xx can never downgrade or re-target it.
 * - **Timeouts** on connect, read, write and the whole call.
 * - **No logging**: no interceptors, and nothing here ever prints a URL, header or body. The
 *   endpoint can carry a provider key in its query, which is why it is never logged or echoed
 *   into exception messages.
 * - **Bounded responses**: bodies above [maxResponseBytes] are refused while streaming.
 * - **Cancellable**: coroutine cancellation cancels the HTTP call.
 */
class OkHttpJsonRpcTransport(
    endpoint: String,
    private val client: OkHttpClient = defaultClient(),
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) : JsonRpcTransport {

    private val url: HttpUrl = try {
        endpoint.toHttpUrl()
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("RPC endpoint is not a valid URL")
    }

    init {
        require(url.isHttps) { "RPC endpoint must use https" }
        require(maxResponseBytes > 0)
    }

    override suspend fun post(body: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // OkHttp's messages can include the host; surface only the exception type.
                    cont.resumeWithException(RpcException("RPC transport failure: ${e.javaClass.simpleName}"))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use { read(it) } }
                    result.fold(cont::resume, cont::resumeWithException)
                }
            })
        }
    }

    private fun read(response: Response): String {
        if (!response.isSuccessful) throw RpcHttpException(response.code)
        val body = response.body
        val declared = body.contentLength()
        if (declared > maxResponseBytes) throw RpcResponseTooLargeException(maxResponseBytes)
        val source = body.source()
        val buffer = Buffer()
        while (true) {
            val read = source.read(buffer, READ_CHUNK)
            if (read == -1L) break
            if (buffer.size > maxResponseBytes) throw RpcResponseTooLargeException(maxResponseBytes)
        }
        return buffer.readUtf8()
    }

    companion object {
        /** getProgramAccounts on a small program or a batch of 100 accounts fits well within this. */
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 4L * 1024 * 1024
        private const val READ_CHUNK = 8_192L
        private val JSON = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
