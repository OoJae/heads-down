package xyz.headsdown.core.wallet

import java.util.Base64

/** Authenticated encryption. `encrypt` output is self-contained (it carries its own IV). */
interface AeadCipher {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /** Throws on any authentication failure (tampering, wrong AAD, wrong or rotated key). */
    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray
}

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
 * - Any decryption failure (tampered prefs, restored backup from another device, invalidated
 *   Keystore key) clears the entry and returns null: the user simply re-authorizes.
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
            val blob = Base64.getDecoder().decode(encoded)
            val plain = cipher.decrypt(blob, aad(chain))
            AuthToken(String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            // Fail closed and self-heal: an unreadable token is as good as no token.
            store.remove(storageKey(chain))
            null
        }
    }

    fun clear(chain: String) = store.remove(storageKey(chain))

    private fun aad(chain: String): ByteArray = "$AAD_PREFIX$chain".toByteArray(Charsets.UTF_8)

    private fun storageKey(chain: String): String = "mwa_auth_token.$chain"

    private companion object {
        const val AAD_PREFIX = "hd/mwa-auth-token/v1/"
    }
}
