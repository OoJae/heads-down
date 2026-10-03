package xyz.headsdown.core.chain.clockin

import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.accounts.FocusBondAccount
import xyz.headsdown.core.chain.accounts.HdConfig
import xyz.headsdown.core.chain.accounts.OreAutomation
import xyz.headsdown.core.chain.accounts.RigAccount
import xyz.headsdown.core.chain.accounts.ShiftLogAccount
import xyz.headsdown.core.chain.clockout.ShiftSeal
import xyz.headsdown.core.chain.ix.AssociatedTokenInstructions
import xyz.headsdown.core.chain.ix.ComputeBudgetInstructions
import xyz.headsdown.core.chain.ix.HeadsDownInstructions
import xyz.headsdown.core.chain.ix.OreInstructions
import xyz.headsdown.core.chain.ix.RigCaps
import xyz.headsdown.core.chain.ix.SkrInstructions
import xyz.headsdown.core.chain.registrar.RegistrarVoucher
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.keys.RigMessageFormat
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.core.keys.ShiftPlan

/**
 * What the user asked for at clock-in. Budgets are lamports of SOL **placed on squares**; the
 * executor fee is added on top by the composer (INTERFACE v1.1 §6.4). Costs are lamports per ORE,
 * times unix seconds. Defaults follow `ml/forecaster/RESULTS.md`: concentrated 0.001 SOL digs on
 * split tiles, heartbeat lease of one round.
 */
data class ClockInRequest(
    val shiftBudgetLamports: ULong,
    val weeklyBudgetLamports: ULong,
    /** Ceiling the wallet signs on the pot-adjusted cost `ema_ev` (`cap_max_cost`). */
    val capMaxCostPerOre: ULong,
    /** The plan's own ceiling (`plan_max_ev_cost ≤ cap_max_cost`), computed at arm time. */
    val planMaxEvCostPerOre: ULong,
    val windowSeconds: Long,
    val digLamports: ULong = DEFAULT_DIG_LAMPORTS,
    val splitTiles: Int = DEFAULT_SPLIT_TILES,
    val soloTiles: Int = 0,
    val leaseRounds: Int = 1,
    val focusOnly: Boolean = false,
    /** A Day Shift: `plan_flags` bit1, recorded on-chain as `ShiftLog.mode` 1. */
    val day: Boolean = false,
    val capsValiditySeconds: Long = SECONDS_PER_WEEK,
    /** 0 = no ComputeBudget instructions (the wallet may add its own priority fee). */
    val priorityMicroLamports: ULong = 0uL,
    /**
     * A Focus Bond: SKR base units to lock on the shift this clock-in arms (0 = none). It comes
     * back when the shift seals `completed`; otherwise it goes to the Bury auction, never to the
     * team (INTERFACE §11.6).
     */
    val focusBondSkr: ULong = 0uL,
) {
    init {
        require(focusBondSkr <= Skr.FOCUS_BOND_CAP) { "a Focus Bond is at most 5,000 SKR" }
        require(windowSeconds in 60..MAX_WINDOW_SECONDS) { "shift window must be 1 minute to 24 hours" }
        require(capsValiditySeconds in 3_600..(4 * SECONDS_PER_WEEK)) { "caps validity must be 1 hour to 4 weeks" }
        require(planMaxEvCostPerOre <= capMaxCostPerOre) { "plan ceiling must not exceed the wallet cap" }
        require(leaseRounds in 1..RigMessageFormat.MAX_LEASE_ROUNDS) { "lease rounds must be 1..3" }
        if (!focusOnly) {
            require(digLamports >= MIN_DIG_LAMPORTS) { "digs are concentrated chunks of at least 0.001 SOL" }
            require(shiftBudgetLamports >= digLamports) { "the shift budget must cover one dig" }
            require(weeklyBudgetLamports >= shiftBudgetLamports) { "the weekly cap must cover the shift" }
            require(splitTiles in 0..ShiftPlan.MAX_SPLIT_TILES && soloTiles in 0..ShiftPlan.MAX_SOLO_TILES) { "tiles out of range" }
            require(splitTiles + soloTiles >= 1) { "a mining shift needs at least one tile" }
        }
    }

    companion object {
        const val DEFAULT_DIG_LAMPORTS: ULong = 1_000_000uL
        const val MIN_DIG_LAMPORTS: ULong = 1_000_000uL
        const val DEFAULT_SPLIT_TILES = 4
        const val SECONDS_PER_WEEK = 7L * 24 * 3600
        const val MAX_WINDOW_SECONDS = 24L * 3600
    }
}

