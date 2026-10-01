package xyz.headsdown.feature.shift

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.headsdown.feature.shift.AccelerometerChoice.Pick
import xyz.headsdown.ml.pickup.PickupFeatures

/** The shift listens at the rate the classifier's windows need, on a sensor that can deliver it. */
class AccelerometerChoiceTest {

    @Test
    fun `the service samples at the classifier's 50 Hz grid`() {
        assertEquals(PickupFeatures.STEP_NS / 1_000, AccelerometerChoice.SAMPLING_PERIOD_US.toLong())
        // 5 s at that rate is 250 samples: well over the 100 a usable window needs.
        assertEquals(250L, 5_000_000L / AccelerometerChoice.SAMPLING_PERIOD_US)
    }

    @Test
    fun `a wake-up accelerometer is used only if it can run at 50 Hz`() {
        assertEquals(Pick.WAKE_UP, AccelerometerChoice.pick(wakeUpMinDelayUs = 5_000, defaultMinDelayUs = 2_500))
        assertEquals(Pick.WAKE_UP, AccelerometerChoice.pick(wakeUpMinDelayUs = 20_000, defaultMinDelayUs = 5_000))
        assertEquals("the wake-up variant tops out at 5 Hz", Pick.DEFAULT, AccelerometerChoice.pick(wakeUpMinDelayUs = 200_000, defaultMinDelayUs = 5_000))
        assertEquals("an on-change wake-up sensor does not stream", Pick.DEFAULT, AccelerometerChoice.pick(wakeUpMinDelayUs = 0, defaultMinDelayUs = 10_000))
    }

    @Test
    fun `without a wake-up variant the default sensor is used, whatever its rate`() {
        // The Redmi 14C case: one accelerometer, kept alive by the shift's wake lock.
        assertEquals(Pick.DEFAULT, AccelerometerChoice.pick(wakeUpMinDelayUs = null, defaultMinDelayUs = 5_000))
        assertEquals(Pick.DEFAULT, AccelerometerChoice.pick(wakeUpMinDelayUs = null, defaultMinDelayUs = 100_000))
    }

    @Test
    fun `when neither reaches 50 Hz the one that survives CPU sleep wins, and no sensor is no sensor`() {
        assertEquals(Pick.WAKE_UP, AccelerometerChoice.pick(wakeUpMinDelayUs = 100_000, defaultMinDelayUs = 66_000))
        assertEquals(Pick.WAKE_UP, AccelerometerChoice.pick(wakeUpMinDelayUs = 100_000, defaultMinDelayUs = null))
        assertEquals(Pick.NONE, AccelerometerChoice.pick(wakeUpMinDelayUs = null, defaultMinDelayUs = null))
    }
}
