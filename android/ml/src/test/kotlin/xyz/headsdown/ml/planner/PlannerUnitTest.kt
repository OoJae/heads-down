package xyz.headsdown.ml.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.ml.PlanRequest
import xyz.headsdown.ml.PlannerEvent
import xyz.headsdown.ml.PlannerEvent.Type
import xyz.headsdown.ml.ShiftWindow
import xyz.headsdown.ml.WalletCaps
import kotlin.random.Random

class PlannerUnitTest {
    private val tz = 60 // WAT
    private val min = 60_000L
    private val day0 = 20_000L // local day index
    private fun at(day: Long, minute: Long) = (day0 + day) * SlotLabels.DAY_MS + minute * min - tz * min
    private fun ev(t: Long, type: Type, alarm: Long? = null) = PlannerEvent(t, tz, type, alarmTs = alarm)

    // ------------------------------------------------------------------ log schema

    @Test
    fun `log lines round-trip and junk is skipped`() {
        val e = PlannerEvent(1_790_000_000_000, 60, Type.ALARM_NEXT, alarmTs = 1_790_025_000_000)
        assertEquals(e, PlannerEvent.parse(e.toJsonLine()))
        val s = PlannerEvent(1_790_000_000_000, -300, Type.SHIFT_ENDED, shiftId = 7, reason = 1)
        assertEquals(s, PlannerEvent.parse(s.toJsonLine()))
        listOf(
            "", "not json", "[1,2]", """{"ts":"x","tz":60,"type":"screen_on"}""", """{"ts":1,"tz":60,"type":"teleport"}""",
            """{"ts":2,"tz":900,"type":"screen_on"}""", """{"ts":true,"tz":60,"type":"screen_on"}""", """{"ts":1.5,"tz":60,"type":"screen_on"}""",
            """{"ts":1,"tz":"60","type":"screen_on"}""",
        ).forEach { assertNull(it, PlannerEvent.parse(it)) }
        assertEquals(0, PlannerEvent.parse("""{"ts":5,"type":"screen_off","extra":{"x":1}}""")!!.tz)
    }

    // ------------------------------------------------------------------ slot labels

    @Test
    fun `screen off for a whole slot is idle, an unlock makes it busy, a notification does not`() {
        val events = listOf(
            ev(at(0, 0), Type.MONITOR_START),
            ev(at(0, 0) + 1, Type.SCREEN_OFF),
            // slot 1 (00:15-00:30): a 10 s notification wake-up, no unlock
            ev(at(0, 20), Type.SCREEN_ON),
            ev(at(0, 20) + 10_000, Type.SCREEN_OFF),
            // slot 2 (00:30-00:45): an unlock
            ev(at(0, 35), Type.SCREEN_ON),
            ev(at(0, 35) + 2_000, Type.USER_PRESENT),
            ev(at(0, 36), Type.SCREEN_OFF),
            // slot 4: screen on for 3 of 15 minutes, no unlock (>10% on): busy
            ev(at(0, 61), Type.SCREEN_ON),
            ev(at(0, 64), Type.SCREEN_OFF),
        )
        val labels = SlotLabels.labels(events, at(0, 75))
        assertEquals(SlotLabels.IDLE, labels[SlotLabels.key(day0, 0)])
        assertEquals(SlotLabels.IDLE, labels[SlotLabels.key(day0, 1)])
        assertEquals(SlotLabels.BUSY, labels[SlotLabels.key(day0, 2)])
        assertEquals(SlotLabels.IDLE, labels[SlotLabels.key(day0, 3)])
        assertEquals(SlotLabels.BUSY, labels[SlotLabels.key(day0, 4)])
        assertEquals("nothing after now", null, labels[SlotLabels.key(day0, 5)])
    }

    @Test
    fun `unobserved time is missing, not busy`() {
        val events = listOf(
            ev(at(0, 0), Type.MONITOR_START),
            ev(at(0, 0) + 1, Type.SCREEN_OFF),
            ev(at(0, 20), Type.MONITOR_STOP),
            ev(at(0, 50), Type.MONITOR_START), // screen state unknown until the next screen event
            ev(at(0, 55), Type.SCREEN_OFF),
        )
        val labels = SlotLabels.labels(events, at(0, 90))
        assertEquals(SlotLabels.IDLE, labels[SlotLabels.key(day0, 0)])
        assertNull("5 observed minutes: missing", labels[SlotLabels.key(day0, 1)])
        assertNull("not watching", labels[SlotLabels.key(day0, 2)])
        assertNull("watching, but the screen state is known only from 00:55", labels[SlotLabels.key(day0, 3)])
        assertEquals(SlotLabels.IDLE, labels[SlotLabels.key(day0, 4)])
    }

