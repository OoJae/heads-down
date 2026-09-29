package xyz.headsdown.surface.haptics

import android.content.Context
import android.os.VibrationEffect.Composition
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowVibrator

/** [Haptics] against Robolectric's vibrator: the platform calls it really makes. */
@RunWith(RobolectricTestRunner::class)
class HapticsDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val vibrator = Haptics.defaultVibrator(context)!!
    private val shadow = shadowOf(vibrator)
    private var now = 10_000_000L

    private fun haptics() = Haptics(context, vibrator, HapticGovernor({ now }), soundEnabled = { false })

    @After
    fun reset() = ShadowVibrator.reset()

    @Test
    fun `composes primitives when the motor supports all of them`() {
        shadow.setHasVibrator(true)
        shadow.setSupportedPrimitives(ALL)
        val plan = haptics().play(HapticCue.ARM_THUNK, HapticMoment.FOREGROUND)
        assertTrue(plan is HapticPlan.Composed)
        assertEquals(
            listOf(
                ShadowVibrator.PrimitiveEffect(Composition.PRIMITIVE_THUD, 1.0f, 0),
                ShadowVibrator.PrimitiveEffect(Composition.PRIMITIVE_LOW_TICK, 0.35f, 70),
            ),
            shadow.primitiveSegmentsInPrimitiveEffects,
        )
    }

    @Test
    fun `falls back to the waveform when a primitive is missing`() {
        shadow.setHasVibrator(true)
        shadow.setHasAmplitudeControl(true)
        shadow.setSupportedPrimitives(listOf(Composition.PRIMITIVE_TICK))
        val plan = haptics().play(HapticCue.ARM_THUNK, HapticMoment.FOREGROUND)
        assertTrue(plan is HapticPlan.AmplitudeWaveform)
        assertTrue(shadow.primitiveSegmentsInPrimitiveEffects.orEmpty().isEmpty())
        assertEquals(HapticScripts.of(HapticCue.ARM_THUNK).timings, shadow.pattern.toList())
    }

    @Test
    fun `basic motor plays the on-off pattern`() {
        shadow.setHasVibrator(true)
        shadow.setHasAmplitudeControl(false)
        shadow.setSupportedPrimitives(emptyList())
        val plan = haptics().play(HapticCue.COOLING_TICK, HapticMoment.SHIFT)
        assertEquals(HapticPlan.OnOffWaveform(HapticScripts.of(HapticCue.COOLING_TICK).onOff, withSound = false), plan)
        assertEquals(HapticScripts.of(HapticCue.COOLING_TICK).onOff, shadow.pattern.toList())
    }

    @Test
    fun `governor refusals never reach the motor`() {
        shadow.setHasVibrator(true)
        shadow.setSupportedPrimitives(ALL)
        val h = haptics()
        assertEquals(HapticPlan.Silent, h.play(HapticCue.MOTHERLODE_FLOURISH, HapticMoment.SHIFT))
        assertEquals(HapticPlan.Silent, h.play(HapticCue.REVEAL_DRUMROLL, HapticMoment.FOREGROUND))
        assertTrue(shadow.primitiveSegmentsInPrimitiveEffects.orEmpty().isEmpty())
        assertFalse(shadow.isVibrating)
    }

    @Test
    fun `thunk sound loads and plays from res raw`() {
        ThunkSound(context).use { it.play() } // decodes R.raw.hd_thunk; must not throw
    }

    private companion object {
        val ALL = listOf(
            Composition.PRIMITIVE_CLICK, Composition.PRIMITIVE_THUD, Composition.PRIMITIVE_SPIN,
            Composition.PRIMITIVE_QUICK_RISE, Composition.PRIMITIVE_SLOW_RISE, Composition.PRIMITIVE_QUICK_FALL,
            Composition.PRIMITIVE_TICK, Composition.PRIMITIVE_LOW_TICK,
        )
    }
}
