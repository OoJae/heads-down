package xyz.headsdown.withdraw

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
import xyz.headsdown.core.chain.withdraw.CloseBlock
import xyz.headsdown.core.chain.withdraw.Revoke
import xyz.headsdown.core.chain.withdraw.RigClose
import xyz.headsdown.ui.theme.HeadsDownTheme

/** Compose UI tests for the withdraw screen (Robolectric, no Hilt app). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WithdrawScreenTest {
    private val rule = createComposeRule()

    /** Same reason as HomeAndIntroTest: the app's manifest has no ui-test host activity. */
    private val hostActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(rule)

    private val both = WithdrawFacts(
        revoke = Revoke(lamports = 20_004_480uL, balance = 18_000_000uL, headsDown = true),
        hasRig = true,
        shiftOpen = false,
        rigClose = RigClose(rentBackLamports = 2_449_920uL, leavesTombstone = true, closesSeekerSeat = false),
        closeBlock = null,
    )

    private val revokes = mutableListOf<Boolean>()
    private val closes = mutableListOf<Boolean>()
    private var connects = 0
    private var confirms = 0
    private var retries = 0
    private var exits = 0

    private fun show(state: WithdrawState) = rule.setContent {
        HeadsDownTheme {
            WithdrawScreen(
                state = state,
                onConnect = { connects++ },
                onRevoke = { revokes += it },
                onCloseRig = { closes += it },
                onConfirm = { confirms++ },
                onRetry = { retries++ },
                onClose = { exits++ },
            )
        }
    }

    @Test
    fun `it shows what can be taken back and signs nothing until something is chosen`() {
        show(WithdrawState.Ready(both))
        rule.onNodeWithTag(WithdrawTags.AUTOMATION).assertTextContains("holds 0.02000448 SOL", substring = true)
        rule.onNodeWithTag(WithdrawTags.RIG).assertTextContains("0.00244992 SOL of account rent", substring = true)
        rule.onNodeWithTag(WithdrawTags.REVOKE).assertIsNotSelected()
        rule.onNodeWithTag(WithdrawTags.CLOSE_RIG).assertIsNotSelected()
        rule.onAllNodesWithTag(WithdrawTags.REVOKE_DETAIL).assertCountEquals(0)
        rule.onAllNodesWithTag(WithdrawTags.CLOSE_DETAIL).assertCountEquals(0)
        rule.onNodeWithTag(WithdrawTags.CONFIRM).performScrollTo().assertIsNotEnabled().assertTextEquals("Nothing chosen")
        rule.onNodeWithTag(WithdrawTags.NOTHING).assertTextEquals(WithdrawCopy.NOTHING_CHOSEN)
        rule.onNodeWithTag(WithdrawTags.REVOKE).performScrollTo().performClick()
        rule.onNodeWithTag(WithdrawTags.CLOSE_RIG).performScrollTo().performClick()
        assertEquals(listOf(true), revokes)
        assertEquals(listOf(true), closes)
        assertEquals(0, confirms)
    }

    @Test
    fun `each choice puts its consequences on screen before the wallet opens`() {
        show(WithdrawState.Ready(both, revoke = true, closeRig = true))
        rule.onNodeWithTag(WithdrawTags.REVOKE).assertIsSelected()
        rule.onNodeWithTag(WithdrawTags.REVOKE_DETAIL).assertTextContains("Nothing more is dug for you until your next clock-in", substring = true)
        rule.onNodeWithTag(WithdrawTags.CLOSE_RIG).assertIsSelected()
        rule.onNodeWithTag(WithdrawTags.CLOSE_DETAIL).assertTextContains("This cannot be undone.", substring = true)
        rule.onNodeWithTag(WithdrawTags.CONFIRM).performScrollTo().assertIsEnabled().assertTextEquals("Take SOL back and close the rig").performClick()
        assertEquals(1, confirms)
        rule.onAllNodesWithTag(WithdrawTags.NOTHING).assertCountEquals(0)
        // Tapping a chosen option again takes it back.
        rule.onNodeWithTag(WithdrawTags.CLOSE_RIG).performScrollTo().performClick()
        assertEquals(listOf(false), closes)
    }

    @Test
    fun `a rig that cannot be closed says why and offers no button for it`() {
        show(WithdrawState.Ready(both.copy(shiftOpen = true, rigClose = null, closeBlock = CloseBlock.SHIFT_OPEN.message), revoke = true))
        rule.onNodeWithTag(WithdrawTags.RIG).assertTextEquals("Your rig cannot be closed right now. A shift is still open on this rig. Clock out first.")
        rule.onAllNodesWithTag(WithdrawTags.CLOSE_RIG).assertCountEquals(0)
        rule.onNodeWithTag(WithdrawTags.REVOKE_DETAIL).assertTextContains("Your open shift stays open", substring = true)
        rule.onNodeWithTag(WithdrawTags.CONFIRM).performScrollTo().assertIsEnabled().assertTextEquals("Take SOL back")
    }

    @Test
    fun `no Automation and no rig leaves nothing to choose`() {
        show(WithdrawState.Ready(WithdrawFacts(null, hasRig = false, shiftOpen = false, rigClose = null, closeBlock = null)))
        rule.onNodeWithTag(WithdrawTags.AUTOMATION).assertTextContains("No ORE Automation for this wallet", substring = true)
        rule.onNodeWithTag(WithdrawTags.RIG).assertTextEquals("No rig is registered for this wallet.")
        rule.onAllNodesWithTag(WithdrawTags.REVOKE).assertCountEquals(0)
        rule.onAllNodesWithTag(WithdrawTags.CLOSE_RIG).assertCountEquals(0)
        rule.onNodeWithTag(WithdrawTags.CONFIRM).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `while the wallet is open nothing on screen can be changed`() {
        show(WithdrawState.Ready(both, revoke = true, working = true))
        rule.onNodeWithTag(WithdrawTags.CONFIRM).performScrollTo().assertIsNotEnabled().assertTextEquals("Waiting for your wallet…")
        rule.onNodeWithTag(WithdrawTags.REVOKE).assertIsNotEnabled()
        rule.onNodeWithTag(WithdrawTags.CLOSE_RIG).assertIsNotEnabled()
    }

    @Test
    fun `a phone with no bound rig asks for the wallet first`() {
        show(WithdrawState.NeedsWallet(problem = WithdrawModel.NO_WALLET))
        rule.onNodeWithTag(WithdrawTags.MESSAGE).assertTextEquals(WithdrawCopy.NEEDS_WALLET)
        rule.onNodeWithTag(WithdrawTags.PROBLEM).assertTextEquals(WithdrawModel.NO_WALLET)
        rule.onAllNodesWithTag(WithdrawTags.CONFIRM).assertCountEquals(0)
        rule.onNodeWithTag(WithdrawTags.CONNECT).assertTextEquals("Connect wallet").performClick()
        assertEquals(1, connects)
    }

    @Test
    fun `connecting shows that the wallet is being waited for`() {
        show(WithdrawState.NeedsWallet(working = true))
        rule.onNodeWithTag(WithdrawTags.CONNECT).assertIsNotEnabled().assertTextEquals("Waiting for your wallet…")
    }

    @Test
    fun `a problem, an unreadable chain and the confirmed result each say what they are`() {
        show(WithdrawState.Unavailable(WithdrawModel.UNREADABLE))
        rule.onNodeWithTag(WithdrawTags.MESSAGE).assertTextEquals(WithdrawModel.UNREADABLE)
        rule.onAllNodesWithTag(WithdrawTags.CONFIRM).assertCountEquals(0)
        rule.onNodeWithTag(WithdrawTags.RETRY).performClick()
        assertEquals(1, retries)
        rule.onNodeWithTag(WithdrawTags.CLOSE).assertTextEquals("Close").performClick()
        assertEquals(1, exits)
    }

    @Test
    fun `done shows the confirmed result and closes`() {
        show(WithdrawState.Done("Confirmed on-chain. 0.02000448 SOL back in your wallet from the ORE Automation."))
        rule.onNodeWithTag(WithdrawTags.MESSAGE).assertTextContains("0.02000448 SOL back in your wallet", substring = true)
        rule.onAllNodesWithTag(WithdrawTags.CONFIRM).assertCountEquals(0)
        rule.onNodeWithText("Done").performClick()
        assertEquals(1, exits)
    }

    @Test
    fun `a problem is shown beside the button and the button stays usable`() {
        show(WithdrawState.Ready(both, revoke = true, problem = WithdrawModel.NOT_LANDED))
        rule.onNodeWithTag(WithdrawTags.PROBLEM).assertTextEquals(WithdrawModel.NOT_LANDED)
        rule.onNodeWithTag(WithdrawTags.CONFIRM).performScrollTo().assertIsEnabled()
    }
}