/** On-chain state read (owner- and PDA-checked) just before building. */
class ClockInChainState(
    val config: HdConfig,
    /** null: first clock-in, the Rig does not exist yet. */
    val rig: RigAccount?,
    /** null: the ORE Automation does not exist yet. */
    val automation: OreAutomation?,
    /** The cluster slot the reads were made at (voucher expiry check); null when unknown. */
    val slot: ULong? = null,
    /** ORE `Board.round_id`: the `end_round` an `end_shift` in this transaction would record. */
    val boardRoundId: ULong? = null,
    /** SKR in the wallet's token account (0 when it has none). */
    val skrBalance: ULong = 0uL,
    val skrAccountExists: Boolean = false,
    /** A Focus Bond still locked on the rig's current (or last) shift, and that shift's log if sealed. */
    val previousBond: FocusBondAccount? = null,
    val previousShiftLog: ShiftLogAccount? = null,
)

/** Why a clock-in cannot be built. The message is fixed text, safe to show. */
class ClockInRefusedException(val reason: Reason) : IllegalStateException(reason.message) {
    enum class Reason(val message: String) {
        RIG_FROZEN("Rig frozen. Unfreeze it with your wallet first."),
        OTHER_AUTHORITY("This Rig belongs to a different wallet."),
        RIG_BUSY("This Rig is in a state that cannot be armed. End its shift with your wallet first."),
        BOND_WOULD_FORFEIT("Your last shift still holds a Focus Bond and its window has not ended. Clocking in now would forfeit it."),
        INSUFFICIENT_SKR("Not enough SKR in this wallet for the Focus Bond."),
    }
}

/** What happened to a registrar voucher at clock-in. */
enum class VoucherUse {
    /** No voucher held: the rig registers (or stays) a guest. */
    NONE,

    /** The Ed25519 voucher is in the transaction; the rig gets its attestation level. */
    INCLUDED,

    /** The rig already carries at least this level with this key: nothing to send. */
    ALREADY_ATTESTED,

    /** Held but for another wallet or key: never sent. */
    SKIPPED_OTHER_KEY,

    /** Signed by a key that is not `Config.registrar`: the program would refuse it. */
    SKIPPED_WRONG_REGISTRAR,

    /** Expired, too close to expiring, or the slot is unknown. */
    SKIPPED_EXPIRED,
}

/** The instructions for one clock-in transaction and what they will do. */
class ClockInPlan(
    val instructions: List<Instruction>,
    val plan: ShiftPlan,
    val caps: RigCaps,
    val rig: Pubkey,
    /** Lamports moved from the wallet into the user's own ORE Automation (0 = none). */
    val deposit: ULong,
    val includesAutomate: Boolean,
    val registersRig: Boolean,
    val rotatesKey: Boolean,
    val endsPreviousShift: Boolean,
    /** `rig.shift_id` after `arm_shift` (it increments by one). */
    val expectedShiftId: ULong,
    /** On-chain `hb_counter`: the local counter must be raised to at least this. */
    val hbCounterFloor: ULong,
    val voucher: VoucherUse = VoucherUse.NONE,
    /** SKR locked as a Focus Bond on the new shift by this transaction (0 = none). */
    val bondLocked: ULong = 0uL,
    /** SKR of the previous shift's Focus Bond that this transaction returns to the wallet (0 = none). */
    val bondReleased: ULong = 0uL,
)

