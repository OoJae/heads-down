package xyz.headsdown.nights

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.headsdown.ui.HomeTags
import xyz.headsdown.ui.ScreenTest
import xyz.headsdown.ui.Screens

/** The home screen's one link to the night log: where it is, when it is there, and what it opens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class AllNightsLinkTest : ScreenTest() {

    @Test
    fun `home offers All nights under the last haul once a rig is bound, and only then`() {
        var onboarding by mutableStateOf(Screens.setUp)
        show(bars = true) { Screens.Home(Screens.haul, onboarding = onboarding) }

        val link = rule.onNodeWithTag(NightsTags.HOME_LINK).performScrollTo().assertTextEquals("All nights").assertHasClickAction()
        rule.onAllNodesWithText("All nights").assertCountEquals(1)
        // Inside the haul's plate, under the amount and the streak.
        val plate = rule.onNodeWithTag(HomeTags.HAUL).getUnclippedBoundsInRoot()
        val streak = rule.onNodeWithText("Streak 3 nights", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val bounds = link.getUnclippedBoundsInRoot()
        assertTrue("in the plate: $bounds in $plate", bounds.top >= plate.top && bounds.bottom <= plate.bottom && bounds.left >= plate.left)
        assertTrue("under the streak: $bounds under $streak", bounds.top >= streak.bottom)

        // It opens the night log with an explicit intent, and hands it nothing.
        link.performClick()
        val started = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertEquals(NightsActivity::class.java.name, started.component?.className)
        assertNull(started.extras)
        assertNull(started.data)

        // No clock-in has confirmed on this phone: there is no rig whose nights could be listed.
        onboarding = Screens.setUp.copy(rigRegistered = false)
        rule.waitForIdle()
        rule.onAllNodesWithTag(NightsTags.HOME_LINK).assertCountEquals(0)
        rule.onAllNodesWithText("All nights").assertCountEquals(0)
    }
}
