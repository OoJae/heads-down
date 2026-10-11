package xyz.headsdown.surface.haptics

/** What the phone's motor can do. Backed by [android.os.Vibrator] on a device, faked in tests. */
interface HapticDevice {
    val hasVibrator: Boolean
    val hasAmplitudeControl: Boolean

    /** `Vibrator.areAllPrimitivesSupported(ids)`: true only if every id is supported. */
    fun areAllPrimitivesSupported(vararg primitiveIds: Int): Boolean
}

/** Whether a sound may accompany a cue right now. */
data class SoundPolicy(
    /** `AudioManager.RINGER_MODE_NORMAL`: silent and vibrate modes never get a sound. */
    val ringerNormal: Boolean,
    /** The user's "audible thunk" preference (on by default). */
    val enabled: Boolean = true,
)

/** How one cue will be rendered on this device. */
sealed interface HapticPlan {
    val withSound: Boolean

    /** `VibrationEffect.startComposition()` with these primitives. */
    data class Composed(val steps: List<PrimitiveStep>, override val withSound: Boolean = false) : HapticPlan

    /**
     * `VibrationEffect.createPredefined(effectId)`: the motor driver's own tuned effect. Only for
     * cues whose script declares one (the UI cues).
     */
    data class Predefined(val effectId: Int, override val withSound: Boolean = false) : HapticPlan

    /** `VibrationEffect.createWaveform(timings, amplitudes, -1)`. */
    data class AmplitudeWaveform(
        val timings: List<Long>,
        val amplitudes: List<Int>,
        override val withSound: Boolean,
    ) : HapticPlan

    /** `VibrationEffect.createWaveform(timings, -1)`: basic motors without amplitude control. */
    data class OnOffWaveform(val timings: List<Long>, override val withSound: Boolean) : HapticPlan

    /** No motor at all: the sound is the only feedback. */
    data object SoundOnly : HapticPlan {
        override val withSound: Boolean get() = true
    }

    data object Silent : HapticPlan {
        override val withSound: Boolean get() = false
    }
}

/**
 * Chooses the richest rendering the motor supports:
 * composed primitives (only if `areAllPrimitivesSupported` for every primitive in the cue), else
 * the cue's predefined effect if it declares one (the UI cues only), else an amplitude waveform,
 * else an on/off pattern. A cue marked [HapticScript.audibleFallback] adds the generated thunk
 * whenever it could not be composed, so a weak motor still "lands".
 */
object HapticPlanner {

    fun plan(cue: HapticCue, device: HapticDevice, sound: SoundPolicy): HapticPlan {
        val script = HapticScripts.of(cue)
        val canSound = script.audibleFallback && sound.enabled && sound.ringerNormal
        if (!device.hasVibrator) return if (canSound) HapticPlan.SoundOnly else HapticPlan.Silent
        if (device.areAllPrimitivesSupported(*script.primitiveIds)) return HapticPlan.Composed(script.primitives)
        // The platform renders these four on any motor, with its own fallback where the driver has none.
        if (script.predefined != null) return HapticPlan.Predefined(script.predefined, withSound = canSound)
        return if (device.hasAmplitudeControl) {
            HapticPlan.AmplitudeWaveform(script.timings, script.amplitudes, withSound = canSound)
        } else {
            HapticPlan.OnOffWaveform(script.onOff, withSound = canSound)
        }
    }
}
