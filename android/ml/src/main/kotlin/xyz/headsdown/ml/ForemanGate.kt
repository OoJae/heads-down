package xyz.headsdown.ml

/**
 * The bounds on everything the Foreman models output. Models can only TIGHTEN:
 *
 * - The pickup classifier can only add a break. Heartbeats need the deterministic "dark" verdict
 *   (FaceDownDetector face-down, screen off, not unlocked, on the charger for Night Shift);
 *   a PICKUP vetoes it. No classifier output can make a phone that is not dark heartbeat, and
 *   screen-on / unlock stay hard breaks outside any model.
 * - A plan proposal is clamped to what the wallet signed: max_ev_cost <= cap_max_cost,
 *   dig_lamports <= cap_round, the window ends by caps_expiry_ts, and the INTERFACE.md §5
 *   `arm_shift` field ranges. A proposal that cannot be made valid is dropped, never loosened.
 * - The weekly budget split never allots more than cap_shift to a night or more than the
 *   week's remaining cap in total (see [xyz.headsdown.ml.planner.BudgetSplitter]).
 */
object ForemanGate {

    /** May the rig heartbeat now? [dark] comes from the deterministic signals only. */
    fun heartbeatAllowed(dark: Boolean, decision: PickupDecision?): Boolean = dark && decision?.isPickup != true

    /** The BREAK `reason` byte the classifier asks for (1 = pickup), or null for no break. */
    fun breakReason(decision: PickupDecision): Int? = if (decision.isPickup) PickupDecision.BREAK_REASON_PICKUP else null

    /**
     * Clamps [proposal] into [caps]. Returns null when no valid plan remains (caps expired,
     * the window already over or empty, or a deploying plan with no tiles or no SOL per dig).
     */
    fun tighten(caps: WalletCaps, proposal: PlanProposal, nowTs: Long): PlanProposal? {
        if (nowTs > caps.capsExpiryTs) return null
        val end = minOf(proposal.windowEndTs, caps.capsExpiryTs)
        val start = proposal.windowStartTs
        if (start >= end || nowTs > end) return null
        val tightened = proposal.copy(
            maxEvCost = proposal.maxEvCost.coerceIn(0L, caps.capMaxCost),
            digLamports = proposal.digLamports.coerceIn(0L, caps.capRoundLamports),
            splitTiles = proposal.splitTiles.coerceIn(0, PlanProposal.MAX_SPLIT),
            soloTiles = proposal.soloTiles.coerceIn(0, PlanProposal.MAX_SOLO),
            leaseRounds = proposal.leaseRounds.coerceIn(1, PlanProposal.MAX_LEASE),
            windowStartTs = start,
            windowEndTs = end,
        )
        if (!tightened.focusOnly && (tightened.splitTiles + tightened.soloTiles < 1 || tightened.digLamports <= 0L)) return null
        return tightened
    }
}

/** The limits the wallet signed at refuel (`set_caps`, Rig account §3.2). Lamports, unix seconds. */
data class WalletCaps(
    val capWeekLamports: Long,
    val capShiftLamports: Long,
    val capRoundLamports: Long,
    /** Ceiling on the pot-adjusted cost `ema_ev`, lamports per ORE. */
    val capMaxCost: Long,
    val capsExpiryTs: Long,
    val spentWeekLamports: Long = 0,
) {
    init {
        require(capWeekLamports >= 0 && capShiftLamports >= 0 && capRoundLamports >= 0 && capMaxCost >= 0 && spentWeekLamports >= 0)
    }

    val remainingWeekLamports: Long get() = (capWeekLamports - spentWeekLamports).coerceAtLeast(0L)
}

/** A plan for a P-256-signed `arm_shift` (INTERFACE.md §5 fields). */
data class PlanProposal(
    val maxEvCost: Long,
    val digLamports: Long,
    val splitTiles: Int,
    val soloTiles: Int,
    val leaseRounds: Int,
    val focusOnly: Boolean,
    val day: Boolean,
    val windowStartTs: Long,
    val windowEndTs: Long,
) {
    companion object {
        const val MAX_SPLIT = 15
        const val MAX_SOLO = 10
        const val MAX_LEASE = 3
    }
}
