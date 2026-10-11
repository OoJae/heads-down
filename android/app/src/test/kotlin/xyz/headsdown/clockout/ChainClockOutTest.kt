package xyz.headsdown.clockout

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.clockout.ClockOutService
import xyz.headsdown.core.chain.rpc.JsonRpcTransport
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.chain.swap.SwapInstructions
import xyz.headsdown.core.chain.swap.SwapProvider
import xyz.headsdown.core.chain.swap.SwapProviderException
import xyz.headsdown.core.chain.swap.SwapQuote
import xyz.headsdown.core.chain.swap.SwapRequest

/**
 * The quote the clock-out sheet states, against the quote the swap is later built from. The swap
 * layer leaves a buy out when the fresh quote's on-chain minimum is below what the user agreed to
 * (`SwapLegBuilder`: `quote.minOutAmount < minOut`), so what the sheet asks the user to agree to
 * has to leave room for the market to tick, and must still be a floor the swap cannot go under.
 */
class ChainClockOutTest {

    /** A market that quotes [out] atoms for any spend, or nothing, or fails. */
    private class Market(var out: ULong?, var fails: Boolean = false) : SwapProvider {
        val asked = mutableListOf<SwapRequest>()

        override suspend fun quote(request: SwapRequest): SwapQuote? {
            asked += request
            if (fails) throw SwapProviderException(SwapProviderException.Reason.UNAVAILABLE)
            return out?.let { SwapQuote(request, it, priceImpactBps = 1, route = listOf("Meteora DLMM"), platformFeeBps = 0, providerPayload = "{}") }
        }

        override suspend fun instructions(quote: SwapQuote, user: Pubkey): SwapInstructions = error("a quote for the sheet builds no instructions")
    }

    private val noRpc = SolanaJsonRpc(JsonRpcTransport { error("a quote for the sheet reads no chain") })

    private fun chain(market: SwapProvider?) = ChainClockOut(ClockOutService(noRpc, market))

    @Test
    fun `the sheet's minimum is one percent under the quote's own, at half a percent of slippage`() = runBlocking {
        val market = Market(out = 96_488_910uL)
        val chain = chain(market)
        assertTrue(chain.buyAvailable)
        val quote = chain.quoteBuy(1_000_000uL)!!
        assertEquals(1_000_000uL, market.asked.single().inAmount)
        assertEquals(50, market.asked.single().slippageBps)
        assertEquals(96_488_910uL, quote.oreAtoms)
        // The quote's own minimum: floor(96,488,910 x 0.995) = 96,006,465. The sheet's: 1% under that.
        assertEquals(95_046_400uL, quote.minOreAtoms)
        assertEquals(50, quote.slippageBps)
        assertEquals(listOf("Meteora DLMM"), quote.route)
        // What the wallet is asked for is the spend, that minimum and that slippage.
        assertEquals(quote.minOreAtoms, quote.leg().minOreAtoms)
        assertEquals(50, quote.leg().slippageBps)
    }

    @Test
    fun `a fresh quote a little lower still buys, and none can deliver less than the sheet said`() = runBlocking {
        val shown = chain(Market(out = 96_488_910uL)).quoteBuy(1_000_000uL)!!
        fun fresh(out: ULong) = SwapQuote.minOut(out, BUY_SLIPPAGE_BPS) // what the swap enforces on-chain
        // SwapLegBuilder's rule, as it stands: the buy is left out when the fresh minimum is below the agreed one.
        fun bought(out: ULong) = fresh(out) >= shown.minOreAtoms
        assertTrue(bought(96_488_910uL))
        assertTrue("one atom lower must not cancel the buy", bought(96_488_909uL))
        assertTrue("half a percent lower", bought(96_006_465uL))
        assertTrue("just under one percent lower", bought(95_530_000uL))
        assertFalse("two percent lower: the price moved, nothing is bought", bought(94_559_131uL))
        // Wherever it is bought, the on-chain minimum is at least what the sheet stated.
        for (out in listOf(96_488_910uL, 96_006_465uL, 95_530_000uL, 95_524_021uL)) {
            if (bought(out)) assertTrue(fresh(out) >= shown.minOreAtoms)
        }
    }

    @Test
    fun `no route, a failed read, a quote too small to have a minimum, or no market at all is no quote`() = runBlocking {
        assertNull(chain(Market(out = null)).quoteBuy(1_000_000uL))
        assertNull(chain(Market(out = 96_488_910uL, fails = true)).quoteBuy(1_000_000uL))
        assertNull(chain(Market(out = 1uL)).quoteBuy(1_000_000uL))
        val none = chain(null)
        assertFalse(none.buyAvailable)
        assertNull(none.quoteBuy(1_000_000uL))
    }
}
