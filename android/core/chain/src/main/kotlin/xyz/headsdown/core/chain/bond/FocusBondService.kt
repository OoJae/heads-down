package xyz.headsdown.core.chain.bond

import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.accounts.FocusBondAccount
import xyz.headsdown.core.chain.accounts.HeadsDownAccounts
import xyz.headsdown.core.chain.accounts.OreAccounts
import xyz.headsdown.core.chain.accounts.RigAccount
import xyz.headsdown.core.chain.accounts.ShiftLogAccount
import xyz.headsdown.core.chain.accounts.SplTokenAccounts
import xyz.headsdown.core.chain.clockout.ShiftSeal
import xyz.headsdown.core.chain.ix.SkrInstructions
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.chain.tx.TransactionBuilder
import xyz.headsdown.core.chain.tx.TxVersion
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.core.wallet.PreparedTransactions
import xyz.headsdown.core.wallet.WalletCapabilities

/** Where a wallet's Focus Bond stands, read from the chain. */
class FocusBondStatus(
    /** SKR in the wallet's token account. */
    val skrBalance: ULong,
    /** null: the wallet has no rig yet. */
    val rig: RigAccount?,
    /** The bond still locked on the rig's current (or last) shift. */
    val bond: FocusBondAccount?,
    /** That shift's ShiftLog when it is already sealed. */
    val bondShiftLog: ShiftLogAccount?,
    /** How an `end_shift` signed now would seal the open shift; null when no shift is open. */
    val sealIfEndedNow: ShiftEndReason?,
) {
    /**
     * A bond can be locked on the open shift right now: the program wants the shift open and
     * clean (Armed or Down, no BREAK or FREEZE recorded) and not bonded yet (INTERFACE §11.4).
     */
    val canLockNow: Boolean
        get() = rig != null && bond == null && rig.shiftOpen && rig.breakReason == 0 &&
            (rig.state == RigSignalState.ARMED || rig.state == RigSignalState.DOWN)

    /** The shift the bond rides on recorded a break (or sealed with one): the SKR goes to the Bury auction. */
    val bondLost: Boolean
        get() = bond != null && (
            (bondShiftLog != null && !(bond.isResolvedBy(bondShiftLog) && bondShiftLog.completed)) ||
                (bondShiftLog == null && rig != null && (rig.breakReason != 0 || rig.state == RigSignalState.BROKEN || rig.state == RigSignalState.FROZEN))
            )
}

/** Why a bond cannot be locked. The message is fixed text, safe to show. */
class BondRefusedException(val reason: Reason) : IllegalStateException(reason.message) {
    enum class Reason(val message: String) {
        NO_OPEN_SHIFT("A Focus Bond is locked on an open shift. Clock in first."),
        SHIFT_NOT_CLEAN("This shift already recorded a break, so it cannot carry a bond."),
        ALREADY_BONDED("This shift already has a Focus Bond."),
        INSUFFICIENT_SKR("Not enough SKR in this wallet for the Focus Bond."),
    }
}

/** `[bond vault, lock_focus_bond]`, serialized for MWA. */
class PreparedBondLock(
    transaction: ByteArray,
    lastValidBlockHeight: Long,
    val authority: Pubkey,
    val shiftId: ULong,
    val amount: ULong,
) : PreparedTransactions(listOf(transaction), lastValidBlockHeight)

/**
 * Focus Bond outside the clock-in transaction: what the home screen shows ([status]), and locking
 * a bond on a shift that is already open ([prepareLock]), which is the path taken when the bond
 * did not fit into the clock-in transaction.
 */
class FocusBondService(
    private val rpc: SolanaJsonRpc,
    private val nowUnix: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    suspend fun status(authority: Pubkey): FocusBondStatus {
        val rigAddress = HeadsDownProgram.rig(authority).address
        val read = rpc.getMultipleAccounts(listOf(rigAddress, Skr.account(authority), Ore.BOARD))
        val rig = read[0]?.let { HeadsDownAccounts.rig(rigAddress, it) }
        val skr = SplTokenAccounts.userBalance(read[1], Skr.MINT, authority)
        var bond: FocusBondAccount? = null
        var log: ShiftLogAccount? = null
        if (rig != null && rig.shiftId > 0uL) {
            val bondAddress = HeadsDownProgram.focusBond(rigAddress, rig.shiftId).address
            val logAddress = HeadsDownProgram.shiftLog(rigAddress, rig.shiftId).address
            val (bondInfo, logInfo) = rpc.getMultipleAccounts(listOf(bondAddress, logAddress))
            bond = bondInfo?.let { HeadsDownAccounts.focusBond(bondAddress, it) }?.takeIf { it.authority == authority }
            log = logInfo?.let { HeadsDownAccounts.shiftLog(logAddress, it) }
        }
        val board = read[2]?.let { OreAccounts.board(Ore.BOARD, it) }
        val seal = if (rig != null && rig.shiftOpen && board != null) ShiftSeal.predict(rig, board.roundId, nowUnix()) else null
        return FocusBondStatus(skr, rig, bond, log, seal)
    }

    /** Locks [amount] SKR on the wallet's open shift. Throws [BondRefusedException] when the program would refuse. */
    suspend fun prepareLock(authority: Pubkey, amount: ULong, capabilities: WalletCapabilities): PreparedBondLock {
        require(amount >= 1uL && amount <= Skr.FOCUS_BOND_CAP) { "a Focus Bond is 1 base unit to 5,000 SKR" }
        val status = status(authority)
        val rig = status.rig
        if (rig == null || !rig.shiftOpen) throw BondRefusedException(BondRefusedException.Reason.NO_OPEN_SHIFT)
        if (status.bond != null) throw BondRefusedException(BondRefusedException.Reason.ALREADY_BONDED)
        // The ShiftLog slot must be free too; an open shift has none unless the rig was re-registered.
        if (!status.canLockNow || status.bondShiftLog != null) throw BondRefusedException(BondRefusedException.Reason.SHIFT_NOT_CLEAN)
        if (amount > status.skrBalance) throw BondRefusedException(BondRefusedException.Reason.INSUFFICIENT_SKR)
        val instructions = listOf(
            SkrInstructions.focusBondVault(authority, rig.shiftId),
            SkrInstructions.lockFocusBond(authority, rig.shiftId, amount),
        )
        val blockhash = rpc.getLatestBlockhash()
        val version = if (capabilities.supportsV0) TxVersion.V0 else TxVersion.LEGACY
        val message = TransactionBuilder.compile(authority, instructions, blockhash.blockhash, version)
        return PreparedBondLock(TransactionBuilder.unsignedTransaction(message), blockhash.lastValidBlockHeight, authority, rig.shiftId, amount)
    }
}
