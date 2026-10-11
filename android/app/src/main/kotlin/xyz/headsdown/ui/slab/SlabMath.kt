package xyz.headsdown.ui.slab

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The slab's fixed geometry and camera, shared by both renderers.
 *
 * View space: x right, y up, z toward the viewer. The eye sits at (0, 0, [EYE_DISTANCE]) and looks
 * down -z at the slab's centre, which is the origin. Slab space is the slab's own frame: +z is
 * the stone top, -z the lit underside, and -y the long edge that faces the viewer at rest (the
 * "lower rim").
 */
object SlabGeometry {
    /** Eye distance in slab units (the slab is one unit wide). */
    const val EYE_DISTANCE = 4.5f

    const val HALF_X = 0.5f
    const val HALF_Y = 0.575f
    const val HALF_Z = 0.075f

    /** The slab's width as a fraction of the composable's width, before any scroll scale. */
    const val WIDTH_FRACTION = 0.62f

    /** The pose with no sensor, no touch and no scroll: the top face, leaning 62 degrees back. */
    const val REST_DEGREES = 62f

    /** No pose leans further than this: at 180 the direction of a tilt vector stops meaning anything. */
    const val MAX_TILT_DEGREES = 165f

    /** The 5x5 board is a square this wide (slab units), centred on the underside. */
    const val GRID = 0.86f
    const val TILES = 5

    /** Half the gap between two tiles, in tile units. */
    const val TILE_GAP = 0.07f

    /** The bevel band in slab units; both renderers widen it to at least [MIN_BEVEL_PX]. */
    const val BEVEL = 0.014f
    const val MIN_BEVEL_PX = 2.5f

    const val FACE_PX = 1
    const val FACE_NX = 1 shl 1
    const val FACE_PY = 1 shl 2
    const val FACE_NY = 1 shl 3

    /** The stone top. */
    const val FACE_PZ = 1 shl 4

    /** The lit underside. */
    const val FACE_NZ = 1 shl 5

    /** The key light in view space (from above, a little left, toward the viewer), normalised. */
    internal val LIGHT = floatArrayOf(-0.2999f, 0.7998f, 0.5199f)

    /**
     * The four corners of each face, by corner index, in drawing order. A corner's index has bit 0
     * set for +x, bit 1 for +y, bit 2 for +z. Faces are in the order of their bits.
     */
    internal val FACE_CORNERS = arrayOf(
        intArrayOf(1, 3, 7, 5),
        intArrayOf(0, 2, 6, 4),
        intArrayOf(2, 3, 7, 6),
        intArrayOf(0, 1, 5, 4),
        intArrayOf(4, 5, 7, 6),
        intArrayOf(0, 1, 3, 2),
    )
}

/**
 * One frame of the slab: where it is on screen and how it is turned, with everything both
 * renderers need derived from that. Mutable and allocation-free on purpose: one instance lives
 * as long as the hero and [set] is called from the draw phase.
 *
 * A pose is a 2D TILT VECTOR in degrees. Its direction is where the top face's normal leans on
 * screen (x right, y up) and its length is the angle between that normal and the screen normal:
 * 0 is face-on to the stone top, 90 edge-on, 180 face-on to the underside. The rotation is the
 * shortest arc from the screen normal to that direction.
 */
class SlabFrame {
    var tiltX = 0f
        private set
    var tiltY = 0f
        private set

    /** The projection of the slab's centre, px. */
    var centerX = 0f
        private set
    var centerY = 0f
        private set

    /** The slab's width in px at the slab centre's depth. */
    var slabWidthPx = 1f
        private set

    /** Focal length in px: [slabWidthPx] times the eye distance. */
    var focal = 1f
        private set

    /**
     * The rotation that takes slab space to view space, row-major. The same nine numbers read
     * column-major are its transpose, the view-to-slab matrix the shader wants (`uV2O`).
     */
    val rotation = FloatArray(9)

    /** The eye in slab space (`uRo`). */
    val eye = FloatArray(3)

    /** The key light in slab space, normalised (`uL`). */
    val light = FloatArray(3)

    /** The eight corners projected to px: x at 2i, y at 2i + 1. */
    val corners = FloatArray(16)

