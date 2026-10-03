package xyz.headsdown.clockout

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.headsdown.core.chain.accounts.OreClaimEstimate
import xyz.headsdown.core.chain.clockout.BondOutcome
import xyz.headsdown.core.chain.clockout.ShiftOutcome
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.ui.theme.HeadsDownTheme

/** Compose UI tests for the clock-out screen (Robolectric, no Hilt app). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClockOutScreenTest {
    private val rule = createComposeRule()

    /** Same reason as HomeAndIntroTest: the app's manifest has no ui-test host activity. */
    private val hostActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(rule)

    private val completed = ClockOutFacts(
        shift = ShiftOutcome.Ends(ShiftEndReason.COMPLETED),
        bond = BondOutcome.Released(100_000_000uL),
        returnedSolLamports = 0uL,
        nothingByDefault = false,
        refinedOre = 1_000uL,
        unrefinedOre = 20_000_000uL,
        fullClaim = OreClaimEstimate(1_000uL, 20_000_000uL, 2_000_000uL),
        shiftLogRent = 1_300_480uL,
    )
    private val insideWindow = completed.copy(
        shift = ShiftOutcome.LeftOpen(ShiftEndReason.MANUAL, 1_790_028_800L),
        bond = BondOutcome.StaysLocked(100_000_000uL),
        nothingByDefault = true,
    )

    private val claims = mutableListOf<Boolean>()
    private val ends = mutableListOf<Boolean>()
    private var confirms = 0
    private var retries = 0
    private var closes = 0

    private fun show(state: ClockOutState) = rule.setContent {
        HeadsDownTheme {
            ClockOutScreen(
                state = state,
                onClaimAll = { claims += it },
                onEndEarly = { ends += it },
                onConfirm = { confirms++ },
                onRetry = { retries++ },
                onClose = { closes++ },
            )
        }
    }

    @Test
    fun `a finished shift shows what clocking out does and keeps the ORE by default`() {
        show(ClockOutState.Ready(completed))
        rule.onNodeWithTag(ClockOutTags.SHIFT).assertTextContains("seals it as completed", substring = true)
        rule.onNodeWithTag(ClockOutTags.RENT).assertTextContains("your wallet pays 0.00130048 SOL of rent", substring = true)
        rule.onNodeWithTag(ClockOutTags.BOND).assertTextEquals("Your Focus Bond comes back to your wallet: 100 SKR.")
        rule.onNodeWithTag(ClockOutTags.ORE).assertTextContains("0.00020001 ORE", substring = true)
        rule.onNodeWithTag(ClockOutTags.KEEP_CHOICE).assertIsSelected()
        rule.onNodeWithTag(ClockOutTags.CLAIM_CHOICE).assertIsNotSelected()
        // The fee line belongs to the claim choice: it is not on screen until that is chosen.
        rule.onAllNodesWithTag(ClockOutTags.CLAIM).assertCountEquals(0)
        rule.onAllNodesWithTag(ClockOutTags.END_EARLY).assertCountEquals(0)
        rule.onNodeWithTag(ClockOutTags.CLAIM_CHOICE).performScrollTo().performClick()
        assertEquals(listOf(true), claims)
        rule.onNodeWithTag(ClockOutTags.CONFIRM).performScrollTo().assertIsEnabled().assertTextEquals("Clock out").performClick()
        assertEquals(1, confirms)
    }

    @Test
    fun `choosing to claim shows what arrives and what ORE keeps`() {
        show(ClockOutState.Ready(completed, claimAll = true))
        rule.onNodeWithTag(ClockOutTags.CLAIM_CHOICE).assertIsSelected()
        rule.onNodeWithTag(ClockOutTags.CLAIM).assertTextContains("about 0.00018001 ORE", substring = true)
        rule.onNodeWithTag(ClockOutTags.CLAIM).assertTextContains("ORE keeps 0.00002 ORE", substring = true)
        rule.onNodeWithTag(ClockOutTags.KEEP_CHOICE).performScrollTo().performClick()
        assertEquals(listOf(false), claims)
    }

    @Test
    fun `a shift inside its window has nothing to sign until the user chooses`() {
        show(ClockOutState.Ready(insideWindow))
        rule.onNodeWithTag(ClockOutTags.SHIFT).assertTextContains("clocking out leaves it open", substring = true)
        rule.onNodeWithTag(ClockOutTags.BOND).assertTextEquals("Your Focus Bond of 100 SKR stays locked until the shift is sealed.")
        rule.onAllNodesWithTag(ClockOutTags.END_EARLY_WARNING).assertCountEquals(0)
        rule.onAllNodesWithTag(ClockOutTags.RENT).assertCountEquals(0)
        rule.onNodeWithTag(ClockOutTags.CONFIRM).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(ClockOutTags.NOTHING).assertTextEquals(ClockOutCopy.NOTHING_TO_SIGN)
        rule.onNodeWithTag(ClockOutTags.END_EARLY).performScrollTo().assertIsNotSelected().performClick()
        assertEquals(listOf(true), ends)
    }

    @Test
    fun `ending early puts the forfeit on screen before the wallet opens`() {
        show(ClockOutState.Ready(insideWindow, endEarly = true))
        rule.onNodeWithTag(ClockOutTags.END_EARLY).assertIsSelected()
        rule.onNodeWithTag(ClockOutTags.END_EARLY_WARNING).assertTextContains("Your Focus Bond of 100 SKR is forfeit.", substring = true)
        rule.onNodeWithTag(ClockOutTags.SHIFT).assertTextContains("ended early", substring = true)
        rule.onNodeWithTag(ClockOutTags.BOND).assertTextContains("is forfeit", substring = true)
        rule.onNodeWithTag(ClockOutTags.CONFIRM).performScrollTo().assertIsEnabled().assertTextEquals("End the shift and clock out")
        rule.onAllNodesWithTag(ClockOutTags.NOTHING).assertCountEquals(0)
        // Tapping the chosen option again takes the choice back.
        rule.onNodeWithTag(ClockOutTags.END_EARLY).performScrollTo().performClick()
        assertEquals(listOf(false), ends)
    }

    @Test
    fun `while the wallet is open nothing on screen can be changed`() {
        show(ClockOutState.Ready(insideWindow, endEarly = true, working = true))
        rule.onNodeWithTag(ClockOutTags.CONFIRM).performScrollTo().assertIsNotEnabled().assertTextEquals("Waiting for your wallet…")
        rule.onNodeWithTag(ClockOutTags.END_EARLY).assertIsNotEnabled()
        rule.onNodeWithTag(ClockOutTags.KEEP_CHOICE).assertIsNotEnabled()
        rule.onNodeWithTag(ClockOutTags.CLAIM_CHOICE).assertIsNotEnabled()
    }

    @Test
    fun `a problem is shown beside the button and the button stays usable`() {
        show(ClockOutState.Ready(completed, problem = ClockOutModel.NOT_LANDED))
        rule.onNodeWithTag(ClockOutTags.PROBLEM).assertTextEquals(ClockOutModel.NOT_LANDED)
        rule.onNodeWithTag(ClockOutTags.CONFIRM).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `an empty Miner offers no claim choice`() {
        show(ClockOutState.Ready(completed.copy(refinedOre = 0uL, unrefinedOre = 0uL, fullClaim = null)))
        rule.onNodeWithTag(ClockOutTags.ORE).assertTextEquals("No ORE in your Miner yet.")
        rule.onAllNodesWithTag(ClockOutTags.KEEP_CHOICE).assertCountEquals(0)
        rule.onAllNodesWithTag(ClockOutTags.CLAIM_CHOICE).assertCountEquals(0)
    }

    @Test
    fun `the other states say what they are and offer only what applies`() {
        show(ClockOutState.NoRig)
        rule.onNodeWithTag(ClockOutTags.MESSAGE).assertTextContains("No rig on this phone yet", substring = true)
        rule.onAllNodesWithTag(ClockOutTags.CONFIRM).assertCountEquals(0)
        rule.onNodeWithTag(ClockOutTags.CLOSE).assertTextEquals("Close").performClick()
        assertEquals(1, closes)
    }

    @Test
    fun `an unreadable chain offers a retry and no button that signs`() {
        show(ClockOutState.Unavailable(ClockOutModel.UNREADABLE))
        rule.onNodeWithTag(ClockOutTags.MESSAGE).assertTextEquals(ClockOutModel.UNREADABLE)
        rule.onAllNodesWithTag(ClockOutTags.CONFIRM).assertCountEquals(0)
        rule.onNodeWithTag(ClockOutTags.RETRY).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `done shows the confirmed result and closes`() {
        show(ClockOutState.Done("Confirmed on-chain. Shift sealed as completed."))
        rule.onNodeWithTag(ClockOutTags.MESSAGE).assertTextEquals("Confirmed on-chain. Shift sealed as completed.")
        rule.onAllNodesWithTag(ClockOutTags.CONFIRM).assertCountEquals(0)
        rule.onNodeWithText("Done").performClick()
        assertEquals(1, closes)
    }
}
