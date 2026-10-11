package xyz.headsdown.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.headsdown.feature.reveal.haul.HonestCopy
import xyz.headsdown.ui.setup.SetupTags

/**
 * A still screen draws nothing, and says nothing it should not. Every state of the home screen,
 * of setup and of the introduction is composed, the clock is run by hand for two seconds, and
 * after it no frame is being waited for and no state is written; then everything the state says,
 * to the eye or to a screen reader, is run through the banned-word check.
 *
 * The clock is held, because with `autoAdvance` the test rule quietly cancels an animation that
 * never ends instead of showing it (see AtRestTest in :core:design).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class ScreensAtRestTest : ScreenTest() {

    private fun pendingWork(): Boolean = Recomposer.runningRecomposers.value.any { it.hasPendingWork }

    private fun advance(millis: Long) {
        rule.mainClock.advanceTimeBy(millis)
        rule.waitForIdle()
    }

    private fun writesDuring(millis: Long): Int {
        var writes = 0
        val handle = Snapshot.registerGlobalWriteObserver { writes++ }
        try {
            advance(millis)
        } finally {
            handle.dispose()
        }
        return writes
    }

    /** Composes each of [names] in turn in one composition and checks it at rest. */
    private fun assertEachAtRest(names: List<String>, screen: @Composable (Int) -> Unit) {
        var shown by mutableIntStateOf(0)
        rule.mainClock.autoAdvance = false
        show { key(shown) { screen(shown) } }
        for (i in names.indices) {
            shown = i
            // The clock is held: let the change reach the composition before the frames run.
            rule.waitForIdle()
            advance(2_000)
            assertFalse("${names[i]}: something is still waiting for a frame", pendingWork())
            assertEquals("${names[i]}: state written at rest", 0, writesDuring(2_000))
            assertFalse("${names[i]}: pending after four seconds", pendingWork())
            val texts = spokenTexts()
            assertTrue("${names[i]}: the screen was read: $texts", texts.size > 6)
            for (text in texts) assertTrue("${names[i]}: banned words in \"$text\"", HonestCopy.violations(text).isEmpty())
        }
    }

    @Test
    fun `every state of the home screen is still, and honest`() {
        val cases = Screens.homeCases
        assertEquals("cold, armed, hot, cooling, broken, frozen, and what can be wrong or missing", 11, cases.size)
        assertEachAtRest(cases.map { "home, ${it.name}" }) { Screens.Home(cases[it]) }
    }

    @Test
    fun `every state of setup is still, and honest`() {
        val cases = Screens.setupCases
        assertEachAtRest(cases.map { "setup, ${it.name}" }) { Screens.Setup(cases[it]) }
    }

    @Test
    fun `the introduction is still, and honest, under both of its labels`() {
        val labels = listOf(NightShiftCopy.CONTINUE, "Back")
        assertEachAtRest(labels.map { "introduction, \"$it\"" }) { NightShiftIntro(onContinue = {}, continueLabel = labels[it]) }
    }

    @Test
    fun `a hot rig is still between two ticks of its clock`() {
        // "Dark for" is the one thing on the home screen that changes by itself: once every half
        // minute, and only while the rig is hot or cooling.
        rule.mainClock.autoAdvance = false
        show { Screens.Home(Screens.down) }
        advance(2_000)
        assertFalse(pendingWork())
        assertEquals("nothing is written for 25 seconds", 0, writesDuring(25_000))
        assertTrue("then the clock ticks once", writesDuring(5_000) >= 1)
        advance(100)
        assertFalse("and the screen is still again", pendingWork())
        assertEquals(0, writesDuring(20_000))
    }

    @Test
    fun `a broken shift's clock has stopped - no tick, however long the screen stays open`() {
        rule.mainClock.autoAdvance = false
        show { Screens.Home(Screens.broken) }
        advance(2_000)
        assertEquals(0, writesDuring(120_000))
        assertFalse(pendingWork())
        rule.onAllNodesWithTag(HomeTags.SHIFT_CLOCK).fetchSemanticsNodes().let { nodes ->
            // What is in the clock's place is the next shift's end, not a count.
            assertEquals(1, nodes.size)
            assertEquals(listOf(HomeCopy.RUNS_UNTIL, "06:28"), nodes.single().config.getOrNull(SemanticsProperties.Text)?.map { it.text })
        }
    }

    @Test
    fun `asking before a freeze comes to rest, takes one more tap, and leaves no second End shift or Freeze`() {
        var frozen = 0
        rule.mainClock.autoAdvance = false
        show { Screens.Home(Screens.down, onFreeze = { frozen++ }) }
        advance(500)
        rule.onAllNodesWithTag(HomeTags.FREEZE_CONFIRM).fetchSemanticsNodes().let { assertEquals(0, it.size) }

        rule.onNodeWithTag(HomeTags.FREEZE).assertTextEquals("Freeze").performClick()
        rule.waitForIdle()
        advance(2_000)
        assertEquals("the first tap only asks", 0, frozen)
        assertFalse("the question has come to rest", pendingWork())
        assertEquals(0, writesDuring(2_000))
        rule.onNodeWithText(HomeCopy.FREEZE_ASK).assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        // The two bars of the question say what they do; the dock's own two are still the only
        // nodes that read "End shift" and "Freeze".
        rule.onNodeWithTag(HomeTags.FREEZE_YES).assertTextEquals("Freeze the rig").assertIsDisplayed()
        rule.onNodeWithTag(HomeTags.FREEZE_KEEP).assertTextEquals("Keep the shift").assertIsDisplayed()
        rule.onNodeWithText("Freeze").assertExists()
        rule.onNodeWithText("End shift").assertExists()
        for (text in spokenTexts()) assertTrue("banned words in \"$text\"", HonestCopy.violations(text).isEmpty())

        // "Keep the shift" closes it, and nothing was frozen.
        rule.onNodeWithTag(HomeTags.FREEZE_KEEP).performClick()
        rule.waitForIdle()
        advance(2_000)
        rule.onAllNodesWithTag(HomeTags.FREEZE_CONFIRM).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        assertEquals(0, frozen)
        assertFalse(pendingWork())

        // Asked again and confirmed: frozen once.
        rule.onNodeWithTag(HomeTags.FREEZE).performClick()
        rule.waitForIdle()
        advance(2_000)
        rule.onNodeWithTag(HomeTags.FREEZE_YES).performClick()
        rule.waitForIdle()
        advance(2_000)
        assertEquals(1, frozen)
        rule.onAllNodesWithTag(HomeTags.FREEZE_CONFIRM).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        assertFalse(pendingWork())
    }

    @Test
    fun `Back closes the freeze question like the sheet it looks like, and keeps the shift`() {
        var frozen = 0
        var back: androidx.activity.OnBackPressedDispatcher? = null
        show {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            Screens.Home(Screens.armed, onFreeze = { frozen++ })
        }
        val dispatcher = checkNotNull(back)
        assertFalse("the home screen itself takes no Back", dispatcher.hasEnabledCallbacks())
        rule.onNodeWithTag(HomeTags.FREEZE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(HomeTags.FREEZE_CONFIRM).assertIsDisplayed()
        assertTrue(dispatcher.hasEnabledCallbacks())

        rule.runOnUiThread { dispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onAllNodesWithTag(HomeTags.FREEZE_CONFIRM).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        assertEquals("nothing was frozen", 0, frozen)
        assertFalse("and the next Back leaves the app as it always did", dispatcher.hasEnabledCallbacks())
    }

    @Test
    fun `turning the page to the steps comes to rest`() {
        var state by androidx.compose.runtime.mutableStateOf(OnboardingState())
        rule.mainClock.autoAdvance = false
        show { xyz.headsdown.ui.setup.SetupContent(state, xyz.headsdown.ui.setup.SetupActions(onMarkIntroSeen = { state = state.copy(introSeen = true) })) }
        advance(500)
        rule.onNodeWithTag(INTRO_CONTINUE_TAG).performClick()
        rule.waitForIdle()
        // The page is on its way to the steps for a moment, on the settle spring.
        advance(32)
        assertTrue("the page is moving", pendingWork())
        advance(3_000)
        assertFalse("and has arrived", pendingWork())
        assertEquals(0, writesDuring(2_000))
        rule.onNodeWithTag(SetupTags.PRIMARY).assertTextEquals("Allow notifications")
        rule.onNodeWithText("Set up your rig").assertIsDisplayed()
        rule.onNodeWithText("Notifications", useUnmergedTree = true).assertIsDisplayed()
    }
}
