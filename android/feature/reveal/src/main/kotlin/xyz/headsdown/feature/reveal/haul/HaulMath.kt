package xyz.headsdown.feature.reveal.haul

import java.math.BigInteger

/** Which route to ORE was cheaper last night. Mining is a route, never income. */
sealed interface RouteVerdict {
    /** Mining cost less per ORE than the market. [percent] is how much less (0..100). */
    data class MiningCheaper(val effective: Long, val market: Long, val percent: Int) : RouteVerdict

    /** Mining cost more per ORE than the market. [percent] is how much more. */
    data class BuyingCheaper(val effective: Long, val market: Long, val percent: Int) : RouteVerdict

    /** Within half a percent of market. */
    data class AboutMarket(val effective: Long, val market: Long) : RouteVerdict

    /**
     * The phone was dark for some rounds and none of them was dug, so nothing was placed. The
     * usual reason is the price gate staying closed; the haul does not say why a round was not
     * dug, so the wording does not claim to know.
     */
    data class GateClosed(val market: Long?) : RouteVerdict

    /** The phone never went dark in this shift: there was no round a dig could have used. */
    data object NeverDark : RouteVerdict

    /** Digs happened but no ORE came back yet: there is no price to compare. */
    data class NothingMined(val market: Long?) : RouteVerdict

    /** Zero-SOL shift. */
    data object FocusOnly : RouteVerdict

    /** No market quote: the effective price is shown alone. */
    data class NoMarket(val effective: Long) : RouteVerdict
}

/** "Buy the rest at market": top up to the nightly target. */
data class BuySuggestion(
    val oreAtoms: Long,
    /** Estimated SOL at the market quote, before price impact and fees. */
    val estimatedLamports: Long,
)

object HaulMath {
    const val ONE_ORE: Long = 100_000_000_000L

    fun verdict(h: HaulSummary): RouteVerdict {
        if (h.focusOnly) return RouteVerdict.FocusOnly
        if (h.digs == 0 && h.solPlacedLamports == 0L) {
            return if (h.roundsDark == 0) RouteVerdict.NeverDark else RouteVerdict.GateClosed(h.marketLamportsPerOre)
        }
        val effective = h.effectiveLamportsPerOre ?: return RouteVerdict.NothingMined(h.marketLamportsPerOre)
        val market = h.marketLamportsPerOre ?: return RouteVerdict.NoMarket(effective)
        val tenthsOfPercent = (effective - market) * 1000 / market
        return when {
            tenthsOfPercent <= -5 -> RouteVerdict.MiningCheaper(effective, market, roundPercent(-tenthsOfPercent))
            tenthsOfPercent >= 5 -> RouteVerdict.BuyingCheaper(effective, market, roundPercent(tenthsOfPercent))
            else -> RouteVerdict.AboutMarket(effective, market)
        }
    }

    /** Offered only with a nightly target that the haul did not reach, and a market quote. */
    fun buyRest(h: HaulSummary): BuySuggestion? {
        val target = h.nightlyTargetOreAtoms ?: return null
        val market = h.marketLamportsPerOre ?: return null
        val rest = target - h.oreMinedAtoms
        if (rest <= 0) return null
        // rest * market / ONE_ORE, rounded up, without Long overflow.
        val lamports = BigInteger.valueOf(rest).multiply(BigInteger.valueOf(market))
            .add(BigInteger.valueOf(ONE_ORE - 1))
            .divide(BigInteger.valueOf(ONE_ORE))
        return BuySuggestion(rest, lamports.toLong())
    }

    private fun roundPercent(tenths: Long): Int = ((tenths + 5) / 10).toInt()
}
