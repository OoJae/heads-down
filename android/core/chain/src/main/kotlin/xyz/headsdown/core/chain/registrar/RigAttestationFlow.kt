package xyz.headsdown.core.chain.registrar

import kotlinx.coroutines.CancellationException
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.wallet.SignInProof
import xyz.headsdown.core.wallet.SiwsRequest

/** A Keystore key generated with the registrar's challenge. Public data only. */
class ChallengedKey(compressedPublicKey: ByteArray, attestationChain: List<ByteArray>) {
    private val key = compressedPublicKey.copyOf()
    private val chain = attestationChain.map { it.copyOf() }
    val compressedPublicKey: ByteArray get() = key.copyOf()

    /** DER certificates, leaf first (`KeyStore.getCertificateChain`). */
    val attestationChain: List<ByteArray> get() = chain.map { it.copyOf() }
}

/** How a rig-key attestation ended. Every outcome but [ATTESTED] registers the rig as a guest. */
enum class AttestationOutcome {
    /** Level 1 (TEE) or 2 (StrongBox): the voucher goes into register_rig / rotate_key. */
    ATTESTED,

    /** The registrar vouched level 0 (a software key or an unlocked device): guest. */
    LEVEL_ZERO,

    /** The registrar could not be reached or answered outside the protocol: guest. */
    REGISTRAR_UNAVAILABLE,

    /** The wallet declined or does not support Sign In With Solana: guest. */
    SIGN_IN_DECLINED,

    /** The registrar refused this key or its chain, or its answer did not check out: guest. */
    REJECTED,
}

/** The result of one [RigAttestationFlow.run]. */
class AttestationRun(
    val outcome: AttestationOutcome,
    /** The wallet that signed in, when the flow got that far. */
    val authority: Pubkey?,
    /** The key generated with the registrar's challenge, or null when the flow stopped before. */
    val key: ChallengedKey?,
    val voucher: RegistrarVoucher?,
    /** Registrar or local code of the step that stopped the flow (for debug logs). */
    val code: String? = null,
)

/** Runs one rig-key attestation (the seam the app fakes in tests). */
fun interface RigAttestor {
    suspend fun run(
        signIn: suspend (SiwsRequest) -> SignInProof?,
        generateKey: suspend (challenge: ByteArray) -> ChallengedKey,
    ): AttestationRun
}

/**
 * The rig-key attestation with the registrar (registrar N1, N7): SIWS nonce → wallet sign-in →
 * session → attestation challenge → the rig key is generated with
 * `setAttestationChallenge(SHA-256("HDattest" ‖ authority ‖ nonce))` → `POST /attest` with the
 * certificate chain, session token and nonce → a level 1/2 voucher, or a guest.
 *
 * Fail-safe: when the registrar is unreachable or anything does not check out, the rig still
 * works as a guest (`has_attestation = 0`); level-0 vouchers are never used (refused on-chain).
 * If the flow stops before a key was generated, [AttestationRun.key] is null and the caller
 * generates one with a local challenge.
 */
class RigAttestationFlow(
    private val registrar: RegistrarClient,
    /** The app's SIWS domain (HeadsDownIdentity.SIWS_DOMAIN). */
    private val domain: String,
    /** This build's cluster, e.g. `solana:devnet` or `solana:localnet`. */
    private val chainId: String,
    /** The `localdev` build only: the devstack registrar's SIWS URI is loopback http. */
    private val allowLoopbackUri: Boolean = false,
) : RigAttestor {
    private sealed interface Step<out T> {
        class Ok<T>(val value: T) : Step<T>
        class Failed(val code: String, val unavailable: Boolean) : Step<Nothing>
    }

    /**
     * @param signIn MWA sign-in with the registrar's fields; null when declined or unsupported.
     * @param generateKey generates (or replaces) the rig key with this attestation challenge. May throw.
     */
    override suspend fun run(
        signIn: suspend (SiwsRequest) -> SignInProof?,
        generateKey: suspend (challenge: ByteArray) -> ChallengedKey,
    ): AttestationRun {
        val request = when (val s = step { registrar.siwsNonce(domain, chainId, allowLoopbackUri) }) {
            is Step.Ok -> s.value
            is Step.Failed -> return AttestationRun(AttestationOutcome.REGISTRAR_UNAVAILABLE, null, null, null, s.code)
        }
        val proof = signIn(request) ?: return AttestationRun(AttestationOutcome.SIGN_IN_DECLINED, null, null, null)
        val authority = Pubkey(proof.account.publicKey)
        val session = when (val s = step { registrar.siwsVerify(proof.signedMessage, proof.signature, authority) }) {
            is Step.Ok -> s.value
            is Step.Failed -> return failed(s, authority, null)
        }
        val challenge = when (val s = step { registrar.attestChallenge(session) }) {
            is Step.Ok -> s.value
            is Step.Failed -> return failed(s, authority, null)
        }
        val key = generateKey(challenge.challenge)
        val result = when (val s = step { registrar.attest(session, authority, key.compressedPublicKey, key.attestationChain, challenge.nonce) }) {
            is Step.Ok -> s.value
            is Step.Failed -> return failed(s, authority, key)
        }
        val voucher = result.voucher ?: return AttestationRun(AttestationOutcome.LEVEL_ZERO, authority, key, null, "level_0")
        return AttestationRun(AttestationOutcome.ATTESTED, authority, key, voucher)
    }

    private fun failed(s: Step.Failed, authority: Pubkey, key: ChallengedKey?) = AttestationRun(
        if (s.unavailable) AttestationOutcome.REGISTRAR_UNAVAILABLE else AttestationOutcome.REJECTED,
        authority, key, null, s.code,
    )

    /** Runs one registrar call, turning every failure into a [Step.Failed] with a code. */
    private suspend fun <T> step(call: suspend () -> T): Step<T> = try {
        Step.Ok(call())
    } catch (e: CancellationException) {
        throw e
    } catch (e: RegistrarException) {
        Step.Failed(e.code, e.unavailable)
    } catch (e: Exception) {
        // Transport failures (no network, DNS, TLS, timeouts): the registrar is unavailable.
        Step.Failed(e.javaClass.simpleName, unavailable = true)
    }
}
