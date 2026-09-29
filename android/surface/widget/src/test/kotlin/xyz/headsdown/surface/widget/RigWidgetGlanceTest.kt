package xyz.headsdown.surface.widget

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasClickAction
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Glance trees the widgets emit, on Robolectric (the chronometer needs a Context). */
@RunWith(RobolectricTestRunner::class)
class RigWidgetGlanceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val open = actionStartActivity(Intent(Intent.ACTION_MAIN).setPackage("xyz.headsdown"))

    @Test
    fun `hot rig shows heat, haul, streak and opens the app`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(RigWidget.STANDARD)
        provideComposable {
            GlanceTheme(colors = WidgetColors.brand) {
                RigWidgetContent(RigWidgetCopy.render(RigWidget.PREVIEW_STATE), WidgetColorMode.BRAND, open)
            }
        }
        onNode(hasText("Rig hot")).assertExists()
        onNode(hasText("Dark for")).assertExists()
        onNode(hasText("Last haul 0.0194 ORE")).assertExists()
        onNode(hasText("Streak 23 nights")).assertExists()
        onNode(hasText("HEADS DOWN")).assertExists()
        onNode(hasClickAction()).assertExists()
        onNode(hasContentDescription("Heads Down. Rig hot.")).assertExists()
    }

    @Test
    fun `compact size keeps heat and status on one line`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(RigWidget.COMPACT)
        provideComposable {
            GlanceTheme(colors = WidgetColors.brand) {
                RigWidgetContent(RigWidgetCopy.render(RigWidgetState(lastShiftRounds = 312)), WidgetColorMode.BRAND, open)
            }
        }
        onNode(hasText("Rig cold")).assertExists()
        onNode(hasText("Last shift: 312 rounds dark")).assertExists()
        onNode(hasText("HEADS DOWN")).assertDoesNotExist()
        onNode(hasText("No haul yet")).assertDoesNotExist()
    }

    @Test
    fun `dynamic colour mode renders the same content`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(RigWidget.WIDE)
        provideComposable {
            GlanceTheme(colors = WidgetColors.providers(WidgetColorMode.DYNAMIC)) {
                RigWidgetContent(
                    RigWidgetCopy.render(RigWidgetState(rig = WidgetRig(RigHeat.FROZEN))),
                    WidgetColorMode.DYNAMIC,
                    open,
                )
            }
        }
        onNode(hasText("Frozen")).assertExists()
        onNode(hasText("No digs until you unfreeze it")).assertExists()
    }

    @Test
    fun `crew widget is an honest placeholder`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(CrewWidget.SIZE)
        provideComposable {
            GlanceTheme(colors = WidgetColors.brand) { CrewPlaceholderContent(WidgetColorMode.BRAND, open = null) }
        }
        onNode(hasText(CrewPlaceholderCopy.TAG)).assertExists()
        onNode(hasText("Rooms are not live yet", ignoreCase = true)).assertExists()
        onNode(hasContentDescription("placeholder")).assertExists()
        onAllNodes(hasClickAction()).assertCountEquals(0)
    }
}
