package xyz.headsdown.core.chain.swap

import kotlinx.coroutines.CancellationException
import xyz.headsdown.core.chain.AssociatedToken
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.accounts.AccountLayoutException
import xyz.headsdown.core.chain.accounts.AddressLookupTables
import xyz.headsdown.core.chain.accounts.SplTokenAccounts
import xyz.headsdown.core.chain.ix.ComputeBudgetInstructions
import xyz.headsdown.core.chain.rpc.AccountInfo
import xyz.headsdown.core.chain.rpc.LatestBlockhash
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.chain.tx.AddressLookupTable
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.chain.tx.TransactionBuilder
import xyz.headsdown.core.chain.tx.TxVersion
import java.io.IOException

/** Why a swap is not in what the wallet signs. Every one of them means: nothing is swapped. */
enum class SwapSkip {
    /** This build or cluster has no swap provider. */
    NOT_AVAILABLE,

    /** The provider has no route for this pair and size. */
    NO_QUOTE,

    /** A fresh quote guarantees less than the user agreed to. */
    PRICE_MOVED,

    /** Unreachable, malformed, or an answer the guard refused. */
    PROVIDER_FAILED,

    /** The wallet signs legacy transactions only; the route needs address lookup tables (v0). */
    NEEDS_V0,

    /** A lookup table the route names is missing, deactivated or not a table. */
    TABLE_UNAVAILABLE,

    /** Even alone and with its tables, the swap does not fit one transaction. */
    DOES_NOT_FIT,

    /** The cluster says the swap would fail right now. */
    SIMULATION_FAILED,

    /** Simulated, the swap delivers less than its minimum to the user's account. */
    DELIVERS_TOO_LITTLE,

    /** Simulated, the swap takes more from the user than the amount being sold (plus fees). */
    TAKES_TOO_MUCH,

    /** The wallet signs one transaction per request, and the swap needs its own. */
    ONE_TRANSACTION_ONLY,
}

/** A swap that passed every check and can be put into a transaction. */
class SwapLeg(
    val quote: SwapQuote,
    /** Setup, swap and cleanup, in order. No compute-budget instructions. */
    val instructions: List<Instruction>,
    val tables: List<AddressLookupTable>,
    /** Compute units to budget for these instructions. */
    val computeUnits: Long,
)

sealed interface SwapLegResult {
    class Ready(val leg: SwapLeg) : SwapLegResult
    data class Skipped(val reason: SwapSkip) : SwapLegResult
}

/**
 * Turns a [SwapRequest] into instructions the app is willing to show a wallet, or says why not.
 * In order: a fresh quote that still guarantees what the user agreed to; the provider's validated
 * instructions; the lookup tables they need, read from the cluster and checked; the swap alone
 * compiled into a v0 transaction; and that transaction **simulated**, with the user's balances
 * compared before and after:
 *
 * - the output account must gain at least the quote's minimum;
 * - the input must not lose more than the amount being sold (plus [MAX_LAMPORT_OVERHEAD] of
 *   fees and account rent when SOL is involved).
 *
 * The checks do not depend on how the provider routes: they look at what the transaction does to
 * the user's own accounts. Any failure is a [SwapLegResult.Skipped]: fail closed.
 */
