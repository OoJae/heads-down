package xyz.headsdown.core.chain.swap

import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.tx.Instruction
import java.io.IOException

/**
 * An exact-in swap: spend exactly [inAmount] base units of [inputMint] for as much [outputMint]
 * as the market gives, but never less than the slippage allows. SOL is the wrapped-SOL mint.
 */
data class SwapRequest(
    val inputMint: Pubkey,
    val outputMint: Pubkey,
    val inAmount: ULong,
    /** The most the price may move against the user between the quote and the fill. */
    val slippageBps: Int,
    /**
     * A hint to the provider to keep the route small (fewer accounts), for a swap that has to
     * share one transaction with other instructions. Null: the provider's default. It is only a
     * hint: whether the result fits is checked on the compiled transaction, never assumed.
     */
    val maxAccounts: Int? = null,
) {
    init {
        require(inputMint != outputMint) { "a swap needs two different mints" }
        require(inAmount > 0uL) { "nothing to swap" }
        require(slippageBps in 1..MAX_SLIPPAGE_BPS) { "slippage must be 1..$MAX_SLIPPAGE_BPS bps" }
        require(maxAccounts == null || maxAccounts in MIN_ROUTE_ACCOUNTS..MAX_ROUTE_ACCOUNTS) { "maxAccounts must be $MIN_ROUTE_ACCOUNTS..$MAX_ROUTE_ACCOUNTS" }
    }

    companion object {
        /** The app's slippage cap: 3%. A wider tolerance is a different product. */
        const val MAX_SLIPPAGE_BPS = 300
        const val DEFAULT_SLIPPAGE_BPS = 50
        const val MIN_ROUTE_ACCOUNTS = 16
        const val MAX_ROUTE_ACCOUNTS = 64
    }
}

/**
 * A provider's quote, already checked against the [request] it answers. [minOutAmount] is
 * computed on the device from [outAmount] and the request's slippage: it is what the on-chain
 * swap instruction is later required to enforce, whatever the provider claims.
 */
class SwapQuote(
    val request: SwapRequest,
    /** What the route pays at the quoted prices. */
    val outAmount: ULong,
    /** Price impact of this size on the route, in basis points, rounded up. Null: not reported. */
    val priceImpactBps: Int?,
    /** Venue names for display, sanitized. */
    val route: List<String>,
    /** The provider's fee on top of the pools' own, in basis points (0: none). */
    val platformFeeBps: Int,
    /** Opaque: what the provider needs back to build the instructions. Never shown or logged. */
    internal val providerPayload: String,
) {
    /** `floor(outAmount * (10,000 - slippage) / 10,000)`: the least the swap may deliver. */
    val minOutAmount: ULong get() = minOut(outAmount, request.slippageBps)

    /** Amounts only: a quote never prints mints, routes or its payload. */
    override fun toString(): String = "SwapQuote(in=${request.inAmount}, out=$outAmount, slippageBps=${request.slippageBps})"

    companion object {
        fun minOut(outAmount: ULong, slippageBps: Int): ULong {
            // out < 2^64 and the factor < 2^14: split so the product never overflows a u64.
            val keep = (10_000 - slippageBps).toULong()
            return outAmount / 10_000uL * keep + outAmount % 10_000uL * keep / 10_000uL
        }
    }
}

/**
 * The instructions of one swap, already validated by the provider against the quote: every one
 * is signed by the user alone. [lookupTables] are the address lookup tables a v0 message needs to
 * fit them into a packet.
 */
class SwapInstructions(
    setup: List<Instruction>,
    val swap: Instruction,
    cleanup: List<Instruction>,
    lookupTables: List<Pubkey>,
    /** The provider's compute-unit estimate for these instructions, when it gave one. */
    val computeUnitLimit: Long?,
) {
    val setup: List<Instruction> = setup.toList()
    val cleanup: List<Instruction> = cleanup.toList()
    val lookupTables: List<Pubkey> = lookupTables.toList()

    /** In execution order. */
    val all: List<Instruction> get() = setup + swap + cleanup
}

/**
 * The provider did not answer inside its protocol, or its answer failed a check. The message is
 * fixed text: never a URL, an address, an amount or a response body.
 */
class SwapProviderException(val reason: Reason) : IOException("swap provider: ${reason.name}") {
    enum class Reason {
        /** Unreachable, timed out or a non-2xx status. */
        UNAVAILABLE,

        /** Not the documented shape. */
        MALFORMED,

        /** A quote for other mints, another amount, mode or slippage than asked. */
        QUOTE_MISMATCH,

        /** A provider fee the app did not ask for. */
        UNEXPECTED_FEE,

        /** The price impact of this size is above the app's cap. */
        PRICE_IMPACT,

        /** An instruction outside the allowlist, a foreign signer, or swap terms that are not the quote's. */
        INSTRUCTIONS_REFUSED,
    }
}

/**
 * Where "buy the rest at market" and "pay in SKR" get their swap. The app uses Jupiter
 * ([JupiterSwapProvider]); tests use a fake. Both calls go over HTTPS, are never logged, and
 * fail closed: no quote means no swap.
 */
interface SwapProvider {
    /** A quote for [request], or null when there is no route. Throws [SwapProviderException] otherwise. */
    suspend fun quote(request: SwapRequest): SwapQuote?

    /**
     * The instructions that execute [quote] for [user], validated: only the user signs, and the
     * swap enforces at least [SwapQuote.minOutAmount].
     */
    suspend fun instructions(quote: SwapQuote, user: Pubkey): SwapInstructions
}
