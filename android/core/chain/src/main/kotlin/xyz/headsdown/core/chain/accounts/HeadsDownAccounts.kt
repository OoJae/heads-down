package xyz.headsdown.core.chain.accounts

import okio.ByteString
import okio.ByteString.Companion.toByteString
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.ProgramAddress
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.rpc.AccountInfo
import xyz.headsdown.core.keys.RigSignalState

/** heads_down `Config` (256 bytes, tag 1; INTERFACE "Config"). */
data class HdConfig(
    val bump: Int,
    val governance: Pubkey,
    val registrar: Pubkey,
    val crankFee: ULong,
    /** The Discretionary `fee` every rig's ORE Automation must use (checked in `dig`). */
    val executorFee: ULong,
    val buryBps: Int,
    val paused: Boolean,
    val executorBump: Int,
    val oreLayoutHash: ByteString,
    val pendingExists: Boolean,
    val pendingEtaSlot: ULong,
)

/** `Rig.tier`. */
enum class RigTier(val wire: Int) { GUEST(0), SEEKER(1) }

/** heads_down `Rig` (384 bytes, tag 2; INTERFACE "Rig"). Field names follow the contract. */
data class RigAccount(
    val address: Pubkey,
    val bump: Int,
    val authority: Pubkey,
    /** 33-byte SEC1 compressed Keystore key. */
    val p256Pubkey: ByteString,
    val attestationLevel: Int,
    val tier: RigTier,
    val state: RigSignalState,
    val sgtMint: Pubkey?,
    val attestationExpirySlot: ULong,
    val capWeek: ULong,
    val capShift: ULong,
    val capRound: ULong,
    val capMaxCost: ULong,
    val capsExpiryTs: Long,
    val planMaxEvCost: ULong,
    val planDigLamports: ULong,
    val planSplitTiles: Int,
    val planSoloTiles: Int,
    val planLeaseRounds: Int,
    val planFlags: Int,
    val planWindowStartTs: Long,
    val planWindowEndTs: Long,
    val shiftId: ULong,
    val hbCounter: ULong,
    val leaseFromRound: ULong,
    val leaseToRound: ULong,
    val gapCount: Long,
    val spentShift: ULong,
    val spentWeek: ULong,
    val weekStartTs: Long,
    val lastDugRound: ULong,
    val shiftStartRound: ULong,
    val shiftDarkRounds: ULong,
    val shiftRoundsDug: ULong,
    val lifetimeDarkRounds: ULong,
    val lifetimeRoundsDug: ULong,
    val lifetimeLamportsDeployed: ULong,
    val streak: Long,
    val freezesLeft: Int,
    val lastShiftDay: Long,
    /** v1.1 @336: a shift is open from `arm_shift` to `end_shift` (a Frozen rig can be in one). */
    val shiftOpen: Boolean = false,
    /** v1.1 @337: the BREAK / FREEZE reason `end_shift` will write into the ShiftLog. */
    val breakReason: Int = 0,
    /** v1.1 @344: unix time of the last `arm_shift`. */
    val shiftStartTs: Long = 0,
)

/**
 * heads_down `SeekerSeat` (128 bytes, tag 3; INTERFACE §3.4): the one rig a Seeker Genesis Token
 * is verified for. `verify_seeker` needs the seat's current rig when it points at another one.
 */
data class SeekerSeatAccount(
    val address: Pubkey,
    val sgtMint: Pubkey,
    val rig: Pubkey,
    val authority: Pubkey,
    val memberNumber: ULong,
    val verifiedSlot: ULong,
)

/**
 * heads_down `ShiftLog` (128 bytes, tag 4; INTERFACE §3.5): a sealed shift. A Focus Bond resolves
 * from it: `break_reason` 0 (`completed`) releases the bond, anything else forfeits it.
 */
data class ShiftLogAccount(
    val address: Pubkey,
    val rig: Pubkey,
    val shiftId: ULong,
    val startRound: ULong,
    val endRound: ULong,
    val darkRounds: ULong,
    val roundsDug: ULong,
    /** `spent_shift`: SOL on squares plus executor fees. */
    val lamportsDeployed: ULong,
    /** 0 completed … 8 unlocked (`ShiftEndReason` wire values). */
    val breakReason: Int,
    /** 0 night, 1 day, 2 focus-only. */
    val mode: Int,
    val startTs: Long,
    val endTs: Long,
) {
    val completed: Boolean get() = breakReason == 0
}

/** `StackTable.status`. */
enum class StackStatus(val wire: Int) {
    /** Joins before `start_round`, check-ins inside the window. */
    OPEN(0),

