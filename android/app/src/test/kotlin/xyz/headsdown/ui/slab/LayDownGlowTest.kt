package xyz.headsdown.ui.slab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lay-down glow's state machine: one slow rise, one fade, never a flash, minimum brightness
 * only while the phone is down, and the user's brightness back the moment it is lifted.
 */
class LayDownGlowTest {
    private var state = GlowState()
    private var now = 50_000L
    private val frames = ArrayList<GlowFrame>()

    /** Runs the machine at the sensor's 50 Hz with the phone at [theta] for [millis]. */
    private fun run(theta: Float, millis: Long, armed: Boolean = true): GlowFrame {
        var frame = LayDownGlowMachine.frame(state, now)
        var t = 0L
        while (t < millis) {
            now += 20
            t += 20
            state = LayDownGlowMachine.step(state, theta, armed, now)
            frame = LayDownGlowMachine.frame(state, now)
            frames += frame
        }
        return frame
    }

    /** Turns the phone from [from] to [to] degrees over [millis]. */
    private fun turn(from: Float, to: Float, millis: Long, armed: Boolean = true) {
        val steps = (millis / 20).toInt()
        for (i in 1..steps) run(from + (to - from) * i / steps, 20, armed)
    }

    @Test
    fun `a rig that is not armed shows nothing, whatever the phone does`() {
        turn(45f, 180f, 600, armed = false)
        run(180f, 5_000, armed = false)
        assertEquals(GlowPhase.Idle, state.phase)
        assertTrue(frames.all { it == GlowFrame.None })
    }

    @Test
    fun `an armed phone in the hand shows nothing`() {
        run(45f, 1_000)
        run(90f, 1_000)
        run(LayDownGlowMachine.START_DEGREES - 1f, 1_000)
        assertEquals(GlowPhase.Idle, state.phase)
        assertTrue(frames.all { it.alpha == 0f && !it.minimumBrightness })
    }

    @Test
    fun `laying it down is one slow rise, a hold of a second, one fade, then dark`() {
        run(45f, 200)
        frames.clear()
        val start = now
        turn(45f, 180f, 600)
        // However fast the turn, the glow is not full before RISE_MILLIS have passed.
        assertEquals(GlowPhase.Rising, state.phase)
        run(180f, 600)
        assertEquals(GlowPhase.Holding, state.phase)
        val heldAt = state.sinceMillis
        assertTrue("full after ${heldAt - start} ms", heldAt - start >= LayDownGlowMachine.RISE_MILLIS)
        assertEquals(GlowFrame(LayDownGlowMachine.PEAK_ALPHA, 1f, 0f, false), LayDownGlowMachine.frame(state, now))

        run(180f, heldAt + LayDownGlowMachine.HOLD_MILLIS - now - 20)
        assertEquals(GlowPhase.Holding, state.phase)
        run(180f, 40)
        assertEquals(GlowPhase.Fading, state.phase)
        run(180f, LayDownGlowMachine.FADE_MILLIS)
        assertEquals(GlowPhase.Dark, state.phase)
        assertEquals(heldAt + LayDownGlowMachine.HOLD_MILLIS + LayDownGlowMachine.FADE_MILLIS, state.sinceMillis)
        assertEquals(GlowFrame(1f, 1f, 1f, true), LayDownGlowMachine.frame(state, now))

        // Never a flash: the overlay only ever gets more opaque, the warmth only rises, the
        // black only rises, and minimum brightness waits for the black to be complete.
        for (i in 1 until frames.size) {
            assertTrue("alpha fell at $i", frames[i].alpha >= frames[i - 1].alpha)
            assertTrue("warmth fell at $i", frames[i].warmth >= frames[i - 1].warmth)
            assertTrue("black fell at $i", frames[i].black >= frames[i - 1].black)
            assertTrue("a step of more than a tenth at $i", frames[i].alpha - frames[i - 1].alpha <= 0.1f)
            if (frames[i].minimumBrightness) assertEquals(1f, frames[i].black, 0f)
        }
        // And it stays dark, unchanged, for as long as the phone lies there: nothing to redraw.
        val dark = state
        run(180f, 60_000)
        assertEquals(dark, state)
    }

