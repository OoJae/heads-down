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

    const val TAG_CLAIM_SOL = 3
    const val TAG_CLAIM_ORE = 4
    const val CLAIM_ORE_BYTES = 9

    /**
     * `claim_sol` (tag 3), 1 byte: pays `Miner.rewards_sol` to the wallet (`claim_sol.rs:10-27`).
     * Only the Miner's authority can sign it. With `reload = 1` (every Heads Down Automation)
     * returned SOL goes back into the Automation at checkpoint instead, so this is usually zero.
     *
     * Accounts (`sdk.rs::claim_sol`): `signer (s,w) | Board (w) | Miner (w) | system | ORE program`.
     */
    fun claimSol(authority: Pubkey): Instruction = Instruction(
        Ore.PROGRAM_ID,
        listOf(
            AccountMeta.signer(authority),
            AccountMeta.writable(Ore.BOARD),
            AccountMeta.writable(Ore.miner(authority).address),
            AccountMeta.readonly(WellKnown.SYSTEM_PROGRAM),
            AccountMeta.readonly(Ore.PROGRAM_ID),
        ),
        byteArrayOf(TAG_CLAIM_SOL.toByte()),
    )

    /**
     * `claim_ore` (tag 4), 9 bytes: `tag | bps u64`. Claims `bps / 10,000` of both the Miner's
     * refined and unrefined ORE to the wallet's ORE account (`claim_ore.rs:8-88`), creating that
     * account if needed. ORE takes a **10% refining fee on the unrefined part** (at least one
     * atom) and shares it among the miners still holding unrefined ORE
     * (`state/miner.rs:76-103`). Not sending this instruction at all keeps everything unrefined.
     *
     * Accounts (`sdk.rs::claim_ore`): `signer (s,w) | Board (w) | Miner (w) | ORE mint (w) |
     * recipient = ATA(signer, ORE) (w) | Treasury (w) | Treasury ORE account (w) | system |
     * SPL Token | ATA program | ORE program`.
     */
    fun claimOre(authority: Pubkey, bps: Int): Instruction {
        // ORE clamps bps to 10,000; 0 would claim nothing and still create the token account.
        require(bps in 1..Ore.DENOMINATOR_BPS) { "claim share must be 1..10000 bps" }
        return Instruction(
            Ore.PROGRAM_ID,
            listOf(
                AccountMeta.signer(authority),
                AccountMeta.writable(Ore.BOARD),
                AccountMeta.writable(Ore.miner(authority).address),
                AccountMeta.writable(Ore.MINT),
                AccountMeta.writable(Ore.account(authority)),
                AccountMeta.writable(Ore.TREASURY),
                AccountMeta.writable(Ore.treasuryTokens),
                AccountMeta.readonly(WellKnown.SYSTEM_PROGRAM),
                AccountMeta.readonly(WellKnown.SPL_TOKEN),
                AccountMeta.readonly(WellKnown.ASSOCIATED_TOKEN),
                AccountMeta.readonly(Ore.PROGRAM_ID),
            ),
            DataWriter(CLAIM_ORE_BYTES).u8(TAG_CLAIM_ORE).u64(bps.toULong()).build(),
        )
    }
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
