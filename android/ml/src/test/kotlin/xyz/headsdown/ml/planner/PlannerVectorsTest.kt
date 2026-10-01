package xyz.headsdown.ml.planner

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.ml.PlanRequest
import xyz.headsdown.ml.PlannerEvent
import xyz.headsdown.ml.ShiftWindow
import xyz.headsdown.ml.TestResources
import xyz.headsdown.ml.WalletCaps
import kotlin.math.abs

/**
 * Cross-language check against `ml/foreman/planner/model.py:plan` (python): raw JSONL logs (with
 * junk lines and events after "now"), and every stage the reference computed from them.
 */
class PlannerVectorsTest {

    private val vectors = TestResources.json("/foreman/planner_vectors.json")
    private val tol = vectors["tolerance"]!!.jsonPrimitive.double
    private val params = PlannerParams.parse(
        buildJsonObject {
            put("format", PlannerParams.FORMAT)
            put("version", 1)
            put("params", vectors["params"]!!)
        }.toString(),
    )
    private val cases = vectors["cases"]!!.jsonArray.map { it.jsonObject }
    private var maxDiff = 0.0

    private fun close(expected: Double, actual: Double, what: String) {
        val d = abs(expected - actual)
        maxDiff = maxOf(maxDiff, d)
        assertTrue("$what: expected $expected, got $actual", d <= tol)
    }

    private fun events(c: JsonObject): List<PlannerEvent> =
        PlannerEvent.parseLines(c["events_jsonl"]!!.jsonArray.map { it.jsonPrimitive.content }.asSequence())

    @Test
    fun `the shipped planner parameters are the ones the vectors were built with`() {
        val shipped = PlannerParams.parse(TestResources.asset("planner_params.json").readText())
        assertEquals(params, shipped)
        assertTrue(cases.size >= 5)
    }

    @Test
    fun `the log parser keeps exactly the lines the reference kept`() {
        for (c in cases) {
            assertEquals(c["name"].toString(), c["parsed_events"]!!.jsonPrimitive.int, events(c).size)
        }
    }

    @Test
    fun `slot labels match`() {
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val got = SlotLabels.labels(events(c), c["now_ts"]!!.jsonPrimitive.long)
            val want = c["labels"]!!.jsonArray.associate { it.jsonArray[0].jsonPrimitive.long to it.jsonArray[1].jsonPrimitive.int }
            assertEquals("$name label count", want.size, got.size)
            assertEquals("$name labels", want, got.toMap())
        }
    }

    @Test
    fun `forecast, window, week and budget split match`() {
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val caps = c["caps"]!!.jsonObject
            val chunk = caps["chunk"]!!.jsonPrimitive.long
            val request = PlanRequest(
                nowTs = c["now_ts"]!!.jsonPrimitive.long,
                tzMinutes = c["tz"]!!.jsonPrimitive.int,
                caps = WalletCaps(
                    capWeekLamports = caps["remaining_week"]!!.jsonPrimitive.long,
                    capShiftLamports = caps["cap_shift"]!!.jsonPrimitive.long,
                    capRoundLamports = chunk,
                    capMaxCost = 0,
                    capsExpiryTs = Long.MAX_VALUE,
                ),
                digLamports = chunk,
            )
            val plan = RhythmShiftPlanner(params).plan(events(c), request)
            assertEquals("$name nights", c["nights"]!!.jsonPrimitive.int, plan.nightsObserved)
            val s = c["slots"]!!.jsonObject
            val starts = s["start_ts"]!!.jsonArray.map { it.jsonPrimitive.long }
            assertEquals(starts, plan.slots.map { it.startTs })
            for ((key, getter) in listOf<Pair<String, (Int) -> Double>>(
                "p" to { i -> plan.slots[i].pIdle },
                "lower" to { i -> plan.slots[i].lower },
                "evidence" to { i -> plan.slots[i].evidence },
            )) {
                val want = TestResources.doubles(s[key]!!)
                for (i in want.indices) close(want[i], getter(i), "$name slot $i $key")
            }
            checkWindow(name, c["window"]!!, plan.nextWindow)
            val week = c["week"]!!.jsonArray
            assertEquals(7, plan.week.size)
            for (n in 0 until 7) checkWindow("$name night $n", week[n], plan.week[n].window)
            val rounds = TestResources.doubles(c["expected_rounds"]!!)
            for (n in 0 until 7) close(rounds[n], plan.week[n].expectedIdleRounds, "$name rounds $n")
            val budget = c["budget"]!!.jsonArray.map { it.jsonPrimitive.long }
            assertEquals("$name budget", budget, plan.week.map { it.lamports })
        }
        println("planner vectors: max |kotlin - python| = $maxDiff over ${cases.size} cases")
    }

    @Test
    fun `the vectors exercise windows, alarms, auto-arm decisions and a binding weekly cap`() {
        val windows = cases.flatMap { it["week"]!!.jsonArray }.filter { it !is JsonNull }.map { it.jsonObject }
        assertTrue("windows proposed", windows.size >= 20)
        val reasons = windows.map { it["reason_code"]!!.jsonPrimitive.content }.toSet()
        assertTrue("history-gated windows are covered", "history" in reasons)
        val tight = cases.first { it["name"]!!.jsonPrimitive.content.contains("tight_week") }
        val total = tight["budget"]!!.jsonArray.sumOf { it.jsonPrimitive.long }
        assertTrue("the tight week spends at most its remaining cap", total <= tight["caps"]!!.jsonObject["remaining_week"]!!.jsonPrimitive.long)
    }

    private fun checkWindow(what: String, expected: kotlinx.serialization.json.JsonElement, actual: ShiftWindow?) {
        if (expected is JsonNull) {
            assertNull(what, actual)
            return
        }
        val e = expected.jsonObject
        assertNotNull(what, actual)
        actual!!
        assertEquals("$what start", e["start_ts"]!!.jsonPrimitive.long, actual.startTs)
        assertEquals("$what end", e["end_ts"]!!.jsonPrimitive.long, actual.endTs)
        assertEquals("$what slots", e["slots"]!!.jsonPrimitive.int, actual.slots)
        close(e["mean_p"]!!.jsonPrimitive.double, actual.meanP, "$what mean_p")
        close(e["min_p"]!!.jsonPrimitive.double, actual.minP, "$what min_p")
        close(e["expected_idle_hours"]!!.jsonPrimitive.double, actual.expectedIdleHours, "$what hours")
        assertEquals("$what auto", e["auto_arm"]!!.jsonPrimitive.boolean, actual.autoArm)
        assertEquals("$what reason", e["reason_code"]!!.jsonPrimitive.content, actual.reason.wire)
    }
}
