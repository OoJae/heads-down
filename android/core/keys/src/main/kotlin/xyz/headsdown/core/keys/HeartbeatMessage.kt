package xyz.headsdown.core.keys

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rig state carried in a signed heartbeat. The byte values are **wire format** shared with
 * the on-chain `heads_down` program; never renumber, only append.
 *
 * `dig` accepts only [DOWN]. [BROKEN] and [FROZEN] are the P-256-signed BREAK / FREEZE
 * signals (`break_shift`, `freeze_rig`).
 */
enum class RigSignalState(val wire: Int) {
    IDLE(0),
    ARMED(1),
    DOWN(2),
    COOLING(3),
    BROKEN(4),
    FROZEN(5),
    ;

    companion object {
        fun fromWire(value: Int): RigSignalState =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown rig state byte $value")
    }
}

/**
 * The exact byte string the rig's Keystore P-256 key signs and the secp256r1 precompile
 * verifies. Fixed length [ENCODED_SIZE] = 101 bytes, all integers little-endian:
 *
 * | offset | size | field          | notes                                            |
 * |-------:|-----:|----------------|--------------------------------------------------|
 * |      0 |    4 | domain tag     | ASCII `HDv1`, versions the whole layout          |
 * |      4 |   32 | program_id     | binds the signature to the heads_down program    |
 * |     36 |   32 | rig            | the Rig account address                          |
 * |     68 |    8 | ore_round_id   | u64, must equal the owner-checked `Board.round_id` |
 * |     76 |    8 | counter        | u64, strictly increasing per rig (anti-replay)   |
 * |     84 |    1 | state          | [RigSignalState.wire]                            |
 * |     85 |    8 | shift_id       | u64, binds to the armed shift                    |
 * |     93 |    8 | lease_end      | u64, last ORE round this heartbeat covers        |
 *
 * **Hashing decision: the message is signed raw, never pre-hashed.** The precompile computes
 * `SHA-256(message)` itself (ECDSA-P256-SHA256), and Keystore `SHA256withECDSA` computes the
 * same digest over the same bytes, so the on-chain program can compare the verified message
 * byte-for-byte against the fields it expects. Pre-hashing would make the program recompute
 * SHA-256 over the fields (extra CU) to learn anything about them, for no security gain.
 */
class HeartbeatMessage(
    programId: ByteArray,
    rig: ByteArray,
    val oreRoundId: ULong,
    val counter: ULong,
    val state: RigSignalState,
    val shiftId: ULong,
    val leaseEnd: ULong,
) {
    private val programIdBytes: ByteArray = programId.copyOf()
    private val rigBytes: ByteArray = rig.copyOf()

    val programId: ByteArray get() = programIdBytes.copyOf()
    val rig: ByteArray get() = rigBytes.copyOf()

    init {
        require(programIdBytes.size == ADDRESS_BYTES) { "program_id must be 32 bytes" }
        require(rigBytes.size == ADDRESS_BYTES) { "rig must be 32 bytes" }
        require(leaseEnd >= oreRoundId) { "lease_end must not precede ore_round_id" }
        require(leaseEnd - oreRoundId < MAX_LEASE_ROUNDS.toULong()) {
            "a heartbeat lease covers at most $MAX_LEASE_ROUNDS rounds"
        }
    }

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(ENCODED_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(DOMAIN_TAG)
        buf.put(programIdBytes)
        buf.put(rigBytes)
        buf.putLong(oreRoundId.toLong())
        buf.putLong(counter.toLong())
        buf.put(state.wire.toByte())
        buf.putLong(shiftId.toLong())
        buf.putLong(leaseEnd.toLong())
        check(buf.remaining() == 0)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean =
        other is HeartbeatMessage && encode().contentEquals(other.encode())

    override fun hashCode(): Int = encode().contentHashCode()

    override fun toString(): String =
        "HeartbeatMessage(round=$oreRoundId, counter=$counter, state=$state, shift=$shiftId, leaseEnd=$leaseEnd)"

    companion object {
        /** ASCII "HDv1". */
        val DOMAIN_TAG: ByteArray = byteArrayOf(0x48, 0x44, 0x76, 0x31)
        const val ADDRESS_BYTES = 32
        const val ENCODED_SIZE = 4 + 32 + 32 + 8 + 8 + 1 + 8 + 8 // 101

        /** Spec: solo-shift heartbeat leases cover 3 ORE rounds or fewer. */
        const val MAX_LEASE_ROUNDS = 3

        const val OFFSET_PROGRAM_ID = 4
        const val OFFSET_RIG = 36
        const val OFFSET_ROUND_ID = 68
        const val OFFSET_COUNTER = 76
        const val OFFSET_STATE = 84
        const val OFFSET_SHIFT_ID = 85
        const val OFFSET_LEASE_END = 93

        /** Strict inverse of [encode]; rejects wrong length, domain tag or state byte. */
        fun decode(bytes: ByteArray): HeartbeatMessage {
            require(bytes.size == ENCODED_SIZE) { "heartbeat must be $ENCODED_SIZE bytes, was ${bytes.size}" }
            require(bytes.copyOfRange(0, 4).contentEquals(DOMAIN_TAG)) { "bad domain tag" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return HeartbeatMessage(
                programId = bytes.copyOfRange(OFFSET_PROGRAM_ID, OFFSET_RIG),
                rig = bytes.copyOfRange(OFFSET_RIG, OFFSET_ROUND_ID),
                oreRoundId = buf.getLong(OFFSET_ROUND_ID).toULong(),
                counter = buf.getLong(OFFSET_COUNTER).toULong(),
                state = RigSignalState.fromWire(bytes[OFFSET_STATE].toInt() and 0xFF),
                shiftId = buf.getLong(OFFSET_SHIFT_ID).toULong(),
                leaseEnd = buf.getLong(OFFSET_LEASE_END).toULong(),
            )
        }
    }
}
