package xyz.headsdown.core.chain.stack

import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.accounts.AccountLayoutException
import xyz.headsdown.core.chain.accounts.HeadsDownAccounts
import xyz.headsdown.core.chain.accounts.OreAccounts
import xyz.headsdown.core.chain.accounts.RigAccount
import xyz.headsdown.core.chain.accounts.RigTier
import xyz.headsdown.core.chain.accounts.SgtHolding
import xyz.headsdown.core.chain.accounts.SplTokenAccounts
import xyz.headsdown.core.chain.accounts.ifCreated
import xyz.headsdown.core.chain.accounts.StackSeatAccount
import xyz.headsdown.core.chain.accounts.StackStatus
import xyz.headsdown.core.chain.accounts.StackTableAccount
import xyz.headsdown.core.chain.gift.SgtLookup
import xyz.headsdown.core.chain.ix.AssociatedTokenInstructions
import xyz.headsdown.core.chain.ix.HeadsDownInstructions
import xyz.headsdown.core.chain.ix.SgtAccounts
import xyz.headsdown.core.chain.ix.SkrInstructions
import xyz.headsdown.core.chain.ix.StackFlags
import xyz.headsdown.core.chain.ix.StackParams
import xyz.headsdown.core.chain.rpc.AccountFilter
import xyz.headsdown.core.chain.rpc.RpcProtocolException
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.chain.tx.TransactionBuilder
import xyz.headsdown.core.chain.tx.TxVersion
import xyz.headsdown.core.wallet.PreparedTransactions
import xyz.headsdown.core.wallet.WalletCapabilities
import java.security.SecureRandom

/**
 * What the host chooses for a new table. Lengths are ORE rounds (about 78 seconds each on
 * mainnet); the service turns them into `start_round` / `end_round` from the live Board.
 */
data class StackDraft(
    /** SKR base units every seat bonds. */
    val bond: ULong,
    /** The window opens this many rounds after the live one. Seats can be taken until then. */
    val startsInRounds: Int,
    /** Rounds in the window. */
    val rounds: Int,
    /** Window rounds a seat may miss and still finish. */
    val graceGaps: Int,
    /** Remote table: verified Seeker rigs only, attested keys, bond cap 1,000 SKR. */
    val remote: Boolean = false,
    /** Every forfeit goes to the Bury auction; finishers only get their own bond back. */
    val buryOnly: Boolean = false,
    val maxSeats: Int = 4,
) {
    val flags: Int get() = (if (remote) StackFlags.REMOTE else 0) or (if (buryOnly) StackFlags.BURY_ONLY else 0)

    companion object {
        /** The fewest rounds between opening and the window: time to land the open and to join. */
        const val MIN_LEAD_ROUNDS = 3
    }
}

/** Why a Stack action cannot be built. The message is fixed text, safe to show. */
class StackRefusedException(val reason: Reason) : IllegalStateException(reason.message) {
    enum class Reason(val message: String) {
        NOT_A_TABLE("This is not a Stack table on this cluster."),
        NO_RIG("A seat belongs to a rig. Clock in once with this wallet first."),
        JOIN_CLOSED("This table's window has started, so its seats are closed."),
        TABLE_FULL("This table is full."),
        ALREADY_SEATED("This rig already has a seat at this table."),
        NEEDS_ATTESTED_KEY("This table takes attested rig keys only. This rig's key has no valid registrar attestation."),
        NEEDS_SEEKER_REMOTE("Remote tables need a verified Seeker rig. This wallet holds no Seeker Genesis Token."),
        NEEDS_SEEKER_BOND("Guest rigs can bond up to 500 SKR. A higher bond needs a verified Seeker rig."),
        INSUFFICIENT_SKR("Not enough SKR in this wallet for the bond."),
        BAD_TABLE("These table settings are outside what the program accepts."),
        BAD_WINDOW("The window must start a few rounds from now, within about a week, and last at most 1,440 rounds."),
        NO_SEAT("This wallet has no seat at this table."),
        NOT_CLAIMABLE("Nothing to claim yet. The table is settled after its last round; if nobody settles it, every bond can be taken back after the timeout."),
    }
}

