package xyz.headsdown.core.chain.clockout

import kotlinx.coroutines.CancellationException
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.Skr
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.accounts.FocusBondAccount
import xyz.headsdown.core.chain.accounts.HeadsDownAccounts
import xyz.headsdown.core.chain.accounts.OreAccounts
import xyz.headsdown.core.chain.accounts.OreClaimEstimate
import xyz.headsdown.core.chain.accounts.OreClaimMath
import xyz.headsdown.core.chain.accounts.ShiftLogAccount
import xyz.headsdown.core.chain.accounts.SplTokenAccounts
import xyz.headsdown.core.chain.ix.ComputeBudgetInstructions
import xyz.headsdown.core.chain.rpc.RpcProtocolException
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.chain.swap.SwapLeg
import xyz.headsdown.core.chain.swap.SwapLegBuilder
import xyz.headsdown.core.chain.swap.SwapLegResult
import xyz.headsdown.core.chain.swap.SwapProvider
import xyz.headsdown.core.chain.swap.SwapProviderException
import xyz.headsdown.core.chain.swap.SwapQuote
import xyz.headsdown.core.chain.swap.SwapRequest
import xyz.headsdown.core.chain.swap.SwapSkip
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.chain.tx.TransactionBuilder
import xyz.headsdown.core.chain.tx.TxVersion
import xyz.headsdown.core.wallet.PreparedTransactions
import xyz.headsdown.core.wallet.WalletCapabilities
import java.io.IOException

/** What became of the "buy the rest at market" leg. */
sealed interface BuyOutcome {
    data object NotRequested : BuyOutcome

    /** In what the wallet signs. [ownTransaction]: it did not fit beside the clock-out and is a second transaction. */
    data class Included(val quote: SwapQuote, val ownTransaction: Boolean) : BuyOutcome

    /** Not in what the wallet signs: nothing is bought. */
    data class Skipped(val reason: SwapSkip) : BuyOutcome
}

/** What the wallet's ORE Miner holds, and what a clock-out would do with the open shift and bond. */
class ClockOutPreview(
    val state: ClockOutChainState,
    /** What happens with the default request (keep unrefined, do not end early). */
    val plan: ClockOutPlan,
    /** Refined ORE: claimable without a fee. Atoms. */
    val refinedOre: ULong,
    /** Unrefined ORE: claiming it costs ORE's 10% refining fee. Atoms. */
    val unrefinedOre: ULong,
    /** What claiming everything now would deliver and cost. */
    val fullClaim: OreClaimEstimate?,
    /** SKR in the wallet's token account. */
    val skrBalance: ULong,
)

/** The clock-out transaction(s), serialized for MWA, plus what they will do once confirmed. */
class PreparedClockOut(
    transactions: List<ByteArray>,
    lastValidBlockHeight: Long,
    val authority: Pubkey,
    val plan: ClockOutPlan,
    val buy: BuyOutcome,
) : PreparedTransactions(transactions, lastValidBlockHeight)

/**
 * Reads what a clock-out depends on, composes it ([ClockOutComposer]), adds the optional buy leg
 * ([SwapLegBuilder]) and serializes for the wallet's `signAndSendTransactions`.
 *
 * **One transaction** when everything fits: `[ComputeBudget] end_shift? release_focus_bond?
 * claim_sol? claim_ore? [swap setup, swap, cleanup]` (v0 with the swap's lookup tables). When the
 * swap does not fit beside the rest it becomes a **second transaction** in the same wallet
 * approval; when it cannot be built, checked or simulated it is left out and the clock-out goes
 * ahead without it. The buy is never what makes a clock-out fail, and never happens unchecked.
 */
