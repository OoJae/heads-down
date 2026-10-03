package xyz.headsdown.core.wallet

import androidx.core.net.toUri
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.Blockchain
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.AuthorizationResult
import com.solana.mobilewalletadapter.common.signin.SignInWithSolana
import kotlinx.coroutines.CancellationException

/** A connected wallet account. Public data only. */
data class WalletAccount(val publicKey: ByteArray, val label: String?) {
    val address: String get() = Base58.encode(publicKey)

    override fun equals(other: Any?): Boolean = other is WalletAccount && publicKey.contentEquals(other.publicKey)
    override fun hashCode(): Int = publicKey.contentHashCode()
}

/** SIWS proof to hand to the SIWS verifier (single-use server nonce, 10-minute expiry). */
class SignInProof(val account: WalletAccount, val signedMessage: ByteArray, val signature: ByteArray)

/**
 * Submits a wallet-signed transaction through the app's own RPC and returns its base58
 * signature. Used where the wallet cannot reach the cluster the app talks to (the `localdev`
 * build's local validator): the wallet only signs (`signTransactions`), the app sends.
 */
fun interface TransactionSubmitter {
    suspend fun submit(signedTransaction: ByteArray): String
}

sealed interface WalletResult<out T> {
    data class Success<T>(val value: T) : WalletResult<T>
    data object NoWalletInstalled : WalletResult<Nothing>

    /** User declined, wallet error, timeout. [reason] is a fixed string ([WalletFailures]), never a token. */
    data class Failed(val reason: String) : WalletResult<Nothing>
}

/**
 * Result of submitting transactions. [allConfirmed] is true only when **every** signature was
 * confirmed on-chain with `err == null`; it is the single source of truth for success UI.
 */
data class SubmissionReport(val outcomes: List<ConfirmationOutcome>) {
    val allConfirmed: Boolean get() = outcomes.isNotEmpty() && outcomes.all { it.isSuccess }
}

/**
 * How the dApp identifies itself to the wallet. The site is build configuration
 * (`headsdown.identityUri`), never a constant here: it must be one the team controls, because
 * whoever controls it can present itself to wallets as Heads Down.
 */
object HeadsDownIdentity {
    const val NAME = "Heads Down"

    /** @param identityUri an absolute `https` URL; the icon is resolved relative to it by the wallet. */
    fun connectionIdentity(identityUri: String): ConnectionIdentity {
        val uri = identityUri.toUri()
        require(uri.scheme == "https" && !uri.host.isNullOrEmpty()) { "the identity URI must be an https URL" }
        return ConnectionIdentity(identityUri = uri, iconUri = "favicon.ico".toUri(), identityName = NAME)
    }
}

/**
 * Mobile Wallet Adapter (clientlib-ktx 2.2.0) for Heads Down.
 *
 * - One wallet confirmation per user action; all calls need an [ActivityResultSender] made
 *   in `Activity.onCreate` (MWA cannot run from a Service: see the tile's trampoline).
 * - The auth token is restored from and persisted to [AuthTokenVault]; it is never logged.
 * - [signAndSend] reports success only after [ConfirmationPoller] saw each signature
 *   confirmed with `err == null`. A wallet "sent" response is not success.
 * - With a [submitter] (the `localdev` build), the wallet only signs (`signTransactions`) and the
 *   app submits through its own RPC: an MWA wallet broadcasts to its own cluster, never to a
 *   validator on the laptop. MWA's `Blockchain` has no localnet, so [chain] stays devnet there.
 */