    /** `settle_stack` ran: every seat has an outcome and a payout. */
    SETTLED(1),

    /** Nobody settled before `refund_after_ts`: every seat takes its own bond back. */
    REFUNDING(2),
}

/** `StackSeat.outcome`. */
enum class SeatOutcome(val wire: Int) { PENDING(0), FINISHED(1), FORFEITED(2) }

/** heads_down `StackTable` (208 bytes, tag 5; INTERFACE §11.3). Amounts are SKR base units. */
data class StackTableAccount(
    val address: Pubkey,
    val host: Pubkey,
    /** `ATA(table, SKR)`: checked against the derivation. */
    val vault: Pubkey,
    val tableId: ULong,
    /** What every seat bonds. */
    val bond: ULong,
    val startRound: ULong,
    /** Last round of the window, inclusive. */
    val endRound: ULong,
    val graceGaps: Long,
    val flags: Int,
    val maxSeats: Int,
    val status: StackStatus,
    val seatCount: Int,
    val finishers: Int,
    val claimedCount: Int,
    val totalBonds: ULong,
    val finisherBonds: ULong,
    val payoutsTotal: ULong,
    val buryAmount: ULong,
    val claimedTotal: ULong,
    /** Unix time after which an unsettled table refunds every bond. */
    val refundAfterTs: Long,
    val openedTs: Long,
    val openedRound: ULong,
) {
    val remote: Boolean get() = flags and FLAG_REMOTE != 0
    val buryOnly: Boolean get() = flags and FLAG_BURY_ONLY != 0
    val attestedOnly: Boolean get() = flags and FLAG_ATTESTED_ONLY != 0

    /** Rounds in the window. */
    val rounds: ULong get() = endRound - startRound + 1uL
    val full: Boolean get() = seatCount >= maxSeats

    companion object {
        const val FLAG_REMOTE = 0x01
        const val FLAG_BURY_ONLY = 0x02
        const val FLAG_ATTESTED_ONLY = 0x04
    }
}

/** heads_down `StackSeat` (200 bytes, tag 6; INTERFACE §11.3). */
data class StackSeatAccount(
    val address: Pubkey,
    val table: Pubkey,
    val rig: Pubkey,
    /** `rig.authority` at join: payouts and the seat rent go only here. */
    val authority: Pubkey,
    /** The SGT re-verified at join, or null when the join was not SGT-verified. */
    val sgtMint: Pubkey?,
    val bond: ULong,
    /** The rig's shift this seat is bound to; 0 until its first counted check-in. */
    val shiftId: ULong,
    /** Window rounds counted. */
    val checkedRounds: ULong,
    /** Last round counted (0 = none). */
    val lastRound: ULong,
    /** What the seat receives at claim, written at settle. */
    val payout: ULong,
    val seatIndex: Int,
    /** A check-in saw a BREAK or FREEZE in the bound shift: final. */
    val broken: Boolean,
    val outcome: SeatOutcome,
    val sgtVerified: Boolean,
)

/** heads_down `FocusBond` (160 bytes, tag 7; INTERFACE §11.3). */
data class FocusBondAccount(
    val address: Pubkey,
    val rig: Pubkey,
    /** Release and both rents go only here. */
    val authority: Pubkey,
    val vault: Pubkey,
    val shiftId: ULong,
    /** SKR base units locked. */
    val amount: ULong,
    val shiftStartRound: ULong,
    val shiftStartTs: Long,
    val lockedTs: Long,
) {
    /** [log] is the bonded shift's own ShiftLog (same rig, id, start round and start time). */
    fun isResolvedBy(log: ShiftLogAccount): Boolean =
        log.rig == rig && log.shiftId == shiftId && log.startRound == shiftStartRound && log.startTs == shiftStartTs
}

/** heads_down `GiftEscrow` (128 bytes, tag 8; INTERFACE §11.3). */
data class GiftEscrowAccount(
    val address: Pubkey,
    /** The refund and the rent go only here. */
    val sender: Pubkey,
    /** A wallet, or an SGT mint (see [recipientKind]). */
    val recipient: Pubkey,
    val nonce: ULong,
    /** The gift; it sits in the escrow on top of its rent. */
    val lamports: ULong,
    val createdTs: Long,
    /** Claims before this unix time, refunds from it. */
    val expiryTs: Long,
    /** 0 wallet, 1 SGT mint. */
    val recipientKind: Int,
) {
    val forSgtMint: Boolean get() = recipientKind == KIND_SGT_MINT

    companion object {
        const val KIND_WALLET = 0
        const val KIND_SGT_MINT = 1
    }
}