    @Test
    fun `lifting a dark phone gives the brightness back at once and lets the black go`() {
        turn(45f, 180f, 600)
        run(180f, 3_000)
        assertEquals(GlowPhase.Dark, state.phase)
        frames.clear()
        // The first sample below the lift angle.
        val lifted = run(LayDownGlowMachine.LIFT_DEGREES - 1f, 20)
        assertEquals(GlowPhase.Lifted, state.phase)
        assertFalse(lifted.minimumBrightness)
        assertEquals(1f, lifted.black, 0f)
        run(140f, LayDownGlowMachine.LIFT_MILLIS)
        assertEquals(0f, frames.last().alpha, 0f)
        // Black only, getting more transparent: no light on the way out.
        for (i in 1 until frames.size) {
            assertEquals(1f, frames[i].black, 0f)
            assertTrue(frames[i].alpha <= frames[i - 1].alpha)
            assertFalse(frames[i].minimumBrightness)
        }
        // Idle again only once the phone has been turned up past the start angle.
        run(140f, 1_000)
        assertEquals(GlowPhase.Lifted, state.phase)
        run(60f, 100)
        assertEquals(GlowPhase.Idle, state.phase)
    }

    @Test
    fun `put straight back down it goes dark again without a second glow`() {
        turn(45f, 180f, 600)
        run(180f, 3_000)
        run(140f, 400)
        assertEquals(GlowPhase.Lifted, state.phase)
        frames.clear()
        turn(140f, 180f, 200)
        run(180f, 2_000)
        assertEquals(GlowPhase.Dark, state.phase)
        assertTrue(frames.last().minimumBrightness)
        // Nothing warm was drawn: every frame on the way was black or nothing.
        assertTrue(frames.all { it.alpha == 0f || it.black == 1f })
    }

    @Test
    fun `picked up during the hold, the glow goes back the way it came`() {
        turn(45f, 180f, 600)
        run(180f, 600)
        assertEquals(GlowPhase.Holding, state.phase)
        frames.clear()
        turn(180f, 45f, 500)
        run(45f, 500)
        assertEquals(GlowPhase.Idle, state.phase)
        assertEquals(GlowFrame.None, frames.last())
        for (i in 1 until frames.size) {
            assertTrue("alpha rose at $i", frames[i].alpha <= frames[i - 1].alpha)
            assertEquals(0f, frames[i].black, 0f)
            assertFalse(frames[i].minimumBrightness)
        }
    }

    @Test
    fun `lifted during the fade it lets go from where it was`() {
        turn(45f, 180f, 600)
        run(180f, 600)
        assertEquals(GlowPhase.Holding, state.phase)
        run(180f, state.sinceMillis + LayDownGlowMachine.HOLD_MILLIS + 300 - now)
        assertEquals(GlowPhase.Fading, state.phase)
        val before = LayDownGlowMachine.frame(state, now)
        frames.clear()
        run(100f, 20)
        assertEquals(GlowPhase.Lifted, state.phase)
        // The same mix of gold and black as the fade had reached, no brighter, then out.
        assertEquals(before.black, frames.last().black, 0.05f)
        assertTrue(frames.last().alpha <= before.alpha + 0.01f)
        run(100f, 400)
        assertEquals(GlowPhase.Idle, state.phase)
        assertEquals(0f, frames.last().alpha, 0f)
        assertTrue(frames.none { it.minimumBrightness })
    }

    @Test
    fun `disarming at any moment clears it`() {
        turn(45f, 180f, 600)
        run(180f, 3_000)
        assertEquals(GlowPhase.Dark, state.phase)
        val cleared = run(180f, 20, armed = false)
        assertEquals(GlowPhase.Idle, state.phase)
        assertEquals(GlowFrame.None, cleared)
    }

    /** The warm part of a frame, and the black part: what the room and the eye actually get. */
    private fun light(frame: GlowFrame) = frame.alpha * (1f - frame.black)
    private fun shade(frame: GlowFrame) = frame.alpha * frame.black

