package xyz.headsdown.core.chain.accounts

import xyz.headsdown.core.chain.Ore
import java.math.BigInteger

/**
 * The gate `dig` applies in every round, ported from `programs/heads-down/program/src/logic.rs`
 * (`ema_ev`, `gate_open`; the crank mirrors it in `crank/src/gate.rs`), so the phone can say
 * before a shift whether the rig would dig at ORE's figure as it stands.
 *
 * Integers only, as on-chain. The Rust works in u128 with saturating steps that no u64 input can
 * reach (the numerator stays below 2^113), so [BigInteger] gives the same result for every input;
 * the Rust test vectors are this port's tests. Pure: nothing here reads the chain.
 */
object OreGate {
    /** Motherlode odds denominator (`MOTHERLODE_ODDS`: `state/round.rs:107-109`, `% 500`). */
    private val MOTHERLODE_ODDS = BigInteger.valueOf(500)
    private val ONE_ORE = BigInteger.valueOf(Ore.ONE_ORE)
    private val FIVE = BigInteger.valueOf(5)
    private val SIX = BigInteger.valueOf(6)

    /**
     * Motherlode-aware production cost, lamports per ORE:
     *
     * `ema_ev = ema · 6 · 500 · 10^11 / (5 · (500 · 10^11 + pot))`, floored
     *
     * i.e. `ema · 1.2 / (1 + pot/500)`. [ema] is ORE's `Board.production_cost_ema`, [pot] its
     * `Treasury.motherlode` in ORE atoms. The result can exceed a u64 (it is `ema · 1.2` when the
     * pot is empty), which is why it is not one.
     */
    fun emaEv(ema: ULong, pot: ULong): BigInteger {
        val num = big(ema) * SIX * MOTHERLODE_ODDS * ONE_ORE
        // den >= 5 * 500 * 10^11 > 0.
        val den = (MOTHERLODE_ODDS * ONE_ORE + big(pot)) * FIVE
        return num / den
    }

    /** The same figure from the two accounts, as their checked decoders returned them. */
    fun emaEv(board: OreBoard, treasury: OreTreasury): BigInteger = emaEv(board.productionCostEma, treasury.motherlode)

    /** The gate opens iff `ema_ev <= min(plan_max_ev_cost, cap_max_cost)`. */
    fun gateOpen(emaEv: BigInteger, planMaxEvCost: ULong, capMaxCost: ULong): Boolean =
        emaEv <= big(minOf(planMaxEvCost, capMaxCost))

    private fun big(value: ULong) = BigInteger(value.toString())
}
