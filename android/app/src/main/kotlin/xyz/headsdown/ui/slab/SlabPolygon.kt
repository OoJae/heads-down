package xyz.headsdown.ui.slab

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Renderer B: the same slab from polygons projected in Kotlin, on a plain Canvas. Same geometry
 * and the same lighting numbers as the shader, per face instead of per pixel: flat faces, the
 * bevel as stroked edges, the board as tile quads, the halo as one radial gradient.
 *
 * It is the renderer of Android 12 and 12L, the default of tests and previews, and the fallback
 * if the shader cannot hold the frame rate. Compose `Path` only; nothing is allocated per frame
 * (the halo's brush is rebuilt when its colour changes).
 */
internal class PolygonSlabDrawer : SlabDrawer {
    private val path = Path()
    private val pts = FloatArray(8)
    private var haloBrush: Brush? = null
    private var haloKey = Color.Unspecified

    override fun DrawScope.draw(frame: SlabFrame, look: SlabLook, palette: SlabPalette) {
        halo(frame, look, palette)
        val eye = frame.eye
        val eyeLength = SlabGeometry.EYE_DISTANCE
        for (face in 0 until 6) {
            val bit = 1 shl face
            if (frame.faces and bit == 0) continue
            val corners = SlabGeometry.FACE_CORNERS[face]
            val c = frame.corners
            path.rewind()
            path.moveTo(c[2 * corners[0]], c[2 * corners[0] + 1])
            path.lineTo(c[2 * corners[1]], c[2 * corners[1] + 1])
            path.lineTo(c[2 * corners[2]], c[2 * corners[2] + 1])
            path.lineTo(c[2 * corners[3]], c[2 * corners[3] + 1])
            path.close()
            val under = bit == SlabGeometry.FACE_NZ
            val ndl = max(0f, if (under) abs(frame.light[2]) else frame.faceLight(bit))
            val ndv = (abs(eye[face / 2]) / eyeLength).coerceIn(0f, 1f)
            var fr = 1f - ndv
            fr *= fr
            fr *= fr
            drawPath(path, stone(palette, 0.55f + 0.6f * ndl, fr * look.rim * 0.30f))
        }

        if (frame.faces and SlabGeometry.FACE_NZ != 0) board(frame, look, palette)
        sides(frame, look)
        bevels(frame, look, palette)
        outline(frame, look, palette)
    }

    /** The chalk rim: the silhouette stroked, strong only when the rim is (a frozen slab). */
    private fun DrawScope.outline(frame: SlabFrame, look: SlabLook, palette: SlabPalette) {
        val alpha = 0.6f * look.rim * look.rim
        if (alpha <= 0.02f) return
        val c = frame.corners
        val width = max(1.5f, 0.004f * frame.slabWidthPx)
        for (i in 0 until frame.edgeCount) {
            val from = frame.edgeCorners[2 * i]
            val to = frame.edgeCorners[2 * i + 1]
            drawLine(
                palette.rim,
                Offset(c[2 * from], c[2 * from + 1]),
                Offset(c[2 * to], c[2 * to + 1]),
                strokeWidth = width,
                cap = StrokeCap.Round,
                alpha = alpha,
            )
        }
    }

    /** The stone in squared light: face^2 * [diffuse] + rim^2 * [sheen], back through a square root. */
    private fun stone(palette: SlabPalette, diffuse: Float, sheen: Float): Color {
        val f = palette.face
        val r = palette.rim
        return Color(
            red = sqrt(min(1f, f.red * f.red * diffuse + r.red * r.red * sheen)),
            green = sqrt(min(1f, f.green * f.green * diffuse + r.green * r.green * sheen)),
            blue = sqrt(min(1f, f.blue * f.blue * diffuse + r.blue * r.blue * sheen)),
        )
    }

    private fun DrawScope.halo(frame: SlabFrame, look: SlabLook, palette: SlabPalette) {
        if (look.glowPx <= 0f) return
        val strength = max(look.heat, 0.35f * look.seam)
        val color: Color
        val alpha: Float
        if (palette.isLight) {
            // On a light page the slab casts a shadow, warmed by whatever light it has.
            color = androidx.compose.ui.graphics.lerp(palette.ink, look.emit, min(1f, strength * 0.9f))
            alpha = 0.26f + (0.46f - 0.26f) * strength
        } else {
            color = look.emit
            alpha = 0.62f * strength
        }
        if (alpha <= 0.004f) return
        val keyed = color.copy(alpha = alpha)
        var brush = haloBrush
        if (brush == null || keyed != haloKey) {
            // The shader's falloff is a cube of the distance; these stops follow it.
            brush = Brush.radialGradient(
                0f to keyed,
                0.62f to keyed,
                0.75f to keyed.copy(alpha = alpha * 0.5f),
                0.88f to keyed.copy(alpha = alpha * 0.14f),
                1f to keyed.copy(alpha = 0f),
                center = Offset.Zero,
                radius = 1f,
            )
            haloBrush = brush
            haloKey = keyed
        }
        val halfW = (frame.right - frame.left) / 2f
        val halfH = (frame.bottom - frame.top) / 2f
        // From above, the light is under the slab: the glow sits low. From below it is all around.
        val low = (1f - min(1f, frame.underside * 2.5f)) * 0.5f * look.glowPx
        val cx = (frame.left + frame.right) / 2f
        val cy = (frame.top + frame.bottom) / 2f + low
        val rx = halfW + look.glowPx
        val ry = halfH + look.glowPx - low
        withTransform({
            translate(cx, cy)
            scale(rx, ry, Offset.Zero)
        }) {
            drawRect(brush, Offset(-1f, -1f), Size(2f, 2f))
        }
    }

    /** The 5x5 board on the underside: a darker bed, then one path of five tiles per row. */
    private fun DrawScope.board(frame: SlabFrame, look: SlabLook, palette: SlabPalette) {
        val half = SlabGeometry.GRID / 2f
        val z = -SlabGeometry.HALF_Z
        val e = look.emit
        val f = palette.face
        val glow = 0.075f * min(1f, look.lit / SlabGeometry.TILES)
        quad(frame, -half, -half, half, half, z)
        drawPath(
            path,
            Color(
                red = sqrt(min(1f, f.red * f.red * 0.40f + e.red * e.red * glow)),
                green = sqrt(min(1f, f.green * f.green * 0.40f + e.green * e.green * glow)),
                blue = sqrt(min(1f, f.blue * f.blue * 0.40f + e.blue * e.blue * glow)),
            ),
        )
        val tl = abs(frame.light[2])
        // A little above the shader's flat pad: there is no shoulder here to catch the light.
        val metal = 0.055f + 0.11f * tl
        val step = SlabGeometry.GRID / SlabGeometry.TILES
        val gap = SlabGeometry.TILE_GAP * step
        for (row in 0 until SlabGeometry.TILES) {
            val lit = (look.lit - row).coerceIn(0f, 1f)
            val k = metal + (0.92f - metal) * lit
            val white = 0.05f * lit
            path.rewind()
            val y0 = -half + row * step + gap
            val y1 = -half + (row + 1) * step - gap
            for (column in 0 until SlabGeometry.TILES) {
                val x0 = -half + column * step + gap
                val x1 = -half + (column + 1) * step - gap
                frame.project(x0, y0, z, pts, 0)
                frame.project(x1, y0, z, pts, 2)
                frame.project(x1, y1, z, pts, 4)
                frame.project(x0, y1, z, pts, 6)
                path.moveTo(pts[0], pts[1])
                path.lineTo(pts[2], pts[3])
                path.lineTo(pts[4], pts[5])
                path.lineTo(pts[6], pts[7])
                path.close()
            }
            drawPath(
                path,
                Color(
                    red = sqrt(min(1f, e.red * e.red * k + white)),
                    green = sqrt(min(1f, e.green * e.green * k + white)),
                    blue = sqrt(min(1f, e.blue * e.blue * k + white)),
                ),
            )
        }
    }

    private fun quad(frame: SlabFrame, x0: Float, y0: Float, x1: Float, y1: Float, z: Float) {
        frame.project(x0, y0, z, pts, 0)
        frame.project(x1, y0, z, pts, 2)
        frame.project(x1, y1, z, pts, 4)
        frame.project(x0, y1, z, pts, 6)
        path.rewind()
        path.moveTo(pts[0], pts[1])
        path.lineTo(pts[2], pts[3])
        path.lineTo(pts[4], pts[5])
        path.lineTo(pts[6], pts[7])
        path.close()
    }

    /** On each visible side face: the light leaking up from the lower rim, and the seam line. */
    private fun DrawScope.sides(frame: SlabFrame, look: SlabLook) {
        if (look.seam <= 0.004f && look.heat <= 0.004f) return
        val hx = SlabGeometry.HALF_X
        val hy = SlabGeometry.HALF_Y
        val low = -SlabGeometry.HALF_Z
        val bevel = max(SlabGeometry.BEVEL, SlabGeometry.MIN_BEVEL_PX / frame.slabWidthPx)
        val seamZ = low + bevel * 1.15f
        val leakZ = low + 0.055f
        for (face in 0 until 4) {
            if (frame.faces and (1 shl face) == 0) continue
            // The face's two ends, along its lower rim.
            val ax: Float
            val ay: Float
            val bx: Float
            val by: Float
            when (face) {
                0 -> { ax = hx; ay = -hy; bx = hx; by = hy }
                1 -> { ax = -hx; ay = -hy; bx = -hx; by = hy }
                2 -> { ax = -hx; ay = hy; bx = hx; by = hy }
                else -> { ax = -hx; ay = -hy; bx = hx; by = -hy }
            }
            if (look.heat > 0.004f) {
                frame.project(ax, ay, low, pts, 0)
                frame.project(bx, by, low, pts, 2)
                frame.project(bx, by, leakZ, pts, 4)
                frame.project(ax, ay, leakZ, pts, 6)
                path.rewind()
                path.moveTo(pts[0], pts[1])
                path.lineTo(pts[2], pts[3])
                path.lineTo(pts[4], pts[5])
                path.lineTo(pts[6], pts[7])
                path.close()
                drawPath(path, look.emit, alpha = 0.30f * look.heat)
            }
            if (look.seam > 0.004f) {
                frame.project(ax, ay, seamZ, pts, 0)
                frame.project(bx, by, seamZ, pts, 2)
                val facing = (abs(frame.eye[face / 2]) / SlabGeometry.EYE_DISTANCE).coerceIn(0f, 1f)
                drawLine(
                    look.emit,
                    Offset(pts[0], pts[1]),
                    Offset(pts[2], pts[3]),
                    strokeWidth = max(1.5f, 0.0095f * frame.slabWidthPx * facing),
                    alpha = look.seam,
                )
            }
        }
    }

    /**
     * The bevel: the four edges of the visible flat face, each as bright as the shader's bevel
     * band would be at its brightest point between that face and the side it meets.
     */
    private fun DrawScope.bevels(frame: SlabFrame, look: SlabLook, palette: SlabPalette) {
        val top = frame.faces and SlabGeometry.FACE_PZ != 0
        val under = frame.faces and SlabGeometry.FACE_NZ != 0
        if (!top && !under) return
        val corners = SlabGeometry.FACE_CORNERS[if (top) 4 else 5]
        val nz = if (top) 1f else -1f
        val eye = frame.eye
        val d = SlabGeometry.EYE_DISTANCE
        val vx = eye[0] / d
        val vy = eye[1] / d
        val vz = eye[2] / d
        val lx = frame.light[0]
        val ly = frame.light[1]
        val lz = if (under) -abs(frame.light[2]) else frame.light[2]
        var hx = lx + vx
        var hy = ly + vy
        var hz = lz + vz
        val hn = sqrt(hx * hx + hy * hy + hz * hz).coerceAtLeast(1e-4f)
        hx /= hn; hy /= hn; hz /= hn
        val width = max(1.5f, 0.004f * frame.slabWidthPx)
        val c = frame.corners
        for (edge in 0 until 4) {
            // The side this edge meets, in the order of the face's corners: -y, +x, +y, -x.
            val sx = if (edge == 1) 1f else if (edge == 3) -1f else 0f
            val sy = if (edge == 0) -1f else if (edge == 2) 1f else 0f
            var best = 0f
            for (i in 1..5) {
                // The bevel's normal, from the flat face (i = 0) round to the side (i = 6).
                val a = i / 6f
                val wz = 1f - a
                val n = sqrt(wz * wz + a * a)
                val nx = sx * a / n
                val ny = sy * a / n
                val nzz = nz * wz / n
                var sp = max(0f, nx * hx + ny * hy + nzz * hz)
                sp *= sp; sp *= sp; sp *= sp; sp *= sp; sp *= sp
                var fr = 1f - max(0f, nx * vx + ny * vy + nzz * vz)
                fr *= fr
                fr *= fr
                best = max(best, sp * 0.42f + fr * look.rim)
            }
            val from = corners[edge]
            val to = corners[(edge + 1) % 4]
            val edgeLight = palette.faceEdge
            val rim = palette.rim
            val color = Color(
                red = sqrt(min(1f, edgeLight.red * edgeLight.red + rim.red * rim.red * best)),
                green = sqrt(min(1f, edgeLight.green * edgeLight.green + rim.green * rim.green * best)),
                blue = sqrt(min(1f, edgeLight.blue * edgeLight.blue + rim.blue * rim.blue * best)),
            )
            drawLine(
                color,
                Offset(c[2 * from], c[2 * from + 1]),
                Offset(c[2 * to], c[2 * to + 1]),
                strokeWidth = width,
                cap = StrokeCap.Round,
            )
        }
    }
}