    /**
     * The silhouette as half-planes (a, b, c) per edge: a*x + b*y + c is the signed distance in px
     * from the edge's line, negative on the slab's side. [edgeCount] of the six are real; the rest
     * are (0, 0, [NO_EDGE]) so the largest of all six is still the distance to the silhouette.
     */
    val edges = FloatArray(18)
    var edgeCount = 0
        private set

    /** The two corners of each silhouette edge, by corner index: from at 2i, to at 2i + 1. */
    val edgeCorners = IntArray(12)

    /** Which faces the eye sees: a mask of [SlabGeometry]'s FACE bits. */
    var faces = 0
        private set

    /** The bounding rectangle of the eight projected corners, px. */
    var left = 0f
        private set
    var top = 0f
        private set
    var right = 0f
        private set
    var bottom = 0f
        private set

    /** How much of the underside faces the eye: 0 at edge-on and above, 1 face-on from below. */
    val underside: Float get() = max(0f, -eye[2] / SlabGeometry.EYE_DISTANCE)

    fun set(tiltXDeg: Float, tiltYDeg: Float, centerX: Float, centerY: Float, slabWidthPx: Float) {
        tiltX = tiltXDeg
        tiltY = tiltYDeg
        this.centerX = centerX
        this.centerY = centerY
        this.slabWidthPx = slabWidthPx
        focal = slabWidthPx * SlabGeometry.EYE_DISTANCE

        val degrees = sqrt(tiltXDeg * tiltXDeg + tiltYDeg * tiltYDeg)
        val r = rotation
        if (degrees < 1e-4f) {
            r[0] = 1f; r[1] = 0f; r[2] = 0f
            r[3] = 0f; r[4] = 1f; r[5] = 0f
            r[6] = 0f; r[7] = 0f; r[8] = 1f
        } else {
            // Rodrigues about the axis z x n = (-dy, dx, 0), where n leans toward (dx, dy).
            val dx = tiltXDeg / degrees
            val dy = tiltYDeg / degrees
            val angle = degrees * DEG
            val c = cos(angle)
            val s = sin(angle)
            val ax = -dy
            val ay = dx
            val k = 1f - c
            r[0] = c + k * ax * ax; r[1] = k * ax * ay; r[2] = s * ay
            r[3] = k * ax * ay; r[4] = c + k * ay * ay; r[5] = -s * ax
            r[6] = -s * ay; r[7] = s * ax; r[8] = c
        }
        // The transpose applied to (0, 0, D) and to the view-space light: the third row, and all three.
        val d = SlabGeometry.EYE_DISTANCE
        eye[0] = r[6] * d
        eye[1] = r[7] * d
        eye[2] = r[8] * d
        val l = SlabGeometry.LIGHT
        light[0] = r[0] * l[0] + r[3] * l[1] + r[6] * l[2]
        light[1] = r[1] * l[0] + r[4] * l[1] + r[7] * l[2]
        light[2] = r[2] * l[0] + r[5] * l[1] + r[8] * l[2]

        var mask = 0
        if (eye[0] > SlabGeometry.HALF_X) mask = mask or SlabGeometry.FACE_PX
        if (eye[0] < -SlabGeometry.HALF_X) mask = mask or SlabGeometry.FACE_NX
        if (eye[1] > SlabGeometry.HALF_Y) mask = mask or SlabGeometry.FACE_PY
        if (eye[1] < -SlabGeometry.HALF_Y) mask = mask or SlabGeometry.FACE_NY
        if (eye[2] > SlabGeometry.HALF_Z) mask = mask or SlabGeometry.FACE_PZ
        if (eye[2] < -SlabGeometry.HALF_Z) mask = mask or SlabGeometry.FACE_NZ
        faces = mask

        var l0 = Float.POSITIVE_INFINITY
        var t0 = Float.POSITIVE_INFINITY
        var r0 = Float.NEGATIVE_INFINITY
        var b0 = Float.NEGATIVE_INFINITY
        for (i in 0 until 8) {
            project(
                if (i and 1 != 0) SlabGeometry.HALF_X else -SlabGeometry.HALF_X,
                if (i and 2 != 0) SlabGeometry.HALF_Y else -SlabGeometry.HALF_Y,
                if (i and 4 != 0) SlabGeometry.HALF_Z else -SlabGeometry.HALF_Z,
                corners, 2 * i,
            )
            val x = corners[2 * i]
            val y = corners[2 * i + 1]
            l0 = min(l0, x); r0 = max(r0, x)
            t0 = min(t0, y); b0 = max(b0, y)
        }
        left = l0; top = t0; right = r0; bottom = b0

        // A box edge is on the silhouette when exactly one of the two faces it joins is visible.
        var n = 0
        for (axis in 0 until 3) {
            val b = (axis + 1) % 3
            val c = (axis + 2) % 3
            for (signs in 0 until 4) {
                val bPositive = signs and 1 != 0
                val cPositive = signs and 2 != 0
                val bVisible = mask and (1 shl (2 * b + if (bPositive) 0 else 1)) != 0
                val cVisible = mask and (1 shl (2 * c + if (cPositive) 0 else 1)) != 0
                if (bVisible == cVisible || n == 6) continue
                val from = (if (bPositive) 1 shl b else 0) or (if (cPositive) 1 shl c else 0)
                val to = from or (1 shl axis)
                if (halfPlane(corners[2 * from], corners[2 * from + 1], corners[2 * to], corners[2 * to + 1], 3 * n)) {
                    edgeCorners[2 * n] = from
                    edgeCorners[2 * n + 1] = to
                    n++
                }
            }
        }
        edgeCount = n
        for (i in n until 6) {
            edges[3 * i] = 0f
            edges[3 * i + 1] = 0f
            edges[3 * i + 2] = NO_EDGE
        }
    }