/**
 * Decoders for heads_down accounts. Per the INTERFACE header rule every read is preceded by an
 * owner, exact-size, tag and version check, and the address must be the canonical PDA (for a
 * Rig: re-derived from the authority stored in it, with the stored bump equal to the canonical
 * one). The v1.2 accounts follow the same rule: each address is re-derived from the seed fields
 * stored in the account, and a stored vault must be the ATA the program derives.
 */
object HeadsDownAccounts {
    const val VERSION = 1
    const val CONFIG_TAG = 1
    const val RIG_TAG = 2
    const val SEEKER_SEAT_TAG = 3
    const val SHIFT_LOG_TAG = 4
    const val STACK_TABLE_TAG = 5
    const val STACK_SEAT_TAG = 6
    const val FOCUS_BOND_TAG = 7
    const val GIFT_ESCROW_TAG = 8
    const val CONFIG_SIZE = 256
    const val RIG_SIZE = 384
    const val SEEKER_SEAT_SIZE = 128
    const val SHIFT_LOG_SIZE = 128
    const val STACK_TABLE_SIZE = 208
    const val STACK_SEAT_SIZE = 200
    const val FOCUS_BOND_SIZE = 160
    const val GIFT_ESCROW_SIZE = 128

    /** Offsets used as `getProgramAccounts` memcmp filters. */
    const val STACK_SEAT_TABLE_OFFSET = 8
    const val STACK_SEAT_AUTHORITY_OFFSET = 72
    const val STACK_TABLE_HOST_OFFSET = 8
    const val GIFT_ESCROW_SENDER_OFFSET = 8
    const val GIFT_ESCROW_RECIPIENT_OFFSET = 40

    fun config(address: Pubkey, account: AccountInfo): HdConfig {
        val canonical = HeadsDownProgram.config
        if (address != canonical.address) throw AccountLayoutException("not the heads_down Config PDA")
        val b = header(account, CONFIG_SIZE, CONFIG_TAG, "Config")
        if (b.u8(2) != canonical.bump) throw AccountLayoutException("Config bump is not canonical")
        val executorBump = b.u8(91)
        if (executorBump != HeadsDownProgram.executor.bump) throw AccountLayoutException("Config executor_bump is not canonical")
        return HdConfig(
            bump = b.u8(2),
            governance = b.pubkey(8),
            registrar = b.pubkey(40),
            crankFee = b.u64(72),
            executorFee = b.u64(80),
            buryBps = b.u16(88),
            paused = flag(b.u8(90), "paused"),
            executorBump = executorBump,
            oreLayoutHash = b.bytes(96, 32).toByteString(),
            pendingExists = flag(b.u8(128), "pending_exists"),
            pendingEtaSlot = b.u64(136),
        )
    }

    fun rig(address: Pubkey, account: AccountInfo): RigAccount {
        val b = header(account, RIG_SIZE, RIG_TAG, "Rig")
        val authority = b.pubkey(8)
        val canonical = HeadsDownProgram.rig(authority)
        if (canonical.address != address) throw AccountLayoutException("Rig is not the PDA of its authority")
        if (b.u8(2) != canonical.bump) throw AccountLayoutException("Rig bump is not canonical")
        val tier = RigTier.entries.firstOrNull { it.wire == b.u8(74) } ?: throw AccountLayoutException("unknown tier")
        val state = try {
            RigSignalState.fromWire(b.u8(75))
        } catch (_: IllegalArgumentException) {
            throw AccountLayoutException("unknown rig state")
        }
        return RigAccount(
            address = address,
            bump = b.u8(2),
            authority = authority,
            p256Pubkey = b.bytes(40, 33).toByteString(),
            attestationLevel = b.u8(73),
            tier = tier,
            state = state,
            sgtMint = b.optionalPubkey(80),
            attestationExpirySlot = b.u64(112),
            capWeek = b.u64(120),
            capShift = b.u64(128),
            capRound = b.u64(136),
            capMaxCost = b.u64(144),
            capsExpiryTs = b.i64(152),
            planMaxEvCost = b.u64(160),
            planDigLamports = b.u64(168),
            planSplitTiles = b.u8(176),
            planSoloTiles = b.u8(177),
            planLeaseRounds = b.u8(178),
            planFlags = b.u8(179),
            planWindowStartTs = b.i64(184),
            planWindowEndTs = b.i64(192),
            shiftId = b.u64(200),
            hbCounter = b.u64(208),
            leaseFromRound = b.u64(216),
            leaseToRound = b.u64(224),
            gapCount = b.u32(232),
            spentShift = b.u64(240),
            spentWeek = b.u64(248),
            weekStartTs = b.i64(256),
            lastDugRound = b.u64(264),
            shiftStartRound = b.u64(272),
            shiftDarkRounds = b.u64(280),
            shiftRoundsDug = b.u64(288),
            lifetimeDarkRounds = b.u64(296),
            lifetimeRoundsDug = b.u64(304),
            lifetimeLamportsDeployed = b.u64(312),
            streak = b.u32(320),
            freezesLeft = b.u8(324),
            lastShiftDay = b.i64(328),
            shiftOpen = flag(b.u8(336), "shift_open"),
            breakReason = b.u8(337),
            shiftStartTs = b.i64(344),
        )
    }

