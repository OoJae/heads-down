package xyz.headsdown.feature.shift

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Accelerometer-only face-down detector. Works on the Redmi 14C (no gyroscope, virtual
 * proximity) and on any Android phone.
 *
 * Android reports the reaction to gravity: a phone lying screen-down reads about
 * `(0, 0, -9.81)` m/s². The detector:
 *
 * 1. **Low-pass filters** samples into a gravity estimate with time constant
 *    [Config.timeConstantMillis] (time-based alpha, so it is correct at any sample rate,
 *    including batched delivery).
 * 2. Computes the **tilt** between that gravity vector and "screen straight down"
 *    (`acos(-gz / |g|)`), and checks the raw magnitude is plausibly "at rest" (≈ 1 g).
 * 3. Applies **hysteresis** in both angle and time: enter face-down only after the tilt stays
 *    below [Config.enterTiltDegrees] for [Config.enterDwellMillis]; leave only after it stays
 *    above [Config.exitTiltDegrees] (or the phone is clearly being moved) for
 *    [Config.exitDwellMillis]. A nightstand bump is a single spike and does not flip state;
 *    a pickup rotates the phone and does.
 *
 * Pure Kotlin: feed it `SensorEvent` values and `SensorEvent.timestamp` (ns).
 */
class FaceDownDetector(private val config: Config = Config()) {

    data class Config(
        val timeConstantMillis: Long = 400,
        val enterTiltDegrees: Double = 25.0,
        val exitTiltDegrees: Double = 40.0,
        val enterDwellMillis: Long = 1_500,
        val exitDwellMillis: Long = 300,
        /** Raw |a| band (m/s²) treated as "at rest". Outside it the phone is being moved. */
        val restMinMagnitude: Double = 0.75 * STANDARD_GRAVITY,
        val restMaxMagnitude: Double = 1.25 * STANDARD_GRAVITY,
        /** A delivery gap longer than this resets the filter instead of blending stale data. */
        val maxGapMillis: Long = 5_000,
    ) {
        init {
            require(enterTiltDegrees < exitTiltDegrees) { "hysteresis needs enter < exit" }
            require(timeConstantMillis > 0 && enterDwellMillis >= 0 && exitDwellMillis >= 0)
        }
    }

    var isFaceDown: Boolean = false
        private set

    /** Last accepted sample time (ms, sensor timebase), or null before the first sample. */
    val lastSampleMillis: Long? get() = if (hasSample) lastMillis else null

    /** Current filtered tilt from "screen straight down", degrees; NaN before the first sample. */
    var tiltDegrees: Double = Double.NaN
        private set

    // Primitive fields with flags, not nullable Longs: this runs for every accelerometer sample
    // of a shift (50 Hz), and a boxed Long per sample is garbage the night does not need.
    private var hasSample = false
    private var lastMillis = 0L
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var hasCandidate = false
    private var candidateSince = 0L

    /** Feeds one sample; returns the (debounced) face-down verdict. Allocates nothing. */
    fun onSample(x: Float, y: Float, z: Float, timestampNanos: Long): Boolean {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return isFaceDown
        val t = timestampNanos / 1_000_000

        if (!hasSample || t - lastMillis > config.maxGapMillis) {
            gx = x.toDouble(); gy = y.toDouble(); gz = z.toDouble()
            hasCandidate = false
        } else {
            val dt = t - lastMillis
            if (dt <= 0) return isFaceDown // out-of-order or duplicate timestamp
            val alpha = dt.toDouble() / (config.timeConstantMillis + dt)
            gx += alpha * (x - gx)
            gy += alpha * (y - gy)
            gz += alpha * (z - gz)
        }
        lastMillis = t
        hasSample = true

        val gravity = sqrt(gx * gx + gy * gy + gz * gz)
        tiltDegrees = if (gravity < 1e-6) 180.0 else Math.toDegrees(acos((-gz / gravity).coerceIn(-1.0, 1.0)))
        val raw = sqrt(x.toDouble() * x + y.toDouble() * y + z.toDouble() * z)
        val atRest = raw >= config.restMinMagnitude && raw <= config.restMaxMagnitude

        val wantsFlip = if (isFaceDown) {
            tiltDegrees > config.exitTiltDegrees || !atRest
        } else {
            tiltDegrees < config.enterTiltDegrees && atRest
        }

        if (!wantsFlip) {
            hasCandidate = false
            return isFaceDown
        }
        if (!hasCandidate) {
            hasCandidate = true
            candidateSince = t
        }
        val dwell = if (isFaceDown) config.exitDwellMillis else config.enterDwellMillis
        if (t - candidateSince >= dwell) {
            isFaceDown = !isFaceDown
            hasCandidate = false
        }
        return isFaceDown
    }

    /** True if the last sample is recent enough to trust for a heartbeat decision. */
    fun isFresh(nowMillis: Long, maxAgeMillis: Long): Boolean {
        if (!hasSample) return false
        return nowMillis - lastMillis in 0..maxAgeMillis
    }

    fun reset() {
        isFaceDown = false
        hasSample = false
        tiltDegrees = Double.NaN
        hasCandidate = false
    }

    companion object {
        const val STANDARD_GRAVITY = 9.80665
    }
}
