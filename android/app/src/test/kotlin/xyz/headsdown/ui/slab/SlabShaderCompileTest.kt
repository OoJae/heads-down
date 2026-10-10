package xyz.headsdown.ui.slab

import android.app.Application
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.RuntimeShader
import android.graphics.Shader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The AGSL source through a real Skia compiler (Robolectric's native graphics): it compiles, and
 * every uniform the renderer sets exists with the size the renderer gives it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class SlabShaderCompileTest {

    /**
     * Robolectric 4.17's Skia is older than Android 13's, older than AGSL itself: it refuses
     * `layout(color)` and spells a child shader's sample `sample(child, xy)` where AGSL says
     * `child.eval(xy)`. Every device that has `RuntimeShader` accepts both (the lab shows this
     * very source on an Android 14 emulator). So the source is compiled here with those two
     * spellings put in the old dialect, which leaves every other line, every uniform's name and
     * every uniform's size to a real compiler.
     */
    private val withoutColorLayout = SLAB_AGSL
        .replace("layout(color) uniform half4", "uniform half4")
        .replace("uGrain.eval(", "sample(uGrain, ")

    @Ignore(
        "Robolectric 4.17's native Skia predates AGSL: it rejects the layout(color) qualifier ('color' is not a " +
            "valid layout qualifier) and child.eval() ('cannot swizzle value of type shader'), both valid from " +
            "API 33. The same source compiles on device; the test below compiles it here in the old dialect.",
    )
    @Test
    fun `the exact source compiles and takes its colours as colours`() {
        val shader = RuntimeShader(SLAB_AGSL)
        ShaderSlabDrawer.COLOR_UNIFORMS.forEach { shader.setColorUniform(it, Color.YELLOW) }
    }

    @Test
    fun `the source compiles and takes every uniform by name`() {
        assertEquals(4, Regex("layout\\(color\\) uniform half4").findAll(SLAB_AGSL).count())
        assertEquals(1, Regex("uGrain\\.eval\\(").findAll(SLAB_AGSL).count())
        val shader = RuntimeShader(withoutColorLayout)
        shader.setFloatUniform("uC", 360f, 500f)
        shader.setFloatUniform("uF", 1_800f)
        shader.setFloatUniform("uRo", floatArrayOf(0f, -3.97f, 2.11f))
        shader.setFloatUniform("uV2O", FloatArray(9) { if (it % 4 == 0) 1f else 0f })
        shader.setFloatUniform("uH", SlabGeometry.HALF_X, SlabGeometry.HALF_Y, SlabGeometry.HALF_Z)
        shader.setFloatUniform("uL", floatArrayOf(-0.3f, 0.8f, 0.52f))
        shader.setFloatUniform("uEdge", FloatArray(18))
        shader.setFloatUniform("uBevel", SlabGeometry.BEVEL)
        shader.setFloatUniform("uGrid", SlabGeometry.GRID)
        shader.setFloatUniform("uLit", 3f)
        shader.setFloatUniform("uHeat", 1f)
        shader.setFloatUniform("uSeam", 1f)
        shader.setFloatUniform("uRim", 0.2f)
        shader.setFloatUniform("uGlow", 96f)
        shader.setFloatUniform("uLight", 0f)
        shader.setFloatUniform("uTune", 1f, 1f, 1f)
        // Colours: half4 here, since the qualifier that makes them colours is the part taken off.
        ShaderSlabDrawer.COLOR_UNIFORMS.forEach { shader.setFloatUniform(it, 0.5f, 0.4f, 0.1f, 1f) }
        shader.setInputShader("uGrain", BitmapShader(SlabGrain.bitmap(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT))

        // The lists the renderer documents are the ones set above: nothing was left out.
        assertEquals(16, ShaderSlabDrawer.FLOAT_UNIFORMS.size)
        assertEquals(listOf("uFace", "uEmit", "uRimC", "uInk"), ShaderSlabDrawer.COLOR_UNIFORMS)
        for (name in ShaderSlabDrawer.FLOAT_UNIFORMS + ShaderSlabDrawer.COLOR_UNIFORMS) {
            assertTrue("$name is not declared", Regex("uniform [a-z0-9]+ $name(\\[6])?;").containsMatchIn(withoutColorLayout))
        }
    }

    @Test
    fun `the uniforms the renderer sets are exactly the ones the source declares`() {
        // Robolectric's shadow does not refuse an unknown uniform's name the way a device does, so
        // the names are checked against the declarations themselves: none missing, none extra
        // (and so no time uniform: a still phone has nothing to redraw for).
        val declared = Regex("uniform (\\w+) (\\w+)(\\[6])?;").findAll(SLAB_AGSL).map { it.groupValues[2] }.toSet()
        assertEquals((ShaderSlabDrawer.FLOAT_UNIFORMS + ShaderSlabDrawer.COLOR_UNIFORMS + "uGrain").toSet(), declared)
        assertEquals(21, declared.size)
        // A source that does not compile is refused here too, so the test above does mean something.
        try {
            RuntimeShader(withoutColorLayout.replace("float cov = ", "float cov = undeclared + "))
            fail("a source with an undeclared name compiled")
        } catch (_: IllegalArgumentException) {
            // As it should be.
        }
    }

    @Test
    fun `a source the compiler refuses costs the shader, never the screen`() {
        // Here the compiler does refuse it (see above), which is exactly the case to survive:
        // asking for the shader must hand back a renderer, not an exception.
        val before = SlabProbe.shadersBuilt
        val drawer = SlabDrawers.create(SlabRenderer.Shader)
        assertTrue(drawer is ShaderSlabDrawer || drawer is PolygonSlabDrawer)
        if (drawer is PolygonSlabDrawer) assertEquals("a refused shader was counted as built", before, SlabProbe.shadersBuilt)
        // Asking for polygons never touches the shader.
        val built = SlabProbe.shadersBuilt
        assertTrue(SlabDrawers.create(SlabRenderer.Polygon) is PolygonSlabDrawer)
        assertEquals(built, SlabProbe.shadersBuilt)
    }

    @Test
    fun `the source has no time, no derivative and no colour literal`() {
        assertTrue("uTime" !in SLAB_AGSL && "iTime" !in SLAB_AGSL)
        assertTrue("dFdx" !in SLAB_AGSL && "fwidth" !in SLAB_AGSL)
        // Colours come in as layout(color) uniforms; coordinates are never half.
        assertEquals(4, Regex("layout\\(color\\) uniform half4").findAll(SLAB_AGSL).count())
        assertTrue(Regex("half[234]? +(p|rd|q|t|d|uC|uRo|uEdge)\\b").find(SLAB_AGSL) == null)
        assertTrue("#" !in SLAB_AGSL)
    }

    @Test
    fun `the grain is the same 64 by 64 every time`() {
        val a = SlabGrain.bitmap()
        val b = SlabGrain.bitmap()
        assertEquals(64, a.width)
        assertEquals(64, a.height)
        assertTrue(a.sameAs(b))
        // Grey, opaque, and neither flat nor clipped.
        val pixels = IntArray(64 * 64)
        a.getPixels(pixels, 0, 64, 0, 0, 64, 64)
        val values = pixels.map { it and 0xFF }
        assertTrue(pixels.all { it ushr 24 == 0xFF && (it shr 16 and 0xFF) == (it and 0xFF) })
        assertTrue(values.average() in 118.0..138.0)
        assertTrue(values.max() - values.min() > 150)
    }
}
