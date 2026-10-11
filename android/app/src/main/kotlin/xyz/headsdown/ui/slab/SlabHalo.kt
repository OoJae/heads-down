package xyz.headsdown.ui.slab

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The polygon renderer's halo: the light under the slab as ONE triangle mesh round the
 * silhouette, coloured per vertex, drawn in one call.
 *
 * The light is a function of the distance to the silhouette, as in the shader: straight out
 * from every edge, round every corner, the cube of what is left of the reach, and weighted
 * toward below the slab while it is seen from above. A mesh says exactly that. An ellipse fitted
 * to the slab's bounds does not (edge-on it is a puddle with a flat core); gradients blended
 * "lighter" do, but ask the GPU to read back what it has drawn for every one of them.
 *
 * Rings of vertices follow the silhouette outward. The first is just inside it, under the faces
 * drawn afterwards, so the faces' antialiased edge lies on full light; the last is at the reach,
 * with no light, so the mesh's own edge needs no antialiasing. Round a corner the rings share
 * one inner point, so no two triangles of the fan cross.
 *
 * Nothing is allocated after construction.
 */
internal class SlabHaloMesh {
    /** Vertex positions, px: x at 2i, y at 2i + 1. */
    val xy = FloatArray(MAX_VERTICES * 2)

    /** One ARGB colour per vertex, not premultiplied. */
    val colors = IntArray(MAX_VERTICES)
    val indices = ShortArray(MAX_QUADS * 6)
    var vertexCount = 0
        private set
    var indexCount = 0
        private set

    private val ring = IntArray(MAX_CORNERS)
    private val ringAngle = FloatArray(MAX_CORNERS)
    private val nx = FloatArray(MAX_CORNERS)
    private val ny = FloatArray(MAX_CORNERS)

    // Made at the first draw, so building a mesh needs nothing of the platform. Plain vertex
    // colours and nothing else: the one way of drawing a mesh that every renderer agrees on.
    // (The platform dithers gradients but not vertex colours, and neither a shader nor texture
    // coordinates on the mesh changed that on the renderers tried, so this halo is NOT dithered,
    // unlike the shader's: its steps are one level of one channel.)
    private val paint by lazy(LazyThreadSafetyMode.NONE) { Paint().apply { isAntiAlias = false } }

    /**
     * Builds the mesh for [frame]. [rgb] is the light's colour (its alpha is ignored), [alpha]
     * its strength at the silhouette where the weight is 1, and [lowest] the weight above the
     * slab: the shader's `0.10 + 0.90 * under` on the dark page, `0.25` on the light one.
     * Returns false when there is nothing to draw.
     */
    fun build(frame: SlabFrame, glowPx: Float, rgb: Int, alpha: Float, lowest: Float): Boolean {
        vertexCount = 0
        indexCount = 0
        if (glowPx <= 0f || alpha <= 0f || frame.slabWidthPx < 1f) return false
        val c = frame.corners
        val cx = frame.centerX
        val cy = frame.centerY

        // The silhouette's corners, in order round the slab's centre (which is inside it).
        var n = 0
        for (i in 0 until 2 * frame.edgeCount) {
            val corner = frame.edgeCorners[i]
            var seen = false
            for (j in 0 until n) if (ring[j] == corner) seen = true
            if (seen || n == MAX_CORNERS) continue
            val angle = atan2(c[2 * corner + 1] - cy, c[2 * corner] - cx)
            var at = n
            while (at > 0 && ringAngle[at - 1] > angle) {
                ring[at] = ring[at - 1]
                ringAngle[at] = ringAngle[at - 1]
                at--
            }
            ring[at] = corner
            ringAngle[at] = angle
            n++
        }
        if (n < 3) return false

        // The outward normal of the edge leaving each corner.
        for (i in 0 until n) {
            val a = ring[i]
            val b = ring[(i + 1) % n]
            val ex = c[2 * b] - c[2 * a]
            val ey = c[2 * b + 1] - c[2 * a + 1]
            val mx = (c[2 * a] + c[2 * b]) / 2f - cx
            val my = (c[2 * a + 1] + c[2 * b + 1]) / 2f - cy
            val length = sqrt(ex * ex + ey * ey)
            var px: Float
            var py: Float
            if (length > 1e-3f) {
                px = ey / length
                py = -ex / length
                if (px * mx + py * my < 0f) {
                    px = -px
                    py = -py
                }
            } else {
                // Two corners on one pixel (an edge seen end-on): away from the centre will do.
                val away = sqrt(mx * mx + my * my).coerceAtLeast(1e-3f)
                px = mx / away
                py = my / away
            }
            nx[i] = px
            ny[i] = py
        }

        // Columns of vertices: round each corner, then along the edge that leaves it.
        val slab = frame.slabWidthPx
        var column = 0
        for (i in 0 until n) {
            val previous = (i + n - 1) % n
            val bx = c[2 * ring[i]]
            val by = c[2 * ring[i] + 1]
            // Every column of the fan starts at one point just inside the corner, on its bisector.
            var ix = -(nx[previous] + nx[i])
            var iy = -(ny[previous] + ny[i])
            val inward = sqrt(ix * ix + iy * iy)
            if (inward > 1e-4f) {
                ix = bx + ix / inward * INSET_PX
                iy = by + iy / inward * INSET_PX
            } else {
                ix = bx
                iy = by
            }
            for (s in 0..FAN) {
                val t = s / FAN.toFloat()
                var dx = nx[previous] + (nx[i] - nx[previous]) * t
                var dy = ny[previous] + (ny[i] - ny[previous]) * t
                val length = sqrt(dx * dx + dy * dy)
                if (length > 1e-4f) {
                    dx /= length
                    dy /= length
                } else {
                    dx = nx[i]
                    dy = ny[i]
                }
                column(column++, bx, by, dx, dy, ix, iy, glowPx, rgb, alpha, lowest, cy, slab)
            }
            val next = ring[(i + 1) % n]
            for (s in 1 until SPLIT) {
                val t = s / SPLIT.toFloat()
                val ex = bx + (c[2 * next] - bx) * t
                val ey = by + (c[2 * next + 1] - by) * t
                column(
                    column++, ex, ey, nx[i], ny[i], ex - nx[i] * INSET_PX, ey - ny[i] * INSET_PX,
                    glowPx, rgb, alpha, lowest, cy, slab,
                )
            }
        }
        vertexCount = column * (RINGS + 1)

        // Two triangles between each pair of neighbouring columns, ring by ring, all the way round.
        var at = 0
        for (col in 0 until column) {
            val a = col * (RINGS + 1)
            val b = ((col + 1) % column) * (RINGS + 1)
            for (k in 0 until RINGS) {
                indices[at++] = (a + k).toShort()
                indices[at++] = (b + k).toShort()
                indices[at++] = (b + k + 1).toShort()
                indices[at++] = (a + k).toShort()
                indices[at++] = (b + k + 1).toShort()
                indices[at++] = (a + k + 1).toShort()
            }
        }
        indexCount = at
        return true
    }

