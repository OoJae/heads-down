package xyz.headsdown.ui

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.headsdown.core.chain.uplink.AckReason
import xyz.headsdown.feature.oemkeepalive.ShiftHealth
import xyz.headsdown.feature.reveal.haul.HonestCopy
import xyz.headsdown.feature.shift.CrankAck
import xyz.headsdown.feature.shift.CrankLinkStatus
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.refusalLine
import xyz.headsdown.feature.shift.ShiftSnapshot
import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.ui.theme.HeadsDownTheme

/** Compose UI tests for the home screen and the night-shift intro (Robolectric, no Hilt app). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HomeAndIntroTest {
    private val rule = createComposeRule()

    /**
     * The app's merged manifest (unlike a library's unit-test manifest) does not pick up
     * ui-test-manifest, and adding it to debugImplementation would ship an exported test
     * activity in debug APKs. Register the host activity with Robolectric instead.
     */
    private val hostActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(rule)

    private fun home(
        snapshot: ShiftSnapshot = ShiftSnapshot.IDLE,
        onClockIn: () -> Unit = {},
        onPreviewReveal: () -> Unit = {},
        onAddWidget: (() -> Unit)? = null,
        onOpenSensorLab: (() -> Unit)? = null,
        onHowItWorks: () -> Unit = {},
    ) = rule.setContent {
        HeadsDownTheme {
            HomeScreen(
                snapshot = snapshot,
                onboarding = OnboardingState(),
                health = ShiftHealth.NoRecentShift,
                onClockIn = onClockIn,
                onEndShift = {},
                onFreeze = {},
                onOpenSetup = {},
                onHowItWorks = onHowItWorks,
                onPreviewReveal = onPreviewReveal,
                onAddWidget = onAddWidget,
                onOpenSensorLab = onOpenSensorLab,
            )
        }
    }

    @Test
    fun `cold rig offers clock in`() {
        var clocked = 0
        home(onClockIn = { clocked++ })
        rule.onNodeWithTag(HomeTags.RIG_WORD).assertTextEquals("Rig cold")
        rule.onNodeWithTag(HomeTags.CLOCK_IN).performScrollTo().performClick()
        assertEquals(1, clocked)
        rule.onNodeWithText("Your phone's night shift").assertIsDisplayed()
        // No amounts line unless the caller states one.
        rule.onAllNodesWithTag(HomeTags.CLOCK_IN_AMOUNTS).assertCountEquals(0)
    }

    @Test
    fun `the amounts a clock-in can move are on screen next to the button, before any wallet prompt`() {
        val line = xyz.headsdown.rig.ClockInPolicy().disclosure()
        rule.setContent {
            HeadsDownTheme {
                HomeScreen(
                    snapshot = ShiftSnapshot.IDLE,
                    onboarding = OnboardingState(),
                    health = ShiftHealth.NoRecentShift,
                    onClockIn = {},
                    onEndShift = {},
                    onFreeze = {},
                    onOpenSetup = {},
                    clockInAmounts = line,
                )
            }
        }
        rule.onNodeWithTag(HomeTags.CLOCK_IN_AMOUNTS).performScrollTo().assertTextEquals(line)
    }

    @Test
    fun `a frozen rig offers the way back - unfreeze with the wallet`() {
        var clocked = 0
        home(ShiftSnapshot(ShiftState.Frozen(shiftId = 3, at = 0)), onClockIn = { clocked++ })
        rule.onNodeWithTag(HomeTags.RIG_WORD).assertTextEquals("Frozen")
        rule.onNodeWithTag(HomeTags.UNFREEZE).performScrollTo().performClick()
        assertEquals(1, clocked)
        rule.onAllNodesWithTag(HomeTags.CLOCK_IN).assertCountEquals(0)
    }

    @Test
    fun `the Focus Bond is chosen before a shift and says where the SKR goes either way`() {
        var chosen: ULong? = null
        rule.setContent {
            HeadsDownTheme {
                HomeScreen(
                    snapshot = ShiftSnapshot.IDLE,
                    onboarding = OnboardingState(),
                    health = ShiftHealth.NoRecentShift,
                    onClockIn = {},
                    onEndShift = {},
                    onFreeze = {},
                    onOpenSetup = {},
                    bondSkr = 10_000_000uL,
                    onBondChange = { chosen = it },
                )
            }
        }
        rule.onNodeWithTag(HomeTags.BOND_CARD).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("It never goes to Heads Down.", substring = true).assertExists()
        rule.onNodeWithTag(HomeTags.BOND_CHOICE + "50 SKR").performScrollTo().performClick()
        assertEquals(50_000_000uL, chosen)
        // Tapping the choice already selected changes nothing.
        chosen = null
        rule.onNodeWithTag(HomeTags.BOND_CHOICE + "10 SKR").performClick()
        assertEquals(null, chosen)
    }

    @Test
    fun `the Focus Bond cannot be changed while a shift is running`() {
        rule.setContent {
            HeadsDownTheme {
                HomeScreen(
                    snapshot = ShiftSnapshot(ShiftState.Down(ShiftSpec(1, ShiftMode.NIGHT), 0, 0)),
                    onboarding = OnboardingState(),
                    health = ShiftHealth.NoRecentShift,
                    onClockIn = {},
                    onEndShift = {},
                    onFreeze = {},
                    onOpenSetup = {},
                    bondSkr = 10_000_000uL,
                    onBondChange = {},
                )
            }
        }
        rule.onAllNodesWithTag(HomeTags.BOND_CARD).assertCountEquals(0)
    }

    @Test
    fun `hot rig shows ember state and shift controls`() {
        home(
            ShiftSnapshot(
                ShiftState.Down(ShiftSpec(1, ShiftMode.NIGHT), 0, 0),
                darkRounds = 55,
                darkSinceWallMillis = System.currentTimeMillis() - 72 * 60_000,
            ),
        )
        rule.onNodeWithTag(HomeTags.RIG_WORD).assertTextEquals("Rig hot")
        rule.onNodeWithText("55").assertExists()
        rule.onNodeWithText("End shift").assertExists()
        rule.onAllNodesWithTag(HomeTags.CLOCK_IN).assertCountEquals(0)
    }

    @Test
    fun `optional entries appear only when wired`() {
        var lab = 0
        var widget = 0
        var reveal = 0
        home(onAddWidget = { widget++ }, onOpenSensorLab = { lab++ }, onPreviewReveal = { reveal++ })
        rule.onNodeWithTag(HomeTags.ADD_WIDGET).performScrollTo().performClick()
        rule.onNodeWithTag(HomeTags.SENSOR_LAB).performScrollTo().performClick()
        rule.onNodeWithTag(HomeTags.PREVIEW_REVEAL).performScrollTo().performClick()
        assertEquals(listOf(1, 1, 1), listOf(widget, lab, reveal))
    }

    @Test
    fun `a crank refusal is shown on the rig card, plainly`() {
        val refused = CrankLinkStatus(
            configured = true,
            accepted = 3,
            lastAck = CrankAck(9uL, "heartbeat", ok = false, reason = AckReason.BAD_SIGNATURE, atWallMillis = 0),
        )
        rule.setContent {
            HeadsDownTheme {
                HomeScreen(
                    snapshot = ShiftSnapshot.IDLE, onboarding = OnboardingState(), health = ShiftHealth.NoRecentShift,
                    onClockIn = {}, onEndShift = {}, onFreeze = {}, onOpenSetup = {}, crank = refused,
                    onOpenRigDebug = {},
                )
            }
        }
        rule.onNodeWithTag(HomeTags.CRANK_REFUSAL).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(refused.refusalLine()!!).assertExists()
        rule.onNodeWithTag(HomeTags.RIG_DEBUG).performScrollTo().assertExists()
        screenTexts().forEach { assertTrue("banned words in: $it", HonestCopy.violations(it).isEmpty()) }
    }

    @Test
    fun `accepted acks show nothing`() {
        home()
        rule.onAllNodesWithTag(HomeTags.CRANK_REFUSAL).assertCountEquals(0)
    }

    @Test
    fun `release-style home has no sensor lab and no widget button`() {
        home()
        rule.onAllNodesWithTag(HomeTags.SENSOR_LAB).assertCountEquals(0)
        rule.onAllNodesWithTag(HomeTags.ADD_WIDGET).assertCountEquals(0)
        rule.onNodeWithTag(HomeTags.HOW_IT_WORKS).assertExists()
    }

    @Test
    fun `home copy is honest`() {
        home(onAddWidget = {}, onOpenSensorLab = {})
        screenTexts().forEach { assertTrue("banned words in: $it", HonestCopy.violations(it).isEmpty()) }
    }

    @Test
    fun `intro explains the ritual and continues`() {
        var continued = 0
        rule.setContent { HeadsDownTheme { NightShiftIntro(onContinue = { continued++ }) } }
        rule.onNodeWithText(NightShiftCopy.TITLE).assertIsDisplayed()
        NightShiftCopy.STEPS.forEach { rule.onNodeWithText(it.title).assertExists() }
        rule.onNodeWithText(NightShiftCopy.NOT_BODY).assertExists()
        rule.onNodeWithTag(INTRO_CONTINUE_TAG).performScrollTo().performClick()
        assertEquals(1, continued)
    }

    @Test
    fun `intro copy is honest and says what it is not`() {
        NightShiftCopy.allText.forEach { assertTrue("banned words in: $it", HonestCopy.violations(it).isEmpty()) }
        assertTrue(NightShiftCopy.NOT_BODY.startsWith("This is not income."))
        assertTrue(NightShiftCopy.allText.any { "cheaper route" in it })
        assertTrue(NightShiftCopy.allText.any { "nothing is lost" in it })
    }

    @Test
    fun `setup is not done until the intro was read`() {
        val everythingElse = OnboardingState(
            notificationsGranted = true,
            exactAlarmsAllowed = true,
            batteryUnrestricted = true,
            tileAdded = true,
            rigKey = xyz.headsdown.rig.RigKeyStatus.Ready(xyz.headsdown.core.keys.KeySecurityLevel.TRUSTED_ENVIRONMENT, "ab", 3),
        )
        assertFalse(everythingElse.allDone)
        assertTrue(everythingElse.copy(introSeen = true).allDone)
    }

    private fun screenTexts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            n.children.forEach(::walk)
        }
        walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }
}
