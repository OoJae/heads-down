package xyz.headsdown.core.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Software AES-GCM with the same blob layout as [KeystoreAesGcmCipher]. */
private class SoftwareAesGcm(private val key: SecretKey = newKey()) : AeadCipher {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(associatedData)
        return byteArrayOf(1) + iv + c.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        require(ciphertext[0].toInt() == 1)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOfRange(1, 13)))
        c.updateAAD(associatedData)
        return c.doFinal(ciphertext, 13, ciphertext.size - 13)
    }

    companion object {
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}

private class MemoryStore : SecretStore {
    val map = mutableMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

class AuthTokenVaultTest {
    private val token = AuthToken("mwa-secret-token-0123456789")
    private val store = MemoryStore()
    private val vault = AuthTokenVault(SoftwareAesGcm(), store)

    @Test
    fun `round-trips a token`() {
        vault.save("solana:devnet", token)
        assertEquals(token, vault.load("solana:devnet"))
    }

    @Test
    fun `stores ciphertext only`() {
        vault.save("solana:devnet", token)
        val stored = store.map.values.single()
        assertFalse(stored.contains(token.value))
        val raw = String(Base64.getDecoder().decode(stored), Charsets.ISO_8859_1)
        assertFalse(raw.contains(token.value))
    }

    @Test
    fun `a Keystore that cannot seal the token loses the token, never the session`() {
        // What a phone with no secure lock screen did: KeyGenerator.generateKey threw ProviderException.
        val broken = object : AeadCipher {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
                throw java.security.ProviderException("Keystore key generation failed")

            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray = throw IllegalStateException("unused")
        }
        // An older session's token is in the store: it must not survive as if it were current.
        vault.save("solana:devnet", token)
        val refusing = AuthTokenVault(broken, store)
        assertFalse(refusing.save("solana:devnet", AuthToken("newer-token")))
        assertTrue(store.map.isEmpty())
        assertNull(refusing.load("solana:devnet"))
        // A store that fails is handled the same way.
        val failingStore = object : SecretStore {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String) = throw IllegalStateException("disk full")
            override fun remove(key: String) = throw IllegalStateException("disk full")
        }
        assertFalse(AuthTokenVault(SoftwareAesGcm(), failingStore).save("solana:devnet", token))
        assertTrue(vault.save("solana:devnet", token))
    }

    @Test
    fun `tampered blob fails closed and is cleared`() {
        vault.save("solana:devnet", token)
        val key = store.map.keys.single()
        val blob = Base64.getDecoder().decode(store.map[key])
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        store.map[key] = Base64.getEncoder().encodeToString(blob)

        assertNull(vault.load("solana:devnet"))
        assertTrue("unreadable entry must be deleted", store.map.isEmpty())
    }

    @Test
    fun `blob is bound to its chain by the AAD`() {
        vault.save("solana:devnet", token)
        // Move the devnet ciphertext into the mainnet slot: AAD mismatch -> rejected.
        store.map["mwa_auth_token.solana:mainnet"] = store.map.getValue("mwa_auth_token.solana:devnet")
        assertNull(vault.load("solana:mainnet"))
        assertEquals(token, vault.load("solana:devnet"))
    }

    @Test
    fun `a different key cannot read the blob`() {
        vault.save("solana:devnet", token)
        val otherVault = AuthTokenVault(SoftwareAesGcm(), store)
        assertNull(otherVault.load("solana:devnet"))
    }

    @Test
    fun `transient keystore failure keeps the blob`() {
        vault.save("solana:devnet", token)
        val locked = object : AeadCipher {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = error("unused")
            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
                throw IllegalStateException("Keystore: device locked")
        }
        assertNull(AuthTokenVault(locked, store).load("solana:devnet"))
        assertEquals("a transient failure must not delete the session", 1, store.map.size)
        assertEquals(token, vault.load("solana:devnet"))
    }

    @Test
    fun `unrecoverable ciphertext is discarded`() {
        vault.save("solana:devnet", token)
        val invalidated = object : AeadCipher {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = error("unused")
            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
                throw UnrecoverableCiphertextException("vault key invalidated")
        }
        assertNull(AuthTokenVault(invalidated, store).load("solana:devnet"))
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun `garbage and missing entries return null`() {
        assertNull(vault.load("solana:devnet"))
        store.map["mwa_auth_token.solana:devnet"] = "not base64 !!"
        assertNull(vault.load("solana:devnet"))
    }

    @Test
    fun `token never appears in toString`() {
        assertEquals("AuthToken(redacted)", token.toString())
        assertFalse("$token".contains(token.value))
        assertFalse(listOf(token).toString().contains(token.value))
    }
}