    @Test
    fun `slots are local time, and the latest alarm report wins only while it is ahead`() {
        // 23:50 local in WAT is 22:50 UTC: still the same local day, slot 95.
        val events = listOf(ev(at(0, 23 * 60 + 40), Type.SCREEN_OFF))
        val labels = SlotLabels.labels(events, at(0, 24 * 60))
        assertEquals(SlotLabels.IDLE, labels[SlotLabels.key(day0, 95)])
        val alarmEvents = listOf(
            ev(at(0, 17 * 60), Type.ALARM_NEXT, alarm = at(1, 6 * 60 + 45)),
            ev(at(0, 18 * 60), Type.ALARM_NEXT, alarm = at(1, 7 * 60)),
        )
        assertEquals(at(1, 7 * 60), SlotLabels.lastAlarm(alarmEvents, at(0, 20 * 60)))
        assertNull("alarm already rang", SlotLabels.lastAlarm(alarmEvents, at(1, 8 * 60)))
        assertNull("not reported yet", SlotLabels.lastAlarm(alarmEvents, at(0, 16 * 60)))
    }

    // ------------------------------------------------------------------ model

    /** Screen off 23:00-07:00 on sleeping nights; awake, an unlock every 10 minutes (every slot busy). */
    private fun nightsOfSleep(days: Int, weekdaysOnly: Boolean = false): List<PlannerEvent> {
        val out = mutableListOf(ev(at(0, 0), Type.MONITOR_START), ev(at(0, 0) + 1, Type.SCREEN_ON))
        var prevSlept = false
        for (d in 0 until days.toLong()) {
            // A "weekday night" starts Monday-Friday evening.
            val sleeps = !weekdaysOnly || SlotLabels.dayOfWeek(day0 + d) < 5
            val awakeFrom = if (prevSlept) 7 * 60 + 10L else 10L
            val awakeTo = if (sleeps) 23 * 60L else 24 * 60L
            var m = awakeFrom
            while (m < awakeTo) {
                out += ev(at(d, m), Type.USER_PRESENT)
                m += 10
            }
            if (sleeps) {
                out += ev(at(d, 23 * 60), Type.SCREEN_OFF)
                out += ev(at(d, 31 * 60), Type.SCREEN_ON)
                out += ev(at(d, 31 * 60) + 1_000, Type.USER_PRESENT)
            }
            prevSlept = sleeps
        }
        return out.sortedBy { it.ts }
    }

    @Test
    fun `no history means the population prior, and history pulls toward the user`() {
        val p = PlannerParams.parse(assetParams())
        val empty = RhythmShiftPlanner(p).plan(emptyList(), PlanRequest(at(0, 18 * 60), tz))
        for (s in empty.slots) {
            val slot = (Math.floorMod(s.startTs + tz * min, SlotLabels.DAY_MS) / SlotLabels.SLOT_MS).toInt()
            assertEquals(p.prior[slot], s.pIdle, 1e-12)
        }
        assertEquals(0, empty.nightsObserved)
        val plan = RhythmShiftPlanner(p).plan(nightsOfSleep(21), PlanRequest(at(21, 18 * 60), tz))
        val night = plan.slots.filter { ((it.startTs + tz * min) / min) % (24 * 60) in (1 * 60) until (5 * 60) }
        val noon = plan.slots.filter { ((it.startTs + tz * min) / min) % (24 * 60) in (12 * 60) until (16 * 60) }
        assertTrue(night.all { it.pIdle > 0.9 })
        assertTrue(noon.all { it.pIdle < 0.5 })
        val w = plan.nextWindow
        assertNotNull(w)
        w!!
        val startLocal = ((w.startTs + tz * min) / min) % (24 * 60)
        val endLocal = ((w.endTs + tz * min) / min) % (24 * 60)
        assertTrue("starts around 23:00, got $startLocal", startLocal in (22 * 60 + 30)..(23 * 60 + 30))
        assertTrue("ends by 07:00, got $endLocal", endLocal in (5 * 60)..(7 * 60))
    }

