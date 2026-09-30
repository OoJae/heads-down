package xyz.headsdown.core.chain.http

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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One JSON-over-HTTP reply. The body is bounded and is never logged or put in an exception. */
class HttpReply(val status: Int, val body: String) {
    val isSuccessful: Boolean get() = status in 200..299

    override fun toString(): String = "HttpReply(status=$status, ${body.length} chars)"
}

/** The service could not be reached or answered outside the protocol. Never carries a URL. */
class HttpTransportException(message: String) : IOException(message)

/**
 * GET and POST of JSON against one base URL (the registrar, the indexer). Paths are fixed
 * strings built by the clients from validated values (base58 addresses, decimal ids).
 */
interface JsonHttp {
    suspend fun get(path: String, bearer: String? = null): HttpReply

    suspend fun post(path: String, json: String, bearer: String? = null): HttpReply
}

/**
 * The request and response handling every [JsonHttp] shares: no redirects (a 3xx can never
 * downgrade or re-target a call), bounded bodies, cancellation cancels the call, and failures
 * surface only as the exception type (OkHttp messages can include the host).
 */
object HttpExchange {
    const val DEFAULT_MAX_RESPONSE_BYTES: Long = 1024L * 1024
    private val JSON = "application/json".toMediaType()
    private val SEGMENT = Regex("[A-Za-z0-9_.-]{1,128}")

    /** [base] plus [path] (`/a/b/c`), each segment checked, so a value can never add a query or `..`. */
    fun resolve(base: HttpUrl, path: String): HttpUrl {
        require(path.startsWith("/")) { "path must be absolute" }
        val builder = base.newBuilder()
        path.removePrefix("/").split('/').forEach { segment ->
            require(segment.matches(SEGMENT) && segment != "." && segment != "..") { "invalid path segment" }
            builder.addPathSegment(segment)
        }
        return builder.build()
    }

    fun request(url: HttpUrl, json: String?, bearer: String?): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
        .apply { if (json == null) get() else post(json.toRequestBody(JSON)) }
        .build()

    fun noRedirects(client: OkHttpClient): OkHttpClient =
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun execute(client: OkHttpClient, request: Request, maxBytes: Long = DEFAULT_MAX_RESPONSE_BYTES): HttpReply {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(HttpTransportException("transport failure: ${e.javaClass.simpleName}"))
                }

                override fun onResponse(call: Call, response: Response) {
                    runCatching { response.use { HttpReply(it.code, readBounded(it, maxBytes)) } }
                        .fold(cont::resume, cont::resumeWithException)
                }
            })
        }
    }

    /** Reads the body, refusing more than [maxBytes] while streaming. */
    fun readBounded(response: Response, maxBytes: Long): String {
        val body = response.body
        if (body.contentLength() > maxBytes) throw HttpTransportException("response exceeds $maxBytes bytes")
        val source = body.source()
        val buffer = Buffer()
        while (source.read(buffer, 8_192L) != -1L) {
            if (buffer.size > maxBytes) throw HttpTransportException("response exceeds $maxBytes bytes")
        }
        return buffer.readUtf8()
    }
}

/**
 * [JsonHttp] over HTTPS only: an `http:` base URL is refused at construction (the manifest also
 * forbids cleartext). The URL may name a path prefix but no user-info, query or fragment, where
 * credentials would hide. The `localdev` build adds a loopback-only cleartext variant in its own
 * source set.
 */
class OkHttpJsonHttp(
    baseUrl: String,
    client: OkHttpClient,
    private val maxResponseBytes: Long = HttpExchange.DEFAULT_MAX_RESPONSE_BYTES,
) : JsonHttp {
    private val base: HttpUrl = try {
        baseUrl.toHttpUrl()
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("service URL is not a valid URL")
    }
    private val client = HttpExchange.noRedirects(client)

    init {
        require(base.isHttps) { "service URL must use https" }
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) {
            "service URL must not carry credentials, a query or a fragment"
        }
    }

    override suspend fun get(path: String, bearer: String?): HttpReply =
        HttpExchange.execute(client, HttpExchange.request(HttpExchange.resolve(base, path), null, bearer), maxResponseBytes)

    override suspend fun post(path: String, json: String, bearer: String?): HttpReply =
        HttpExchange.execute(client, HttpExchange.request(HttpExchange.resolve(base, path), json, bearer), maxResponseBytes)
}
