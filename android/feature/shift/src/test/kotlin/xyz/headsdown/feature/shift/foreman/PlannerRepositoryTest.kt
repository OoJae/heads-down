package xyz.headsdown.feature.shift.foreman

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.headsdown.ml.ForemanModels
import xyz.headsdown.ml.PlanRequest
import xyz.headsdown.ml.PlannerEvent
import xyz.headsdown.ml.ShiftPlanner
import xyz.headsdown.ml.planner.SlotLabels
import java.io.File
import java.lang.reflect.Modifier
import java.util.Random
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlin.math.abs

/**
 * The Shift Planner behind a StateFlow: a fake clock, synthetic logs written the way the service
 * writes them, the reference logs of :ml's vectors, and the wallet's limits as the only gate to
 * a plan that could be signed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val tz = 60 // WAT
    private val minute = 60_000L
    private val day0 = 20_000L

    /** Epoch ms of [minuteOfDay] local time on local day [day]. */
    private fun at(day: Long, minuteOfDay: Long) = (day0 + day) * SlotLabels.DAY_MS + minuteOfDay * minute - tz * minute

    private fun localMinute(ts: Long): Long = Math.floorMod(ts + tz * minute, SlotLabels.DAY_MS) / minute

    private var now = at(0, 0)
    private var alarm: Long? = null
    private val shipped: ShiftPlanner = ForemanModels.shiftPlanner(ForemanVectors.text("/foreman/planner_params.json"))

    private fun repository(log: PlannerLog, withAlarmSource: Boolean = true, planner: () -> ShiftPlanner = { shipped }, tzMinutes: (Long) -> Int = { tz }) =
        PlannerRepository(log, planner, { now }, tzMinutes, if (withAlarmSource) ({ alarm }) else null, Dispatchers.Unconfined)

    /**
     * [nights] nights as the shift service logs them: started at 23:00 on the charger with the
     * screen off, the screen woken and unlocked at 07:00, then the service stops.
     */
    private fun nightsOfShifts(log: PlannerLog, nights: Int) {
        val recorder = RhythmRecorder(log, { now }, Executor { it.run() }, tzMinutes = { tz })
        for (d in 0 until nights.toLong()) {
            now = at(d, 23 * 60)
            recorder.monitorStart(screenOn = false, charging = true, nextAlarmTs = null)
            now += 4_000
            recorder.faceDown(down = true)
            now = at(d + 1, 7 * 60)
            recorder.faceDown(down = false)
            recorder.screen(on = true)
            now += 2_000
            recorder.userPresent()
            now += 1_000
            recorder.monitorStop()
        }
    }

    private val limits = WalletLimits(
        capWeekLamports = 500_000_000, capShiftLamports = 120_000_000, capRoundLamports = 2_000_000,
        capMaxCost = 670_000_000, capsExpiryUnix = Long.MAX_VALUE / 2, spentWeekLamports = 100_000_000,
    )
    private val template = PlanTemplate(maxEvCost = 530_000_000, digLamports = 1_000_000, splitTiles = 4, soloTiles = 0)

    // ------------------------------------------------------------------ tonight's window

    @Test
    fun `three weeks of logged shifts give tonight's window as a StateFlow value`() {
        val log = PlannerLog(tmp.newFolder())
        nightsOfShifts(log, nights = 21)
        val repo = repository(log)
        assertNull("nothing is published before the first plan", repo.tonight.value)

        now = at(21, 18 * 60)
        val plan = repo.plan()
        assertEquals(plan, repo.tonight.value)
        assertEquals(now, plan.plannedAtWallMillis)
        assertEquals(60, plan.tzMinutes)
        assertEquals("21 nights across midnight touch 22 local days", 22, plan.nightsObserved)

        val w = plan.window
        assertNotNull(w)
        w!!
        assertTrue("starts tonight, not in the past", w.startWallMillis >= now && w.startWallMillis < now + SlotLabels.DAY_MS)
        assertTrue("covers the small hours the phone always sat idle (${localMinute(w.startWallMillis)}..${localMinute(w.endWallMillis)})",
            w.startWallMillis <= at(22, 60) && w.endWallMillis >= at(22, 5 * 60))
        assertTrue("stays inside the night it has evidence for", w.startWallMillis >= at(21, 21 * 60) && w.endWallMillis <= at(22, 9 * 60))
        assertEquals("whole 15-minute slots", 0L, (w.endWallMillis - w.startWallMillis) % SlotLabels.SLOT_MS)
        assertEquals((w.endWallMillis - w.startWallMillis) / SlotLabels.SLOT_MS, w.slots.toLong())
        assertTrue(w.minIdle in 0.7..1.0 && w.meanIdle >= w.minIdle && w.expectedIdleHours > 3.0)
        println("three weeks of shifts 23:00-07:00 -> window ${localMinute(w.startWallMillis) / 60}:${localMinute(w.startWallMillis) % 60} to " +
            "${localMinute(w.endWallMillis) / 60}:${localMinute(w.endWallMillis) % 60}, ${w.confidence}, min P(idle) ${w.minIdle}")

        assertEquals("the next seven nights", 7, plan.week.size)
        assertEquals(w, plan.week.first().window)
        // No limits known here: a window to show, nothing to sign, no budget split.
        assertEquals(PlanGate.NO_LIMITS, plan.gate)
        assertNull(plan.signable)
        assertTrue(plan.week.all { it.lamports == null })
        assertNull(plan.tonightLamports)
    }

    @Test
    fun `with no history the planner still answers, and never calls the window confident`() {
        val repo = repository(PlannerLog(tmp.newFolder()))
        now = at(0, 18 * 60)
        val plan = repo.plan()
        assertEquals(0, plan.nightsObserved)
        assertEquals(7, plan.week.size)
        plan.window?.let {
            assertEquals(WindowConfidence.NEEDS_HISTORY, it.confidence)
            assertFalse(it.confident)
        }
        // The built-in fallback parameters (the shipped file failed to load) never auto-arm either.
        val fallback = repository(PlannerLog(tmp.newFolder()).also { nightsOfShifts(it, 30) }, planner = { ForemanModels.shiftPlanner(null as String?) })
        now = at(30, 18 * 60)
        assertFalse(fallback.plan().window?.confident ?: false)
    }

    @Test
    fun `the window ends at the next alarm, which is logged when planning`() {
        val dir = tmp.newFolder()
        val log = PlannerLog(dir)
        nightsOfShifts(log, nights = 21)
        val repo = repository(log)
        now = at(21, 18 * 60)
        val free = repo.plan().window!!
        alarm = at(22, 5 * 60 + 40)
        assertTrue("the alarm falls inside the window proposed without one", alarm!! > free.startWallMillis && alarm!! < free.endWallMillis)
        val cut = repo.plan().window!!
        assertEquals(alarm, cut.endWallMillis)
        assertEquals(free.startWallMillis, cut.startWallMillis)
        val last = File(dir, "planner.jsonl").readLines().last()
        assertEquals("""{"ts":$now,"tz":60,"type":"alarm_next","alarm_ts":$alarm}""", last)

        // The alarm is switched off: the next plan says so and the window is free again.
        alarm = null
        assertEquals(free, repo.plan().window)
        assertEquals("""{"ts":$now,"tz":60,"type":"alarm_next"}""", File(dir, "planner.jsonl").readLines().last())
    }

    // ------------------------------------------------------------------ the wallet's limits

    @Test
    fun `with the wallet's limits the week is split and the plan is signable`() {
        val log = PlannerLog(tmp.newFolder())
        nightsOfShifts(log, nights = 21)
        val repo = repository(log)
        repo.setBudget(PlanningBudget(limits, template))
        now = at(21, 18 * 60)
        val plan = repo.plan()
        assertEquals(PlanGate.WITHIN_LIMITS, plan.gate)
        val s = plan.signable!!
        val w = plan.window!!
        assertEquals("a template inside the limits passes unchanged", template.maxEvCost, s.maxEvCost)
        assertEquals(template.digLamports, s.digLamports)
        assertEquals(4 to 0, s.splitTiles to s.soloTiles)
        assertEquals(1, s.leaseRounds)
        assertFalse(s.focusOnly || s.day)
        assertEquals(w.startWallMillis / 1000, s.windowStartUnix)
        assertEquals(w.endWallMillis / 1000, s.windowEndUnix)

        val shares = plan.week.map { it.lamports!! }
        assertEquals(shares.first(), plan.tonightLamports)
        assertTrue("whole digs", shares.all { it % template.digLamports == 0L && it >= 0 })
        assertTrue("no night above the per-shift cap", shares.all { it <= limits.capShiftLamports })
        assertTrue("the week's remainder is never exceeded: ${shares.sum()}", shares.sum() <= limits.remainingWeekLamports)
        assertTrue("and a week of idle nights gets a budget", shares.sum() > 0)
        for (night in plan.week) {
            assertTrue("at most one dig per expected idle round", night.lamports!! / template.digLamports <= night.expectedIdleRounds.toLong())
        }

        // The limits are forgotten: the window stays, the plan to sign and the split go.
        repo.setBudget(null)
        val bare = repo.plan()
        assertEquals(plan.window, bare.window)
        assertEquals(PlanGate.NO_LIMITS, bare.gate)
        assertNull(bare.signable)
    }

    @Test
    fun `a template above the limits is clamped down, never up`() {
        val log = PlannerLog(tmp.newFolder())
        nightsOfShifts(log, nights = 21)
        val repo = repository(log)
        now = at(21, 18 * 60)
        val window = repo.plan().window!!
        val expiry = window.startWallMillis / 1000 + 3_600 // the caps run out one hour into the window
        repo.setBudget(
            PlanningBudget(
                limits.copy(capsExpiryUnix = expiry),
                PlanTemplate(maxEvCost = 9_000_000_000, digLamports = 50_000_000, splitTiles = 30, soloTiles = 20, leaseRounds = 9),
            ),
        )
        val s = repo.plan().signable!!
        assertEquals(limits.capMaxCost, s.maxEvCost)
        assertEquals(limits.capRoundLamports, s.digLamports)
        assertEquals(15, s.splitTiles)
        assertEquals(10, s.soloTiles)
        assertEquals(3, s.leaseRounds)
        assertEquals("the plan window ends when the caps expire", expiry, s.windowEndUnix)
        assertEquals(window.startWallMillis / 1000, s.windowStartUnix)
    }

    @Test
    fun `limits that refuse the plan leave nothing to sign`() {
        val log = PlannerLog(tmp.newFolder())
        nightsOfShifts(log, nights = 21)
        val repo = repository(log)
        now = at(21, 18 * 60)
        val refusals = mapOf(
            "expired caps" to PlanningBudget(limits.copy(capsExpiryUnix = now / 1000 - 1), template),
            "caps that end before the window starts" to PlanningBudget(limits.copy(capsExpiryUnix = now / 1000 + 60), template),
            "no tiles" to PlanningBudget(limits, template.copy(splitTiles = 0, soloTiles = 0)),
            "no SOL per dig" to PlanningBudget(limits, template.copy(digLamports = 0)),
            "a round cap of zero" to PlanningBudget(limits.copy(capRoundLamports = 0), template),
        )
        for ((why, budget) in refusals) {
            repo.setBudget(budget)
            val plan = repo.plan()
            assertNotNull(why, plan.window)
            assertEquals(why, PlanGate.REFUSED_BY_LIMITS, plan.gate)
            assertNull(why, plan.signable)
        }
        // A focus-only template deploys nothing, so it needs no tiles and no SOL.
        repo.setBudget(PlanningBudget(limits, PlanTemplate(0, 0, 0, 0, focusOnly = true)))
        val focus = repo.plan()
        assertEquals(PlanGate.WITHIN_LIMITS, focus.gate)
        assertTrue(focus.signable!!.focusOnly)
    }

    // ------------------------------------------------------------------ a signable plan only comes out of the gate

    @Test
    fun `a SignablePlan cannot be built or altered outside the gate`() {
        // The real constructor is private. The compiler's bridge for the companion is synthetic,
        // which neither Kotlin nor Java source can call.
        val constructors = SignablePlan::class.java.declaredConstructors
        assertTrue(constructors.isNotEmpty())
        assertTrue("no constructor that source code can call", constructors.all { Modifier.isPrivate(it.modifiers) || it.isSynthetic })
        assertTrue("no copy()", SignablePlan::class.java.methods.none { it.name.startsWith("copy") })
        assertTrue("every field is read-only", SignablePlan::class.java.declaredFields.all { Modifier.isFinal(it.modifiers) || Modifier.isStatic(it.modifiers) })
    }

    @Test
    fun `tighten and retighten only ever tighten - randomized`() {
        val rnd = Random(2026)
        var kept = 0
        repeat(20_000) {
            val l = WalletLimits(
                rnd.nextInt(1_000_000_000).toLong(), rnd.nextInt(300_000_000).toLong(), rnd.nextInt(5_000_000).toLong(),
                rnd.nextInt(2_000_000_000).toLong(), 1_700_000_000L + rnd.nextInt(200_000_000), rnd.nextInt(600_000_000).toLong(),
            )
            val start = 1_700_000_000L + rnd.nextInt(200_000_000)
            val end = start + rnd.nextInt(100_000) - 100
            val t = PlanTemplate(
                rnd.nextLong() % 5_000_000_000, rnd.nextInt(50_000_000).toLong() - 10, rnd.nextInt(43) - 3, rnd.nextInt(43) - 3,
                rnd.nextInt(12) - 3, rnd.nextBoolean(), rnd.nextBoolean(),
            )
            val nowUnix = 1_700_000_000L + rnd.nextInt(200_000_000)
            val s = SignablePlan.tighten(l, t, start, end, nowUnix) ?: return@repeat
            kept++
            assertTrue(s.maxEvCost in 0..l.capMaxCost && s.maxEvCost <= maxOf(0, t.maxEvCost))
            assertTrue(s.digLamports in 0..l.capRoundLamports && s.digLamports <= maxOf(0, t.digLamports))
            assertTrue(s.splitTiles in 0..15 && s.soloTiles in 0..10 && s.leaseRounds in 1..3)
            assertTrue(s.windowStartUnix == start && s.windowEndUnix <= end && s.windowEndUnix <= l.capsExpiryUnix && s.windowStartUnix < s.windowEndUnix)
            assertTrue(nowUnix <= s.windowEndUnix && nowUnix <= l.capsExpiryUnix)
            assertTrue(s.focusOnly || (s.splitTiles + s.soloTiles >= 1 && s.digLamports > 0))
            assertEquals(t.focusOnly to t.day, s.focusOnly to s.day)
            // What core/keys will sign is exactly this plan, and it is always encodable.
            val wire = s.toShiftPlan()
            assertEquals(s.maxEvCost.toULong(), wire.maxEvCost)
            assertEquals(s.digLamports.toULong(), wire.digLamports)
            assertEquals(listOf(s.splitTiles, s.soloTiles, s.leaseRounds), listOf(wire.splitTiles, wire.soloTiles, wire.leaseRounds))
            assertEquals(s.windowStartUnix to s.windowEndUnix, wire.windowStartTs to wire.windowEndTs)
            assertEquals(s.focusOnly to s.day, wire.focusOnly to wire.day)
            assertEquals(36, wire.encode().size)

            // The same limits a moment later: unchanged. Lower limits later: tighter or gone.
            assertEquals(s, s.retighten(l, nowUnix))
            val lower = l.copy(
                capMaxCost = l.capMaxCost / 2, capRoundLamports = l.capRoundLamports / 2,
                capsExpiryUnix = l.capsExpiryUnix - rnd.nextInt(50_000),
            )
            val later = nowUnix + rnd.nextInt(50_000)
            val again = s.retighten(lower, later)
            if (again != null) {
                assertTrue(again.maxEvCost <= s.maxEvCost && again.maxEvCost <= lower.capMaxCost)
                assertTrue(again.digLamports <= s.digLamports && again.digLamports <= lower.capRoundLamports)
                assertTrue(again.windowEndUnix <= s.windowEndUnix && again.windowEndUnix <= lower.capsExpiryUnix && later <= again.windowEndUnix)
                assertEquals(s.windowStartUnix, again.windowStartUnix)
                assertEquals(listOf(s.splitTiles, s.soloTiles, s.leaseRounds), listOf(again.splitTiles, again.soloTiles, again.leaseRounds))
            } else {
                assertTrue("dropped only for a reason", later > lower.capsExpiryUnix || later > s.windowEndUnix ||
                    s.windowStartUnix >= minOf(s.windowEndUnix, lower.capsExpiryUnix) || (!s.focusOnly && lower.capRoundLamports == 0L))
            }
        }
        println("randomized tighten: $kept of 20000 plans survive the limits")
        assertTrue("the random plans are not all refused: $kept", kept > 500)
    }

    @Test
    fun `limits read from chain are clamped into range, never rejected`() {
        val l = WalletLimits.fromChain(ULong.MAX_VALUE, 120_000_000uL, 2_000_000uL, ULong.MAX_VALUE, 1_800_000_000, 5uL)
        assertEquals(Long.MAX_VALUE, l.capWeekLamports)
        assertEquals(Long.MAX_VALUE, l.capMaxCost)
        assertEquals(Long.MAX_VALUE - 5, l.remainingWeekLamports)
        assertEquals(0L, WalletLimits(10, 10, 10, 10, 0, spentWeekLamports = 50).remainingWeekLamports)
    }

    // ------------------------------------------------------------------ the reference logs

    @Test
    fun `the vectors' logs give the reference windows and budget splits through the log file`() {
        val vectors = ForemanVectors.json("/foreman/planner_vectors.json")
        val tol = vectors["tolerance"]!!.jsonPrimitive.double
        val cases = vectors["cases"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 5)
        var windows = 0
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val dir = tmp.newFolder()
            // The raw lines, junk and events after "now" included, as if the service had written them.
            File(dir, "planner.jsonl").writeText(c["events_jsonl"]!!.jsonArray.joinToString("\n", postfix = "\n") { it.jsonPrimitive.content })
            val caseTz = c["tz"]!!.jsonPrimitive.int
            val caps = c["caps"]!!.jsonObject
            val chunk = caps["chunk"]!!.jsonPrimitive.long
            now = c["now_ts"]!!.jsonPrimitive.long
            val repo = repository(PlannerLog(dir), withAlarmSource = false, tzMinutes = { caseTz })
            repo.setBudget(
                PlanningBudget(
                    WalletLimits(caps["remaining_week"]!!.jsonPrimitive.long, caps["cap_shift"]!!.jsonPrimitive.long, chunk, 0, Long.MAX_VALUE),
                    PlanTemplate(maxEvCost = 0, digLamports = chunk, splitTiles = 1, soloTiles = 0),
                ),
            )
            val plan = repo.plan()
            assertEquals("$name nights", c["nights"]!!.jsonPrimitive.int, plan.nightsObserved)
            checkWindow("$name window", c["window"]!!, plan.window, tol)
            val week = c["week"]!!.jsonArray
            for (n in 0 until 7) checkWindow("$name night $n", week[n], plan.week[n].window, tol)
            assertEquals("$name budget", c["budget"]!!.jsonArray.map { it.jsonPrimitive.long }, plan.week.map { it.lamports })
            val rounds = c["expected_rounds"]!!.jsonArray.map { it.jsonPrimitive.double }
            for (n in 0 until 7) assertTrue("$name rounds $n", abs(rounds[n] - plan.week[n].expectedIdleRounds) <= tol)
            if (plan.window != null) windows++
        }
        assertTrue("the reference logs propose windows", windows >= 3)
    }

    private fun checkWindow(what: String, expected: JsonElement, actual: PlannedWindow?, tol: Double) {
        if (expected is JsonNull) {
            assertNull(what, actual)
            return
        }
        val e = expected.jsonObject
        assertNotNull(what, actual)
        actual!!
        assertEquals("$what start", e["start_ts"]!!.jsonPrimitive.long, actual.startWallMillis)
        assertEquals("$what end", e["end_ts"]!!.jsonPrimitive.long, actual.endWallMillis)
        assertEquals("$what slots", e["slots"]!!.jsonPrimitive.int, actual.slots)
        assertTrue("$what mean", abs(e["mean_p"]!!.jsonPrimitive.double - actual.meanIdle) <= tol)
        assertTrue("$what min", abs(e["min_p"]!!.jsonPrimitive.double - actual.minIdle) <= tol)
        assertTrue("$what hours", abs(e["expected_idle_hours"]!!.jsonPrimitive.double - actual.expectedIdleHours) <= tol)
        assertEquals("$what confident", e["auto_arm"]!!.jsonPrimitive.boolean, actual.confident)
        val reason = when (actual.confidence) {
            WindowConfidence.CONFIDENT -> "ok"
            WindowConfidence.NEEDS_HISTORY -> "history"
            WindowConfidence.NOT_CONFIDENT -> "confidence"
        }
        assertEquals("$what reason", e["reason_code"]!!.jsonPrimitive.content, reason)
    }

    // ------------------------------------------------------------------ off the caller's thread

    @Test
    fun `refresh and requestRefresh plan on the repository's dispatcher and publish`() = runTest {
        val log = PlannerLog(tmp.newFolder())
        nightsOfShifts(log, nights = 10)
        now = at(10, 18 * 60)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = PlannerRepository(log, { shipped }, { now }, { tz }, null, dispatcher)

        repo.requestRefresh()
        assertNull("not planned on the caller's thread", repo.tonight.value)
        advanceUntilIdle()
        val first = repo.tonight.value
        assertNotNull(first)

        now += 30 * minute
        val second = repo.refresh()
        assertEquals(second, repo.tonight.value)
        assertEquals(now, second.plannedAtWallMillis)

        // A planner that fails keeps the last plan in place and throws only at the caller who waits.
        val crashing = object : ShiftPlanner {
            override fun plan(events: List<PlannerEvent>, request: PlanRequest): xyz.headsdown.ml.ShiftPlan = error("planner crashed")
        }
        val failing = PlannerRepository(log, { crashing }, { now }, { tz }, null, dispatcher)
        failing.requestRefresh()
        advanceUntilIdle()
        assertNull(failing.tonight.value)
    }

    @Test
    fun `planning writes nothing but the alarm line and sends nothing anywhere`() {
        val dir = tmp.newFolder()
        val log = PlannerLog(dir)
        nightsOfShifts(log, nights = 3)
        val before = File(dir, "planner.jsonl").readLines()
        now = at(3, 18 * 60)
        repository(log).plan()
        val after = File(dir, "planner.jsonl").readLines()
        assertEquals(before, after.dropLast(1))
        assertEquals(PlannerEvent.Type.ALARM_NEXT, PlannerEvent.parse(after.last())!!.type)
        // Without an alarm source, planning does not touch the log at all.
        repository(log, withAlarmSource = false).plan()
        assertEquals(after, File(dir, "planner.jsonl").readLines())
    }
}
