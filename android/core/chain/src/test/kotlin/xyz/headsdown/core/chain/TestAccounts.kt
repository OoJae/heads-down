package xyz.headsdown.core.chain

import xyz.headsdown.core.chain.rpc.AccountInfo
import xyz.headsdown.core.keys.RigSignalState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** heads_down / ORE accounts laid out per INTERFACE / docs/ORE.md, for composer and service tests. */
object TestAccounts {
    fun configBytes(executorFee: Long = 10_000, paused: Boolean = false, registrar: ByteArray = ByteArray(32) { 2 }): ByteArray =
        ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0, 1); put(1, 1); put(2, HeadsDownProgram.config.bump.toByte())
            position(8); put(ByteArray(32) { 1 })
            position(40); put(registrar)
            putLong(72, 7_000); putLong(80, executorFee); putShort(88, 2_000); put(90, if (paused) 1 else 0)
            put(91, HeadsDownProgram.executor.bump.toByte())
        }.array()

    private val OPEN_STATES = setOf(RigSignalState.ARMED, RigSignalState.DOWN, RigSignalState.COOLING, RigSignalState.BROKEN)

    fun rigBytes(
        authority: Pubkey,
        p256: ByteArray,
        state: RigSignalState = RigSignalState.IDLE,
        shiftId: Long = 0,
        hbCounter: Long = 0,
        /** v1.1 @336: open from arm_shift to end_shift, as the program keeps it. */
        shiftOpen: Boolean = state in OPEN_STATES,
        attestationLevel: Int = 0,
        attestationExpirySlot: Long = 0,
        streak: Int = 0,
    ): ByteArray = ByteBuffer.allocate(384).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(0, 2); put(1, 1); put(2, HeadsDownProgram.rig(authority).bump.toByte())
        position(8); put(authority.bytes)
        position(40); put(p256)
        put(73, attestationLevel.toByte())
        put(75, state.wire.toByte())
        putLong(112, attestationExpirySlot)
        putLong(200, shiftId); putLong(208, hbCounter)
        putInt(320, streak)
        put(336, if (shiftOpen) 1 else 0)
    }.array()

    fun automationBytes(
        authority: Pubkey,
        amount: Long,
        balance: Long,
        executor: Pubkey = HeadsDownProgram.executor.address,
        fee: Long = 10_000,
        strategy: Long = 2,
        reload: Long = 1,
    ): ByteArray = ByteBuffer.allocate(160).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(0, 100)
        putLong(8, amount)
        position(16); put(authority.bytes)
        putLong(48, balance)
        position(56); put(executor.bytes)
        putLong(88, fee); putLong(96, strategy); putLong(112, reload)
        putLong(136, -1); putShort(146, -1)
    }.array()

    fun info(owner: Pubkey, data: ByteArray, lamports: ULong = 1_000_000uL) = AccountInfo(lamports, owner, data, executable = false)

    /** An account as the RPC `value` JSON. */
    fun json(owner: Pubkey, data: ByteArray, lamports: Long = 1_000_000): String =
        """{"data":["${Base64.getEncoder().encodeToString(data)}","base64"],"executable":false,"lamports":$lamports,"owner":"$owner","rentEpoch":18446744073709551615,"space":${data.size}}"""

    // ---- the Rig fields the v1.2 flows read (INTERFACE §3.2 / §3.3) -----------------------

    /** A Rig with the plan, lease and shift fields a clock-out, a bond or a Stack seat depends on. */
    fun rigBytesFull(
        authority: Pubkey,
        p256: ByteArray,
        state: RigSignalState,
        shiftId: Long,
        shiftOpen: Boolean,
        breakReason: Int = 0,
        planWindowEndTs: Long = 0,
        planLeaseRounds: Int = 1,
        leaseToRound: Long = 0,
        shiftStartRound: Long = 0,
        shiftDarkRounds: Long = 0,
        shiftStartTs: Long = 0,
        tier: Int = 0,
        sgtMint: Pubkey? = null,
        attestationLevel: Int = 0,
        attestationExpirySlot: Long = 0,
    ): ByteArray = ByteBuffer.wrap(rigBytes(authority, p256, state, shiftId, 0, shiftOpen, attestationLevel, attestationExpirySlot))
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            put(74, tier.toByte())
            if (sgtMint != null) {
                position(80); put(sgtMint.bytes)
            }
            put(178, planLeaseRounds.toByte())
            putLong(192, planWindowEndTs)
            putLong(216, if (leaseToRound == 0L) 0 else leaseToRound)
            putLong(224, leaseToRound)
            putLong(272, shiftStartRound)
            putLong(280, shiftDarkRounds)
            put(337, breakReason.toByte())
            putLong(344, shiftStartTs)
        }.array()

    // ---- heads_down v1.2 accounts (INTERFACE §11.3) --------------------------------------

    private fun header(size: Int, tag: Int, bump: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0, tag.toByte()); put(1, 1); put(2, bump.toByte())
        }

    fun shiftLogBytes(
        rig: Pubkey,
        shiftId: Long,
        breakReason: Int = 0,
        startRound: Long = 100,
        endRound: Long = 400,
        darkRounds: Long = 250,
        startTs: Long = 1_790_000_000,
        endTs: Long = 1_790_028_900,
        mode: Int = 0,
    ): ByteArray = header(128, 4, HeadsDownProgram.shiftLog(rig, shiftId.toULong()).bump).apply {
        position(8); put(rig.bytes)
        putLong(40, shiftId); putLong(48, startRound); putLong(56, endRound); putLong(64, darkRounds)
        putLong(72, 12); putLong(80, 12_120_000)
        put(88, breakReason.toByte()); put(89, mode.toByte())
        putLong(96, startTs); putLong(104, endTs)
    }.array()

    /** A SeekerSeat (INTERFACE §3.4): the rig a Seeker Genesis Token is verified for. */
    fun seekerSeatBytes(sgtMint: Pubkey, rig: Pubkey, authority: Pubkey, memberNumber: Long = 33_078, verifiedSlot: Long = 451_000_000): ByteArray =
        header(128, 3, HeadsDownProgram.seekerSeat(sgtMint).bump).apply {
            position(8); put(sgtMint.bytes)
            position(40); put(rig.bytes)
            position(72); put(authority.bytes)
            putLong(104, memberNumber); putLong(112, verifiedSlot)
        }.array()

    fun stackTableBytes(
        host: Pubkey,
        tableId: Long,
        bond: Long = 200_000_000,
        startRound: Long = 1_000,
        endRound: Long = 1_099,
        graceGaps: Int = 3,
        flags: Int = 0,
        maxSeats: Int = 4,
        status: Int = 0,
        seatCount: Int = 0,
        finishers: Int = 0,
        totalBonds: Long = 0,
        payoutsTotal: Long = 0,
        buryAmount: Long = 0,
        refundAfterTs: Long = 1_790_300_000,
        openedTs: Long = 1_790_000_000,
        openedRound: Long = 990,
        claimedCount: Int = 0,
    ): ByteArray {
        val table = HeadsDownProgram.stackTable(host, tableId.toULong())
        return header(208, 5, table.bump).apply {
            position(8); put(host.bytes)
            position(40); put(HeadsDownProgram.skrVault(table.address).bytes)
            putLong(72, tableId); putLong(80, bond); putLong(88, startRound); putLong(96, endRound)
            putInt(104, graceGaps)
            put(108, flags.toByte()); put(109, maxSeats.toByte()); put(110, status.toByte()); put(111, seatCount.toByte())
            put(112, finishers.toByte()); put(113, claimedCount.toByte())
            putLong(120, totalBonds); putLong(136, payoutsTotal); putLong(144, buryAmount)
            putLong(160, refundAfterTs); putLong(168, openedTs); putLong(176, openedRound)
        }.array()
    }

    fun stackSeatBytes(
        table: Pubkey,
        rig: Pubkey,
        authority: Pubkey,
        bond: Long = 200_000_000,
        sgtMint: Pubkey? = null,
        /** The seat key: the rig (in-person) unless [keyedBySgt]. */
        keyedBySgt: Boolean = false,
        shiftId: Long = 0,
        checkedRounds: Long = 0,
        lastRound: Long = 0,
        payout: Long = 0,
        seatIndex: Int = 0,
        broken: Boolean = false,
        outcome: Int = 0,
    ): ByteArray {
        val key = if (keyedBySgt) sgtMint!! else rig
        return header(200, 6, HeadsDownProgram.stackSeat(table, key).bump).apply {
            position(8); put(table.bytes)
            position(40); put(rig.bytes)
            position(72); put(authority.bytes)
            if (sgtMint != null) {
                position(104); put(sgtMint.bytes)
            }
            putLong(136, bond); putLong(144, shiftId); putLong(152, checkedRounds); putLong(160, lastRound); putLong(168, payout)
            put(176, seatIndex.toByte()); put(177, if (broken) 1 else 0); put(178, outcome.toByte()); put(179, if (sgtMint != null) 1 else 0)
        }.array()
    }

    fun focusBondBytes(
        rig: Pubkey,
        authority: Pubkey,
        shiftId: Long,
        amount: Long = 100_000_000,
        shiftStartRound: Long = 100,
        shiftStartTs: Long = 1_790_000_000,
    ): ByteArray {
        val bond = HeadsDownProgram.focusBond(rig, shiftId.toULong())
        return header(160, 7, bond.bump).apply {
            position(8); put(rig.bytes)
            position(40); put(authority.bytes)
            position(72); put(HeadsDownProgram.skrVault(bond.address).bytes)
            putLong(104, shiftId); putLong(112, amount); putLong(120, shiftStartRound); putLong(128, shiftStartTs); putLong(136, shiftStartTs + 5)
        }.array()
    }

    fun giftEscrowBytes(
        sender: Pubkey,
        nonce: Long,
        recipient: Pubkey,
        kind: Int = 0,
        lamports: Long = 500_000_000,
        createdTs: Long = 1_790_000_000,
    ): ByteArray = header(128, 8, HeadsDownProgram.giftEscrow(sender, nonce.toULong()).bump).apply {
        position(8); put(sender.bytes)
        position(40); put(recipient.bytes)
        putLong(72, nonce); putLong(80, lamports); putLong(88, createdTs); putLong(96, createdTs + 30L * 86_400)
        put(104, kind.toByte())
    }.array()

    // ---- SPL Token and ORE ---------------------------------------------------------------

    /** A classic SPL Token account (165 bytes), Initialized. */
    fun tokenAccountBytes(mint: Pubkey, owner: Pubkey, amount: Long, state: Int = 1): ByteArray =
        ByteBuffer.allocate(165).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(mint.bytes); put(owner.bytes)
            putLong(64, amount)
            put(108, state.toByte())
        }.array()

    fun boardBytes(roundId: Long, startSlot: Long = 451_700_000, endSlot: Long = 451_700_240, ema: Long = 918_782_720): ByteArray =
        ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0, 105)
            putLong(8, roundId); putLong(16, startSlot); putLong(24, endSlot); putLong(32, ema)
        }.array()

    /** [factorBits]: the raw I80F48 bits of `miner_rewards_factor` (48 fractional bits). */
    fun treasuryBytes(motherlode: Long = 0, factorBits: Long = 0, totalRefined: Long = 0, totalUnrefined: Long = 0): ByteArray =
        ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0, 104)
            putLong(8, motherlode)
            putLong(16, factorBits)
            putLong(32, totalRefined); putLong(40, totalUnrefined)
        }.array()

    fun minerBytes(
        authority: Pubkey,
        rewardsSol: Long = 0,
        refinedOre: Long = 0,
        rewardsOre: Long = 0,
        factorBits: Long = 0,
        checkpointFee: Long = 10_000,
    ): ByteArray = ByteBuffer.allocate(752).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(0, 103)
        position(8); put(authority.bytes)
        putLong(56, checkpointFee)
        putLong(672, factorBits)
        putLong(688, rewardsSol); putLong(696, refinedOre); putLong(704, rewardsOre)
    }.array()
}