class SwapLegBuilder(
    private val rpc: SolanaJsonRpc,
    private val provider: SwapProvider,
) {
    /**
     * @param minOut the least output the user agreed to (from the quote they were shown).
     * @param blockhash the blockhash the final transaction will use (the simulation uses it too).
     */
    suspend fun build(
        user: Pubkey,
        request: SwapRequest,
        minOut: ULong,
        blockhash: LatestBlockhash,
        supportsV0: Boolean,
    ): SwapLegResult {
        if (!supportsV0) return SwapLegResult.Skipped(SwapSkip.NEEDS_V0)
        val quote = try {
            provider.quote(request) ?: return SwapLegResult.Skipped(SwapSkip.NO_QUOTE)
        } catch (e: CancellationException) {
            throw e
        } catch (_: SwapProviderException) {
            return SwapLegResult.Skipped(SwapSkip.PROVIDER_FAILED)
        }
        if (quote.request != request) return SwapLegResult.Skipped(SwapSkip.PROVIDER_FAILED)
        if (quote.minOutAmount < minOut) return SwapLegResult.Skipped(SwapSkip.PRICE_MOVED)
        val swap = try {
            provider.instructions(quote, user)
        } catch (e: CancellationException) {
            throw e
        } catch (_: SwapProviderException) {
            return SwapLegResult.Skipped(SwapSkip.PROVIDER_FAILED)
        }
        // Whatever the provider says, only the user may be asked to sign.
        if (swap.all.any { ix -> ix.accounts.any { it.isSigner && it.pubkey != user } }) return SwapLegResult.Skipped(SwapSkip.PROVIDER_FAILED)
        if (swap.all.any { it.programId == WellKnown.COMPUTE_BUDGET }) return SwapLegResult.Skipped(SwapSkip.PROVIDER_FAILED)

        // The lookup tables and the user's balances before, in one read.
        val watched = watchedAccounts(user, request)
        val read = try {
            rpc.getMultipleAccounts(swap.lookupTables + watched)
        } catch (_: IOException) {
            return SwapLegResult.Skipped(SwapSkip.TABLE_UNAVAILABLE)
        }
        val slot = blockhash.contextSlot.takeIf { it >= 0 }?.toULong()
        val tables = swap.lookupTables.mapIndexed { i, address ->
            val info = read[i] ?: return SwapLegResult.Skipped(SwapSkip.TABLE_UNAVAILABLE)
            try {
                AddressLookupTables.decode(address, info, slot)
            } catch (_: AccountLayoutException) {
                return SwapLegResult.Skipped(SwapSkip.TABLE_UNAVAILABLE)
            }
        }
        val before = read.drop(swap.lookupTables.size)

        val units = (swap.computeUnitLimit?.let { it + it / 5 } ?: DEFAULT_SWAP_UNITS).coerceIn(MIN_SWAP_UNITS, ComputeBudgetInstructions.MAX_UNITS)
        val alone = listOf(ComputeBudgetInstructions.setComputeUnitLimit(units)) + swap.all
        val message = TransactionBuilder.compile(user, alone, blockhash.blockhash, TxVersion.V0, tables)
        if (!TransactionBuilder.fits(message)) return SwapLegResult.Skipped(SwapSkip.DOES_NOT_FIT)

        val simulated = try {
            rpc.simulateTransaction(TransactionBuilder.unsignedTransaction(message), watched)
        } catch (_: IOException) {
            return SwapLegResult.Skipped(SwapSkip.SIMULATION_FAILED)
        }
        if (!simulated.succeeded) return SwapLegResult.Skipped(SwapSkip.SIMULATION_FAILED)
        val verdict = try {
            judge(user, request, quote, before, simulated.accounts)
        } catch (_: AccountLayoutException) {
            SwapSkip.SIMULATION_FAILED
        }
        if (verdict != null) return SwapLegResult.Skipped(verdict)
        return SwapLegResult.Ready(SwapLeg(quote, swap.all, tables, units))
    }

    /** The user's wallet, then the token accounts of the non-SOL sides (input first). */
    private fun watchedAccounts(user: Pubkey, request: SwapRequest): List<Pubkey> = buildList {
        add(user)
        if (request.inputMint != WellKnown.WRAPPED_SOL_MINT) add(AssociatedToken.address(user, request.inputMint).address)
        if (request.outputMint != WellKnown.WRAPPED_SOL_MINT) add(AssociatedToken.address(user, request.outputMint).address)
    }

    /** null: the simulated transaction did what the quote promised to the user's own accounts. */
    private fun judge(user: Pubkey, request: SwapRequest, quote: SwapQuote, before: List<AccountInfo?>, after: List<AccountInfo?>): SwapSkip? {
        if (after.size != before.size) return SwapSkip.SIMULATION_FAILED
        val lamportsBefore = before[0]?.lamports ?: 0uL
        val lamportsAfter = after[0]?.lamports ?: 0uL
        var next = 1
        val solIn = request.inputMint == WellKnown.WRAPPED_SOL_MINT
        val solOut = request.outputMint == WellKnown.WRAPPED_SOL_MINT

        if (solIn) {
            val spent = if (lamportsBefore > lamportsAfter) lamportsBefore - lamportsAfter else 0uL
            if (spent > request.inAmount + MAX_LAMPORT_OVERHEAD) return SwapSkip.TAKES_TOO_MUCH
        } else {
            val had = SplTokenAccounts.userBalance(before[next], request.inputMint, user)
            val has = SplTokenAccounts.userBalance(after[next], request.inputMint, user)
            next++
            val spent = if (had > has) had - has else 0uL
            if (spent > request.inAmount) return SwapSkip.TAKES_TOO_MUCH
            // A token sale may cost fees and rent in SOL, never more.
            if (!solOut && lamportsBefore > lamportsAfter && lamportsBefore - lamportsAfter > MAX_LAMPORT_OVERHEAD) return SwapSkip.TAKES_TOO_MUCH
        }

        if (solOut) {
            // Unwrapped SOL lands in the wallet, less fees: at least the minimum minus the overhead.
            val floor = if (quote.minOutAmount > MAX_LAMPORT_OVERHEAD) quote.minOutAmount - MAX_LAMPORT_OVERHEAD else 0uL
            val gained = if (lamportsAfter > lamportsBefore) lamportsAfter - lamportsBefore else 0uL
            if (gained < floor) return SwapSkip.DELIVERS_TOO_LITTLE
        } else {
            val had = SplTokenAccounts.userBalance(before[next], request.outputMint, user)
            val has = SplTokenAccounts.userBalance(after[next], request.outputMint, user)
            val gained = if (has > had) has - had else 0uL
            if (gained < quote.minOutAmount) return SwapSkip.DELIVERS_TOO_LITTLE
        }
        return null
    }

    companion object {
        /**
         * SOL a swap may cost on top of the amount sold: the transaction fee, a priority fee, and
         * the rent of the output token account when it has to be created (0.00204 SOL).
         */
        const val MAX_LAMPORT_OVERHEAD: ULong = 5_000_000uL
        const val DEFAULT_SWAP_UNITS = 1_000_000L
        const val MIN_SWAP_UNITS = 200_000L
    }
}
