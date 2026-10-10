package xyz.headsdown.ui.slab

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min

/**
 * Renderer A: the slab as ONE fragment shader that intersects each pixel's ray with the box
 * analytically. No raymarch, no time uniform, no derivatives, no multisampling.
 *
 * - Antialiasing comes from Kotlin: [SlabFrame.edges] are the silhouette's half-planes in px and
 *   coverage is `clamp(0.5 - distance)`. Interior edges are a rounded-box normal evaluated on the
 *   flat box, so the bevel is shading, not geometry.
 * - PRECISION: on Mali `half` can be a true 16-bit float. Every coordinate, ray and half-plane is
 *   `float`; only the colour uniforms are `half`.
 * - Light is computed in squared space (square in, square root out), the output is premultiplied
 *   and transparent outside the slab and its halo, and the halo has finite support, so the draw
 *   rectangle's edge is exactly zero.
 * - Every colour is a `layout(color)` uniform: there is no colour literal below.
 */
internal const val SLAB_AGSL = """
uniform float2 uC;
uniform float uF;
uniform float3 uRo;
uniform float3x3 uV2O;
uniform float3 uH;
uniform float3 uL;
uniform float3 uEdge[6];
uniform float uBevel;
uniform float uGrid;
uniform float uLit;
uniform float uHeat;
uniform float uSeam;
uniform float uRim;
uniform float uGlow;
uniform float uLight;
uniform float3 uTune;
layout(color) uniform half4 uFace;
layout(color) uniform half4 uEmit;
layout(color) uniform half4 uRimC;
layout(color) uniform half4 uInk;
uniform shader uGrain;

const float GAP = 0.07;
const float GRAIN_TEXELS = 224.0;

float3 lin(half4 c) {
    float3 v = float3(c.rgb);
    return mix(v, v * v, uTune.z);
}

half4 main(float2 p) {
    // Distance to the silhouette: the largest half-plane for coverage, and the length of the
    // positive parts for the halo, which rounds the glow at the corners.
    float d = -1.0e6;
    float o2 = 0.0;
    for (int i = 0; i < 6; i++) {
        float e = dot(uEdge[i].xy, p) + uEdge[i].z;
        d = max(d, e);
        float eo = max(e, 0.0);
        o2 += eo * eo;
    }
    float cov = clamp(0.5 - d, 0.0, 1.0);

    float3 emit = lin(uEmit);
    float slabW = uF / length(uRo);
    float under = clamp(-uRo.z / length(uRo) * 2.5, 0.0, 1.0);

    // The light under the slab. Finite support: zero at uGlow px from the silhouette.
    float g = clamp(1.0 - sqrt(o2) / max(uGlow, 1.0), 0.0, 1.0);
    g = g * g * g;
    float below = smoothstep(-0.22, 0.62, (p.y - uC.y) / (0.45 * slabW));
    float hs = max(uHeat, 0.35 * uSeam);
    float wDark = mix(0.10 + 0.90 * below, 1.0, under);
    float aDark = g * wDark * hs * 0.62;
    float aLight = g * (0.25 + 0.75 * below) * mix(0.26, 0.46, hs);
    float3 cLight = mix(float3(uInk.rgb), float3(uEmit.rgb), clamp(hs * 0.9, 0.0, 1.0));
    float ha = mix(aDark, aLight, uLight);
    float3 hc = mix(float3(uEmit.rgb), cLight, uLight);

    float3 c = float3(0.0);
    if (cov > 0.0) {
        float3 rd = uV2O * float3(p.x - uC.x, uC.y - p.y, -uF);
        float3 sg = step(float3(0.0), rd) * 2.0 - 1.0;
        float3 inv = sg / max(abs(rd), float3(1.0e-5));
        float3 ta = (-uH - uRo) * inv;
        float3 tb = (uH - uRo) * inv;
        float3 tn = min(ta, tb);
        float t = max(max(tn.x, tn.y), tn.z);
        float3 q = clamp(uRo + rd * t, -uH, uH);
        bool onZ = tn.z >= tn.x && tn.z >= tn.y;
        bool onY = !onZ && tn.y >= tn.x;
        bool isUnder = onZ && uRo.z < 0.0;

        // Rounded-box normal on the flat box: the face normal, turning toward the neighbouring
        // face inside the bevel band.
        float3 sq = step(float3(0.0), q) * 2.0 - 1.0;
        float3 e3 = max(abs(q) - (uH - float3(uBevel)), float3(0.0));
        float3 n = normalize(e3 * sq + float3(1.0e-6));
        float bm = clamp((e3.x + e3.y + e3.z) / uBevel - 1.0, 0.0, 1.0);

        float3 v = normalize(uRo - q);
        float3 l = uL;
        if (isUnder) { l = float3(uL.xy, -abs(uL.z)); }
        float ndl = max(dot(n, l), 0.0);
        float ndv = max(dot(n, v), 0.0);
        float3 hv = normalize(l + v);
        float sp = max(dot(n, hv), 0.0);
        sp *= sp; sp *= sp; sp *= sp; sp *= sp; sp *= sp;
        float fr = 1.0 - ndv;
        fr *= fr; fr *= fr;

        float3 face = lin(uFace);
        float3 rimc = lin(uRimC);
        float2 guv = onZ ? q.xy : (onY ? q.xz : q.yz);
        float gr = (float(uGrain.eval(guv * GRAIN_TEXELS).r) - 0.5) * uTune.x * smoothstep(0.06, 0.3, ndv);

        // A flat face under a far light is one flat colour; the sweep leans it toward the light.
        c = face * (0.55 + 0.6 * ndl) * (1.0 + 0.5 * gr) * (1.0 + 0.22 * dot(q, l));
        c += rimc * sp * mix(0.022, 0.42, bm);
        c += rimc * fr * uRim * mix(0.30, 1.0, bm);

        if (!onZ) {
            // A side face: light leaking up from the lower rim, and the seam line on it.
            float zz = q.z + uH.z;
            float sw = max(0.0045, 1.3 * t);
            float seam = uSeam * (1.0 - smoothstep(sw * 0.4, sw * 1.4, abs(zz - uBevel * 1.15)));
            float leak = uHeat * exp(-zz * 24.0) * 0.5;
            c += emit * (seam * 1.05 + leak);
        }

        if (isUnder) {
            // The 5x5 board. The footprint of one pixel on the face is analytic: the derivative
            // of the hit point with respect to the pixel, in tile units.
            float2 cuv = (q.xy / uGrid + 0.5) * 5.0;
            float2 cell = floor(cuv);
            float2 f = cuv - cell;
            float rz = sg.z * max(abs(rd.z), 1.0e-4);
            float3 ex = uV2O[0];
            float3 ey = -uV2O[1];
            float3 dqx = t * (ex - rd * (ex.z / rz));
            float3 dqy = t * (ey - rd * (ey.z / rz));
            float2 fw = (abs(dqx.xy) + abs(dqy.xy)) * (5.0 / uGrid) + float2(1.0e-5);

            float2 ed = min(f, 1.0 - f);
            float2 m2 = clamp((ed - GAP) / fw + 0.5, 0.0, 1.0);
            // Under about 4 px a tile, the lines fade to their mean instead of shimmering.
            float2 lod = smoothstep(float2(0.16), float2(0.28), fw);
            m2 = mix(m2, float2(1.0 - 2.0 * GAP), lod);
            float tile = m2.x * m2.y;
            float2 bd = min(cuv, 5.0 - cuv);
            float2 b2 = clamp(bd / fw + 0.5, 0.0, 1.0);
            float board = b2.x * b2.y;
            float lit = clamp(uLit - cell.y, 0.0, 1.0);

            // Each tile is a raised pad: its shoulders turn the normal outward.
            float2 c2 = f - 0.5;
            float bw = max(0.085, 1.5 * max(fw.x, fw.y));
            float2 be = clamp((abs(c2) - (0.5 - GAP - bw)) / bw, 0.0, 1.0);
            float2 sc = step(float2(0.0), c2) * 2.0 - 1.0;
            float3 nt = normalize(float3(sc * be * uTune.y, -1.0));
            float tl = max(dot(nt, l), 0.0);
            float ts = max(dot(nt, hv), 0.0);
            ts *= ts; ts *= ts; ts *= ts; ts *= ts;
            float shoulder = max(be.x, be.y) * uTune.y;
            float3 metal = emit * (0.030 + 0.11 * tl + 0.30 * ts) * (1.0 + 0.5 * gr);
            float core = clamp(1.0 - dot(c2, c2) * 2.4, 0.0, 1.0);
            float3 hot = (emit * (0.74 + 0.24 * core) + float3(0.018 * core)) * (1.0 - 0.30 * shoulder);
            float3 pad = mix(metal, hot, lit);
            float3 gap = face * 0.40 + emit * 0.075 * lit;
            c = mix(c, mix(gap, pad, tile), board);
        }
    }

    c = min(c, float3(1.0));
    float3 disp = mix(c, sqrt(c), uTune.z);
    float a = cov + ha * (1.0 - cov);
    float3 rgb = disp * cov + hc * ha * (1.0 - cov);

    // One LSB of interleaved-gradient noise: dark gradients band on this LCD.
    float dn = fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715)))) - 0.5;
    float dw = min(a * 24.0, 1.0) / 255.0;
    a = clamp(a + dn * dw * step(a, 0.999), 0.0, 1.0);
    rgb = clamp(rgb + float3(dn * dw), float3(0.0), float3(a));
    return half4(half3(rgb), half(a));
}
"""

