package xyz.headsdown.ui.slab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** The pose the hero draws, from its parts: gravity, the finger, the scroll and the launch. */
class SlabPoseTest {
    private val out = FloatArray(2)

    private fun pose(
        gravityX: Float = 0f,
        gravityY: Float = SlabGeometry.REST_DEGREES,
        weight: Float = 1f,
        userX: Float = 0f,
        userY: Float = 0f,
        scrollPitch: Float = 0f,
        launch: Float = 0f,
    ): Pair<Float, Float> {
        SlabPose.compose(gravityX, gravityY, weight, userX, userY, scrollPitch, launch, out)
        return out[0] to out[1]
    }

    @Test
    fun `with nothing acting on it the pose is rest`() {
        assertEquals(0f to SlabGeometry.REST_DEGREES, pose())
    }

    @Test
    fun `gravity counts by its weight, and with none the pose is rest whatever the phone does`() {
        assertEquals(40f to 100f, pose(gravityX = 40f, gravityY = 100f))
        val (x, y) = pose(gravityX = 40f, gravityY = 100f, weight = 0.5f)
        assertEquals(20f, x, 1e-4f)
        assertEquals(81f, y, 1e-4f)
        assertEquals(0f to SlabGeometry.REST_DEGREES, pose(gravityX = 40f, gravityY = 100f, weight = 0f))
    }

    @Test
    fun `the finger and the scroll add to it`() {
        val (x, y) = pose(userX = 12f, userY = -5f, scrollPitch = 30f)
        assertEquals(12f, x, 1e-4f)
        assertEquals(SlabGeometry.REST_DEGREES - 5f + 30f, y, 1e-4f)
    }

    @Test
    fun `the launch starts exactly edge-on, however the phone is held`() {
        // Held flatter than usual, at the usual angle, steeper, upright, and rolled to one side.
        for ((gx, gy) in listOf(0f to 35f, 0f to 62f, 0f to 89f, 0f to 143f, 60f to 100f, -90f to 20f)) {
            val start = pose(gravityX = gx, gravityY = gy, launch = 1f)
            assertEquals("held at ($gx, $gy)", 0f, start.first, 1e-4f)
            assertEquals("held at ($gx, $gy)", SlabMotionSpec.LAUNCH_FROM_DEGREES, start.second, 1e-4f)
            // And ends at the pose the phone asks for, passing straight between the two.
            val end = pose(gravityX = gx, gravityY = gy, launch = 0f)
            val scale = SlabFrame.clampScale(gx, gy)
            assertEquals(gx * scale, end.first, 1e-3f)
            assertEquals(gy * scale, end.second, 1e-3f)
            var previous = 0f
            for (i in 10 downTo 0) {
                val (x, y) = pose(gravityX = gx, gravityY = gy, launch = i / 10f)
                val gone = sqrt((x - start.first) * (x - start.first) + (y - start.second) * (y - start.second))
                assertTrue("the launch went back at $i for ($gx, $gy)", gone >= previous - 1e-3f)
                previous = gone
            }
        }
    }

    @Test
    fun `the bounce at the end of the launch goes a little past the pose, not back toward edge-on`() {
        // The spring takes the turn a little below zero: the slab opens a touch too far and settles.
        val (_, y) = pose(launch = -0.05f)
        assertEquals(SlabGeometry.REST_DEGREES - 0.05f * (SlabMotionSpec.LAUNCH_FROM_DEGREES - SlabGeometry.REST_DEGREES), y, 1e-4f)
    }

    @Test
    fun `whatever is added, the pose never leans past the limit`() {
        for (launch in listOf(0f, 0.3f, 1f)) {
            val (x, y) = pose(gravityX = 100f, gravityY = 150f, userX = 80f, userY = 90f, scrollPitch = 70f, launch = launch)
            assertTrue(sqrt(x * x + y * y) <= SlabGeometry.MAX_TILT_DEGREES + 1e-2f)
        }
        val (x, y) = pose(gravityY = 158f, scrollPitch = 70f)
        assertEquals(0f, x, 0f)
        assertEquals(SlabGeometry.MAX_TILT_DEGREES, y, 1e-3f)
    }
}
