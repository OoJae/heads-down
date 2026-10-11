package xyz.headsdown.ui.slab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** The polygon renderer's halo as numbers: where its vertices are and how much light each has. */
class SlabHaloTest {
    private val frame = SlabFrame()
    private val mesh = SlabHaloMesh()
    private val gold = 0xFFF2B233.toInt()
    private val glow = 98f
    private val per = SlabHaloMesh.RINGS + 1

    private fun build(tiltX: Float, tiltY: Float, lowest: Float = 0.1f): Boolean {
        frame.set(tiltX, tiltY, 360f, 770f, 446.4f)
        return mesh.build(frame, glow, gold, 0.62f, lowest)
    }

    private fun alpha(vertex: Int) = mesh.colors[vertex] ushr 24

    /** The true distance from a point to the silhouette's outline: the nearest of its edges, as segments. */
    private fun outlineDistance(x: Float, y: Float): Float {
        var nearest = Float.MAX_VALUE
        for (e in 0 until frame.edgeCount) {
            val ax = frame.corners[2 * frame.edgeCorners[2 * e]]
            val ay = frame.corners[2 * frame.edgeCorners[2 * e] + 1]
            val bx = frame.corners[2 * frame.edgeCorners[2 * e + 1]]
            val by = frame.corners[2 * frame.edgeCorners[2 * e + 1] + 1]
            val length2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
            val t = if (length2 > 0f) (((x - ax) * (bx - ax) + (y - ay) * (by - ay)) / length2).coerceIn(0f, 1f) else 0f
            nearest = minOf(nearest, hypot(x - (ax + (bx - ax) * t), y - (ay + (by - ay) * t)))
        }
        return nearest
    }

    @Test
    fun `every ring is the distance from the silhouette it stands for, round the corners too`() {
        for ((tx, ty) in listOf(0f to 62f, 0f to 90f, 0f to 143f, 60f to 60f, -75f to 75f, 0f to 0f, 120f to 60f, 0f to 165f)) {
            assertTrue("no halo at ($tx, $ty)", build(tx, ty))
            assertEquals(0, mesh.vertexCount % per)
            assertTrue(mesh.vertexCount <= SlabHaloMesh.MAX_VERTICES)
            for (v in 0 until mesh.vertexCount) {
                val x = mesh.xy[2 * v]
                val y = mesh.xy[2 * v + 1]
                val distance = outlineDistance(x, y)
                val k = v % per
                if (k == 0) {
                    // Inside the slab, within the inset: the faces drawn over it hide its edge.
                    assertTrue("the inner ring is $distance px off at ($tx, $ty)", distance <= SlabHaloMesh.INSET_PX + 0.01f)
                    assertTrue("the inner ring is outside the slab at ($tx, $ty)", frame.distance(x, y) <= 0.01f)
                } else {
                    // The light is a function of this distance: that is what makes the halo hug
                    // the silhouette, and rounded rather than mitred at its corners.
                    assertEquals("ring $k at ($tx, $ty)", glow * SlabHaloMesh.REACH[k], distance, 0.05f)
                    assertTrue("a vertex inside the slab at ($tx, $ty)", frame.distance(x, y) > 0f)
                }
                if (k == SlabHaloMesh.RINGS) assertEquals("light at the reach at ($tx, $ty)", 0, alpha(v))
                // Nothing is drawn further out than the shader's own draw rectangle reaches.
                assertTrue(x >= frame.left - glow - 1f && x <= frame.right + glow + 1f)
                assertTrue(y >= frame.top - glow - 1f && y <= frame.bottom + glow + 1f)
                assertEquals(0xF2B233, mesh.colors[v] and 0xFFFFFF)
            }
        }
    }

