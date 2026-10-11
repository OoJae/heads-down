package xyz.headsdown.core.chain.withdraw

import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.accounts.HeadsDownAccounts
import xyz.headsdown.core.chain.accounts.OreAccounts
import xyz.headsdown.core.chain.accounts.ShiftLogAccount
import xyz.headsdown.core.chain.accounts.ifCreated
import xyz.headsdown.core.chain.ix.ComputeBudgetInstructions
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.chain.tx.TransactionBuilder
import xyz.headsdown.core.chain.tx.TxVersion
import xyz.headsdown.core.wallet.PreparedTransactions
import xyz.headsdown.core.wallet.WalletCapabilities

/** What the wallet could take back, read before anything is signed. */
class WithdrawPreview(
    val state: WithdrawChainState,
    /** What closing the ORE Automation would hand back; null: there is none. */
    val revoke: Revoke?,
    /** What closing the rig would hand back; null: there is no rig, or it cannot be closed now. */
    val rigClose: RigClose?,
    /** Why the rig cannot be closed now; null when it can, or when there is no rig. */
    val closeBlock: CloseBlock?,
    /** The rent the rig's shift logs hold for the wallet; null when there is no such log. */
    val logRent: LogRent? = null,
) {
    val hasRig: Boolean get() = state.rig != null
    val shiftOpen: Boolean get() = state.rig?.shiftOpen == true
}

/** The withdrawal transaction, serialized for MWA, plus what it will do once confirmed. */
class PreparedWithdraw(
    transaction: ByteArray,
    lastValidBlockHeight: Long,
    val authority: Pubkey,
    val plan: WithdrawPlan,
) : PreparedTransactions(listOf(transaction), lastValidBlockHeight)

/**
 * Reads what a withdrawal depends on, composes it ([WithdrawComposer]) and serializes one
 * transaction for the wallet's `signAndSendTransactions`.
 */
