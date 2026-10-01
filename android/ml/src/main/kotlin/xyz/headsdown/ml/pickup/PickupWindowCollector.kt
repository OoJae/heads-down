package xyz.headsdown.ml.pickup

import xyz.headsdown.ml.AccelSample
import xyz.headsdown.ml.MotionWindow

/**
 * Cuts classifier windows out of the live accelerometer stream: every sample from trigger - 2 s
 * through the first sample at or after trigger + 3 s, the same cut the sensor lab records and
 * `ml/foreman/classifier/trigger.py:cut_window` reproduces.
 *
 * Samples whose timestamp does not increase are ignored (as [MotionTrigger] does), and so are
 * samples with a non-finite value (the feature pipeline would drop them anyway, and one would
 * poison the trigger's filters for good). Windows may overlap; each is emitted once, on the
 * sample that closes it.
 *
 * Built for the shift service's sensor thread, which sees every sample of a night:
 * - [onSample] allocates nothing while no window closes. History lives in fixed primitive ring
 *   buffers of [maxSamplesPerWindow] samples (5 s at up to ~800 Hz; Android caps apps at 200 Hz).
 *   A [MotionWindow] is built only on the sample that closes one.
 * - A window that needed more history than the ring holds loses its oldest samples. More than
 *   0.5 s of that fails the feature spec's gap check, so it is a PICKUP (fail-closed).
 *
 * With a [rest] tracker, a trigger left "moving" by a posture the phone has since settled into
 * gets that posture as its new resting reference ([MotionTrigger.settle]). Without one (the
 * default) the trigger behaves exactly as the Python reference does.
 *
 * Pure Kotlin, single-threaded: feed it from one thread. The listener runs on that thread and
 * must not call back into the collector, except [reset].
 */
