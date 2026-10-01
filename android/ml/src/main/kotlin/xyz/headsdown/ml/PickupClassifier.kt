package xyz.headsdown.ml

/**
 * One accelerometer sample: `SensorEvent.values` (m/s², Android convention: a phone lying
 * screen-down reads about (0, 0, -9.81)) and `SensorEvent.timestamp` (ns).
 */
data class AccelSample(val tNanos: Long, val x: Float, val y: Float, val z: Float)

/**
 * A motion window: every sample from [triggerNanos] - 2 s through the first sample at or after
 * [triggerNanos] + 3 s, as [xyz.headsdown.ml.pickup.PickupWindowCollector] cuts it on the phone
 * and the sensor lab records it for training.
 */
class MotionWindow(val triggerNanos: Long, val samples: List<AccelSample>)

enum class PickupVerdict {
    /** The phone was picked up: the caller breaks the shift (INTERFACE.md BREAK reason 1). */
    PICKUP,

    /** A bump, a slide or a set-down: no break from the classifier (the hard gates still apply). */
    NOT_PICKUP,
}

/** Why a window was classified PICKUP without asking the model (the policy is fail-closed). */
enum class FailClosedReason {
    /** Fewer than 100 samples between trigger - 2 s and trigger + 3 s. */
    TOO_FEW_SAMPLES,

    /** A hole longer than 0.5 s in that span (including its ends: no history, stream stalled). */
    GAP,

    /** The model produced a non-finite value. */
    NON_FINITE,

    /** No model could be loaded. */
    MODEL_UNAVAILABLE,
}

/**
 * The classifier's answer for one motion window.
 *
 * @property pPickup calibrated P(pickup), assuming the 50/50 prior of the training mix.
 * @property calibratedLogit the decision score; [verdict] is PICKUP iff it is >= the model's
 *   threshold (compared in logit space: probabilities saturate at 1.0).
 * @property failClosed set when the verdict is PICKUP because the window was unusable.
 */
data class PickupDecision(
    val pPickup: Double,
    val calibratedLogit: Double,
    val verdict: PickupVerdict,
    val failClosed: FailClosedReason?,
    val model: String,
) {
    val isPickup: Boolean get() = verdict == PickupVerdict.PICKUP

    companion object {
        /** `break_reason` / BREAK `reason` byte for a pickup (programs/heads-down/INTERFACE.md §3.5). */
        const val BREAK_REASON_PICKUP: Int = 1

        fun failClosed(reason: FailClosedReason, model: String) =
            PickupDecision(1.0, Double.POSITIVE_INFINITY, PickupVerdict.PICKUP, reason, model)
    }
}

/**
 * Motion window → P(pickup) + verdict. Accelerometer only (the Redmi 14C has no gyroscope and a
 * virtual proximity sensor).
 *
 * Bounds (see [ForemanGate]): the classifier can only ADD a break. It never un-breaks a shift and
 * never overrides the hard signals: screen-on, unlock, unplugging and the deterministic
 * FaceDownDetector stay outside the model. An unusable window is a PICKUP (fail-closed).
 */
interface PickupClassifier {
    val modelName: String

    fun classify(window: MotionWindow): PickupDecision
}
