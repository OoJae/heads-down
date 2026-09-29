package xyz.headsdown.core.chain.uplink

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.keys.HeartbeatPreimage
import xyz.headsdown.core.keys.PlanPreimage
import xyz.headsdown.core.keys.ShiftSignalPreimage
import xyz.headsdown.core.keys.SignedRigMessage
import java.util.Base64

/**
 * Wire JSON for the crank intake. u64 values are JSON numbers written from the exact unsigned
 * decimal (never through a Double), `rig` is base58, `sig` is base64 of the 64-byte low-S
 * `r ‖ s`. The crank re-derives everything else (program id, `Rig.p256_pubkey`) from chain.
 *
 * HEARTBEAT (exactly the six contract fields):
 * `{"rig":…, "counter":…, "shift_id":…, "round_id":…, "lease_rounds":…, "sig":…}`
 *
 * BREAK / FREEZE add a `kind` so they can never be mistaken for heartbeats:
 * `{"kind":"break"|"freeze", "rig":…, "counter":…, "shift_id":…, "reason":…, "sig":…}`
 */
object HeartbeatJson {
    fun encode(message: SignedRigMessage<*>): String {
        val rig = Pubkey(message.payload.rig).toBase58()
        val sig = Base64.getEncoder().encodeToString(message.signature)
        return when (val p = message.payload) {
            is HeartbeatPreimage -> buildJsonObject {
                put("rig", rig)
                put("counter", p.counter.toJsonNumber())
                put("shift_id", p.shiftId.toJsonNumber())
                put("round_id", p.roundId.toJsonNumber())
                put("lease_rounds", p.leaseRounds)
                put("sig", sig)
            }
            is ShiftSignalPreimage -> buildJsonObject {
                put("kind", p.kind.name.lowercase())
                put("rig", rig)
                put("counter", p.counter.toJsonNumber())
                put("shift_id", p.shiftId.toJsonNumber())
                put("reason", p.reason.wire)
                put("sig", sig)
            }
            // PLAN arms through the crank only once the P-256 arm path is wired; refuse for now.
            is PlanPreimage -> throw IllegalArgumentException("PLAN messages are not sent over the uplink")
        }.toString()
    }

    /** A JSON number with the exact u64 value (kotlinx keeps unsigned literals unquoted). */
    private fun ULong.toJsonNumber(): Number = java.math.BigInteger(this.toString())
}
