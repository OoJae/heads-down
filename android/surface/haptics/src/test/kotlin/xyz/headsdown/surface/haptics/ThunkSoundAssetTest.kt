package xyz.headsdown.surface.haptics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Provenance check for the audible thunk: the committed WAV must be exactly what [ThunkSynth]
 * generates. Regenerate after changing the synth with
 * `HD_REGENERATE_SOUNDS=1 ./gradlew :surface:haptics:testDebugUnitTest`.
 */
class ThunkSoundAssetTest {
    private val asset = File("src/main/res/raw/hd_thunk.wav")

    @Test
    fun `committed thunk is the generated one`() {
        val generated = ThunkSynth.wav()
        if (System.getenv("HD_REGENERATE_SOUNDS") == "1") asset.writeBytes(generated)
        assertTrue("missing ${asset.absolutePath}", asset.exists())
        assertArrayEquals("hd_thunk.wav differs from ThunkSynth; regenerate it", generated, asset.readBytes())
    }

    @Test
    fun `thunk is short, small and never clips`() {
        val samples = ThunkSynth.samples()
        assertEquals(ThunkSynth.SAMPLE_RATE * ThunkSynth.DURATION_MILLIS / 1000, samples.size)
        assertTrue("under 10 KB", ThunkSynth.wav(samples).size < 10 * 1024)
        val peak = samples.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak leaves headroom", peak in 8_000..(0.86 * Short.MAX_VALUE).toInt())
        // Starts and ends at silence: no click when SoundPool starts or stops it.
        assertEquals(0, samples.first().toInt())
        assertTrue(abs(samples.last().toInt()) < 64)
    }

    @Test
    fun `wav header is canonical 16-bit mono PCM`() {
        val wav = ThunkSynth.wav(ShortArray(4) { (it * 1000).toShort() })
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals(44 + 8, wav.size)
        fun u16(at: Int) = (wav[at].toInt() and 0xFF) or ((wav[at + 1].toInt() and 0xFF) shl 8)
        assertEquals(1, u16(20)) // PCM
        assertEquals(1, u16(22)) // mono
        assertEquals(16, u16(34)) // bits per sample
        assertEquals(1000, u16(44 + 2))
    }
}
