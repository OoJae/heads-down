package xyz.headsdown.ui.slab

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** States to numbers, the policy's truth table, and the scroll mapping. */
class SlabLookTest {

    @Test
    fun `each state settles where the design says`() {
        for (state in listOf(SlabState.Cold, SlabState.Broken)) {
            assertEquals(SlabTarget(lit = 0f, heat = 0f, seam = 0f, rim = 0.15f, emission = SlabEmission.Gold), SlabTarget.of(state, 5))
        }
        assertEquals(SlabTarget(0f, 0f, 1f, 0.15f, SlabEmission.Ember), SlabTarget.of(SlabState.Armed, 0))
        assertEquals(SlabTarget(5f, 1f, 1f, 0.2f, SlabEmission.Gold), SlabTarget.of(SlabState.Hot, 5))
        assertEquals(SlabTarget(3f, 1f, 1f, 0.2f, SlabEmission.Cooling), SlabTarget.of(SlabState.Cooling, 3))
        assertEquals(SlabTarget(0f, 0f, 0f, 1f, SlabEmission.Chalk), SlabTarget.of(SlabState.Frozen, 5))
    }

    @Test
    fun `the state caps the rows whatever is asked`() {
        for (heat in listOf(-3, 0, 2, 5, 99)) {
            assertEquals(0f, SlabTarget.of(SlabState.Cold, heat).lit, 0f)
            assertEquals(0f, SlabTarget.of(SlabState.Broken, heat).lit, 0f)
            assertEquals(0f, SlabTarget.of(SlabState.Frozen, heat).lit, 0f)
            assertTrue(SlabTarget.of(SlabState.Armed, heat).lit <= 3f)
            assertTrue(SlabTarget.of(SlabState.Cooling, heat).lit <= 3f)
            assertTrue(SlabTarget.of(SlabState.Hot, heat).lit in 0f..5f)
        }
        assertEquals(2f, SlabTarget.of(SlabState.Hot, 2).lit, 0f)
        assertEquals(5f, SlabTarget.of(SlabState.Hot, 99).lit, 0f)
    }

    @Test
    fun `only a hot or cooling slab is ever gold`() {
        val palette = SlabPalette.Dark
        // Unlit, the emission only tints the relief: bronze for a cold rig, chalk for a frozen one.
        assertEquals(SlabEmission.Gold, SlabTarget.of(SlabState.Cold, 0).emission)
        assertEquals(0f, SlabTarget.of(SlabState.Cold, 5).lit, 0f)
        assertEquals(palette.emitEmber, SlabLook.emission(SlabEmission.Ember, 0f, palette))
        assertEquals(palette.emitEmber, SlabLook.emission(SlabEmission.Ember, 1f, palette))
        assertEquals(palette.rim, SlabLook.emission(SlabEmission.Chalk, 0f, palette))
        assertEquals(palette.emitGold, SlabLook.emission(SlabEmission.Gold, 0f, palette))
        // Cooling: gold while the heat is whole, ember where the grace ends, between in between.
        assertEquals(palette.emitGold, SlabLook.emission(SlabEmission.Cooling, 1f, palette))
        assertEquals(palette.emitEmber, SlabLook.emission(SlabEmission.Cooling, SlabTarget.COOLED, palette))
        val midway = SlabLook.emission(SlabEmission.Cooling, 0.675f, palette)
        assertNotEquals(palette.emitGold, midway)
        assertNotEquals(palette.emitEmber, midway)
        assertTrue(midway.green < palette.emitGold.green && midway.green > palette.emitEmber.green)
    }

    @Test
    fun `an armed slab lights at most three rows as it turns over, none before the underside shows`() {
        assertEquals(0f, SlabTarget.armedFlipRows(SlabGeometry.REST_DEGREES), 0f)
        assertEquals(0f, SlabTarget.armedFlipRows(90f), 0f)
        assertEquals(3f, SlabTarget.armedFlipRows(TiltMapper.MAX_LEAN_DEGREES), 0f)
        assertEquals(3f, SlabTarget.armedFlipRows(400f), 0f)
        var previous = 0f
        var lean = 0f
        while (lean <= 180f) {
            val rows = SlabTarget.armedFlipRows(lean)
            assertTrue(rows in 0f..SlabTarget.ARMED_ROWS.toFloat())
            assertTrue(rows >= previous)
            previous = rows
            lean += 1f
        }
        // Upright (lean 143 with the gain) already shows most of them.
        assertTrue(SlabTarget.armedFlipRows(143f) > 2.5f)
    }

    @Test
    fun `the look copies a target and resolves its colour`() {
        val look = SlabLook()
        look.set(SlabTarget.of(SlabState.Hot, 5), SlabPalette.Light)
        assertEquals(5f, look.lit, 0f)
        assertEquals(1f, look.heat, 0f)
        assertEquals(1f, look.seam, 0f)
        assertEquals(0.2f, look.rim, 0f)
        assertEquals(SlabPalette.Light.emitGold, look.emit)
        look.set(SlabTarget.of(SlabState.Frozen, 5), SlabPalette.Dark)
        assertEquals(SlabPalette.Dark.rim, look.emit)
        assertEquals(1f, look.rim, 0f)
    }

