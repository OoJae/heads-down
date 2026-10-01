package xyz.headsdown.ml.planner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import xyz.headsdown.ml.json.arr
import xyz.headsdown.ml.json.doubles
import xyz.headsdown.ml.json.int
import xyz.headsdown.ml.json.ints
import xyz.headsdown.ml.json.num
import xyz.headsdown.ml.json.obj
import xyz.headsdown.ml.json.str

/**
 * Rhythm-model parameters (`planner_params.json`, format headsdown.foreman.planner v1), tuned
 * on synthetic archetypes by ml/foreman/planner/evaluate.py. See `ml/foreman/planner/model.py`.
 */
data class PlannerParams(
    val halfLifeDays: Double,
    val k0: Double,
    val k1: Double,
    val k2: Double,
    /** Circular smoothing weights over slot offsets -h..+h (odd length). */
    val kernel: List<Double>,
    /** Monday = 0. */
    val weekendDays: Set<Int>,
    val windowRule: WindowRule,
    /** Kadane: the per-slot bar. Survival: the bar for a run's first and last slot. */
    val windowThreshold: Double,
    /** Survival rule exponent (discounts the independence of slots). */
    val windowGamma: Double,
    val minWindowSlots: Int,
    val autoArmThreshold: Double,
    val autoArmZ: Double,
    val autoArmMinNights: Int,
    val horizonSlots: Int,
    val roundSeconds: Double,
    /** Population P(idle) per slot (96), used before the user has history. */
    val prior: List<Double>,
) {
    init {
        require(halfLifeDays > 0 && k0 > 0 && k1 > 0 && k2 > 0) { "positive shrinkage and half-life" }
        require(kernel.size % 2 == 1 && kernel.all { it >= 0 }) { "odd, non-negative kernel" }
        require(weekendDays.all { it in 0..6 })
        require(windowThreshold in 0.0..1.0 && autoArmThreshold in 0.0..1.0 && autoArmZ >= 0 && windowGamma > 0)
        require(minWindowSlots >= 1 && autoArmMinNights >= 0 && horizonSlots in 1..SLOTS_PER_DAY * 2 && roundSeconds > 0)
        require(prior.size == SLOTS_PER_DAY && prior.all { it in 0.0..1.0 }) { "96 prior probabilities" }
    }

    enum class WindowRule(val wire: String) {
        /** Maximize length x prod(p)^gamma: expected completed dark time. */
        SURVIVAL("survival"),

        /** Maximize the sum of (p - tau). */
        KADANE("kadane"),
        ;

        companion object {
            fun fromWire(s: String): WindowRule = entries.firstOrNull { it.wire == s } ?: throw IllegalArgumentException("unknown window rule $s")
        }
    }

    companion object {
        const val FORMAT = "headsdown.foreman.planner"
        const val SLOTS_PER_DAY = 96

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): PlannerParams {
            val doc = json.parseToJsonElement(text).jsonObject
            require(doc.str("format") == FORMAT) { "not a planner parameter file" }
            require(doc.int("version") == 1) { "unsupported planner parameter version" }
            val p = doc.obj("params")
            return PlannerParams(
                halfLifeDays = p.num("half_life_days"),
                k0 = p.num("k0"),
                k1 = p.num("k1"),
                k2 = p.num("k2"),
                kernel = p.arr("kernel").doubles().toList(),
                weekendDays = p.arr("weekend_days").ints().toSet(),
                windowRule = WindowRule.fromWire(p.str("window_rule")),
                windowThreshold = p.num("window_threshold"),
                windowGamma = p.num("window_gamma"),
                minWindowSlots = p.int("min_window_slots"),
                autoArmThreshold = p.num("auto_arm_threshold"),
                autoArmZ = p.num("auto_arm_z"),
                autoArmMinNights = p.int("auto_arm_min_nights"),
                horizonSlots = p.int("horizon_slots"),
                roundSeconds = p.num("round_seconds"),
                prior = p.arr("prior").doubles().toList(),
            )
        }

        /**
         * Used only if the shipped parameters fail to load: a flat 0.5 prior (no window is proposed
         * until the user's own nights clear the 0.8 bar) and auto-arm off.
         */
        val DEFAULT = PlannerParams(
            halfLifeDays = 21.0, k0 = 2.0, k1 = 4.0, k2 = 4.0, kernel = listOf(0.5, 1.0, 0.5),
            weekendDays = setOf(5, 6), windowRule = WindowRule.SURVIVAL, windowThreshold = 0.8, windowGamma = 0.5,
            minWindowSlots = 8, autoArmThreshold = 1.0,
            autoArmZ = 1.0, autoArmMinNights = 7, horizonSlots = SLOTS_PER_DAY, roundSeconds = 78.0,
            prior = List(SLOTS_PER_DAY) { 0.5 },
        )
    }
}
