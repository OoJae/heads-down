package xyz.headsdown.core.chain.clockout

import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.accounts.FocusBondAccount
import xyz.headsdown.core.chain.accounts.OreBoard
import xyz.headsdown.core.chain.accounts.OreClaimEstimate
import xyz.headsdown.core.chain.accounts.OreClaimMath
import xyz.headsdown.core.chain.accounts.OreMiner
import xyz.headsdown.core.chain.accounts.OreTreasury
import xyz.headsdown.core.chain.accounts.RigAccount
import xyz.headsdown.core.chain.accounts.ShiftLogAccount
import xyz.headsdown.core.chain.ix.AssociatedTokenInstructions
import xyz.headsdown.core.chain.ix.HeadsDownInstructions
import xyz.headsdown.core.chain.ix.OreInstructions
import xyz.headsdown.core.chain.ix.SkrInstructions
import xyz.headsdown.core.chain.swap.SwapRequest
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.core.keys.ShiftEndReason

/** "Buy the rest at market": spend SOL for ORE through the swap provider. */
data class BuyLeg(
    /** Lamports of SOL to spend (exact-in). */
    val spendLamports: ULong,
    /**
     * The least ORE (atoms) the user agreed to receive: the minimum of the quote they were shown.
     * A fresh quote that guarantees less is refused: nothing is bought.
     */
    val minOreAtoms: ULong,
    val slippageBps: Int,
) {
    init {
        require(spendLamports > 0uL) { "nothing to spend" }
        require(minOreAtoms > 0uL) { "a buy needs a minimum the user agreed to" }
        require(slippageBps in 1..SwapRequest.MAX_SLIPPAGE_BPS) { "slippage must be 1..${SwapRequest.MAX_SLIPPAGE_BPS} bps" }
    }
}

/** What the user chose at clock-out. The defaults are the safe ones. */
data class ClockOutRequest(
    /**
     * Share of the Miner's ORE to claim, in basis points. **0 keeps everything unrefined**: no
     * `claim_ore` is sent, no refining fee is paid, and the ORE stays in the user's own ORE Miner
     * account. 10,000 claims all of it.
     */
    val claimOreBps: Int = 0,
    /**
     * End a shift that is still inside its plan window (or still recoverable). On-chain that
     * seals it with a reason other than `completed`: it does not count for the streak and a Focus
     * Bond on it is forfeit. Never set by default.
     */
    val endShiftEarly: Boolean = false,
    /** The user saw that ending early forfeits the Focus Bond on this shift, and chose to. */
    val acceptBondForfeit: Boolean = false,
    val buy: BuyLeg? = null,
    /** 0 = no priority fee (the wallet may add its own). */
    val priorityMicroLamports: ULong = 0uL,
) {
    init {
        require(claimOreBps in 0..10_000) { "claim share must be 0..10000 bps" }
    }
}

/** On-chain state read (owner- and PDA-checked) just before composing. */
class ClockOutChainState(
    /** null: no Rig for this wallet. */
    val rig: RigAccount?,
    /** `Board.round_id` is the `end_round` an `end_shift` would record. */
    val board: OreBoard,
    /** null: the wallet has no ORE Miner yet. */
    val miner: OreMiner?,
    val treasury: OreTreasury?,
    /** The Focus Bond on the rig's current (or last) shift, if one is still locked. */
    val bond: FocusBondAccount? = null,
    /** That shift's ShiftLog when it is already sealed. */
    val bondShiftLog: ShiftLogAccount? = null,
    /** The wallet's SKR token account exists (a released bond needs somewhere to go). */
    val skrAccountExists: Boolean = true,
)

/** What happens to the open shift. */
sealed interface ShiftOutcome {
    /** No shift is open: nothing to end. */
    data object NoOpenShift : ShiftOutcome

    /** `end_shift` is in the transaction and will seal the shift with [reason]. */
    data class Ends(val reason: ShiftEndReason) : ShiftOutcome {
        /** Only a `completed` shift counts for the streak and releases a Focus Bond. */
        val completed: Boolean get() = reason == ShiftEndReason.COMPLETED
    }

    /**
     * The shift is left open: ending it now would seal it [ifEndedNow], not `completed`. After
     * [windowEndTs] it seals `completed` by itself (anyone may end it then), or at the next clock-in.
     */
    data class LeftOpen(val ifEndedNow: ShiftEndReason, val windowEndTs: Long) : ShiftOutcome
}

/** What happens to a Focus Bond on the shift. */
sealed interface BondOutcome {
    data object None : BondOutcome

    /** `release_focus_bond` is in the transaction: the SKR returns to the wallet. */
    data class Released(val amount: ULong) : BondOutcome

