package xyz.headsdown.clockout

import xyz.headsdown.core.chain.accounts.OreClaimEstimate
import xyz.headsdown.core.chain.clockout.BondOutcome
import xyz.headsdown.core.chain.clockout.ClockOutPlan
import xyz.headsdown.core.chain.clockout.ClockOutPreview
import xyz.headsdown.core.chain.clockout.ShiftOutcome
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.rig.ClockInPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What a clock-out would do, reduced to what the screen says. Built from a [ClockOutPreview]
 * (chain state read through the checked decoders), or directly in tests.
 */
data class ClockOutFacts(
    val shift: ShiftOutcome,
    val bond: BondOutcome,
    /** SOL the Miner hands back to the wallet with this clock-out (0 = none). */
    val returnedSolLamports: ULong,
    /** The default clock-out (keep the ORE where it is, end nothing early) has no instruction at all. */
    val nothingByDefault: Boolean,
    /** Refined ORE in the Miner: claimable without a fee. Atoms. */
    val refinedOre: ULong,
    /** Unrefined ORE in the Miner: claiming it costs ORE's refining fee. Atoms. */
    val unrefinedOre: ULong,
    /** What claiming everything now would deliver and cost; null without a Miner. */
    val fullClaim: OreClaimEstimate?,
) {
    val oreInMiner: ULong get() = refinedOre + unrefinedOre

    /** There is ORE a `claim_ore` would move. */
    val canClaim: Boolean get() = fullClaim != null && fullClaim.refined + fullClaim.unrefined > 0uL

    /** The shift is inside its window: it is only ended if the user asks for that. */
    val canEndEarly: Boolean get() = shift is ShiftOutcome.LeftOpen

    /** With these choices the wallet has something to sign. */
    fun somethingToSign(claimAll: Boolean, endEarly: Boolean): Boolean =
        !nothingByDefault || (claimAll && canClaim) || (endEarly && canEndEarly)

    /**
     * These facts as they become when the user chooses to end the shift inside its window: it is
     * sealed with the reason `end_shift` would record now, and a bond on it is forfeit.
     */
    fun endedEarly(): ClockOutFacts {
        val open = shift as? ShiftOutcome.LeftOpen ?: return this
        return copy(
            shift = ShiftOutcome.Ends(open.ifEndedNow),
            bond = (bond as? BondOutcome.StaysLocked)?.let { BondOutcome.Forfeit(it.amount, open.ifEndedNow) } ?: bond,
            nothingByDefault = false,
        )
    }

    companion object {
        fun from(preview: ClockOutPreview): ClockOutFacts = ClockOutFacts(
            shift = preview.plan.shift,
            bond = preview.plan.bond,
            returnedSolLamports = preview.plan.claimedSolLamports,
            nothingByDefault = preview.plan.isEmpty,
            refinedOre = preview.refinedOre,
            unrefinedOre = preview.unrefinedOre,
            fullClaim = preview.fullClaim,
        )
    }
}

/** The lines of the clock-out screen. Every one is a statement of what the transaction does. */
data class ClockOutLines(
    val shift: String,
    val bond: String?,
    val ore: String,
    /** What "claim all" delivers and what ORE keeps; null when there is nothing to claim. */
    val claim: String?,
    val sol: String?,
) {
    fun all(): List<String> = listOfNotNull(shift, bond, ore, claim, sol)
}

/**
 * The words of the clock-out screen. Nothing here promises anything: each line says what the
 * transaction will do, in amounts, and what ORE itself charges.
 */
object ClockOutCopy {

    const val ORE_KEPT = "ORE left in your Miner stays yours: only your wallet can claim it, here or in ORE's own app."

    const val NOTHING_TO_SIGN = "Nothing to sign right now: no shift to seal, no bond to take back and no ORE claimed."

    fun lines(f: ClockOutFacts, zone: ZoneId = ZoneId.systemDefault()): ClockOutLines = ClockOutLines(
        shift = shift(f.shift, zone),
        bond = bond(f.bond),
        ore = if (f.oreInMiner == 0uL) {
            "No ORE in your Miner yet."
        } else {
            "In your ORE Miner: ${ore(f.oreInMiner)} ORE (${ore(f.refinedOre)} refined, ${ore(f.unrefinedOre)} unrefined)."
        },
        claim = f.fullClaim?.takeIf { f.canClaim }?.let(::claim),
        sol = f.returnedSolLamports.takeIf { it > 0uL }?.let { "${ClockInPolicy.sol(it)} SOL that ORE handed back to your Miner goes to your wallet." },
    )

    fun claim(c: OreClaimEstimate): String =
        if (c.fee == 0uL) {
            "Claiming all now sends about ${ore(c.received)} ORE to your wallet, with no refining fee."
        } else {
            "Claiming all now sends about ${ore(c.received)} ORE to your wallet. ORE keeps ${ore(c.fee)} ORE: its 10% refining fee " +
                "on the unrefined part, which it shares among miners who have not claimed."
        }

