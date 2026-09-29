package xyz.headsdown.core.wallet

import java.util.Base64
import javax.crypto.AEADBadTagException

/** Authenticated encryption. `encrypt` output is self-contained (it carries its own IV). */
interface AeadCipher {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /**
     * Throws [UnrecoverableCiphertextException] (or `AEADBadTagException`) when the blob can
     * never be decrypted (tampering, wrong AAD, key invalidated or replaced), and any other
     * exception for transient failures (e.g. device locked) where the blob is still good.
     */
    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray
}

/** The ciphertext is permanently unreadable; the caller should discard it. */
class UnrecoverableCiphertextException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Minimal persistent string store. Only ever sees ciphertext. */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/**
 * Keeps the MWA auth token encrypted at rest under a Keystore AES-256-GCM key
 * ([KeystoreAesGcmCipher] on device).
 *
 * - The AAD binds each blob to its purpose and chain (`hd/mwa-auth-token/v1/<chain>`), so a
 *   devnet token blob cannot be replayed as a mainnet one, nor any other blob as a token.
 * - A permanently unreadable blob (tampered prefs, restored from another device, invalidated
 *   Keystore key) is deleted and null returned: the user simply re-authorizes.
 * - A transient failure (e.g. the Keystore refuses while the device is locked) returns null
 *   but keeps the blob, so a valid session is not thrown away.
 * - Never logs. [AuthToken.toString] is redacted.
 */
class AuthTokenVault(
    private val cipher: AeadCipher,
    private val store: SecretStore,
) {
    fun save(chain: String, token: AuthToken) {
        val blob = cipher.encrypt(token.value.toByteArray(Charsets.UTF_8), aad(chain))
        store.put(storageKey(chain), Base64.getEncoder().encodeToString(blob))
    }

    fun load(chain: String): AuthToken? {
        val encoded = store.get(storageKey(chain)) ?: return null
        return try {
            val blob = Base64.getDecoder().decode(encoded) // IllegalArgumentException if corrupt
            val plain = cipher.decrypt(blob, aad(chain))
            AuthToken(String(plain, Charsets.UTF_8))
        } catch (_: UnrecoverableCiphertextException) {
            discard(chain)
        } catch (_: AEADBadTagException) {
            discard(chain)
        } catch (_: IllegalArgumentException) {
            discard(chain)
        } catch (_: Exception) {
            null // transient: fail closed for now, keep the blob
        }
    }

    private fun discard(chain: String): AuthToken? {
        store.remove(storageKey(chain))
        return null
    }

    fun clear(chain: String) = store.remove(storageKey(chain))

    private fun aad(chain: String): ByteArray = "$AAD_PREFIX$chain".toByteArray(Charsets.UTF_8)

    private fun storageKey(chain: String): String = "mwa_auth_token.$chain"

    private companion object {
        const val AAD_PREFIX = "hd/mwa-auth-token/v1/"
    }
}
