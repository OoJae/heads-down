package xyz.headsdown.ui.slab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [SlabFrame] held against a second derivation that shares none of its reasoning, in doubles:
 *
 * - the rotation is built from elementary rotations, `Rz(phi) Ry(theta) Rz(-phi)`, not Rodrigues;
 * - a face is visible when its projected outline winds the right way, not from where the eye is;
 * - the silhouette is the convex hull of the eight projected corners, not "edges with one
 *   visible face";
 * - a pixel is on the slab when the ray through it hits the box (the shader's own test, with the
 *   uniforms exactly as the renderer hands them over), not when the half-planes say so.
 */
class SlabMathIndependentTest {
    private val d = SlabGeometry.EYE_DISTANCE.toDouble()
    private val half = doubleArrayOf(SlabGeometry.HALF_X.toDouble(), SlabGeometry.HALF_Y.toDouble(), SlabGeometry.HALF_Z.toDouble())
    private val cx = 360.0
    private val cy = 770.0
    private val width = 446.4

    /** Four poses for the brief, and the awkward ones round them. */
    private val poses = listOf(
        0f to 62f, // rest
        40f to 40f, // leaning toward a corner
        -50f to 100f, // past edge-on, rolled
        12f to -140f, // from below, the other way up
        0f to 0f, // exactly face-on
        0f to 90f, // exactly edge-on
        90f to 0f, // exactly edge-on, sideways
        63.64f to 63.64f, // edge-on along the diagonal
        0f to 165f, // the clamp
        0f to 180f, // exactly face-on from below
        110f to -70f,
        0f to 89.2f, // the top face seen at under a degree
        0f to 90.8f, // the underside seen at under a degree
    )

    private fun rotation(tx: Double, ty: Double): Array<DoubleArray> {
        val degrees = hypot(tx, ty)
        if (degrees < 1e-9) return arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        val theta = Math.toRadians(degrees)
        val phi = atan2(ty, tx)
        fun rz(a: Double) = arrayOf(doubleArrayOf(cos(a), -sin(a), 0.0), doubleArrayOf(sin(a), cos(a), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        fun ry(a: Double) = arrayOf(doubleArrayOf(cos(a), 0.0, sin(a)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(a), 0.0, cos(a)))
        return times(times(rz(phi), ry(theta)), rz(-phi))
    }

    private fun times(a: Array<DoubleArray>, b: Array<DoubleArray>) =
        Array(3) { i -> DoubleArray(3) { j -> a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j] } }

    private fun apply(m: Array<DoubleArray>, v: DoubleArray) =
        DoubleArray(3) { i -> m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2] }

    private fun corner(index: Int) = doubleArrayOf(
        if (index and 1 != 0) half[0] else -half[0],
        if (index and 2 != 0) half[1] else -half[1],
        if (index and 4 != 0) half[2] else -half[2],
    )

    /** Pinhole projection from the eye at (0, 0, D), y up in the world and down on the screen. */
    private fun project(r: Array<DoubleArray>, p: DoubleArray): DoubleArray {
        val v = apply(r, p)
        val scale = width * d / (d - v[2])
        return doubleArrayOf(cx + v[0] * scale, cy - v[1] * scale)
    }

