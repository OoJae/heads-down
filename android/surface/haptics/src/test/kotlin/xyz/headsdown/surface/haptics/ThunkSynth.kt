package xyz.headsdown.surface.haptics

import java.io.ByteArrayOutputStream

/**
 * Generates `res/raw/hd_thunk.wav`, the audible arm thunk. Everything is synthesised here, so
 * the sound has no third-party source and is dedicated to the public domain (CC0 1.0); see
 * `surface/haptics/SOUNDS.md`. [ThunkSoundAssetTest] fails if the committed file differs.
 *
 * The body is a sine gliding from ~170 Hz to ~95 Hz (low enough to feel like a "thunk", high
 * enough that a phone speaker, which rolls off below ~150 Hz, still reproduces it), plus a
 * fast-decaying wooden overtone and a 3 ms noise click for the attack. `StrictMath` keeps the
 * output bit-identical on every JVM.
 */
object ThunkSynth {
    const val SAMPLE_RATE = 22_050
    const val DURATION_MILLIS = 160
    private const val PEAK = 0.85

    fun samples(): ShortArray {
        val n = SAMPLE_RATE * DURATION_MILLIS / 1000
        val out = ShortArray(n)
        var phaseBody = 0.0
        var phaseOver = 0.0
        var seed = 0x2545F491L
        var noiseLp = 0.0
        val fadeOutSamples = SAMPLE_RATE * 12 / 1000
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val f = 95.0 + 75.0 * StrictMath.exp(-t / 0.040)
            phaseBody += 2 * StrictMath.PI * f / SAMPLE_RATE
            phaseOver += 2 * StrictMath.PI * (2.76 * f) / SAMPLE_RATE
            val body = StrictMath.sin(phaseBody) * StrictMath.exp(-t / 0.050)
            val over = 0.35 * StrictMath.sin(phaseOver) * StrictMath.exp(-t / 0.018)
            // 64-bit LCG (Knuth MMIX) -> [-1, 1), then a one-pole low-pass to soften the click.
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val white = ((seed ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
            noiseLp += 0.35 * (white - noiseLp)
            val click = 0.5 * noiseLp * StrictMath.exp(-t / 0.0025)
            val attack = minOf(1.0, t / 0.002)
            val fade = minOf(1.0, (n - 1 - i).toDouble() / fadeOutSamples)
            val v = (attack * fade * (0.75 * body + over + click)).coerceIn(-1.0, 1.0)
            out[i] = StrictMath.round(v * PEAK * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** 16-bit little-endian mono PCM in a canonical 44-byte RIFF/WAVE header. */
    fun wav(samples: ShortArray = samples()): ByteArray {
        val data = samples.size * 2
        val out = ByteArrayOutputStream(44 + data)
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun u32(v: Int) = repeat(4) { out.write((v ushr (8 * it)) and 0xFF) }
        fun u16(v: Int) = repeat(2) { out.write((v ushr (8 * it)) and 0xFF) }
        ascii("RIFF"); u32(36 + data); ascii("WAVE")
        ascii("fmt "); u32(16); u16(1); u16(1); u32(SAMPLE_RATE); u32(SAMPLE_RATE * 2); u16(2); u16(16)
        ascii("data"); u32(data)
        samples.forEach { u16(it.toInt()) }
        return out.toByteArray()
    }
}