class ClockOutService(
    private val rpc: SolanaJsonRpc,
    /** null: no swap provider on this build or cluster (the buy leg is unavailable). */
    private val swap: SwapProvider? = null,
    private val nowUnix: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    val buyAvailable: Boolean get() = swap != null

    /** The chain state a clock-out for [authority] depends on, through the checked decoders. */
    suspend fun read(authority: Pubkey): ClockOutChainState = readWithSkr(authority).first

    private suspend fun readWithSkr(authority: Pubkey): Pair<ClockOutChainState, ULong> {
        val rigAddress = HeadsDownProgram.rig(authority).address
        val minerAddress = Ore.miner(authority).address
        val skrAddress = Skr.account(authority)
        val first = rpc.getMultipleAccounts(listOf(rigAddress, Ore.BOARD, minerAddress, Ore.TREASURY, skrAddress))
        val rig = first[0]?.let { HeadsDownAccounts.rig(rigAddress, it) }
        val board = OreAccounts.board(Ore.BOARD, first[1] ?: throw RpcProtocolException("no ORE Board on this cluster"))
        val miner = first[2]?.let { OreAccounts.miner(minerAddress, it) }
        val treasury = first[3]?.let { OreAccounts.treasury(Ore.TREASURY, it) }
        val skr = SplTokenAccounts.userBalance(first[4], Skr.MINT, authority)

        // A Focus Bond lives on the rig's current (or last) shift; shift 0 means "never armed".
        var bond: FocusBondAccount? = null
        var log: ShiftLogAccount? = null
        if (rig != null && rig.shiftId > 0uL) {
            val bondAddress = HeadsDownProgram.focusBond(rigAddress, rig.shiftId).address
            val logAddress = HeadsDownProgram.shiftLog(rigAddress, rig.shiftId).address
            val second = rpc.getMultipleAccounts(listOf(bondAddress, logAddress))
            bond = second[0]?.let { HeadsDownAccounts.focusBond(bondAddress, it) }
            log = if (bond != null) second[1]?.let { HeadsDownAccounts.shiftLog(logAddress, it) } else null
        }
        return ClockOutChainState(rig, board, miner, treasury, bond, log, skrAccountExists = first[4] != null) to skr
    }

    /** What the reveal shows before anything is signed. Read-only. */
    suspend fun preview(authority: Pubkey): ClockOutPreview {
        val (state, skr) = readWithSkr(authority)
        val miner = state.miner
        val treasury = state.treasury
        return ClockOutPreview(
            state = state,
            plan = ClockOutComposer.compose(authority, ClockOutRequest(), state, nowUnix()),
            refinedOre = if (miner != null && treasury != null) OreClaimMath.refined(miner, treasury) else 0uL,
            unrefinedOre = miner?.rewardsOre ?: 0uL,
            fullClaim = if (miner != null && treasury != null) OreClaimMath.estimate(miner, treasury, Ore.DENOMINATOR_BPS) else null,
            skrBalance = skr,
        )
    }

    /**
     * A quote for buying ORE with [spendLamports] of SOL, to show before signing. Null: no
     * provider, no route, or the provider failed. In every one of those cases there is no buy.
     */
    suspend fun quoteBuy(spendLamports: ULong, slippageBps: Int = SwapRequest.DEFAULT_SLIPPAGE_BPS): SwapQuote? {
        val provider = swap ?: return null
        return try {
            provider.quote(SwapRequest(WellKnown.WRAPPED_SOL_MINT, Ore.MINT, spendLamports, slippageBps))
        } catch (e: CancellationException) {
            throw e
        } catch (_: SwapProviderException) {
            null
        }
    }

    /**
     * Builds the clock-out for [authority]. Returns null when there is nothing to sign: no open
     * shift to end, nothing to claim, no bond to release and no buy.
     */
    suspend fun prepare(authority: Pubkey, request: ClockOutRequest, capabilities: WalletCapabilities): PreparedClockOut? {
        val state = read(authority)
        val plan = ClockOutComposer.compose(authority, request, state, nowUnix())
        val blockhash = rpc.getLatestBlockhash()
        val version = if (capabilities.supportsV0) TxVersion.V0 else TxVersion.LEGACY

        val leg: SwapLegResult? = request.buy?.let { buy ->
            val provider = swap ?: return@let SwapLegResult.Skipped(SwapSkip.NOT_AVAILABLE)
            try {
                SwapLegBuilder(rpc, provider).build(
                    user = authority,
                    request = SwapRequest(WellKnown.WRAPPED_SOL_MINT, Ore.MINT, buy.spendLamports, buy.slippageBps),
                    minOut = buy.minOreAtoms,
                    blockhash = blockhash,
                    supportsV0 = capabilities.supportsV0,
                )
            } catch (_: IllegalArgumentException) {
                SwapLegResult.Skipped(SwapSkip.PROVIDER_FAILED)
            }
        }
        val ready = (leg as? SwapLegResult.Ready)?.leg
        var buy: BuyOutcome = when (leg) {
            null -> BuyOutcome.NotRequested
            is SwapLegResult.Skipped -> BuyOutcome.Skipped(leg.reason)
            is SwapLegResult.Ready -> BuyOutcome.Included(leg.leg.quote, ownTransaction = false)
        }

        val own = budgeted(plan.instructions, ClockOutComposer.COMPUTE_UNITS, request.priorityMicroLamports, always = false)
        val transactions = mutableListOf<ByteArray>()
        if (ready == null) {
            if (plan.isEmpty) return null
            transactions += TransactionBuilder.unsignedTransaction(TransactionBuilder.compile(authority, own, blockhash.blockhash, version))
        } else {
            val merged = merged(authority, plan, ready, request.priorityMicroLamports, blockhash.blockhash)
            if (merged != null) {
                transactions += merged
            } else {
                // Beside the clock-out it does not fit (or would fail): its own transaction, or none.
                val twoAllowed = capabilities.maxTransactionsPerRequest == 0 || capabilities.maxTransactionsPerRequest >= 2
                if (!plan.isEmpty) transactions += TransactionBuilder.unsignedTransaction(TransactionBuilder.compile(authority, own, blockhash.blockhash, version))
                if (plan.isEmpty || twoAllowed) {
                    val alone = budgeted(ready.instructions, ready.computeUnits, request.priorityMicroLamports, always = true)
                    transactions += TransactionBuilder.unsignedTransaction(
                        TransactionBuilder.compile(authority, alone, blockhash.blockhash, TxVersion.V0, ready.tables),
                    )
                    buy = BuyOutcome.Included(ready.quote, ownTransaction = !plan.isEmpty)
                } else {
                    buy = BuyOutcome.Skipped(SwapSkip.ONE_TRANSACTION_ONLY)
                }
            }
        }
        if (transactions.isEmpty()) return null
        return PreparedClockOut(transactions, blockhash.lastValidBlockHeight, authority, plan, buy)
    }

    /**
     * The clock-out and the swap as one v0 transaction, or null when that does not fit a packet
     * or the cluster says the combination would fail (the swap alone was already simulated).
     */
    private suspend fun merged(authority: Pubkey, plan: ClockOutPlan, leg: SwapLeg, priority: ULong, blockhash: ByteArray): ByteArray? {
        if (plan.isEmpty) return null
        val units = (ClockOutComposer.COMPUTE_UNITS + leg.computeUnits).coerceAtMost(ComputeBudgetInstructions.MAX_UNITS)
        val all = budgeted(plan.instructions + leg.instructions, units, priority, always = true)
        val message = TransactionBuilder.compile(authority, all, blockhash, TxVersion.V0, leg.tables)
        if (!TransactionBuilder.fits(message)) return null
        val tx = TransactionBuilder.unsignedTransaction(message)
        val simulated = try {
            rpc.simulateTransaction(tx)
        } catch (_: IOException) {
            return null
        }
        return tx.takeIf { simulated.succeeded }
    }

    /** [instructions] behind a compute-unit limit (and a price when a priority fee is set). */
    private fun budgeted(instructions: List<Instruction>, units: Long, priority: ULong, always: Boolean): List<Instruction> {
        if (instructions.isEmpty() || (!always && priority == 0uL)) return instructions
        return buildList {
            add(ComputeBudgetInstructions.setComputeUnitLimit(units))
            if (priority > 0uL) add(ComputeBudgetInstructions.setComputeUnitPrice(priority))
            addAll(instructions)
        }
    }
}
