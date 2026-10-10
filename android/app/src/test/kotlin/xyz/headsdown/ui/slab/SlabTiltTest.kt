package xyz.headsdown.ui.slab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The gravity filter and the pose mapping on synthetic vectors. 50 Hz, like the sensor. */
class SlabTiltTest {
    private val g = GravityFilter.G
    private val filter = GravityFilter()
    private val mapper = TiltMapper()
    private var nanos = 1_000_000_000L
    private val step = 20_000_000L

    /** Feeds one sample the way the hero does and returns whether the filter took it. */
    private fun feed(x: Float, y: Float, z: Float, dtNanos: Long = step): Boolean {
        nanos += dtNanos
        val fresh = !filter.hasValue
        if (!filter.add(x, y, z, nanos)) return false
        mapper.update(
            filter.x, filter.y, filter.z, if (fresh) 0f else dtNanos * 1e-9f,
            steady = filter.speedDegreesPerSecond < TiltMapper.STEADY_DEGREES_PER_SECOND,
        )
        return true
    }

    /** The reading of a phone whose screen normal is [theta] degrees from straight up, top edge leading. */
    private fun held(theta: Float): Triple<Float, Float, Float> {
        val r = Math.toRadians(theta.toDouble())
        return Triple(0f, (g * sin(r)).toFloat(), (g * cos(r)).toFloat())
    }

    private fun hold(theta: Float, seconds: Float) {
        val (x, y, z) = held(theta)
        repeat((seconds * 50).toInt()) { feed(x, y, z) }
    }

    private val lean: Float get() = sqrt(mapper.poseX * mapper.poseX + mapper.poseY * mapper.poseY)

    @Test
    fun `flat on a table leans the least and points straight up`() {
        repeat(50) { feed(0f, 0f, g) }
        assertEquals(0f, mapper.thetaDegrees, 0.01f)
        assertEquals(0f, mapper.poseX, 0f)
        assertEquals(TiltMapper.MIN_LEAN_DEGREES, mapper.poseY, 0f)
    }

    @Test
    fun `held at the neutral angle is the rest pose`() {
        hold(45f, 1f)
        assertEquals(45f, mapper.thetaDegrees, 0.01f)
        assertEquals(0f, mapper.poseX, 1e-3f)
        assertEquals(SlabGeometry.REST_DEGREES, mapper.poseY, 0.05f)
    }

    @Test
    fun `upright presents the underside`() {
        repeat(50) { feed(0f, g, 0f) }
        assertEquals(90f, mapper.thetaDegrees, 0.01f)
        assertEquals(0f, mapper.poseX, 1e-3f)
        // 62 + 1.8 * (90 - 45): well past edge-on, by the time the screen is only upright.
        assertEquals(143f, mapper.poseY, 0.05f)
        assertTrue(mapper.poseY > 90f + 45f)
    }

    @Test
    fun `face-down is the fullest lean and has not spun`() {
        repeat(50) { feed(0f, 0f, -g) }
        assertEquals(180f, mapper.thetaDegrees, 0.01f)
        assertEquals(0f, mapper.poseX, 0f)
        assertEquals(TiltMapper.MAX_LEAN_DEGREES, mapper.poseY, 0f)
    }

    @Test
    fun `on its side leans sideways, toward the edge that is up`() {
        repeat(50) { feed(g, 0f, 0f) }
        assertEquals(143f, mapper.poseX, 0.05f)
        assertEquals(0f, mapper.poseY, 0.05f)
        filter.reset()
        repeat(50) { feed(-g, 0f, 0f) }
        assertEquals(-143f, mapper.poseX, 0.05f)
    }

    @Test
    fun `noise on a table never moves the slab at all`() {
        val random = Random(7)
        repeat(50) { feed(0f, 0f, g) }
        repeat(500) {
            // 0.05 m/s^2 of noise on every axis: far more than a resting sc7a20 shows.
            feed(random.nextGaussian().toFloat() * 0.05f, random.nextGaussian().toFloat() * 0.05f, g + random.nextGaussian().toFloat() * 0.05f)
            assertEquals(0f, mapper.poseX, 0f)
            assertEquals(TiltMapper.MIN_LEAN_DEGREES, mapper.poseY, 0f)
        }
    }

    @Test
    fun `noise in a steady hold stays inside the deadband`() {
        val random = Random(11)
        hold(45f, 2f)
        val (x, y, z) = held(45f)
        val restX = mapper.poseX
        val restY = mapper.poseY
        var worst = 0f
        repeat(500) {
            feed(x + random.nextGaussian().toFloat() * 0.005f, y + random.nextGaussian().toFloat() * 0.005f, z + random.nextGaussian().toFloat() * 0.005f)
            worst = maxOf(worst, abs(mapper.poseX - restX), abs(mapper.poseY - restY))
        }
        // 0.005 m/s^2 rms (0.5 mg) is 0.03 degrees a sample and 0.05 after the gain; the filter
        // leaves about a quarter of it. The real sensor's noise is measured in the lab ("Rest 5 s").
        assertTrue("the pose wandered $worst degrees", worst < SlabMotionSpec.DEADBAND_DEGREES)
    }

