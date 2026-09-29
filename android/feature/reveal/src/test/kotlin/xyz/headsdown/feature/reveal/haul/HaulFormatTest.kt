package xyz.headsdown.feature.reveal.haul

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class HaulFormatTest {

    @Test
    fun `amounts round down and never show a fake zero`() {
        assertEquals("0 SOL", HaulFormat.sol(0))
        assertEquals("<0.0001 SOL", HaulFormat.sol(99_999))
        assertEquals("0.0180 SOL", HaulFormat.sol(18_000_000))
        assertEquals("0.0199 SOL", HaulFormat.sol(19_999_999))
        assertEquals("1.500 SOL", HaulFormat.sol(1_500_000_000))
        assertEquals("0.0194 ORE", HaulFormat.ore(1_949_999_999))
        assertEquals("<0.0001 ORE", HaulFormat.ore(1))
    }

    @Test
    fun `prices are SOL per ORE to three decimals`() {
        assertEquals("0.680", HaulFormat.price(680_000_000))
        assertEquals("0.758", HaulFormat.price(758_000_000))
        assertEquals("1.190", HaulFormat.price(1_190_000_000))
    }

    @Test
    fun `durations, counts and clock`() {
        assertEquals("7 h 40 m", HaulFormat.duration((7 * 60 + 40) * 60_000L))
        assertEquals("7 h 05 m", HaulFormat.duration((7 * 60 + 5) * 60_000L))
        assertEquals("45 m", HaulFormat.duration(45 * 60_000L))
        assertEquals("0 m", HaulFormat.duration(-5))
        assertEquals("1,234", HaulFormat.count(1234))
        val zone = ZoneId.of("Asia/Seoul")
        val t = LocalDateTime.of(2026, 9, 30, 3, 14).atZone(zone).toInstant().toEpochMilli()
        assertEquals("03:14", HaulFormat.clock(t, zone))
    }

    @Test
    fun `sample night is deterministic and honest`() {
        val zone = ZoneId.of("Africa/Lagos")
        val now = LocalDateTime.of(2026, 9, 30, 7, 5).atZone(zone).toInstant().toEpochMilli()
        val a = FakeHaulRepository.sampleNight(now, zone)
        val b = FakeHaulRepository.sampleNight(now, zone)
        assertEquals(a, b)
        assertEquals(HaulProvenance.SAMPLE, a.provenance)
        assertEquals(312, a.roundsDark)
        assertEquals(18, a.digs)
        assertEquals(18_000_000L, a.solPlacedLamports)
        assertTrue("never a scripted Motherlode", a.rounds.none { it.motherlode })
        assertTrue(a.rounds.filter { it.dug }.all { Integer.bitCount(it.dugMask) == 4 })
        assertEquals(LocalDateTime.of(2026, 9, 30, 6, 50), java.time.Instant.ofEpochMilli(a.endedAtWallMillis).atZone(zone).toLocalDateTime())
        // Before 06:50 the sample is the previous morning.
        val early = LocalDateTime.of(2026, 9, 30, 6, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(
            LocalDateTime.of(2026, 9, 29, 6, 50),
            java.time.Instant.ofEpochMilli(FakeHaulRepository.sampleNight(early, zone).endedAtWallMillis).atZone(zone).toLocalDateTime(),
        )
    }
}
