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

    private val ui = listOf(HapticCue.UI_KNOCK, HapticCue.UI_SNAP, HapticCue.UI_CONFIRM)

    @Test
    fun `UI cues exist only in the open app, never during a shift or over the reveal`() {
        ui.forEach { cue ->
            assertFalse("$cue during a shift", governor.tryAcquire(cue, HapticMoment.SHIFT))
            assertFalse("$cue over the reveal", governor.tryAcquire(cue, HapticMoment.REVEAL))
            assertEquals(listOf(HapticMoment.FOREGROUND), HapticMoment.entries.filter { HapticGovernor.allowedAt(cue, it) })
        }
        // A refusal took nothing: each still plays in the foreground, and the shift's budget is whole.
        ui.forEach { cue -> assertTrue("$cue", governor.tryAcquire(cue, HapticMoment.FOREGROUND)) }
        assertTrue(governor.tryAcquire(HapticCue.ARM_THUNK, HapticMoment.SHIFT))
        now += 61_000
        assertTrue(governor.tryAcquire(HapticCue.COOLING_TICK, HapticMoment.SHIFT))
    }

    @Test
    fun `each UI cue has its own minimum interval`() {
        for ((cue, interval) in listOf(HapticCue.UI_KNOCK to 180L, HapticCue.UI_SNAP to 400L, HapticCue.UI_CONFIRM to 1_500L)) {
            assertTrue("$cue", governor.tryAcquire(cue, HapticMoment.FOREGROUND))
            now += interval - 1
            assertFalse("$cue a millisecond early", governor.tryAcquire(cue, HapticMoment.FOREGROUND))
            now += 1
            assertTrue("$cue on time", governor.tryAcquire(cue, HapticMoment.FOREGROUND))
            now += 60_000
        }
    }

    @Test
    fun `UI cues share a budget of eight in ten seconds`() {
        // A finger drumming on the slab five times a second for a minute.
        val played = ArrayList<Long>()
        val start = now
        repeat(300) { i ->
            val cue = if (i % 3 == 2) HapticCue.UI_SNAP else HapticCue.UI_KNOCK
            if (governor.tryAcquire(cue, HapticMoment.FOREGROUND)) played += now - start
            now += 200
        }
        // No ten-second stretch holds more than eight.
        for (t in played) assertTrue("more than eight from $t", played.count { it >= t && it < t + 10_000 } <= 8)
        assertEquals("the first eight play back to back", listOf(0L, 200L, 400L, 600L, 800L, 1000L, 1200L, 1400L), played.take(8))
        // The ninth waits for the first to leave the window.
        assertEquals(10_000L, played[8])
        assertTrue("played ${played.size} in a minute", played.size in 40..48)
    }

    @Test
    fun `the UI budget is the UI cues' alone`() {
        repeat(8) {
            assertTrue(governor.tryAcquire(HapticCue.UI_KNOCK, HapticMoment.FOREGROUND))
            now += 200
        }
        assertFalse(governor.tryAcquire(HapticCue.UI_KNOCK, HapticMoment.FOREGROUND))
        assertFalse("the budget is shared by all three", governor.tryAcquire(HapticCue.UI_CONFIRM, HapticMoment.FOREGROUND))
        // The rig's own cues are not UI cues: they are untouched by it, at either moment.
        assertTrue(governor.tryAcquire(HapticCue.COOLING_TICK, HapticMoment.FOREGROUND))
        assertTrue(governor.tryAcquire(HapticCue.ARM_THUNK, HapticMoment.SHIFT))
        // A refused UI cue did not take a place either: once the window has moved on, eight more.
        now += 10_000
        repeat(8) {
            assertTrue(governor.tryAcquire(HapticCue.UI_KNOCK, HapticMoment.FOREGROUND))
            now += 200
        }
    }

    @Test
    fun `moment matrix of the UI cues`() {
        ui.forEach { cue ->
            assertFalse(HapticGovernor.allowedAt(cue, HapticMoment.SHIFT))
            assertFalse(HapticGovernor.allowedAt(cue, HapticMoment.REVEAL))
            assertTrue(HapticGovernor.allowedAt(cue, HapticMoment.FOREGROUND))
        }
        // The whole vocabulary: nothing but the two shift cues exists during a shift.
        assertEquals(
            listOf(HapticCue.ARM_THUNK, HapticCue.COOLING_TICK),
            HapticCue.entries.filter { HapticGovernor.allowedAt(it, HapticMoment.SHIFT) },
        )
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
