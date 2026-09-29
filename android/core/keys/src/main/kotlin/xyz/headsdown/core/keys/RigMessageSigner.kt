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
 * A rig message ready for the secp256r1 precompile:
 * - [message]: the 32-byte `SHA-256(preimage)` the precompile verifies,
 * - [signature]: 64-byte raw `r ‖ s` with `s ≤ n/2` (low-S),
 * - [publicKey]: the 33-byte compressed key that `Rig.p256_pubkey` must equal,
 * - [payload]: the decoded preimage (for the uplink JSON and the program's instruction data).
 */
class SignedRigMessage<out M : RigMessage>(
    val payload: M,
    signature: ByteArray,
    publicKey: ByteArray,
) {
    private val signatureBytes = signature.copyOf()
    private val publicKeyBytes = publicKey.copyOf()
    private val digest = payload.digest()

    val message: ByteArray get() = digest.copyOf()
    val signature: ByteArray get() = signatureBytes.copyOf()
    val publicKey: ByteArray get() = publicKeyBytes.copyOf()

    init {
        require(signatureBytes.size == P256.RAW_SIGNATURE_BYTES) { "signature must be raw 64 bytes" }
        require(P256.isLowS(signatureBytes)) { "signature must be low-S" }
        require(publicKeyBytes.size == P256.COMPRESSED_PUBLIC_KEY_BYTES) { "pubkey must be compressed" }
    }

    override fun toString(): String = "SignedRigMessage($payload)"
}

typealias SignedHeartbeat = SignedRigMessage<HeartbeatPreimage>
typealias SignedShiftSignal = SignedRigMessage<ShiftSignalPreimage>
typealias SignedPlan = SignedRigMessage<PlanPreimage>

/**
 * Signs every kind of rig message with one key and one [RigCounter].
 *
 * Order of operations, for each message: (1) take the next counter value, which is persisted
 * before it is returned; (2) build the preimage; (3) hash it to the 32-byte digest; (4) sign the
 * digest with `SHA256withECDSA` (the precompile verifies ECDSA-P256 over SHA-256 of the same 32
 * bytes); (5) strict DER to raw `r ‖ s`; (6) normalize to low-S. A failure after step 1 burns a
 * counter value, which is harmless: the program only needs the sequence to increase.
 */
class RigMessageSigner(
    private val signer: DerSigner,
    compressedPublicKey: ByteArray,
    private val counter: RigCounter,
) {
    private val publicKey = compressedPublicKey.copyOf()

    init {
        // Throws if the key is not a valid compressed P-256 point.
        P256.decompress(publicKey)
    }

    val compressedPublicKey: ByteArray get() = publicKey.copyOf()

    fun heartbeat(programId: ByteArray, rig: ByteArray, shiftId: ULong, roundId: ULong, leaseRounds: Int): SignedHeartbeat =
        sign(HeartbeatPreimage(programId, rig, counter.next(), shiftId, roundId, leaseRounds))

    fun shiftSignal(
        programId: ByteArray,
        rig: ByteArray,
        kind: RigMessageKind,
        shiftId: ULong,
        reason: ShiftEndReason,
    ): SignedShiftSignal = sign(ShiftSignalPreimage(programId, rig, kind, counter.next(), shiftId, reason))

    fun plan(programId: ByteArray, rig: ByteArray, plan: ShiftPlan): SignedPlan =
        sign(PlanPreimage(programId, rig, counter.next(), plan))

    private fun <M : RigMessage> sign(payload: M): SignedRigMessage<M> {
        val raw = P256.normalizeLowS(P256.derToRaw(signer.signDer(payload.digest())))
        return SignedRigMessage(payload, raw, publicKey)
    }
}