/**
 * Composes the single clock-in transaction:
 *
 * `[ComputeBudget?] [end_shift?] [release_focus_bond?] [ed25519 voucher? rotate_key?] [ORE automate?] [ed25519 voucher? register_rig?] set_caps arm_shift [bond vault, lock_focus_bond]?`
 *
 * - **ORE automate** points the user's own Automation at the heads_down Executor PDA with the
 *   Discretionary strategy and `fee = Config.executor_fee` (read at Config @80; any other fee
 *   makes every dig a `StrategyMismatch` skip), per-tile cap `dig_lamports / tiles`, and tops the
 *   balance up to the shift budget plus one executor fee per dig round. It is skipped when
 *   nothing would change, and always for focus-only shifts.
 * - **The fee is inside every cap** (INTERFACE v1.1 §6.4): `dig` reserves `automation.fee` from
 *   `min(cap_round, cap_shift - spent_shift, cap_week - spent_week)` before placing SOL, so
 *   `cap_round = dig_lamports + executor_fee` and `cap_shift` / `cap_week` carry one executor fee
 *   per dig round their budgets allow. Otherwise every dig would place less than the plan.
 * - **register_rig** only when the Rig does not exist; **rotate_key** when it exists with a
 *   different device key (reinstall), or to attach a registrar voucher; **end_shift** when the
 *   Rig still has a shift open (`shift_open`, Rig @336).
 * - A **registrar voucher** is included only when it covers this wallet and key, was signed by
 *   `Config.registrar` and will not expire before the transaction lands; anything else registers
 *   the rig as a guest (level-0 or unusable vouchers would fail the whole clock-in on-chain).
 * - **set_caps** then **arm_shift** (wallet path) with a plan inside the caps; `plan_flags` bit0
 *   for focus-only, bit1 for a Day Shift.
 * - **A Focus Bond** ([ClockInRequest.focusBondSkr]): `lock_focus_bond` right after `arm_shift`,
 *   behind the companion that creates the bond's SKR vault. The program only asks that the shift
 *   be open and clean, which it is one instruction earlier (INTERFACE §11.4, tag 20).
 * - **The previous shift's bond**: when this clock-in ends a shift that seals `completed` (or the
 *   shift is already sealed so), its bond is released in the same transaction. A bond on a shift
 *   still inside its window would be forfeited by ending it: that clock-in is refused.
 *
 * Pure function of its inputs: every decision is unit-tested without a network.
 */
object ClockInComposer {

