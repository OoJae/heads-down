package xyz.headsdown.feature.reveal.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.feature.reveal.haul.FakeHaulRepository
import xyz.headsdown.feature.reveal.haul.HaulProvenance
import xyz.headsdown.feature.reveal.haul.HonestCopy
import java.time.ZoneId

class ShareGridTest {
    private val night = FakeHaulRepository.sampleNight(1_790_000_000_000L, ZoneId.of("UTC"))

    @Test
    fun `levels follow where the rig dug`() {
        val grid = ShareGrid.from(night)
        val counts = (0 until 25).map { t -> night.rounds.count { it.dugTile(t) } }
        counts.forEachIndexed { t, c -> assertEquals("tile $t", c == 0, grid.levels[t] == 0) }
        assertEquals(3, grid.levels.max())
        assertEquals(5, grid.emojiRows().size)
        assertTrue(grid.emojiRows().all { it.codePointCount(0, it.length) == 5 })
    }

    @Test
    fun `outcomes cannot leak into the grid or its text`() {
        val base = ShareGrid.from(night)
        val rerolled = night.copy(
            rounds = night.rounds.map { it.copy(winningTile = (it.winningTile + 7) % 25, motherlode = true) },
            oreMinedAtoms = 99_999_999_999L,
            effectiveLamportsPerOre = 1L,
            marketLamportsPerOre = 2L,
            solPlacedLamports = 5_000_000_000L,
            provenance = HaulProvenance.ON_CHAIN,
        )
        assertEquals(base, ShareGrid.from(rerolled))
        assertEquals(base.shareText(), ShareGrid.from(rerolled).shareText())
    }

    @Test
    fun `share text has no amounts, prices or banned words`() {
        val text = ShareGrid.from(night).shareText()
        val expected = listOf("Heads Down · night shift") +
            ShareGrid.from(night).emojiRows() +
            listOf("312 rounds dark · 7 h 40 m · streak 23", "Powered by ORE")
        assertEquals(expected.joinToString("\n"), text)
        assertFalse(text.contains("SOL"))
        assertFalse(Regex("\\d+\\.\\d+").containsMatchIn(text))
        assertTrue(HonestCopy.violations(text).isEmpty())
    }

    @Test
    fun `a single dig still shows and a gate-closed night is blank`() {
        val one = night.copy(rounds = night.rounds.take(1).map { it.copy(dugMask = 1 shl 12) } + night.rounds.drop(1).map { it.copy(dugMask = 0) })
        val levels = ShareGrid.from(one).levels
        assertEquals(3, levels[12])
        assertEquals(24, levels.count { it == 0 })
        val closed = FakeHaulRepository.sampleGateClosed(1_790_000_000_000L, ZoneId.of("UTC"))
        assertTrue(ShareGrid.from(closed).levels.all { it == 0 })
    }
}
