package xyz.headsdown.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.feature.shift.BreakReason
import xyz.headsdown.feature.shift.CoolReason
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSnapshot
import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.surface.haptics.HapticCue
import xyz.headsdown.surface.widget.RigHeat

class ShiftSurfacesTest {
    private val night = ShiftSpec(7, ShiftMode.NIGHT, plannedRounds = 370)
    private val armed = ShiftState.Armed(night, since = 0)
    private val down = ShiftState.Down(night, since = 10, firstDownAt = 10)
    private fun cooling(reason: CoolReason) = ShiftState.Cooling(night, since = 20, deadline = 30, reason = reason, firstDownAt = 10)

    @Test
    fun `heat mapping`() {
        fun heat(state: ShiftState) = ShiftSurfaces.widgetRig(ShiftSnapshot(state)).heat
        assertEquals(RigHeat.COLD, heat(ShiftState.Idle))
        assertEquals(RigHeat.ARMED, heat(armed))
        assertEquals(RigHeat.HOT, heat(down))
        assertEquals(RigHeat.COOLING, heat(cooling(CoolReason.LIFTED)))
        assertEquals(RigHeat.COLD, heat(ShiftState.Broken(night, 40, BreakReason.UNLOCKED)))
        assertEquals(RigHeat.FROZEN, heat(ShiftState.Frozen(7, 50)))
    }

    @Test
    fun `widget knows when heartbeats cannot dig`() {
        val base = ShiftSnapshot(down, darkRounds = 12, darkSinceWallMillis = 1_000L)
        assertFalse(ShiftSurfaces.widgetRig(base).canDig) // defaults: unsigned, local-only
        assertFalse(ShiftSurfaces.widgetRig(base.copy(signing = true, localOnly = true)).canDig)
        assertTrue(ShiftSurfaces.widgetRig(base.copy(signing = true, localOnly = false)).canDig)
        val rig = ShiftSurfaces.widgetRig(base)
        assertEquals(12, rig.darkRounds)
        assertEquals(1_000L, rig.darkSinceWallMillis)
        val focus = ShiftSurfaces.widgetRig(ShiftSnapshot(ShiftState.Armed(ShiftSpec(8, ShiftMode.FOCUS_ONLY), 0)))
        assertTrue(focus.focusOnly)
    }

    @Test
    fun `one arm thunk when the rig first goes hot`() {
        assertEquals(HapticCue.ARM_THUNK, ShiftSurfaces.cueFor(armed, down))
        // Going dark again after cooling (3 am) is silent.
        assertNull(ShiftSurfaces.cueFor(cooling(CoolReason.LIFTED), down))
        assertNull(ShiftSurfaces.cueFor(ShiftState.Idle, armed))
    }

    @Test
    fun `cooling ticks only when the user lifted or woke the phone`() {
        assertEquals(HapticCue.COOLING_TICK, ShiftSurfaces.cueFor(down, cooling(CoolReason.LIFTED)))
        assertEquals(HapticCue.COOLING_TICK, ShiftSurfaces.cueFor(down, cooling(CoolReason.SCREEN_ON)))
        // A charger that slips at 3 am must not buzz the nightstand.
        assertNull(ShiftSurfaces.cueFor(down, cooling(CoolReason.UNPLUGGED)))
    }

    @Test
    fun `a whole night of rounds produces no cue`() {
        var previous: ShiftState = down
        var cues = 0
        repeat(370) {
            // Each round the service republishes the same Down state with a new count.
            val next = ShiftState.Down(night, since = 10, firstDownAt = 10)
            if (ShiftSurfaces.cueFor(previous, next) != null) cues++
            previous = next
        }
        assertEquals(0, cues)
    }

    @Test
    fun `breaks, freezes and ends are silent`() {
        assertNull(ShiftSurfaces.cueFor(cooling(CoolReason.LIFTED), ShiftState.Broken(night, 40, BreakReason.LIFTED)))
        assertNull(ShiftSurfaces.cueFor(down, ShiftState.Frozen(7, 50)))
        assertNull(ShiftSurfaces.cueFor(down, ShiftState.Idle))
    }
}