    @Test
    fun `the falloff is the cube of what is left of the reach`() {
        assertEquals(SlabHaloMesh.RINGS + 1, SlabHaloMesh.REACH.size)
        assertEquals(SlabHaloMesh.RINGS + 1, SlabHaloMesh.FALLOFF.size)
        for (k in 0..SlabHaloMesh.RINGS) {
            val left = 1f - SlabHaloMesh.REACH[k]
            assertEquals("ring $k", left * left * left, SlabHaloMesh.FALLOFF[k], 5e-4f)
            if (k > 0) assertTrue(SlabHaloMesh.REACH[k] > SlabHaloMesh.REACH[k - 1])
        }
        // Between two rings the light is drawn as a straight line: never more than a fiftieth of
        // the whole light away from the cube it stands for.
        for (k in 1..SlabHaloMesh.RINGS) {
            for (i in 1 until 10) {
                val t = i / 10f
                val reach = SlabHaloMesh.REACH[k - 1] + (SlabHaloMesh.REACH[k] - SlabHaloMesh.REACH[k - 1]) * t
                val drawn = SlabHaloMesh.FALLOFF[k - 1] + (SlabHaloMesh.FALLOFF[k] - SlabHaloMesh.FALLOFF[k - 1]) * t
                val left = 1f - reach
                assertEquals("between rings ${k - 1} and $k", left * left * left, drawn, 0.02f)
            }
        }
    }

    @Test
    fun `the triangles close the ring, with no vertex left out and none out of range`() {
        assertTrue(build(0f, 62f))
        val columns = mesh.vertexCount / per
        assertEquals(columns * SlabHaloMesh.RINGS * 6, mesh.indexCount)
        val used = BooleanArray(mesh.vertexCount)
        for (i in 0 until mesh.indexCount) {
            val index = mesh.indices[i].toInt()
            assertTrue(index in 0 until mesh.vertexCount)
            used[index] = true
        }
        assertTrue(used.all { it })
        // No triangle is a sliver across the slab: every one spans two neighbouring columns.
        for (t in 0 until mesh.indexCount / 3) {
            val cols = (0 until 3).map { mesh.indices[3 * t + it] / per }.toSet()
            assertEquals(2, cols.size)
            val (a, b) = cols.sorted()
            assertTrue(b - a == 1 || (a == 0 && b == columns - 1))
        }
    }

    @Test
    fun `no two triangles overlap, so no pixel gets the light twice`() {
        // The sum of the triangles' areas is the area of the band they tile: the band between
        // the inner outline and the outer one. A fan whose columns crossed would count some of it twice.
        for ((tx, ty) in listOf(0f to 62f, 0f to 143f, -75f to 75f, 0f to 90f)) {
            assertTrue(build(tx, ty))
            var triangles = 0.0
            var winding = 0
            for (t in 0 until mesh.indexCount / 3) {
                val a = mesh.indices[3 * t].toInt()
                val b = mesh.indices[3 * t + 1].toInt()
                val c = mesh.indices[3 * t + 2].toInt()
                val area = (mesh.xy[2 * b] - mesh.xy[2 * a]).toDouble() * (mesh.xy[2 * c + 1] - mesh.xy[2 * a + 1]) -
                    (mesh.xy[2 * c] - mesh.xy[2 * a]).toDouble() * (mesh.xy[2 * b + 1] - mesh.xy[2 * a + 1])
                triangles += abs(area) / 2.0
                // Every triangle that has any area winds the same way: none is folded over.
                if (abs(area) > 0.5) {
                    val sign = if (area > 0) 1 else -1
                    if (winding == 0) winding = sign
                    assertEquals("a triangle folded over at ($tx, $ty)", winding, sign)
                }
            }
            fun ringArea(k: Int): Double {
                var area = 0.0
                val columns = mesh.vertexCount / per
                for (col in 0 until columns) {
                    val a = col * per + k
                    val b = ((col + 1) % columns) * per + k
                    area += mesh.xy[2 * a].toDouble() * mesh.xy[2 * b + 1] - mesh.xy[2 * b].toDouble() * mesh.xy[2 * a + 1]
                }
                return abs(area) / 2.0
            }
            val band = ringArea(SlabHaloMesh.RINGS) - ringArea(0)
            assertEquals("the mesh covers its band once at ($tx, $ty)", band, triangles, band * 1e-4)
        }
    }

