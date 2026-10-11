package xyz.headsdown.clockout

import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.clockout.BuyLeg
import xyz.headsdown.core.chain.clockout.ClockOutRequest
import xyz.headsdown.core.chain.clockout.ClockOutService
import xyz.headsdown.core.chain.clockout.PreparedClockOut
import xyz.headsdown.core.chain.swap.SwapQuote
import xyz.headsdown.core.wallet.WalletCapabilities
import javax.inject.Inject
import javax.inject.Singleton

/** What the market gives for a spend right now, as the screen states it before the wallet opens. */
data class BuyQuote(
    val spendLamports: ULong,
    /** ORE (atoms) at the quoted prices. */
    val oreAtoms: ULong,
    /** The least ORE (atoms) the swap may deliver; under it nothing is bought. */
    val minOreAtoms: ULong,
    val slippageBps: Int,
    /** Venue names, for display. */
    val route: List<String> = emptyList(),
) {
    /** What the wallet is asked to sign for: this spend, and never less ORE than was shown. */
    fun leg(): BuyLeg = BuyLeg(spendLamports, minOreAtoms, slippageBps)
}

/** The chain side of a clock-out, as the screen needs it. [ChainClockOut] is the real one. */
interface ClockOutChain {
    /** This build can buy ORE at clock-out (it has a swap API). False: the sheet does not offer it. */
    val buyAvailable: Boolean get() = false

    /** Read-only: what a clock-out for [authority] would do now. */
    suspend fun preview(authority: Pubkey): ClockOutFacts

    /** Read-only: a quote for spending [spendLamports] of SOL on ORE. Null: no quote, so no buy. */
    suspend fun quoteBuy(spendLamports: ULong): BuyQuote? = null

    /** Builds what the wallet signs, inside the wallet session. Null: nothing to sign. */
    suspend fun prepare(authority: Pubkey, request: ClockOutRequest, capabilities: WalletCapabilities): PreparedClockOut?
}

@Singleton
class ChainClockOut @Inject constructor(private val service: ClockOutService) : ClockOutChain {
    override val buyAvailable: Boolean get() = service.buyAvailable

    override suspend fun preview(authority: Pubkey): ClockOutFacts = ClockOutFacts.from(service.preview(authority))

    /**
     * The minimum the sheet states is not the quote's own: when the wallet opens, the swap is
     * built from a fresh quote, and a fresh quote one atom below this one must not cancel the
     * buy. So the user is shown, and agrees to, [BUY_QUOTE_ROOM_BPS] less than this quote's
     * minimum. A fresh quote is used only if its own on-chain minimum is at least that
     * (`SwapLegBuilder`), so the swap can never deliver less than the sheet said.
     */
    override suspend fun quoteBuy(spendLamports: ULong): BuyQuote? {
        val quote = service.quoteBuy(spendLamports, BUY_SLIPPAGE_BPS) ?: return null
        val agreed = SwapQuote.minOut(quote.minOutAmount, BUY_QUOTE_ROOM_BPS)
        return if (agreed == 0uL) null else BuyQuote(spendLamports, quote.outAmount, agreed, quote.request.slippageBps, quote.route)
    }

    override suspend fun prepare(authority: Pubkey, request: ClockOutRequest, capabilities: WalletCapabilities): PreparedClockOut? =
        service.prepare(authority, request, capabilities)
}

/** 0.5%: the most the fill may fall short of the quote the swap is built from, enforced on-chain. */
const val BUY_SLIPPAGE_BPS = 50

/** 1%: how far a fresh quote may be below the one on the sheet before the buy is left out. */
const val BUY_QUOTE_ROOM_BPS = 100
