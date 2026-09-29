package xyz.headsdown.core.keys

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The P-256-signed messages of `programs/heads-down/INTERFACE.md` ("Signed P-256 messages").
 *
 * Each kind has a fixed-size **preimage** (little-endian, no padding, field offsets are the
 * running sums of the field sizes). The bytes the rig key signs, and that the secp256r1
 * precompile verifies, are the 32-byte **`SHA-256(preimage)`** ([RigMessage.digest]). The
 * program rebuilds the preimage from its own state plus the fields carried in the instruction
 * data, hashes it with `sol_sha256`, and requires byte equality with the precompile's message.
 *
 * ```
 * HEARTBEAT (94): "HDv1" | program_id(32) | rig(32) | kind=1 | counter u64 | shift_id u64 | round_id u64 | lease_rounds u8
 * BREAK/FREEZE (86): "HDv1" | program_id(32) | rig(32) | kind=2|3 | counter u64 | shift_id u64 | reason u8
 * PLAN (113): "HDv1" | program_id(32) | rig(32) | kind=4 | counter u64 | max_ev_cost u64 | dig_lamports u64 |
 *             split u8 | solo u8 | lease u8 | flags u8 | window_start i64 | window_end i64
 * ```
 *
 * `counter` strictly increases per rig across **all** kinds ([RigCounter]).
 */
object RigMessageFormat {
    /** ASCII "HDv1": versions every layout below. */
    val DOMAIN_TAG: ByteArray get() = byteArrayOf(0x48, 0x44, 0x76, 0x31)

    const val ADDRESS_BYTES = 32
    const val DIGEST_BYTES = 32

    const val HEARTBEAT_BYTES = 4 + 32 + 32 + 1 + 8 + 8 + 8 + 1 // 94
    const val SIGNAL_BYTES = 4 + 32 + 32 + 1 + 8 + 8 + 1 // 86
    const val PLAN_BYTES = 4 + 32 + 32 + 1 + 8 + ShiftPlan.ENCODED_BYTES // 113

    // Offsets shared by every kind.
    const val OFFSET_PROGRAM_ID = 4
    const val OFFSET_RIG = 36
    const val OFFSET_KIND = 68
    const val OFFSET_COUNTER = 69

    // HEARTBEAT
    const val OFFSET_HB_SHIFT_ID = 77
    const val OFFSET_HB_ROUND_ID = 85
    const val OFFSET_HB_LEASE_ROUNDS = 93

    // BREAK / FREEZE
    const val OFFSET_SIGNAL_SHIFT_ID = 77
    const val OFFSET_SIGNAL_REASON = 85

    // PLAN
    const val OFFSET_PLAN_FIELDS = 77

    /** Spec: a heartbeat lease covers 1..=3 ORE rounds (`plan_lease_rounds` is 1..=3 too). */
    const val MAX_LEASE_ROUNDS = 3

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/** `kind` byte of a signed message. Wire format: never renumber. */
enum class RigMessageKind(val wire: Int) {
    HEARTBEAT(1),
    BREAK(2),
    FREEZE(3),
    PLAN(4),
    ;

    companion object {
        fun fromWire(value: Int): RigMessageKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("unknown message kind $value")
    }
}

/**
 * `ShiftLog.break_reason` codes (INTERFACE, ShiftLog), also carried by BREAK / FREEZE messages.
 * Wire format: never renumber.
 */
enum class ShiftEndReason(val wire: Int) {
    COMPLETED(0),
    PICKUP(1),
    SCREEN_ON(2),
    FREEZE(3),
    LEASE_LAPSE(4),
    BUDGET(5),
    MANUAL(6),
    ;

    companion object {
        fun fromWire(value: Int): ShiftEndReason =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("unknown reason $value")
    }
}

/** A signed-message preimage bound to one program and one Rig account. */
sealed class RigMessage(programId: ByteArray, rig: ByteArray) {
    protected val programIdBytes: ByteArray = programId.copyOf()
    protected val rigBytes: ByteArray = rig.copyOf()

    val programId: ByteArray get() = programIdBytes.copyOf()
    val rig: ByteArray get() = rigBytes.copyOf()

    abstract val kind: RigMessageKind

    /** Strictly increasing per rig across all kinds. */
    abstract val counter: ULong

    init {
        require(programIdBytes.size == RigMessageFormat.ADDRESS_BYTES) { "program_id must be 32 bytes" }
        require(rigBytes.size == RigMessageFormat.ADDRESS_BYTES) { "rig must be 32 bytes" }
    }

