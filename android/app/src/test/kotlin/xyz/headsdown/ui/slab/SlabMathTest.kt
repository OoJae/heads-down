package xyz.headsdown.ui.slab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The pose, the projection and the silhouette, on poses whose answers are known by hand. */
class SlabMathTest {
    private val frame = SlabFrame()
    private val d = SlabGeometry.EYE_DISTANCE
    private val hx = SlabGeometry.HALF_X
    private val hy = SlabGeometry.HALF_Y
    private val hz = SlabGeometry.HALF_Z

    /** A 400 px slab centred on (360, 500). */
    private fun pose(tiltX: Float, tiltY: Float) = frame.apply { set(tiltX, tiltY, 360f, 500f, 400f) }

    private fun corner(index: Int) = frame.corners[2 * index] to frame.corners[2 * index + 1]

    @Test
    fun `face-on shows only the stone top, as a rectangle a little larger than the slab`() {
        pose(0f, 0f)
        assertEquals(SlabGeometry.FACE_PZ, frame.faces)
        assertEquals(4, frame.edgeCount)
        // The top face is hz nearer the eye than the centre, so it is drawn D / (D - hz) larger.
        val scale = 400f * d / (d - hz)
        assertEquals(360f - hx * scale, frame.left, 0.01f)
        assertEquals(360f + hx * scale, frame.right, 0.01f)
        assertEquals(500f - hy * scale, frame.top, 0.01f)
        assertEquals(500f + hy * scale, frame.bottom, 0.01f)
        assertEquals(0f, frame.underside, 0f)
        // +y in slab space is up on screen: the corner with +x, +y, +z is the top right one.
        val (x, y) = corner(7)
        assertEquals(frame.right, x, 0.01f)
        assertEquals(frame.top, y, 0.01f)
    }

    @Test
    fun `edge-on shows only the lower rim's face`() {
        pose(0f, 90f)
        assertEquals(SlabGeometry.FACE_NY, frame.faces)
        assertEquals(4, frame.edgeCount)
        // The -y face is hy nearer the eye; it is 2 hx wide and 2 hz tall.
        val scale = 400f * d / (d - hy)
        assertEquals(2f * hx * scale, frame.right - frame.left, 0.02f)
        assertEquals(2f * hz * scale, frame.bottom - frame.top, 0.02f)
        // The stone top (+z) is up on screen, the underside down.
        assertTrue(corner(4).second < corner(0).second)
    }

    @Test
    fun `turned right over shows only the underside, and the lower rim is at the top of the screen`() {
        pose(0f, 180f)
        assertEquals(SlabGeometry.FACE_NZ, frame.faces)
        assertEquals(4, frame.edgeCount)
        assertEquals(1f, frame.underside, 1e-4f)
        // Seen from below, the near edge (-y) is up, as the near edge of a ceiling is.
        assertTrue(corner(0).second < corner(2).second)
        // The turn is about the screen's x axis: left stays left.
        assertTrue(corner(0).first < corner(1).first)
    }

    @Test
    fun `at 45 degrees the top and the lower rim show, and the silhouette has six edges`() {
        pose(0f, 45f)
        assertEquals(SlabGeometry.FACE_PZ or SlabGeometry.FACE_NY, frame.faces)
        assertEquals(6, frame.edgeCount)
    }

    @Test
    fun `leaning toward a corner shows three faces and still six edges`() {
        pose(40f, 40f)
        // The normal leans right and up, so the eye is on the slab's -x, -y, +z side.
        assertEquals(SlabGeometry.FACE_PZ or SlabGeometry.FACE_NX or SlabGeometry.FACE_NY, frame.faces)
        assertEquals(6, frame.edgeCount)
    }

