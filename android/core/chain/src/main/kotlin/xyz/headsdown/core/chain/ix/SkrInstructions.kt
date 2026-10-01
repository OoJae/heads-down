package xyz.headsdown.core.chain.ix

import xyz.headsdown.core.chain.AssociatedToken
import xyz.headsdown.core.chain.GiftLimits
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.tx.AccountMeta
import xyz.headsdown.core.chain.tx.DataWriter
import xyz.headsdown.core.chain.tx.Instruction

/** Associated Token Account program. The phone only ever sends `CreateIdempotent`. */
object AssociatedTokenInstructions {
    const val TAG_CREATE_IDEMPOTENT = 1

    /**
     * `CreateIdempotent` (tag 1): creates `ATA(owner, mint)` if it does not exist, and succeeds
     * when it already does. It is the companion that creates a table's or a bond's SKR vault
     * before `open_stack` / `lock_focus_bond` (INTERFACE §11.2), and the wallet's own SKR account
     * before a payout.
     *
     * Accounts: `payer (s,w) | ata (w) | owner | mint | system | token program`.
     */
    fun createIdempotent(payer: Pubkey, owner: Pubkey, mint: Pubkey, tokenProgram: Pubkey = WellKnown.SPL_TOKEN): Instruction = Instruction(
        WellKnown.ASSOCIATED_TOKEN,
        listOf(
            AccountMeta.signer(payer),
            AccountMeta.writable(AssociatedToken.address(owner, mint, tokenProgram).address),
            AccountMeta.readonly(owner),
            AccountMeta.readonly(mint),
            AccountMeta.readonly(WellKnown.SYSTEM_PROGRAM),
            AccountMeta.readonly(tokenProgram),
        ),
        byteArrayOf(TAG_CREATE_IDEMPOTENT.toByte()),
    )
}

/** `open_stack` flags (INTERFACE §11.3, `StackTable.flags`). */
object StackFlags {
    /** Remote table: Seeker rigs only (SGT re-checked at join), attested keys, lower bond cap. */
    const val REMOTE = 0x01

    /** Every forfeit goes to the Bury auction; finishers only get their own bond back. */
    const val BURY_ONLY = 0x02

    /** Attested rig keys only (registrar level 1 or 2, unexpired). Forced on with [REMOTE]. */
    const val ATTESTED_ONLY = 0x04
    const val ALL = REMOTE or BURY_ONLY or ATTESTED_ONLY
}

/**
 * `open_stack` parameters. Bond in SKR base units; the window is ORE rounds
 * `[startRound, endRound]`, both inclusive. Checked here against every rule the program enforces
 * without chain state (INTERFACE §11.4, tag 15); the rules that need the live `Board.round_id`
 * are checked by [admits].
 */
data class StackParams(
    val tableId: ULong,
    val bond: ULong,
    val startRound: ULong,
    val endRound: ULong,
    val graceGaps: Long,
    val flags: Int,
    val maxSeats: Int,
) {
    init {
        require(flags and StackFlags.ALL.inv() == 0) { "unknown stack flag bits" }
        require(maxSeats in MIN_SEATS..MAX_SEATS) { "a table has 2..8 seats" }
        require(bond >= 1uL && bond <= bondCap(flags)) { "bond must be 1 base unit up to the cap" }
        require(endRound >= startRound) { "the window must not be empty" }
        require(rounds <= MAX_ROUNDS) { "the window is at most $MAX_ROUNDS rounds" }
        require(graceGaps in 0..0xFFFF_FFFFL && graceGaps.toULong() < rounds) { "grace must be below the window length" }
    }

    val remote: Boolean get() = flags and StackFlags.REMOTE != 0
    val buryOnly: Boolean get() = flags and StackFlags.BURY_ONLY != 0

    /** Rounds in the window. */
    val rounds: ULong get() = endRound - startRound + 1uL

    /** The rules that depend on the live round: the window starts later, and not too far ahead. */
    fun admits(boardRoundId: ULong): Boolean = startRound > boardRoundId && startRound - boardRoundId <= MAX_LEAD_ROUNDS

    companion object {
        const val MIN_SEATS = 2
        const val MAX_SEATS = 8

        /** Longest window (`MAX_STACK_ROUNDS`). */
        const val MAX_ROUNDS: ULong = 1_440uL

        /** How far ahead of the live round a window may start (`MAX_STACK_LEAD_ROUNDS`). */
        const val MAX_LEAD_ROUNDS: ULong = 10_080uL

        fun bondCap(flags: Int): ULong = if (flags and StackFlags.REMOTE != 0) Skr.REMOTE_BOND_CAP else Skr.STACK_BOND_CAP
    }
}

