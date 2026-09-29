package xyz.headsdown.rig

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
    ) : RigKeyStatus
    data class Failed(val reason: String) : RigKeyStatus
}

/**
 * The device's single rig key (slot "primary").
 *
 * The attestation challenge is generated locally for now. Once the Key Attestation
 * registrar is live, it issues a single-use challenge bound to the user's SIWS session and
 * verifies the returned chain; until then the attestation is informational only and the Rig
 * registers as a guest (`attestation_level` 0). Every message this key signs goes through one
 * shared, write-ahead [RigCounter].
 */
@Singleton
class RigKeyRepository @Inject constructor(
    private val keys: RigKeyManager,
    private val counter: RigCounter,
) : RigSignerProvider {
    private val alias = keys.aliasFor("primary")

    fun status(): RigKeyStatus = try {
        if (!keys.hasKey(alias)) RigKeyStatus.Missing else {
            val info = keys.info(alias)
            RigKeyStatus.Ready(
                securityLevel = info.securityLevel,
                fingerprint = info.compressedPublicKey.copyOfRange(0, 8).joinToString("") { "%02x".format(it) },
                attestationCertificates = info.attestationChain.size,
            )
        }
    } catch (e: Exception) {
        RigKeyStatus.Failed(e.javaClass.simpleName)
    }

    /** Blocking Keystore work (StrongBox can take seconds): call off the main thread. */
    fun create(): RigKeyStatus = try {
        val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
        keys.generate(alias, challenge)
        status()
    } catch (e: Exception) {
        RigKeyStatus.Failed(e.javaClass.simpleName)
    }

    /** The 33-byte compressed rig key, or null when none exists yet. */
    fun compressedPublicKey(): ByteArray? =
        if (!keys.hasKey(alias)) null else runCatching { keys.compressedPublicKey(alias) }.getOrNull()

    override fun messageSigner(): RigMessageSigner? =
        if (!keys.hasKey(alias)) null else runCatching { keys.messageSigner(alias, counter) }.getOrNull()
}