    @Test
    fun `the rotation takes the top normal to where the tilt vector says`() {
        for ((tx, ty) in listOf(0f to 62f, 30f to 0f, -50f to 100f, 12f to -140f)) {
            pose(tx, ty)
            val degrees = sqrt(tx * tx + ty * ty)
            val radians = Math.toRadians(degrees.toDouble())
            val r = frame.rotation
            // The third column is where slab +z goes in view space.
            assertEquals((sin(radians) * tx / degrees).toFloat(), r[2], 1e-5f)
            assertEquals((sin(radians) * ty / degrees).toFloat(), r[5], 1e-5f)
            assertEquals(cos(radians).toFloat(), r[8], 1e-5f)
            // Orthonormal: rows have unit length and the eye stays D away.
            for (row in 0 until 3) {
                assertEquals(1f, r[3 * row] * r[3 * row] + r[3 * row + 1] * r[3 * row + 1] + r[3 * row + 2] * r[3 * row + 2], 1e-5f)
            }
            val e = frame.eye
            assertEquals(d, sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]), 1e-4f)
            val l = frame.light
            assertEquals(1f, sqrt(l[0] * l[0] + l[1] * l[1] + l[2] * l[2]), 2e-3f)
        }
    }

    @Test
    fun `at rest the eye is in front of and above the slab, and the top is lit`() {
        pose(0f, SlabGeometry.REST_DEGREES)
        val radians = Math.toRadians(SlabGeometry.REST_DEGREES.toDouble())
        assertEquals(0f, frame.eye[0], 1e-5f)
        assertEquals((-d * sin(radians)).toFloat(), frame.eye[1], 1e-4f)
        assertEquals((d * cos(radians)).toFloat(), frame.eye[2], 1e-4f)
        assertEquals(SlabGeometry.FACE_PZ or SlabGeometry.FACE_NY, frame.faces)
        assertTrue("the top faces the key light", frame.faceLight(SlabGeometry.FACE_PZ) > 0.8f)
        assertTrue("the lower rim's face is in shade", frame.faceLight(SlabGeometry.FACE_NY) < 0.3f)
    }

    @Test
    fun `the centre projects to the centre and every corner lies in or on the silhouette`() {
        val out = FloatArray(2)
        for ((tx, ty) in listOf(0f to 0f, 0f to 62f, 0f to 90f, 35f to 120f, -80f to 20f, 0f to 165f, 110f to -70f)) {
            pose(tx, ty)
            frame.project(0f, 0f, 0f, out, 0)
            assertEquals(360f, out[0], 1e-3f)
            assertEquals(500f, out[1], 1e-3f)
            assertTrue("the centre is inside at ($tx, $ty)", frame.distance(360f, 500f) < -10f)
            var onEdge = 0
            for (i in 0 until 8) {
                val (x, y) = corner(i)
                val distance = frame.distance(x, y)
                assertTrue("corner $i is outside by $distance at ($tx, $ty)", distance < 0.05f)
                if (abs(distance) < 0.05f) onEdge++
                assertTrue(x in frame.left..frame.right && y in frame.top..frame.bottom)
            }
            // A convex silhouette of n edges has n corners on it.
            assertEquals("corners on the silhouette at ($tx, $ty)", frame.edgeCount, onEdge)
        }
    }

    @Test
    fun `the half-planes measure pixels and unused ones never win`() {
        pose(0f, 0f)
        assertEquals(10f, frame.distance(frame.right + 10f, 500f), 0.01f)
        assertEquals(-10f, frame.distance(frame.right - 10f, 500f), 0.01f)
        assertEquals(25f, frame.distance(360f, frame.top - 25f), 0.01f)
        for (i in frame.edgeCount until 6) {
            assertEquals(0f, frame.edges[3 * i], 0f)
            assertEquals(0f, frame.edges[3 * i + 1], 0f)
            assertEquals(SlabFrame.NO_EDGE, frame.edges[3 * i + 2], 0f)
        }
        // Every real half-plane has a unit normal.
        for (i in 0 until frame.edgeCount) {
            val a = frame.edges[3 * i]
            val b = frame.edges[3 * i + 1]
            assertEquals(1f, sqrt(a * a + b * b), 1e-4f)
        }
    }

    @Test
    fun `the silhouette is continuous through edge-on`() {
        // From just above to just below edge-on the outline must not jump: the bounds move about
        // a pixel for a tenth of a degree, whichever faces count as visible.
        var previous = FloatArray(4)
        var first = true
        var lean = 80f
        while (lean <= 100f) {
            pose(0f, lean)
            val now = floatArrayOf(frame.left, frame.top, frame.right, frame.bottom)
            if (!first) for (i in 0 until 4) assertEquals("bound $i at $lean", previous[i], now[i], 1.5f)
            previous = now
            first = false
            lean += 0.1f
        }
    }

    @Test
    fun `a tilt longer than the limit is scaled back to it`() {
        assertEquals(1f, SlabFrame.clampScale(0f, 100f), 0f)
        assertEquals(1f, SlabFrame.clampScale(120f, 100f), 1e-6f)
        val scale = SlabFrame.clampScale(0f, 330f)
        assertEquals(SlabGeometry.MAX_TILT_DEGREES, 330f * scale, 1e-3f)
        val diagonal = SlabFrame.clampScale(150f, 150f)
        assertEquals(SlabGeometry.MAX_TILT_DEGREES, sqrt(2f) * 150f * diagonal, 1e-3f)
    }

    @Test
    fun `an upright slab and its halo fit across the hero at every lean`() {
        // The Redmi's 720 px, the slab at its share of the width, the halo at its reach.
        val width = 720f
        val slab = width * SlabGeometry.WIDTH_FRACTION
        val glow = slab * SlabLook.GLOW_FRACTION
        var lean = 0f
        while (lean <= SlabGeometry.MAX_TILT_DEGREES) {
            frame.set(0f, lean, width / 2f, 800f, slab)
            assertTrue("cut on the left at $lean", frame.left - glow >= 0f)
            assertTrue("cut on the right at $lean", frame.right + glow <= width)
            lean += 1f
        }
    }

    @Test
    fun `the board fits on the underside with a margin on every side`() {
        assertTrue(SlabGeometry.GRID / 2f < SlabGeometry.HALF_X - SlabGeometry.BEVEL)
        assertTrue(SlabGeometry.GRID / 2f < SlabGeometry.HALF_Y - SlabGeometry.BEVEL)
        assertTrue(SlabGeometry.BEVEL < SlabGeometry.HALF_Z / 2f)
    }
}
