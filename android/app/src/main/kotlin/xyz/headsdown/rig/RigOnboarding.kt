package xyz.headsdown.rig

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.headsdown.core.chain.registrar.AttestationOutcome
import xyz.headsdown.core.chain.registrar.ChallengedKey
import xyz.headsdown.core.chain.registrar.RegistrarVoucher
import xyz.headsdown.core.chain.registrar.RigAttestor
import xyz.headsdown.core.wallet.SignInProof
import xyz.headsdown.core.wallet.SiwsRequest

/** The rig key operations onboarding needs ([RigKeyRepository] on the phone). Blocking calls. */
interface RigKeys {
    /** A guest key with a local challenge. */
    fun create(): RigKeyStatus

    /** A key generated with the registrar's attestation challenge. May throw. */
    fun generateChallenged(challenge: ByteArray): ChallengedKey

    fun status(voucherLevel: Int? = null): RigKeyStatus
}

/** Where the voucher is kept for clock-in ([VoucherStore] on the phone). */
interface VoucherSink {
    fun save(voucher: RegistrarVoucher)
    fun clear()
}

/** What creating the rig key ended with. */
data class RigKeySetup(val status: RigKeyStatus, val attestation: AttestationOutcome)

/**
 * Creates the rig key, attested by the registrar when it can be (registrar N1, N7):
 * SIWS nonce → the wallet signs in with the registrar's exact fields → attestation challenge →
 * the key is generated with it → `POST /attest` → a level 1/2 voucher is stored for clock-in.
 *
 * Fail-safe: no registrar configured, unreachable, a declined sign-in, level 0 or a refused chain
 * all end with a working guest key (`has_attestation = 0` at registration). A key generated with
 * the registrar's challenge is kept even when its voucher is refused. Any stored voucher for an
 * earlier key is dropped first, since it cannot vouch for the new one.
 */
class RigOnboarding(
    /** Null when this build has no registrar (an empty registrar URL). */
    private val attestor: RigAttestor?,
    private val keys: RigKeys,
    private val vouchers: VoucherSink,
    /** Keystore generation is blocking (StrongBox can take seconds). */
    private val keystore: CoroutineDispatcher = Dispatchers.Default,
    private val debugLog: (String) -> Unit = {},
) {
    /**
     * @param signIn the wallet's Sign In With Solana for the registrar's request; null when the
     *   user declines or the wallet cannot. Called at most once, after the registrar answered.
     */
    suspend fun createKey(signIn: suspend (SiwsRequest) -> SignInProof?): RigKeySetup {
        vouchers.clear()
        if (attestor == null) {
            return RigKeySetup(withContext(keystore) { keys.create() }, AttestationOutcome.REGISTRAR_UNAVAILABLE)
        }
        val run = try {
            attestor.run(signIn = signIn, generateKey = { challenge -> withContext(keystore) { keys.generateChallenged(challenge) } })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The Keystore refused the challenged generation: fall back to a local guest key.
            debugLog("attested key generation failed (${e.javaClass.simpleName}); creating a guest key")
            return RigKeySetup(withContext(keystore) { keys.create() }, AttestationOutcome.REJECTED)
        }
        if (run.outcome != AttestationOutcome.ATTESTED) debugLog("rig key attestation ${run.outcome} (${run.code ?: "-"})")
        run.voucher?.let(vouchers::save)
        val status = withContext(keystore) { if (run.key == null) keys.create() else keys.status(run.voucher?.level) }
        return RigKeySetup(status, run.outcome)
    }
}
