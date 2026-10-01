package xyz.headsdown.core.chain.uplink

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.keys.HeartbeatPreimage
import xyz.headsdown.core.keys.PlanPreimage
import xyz.headsdown.core.keys.RigMessageKind
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.core.keys.ShiftSignalPreimage
import xyz.headsdown.core.keys.SignedRigMessage
import java.util.Base64

/**
 * Phone → crank intake frames (shared contract A, WebSocket text frames at `/ws`). Integers are
 * exact JSON numbers written from the unsigned decimal (never through a Double), `rig` is base58,
 * `sig64` is standard base64 of the 64-byte low-S `r ‖ s`. The signed bytes are the v1.1
 * preimages (HEARTBEAT 94 B, BREAK / FREEZE 86 B) as `SHA-256(preimage)`; the crank reads
 * `Rig.p256_pubkey` from chain, so no key is sent.
 *
 * ```
 * {"type":"heartbeat","rig":…,"counter":N,"shift_id":N,"round_id":N,"lease_rounds":1..3,"sig64":…}
 * {"type":"break","rig":…,"counter":N,"shift_id":N,"reason":R,"sig64":…}     R ∈ {1,2,4,5,6,7,8}
 * {"type":"freeze","rig":…,"counter":N,"shift_id":N,"reason":3,"sig64":…}
 * ```
 *
 * The crank lands BREAK and FREEZE on-chain itself (P-256 path of `break_shift` / `freeze_rig`).
 */
object HeartbeatJson {
    const val TYPE_HEARTBEAT = "heartbeat"
    const val TYPE_BREAK = "break"
    const val TYPE_FREEZE = "freeze"

    fun encode(message: SignedRigMessage<*>): String {
        val rig = Pubkey(message.payload.rig).toBase58()
        val sig = Base64.getEncoder().encodeToString(message.signature)
        return when (val p = message.payload) {
            is HeartbeatPreimage -> buildJsonObject {
                put("type", TYPE_HEARTBEAT)
                put("rig", rig)
                put("counter", p.counter.toJsonNumber())
                put("shift_id", p.shiftId.toJsonNumber())
                put("round_id", p.roundId.toJsonNumber())
                put("lease_rounds", p.leaseRounds)
                put("sig64", sig)
            }
            is ShiftSignalPreimage -> {
                val type = if (p.kind == RigMessageKind.BREAK) {
                    require(p.reason.isBreakReason) { "BREAK reason must be 1, 2, 4, 5, 6, 7 or 8" }
                    TYPE_BREAK
                } else {
                    require(p.reason == ShiftEndReason.FREEZE) { "FREEZE frames carry reason 3" }
                    TYPE_FREEZE
                }
                buildJsonObject {
                    put("type", type)
                    put("rig", rig)
                    put("counter", p.counter.toJsonNumber())
                    put("shift_id", p.shiftId.toJsonNumber())
                    put("reason", p.reason.wire)
                    put("sig64", sig)
                }
            }
            // PLAN arms through the wallet path; a PLAN is never sent over the uplink.
            is PlanPreimage -> throw IllegalArgumentException("PLAN messages are not sent over the uplink")
        }.toString()
    }

    /** The frame type a message is sent as (for matching acks and for logs). */
    fun typeOf(message: SignedRigMessage<*>): String = when (val p = message.payload) {
        is HeartbeatPreimage -> TYPE_HEARTBEAT
        is ShiftSignalPreimage -> if (p.kind == RigMessageKind.BREAK) TYPE_BREAK else TYPE_FREEZE
        is PlanPreimage -> "plan"
    }

    /** A JSON number with the exact u64 value (kotlinx keeps unsigned literals unquoted). */
    private fun ULong.toJsonNumber(): Number = java.math.BigInteger(this.toString())
}

/** The `reason` code of a crank ack (contract A). Unknown codes map to [UNKNOWN]. */
enum class AckReason(val wire: String) {
    ACCEPTED("accepted"),
    BAD_SIGNATURE("bad_signature"),
    STALE_COUNTER("stale_counter"),
    UNKNOWN_RIG("unknown_rig"),
    RATE_LIMITED("rate_limited"),
    MALFORMED("malformed"),
    LEASE_INVALID("lease_invalid"),

    /** A code this build does not know (forward compatibility). */
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(code: String?): AckReason = entries.firstOrNull { it.wire == code && it != UNKNOWN } ?: UNKNOWN
    }
}

/** A frame from the crank. Only acks are acted upon; every other type is ignored. */
sealed interface CrankReply {
    /** `{"type":"ack","counter":N,"ok":true|false,"reason":"<code>"}` */
    data class Ack(val counter: ULong, val ok: Boolean, val reason: AckReason) : CrankReply

    /** A well-formed frame of another type (for example the crank's `status`). */
    data class Other(val type: String) : CrankReply

    companion object {
        /** Longest crank frame read; anything longer is ignored unread. */
        const val MAX_FRAME_CHARS = 4_096

        /**
         * Parses one crank text frame, or null when it is not a well-formed contract-A frame.
         * Unknown fields are ignored; `counter` may be a JSON number or a decimal string. Never
         * throws, and never returns the frame text.
         */
        fun parse(text: String): CrankReply? {
            if (text.length > MAX_FRAME_CHARS) return null
            val obj = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (type != "ack") return Other(type.take(32))
            val counter = u64(obj["counter"]) ?: return null
            val ok = (obj["ok"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
            val reasonText = (obj["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val reason = when {
                reasonText != null -> AckReason.fromWire(reasonText)
                ok -> AckReason.ACCEPTED
                else -> AckReason.UNKNOWN
            }
            return Ack(counter, ok, reason)
        }

        private fun u64(e: kotlinx.serialization.json.JsonElement?): ULong? {
            val p = e as? JsonPrimitive ?: return null
            if (p is JsonNull) return null
            val digits = p.content
            if (digits.isEmpty() || digits.length > 20 || !digits.all { it in '0'..'9' }) return null
            return digits.toULongOrNull()
        }
    }
}
