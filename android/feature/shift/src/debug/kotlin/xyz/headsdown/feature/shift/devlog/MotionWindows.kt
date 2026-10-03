package xyz.headsdown.feature.shift.devlog

/*
 * DEBUG BUILDS ONLY (src/debug). Pure capture logic for the pickup/bump classifier's training
 * data: cut a window around each motion event in an accelerometer stream.
 * Accelerometer only: the Redmi 14C has no gyroscope.
 *
 * What counts as a motion event is decided by `xyz.headsdown.ml.pickup.MotionTrigger`, the one
 * trigger the app has: the shift service runs the same class, so recorded windows and on-device
 * windows start on the same sample. The lab has no trigger of its own.
 */

/** One accelerometer sample. [tNanos] is `SensorEvent.timestamp`; [recvNanos] is delivery time. */
data class AccelSample(val tNanos: Long, val recvNanos: Long, val x: Float, val y: Float, val z: Float)

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
