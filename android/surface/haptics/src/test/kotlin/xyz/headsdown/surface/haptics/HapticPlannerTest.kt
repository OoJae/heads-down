package xyz.headsdown.surface.haptics

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
