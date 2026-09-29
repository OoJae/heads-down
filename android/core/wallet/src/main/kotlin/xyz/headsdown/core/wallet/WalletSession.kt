package xyz.headsdown.core.wallet

/**
 * What the connected wallet can sign, from MWA `get_capabilities`.
 *
 * The transaction version is chosen from this (THREAT_MODEL §8, "Transaction UX"): v0 when the
 * wallet lists version `0`, otherwise legacy.
 */
data class WalletCapabilities(
    val supportsLegacy: Boolean,
    val supportsV0: Boolean,
    /** 0 = the wallet did not state a limit. */
    val maxTransactionsPerRequest: Int,
) {
    companion object {
        /** What every MWA wallet supports; used when `get_capabilities` fails or says nothing. */
        val LEGACY_ONLY = WalletCapabilities(supportsLegacy = true, supportsV0 = false, maxTransactionsPerRequest = 0)

        /**
         * Parses MWA's `supportedTransactionVersions` (`"legacy"` as a String, versions as
         * Numbers). Unknown entries are ignored; an empty or unusable list means legacy only.
         */
        fun fromMwa(supportedTransactionVersions: Array<out Any?>, maxTransactionsPerRequest: Int): WalletCapabilities {
            var legacy = false
            var v0 = false
            for (entry in supportedTransactionVersions) {
                when (entry) {
                    is String -> if (entry == "legacy") legacy = true else if (entry == "0") v0 = true
                    is Number -> if (entry.toDouble() == 0.0) v0 = true
                }
            }
            if (!legacy && !v0) legacy = true
            return WalletCapabilities(legacy, v0, maxTransactionsPerRequest.coerceAtLeast(0))
        }
    }
}

/** Serialized, unsigned transactions built inside one wallet session. */
open class PreparedTransactions(
    transactions: List<ByteArray>,
    /** From the `getLatestBlockhash` the transactions were built against. */
    val lastValidBlockHeight: Long,
) {
    val transactions: List<ByteArray> = transactions.map { it.copyOf() }

    init {
        require(this.transactions.isNotEmpty()) { "nothing to sign" }
        require(lastValidBlockHeight > 0) { "lastValidBlockHeight must come from getLatestBlockhash" }
    }
}

/** Outcome of [HeadsDownWallet.signAndSendInSession]. */
sealed interface WalletSession<out T : PreparedTransactions> {
    val account: WalletAccount

    /** The preparer decided there is nothing to sign (for example a focus-only shift). */
    data class NothingToSign(override val account: WalletAccount) : WalletSession<Nothing>

    /**
     * The wallet signed and sent. [report] says whether each signature was confirmed with
     * `err == null`; only [SubmissionReport.allConfirmed] may be shown as success.
     */
    data class Submitted<T : PreparedTransactions>(
        override val account: WalletAccount,
        val prepared: T,
        val report: SubmissionReport,
    ) : WalletSession<T>
}

/**
 * Wallet failure text shown to the user. MWA builds some messages from arbitrary exception text
 * (`e.message.toString()` on any RuntimeException), which could carry anything. Only the fixed
 * strings MWA itself defines pass through; everything else collapses to a generic message, so
 * no token, key or payload can reach a toast, a crash report or a log line through this path.
 */
object WalletFailures {
    const val GENERIC = "Wallet request failed"

    private val KNOWN = setOf(
        "Auth token invalid",
        "User did not authorize signing",
        "Too many payloads to sign",
        "Remote exception",
        "Transaction payloads invalid",
        "Not all transactions were submitted",
        "IO error while sending operation",
        "Timed out while waiting for result",
        "Timed out waiting to send association intent",
        "Timed out waiting for local association to be ready",
        "Request was interrupted",
        "Request was cancelled",
        "Interrupted while waiting for local association to be ready",
        "Failed establishing local association with wallet",
        "Local association was cancelled before connected",
        "Authorization result contained a non-HTTPS wallet base URI",
        "JSON-RPC client exception",
        "Execution exception",
        "Sign in failed, no sign in result returned by wallet",
        "No compatible wallet found.",
    )

    fun sanitize(message: String?): String = if (message != null && message in KNOWN) message else GENERIC
}
