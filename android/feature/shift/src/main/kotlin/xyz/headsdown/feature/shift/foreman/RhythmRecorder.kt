package xyz.headsdown.feature.shift.foreman

import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.feature.shift.Transition
import xyz.headsdown.feature.shift.isRunning
import xyz.headsdown.feature.shift.spec
import xyz.headsdown.feature.shift.wireReason
import xyz.headsdown.ml.PlannerEvent
import xyz.headsdown.ml.PlannerEvent.Type
import java.util.TimeZone
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Writes the planner log from what the shift service already observes: the screen, unlock,
 * charger and face-down signals, the next alarm and the shift's own lifecycle
 * (ml/foreman/planner/LOG_SCHEMA.md). It adds no sensor, permission or receiver of its own
 * beyond the next-alarm broadcast, and what it writes stays in [PlannerLog].
 *
 * Every event is stamped when it is reported (wall clock, with the UTC offset of that instant)
 * and written on [executor] in that order, so no disk access happens on the calling thread.
 */
internal class RhythmRecorder(
    private val log: PlannerLog,
    private val clock: () -> Long,
    private val executor: Executor,
    private val tzMinutes: (epochMillis: Long) -> Int = ::deviceTzMinutes,
) {
    /**
     * The observer starts. Writes, in order: the `monitor_stop` an earlier session never got to
     * write (the OS killed it), `monitor_start`, the screen state and the charger state right
     * now, and the next alarm.
     *
     * The schema only asks for the screen state here; the charger state is written too because a
     * phone plugged in before the shift would otherwise never show a charger event at all.
     */
    fun monitorStart(screenOn: Boolean, charging: Boolean, nextAlarmTs: Long?) {
        val now = clock()
        submit {
            val batch = ArrayList<PlannerEvent>(5)
            log.openSessionAliveAt()?.let { alive ->
                val at = alive.coerceAtMost(now)
                batch += event(at, Type.MONITOR_STOP)
            }
            batch += event(now, Type.MONITOR_START)
            batch += event(now, if (screenOn) Type.SCREEN_ON else Type.SCREEN_OFF)
            batch += event(now, if (charging) Type.POWER_CONNECTED else Type.POWER_DISCONNECTED)
            batch += event(now, Type.ALARM_NEXT, alarmTs = nextAlarmTs)
            log.append(batch)
            log.markAlive(now)
        }
    }

    /** The observer stops in an orderly way (the shift ended and the service is going away). */
    fun monitorStop() {
        val now = clock()
        submit {
            log.append(event(now, Type.MONITOR_STOP))
            log.closeSession()
        }
    }

    /** Still observing: moves the time a killed session would be closed at. */
    fun alive() {
        val now = clock()
        submit { log.markAlive(now) }
    }

    fun screen(on: Boolean) = write(if (on) Type.SCREEN_ON else Type.SCREEN_OFF)

    fun userPresent() = write(Type.USER_PRESENT)

    fun power(connected: Boolean) = write(if (connected) Type.POWER_CONNECTED else Type.POWER_DISCONNECTED)

    fun faceDown(down: Boolean) = write(if (down) Type.FACE_DOWN_START else Type.FACE_DOWN_END)

    /** `AlarmManager.getNextAlarmClock()` as of now; null when no alarm is set. */
    fun alarmNext(alarmTs: Long?) = write(Type.ALARM_NEXT, alarmTs = alarmTs)

    /** `shift_armed` when a shift starts running and `shift_ended` when it stops, from the machine's own transitions. */
    fun onTransition(t: Transition) {
        val was = t.from.isRunning
        val runs = t.to.isRunning
        if (!was && runs) {
            write(Type.SHIFT_ARMED, shiftId = t.to.spec?.shiftId)
        } else if (was && !runs) {
            val spec = t.from.spec ?: return
            write(Type.SHIFT_ENDED, shiftId = spec.shiftId, reason = endReason(t.to, spec, clock() / 1000))
        }
    }

    private fun write(type: Type, alarmTs: Long? = null, shiftId: Long? = null, reason: Int? = null) {
        val now = clock()
        submit {
            log.append(event(now, type, alarmTs, shiftId, reason))
            log.markAlive(now)
        }
    }

    private fun event(ts: Long, type: Type, alarmTs: Long? = null, shiftId: Long? = null, reason: Int? = null) =
        PlannerEvent(ts, tzMinutes(ts).coerceIn(-PlannerEvent.MAX_TZ_MINUTES, PlannerEvent.MAX_TZ_MINUTES), type, alarmTs, shiftId, reason)

    private fun submit(task: () -> Unit) {
        try {
            executor.execute {
                try {
                    task()
                } catch (_: RuntimeException) {
                    // An exception escaping an executor thread would take the app down with it:
                    // a log line is never worth a shift.
                }
            }
        } catch (_: RejectedExecutionException) {
            // The service is gone. A session left open is closed by the next start.
        }
    }

    companion object {
        /** The schema's `tz`: the UTC offset in minutes at that instant. */
        fun deviceTzMinutes(epochMillis: Long): Int = TimeZone.getDefault().getOffset(epochMillis) / 60_000

        /**
         * The schema's `reason` (INTERFACE.md `break_reason`) for how a shift stopped on this
         * phone: 1 pickup, 2 screen_on, 7 unplugged, 8 unlocked for a break, 3 for a freeze, and
         * for a shift ended by hand 0 (completed) once its plan window is over, 6 (manual) inside it.
         */
        fun endReason(to: ShiftState, spec: ShiftSpec, nowUnix: Long): Int = when (to) {
            is ShiftState.Broken -> to.reason.wireReason.wire
            is ShiftState.Frozen -> REASON_FREEZE
            else -> if (spec.windowEndUnix != null && nowUnix > spec.windowEndUnix) REASON_COMPLETED else REASON_MANUAL
        }

        private const val REASON_COMPLETED = 0
        private const val REASON_FREEZE = 3
        private const val REASON_MANUAL = 6
    }
}
