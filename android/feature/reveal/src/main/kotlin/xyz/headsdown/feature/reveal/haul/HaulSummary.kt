package xyz.headsdown.feature.reveal.haul

/** Where a haul came from. The reveal labels anything that is not on-chain. */
enum class HaulProvenance {
    /** Illustrative data from [FakeHaulRepository]: never the user's own night. */
    SAMPLE,

    /** Served by the indexer from the rig's ShiftLog and ORE's round events (contract B). */
    ON_CHAIN,

    /** The indexer flagged it `simulated`: labelled, and never pushed to the widget. */
    SIMULATED,
}

/** The 5x5 ORE board. */
object Board {
    const val SIZE = 5
    const val TILES = SIZE * SIZE
    const val ALL_TILES_MASK = (1 shl TILES) - 1
}

/** One ORE round the rig was dark for. */
data class RoundReplay(
    val roundId: Long,
    /** Bit `i` set = the rig dug tile `i` this round. 0 = dark and heartbeating, but no dig. */
    val dugMask: Int,
    /** The board's winning tile this round, 0..24, or null when it is not known yet. */
    val winningTile: Int?,
    /** ORE's Motherlode hit the board this round. */
    val motherlode: Boolean = false,
    val endedAtWallMillis: Long = 0,
) {
    init {
        require(dugMask and Board.ALL_TILES_MASK.inv() == 0) { "dug mask has bits beyond 25 tiles" }
        require(winningTile == null || winningTile in 0 until Board.TILES) { "winning tile is 0..24" }
    }

    val dug: Boolean get() = dugMask != 0

    /** One of the rig's tiles won this round. */
    val hit: Boolean get() = dug && winningTile != null && (dugMask ushr winningTile) and 1 == 1

    fun dugTile(tile: Int): Boolean = (dugMask ushr tile) and 1 == 1
}

/** Nights in a row, before and after this shift. */
data class StreakUpdate(val before: Int, val after: Int) {
    init {
        require(before >= 0 && after >= 0)
    }
}

/**
 * Everything the morning reveal shows. Amounts are integers in base units: lamports (9
 * decimals) and ORE atoms (11 decimals). Prices are lamports per whole ORE.
 */
data class HaulSummary(
    val provenance: HaulProvenance,
    val shiftId: Long,
    val startedAtWallMillis: Long,
    val endedAtWallMillis: Long,
    /** Every dark round, in order. */
    val rounds: List<RoundReplay>,
    /** SOL the Automation put on the board (most of it comes back; see the effective price). */
    val solPlacedLamports: Long,
    val oreMinedAtoms: Long,
    /** (SOL that did not come back + crank fees) / ORE mined. Null when nothing was mined. */
    val effectiveLamportsPerOre: Long?,
    /** Market price at the reveal (Jupiter quote). Null when no quote is available. */
    val marketLamportsPerOre: Long?,
    /** The user's nightly ORE target, if set: "buy the rest" tops up to it. */
    val nightlyTargetOreAtoms: Long?,
    val streak: StreakUpdate,
    /** When the phone was first picked up after the shift, if it was. */
    val firstPickupWallMillis: Long? = null,
    /** Zero-SOL shift: counts for the streak, never digs. */
    val focusOnly: Boolean = false,
    /** Dark rounds from the ShiftLog, when the source reports them; else counted from [rounds]. */
    val darkRoundsTotal: Int? = null,
    /** Rounds dug from the ShiftLog, when the source reports them; else counted from [rounds]. */
    val digsTotal: Int? = null,
    /** Executor fees the Automation paid on top of the SOL placed. */
    val feesLamports: Long = 0,
    /** Where the market quote came from (for example "jupiter"). */
    val marketSource: String? = null,
    /** An https block-explorer link to this shift's on-chain record. */
    val explorerUrl: String? = null,
) {
    init {
        require(endedAtWallMillis >= startedAtWallMillis)
        require(solPlacedLamports >= 0 && oreMinedAtoms >= 0 && feesLamports >= 0)
        require(darkRoundsTotal == null || darkRoundsTotal >= 0)
        require(digsTotal == null || digsTotal >= 0)
        require(explorerUrl == null || explorerUrl.startsWith("https://")) { "explorer links are https" }
        require(effectiveLamportsPerOre == null || effectiveLamportsPerOre > 0)
        require(marketLamportsPerOre == null || marketLamportsPerOre > 0)
        require(nightlyTargetOreAtoms == null || nightlyTargetOreAtoms > 0)
    }

    val roundsDark: Int get() = darkRoundsTotal ?: rounds.size
    val digs: Int get() = digsTotal ?: rounds.count { it.dug }
    val hits: Int get() = rounds.count { it.hit }
    val darkMillis: Long get() = endedAtWallMillis - startedAtWallMillis

    /** The rig shared a Motherlode: the only thing that earns the flourish. */
    val sharedMotherlode: Boolean get() = rounds.any { it.hit && it.motherlode }

    /** A Motherlode round where the rig dug, but on other tiles. */
    val motherlodeNearMiss: RoundReplay? get() = rounds.firstOrNull { it.motherlode && it.dug && !it.hit }
}

/** The data interface the reveal reads. The indexer-backed implementation replaces the fake. */
fun interface HaulRepository {
    /** The most recent finished shift's haul, or null if there is none yet. */
    suspend fun latest(): HaulSummary?
}
