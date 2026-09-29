package xyz.headsdown.feature.shift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.surface.notification.RigPhase

class ShiftSupportTest {

    @Test
    fun `redmi style virtual proximity is not trusted`() {
        assertEquals(ProximityKind.VIRTUAL, ProximityClassifier.classify("Elliptic Proximity", "Elliptic Labs"))
        assertEquals(ProximityKind.VIRTUAL, ProximityClassifier.classify("proximity_virtual Non-wakeup", "xiaomi"))
        assertEquals(ProximityKind.PHYSICAL, ProximityClassifier.classify("TMD3702 Proximity", "ams AG"))
        assertEquals(ProximityKind.NONE, ProximityClassifier.classify(null, null))
    }

    @Test
    fun `only a physical proximity sensor is usable`() {
        val base = SensorProfile(true, false, false, ProximityKind.VIRTUAL, true)
        assertFalse(base.proximityUsable)
        assertTrue(base.copy(proximity = ProximityKind.PHYSICAL).proximityUsable)
    }

    @Test
    fun `day shift presets convert to whole ORE rounds`() {
        assertEquals(20, ShiftSpec.roundsForMinutes(25)) // 1500 s / 78 = 19.2 -> 20
        assertEquals(39, ShiftSpec.roundsForMinutes(50))
        assertEquals(70, ShiftSpec.roundsForMinutes(90))
    }

    @Test
    fun `shift spec validation`() {
        assertThrows(IllegalArgumentException::class.java) { ShiftSpec(-1, ShiftMode.DAY) }
        assertThrows(IllegalArgumentException::class.java) { ShiftSpec(1, ShiftMode.DAY, plannedRounds = 0) }
        assertTrue(ShiftSpec(1, ShiftMode.NIGHT).requiresCharger)
        assertFalse(ShiftSpec(1, ShiftMode.DAY).requiresCharger)
    }

    @Test
    fun `only armed, down and cooling count as a running shift`() {
        val spec = ShiftSpec(1, ShiftMode.DAY)
        assertTrue(ShiftState.Armed(spec, 0).isRunning)
        assertTrue(ShiftState.Down(spec, 0, 0).isRunning)
        assertTrue(ShiftState.Cooling(spec, 0, 1, CoolReason.LIFTED, 0).isRunning)
        assertFalse(ShiftState.Idle.isRunning)
        assertFalse(ShiftState.Broken(spec, 0, BreakReason.LIFTED).isRunning)
        assertFalse(ShiftState.Frozen(1, 0).isRunning)
    }

    @Test
    fun `snapshot maps onto notification phases`() {
        val spec = ShiftSpec(1, ShiftMode.FOCUS_ONLY, plannedRounds = 20)
        val cases = mapOf(
            ShiftState.Armed(spec, 0) to RigPhase.ARMED,
            ShiftState.Down(spec, 0, 0) to RigPhase.HOT,
            ShiftState.Cooling(spec, 0, 1, CoolReason.LIFTED, 0) to RigPhase.COOLING,
            ShiftState.Broken(spec, 0, BreakReason.UNLOCKED) to RigPhase.COLD,
            ShiftState.Frozen(1, 0) to RigPhase.FROZEN,
            ShiftState.Idle to RigPhase.COLD,
        )
        for ((state, phase) in cases) {
            val n = ShiftSnapshot(state, darkRounds = 3).toNotificationState()
            assertEquals(phase, n.phase)
            assertEquals(3, n.darkRounds)
        }
        val hot = ShiftSnapshot(ShiftState.Down(spec, 0, 0)).toNotificationState()
        assertTrue(hot.focusOnly)
        assertEquals(20, hot.plannedRounds)
        assertNull(ShiftSnapshot(ShiftState.Idle).toNotificationState().plannedRounds)
    }
}