/** Counts what the inert defaults must never do; tests read it. */
internal object SlabProbe {
    @Volatile var shadersBuilt = 0
    @Volatile var frameCallbacks = 0
    @Volatile var draws = 0
}

/** The grain: 64x64 of noise from a fixed seed, made once. */
internal object SlabGrain {
    const val SIZE = 64

    fun bitmap(): Bitmap {
        val pixels = IntArray(SIZE * SIZE)
        var seed = 0x51AB5EEDL
        for (i in pixels.indices) {
            // Two draws of a 48-bit LCG, averaged: a triangular distribution reads as stone, not static.
            seed = (seed * 0x5DEECE66DL + 0xBL) and 0xFFFFFFFFFFFFL
            val a = (seed ushr 24).toInt() and 0xFF
            seed = (seed * 0x5DEECE66DL + 0xBL) and 0xFFFFFFFFFFFFL
            val b = (seed ushr 24).toInt() and 0xFF
            val v = (a + b) / 2
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }
}

/**
 * Renderer A. One [RuntimeShader] and one brush for the hero's lifetime; a frame only sets
 * uniforms and draws the slab's bounding rectangle plus the halo margin.
 */
@RequiresApi(33)
internal class ShaderSlabDrawer : SlabDrawer {
    private val shader = RuntimeShader(SLAB_AGSL)
    private val brush = ShaderBrush(shader)

