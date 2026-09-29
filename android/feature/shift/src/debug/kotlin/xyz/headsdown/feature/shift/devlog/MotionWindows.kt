package xyz.headsdown.feature.shift.devlog

import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * DEBUG BUILDS ONLY (src/debug). Pure capture logic for the pickup/bump classifier's training
 * data: find motion events in an accelerometer stream and cut a window around each one.
 * Accelerometer only: the Redmi 14C has no gyroscope.
 */

/** One accelerometer sample. [tNanos] is `SensorEvent.timestamp`; [recvNanos] is delivery time. */
data class AccelSample(val tNanos: Long, val recvNanos: Long, val x: Float, val y: Float, val z: Float)

/**
 * Flags the start of a motion event: the magnitude leaves ~1 g (a bump or a grab), or the
 * gravity direction swings away from where it rested (a slow lift or a slide that rotates the
 * phone). A refractory period keeps one physical event from firing many triggers.
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
    fun onSample(s: AccelSample): Boolean {
        val v = doubleArrayOf(s.x.toDouble(), s.y.toDouble(), s.z.toDouble())
        val mag = norm(v)
        val f = fast
        val sl = slow
        if (f == null || sl == null) {
            fast = v.copyOf()
            slow = v.copyOf()
            restMagnitude = mag
            lastNanos = s.tNanos
            return false
        }
        if (s.tNanos <= lastNanos) return false // duplicate or out-of-order timestamp
        val dtMillis = (s.tNanos - lastNanos) / 1e6
        lastNanos = s.tNanos
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
            s.tNanos - lastTriggerNanos < config.refractoryMillis * 1_000_000
        if (moving && !refractory) {
            lastTriggerNanos = s.tNanos
            return true
        }
        return false
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

/** A captured window: [preMillis] before the trigger to [postMillis] after the last trigger. */
data class MotionWindow(val id: Int, val triggerNanos: Long, val samples: List<AccelSample>)

/**
 * Keeps a rolling pre-trigger buffer and emits one [MotionWindow] per motion event. A trigger
 * while a window is open extends it (up to [maxMillis]), so a pickup that jostles twice stays
 * one window.
 */
class WindowRecorder(
    private val preMillis: Long = 2_000,
    private val postMillis: Long = 3_000,
    private val maxMillis: Long = 10_000,
    private val onWindow: (MotionWindow) -> Unit,
) {
    private val ring = ArrayDeque<AccelSample>()
    private var open: MutableList<AccelSample>? = null
    private var openTrigger = 0L
    private var openStart = 0L
    private var closeAt = 0L
    private var nextId = 1

    val windowsEmitted: Int get() = nextId - 1

    fun onSample(s: AccelSample, triggered: Boolean) {
        val window = open
        if (window != null) {
            window += s
            if (triggered) closeAt = minOf(s.tNanos + postMillis * MS, openStart + maxMillis * MS)
            if (s.tNanos >= closeAt) emit()
            return
        }
        ring.addLast(s)
        while (ring.isNotEmpty() && s.tNanos - ring.first().tNanos > preMillis * MS) ring.removeFirst()
        if (triggered) {
            open = ring.toMutableList()
            ring.clear()
            openTrigger = s.tNanos
            openStart = open!!.first().tNanos
            closeAt = minOf(s.tNanos + postMillis * MS, openStart + maxMillis * MS)
        }
    }

    /** Emits a window still open (the session is ending). */
    fun flush() {
        if (open != null) emit()
    }

    private fun emit() {
        val samples = open ?: return
        open = null
        onWindow(MotionWindow(nextId++, openTrigger, samples.toList()))
    }

    private companion object {
        const val MS = 1_000_000L
    }
}