    /** A Seeker seat. The address must be `["seeker", sgt_mint]` for the mint stored in it. */
    fun seekerSeat(address: Pubkey, account: AccountInfo): SeekerSeatAccount {
        val b = header(account, SEEKER_SEAT_SIZE, SEEKER_SEAT_TAG, "SeekerSeat")
        val mint = b.pubkey(8)
        canonical(address, b, HeadsDownProgram.seekerSeat(mint), "SeekerSeat")
        return SeekerSeatAccount(
            address = address,
            sgtMint = mint,
            rig = b.pubkey(40),
            authority = b.pubkey(72),
            memberNumber = b.u64(104),
            verifiedSlot = b.u64(112),
        )
    }

    /** A sealed shift. The address must be `["shift", rig, shift_id]` for the rig and id stored in it. */
    fun shiftLog(address: Pubkey, account: AccountInfo): ShiftLogAccount {
        val b = header(account, SHIFT_LOG_SIZE, SHIFT_LOG_TAG, "ShiftLog")
        val rig = b.pubkey(8)
        val shiftId = b.u64(40)
        canonical(address, b, HeadsDownProgram.shiftLog(rig, shiftId), "ShiftLog")
        return ShiftLogAccount(
            address = address,
            rig = rig,
            shiftId = shiftId,
            startRound = b.u64(48),
            endRound = b.u64(56),
            darkRounds = b.u64(64),
            roundsDug = b.u64(72),
            lamportsDeployed = b.u64(80),
            breakReason = b.u8(88),
            mode = b.u8(89),
            startTs = b.i64(96),
            endTs = b.i64(104),
        )
    }

    /** A Stack table. The address must be `["stack", host, table_id]` and its vault `ATA(table, SKR)`. */
    fun stackTable(address: Pubkey, account: AccountInfo): StackTableAccount {
        val b = header(account, STACK_TABLE_SIZE, STACK_TABLE_TAG, "StackTable")
        val host = b.pubkey(8)
        val tableId = b.u64(72)
        canonical(address, b, HeadsDownProgram.stackTable(host, tableId), "StackTable")
        val vault = b.pubkey(40)
        if (vault != HeadsDownProgram.skrVault(address)) throw AccountLayoutException("StackTable vault is not its SKR ATA")
        val start = b.u64(88)
        val end = b.u64(96)
        if (end < start) throw AccountLayoutException("StackTable window is empty")
        val flags = b.u8(108)
        if (flags and 0x07.inv() != 0) throw AccountLayoutException("StackTable has unknown flags")
        val status = StackStatus.entries.firstOrNull { it.wire == b.u8(110) } ?: throw AccountLayoutException("unknown StackTable status")
        return StackTableAccount(
            address = address,
            host = host,
            vault = vault,
            tableId = tableId,
            bond = b.u64(80),
            startRound = start,
            endRound = end,
            graceGaps = b.u32(104),
            flags = flags,
            maxSeats = b.u8(109),
            status = status,
            seatCount = b.u8(111),
            finishers = b.u8(112),
            claimedCount = b.u8(113),
            totalBonds = b.u64(120),
            finisherBonds = b.u64(128),
            payoutsTotal = b.u64(136),
            buryAmount = b.u64(144),
            claimedTotal = b.u64(152),
            refundAfterTs = b.i64(160),
            openedTs = b.i64(168),
            openedRound = b.u64(176),
        )
    }

