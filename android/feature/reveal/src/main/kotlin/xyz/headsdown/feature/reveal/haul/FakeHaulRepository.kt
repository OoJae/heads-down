package xyz.headsdown.feature.reveal.haul

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * Stand-in until the indexer serves real ShiftLogs. Every haul it returns is marked
 * [HaulProvenance.SAMPLE], and the reveal says so on screen.
 *
 * The numbers follow docs/ECONOMICS.md scenario B scaled to the app's current clock-in policy
 * (0.001 SOL digs, at most 20 a shift): an effective 0.680 SOL per ORE against a 0.758 market,
 * a 0.02 ORE nightly target, and no Motherlode (never scripted).
 */
class FakeHaulRepository(
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : HaulRepository {
    override suspend fun latest(): HaulSummary = sampleNight(clock(), zone())

    companion object {
        const val ROUND_MILLIS = 78_000L
        private const val SEED = 20_260_929
        private const val FIRST_ROUND_ID = 422_600L

        /** A mined night that ended at the most recent 06:50 before [nowWallMillis]. */
        fun sampleNight(nowWallMillis: Long, zone: ZoneId): HaulSummary {
            val end = lastMorning(nowWallMillis, zone)
            val start = end - (7 * 60 + 40) * 60_000L
            val rounds = sampleRounds(start, darkRounds = 312, digs = 18)
            return HaulSummary(
                provenance = HaulProvenance.SAMPLE,
                shiftId = 23,
                startedAtWallMillis = start,
                endedAtWallMillis = end,
                rounds = rounds,
                solPlacedLamports = rounds.count { it.dug } * 1_000_000L,
                oreMinedAtoms = 582_000_000L,
                effectiveLamportsPerOre = 680_000_000L,
                marketLamportsPerOre = 758_000_000L,
                nightlyTargetOreAtoms = 2_000_000_000L,
                streak = StreakUpdate(before = 22, after = 23),
                firstPickupWallMillis = end + 2 * 60_000L,
            )
        }

        /** Scenario A: the gate never opened, nothing was placed, buying was cheaper. */
        fun sampleGateClosed(nowWallMillis: Long, zone: ZoneId): HaulSummary {
            val end = lastMorning(nowWallMillis, zone)
            val start = end - (7 * 60 + 40) * 60_000L
            return sampleNight(nowWallMillis, zone).copy(
                rounds = sampleRounds(start, darkRounds = 312, digs = 0),
                solPlacedLamports = 0,
                oreMinedAtoms = 0,
                effectiveLamportsPerOre = null,
            )
        }

        internal fun sampleRounds(startWallMillis: Long, darkRounds: Int, digs: Int): List<RoundReplay> {
            val random = Random(SEED)
            val digRounds = (0 until darkRounds).shuffled(random).take(digs).toSet()
            return (0 until darkRounds).map { i ->
                val mask = if (i in digRounds) {
                    (0 until Board.TILES).shuffled(random).take(4).fold(0) { acc, tile -> acc or (1 shl tile) }
                } else {
                    0
                }
                RoundReplay(
                    roundId = FIRST_ROUND_ID + i,
                    dugMask = mask,
                    winningTile = random.nextInt(Board.TILES),
                    motherlode = false,
                    endedAtWallMillis = startWallMillis + (i + 1) * ROUND_MILLIS,
                )
            }
        }

        private fun lastMorning(nowWallMillis: Long, zone: ZoneId): Long {
            val now = Instant.ofEpochMilli(nowWallMillis).atZone(zone)
            var morning = ZonedDateTime.of(now.toLocalDate(), LocalTime.of(6, 50), zone)
            if (morning.isAfter(now)) morning = morning.minusDays(1)
            return morning.toInstant().toEpochMilli()
        }
    }
}
