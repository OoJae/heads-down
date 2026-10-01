package xyz.headsdown.feature.shift.devlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import xyz.headsdown.ml.pickup.MotionTrigger
import kotlin.math.cos
import kotlin.math.sin

class MotionWindowsTest {
    private val g = 9.81f
    private val stepNanos = 20_000_000L // 50 Hz

    /** Face-down at rest, with a little sensor noise. */
    private fun rest(i: Int) = AccelSample(i * stepNanos, i * stepNanos, 0.02f * ((i % 3) - 1), 0.01f, -g)

    /** The lab feeds the app's trigger exactly as [SensorLabService] does. */
    private fun MotionTrigger.onSample(s: AccelSample) = onSample(s.tNanos, s.x, s.y, s.z)

    @Test
    fun `the lab has no trigger of its own`() {
        // One source of truth: the trigger the shift service runs (:ml), nothing in devlog.
        try {
            Class.forName("xyz.headsdown.feature.shift.devlog.MotionTrigger")
            fail("the sensor lab still has its own MotionTrigger")
        } catch (_: ClassNotFoundException) {
            // expected
        }
        assertEquals("xyz.headsdown.ml.pickup.MotionTrigger", MotionTrigger::class.java.name)
    }

    @Test
    fun `stillness never triggers`() {
        val trigger = MotionTrigger.live()
        assertEquals(0, (0 until 50 * 60).count { trigger.onSample(rest(it)) })
    }

    @Test
    fun `a nightstand bump triggers once`() {
        val trigger = MotionTrigger.live()
        val fired = (0 until 500).count { i ->
            val s = if (i in 200..203) rest(i).copy(z = -g - 4f, x = 2f) else rest(i)
            trigger.onSample(s)
        }
        assertEquals(1, fired)
    }

    @Test
    fun `a slow lift that keeps about 1 g still triggers on tilt`() {
        val trigger = MotionTrigger.live()
        var firedAt = -1
        for (i in 0 until 400) {
            // From face-down, rotate about the x axis by up to 70 degrees over 2 s, magnitude unchanged.
            val angle = if (i < 100) 0.0 else Math.toRadians(minOf(70.0, (i - 100) * 0.7))
            val s = AccelSample(i * stepNanos, i * stepNanos, 0f, (g * sin(angle)).toFloat(), (-g * cos(angle)).toFloat())
            if (trigger.onSample(s) && firedAt < 0) firedAt = i
        }
        assertTrue("fired at $firedAt", firedAt in 100..140)
    }

    @Test
    fun `refractory period merges one jostle into one trigger`() {
        val trigger = MotionTrigger.live(MotionTrigger.Config(refractoryMillis = 3_000))
        val fired = (0 until 300).count { i ->
            // Two jolts 0.6 s apart, then another 4 s later.
            val jolt = i == 50 || i == 80 || i == 280
            trigger.onSample(if (jolt) rest(i).copy(z = -g - 5f) else rest(i))
        }
        assertEquals(2, fired)
    }

    @Test
    fun `a session started in the hand records the lay-down and then goes quiet`() {
        // Tap Start holding the phone face-up, turn it over onto the table, leave it: the plain
        // trigger would fire every 3 s from here on, and every "window" would be a still phone.
        val trigger = MotionTrigger.live()
        val windows = mutableListOf<MotionWindow>()
        val recorder = WindowRecorder(onWindow = { windows += it })
        var fires = 0
        for (i in 0 until 50 * 60) {
            val angle = Math.toRadians(if (i < 100) 150.0 else maxOf(0.0, 150.0 - (i - 100) * 3.0))
            val s = AccelSample(i * stepNanos, i * stepNanos, 0f, (g * sin(angle)).toFloat(), (-g * cos(angle)).toFloat())
            val fired = trigger.onSample(s)
            if (fired) fires++
            recorder.onSample(s, fired)
        }
        assertTrue("the lay-down, and at most one re-fire while it settles: $fires", fires in 1..2)
        assertEquals("one window: the lay-down", 1, windows.size)
        assertEquals(1, trigger.settles)
    }

    @Test
    fun `windows span two seconds before to three seconds after`() {
        val windows = mutableListOf<MotionWindow>()
        val recorder = WindowRecorder(onWindow = { windows += it })
        for (i in 0 until 1_000) recorder.onSample(rest(i), triggered = i == 400)
        assertEquals(1, windows.size)
        val w = windows.single()
        assertEquals(1, w.id)
        assertEquals(400 * stepNanos, w.triggerNanos)
        val span = (w.samples.last().tNanos - w.samples.first().tNanos) / 1_000_000
        assertTrue("span $span ms", span in 4_900..5_100)
        val before = (w.triggerNanos - w.samples.first().tNanos) / 1_000_000
        assertEquals(2_000L, before)
        assertEquals(1, recorder.windowsEmitted)
    }

    @Test
    fun `a trigger inside an open window extends it, capped at ten seconds`() {
        val windows = mutableListOf<MotionWindow>()
        val recorder = WindowRecorder(onWindow = { windows += it })
        // Triggers every 2 s from 4 s to 20 s: one window, capped.
        for (i in 0 until 1_500) recorder.onSample(rest(i), triggered = i >= 200 && i <= 1_000 && i % 100 == 0)
        assertTrue(windows.isNotEmpty())
        val first = windows.first()
        val span = (first.samples.last().tNanos - first.samples.first().tNanos) / 1_000_000
        assertTrue("span $span ms", span <= 10_000)
    }

    @Test
    fun `flush emits a window still open when the session ends`() {
        val windows = mutableListOf<MotionWindow>()
        val recorder = WindowRecorder(onWindow = { windows += it })
        for (i in 0 until 120) recorder.onSample(rest(i), triggered = i == 110)
        assertEquals(0, windows.size)
        recorder.flush()
        assertEquals(1, windows.size)
        recorder.flush()
        assertEquals(1, windows.size)
    }
}
