package xyz.headsdown.ui.slab

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Turns raw accelerometer samples into a steady up-vector: a One Euro filter on the unit vector,
 * with a time-based alpha so the sensor's jitter in delivery does not change the smoothing.
 * Slow hands get a low cutoff (no shimmer at rest), a fast turn raises it (no lag in a flip).
 *
 * Pure Kotlin, no allocation after construction, single-threaded.
 */
class GravityFilter(
    private val minCutoffHz: Float = 1.2f,
    /** Added to the cutoff per degree per second of turn. */
    private val betaPerDegreePerSecond: Float = 0.035f,
    private val derivativeCutoffHz: Float = 1f,
) {
    /** The filtered up-vector in the display's axes, unit length once [hasValue]. */
    var x = 0f
        private set
    var y = 0f
        private set
    var z = 1f
        private set
    var hasValue = false
        private set

    /** How fast the filtered vector is turning, degrees per second. */
    var speedDegreesPerSecond = 0f
        private set

    private var dx = 0f
    private var dy = 0f
    private var dz = 0f
    private var lastNanos = 0L

    fun reset() {
        hasValue = false
        speedDegreesPerSecond = 0f
        dx = 0f; dy = 0f; dz = 0f
    }

    /**
     * Feeds one sample (m/s^2, any consistent axes) with the sensor's timestamp. Returns true if
     * the filtered vector was updated. A sample is IGNORED when it is not finite, when its length
     * is more than [MAX_OFF_G] g away from 1 g (the phone is being shaken or dropped: that is not
     * gravity), and when time did not move forward. A gap over [RESET_GAP_NANOS] starts again
     * from the sample.
     */
    fun add(ax: Float, ay: Float, az: Float, timestampNanos: Long): Boolean {
        if (!SlabFrame.isFinite(ax) || !SlabFrame.isFinite(ay) || !SlabFrame.isFinite(az)) return false
        val length = sqrt(ax * ax + ay * ay + az * az)
        if (abs(length - G) > MAX_OFF_G * G) return false
        val ux = ax / length
        val uy = ay / length
        val uz = az / length
        if (!hasValue || timestampNanos - lastNanos > RESET_GAP_NANOS) {
            x = ux; y = uy; z = uz
            dx = 0f; dy = 0f; dz = 0f
            speedDegreesPerSecond = 0f
            lastNanos = timestampNanos
            hasValue = true
            return true
        }
        val dtNanos = timestampNanos - lastNanos
        if (dtNanos <= 0L) return false
        val dt = dtNanos * 1e-9f
        lastNanos = timestampNanos

        // The derivative of the unit vector is its angular speed, in radians per second.
        val da = alpha(derivativeCutoffHz, dt)
        dx += da * ((ux - x) / dt - dx)
        dy += da * ((uy - y) / dt - dy)
        dz += da * ((uz - z) / dt - dz)
        val speed = sqrt(dx * dx + dy * dy + dz * dz) * DEGREES
        speedDegreesPerSecond = speed

        val a = alpha(minCutoffHz + betaPerDegreePerSecond * speed, dt)
        val fx = x + a * (ux - x)
        val fy = y + a * (uy - y)
        val fz = z + a * (uz - z)
        val n = sqrt(fx * fx + fy * fy + fz * fz)
        if (n < 1e-4f) {
            // Opposite vectors averaged to nothing (a flip between two samples): take the new one.
            x = ux; y = uy; z = uz
        } else {
            x = fx / n; y = fy / n; z = fz / n
        }
        return true
    }

    private fun alpha(cutoffHz: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dt)
    }

    companion object {
        const val G = 9.80665f
        const val MAX_OFF_G = 0.35f
        const val RESET_GAP_NANOS = 250_000_000L
        internal const val DEGREES = (180.0 / PI).toFloat()
    }
}

/**
 * Maps the filtered up-vector to the slab's pose.
 *
 * The slab stays level with the real world, like a bubble level, so the top face's normal is the
 * up-vector. A literal level would show the underside only once the screen is already out of
 * sight, so the lean GAINS on the tilt around the angle the phone is usually held at: the
 * underside is fully presented by the time the phone is upright.
 *
 * Pure Kotlin, no allocation, single-threaded.
 */
class TiltMapper {
    /** The pose: a tilt vector in degrees (see [SlabFrame]). */
    var poseX = 0f
        private set
    var poseY = SlabGeometry.REST_DEGREES
        private set

    /** The phone's own angle: 0 lying face-up, 90 upright, 180 face-down. */
    var thetaDegrees = NEUTRAL_START_DEGREES
        private set

    /** The angle the phone is usually held at: a slow average of [thetaDegrees] while it is steady. */
    var neutralDegrees = NEUTRAL_START_DEGREES
        private set

    /**
     * Updates the pose from an up-vector of unit length. [dtSeconds] is the time since the last
     * call and [steady] says the phone is not turning; both only move the neutral angle.
     */
    fun update(ux: Float, uy: Float, uz: Float, dtSeconds: Float, steady: Boolean) {
        val theta = acos(uz.coerceIn(-1f, 1f)) * GravityFilter.DEGREES
        thetaDegrees = theta
        if (steady && dtSeconds > 0f && theta in NEUTRAL_MIN_DEGREES..NEUTRAL_MAX_DEGREES) {
            val gap = theta - neutralDegrees
            // Close enough is left alone, so a phone on a stand stops moving the slab at all.
            if (abs(gap) > NEUTRAL_SETTLED_DEGREES) neutralDegrees += gap * (1f - exp(-dtSeconds / NEUTRAL_SECONDS))
        }

        // Where the normal leans on screen. Flat on a table the horizontal part is all noise, so
        // the direction fades to straight up before it can spin.
        val h = sqrt(ux * ux + uy * uy)
        var dirX = 0f
        var dirY = 1f
        if (h > DIRECTION_NONE) {
            val t = ((h - DIRECTION_NONE) / (DIRECTION_FULL - DIRECTION_NONE)).coerceIn(0f, 1f)
            val w = t * t * (3f - 2f * t)
            val bx = w * ux / h
            val by = (1f - w) + w * uy / h
            val n = sqrt(bx * bx + by * by)
            if (n > 1e-3f) {
                dirX = bx / n
                dirY = by / n
            }
        }
        val lean = (SlabGeometry.REST_DEGREES + GAIN * (theta - neutralDegrees)).coerceIn(MIN_LEAN_DEGREES, MAX_LEAN_DEGREES)
        poseX = dirX * lean
        poseY = dirY * lean
    }

    companion object {
        const val GAIN = 1.8f
        const val MIN_LEAN_DEGREES = 30f
        const val MAX_LEAN_DEGREES = 158f
        const val NEUTRAL_START_DEGREES = 45f
        const val NEUTRAL_MIN_DEGREES = 15f
        const val NEUTRAL_MAX_DEGREES = 75f
        const val NEUTRAL_SECONDS = 4f
        const val NEUTRAL_SETTLED_DEGREES = 0.02f

        /** The horizontal part of the up-vector below which the lean has no direction of its own. */
        const val DIRECTION_NONE = 0.1f
        const val DIRECTION_FULL = 0.3f

        /** Below this turn rate the phone counts as held steady. */
        const val STEADY_DEGREES_PER_SECOND = 8f
    }
}
