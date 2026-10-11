package xyz.headsdown.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.headsdown.core.keys.KeySecurityLevel
import xyz.headsdown.feature.oemkeepalive.KeepAliveStep
import xyz.headsdown.rig.RigKeyStatus
import xyz.headsdown.ui.setup.SetupActions
import xyz.headsdown.ui.setup.SetupContent
import xyz.headsdown.ui.setup.SetupCopy
import xyz.headsdown.ui.setup.SetupStep
import xyz.headsdown.ui.setup.SetupTags

/**
 * First launch and setup, composed without Hilt: the stateless `SetupContent` with a state the
 * test changes the way the view model would.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class SetupContentTest : ScreenTest() {

    private val ready = RigKeyStatus.Ready(KeySecurityLevel.TRUSTED_ENVIRONMENT, "3fa9c1d2", 3)

    /** What a screen reader says of a step's state: it is on the step's header, under the tagged row. */
    private fun status(step: Int): String? {
        fun find(node: SemanticsNode): String? =
            node.config.getOrNull(SemanticsProperties.StateDescription) ?: node.children.firstNotNullOfOrNull(::find)
        return find(rule.onNodeWithTag(SetupTags.STEP + step, useUnmergedTree = true).fetchSemanticsNode())
    }

    @Test
    fun `the dock's bar follows the state, one step after another, under each step's own label`() {
        var state by mutableStateOf(OnboardingState())
        val calls = mutableListOf<String>()
        val actions = SetupActions(
            onMarkIntroSeen = { calls += "intro"; state = state.copy(introSeen = true) },
            onAllowNotifications = { calls += "notifications"; state = state.copy(notificationsGranted = true) },
            onAllowExactAlarm = { calls += "alarm"; state = state.copy(exactAlarmsAllowed = true) },
            onOpenKeepAlive = { calls += "keep-alive:${it.name}"; state = state.copy(batteryUnrestricted = true) },
            onAddTile = { calls += "tile"; state = state.copy(tileAdded = true) },
            onCreateRigKey = { calls += "key"; state = state.copy(creatingKey = true) },
            onFinish = { calls += "finish" },
        )
        show { SetupContent(state, actions) }

        /** Taps the bar once it has rested after its last change, and lets the screen settle. */
        fun tapBar(tag: String = SetupTags.PRIMARY) {
            rule.mainClock.advanceTimeBy(400)
            rule.onNodeWithTag(tag).assertIsDisplayed().performClick()
            rule.waitForIdle()
        }

        rule.onNodeWithTag(INTRO_CONTINUE_TAG).assertTextEquals("Set up my rig").assertIsEnabled()
        rule.onAllNodesWithTag(SetupTags.PRIMARY).assertCountEquals(0)
        tapBar(INTRO_CONTINUE_TAG)

        val expected = listOf(
            "Allow notifications" to "notifications",
            "Allow exact alarm" to "alarm",
            "Allow background running" to "keep-alive:${KeepAliveStep.IGNORE_BATTERY_OPTIMIZATIONS.name}",
            "Add the tile" to "tile",
            "Create rig key" to "key",
        )
        for ((i, step) in expected.withIndex()) {
            val (label, call) = step
            rule.onNodeWithTag(SetupTags.PRIMARY).assertTextEquals(label)
            // Exactly one node says it, and the step it belongs to is the current one.
            rule.onAllNodesWithText(label).assertCountEquals(1)
            assertEquals("step ${i + 1} is current while the bar says \"$label\"", "Current step", status(i + 1))
            tapBar()
            assertEquals(call, calls.last())
        }
        assertEquals(listOf("intro") + expected.map { it.second }, calls)

        // The key is being created: the bar says so and cannot be tapped.
        rule.onNodeWithTag(SetupTags.PRIMARY).assertTextEquals("Creating the key…").assertIsNotEnabled()
        tapBar()
        assertEquals("a busy bar ignores the tap", "key", calls.last())

        // Created: everything is done, and the one bar leaves setup.
        state = state.copy(creatingKey = false, rigKey = ready)
        rule.waitForIdle()
        rule.onNodeWithTag(SetupTags.PRIMARY).assertTextEquals("Start using Heads Down").assertIsEnabled()
        for (step in 1..5) assertEquals("Done", status(step))
        rule.onNode(hasContentDescription("5 of 5 steps done")).assertExists()
        tapBar()
        assertEquals("finish", calls.last())
    }

    @Test
    fun `for a moment after its label changed the bar takes no tap`() {
        var state by mutableStateOf(OnboardingState())
        var notifications = 0
        show {
            SetupContent(state, SetupActions(onMarkIntroSeen = { state = state.copy(introSeen = true) }, onAllowNotifications = { notifications++ }))
        }
        // Frames by hand: the page turning to the steps must not carry the clock past the rest.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(INTRO_CONTINUE_TAG).performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.onNodeWithTag(SetupTags.PRIMARY).assertTextEquals("Allow notifications")
        // The second half of a double tap on "Set up my rig" lands on the new label, and does nothing.
        rule.onNodeWithTag(SetupTags.PRIMARY).performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(0, notifications)
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithTag(SetupTags.PRIMARY).performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(1, notifications)
    }

    @Test
    fun `first launch says what this is, and what it is not, before a single step`() {
        var howItWorks = 0
        show { Screens.Setup(Screens.firstLaunch, SetupActions(onHowItWorks = { howItWorks++ })) }
        val dock = rule.onNodeWithTag(SetupTags.DOCK).getUnclippedBoundsInRoot()
        // The statement, the lead and "This is not income…" are on the first screenful, over the bar.
        rule.onNodeWithTag(SetupTags.TURN).assertTextEquals("Turn it over.").assert(isHeading()).assertHasClickAction().assertIsDisplayed()
        rule.onNodeWithText(NightShiftCopy.LEAD).assertIsDisplayed()
        rule.onNodeWithText(NightShiftCopy.NOT_TITLE).assertIsDisplayed()
        val notIncome = rule.onNodeWithText(NightShiftCopy.NOT_BODY).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("\"This is not income\" ends above the dock: $notIncome, ${dock.top}", notIncome.bottom <= dock.top)
        assertTrue(NightShiftCopy.NOT_BODY.startsWith("This is not income."))
        // Nothing to skip before the introduction was read, and no step can be opened yet.
        rule.onAllNodesWithTag(SetupTags.SKIP).assertCountEquals(0)
        for (step in SetupStep.entries) {
            assertEquals("Not done yet", status(step.number))
            rule.onNodeWithText(SetupCopy.title(step)).assertHasNoClickAction()
        }
        // The hero: a large share of the screen, one image with one sentence.
        val hero = rule.onNodeWithTag(SetupTags.HERO)
        hero.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image)).assert(hasContentDescription(SetupCopy.HERO_FACE_DOWN))
        assertTrue(hero.getUnclippedBoundsInRoot().height > 200.dp)
        // The whole ritual is one link away.
        rule.onNodeWithTag(SetupTags.HOW_IT_WORKS).performScrollTo().assertTextEquals("How the night shift works").performClick()
        assertEquals(1, howItWorks)
    }

    @Test
    fun `tapping the heading turns the slab over, so nothing depends on tilting a phone`() {
        show { Screens.Setup(Screens.firstLaunch) }
        rule.onNodeWithTag(SetupTags.HERO).assert(hasContentDescription(SetupCopy.HERO_FACE_DOWN))
        rule.onNodeWithTag(SetupTags.TURN).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        rule.onNodeWithTag(SetupTags.HERO).assert(hasContentDescription(SetupCopy.HERO_TURNED))
        rule.onNodeWithTag(SetupTags.TURN).performClick()
        rule.onNodeWithTag(SetupTags.HERO).assert(hasContentDescription(SetupCopy.HERO_FACE_DOWN))
    }

    @Test
    fun `opened with the introduction read, the page is at the steps and the first open one is current`() {
        var skipped = 0
        show { Screens.Setup(Screens.keepAliveXiaomi, SetupActions(onFinish = { skipped++ })) }
        rule.onNodeWithText("Set up your rig").assert(isHeading())
        assertEquals(listOf("Done", "Done", "Current step", "Not done yet", "Not done yet"), (1..5).map(::status))
        rule.onNode(hasContentDescription("2 of 5 steps done")).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        // The current step is on screen, with its guide open under it; the bar carries its action.
        rule.onNodeWithText("Keep the shift alive", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("HyperOS closes background apps aggressively. These settings keep your shift running until morning.", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Turn on Autostart").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.PRIMARY).assertTextEquals("Allow background running")
        // The settings pages of the guide open from the guide; background running only from the bar.
        rule.onAllNodesWithText("Open settings").assertCountEquals(2)
        rule.onAllNodesWithText("Allow background running").assertCountEquals(1)
        rule.onAllNodesWithText("I turned Autostart on").assertCountEquals(1)
        // "Skip for now" is offered once there are steps, at the top of the page.
        rule.onNodeWithTag(SetupTags.SKIP).performScrollTo().assertTextEquals("Skip for now").performClick()
        assertEquals(1, skipped)
    }

    @Test
    fun `the guide's own actions reach the phone, and Autostart is never offered twice`() {
        var state by mutableStateOf(Screens.keepAliveXiaomi.state)
        val opened = mutableListOf<KeepAliveStep>()
        show {
            SetupContent(state, SetupActions(onOpenKeepAlive = { opened += it }, onConfirmAutostart = { state = state.copy(autostartConfirmed = true) }))
        }
        rule.onNodeWithTag(SetupTags.OPEN_SETTINGS + KeepAliveStep.AUTOSTART.name).performScrollTo().performClick()
        rule.onNodeWithTag(SetupTags.OPEN_SETTINGS + KeepAliveStep.BATTERY_NO_RESTRICTIONS.name).performScrollTo().performClick()
        assertEquals(listOf(KeepAliveStep.AUTOSTART, KeepAliveStep.BATTERY_NO_RESTRICTIONS), opened)

        // Background running allowed first: now the bar is the confirmation, and the link is gone.
        state = state.copy(batteryUnrestricted = true)
        rule.waitForIdle()
        rule.onNodeWithTag(SetupTags.PRIMARY).assertTextEquals("I turned Autostart on")
        rule.onAllNodesWithText("I turned Autostart on").assertCountEquals(1)
        rule.onAllNodesWithTag(SetupTags.CONFIRM_AUTOSTART).assertCountEquals(0)
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag(SetupTags.PRIMARY).performClick()
        rule.waitForIdle()
        assertEquals("Done", status(3))
        rule.onNodeWithTag(SetupTags.PRIMARY).assertTextEquals("Add the tile")
    }

    @Test
    fun `a step still to do can be taken out of turn, and the bar goes with it`() {
        var selected by mutableStateOf<SetupStep?>(null)
        show { SetupContent(Screens.firstStep.state, SetupActions(), selectedStep = selected, onSelectStep = { selected = it }) }
        rule.onNodeWithTag(SetupTags.PRIMARY).assertTextEquals("Allow notifications")
        rule.onNodeWithTag(SetupTags.STEP + 4).performScrollTo()
        rule.onNodeWithText("Quick Settings tile").assertHasClickAction().performClick()
        rule.waitForIdle()
        assertEquals(SetupStep.Tile, selected)
        rule.onNodeWithTag(SetupTags.PRIMARY).assertTextEquals("Add the tile")
        assertEquals(listOf("Not done yet", "Not done yet", "Not done yet", "Current step", "Not done yet"), (1..5).map(::status))
    }

    @Test
    fun `where Android has no tile prompt the step says how, and the bar confirms it`() {
        var confirmed = 0
        show { Screens.Setup(Screens.tileByHand, SetupActions(onConfirmTile = { confirmed++ })) }
        rule.onNodeWithText(SetupCopy.TILE_BY_HAND).assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.PRIMARY).assertTextEquals("Done").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun `a key that could not be created is a problem in words, and the bar offers it again`() {
        show { Screens.Setup(Screens.keyFailed) }
        rule.onNodeWithText("Key creation failed (keystore unavailable). Try again.").assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        assertEquals("Current step", status(5))
        rule.onNodeWithTag(SetupTags.PRIMARY).assertTextEquals("Create rig key").assertIsEnabled()
    }

    @Test
    fun `until the phone's settings are read the steps are still lines and the bar is not a guess`() {
        show { Screens.Setup(Screens.setupLoading) }
        rule.onNodeWithText("Set up your rig").assertExists()
        for (step in 1..5) rule.onAllNodesWithTag(SetupTags.STEP + step).assertCountEquals(0)
        rule.onAllNodesWithTag(SetupTags.PRIMARY).assertCountEquals(0)
        rule.onAllNodesWithTag(SetupTags.PROGRESS).assertCountEquals(0)
        // First launch needs no settings to know its bar.
        assertEquals("Set up my rig", xyz.headsdown.ui.setup.SetupPrimary.of(OnboardingState()).label)
    }

    @Test
    fun `at font scale 2 the bar is whole, inside the screen, and the current step is above it`() {
        show(2f) { Screens.Setup(Screens.rigKey) }
        val bar = rule.onNodeWithTag(SetupTags.PRIMARY).assertTextEquals("Create rig key").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("$bar", bar.left >= 20.dp && bar.right <= 340.dp && bar.bottom <= rootHeight() && bar.height >= 56.dp)
        val current = rule.onNodeWithTag(SetupTags.STEP + 5).assertIsDisplayed().getUnclippedBoundsInRoot()
        val dock = rule.onNodeWithTag(SetupTags.DOCK).getUnclippedBoundsInRoot()
        assertTrue("the current step ends above the dock: $current, ${dock.top}", current.bottom <= dock.top)
        assertEquals("Current step", status(5))
    }
}
