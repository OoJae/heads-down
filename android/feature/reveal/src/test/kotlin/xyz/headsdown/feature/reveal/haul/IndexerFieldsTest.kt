package xyz.headsdown.feature.reveal.haul

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.feature.reveal.board.BoardReplay
import xyz.headsdown.feature.reveal.share.ShareGrid
import java.time.LocalDateTime
import java.time.ZoneId

/** The fields an indexer haul (contract B) adds over the sample night. */
class IndexerFieldsTest {
    private val zone = ZoneId.of("Africa/Lagos")
    private val now = LocalDateTime.of(2026, 9, 30, 7, 5).atZone(zone).toInstant().toEpochMilli()
    private val night = FakeHaulRepository.sampleNight(now, zone).copy(provenance = HaulProvenance.ON_CHAIN)

    @Test
    fun `simulated data is labelled and on-chain data is not`() {
        assertEquals("SIMULATED · not on-chain data", RevealCopyBuilder.build(night.copy(provenance = HaulProvenance.SIMULATED), zone).sampleBadge)
        assertEquals(null, RevealCopyBuilder.build(night, zone).sampleBadge)
    }

    @Test
    fun `ShiftLog counts win over the rounds list, fees and market source are shown`() {
        val h = night.copy(darkRoundsTotal = 400, digsTotal = 25, feesLamports = 250_000, marketSource = "jupiter")
        assertEquals(400, h.roundsDark)
        assertEquals(25, h.digs)
        val copy = RevealCopyBuilder.build(h, zone)
        assertEquals("400", copy.stats[0].value)
        assertEquals("25", copy.stats[1].value)
        assertEquals("Market 0.758 (jupiter)", copy.marketPrice)
        assertTrue(copy.solPlacedNote, copy.solPlacedNote.contains("plus 0.0002 SOL in executor fees"))
        copy.allText.forEach { assertTrue(it, HonestCopy.violations(it).isEmpty()) }
    }

    @Test
    fun `a round without a known winner is never a hit and never flashes`() {
        val unknown = RoundReplay(1, dugMask = 0b111, winningTile = null)
        assertTrue(unknown.dug)
        assertFalse(unknown.hit)
        val replay = BoardReplay(listOf(unknown, RoundReplay(2, dugMask = 0b1, winningTile = 0)), durationMillis = 100)
        assertEquals(setOf(0), replay.hitTiles)
        assertTrue(replay.frameAt(60).flashes.none { it.tile != 0 })
        // The share grid reads only dug masks.
        assertEquals(1 + 1 + 1, ShareGrid.from(night.copy(rounds = listOf(unknown))).levels.count { it > 0 })
    }

    @Test
    fun `explorer links must be https`() {
        assertThrows(IllegalArgumentException::class.java) { night.copy(explorerUrl = "http://explorer.example/tx") }
        assertEquals("https://explorer.solana.com/x", night.copy(explorerUrl = "https://explorer.solana.com/x").explorerUrl)
    }
}
