package xyz.headsdown.core.chain.swap

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.http.HttpReply
import xyz.headsdown.core.chain.swap.SwapProviderException.Reason
import xyz.headsdown.core.chain.tx.AccountMeta
import xyz.headsdown.core.chain.tx.Instruction
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64

/**
 * Jupiter's public Swap API (`/quote` and `/swap-instructions`), as the app uses it:
 *
 * - **Exact-in quotes only**, for the mints, amount and slippage asked. An answer for anything
 *   else, with a platform fee the app did not ask for, or with a price impact above
 *   [maxPriceImpactBps], is refused.
 * - **Instructions, not a transaction.** The app composes its own transaction, so Jupiter never
 *   chooses the fee payer, the blockhash or the other instructions. Every returned instruction
 *   goes through [JupiterGuard] before it is handed on.
 * - **Nothing is logged.** Not the quote, not the wallet, not a response body; failures carry a
 *   fixed reason only.
 *
 * A team proxy can front the same two paths; the checks here do not trust whoever answers.
 */
class JupiterSwapProvider(
    private val http: SwapHttp,
    /** Quotes moving the price by more than this are refused. */
    private val maxPriceImpactBps: Int = DEFAULT_MAX_PRICE_IMPACT_BPS,
) : SwapProvider {

    init {
        require(maxPriceImpactBps in 1..10_000)
    }

    override suspend fun quote(request: SwapRequest): SwapQuote? {
        val reply = exchange {
            http.get(
                "/quote",
                listOfNotNull(
                    "inputMint" to request.inputMint.toBase58(),
                    "outputMint" to request.outputMint.toBase58(),
                    "amount" to request.inAmount.toString(),
                    "slippageBps" to request.slippageBps.toString(),
                    "swapMode" to "ExactIn",
                    // Routes through thinly traded intermediate tokens fail more often than they help.
                    "restrictIntermediateTokens" to "true",
                    // A leaner route, so the swap can share a packet with the app's own instructions.
                    request.maxAccounts?.let { "maxAccounts" to it.toString() },
                ),
            )
        }
        // Jupiter answers 400 with an error code when it has no route for the pair or the size.
        if (reply.status == 400 || reply.status == 404) return null
        if (!reply.isSuccessful) throw SwapProviderException(Reason.UNAVAILABLE)
        return parseQuote(reply.body, request, maxPriceImpactBps)
    }

    override suspend fun instructions(quote: SwapQuote, user: Pubkey): SwapInstructions {
        val payload = try {
            Json.parseToJsonElement(quote.providerPayload) as JsonObject
        } catch (_: Exception) {
            throw SwapProviderException(Reason.MALFORMED)
        }
        val body = buildJsonObject {
            put("userPublicKey", user.toBase58())
            put("quoteResponse", payload)
            put("wrapAndUnwrapSol", true)
            put("useSharedAccounts", true)
            put("dynamicComputeUnitLimit", true)
            put("asLegacyTransaction", false)
        }
        val reply = exchange { http.post("/swap-instructions", body.toString()) }
        if (!reply.isSuccessful) throw SwapProviderException(Reason.UNAVAILABLE)
        val parsed = parseInstructions(reply.body)
        JupiterGuard.check(quote, parsed, user)
        return parsed
    }

    private suspend fun exchange(call: suspend () -> HttpReply): HttpReply = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        throw SwapProviderException(Reason.UNAVAILABLE)
    }

    companion object {
        /** 2%: beyond this the size is wrong for the pool, and "at market" would be a lie. */
        const val DEFAULT_MAX_PRICE_IMPACT_BPS = 200

        const val MAX_ROUTE_STEPS = 8
        const val MAX_SETUP_INSTRUCTIONS = 8
        const val MAX_ACCOUNTS_PER_INSTRUCTION = 96
        const val MAX_INSTRUCTION_DATA = 1_024
        const val MAX_LOOKUP_TABLES = 8
        private const val MAX_COMPUTE_UNITS = 1_400_000L
        private val LABEL = Regex("[A-Za-z0-9 ._+()-]{1,32}")
        private val DECIMAL = Regex("[0-9]{1,20}")
        private val FRACTION = Regex("[0-9]{1,6}(\\.[0-9]{1,40})?")

        /** Parses and checks a `/quote` answer for [request]. Public for tests. */
        fun parseQuote(body: String, request: SwapRequest, maxPriceImpactBps: Int = DEFAULT_MAX_PRICE_IMPACT_BPS): SwapQuote {
            val o = json(body)
            if (o.pubkey("inputMint") != request.inputMint || o.pubkey("outputMint") != request.outputMint) throw SwapProviderException(Reason.QUOTE_MISMATCH)
            if (o.u64("inAmount") != request.inAmount) throw SwapProviderException(Reason.QUOTE_MISMATCH)
            if (o.string("swapMode") != "ExactIn") throw SwapProviderException(Reason.QUOTE_MISMATCH)
            if (o.int("slippageBps") != request.slippageBps) throw SwapProviderException(Reason.QUOTE_MISMATCH)
            val out = o.u64("outAmount")
            if (out == 0uL) throw SwapProviderException(Reason.MALFORMED)
            // The provider's own threshold must not be below what the slippage allows.
            val threshold = o.u64("otherAmountThreshold")
            if (threshold > out || threshold < SwapQuote.minOut(out, request.slippageBps)) throw SwapProviderException(Reason.QUOTE_MISMATCH)
            val fee = o["platformFee"]
            if (fee != null && fee !is JsonNull) {
                val feeBps = (fee as? JsonObject)?.get("feeBps")?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                val amount = (fee as? JsonObject)?.get("amount")?.let { (it as? JsonPrimitive)?.content }
                // The app asks for no platform fee: any fee here is one the user was not shown.
                if (feeBps != 0 || (amount != null && amount != "0")) throw SwapProviderException(Reason.UNEXPECTED_FEE)
            }
            val impact = o["priceImpactPct"]?.takeUnless { it is JsonNull }?.let { e ->
                val text = (e as? JsonPrimitive)?.content ?: throw SwapProviderException(Reason.MALFORMED)
                if (!text.matches(FRACTION)) throw SwapProviderException(Reason.MALFORMED)
                // A fraction (0.004 = 0.4%), rounded up to whole basis points.
                BigDecimal(text).movePointRight(4).setScale(0, RoundingMode.CEILING).min(BigDecimal.valueOf(10_000)).toInt()
            }
            if (impact != null && impact > maxPriceImpactBps) throw SwapProviderException(Reason.PRICE_IMPACT)
            val plan = o["routePlan"] as? JsonArray ?: throw SwapProviderException(Reason.MALFORMED)
            if (plan.isEmpty() || plan.size > MAX_ROUTE_STEPS) throw SwapProviderException(Reason.MALFORMED)
            val route = plan.map { step ->
                val label = ((step as? JsonObject)?.get("swapInfo") as? JsonObject)?.get("label")
                (label as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.matches(LABEL) } ?: "another venue"
            }
            return SwapQuote(request, out, impact, route, platformFeeBps = 0, providerPayload = o.toString())
        }

        /** Parses a `/swap-instructions` answer. Shape only: [JupiterGuard] decides what is acceptable. */
        fun parseInstructions(body: String): SwapInstructions {
            val o = json(body)
            // A token-ledger instruction or extra instructions belong to features the app never asks for.
            if (o["tokenLedgerInstruction"].let { it != null && it !is JsonNull }) throw SwapProviderException(Reason.INSTRUCTIONS_REFUSED)
            val other = o["otherInstructions"]
            if (other != null && other !is JsonNull && (other as? JsonArray)?.isEmpty() != true) throw SwapProviderException(Reason.INSTRUCTIONS_REFUSED)
            val setup = (o["setupInstructions"] as? JsonArray ?: throw SwapProviderException(Reason.MALFORMED))
            if (setup.size > MAX_SETUP_INSTRUCTIONS) throw SwapProviderException(Reason.MALFORMED)
            val swap = instruction(o["swapInstruction"] ?: throw SwapProviderException(Reason.MALFORMED))
            val cleanup = o["cleanupInstruction"]?.takeUnless { it is JsonNull }?.let { listOf(instruction(it)) }.orEmpty()
            val tables = (o["addressLookupTableAddresses"] as? JsonArray ?: JsonArray(emptyList()))
            if (tables.size > MAX_LOOKUP_TABLES) throw SwapProviderException(Reason.MALFORMED)
            val limit = (o["computeUnitLimit"] as? JsonPrimitive)?.takeUnless { it is JsonNull || it.isString }?.content?.toLongOrNull()
                ?.takeIf { it in 1..MAX_COMPUTE_UNITS }
            return SwapInstructions(
                setup = setup.map(::instruction),
                swap = swap,
                cleanup = cleanup,
                lookupTables = tables.map { pubkey((it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content) }.distinct(),
                computeUnitLimit = limit,
            )
        }

        private fun instruction(e: JsonElement): Instruction {
            val o = e as? JsonObject ?: throw SwapProviderException(Reason.MALFORMED)
            val accounts = o["accounts"] as? JsonArray ?: throw SwapProviderException(Reason.MALFORMED)
            if (accounts.size > MAX_ACCOUNTS_PER_INSTRUCTION) throw SwapProviderException(Reason.MALFORMED)
            val data = try {
                Base64.getDecoder().decode(o.string("data"))
            } catch (_: IllegalArgumentException) {
                throw SwapProviderException(Reason.MALFORMED)
            }
            if (data.size > MAX_INSTRUCTION_DATA) throw SwapProviderException(Reason.MALFORMED)
            return Instruction(
                o.pubkey("programId"),
                accounts.map { a ->
                    val m = a as? JsonObject ?: throw SwapProviderException(Reason.MALFORMED)
                    AccountMeta(m.pubkey("pubkey"), m.bool("isSigner"), m.bool("isWritable"))
                },
                data,
            )
        }

        private fun json(body: String): JsonObject = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw SwapProviderException(Reason.MALFORMED)

        private fun JsonObject.string(key: String): String =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw SwapProviderException(Reason.MALFORMED)

        private fun JsonObject.pubkey(key: String): Pubkey = pubkey((this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content)

        private fun pubkey(text: String?): Pubkey = try {
            Pubkey.fromBase58(text ?: throw SwapProviderException(Reason.MALFORMED))
        } catch (_: IllegalArgumentException) {
            throw SwapProviderException(Reason.MALFORMED)
        }

        /** Amounts arrive as decimal strings. */
        private fun JsonObject.u64(key: String): ULong {
            val text = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: throw SwapProviderException(Reason.MALFORMED)
            if (!text.matches(DECIMAL)) throw SwapProviderException(Reason.MALFORMED)
            return text.toULongOrNull() ?: throw SwapProviderException(Reason.MALFORMED)
        }

        private fun JsonObject.int(key: String): Int {
            val p = this[key] as? JsonPrimitive ?: throw SwapProviderException(Reason.MALFORMED)
            if (p is JsonNull || p.isString) throw SwapProviderException(Reason.MALFORMED)
            return p.content.toIntOrNull() ?: throw SwapProviderException(Reason.MALFORMED)
        }

        private fun JsonObject.bool(key: String): Boolean {
            val p = this[key] as? JsonPrimitive ?: throw SwapProviderException(Reason.MALFORMED)
            if (p.isString) throw SwapProviderException(Reason.MALFORMED)
            return p.content.toBooleanStrictOrNull() ?: throw SwapProviderException(Reason.MALFORMED)
        }
    }
}
