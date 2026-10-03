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
 * - Not being able to keep a token is never an error. [save] runs right after the wallet has
 *   signed, so a Keystore that cannot seal the token (it happens: no secure lock screen, a
 *   locked device, a broken Keystore) must not take the session down with it. The token is
 *   simply not kept, and the wallet asks to authorize again next time.
 * - Never logs. [AuthToken.toString] is redacted.
 */
class AuthTokenVault(
    private val cipher: AeadCipher,
    private val store: SecretStore,
) {
    /** @return false when the token could not be sealed or stored: it is then not kept at all. */
    fun save(chain: String, token: AuthToken): Boolean = try {
        val blob = cipher.encrypt(token.value.toByteArray(Charsets.UTF_8), aad(chain))
        store.put(storageKey(chain), Base64.getEncoder().encodeToString(blob))
        true
    } catch (_: Exception) {
        // Whatever was stored before belongs to an older session: do not leave it behind.
        runCatching { store.remove(storageKey(chain)) }
        false
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