    init {
        SlabProbe.shadersBuilt++
        val grain = BitmapShader(SlabGrain.bitmap(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        grain.filterMode = BitmapShader.FILTER_MODE_LINEAR
        shader.setInputShader("uGrain", grain)
        shader.setFloatUniform("uH", SlabGeometry.HALF_X, SlabGeometry.HALF_Y, SlabGeometry.HALF_Z)
        shader.setFloatUniform("uGrid", SlabGeometry.GRID)
    }

    override fun DrawScope.draw(frame: SlabFrame, look: SlabLook, palette: SlabPalette) {
        val s = shader
        s.setFloatUniform("uC", frame.centerX, frame.centerY)
        s.setFloatUniform("uF", frame.focal)
        s.setFloatUniform("uRo", frame.eye)
        s.setFloatUniform("uV2O", frame.rotation)
        s.setFloatUniform("uL", frame.light)
        s.setFloatUniform("uEdge", frame.edges)
        s.setFloatUniform("uBevel", max(SlabGeometry.BEVEL, SlabGeometry.MIN_BEVEL_PX / frame.slabWidthPx))
        s.setFloatUniform("uLit", look.lit)
        s.setFloatUniform("uHeat", look.heat)
        s.setFloatUniform("uSeam", look.seam)
        s.setFloatUniform("uRim", look.rim)
        s.setFloatUniform("uGlow", look.glowPx)
        s.setFloatUniform("uLight", if (palette.isLight) 1f else 0f)
        s.setFloatUniform("uTune", look.grain, look.emboss, if (look.squared) 1f else 0f)
        s.setColorUniform("uFace", palette.face.toArgb())
        s.setColorUniform("uEmit", look.emit.toArgb())
        s.setColorUniform("uRimC", palette.rim.toArgb())
        s.setColorUniform("uInk", palette.ink.toArgb())

        // Only the slab's bounding rectangle plus the halo's reach, and never outside the hero.
        val margin = look.glowPx + 1f
        val l = max(0f, frame.left - margin)
        val t = max(0f, frame.top - margin)
        val r = min(size.width, frame.right + margin)
        val b = min(size.height, frame.bottom + margin)
        if (r <= l || b <= t) return
        repeat(look.passes) { drawRect(brush, Offset(l, t), Size(r - l, b - t)) }
    }

    companion object {
        /** Every uniform the source declares, for the compile test. */
        val FLOAT_UNIFORMS = listOf(
            "uC", "uF", "uRo", "uV2O", "uH", "uL", "uEdge", "uBevel", "uGrid", "uLit", "uHeat", "uSeam", "uRim",
            "uGlow", "uLight", "uTune",
        )
        val COLOR_UNIFORMS = listOf("uFace", "uEmit", "uRimC", "uInk")
    }
}
