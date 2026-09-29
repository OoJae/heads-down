package xyz.headsdown.feature.reveal.haul

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class HaulMathTest {
    private val zone = ZoneId.of("Africa/Lagos")
    private val night = FakeHaulRepository.sampleNight(1_790_000_000_000L, zone)

    @Test
    fun `sample night mined ten percent below market`() {
        assertEquals(RouteVerdict.MiningCheaper(680_000_000L, 758_000_000L, 10), HaulMath.verdict(night))
    }

    @Test
    fun `mining pricier than market`() {
        val pricier = night.copy(effectiveLamportsPerOre = 1_190_000_000L, marketLamportsPerOre = 758_000_000L)
        assertEquals(RouteVerdict.BuyingCheaper(1_190_000_000L, 758_000_000L, 57), HaulMath.verdict(pricier))
    }

    @Test
    fun `within half a percent is about market`() {
        val close = night.copy(effectiveLamportsPerOre = 760_000_000L)
        assertEquals(RouteVerdict.AboutMarket(760_000_000L, 758_000_000L), HaulMath.verdict(close))
    }

    @Test
    fun `gate closed, nothing mined, focus only and no market verdicts`() {
        val closed = FakeHaulRepository.sampleGateClosed(1_790_000_000_000L, zone)
        assertEquals(RouteVerdict.GateClosed(758_000_000L), HaulMath.verdict(closed))
        assertEquals(RouteVerdict.NothingMined(758_000_000L), HaulMath.verdict(night.copy(oreMinedAtoms = 0, effectiveLamportsPerOre = null)))
        assertEquals(RouteVerdict.FocusOnly, HaulMath.verdict(night.copy(focusOnly = true)))
        assertEquals(RouteVerdict.NoMarket(680_000_000L), HaulMath.verdict(night.copy(marketLamportsPerOre = null)))
    }

    @Test
    fun `buy the rest tops up to the nightly target at market, rounded up`() {
        val buy = HaulMath.buyRest(night)!!
        assertEquals(2_000_000_000L - 582_000_000L, buy.oreAtoms)
        // 0.01418 ORE * 0.758 SOL/ORE = 0.01074844 SOL
        assertEquals(10_748_440L, buy.estimatedLamports)
        // Rounds up a fraction of a lamport.
        assertEquals(1L, HaulMath.buyRest(night.copy(oreMinedAtoms = 2_000_000_000L - 1))!!.estimatedLamports)
    }

    @Test
    fun `no suggestion without a target, a quote, or a shortfall`() {
        assertNull(HaulMath.buyRest(night.copy(nightlyTargetOreAtoms = null)))
        assertNull(HaulMath.buyRest(night.copy(marketLamportsPerOre = null)))
        assertNull(HaulMath.buyRest(night.copy(oreMinedAtoms = 2_000_000_000L)))
        assertNull(HaulMath.buyRest(night.copy(oreMinedAtoms = 3_000_000_000L)))
    }

    @Test
    fun `gate closed night offers the whole target`() {
        val closed = FakeHaulRepository.sampleGateClosed(1_790_000_000_000L, zone)
        // ECONOMICS scenario A: "Buy your 0.02 ORE for 0.0152 SOL?"
        assertEquals(BuySuggestion(2_000_000_000L, 15_160_000L), HaulMath.buyRest(closed))
    }

    @Test
    fun `derived counts`() {
        val r = listOf(
            RoundReplay(1, dugMask = 0b11, winningTile = 1),
            RoundReplay(2, dugMask = 0, winningTile = 3),
            RoundReplay(3, dugMask = 0b100, winningTile = 2, motherlode = true),
            RoundReplay(4, dugMask = 0b1000, winningTile = 7, motherlode = true),
        )
        val h = night.copy(rounds = r)
        assertEquals(4, h.roundsDark)
        assertEquals(3, h.digs)
        assertEquals(2, h.hits)
        assertEquals(true, h.sharedMotherlode)
        assertEquals(4L, h.motherlodeNearMiss?.roundId)
    }
}
