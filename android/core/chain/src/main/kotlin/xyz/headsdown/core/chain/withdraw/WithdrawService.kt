package xyz.headsdown.core.chain.withdraw

import kotlinx.coroutines.CancellationException
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.accounts.HeadsDownAccounts
import xyz.headsdown.core.chain.accounts.OreAccounts
import xyz.headsdown.core.chain.accounts.ShiftLogAccount
import xyz.headsdown.core.chain.accounts.ifCreated
import xyz.headsdown.core.chain.ix.ComputeBudgetInstructions
import xyz.headsdown.core.chain.rpc.RpcProtocolException
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.chain.tx.TransactionBuilder
import xyz.headsdown.core.chain.tx.TxVersion
import xyz.headsdown.core.wallet.PreparedTransactions
import xyz.headsdown.core.wallet.WalletCapabilities
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    /**
     * The chain state a withdrawal for [authority] depends on, through the checked decoders.
     * [withLogs] also reads the shift logs. That part is extra: whatever goes wrong in it (a node
     * that refuses the read, a log that does not decode) leaves the logs out and nothing else, so
     * it can never stand between the wallet and its SOL.
     */
    suspend fun read(authority: Pubkey, withLogs: Boolean = true): WithdrawChainState {
        val rigAddress = HeadsDownProgram.rig(authority).address
        val automationAddress = Ore.automation(authority).address
        val read = rpc.getMultipleAccounts(listOf(rigAddress, automationAddress))
        val slot = HeadsDownAccounts.rigSlot(rigAddress, read[0])
        val rig = slot.rigOrNull
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
        // A closed rig's tombstone keeps the shift counter, so its logs can still be found.
        val lastShiftId = rig?.takeIf { it.authority == authority }?.shiftId ?: slot.tombstoneOrNull?.shiftId ?: 0uL
        var logs = emptyList<SealedLog>()
        var clusterUnix: Long? = null
        if (withLogs && lastShiftId > 0uL) {
            try {
                val found = readLogs(rigAddress, lastShiftId, authority)
                // Only when a log looks due is the cluster's clock worth a read; without it none is called ripe.
                if (found.any { nowUnix() >= it.endTs + WithdrawComposer.LOG_TTL_SECONDS }) clusterUnix = clusterTime()
                logs = found
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                logs = emptyList()
            }
        }
        return WithdrawChainState(
            automation = automation,
            automationLamports = automationInfo?.lamports ?: 0uL,
            rig = rig,
            rigLamports = if (rig != null) read[0]?.lamports ?: 0uL else 0uL,
            bondHeld = bondHeld,
            tombstoneRent = tombstoneRent,
            logs = logs,
            clusterUnix = clusterUnix,
        )
    }

    /** The time a log's 30 days are judged by: the phone's clock, and never later than the cluster's. */
    private fun ripeAt(state: WithdrawChainState): Long = state.clusterUnix?.let { minOf(it, nowUnix()) } ?: nowUnix()

    /** `Clock.unix_timestamp`, from the sysvar the program itself reads. */
    private suspend fun clusterTime(): Long {
        val clock = rpc.getAccountInfo(WellKnown.CLOCK_SYSVAR) ?: throw RpcProtocolException("no Clock sysvar")
        val data = clock.data
        if (data.size < CLOCK_UNIX_TIMESTAMP_OFFSET + 8) throw RpcProtocolException("Clock sysvar is too short")
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getLong(CLOCK_UNIX_TIMESTAMP_OFFSET)
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
                // An account at a log's address that does not decode as one is left out, not fatal.
                val log = runCatching { HeadsDownAccounts.shiftLog(addresses[i], account) }.getOrNull() ?: return@forEachIndexed
                found += log to account.lamports
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
            logRent = if (own) WithdrawComposer.logRent(state, ripeAt(state)) else null,
        )
    }

    /** Builds the withdrawal for [authority] from a fresh read. Null: nothing to sign. */
    suspend fun prepare(authority: Pubkey, request: WithdrawRequest, capabilities: WalletCapabilities): PreparedWithdraw? {
        // The logs are read only when their rent was asked for: taking the SOL back never waits on them.
        val state = read(authority, withLogs = request.closeLogs)
        val plan = WithdrawComposer.compose(authority, request, state, ripeAt(state))
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

        const val CLOCK_UNIX_TIMESTAMP_OFFSET = 32
    }
}
