package xyz.headsdown.feature.shift.foreman

import android.app.AlarmManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.headsdown.ml.PlanRequest
import xyz.headsdown.ml.PlannerEvent
import xyz.headsdown.ml.ShiftPlanner
import xyz.headsdown.ml.ShiftWindow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the on-device Shift Planner over the planner log and publishes its answer for tonight.
 *
 * The planner only proposes. A proposal can be signed only as a [SignablePlan], and the only
 * way to one is `ForemanGate.tighten` against the wallet's limits: this repository calls it for
 * every plan it publishes ([TonightPlan.signable]), and the limits are checked again when a plan
 * is about to be used ([SignablePlan.retighten]) and once more by the program. Without known
 * limits there is a window to show and nothing to sign.
 *
 * Nothing here talks to a network. The log is read from, and the next alarm written to, the
 * app-private [PlannerLog].
 */
@Singleton
class PlannerRepository internal constructor(
    private val log: PlannerLog,
    private val planner: () -> ShiftPlanner,
    private val clock: () -> Long,
    private val tzMinutes: (epochMillis: Long) -> Int,
    /** The system's next alarm clock (epoch ms), or null when none is set. Null provider: the log's own alarm lines stand. */
    private val nextAlarm: (() -> Long?)?,
    private val dispatcher: CoroutineDispatcher,
) {
    @Inject constructor(@ApplicationContext context: Context, log: PlannerLog, runtime: ForemanRuntime) : this(
        log = log,
        planner = { runtime.shiftPlanner },
        clock = System::currentTimeMillis,
        tzMinutes = RhythmRecorder::deviceTzMinutes,
        nextAlarm = { (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.nextAlarmClock?.triggerTime },
        dispatcher = Dispatchers.Default,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _tonight = MutableStateFlow<TonightPlan?>(null)

    /** The latest plan, or null before the first one in this process. */
    val tonight: StateFlow<TonightPlan?> = _tonight.asStateFlow()

    @Volatile private var budget: PlanningBudget? = null

    /**
     * The wallet's limits (as read from the Rig account) and the plan the user would arm with.
     * With them the week is split and [TonightPlan.signable] is built; null forgets them.
     * Takes effect at the next [refresh].
     */
    fun setBudget(budget: PlanningBudget?) {
        this.budget = budget
    }

    /** Plans from the log as it is now, off the caller's thread, and publishes the result. */
    suspend fun refresh(): TonightPlan = withContext(dispatcher) { plan() }

    /** [refresh] for callers that cannot wait (the shift service when a shift starts or ends). */
    fun requestRefresh() {
        scope.launch {
            try {
                plan()
            } catch (_: RuntimeException) {
                // The last published plan stands.
            }
        }
    }

    @Synchronized
    internal fun plan(): TonightPlan {
        val now = clock()
        val tz = tzMinutes(now).coerceIn(-PlannerEvent.MAX_TZ_MINUTES, PlannerEvent.MAX_TZ_MINUTES)
        // LOG_SCHEMA.md: the next alarm is logged when planning; the window ends at it.
        nextAlarm?.let { provider ->
            val alarm = try {
                provider()
            } catch (_: RuntimeException) {
                null
            }
            log.append(PlannerEvent(now, tz, PlannerEvent.Type.ALARM_NEXT, alarmTs = alarm))
        }
        val known = budget
        val request = PlanRequest(now, tz, known?.limits?.toCaps(), known?.template?.digLamports ?: 0L)
        val result = planner().plan(log.events(), request)
        val window = result.nextWindow?.toPlanned()
        val signable = if (window != null && known != null) {
            // Seconds, rounded down at both ends: the plan never outlasts the proposed window.
            SignablePlan.tighten(known.limits, known.template, Math.floorDiv(window.startWallMillis, 1000L), Math.floorDiv(window.endWallMillis, 1000L), Math.floorDiv(now, 1000L))
        } else {
            null
        }
        val plan = TonightPlan(
            plannedAtWallMillis = now,
            tzMinutes = tz,
            nightsObserved = result.nightsObserved,
            window = window,
            week = result.week.map { NightShare(it.window?.toPlanned(), it.expectedIdleRounds, it.lamports) },
            signable = signable,
            gate = when {
                window == null -> PlanGate.NO_WINDOW
                known == null -> PlanGate.NO_LIMITS
                signable == null -> PlanGate.REFUSED_BY_LIMITS
                else -> PlanGate.WITHIN_LIMITS
            },
        )
        _tonight.value = plan
        return plan
    }

    private fun ShiftWindow.toPlanned() = PlannedWindow(
        startWallMillis = startTs,
        endWallMillis = endTs,
        slots = slots,
        meanIdle = meanP,
        minIdle = minP,
        expectedIdleHours = expectedIdleHours,
        confidence = when (reason) {
            ShiftWindow.Reason.OK -> WindowConfidence.CONFIDENT
            ShiftWindow.Reason.HISTORY -> WindowConfidence.NEEDS_HISTORY
            ShiftWindow.Reason.CONFIDENCE -> WindowConfidence.NOT_CONFIDENT
        },
    )
}
