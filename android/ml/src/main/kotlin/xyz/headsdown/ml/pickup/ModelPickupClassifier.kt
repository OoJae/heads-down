package xyz.headsdown.ml.pickup

import xyz.headsdown.ml.FailClosedReason
import xyz.headsdown.ml.MotionWindow
import xyz.headsdown.ml.PickupClassifier
import xyz.headsdown.ml.PickupDecision
import xyz.headsdown.ml.PickupVerdict

/**
 * [PickupClassifier] over a [PickupModelDocument]: feature spec v1, the model's raw logit, Platt
 * calibration, and the threshold compared in logit space. Fail-closed: an unusable window
 * (too few samples, a gap, no history) or a non-finite score is a PICKUP.
 *
 * [backend] optionally replaces the model's own evaluation (the LiteRT `.tflite` path for the
 * CNN). If the backend throws, the pure-Kotlin model answers instead, so a missing runtime can
 * never turn a pickup into a non-pickup.
 */
class ModelPickupClassifier(
    private val document: PickupModelDocument,
    private val backend: LogitBackend? = null,
) : PickupClassifier {

    /** An alternative evaluator of the same model (for example LiteRT). */
    fun interface LogitBackend {
        fun logit(features: DoubleArray, channels: Array<DoubleArray>): Double
    }

    override val modelName: String get() = document.name

    override fun classify(window: MotionWindow): PickupDecision {
        val ex = PickupFeatures.extract(window)
        val reason = ex.failReason
        if (reason != null) return PickupDecision.failClosed(reason, modelName)
        val features = ex.features ?: return PickupDecision.failClosed(FailClosedReason.NON_FINITE, modelName)
        val channels = ex.channels ?: return PickupDecision.failClosed(FailClosedReason.NON_FINITE, modelName)
        if (features.any { !it.isFinite() }) return PickupDecision.failClosed(FailClosedReason.NON_FINITE, modelName)
        val z = backend?.let { runCatching { it.logit(features, channels) }.getOrNull() }
            ?: document.model.logit(features, channels)
        if (!z.isFinite()) return PickupDecision.failClosed(FailClosedReason.NON_FINITE, modelName)
        val lg = document.calibratedLogit(z)
        val verdict = if (lg >= document.thresholdLogit) PickupVerdict.PICKUP else PickupVerdict.NOT_PICKUP
        return PickupDecision(document.probability(lg), lg, verdict, null, modelName)
    }
}

/** A classifier for when no model could be loaded: every window is a PICKUP (fail-closed). */
object FailClosedPickupClassifier : PickupClassifier {
    override val modelName: String = "fail-closed"

    override fun classify(window: MotionWindow): PickupDecision =
        PickupDecision.failClosed(FailClosedReason.MODEL_UNAVAILABLE, modelName)
}
