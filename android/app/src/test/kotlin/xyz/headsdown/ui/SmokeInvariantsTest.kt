package xyz.headsdown.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.headsdown.core.keys.KeySecurityLevel
import xyz.headsdown.feature.oemkeepalive.OemFamily
import xyz.headsdown.feature.oemkeepalive.OemProfile
import xyz.headsdown.rig.RigKeyStatus
import xyz.headsdown.ui.setup.SetupActions
import xyz.headsdown.ui.setup.SetupContent
import xyz.headsdown.ui.setup.SetupTags

/**
 * What `scripts/devstack/emulator-smoke.sh` relies on, as unit tests. The script drives the app
 * through uiautomator by the text it sees (`emulator/ui.py`): a node's text or else its content
 * description, the first match from the top, a plain match ignoring case, never scrolling back
 * up. It cannot be run while a phone is on a live shift, so each thing it assumes about these
 * two screens is pinned here, against the same 360dp phone with its bars.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class SmokeInvariantsTest : ScreenTest() {

    private val screen = 800.dp

    // ------------------------------------------------------------------ I1

    @Test
    fun `I1 - first launch offers Set up my rig, and after it the bar carries the next step under its own label`() {
        var state by mutableStateOf(OnboardingState())
        val taps = mutableListOf<String>()
        show(bars = true) {
            SetupContent(
                state,
                SetupActions(
                    onMarkIntroSeen = { taps += "intro"; state = state.copy(introSeen = true) },
                    onAllowNotifications = { taps += "notifications"; state = state.copy(notificationsGranted = true, exactAlarmsAllowed = true) },
                ),
            )
        }
        // `ui find "Set up my rig"`: there, without scrolling, once.
        assertTrue(uiHas("Set up my rig"))
        assertEquals(1, uiMatches("Set up my rig").size)
        assertFalse("the steps' actions are not offered yet", uiHas("Allow notifications"))
        rule.onNodeWithTag(INTRO_CONTINUE_TAG).assertTextEquals("Set up my rig").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(listOf("intro"), taps)

        // The introduction is read: the same bar now carries the first real step.
        assertFalse(uiHas("Set up my rig"))
        assertTrue(uiHas("Allow notifications"))
        assertEquals("one node says it: the script taps the first", 1, uiMatches("Allow notifications").size)
        rule.onNodeWithTag(SetupTags.PRIMARY).assertTextEquals("Allow notifications").assertIsDisplayed()

        // The script looks again a second or more later, and taps what the bar says then.
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag(SetupTags.PRIMARY).performClick()
        rule.waitForIdle()
        assertEquals(listOf("intro", "notifications"), taps)
        assertTrue(uiHas("Allow background running"))
        assertFalse(uiHas("Allow notifications"))
    }

    @Test
    fun `I1 - the script's whole walk through setup - each label it looks for is the bar, once, and its tap lands`() {
        // The emulator the script was written against: Android 14 (exact alarms are allowed from
        // the start, there is a tile prompt), a stock keep-alive with no Autostart step.
        var state by mutableStateOf(OnboardingState(exactAlarmsAllowed = true, oem = OemProfile(OemFamily.OTHER)))
        val did = mutableListOf<String>()
        val actions = SetupActions(
            onMarkIntroSeen = { did += "Set up my rig"; state = state.copy(introSeen = true) },
            onAllowNotifications = { did += "Allow notifications"; state = state.copy(notificationsGranted = true) },
            onOpenKeepAlive = { did += "Allow background running"; state = state.copy(batteryUnrestricted = true) },
            onAddTile = { did += "Add the tile"; state = state.copy(tileAdded = true) },
            onCreateRigKey = {
                did += "Create rig key"
                state = state.copy(rigKey = RigKeyStatus.Ready(KeySecurityLevel.SOFTWARE_OR_UNKNOWN, "ab", 0))
            },
        )
        show(bars = true) { SetupContent(state, actions) }
        // In the order of emulator-smoke.sh, section 1. It never taps "Allow exact alarm", "Skip
        // for now" or "Start using Heads Down": the walk has to end by itself.
        val script = listOf("Set up my rig", "Allow notifications", "Allow background running", "Add the tile", "Create rig key")
        for (label in script) {
            // The script sleeps two seconds or more between its steps.
            rule.mainClock.advanceTimeBy(2_000)
            rule.waitForIdle()
            assertFalse("$label: the script would think this is the home screen", uiHas("Rounds dark"))
            // `ui has` and `ui find`: on screen now, no scrolling. `ui tap`: the first match from the top.
            val seen = uiMatches(label).filter { it.visible }
            assertEquals("$label: ${uiTexts().map { it.text }}", 1, seen.size)
            val bar = rule.onNodeWithTag(if (label == script.first()) INTRO_CONTINUE_TAG else SetupTags.PRIMARY).fetchSemanticsNode().boundsInRoot
            assertTrue("$label is the bar's own label", bar.contains(seen.single().bounds.center))
            // The tap itself, where the script puts it: the middle of that text, through whatever is over it.
            rule.onRoot().performTouchInput { click(seen.single().bounds.center) }
            rule.waitForIdle()
            assertEquals("the tap on \"$label\" reached it", label, did.last())
        }
        assertEquals(script, did)
        assertTrue("every step is done: the root shows the home screen by itself", state.allDone)
        // The script then taps "Continue" if it sees one. Nothing here says it.
        assertFalse(uiTexts().any { "continue" in it.text.lowercase() })
    }

    @Test
    fun `I1 - Set up my rig is there from the first frame, before the phone's settings were read`() {
        show(bars = true) { SetupContent(OnboardingState(), SetupActions(), loading = true) }
        assertTrue(uiHas("Set up my rig"))
        assertEquals(1, uiMatches("Set up my rig").size)
        assertFalse(uiHas("Rounds dark"))
        rule.onNodeWithTag(INTRO_CONTINUE_TAG).assertTextEquals("Set up my rig").assertIsDisplayed()
    }

    // ------------------------------------------------------------------ I2

    @Test fun `I2 - first launch never says Rounds dark or continue`() = assertSetupSaysNeither(Screens.firstLaunch)

    @Test fun `I2 - the first step never says Rounds dark or continue`() = assertSetupSaysNeither(Screens.firstStep)

    @Test fun `I2 - the keep-alive guide never says Rounds dark or continue`() = assertSetupSaysNeither(Screens.keepAliveXiaomi)

    @Test fun `I2 - the tile added by hand never says Rounds dark or continue`() = assertSetupSaysNeither(Screens.tileByHand)

    @Test fun `I2 - a failed key never says Rounds dark or continue`() = assertSetupSaysNeither(Screens.keyFailed)

    @Test fun `I2 - setup when all is done never says Rounds dark or continue`() = assertSetupSaysNeither(Screens.allDone)

    @Test fun `I2 - setup while loading never says Rounds dark or continue`() = assertSetupSaysNeither(Screens.setupLoading)

    /**
     * The script decides it is on the home screen by seeing "Rounds dark", and taps anything that
     * says "Continue". Neither may be anywhere on the setup screen: not on screen and not below it.
     */
    private fun assertSetupSaysNeither(case: SetupCase) {
        show(bars = true) { Screens.Setup(case) }
        val texts = uiTexts().map { it.text }
        assertTrue("the screen was read: $texts", texts.size > 8)
        for (text in texts) {
            assertFalse("${case.name}: \"$text\"", "rounds dark" in text.lowercase())
            assertFalse("${case.name}: \"$text\"", "continue" in text.lowercase())
        }
        // Nor a text that is only "Allow": that is the system dialog's button, tapped by exact match.
        assertEquals(emptyList<UiText>(), uiMatches("Allow", exact = true))
    }

    // ------------------------------------------------------------------ I3

    @Test fun `I3 - idle home shows Rounds dark without scrolling`() = assertRoundsDarkOnScreen(Screens.idle)

    @Test fun `I3 - armed home shows Rounds dark without scrolling`() = assertRoundsDarkOnScreen(Screens.armed)

    @Test fun `I3 - hot home shows Rounds dark without scrolling`() = assertRoundsDarkOnScreen(Screens.down)

    @Test fun `I3 - broken home shows Rounds dark without scrolling`() = assertRoundsDarkOnScreen(Screens.broken)

    @Test fun `I3 - frozen home shows Rounds dark without scrolling`() = assertRoundsDarkOnScreen(Screens.frozen)

    @Test fun `I3 - home with a banner shows Rounds dark without scrolling`() = assertRoundsDarkOnScreen(Screens.banner)

    @Test fun `I3 - home while loading shows Rounds dark without scrolling`() = assertRoundsDarkOnScreen(Screens.loading)

    private fun assertRoundsDarkOnScreen(case: HomeCase) {
        show(bars = true) { Screens.Home(case) }
        assertTrue("${case.name}: `ui has \"Rounds dark\"`", uiHas("Rounds dark"))
        // Whole, above the dock: not a sliver of it.
        val rounds = rule.onNodeWithTag(HomeTags.ROUNDS_DARK).getUnclippedBoundsInRoot()
        assertTrue("${case.name}: $rounds", rounds.bottom <= rule.onNodeWithTag(HomeTags.DOCK).getUnclippedBoundsInRoot().top)
        // After setup the script taps "Continue" if it sees one: the home screen has none either.
        assertFalse(uiTexts().any { "continue" in it.text.lowercase() })
    }

    // ------------------------------------------------------------------ I4

    @Test fun `I4 - idle home has exactly one Clock in`() = assertOneClockIn(Screens.idle)

    @Test fun `I4 - broken home has exactly one Clock in`() = assertOneClockIn(Screens.broken)

    @Test fun `I4 - home with a banner and a haul has exactly one Clock in`() = assertOneClockIn(Screens.banner.copy(lastHaul = "0.0194 ORE", streak = "Streak 3 nights"))

    /** `ui tap "Clock in" exact`: one node on the whole page reads exactly that, and it is the bar. */
    private fun assertOneClockIn(case: HomeCase) {
        var clockIns = 0
        show(bars = true) { Screens.Home(case, onClockIn = { clockIns++ }) }
        val matches = uiMatches("Clock in", exact = true)
        assertEquals("${case.name}: $matches", 1, matches.size)
        assertTrue("on screen without scrolling", matches.single().visible)
        val bar = rule.onNodeWithTag(HomeTags.CLOCK_IN).fetchSemanticsNode().boundsInRoot
        assertTrue("it is the bar's own label", bar.contains(matches.single().bounds.center))
        rule.onNodeWithTag(HomeTags.CLOCK_IN).performClick()
        assertEquals(1, clockIns)
    }

    @Test
    fun `I4 - once armed the rig word reads exactly Armed, on screen where the tap left it`() {
        show(bars = true) { Screens.Home(Screens.armed) }
        // `ui has "Armed" exact`
        assertTrue(uiHas("Armed", exact = true))
        assertEquals(1, uiMatches("Armed", exact = true).size)
        rule.onNodeWithTag(HomeTags.RIG_WORD).assertTextEquals("Armed").assertIsDisplayed()
        // And the bar that was "Clock in" is gone, not left under the new ones.
        assertEquals(emptyList<UiText>(), uiMatches("Clock in", exact = true))
    }

    @Test
    fun `I4 - Armed is on screen after the clock-in wherever the page had been scrolled to`() {
        var shown by mutableStateOf(Screens.idle)
        show(bars = true) { Screens.Home(shown) }
        // Someone read the page to its end before tapping the bar, which is always in reach.
        rule.onNodeWithTag(HomeTags.RIG_DEBUG).performScrollTo()
        assertFalse(uiHas("Rig cold", exact = true))
        rule.onNodeWithTag(HomeTags.CLOCK_IN).assertIsDisplayed()

        // The clock-in is confirmed: the page goes back to the word that says so.
        shown = Screens.armed
        rule.waitForIdle()
        assertTrue(uiHas("Armed", exact = true))
        rule.onNodeWithTag(HomeTags.RIG_WORD).assertTextEquals("Armed").assertIsDisplayed()
        rule.onNodeWithText("Lay your phone face-down to start the shift.").assertIsDisplayed()
        assertTrue(uiHas("Rounds dark"))
    }

    @Test
    fun `a page is not pulled to its top when the word stays the same`() {
        var shown by mutableStateOf(Screens.broken)
        show(bars = true) { Screens.Home(shown) }
        rule.onNodeWithTag(HomeTags.RIG_DEBUG).performScrollTo().assertIsDisplayed()
        // A shift that broke and a cold rig are both "Rig cold": nothing the reader did, nothing moves.
        shown = Screens.idle
        rule.waitForIdle()
        rule.onNodeWithTag(HomeTags.RIG_DEBUG).assertIsDisplayed()
        assertFalse(uiHas("Rig cold", exact = true))
    }

    // ------------------------------------------------------------------ I5

    @Test
    fun `I5 - the way out is below the clock-out link, so a script that only scrolls down finds both`() {
        show(bars = true) { Screens.Home(Screens.broken) }
        // `ui tap "Clock out: seal the shift"`, then, further down, `ui tap "Take SOL back or close the rig"`.
        val clockOut = uiMatches("Clock out: seal the shift")
        val takeBack = uiMatches("Take SOL back or close the rig")
        assertEquals(1, clockOut.size)
        assertEquals(1, takeBack.size)
        val order = uiTexts().map { it.text }
        assertTrue("in the tree", order.indexOf(clockOut.single().text) < order.indexOf(takeBack.single().text))
        val clockOutTop = rule.onNodeWithTag(HomeTags.CLOCK_OUT).getUnclippedBoundsInRoot().top
        val takeBackTop = rule.onNodeWithTag(HomeTags.WITHDRAW).getUnclippedBoundsInRoot().top
        assertTrue("and on the page: $clockOutTop, $takeBackTop", takeBackTop > clockOutTop)
        // The debug entry the script opens by its first words comes last, and nothing before it matches.
        val debug = uiMatches("Rig key and devstack")
        assertEquals(1, debug.size)
        assertTrue(rule.onNodeWithTag(HomeTags.RIG_DEBUG).getUnclippedBoundsInRoot().top > takeBackTop)
    }

    @Test
    fun `the debug entry at the foot of the page is within the seven swipes the script makes`() {
        show(bars = true) { Screens.Home(Screens.idle.copy(lastHaul = "0.0194 ORE", streak = "Streak 3 nights")) }
        // `ui tap` looks, swipes up, and looks again, eight times: seven swipes. A swipe drags the
        // page from 78% to 33% of the screen's height; a fling carries it further, never less.
        val oneSwipe = screen * 0.45f - 16.dp
        val scroller = rule.onNode(SemanticsMatcher("the page") { (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 0f })
        val range = scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue().toDp()
        assertTrue("the page scrolls $range; seven swipes move it ${oneSwipe * 7}", range <= oneSwipe * 7)
    }

    // ------------------------------------------------------------------ I8

    @Test fun `I8 - idle - the dock stays under the line the script swipes from`() = assertDockBelowSwipe { Screens.Home(Screens.idle) }

    @Test fun `I8 - armed - the dock stays under the line the script swipes from`() = assertDockBelowSwipe { Screens.Home(Screens.armed) }

    @Test fun `I8 - frozen - the dock stays under the line the script swipes from`() = assertDockBelowSwipe { Screens.Home(Screens.frozen) }

    @Test fun `I8 - setup - the dock stays under the line the script swipes from`() =
        assertDockBelowSwipe(SetupTags.DOCK) { Screens.Setup(Screens.firstStep) }

    @Test fun `I8 - first launch - the dock stays under the line the script swipes from`() =
        assertDockBelowSwipe(SetupTags.DOCK) { Screens.Setup(Screens.firstLaunch) }

    /**
     * The script's swipe starts at 78% of the screen's height. A dock that reached up to there
     * would take the finger and the page would not move. One 56dp bar (and a line of caption)
     * keeps it well under, with a navigation bar below it.
     */
    private fun assertDockBelowSwipe(tag: String = HomeTags.DOCK, content: @androidx.compose.runtime.Composable () -> Unit) {
        show(bars = true, content = content)
        val dock = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertTrue("the dock starts at ${dock.top}; the swipe at ${screen * 0.78f}", dock.top > screen * 0.78f + 8.dp)
        assertTrue("one bar and its caption at most: ${dock.height}", dock.height <= 56.dp + 24.dp + 8.dp + 16.dp)
    }
}
