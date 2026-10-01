package xyz.headsdown.ml.pickup

import xyz.headsdown.ml.AccelSample
import xyz.headsdown.ml.MotionWindow

/**
 * Cuts classifier windows out of the live accelerometer stream: every sample from trigger - 2 s
 * (a ring buffer) through the first sample at or after trigger + 3 s, the same cut the sensor lab
 * records and `ml/foreman/classifier/trigger.py:cut_window` reproduces.
 *
 * Samples whose timestamp does not increase are ignored (as [MotionTrigger] does). Windows may
 * overlap; each is emitted once, on the sample that closes it. Pure Kotlin, single-threaded:
 * feed it from the sensor thread.
 */
class PickupWindowCollector(
    private val trigger: MotionTrigger = MotionTrigger(),
    private val preNanos: Long = PRE_NANOS,
    private val postNanos: Long = POST_NANOS,
    private val maxSamplesPerWindow: Int = 4_096,
    private val onWindow: (MotionWindow) -> Unit,
) {
    private val ring = ArrayDeque<AccelSample>()
    private val pending = ArrayList<Pending>(2)
    private var lastNanos = Long.MIN_VALUE

    private class Pending(val triggerNanos: Long, val samples: ArrayList<AccelSample>)

    /** Number of windows currently waiting for their post-trigger samples. */
    val openWindows: Int get() = pending.size

    fun onSample(s: AccelSample) {
        if (s.tNanos <= lastNanos) return
        lastNanos = s.tNanos
        val fired = trigger.onSample(s.tNanos, s.x, s.y, s.z)

        ring.addLast(s)
        while (ring.isNotEmpty() && s.tNanos - ring.first().tNanos > preNanos) ring.removeFirst()
        while (ring.size > maxSamplesPerWindow) ring.removeFirst()

        val it = pending.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.samples.size < maxSamplesPerWindow) p.samples += s
            if (s.tNanos >= p.triggerNanos + postNanos) {
                it.remove()
                onWindow(MotionWindow(p.triggerNanos, p.samples))
            }
        }
        if (fired) pending += Pending(s.tNanos, ArrayList(ring))
    }

    /** Drops the history and any open window (a new shift, or a sensor restart). */
    fun reset() {
        ring.clear()
        pending.clear()
        lastNanos = Long.MIN_VALUE
        trigger.reset()
    }

    companion object {
        const val PRE_NANOS = 2_000_000_000L
        const val POST_NANOS = 3_000_000_000L
    }
}
