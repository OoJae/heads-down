package xyz.headsdown.ml.planner

import xyz.headsdown.ml.IdleSlot
import xyz.headsdown.ml.ShiftWindow
import java.util.SortedMap
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The rhythm model (port of ml/foreman/planner/model.py): a per-slot Beta-Bernoulli posterior
 * with day-of-week effects, three levels of shrinkage (all days → weekday/weekend → day of week)
 * over recency-weighted counts. Interpretable by construction: every P(idle) is a weighted count
 * of the user's own nights, pulled toward the population curve when there are few.
 */
object RhythmModel {
    private const val N = SlotLabels.SLOTS_PER_DAY

    class Posterior(
        val mean: Array<DoubleArray>, // [dow][slot]
        val lower: Array<DoubleArray>,
        val evidence: Array<DoubleArray>,
        val nights: Int,
    )

    /** Weighted counts accumulated in (day, slot) order, as the Python reference does. */
    fun fit(labels: SortedMap<Long, Int>, nowDay: Long, p: PlannerParams): Posterior {
        val aAll = DoubleArray(N)
        val nAll = DoubleArray(N)
        val aT = Array(2) { DoubleArray(N) }
        val nT = Array(2) { DoubleArray(N) }
        val aD = Array(7) { DoubleArray(N) }
        val nD = Array(7) { DoubleArray(N) }
        val days = HashSet<Long>()
        for ((k, y) in labels) {
            val day = SlotLabels.dayOf(k)
            if (day > nowDay) continue
            val slot = SlotLabels.slotOf(k)
            val w = 0.5.pow((nowDay - day).toDouble() / p.halfLifeDays)
            days += day
            val d = SlotLabels.dayOfWeek(day)
            val t = if (d in p.weekendDays) 1 else 0
            val hit = if (y == SlotLabels.IDLE) w else 0.0
            aAll[slot] += hit
            nAll[slot] += w
            aT[t][slot] += hit
            nT[t][slot] += w
            aD[d][slot] += hit
            nD[d][slot] += w
        }
        val sAAll = smooth(aAll, p.kernel)
        val sNAll = smooth(nAll, p.kernel)
        val sAT = Array(2) { smooth(aT[it], p.kernel) }
        val sNT = Array(2) { smooth(nT[it], p.kernel) }
        val sAD = Array(7) { smooth(aD[it], p.kernel) }
        val sND = Array(7) { smooth(nD[it], p.kernel) }
        val m0 = DoubleArray(N) { s -> (p.k0 * p.prior[s] + sAAll[s]) / (p.k0 + sNAll[s]) }
        val m1 = Array(2) { t -> DoubleArray(N) { s -> (p.k1 * m0[s] + sAT[t][s]) / (p.k1 + sNT[t][s]) } }
        val mean = Array(7) { DoubleArray(N) }
        val lower = Array(7) { DoubleArray(N) }
        val evid = Array(7) { DoubleArray(N) }
        for (d in 0 until 7) {
            val t = if (d in p.weekendDays) 1 else 0
            for (s in 0 until N) {
                val m = (p.k2 * m1[t][s] + sAD[d][s]) / (p.k2 + sND[d][s])
                val sd = sqrt(m * (1.0 - m) / (sNT[t][s] + 1.0))
                mean[d][s] = m
                lower[d][s] = max(0.0, m - p.autoArmZ * sd)
                evid[d][s] = sNT[t][s]
            }
        }
        return Posterior(mean, lower, evid, days.size)
    }

    /** y[s] = Σ_j kernel[j] * x[(s + j - h) mod 96], j in order. */
    fun smooth(x: DoubleArray, kernel: List<Double>): DoubleArray {
        if (kernel.size == 1) return DoubleArray(x.size) { kernel[0] * x[it] }
        val h = kernel.size / 2
        val n = x.size
        return DoubleArray(n) { s ->
            var acc = 0.0
            for (j in kernel.indices) acc += kernel[j] * x[Math.floorMod(s + j - h, n)]
            acc
        }
    }

    /** P(idle) for the next horizonSlots slots, from the slot containing [nowTs] (local [tz]). */
    fun forecast(post: Posterior, nowTs: Long, tz: Int, p: PlannerParams): List<IdleSlot> {
        val tzMs = tz * 60_000L
        val local = nowTs + tzMs
        val first = Math.floorDiv(local, SlotLabels.SLOT_MS) * SlotLabels.SLOT_MS
        return List(p.horizonSlots) { i ->
            val ls = first + i * SlotLabels.SLOT_MS
            val day = Math.floorDiv(ls, SlotLabels.DAY_MS)
            val slot = (Math.floorMod(ls, SlotLabels.DAY_MS) / SlotLabels.SLOT_MS).toInt()
            val d = SlotLabels.dayOfWeek(day)
            IdleSlot(ls - tzMs, post.mean[d][slot], post.lower[d][slot], post.evidence[d][slot])
        }
    }