    /** One column: its inner vertex at ([ix], [iy]), the rest out from ([bx], [by]) along ([dx], [dy]). */
    private fun column(
        index: Int,
        bx: Float,
        by: Float,
        dx: Float,
        dy: Float,
        ix: Float,
        iy: Float,
        glowPx: Float,
        rgb: Int,
        alpha: Float,
        lowest: Float,
        centerY: Float,
        slabWidthPx: Float,
    ) {
        var at = index * (RINGS + 1)
        for (k in 0..RINGS) {
            val x = if (k == 0) ix else bx + dx * glowPx * REACH[k]
            val y = if (k == 0) iy else by + dy * glowPx * REACH[k]
            xy[2 * at] = x
            xy[2 * at + 1] = y
            val a = (alpha * FALLOFF[k] * weight(y, centerY, slabWidthPx, lowest) * 255f + 0.5f).toInt().coerceIn(0, 255)
            colors[at] = (a shl 24) or (rgb and 0x00FFFFFF)
            at++
        }
    }

    fun draw(canvas: Canvas) {
        if (indexCount == 0) return
        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLES, vertexCount * 2, xy, 0, null, 0, colors, 0, indices, 0, indexCount, paint,
        )
    }

    companion object {
        const val MAX_CORNERS = 6

        /** Steps round a corner, and along an edge (so the weighting bends along a long edge). */
        const val FAN = 4
        const val SPLIT = 3

        /** Rings outward from the silhouette. */
        const val RINGS = 6

        const val MAX_VERTICES = MAX_CORNERS * (FAN + SPLIT) * (RINGS + 1)
        const val MAX_QUADS = MAX_CORNERS * (FAN + SPLIT) * RINGS

        /** How far inside the silhouette the first ring sits, under the faces. */
        const val INSET_PX = 1.5f

        /** Where each ring is, as a fraction of the reach (the first is [INSET_PX] inside). */
        internal val REACH = floatArrayOf(0f, 0.10f, 0.22f, 0.36f, 0.53f, 0.74f, 1f)

        /** The light at each ring: the cube of what is left of the reach, the shader's `g * g * g`. */
        internal val FALLOFF = floatArrayOf(1f, 0.729f, 0.4746f, 0.2621f, 0.1038f, 0.0176f, 0f)

        /**
         * The shader's weighting: [lowest] above the slab's centre, rising to 1 below it, so light
         * that is under the slab shows under it.
         */
        fun weight(y: Float, centerY: Float, slabWidthPx: Float, lowest: Float): Float {
            val t = (((y - centerY) / (0.45f * slabWidthPx) + 0.22f) / (0.62f + 0.22f)).coerceIn(0f, 1f)
            return lowest + (1f - lowest) * t * t * (3f - 2f * t)
        }
    }
}
