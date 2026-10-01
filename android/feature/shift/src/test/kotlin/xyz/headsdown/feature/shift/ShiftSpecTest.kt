package xyz.headsdown.feature.shift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-chain plan facts a shift carries: heartbeat lease and plan window. */
class ShiftSpecTest {

    @Test
    fun `heartbeats lease 1 to 3 rounds`() {
        assertEquals(1, ShiftSpec(1, ShiftMode.NIGHT).leaseRounds)
        assertEquals(3, ShiftSpec(1, ShiftMode.DAY, leaseRounds = 3).leaseRounds)
        assertThrows(IllegalArgumentException::class.java) { ShiftSpec(1, ShiftMode.NIGHT, leaseRounds = 0) }
        assertThrows(IllegalArgumentException::class.java) { ShiftSpec(1, ShiftMode.NIGHT, leaseRounds = 4) }
    }

    @Test
    fun `a BREAK is sent inside the plan window, never after it`() {
        val spec = ShiftSpec(1, ShiftMode.NIGHT, windowEndUnix = 1_790_668_800L)
        assertTrue(spec.breakStillUseful(1_790_650_000L))
        assertTrue(spec.breakStillUseful(1_790_668_800L))
        // After the window the program digs nothing; a BREAK would only cost the night's streak.
        assertFalse(spec.breakStillUseful(1_790_668_801L))
        // A shift armed without a known window (focus-only fallback) always relays.
        assertTrue(ShiftSpec(1, ShiftMode.FOCUS_ONLY).breakStillUseful(Long.MAX_VALUE))
    }
}