    fun compose(
        authority: Pubkey,
        rigKey: ByteArray,
        request: ClockInRequest,
        state: ClockInChainState,
        nowUnix: Long,
        voucher: RegistrarVoucher? = null,
    ): ClockInPlan {
        val rigAddress = HeadsDownProgram.rig(authority).address
        val rig = state.rig
        if (rig != null) {
            if (rig.authority != authority) throw ClockInRefusedException(ClockInRefusedException.Reason.OTHER_AUTHORITY)
            if (rig.state == RigSignalState.FROZEN) throw ClockInRefusedException(ClockInRefusedException.Reason.RIG_FROZEN)
            // arm_shift needs Idle: only an open shift can be closed here (end_shift requires shift_open).
            if (rig.state != RigSignalState.IDLE && !rig.shiftOpen) throw ClockInRefusedException(ClockInRefusedException.Reason.RIG_BUSY)
        }

        val fee = state.config.executorFee
        val focus = request.focusOnly
        val tiles = if (focus) 0 else request.splitTiles + request.soloTiles
        val flags = (if (focus) ShiftPlan.FLAG_FOCUS_ONLY else 0) or (if (request.day) ShiftPlan.FLAG_DAY else 0)
        val plan = ShiftPlan(
            maxEvCost = if (focus) 0uL else request.planMaxEvCostPerOre,
            digLamports = if (focus) 0uL else request.digLamports,
            splitTiles = if (focus) 0 else request.splitTiles,
            soloTiles = if (focus) 0 else request.soloTiles,
            leaseRounds = request.leaseRounds,
            flags = flags,
            windowStartTs = nowUnix,
            windowEndTs = Math.addExact(nowUnix, request.windowSeconds),
        )
        val capsExpiry = Math.addExact(nowUnix, request.capsValiditySeconds)
        val caps = if (focus) {
            // A focus-only shift never deploys: the wallet grants no spending at all.
            RigCaps(capWeek = 0uL, capShift = 0uL, capRound = 0uL, capMaxCost = 0uL, capsExpiryTs = capsExpiry)
        } else {
            RigCaps(
                capWeek = withFees(request.weeklyBudgetLamports, request.digLamports, fee),
                capShift = withFees(request.shiftBudgetLamports, request.digLamports, fee),
                capRound = checkedAdd(request.digLamports, fee),
                capMaxCost = request.capMaxCostPerOre,
                capsExpiryTs = capsExpiry,
            )
        }
        check(caps.admits(plan)) { "plan exceeds caps" }

        val ixs = mutableListOf<Instruction>()
        if (request.priorityMicroLamports > 0uL) {
            ixs += ComputeBudgetInstructions.setComputeUnitLimit(COMPUTE_UNIT_LIMIT)
            ixs += ComputeBudgetInstructions.setComputeUnitPrice(request.priorityMicroLamports)
        }

        val endsPrevious = rig != null && rig.shiftOpen
        if (rig != null && endsPrevious) ixs += HeadsDownInstructions.endShift(authority, rigAddress, rig.shiftId)

        // The Focus Bond on the shift being ended (or already sealed): release it when that shift
        // is completed; never forfeit it by clocking in over a shift that could still complete.
        var bondReleased = 0uL
        val previousBond = state.previousBond?.takeIf { rig != null && it.rig == rigAddress && it.authority == authority && it.shiftId == rig.shiftId }
        if (rig != null && previousBond != null) {
            val log = state.previousShiftLog
            val completes = when {
                log != null -> previousBond.isResolvedBy(log) && log.completed
                endsPrevious -> {
                    val reason = ShiftSeal.predict(rig, state.boardRoundId ?: ULong.MAX_VALUE, nowUnix)
                    val fixed = rig.state == RigSignalState.BROKEN || rig.state == RigSignalState.FROZEN || ShiftSeal.pastWindow(rig, nowUnix)
                    if (reason != ShiftEndReason.COMPLETED && !fixed) throw ClockInRefusedException(ClockInRefusedException.Reason.BOND_WOULD_FORFEIT)
                    reason == ShiftEndReason.COMPLETED
                }
                else -> false
            }
            if (completes) {
                if (!state.skrAccountExists) ixs += AssociatedTokenInstructions.createIdempotent(authority, authority, Skr.MINT)
                ixs += SkrInstructions.releaseFocusBond(authority, previousBond.shiftId)
                bondReleased = previousBond.amount
            }
        }

        val registers = rig == null
        val keyChanges = rig != null && !rig.p256Pubkey.toByteArray().contentEquals(rigKey)
        val voucherUse = voucherUse(voucher, authority, rigKey, state, rig, keyChanges)
        val attestationUpgrade = rig != null && !keyChanges && voucherUse == VoucherUse.INCLUDED
        val rotates = keyChanges || attestationUpgrade
        if (rotates) {
            val attestation = if (voucherUse == VoucherUse.INCLUDED) {
                ixs += voucher!!.instruction
                voucher.attestation(ed25519Ix = ixs.lastIndex)
            } else {
                null
            }
            ixs += HeadsDownInstructions.rotateKey(authority, rigKey, attestation)
        }

        var deposit = 0uL
        var automate = false
        if (!focus) {
            val perTile = request.digLamports / tiles.toULong()
            check(perTile > 0uL) { "per-tile amount rounds to zero" }
            val target = caps.capShift
            val current = state.automation?.balance ?: 0uL
            deposit = if (target > current) target - current else 0uL
            val a = state.automation
            val upToDate = a != null && a.executor == HeadsDownProgram.executor.address && a.isDiscretionary &&
                a.fee == fee && a.amount == perTile && a.reload == 1uL && a.authority == authority
            automate = !upToDate || deposit > 0uL
            if (automate) ixs += OreInstructions.automateHeadsDown(authority, perTile, deposit, fee)
        }

        if (registers) {
            val attestation = if (voucherUse == VoucherUse.INCLUDED) {
                ixs += voucher!!.instruction
                voucher.attestation(ed25519Ix = ixs.lastIndex)
            } else {
                null
            }
            ixs += HeadsDownInstructions.registerRig(authority, rigKey, attestation)
        }
        ixs += HeadsDownInstructions.setCaps(authority, caps)
        ixs += HeadsDownInstructions.armShift(authority, plan)

        val expectedShiftId = checkedAdd(rig?.shiftId ?: 0uL, 1uL)
        if (request.focusBondSkr > 0uL) {
            // A bond released earlier in this transaction is spendable again by now.
            if (request.focusBondSkr > checkedAdd(state.skrBalance, bondReleased)) {
                throw ClockInRefusedException(ClockInRefusedException.Reason.INSUFFICIENT_SKR)
            }
            ixs += SkrInstructions.focusBondVault(authority, expectedShiftId)
            ixs += SkrInstructions.lockFocusBond(authority, expectedShiftId, request.focusBondSkr)
        }

        return ClockInPlan(
            instructions = ixs,
            plan = plan,
            caps = caps,
            rig = rigAddress,
            deposit = deposit,
            includesAutomate = automate,
            registersRig = registers,
            rotatesKey = rotates,
            endsPreviousShift = endsPrevious,
            expectedShiftId = expectedShiftId,
            hbCounterFloor = rig?.hbCounter ?: 0uL,
            voucher = voucherUse,
            bondLocked = request.focusBondSkr,
            bondReleased = bondReleased,
        )
    }