/**
 * A wallet's Seeker Genesis Token holding: the Token-2022 token account and its mint. The program
 * re-verifies it with `sgt-verify` wherever it is passed (`join_stack`, `claim_gift`).
 */
data class SgtAccounts(val tokenAccount: Pubkey, val mint: Pubkey)

/** Who a gift is for (`GiftEscrow.recipient_kind`). */
enum class GiftRecipientKind(val wire: Int) {
    /** That wallet claims. */
    WALLET(0),

    /** Whoever holds that SGT mint when claiming (verified in the program). */
    SGT_MINT(1),
    ;

    companion object {
        fun fromWire(value: Int): GiftRecipientKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("unknown recipient kind $value")
    }
}

/**
 * The v1.2 SKR instructions the phone builds (INTERFACE §11.4), byte for byte the executed golden
 * vectors in `programs/heads-down/vectors/instructions.json` (`GoldenInstructionsTest`): Stack
 * (open, join, claim), Focus Bond (lock, release) and Gift a Rig (create, claim, refund).
 *
 * `stack_checkin`, `settle_stack`, `forfeit_focus_bond`, `init_bury_vault` and `bury_auction_buy`
 * are permissionless and sent by the crank; the phone does not build them.
 *
 * As for the v1.1 builders: PDAs and vaults are always derived here, never taken as free
 * parameters where the program derives them itself, and every value is range-checked so an
 * instruction the program would refuse for its data is never built.
 */
object SkrInstructions {
    const val TAG_OPEN_STACK = 15
    const val TAG_JOIN_STACK = 16
    const val TAG_CLAIM_STACK = 19
    const val TAG_LOCK_FOCUS_BOND = 20
    const val TAG_RELEASE_FOCUS_BOND = 21
    const val TAG_CREATE_GIFT = 23
    const val TAG_CLAIM_GIFT = 24
    const val TAG_REFUND_GIFT = 25

    const val OPEN_STACK_BYTES = 39
    const val LOCK_FOCUS_BOND_BYTES = 17
    const val CREATE_GIFT_BYTES = 50

    private val programId get() = HeadsDownProgram.ID

    // ------------------------------------------------------------------------------ Stack

    /**
     * `open_stack` (tag 15), 39 bytes: `tag | table_id u64 | bond u64 | start_round u64 |
     * end_round u64 | grace_gaps u32 | flags u8 | max_seats u8`.
     *
     * Accounts: `host (s,w: pays the table rent) | table (w) = ["stack", host, table_id] |
     * table SKR vault = ATA(table, SKR) | ORE Board | system`. The vault must already exist:
     * put [openStackVault] before this instruction.
     */
    fun openStack(host: Pubkey, params: StackParams): Instruction {
        val table = HeadsDownProgram.stackTable(host, params.tableId).address
        val data = DataWriter(OPEN_STACK_BYTES)
            .u8(TAG_OPEN_STACK)
            .u64(params.tableId)
            .u64(params.bond)
            .u64(params.startRound)
            .u64(params.endRound)
            .u32(params.graceGaps)
            .u8(params.flags)
            .u8(params.maxSeats)
            .build()
        return Instruction(
            programId,
            listOf(
                AccountMeta.signer(host),
                AccountMeta.writable(table),
                AccountMeta.readonly(HeadsDownProgram.skrVault(table)),
                AccountMeta.readonly(Ore.BOARD),
                AccountMeta.readonly(WellKnown.SYSTEM_PROGRAM),
            ),
            data,
        )
    }

    /** The companion of [openStack]: creates the table's SKR vault, paid by the host. */
    fun openStackVault(host: Pubkey, tableId: ULong): Instruction =
        AssociatedTokenInstructions.createIdempotent(host, HeadsDownProgram.stackTable(host, tableId).address, Skr.MINT)

