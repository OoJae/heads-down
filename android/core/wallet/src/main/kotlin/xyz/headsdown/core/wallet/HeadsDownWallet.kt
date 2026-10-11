package xyz.headsdown.core.wallet

import androidx.core.net.toUri
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.Blockchain
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.AuthorizationResult
import com.solana.mobilewalletadapter.common.ProtocolContract
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

    /**
     * The icon, relative to the identity site (`site/icon.png` in the repository). A PNG, not the
     * site's `favicon.ico`: every Android image loader decodes PNG, and not all of them decode ICO.
     */
    const val ICON = "icon.png"

    /** @param identityUri an absolute `https` URL; the icon is resolved relative to it by the wallet. */
    fun connectionIdentity(identityUri: String): ConnectionIdentity {
        val uri = identityUri.toUri()
        require(uri.scheme == "https" && !uri.host.isNullOrEmpty()) { "the identity URI must be an https URL" }
        return ConnectionIdentity(identityUri = uri, iconUri = ICON.toUri(), identityName = NAME)
    }
}

/**
 * What a failed wallet session was. clientlib-ktx 2.2.0 wraps the whole session, not only the
 * connection, in the catch that says "Failed establishing local association with wallet": a token
 * the wallet refuses, a signing request the user declines and a wallet that never came up all read
 * the same. The wallet's own answer is still in the exception's causes, and is read from there.
 */
internal object WalletAnswer {
    /** The error code the wallet answered with, or null if it never answered with an error. */
    fun code(failure: Throwable?): Int? =
        generateSequence(failure) { it.cause }.take(8)
            .filterIsInstance<JsonRpc20Client.JsonRpc20RemoteException>().firstOrNull()?.code

    /** The library's own word for [code], which is what [WalletFailures] knows; [fallback] when the wallet gave none. */
    fun message(code: Int?, fallback: String): String = when (code) {
        null -> fallback
        ProtocolContract.ERROR_AUTHORIZATION_FAILED -> "Auth token invalid"
        ProtocolContract.ERROR_NOT_SIGNED -> "User did not authorize signing"
        ProtocolContract.ERROR_TOO_MANY_PAYLOADS -> "Too many payloads to sign"
        else -> "Remote exception"
    }

    /**
     * The wallet refused the stored token: one was [presented], the wallet answered with an error
     * ([code] is not null), and the session never [entered] the part where something is signed.
     * A wallet the user closed, or that never came up, gave no answer, and refused nothing.
     */
    fun refusedToken(presented: Boolean, entered: Boolean, code: Int?): Boolean = presented && !entered && code != null
}

/**
 * Mobile Wallet Adapter (clientlib-ktx 2.2.0) for Heads Down.
 *
 * - One wallet confirmation per user action; all calls need an [ActivityResultSender] made
 *   in `Activity.onCreate` (MWA cannot run from a Service: see the tile's trampoline).
 * - The auth token is restored from and persisted to [AuthTokenVault]; it is never logged. A
 *   token the wallet refuses is dropped, and the failure asks for one more try ([withStoredToken]).
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

    /** The last call ended with the wallet refusing the stored token ([withStoredToken]). */
    @Volatile private var tokenRefused = false

    suspend fun connect(sender: ActivityResultSender): WalletResult<WalletAccount> {
        return when (val result = withStoredToken { adapter.connect(sender) }) {
            is TransactionResult.Success -> onAuthorized(result.authResult)
            is TransactionResult.NoWalletFound -> WalletResult.NoWalletInstalled
            is TransactionResult.Failure -> failed(result)
        }
    }

    /**
     * Sign In With Solana with the fields the registrar issued (`POST /siws/nonce`, registrar N7):
     * domain, URI, statement, version, chain id, nonce, issued-at and expiration are copied
     * verbatim, because the registrar verifies the exact message the wallet signs. The returned
     * proof is verified server-side (`POST /siws/verify`), not trusted locally.
     */
    suspend fun signIn(sender: ActivityResultSender, request: SiwsRequest): WalletResult<SignInProof> {
        // 2.2.0 added a String-address overload, so the null address must be typed.
        val payload = SignInWithSolana.Payload(
            request.domain, null as ByteArray?, request.statement, request.uri.toUri(), request.version, request.chainId,
            request.nonce, request.issuedAt, request.expirationTime, null, null, null,
        )
        return when (val result = withStoredToken { adapter.signIn(sender, payload) }) {
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
            is TransactionResult.Failure -> failed(result)
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
        var entered = false
        val result = withStoredToken({ entered }) {
            adapter.transact(sender) {
                entered = true
                if (submitter == null) {
                    SignedOrSent.Sent(signAndSendTransactions(transactions.toTypedArray()).signatures.map(Base58::encode))
                } else {
                    SignedOrSent.Signed(signTransactions(transactions.toTypedArray()).signedPayloads.toList())
                }
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
            is TransactionResult.Failure -> failed(result)
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
        var entered = false
        val result = withStoredToken({ entered }) {
            adapter.transact(sender) { auth ->
                entered = true
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
            is TransactionResult.Failure -> failed(result)
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
     * One wallet call with the stored token. A wallet may refuse the token it handed out: Jupiter
     * Mobile did, a day later, and clientlib-ktx 2.2.0 does not say "Auth token invalid" then
     * ([WalletAnswer]): the refusal read "Failed establishing local association with wallet", the
     * token was never dropped, and every later call failed the same way.
     *
     * A refused token is dropped here, and the failure says so ([WalletFailures.RECONNECT]): the
     * next call presents none, and the wallet shows its own approval and hands out a new one. The
     * call is not made a second time from here. That was tried on the phone (11 October 2026):
     * started while the wallet's first window was still closing, the second session was cancelled
     * at once; the tap that followed it, with no token, went through.
     *
     * [entered] says whether the session got past the authorization. After that an error is about
     * what was to be signed, not about the token, and the token is kept.
     */
    private suspend fun <T> withStoredToken(
        entered: () -> Boolean = { false },
        call: suspend () -> TransactionResult<T>,
    ): TransactionResult<T> {
        restoreToken()
        val presented = adapter.authToken != null
        tokenRefused = false
        val result = call()
        if (result is TransactionResult.Failure && WalletAnswer.refusedToken(presented, entered(), WalletAnswer.code(result.e))) {
            runCatching { vault.clear(chain.fullName) }
            adapter.authToken = null
            tokenRefused = true
        }
        return result
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

    private fun <T> failed(result: TransactionResult.Failure<T>): WalletResult.Failed {
        if (tokenRefused) return WalletResult.Failed(WalletFailures.RECONNECT)
        val message = WalletAnswer.message(WalletAnswer.code(result.e), result.message)
        // A failed authorization may mean the stored token was revoked: drop it.
        if (message == "Auth token invalid") runCatching { vault.clear(chain.fullName) }
        return WalletResult.Failed(WalletFailures.sanitize(message))
    }

    companion object {
        const val PREPARE_FAILED = "Could not build the transaction. Nothing was sent."
        const val SUBMIT_FAILED = "The wallet signed, but the transaction could not be submitted. Nothing was armed."
    }
}