    /**
     * Covers ORE automate (account creation), register_rig with a voucher, the two small updates,
     * and a Focus Bond (its vault creation, the lock and a release of the previous one).
     */
    const val COMPUTE_UNIT_LIMIT = 400_000L

    /**
     * Whether the voucher goes into this transaction. It is only ever needed where the key is set:
     * `register_rig`, `rotate_key`, or a `rotate_key` to the same key that raises the rig's level.
     */
    private fun voucherUse(
        voucher: RegistrarVoucher?,
        authority: Pubkey,
        rigKey: ByteArray,
        state: ClockInChainState,
        rig: RigAccount?,
        keyChanges: Boolean,
    ): VoucherUse {
        if (voucher == null) return VoucherUse.NONE
        if (!voucher.covers(authority, rigKey)) return VoucherUse.SKIPPED_OTHER_KEY
        if (voucher.registrar != state.config.registrar) return VoucherUse.SKIPPED_WRONG_REGISTRAR
        val slot = state.slot ?: return VoucherUse.SKIPPED_EXPIRED
        if (!voucher.usableAt(slot)) return VoucherUse.SKIPPED_EXPIRED
        if (rig != null && !keyChanges) {
            val current = rig.attestationLevel
            val stillValid = rig.attestationExpirySlot > slot
            if (current >= voucher.level && stillValid) return VoucherUse.ALREADY_ATTESTED
        }
        return VoucherUse.INCLUDED
    }

    /** [budget] of SOL placed, plus one executor fee for every dig round that budget allows. */
    private fun withFees(budget: ULong, dig: ULong, fee: ULong): ULong = checkedAdd(budget, checkedMul(ceilDiv(budget, dig), fee))

    private fun ceilDiv(a: ULong, b: ULong): ULong = a / b + if (a % b == 0uL) 0uL else 1uL

    private fun checkedAdd(a: ULong, b: ULong): ULong {
        val r = a + b
        check(r >= a) { "u64 overflow" }
        return r
    }

    private fun checkedMul(a: ULong, b: ULong): ULong {
        if (a == 0uL || b == 0uL) return 0uL
        val r = a * b
        check(r / b == a) { "u64 overflow" }
        return r
    }
}
