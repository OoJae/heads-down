package xyz.headsdown.core.design

import android.animation.ValueAnimator
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.headsdown.core.design.components.BarButton
import xyz.headsdown.core.design.components.DisplayText
import xyz.headsdown.core.design.components.HeroNumerals
import xyz.headsdown.core.design.components.Label

/** The theme in a composition: the tokens, the Material bridge, the system's motion setting. */
@RunWith(RobolectricTestRunner::class)
class ThemeTest {
    @get:Rule val rule = createComposeRule()

    @After
    fun animatorsBackOn() = setAnimatorScale(1f)

    /** The scale behind the system's "Remove animations" setting (`ValueAnimator.areAnimatorsEnabled`). */
    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    @Test
    fun `the theme is dark by default, and provides the tokens and a Material theme made of them`() {
        var colors: HdColorScheme? = null
        var primary: Color? = null
        var content: Color? = null
        var bodySmall = 0.sp
        var plate = false
        var type: HdType? = null
        rule.setContent {
            HeadsDownTheme {
                colors = Hd.colors
                type = Hd.type
                primary = MaterialTheme.colorScheme.primary
                content = LocalContentColor.current
                bodySmall = MaterialTheme.typography.bodySmall.fontSize
                plate = MaterialTheme.shapes.medium == Hd.shapes.plate
            }
        }
        rule.waitForIdle()
        assertEquals(HdPalette.Dark, colors)
        assertEquals(HdType.Default, type)
        assertEquals(HdPalette.Dark.chalk, primary)
        // Text outside any Surface is ink, not Material's default black.
        assertEquals(HdPalette.Dark.chalk, content)
        assertEquals(16.sp, bodySmall)
        assertTrue(plate)
    }

    @Test
    fun `the light palette is complete and switches with one flag`() {
        var dark by mutableStateOf(true)
        val seen = mutableListOf<Triple<HdColorScheme, Color, Color>>()
        rule.setContent {
            HeadsDownTheme(darkTheme = dark) {
                seen += Triple(Hd.colors, MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.tertiary)
            }
        }
        rule.waitForIdle()
        dark = false
        rule.waitForIdle()
        assertEquals(Triple(HdPalette.Dark, HdPalette.Dark.pit, HdPalette.Dark.seam), seen.first())
        assertEquals(Triple(HdPalette.Light, HdPalette.Light.pit, HdPalette.Light.seam), seen.last())
    }

    @Test
    fun `a Material outlined button is given an outline that can be seen`() {
        var dark by mutableStateOf(true)
        val seen = mutableListOf<Triple<BorderStroke, BorderStroke, Color>>()
        rule.setContent {
            HeadsDownTheme(darkTheme = dark) {
                seen += Triple(HdMaterial.controlBorder(), HdMaterial.controlBorder(enabled = false), MaterialTheme.colorScheme.outlineVariant)
            }
        }
        rule.waitForIdle()
        dark = false
        rule.waitForIdle()
        for ((palette, triple) in listOf(HdPalette.Dark to seen.first(), HdPalette.Light to seen.last())) {
            val (enabled, disabled, materialDefault) = triple
            // What Material 3 would draw the border with: the hairline, which is decoration.
            assertEquals(palette.hairline, materialDefault)
            assertEquals(BorderStroke(1.dp, palette.ash), enabled)
            assertEquals(BorderStroke(1.dp, palette.hairline), disabled)
        }
    }

    @Test
    fun `a token can be pinned from outside the theme`() {
        var reduced: Boolean? = null
        var pit: Color? = null
        rule.setContent {
            CompositionLocalProvider(LocalHdMotion provides HdMotion(reduced = true), LocalHdColors provides HdPalette.Light) {
                reduced = Hd.motion.reduced
                pit = Hd.colors.pit
            }
        }
        rule.waitForIdle()
        assertEquals(true, reduced)
        assertEquals(HdPalette.Light.pit, pit)
    }

    @Test
    fun `motion follows the system's Remove animations setting`() {
        assertFalse("animators are on in a fresh test", HdMotion.systemReduced())
        setAnimatorScale(0f)
        assertTrue(HdMotion.systemReduced())

        var reduced: Boolean? = null
        var clicks = 0
        rule.setContent {
            HeadsDownTheme {
                reduced = Hd.motion.reduced
                BarButton("Clock in", onClick = { clicks++ }, modifier = Modifier.testTag("bar"), seam = true)
            }
        }
        rule.waitForIdle()
        assertEquals(true, reduced)
        // Pressed with motion reduced: it dims in one step, and the click still lands.
        rule.onNodeWithTag("bar").performClick()
        rule.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun `text in all three faces composes in the default graphics mode, in both palettes`() {
        // Every screen test in the app composes these fonts the same way: from res/font, under
        // Robolectric's default (legacy) graphics.
        var dark by mutableStateOf(true)
        rule.setContent {
            HeadsDownTheme(darkTheme = dark) {
                androidx.compose.foundation.layout.Column {
                    DisplayText("Rig hot", Modifier.testTag("display"))
                    HeroNumerals("0.0194", Modifier.testTag("hero"))
                    Label("Powered by ORE", Modifier.testTag("mono"))
                    Text("Your phone's night shift", Modifier.testTag("material-title"), style = MaterialTheme.typography.headlineSmall)
                    Text("Signing a heartbeat every ORE round.", Modifier.testTag("material-body"))
                }
            }
        }
        for (pass in 0..1) {
            rule.onNodeWithTag("display").assertIsDisplayed().assertTextEquals("Rig hot")
            rule.onNodeWithTag("hero").assertIsDisplayed().assertTextEquals("0.0194")
            rule.onNodeWithTag("mono").assertIsDisplayed().assertTextEquals("Powered by ORE")
            rule.onNodeWithTag("material-title").assertIsDisplayed()
            rule.onNodeWithTag("material-body").assertIsDisplayed()
            dark = false
            rule.waitForIdle()
        }
    }
}
