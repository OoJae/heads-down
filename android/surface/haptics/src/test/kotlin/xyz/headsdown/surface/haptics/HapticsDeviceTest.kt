package xyz.headsdown.surface.haptics

import android.content.Context
import android.os.VibrationEffect
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
    fun `a UI cue on a basic motor is the driver's predefined effect`() {
        shadow.setHasVibrator(true)
        shadow.setHasAmplitudeControl(false)
        shadow.setSupportedPrimitives(emptyList())
        val h = haptics()
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_TICK), h.play(HapticCue.UI_KNOCK, HapticMoment.FOREGROUND))
        assertEquals(VibrationEffect.EFFECT_TICK, lastPredefinedEffect())
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_HEAVY_CLICK), h.play(HapticCue.UI_CONFIRM, HapticMoment.FOREGROUND))
        assertEquals(VibrationEffect.EFFECT_HEAVY_CLICK, lastPredefinedEffect())
        assertTrue(shadow.primitiveSegmentsInPrimitiveEffects.orEmpty().isEmpty())
    }

    /**
     * The effect id of the one prebaked segment the motor was last given, or null. On Android 12
     * and later a predefined effect is a composition of one `PrebakedSegment`, a platform class
     * that is not in the SDK, kept by the shadow in a field it does not publish: read by reflection.
     */
    private fun lastPredefinedEffect(): Int? {
        val field = ShadowVibrator::class.java.getDeclaredField("vibrationEffectSegments").apply { isAccessible = true }
        val segment = (field.get(null) as List<*>).singleOrNull() ?: return null
        if (segment.javaClass.simpleName != "PrebakedSegment") return null
        return segment.javaClass.getMethod("getEffectId").invoke(segment) as Int
    }

    @Test
    fun `a UI cue on a full motor is composed, and one during a shift never reaches the motor`() {
        shadow.setHasVibrator(true)
        shadow.setSupportedPrimitives(ALL)
        val h = haptics()
        assertEquals(HapticPlan.Silent, h.play(HapticCue.UI_KNOCK, HapticMoment.SHIFT))
        assertEquals(HapticPlan.Silent, h.play(HapticCue.UI_CONFIRM, HapticMoment.REVEAL))
        assertFalse(shadow.isVibrating)
        assertTrue(shadow.primitiveSegmentsInPrimitiveEffects.orEmpty().isEmpty())
        assertTrue(h.play(HapticCue.UI_SNAP, HapticMoment.FOREGROUND) is HapticPlan.Composed)
        assertEquals(
            listOf(ShadowVibrator.PrimitiveEffect(Composition.PRIMITIVE_CLICK, 0.5f, 0)),
            shadow.primitiveSegmentsInPrimitiveEffects,
        )
        // Within the snap's own 400 ms: refused, and the motor is not asked again.
        now += 399
        assertEquals(HapticPlan.Silent, h.play(HapticCue.UI_SNAP, HapticMoment.FOREGROUND))
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