class PickupWindowCollector(
    private val trigger: MotionTrigger = MotionTrigger(),
    private val preNanos: Long = PRE_NANOS,
    private val postNanos: Long = POST_NANOS,
    private val maxSamplesPerWindow: Int = 4_096,
    private val rest: RestTracker? = null,
    private val listener: Listener,
) {
    interface Listener {
        /**
         * A trigger fired at [triggerNanos] and opened a window.
         *
         * [onset] tells a fresh motion event from a re-fire: true when the phone was at its
         * resting posture on the sample before, false when it was already off it (the same
         * episode outlasting the refractory period, or a posture the trigger's reference has not
         * followed). The classifier was trained on onsets only.
         *
         * The returned tag comes back with that window in [onWindow].
         */
        fun onTrigger(triggerNanos: Long, onset: Boolean): Int = 0

        /** The window is complete. Called on the sample that closes it. */
        fun onWindow(window: MotionWindow, tag: Int)
    }

    /** Every window, whatever fired it (what the vectors and the sensor lab expect). */
    constructor(
        trigger: MotionTrigger = MotionTrigger(),
        preNanos: Long = PRE_NANOS,
        postNanos: Long = POST_NANOS,
        maxSamplesPerWindow: Int = 4_096,
        onWindow: (MotionWindow) -> Unit,
    ) : this(trigger, preNanos, postNanos, maxSamplesPerWindow, null, WindowsOnly(onWindow))

    private class WindowsOnly(private val sink: (MotionWindow) -> Unit) : Listener {
        override fun onWindow(window: MotionWindow, tag: Int) = sink(window)
    }

    init {
        require(maxSamplesPerWindow > 0 && preNanos >= 0 && postNanos >= 0)
    }

    // The last [maxSamplesPerWindow] samples; sample number n lives at index n % capacity.
    private val times = LongArray(maxSamplesPerWindow)
    private val xs = FloatArray(maxSamplesPerWindow)
    private val ys = FloatArray(maxSamplesPerWindow)
    private val zs = FloatArray(maxSamplesPerWindow)

    /** Samples accepted since the last reset: the number of the next one. */
    private var count = 0L

    /** Number of the oldest sample within [preNanos] of the newest: where a window would start. */
    private var historyStart = 0L

    // Open windows, oldest trigger first (they close in that order: the post time is constant).
    private var openTrigger = LongArray(INITIAL_OPEN)
    private var openStart = LongArray(INITIAL_OPEN)
    private var openTag = IntArray(INITIAL_OPEN)
    private var open = 0

    private var lastNanos = Long.MIN_VALUE

    /** Bumped by [reset], so a listener that resets from a callback ends the sample cleanly. */
    private var generation = 0

    /** Number of windows currently waiting for their post-trigger samples. */
    val openWindows: Int get() = open

    /** How often the [rest] tracker gave the trigger a new resting posture. */
    var settles: Int = 0
        private set

    fun onSample(s: AccelSample) = onSample(s.tNanos, s.x, s.y, s.z)

    fun onSample(tNanos: Long, x: Float, y: Float, z: Float) {
        if (tNanos <= lastNanos) return
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        lastNanos = tNanos
        val wasMoving = trigger.isMoving
        val fired = trigger.onSample(tNanos, x, y, z)

        val capacity = maxSamplesPerWindow
        val number = count
        val slot = (number % capacity).toInt()
        times[slot] = tNanos
        xs[slot] = x
        ys[slot] = y
        zs[slot] = z
        count = number + 1

        var start = historyStart
        val oldest = count - capacity
        if (start < oldest) start = oldest
        while (start < number && tNanos - times[(start % capacity).toInt()] > preNanos) start++
        historyStart = start

        val entered = generation
        var i = 0
        while (i < open) {
            if (tNanos < openTrigger[i] + postNanos) {
                i++
                continue
            }
            val window = cut(openTrigger[i], openStart[i], number)
            val tag = openTag[i]
            close(i)
            listener.onWindow(window, tag)
            if (generation != entered) return
        }
        if (fired) {
            val tag = listener.onTrigger(tNanos, onset = !wasMoving)
            if (generation != entered) return
            if (open == openTrigger.size) grow()
            openTrigger[open] = tNanos
            openStart[open] = historyStart
            openTag[open] = tag
            open++
        }
        // "At rest" speaks for the blocks before this sample; "moving" for this sample. Only a
        // trigger that was moving through all of that stillness is stuck on an old posture (a
        // knock that lands on a block boundary is not).
        if (rest != null && rest.onSample(tNanos, x, y, z) && trigger.isMoving &&
            tNanos - trigger.movingSinceNanos >= rest.evidenceNanos
        ) {
            trigger.settle(rest.restX, rest.restY, rest.restZ)
            settles++
        }
    }

    /** Drops the history and any open window (a sensor restart). */
    fun reset() {
        generation++
        count = 0L
        historyStart = 0L
        open = 0
        lastNanos = Long.MIN_VALUE
        trigger.reset()
        rest?.reset()
    }

    /** Samples [from]..[to] (numbers), as far as the ring still holds them. */
    private fun cut(triggerNanos: Long, from: Long, to: Long): MotionWindow {
        val capacity = maxSamplesPerWindow
        var n = maxOf(from, to + 1 - capacity)
        val samples = ArrayList<AccelSample>((to - n + 1).toInt())
        while (n <= to) {
            val slot = (n % capacity).toInt()
            samples += AccelSample(times[slot], xs[slot], ys[slot], zs[slot])
            n++
        }
        return MotionWindow(triggerNanos, samples)
    }

    private fun close(index: Int) {
        for (j in index until open - 1) {
            openTrigger[j] = openTrigger[j + 1]
            openStart[j] = openStart[j + 1]
            openTag[j] = openTag[j + 1]
        }
        open--
    }

    private fun grow() {
        val size = openTrigger.size * 2
        openTrigger = openTrigger.copyOf(size)
        openStart = openStart.copyOf(size)
        openTag = openTag.copyOf(size)
    }

    companion object {
        const val PRE_NANOS = 2_000_000_000L
        const val POST_NANOS = 3_000_000_000L
        private const val INITIAL_OPEN = 4
    }
}