    /**
     * The bonded shift sealed (or will seal in this transaction) with [reason]: the SKR goes to
     * the Bury auction. `forfeit_focus_bond` is permissionless; the phone does not send it.
     */
    data class Forfeit(val amount: ULong, val reason: ShiftEndReason?) : BondOutcome

    /** The shift stays open, so the bond stays locked until it is sealed. */
    data class StaysLocked(val amount: ULong) : BondOutcome
}

/** Why a clock-out cannot be built. The message is fixed text, safe to show. */
class ClockOutRefusedException(val reason: Reason) : IllegalStateException(reason.message) {
    enum class Reason(val message: String) {
        OTHER_AUTHORITY("This Rig belongs to a different wallet."),
        BOND_WOULD_FORFEIT("Ending this shift now forfeits its Focus Bond. Wait for the shift window to end, or confirm that you accept it."),
    }
}

/**
 * The clock-out's own instructions and what they do. The service adds the compute budget and the
 * optional buy leg around them.
 */
class ClockOutPlan(
    val instructions: List<Instruction>,
    val shift: ShiftOutcome,
    val bond: BondOutcome,
    /** SOL the Miner returns to the wallet (`claim_sol`); 0 = no instruction. */
    val claimedSolLamports: ULong,
    /** What `claim_ore` moves; null = kept unrefined (no instruction). */
    val claimedOre: OreClaimEstimate?,
) {
    val isEmpty: Boolean get() = instructions.isEmpty()
}

/**
 * How `end_shift` will seal a shift (INTERFACE v1.1 §6.8, `instructions/end_shift.rs`), so the
 * phone can say it before the wallet signs:
 *
 * - Cooling or Broken: the stored BREAK reason; Frozen: `freeze`;
 * - Armed or Down with no dark round: `lease_lapse`;
 * - Armed or Down, ended by its authority inside the plan window: `manual`;
 * - otherwise `completed`.
 *
 * The program compares the **cluster's** clock with `plan_window_end_ts`. The phone's clock can
 * run ahead of it, so a shift counts as past its window only [CLOCK_MARGIN_SECONDS] after the
 * window's end: never "completed" on the phone and "manual" on-chain.
 */
object ShiftSeal {
    const val CLOCK_MARGIN_SECONDS = 90L

    fun pastWindow(rig: RigAccount, nowUnix: Long): Boolean = nowUnix > rig.planWindowEndTs + CLOCK_MARGIN_SECONDS

    /** Dark rounds as `end_shift` settles them: a lease running past `end_round` is not counted. */
    fun settledDarkRounds(rig: RigAccount, endRound: ULong): ULong {
        val beyond = if (rig.leaseToRound > endRound) rig.leaseToRound - endRound else 0uL
        return if (rig.shiftDarkRounds > beyond) rig.shiftDarkRounds - beyond else 0uL
    }

    /** The reason `end_shift`, signed by the rig's authority now, would record. */
    fun predict(rig: RigAccount, endRound: ULong, nowUnix: Long): ShiftEndReason = when (rig.state) {
        RigSignalState.BROKEN, RigSignalState.COOLING -> runCatching { ShiftEndReason.fromWire(rig.breakReason) }.getOrDefault(ShiftEndReason.MANUAL)
        RigSignalState.FROZEN -> ShiftEndReason.FREEZE
        else -> when {
            settledDarkRounds(rig, endRound) == 0uL -> ShiftEndReason.LEASE_LAPSE
            !pastWindow(rig, nowUnix) -> ShiftEndReason.MANUAL
            else -> ShiftEndReason.COMPLETED
        }
    }
}

/**
 * Composes the clock-out transaction (without the buy leg, which the service adds):
 *
 * `[end_shift?] [SKR account?] [release_focus_bond?] [ORE claim_sol?] [ORE claim_ore?]`
 *
 * - **end_shift** when a shift is open and ending it changes nothing for the worse: it would seal
 *   `completed`, or its outcome is already fixed (Broken, Frozen, or past its window). A shift
 *   still inside its window is **left open** unless the user asks ([ClockOutRequest.endShiftEarly]):
 *   ending it there seals it `manual`, which costs the night's streak and a Focus Bond.
 * - **release_focus_bond** right after an `end_shift` that seals `completed`, or alone when the
 *   bonded shift is already sealed `completed`. A bond whose shift sealed otherwise is forfeit;
 *   the phone says so and sends nothing (forfeiting is permissionless and goes to the Bury auction).
 * - **claim_sol** when the Miner holds returned SOL (normally zero: Heads Down Automations reload).
 * - **claim_ore(bps)** only when the user chose to claim. The default keeps the ORE unrefined.
 *
 * Pure function of its inputs.
 */
