package xyz.headsdown.surface.haptics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticGovernorTest {
    private var now = 1_000_000L
    private val governor = HapticGovernor({ now })

    @Test
    fun `reveal cues never fire during a shift or in the app`() {
        listOf(HapticCue.REVEAL_DRUMROLL, HapticCue.MOTHERLODE_FLOURISH).forEach { cue ->
            assertFalse(governor.tryAcquire(cue, HapticMoment.SHIFT))
            assertFalse(governor.tryAcquire(cue, HapticMoment.FOREGROUND))
            assertTrue(governor.tryAcquire(cue, HapticMoment.REVEAL))
        }
    }

    @Test
    fun `shift cues are not played over the reveal`() {
        assertFalse(governor.tryAcquire(HapticCue.ARM_THUNK, HapticMoment.REVEAL))
        assertFalse(governor.tryAcquire(HapticCue.COOLING_TICK, HapticMoment.REVEAL))
    }

    @Test
    fun `a caller asking every ORE round all night gets a handful of buzzes`() {
        // 8 hours of 78 s rounds, each one (wrongly) asking for a cooling tick.
        var played = 0
        repeat(8 * 3600 / 78) {
            if (governor.tryAcquire(HapticCue.COOLING_TICK, HapticMoment.SHIFT)) played++
            now += 78_000
        }
        assertTrue("played $played", played <= 2 * 8 + 1)
        assertTrue("played $played", played < 8 * 3600 / 78 / 10)
    }

    @Test
    fun `shift budget is shared by the whole vocabulary`() {
        assertTrue(governor.tryAcquire(HapticCue.ARM_THUNK, HapticMoment.SHIFT))
        now += 61_000
        assertTrue(governor.tryAcquire(HapticCue.COOLING_TICK, HapticMoment.SHIFT))
        now += 61_000
        assertFalse(governor.tryAcquire(HapticCue.COOLING_TICK, HapticMoment.SHIFT))
        now += 60 * 60_000L
        assertTrue(governor.tryAcquire(HapticCue.COOLING_TICK, HapticMoment.SHIFT))
    }

    @Test
    fun `minimum interval per cue`() {
        assertTrue(governor.tryAcquire(HapticCue.COOLING_TICK, HapticMoment.FOREGROUND))
        now += 59_999
        assertFalse(governor.tryAcquire(HapticCue.COOLING_TICK, HapticMoment.FOREGROUND))
        now += 1
        assertTrue(governor.tryAcquire(HapticCue.COOLING_TICK, HapticMoment.FOREGROUND))
        assertTrue(governor.tryAcquire(HapticCue.ARM_THUNK, HapticMoment.FOREGROUND))
        now += 4 * 60_000L
        assertFalse(governor.tryAcquire(HapticCue.ARM_THUNK, HapticMoment.FOREGROUND))
    }

    @Test
    fun `refusals do not consume the budget`() {
        repeat(10) { assertFalse(governor.tryAcquire(HapticCue.REVEAL_DRUMROLL, HapticMoment.SHIFT)) }
        assertTrue(governor.tryAcquire(HapticCue.ARM_THUNK, HapticMoment.SHIFT))
        now += 61_000
        assertTrue(governor.tryAcquire(HapticCue.COOLING_TICK, HapticMoment.SHIFT))
    }

    @Test
    fun `moment matrix`() {
        val allowed = HapticCue.entries.associateWith { cue ->
            HapticMoment.entries.filter { HapticGovernor.allowedAt(cue, it) }
        }
        assertEquals(listOf(HapticMoment.SHIFT, HapticMoment.FOREGROUND), allowed[HapticCue.ARM_THUNK])
        assertEquals(listOf(HapticMoment.SHIFT, HapticMoment.FOREGROUND), allowed[HapticCue.COOLING_TICK])
        assertEquals(listOf(HapticMoment.REVEAL), allowed[HapticCue.REVEAL_DRUMROLL])
        assertEquals(listOf(HapticMoment.REVEAL), allowed[HapticCue.MOTHERLODE_FLOURISH])
    }
}