    @Test
    fun `a 600 ms flip is followed without overshoot, wobble or spin`() {
        hold(45f, 1f)
        var previous = lean
        var atEnd = 0f
        for (i in 1..30) {
            val (x, y, z) = held(45f + 135f * i / 30f)
            feed(x, y, z)
            assertTrue("the lean went back at sample $i", lean >= previous - 1e-3f)
            assertEquals(0f, mapper.poseX, 0.01f)
            previous = lean
            atEnd = mapper.thetaDegrees
        }
        // The cutoff rises with the turn rate, so the filter is not far behind when the phone lands.
        assertTrue("the filtered angle was only $atEnd when the phone landed", atEnd > 150f)
        hold(180f, 0.3f)
        assertTrue(mapper.thetaDegrees > 178f)
        assertEquals(TiltMapper.MAX_LEAN_DEGREES, lean, 0f)
        assertTrue(mapper.thetaDegrees <= 180f)
    }

    @Test
    fun `samples that are not gravity are ignored`() {
        hold(45f, 0.5f)
        val before = Triple(filter.x, filter.y, filter.z)
        // Shaken or dropped: more than 0.35 g away from 1 g.
        assertFalse(feed(0f, g * 1.4f, 0f))
        assertFalse(feed(0f, g * 0.6f, 0f))
        assertFalse(feed(Float.NaN, 0f, g))
        assertFalse(feed(0f, Float.POSITIVE_INFINITY, g))
        assertEquals(before, Triple(filter.x, filter.y, filter.z))
        // 1.3 g is still taken: a firm hand is not a drop.
        assertTrue(feed(0f, g * 0.92f, g * 0.92f))
        // Time that did not move forward.
        val taken = Triple(filter.x, filter.y, filter.z)
        assertFalse(feed(0f, g, 0f, dtNanos = 0L))
        assertFalse(feed(0f, g, 0f, dtNanos = -5_000_000L))
        assertEquals(taken, Triple(filter.x, filter.y, filter.z))
    }

    @Test
    fun `a gap over 250 ms starts again from the sample`() {
        hold(45f, 0.5f)
        val (x, y, z) = held(120f)
        assertTrue(feed(x, y, z, dtNanos = 300_000_000L))
        assertEquals(120f, mapper.thetaDegrees, 0.01f)
        assertEquals(0f, filter.speedDegreesPerSecond, 0f)
        // Without the gap the same jump is only approached.
        filter.reset()
        hold(45f, 0.5f)
        feed(x, y, z)
        assertTrue(mapper.thetaDegrees > 50f && mapper.thetaDegrees < 110f)
    }

    @Test
    fun `the neutral angle follows a steady hold, slowly, and only between 15 and 75 degrees`() {
        hold(45f, 1f)
        assertEquals(TiltMapper.NEUTRAL_START_DEGREES, mapper.neutralDegrees, 0.01f)
        // A new hold angle first tips the slab...
        hold(60f, 1f)
        assertTrue(lean > 75f)
        // ...and after some seconds it is the new rest.
        hold(60f, 40f)
        assertEquals(60f, mapper.neutralDegrees, 0.05f)
        assertEquals(SlabGeometry.REST_DEGREES, lean, 0.1f)
        // Flat on a table and upright in a stand are not hold angles.
        hold(5f, 30f)
        assertEquals(60f, mapper.neutralDegrees, 0.05f)
        hold(88f, 30f)
        assertEquals(60f, mapper.neutralDegrees, 0.05f)
        assertTrue(mapper.neutralDegrees in TiltMapper.NEUTRAL_MIN_DEGREES..TiltMapper.NEUTRAL_MAX_DEGREES)
    }

    @Test
    fun `a phone left in a stand stops moving the slab`() {
        hold(60f, 60f)
        val settledX = mapper.poseX
        val settledY = mapper.poseY
        hold(60f, 10f)
        // To the last bits of a float: three orders of magnitude inside the deadband.
        assertEquals(settledX, mapper.poseX, 1e-4f)
        assertEquals(settledY, mapper.poseY, 1e-4f)
    }

    @Test
    fun `nearly flat, the direction fades to straight up instead of spinning`() {
        // The horizontal part of the up-vector is 0.05: all of it could be noise.
        repeat(50) { feed(g * 0.05f, 0f, g * 0.9987f) }
        assertEquals(0f, mapper.poseX, 0f)
        // At 0.2 it is half believed, at 0.3 and beyond entirely.
        filter.reset()
        repeat(50) { feed(g * 0.3f, 0f, g * 0.954f) }
        assertEquals(0f, mapper.poseY, 0.01f)
        assertTrue(mapper.poseX > 0f)
    }
}
