package xyz.headsdown.feature.shift.foreman

import xyz.headsdown.ml.FailClosedReason
import xyz.headsdown.ml.ForemanGate
import xyz.headsdown.ml.MotionWindow
import xyz.headsdown.ml.PickupClassifier
import xyz.headsdown.ml.PickupDecision
import xyz.headsdown.ml.pickup.MotionTrigger
import xyz.headsdown.ml.pickup.PickupWindowCollector
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * The pickup classifier's place in a shift: every accelerometer sample goes in, and the only
 * thing that ever comes out is "this was a pickup" for a rig that was hot the whole time.
 *
 * Which windows the model is asked about (all three must hold, or the window is dropped unread):
 * 1. **A fresh motion.** The trigger fired on the sample the phone left its resting posture
 *    ([PickupWindowCollector.Listener.onTrigger] `onset`). A re-fire of the same episode is not
 *    judged: the model was trained on onsets, and it calls a window with no motion in it a
 *    PICKUP. [MotionTrigger.live] brings the trigger back to rest once the phone lies still, so
 *    the next motion is an onset again.
 * 2. **Hot when it began.** The rig was DOWN with the screen off on the trigger sample. The
 *    set-down that starts a shift is not a candidate pickup.
 * 3. **Hot until it closed**, without a break in between. A rig that left DOWN in those 3 s is
 *    already cooling under the deterministic rules (tilt, screen-on, unplug), and its grace
 *    window is theirs to run.
 *
 * What a verdict can do: PICKUP (or any unusable window, or a classifier that fails: fail-closed)
 * calls [onPickup], which dispatches `ShiftEvent.PickupDetected`. NOT_PICKUP does nothing at all:
 * no callback, no state, no change to the trigger. So the model can add a break and nothing else.
 *
 * [enabled] is the switch that takes the classifier out of the shift ([ForemanSettings.pickupBreaksEnabled]):
 * off, no window is judged and nothing is ever called back, which leaves exactly the
 * deterministic rules. It is read when a window closes, so it takes effect at once.
 *
 * Threads: [onSample] on the sensor thread (it allocates nothing until a window closes),
 * [onRig] and [clear] on the main thread, classification on [executor]; [onPickup] is called on
 * the executor's thread.
 */
internal class PickupWatch(
    /** Resolved on [executor], so a model loads off the main thread. */
    private val classifier: () -> PickupClassifier,
    private val executor: Executor,
    private val onPickup: (PickupDecision) -> Unit,
    private val enabled: () -> Boolean = { true },
    trigger: MotionTrigger = MotionTrigger.live(),
) : PickupWindowCollector.Listener {

    /** What the watch has seen, for tests and the debug screen. Counts since the service started. */
    data class Stats(
        val triggers: Int = 0,
        /** Triggers that were not the start of a motion (rule 1). */
        val reFires: Int = 0,
        /** Onsets while the rig was not hot (rule 2). */
        val notHot: Int = 0,
        /** Windows whose rig left DOWN before they closed, or before the verdict (rule 3). */
        val interrupted: Int = 0,
        val judged: Int = 0,
        val pickups: Int = 0,
        /** Windows that would have been judged while the classifier was switched off. */
        val switchedOff: Int = 0,
    )

    private val collector = PickupWindowCollector(trigger = trigger, listener = this)

    /** Bumped on every change of "hot": odd while the rig is DOWN with the screen off. */
    @Volatile private var epoch = 0

    /**
     * The pickup verdict that has not been turned into a break yet (the instant between the
     * executor and the main thread). `ForemanGate.heartbeatAllowed` reads it, so not even that
     * instant can sign a heartbeat.
     */
    @Volatile var veto: PickupDecision? = null
        private set

    // Each counter has one writer: the sensor thread for the first five, the executor for the rest.
    @Volatile private var triggers = 0
    @Volatile private var reFires = 0
    @Volatile private var notHot = 0
    @Volatile private var interruptedWindows = 0
    @Volatile private var switchedOff = 0
    @Volatile private var staleVerdicts = 0
    @Volatile private var judged = 0
    @Volatile private var pickups = 0

    val stats: Stats get() = Stats(triggers, reFires, notHot, interruptedWindows + staleVerdicts, judged, pickups, switchedOff)

    /** Main thread, after every transition: is the rig DOWN with the screen off? */
    fun onRig(hot: Boolean) {
        val now = epoch
        if (hot != (now and 1 == 1)) epoch = now + 1
    }

    /** Main thread: the verdict was handled (or a new shift starts). */
    fun clear() {
        veto = null
    }

    /** Sensor thread: every accelerometer sample of the service's life. */
    fun onSample(tNanos: Long, x: Float, y: Float, z: Float) = collector.onSample(tNanos, x, y, z)

    override fun onTrigger(triggerNanos: Long, onset: Boolean): Int {
        triggers++
        val now = epoch
        return when {
            !onset -> NOT_JUDGED.also { reFires++ }
            now and 1 == 0 -> NOT_JUDGED.also { notHot++ }
            else -> now
        }
    }

    override fun onWindow(window: MotionWindow, tag: Int) {
        if (tag == NOT_JUDGED) return
        if (tag != epoch) {
            interruptedWindows++
            return
        }
        val on = try {
            enabled()
        } catch (_: RuntimeException) {
            true // a switch that cannot be read does not take the classifier out
        }
        if (!on) {
            switchedOff++
            return
        }
        try {
            executor.execute {
                try {
                    judge(window, tag)
                } catch (_: RuntimeException) {
                    // Nothing may escape an executor thread (it would take the app down). The
                    // veto, if it was set, already stops heartbeats.
                }
            }
        } catch (_: RejectedExecutionException) {
            // The service is shutting down: the shift is over, there is nothing left to break.
        }
    }

    private fun judge(window: MotionWindow, tag: Int) {
        val decision = try {
            classifier().classify(window)
        } catch (_: RuntimeException) {
            // A classifier that cannot answer is no reason to keep digging.
            PickupDecision.failClosed(FailClosedReason.MODEL_UNAVAILABLE, "fail-closed")
        }
        judged++
        if (ForemanGate.breakReason(decision) == null) return // not a pickup: nothing happens
        if (tag != epoch) {
            staleVerdicts++ // the rig left DOWN while the model ran: the deterministic rules have it
            return
        }
        veto = decision
        pickups++
        onPickup(decision)
    }

    private companion object {
        const val NOT_JUDGED = -1
    }
}
