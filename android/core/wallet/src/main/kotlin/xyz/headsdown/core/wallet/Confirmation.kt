package xyz.headsdown.core.wallet

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

enum class Commitment(val rpcName: String) {
    PROCESSED("processed"),
    CONFIRMED("confirmed"),
    FINALIZED("finalized"),
}

/**
 * One entry of `getSignatureStatuses`. [err] is the raw JSON of `TransactionError`, or null
 * when the transaction executed successfully.
 */
data class SignatureStatus(
    val slot: Long,
    val confirmationStatus: Commitment?,
    val err: String?,
)

/** The two RPC calls the confirmation logic needs. The real client lives behind the team proxy. */
interface SolanaRpc {
    /** `getSignatureStatuses([sig], {searchTransactionHistory: false})`; null when not found. */
    suspend fun getSignatureStatus(signature: String): SignatureStatus?

    /** `getBlockHeight` at [commitment]. */
    suspend fun getBlockHeight(commitment: Commitment): Long
}

/**
 * Stub: no RPC endpoint is configured in this build (API keys never ship in the APK).
 * Every call throws, which the poller reports as [ConfirmationOutcome.RpcUnavailable]:
 * the UI must then say "unknown", never "success".
 */
object UnconfiguredSolanaRpc : SolanaRpc {
    override suspend fun getSignatureStatus(signature: String): SignatureStatus? =
        throw IllegalStateException("Solana RPC not configured")

    override suspend fun getBlockHeight(commitment: Commitment): Long =
        throw IllegalStateException("Solana RPC not configured")
}

sealed interface ConfirmationOutcome {
    val signature: String

    /** The ONLY outcome that may be shown to the user as success. */
    data class Confirmed(override val signature: String, val slot: Long, val commitment: Commitment) : ConfirmationOutcome

    /** Landed on-chain and failed (`err != null`). Never success, whatever the wallet said. */
    data class FailedOnChain(override val signature: String, val slot: Long, val err: String) : ConfirmationOutcome

    /** The blockhash expired (block height passed `lastValidBlockHeight`) and it never landed. */
    data class Expired(
        override val signature: String,
        val lastValidBlockHeight: Long,
        val observedBlockHeight: Long,
    ) : ConfirmationOutcome

    /** We could not find out. Treat as "unknown" and re-check later; never as success. */
    data class RpcUnavailable(override val signature: String, val reason: String) : ConfirmationOutcome

    /** Local wait budget exhausted without expiry evidence (e.g. a stale RPC node). */
    data class TimedOut(override val signature: String, val polls: Int) : ConfirmationOutcome
}

val ConfirmationOutcome.isSuccess: Boolean get() = this is ConfirmationOutcome.Confirmed

/**
 * Polls a transaction signature until it is confirmed with `err == null`, fails on-chain, or
 * provably expires by block height.
 *
 * Expiry rule (the standard one): a transaction built with `lastValidBlockHeight = H` can no
 * longer be included once the cluster's block height exceeds `H`. We only declare
 * [ConfirmationOutcome.Expired] after observing `blockHeight > H` **and** re-checking the
 * status once more, so a transaction that landed right at the boundary is not misreported.
 */
class ConfirmationPoller(
    private val rpc: SolanaRpc,
    private val target: Commitment = Commitment.CONFIRMED,
    private val pollIntervalMillis: Long = 1_000,
    private val maxPolls: Int = 150,
    private val maxConsecutiveRpcFailures: Int = 5,
) {
    init {
        require(target != Commitment.PROCESSED) { "processed is not a confirmation" }
        require(pollIntervalMillis > 0 && maxPolls > 0 && maxConsecutiveRpcFailures > 0)
    }

    suspend fun await(signature: String, lastValidBlockHeight: Long): ConfirmationOutcome {
        var failures = 0
        var lastError = ""
        repeat(maxPolls) { poll ->
            try {
                val status = rpc.getSignatureStatus(signature)
                evaluate(signature, status)?.let { return it }

                val height = rpc.getBlockHeight(Commitment.CONFIRMED)
                if (height > lastValidBlockHeight) {
                    // Final re-check closes the race with a transaction that landed at H.
                    evaluate(signature, rpc.getSignatureStatus(signature))?.let { return it }
                    return ConfirmationOutcome.Expired(signature, lastValidBlockHeight, height)
                }
                failures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                lastError = e.javaClass.simpleName
                if (failures >= maxConsecutiveRpcFailures) {
                    return ConfirmationOutcome.RpcUnavailable(signature, lastError)
                }
            }
            if (poll < maxPolls - 1) delay(pollIntervalMillis)
        }
        return ConfirmationOutcome.TimedOut(signature, maxPolls)
    }

    /** Terminal outcome for [status], or null to keep polling. */
    private fun evaluate(signature: String, status: SignatureStatus?): ConfirmationOutcome? {
        if (status == null) return null
        // An executed-and-failed transaction is final for our purposes at any commitment.
        status.err?.let { return ConfirmationOutcome.FailedOnChain(signature, status.slot, it) }
        val level = status.confirmationStatus ?: return null
        return if (level >= target) ConfirmationOutcome.Confirmed(signature, status.slot, level) else null
    }
}
