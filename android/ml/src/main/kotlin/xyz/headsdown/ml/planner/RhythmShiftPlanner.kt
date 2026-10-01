package xyz.headsdown.ml.planner

import xyz.headsdown.ml.NightBudget
import xyz.headsdown.ml.PlanRequest
import xyz.headsdown.ml.PlannerEvent
import xyz.headsdown.ml.ShiftPlan
import xyz.headsdown.ml.ShiftPlanner
import xyz.headsdown.ml.ShiftWindow

/**
 * [ShiftPlanner] over the rhythm model: logs → slot labels → posterior → the next 24 h of
 * P(idle), the next window (ending by the next alarm), and the next 7 nights with a weekly split
 * bounded by the wallet caps. Same arithmetic as `ml/foreman/planner/model.py:plan`.
 */
class RhythmShiftPlanner(private val params: PlannerParams = PlannerParams.DEFAULT) : ShiftPlanner {

    override fun plan(events: List<PlannerEvent>, request: PlanRequest): ShiftPlan {
        val now = request.nowTs
        val tz = request.tzMinutes
        val labels = SlotLabels.labels(events, now)
        val nowDay = Math.floorDiv(now + tz * 60_000L, SlotLabels.DAY_MS)
        val post = RhythmModel.fit(labels, nowDay, params)
        val slots = RhythmModel.forecast(post, now, tz, params)
        val window = RhythmModel.proposeWindow(slots, now, SlotLabels.lastAlarm(events, now), post.nights, params)
        val week = ArrayList<ShiftWindow?>(7)
        week += window
        for (n in 1 until 7) {
            val t = now + n * SlotLabels.DAY_MS
            week += RhythmModel.proposeWindow(RhythmModel.forecast(post, t, tz, params), t, null, post.nights, params)
        }
        val caps = request.caps
        // A dig can never exceed cap_round on-chain, so neither can the unit the split allocates in.
        val chunk = if (caps != null) minOf(request.digLamports, caps.capRoundLamports) else 0L
        val lamports = if (caps != null && chunk > 0) {
            BudgetSplitter.split(week, caps.remainingWeekLamports, caps.capShiftLamports, chunk, params.roundSeconds)
        } else {
            null
        }
        return ShiftPlan(
            slots = slots,
            nextWindow = window,
            week = week.mapIndexed { i, w -> NightBudget(w, RhythmModel.expectedRounds(w, params.roundSeconds), lamports?.get(i)) },
            nightsObserved = post.nights,
        )
    }
}
