package xyz.headsdown.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.feature.reveal.haul.HonestCopy
import java.time.ZoneId
import java.time.ZonedDateTime

/** A Night Shift ends just before the user's own alarm, so that waking up is not a BREAK. */
class ShiftWindowTest {

    private val lagos = ZoneId.of("Africa/Lagos")
    private val night = ClockInPolicy()
    private val day = ClockInPolicy(day = true, windowSeconds = 25 * 60)

    /** 23:30 local on 3 October 2026. */
    private val now = ZonedDateTime.of(2026, 10, 3, 23, 30, 0, 0, lagos).toInstant().toEpochMilli()
    private fun alarmIn(hours: Int, minutes: Int = 0) = now + (hours * 3600L + minutes * 60L) * 1000

    @Test
    fun `an alarm in the morning ends the shift two minutes before it`() {
        // Alarm at 06:30, seven hours away: the window is seven hours less two minutes, not the policy's eight.
        assertEquals(7 * 3600L - 120, ShiftWindow.seconds(night, now, alarmIn(7)))
        assertEquals(alarmIn(7), ShiftWindow.alarmFor(night, now, alarmIn(7)))
        // A later alarm lengthens it past the policy's eight hours, up to the bound.
        assertEquals(9 * 3600L + 30 * 60 - 120, ShiftWindow.seconds(night, now, alarmIn(9, 30)))
    }

    @Test
    fun `no alarm, an alarm about to ring or one far away leaves the policy's length`() {
        assertEquals(night.windowSeconds, ShiftWindow.seconds(night, now, null))
        assertEquals(night.windowSeconds, ShiftWindow.seconds(night, now, alarmIn(0, 20)))
        assertEquals(night.windowSeconds, ShiftWindow.seconds(night, now, alarmIn(20)))
        assertEquals(night.windowSeconds, ShiftWindow.seconds(night, now, now - 60_000)) // already rang
        assertNull(ShiftWindow.alarmFor(night, now, alarmIn(0, 20)))
        // The bounds themselves: 30 minutes and 14 hours of window.
        assertEquals(ShiftWindow.MIN_SECONDS, ShiftWindow.seconds(night, now, now + (ShiftWindow.MIN_SECONDS + 120) * 1000))
        assertEquals(night.windowSeconds, ShiftWindow.seconds(night, now, now + (ShiftWindow.MIN_SECONDS + 119) * 1000))
        assertEquals(ShiftWindow.MAX_SECONDS, ShiftWindow.seconds(night, now, now + (ShiftWindow.MAX_SECONDS + 120) * 1000))
        assertEquals(night.windowSeconds, ShiftWindow.seconds(night, now, now + (ShiftWindow.MAX_SECONDS + 121) * 1000))
    }

    @Test
    fun `a Day Shift keeps its own length whatever the alarm says`() {
        assertEquals(25 * 60L, ShiftWindow.seconds(day, now, alarmIn(7)))
        assertNull(ShiftWindow.alarmFor(day, now, alarmIn(7)))
    }

    @Test
    fun `every window is one the clock-in request accepts`() {
        for (alarm in listOf(null, alarmIn(0, 20), alarmIn(0, 33), alarmIn(7), alarmIn(13, 59), alarmIn(20))) {
            val seconds = ShiftWindow.seconds(night, now, alarm)
            night.request().copy(windowSeconds = seconds) // throws if out of the contract's 1 minute to 24 hours
            assertTrue(seconds in 60..24 * 3600L)
        }
    }

    @Test
    fun `the line next to the button says until when, and what a pickup before then does`() {
        assertEquals(
            "A shift you start now runs until 06:28, two minutes before your alarm. Picking the phone up before then ends the shift early.",
            ShiftWindow.describe(night, now, alarmIn(7), lagos),
        )
        assertEquals(
            "A shift you start now runs until 07:30. Picking the phone up before then ends the shift early. " +
                "Set your alarm first, and the shift ends just before it.",
            ShiftWindow.describe(night, now, null, lagos),
        )
        val dayLine = ShiftWindow.describe(day, now, alarmIn(7), lagos)
        assertEquals("A shift you start now runs until 23:55. Picking the phone up before then ends the shift early.", dayLine)
        assertFalse(dayLine.contains("alarm"))
        for (line in listOf(ShiftWindow.describe(night, now, alarmIn(7), lagos), ShiftWindow.describe(night, now, null, lagos), dayLine)) {
            assertEquals(line, emptyList<String>(), HonestCopy.violations(line))
        }
    }
}
