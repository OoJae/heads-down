package xyz.headsdown.core.keys

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/** Software P-256 helpers (JDK SunEC) standing in for the Android Keystore in JVM tests. */
object TestCrypto {
    fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        require(clean.length % 2 == 0)
        return ByteArray(clean.length / 2) { i -> clean.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private val p256Params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    fun newKeyPair(seed: Long? = null): KeyPair = KeyPairGenerator.getInstance("EC").run {
        val random = if (seed == null) SecureRandom() else SecureRandom.getInstance("SHA1PRNG").apply { setSeed(seed) }
        initialize(ECGenParameterSpec("secp256r1"), random)
        generateKeyPair()
    }

    fun publicKey(x: BigInteger, y: BigInteger): ECPublicKey =
        KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256Params)) as ECPublicKey

    fun derSigner(keyPair: KeyPair): DerSigner = DerSigner { message ->
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(message)
            sign()
        }
    }

    /** SHA256withECDSA verification: the same predicate the secp256r1 precompile evaluates. */
    fun verifyRaw(publicKey: PublicKey, message: ByteArray, raw: ByteArray): Boolean =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(message)
            verify(P256.rawToDer(raw))
        }
}