    @Test
    fun `day-of-week effects separate the nights a user sleeps from the ones they do not`() {
        // Light shrinkage so the mechanism shows clearly (the tuned k1, k2 decide how much of it
        // the shipped model keeps; see planner/RESULTS.md).
        val p = PlannerParams.parse(assetParams()).copy(k1 = 2.0, k2 = 2.0)
        val events = nightsOfSleep(42, weekdaysOnly = true)
        val post = RhythmModel.fit(SlotLabels.labels(events, at(42, 12 * 60)), day0 + 42, p)
        val threeAm = 3 * 4
        // 03:00 belongs to the previous evening's night: idle Tuesday-Saturday, busy Sunday and Monday.
        val slept = (1..5).map { post.mean[it][threeAm] }
        val awake = listOf(0, 6).map { post.mean[it][threeAm] }
        assertTrue("slept $slept vs awake $awake", slept.min() > awake.max() + 0.3)
    }

    @Test
    fun `the next window ends at the alarm`() {
        val p = PlannerParams.parse(assetParams())
        val now = at(21, 18 * 60)
        val alarm = at(22, 6 * 60)
        val events = nightsOfSleep(21) + ev(now - 30 * min, Type.ALARM_NEXT, alarm = alarm)
        val w = RhythmShiftPlanner(p).plan(events.sortedBy { it.ts }, PlanRequest(now, tz)).nextWindow!!
        assertEquals(alarm, w.endTs)
    }

    @Test
    fun `auto-arm needs history and confidence`() {
        val p = PlannerParams.parse(assetParams())
        val short = RhythmShiftPlanner(p).plan(nightsOfSleep(3), PlanRequest(at(3, 18 * 60), tz)).nextWindow
        if (short != null) {
            assertFalse(short.autoArm)
            assertEquals(ShiftWindow.Reason.HISTORY, short.reason)
        }
        val off = RhythmShiftPlanner(PlannerParams.DEFAULT).plan(nightsOfSleep(30), PlanRequest(at(30, 18 * 60), tz)).nextWindow
        assertNotNull(off)
        assertFalse("the fallback parameters never auto-arm", off!!.autoArm)
    }

    // ------------------------------------------------------------------ weekly split bounds

    @Test
    fun `the weekly split never exceeds the caps (randomized)`() {
        val rnd = Random(7)
        repeat(2_000) {
            val windows = List(7) {
                if (rnd.nextInt(5) == 0) null else ShiftWindow(0, 1, 1, 0.9, 0.8, rnd.nextDouble(0.0, 12.0), false, ShiftWindow.Reason.HISTORY)
            }
            val chunk = rnd.nextLong(1, 5_000_000)
            val remaining = rnd.nextLong(-1_000, 2_000_000_000)
            val capShift = rnd.nextLong(-1_000, 500_000_000)
            val split = BudgetSplitter.split(windows, remaining, capShift, chunk, 78.0)
            assertTrue(split.sum() <= maxOf(0, remaining))
            for ((i, v) in split.withIndex()) {
                assertTrue(v >= 0 && v % chunk == 0L)
                assertTrue(v <= maxOf(0, capShift))
                val rounds = RhythmModel.expectedRounds(windows[i], 78.0)
                assertTrue("one chunk per expected idle round at most", v / chunk <= kotlin.math.floor(rounds).toLong())
            }
        }
        assertEquals(0L, BudgetSplitter.split(listOf(null), 10, 10, 0, 78.0).single())
    }

    @Test
    fun `the split follows expected idle rounds and spends the whole cap when nights allow`() {
        val w = { h: Double -> ShiftWindow(0, 1, 1, 0.9, 0.8, h, false, ShiftWindow.Reason.OK) }
        val split = BudgetSplitter.split(listOf(w(8.0), w(4.0), null, w(8.0)), 50_000_000, 40_000_000, 1_000_000, 78.0)
        assertEquals(50_000_000L, split.sum())
        assertEquals(0L, split[2])
        assertTrue(split[0] > split[1] && split[0] == split[3] || split[0] == split[3] + 1_000_000)
    }

    @Test
    fun `the planner caps the dig unit at cap_round`() {
        val p = PlannerParams.parse(assetParams())
        val caps = WalletCaps(capWeekLamports = 100_000_000, capShiftLamports = 60_000_000, capRoundLamports = 500_000, capMaxCost = 1, capsExpiryTs = Long.MAX_VALUE)
        val plan = RhythmShiftPlanner(p).plan(nightsOfSleep(21), PlanRequest(at(21, 18 * 60), tz, caps, digLamports = 2_000_000))
        val lamports = plan.week.mapNotNull { it.lamports }
        assertEquals(7, lamports.size)
        assertTrue(lamports.all { it % 500_000 == 0L && it <= 60_000_000 })
        assertTrue(lamports.sum() <= 100_000_000)
    }

    private fun assetParams(): String = xyz.headsdown.ml.TestResources.asset("planner_params.json").readText()
}
