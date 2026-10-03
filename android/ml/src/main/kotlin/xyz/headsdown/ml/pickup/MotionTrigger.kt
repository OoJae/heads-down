package xyz.headsdown.ml.pickup

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Flags the start of a motion event: the magnitude leaves ~1 g (a bump or a grab), or the
 * gravity direction swings away from where it rested (a slow lift or a slide that rotates the
 * phone). A refractory period keeps one physical event from firing many triggers.
 *
 * The one trigger in the app: the shift service and the debug sensor lab both use this class, so
 * on-device windows line up with the recorded training windows. The Python port
 * (`ml/foreman/classifier/trigger.py`) is pinned to it by `pickup_vectors.json` → `trigger`.
 *
 * [onSample] allocates nothing: it runs for every accelerometer sample of a shift.
 *
 * **On a live stream, use [live].** The resting reference only follows the phone while it is
 * still relative to that reference. A phone that comes to rest in a new posture (laid down after
 * arming in the hand, tipped on a pillow) therefore stays "moving" for good, and the plain
 * trigger re-fires every refractory period for the rest of the night. Two additions handle that
 * without touching the arithmetic the vectors pin:
 * - [isMoving] lets the caller tell a fresh motion event from such a re-fire;
 * - with a [rest] tracker, a trigger that stayed "moving" through a stretch the tracker calls
 *   rest takes that posture as its new resting reference ([settle]).
 *
 * The Python reference runs the plain trigger: every training stream starts with the phone
 * already lying there, which is the state [live] restores on the phone.
 */
class MotionTrigger(
    private val config: Config = Config(),
    private val rest: RestTracker? = null,
) {

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

    private var started = false

    // Fast low-pass: the current posture.
    private var fx = 0.0
    private var fy = 0.0
    private var fz = 0.0

    // Slow low-pass: the resting posture.
    private var sx = 0.0
    private var sy = 0.0
    private var sz = 0.0
    private var restMagnitude = 0.0
    private var lastNanos = 0L
    private var lastTriggerNanos = Long.MIN_VALUE

    /**
     * Whether the last sample was off the resting reference (a bump in progress, a hand, or a
     * posture the reference has not followed). A trigger that fires while this was already true
     * on the sample before is a re-fire of the same episode, not the start of a motion.
     */
    var isMoving: Boolean = false
        private set

    /** Timestamp of the sample that began the current run of moving samples. Meaningful while [isMoving]. */
    var movingSinceNanos: Long = 0L
        private set

    /** How often the [rest] tracker gave the trigger a new resting posture. */
    var settles: Int = 0
        private set

    /** True if this sample starts a new motion event. A sample with a non-finite value is ignored. */
    fun onSample(tNanos: Long, x: Float, y: Float, z: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false // it would poison the filters for good
        val vx = x.toDouble()
        val vy = y.toDouble()
        val vz = z.toDouble()
        val mag = sqrt(vx * vx + vy * vy + vz * vz)
        if (!started) {
            fx = vx
            fy = vy
            fz = vz
            sx = vx
            sy = vy
            sz = vz
            restMagnitude = mag
            lastNanos = tNanos
            started = true
            rest?.onSample(tNanos, x, y, z)
            return false
        }
        if (tNanos <= lastNanos) return false // duplicate or out-of-order timestamp
        val dtMillis = (tNanos - lastNanos) / 1e6
        lastNanos = tNanos
        val fast = dtMillis / (config.fastTauMillis + dtMillis)
        fx += fast * (vx - fx)
        fy += fast * (vy - fy)
        fz += fast * (vz - fz)
        val tilt = restAngleDegrees()
        val jolt = abs(mag - restMagnitude)
        val moving = jolt > config.magnitudeThreshold || tilt > config.tiltThresholdDegrees
        // The resting reference only follows the phone while it is still, so a slow lift keeps
        // its full tilt instead of being averaged away.
        if (!moving) {
            val slow = dtMillis / (config.slowTauMillis + dtMillis)
            sx += slow * (fx - sx)
            sy += slow * (fy - sy)
            sz += slow * (fz - sz)
            restMagnitude += slow * (mag - restMagnitude)
        }
        if (moving && !isMoving) movingSinceNanos = tNanos
        isMoving = moving
        val refractory = lastTriggerNanos != Long.MIN_VALUE &&
            tNanos - lastTriggerNanos < config.refractoryMillis * 1_000_000
        var fired = false
        if (moving && !refractory) {
            lastTriggerNanos = tNanos
            fired = true
        }
        // "At rest" speaks for the blocks before this sample, "moving" for this sample. Only a
        // trigger that was moving through all of that stillness is stuck on an old posture (a
        // knock that lands on a block boundary is not).
        if (rest != null && rest.onSample(tNanos, x, y, z) && moving &&
            tNanos - movingSinceNanos >= rest.evidenceNanos
        ) {
            settle(rest.restX, rest.restY, rest.restZ)
            settles++
        }
        return fired
    }

    /**
     * The phone lies still with gravity ([x], [y], [z]) m/s²: the resting reference restarts
     * from that posture, and the next motion fires at once (the refractory period is cleared).
     * Non-finite input is ignored. Does not touch the arithmetic of [onSample].
     */
    fun settle(x: Double, y: Double, z: Double) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        fx = x
        fy = y
        fz = z
        sx = x
        sy = y
        sz = z
        restMagnitude = sqrt(x * x + y * y + z * z)
        isMoving = false
        lastTriggerNanos = Long.MIN_VALUE
        started = true
    }

    fun reset() {
        started = false
        isMoving = false
        restMagnitude = 0.0
        lastNanos = 0L
        lastTriggerNanos = Long.MIN_VALUE
        rest?.reset()
    }

    /** Angle between the current posture and the resting posture, degrees. */
    private fun restAngleDegrees(): Double {
        val na = sqrt(fx * fx + fy * fy + fz * fz)
        val nb = sqrt(sx * sx + sy * sy + sz * sz)
        if (na < 1e-6 || nb < 1e-6) return 0.0
        val cos = ((fx * sx + fy * sy + fz * sz) / (na * nb)).coerceIn(-1.0, 1.0)
        // The constant java.lang.Math.toDegrees multiplies by on current runtimes (and the Python
        // port's RAD_TO_DEG), spelled out so every Android release computes the same value.
        return acos(cos) * PickupFeatures.RAD_TO_DEG
    }

    companion object {
        /**
         * The trigger as the phone runs it (shift service and sensor lab alike): the pinned
         * arithmetic, plus coming back to rest after the phone settles in a new posture.
         */
        fun live(config: Config = Config()): MotionTrigger = MotionTrigger(config, RestTracker())
    }
}
