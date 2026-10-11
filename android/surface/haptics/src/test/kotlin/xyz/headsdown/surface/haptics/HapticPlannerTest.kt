package xyz.headsdown.surface.haptics

import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticPlannerTest {

    private class FakeDevice(
        override val hasVibrator: Boolean = true,
        override val hasAmplitudeControl: Boolean = true,
        val supported: Set<Int> = ALL,
    ) : HapticDevice {
        val asked = mutableListOf<List<Int>>()
        override fun areAllPrimitivesSupported(vararg primitiveIds: Int): Boolean {
            asked += primitiveIds.toList()
            return primitiveIds.all { it in supported }
        }
    }

    private val loud = SoundPolicy(ringerNormal = true)

    @Test
    fun `full LRA composes every cue and never adds sound`() {
        HapticCue.entries.forEach { cue ->
            val plan = HapticPlanner.plan(cue, FakeDevice(), loud)
            assertTrue("$cue", plan is HapticPlan.Composed)
            assertEquals(HapticScripts.of(cue).primitives, (plan as HapticPlan.Composed).steps)
            assertFalse(plan.withSound)
        }
    }

    @Test
    fun `asks areAllPrimitivesSupported for exactly the cue's primitives`() {
        val device = FakeDevice()
        HapticPlanner.plan(HapticCue.ARM_THUNK, device, loud)
        assertEquals(listOf(listOf(Composition.PRIMITIVE_THUD, Composition.PRIMITIVE_LOW_TICK)), device.asked)
    }

    @Test
    fun `one missing primitive falls back to the amplitude waveform`() {
        val device = FakeDevice(supported = ALL - Composition.PRIMITIVE_THUD)
        val plan = HapticPlanner.plan(HapticCue.ARM_THUNK, device, loud)
        val script = HapticScripts.of(HapticCue.ARM_THUNK)
        assertEquals(HapticPlan.AmplitudeWaveform(script.timings, script.amplitudes, withSound = true), plan)
        // The cooling tick uses only LOW_TICK, which is still supported.
        assertTrue(HapticPlanner.plan(HapticCue.COOLING_TICK, device, loud) is HapticPlan.Composed)
    }

    @Test
    fun `basic motor (Redmi 14C class) gets on-off pattern plus the audible thunk`() {
        val redmi = FakeDevice(hasAmplitudeControl = false, supported = emptySet())
        val arm = HapticPlanner.plan(HapticCue.ARM_THUNK, redmi, loud)
        assertEquals(HapticPlan.OnOffWaveform(HapticScripts.of(HapticCue.ARM_THUNK).onOff, withSound = true), arm)
        // Only the thunk has a sound; the other cues stay silent-but-buzzing.
        listOf(HapticCue.COOLING_TICK, HapticCue.REVEAL_DRUMROLL, HapticCue.MOTHERLODE_FLOURISH).forEach {
            val plan = HapticPlanner.plan(it, redmi, loud)
            assertTrue("$it", plan is HapticPlan.OnOffWaveform)
            assertFalse("$it", plan.withSound)
        }
    }

    @Test
    fun `silent or vibrate ringer and the user's preference suppress the sound`() {
        val redmi = FakeDevice(hasAmplitudeControl = false, supported = emptySet())
        assertFalse(HapticPlanner.plan(HapticCue.ARM_THUNK, redmi, SoundPolicy(ringerNormal = false)).withSound)
        assertFalse(HapticPlanner.plan(HapticCue.ARM_THUNK, redmi, SoundPolicy(ringerNormal = true, enabled = false)).withSound)
    }

    @Test
    fun `no vibrator means sound only for the thunk and silence otherwise`() {
        val none = FakeDevice(hasVibrator = false)
        assertEquals(HapticPlan.SoundOnly, HapticPlanner.plan(HapticCue.ARM_THUNK, none, loud))
        assertEquals(HapticPlan.Silent, HapticPlanner.plan(HapticCue.ARM_THUNK, none, SoundPolicy(ringerNormal = false)))
        assertEquals(HapticPlan.Silent, HapticPlanner.plan(HapticCue.COOLING_TICK, none, loud))
        assertTrue(none.asked.isEmpty())
    }

    @Test
    fun `scripts are short and well formed`() {
        HapticCue.entries.forEach { cue ->
            val s = HapticScripts.of(cue)
            val composedMillis = s.primitives.sumOf { it.delayMillis }
            assertTrue("$cue waveform ${s.waveformMillis} ms", s.waveformMillis in 1..3_000)
            assertTrue("$cue composition delays $composedMillis ms", composedMillis <= 2_500)
            assertTrue("$cue on/off", s.onOff.sum() in 1..3_000)
            // Composition size stays well under what HALs accept.
            assertTrue("$cue primitives ${s.primitives.size}", s.primitives.size <= 20)
        }
        // Only the thunk is ever audible.
        assertEquals(listOf(HapticCue.ARM_THUNK), HapticCue.entries.filter { HapticScripts.of(it).audibleFallback })
    }

    @Test
    fun `UI cues take the motor's own predefined effect when there are no primitives`() {
        // The Redmi 14C: no primitives, no amplitude control. A 10 ms on/off pattern barely
        // starts such a motor; the driver's tuned tick does.
        val redmi = FakeDevice(hasAmplitudeControl = false, supported = emptySet())
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_TICK), HapticPlanner.plan(HapticCue.UI_KNOCK, redmi, loud))
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_CLICK), HapticPlanner.plan(HapticCue.UI_SNAP, redmi, loud))
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_HEAVY_CLICK), HapticPlanner.plan(HapticCue.UI_CONFIRM, redmi, loud))
        // The tier sits between composed and waveform: amplitude control alone does not skip it.
        val noPrimitives = FakeDevice(hasAmplitudeControl = true, supported = emptySet())
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_TICK), HapticPlanner.plan(HapticCue.UI_KNOCK, noPrimitives, loud))
        // One primitive of two missing is still "not composed".
        val clickOnly = FakeDevice(supported = setOf(Composition.PRIMITIVE_CLICK))
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_HEAVY_CLICK), HapticPlanner.plan(HapticCue.UI_CONFIRM, clickOnly, loud))
        assertEquals(
            HapticPlan.Composed(listOf(PrimitiveStep(Composition.PRIMITIVE_CLICK, 0.5f))),
            HapticPlanner.plan(HapticCue.UI_SNAP, clickOnly, loud),
        )
        // Never a sound, and nothing at all without a motor.
        listOf(HapticCue.UI_KNOCK, HapticCue.UI_SNAP, HapticCue.UI_CONFIRM).forEach { cue ->
            assertFalse("$cue", HapticPlanner.plan(cue, redmi, loud).withSound)
            assertEquals("$cue", HapticPlan.Silent, HapticPlanner.plan(cue, FakeDevice(hasVibrator = false), loud))
        }
    }

    @Test
    fun `only the UI cues declare a predefined effect, so no other cue's plan has changed`() {
        assertEquals(
            listOf(HapticCue.UI_KNOCK, HapticCue.UI_SNAP, HapticCue.UI_CONFIRM),
            HapticCue.entries.filter { HapticScripts.of(it).predefined != null },
        )
        assertEquals(listOf(HapticCue.UI_KNOCK, HapticCue.UI_SNAP, HapticCue.UI_CONFIRM), HapticCue.entries.filter { it.isUi })
        val redmi = FakeDevice(hasAmplitudeControl = false, supported = emptySet())
        val waveform = FakeDevice(hasAmplitudeControl = true, supported = emptySet())
        HapticCue.entries.filterNot { it.isUi }.forEach { cue ->
            val script = HapticScripts.of(cue)
            val sound = script.audibleFallback
            assertEquals("$cue", HapticPlan.OnOffWaveform(script.onOff, withSound = sound), HapticPlanner.plan(cue, redmi, loud))
            assertEquals(
                "$cue",
                HapticPlan.AmplitudeWaveform(script.timings, script.amplitudes, withSound = sound),
                HapticPlanner.plan(cue, waveform, loud),
            )
        }
    }

    @Test
    fun `the UI scripts are the ones the design names`() {
        val knock = HapticScripts.of(HapticCue.UI_KNOCK)
        assertEquals(listOf(PrimitiveStep(Composition.PRIMITIVE_TICK, 0.6f)), knock.primitives)
        assertEquals(listOf(0L, 10L), knock.onOff)
        val snap = HapticScripts.of(HapticCue.UI_SNAP)
        assertEquals(listOf(PrimitiveStep(Composition.PRIMITIVE_CLICK, 0.5f)), snap.primitives)
        assertEquals(listOf(0L, 12L), snap.onOff)
        val confirm = HapticScripts.of(HapticCue.UI_CONFIRM)
        assertEquals(
            listOf(PrimitiveStep(Composition.PRIMITIVE_CLICK, 0.7f), PrimitiveStep(Composition.PRIMITIVE_TICK, 0.4f, delayMillis = 60)),
            confirm.primitives,
        )
        assertEquals(listOf(0L, 18L), confirm.onOff)
        // Every UI cue is over in under a tenth of a second on any motor.
        listOf(knock, snap, confirm).forEach { script ->
            assertTrue(script.waveformMillis <= 100)
            assertTrue(script.onOff.sum() <= 20)
            assertTrue(script.predefined in HapticScript.PREDEFINED)
        }
    }

    @Test
    fun `the cooling tick is a single quiet pulse`() {
        val s = HapticScripts.of(HapticCue.COOLING_TICK)
        assertEquals(1, s.primitives.size)
        assertTrue(s.primitives.single().scale <= 0.5f)
        assertEquals(1, s.amplitudes.count { it > 0 })
    }

    private companion object {
        val ALL = setOf(
            Composition.PRIMITIVE_CLICK, Composition.PRIMITIVE_THUD, Composition.PRIMITIVE_SPIN,
            Composition.PRIMITIVE_QUICK_RISE, Composition.PRIMITIVE_SLOW_RISE, Composition.PRIMITIVE_QUICK_FALL,
            Composition.PRIMITIVE_TICK, Composition.PRIMITIVE_LOW_TICK,
        )
    }
}