    private fun cross(o: DoubleArray, a: DoubleArray, b: DoubleArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    /** Andrew's monotone chain; collinear points are dropped. */
    private fun hull(points: List<DoubleArray>): List<DoubleArray> {
        val sorted = points.sortedWith(compareBy<DoubleArray> { it[0] }.thenBy { it[1] })
        val lower = ArrayList<DoubleArray>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 1e-7) lower.removeAt(lower.size - 1)
            lower += p
        }
        val upper = ArrayList<DoubleArray>()
        for (p in sorted.reversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 1e-7) upper.removeAt(upper.size - 1)
            upper += p
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    /** Positive inside a convex polygon given in either winding: the least distance to an edge line. */
    private fun inside(polygon: List<DoubleArray>, x: Double, y: Double): Double {
        var least = Double.POSITIVE_INFINITY
        var sign = 0.0
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val length = hypot(b[0] - a[0], b[1] - a[1])
            val side = ((b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0])) / length
            if (sign == 0.0) {
                // The centre of the slab is inside, whatever the winding.
                sign = if ((b[0] - a[0]) * (cy - a[1]) - (b[1] - a[1]) * (cx - a[0]) >= 0.0) 1.0 else -1.0
            }
            least = min(least, side * sign)
        }
        return least
    }

    @Test
    fun `the rotation, the eye and the light are the ones elementary rotations give`() {
        for ((tx, ty) in poses) {
            val frame = SlabFrame().apply { set(tx, ty, cx.toFloat(), cy.toFloat(), width.toFloat()) }
            val r = rotation(tx.toDouble(), ty.toDouble())
            for (i in 0 until 3) for (j in 0 until 3) {
                assertEquals("R[$i][$j] at ($tx, $ty)", r[i][j], frame.rotation[3 * i + j].toDouble(), 2e-6)
            }
            // The eye and the light in the slab's frame are the inverse rotation of the view's.
            val inverse = Array(3) { i -> DoubleArray(3) { j -> r[j][i] } }
            val eye = apply(inverse, doubleArrayOf(0.0, 0.0, d))
            val light = apply(inverse, SlabGeometry.LIGHT.map { it.toDouble() }.toDoubleArray())
            for (i in 0 until 3) {
                assertEquals("eye[$i] at ($tx, $ty)", eye[i], frame.eye[i].toDouble(), 1e-5)
                assertEquals("light[$i] at ($tx, $ty)", light[i], frame.light[i].toDouble(), 1e-5)
            }
            // The stone top's normal leans where the tilt vector points, by its length.
            val normal = apply(r, doubleArrayOf(0.0, 0.0, 1.0))
            val degrees = hypot(tx.toDouble(), ty.toDouble())
            assertEquals(cos(Math.toRadians(degrees)), normal[2], 1e-9)
            if (degrees > 1e-3 && degrees < 179.9) {
                val lean = hypot(normal[0], normal[1])
                assertEquals(tx / degrees, normal[0] / lean, 1e-9)
                assertEquals(ty / degrees, normal[1] / lean, 1e-9)
            }
        }
    }

    @Test
    fun `every corner lands where a pinhole camera puts it`() {
        for ((tx, ty) in poses) {
            val frame = SlabFrame().apply { set(tx, ty, cx.toFloat(), cy.toFloat(), width.toFloat()) }
            val r = rotation(tx.toDouble(), ty.toDouble())
            for (i in 0 until 8) {
                val p = project(r, corner(i))
                assertEquals("corner $i x at ($tx, $ty)", p[0], frame.corners[2 * i].toDouble(), 2e-3)
                assertEquals("corner $i y at ($tx, $ty)", p[1], frame.corners[2 * i + 1].toDouble(), 2e-3)
            }
            val xs = (0 until 8).map { project(r, corner(it))[0] }
            val ys = (0 until 8).map { project(r, corner(it))[1] }
            assertEquals(xs.min(), frame.left.toDouble(), 2e-3)
            assertEquals(xs.max(), frame.right.toDouble(), 2e-3)
            assertEquals(ys.min(), frame.top.toDouble(), 2e-3)
            assertEquals(ys.max(), frame.bottom.toDouble(), 2e-3)
        }
    }

    @Test
    fun `a face is visible exactly when its projected outline winds toward the viewer`() {
        for ((tx, ty) in poses) {
            val frame = SlabFrame().apply { set(tx, ty, cx.toFloat(), cy.toFloat(), width.toFloat()) }
            val r = rotation(tx.toDouble(), ty.toDouble())
            for (face in 0 until 6) {
                val axis = face / 2
                val positive = face % 2 == 0
                // The face's four corners in order round its outward normal (right-handed).
                val u = (axis + 1) % 3
                val v = (axis + 2) % 3
                val ring = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1).map { (su, sv) ->
                    val p = DoubleArray(3)
                    p[axis] = if (positive) half[axis] else -half[axis]
                    p[u] = su * half[u]
                    p[v] = (if (positive) sv else -sv) * half[v]
                    project(r, p)
                }
                // Twice the signed area on screen. The screen's y points down, so an outline that
                // is counter-clockwise in the world, facing us, comes out negative here.
                var area = 0.0
                for (i in 0 until 4) {
                    val a = ring[i]
                    val b = ring[(i + 1) % 4]
                    area += a[0] * b[1] - b[0] * a[1]
                }
                val bit = 1 shl face
                val says = frame.faces and bit != 0
                // A face seen exactly edge-on has no area and counts as hidden; a float's worth of
                // doubt either side of that is not a disagreement.
                if (abs(area) > 1.0) {
                    assertEquals("face $face at ($tx, $ty), area $area", area < 0.0, says)
                } else {
                    assertTrue("face $face of no area counted as visible at ($tx, $ty)", !says || abs(area) > 1e-3)
                }
            }
        }
    }

    @Test
    fun `the degenerate poses show exactly the faces they should`() {
        fun faces(tx: Float, ty: Float) = SlabFrame().apply { set(tx, ty, 360f, 770f, 446.4f) }.faces
        assertEquals("face-on", SlabGeometry.FACE_PZ, faces(0f, 0f))
        assertEquals("face-on from below", SlabGeometry.FACE_NZ, faces(0f, 180f))
        // Exactly edge-on, the eye is inside the slab's own thickness: neither flat face shows.
        assertEquals("edge-on", SlabGeometry.FACE_NY, faces(0f, 90f))
        assertEquals("edge-on from the top edge", SlabGeometry.FACE_PY, faces(0f, -90f))
        assertEquals("edge-on from the right", SlabGeometry.FACE_NX, faces(90f, 0f))
        assertEquals("edge-on from the left", SlabGeometry.FACE_PX, faces(-90f, 0f))
        assertEquals("edge-on along the diagonal", SlabGeometry.FACE_NX or SlabGeometry.FACE_NY, faces(63.6396f, 63.6396f))
        // The flat faces appear only once the eye clears the slab's thickness: acos(hz / D) is 89.05 degrees.
        assertEquals(SlabGeometry.FACE_PZ or SlabGeometry.FACE_NY, faces(0f, 89.0f))
        assertEquals(SlabGeometry.FACE_NY, faces(0f, 89.1f))
        assertEquals(SlabGeometry.FACE_NY, faces(0f, 90.9f))
        assertEquals(SlabGeometry.FACE_NZ or SlabGeometry.FACE_NY, faces(0f, 91.0f))
        // And at the clamp the underside and the lower rim show, nothing else.
        assertEquals(SlabGeometry.FACE_NZ or SlabGeometry.FACE_NY, faces(0f, SlabGeometry.MAX_TILT_DEGREES))
    }

    @Test
    fun `the silhouette is the convex hull of the projected corners`() {
        for ((tx, ty) in poses) {
            val frame = SlabFrame().apply { set(tx, ty, cx.toFloat(), cy.toFloat(), width.toFloat()) }
            val r = rotation(tx.toDouble(), ty.toDouble())
            val outline = hull((0 until 8).map { project(r, corner(it)) })
            assertEquals("silhouette edges at ($tx, $ty)", outline.size, frame.edgeCount)
            // Every side of the hull is one of the frame's half-planes: both its ends lie on that
            // line, and the slab's centre is on the negative side of it.
            val used = HashSet<Int>()
            for (i in outline.indices) {
                val a = outline[i]
                val b = outline[(i + 1) % outline.size]
                val match = (0 until frame.edgeCount).firstOrNull { e ->
                    val on = { p: DoubleArray -> abs(frame.edges[3 * e] * p[0] + frame.edges[3 * e + 1] * p[1] + frame.edges[3 * e + 2]) }
                    on(a) < 0.02 && on(b) < 0.02
                }
                assertTrue("hull side $i has no half-plane at ($tx, $ty)", match != null)
                used += match!!
                assertTrue(frame.edges[3 * match] * cx + frame.edges[3 * match + 1] * cy + frame.edges[3 * match + 2] < 0.0)
            }
            assertEquals("each half-plane is one side of the hull at ($tx, $ty)", frame.edgeCount, used.size)
            // And the corners the frame names for each edge are the ends of that side.
            for (e in 0 until frame.edgeCount) {
                for (end in 0 until 2) {
                    val p = project(r, corner(frame.edgeCorners[2 * e + end]))
                    assertTrue(outline.any { hypot(it[0] - p[0], it[1] - p[1]) < 0.02 })
                }
            }
        }
    }

    @Test
    fun `the half-planes and the hull agree on every pixel, to the mitre at a corner`() {
        val random = Random(41)
        for ((tx, ty) in poses) {
            val frame = SlabFrame().apply { set(tx, ty, cx.toFloat(), cy.toFloat(), width.toFloat()) }
            val r = rotation(tx.toDouble(), ty.toDouble())
            val outline = hull((0 until 8).map { project(r, corner(it)) })
            repeat(400) {
                val x = frame.left - 40f + random.nextFloat() * (frame.right - frame.left + 80f)
                val y = frame.top - 40f + random.nextFloat() * (frame.bottom - frame.top + 80f)
                val expected = -inside(outline, x.toDouble(), y.toDouble())
                assertEquals("distance at ($x, $y) for ($tx, $ty)", expected, frame.distance(x, y).toDouble(), 0.02)
            }
        }
    }

    @Test
    fun `a pixel is covered exactly when the shader's ray, from the uniforms as handed over, hits the box`() {
        val random = Random(43)
        for ((tx, ty) in poses) {
            val frame = SlabFrame().apply { set(tx, ty, cx.toFloat(), cy.toFloat(), width.toFloat()) }
            // uV2O is the nine numbers of `rotation` read column-major: column j is row j.
            val m = frame.rotation
            var hits = 0
            repeat(600) {
                val x = frame.left - 30f + random.nextFloat() * (frame.right - frame.left + 60f)
                val y = frame.top - 30f + random.nextFloat() * (frame.bottom - frame.top + 60f)
                val ray = doubleArrayOf(x - frame.centerX.toDouble(), frame.centerY.toDouble() - y, -frame.focal.toDouble())
                val rd = DoubleArray(3) { i -> m[i] * ray[0] + m[3 + i] * ray[1] + m[6 + i] * ray[2] }
                var near = Double.NEGATIVE_INFINITY
                var far = Double.POSITIVE_INFINITY
                for (i in 0 until 3) {
                    val ro = frame.eye[i].toDouble()
                    val inv = (if (rd[i] >= 0.0) 1.0 else -1.0) / max(abs(rd[i]), 1e-5)
                    val a = (-half[i] - ro) * inv
                    val b = (half[i] - ro) * inv
                    near = max(near, min(a, b))
                    far = min(far, max(a, b))
                }
                val hit = near <= far && far > 0.0
                val distance = frame.distance(x, y)
                // Half a pixel either side of the outline belongs to the antialiasing, not to this.
                if (distance < -0.5f) {
                    assertTrue("the ray misses inside the silhouette at ($x, $y) for ($tx, $ty)", hit)
                    hits++
                    // The hit is on the box, on the face the frame says is visible.
                    val q = DoubleArray(3) { i -> frame.eye[i] + rd[i] * near }
                    for (i in 0 until 3) assertTrue(abs(q[i]) <= half[i] + 1e-3)
                }
                if (distance > 0.5f) assertTrue("the ray hits outside the silhouette at ($x, $y) for ($tx, $ty)", !hit)
            }
            assertTrue("no pixel was tested inside at ($tx, $ty)", hits > 20)
        }
    }

    @Test
    fun `the 165 degree clamp keeps the direction and only shortens the lean`() {
        val random = Random(47)
        repeat(500) {
            val x = (random.nextFloat() - 0.5f) * 700f
            val y = (random.nextFloat() - 0.5f) * 700f
            val scale = SlabFrame.clampScale(x, y)
            val length = sqrt(x * x + y * y)
            assertTrue(scale > 0f && scale <= 1f)
            assertTrue("lean ${length * scale}", length * scale <= SlabGeometry.MAX_TILT_DEGREES + 1e-2f)
            if (length <= SlabGeometry.MAX_TILT_DEGREES) assertEquals(1f, scale, 0f)
        }
        // At the clamp the slab is still 15 degrees short of face-on from below: its lean has a
        // direction, the lower rim's face still shows, and the silhouette is still six-sided.
        val frame = SlabFrame().apply { set(0f, 400f * SlabFrame.clampScale(0f, 400f), 360f, 770f, 446.4f) }
        assertEquals(SlabGeometry.MAX_TILT_DEGREES, frame.tiltY, 1e-3f)
        assertEquals(6, frame.edgeCount)
        // Not a number in, nothing sensible out, but never a crash and never a scale above one.
        assertTrue(SlabFrame.clampScale(Float.POSITIVE_INFINITY, 0f) <= 1f)
    }
}
