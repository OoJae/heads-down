package xyz.headsdown.core.chain.swap

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.TlsServer
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.swap.SwapProviderException.Reason
import java.util.concurrent.TimeUnit

/**
 * Jupiter's quote and swap-instructions API against a fake server (MockWebServer over TLS) that
 * replays live answers: the exact requests the phone sends, what it accepts, and how it fails
 * closed.
 */
class JupiterSwapProviderTest {

    private val tls = TlsServer()
    private val provider = JupiterSwapProvider(OkHttpSwapHttp(tls.url("/swap/v1"), tls.client))
    private val fixture = JupiterFixture.solOre

    @After
    fun close() = tls.close()

    private fun respond(code: Int, body: String) =
        tls.server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())

    private fun reason(block: suspend () -> Unit): Reason =
        (runCatching { runBlocking { block() } }.exceptionOrNull() as SwapProviderException).reason

    /** The live quote with one top-level field replaced (raw JSON value). */
    private fun quoteWith(field: String, value: String): String {
        val o = Json.parseToJsonElement(fixture.quoteBody).jsonObject
        return "{" + o.entries.joinToString(",") { (k, v) -> "\"$k\":" + if (k == field) value else v.toString() } + "}"
    }

    @Test
    fun `quote asks for an exact-in SOL to ORE swap and parses the live answer`() = runBlocking {
        respond(200, fixture.quoteBody)
        val quote = provider.quote(fixture.request)!!
        val sent = tls.server.takeRequest()
        assertEquals("GET", sent.method)
        assertEquals("/swap/v1/quote", sent.url.encodedPath)
        assertEquals(WellKnown.WRAPPED_SOL_MINT.toBase58(), sent.url.queryParameter("inputMint"))
        assertEquals(Ore.MINT.toBase58(), sent.url.queryParameter("outputMint"))
        assertEquals("15200000", sent.url.queryParameter("amount"))
        assertEquals("50", sent.url.queryParameter("slippageBps"))
        assertEquals("ExactIn", sent.url.queryParameter("swapMode"))
        assertEquals("true", sent.url.queryParameter("restrictIntermediateTokens"))
        assertEquals(6, sent.url.querySize)
        // No credentials of any kind go to the provider.
        assertNull(sent.headers["Authorization"])
        assertNull(sent.headers["Cookie"])

        assertEquals(fixture.request, quote.request)
        assertEquals(1_827_100_837uL, quote.outAmount) // 0.01827 ORE for 0.0152 SOL
        // The floor is computed on the device: floor(out * 9950 / 10000). Jupiter rounded its own up.
        assertEquals(1_817_965_332uL, quote.minOutAmount)
        assertEquals(41, quote.priceImpactBps) // 0.40015 % rounded up
        assertEquals(listOf("Meteora DLMM"), quote.route)
        assertEquals(0, quote.platformFeeBps)
    }

    @Test
    fun `instructions post the wallet and the quote, and come back validated`() = runBlocking {
        respond(200, fixture.swapBody)
        val ix = provider.instructions(fixture.quote, fixture.user)
        val sent = tls.server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/swap/v1/swap-instructions", sent.url.encodedPath)
        assertEquals(0, sent.url.querySize)
        val body = Json.parseToJsonElement(sent.body!!.utf8()).jsonObject
        assertEquals(fixture.user.toBase58(), body["userPublicKey"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(fixture.quoteBody), body["quoteResponse"])
        assertEquals("true", body["wrapAndUnwrapSol"]!!.jsonPrimitive.content)
        assertEquals("false", body["asLegacyTransaction"]!!.jsonPrimitive.content)
        assertEquals(setOf("userPublicKey", "quoteResponse", "wrapAndUnwrapSol", "useSharedAccounts", "dynamicComputeUnitLimit", "asLegacyTransaction"), body.keys)

        // Wrap SOL (create wSOL account, transfer, sync), create the ORE account, swap, unwrap.
        assertEquals(
            listOf(WellKnown.ASSOCIATED_TOKEN, WellKnown.SYSTEM_PROGRAM, WellKnown.SPL_TOKEN, WellKnown.ASSOCIATED_TOKEN),
            ix.setup.map { it.programId },
        )
        assertEquals(JupiterGuard.JUPITER_V6, ix.swap.programId)
        assertEquals(33, ix.swap.accounts.size)
        assertEquals(listOf(WellKnown.SPL_TOKEN), ix.cleanup.map { it.programId })
        assertEquals(listOf("DnwaKnJk8zmnSywMErVBKDtfZGMCNWvGkxZ4eyAUXpSe"), ix.lookupTables.map { it.toBase58() })
        assertEquals(1_400_000L, ix.computeUnitLimit)
        assertEquals(6, ix.all.size)
        // Jupiter's own compute-budget instructions are not passed on: the app sets its own.
        assertTrue(ix.all.none { it.programId == WellKnown.COMPUTE_BUDGET })
    }

    @Test
    fun `an SKR to SOL swap for a gift parses and validates too`() = runBlocking {
        val gift = JupiterFixture.skrSol
        respond(200, gift.quoteBody)
        val quote = provider.quote(gift.request)!!
        assertEquals(Skr.MINT, quote.request.inputMint)
        assertEquals(500uL * Skr.ONE_SKR, quote.request.inAmount)
        assertEquals(75_989_714uL, quote.outAmount) // 0.0760 SOL for 500 SKR
        assertEquals(75_609_765uL, quote.minOutAmount)
        assertEquals(listOf("HumidiFi", "ZeroFi", "Raydium CLMM"), quote.route)
        respond(200, gift.swapBody)
        val ix = provider.instructions(quote, gift.user)
        assertEquals(63, ix.swap.accounts.size)
        assertEquals(3, ix.lookupTables.size)
        // The output is wrapped SOL: one account to create, and a close that unwraps it to the wallet.
        assertEquals(listOf(WellKnown.ASSOCIATED_TOKEN), ix.setup.map { it.programId })
        assertEquals(1, ix.cleanup.size)
    }

    @Test
    fun `a route-size hint is sent as maxAccounts and gets a smaller route`() = runBlocking {
        // The same 500 SKR, asked with maxAccounts=32 so the swap can share a packet with a gift.
        val lean = JupiterFixture.skrSolGift
        assertEquals(32, lean.request.maxAccounts)
        respond(200, lean.quoteBody)
        val quote = provider.quote(lean.request)!!
        val sent = tls.server.takeRequest()
        assertEquals("32", sent.url.queryParameter("maxAccounts"))
        assertEquals(7, sent.url.querySize)
        assertEquals(listOf("HumidiFi", "Kipseli"), quote.route)
        assertEquals(77_211_172uL, quote.outAmount)
        respond(200, lean.swapBody)
        val ix = provider.instructions(quote, lean.user)
        assertEquals(49, ix.swap.accounts.size) // 63 without the hint
        assertEquals(2, ix.lookupTables.size)
        // The hint is bounded: it cannot be used to ask for a degenerate or unbounded route.
        assertThrows(IllegalArgumentException::class.java) { lean.request.copy(maxAccounts = 15) }
        assertThrows(IllegalArgumentException::class.java) { lean.request.copy(maxAccounts = 65) }
        assertNull(fixture.request.maxAccounts)
    }

    @Test
    fun `no route is no quote, and a failing provider never becomes one`() = runBlocking {
        respond(400, """{"error":"Could not find any route","errorCode":"COULD_NOT_FIND_ANY_ROUTE"}""")
        assertNull(provider.quote(fixture.request))
        respond(404, "")
        assertNull(provider.quote(fixture.request))
        for (status in listOf(401, 429, 500, 503)) {
            respond(status, """{"error":"nope"}""")
            assertEquals("$status", Reason.UNAVAILABLE, reason { provider.quote(fixture.request) })
        }
        // A redirect is not followed (it could downgrade or re-target the call).
        tls.server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://example.org/quote").build())
        assertEquals(Reason.UNAVAILABLE, reason { provider.quote(fixture.request) })
        assertEquals(7, tls.server.requestCount)
        respond(500, "boom")
        assertEquals(Reason.UNAVAILABLE, reason { provider.instructions(fixture.quote, fixture.user) })
    }

    @Test
    fun `a stalled or broken connection is unavailable, with no detail`() {
        val quick = tls.client.newBuilder().callTimeout(300, TimeUnit.MILLISECONDS).build()
        val impatient = JupiterSwapProvider(OkHttpSwapHttp(tls.url("/swap/v1"), quick))
        tls.server.enqueue(MockResponse.Builder().body(fixture.quoteBody).headersDelay(3, TimeUnit.SECONDS).build())
        val e = runCatching { runBlocking { impatient.quote(fixture.request) } }.exceptionOrNull() as SwapProviderException
        assertEquals(Reason.UNAVAILABLE, e.reason)
        // Nothing of the request (mints, amount, host) leaks into the exception.
        assertEquals("swap provider: UNAVAILABLE", e.message)
        assertNull(e.cause)
        // A server presenting a certificate the client does not trust.
        val untrusting = JupiterSwapProvider(OkHttpSwapHttp(tls.url("/swap/v1"), OkHttpClient()))
        assertEquals(Reason.UNAVAILABLE, reason { untrusting.quote(fixture.request) })
    }

    @Test
    fun `a quote that is not the one asked for is refused`() {
        val cases = mapOf(
            quoteWith("inputMint", "\"${Skr.MINT}\"") to Reason.QUOTE_MISMATCH,
            quoteWith("outputMint", "\"${Skr.MINT}\"") to Reason.QUOTE_MISMATCH,
            quoteWith("inAmount", "\"15200001\"") to Reason.QUOTE_MISMATCH,
            quoteWith("swapMode", "\"ExactOut\"") to Reason.QUOTE_MISMATCH,
            quoteWith("slippageBps", "500") to Reason.QUOTE_MISMATCH,
            // A threshold below what 0.5 % allows, or above the quote itself.
            quoteWith("otherAmountThreshold", "\"1000000000\"") to Reason.QUOTE_MISMATCH,
            quoteWith("otherAmountThreshold", "\"1827100838\"") to Reason.QUOTE_MISMATCH,
            quoteWith("outAmount", "\"0\"") to Reason.MALFORMED,
            quoteWith("outAmount", "\"-5\"") to Reason.MALFORMED,
            quoteWith("outAmount", "1.5e9") to Reason.MALFORMED,
            quoteWith("inputMint", "\"not-base58!\"") to Reason.MALFORMED,
            quoteWith("routePlan", "[]") to Reason.MALFORMED,
            quoteWith("priceImpactPct", "\"NaN\"") to Reason.MALFORMED,
            "[]" to Reason.MALFORMED,
            "not json" to Reason.MALFORMED,
            "" to Reason.MALFORMED,
        )
        for ((body, want) in cases) {
            val got = (runCatching { JupiterSwapProvider.parseQuote(body, fixture.request) }.exceptionOrNull() as SwapProviderException).reason
            assertEquals(body.take(80), want, got)
        }
    }

    @Test
    fun `a provider fee the app did not ask for and a large price impact are refused`() {
        fun parse(body: String, maxImpact: Int = 200) = JupiterSwapProvider.parseQuote(body, fixture.request, maxImpact)
        val fee = assertThrows(SwapProviderException::class.java) { parse(quoteWith("platformFee", """{"amount":"1000","feeBps":25}""")) }
        assertEquals(Reason.UNEXPECTED_FEE, fee.reason)
        // An explicit zero fee is no fee.
        assertEquals(0, parse(quoteWith("platformFee", """{"amount":"0","feeBps":0}""")).platformFeeBps)
        // 2.01 % impact against a 2 % cap; exactly 2 % passes.
        assertEquals(Reason.PRICE_IMPACT, assertThrows(SwapProviderException::class.java) { parse(quoteWith("priceImpactPct", "\"0.0201\"")) }.reason)
        assertEquals(200, parse(quoteWith("priceImpactPct", "\"0.02\"")).priceImpactBps)
        assertEquals(1, parse(quoteWith("priceImpactPct", "\"0.00000001\"")).priceImpactBps)
        assertEquals(0, parse(quoteWith("priceImpactPct", "\"0\"")).priceImpactBps)
        assertNull(parse(quoteWith("priceImpactPct", "null")).priceImpactBps)
        // The live quote under a tighter cap.
        assertEquals(Reason.PRICE_IMPACT, assertThrows(SwapProviderException::class.java) { parse(fixture.quoteBody, maxImpact = 40) }.reason)
    }

    @Test
    fun `venue names are shown only when they are plain text`() {
        val odd = fixture.quoteBody.replace("\"label\":\"Meteora DLMM\"", "\"label\":\"<script>alert(1)</script>\"")
        assertEquals(listOf("another venue"), JupiterSwapProvider.parseQuote(odd, fixture.request).route)
    }

    @Test
    fun `a quote prints amounts only, never mints, routes or its payload`() {
        val text = fixture.quote.toString()
        assertEquals("SwapQuote(in=15200000, out=1827100837, slippageBps=50)", text)
        assertFalse(text.contains(Ore.MINT.toBase58()))
        assertFalse(text.contains("Meteora"))
    }

    @Test
    fun `the minimum output is the floor of the slippage, for any size`() {
        assertEquals(995uL, SwapQuote.minOut(1_000uL, 50))
        assertEquals(0uL, SwapQuote.minOut(1uL, 50))
        assertEquals(9_949uL, SwapQuote.minOut(9_999uL, 50)) // 9949.005 floors
        assertEquals(1_817_965_332uL, SwapQuote.minOut(1_827_100_837uL, 50))
        // No overflow at the top of u64.
        assertEquals(ULong.MAX_VALUE / 10_000uL * 9_700uL + ULong.MAX_VALUE % 10_000uL * 9_700uL / 10_000uL, SwapQuote.minOut(ULong.MAX_VALUE, 300))
    }

    @Test
    fun `requests and endpoints are validated before anything is sent`() {
        val sol = WellKnown.WRAPPED_SOL_MINT
        assertThrows(IllegalArgumentException::class.java) { SwapRequest(sol, sol, 1uL, 50) }
        assertThrows(IllegalArgumentException::class.java) { SwapRequest(sol, Ore.MINT, 0uL, 50) }
        assertThrows(IllegalArgumentException::class.java) { SwapRequest(sol, Ore.MINT, 1uL, 0) }
        // The app never trades with more than 3 % slippage.
        assertThrows(IllegalArgumentException::class.java) { SwapRequest(sol, Ore.MINT, 1uL, 301) }
        SwapRequest(sol, Ore.MINT, 1uL, 300)
        // HTTPS only, and nowhere for a key to hide.
        assertThrows(IllegalArgumentException::class.java) { OkHttpSwapHttp("http://lite-api.jup.ag/swap/v1", tls.client) }
        assertThrows(IllegalArgumentException::class.java) { OkHttpSwapHttp("https://lite-api.jup.ag/swap/v1?api-key=x", tls.client) }
        assertThrows(IllegalArgumentException::class.java) { OkHttpSwapHttp("https://user:pw@lite-api.jup.ag/swap/v1", tls.client) }
        assertThrows(IllegalArgumentException::class.java) { OkHttpSwapHttp("not a url", tls.client) }
        // A query value can never smuggle a second parameter or a path.
        val http = OkHttpSwapHttp(tls.url("/swap/v1"), tls.client)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { http.get("/quote", listOf("amount" to "1&feeAccount=x")) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { http.get("/quote", listOf("a b" to "1")) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { http.get("/../quote", emptyList()) } }
        assertEquals(0, tls.server.requestCount)
    }

    @Test
    fun `an oversized answer is refused while streaming`() {
        val small = JupiterSwapProvider(OkHttpSwapHttp(tls.url("/swap/v1"), tls.client, maxResponseBytes = 1_024))
        respond(200, fixture.quoteBody + " ".repeat(2_000))
        assertEquals(Reason.UNAVAILABLE, reason { small.quote(fixture.request) })
    }

    @Test
    fun `instructions outside the documented shape are refused`() {
        fun swapWith(field: String, value: String): String {
            val o = Json.parseToJsonElement(fixture.swapBody).jsonObject
            return "{" + o.entries.joinToString(",") { (k, v) -> "\"$k\":" + if (k == field) value else v.toString() } + "}"
        }
        fun parseReason(body: String) = (runCatching { JupiterSwapProvider.parseInstructions(body) }.exceptionOrNull() as SwapProviderException).reason
        assertEquals(Reason.MALFORMED, parseReason("{}"))
        assertEquals(Reason.MALFORMED, parseReason(swapWith("swapInstruction", "null")))
        assertEquals(Reason.MALFORMED, parseReason(swapWith("swapInstruction", """{"programId":"x","accounts":[],"data":""}""")))
        assertEquals(Reason.MALFORMED, parseReason(swapWith("swapInstruction", """{"programId":"${Ore.PROGRAM_ID}","accounts":[],"data":"***"}""")))
        // Features the app never asks for: a token ledger, extra (tip) instructions.
        val anyIx = """{"programId":"${Ore.PROGRAM_ID}","accounts":[],"data":""}"""
        assertEquals(Reason.INSTRUCTIONS_REFUSED, parseReason(swapWith("tokenLedgerInstruction", anyIx)))
        assertEquals(Reason.INSTRUCTIONS_REFUSED, parseReason(swapWith("otherInstructions", "[$anyIx]")))
        // Too many of anything.
        assertEquals(Reason.MALFORMED, parseReason(swapWith("setupInstructions", "[" + List(9) { anyIx }.joinToString(",") + "]")))
        val tables = List(9) { "\"${Pubkey(ByteArray(32) { i -> (it + i).toByte() })}\"" }.joinToString(",")
        assertEquals(Reason.MALFORMED, parseReason(swapWith("addressLookupTableAddresses", "[$tables]")))
        // A compute-unit figure outside the runtime's range is ignored, not trusted.
        assertNull(JupiterSwapProvider.parseInstructions(swapWith("computeUnitLimit", "99999999")).computeUnitLimit)
        assertNull(JupiterSwapProvider.parseInstructions(swapWith("computeUnitLimit", "null")).computeUnitLimit)
    }
}
