package xyz.headsdown.core.wallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable Android Keystore key.
 *
 * Blob format: `version(1) ‖ iv(12) ‖ ciphertext+tag(16)`. The Keystore generates the IV
 * (randomized encryption is enforced), so IV reuse is impossible by construction.
 *
 * The key requires an unlocked device: MWA only runs from a foreground Activity anyway, and
 * this keeps the token undecryptable while the phone sits locked on the nightstand. A phone
 * with no secure lock screen cannot hold such a key (the Keystore refuses to generate it:
 * "User ECDH key missing"), and has no locked state to protect either, so there the key is made
 * without that requirement. It is still non-exportable and usable by this app only.
 */
class KeystoreAesGcmCipher(
    private val alias: String = DEFAULT_ALIAS,
) : AeadCipher {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected GCM IV size" }
        val ct = cipher.doFinal(plaintext)
        return byteArrayOf(VERSION) + iv + ct
    }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        if (ciphertext.size <= 1 + IV_BYTES + TAG_BYTES) throw UnrecoverableCiphertextException("ciphertext too short")
        if (ciphertext[0] != VERSION) throw UnrecoverableCiphertextException("unknown blob version")
        // A missing key means the blob was sealed by a key that no longer exists.
        val key = existingKey() ?: throw UnrecoverableCiphertextException("vault key missing")
        val iv = ciphertext.copyOfRange(1, 1 + IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, iv))
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw UnrecoverableCiphertextException("vault key invalidated", e)
        }
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext, 1 + IV_BYTES, ciphertext.size - 1 - IV_BYTES) // AEADBadTagException on tamper
    }

    private fun existingKey(): SecretKey? = keyStore.getKey(alias, null) as? SecretKey

    @Synchronized
    private fun key(): SecretKey {
        existingKey()?.let { return it }
        return try {
            generate(unlockedDeviceRequired = true)
        } catch (_: java.security.ProviderException) {
            // No secure lock screen: the Keystore has no user key to bind an unlocked-only key to.
            generate(unlockedDeviceRequired = false)
        }
    }

    private fun generate(unlockedDeviceRequired: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUnlockedDeviceRequired(unlockedDeviceRequired)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "hd.wallet.vault.aesgcm.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
    }
}

/** [SecretStore] backed by private SharedPreferences. Values are ciphertext only. */
class SharedPreferencesSecretStore(context: Context, fileName: String = FILE_NAME) : SecretStore {
    private val prefs = context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit { putString(key, value) }
    override fun remove(key: String) = prefs.edit { remove(key) }

    companion object {
        const val FILE_NAME = "hd_wallet_vault"
    }
}
