package xyz.headsdown.haul

import kotlinx.coroutines.CancellationException
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.indexer.HaulMode
import xyz.headsdown.core.chain.indexer.IndexerHaul
import xyz.headsdown.core.chain.indexer.IndexerHaulClient
import xyz.headsdown.feature.reveal.haul.HaulProvenance
import xyz.headsdown.feature.reveal.haul.HaulRepository
import xyz.headsdown.feature.reveal.haul.HaulSummary
import xyz.headsdown.feature.reveal.haul.NightReport
import xyz.headsdown.feature.reveal.haul.OpenNight
import xyz.headsdown.feature.reveal.haul.RoundReplay
import xyz.headsdown.feature.reveal.haul.StreakUpdate
import xyz.headsdown.feature.shift.RigBindingProvider
import xyz.headsdown.feature.shift.ShiftRecord
import xyz.headsdown.surface.widget.RigWidgetUpdates
import xyz.headsdown.surface.widget.WidgetHaul
import java.math.BigDecimal
import java.math.RoundingMode

/** Where the rig's on-chain shift counter stands against a shift this phone worked. */
enum class ShiftOnChain {
    /** That shift is the rig's current one, and it is open: nobody has sealed it. */
    OPEN,

    /** The rig is past it, or it is the current one and closed: it is sealed. */
    SEALED,

    /** The rig never reached that shift id: it was not an on-chain shift (a focus-only one has a local id). */
    NOT_ON_CHAIN,
}

/**
 * The morning haul from the indexer (contract B), for the rig this phone is bound to.
 *
 * - No indexer configured, no bound rig, or no finished shift (404): null, "no haul yet".
 * - `first_pickup_ts` is not observable on-chain: it comes from the shift service's own journal
 *   ([lastShift]) when the journal still holds that shift.
 * - A real (not `simulated`) haul updates the widget's last haul and streak.
 * - The indexer serves sealed shifts only. A shift this phone worked after the last sealed one
 *   is reported as itself ([night]), never covered by the older haul.
 */
class IndexerHaulRepository(
    /** Null when this build has no indexer (an empty indexer URL). */
    private val client: IndexerHaulClient?,
    private val binding: RigBindingProvider,
    /** The shift service's journal: the most recent shift this phone armed, or null. */
    private val lastShift: () -> ShiftRecord?,
    private val widgets: RigWidgetUpdates,
    /** Reads the Rig: where [shiftId] stands on-chain. Throws when the chain cannot be read. */
    private val onChain: suspend (rig: Pubkey, shiftId: Long) -> ShiftOnChain = { _, _ -> ShiftOnChain.NOT_ON_CHAIN },
) : HaulRepository {

    override suspend fun latest(): HaulSummary? {
        val indexer = client ?: return null
        val bound = binding.current()
        if (!bound.isRegistered) return null
        return indexer.latest(Pubkey(bound.rigAddress))?.let(::shown)
    }

    override suspend fun night(): NightReport {
        val indexer = client ?: return NightReport.None
        val bound = binding.current()
        if (!bound.isRegistered) return NightReport.None
        val rig = Pubkey(bound.rigAddress)
        val latest = indexer.latest(rig)
        val mine = lastShift()
        val latestId = latest?.shiftId?.toLongOrNull()
        // No shift of this phone's after the last sealed one: that one is the night.
        if (mine == null || (latestId != null && latestId >= mine.shiftId)) return report(latest)
        val night = OpenNight(mine.shiftId, mine.armedAtWallMillis, mine.lastHeartbeatWallMillis, mine.darkRounds.coerceAtLeast(0))
        val where = try {
            onChain(rig, mine.shiftId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The indexer answered and the node did not: asked again, not guessed.
            return NightReport.NotReadYet(night)
        }
        return when (where) {
            ShiftOnChain.OPEN -> NightReport.NotSealed(night)
            ShiftOnChain.SEALED ->
                indexer.byShift(rig, mine.shiftId.toULong())?.let(::shown)?.let(NightReport::Haul) ?: NightReport.NotReadYet(night)
            ShiftOnChain.NOT_ON_CHAIN -> report(latest)
        }
    }

    private fun report(haul: IndexerHaul?): NightReport = haul?.let(::shown)?.let(NightReport::Haul) ?: NightReport.None

    /** The haul as the reveal shows it; a real one also reaches the widget. */
    private fun shown(haul: IndexerHaul): HaulSummary? {
        val shiftId = haul.shiftId.toLongOrNull() ?: return null
        val journaled = lastShift()?.takeIf { it.shiftId == shiftId }?.firstPickupWallMillis
        val summary = HaulMapping.toSummary(haul, haul.firstPickupTs?.let { it * 1000 } ?: journaled)
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