class HeadsDownWallet(
    private val adapter: MobileWalletAdapter,
    private val vault: AuthTokenVault,
    private val poller: ConfirmationPoller,
    private val chain: Blockchain = Solana.Devnet,
    private val submitter: TransactionSubmitter? = null,
) {
    init {
        adapter.blockchain = chain
    }

    suspend fun connect(sender: ActivityResultSender): WalletResult<WalletAccount> {
        restoreToken()
        return when (val result = adapter.connect(sender)) {
            is TransactionResult.Success -> onAuthorized(result.authResult)
            is TransactionResult.NoWalletFound -> WalletResult.NoWalletInstalled
            is TransactionResult.Failure -> failed(result.message)
        }
    }

    /**
     * Sign In With Solana with the fields the registrar issued (`POST /siws/nonce`, registrar N7):
     * domain, URI, statement, version, chain id, nonce, issued-at and expiration are copied
     * verbatim, because the registrar verifies the exact message the wallet signs. The returned
     * proof is verified server-side (`POST /siws/verify`), not trusted locally.
     */
    suspend fun signIn(sender: ActivityResultSender, request: SiwsRequest): WalletResult<SignInProof> {
        restoreToken()
        // 2.2.0 added a String-address overload, so the null address must be typed.
        val payload = SignInWithSolana.Payload(
            request.domain, null as ByteArray?, request.statement, request.uri.toUri(), request.version, request.chainId,
            request.nonce, request.issuedAt, request.expirationTime, null, null, null,
        )
        return when (val result = adapter.signIn(sender, payload)) {
            is TransactionResult.Success -> {
                val account = when (val auth = onAuthorized(result.authResult)) {
                    is WalletResult.Success -> auth.value
                    is WalletResult.Failed -> return auth
                    WalletResult.NoWalletInstalled -> return WalletResult.NoWalletInstalled
                }
                val siws = result.payload
                WalletResult.Success(SignInProof(account, siws.signedMessage, siws.signature))
            }
            is TransactionResult.NoWalletFound -> WalletResult.NoWalletInstalled
            is TransactionResult.Failure -> failed(result.message)
        }
    }

    /**
     * Signs and sends serialized transactions in one wallet session, then waits for on-chain
     * confirmation of each. [lastValidBlockHeight] is the one returned with the blockhash the
     * transactions were built against.
     */
    suspend fun signAndSend(
        sender: ActivityResultSender,
        transactions: List<ByteArray>,
        lastValidBlockHeight: Long,
    ): WalletResult<SubmissionReport> {
        require(transactions.isNotEmpty()) { "nothing to send" }
        restoreToken()
        val result = adapter.transact(sender) {
            if (submitter == null) {
                SignedOrSent.Sent(signAndSendTransactions(transactions.toTypedArray()).signatures.map(Base58::encode))
            } else {
                SignedOrSent.Signed(signTransactions(transactions.toTypedArray()).signedPayloads.toList())
            }
        }
        return when (result) {
            is TransactionResult.Success -> {
                persistToken(result.authResult)
                val signatures = when (val step = result.payload) {
                    is SignedOrSent.Sent -> step.signatures
                    is SignedOrSent.Signed -> submitAll(step.payloads) ?: return WalletResult.Failed(SUBMIT_FAILED)
                }
                WalletResult.Success(SubmissionReport(signatures.map { poller.await(it, lastValidBlockHeight) }))
            }
            is TransactionResult.NoWalletFound -> WalletResult.NoWalletInstalled
            is TransactionResult.Failure -> failed(result.message)
        }
    }

    private sealed interface SignedOrSent {
        class Sent(val signatures: List<String>) : SignedOrSent
        class Signed(val payloads: List<ByteArray>) : SignedOrSent
    }

    /** Submits wallet-signed transactions through the app's RPC; null if any submission failed. */
    private suspend fun submitAll(signed: List<ByteArray>): List<String>? {
        val submit = submitter ?: return null
        return try {
            signed.map { submit.submit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * One wallet association, one approval: authorize (or silently reauthorize), read the
     * wallet's capabilities, let [prepare] build the transactions for the authorized account
     * against a fresh blockhash, then `signAndSendTransactions`. After the session closes, each
     * signature is awaited with [ConfirmationPoller] (`err == null` only).
     *
     * [prepare] returning null means there is nothing to sign ([WalletSession.NothingToSign]).
     * If [prepare] throws (RPC down, unexpected chain state), nothing is sent and the result is
     * [WalletResult.Failed] with [PREPARE_FAILED]; the exception text is never surfaced.
     */
    suspend fun <T : PreparedTransactions> signAndSendInSession(
        sender: ActivityResultSender,
        prepare: suspend (account: WalletAccount, capabilities: WalletCapabilities) -> T?,
    ): WalletResult<WalletSession<T>> {
        restoreToken()
        val result = adapter.transact(sender) { auth ->
            val first = auth.accounts.firstOrNull() ?: return@transact SessionStep.NoAccount
            val account = WalletAccount(first.publicKey, first.accountLabel)
            val capabilities = try {
                getCapabilities().let {
                    WalletCapabilities.fromMwa(it.supportedTransactionVersions, it.maxTransactionsPerSigningRequest)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                WalletCapabilities.LEGACY_ONLY // every MWA wallet signs legacy transactions
            }
            val prepared = try {
                prepare(account, capabilities)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@transact SessionStep.PrepareFailed
            } ?: return@transact SessionStep.Empty(account)
            val limit = capabilities.maxTransactionsPerRequest
            if (limit in 1 until prepared.transactions.size) return@transact SessionStep.TooMany
            if (submitter != null) {
                // The wallet signs only; the app submits after the session closes.
                val signed = signTransactions(prepared.transactions.toTypedArray())
                return@transact SessionStep.Signed(account, prepared, signed.signedPayloads.toList())
            }
            val sent = signAndSendTransactions(prepared.transactions.toTypedArray())
            SessionStep.Sent(account, prepared, sent.signatures.map(Base58::encode))
        }
        return when (result) {
            is TransactionResult.Success -> {
                persistToken(result.authResult)
                when (val step = result.payload) {
                    SessionStep.NoAccount -> WalletResult.Failed("wallet returned no accounts")
                    SessionStep.PrepareFailed -> WalletResult.Failed(PREPARE_FAILED)
                    SessionStep.TooMany -> WalletResult.Failed("Too many payloads to sign")
                    is SessionStep.Empty -> WalletResult.Success(WalletSession.NothingToSign(step.account))
                    is SessionStep.Sent -> {
                        val outcomes = step.signatures.map { poller.await(it, step.prepared.lastValidBlockHeight) }
                        WalletResult.Success(WalletSession.Submitted(step.account, step.prepared, SubmissionReport(outcomes)))
                    }
                    is SessionStep.Signed -> {
                        val signatures = submitAll(step.payloads) ?: return WalletResult.Failed(SUBMIT_FAILED)
                        val outcomes = signatures.map { poller.await(it, step.prepared.lastValidBlockHeight) }
                        WalletResult.Success(WalletSession.Submitted(step.account, step.prepared, SubmissionReport(outcomes)))
                    }
                }
            }
            is TransactionResult.NoWalletFound -> WalletResult.NoWalletInstalled
            is TransactionResult.Failure -> failed(result.message)
        }
    }

    /** What happened inside the MWA session, before confirmation polling. */
    private sealed interface SessionStep<out T> {
        data object NoAccount : SessionStep<Nothing>
        data object PrepareFailed : SessionStep<Nothing>
        data object TooMany : SessionStep<Nothing>
        class Empty(val account: WalletAccount) : SessionStep<Nothing>
        class Sent<T>(val account: WalletAccount, val prepared: T, val signatures: List<String>) : SessionStep<T>
        class Signed<T>(val account: WalletAccount, val prepared: T, val payloads: List<ByteArray>) : SessionStep<T>
    }

    suspend fun disconnect(sender: ActivityResultSender) {
        restoreToken()
        adapter.disconnect(sender)
        vault.clear(chain.fullName)
    }

    private fun restoreToken() {
        adapter.authToken = runCatching { vault.load(chain.fullName)?.value }.getOrNull()
    }

    /**
     * Called right after a wallet session, when its transactions are already signed (and, on
     * mainnet, sent). Nothing here may throw: an unkept token costs one more approval next time,
     * a crash here would cost the user the session they just approved.
     */
    private fun persistToken(auth: AuthorizationResult) {
        runCatching { auth.authToken.takeIf { it.isNotEmpty() }?.let { vault.save(chain.fullName, AuthToken(it)) } }
    }

    private fun onAuthorized(auth: AuthorizationResult): WalletResult<WalletAccount> {
        persistToken(auth)
        val account = auth.accounts.firstOrNull() ?: return WalletResult.Failed("wallet returned no accounts")
        return WalletResult.Success(WalletAccount(account.publicKey, account.accountLabel))
    }

    private fun failed(message: String): WalletResult.Failed {
        // A failed authorization may mean the stored token was revoked: drop it.
        if (message == "Auth token invalid") vault.clear(chain.fullName)
        return WalletResult.Failed(WalletFailures.sanitize(message))
    }

    companion object {
        const val PREPARE_FAILED = "Could not build the transaction. Nothing was sent."
        const val SUBMIT_FAILED = "The wallet signed, but the transaction could not be submitted. Nothing was armed."
    }
}
