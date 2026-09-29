package xyz.headsdown.core.chain.accounts

import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.rpc.AccountInfo

/** ORE `Board` (40 bytes, discriminator 105; `docs/ORE.md` §3). */
data class OreBoard(
    val roundId: ULong,
    val startSlot: ULong,
    /** `u64::MAX` until the round's first deploy. */
    val endSlot: ULong,
    /** Lamports per whole ORE, 20-round EMA (`reset.rs:239-251`). */
    val productionCostEma: ULong,
)

/** ORE `Treasury` (48 bytes, discriminator 104). */
data class OreTreasury(
    /** ORE base units (11 decimals). */
    val motherlode: ULong,
)

/** ORE `Automation` (160 bytes, discriminator 100; `state/automation.rs:9-43`). */
data class OreAutomation(
    /** Per-square cap the executor may deploy. */
    val amount: ULong,
    val authority: Pubkey,
    val balance: ULong,
    val executor: Pubkey,
    val fee: ULong,
    /** 2 = Discretionary. */
    val strategy: ULong,
    val mask: ULong,
    val reload: ULong,
    val maxProductionCost: ULong,
    val minMotherlode: Int,
    val maxMotherlode: Int,
    val splitTiles: Int,
    val soloTiles: Int,
) {
    val isDiscretionary: Boolean get() = strategy == STRATEGY_DISCRETIONARY

    companion object {
        const val STRATEGY_DISCRETIONARY: ULong = 2uL
    }
}

/** ORE `Miner` (752 bytes, discriminator 103; `state/miner.rs:8-66`). */
data class OreMiner(
    val authority: Pubkey,
    val checkpointId: ULong,
    val checkpointFee: ULong,
    val deployed: List<ULong>,
    val roundId: ULong,
    val rewardsSol: ULong,
    val refinedOre: ULong,
    val rewardsOre: ULong,
    val lifetimeRewardsOre: ULong,
    val lifetimeDeployed: ULong,
    val lifetimeRewardsSol: ULong,
)

/**
 * Decoders for the ORE accounts Heads Down reads. Each one checks, before reading a field:
 * the owner is the pinned ORE program, the size is exact, the 8-byte Steel header is
 * `[discriminator, 0 × 7]`, and the address is the pinned constant or the PDA re-derived from
 * the account's own authority. A mismatch throws [AccountLayoutException]: on the phone that
 * means "show nothing / build nothing", never "guess".
 */
object OreAccounts {
    const val BOARD_SIZE = 40
    const val TREASURY_SIZE = 48
    const val AUTOMATION_SIZE = 160
    const val MINER_SIZE = 752

    const val BOARD_DISC = 105
    const val TREASURY_DISC = 104
    const val AUTOMATION_DISC = 100
    const val MINER_DISC = 103

    fun board(address: Pubkey, account: AccountInfo): OreBoard {
        if (address != Ore.BOARD) throw AccountLayoutException("not the ORE Board address")
        val b = checked(account, BOARD_SIZE, BOARD_DISC, "Board")
        return OreBoard(roundId = b.u64(8), startSlot = b.u64(16), endSlot = b.u64(24), productionCostEma = b.u64(32))
    }

    fun treasury(address: Pubkey, account: AccountInfo): OreTreasury {
        if (address != Ore.TREASURY) throw AccountLayoutException("not the ORE Treasury address")
        return OreTreasury(motherlode = checked(account, TREASURY_SIZE, TREASURY_DISC, "Treasury").u64(8))
    }

    fun automation(address: Pubkey, account: AccountInfo): OreAutomation {
        val b = checked(account, AUTOMATION_SIZE, AUTOMATION_DISC, "Automation")
        val authority = b.pubkey(16)
        if (Ore.automation(authority).address != address) throw AccountLayoutException("Automation is not the PDA of its authority")
        return OreAutomation(
            amount = b.u64(8),
            authority = authority,
            balance = b.u64(48),
            executor = b.pubkey(56),
            fee = b.u64(88),
            strategy = b.u64(96),
            mask = b.u64(104),
            reload = b.u64(112),
            maxProductionCost = b.u64(136),
            minMotherlode = b.u16(144),
            maxMotherlode = b.u16(146),
            splitTiles = b.u16(148),
            soloTiles = b.u16(150),
        )
    }

    fun miner(address: Pubkey, account: AccountInfo): OreMiner {
        val b = checked(account, MINER_SIZE, MINER_DISC, "Miner")
        val authority = b.pubkey(8)
        if (Ore.miner(authority).address != address) throw AccountLayoutException("Miner is not the PDA of its authority")
        return OreMiner(
            authority = authority,
            checkpointId = b.u64(48),
            checkpointFee = b.u64(56),
            deployed = List(25) { b.u64(64 + 8 * it) },
            roundId = b.u64(664),
            rewardsSol = b.u64(688),
            refinedOre = b.u64(696),
            rewardsOre = b.u64(704),
            lifetimeRewardsOre = b.u64(728),
            lifetimeDeployed = b.u64(736),
            lifetimeRewardsSol = b.u64(744),
        )
    }

    private fun checked(account: AccountInfo, size: Int, disc: Int, name: String): AccountBytes {
        if (account.owner != Ore.PROGRAM_ID) throw AccountLayoutException("$name is not owned by ORE")
        val b = AccountBytes(account.data)
        if (b.size != size) throw AccountLayoutException("$name must be $size bytes, was ${b.size}")
        if (b.u8(0) != disc) throw AccountLayoutException("$name discriminator ${b.u8(0)} != $disc")
        for (i in 1 until 8) if (b.u8(i) != 0) throw AccountLayoutException("$name header byte $i is not zero")
        return b
    }
}
