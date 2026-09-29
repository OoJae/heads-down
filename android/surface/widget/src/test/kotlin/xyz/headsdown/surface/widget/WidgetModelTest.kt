package xyz.headsdown.surface.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetModelTest {
    private val hot = WidgetRig(heat = RigHeat.HOT, darkSinceWallMillis = 1_000L, darkRounds = 55)

    @Test
    fun `a running shift that ends remembers its dark rounds`() {
        val running = WidgetStateReducer.onRig(RigWidgetState(), hot, bootCount = 7)
        assertNull(running.lastShiftRounds)
        val ended = WidgetStateReducer.onRig(running, WidgetRig(RigHeat.COLD), bootCount = 7)
        assertEquals(55, ended.lastShiftRounds)
        assertEquals(RigHeat.COLD, ended.rig.heat)
        // A later idle push (fresh process) keeps it.
        assertEquals(55, WidgetStateReducer.onRig(ended, WidgetRig(), 7).lastShiftRounds)
    }

    @Test
    fun `a frozen rig also closes the shift`() {
        val running = WidgetStateReducer.onRig(RigWidgetState(), hot, 1)
        assertEquals(55, WidgetStateReducer.onRig(running, WidgetRig(RigHeat.FROZEN), 1).lastShiftRounds)
    }

    @Test
    fun `a running rig from an earlier boot is displayed cold`() {
        val saved = WidgetStateReducer.onRig(RigWidgetState(), hot, bootCount = 7)
        val shown = WidgetStateReducer.forDisplay(saved, currentBootCount = 8)
        assertEquals(RigHeat.COLD, shown.rig.heat)
        assertNull(shown.rig.darkSinceWallMillis)
        assertEquals(55, shown.lastShiftRounds)
        // Same boot, or unknown boot count: shown as saved.
        assertEquals(saved, WidgetStateReducer.forDisplay(saved, currentBootCount = 7))
        assertEquals(saved, WidgetStateReducer.forDisplay(saved, currentBootCount = null))
    }

    @Test
    fun `per-round increments are stored but not rendered`() {
        val a = WidgetStateReducer.onRig(RigWidgetState(), hot, 1)
        val b = WidgetStateReducer.onRig(a, hot.copy(darkRounds = 56), 1)
        assertFalse(WidgetUpdatePolicy.needsRender(a, b))
        assertEquals(56, b.rig.darkRounds)
    }

    @Test
    fun `visible changes are rendered`() {
        val a = WidgetStateReducer.onRig(RigWidgetState(), hot, 1)
        assertTrue(WidgetUpdatePolicy.needsRender(a, WidgetStateReducer.onRig(a, hot.copy(heat = RigHeat.COOLING), 1)))
        assertTrue(WidgetUpdatePolicy.needsRender(a, WidgetStateReducer.onRig(a, hot.copy(canDig = false), 1)))
        assertTrue(WidgetUpdatePolicy.needsRender(a, WidgetStateReducer.onHaul(a, WidgetHaul(1, 2))))
        assertTrue(WidgetUpdatePolicy.needsRender(a, WidgetStateReducer.onStreak(a, 3)))
        assertFalse(WidgetUpdatePolicy.needsRender(a, a))
    }

    @Test
    fun `a whole night of rounds renders only on heat changes`() {
        var state = RigWidgetState()
        var renders = 0
        fun push(rig: WidgetRig) {
            val next = WidgetStateReducer.onRig(state, rig, 1)
            if (WidgetUpdatePolicy.needsRender(state, next)) renders++
            state = next
        }
        push(WidgetRig(RigHeat.ARMED))
        repeat(370) { push(hot.copy(darkRounds = it)) }
        push(WidgetRig(RigHeat.COLD))
        assertEquals(3, renders)
    }

    @Test
    fun `streak never goes negative`() {
        assertEquals(0, WidgetStateReducer.onStreak(RigWidgetState(), -4).streakNights)
    }

    @Test
    fun `chronometer anchors elapsed time to the wall-clock start`() {
        // Dark since 72 minutes ago -> base is 72 minutes before elapsedRealtime "now".
        assertEquals(10_000_000L - 72 * 60_000L, ChronometerAnchor.base(1_000_000L, 1_000_000L + 72 * 60_000L, 10_000_000L))
        // Clock skew (start in the future) shows 0:00, never negative time.
        assertEquals(10_000_000L, ChronometerAnchor.base(2_000_000L, 1_000_000L, 10_000_000L))
    }

    @Test
    fun `only armed hot and cooling are running`() {
        assertEquals(setOf(RigHeat.ARMED, RigHeat.HOT, RigHeat.COOLING), RigHeat.entries.filter { it.running }.toSet())
    }
}