    @Test
    fun `the palettes are the brand's`() {
        assertEquals(Color(0xFF0C0C0D), SlabPalette.Dark.pit)
        assertEquals(Color(0xFF18181A), SlabPalette.Dark.face)
        assertEquals(Color(0xFFEFEAE0), SlabPalette.Dark.rim)
        assertEquals(Color(0xFFF1ECE2), SlabPalette.Light.pit)
        assertEquals(Color(0xFF151413), SlabPalette.Light.face)
        assertEquals(Color(0xFF151413), SlabPalette.Light.ink)
        for (palette in listOf(SlabPalette.Dark, SlabPalette.Light)) {
            assertEquals(Color(0xFFF2B233), palette.emitGold)
            assertEquals(Color(0xFFE8622A), palette.emitEmber)
        }
        assertTrue(SlabPalette.Light.isLight && !SlabPalette.Dark.isLight)
    }

    // ---- SlabPolicy ----

    @Test
    fun `policy truth table`() {
        for (sdk in listOf(31, 32, 33, 34, 36)) {
            for (bits in 0 until 16) {
                val animators = bits and 1 != 0
                val powerSave = bits and 2 != 0
                val robolectric = bits and 4 != 0
                val disabled = bits and 8 != 0
                val config = SlabPolicy.resolve(sdk, animators, powerSave, robolectric, disabled)
                val case = "sdk $sdk animators $animators powerSave $powerSave robolectric $robolectric disabled $disabled"
                val shader = sdk >= 33 && !robolectric && !disabled
                val live = sdk >= 33 && animators && !powerSave && !robolectric
                assertEquals(case, if (shader) SlabRenderer.Shader else SlabRenderer.Polygon, config.renderer)
                assertEquals(case, if (live) SlabMotion.Live else SlabMotion.Static, config.motion)
            }
        }
    }

    @Test
    fun `policy, the cases by name`() {
        // The Redmi 14C on Android 16, everything on: the shader, alive.
        assertEquals(SlabConfig(SlabRenderer.Shader, SlabMotion.Live), SlabPolicy.resolve(36, true, false, false, false))
        // "Remove animations" and battery saver: one still frame of the shader.
        assertEquals(SlabConfig(SlabRenderer.Shader, SlabMotion.Static), SlabPolicy.resolve(36, false, false, false, false))
        assertEquals(SlabConfig(SlabRenderer.Shader, SlabMotion.Static), SlabPolicy.resolve(36, true, true, false, false))
        // The kill switch: polygons, still alive.
        assertEquals(SlabConfig(SlabRenderer.Polygon, SlabMotion.Live), SlabPolicy.resolve(36, true, false, false, true))
        // Android 12 and 12L, and Robolectric at any level: inert.
        assertEquals(SlabConfig.Inert, SlabPolicy.resolve(31, true, false, false, false))
        assertEquals(SlabConfig.Inert, SlabPolicy.resolve(32, true, false, false, false))
        assertEquals(SlabConfig.Inert, SlabPolicy.resolve(34, true, false, true, false))
        assertEquals(SlabConfig(SlabRenderer.Polygon, SlabMotion.Static), SlabConfig.Inert)
    }

    // ---- SlabScroll ----

    @Test
    fun `scroll drops the camera, lets gravity go and shrinks the slab`() {
        assertEquals(0f, SlabScroll.progress(0f, 600f), 0f)
        assertEquals(0f, SlabScroll.progress(-40f, 600f), 0f)
        assertEquals(0.5f, SlabScroll.progress(300f, 600f), 0f)
        assertEquals(1f, SlabScroll.progress(5_000f, 600f), 0f)
        assertEquals(0f, SlabScroll.progress(100f, 0f), 0f)

        assertEquals(0f, SlabScroll.pitchDegrees(0f), 0f)
        assertEquals(70f, SlabScroll.pitchDegrees(0.6f), 1e-4f)
        assertEquals(70f, SlabScroll.pitchDegrees(1f), 1e-4f)
        assertEquals(1f, SlabScroll.gravityWeight(0f), 0f)
        assertEquals(1f, SlabScroll.gravityWeight(0.5f), 0f)
        assertEquals(0f, SlabScroll.gravityWeight(0.9f), 1e-6f)
        assertEquals(0f, SlabScroll.gravityWeight(1f), 0f)
        assertEquals(1f, SlabScroll.scale(0f), 0f)
        assertEquals(SlabScroll.SCALE_END, SlabScroll.scale(1f), 1e-6f)
        assertEquals(0f, SlabScroll.sink(0f), 0f)

        var p = 0f
        var pitch = 0f
        var gravity = 1f
        var scale = 1f
        while (p <= 1f) {
            assertTrue(SlabScroll.pitchDegrees(p) >= pitch)
            assertTrue(SlabScroll.gravityWeight(p) <= gravity)
            assertTrue(SlabScroll.scale(p) <= scale)
            pitch = SlabScroll.pitchDegrees(p)
            gravity = SlabScroll.gravityWeight(p)
            scale = SlabScroll.scale(p)
            p += 0.01f
        }
        // Rest plus the whole pitch is the underside, and inside the limit.
        assertTrue(SlabGeometry.REST_DEGREES + SlabScroll.PITCH_DEGREES > 120f)
        assertTrue(SlabGeometry.REST_DEGREES + SlabScroll.PITCH_DEGREES < SlabGeometry.MAX_TILT_DEGREES)
    }
}
