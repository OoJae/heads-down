package xyz.headsdown.ml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One line of the planner log (ml/foreman/planner/LOG_SCHEMA.md): written by the app on the
 * phone, read only by the on-device planner, never uploaded.
 *
 * @property ts epoch milliseconds (UTC).
 * @property tz the UTC offset in minutes at that instant (`TimeZone.getDefault().getOffset(ts) / 60_000`).
 */
data class PlannerEvent(
    val ts: Long,
    val tz: Int,
    val type: Type,
    val alarmTs: Long? = null,
    val shiftId: Long? = null,
    val reason: Int? = null,
) {
    enum class Type(val wire: String) {
        MONITOR_START("monitor_start"),
        MONITOR_STOP("monitor_stop"),
        SCREEN_ON("screen_on"),
        SCREEN_OFF("screen_off"),
        USER_PRESENT("user_present"),
        POWER_CONNECTED("power_connected"),
        POWER_DISCONNECTED("power_disconnected"),
        FACE_DOWN_START("face_down_start"),
        FACE_DOWN_END("face_down_end"),
        ALARM_NEXT("alarm_next"),
        SHIFT_ARMED("shift_armed"),
        SHIFT_ENDED("shift_ended"),
        ;

        companion object {
            fun fromWire(s: String): Type? = entries.firstOrNull { it.wire == s }
        }
    }

    init {
        require(tz in -MAX_TZ_MINUTES..MAX_TZ_MINUTES) { "tz out of range" }
    }

    val localMillis: Long get() = ts + tz * 60_000L

    fun toJsonLine(): String = buildJsonObject {
        put("ts", ts)
        put("tz", tz)
        put("type", type.wire)
        alarmTs?.let { put("alarm_ts", it) }
        shiftId?.let { put("shift_id", it) }
        reason?.let { put("reason", it) }
    }.toString()

    companion object {
        const val MAX_TZ_MINUTES = 14 * 60
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses one JSONL line; null for blank, malformed or unknown-type lines (they are skipped). */
        fun parse(line: String): PlannerEvent? {
            if (line.isBlank()) return null
            val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
            val ts = obj.longField("ts") ?: return null
            val tz = (obj["tz"] as? JsonPrimitive)?.let { if (it.isString) null else it.intOrNull } ?: if (obj["tz"] == null) 0 else return null
            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(Type::fromWire) ?: return null
            if (tz !in -MAX_TZ_MINUTES..MAX_TZ_MINUTES) return null
            return PlannerEvent(ts, tz, type, obj.longField("alarm_ts"), obj.longField("shift_id"), obj.longField("reason")?.toInt())
        }

        fun parseLines(lines: Sequence<String>): List<PlannerEvent> = lines.mapNotNull(::parse).toList()

        private fun JsonObject.longField(key: String): Long? {
            val p = this[key] as? JsonPrimitive ?: return null
            if (p.isString || p.booleanOrNull != null) return null
            return p.longOrNull
        }
    }
}

/** What to plan for: the current time and zone, and optionally the wallet caps for a weekly split. */
data class PlanRequest(
    val nowTs: Long,
    val tzMinutes: Int,
    val caps: WalletCaps? = null,
    /** SOL per dig (`plan_dig_lamports`): the unit the weekly split allocates in. */
    val digLamports: Long = 0,
)

/** P(idle) for one 15-minute slot, with the conservative lower bound used for auto-arm. */
data class IdleSlot(val startTs: Long, val pIdle: Double, val lower: Double, val evidence: Double)

/** A proposed shift window (epoch ms). [autoArm] = confident enough to arm with zero taps. */
data class ShiftWindow(
    val startTs: Long,
    val endTs: Long,
    val slots: Int,
    val meanP: Double,
    val minP: Double,
    val expectedIdleHours: Double,
    val autoArm: Boolean,
    val reason: Reason,
) {
    enum class Reason(val wire: String) {
        /** Every slot's lower bound clears the auto-arm bar. */
        OK("ok"),

        /** Not enough nights of history yet. */
        HISTORY("history"),

        /** Some slot is not confidently idle. */
        CONFIDENCE("confidence"),
    }
}

/** One night of the weekly split. [lamports] is null when no caps were given. */
data class NightBudget(val window: ShiftWindow?, val expectedIdleRounds: Double, val lamports: Long?)

data class ShiftPlan(
    /** The next 24 hours from the slot containing now. */
    val slots: List<IdleSlot>,
    /** The best window starting within the next 24 hours (tonight's, when planned in the evening), ending by the next alarm. */
    val nextWindow: ShiftWindow?,
    /** The next 7 nights (index 0 = [nextWindow]) with the budget split when caps were given. */
    val week: List<NightBudget>,
    val nightsObserved: Int,
)

/**
 * The app's own logs → P(idle) per 15-minute slot for the next 24 h, the next shift window, and
 * a weekly budget split.
 *
 * Bounds: the planner only PROPOSES. A window becomes a P-256 PLAN only through
 * [ForemanGate.tighten] (inside the wallet caps, ending by caps_expiry); the split never exceeds
 * cap_shift per night or the week's remaining cap. Zero-tap auto-arm needs [ShiftWindow.autoArm]
 * and the phone actually face-down on the charger inside the window.
 */
interface ShiftPlanner {
    fun plan(events: List<PlannerEvent>, request: PlanRequest): ShiftPlan
}
