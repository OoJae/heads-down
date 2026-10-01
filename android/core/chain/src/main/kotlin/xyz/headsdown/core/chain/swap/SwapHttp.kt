package xyz.headsdown.core.chain.swap

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import xyz.headsdown.core.chain.http.HttpExchange
import xyz.headsdown.core.chain.http.HttpReply

/**
 * The two calls a swap provider needs: a GET with query parameters (the quote) and a JSON POST
 * (the instructions). Separate from `JsonHttp`, whose paths can never carry a query.
 */
interface SwapHttp {
    suspend fun get(path: String, query: List<Pair<String, String>>): HttpReply

    suspend fun post(path: String, json: String): HttpReply
}

/**
 * [SwapHttp] over HTTPS only, with the same posture as every other client here: no redirects,
 * bounded responses, cancellation cancels the call, and nothing is ever logged or put into an
 * exception (a quote request names the mints and the amount; the instructions request names the
 * wallet). The base URL may carry a path prefix but no user-info, query or fragment; query
 * parameter names and values are restricted to `[A-Za-z0-9_.-]`, so a value can never smuggle a
 * second parameter.
 */
class OkHttpSwapHttp(
    baseUrl: String,
    client: OkHttpClient,
    private val maxResponseBytes: Long = MAX_RESPONSE_BYTES,
) : SwapHttp {
    private val base: HttpUrl = try {
        baseUrl.toHttpUrl()
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("swap URL is not a valid URL")
    }
    private val client = HttpExchange.noRedirects(client)

    init {
        require(base.isHttps) { "swap URL must use https" }
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) {
            "swap URL must not carry credentials, a query or a fragment"
        }
    }

    override suspend fun get(path: String, query: List<Pair<String, String>>): HttpReply {
        val builder = HttpExchange.resolve(base, path).newBuilder()
        for ((name, value) in query) {
            require(name.matches(TOKEN) && value.matches(TOKEN)) { "invalid query parameter" }
            builder.addQueryParameter(name, value)
        }
        return HttpExchange.execute(client, HttpExchange.request(builder.build(), null, null), maxResponseBytes)
    }

    override suspend fun post(path: String, json: String): HttpReply =
        HttpExchange.execute(client, HttpExchange.request(HttpExchange.resolve(base, path), json, null), maxResponseBytes)

    companion object {
        /** A swap-instructions answer is about 10 kB; a quote about 2 kB. */
        const val MAX_RESPONSE_BYTES: Long = 256L * 1024
        private val TOKEN = Regex("[A-Za-z0-9_.-]{1,64}")
    }
}
