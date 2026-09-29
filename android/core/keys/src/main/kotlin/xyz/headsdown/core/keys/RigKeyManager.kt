package xyz.headsdown.core.keys

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/** Where the rig key's private half lives, from [KeyInfo.getSecurityLevel]. */
enum class KeySecurityLevel(val attestationLevel: Int) {
    /** Dedicated secure element (e.g. Pixel Titan M). Rig.attestation_level = 2. */
    STRONGBOX(2),

    /** Trusted Execution Environment. The Redmi 14C lands here. Rig.attestation_level = 1. */
    TRUSTED_ENVIRONMENT(1),

    /** Software-only or unknown: never accepted as an attested rig. */
    SOFTWARE_OR_UNKNOWN(0),
}

data class RigKeyInfo(
    val alias: String,
    val compressedPublicKey: ByteArray,
    val securityLevel: KeySecurityLevel,
    /** DER X.509 certificates, leaf first. Verified server-side by the Key Attestation registrar. */
    val attestationChain: List<ByteArray>,
) {
    override fun equals(other: Any?): Boolean = other is RigKeyInfo && alias == other.alias &&
        compressedPublicKey.contentEquals(other.compressedPublicKey)

    override fun hashCode(): Int = alias.hashCode() * 31 + compressedPublicKey.contentHashCode()
}

/**
 * Owns the rig's hardware-backed, non-exportable P-256 signing key in the Android Keystore.
 *
 * Key policy:
 *  - EC P-256, `PURPOSE_SIGN` only, `DIGEST_SHA256` only (signs with `SHA256withECDSA`).
 *  - StrongBox when the device advertises `FEATURE_STRONGBOX_KEYSTORE`, otherwise the TEE.
 *    If StrongBox generation still fails ([StrongBoxUnavailableException]) we fall back to
 *    the TEE and report the real level via [KeyInfo], never the requested one.
 *  - Non-exportable: Keystore private keys never leave secure hardware; this class never
 *    touches key material, only handles to it.
 *  - Key attestation: generated with the registrar's challenge, so the certificate chain
 *    proves (to the registrar) that the key is hardware-backed and bound to this app.
 *
 * **Trade-off: no user authentication per use.** Heartbeats are signed once per ORE round
 * (~78 s) by a foreground service while the phone lies face-down with the screen off. A key
 * that required biometric/credential auth per use (or within a validity window) could not
 * sign in that state, so `setUserAuthenticationRequired(false)`. The risk is bounded
 * elsewhere, by design: the key can only *attest presence* for a shift the wallet already
 * funded and capped on-chain. It cannot move funds, raise caps, or choose amounts/tiles.
 * Worst case with a compromised device is that the armed, wallet-capped budget is deployed
 * into ORE at or below the user's capped production cost; Freeze stops it instantly and
 * Revoke (wallet-signed) re-points the ORE Automation executor.
 */
class RigKeyManager(
    private val context: Context,
) {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    fun aliasFor(rigSlot: String): String {
        require(rigSlot.matches(SLOT_PATTERN)) { "rig slot must match ${SLOT_PATTERN.pattern}" }
        return "$ALIAS_PREFIX$rigSlot"
    }

    fun hasKey(alias: String): Boolean = keyStore.containsAlias(alias)

    val deviceHasStrongBox: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    /**
     * Generates (or replaces) the rig key under [alias].
     *
     * @param attestationChallenge the registrar's single-use nonce (1..128 bytes). The
     *   resulting certificate chain embeds it, which is what makes the attestation fresh.
     */
    fun generate(alias: String, attestationChallenge: ByteArray): RigKeyInfo {
        require(alias.startsWith(ALIAS_PREFIX)) { "use aliasFor()" }
        require(attestationChallenge.size in 1..MAX_CHALLENGE_BYTES) { "challenge must be 1..128 bytes" }

        if (deviceHasStrongBox) {
            try {
                generateInternal(alias, attestationChallenge, strongBox = true)
                return info(alias)
            } catch (_: StrongBoxUnavailableException) {
                // Advertised but unusable for this key type/size: fall through to the TEE.
                deleteKey(alias)
            }
        }
        generateInternal(alias, attestationChallenge, strongBox = false)
        return info(alias)
    }

    fun info(alias: String): RigKeyInfo = RigKeyInfo(
        alias = alias,
        compressedPublicKey = compressedPublicKey(alias),
        securityLevel = securityLevel(alias),
        attestationChain = attestationChain(alias),
    )

    fun compressedPublicKey(alias: String): ByteArray {
        val cert = keyStore.getCertificate(alias) ?: throw IllegalStateException("no rig key")
        val pub = cert.publicKey as? ECPublicKey ?: throw IllegalStateException("rig key is not EC")
        return P256.compress(pub)
    }

    fun attestationChain(alias: String): List<ByteArray> =
        keyStore.getCertificateChain(alias)?.map { it.encoded } ?: emptyList()

    fun securityLevel(alias: String): KeySecurityLevel {
        val key = privateKey(alias)
        val info = KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java)
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeySecurityLevel.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeySecurityLevel.TRUSTED_ENVIRONMENT
            else -> KeySecurityLevel.SOFTWARE_OR_UNKNOWN
        }
    }

    /**
     * A [DerSigner] bound to the Keystore key. Each call runs one `SHA256withECDSA` op. Rig
     * messages pass their 32-byte digest here, so Keystore signs `SHA-256(digest)`, which is
     * exactly what the secp256r1 precompile verifies for a 32-byte message.
     */
    fun signer(alias: String): DerSigner {
        val key = privateKey(alias)
        return DerSigner { message ->
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initSign(key)
                update(message)
                sign()
            }
        }
    }

    /** Signs HEARTBEAT / BREAK / FREEZE / PLAN digests with this key and the shared [counter]. */
    fun messageSigner(alias: String, counter: RigCounter): RigMessageSigner =
        RigMessageSigner(signer(alias), compressedPublicKey(alias), counter)

    fun deleteKey(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun privateKey(alias: String): PrivateKey =
        keyStore.getKey(alias, null) as? PrivateKey ?: throw IllegalStateException("no rig key")

    private fun generateInternal(alias: String, challenge: ByteArray, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false) // see class KDoc: background signing
            .setAttestationChallenge(challenge)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val CURVE = "secp256r1"
        const val ALIAS_PREFIX = "hd.rig.p256.v1."
        const val MAX_CHALLENGE_BYTES = 128
        private val SLOT_PATTERN = Regex("[a-z0-9_-]{1,32}")
    }
}
