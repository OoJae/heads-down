package xyz.headsdown.ml.pickup

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Flags the start of a motion event: the magnitude leaves ~1 g (a bump or a grab), or the
 * gravity direction swings away from where it rested (a slow lift or a slide that rotates the
 * phone). A refractory period keeps one physical event from firing many triggers.
 *
 * The production copy of the sensor lab's trigger (feature/shift src/debug `MotionWindows.kt`),
 * identical arithmetic, so on-device windows line up with the recorded training windows. The
 * Python port (`ml/foreman/classifier/trigger.py`) is pinned to this class by
 * `pickup_vectors.json` → `trigger`.
 */
class MotionTrigger(private val config: Config = Config()) {

    data class Config(
        /** |a| deviation from the resting magnitude that counts as motion, m/s². */
        val magnitudeThreshold: Double = 1.2,
        /** Gravity direction change from the resting direction that counts as motion, degrees. */
        val tiltThresholdDegrees: Double = 12.0,
        /** Fast low-pass (current posture) and slow low-pass (resting posture) time constants. */
        val fastTauMillis: Double = 80.0,
        val slowTauMillis: Double = 2_000.0,
        val refractoryMillis: Long = 3_000,
    )

    private var fast: DoubleArray? = null
    private var slow: DoubleArray? = null
    private var restMagnitude = 0.0
    private var lastNanos = 0L
    private var lastTriggerNanos = Long.MIN_VALUE

    /** True if this sample starts a new motion event. */
    fun onSample(tNanos: Long, x: Float, y: Float, z: Float): Boolean {
        val v = doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble())
        val mag = norm(v)
        val f = fast
        val sl = slow
        if (f == null || sl == null) {
            fast = v.copyOf()
            slow = v.copyOf()
            restMagnitude = mag
            lastNanos = tNanos
            return false
        }
        if (tNanos <= lastNanos) return false // duplicate or out-of-order timestamp
        val dtMillis = (tNanos - lastNanos) / 1e6
        lastNanos = tNanos
        blend(f, v, dtMillis / (config.fastTauMillis + dtMillis))
        val tilt = angleDegrees(f, sl)
        val jolt = abs(mag - restMagnitude)
        val moving = jolt > config.magnitudeThreshold || tilt > config.tiltThresholdDegrees
        // The resting reference only follows the phone while it is still, so a slow lift keeps
        // its full tilt instead of being averaged away.
        if (!moving) {
            val a = dtMillis / (config.slowTauMillis + dtMillis)
            blend(sl, f, a)
            restMagnitude += a * (mag - restMagnitude)
        }
        val refractory = lastTriggerNanos != Long.MIN_VALUE &&
            tNanos - lastTriggerNanos < config.refractoryMillis * 1_000_000
        if (moving && !refractory) {
            lastTriggerNanos = tNanos
            return true
        }
        return false
    }

    fun reset() {
        fast = null
        slow = null
        restMagnitude = 0.0
        lastNanos = 0L
        lastTriggerNanos = Long.MIN_VALUE
    }

    private fun blend(into: DoubleArray, target: DoubleArray, alpha: Double) {
        for (i in 0..2) into[i] += alpha * (target[i] - into[i])
    }

    private fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun angleDegrees(a: DoubleArray, b: DoubleArray): Double {
        val na = norm(a)
        val nb = norm(b)
        if (na < 1e-6 || nb < 1e-6) return 0.0
        val cos = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }
}
