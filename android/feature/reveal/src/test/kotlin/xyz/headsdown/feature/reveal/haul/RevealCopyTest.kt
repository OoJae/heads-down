package xyz.headsdown.feature.reveal.haul

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RevealCopyTest {
    private val zone = ZoneId.of("Africa/Lagos")
    private val now = LocalDateTime.of(2026, 9, 30, 7, 5).atZone(zone).toInstant().toEpochMilli()
    private val night = FakeHaulRepository.sampleNight(now, zone)

    @Test
    fun `sample night copy`() {
        val c = RevealCopyBuilder.build(night, zone)
        assertEquals("Morning haul", c.title)
        assertEquals("23:10 – 06:50 · 7 h 40 m shift", c.subtitle)
        assertEquals("SAMPLE NIGHT · not your data", c.sampleBadge)
        assertEquals(
            listOf(
                RevealStat("Rounds dark", "312"),
                RevealStat("Digs", "18"),
                RevealStat("SOL placed", "0.0180 SOL"),
                RevealStat("ORE mined", "0.0058 ORE"),
            ),
            c.stats,
        )
        assertEquals("0.680 SOL per ORE", c.effectivePrice)
        assertEquals("Market 0.758", c.marketPrice)
        assertEquals("Mining was the cheaper route last night: 10% below market.", c.verdict)
        assertEquals("Streak 22 → 23", c.streak)
        assertEquals("Buy the rest at market · 0.0141 ORE", c.buyButton)
        assertEquals("First pickup 06:52", c.firstPickup)
        assertNull(c.nearMiss)
    }

    @Test
    fun `on-chain hauls carry no sample badge`() {
        assertNull(RevealCopyBuilder.build(night.copy(provenance = HaulProvenance.ON_CHAIN), zone).sampleBadge)
    }

    @Test
    fun `gate closed night says nothing was placed`() {
        val c = RevealCopyBuilder.build(FakeHaulRepository.sampleGateClosed(now, zone), zone)
        assertEquals(
            "No round was dug in this shift, so nothing was placed. Most often that is the price gate staying closed: " +
                "on those nights buying is the cheaper route.",
            c.verdict,
        )
        // A shift in which the phone never went dark is not blamed on the gate (seen on the emulator:
        // a shift armed and ended a minute later read "the price gate stayed closed all night").
        val neverDark = FakeHaulRepository.sampleGateClosed(now, zone).copy(rounds = emptyList(), darkRoundsTotal = 0, digsTotal = 0)
        assertEquals("The phone never lay face-down and dark in this shift, so nothing was placed.", RevealCopyBuilder.build(neverDark, zone).verdict)
        assertEquals("0 SOL", c.stats[2].value)
        assertNull(c.effectivePrice)
        assertEquals("Buy the rest at market · 0.0200 ORE", c.buyButton)
    }

    @Test
    fun `streak holds and resets`() {
        assertEquals("Streak holds at 5", RevealCopyBuilder.build(night.copy(streak = StreakUpdate(5, 5)), zone).streak)
        assertEquals("Streak reset to 0", RevealCopyBuilder.build(night.copy(streak = StreakUpdate(5, 0)), zone).streak)
    }

    @Test
    fun `near miss is reported plainly`() {
        val start = night.startedAtWallMillis
        val miss = night.copy(
            rounds = listOf(RoundReplay(1, dugMask = 0b1, winningTile = 9, motherlode = true, endedAtWallMillis = start + 3_600_000L)),
        )
        assertEquals(
            "The Motherlode hit the board at 00:10, on a tile your rig did not dig.",
            RevealCopyBuilder.build(miss, zone).nearMiss,
        )
    }

    @Test
    fun `every verdict and every screen string is honest`() {
        val variants = listOf(
            night,
            night.copy(provenance = HaulProvenance.ON_CHAIN),
            FakeHaulRepository.sampleGateClosed(now, zone),
            night.copy(effectiveLamportsPerOre = 1_190_000_000L),
            night.copy(effectiveLamportsPerOre = 758_000_000L),
            night.copy(focusOnly = true),
            night.copy(marketLamportsPerOre = null),
            night.copy(oreMinedAtoms = 0, effectiveLamportsPerOre = null),
        )
        variants.forEach { h ->
            RevealCopyBuilder.build(h, zone).allText.forEach { s ->
                assertTrue("banned words ${HonestCopy.violations(s)} in: $s", HonestCopy.violations(s).isEmpty())
            }
        }
    }

    @Test
    fun `the honesty check catches what it should and nothing else`() {
        assertEquals(listOf("earn"), HonestCopy.violations("Earn ORE while you sleep"))
        assertEquals(listOf("yield", "staking"), HonestCopy.violations("Yield from staking"))
        assertEquals(listOf("passive income"), HonestCopy.violations("passive income"))
        assertEquals(listOf("proof of focus"), HonestCopy.violations("Proof of focus"))
        assertEquals(listOf("bet"), HonestCopy.violations("a safe bet"))
        // Word boundaries: these are fine.
        assertTrue(HonestCopy.violations("better, between, learned, alphabet, instead").isEmpty())
        assertTrue(HonestCopy.violations("dig, haul, bond, accumulate ORE by the cheaper route").isEmpty())
    }
}
