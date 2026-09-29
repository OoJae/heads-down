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

    fun publicKeyFromCompressed(compressed: ByteArray): ECPublicKey =
        P256.decompress(compressed).let { (x, y) -> publicKey(x, y) }

    private const val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /** Base58 -> 32 bytes (Solana addresses only; strict). */
    fun base58Address(text: String): ByteArray {
        var n = BigInteger.ZERO
        for (c in text) {
            val digit = B58.indexOf(c)
            require(digit >= 0) { "bad base58" }
            n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
        }
        val leadingZeros = text.takeWhile { it == '1' }.length
        val body = n.toByteArray().dropWhile { it.toInt() == 0 }.toByteArray()
        val out = ByteArray(leadingZeros) + body
        require(out.size == 32) { "not a 32-byte address" }
        return out
    }

    private val bcP256 by lazy {
        val x9 = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256r1")
        org.bouncycastle.crypto.params.ECDomainParameters(x9.curve, x9.g, x9.n, x9.h)
    }

    /** Compressed public key for a private scalar. */
    fun compressedPublicKey(privateScalar: BigInteger): ByteArray {
        val q = bcP256.g.multiply(privateScalar).normalize()
        return P256.compress(q.affineXCoord.toBigInteger(), q.affineYCoord.toBigInteger())
    }

    /**
     * Deterministic ECDSA-P256 (RFC 6979, HMAC-SHA256 nonce) over `SHA-256(message)`, as raw
     * `r ‖ s` **before** low-S normalization. The same signature any RFC 6979 implementation
     * (e.g. the Rust `p256` crate's `SigningKey::sign`) produces for this key and message.
     */
    fun signRfc6979(privateScalar: BigInteger, message: ByteArray): ByteArray {
        val signer = org.bouncycastle.crypto.signers.ECDSASigner(
            org.bouncycastle.crypto.signers.HMacDSAKCalculator(org.bouncycastle.crypto.digests.SHA256Digest()),
        )
        signer.init(true, org.bouncycastle.crypto.params.ECPrivateKeyParameters(privateScalar, bcP256))
        val (r, s) = signer.generateSignature(RigMessageFormat.sha256(message))
        return with(P256) { r.toFixed32() + s.toFixed32() }
    }
}