    fun shift(outcome: ShiftOutcome, zone: ZoneId = ZoneId.systemDefault()): String = when (outcome) {
        ShiftOutcome.NoOpenShift -> "No shift is open."
        is ShiftOutcome.Ends ->
            if (outcome.completed) {
                "Your shift's window is over. Clocking out seals it as completed, which counts for your streak."
            } else {
                "Clocking out seals your shift as ended early: ${reason(outcome.reason)}. It does not count for your streak."
            }
        is ShiftOutcome.LeftOpen -> {
            val until = TIME.format(Instant.ofEpochSecond(outcome.windowEndTs).atZone(zone))
            val later = if (outcome.ifEndedNow == ShiftEndReason.MANUAL) " Left alone, it seals as completed once the window is over." else ""
            "Your shift is still inside its window (until $until), so clocking out leaves it open.$later"
        }
    }

    /** The warning beside "End the shift now": what that choice costs, before it is made. */
    fun endEarlyWarning(f: ClockOutFacts): String? {
        val open = f.shift as? ShiftOutcome.LeftOpen ?: return null
        val bond = (f.bond as? BondOutcome.StaysLocked)?.let { " Your Focus Bond of ${skr(it.amount)} SKR is forfeit." }.orEmpty()
        return "Ending it now seals the shift as ended early (${reason(open.ifEndedNow)}): it does not count for your streak.$bond This cannot be undone."
    }

    fun bond(outcome: BondOutcome): String? = when (outcome) {
        BondOutcome.None -> null
        is BondOutcome.Released -> "Your Focus Bond comes back to your wallet: ${skr(outcome.amount)} SKR."
        is BondOutcome.Forfeit ->
            "Your Focus Bond of ${skr(outcome.amount)} SKR is forfeit: the shift did not complete. It goes to the Bury auction, " +
                "where it is sold for ORE that is burned. The team keeps none of it."
        is BondOutcome.StaysLocked -> "Your Focus Bond of ${skr(outcome.amount)} SKR stays locked until the shift is sealed."
    }

    fun reason(r: ShiftEndReason): String = when (r) {
        ShiftEndReason.COMPLETED -> "completed"
        ShiftEndReason.PICKUP -> "the phone was picked up"
        ShiftEndReason.SCREEN_ON -> "the screen stayed on"
        ShiftEndReason.FREEZE -> "the rig was frozen"
        ShiftEndReason.LEASE_LAPSE -> "no heartbeat was recorded on-chain"
        ShiftEndReason.BUDGET -> "the shift's budget was used up"
        ShiftEndReason.MANUAL -> "ended by hand inside its window"
        ShiftEndReason.UNPLUGGED -> "the charger was unplugged"
        ShiftEndReason.UNLOCKED -> "the phone was unlocked"
    }

    /** What a confirmed clock-out did. [plan] is the one the wallet signed. */
    fun done(plan: ClockOutPlan): String = done(plan.shift, plan.bond, plan.claimedSolLamports, plan.claimedOre)

    fun done(shift: ShiftOutcome, bond: BondOutcome, returnedSolLamports: ULong, claimedOre: OreClaimEstimate?): String {
        val parts = buildList {
            (shift as? ShiftOutcome.Ends)?.let { add(if (it.completed) "Shift sealed as completed." else "Shift sealed as ended early.") }
            when (bond) {
                is BondOutcome.Released -> add("Focus Bond back in your wallet: ${skr(bond.amount)} SKR.")
                is BondOutcome.Forfeit -> add("Focus Bond forfeit: ${skr(bond.amount)} SKR.")
                else -> Unit
            }
            if (returnedSolLamports > 0uL) add("${ClockInPolicy.sol(returnedSolLamports)} SOL back in your wallet.")
            claimedOre?.let { add("About ${ore(it.received)} ORE claimed to your wallet.") }
        }
        return (listOf("Confirmed on-chain.") + parts).joinToString(" ")
    }

    /** ORE atoms (11 decimals), truncated to 8 places; dust below that is "<0.00000001", never "0". */
    fun ore(atoms: ULong): String = fixed(atoms, 11, 8)

    /** SKR base units (6 decimals). */
    fun skr(units: ULong): String = fixed(units, 6, 6)

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private fun fixed(value: ULong, decimals: Int, shown: Int): String {
        var unit = 1uL
        repeat(decimals) { unit *= 10uL }
        val whole = value / unit
        val frac = (value % unit).toString().padStart(decimals, '0').take(shown).trimEnd('0')
        if (whole == 0uL && frac.isEmpty() && value > 0uL) return "<0." + "0".repeat(shown - 1) + "1"
        return if (frac.isEmpty()) whole.toString() else "$whole.$frac"
    }
}
