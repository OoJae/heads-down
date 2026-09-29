package xyz.headsdown.feature.reveal

import org.junit.Assert.assertEquals
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

    @Test
    fun `fallback respects DST in the user's zone`() {
        // UK clocks go back at 02:00 on 2026-10-25: 07:00 local is 07:00 GMT, not BST.
        val now = at(london, 2026, 10, 24, 23, 0)
        val plan = RevealTimePlanner.plan(now, null, london)
        assertEquals(at(london, 2026, 10, 25, 7), plan.triggerAtWallMillis)
        assertEquals(8 * 60 * 60 * 1000L + 60 * 60 * 1000L, plan.triggerAtWallMillis - now) // 9 h across the change
    }
}
