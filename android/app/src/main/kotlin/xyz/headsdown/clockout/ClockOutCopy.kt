package xyz.headsdown.clockout

import xyz.headsdown.core.chain.accounts.OreClaimEstimate
import xyz.headsdown.core.chain.clockout.BondOutcome
import xyz.headsdown.core.chain.clockout.BuyOutcome
import xyz.headsdown.core.chain.clockout.ClockOutPlan
import xyz.headsdown.core.chain.clockout.ClockOutPreview
import xyz.headsdown.core.chain.clockout.ShiftOutcome
import xyz.headsdown.core.chain.swap.SwapSkip
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.rig.ClockInPolicy
import java.math.BigDecimal
import java.math.RoundingMode
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
    /** Rent of the log that sealing the shift stores on-chain, paid by the wallet (0 = no open shift). */
    val shiftLogRent: ULong = 0uL,
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
            shiftLogRent = preview.shiftLogRent,
        )
    }
}

/** The lines of the clock-out screen. Every one is a statement of what the transaction does. */
data class ClockOutLines(
    val shift: String,
    /** What sealing the shift costs in rent, and how it comes back; null when no shift is sealed. */
    val rent: String?,
    val bond: String?,
    val ore: String,
    /** What "claim all" delivers and what ORE keeps; null when there is nothing to claim. */
    val claim: String?,
    val sol: String?,
) {
    fun all(): List<String> = listOfNotNull(shift, rent, bond, ore, claim, sol)
}

/**
 * The words of the clock-out screen. Nothing here promises anything: each line says what the
 * transaction will do, in amounts, and what ORE itself charges.
 */
object ClockOutCopy {

    const val ORE_KEPT = "ORE left in your Miner stays yours: only your wallet can claim it, here or in ORE's own app."

    const val NOTHING_TO_SIGN = "Nothing to sign right now: with these choices no shift is sealed, no bond comes back and no ORE is claimed."

    /** Under "Buy ORE", before a choice is made: what the buy is and where the ORE goes. */
    const val BUY_OFFER = "Swap a little SOL for ORE at the market price, in the same approval. The swap runs through Jupiter, " +
        "and the ORE goes to your wallet, not to your Miner."

    /** True for a first buy only, and the sheet cannot tell: said as a condition. */
    const val BUY_FIRST_ORE = "If this wallet has never held ORE, the swap also opens its ORE token account: about 0.002 SOL of rent on top, " +
        "which comes back when that account is closed."

    const val BUY_QUOTING = "Reading the market price…"

    /** The swap had a transaction of its own, and that one did not land. */
    const val BUY_NOT_LANDED = "No ORE was bought: the swap did not go through on-chain."

    const val BUY_NOT_CONFIRMED = "The swap for ORE could not be confirmed on-chain: check your wallet's activity for it."

    const val BUY_NO_QUOTE = "No market quote right now, so no ORE will be bought. The rest of the clock-out is unchanged."

    /** The chip for a spend: "Off", or the amount. */
    fun buyLabel(spendLamports: ULong): String = if (spendLamports == 0uL) "Off" else "${ClockInPolicy.sol(spendLamports)} SOL"

    /** What the quote on the sheet promises: the amount at the quote, its price, and the least the swap may deliver. */
    fun buyQuote(q: BuyQuote): String {
        val route = q.route.takeIf { it.isNotEmpty() }?.let { " Route: ${it.joinToString(", ")}." }.orEmpty()
        return "${ClockInPolicy.sol(q.spendLamports)} SOL buys about ${ore(q.oreAtoms)} ORE right now: ${price(q.spendLamports, q.oreAtoms)} SOL per ORE. " +
            "If the price moves and the swap would deliver less than ${ore(q.minOreAtoms)} ORE, nothing is bought.$route " +
            "Heads Down adds no fee; the pools' own fees are in that price."
    }

    /** SOL per whole ORE of a quote, 3 decimals, rounded up: the user never pays less than is shown. */
    fun price(spendLamports: ULong, oreAtoms: ULong): String {
        if (oreAtoms == 0uL) return "?"
        // (lamports / 1e9) / (atoms / 1e11) = lamports * 100 / atoms.
        return BigDecimal(spendLamports.toString()).multiply(BigDecimal(100)).divide(BigDecimal(oreAtoms.toString()), 3, RoundingMode.UP).toPlainString()
    }

    /** What became of the buy, for the confirmed result; null when none was asked for. */
    fun bought(buy: BuyOutcome): String? = when (buy) {
        BuyOutcome.NotRequested -> null
        is BuyOutcome.Included ->
            "${ClockInPolicy.sol(buy.quote.request.inAmount)} SOL swapped for ORE: at least ${ore(buy.quote.minOutAmount)} ORE is in your wallet."
        is BuyOutcome.Skipped -> "No ORE was bought: ${skipped(buy.reason)}."
    }