    /** The contiguous run maximizing Σ(p − τ) (the earliest run wins ties), at least minWindowSlots long. */
    fun bestRunKadane(slots: List<IdleSlot>, p: PlannerParams): IntArray? {
        var bestSum = 0.0
        var best: IntArray? = null
        var curSum = 0.0
        var curStart = 0
        for ((i, s) in slots.withIndex()) {
            val v = s.pIdle - p.windowThreshold
            if (curSum <= 0.0) {
                curSum = v
                curStart = i
            } else {
                curSum += v
            }
            if (curSum > bestSum) {
                bestSum = curSum
                best = intArrayOf(curStart, i)
            }
        }
        if (best == null || best[1] - best[0] + 1 < p.minWindowSlots) return null
        return best
    }

    /**
     * The run (at least minWindowSlots long, first and last slot with p >= τ) maximizing expected
     * completed dark time, length × Π p^γ: every extra slot adds time but must also stay idle or
     * the shift breaks. Earliest start, then shortest, wins ties.
     */
    fun bestRunSurvival(slots: List<IdleSlot>, p: PlannerParams): IntArray? {
        val n = slots.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + ln(max(slots[i].pIdle, 1e-12))
        var bestScore = 0.0
        var best: IntArray? = null
        for (i0 in 0 until n) {
            if (slots[i0].pIdle < p.windowThreshold) continue
            for (i1 in (i0 + p.minWindowSlots - 1) until n) {
                if (slots[i1].pIdle < p.windowThreshold) continue
                val score = (i1 - i0 + 1) * exp(p.windowGamma * (prefix[i1 + 1] - prefix[i0]))
                if (score > bestScore) {
                    bestScore = score
                    best = intArrayOf(i0, i1)
                }
            }
        }
        return best
    }

    /** The best run under the configured rule, ending by [alarmTs] when the alarm falls inside it. */
    fun proposeWindow(slots: List<IdleSlot>, nowTs: Long, alarmTs: Long?, nights: Int, p: PlannerParams): ShiftWindow? {
        val best = when (p.windowRule) {
            PlannerParams.WindowRule.SURVIVAL -> bestRunSurvival(slots, p)
            PlannerParams.WindowRule.KADANE -> bestRunKadane(slots, p)
        } ?: return null
        val i0 = best[0]
        val i1 = best[1]
        val start = max(slots[i0].startTs, nowTs)
        var end = slots[i1].startTs + SlotLabels.SLOT_MS
        if (alarmTs != null && start < alarmTs && alarmTs < end) end = alarmTs
        if (end <= start) return null
        val run = slots.subList(i0, i1 + 1)
        var sum = 0.0
        for (s in run) sum += s.pIdle
        val meanP = sum / run.size
        var expHours = 0.0
        for (s in run) {
            val overlap = max(0L, minOf(end, s.startTs + SlotLabels.SLOT_MS) - max(start, s.startTs))
            expHours += s.pIdle * overlap / 3_600_000.0
        }
        val lowest = run.minOf { it.lower }
        val reason = when {
            nights < p.autoArmMinNights -> ShiftWindow.Reason.HISTORY
            lowest < p.autoArmThreshold -> ShiftWindow.Reason.CONFIDENCE
            else -> ShiftWindow.Reason.OK
        }
        return ShiftWindow(start, end, run.size, meanP, run.minOf { it.pIdle }, expHours, reason == ShiftWindow.Reason.OK, reason)
    }

    fun expectedRounds(w: ShiftWindow?, roundSeconds: Double): Double =
        if (w == null) 0.0 else w.expectedIdleHours * 3600.0 / roundSeconds
}

/**
 * The weekly split: D'Hondt allocation of whole dig chunks across nights in proportion to
 * expected idle rounds. Bounded: each night <= cap_shift and <= one chunk per expected idle
 * round (the program digs at most once per round), the total <= the week's remaining cap.
 */
object BudgetSplitter {
    fun split(windows: List<ShiftWindow?>, remainingWeek: Long, capShift: Long, chunk: Long, roundSeconds: Double): LongArray {
        if (chunk <= 0L) return LongArray(windows.size)
        val weights = DoubleArray(windows.size) { RhythmModel.expectedRounds(windows[it], roundSeconds) }
        val caps = LongArray(windows.size) { minOf(max(0L, capShift) / chunk, floor(weights[it]).toLong()) }
        val alloc = LongArray(windows.size)
        var left = max(0L, remainingWeek) / chunk
        while (left > 0) {
            var best = -1
            var bestQ = 0.0
            for (i in weights.indices) {
                if (alloc[i] >= caps[i] || weights[i] <= 0.0) continue
                val q = weights[i] / (alloc[i] + 1)
                if (best < 0 || q > bestQ) {
                    best = i
                    bestQ = q
                }
            }
            if (best < 0) break
            alloc[best]++
            left--
        }
        return LongArray(windows.size) { alloc[it] * chunk }
    }
}
