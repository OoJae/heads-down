package xyz.headsdown.core.chain.clockin

import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.accounts.HdConfig
import xyz.headsdown.core.chain.accounts.OreAutomation
import xyz.headsdown.core.chain.accounts.RigAccount
import xyz.headsdown.core.chain.ix.ComputeBudgetInstructions
import xyz.headsdown.core.chain.ix.HeadsDownInstructions
import xyz.headsdown.core.chain.ix.OreInstructions
import xyz.headsdown.core.chain.ix.RigCaps
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.core.keys.ShiftPlan

/**
 * What the user asked for at clock-in. Budgets are lamports, costs lamports per ORE, times
 * unix seconds. Defaults follow `ml/forecaster/RESULTS.md`: concentrated 0.001 SOL digs on
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
    val capsValiditySeconds: Long = SECONDS_PER_WEEK,
    /** 0 = no ComputeBudget instructions (the wallet may add its own priority fee). */
    val priorityMicroLamports: ULong = 0uL,
) {
    init {
        require(windowSeconds in 60..MAX_WINDOW_SECONDS) { "shift window must be 1 minute to 24 hours" }
        require(capsValiditySeconds in 3_600..(4 * SECONDS_PER_WEEK)) { "caps validity must be 1 hour to 4 weeks" }
        require(planMaxEvCostPerOre <= capMaxCostPerOre) { "plan ceiling must not exceed the wallet cap" }
        if (!focusOnly) {
            require(digLamports >= MIN_DIG_LAMPORTS) { "digs are concentrated chunks of at least 0.001 SOL" }
            require(shiftBudgetLamports >= digLamports) { "the shift budget must cover one dig" }
            require(weeklyBudgetLamports >= shiftBudgetLamports) { "the weekly cap must cover the shift" }
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
)

/** Why a clock-in cannot be built. The message is fixed text, safe to show. */
class ClockInRefusedException(val reason: Reason) : IllegalStateException(reason.message) {
    enum class Reason(val message: String) {
        RIG_FROZEN("Rig frozen. Unfreeze it with your wallet first."),
        OTHER_AUTHORITY("This Rig belongs to a different wallet."),
    }
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
)

/**
 * Composes the single clock-in transaction:
 *
 * `[ComputeBudget?] [end_shift?] [rotate_key?] [ORE automate?] [register_rig?] set_caps arm_shift`
 *
 * - **ORE automate** (the deposit) points the user's own Automation at the heads_down Executor
 *   PDA with Discretionary strategy and `fee = Config.executor_fee`, per-tile cap =
 *   `dig_lamports / tiles`, and tops the balance up to the shift budget plus one executor fee
 *   per possible dig. It is skipped when nothing would change, and always for focus-only shifts.
 * - **register_rig** only when the Rig does not exist; **rotate_key** when it exists with a
 *   different device key (reinstall); **end_shift** when a previous shift was left open.
 * - **set_caps** then **arm_shift** (wallet path) with a plan inside the caps.
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
    ): ClockInPlan {
        val rigAddress = HeadsDownProgram.rig(authority).address
        val rig = state.rig
        if (rig != null) {
            if (rig.authority != authority) throw ClockInRefusedException(ClockInRefusedException.Reason.OTHER_AUTHORITY)
            if (rig.state == RigSignalState.FROZEN) throw ClockInRefusedException(ClockInRefusedException.Reason.RIG_FROZEN)
        }

        val tiles = if (request.focusOnly) 0 else request.splitTiles + request.soloTiles
        val plan = ShiftPlan(
            maxEvCost = if (request.focusOnly) 0uL else request.planMaxEvCostPerOre,
            digLamports = if (request.focusOnly) 0uL else request.digLamports,
            splitTiles = if (request.focusOnly) 0 else request.splitTiles,
            soloTiles = if (request.focusOnly) 0 else request.soloTiles,
            leaseRounds = request.leaseRounds,
            flags = if (request.focusOnly) ShiftPlan.FLAG_FOCUS_ONLY else 0,
            windowStartTs = nowUnix,
            windowEndTs = Math.addExact(nowUnix, request.windowSeconds),
        )
        val caps = RigCaps(
            capWeek = request.weeklyBudgetLamports,
            capShift = request.shiftBudgetLamports,
            capRound = if (request.focusOnly) minOf(request.digLamports, request.shiftBudgetLamports) else request.digLamports,
            capMaxCost = request.capMaxCostPerOre,
            capsExpiryTs = Math.addExact(nowUnix, request.capsValiditySeconds),
        )
        check(caps.admits(plan)) { "plan exceeds caps" }

        val ixs = mutableListOf<Instruction>()
        if (request.priorityMicroLamports > 0uL) {
            ixs += ComputeBudgetInstructions.setComputeUnitLimit(COMPUTE_UNIT_LIMIT)
            ixs += ComputeBudgetInstructions.setComputeUnitPrice(request.priorityMicroLamports)
        }

        val endsPrevious = rig != null && rig.state in OPEN_STATES
        if (rig != null && endsPrevious) ixs += HeadsDownInstructions.endShift(authority, rigAddress, rig.shiftId)

        val rotates = rig != null && !rig.p256Pubkey.toByteArray().contentEquals(rigKey)
        if (rotates) ixs += HeadsDownInstructions.rotateKey(authority, rigKey)

        var deposit = 0uL
        var automate = false
        if (!request.focusOnly) {
            val perTile = request.digLamports / tiles.toULong()
            check(perTile > 0uL) { "per-tile amount rounds to zero" }
            val fee = state.config.executorFee
            val maxDigs = ceilDiv(request.shiftBudgetLamports, request.digLamports)
            val target = checkedAdd(request.shiftBudgetLamports, checkedMul(maxDigs, fee))
            val current = state.automation?.balance ?: 0uL
            deposit = if (target > current) target - current else 0uL
            val a = state.automation
            val upToDate = a != null && a.executor == HeadsDownProgram.executor.address && a.isDiscretionary &&
                a.fee == fee && a.amount == perTile && a.reload == 1uL && a.authority == authority
            automate = !upToDate || deposit > 0uL
            if (automate) ixs += OreInstructions.automateHeadsDown(authority, perTile, deposit, fee)
        }

        val registers = rig == null
        if (registers) ixs += HeadsDownInstructions.registerRig(authority, rigKey)
        ixs += HeadsDownInstructions.setCaps(authority, caps)
        ixs += HeadsDownInstructions.armShift(authority, plan)

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
            expectedShiftId = checkedAdd(rig?.shiftId ?: 0uL, 1uL),
            hbCounterFloor = rig?.hbCounter ?: 0uL,
        )
    }

    /** Covers ORE automate (account creation), register_rig and the two small updates. */
    const val COMPUTE_UNIT_LIMIT = 400_000L

    private val OPEN_STATES = setOf(RigSignalState.ARMED, RigSignalState.DOWN, RigSignalState.COOLING, RigSignalState.BROKEN)

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