    /**
     * `join_stack` (tag 16), 1 byte. The bond is the table's; it moves from the wallet's SKR
     * account into the table vault.
     *
     * Accounts: `authority (s,w) | rig | table (w) | seat (w) = ["stackseat", table, key] |
     * authority's SKR account (w) | table SKR vault (w) | ORE Board | SPL Token | system
     * [| SGT token account | SGT mint]`.
     *
     * The seat key is the rig at an in-person table and the rig's SGT mint at a [remote] table.
     * [sgt] is required at a remote table and at any table whose bond is above the 500 SKR guest
     * cap; the program re-verifies it and requires it to be the rig's own SGT.
     */
    fun joinStack(authority: Pubkey, table: Pubkey, remote: Boolean, sgt: SgtAccounts? = null): Instruction {
        require(!remote || sgt != null) { "a remote table seats a verified Seeker: the SGT accounts are required" }
        val rig = HeadsDownProgram.rig(authority).address
        val key = if (remote) sgt!!.mint else rig
        val accounts = mutableListOf(
            AccountMeta.signer(authority),
            AccountMeta.readonly(rig),
            AccountMeta.writable(table),
            AccountMeta.writable(HeadsDownProgram.stackSeat(table, key).address),
            AccountMeta.writable(Skr.account(authority)),
            AccountMeta.writable(HeadsDownProgram.skrVault(table)),
            AccountMeta.readonly(Ore.BOARD),
            AccountMeta.readonly(WellKnown.SPL_TOKEN),
            AccountMeta.readonly(WellKnown.SYSTEM_PROGRAM),
        )
        if (sgt != null) {
            accounts += AccountMeta.readonly(sgt.tokenAccount)
            accounts += AccountMeta.readonly(sgt.mint)
        }
        return Instruction(programId, accounts, byteArrayOf(TAG_JOIN_STACK.toByte()))
    }

    /**
     * `claim_stack` (tag 19), 1 byte. Permissionless: the payout (or, after the timeout, the
     * refund) and the seat rent go only to the stored [seatAuthority], whoever signs the
     * transaction. The seat closes, so it claims once.
     *
     * Accounts: `table (w) | seat (w) | seat authority (w) | seat authority's SKR account (w) |
     * table SKR vault (w) | SPL Token`.
     */
    fun claimStack(table: Pubkey, seat: Pubkey, seatAuthority: Pubkey): Instruction = Instruction(
        programId,
        listOf(
            AccountMeta.writable(table),
            AccountMeta.writable(seat),
            AccountMeta.writable(seatAuthority),
            AccountMeta.writable(Skr.account(seatAuthority)),
            AccountMeta.writable(HeadsDownProgram.skrVault(table)),
            AccountMeta.readonly(WellKnown.SPL_TOKEN),
        ),
        byteArrayOf(TAG_CLAIM_STACK.toByte()),
    )

    // ------------------------------------------------------------------------- Focus Bond

    /**
     * `lock_focus_bond` (tag 20), 17 bytes: `tag | shift_id u64 | amount u64`. Locks SKR on the
     * rig's open, clean shift [shiftId] (Armed or Down, no BREAK yet; so it may follow
     * `arm_shift` in the same transaction). The bond's vault must already exist: put
     * [focusBondVault] before this instruction.
     *
     * Accounts: `authority (s,w) | rig | bond (w) = ["bond", rig, shift_id] | authority's SKR
     * account (w) | bond SKR vault (w) | ShiftLog ["shift", rig, shift_id] (must not exist) |
     * SPL Token | system`.
     */
    fun lockFocusBond(authority: Pubkey, shiftId: ULong, amount: ULong): Instruction {
        require(amount >= 1uL && amount <= Skr.FOCUS_BOND_CAP) { "a Focus Bond is 1 base unit to 5,000 SKR" }
        require(shiftId >= 1uL) { "shift ids start at 1 (the first arm)" }
        val rig = HeadsDownProgram.rig(authority).address
        val bond = HeadsDownProgram.focusBond(rig, shiftId).address
        return Instruction(
            programId,
            listOf(
                AccountMeta.signer(authority),
                AccountMeta.readonly(rig),
                AccountMeta.writable(bond),
                AccountMeta.writable(Skr.account(authority)),
                AccountMeta.writable(HeadsDownProgram.skrVault(bond)),
                AccountMeta.readonly(HeadsDownProgram.shiftLog(rig, shiftId).address),
                AccountMeta.readonly(WellKnown.SPL_TOKEN),
                AccountMeta.readonly(WellKnown.SYSTEM_PROGRAM),
            ),
            DataWriter(LOCK_FOCUS_BOND_BYTES).u8(TAG_LOCK_FOCUS_BOND).u64(shiftId).u64(amount).build(),
        )
    }

    /** The companion of [lockFocusBond]: creates the bond's SKR vault, paid by the wallet. */
    fun focusBondVault(authority: Pubkey, shiftId: ULong): Instruction {
        val rig = HeadsDownProgram.rig(authority).address
        return AssociatedTokenInstructions.createIdempotent(authority, HeadsDownProgram.focusBond(rig, shiftId).address, Skr.MINT)
    }

