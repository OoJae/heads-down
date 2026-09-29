package xyz.headsdown.surface.haptics

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService

/** [HapticDevice] over the platform [Vibrator]. */
class VibratorHapticDevice(private val vibrator: Vibrator?) : HapticDevice {
    override val hasVibrator: Boolean get() = vibrator?.hasVibrator() == true
    override val hasAmplitudeControl: Boolean get() = vibrator?.hasAmplitudeControl() == true

    // The ids only ever come from HapticScripts, which uses the Composition.PRIMITIVE_* constants.
    @SuppressLint("WrongConstant")
    override fun areAllPrimitivesSupported(vararg primitiveIds: Int): Boolean =
        vibrator?.areAllPrimitivesSupported(*primitiveIds) == true
}

/**
 * Plays the Heads Down haptic language: composed primitives where the motor supports them,
 * waveforms where it does not, and a generated audible thunk for basic motors (the Redmi 14C).
 * Every request goes through a [HapticGovernor] first, so nothing can buzz every round at night.
 *
 * Call from the main thread. [close] releases the SoundPool.
 */
class Haptics(
    context: Context,
    private val vibrator: Vibrator? = defaultVibrator(context),
    private val governor: HapticGovernor = HapticGovernor({ SystemClock.elapsedRealtime() }),
    /** The user's "audible thunk" preference. */
    private val soundEnabled: () -> Boolean = { true },
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val device = VibratorHapticDevice(vibrator)
    private var thunk: ThunkSound? = null

    /** What the arm thunk will be on this phone (for the setup screen and diagnostics). */
    fun planFor(cue: HapticCue): HapticPlan = HapticPlanner.plan(cue, device, soundPolicy())

    /**
     * Loads the thunk ahead of time on phones that will need it (SoundPool decodes
     * asynchronously), so the first arm lands on the moment the rig goes hot.
     */
    fun prepare() {
        if (planFor(HapticCue.ARM_THUNK).withSound) thunkSound()
    }

    /** Plays [cue] if the governor allows it at [moment]. Returns what was rendered. */
    fun play(cue: HapticCue, moment: HapticMoment): HapticPlan {
        if (!governor.tryAcquire(cue, moment)) return HapticPlan.Silent
        val plan = planFor(cue)
        render(plan, moment)
        return plan
    }

    private fun render(plan: HapticPlan, moment: HapticMoment) {
        val effect: VibrationEffect? = when (plan) {
            is HapticPlan.Composed -> VibrationEffect.startComposition().apply {
                plan.steps.forEach { addPrimitive(it.primitiveId, it.scale, it.delayMillis) }
            }.compose()
            is HapticPlan.AmplitudeWaveform ->
                VibrationEffect.createWaveform(plan.timings.toLongArray(), plan.amplitudes.toIntArray(), NO_REPEAT)
            is HapticPlan.OnOffWaveform -> VibrationEffect.createWaveform(plan.timings.toLongArray(), NO_REPEAT)
            HapticPlan.SoundOnly, HapticPlan.Silent -> null
        }
        if (effect != null) vibrate(effect, moment)
        if (plan.withSound) thunkSound().play()
    }

    private fun vibrate(effect: VibrationEffect, moment: HapticMoment) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, attributesFor(moment)) else v.vibrate(effect)
        } catch (_: RuntimeException) {
            // A motor that rejects the effect (or a revoked permission) means no buzz, never a crash.
        }
    }

    private fun soundPolicy() = SoundPolicy(ringerNormal = ringerNormal(), enabled = soundEnabled())

    private fun ringerNormal(): Boolean =
        appContext.getSystemService<AudioManager>()?.ringerMode == AudioManager.RINGER_MODE_NORMAL

    private fun thunkSound(): ThunkSound = thunk ?: ThunkSound(appContext).also { thunk = it }

    override fun close() {
        thunk?.close()
        thunk = null
    }

    companion object {
        private const val NO_REPEAT = -1

        fun defaultVibrator(context: Context): Vibrator? =
            context.getSystemService<VibratorManager>()?.defaultVibrator

        @RequiresApi(33)
        private fun attributesFor(moment: HapticMoment): VibrationAttributes = VibrationAttributes.createForUsage(
            when (moment) {
                // A physical event the user caused (laying the phone down, lifting it).
                HapticMoment.SHIFT -> VibrationAttributes.USAGE_HARDWARE_FEEDBACK
                // The replay is on-screen content, like media.
                HapticMoment.REVEAL -> VibrationAttributes.USAGE_MEDIA
                HapticMoment.FOREGROUND -> VibrationAttributes.USAGE_TOUCH
            },
        )
    }
}