    fun skipped(reason: SwapSkip): String = when (reason) {
        SwapSkip.NOT_AVAILABLE -> "this build has no market to buy from"
        SwapSkip.NO_QUOTE -> "the market had no route for that amount"
        SwapSkip.PRICE_MOVED -> "the price moved past what the sheet showed"
        SwapSkip.PROVIDER_FAILED -> "the market quote could not be read or did not pass the app's checks"
        SwapSkip.NEEDS_V0 -> "your wallet cannot sign the kind of transaction the swap needs"
        SwapSkip.TABLE_UNAVAILABLE, SwapSkip.DOES_NOT_FIT -> "the swap could not be fitted into a transaction"
        SwapSkip.SIMULATION_FAILED -> "the swap would have failed just now (most often: not enough SOL in the wallet for it)"
        SwapSkip.DELIVERS_TOO_LITTLE -> "the swap would have delivered less than its minimum"
        SwapSkip.TAKES_TOO_MUCH -> "the swap would have taken more SOL than you chose"
        SwapSkip.ONE_TRANSACTION_ONLY -> "the swap needs a transaction of its own and your wallet signs one at a time"
    }

    fun lines(f: ClockOutFacts, zone: ZoneId = ZoneId.systemDefault()): ClockOutLines = ClockOutLines(
        shift = shift(f.shift, zone),
        rent = rent(f),
        bond = bond(f.bond),
        ore = if (f.oreInMiner == 0uL) {
            "No ORE in your Miner yet."
        } else {
            "In your ORE Miner: ${ore(f.oreInMiner)} ORE (${ore(f.refinedOre)} refined, ${ore(f.unrefinedOre)} unrefined)."
        },
        claim = f.fullClaim?.takeIf { f.canClaim }?.let(::claim),
        sol = f.returnedSolLamports.takeIf { it > 0uL }?.let { "${ClockInPolicy.sol(it)} SOL that ORE handed back to your Miner goes to your wallet." },
    )

    /** Sealing a shift writes its ShiftLog, and the wallet that seals it pays that account's rent. */
    fun rent(f: ClockOutFacts): String? =
        if (f.shift is ShiftOutcome.Ends && f.shiftLogRent > 0uL) {
            "Sealing stores the shift's log on-chain, and your wallet pays ${ClockInPolicy.sol(f.shiftLogRent)} SOL of rent for it. " +
                "From 30 days after the shift ended, anyone can send that rent back to your wallet."
        } else {
            null
        }

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
            when (outcome.ifEndedNow) {
                // Still running: it only has to be left alone.
                ShiftEndReason.MANUAL ->
                    "Your shift is still inside its window (until $until), so clocking out leaves it open. " +
                        "Left alone, it seals as completed once the window is over."
                ShiftEndReason.LEASE_LAPSE ->
                    "Your shift has no dark round on-chain yet and is still inside its window (until $until), so clocking out leaves it open."
                // Cooled by a pickup, the screen or the charger: it will not seal as completed any more.
                else ->
                    "Your shift cooled (${reason(outcome.ifEndedNow)}) and is still inside its window (until $until), so clocking out " +
                        "leaves it open. It will be sealed as ended early: by \"End the shift now\", or by your next clock-in."
            }
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

    /** What a confirmed clock-out did. [plan] is the one the wallet signed, [buy] what became of the buy. */
    fun done(plan: ClockOutPlan, buy: BuyOutcome = BuyOutcome.NotRequested): String =
        done(plan.shift, plan.bond, plan.claimedSolLamports, plan.claimedOre, bought(buy))

    fun done(shift: ShiftOutcome, bond: BondOutcome, returnedSolLamports: ULong, claimedOre: OreClaimEstimate?, bought: String? = null): String {
        val parts = buildList {
            (shift as? ShiftOutcome.Ends)?.let { add(if (it.completed) "Shift sealed as completed." else "Shift sealed as ended early.") }
            when (bond) {
                is BondOutcome.Released -> add("Focus Bond back in your wallet: ${skr(bond.amount)} SKR.")
                is BondOutcome.Forfeit -> add("Focus Bond forfeit: ${skr(bond.amount)} SKR.")
                else -> Unit
            }
            if (returnedSolLamports > 0uL) add("${ClockInPolicy.sol(returnedSolLamports)} SOL back in your wallet.")
            claimedOre?.let { add("About ${ore(it.received)} ORE claimed to your wallet.") }
            bought?.let(::add)
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