    @Test
    fun `from above the light is under the slab, from below it is all round`() {
        // At rest: the vertices along the lower rim are bright, those along the far edge are not.
        assertTrue(build(0f, 62f))
        var below = 0
        var above = 255
        for (column in 0 until mesh.vertexCount / per) {
            val v = column * per
            val y = mesh.xy[2 * v + 1]
            if (y > frame.bottom - 3f) below = maxOf(below, alpha(v))
            if (y < frame.top + 3f) above = minOf(above, alpha(v))
        }
        assertEquals((0.62f * 255f + 0.5f).toInt(), below)
        assertEquals("above the slab, a tenth of it", 16, above)
        // From underneath, the weighting is gone: every inner vertex carries the full light.
        frame.set(0f, 143f, 360f, 770f, 446.4f)
        assertTrue(mesh.build(frame, glow, gold, 0.62f, lowest = 1f))
        for (column in 0 until mesh.vertexCount / per) assertEquals(158, alpha(column * per))
    }

    @Test
    fun `each column runs straight out from the silhouette`() {
        assertTrue(build(0f, 62f))
        for (column in 0 until mesh.vertexCount / per) {
            val base = column * per + 1
            val x0 = mesh.xy[2 * base]
            val y0 = mesh.xy[2 * base + 1]
            val x1 = mesh.xy[2 * (column * per + SlabHaloMesh.RINGS)]
            val y1 = mesh.xy[2 * (column * per + SlabHaloMesh.RINGS) + 1]
            val length = hypot(x1 - x0, y1 - y0)
            assertEquals(glow * (1f - SlabHaloMesh.REACH[1]), length, 0.05f)
            // Every ring past the inner one is on the line between the two, in order.
            var previous = -1f
            for (k in 1..SlabHaloMesh.RINGS) {
                val x = mesh.xy[2 * (column * per + k)]
                val y = mesh.xy[2 * (column * per + k) + 1]
                val along = ((x - x0) * (x1 - x0) + (y - y0) * (y1 - y0)) / length
                val off = abs((x - x0) * (y1 - y0) - (y - y0) * (x1 - x0)) / length
                assertTrue(off < 0.02f)
                assertTrue(along > previous)
                previous = along
            }
        }
    }

    @Test
    fun `the colour is the light's, whatever alpha it came with`() {
        frame.set(0f, 62f, 360f, 770f, 446.4f)
        assertTrue(mesh.build(frame, glow, 0x00E8622A, 0.5f, 1f))
        for (v in 0 until mesh.vertexCount) assertEquals(0xE8622A, mesh.colors[v] and 0xFFFFFF)
    }

    @Test
    fun `no reach, no light or no slab is no mesh`() {
        frame.set(0f, 62f, 360f, 770f, 446.4f)
        assertFalse(mesh.build(frame, 0f, gold, 0.62f, 0.1f))
        assertFalse(mesh.build(frame, glow, gold, 0f, 0.1f))
        assertEquals(0, mesh.indexCount)
        frame.set(0f, 62f, 360f, 770f, 0.5f)
        assertFalse(mesh.build(frame, glow, gold, 0.62f, 0.1f))
    }

    @Test
    fun `the weighting is the shader's`() {
        // smoothstep(-0.22, 0.62, (y - cy) / (0.45 * width)), from the lowest weight up to one.
        val w = 400f
        assertEquals(0.1f, SlabHaloMesh.weight(500f - 0.22f * 0.45f * w, 500f, w, 0.1f), 1e-5f)
        assertEquals(0.1f, SlabHaloMesh.weight(0f, 500f, w, 0.1f), 0f)
        assertEquals(1f, SlabHaloMesh.weight(500f + 0.62f * 0.45f * w, 500f, w, 0.1f), 1e-5f)
        assertEquals(1f, SlabHaloMesh.weight(2_000f, 500f, w, 0.1f), 0f)
        val middle = SlabHaloMesh.weight(500f + 0.2f * 0.45f * w, 500f, w, 0.1f)
        assertEquals(0.55f, middle, 1e-4f)
        assertTrue(abs(SlabHaloMesh.weight(600f, 500f, w, 1f) - 1f) < 1e-6f)
    }
}
