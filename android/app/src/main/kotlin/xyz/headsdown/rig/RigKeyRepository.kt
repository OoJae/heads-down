package xyz.headsdown.rig

import xyz.headsdown.core.chain.registrar.ChallengedKey
import xyz.headsdown.core.keys.KeySecurityLevel
import xyz.headsdown.core.keys.RigCounter
import xyz.headsdown.core.keys.RigKeyManager
import xyz.headsdown.core.keys.RigMessageSigner
import xyz.headsdown.feature.shift.RigSignerProvider
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RigKeyStatus {
    data object Missing : RigKeyStatus
    data class Ready(
        val securityLevel: KeySecurityLevel,
        /** First bytes of the compressed P-256 pubkey, hex: a human-checkable fingerprint. */
        val fingerprint: String,
        val attestationCertificates: Int,
        /** Registrar voucher level for this key (1 TEE, 2 StrongBox), or null: a guest rig. */
        val voucherLevel: Int? = null,
    ) : RigKeyStatus
    data class Failed(val reason: String) : RigKeyStatus
}

/**
 * The device's single rig key (slot "primary").
 *
 * With a registrar, the key is generated with the registrar's attestation challenge
 * (`SHA-256("HDattest" ‖ authority ‖ nonce)`, registrar N1) so the certificate chain proves it is
 * hardware-backed and fresh; see [RigOnboarding]. Without one (not configured, unreachable,
 * declined), the challenge is local random bytes and the rig registers as a guest
 * (`attestation_level` 0). Every message this key signs goes through one shared, write-ahead
 * [RigCounter].
 */
@Singleton
class RigKeyRepository @Inject constructor(
    private val keys: RigKeyManager,
    private val counter: RigCounter,
) : RigSignerProvider, RigKeys {
    private val alias = keys.aliasFor("primary")

    override fun status(voucherLevel: Int?): RigKeyStatus = try {
        if (!keys.hasKey(alias)) RigKeyStatus.Missing else {
            val info = keys.info(alias)
            RigKeyStatus.Ready(
                securityLevel = info.securityLevel,
                fingerprint = info.compressedPublicKey.copyOfRange(0, 8).joinToString("") { "%02x".format(it) },
                attestationCertificates = info.attestationChain.size,
                voucherLevel = voucherLevel,
            )
        }
    } catch (e: Exception) {
        RigKeyStatus.Failed(e.javaClass.simpleName)
    }

    /** Blocking Keystore work (StrongBox can take seconds): call off the main thread. */
    override fun create(): RigKeyStatus = try {
        val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
        keys.generate(alias, challenge)
        status()
    } catch (e: Exception) {
        RigKeyStatus.Failed(e.javaClass.simpleName)
    }

    /**
     * Generates (or replaces) the rig key with the registrar's [challenge] and returns what the
     * registrar needs: the compressed key and the DER certificate chain, leaf first. Blocking.
     */
    override fun generateChallenged(challenge: ByteArray): ChallengedKey {
        val info = keys.generate(alias, challenge)
        return ChallengedKey(info.compressedPublicKey, info.attestationChain)
    }

    /** The 33-byte compressed rig key, or null when none exists yet. */
    fun compressedPublicKey(): ByteArray? =
        if (!keys.hasKey(alias)) null else runCatching { keys.compressedPublicKey(alias) }.getOrNull()

    override fun messageSigner(): RigMessageSigner? =
        if (!keys.hasKey(alias)) null else runCatching { keys.messageSigner(alias, counter) }.getOrNull()
}
