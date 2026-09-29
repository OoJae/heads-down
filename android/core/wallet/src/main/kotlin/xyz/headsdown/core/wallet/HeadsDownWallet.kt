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

/** Where the dApp identifies itself to the wallet. */
object HeadsDownIdentity {
    val connectionIdentity = ConnectionIdentity(
        identityUri = "https://headsdown.xyz".toUri(),
        iconUri = "favicon.ico".toUri(), // resolved relative to identityUri by the wallet
        identityName = "Heads Down",
    )
    const val SIWS_DOMAIN = "headsdown.xyz"
}

/**
 * Mobile Wallet Adapter (clientlib-ktx 2.2.0) for Heads Down.
 *
 * - One wallet confirmation per user action; all calls need an [ActivityResultSender] made
 *   in `Activity.onCreate` (MWA cannot run from a Service: see the tile's trampoline).
 * - The auth token is restored from and persisted to [AuthTokenVault]; it is never logged.
 * - [signAndSend] reports success only after [ConfirmationPoller] saw each signature
 *   confirmed with `err == null`. A wallet "sent" response is not success.
 */
class HeadsDownWallet(
    private val adapter: MobileWalletAdapter,
    private val vault: AuthTokenVault,
    private val poller: ConfirmationPoller,
    private val chain: Blockchain = Solana.Devnet,
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
     * Sign In With Solana. [nonce] must come from the SIWS nonce service (single use, >= 8
     * alphanumerics); the returned proof is verified server-side, not trusted locally.
     */
    suspend fun signIn(sender: ActivityResultSender, nonce: String, statement: String): WalletResult<SignInProof> {
        restoreToken()
        // 2.2.0 added a String-address overload, so the null address must be typed.
        val payload = SignInWithSolana.Payload(
            HeadsDownIdentity.SIWS_DOMAIN, null as ByteArray?, statement, null, "1", chain.fullName, nonce,
            null, null, null, null, null,
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
        val result = adapter.transact(sender) { signAndSendTransactions(transactions.toTypedArray()) }
        return when (result) {
            is TransactionResult.Success -> {
                persistToken(result.authResult)
                val signatures = result.payload.signatures.map(Base58::encode)
                WalletResult.Success(SubmissionReport(signatures.map { poller.await(it, lastValidBlockHeight) }))
            }
            is TransactionResult.NoWalletFound -> WalletResult.NoWalletInstalled
            is TransactionResult.Failure -> failed(result.message)
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
    }

    suspend fun disconnect(sender: ActivityResultSender) {
        restoreToken()
        adapter.disconnect(sender)
        vault.clear(chain.fullName)
    }

    private fun restoreToken() {
        adapter.authToken = vault.load(chain.fullName)?.value
    }

    private fun persistToken(auth: AuthorizationResult) {
        auth.authToken.takeIf { it.isNotEmpty() }?.let { vault.save(chain.fullName, AuthToken(it)) }
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
    }
}