    /** Writes the line through two px points at [at], oriented so the slab's centre is negative. */
    private fun halfPlane(x0: Float, y0: Float, x1: Float, y1: Float, at: Int): Boolean {
        var a = y1 - y0
        var b = x0 - x1
        val length = sqrt(a * a + b * b)
        if (length < 1e-3f) return false // seen end-on: the edge is a point and bounds nothing
        a /= length
        b /= length
        var c = -(a * x0 + b * y0)
        if (a * centerX + b * centerY + c > 0f) {
            a = -a; b = -b; c = -c
        }
        edges[at] = a
        edges[at + 1] = b
        edges[at + 2] = c
        return true
    }

    /** Projects a slab-space point to px, into [out] at [at] and [at] + 1. */
    fun project(x: Float, y: Float, z: Float, out: FloatArray, at: Int) {
        val r = rotation
        val vx = r[0] * x + r[1] * y + r[2] * z
        val vy = r[3] * x + r[4] * y + r[5] * z
        val vz = r[6] * x + r[7] * y + r[8] * z
        val scale = focal / (SlabGeometry.EYE_DISTANCE - vz)
        out[at] = centerX + vx * scale
        out[at + 1] = centerY - vy * scale
    }

    /**
     * The signed distance in px from the silhouette, to within the mitre at a corner: negative
     * inside the slab. This is the number the shader turns into coverage.
     */
    fun distance(px: Float, py: Float): Float {
        var d = Float.NEGATIVE_INFINITY
        for (i in 0 until 6) d = max(d, edges[3 * i] * px + edges[3 * i + 1] * py + edges[3 * i + 2])
        return d
    }

    /** The cosine between a face's outward normal and the key light. [face] is one FACE bit. */
    fun faceLight(face: Int): Float = when (face) {
        SlabGeometry.FACE_PX -> light[0]
        SlabGeometry.FACE_NX -> -light[0]
        SlabGeometry.FACE_PY -> light[1]
        SlabGeometry.FACE_NY -> -light[1]
        SlabGeometry.FACE_PZ -> light[2]
        else -> -light[2]
    }

    companion object {
        /** The constant term of an unused half-plane: far inside, so it is never the largest. */
        const val NO_EDGE = -1.0e5f
        private const val DEG = (Math.PI / 180.0).toFloat()

        /** Clamps a tilt vector's length to [SlabGeometry.MAX_TILT_DEGREES]; returns the scale to apply. */
        fun clampScale(tiltXDeg: Float, tiltYDeg: Float): Float {
            val degrees = sqrt(tiltXDeg * tiltXDeg + tiltYDeg * tiltYDeg)
            return if (degrees > SlabGeometry.MAX_TILT_DEGREES) SlabGeometry.MAX_TILT_DEGREES / degrees else 1f
        }

        internal fun isFinite(v: Float): Boolean = abs(v) <= Float.MAX_VALUE
    }
}