object ClockOutComposer {

    fun compose(authority: Pubkey, request: ClockOutRequest, state: ClockOutChainState, nowUnix: Long): ClockOutPlan {
        val rig = state.rig
        if (rig != null && rig.authority != authority) throw ClockOutRefusedException(ClockOutRefusedException.Reason.OTHER_AUTHORITY)
        val ixs = mutableListOf<Instruction>()

        // ---- the shift
        // "Settled": ending it now changes nothing for the worse. It would seal completed, or its
        // outcome is already fixed (Broken and Frozen are final until end_shift; past its window
        // nothing more can happen to it).
        var settled = false
        val shift: ShiftOutcome = if (rig == null || !rig.shiftOpen) {
            ShiftOutcome.NoOpenShift
        } else {
            val reason = ShiftSeal.predict(rig, state.board.roundId, nowUnix)
            settled = reason == ShiftEndReason.COMPLETED ||
                rig.state == RigSignalState.BROKEN || rig.state == RigSignalState.FROZEN ||
                ShiftSeal.pastWindow(rig, nowUnix)
            if (settled || request.endShiftEarly) ShiftOutcome.Ends(reason) else ShiftOutcome.LeftOpen(reason, rig.planWindowEndTs)
        }

        // ---- the Focus Bond on that shift
        val heldBond = state.bond?.takeIf { rig != null && it.rig == rig.address && it.authority == authority }
        val sealed = heldBond?.let { b -> state.bondShiftLog?.takeIf { b.isResolvedBy(it) } }
        val bond: BondOutcome = when {
            heldBond == null -> BondOutcome.None
            // Already sealed (by a permissionless end_shift, or at an earlier clock-out).
            sealed != null -> if (sealed.completed) BondOutcome.Released(heldBond.amount) else BondOutcome.Forfeit(heldBond.amount, reasonOf(sealed.breakReason))
            // A log in the slot that is not this shift's: the bonded shift can never be sealed.
            state.bondShiftLog != null -> BondOutcome.Forfeit(heldBond.amount, null)
            shift is ShiftOutcome.Ends && rig != null && heldBond.shiftId == rig.shiftId ->
                if (shift.completed) BondOutcome.Released(heldBond.amount) else BondOutcome.Forfeit(heldBond.amount, shift.reason)
            else -> BondOutcome.StaysLocked(heldBond.amount)
        }
        // Ending early is the one case where the user's own choice loses the bond: never silently.
        val endedByChoice = shift is ShiftOutcome.Ends && !settled
        if (endedByChoice && bond is BondOutcome.Forfeit && !request.acceptBondForfeit) {
            throw ClockOutRefusedException(ClockOutRefusedException.Reason.BOND_WOULD_FORFEIT)
        }

        if (shift is ShiftOutcome.Ends && rig != null) ixs += HeadsDownInstructions.endShift(authority, rig.address, rig.shiftId)
        if (bond is BondOutcome.Released && heldBond != null) {
            if (!state.skrAccountExists) ixs += AssociatedTokenInstructions.createIdempotent(authority, authority, Skr.MINT)
            ixs += SkrInstructions.releaseFocusBond(authority, heldBond.shiftId)
        }

        // ---- ORE: returned SOL, and (only if asked) the ORE itself
        val miner = state.miner?.takeIf { it.authority == authority }
        val claimSol = miner?.rewardsSol ?: 0uL
        if (claimSol > 0uL) ixs += OreInstructions.claimSol(authority)
        val claimOre = if (request.claimOreBps > 0 && miner != null && state.treasury != null) {
            OreClaimMath.estimate(miner, state.treasury, request.claimOreBps).takeIf { it.refined + it.unrefined > 0uL }
        } else {
            null
        }
        if (claimOre != null) ixs += OreInstructions.claimOre(authority, request.claimOreBps)

        return ClockOutPlan(ixs, shift, bond, claimSol, claimOre)
    }

    /** Covers end_shift, a bond release, and ORE's claim (which may create the wallet's ORE account). */
    const val COMPUTE_UNITS = 250_000L

    private fun reasonOf(wire: Int): ShiftEndReason? = runCatching { ShiftEndReason.fromWire(wire) }.getOrNull()

    /** The bond PDA a clock-out looks at: the one on the rig's current (or last) shift. */
    fun bondAddress(authority: Pubkey, shiftId: ULong): Pubkey =
        HeadsDownProgram.focusBond(HeadsDownProgram.rig(authority).address, shiftId).address
}
