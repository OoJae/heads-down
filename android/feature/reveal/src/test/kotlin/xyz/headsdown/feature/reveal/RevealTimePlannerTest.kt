package xyz.headsdown.feature.reveal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RevealTimePlannerTest {
    private val lagos = ZoneId.of("Africa/Lagos")
    private val london = ZoneId.of("Europe/London")

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `aligns one minute after the user's next alarm`() {
        val now = at(lagos, 2026, 9, 29, 23, 10)
        val alarm = at(lagos, 2026, 9, 30, 6, 30)
        val plan = RevealTimePlanner.plan(now, alarm, lagos)
        assertEquals(RevealPlan(alarm + 60_000, RevealSource.SYSTEM_ALARM), plan)
    }

    @Test
    fun `no alarm falls back to seven tomorrow`() {
        val now = at(lagos, 2026, 9, 29, 23, 10)
        assertEquals(
            RevealPlan(at(lagos, 2026, 9, 30, 7), RevealSource.FALLBACK),
            RevealTimePlanner.plan(now, null, lagos),
        )
    }

    @Test
    fun `early morning shift falls back to seven today`() {
        val now = at(lagos, 2026, 9, 30, 1, 0)
        assertEquals(at(lagos, 2026, 9, 30, 7), RevealTimePlanner.plan(now, null, lagos).triggerAtWallMillis)
    }

    @Test
    fun `fallback too close rolls to the next day`() {
        val now = at(lagos, 2026, 9, 30, 6, 50) // 10 minutes before 07:00 < 15 min lead
        assertEquals(at(lagos, 2026, 10, 1, 7), RevealTimePlanner.plan(now, null, lagos).triggerAtWallMillis)
    }

    @Test
    fun `alarms that are imminent or too far away are ignored`() {
        val now = at(lagos, 2026, 9, 29, 23, 0)
        val imminent = now + 5 * 60_000
        assertEquals(RevealSource.FALLBACK, RevealTimePlanner.plan(now, imminent, lagos).source)
        val dayAfter = at(lagos, 2026, 10, 1, 6, 30)
        assertEquals(RevealSource.FALLBACK, RevealTimePlanner.plan(now, dayAfter, lagos).source)
        val past = now - 60_000
        assertEquals(RevealSource.FALLBACK, RevealTimePlanner.plan(now, past, lagos).source)
    }

    // The shift's window as the app computes it (ShiftWindow): 120 s before a usable alarm,
    // otherwise the policy's eight hours from clock-in. Unix seconds, as the plan carries it.
    private fun eightHoursFrom(nowWallMillis: Long) = nowWallMillis / 1000 + 8 * 3600
    private fun justBefore(alarmWallMillis: Long) = alarmWallMillis / 1000 - 120

    @Test
    fun `the earliest reveal is one minute after the window's end`() {
        val end = at(lagos, 2026, 9, 30, 7, 30)
        assertEquals(at(lagos, 2026, 9, 30, 7, 31), RevealTimePlanner.notBefore(end / 1000))
        assertNull(RevealTimePlanner.notBefore(null))
    }

    @Test
    fun `a clock-in at half past eleven with no alarm reveals after its eight hours, not at seven`() {
        val now = at(lagos, 2026, 9, 29, 23, 30)
        val notBefore = RevealTimePlanner.notBefore(eightHoursFrom(now)) // the window ends 07:30
        assertEquals(
            RevealPlan(at(lagos, 2026, 9, 30, 7, 31), RevealSource.WINDOW_END),
            RevealTimePlanner.plan(now, null, lagos, notBefore),
        )
    }

    @Test
    fun `a morning alarm still decides when the window ends just before it`() {
        val now = at(lagos, 2026, 9, 29, 23, 10)
        val alarm = at(lagos, 2026, 9, 30, 6, 30)
        val notBefore = RevealTimePlanner.notBefore(justBefore(alarm)) // 06:29, before alarm + 60 s
        assertEquals(RevealPlan(alarm + 60_000, RevealSource.SYSTEM_ALARM), RevealTimePlanner.plan(now, alarm, lagos, notBefore))
    }

    @Test
    fun `an alarm twenty minutes ahead does not fire the reveal inside an eight hour window`() {
        // Too close for the window to end at it (under 30 minutes), near enough for the planner (over 15).
        val now = at(lagos, 2026, 9, 29, 23, 30)
        val alarm = at(lagos, 2026, 9, 29, 23, 50)
        assertEquals(RevealPlan(alarm + 60_000, RevealSource.SYSTEM_ALARM), RevealTimePlanner.plan(now, alarm, lagos))
        assertEquals(
            RevealPlan(at(lagos, 2026, 9, 30, 7, 31), RevealSource.WINDOW_END),
            RevealTimePlanner.plan(now, alarm, lagos, RevealTimePlanner.notBefore(eightHoursFrom(now))),
        )
    }

    @Test
    fun `no window known changes nothing`() {
        val now = at(lagos, 2026, 9, 29, 23, 30)
        val alarm = at(lagos, 2026, 9, 30, 6, 30)
        assertEquals(RevealPlan(at(lagos, 2026, 9, 30, 7), RevealSource.FALLBACK), RevealTimePlanner.plan(now, null, lagos, null))
        assertEquals(RevealPlan(alarm + 60_000, RevealSource.SYSTEM_ALARM), RevealTimePlanner.plan(now, alarm, lagos, null))
    }

    @Test
    fun `a window that has already ended, or ends exactly at the planned time, changes nothing`() {
        val now = at(lagos, 2026, 9, 29, 23, 30)
        val seven = at(lagos, 2026, 9, 30, 7)
        assertEquals(RevealPlan(seven, RevealSource.FALLBACK), RevealTimePlanner.plan(now, null, lagos, seven))
        assertEquals(RevealPlan(seven, RevealSource.FALLBACK), RevealTimePlanner.plan(now, null, lagos, now - 60_000))
        assertEquals(RevealPlan(seven + 1, RevealSource.WINDOW_END), RevealTimePlanner.plan(now, null, lagos, seven + 1))
    }

    @Test
    fun `fallback respects DST in the user's zone`() {
        // UK clocks go back at 02:00 on 2026-10-25: 07:00 local is 07:00 GMT, not BST.
        val now = at(london, 2026, 10, 24, 23, 0)
        val plan = RevealTimePlanner.plan(now, null, london)
        assertEquals(at(london, 2026, 10, 25, 7), plan.triggerAtWallMillis)
        assertEquals(8 * 60 * 60 * 1000L + 60 * 60 * 1000L, plan.triggerAtWallMillis - now) // 9 h across the change
    }
}
