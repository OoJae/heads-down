package xyz.headsdown.core.keys

/**
 * Something that signs bytes with P-256 / SHA-256 and returns an ASN.1 DER signature,
 * i.e. `Signature.getInstance("SHA256withECDSA")`. On device this is the Keystore key
 * ([RigKeyManager.signer]); in JVM tests it is a software JCA key.
 */
fun interface DerSigner {
    fun signDer(message: ByteArray): ByteArray
}

/**
 * A heartbeat ready for the secp256r1 precompile: raw message, 64-byte low-S `r ‖ s`,
 * and the 33-byte compressed public key that `Rig.p256` must equal.
 */
class SignedHeartbeat(
    message: ByteArray,
    signature: ByteArray,
    publicKey: ByteArray,
) {
    private val messageBytes = message.copyOf()
    private val signatureBytes = signature.copyOf()
    private val publicKeyBytes = publicKey.copyOf()

    val message: ByteArray get() = messageBytes.copyOf()
    val signature: ByteArray get() = signatureBytes.copyOf()
    val publicKey: ByteArray get() = publicKeyBytes.copyOf()

    init {
        require(messageBytes.size == HeartbeatMessage.ENCODED_SIZE) { "bad heartbeat length" }
        require(signatureBytes.size == P256.RAW_SIGNATURE_BYTES) { "signature must be raw 64 bytes" }
        require(P256.isLowS(signatureBytes)) { "signature must be low-S" }
        require(publicKeyBytes.size == P256.COMPRESSED_PUBLIC_KEY_BYTES) { "pubkey must be compressed" }
    }

    val decoded: HeartbeatMessage get() = HeartbeatMessage.decode(messageBytes)

    override fun toString(): String = "SignedHeartbeat($decoded)"
}

/** Signs [HeartbeatMessage]s and converts the Keystore output into precompile form. */
class HeartbeatSigner(
    private val signer: DerSigner,
    compressedPublicKey: ByteArray,
) {
    private val publicKey = compressedPublicKey.copyOf()

    init {
        // Throws if the key is not a valid compressed P-256 point.
        P256.decompress(publicKey)
    }

    fun sign(heartbeat: HeartbeatMessage): SignedHeartbeat {
        val message = heartbeat.encode()
        val raw = P256.normalizeLowS(P256.derToRaw(signer.signDer(message)))
        return SignedHeartbeat(message, raw, publicKey)
    }
}
