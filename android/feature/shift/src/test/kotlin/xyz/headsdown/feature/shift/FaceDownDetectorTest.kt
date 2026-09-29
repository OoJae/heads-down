package xyz.headsdown.feature.shift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FaceDownDetectorTest {

    private val g = FaceDownDetector.STANDARD_GRAVITY.toFloat()
    private val faceDown = Triple(0f, 0f, -g)
    private val faceUp = Triple(0f, 0f, g)

    private var t = 0L // ms

    private fun FaceDownDetector.feed(v: Triple<Float, Float, Float>, durationMs: Long, stepMs: Long = 200): Boolean {
        val end = t + durationMs
        var out = isFaceDown
        while (t < end) {
            out = onSample(v.first, v.second, v.third, t * 1_000_000)
            t += stepMs
        }
        return out
    }

    private fun tilted(degrees: Double): Triple<Float, Float, Float> {
        val r = Math.toRadians(degrees)
        return Triple((g * sin(r)).toFloat(), 0f, (-g * cos(r)).toFloat())
    }

    @Test
    fun `face-up on a table is never face-down`() {
        val d = FaceDownDetector()
        assertFalse(d.feed(faceUp, 60_000))
        assertEquals(180.0, d.tiltDegrees, 0.5)
    }

    @Test
    fun `enters face-down only after the dwell time`() {
        val d = FaceDownDetector()
        assertFalse(d.feed(faceDown, 1_400))
        assertTrue(d.feed(faceDown, 400))
        assertEquals(0.0, d.tiltDegrees, 0.5)
    }

    @Test
    fun `dwell is independent of the sample rate`() {
        val d = FaceDownDetector()
        assertFalse(d.feed(faceDown, 1_480, stepMs = 20))
        assertTrue(d.feed(faceDown, 100, stepMs = 20))
    }

    @Test
    fun `a slightly tilted nightstand still counts`() {
        assertTrue(FaceDownDetector().feed(tilted(15.0), 3_000))
    }

    @Test
    fun `hysteresis - between thresholds keeps the previous verdict`() {
        val fromUp = FaceDownDetector()
        assertFalse(fromUp.feed(tilted(30.0), 10_000))

        t = 0
        val fromDown = FaceDownDetector()
        assertTrue(fromDown.feed(faceDown, 3_000))
        assertTrue("30° is inside the band: stay face-down", fromDown.feed(tilted(30.0), 10_000))
    }

    @Test
    fun `a single bump does not flip a face-down phone`() {
        val d = FaceDownDetector()
        assertTrue(d.feed(faceDown, 3_000))
        // One violent sample (knocked table), then rest.
        assertTrue(d.feed(Triple(6f, 0f, -2f), 200))
        assertTrue(d.feed(faceDown, 2_000))
    }

    @Test
    fun `picking the phone up and turning it over exits quickly`() {
        val d = FaceDownDetector()
        assertTrue(d.feed(faceDown, 3_000))
        assertFalse(d.feed(faceUp, 1_000))
    }

    @Test
    fun `sustained motion exits even without rotation`() {
        val d = FaceDownDetector()
        assertTrue(d.feed(faceDown, 3_000))
        // Lifted straight up: ~1.4 g along -z for half a second.
        assertFalse(d.feed(Triple(0f, 0f, -1.45f * g), 600))
    }

    @Test
    fun `free fall or bogus magnitude never enters`() {
        val d = FaceDownDetector()
        assertFalse(d.feed(Triple(0f, 0f, -0.2f * g), 5_000))
        assertFalse(d.feed(Triple(0f, 0f, -2f * g), 5_000))
    }

    @Test
    fun `non-finite and out-of-order samples are ignored`() {
        val d = FaceDownDetector()
        assertTrue(d.feed(faceDown, 3_000))
        val last = d.lastSampleMillis
        assertTrue(d.onSample(Float.NaN, 0f, g, t * 1_000_000))
        assertTrue(d.onSample(0f, Float.POSITIVE_INFINITY, g, t * 1_000_000))
        assertTrue(d.onSample(faceUp.first, faceUp.second, faceUp.third, (last!! - 100) * 1_000_000))
        assertEquals(last, d.lastSampleMillis)
    }

    @Test
    fun `a long delivery gap resets the filter instead of blending stale gravity`() {
        val d = FaceDownDetector()
        assertTrue(d.feed(faceDown, 3_000))
        t += 10_000 // batched delivery / suspended CPU
        d.onSample(faceUp.first, faceUp.second, faceUp.third, t * 1_000_000)
        assertEquals("filter restarted from the fresh sample", 180.0, d.tiltDegrees, 0.5)
    }

    @Test
    fun `freshness window`() {
        val d = FaceDownDetector()
        assertFalse(d.isFresh(nowMillis = 0, maxAgeMillis = 5_000))
        d.feed(faceDown, 1_000)
        val last = d.lastSampleMillis!!
        assertTrue(d.isFresh(last + 5_000, 5_000))
        assertFalse(d.isFresh(last + 5_001, 5_000))
        assertFalse("sample from the future", d.isFresh(last - 1, 5_000))
        d.reset()
        assertFalse(d.isFaceDown)
        assertFalse(d.isFresh(last, 5_000))
    }

    @Test
    fun `config rejects inverted hysteresis`() {
        assertThrows(IllegalArgumentException::class.java) {
            FaceDownDetector.Config(enterTiltDegrees = 40.0, exitTiltDegrees = 40.0)
        }
    }
}