/** How a wallet's rig can take a seat. */
class SeatPlan(
    /** The table needs a verified Seeker (remote, or a bond above the guest cap). */
    val needsSeeker: Boolean,
    /** The Seeker Genesis Token passed to `join_stack` (null when not needed). */
    val sgt: SgtHolding?,
    /** `verify_seeker` goes first: the rig is not (or no longer) verified with that token. */
    val verifiesSeeker: Boolean,
    internal val verifyInstruction: Instruction?,
    val rig: RigAccount,
)

/** Whether [authority] could take a seat right now, for the review screen. */
sealed interface JoinCheck {
    data class Eligible(val plan: SeatPlan) : JoinCheck
    data class Refused(val reason: StackRefusedException.Reason) : JoinCheck
}

enum class StackAction { OPEN_AND_JOIN, JOIN, CLAIM }

/** One Stack transaction, serialized for MWA. */
class PreparedStack(
    transaction: ByteArray,
    lastValidBlockHeight: Long,
    val authority: Pubkey,
    val table: Pubkey,
    val action: StackAction,
    /** SKR base units this transaction moves out of (OPEN_AND_JOIN, JOIN) or into (CLAIM) the wallet. */
    val amount: ULong,
    /** `verify_seeker` is in the transaction too. */
    val verifiesSeeker: Boolean = false,
) : PreparedTransactions(listOf(transaction), lastValidBlockHeight)

/** A table in the "my tables" list: the table and this wallet's seat, without the other seats. */
class StackSummary(val view: StackTableView, val mySeat: StackSeatView?, val hostedByMe: Boolean)

/**
 * Stack on the phone: open a table (and take the first seat), join one, read it live, and claim.
 *
 * `stack_checkin` and `settle_stack` are permissionless and sent by the crank; the phone does
 * not build them. What the phone shows before settlement is computed from the seats with the
 * program's own rules ([StackMath]); after settlement it shows what the chain wrote.
 *
 * Every refusal the program would make for a join is made here first, with a reason
 * ([StackRefusedException]), so the wallet is never asked to sign a transaction that would fail.
 */
