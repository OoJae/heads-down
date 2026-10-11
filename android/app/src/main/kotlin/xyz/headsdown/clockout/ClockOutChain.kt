package xyz.headsdown.clockout

import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.clockout.BuyLeg
import xyz.headsdown.core.chain.clockout.ClockOutRequest
import xyz.headsdown.core.chain.clockout.ClockOutService
import xyz.headsdown.core.chain.clockout.PreparedClockOut
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

    override suspend fun quoteBuy(spendLamports: ULong): BuyQuote? =
        service.quoteBuy(spendLamports, BUY_SLIPPAGE_BPS)?.takeIf { it.minOutAmount > 0uL }?.let {
            BuyQuote(spendLamports, it.outAmount, it.minOutAmount, it.request.slippageBps, it.route)
        }

    override suspend fun prepare(authority: Pubkey, request: ClockOutRequest, capabilities: WalletCapabilities): PreparedClockOut? =
        service.prepare(authority, request, capabilities)
}

/** 1%: the most the price may move against the user between the quote on the sheet and the fill. */
const val BUY_SLIPPAGE_BPS = 100
