package xyz.headsdown.feature.shift

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which transitions count as the morning's first pickup (the haul's `first_pickup_ts`). */
class PickupTest {

    private val clock = FakeClock()
    private val night = ShiftSpec(shiftId = 7, mode = ShiftMode.NIGHT)

    private fun hot(): ShiftStateMachine = ShiftStateMachine(clock, graceMillis = 10_000, initialSignals = Signals(charging = true)).apply {
        dispatch(ShiftEvent.Arm(night))
        dispatch(ShiftEvent.ScreenOff)
        dispatch(ShiftEvent.Posture(faceDown = true))
        check(state is ShiftState.Down)
    }

    @Test
    fun `lifting the dark phone is a pickup`() {
        assertTrue(hot().dispatch(ShiftEvent.Posture(faceDown = false)).isPickup)
    }

    @Test
    fun `unlocking is a pickup, and so is ending the shift by hand`() {
        assertTrue(hot().dispatch(ShiftEvent.UserPresent).isPickup)
        assertTrue(hot().dispatch(ShiftEvent.End).isPickup)
    }

    @Test
    fun `a notification lighting the screen or an unplugged charger is not`() {
        assertFalse(hot().dispatch(ShiftEvent.ScreenOn).isPickup)
        assertFalse(hot().dispatch(ShiftEvent.Power(charging = false)).isPickup)
    }

    @Test
    fun `nothing before the rig first went dark counts`() {
        val m = ShiftStateMachine(clock, graceMillis = 10_000, initialSignals = Signals(charging = true))
        m.dispatch(ShiftEvent.Arm(night))
        assertFalse(m.dispatch(ShiftEvent.Posture(faceDown = false)).isPickup)
        assertFalse(m.dispatch(ShiftEvent.End).isPickup)
    }
}