class WithdrawService(
    private val rpc: SolanaJsonRpc,
    private val nowUnix: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    /** The chain state a withdrawal for [authority] depends on, through the checked decoders. */
    suspend fun read(authority: Pubkey): WithdrawChainState {
        val rigAddress = HeadsDownProgram.rig(authority).address
        val automationAddress = Ore.automation(authority).address
        val read = rpc.getMultipleAccounts(listOf(rigAddress, automationAddress))
        val rig = HeadsDownAccounts.rigOrNull(rigAddress, read[0])
        val automationInfo = read[1].ifCreated()
        val automation = automationInfo?.let { OreAccounts.automation(automationAddress, it) }

        // A Focus Bond lives on the rig's current (or last) shift; shift 0 means "never armed".
        var bondHeld = false
        var tombstoneRent = 0uL
        if (rig != null) {
            if (rig.shiftId > 0uL) {
                val bondAddress = HeadsDownProgram.focusBond(rigAddress, rig.shiftId).address
                bondHeld = rpc.getAccountInfo(bondAddress).ifCreated()?.let { HeadsDownAccounts.focusBond(bondAddress, it) } != null
            }
            tombstoneRent = rpc.getMinimumBalanceForRentExemption(HeadsDownAccounts.RIG_TOMBSTONE_SIZE)
        }
        val logs = if (rig != null && rig.authority == authority) readLogs(rigAddress, rig.shiftId, authority) else emptyList()
        return WithdrawChainState(
            automation = automation,
            automationLamports = automationInfo?.lamports ?: 0uL,
            rig = rig,
            rigLamports = if (rig != null) read[0]?.lamports ?: 0uL else 0uL,
            bondHeld = bondHeld,
            tombstoneRent = tombstoneRent,
            logs = logs,
        )
    }

    /**
     * The logs of the rig's sealed shifts. Shift ids run from 1 to the rig's current one, and a
     * log's address is derived from its id, so they are read by address: the most recent
     * [MAX_LOGS_READ] of them. For a log past its 30 days the shift's Focus Bond is looked up as
     * well, because the program keeps a log while its bond is there.
     */
    private suspend fun readLogs(rigAddress: Pubkey, lastShiftId: ULong, authority: Pubkey): List<SealedLog> {
        if (lastShiftId == 0uL) return emptyList()
        val first = if (lastShiftId > MAX_LOGS_READ.toULong()) lastShiftId - MAX_LOGS_READ.toULong() + 1uL else 1uL
        val ids = generateSequence(first) { if (it < lastShiftId) it + 1uL else null }.toList()
        val now = nowUnix()
        val found = mutableListOf<Pair<ShiftLogAccount, ULong>>()
        for (chunk in ids.chunked(ACCOUNTS_PER_READ)) {
            val addresses = chunk.map { HeadsDownProgram.shiftLog(rigAddress, it).address }
            rpc.getMultipleAccounts(addresses).forEachIndexed { i, info ->
                val account = info.ifCreated() ?: return@forEachIndexed
                found += HeadsDownAccounts.shiftLog(addresses[i], account) to account.lamports
            }
        }
        val due = found.filter { (log, _) -> now >= log.endTs + WithdrawComposer.LOG_TTL_SECONDS }.map { it.first.shiftId }
        val bonded = mutableSetOf<ULong>()
        for (chunk in due.chunked(ACCOUNTS_PER_READ)) {
            rpc.getMultipleAccounts(chunk.map { HeadsDownProgram.focusBond(rigAddress, it).address }).forEachIndexed { i, info ->
                if (info.ifCreated() != null) bonded += chunk[i]
            }
        }
        return found.map { (log, lamports) ->
            SealedLog(log.shiftId, lamports, log.endTs, paidByWallet = log.rentGoesTo(authority), bondHeld = log.shiftId in bonded)
        }
    }

    /** What the screen shows before anything is signed. Read-only. */
    suspend fun preview(authority: Pubkey): WithdrawPreview {
        val state = read(authority)
        // Another wallet's rig is never offered for closing (the composer refuses it as well).
        val own = state.rig == null || state.rig.authority == authority
        return WithdrawPreview(
            state = state,
            revoke = WithdrawComposer.revoke(authority, state),
            rigClose = if (own) WithdrawComposer.rigClose(state) else null,
            closeBlock = if (own) WithdrawComposer.closeBlock(state) else null,
            logRent = if (own) WithdrawComposer.logRent(state, nowUnix()) else null,
        )
    }

    /** Builds the withdrawal for [authority] from a fresh read. Null: nothing to sign. */
    suspend fun prepare(authority: Pubkey, request: WithdrawRequest, capabilities: WalletCapabilities): PreparedWithdraw? {
        val plan = WithdrawComposer.compose(authority, request, read(authority), nowUnix())
        if (plan.isEmpty) return null
        val blockhash = rpc.getLatestBlockhash()
        val version = if (capabilities.supportsV0) TxVersion.V0 else TxVersion.LEGACY
        val instructions = if (request.priorityMicroLamports == 0uL) {
            plan.instructions
        } else {
            listOf(
                ComputeBudgetInstructions.setComputeUnitLimit(
                    WithdrawComposer.COMPUTE_UNITS + WithdrawComposer.COMPUTE_UNITS_PER_LOG * plan.logsClosed.size,
                ),
                ComputeBudgetInstructions.setComputeUnitPrice(request.priorityMicroLamports),
            ) + plan.instructions
        }
        val message = TransactionBuilder.compile(authority, instructions, blockhash.blockhash, version)
        return PreparedWithdraw(TransactionBuilder.unsignedTransaction(message), blockhash.lastValidBlockHeight, authority, plan)
    }

    private companion object {
        /** A rig that worked every night for a year has 365 logs; older ones wait for a later visit. */
        const val MAX_LOGS_READ = 400

        /** `getMultipleAccounts` takes at most 100 addresses. */
        const val ACCOUNTS_PER_READ = 100
    }
}
