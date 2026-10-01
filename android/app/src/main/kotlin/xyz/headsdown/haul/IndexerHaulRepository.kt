package xyz.headsdown.haul

import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.indexer.HaulMode
import xyz.headsdown.core.chain.indexer.IndexerHaul
import xyz.headsdown.core.chain.indexer.IndexerHaulClient
import xyz.headsdown.feature.reveal.haul.HaulProvenance
import xyz.headsdown.feature.reveal.haul.HaulRepository
import xyz.headsdown.feature.reveal.haul.HaulSummary
import xyz.headsdown.feature.reveal.haul.RoundReplay
import xyz.headsdown.feature.reveal.haul.StreakUpdate
import xyz.headsdown.feature.shift.RigBindingProvider
import xyz.headsdown.surface.widget.RigWidgetUpdates
import xyz.headsdown.surface.widget.WidgetHaul
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The morning haul from the indexer (contract B), for the rig this phone is bound to.
 *
 * - No indexer configured, no bound rig, or no finished shift (404): null, "no haul yet".
 * - `first_pickup_ts` is not observable on-chain: it comes from the shift service's own journal
 *   ([firstPickup]) when the journal still holds that shift.
 * - A real (not `simulated`) haul updates the widget's last haul and streak.
 */
class IndexerHaulRepository(
    /** Null when this build has no indexer (an empty indexer URL). */
    private val client: IndexerHaulClient?,
    private val binding: RigBindingProvider,
    /** The journaled first pickup (wall millis) for a shift id, or null. */
    private val firstPickup: (shiftId: Long) -> Long?,
    private val widgets: RigWidgetUpdates,
) : HaulRepository {

    override suspend fun latest(): HaulSummary? {
        val indexer = client ?: return null
        val bound = binding.current()
        if (!bound.isRegistered) return null
        val haul = indexer.latest(Pubkey(bound.rigAddress)) ?: return null
        val shiftId = haul.shiftId.toLongOrNull() ?: return null
        val summary = HaulMapping.toSummary(haul, haul.firstPickupTs?.let { it * 1000 } ?: firstPickup(shiftId))
        if (!haul.simulated) {
            widgets.onHaul(WidgetHaul(summary.oreMinedAtoms, summary.endedAtWallMillis))
            widgets.onStreak(haul.streakAfter.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        }
        return summary
    }

    private fun ULong.toLongOrNull(): Long? = if (this > Long.MAX_VALUE.toULong()) null else toLong()
}

/** Contract B → the reveal's model. Pure, so it is unit-tested without a network. */
object HaulMapping {

    fun toSummary(h: IndexerHaul, firstPickupWallMillis: Long?): HaulSummary {
        // The replay shows the rounds the rig was dark for (and any it dug), in order.
        val rounds = h.rounds.filter { it.dark || it.dugMask != 0 }.map { r ->
            RoundReplay(
                roundId = r.roundId.toLongCapped(),
                dugMask = r.dugMask,
                winningTile = r.winningSquare,
                motherlode = r.motherlode,
            )
        }
        return HaulSummary(
            provenance = if (h.simulated) HaulProvenance.SIMULATED else HaulProvenance.ON_CHAIN,
            shiftId = h.shiftId.toLongCapped(),
            startedAtWallMillis = h.startTs * 1000,
            endedAtWallMillis = h.endTs * 1000,
            rounds = rounds,
            solPlacedLamports = h.solPlacedLamports.toLongCapped(),
            oreMinedAtoms = h.oreMinedAtoms.min(Long.MAX_VALUE.toBigInteger()).toLong(),
            effectiveLamportsPerOre = h.effectiveLamportsPerOre?.toWholeLamports(),
            marketLamportsPerOre = h.marketLamportsPerOre?.toWholeLamports(),
            // No nightly ORE target is set anywhere yet: no "buy the rest" offer.
            nightlyTargetOreAtoms = null,
            streak = StreakUpdate(h.streakBefore.toIntCapped(), h.streakAfter.toIntCapped()),
            firstPickupWallMillis = firstPickupWallMillis?.takeIf { it in h.startTs * 1000..h.endTs * 1000 + MAX_PICKUP_AFTER_END_MILLIS },
            focusOnly = h.mode == HaulMode.FOCUS_ONLY,
            darkRoundsTotal = h.darkRounds.toLongCapped().toIntCapped(),
            digsTotal = h.roundsDug.toLongCapped().toIntCapped(),
            feesLamports = h.feesLamports.toLongCapped(),
            marketSource = h.marketSource,
            explorerUrl = h.explorerShiftLog,
        )
    }

    /** A pickup outside the shift (or a day after it) belongs to another night. */
    private const val MAX_PICKUP_AFTER_END_MILLIS = 24L * 3600 * 1000

    /** Prices are shown with 3 decimals of SOL; fractional lamports round to the nearest. */
    private fun BigDecimal.toWholeLamports(): Long? =
        setScale(0, RoundingMode.HALF_UP).takeIf { it.signum() > 0 && it <= BigDecimal.valueOf(Long.MAX_VALUE) }?.toLong()

    private fun ULong.toLongCapped(): Long = if (this > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else toLong()

    private fun Long.toIntCapped(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
}