    @Test
    fun `put back down after a lift, the black comes up slowly and never as a step`() {
        turn(45f, 180f, 600)
        run(180f, 3_000)
        run(140f, 400)
        assertEquals(GlowPhase.Lifted, state.phase)
        frames.clear()
        run(180f, 20)
        assertEquals(GlowPhase.Dimming, state.phase)
        run(180f, LayDownGlowMachine.FADE_MILLIS / 2)
        assertEquals(GlowPhase.Dimming, state.phase)
        assertFalse("minimum brightness before the black is complete", frames.last().minimumBrightness)
        assertTrue(frames.last().alpha in 0.4f..0.6f)
        run(180f, LayDownGlowMachine.FADE_MILLIS)
        assertEquals(GlowPhase.Dark, state.phase)
        for (i in 1 until frames.size) {
            assertEquals("light on the way back down at $i", 0f, light(frames[i]), 0f)
            assertTrue("the black stepped at $i", frames[i].alpha - frames[i - 1].alpha <= 0.05f)
            assertTrue(frames[i].alpha >= frames[i - 1].alpha)
        }
        // Lifted halfway through the dimming, it lets go from exactly where it was.
        run(140f, 400)
        run(180f, 20 + LayDownGlowMachine.FADE_MILLIS / 2)
        val before = LayDownGlowMachine.frame(state, now)
        val after = run(140f, 20)
        assertEquals(GlowPhase.Lifted, state.phase)
        assertEquals(before.alpha, after.alpha, 0.05f)
        assertEquals(1f, after.black, 0f)
    }

    @Test
    fun `a rig armed with the phone already turned over gets no light, only the dark`() {
        // Over a face in bed, at 140 degrees, when the rig is armed: nothing, however long.
        run(140f, 10_000)
        assertEquals(GlowPhase.Lifted, state.phase)
        assertTrue(frames.all { it.alpha == 0f && !it.minimumBrightness })
        // Rolled further, to face-down: black, slowly, with no light on the way.
        frames.clear()
        turn(140f, 180f, 300)
        run(180f, 2_000)
        assertEquals(GlowPhase.Dark, state.phase)
        assertTrue(frames.all { light(it) == 0f })
        assertTrue(frames.last().minimumBrightness)
        // Only a phone that has been seen turned up can glow: and then it does.
        run(60f, 500)
        assertEquals(GlowState(updatedMillis = state.updatedMillis, primed = true), state)
        frames.clear()
        turn(60f, 180f, 600)
        run(180f, 600)
        assertEquals(GlowPhase.Holding, state.phase)
        assertTrue(frames.any { light(it) > 0.8f })
    }

    @Test
    fun `an app that comes back to a phone lying face-down goes dark without a second glow`() {
        // The controller starts from nothing on every resume; the phone has not moved.
        run(180f, 3_000)
        assertEquals(GlowPhase.Dark, state.phase)
        assertTrue(frames.all { light(it) == 0f })
        for (i in 1 until frames.size) assertTrue(frames[i].alpha - frames[i - 1].alpha <= 0.05f)
    }

    @Test
    fun `disarmed, the phone is not remembered as having been turned up`() {
        run(45f, 500)
        assertTrue(state.primed)
        run(45f, 20, armed = false)
        assertEquals(GlowState(), state)
        // Armed again while already over the face: no light.
        run(150f, 2_000)
        assertTrue(frames.all { it.alpha == 0f })
    }

