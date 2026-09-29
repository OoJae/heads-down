package xyz.headsdown.surface.haptics

import android.os.VibrationEffect.Composition

/**
 * The whole Heads Down haptic vocabulary. There is deliberately **no per-round cue**: a rig
 * that is hot at 3 am signs a heartbeat every ~78 s in silence. Night cues exist only for
 * moments the user caused (laying the phone down, picking it up).
 */
enum class HapticCue {
    /** Once per shift, when the rig first goes hot (the phone was just laid face-down). */
    ARM_THUNK,

    /** The rig started cooling because the user lifted the phone or woke the screen. */
    COOLING_TICK,

    /** The morning reveal's board replay: accelerating ticks that land on a thud. */
    REVEAL_DRUMROLL,

    /** The rig shared a Motherlode. Morning only, and only when it really happened. */
    MOTHERLODE_FLOURISH,
}

/** One `VibrationEffect.Composition` primitive: `addPrimitive(id, scale, delayMillis)`. */
data class PrimitiveStep(val primitiveId: Int, val scale: Float, val delayMillis: Int = 0) {
    init {
        require(scale in 0f..1f) { "primitive scale is 0..1" }
        require(delayMillis >= 0)
    }
}

/**
 * How one cue is rendered on each class of motor.
 *
 * @property primitives the composed version (LRA motors that report every primitive).
 * @property timings / [amplitudes] a `createWaveform` fallback for motors with amplitude
 *   control but no (or partial) primitive support. Timings alternate off/on, starting with an
 *   off segment, exactly like `VibrationEffect.createWaveform(long[], int[], -1)`.
 * @property onOff the same shape for motors without amplitude control (plain on/off pattern).
 * @property audibleFallback play the generated thunk when the motor cannot render this cue
 *   with composed primitives (the Redmi 14C's basic motor).
 */
data class HapticScript(
    val primitives: List<PrimitiveStep>,
    val timings: List<Long>,
    val amplitudes: List<Int>,
    val onOff: List<Long>,
    val audibleFallback: Boolean,
) {
    init {
        require(primitives.isNotEmpty())
        require(timings.size == amplitudes.size && timings.isNotEmpty())
        require(amplitudes.all { it in 0..255 })
        require(timings.all { it >= 0 } && onOff.all { it >= 0 } && onOff.size >= 2)
    }

    val primitiveIds: IntArray get() = primitives.map { it.primitiveId }.distinct().toIntArray()

    /** Total length of the waveform fallback, ms. */
    val waveformMillis: Long get() = timings.sum()
}

object HapticScripts {

    fun of(cue: HapticCue): HapticScript = when (cue) {
        HapticCue.ARM_THUNK -> ARM_SCRIPT
        HapticCue.COOLING_TICK -> COOL_SCRIPT
        HapticCue.REVEAL_DRUMROLL -> DRUM_SCRIPT
        HapticCue.MOTHERLODE_FLOURISH -> FLOURISH_SCRIPT
    }

    /** A single weighty thud with a soft after-knock: "the rig is down". */
    private val ARM_SCRIPT = HapticScript(
        primitives = listOf(
            PrimitiveStep(Composition.PRIMITIVE_THUD, 1.0f),
            PrimitiveStep(Composition.PRIMITIVE_LOW_TICK, 0.35f, delayMillis = 70),
        ),
        timings = listOf(0, 45, 60, 18),
        amplitudes = listOf(0, 255, 0, 90),
        onOff = listOf(0, 55),
        audibleFallback = true,
    )

    /** One quiet low tick. No countdown, no repeat. */
    private val COOL_SCRIPT = HapticScript(
        primitives = listOf(PrimitiveStep(Composition.PRIMITIVE_LOW_TICK, 0.5f)),
        timings = listOf(0, 14),
        amplitudes = listOf(0, 110),
        onOff = listOf(0, 12),
        audibleFallback = false,
    )

    /** Ticks that speed up and swell for about two seconds, landing on a thud. */
    private val DRUM_SCRIPT: HapticScript = run {
        val gaps = listOf(0, 190, 170, 150, 132, 116, 102, 90, 80, 71, 63, 56, 50, 45, 41, 38)
        val ticks = gaps.mapIndexed { i, gap ->
            PrimitiveStep(Composition.PRIMITIVE_TICK, 0.25f + 0.5f * i / (gaps.size - 1), gap)
        }
        val primitives = ticks + PrimitiveStep(Composition.PRIMITIVE_THUD, 1.0f, delayMillis = 90)
        // Waveform: 12 ms pulses on the same accelerating grid, then a 60 ms landing.
        val timings = mutableListOf<Long>()
        val amplitudes = mutableListOf<Int>()
        gaps.forEachIndexed { i, gap ->
            timings += gap.toLong(); amplitudes += 0
            timings += 12L; amplitudes += 70 + (120 * i / (gaps.size - 1))
        }
        timings += 90L; amplitudes += 0
        timings += 60L; amplitudes += 255
        val onOff = mutableListOf<Long>()
        gaps.forEach { gap -> onOff += gap.toLong(); onOff += 10L }
        onOff += 90L; onOff += 50L
        HapticScript(primitives, timings, amplitudes, onOff, audibleFallback = false)
    }

    /** A rise, a snap and a spin: saved for a Motherlode share, never scripted. */
    private val FLOURISH_SCRIPT = HapticScript(
        primitives = listOf(
            PrimitiveStep(Composition.PRIMITIVE_SLOW_RISE, 0.6f),
            PrimitiveStep(Composition.PRIMITIVE_QUICK_FALL, 0.8f),
            PrimitiveStep(Composition.PRIMITIVE_CLICK, 1.0f, delayMillis = 80),
            PrimitiveStep(Composition.PRIMITIVE_CLICK, 0.7f, delayMillis = 70),
            PrimitiveStep(Composition.PRIMITIVE_SPIN, 0.6f, delayMillis = 60),
        ),
        timings = listOf(0, 80, 0, 80, 0, 80, 60, 20, 70, 20, 60, 120),
        amplitudes = listOf(0, 60, 0, 120, 0, 200, 0, 255, 0, 180, 0, 140),
        onOff = listOf(0, 120, 80, 25, 70, 25, 60, 90),
        audibleFallback = false,
    )
}