class StackService(
    private val rpc: SolanaJsonRpc,
    private val sgt: SgtLookup = SgtLookup(rpc),
    private val nowUnix: () -> Long = { System.currentTimeMillis() / 1000 },
    private val newTableId: () -> ULong = { SecureRandom().nextLong().toULong() },
) {
    // ------------------------------------------------------------------------- reading

    /**
     * The table at [address] with its seats, or null when there is no Stack table there (no
     * account, or an account that is not a canonical StackTable).
     */
    suspend fun table(address: Pubkey): StackTableView? {
        val (tableInfo, boardInfo) = rpc.getMultipleAccounts(listOf(address, Ore.BOARD))
        if (tableInfo == null) return null
        val table = try {
            HeadsDownAccounts.stackTable(address, tableInfo)
        } catch (_: AccountLayoutException) {
            return null
        }
        val board = OreAccounts.board(Ore.BOARD, boardInfo ?: throw RpcProtocolException("no ORE Board on this cluster"))
        return StackTableView(table, seatsOf(address), board.roundId, nowUnix())
    }

    /** Every seat of [table] that still exists (a seat closes when it claims), through the checked decoder. */
    private suspend fun seatsOf(table: Pubkey): List<StackSeatAccount> {
        val found = rpc.getProgramAccounts(
            HeadsDownProgram.ID,
            listOf(
                AccountFilter.DataSize(HeadsDownAccounts.STACK_SEAT_SIZE),
                AccountFilter.Memcmp(HeadsDownAccounts.STACK_SEAT_TABLE_OFFSET, table.bytes),
            ),
        )
        if (found.size > StackMath.MAX_SEATS) throw RpcProtocolException("more seats than a table has")
        return found.map { HeadsDownAccounts.stackSeat(it.pubkey, it.account) }.filter { it.table == table }
    }

    /** Tables [authority] hosts or sits at, most recently opened first, at most [MAX_LISTED]. */
    suspend fun mine(authority: Pubkey): List<StackSummary> {
        val seats = rpc.getProgramAccounts(
            HeadsDownProgram.ID,
            listOf(
                AccountFilter.DataSize(HeadsDownAccounts.STACK_SEAT_SIZE),
                AccountFilter.Memcmp(HeadsDownAccounts.STACK_SEAT_AUTHORITY_OFFSET, authority.bytes),
            ),
        ).mapNotNull { runCatching { HeadsDownAccounts.stackSeat(it.pubkey, it.account) }.getOrNull() }
            .filter { it.authority == authority }
        val hosted = rpc.getProgramAccounts(
            HeadsDownProgram.ID,
            listOf(
                AccountFilter.DataSize(HeadsDownAccounts.STACK_TABLE_SIZE),
                AccountFilter.Memcmp(HeadsDownAccounts.STACK_TABLE_HOST_OFFSET, authority.bytes),
            ),
        ).map { it.pubkey }
        val addresses = (seats.map { it.table } + hosted).distinct().take(MAX_LISTED)
        if (addresses.isEmpty()) return emptyList()
        val read = rpc.getMultipleAccounts(listOf(Ore.BOARD) + addresses)
        val board = OreAccounts.board(Ore.BOARD, read[0] ?: throw RpcProtocolException("no ORE Board on this cluster"))
        val now = nowUnix()
        return addresses.mapIndexedNotNull { i, address ->
            val table = read[i + 1]?.let { runCatching { HeadsDownAccounts.stackTable(address, it) }.getOrNull() } ?: return@mapIndexedNotNull null
            val view = StackTableView(table, seats.filter { it.table == address }, board.roundId, now)
            StackSummary(view, view.seatOf(authority), hostedByMe = table.host == authority)
        }.sortedByDescending { it.view.table.openedTs }
    }

    // ------------------------------------------------------------------------- joining

    /** Whether [authority]'s rig could take a seat at [tableAddress] now. Read-only. */
    suspend fun joinCheck(authority: Pubkey, tableAddress: Pubkey): JoinCheck = try {
        JoinCheck.Eligible(readJoin(authority, tableAddress).plan)
    } catch (e: StackRefusedException) {
        JoinCheck.Refused(e.reason)
    }

    /** `[verify_seeker?] join_stack` for [authority]. Throws [StackRefusedException] when the program would refuse. */
    suspend fun prepareJoin(authority: Pubkey, tableAddress: Pubkey, capabilities: WalletCapabilities): PreparedStack {
        val join = readJoin(authority, tableAddress)
        val instructions = listOfNotNull(
            join.plan.verifyInstruction,
            SkrInstructions.joinStack(authority, tableAddress, join.table.remote, join.plan.sgt?.accounts()),
        )
        return prepared(authority, instructions, capabilities, tableAddress, StackAction.JOIN, join.table.bond, join.plan.verifiesSeeker)
    }

    private class JoinRead(val table: StackTableAccount, val plan: SeatPlan)

    private suspend fun readJoin(authority: Pubkey, tableAddress: Pubkey): JoinRead {
        val rigAddress = HeadsDownProgram.rig(authority).address
        val seatByRig = HeadsDownProgram.stackSeat(tableAddress, rigAddress).address
        val read = rpc.getMultipleAccounts(listOf(tableAddress, Ore.BOARD, rigAddress, Skr.account(authority), seatByRig))
        val table = read[0]?.let { runCatching { HeadsDownAccounts.stackTable(tableAddress, it) }.getOrNull() }
            ?: throw StackRefusedException(StackRefusedException.Reason.NOT_A_TABLE)
        val board = OreAccounts.board(Ore.BOARD, read[1] ?: throw RpcProtocolException("no ORE Board on this cluster"))
        if (table.status != StackStatus.OPEN || board.roundId >= table.startRound) throw StackRefusedException(StackRefusedException.Reason.JOIN_CLOSED)
        if (table.full) throw StackRefusedException(StackRefusedException.Reason.TABLE_FULL)
        val rig = HeadsDownAccounts.rigOrNull(rigAddress, read[2])
        if (!table.remote && read[4].ifCreated() != null) throw StackRefusedException(StackRefusedException.Reason.ALREADY_SEATED)
        val plan = seatPlan(authority, rig, table.bond, table.remote, table.attestedOnly, SplTokenAccounts.userBalance(read[3].ifCreated(), Skr.MINT, authority))
        if (table.remote) {
            // A remote seat is keyed by the Seeker Genesis Token: one seat per Seeker.
            val seat = HeadsDownProgram.stackSeat(tableAddress, plan.sgt!!.mint).address
            if (rpc.getAccountInfo(seat) != null) throw StackRefusedException(StackRefusedException.Reason.ALREADY_SEATED)
        }
        return JoinRead(table, plan)
    }

    /**
     * The eligibility rules of `join_stack` (INTERFACE §11.5), in the program's order: the rig,
     * the attestation, the Seeker Genesis Token, then the SKR for the bond.
     */
    private suspend fun seatPlan(
        authority: Pubkey,
        rig: RigAccount?,
        bond: ULong,
        remote: Boolean,
        attestedOnly: Boolean,
        skrBalance: ULong,
    ): SeatPlan {
        if (rig == null) throw StackRefusedException(StackRefusedException.Reason.NO_RIG)
        if (attestedOnly || remote) {
            val slot = rpc.getLatestBlockhash().contextSlot
            val valid = rig.attestationLevel >= 1 && slot >= 0 && rig.attestationExpirySlot > slot.toULong()
            if (!valid) throw StackRefusedException(StackRefusedException.Reason.NEEDS_ATTESTED_KEY)
        }
        val needsSeeker = remote || bond > Skr.GUEST_BOND_CAP
        var holding: SgtHolding? = null
        var verify: Instruction? = null
        if (needsSeeker) {
            // The token the rig was verified with, if the wallet still holds it ...
            val verifiedMint = rig.sgtMint?.takeIf { rig.tier == RigTier.SEEKER }
            holding = verifiedMint?.let { sgt.holdingOf(authority, it) }
            if (holding == null) {
                // ... otherwise any Seeker Genesis Token the wallet holds now, verified first.
                val held = sgt.holdings(authority).firstOrNull()
                    ?: throw StackRefusedException(if (remote) StackRefusedException.Reason.NEEDS_SEEKER_REMOTE else StackRefusedException.Reason.NEEDS_SEEKER_BOND)
                val seatAddress = HeadsDownProgram.seekerSeat(held.mint).address
                val previousRig = rpc.getAccountInfo(seatAddress).ifCreated()
                    ?.let { HeadsDownAccounts.seekerSeat(seatAddress, it).rig }
                    ?.takeIf { it != rig.address && it != Pubkey.DEFAULT }
                verify = HeadsDownInstructions.verifySeeker(authority, held.mint, held.tokenAccount, previousRig)
                holding = held
            }
        }
        if (skrBalance < bond) throw StackRefusedException(StackRefusedException.Reason.INSUFFICIENT_SKR)
        return SeatPlan(needsSeeker, holding, verify != null, verify, rig)
    }

    // ------------------------------------------------------------------------- opening

    /**
     * `[table vault] open_stack [verify_seeker?] join_stack`: the host opens the table and takes
     * its first seat in one transaction (the host bonds like everyone else).
     */
    suspend fun prepareOpen(host: Pubkey, draft: StackDraft, capabilities: WalletCapabilities): PreparedStack {
        val rigAddress = HeadsDownProgram.rig(host).address
        val read = rpc.getMultipleAccounts(listOf(Ore.BOARD, rigAddress, Skr.account(host)))
        val board = OreAccounts.board(Ore.BOARD, read[0] ?: throw RpcProtocolException("no ORE Board on this cluster"))
        if (draft.startsInRounds < StackDraft.MIN_LEAD_ROUNDS || draft.rounds < 1) throw StackRefusedException(StackRefusedException.Reason.BAD_WINDOW)
        val start = board.roundId + draft.startsInRounds.toULong()
        val params = try {
            StackParams(
                tableId = newTableId(),
                bond = draft.bond,
                startRound = start,
                endRound = start + draft.rounds.toULong() - 1uL,
                graceGaps = draft.graceGaps.toLong(),
                flags = draft.flags,
                maxSeats = draft.maxSeats,
            )
        } catch (_: IllegalArgumentException) {
            throw StackRefusedException(StackRefusedException.Reason.BAD_TABLE)
        }
        if (!params.admits(board.roundId)) throw StackRefusedException(StackRefusedException.Reason.BAD_WINDOW)
        val rig = HeadsDownAccounts.rigOrNull(rigAddress, read[1])
        val plan = seatPlan(host, rig, draft.bond, draft.remote, attestedOnly = draft.remote, SplTokenAccounts.userBalance(read[2].ifCreated(), Skr.MINT, host))
        val table = HeadsDownProgram.stackTable(host, params.tableId).address
        val instructions = listOfNotNull(
            SkrInstructions.openStackVault(host, params.tableId),
            SkrInstructions.openStack(host, params),
            plan.verifyInstruction,
            SkrInstructions.joinStack(host, table, draft.remote, plan.sgt?.accounts()),
        )
        return prepared(host, instructions, capabilities, table, StackAction.OPEN_AND_JOIN, draft.bond, plan.verifiesSeeker)
    }

    // ------------------------------------------------------------------------- claiming

    /**
     * `[SKR account?] claim_stack` for the seat of [seatAuthority], paid for by [payer]. The
     * instruction is permissionless and pays only the seat's stored wallet, so anyone at the
     * table can press claim for anyone else.
     */
    suspend fun prepareClaim(payer: Pubkey, tableAddress: Pubkey, seatAuthority: Pubkey, capabilities: WalletCapabilities): PreparedStack {
        val view = table(tableAddress) ?: throw StackRefusedException(StackRefusedException.Reason.NOT_A_TABLE)
        val seat = view.seatOf(seatAuthority) ?: throw StackRefusedException(StackRefusedException.Reason.NO_SEAT)
        if (!seat.claimable) throw StackRefusedException(StackRefusedException.Reason.NOT_CLAIMABLE)
        val instructions = buildList {
            // The program reads the destination only when something is paid.
            if (seat.payout > 0uL && rpc.getAccountInfo(Skr.account(seatAuthority)) == null) {
                add(AssociatedTokenInstructions.createIdempotent(payer, seatAuthority, Skr.MINT))
            }
            add(SkrInstructions.claimStack(tableAddress, seat.seat.address, seatAuthority))
        }
        return prepared(payer, instructions, capabilities, tableAddress, StackAction.CLAIM, seat.payout)
    }

    private suspend fun prepared(
        payer: Pubkey,
        instructions: List<Instruction>,
        capabilities: WalletCapabilities,
        table: Pubkey,
        action: StackAction,
        amount: ULong,
        verifiesSeeker: Boolean = false,
    ): PreparedStack {
        val blockhash = rpc.getLatestBlockhash()
        val version = if (capabilities.supportsV0) TxVersion.V0 else TxVersion.LEGACY
        val message = TransactionBuilder.compile(payer, instructions, blockhash.blockhash, version)
        check(TransactionBuilder.fits(message)) { "Stack transaction does not fit one packet" }
        return PreparedStack(TransactionBuilder.unsignedTransaction(message), blockhash.lastValidBlockHeight, payer, table, action, amount, verifiesSeeker)
    }

    private fun SgtHolding.accounts() = SgtAccounts(tokenAccount, mint)

    companion object {
        const val MAX_LISTED = 20
    }
}