    @Test
    fun `whatever the hand does, the overlay never steps and no light returns after the fade`() {
        // Random handling at the sensor's rate: slow turns, fast flips, jitter, holds at every angle.
        val random = java.util.Random(20261011)
        repeat(300) { run ->
            state = GlowState()
            frames.clear()
            var theta = random.nextFloat() * 180f
            var previous = LayDownGlowMachine.frame(state, now)
            var fading = false
            var lightAtFade = 0f
            var target = theta
            var speed = 0f
            repeat(1_500) { i ->
                if (i % (10 + random.nextInt(120)) == 0) {
                    target = when (random.nextInt(6)) {
                        0 -> 180f
                        1 -> 150f + random.nextFloat() * 30f
                        2 -> 110f + random.nextFloat() * 20f
                        else -> random.nextFloat() * 180f
                    }
                    speed = 20f + random.nextFloat() * 900f // degrees per second
                }
                val step = speed * 0.02f
                theta = if (target > theta) minOf(target, theta + step) else maxOf(target, theta - step)
                val jittered = (theta + (random.nextFloat() - 0.5f) * 1.5f).coerceIn(0f, 180f)
                now += 20
                state = LayDownGlowMachine.step(state, jittered, true, now)
                val frame = LayDownGlowMachine.frame(state, now)
                val where = "run $run step $i (${state.phase} at $jittered)"
                assertTrue(where, frame.alpha in 0f..1f && frame.warmth in 0f..1f && frame.black in 0f..1f)
                // Nothing steps: a fiftieth of a second never changes the light or the black by more than this.
                assertTrue("$where: the light stepped", kotlin.math.abs(light(frame) - light(previous)) <= 0.11f)
                assertTrue("$where: the black stepped", kotlin.math.abs(shade(frame) - shade(previous)) <= 0.11f)
                if (frame.minimumBrightness) {
                    assertTrue("$where: minimum brightness off the table", jittered >= LayDownGlowMachine.LIFT_DEGREES)
                    assertEquals(where, 1f, shade(frame), 0f)
                }
                // Once the fade has begun, the light only ever falls until the phone has been turned up.
                when (state.phase) {
                    GlowPhase.Fading, GlowPhase.Dimming, GlowPhase.Dark, GlowPhase.Lifted -> {
                        if (!fading) {
                            fading = true
                            lightAtFade = light(previous)
                        }
                        assertTrue("$where: light came back", light(frame) <= lightAtFade + 1e-4f)
                        lightAtFade = light(frame)
                    }
                    GlowPhase.Idle -> fading = false
                    GlowPhase.Rising, GlowPhase.Holding -> assertFalse("$where: a second glow without turning up", fading)
                }
                previous = frame
            }
        }
    }

    @Test
    fun `a hand trembling at the threshold does not make the light flicker`() {
        // The whole pipeline the composable runs: the gravity filter, then the machine. A phone
        // held at 142 degrees with a 1 degree tremor at 9 Hz, for five seconds.
        val filter = GravityFilter()
        run(45f, 200)
        var nanos = now * 1_000_000L
        var lowest = 1f
        var highest = 0f
        repeat(400) { i ->
            nanos += 20_000_000L
            val seconds = i * 0.02
            val degrees = 142.0 + 1.0 * Math.sin(2.0 * Math.PI * 9.0 * seconds)
            val r = Math.toRadians(degrees)
            filter.add(0f, (9.81 * Math.sin(r)).toFloat(), (9.81 * Math.cos(r)).toFloat(), nanos)
            val theta = Math.toDegrees(Math.acos(filter.z.toDouble().coerceIn(-1.0, 1.0))).toFloat()
            state = LayDownGlowMachine.step(state, theta, true, nanos / 1_000_000L)
            val frame = LayDownGlowMachine.frame(state, nanos / 1_000_000L)
            // Once the rise has settled, in the last three seconds.
            if (i >= 250) {
                lowest = minOf(lowest, light(frame))
                highest = maxOf(highest, light(frame))
            }
        }
        assertEquals(GlowPhase.Rising, state.phase)
        assertTrue("the light is there at 142 degrees", lowest > 0.1f)
        assertTrue("the light swung by ${highest - lowest}", highest - lowest < 0.03f)
    }

    @Test
    fun `minimum brightness is asked only while the phone is down`() {
        // A whole evening of handling: down, up, half down, down again.
        val script = listOf(45f to 180f, 180f to 180f, 180f to 30f, 30f to 130f, 130f to 100f, 100f to 180f, 180f to 180f, 180f to 149f, 149f to 180f)
        for ((from, to) in script) {
            val steps = 60
            for (i in 1..steps) {
                val theta = from + (to - from) * i / steps
                val frame = run(theta, 20)
                if (frame.minimumBrightness) {
                    assertTrue("minimum brightness at $theta degrees", theta >= LayDownGlowMachine.LIFT_DEGREES)
                }
                assertTrue(frame.alpha in 0f..1f && frame.warmth in 0f..1f && frame.black in 0f..1f)
            }
            run(to, 2_000)
        }
    }
}