    /**
     * A seat. Its key is the rig at an in-person table and the SGT mint at a remote one, so the
     * address must be `["stackseat", table, rig]` or `["stackseat", table, sgt_mint]`.
     */
    fun stackSeat(address: Pubkey, account: AccountInfo): StackSeatAccount {
        val b = header(account, STACK_SEAT_SIZE, STACK_SEAT_TAG, "StackSeat")
        val table = b.pubkey(8)
        val rig = b.pubkey(40)
        val sgtMint = b.optionalPubkey(104)
        val byRig = HeadsDownProgram.stackSeat(table, rig)
        val derived = byRig.takeIf { it.address == address }
            ?: sgtMint?.let { HeadsDownProgram.stackSeat(table, it) }?.takeIf { it.address == address }
            ?: throw AccountLayoutException("StackSeat is not the PDA of its table and rig or SGT")
        if (b.u8(2) != derived.bump) throw AccountLayoutException("StackSeat bump is not canonical")
        val outcome = SeatOutcome.entries.firstOrNull { it.wire == b.u8(178) } ?: throw AccountLayoutException("unknown seat outcome")
        return StackSeatAccount(
            address = address,
            table = table,
            rig = rig,
            authority = b.pubkey(72),
            sgtMint = sgtMint,
            bond = b.u64(136),
            shiftId = b.u64(144),
            checkedRounds = b.u64(152),
            lastRound = b.u64(160),
            payout = b.u64(168),
            seatIndex = b.u8(176),
            broken = flag(b.u8(177), "broken"),
            outcome = outcome,
            sgtVerified = flag(b.u8(179), "sgt_verified"),
        )
    }

    /** A Focus Bond. The address must be `["bond", rig, shift_id]` and its vault `ATA(bond, SKR)`. */
    fun focusBond(address: Pubkey, account: AccountInfo): FocusBondAccount {
        val b = header(account, FOCUS_BOND_SIZE, FOCUS_BOND_TAG, "FocusBond")
        val rig = b.pubkey(8)
        val shiftId = b.u64(104)
        canonical(address, b, HeadsDownProgram.focusBond(rig, shiftId), "FocusBond")
        val vault = b.pubkey(72)
        if (vault != HeadsDownProgram.skrVault(address)) throw AccountLayoutException("FocusBond vault is not its SKR ATA")
        return FocusBondAccount(
            address = address,
            rig = rig,
            authority = b.pubkey(40),
            vault = vault,
            shiftId = shiftId,
            amount = b.u64(112),
            shiftStartRound = b.u64(120),
            shiftStartTs = b.i64(128),
            lockedTs = b.i64(136),
        )
    }

    /** A gift escrow. The address must be `["gift", sender, nonce]` for the sender and nonce stored in it. */
    fun giftEscrow(address: Pubkey, account: AccountInfo): GiftEscrowAccount {
        val b = header(account, GIFT_ESCROW_SIZE, GIFT_ESCROW_TAG, "GiftEscrow")
        val sender = b.pubkey(8)
        val nonce = b.u64(72)
        canonical(address, b, HeadsDownProgram.giftEscrow(sender, nonce), "GiftEscrow")
        val kind = b.u8(104)
        if (kind != GiftEscrowAccount.KIND_WALLET && kind != GiftEscrowAccount.KIND_SGT_MINT) {
            throw AccountLayoutException("unknown gift recipient kind")
        }
        val recipient = b.pubkey(40)
        if (recipient == Pubkey.DEFAULT) throw AccountLayoutException("GiftEscrow has no recipient")
        val lamports = b.u64(80)
        // The gift sits on top of the escrow's rent: an escrow holding less than it promises is not one.
        if (account.lamports < lamports) throw AccountLayoutException("GiftEscrow holds less than the gift")
        return GiftEscrowAccount(
            address = address,
            sender = sender,
            recipient = recipient,
            nonce = nonce,
            lamports = lamports,
            createdTs = b.i64(88),
            expiryTs = b.i64(96),
            recipientKind = kind,
        )
    }

    /** The account sits at the canonical PDA of its own seed fields, with the canonical bump stored. */
    private fun canonical(address: Pubkey, b: AccountBytes, expected: ProgramAddress, name: String) {
        if (expected.address != address) throw AccountLayoutException("$name is not the PDA of its seeds")
        if (b.u8(2) != expected.bump) throw AccountLayoutException("$name bump is not canonical")
    }

    private fun header(account: AccountInfo, size: Int, tag: Int, name: String): AccountBytes {
        if (account.owner != HeadsDownProgram.ID) throw AccountLayoutException("$name is not owned by heads_down")
        val b = AccountBytes(account.data)
        if (b.size != size) throw AccountLayoutException("$name must be $size bytes, was ${b.size}")
        if (b.u8(0) != tag) throw AccountLayoutException("$name tag ${b.u8(0)} != $tag")
        if (b.u8(1) != VERSION) throw AccountLayoutException("$name version ${b.u8(1)} is not $VERSION")
        return b
    }

    private fun flag(value: Int, name: String): Boolean = when (value) {
        0 -> false
        1 -> true
        else -> throw AccountLayoutException("$name must be 0 or 1")
    }
}
