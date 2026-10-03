package xyz.headsdown.core.chain.clockin

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
import xyz.headsdown.core.chain.registrar.RegistrarVoucher
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.chain.tx.TransactionBuilder
import xyz.headsdown.core.chain.tx.TxVersion
import xyz.headsdown.core.wallet.PreparedTransactions
import xyz.headsdown.core.wallet.WalletCapabilities

/** The clock-in transaction, serialized for MWA, plus what it will do once confirmed. */
class PreparedClockIn(
    transaction: ByteArray,
    lastValidBlockHeight: Long,
    val authority: Pubkey,
    val plan: ClockInPlan,
    val version: TxVersion,
    /**
     * A Focus Bond was asked for but the transaction would not fit one packet with it (a first
     * clock-in that also carries a registrar voucher): the shift is armed without it, and the
     * bond can be locked right after, on the open shift.
     */
    val bondDeferred: Boolean = false,
) : PreparedTransactions(listOf(transaction), lastValidBlockHeight)

/**
 * Reads the chain state a clock-in depends on, composes the single transaction
 * ([ClockInComposer]) and serializes it for the wallet's `signAndSendTransactions`.
 *
 * Every account is decoded through the owner/size/PDA-checked decoders; anything unexpected
 * throws, which the wallet session turns into "nothing was sent". The transaction version
 * follows the wallet's capabilities (v0 when supported, else legacy).
 */
class ClockInService(
    private val rpc: SolanaJsonRpc,
    private val nowUnix: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /**
     * @param voucher a registrar voucher for this wallet and key, if one is held. The composer
     *   includes it only when the program will accept it; otherwise the rig is a guest.
     * @return null when the heads_down Config does not exist on this cluster (program not
     *   deployed or not initialized): there is nothing to arm on-chain.
     */
    suspend fun prepare(
        authority: Pubkey,
        rigKey: ByteArray,
        request: ClockInRequest,
        capabilities: WalletCapabilities,
        voucher: RegistrarVoucher? = null,
    ): PreparedClockIn? {
        val configAddress = HeadsDownProgram.config.address
        val rigAddress = HeadsDownProgram.rig(authority).address
        val automationAddress = Ore.automation(authority).address
        val skrAddress = Skr.account(authority)
        val read = rpc.getMultipleAccounts(listOf(configAddress, rigAddress, automationAddress, Ore.BOARD, skrAddress))
        val configInfo = read[0] ?: return null
        val rig = read[1]?.let { HeadsDownAccounts.rig(rigAddress, it) }

        // A Focus Bond lives on the rig's current (or last) shift: release it here if it is due.
        var previousBond: FocusBondAccount? = null
        var previousLog: ShiftLogAccount? = null
        if (rig != null && rig.shiftId > 0uL) {
            val bondAddress = HeadsDownProgram.focusBond(rigAddress, rig.shiftId).address
            val logAddress = HeadsDownProgram.shiftLog(rigAddress, rig.shiftId).address
            val (bondInfo, logInfo) = rpc.getMultipleAccounts(listOf(bondAddress, logAddress))
            previousBond = bondInfo?.let { HeadsDownAccounts.focusBond(bondAddress, it) }
            if (previousBond != null) previousLog = logInfo?.let { HeadsDownAccounts.shiftLog(logAddress, it) }
        }

        // The blockhash's context slot dates the voucher expiry check (expiry_slot > Clock.slot).
        val blockhash = rpc.getLatestBlockhash()
        val state = ClockInChainState(
            config = HeadsDownAccounts.config(configAddress, configInfo),
            rig = rig,
            automation = read[2]?.let { OreAccounts.automation(automationAddress, it) },
            slot = blockhash.contextSlot.takeIf { it >= 0 }?.toULong(),
            boardRoundId = read[3]?.let { OreAccounts.board(Ore.BOARD, it).roundId },
            skrBalance = SplTokenAccounts.userBalance(read[4], Skr.MINT, authority),
            skrAccountExists = read[4] != null,
            previousBond = previousBond,
            previousShiftLog = previousLog,
        )
        val version = if (capabilities.supportsV0) TxVersion.V0 else TxVersion.LEGACY
        val now = nowUnix()
        var plan = ClockInComposer.compose(authority, rigKey, request, state, now, voucher)
        var message = TransactionBuilder.compile(authority, plan.instructions, blockhash.blockhash, version)
        var bondDeferred = false
        if (!TransactionBuilder.fits(message) && request.focusBondSkr > 0uL) {
            // Arming the shift comes first: the bond is locked right after, in its own transaction.
            plan = ClockInComposer.compose(authority, rigKey, request.copy(focusBondSkr = 0uL), state, now, voucher)
            message = TransactionBuilder.compile(authority, plan.instructions, blockhash.blockhash, version)
            bondDeferred = true
        }
        return PreparedClockIn(TransactionBuilder.unsignedTransaction(message), blockhash.lastValidBlockHeight, authority, plan, version, bondDeferred)
    }

    /** The authority's Rig (checked decode), or null if it does not exist. */
    suspend fun readRig(authority: Pubkey): RigAccount? {
        val address = HeadsDownProgram.rig(authority).address
        return rpc.getAccountInfo(address)?.let { HeadsDownAccounts.rig(address, it) }
    }
}
