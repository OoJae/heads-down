package xyz.headsdown.core.chain.ix

import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.tx.AccountMeta
import xyz.headsdown.core.chain.tx.DataWriter
import xyz.headsdown.core.chain.tx.Instruction

/** `AutomationConditions` (24 bytes). ORE stores `maxProductionCost` but never enforces it. */
data class AutomationConditions(
    val maxProductionCost: ULong = ULong.MAX_VALUE,
    val minMotherlode: Int = 0,
    val maxMotherlode: Int = 0xFFFF,
    /** ORE accepts non-zero split/solo only with the Random strategy (`automate.rs:32-38`). */
    val splitTiles: Int = 0,
    val soloTiles: Int = 0,
)

/**
 * ORE instructions the wallet signs. Layouts are ORE's `api/src/instruction.rs` at the pinned
 * commit; accounts follow `sdk.rs::automate` (`signer, automation, executor, miner, system`).
 */
object OreInstructions {
    const val TAG_AUTOMATE = 0
    const val AUTOMATE_V2_BYTES = 66
    const val STRATEGY_DISCRETIONARY = 2

    /**
     * `AutomateV2` (66 bytes): `tag 0 | amount u64 | deposit u64 | fee u64 | mask u64 |
     * strategy u8 | reload u64 | max_production_cost u64 | min_motherlode u16 |
     * max_motherlode u16 | split_tiles u16 | solo_tiles u16 | _buffer u64`.
     *
     * Creates or updates the signer's Automation (and Miner), moves [deposit] lamports into it
     * and, if the Miner has none, pays ORE's 10,000-lamport checkpoint fee.
     */
    fun automate(
        authority: Pubkey,
        amountPerTile: ULong,
        deposit: ULong,
        executor: Pubkey,
        fee: ULong,
        strategy: Int,
        reload: Boolean,
        mask: ULong = 0uL,
        conditions: AutomationConditions = AutomationConditions(),
    ): Instruction {
        // Random 0, Preferred 1, Discretionary 2, DiscretionaryBps 3; ORE unwrap()-panics on others.
        require(strategy in 0..3) { "unknown ORE automation strategy $strategy" }
        if (strategy != 0 /* Random */) {
            require(conditions.splitTiles == 0 && conditions.soloTiles == 0) { "ORE rejects tile conditions unless strategy is Random" }
        }
        val data = DataWriter(AUTOMATE_V2_BYTES)
            .u8(TAG_AUTOMATE)
            .u64(amountPerTile)
            .u64(deposit)
            .u64(fee)
            .u64(mask)
            .u8(strategy)
            .u64(if (reload) 1uL else 0uL)
            .u64(conditions.maxProductionCost)
            .u16(conditions.minMotherlode)
            .u16(conditions.maxMotherlode)
            .u16(conditions.splitTiles)
            .u16(conditions.soloTiles)
            .u64(0uL)
            .build()
        return Instruction(
            Ore.PROGRAM_ID,
            listOf(
                AccountMeta.signer(authority),
                AccountMeta.writable(Ore.automation(authority).address),
                AccountMeta.writable(executor),
                AccountMeta.writable(Ore.miner(authority).address),
                AccountMeta.readonly(WellKnown.SYSTEM_PROGRAM),
            ),
            data,
        )
    }

    /**
     * The Heads Down refuel: the executor is **always** the heads_down Executor PDA and the
     * strategy **always** Discretionary (2), with the fixed `fee = Config.executor_fee` that
     * `dig` requires. [amountPerTile] is ORE's own per-square ceiling on any executor, so it is
     * set to the plan's per-tile amount, not a large value (`docs/ORE.md` §4).
     */
    fun automateHeadsDown(authority: Pubkey, amountPerTile: ULong, deposit: ULong, executorFee: ULong): Instruction {
        require(amountPerTile > 0uL) { "a zero per-tile cap would make every dig a no-op" }
        return automate(
            authority = authority,
            amountPerTile = amountPerTile,
            deposit = deposit,
            executor = HeadsDownProgram.executor.address,
            fee = executorFee,
            strategy = STRATEGY_DISCRETIONARY,
            reload = true,
        )
    }

    /**
     * Revoke: `automate` with `executor = Pubkey::default()` closes the Automation and returns
     * every lamport to the wallet (`automate.rs:88-97`). Heads Down can no longer deploy.
     */
    fun revoke(authority: Pubkey): Instruction = automate(
        authority = authority,
        amountPerTile = 0uL,
        deposit = 0uL,
        executor = Pubkey.DEFAULT,
        fee = 0uL,
        strategy = STRATEGY_DISCRETIONARY,
        reload = false,
    )
}

/** Compute Budget program (priority fee and CU limit). */
object ComputeBudgetInstructions {
    fun setComputeUnitLimit(units: Long): Instruction {
        require(units in 1..MAX_UNITS) { "CU limit must be 1..$MAX_UNITS" }
        return Instruction(WellKnown.COMPUTE_BUDGET, emptyList(), DataWriter(5).u8(2).u32(units).build())
    }

    fun setComputeUnitPrice(microLamports: ULong): Instruction =
        Instruction(WellKnown.COMPUTE_BUDGET, emptyList(), DataWriter(9).u8(3).u64(microLamports).build())

    const val MAX_UNITS = 1_400_000L
}