    /** The exact preimage bytes (94 / 86 / 113). */
    abstract fun preimage(): ByteArray

    /** `SHA-256(preimage)`: the 32 bytes that are signed and handed to the precompile. */
    fun digest(): ByteArray = RigMessageFormat.sha256(preimage())

    protected fun header(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(RigMessageFormat.DOMAIN_TAG)
        put(programIdBytes)
        put(rigBytes)
        put(kind.wire.toByte())
        putLong(counter.toLong())
    }

    override fun equals(other: Any?): Boolean =
        other is RigMessage && other.javaClass == javaClass && preimage().contentEquals(other.preimage())

    override fun hashCode(): Int = preimage().contentHashCode()

    companion object {
        /** Strict inverse of [preimage]: rejects a wrong length, domain tag, kind or field value. */
        fun decode(bytes: ByteArray): RigMessage {
            require(bytes.size >= RigMessageFormat.OFFSET_COUNTER + 8) { "message too short" }
            require(bytes.copyOfRange(0, 4).contentEquals(RigMessageFormat.DOMAIN_TAG)) { "bad domain tag" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val programId = bytes.copyOfRange(RigMessageFormat.OFFSET_PROGRAM_ID, RigMessageFormat.OFFSET_RIG)
            val rig = bytes.copyOfRange(RigMessageFormat.OFFSET_RIG, RigMessageFormat.OFFSET_KIND)
            val kind = RigMessageKind.fromWire(bytes[RigMessageFormat.OFFSET_KIND].toInt() and 0xFF)
            val counter = buf.getLong(RigMessageFormat.OFFSET_COUNTER).toULong()
            return when (kind) {
                RigMessageKind.HEARTBEAT -> {
                    require(bytes.size == RigMessageFormat.HEARTBEAT_BYTES) { "heartbeat must be 94 bytes" }
                    HeartbeatPreimage(
                        programId, rig, counter,
                        shiftId = buf.getLong(RigMessageFormat.OFFSET_HB_SHIFT_ID).toULong(),
                        roundId = buf.getLong(RigMessageFormat.OFFSET_HB_ROUND_ID).toULong(),
                        leaseRounds = bytes[RigMessageFormat.OFFSET_HB_LEASE_ROUNDS].toInt() and 0xFF,
                    )
                }
                RigMessageKind.BREAK, RigMessageKind.FREEZE -> {
                    require(bytes.size == RigMessageFormat.SIGNAL_BYTES) { "signal must be 86 bytes" }
                    ShiftSignalPreimage(
                        programId, rig, kind, counter,
                        shiftId = buf.getLong(RigMessageFormat.OFFSET_SIGNAL_SHIFT_ID).toULong(),
                        reason = ShiftEndReason.fromWire(bytes[RigMessageFormat.OFFSET_SIGNAL_REASON].toInt() and 0xFF),
                    )
                }
                RigMessageKind.PLAN -> {
                    require(bytes.size == RigMessageFormat.PLAN_BYTES) { "plan must be 113 bytes" }
                    PlanPreimage(programId, rig, counter, ShiftPlan.decode(bytes, RigMessageFormat.OFFSET_PLAN_FIELDS))
                }
            }
        }
    }
}

/**
 * HEARTBEAT (kind 1, 94 bytes). Grants a lease over ORE rounds
 * `[round_id, round_id + min(lease_rounds, plan_lease_rounds) - 1]`.
 */
class HeartbeatPreimage(
    programId: ByteArray,
    rig: ByteArray,
    override val counter: ULong,
    val shiftId: ULong,
    /** ORE `Board.round_id` at signing. */
    val roundId: ULong,
    val leaseRounds: Int,
) : RigMessage(programId, rig) {
    override val kind: RigMessageKind get() = RigMessageKind.HEARTBEAT

    init {
        require(leaseRounds in 1..RigMessageFormat.MAX_LEASE_ROUNDS) { "lease_rounds must be 1..3" }
    }

    override fun preimage(): ByteArray = header(RigMessageFormat.HEARTBEAT_BYTES).run {
        putLong(shiftId.toLong())
        putLong(roundId.toLong())
        put(leaseRounds.toByte())
        check(remaining() == 0)
        array()
    }

    override fun toString(): String =
        "HeartbeatPreimage(counter=$counter, shift=$shiftId, round=$roundId, lease=$leaseRounds)"
}

/** BREAK (kind 2) or FREEZE (kind 3), 86 bytes. */
class ShiftSignalPreimage(
    programId: ByteArray,
    rig: ByteArray,
    override val kind: RigMessageKind,
    override val counter: ULong,
    val shiftId: ULong,
    val reason: ShiftEndReason,
) : RigMessage(programId, rig) {
    init {
        require(kind == RigMessageKind.BREAK || kind == RigMessageKind.FREEZE) { "signal kind must be BREAK or FREEZE" }
    }

    override fun preimage(): ByteArray = header(RigMessageFormat.SIGNAL_BYTES).run {
        putLong(shiftId.toLong())
        put(reason.wire.toByte())
        check(remaining() == 0)
        array()
    }

    override fun toString(): String = "ShiftSignalPreimage($kind, counter=$counter, shift=$shiftId, reason=$reason)"
}

/** PLAN (kind 4, 113 bytes): `arm_shift` authorized by the rig key instead of the wallet. */
class PlanPreimage(
    programId: ByteArray,
    rig: ByteArray,
    override val counter: ULong,
    val plan: ShiftPlan,
) : RigMessage(programId, rig) {
    override val kind: RigMessageKind get() = RigMessageKind.PLAN

    override fun preimage(): ByteArray = header(RigMessageFormat.PLAN_BYTES).run {
        put(plan.encode())
        check(remaining() == 0)
        array()
    }

    override fun toString(): String = "PlanPreimage(counter=$counter, $plan)"
}

/**
 * The per-shift plan (`Rig.plan_*`), in the field order shared by the PLAN preimage and the
 * `arm_shift` instruction data (36 bytes):
 * `max_ev_cost u64 | dig_lamports u64 | split u8 | solo u8 | lease u8 | flags u8 | window_start i64 | window_end i64`.
 *
 * Validated against the INTERFACE ranges here so a malformed plan is never signed or sent; the
 * program re-checks everything (and `plan ≤ caps`).
 */
data class ShiftPlan(
    /** Ceiling on the pot-adjusted cost `ema_ev`, lamports per ORE. */
    val maxEvCost: ULong,
    /** SOL per dig (a concentrated chunk), lamports. */
    val digLamports: ULong,
    val splitTiles: Int,
    val soloTiles: Int,
    val leaseRounds: Int,
    val flags: Int,
    /** Unix seconds. */
    val windowStartTs: Long,
    val windowEndTs: Long,
) {
    init {
        require(splitTiles in 0..MAX_SPLIT_TILES) { "split tiles must be 0..15" }
        require(soloTiles in 0..MAX_SOLO_TILES) { "solo tiles must be 0..10" }
        require(leaseRounds in 1..RigMessageFormat.MAX_LEASE_ROUNDS) { "lease rounds must be 1..3" }
        require(flags and FLAG_FOCUS_ONLY.inv() == 0) { "unknown plan flag bits" }
        require(focusOnly || splitTiles + soloTiles >= 1) { "a mining plan needs at least one tile" }
        require(windowEndTs > windowStartTs) { "plan window must not be empty" }
    }

    val focusOnly: Boolean get() = flags and FLAG_FOCUS_ONLY != 0

    val tiles: Int get() = splitTiles + soloTiles

    fun encode(): ByteArray = ByteBuffer.allocate(ENCODED_BYTES).order(ByteOrder.LITTLE_ENDIAN).run {
        putLong(maxEvCost.toLong())
        putLong(digLamports.toLong())
        put(splitTiles.toByte())
        put(soloTiles.toByte())
        put(leaseRounds.toByte())
        put(flags.toByte())
        putLong(windowStartTs)
        putLong(windowEndTs)
        check(remaining() == 0)
        array()
    }

    companion object {
        const val ENCODED_BYTES = 8 + 8 + 1 + 1 + 1 + 1 + 8 + 8 // 36
        const val MAX_SPLIT_TILES = 15
        const val MAX_SOLO_TILES = 10

        /** bit0: focus-only (never deploys). */
        const val FLAG_FOCUS_ONLY = 0x01

        fun decode(bytes: ByteArray, offset: Int): ShiftPlan {
            require(offset >= 0 && bytes.size - offset >= ENCODED_BYTES) { "truncated plan" }
            val buf = ByteBuffer.wrap(bytes, offset, ENCODED_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            return ShiftPlan(
                maxEvCost = buf.getLong().toULong(),
                digLamports = buf.getLong().toULong(),
                splitTiles = buf.get().toInt() and 0xFF,
                soloTiles = buf.get().toInt() and 0xFF,
                leaseRounds = buf.get().toInt() and 0xFF,
                flags = buf.get().toInt() and 0xFF,
                windowStartTs = buf.getLong(),
                windowEndTs = buf.getLong(),
            )
        }
    }
}