    /**
     * `release_focus_bond` (tag 21), 1 byte. Permissionless once the bonded shift's ShiftLog is
     * sealed `completed`: the whole vault goes back to the stored owner, and the vault and the
     * bond close (both rents to the owner). So it may follow `end_shift` in the same transaction.
     *
     * Accounts: `bond (w) | ShiftLog | bond SKR vault (w) | owner's SKR account (w) | owner (w) |
     * SPL Token`.
     */
    fun releaseFocusBond(authority: Pubkey, shiftId: ULong): Instruction {
        val rig = HeadsDownProgram.rig(authority).address
        val bond = HeadsDownProgram.focusBond(rig, shiftId).address
        return Instruction(
            programId,
            listOf(
                AccountMeta.writable(bond),
                AccountMeta.readonly(HeadsDownProgram.shiftLog(rig, shiftId).address),
                AccountMeta.writable(HeadsDownProgram.skrVault(bond)),
                AccountMeta.writable(Skr.account(authority)),
                AccountMeta.writable(authority),
                AccountMeta.readonly(WellKnown.SPL_TOKEN),
            ),
            byteArrayOf(TAG_RELEASE_FOCUS_BOND.toByte()),
        )
    }

    // -------------------------------------------------------------------------- Gift a Rig

    /**
     * `create_gift` (tag 23), 50 bytes: `tag | nonce u64 | recipient_kind u8 | recipient [32] |
     * lamports u64`. Escrows [lamports] of SOL (1 lamport to 10 SOL) for a wallet or for an SGT
     * mint, for 30 days. The program escrows SOL only: a sender paying in SKR puts a swap before
     * this instruction.
     *
     * Accounts: `sender (s,w: pays the rent and the gift) | gift (w) = ["gift", sender, nonce] | system`.
     */
    fun createGift(sender: Pubkey, nonce: ULong, kind: GiftRecipientKind, recipient: Pubkey, lamports: ULong): Instruction {
        require(recipient != Pubkey.DEFAULT) { "a gift needs a recipient" }
        require(lamports >= 1uL && lamports <= GiftLimits.MAX_LAMPORTS) { "a gift is 1 lamport to 10 SOL" }
        val data = DataWriter(CREATE_GIFT_BYTES)
            .u8(TAG_CREATE_GIFT)
            .u64(nonce)
            .u8(kind.wire)
            .bytes(recipient.bytes)
            .u64(lamports)
            .build()
        return Instruction(
            programId,
            listOf(
                AccountMeta.signer(sender),
                AccountMeta.writable(HeadsDownProgram.giftEscrow(sender, nonce).address),
                AccountMeta.readonly(WellKnown.SYSTEM_PROGRAM),
            ),
            data,
        )
    }

    /**
     * `claim_gift` (tag 24), 1 byte. Before the gift's expiry only. A wallet gift is claimed by
     * that wallet; an SGT gift by whoever holds that SGT now, proven with [sgt]. The lamports go
     * to the claimer and the escrow's rent back to [giftSender], so the same transaction can
     * continue with the claimer's own ORE `automate` and `register_rig`.
     *
     * Accounts: `claimer (s,w) | gift (w) | sender (w) [| SGT token account | SGT mint]`.
     */
    fun claimGift(claimer: Pubkey, gift: Pubkey, giftSender: Pubkey, sgt: SgtAccounts? = null): Instruction {
        val accounts = mutableListOf(
            AccountMeta.signer(claimer),
            AccountMeta.writable(gift),
            AccountMeta.writable(giftSender),
        )
        if (sgt != null) {
            accounts += AccountMeta.readonly(sgt.tokenAccount)
            accounts += AccountMeta.readonly(sgt.mint)
        }
        return Instruction(programId, accounts, byteArrayOf(TAG_CLAIM_GIFT.toByte()))
    }

    /**
     * `refund_gift` (tag 25), 1 byte. Permissionless from the gift's expiry: every lamport goes to
     * the stored [giftSender], whoever signs the transaction.
     *
     * Accounts: `gift (w) | sender (w)`.
     */
    fun refundGift(gift: Pubkey, giftSender: Pubkey): Instruction = Instruction(
        programId,
        listOf(AccountMeta.writable(gift), AccountMeta.writable(giftSender)),
        byteArrayOf(TAG_REFUND_GIFT.toByte()),
    )
}
