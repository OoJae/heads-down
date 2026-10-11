package xyz.headsdown.clockout

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.headsdown.core.chain.accounts.OreClaimEstimate
import xyz.headsdown.core.chain.clockout.BondOutcome
import xyz.headsdown.core.chain.clockout.ShiftOutcome
import xyz.headsdown.core.chain.withdraw.Revoke
import xyz.headsdown.core.chain.withdraw.RigClose
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.withdraw.WithdrawCopy
import xyz.headsdown.withdraw.WithdrawFacts
import xyz.headsdown.withdraw.WithdrawScreen
import xyz.headsdown.withdraw.WithdrawState
import xyz.headsdown.withdraw.WithdrawTags

/**
 * Steps 5 and 6 of `scripts/devstack/emulator-smoke.sh`, walked over the two sheets the way the
 * script walks them: by the texts that show on the glass, the first match in layout order, a
 * finger on the middle of what shows of it, swiping up to look and never back down. The model's
 * part is played by the test (a tap changes the state, the bar confirms), the wallet's by nobody.
 *
 * The other sheet tests ask a node for its place and scroll to it; this one only knows what the
 * script knows. It runs on the Redmi 14C's screen (360 x 820 dp, between its status bar and a
 * three-button navigation bar), there again at the largest system font, and on a small phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = EmulatorScriptWalkTest.REDMI)
class EmulatorScriptWalkTest : SheetTestBase() {

    /** What the run reaches before step 5: the shift cooled by the pickup, inside its window, no bond, nothing claimable yet. */
    private val cooled = ClockOutFacts(
        shift = ShiftOutcome.LeftOpen(ShiftEndReason.PICKUP, 1_790_028_800L),
        bond = BondOutcome.None,
        returnedSolLamports = 0uL,
        nothingByDefault = true,
        refinedOre = 0uL,
        unrefinedOre = 0uL,
        fullClaim = null,
        shiftLogRent = 1_300_480uL,
    )

    /** And before step 6: the shift sealed, the Automation still holding its SOL, the rig closable. */
    private val both = WithdrawFacts(
        revoke = Revoke(lamports = 20_004_480uL, balance = 18_000_000uL, headsDown = true),
        hasRig = true,
        shiftOpen = false,
        rigClose = RigClose(rentBackLamports = 2_449_920uL, leavesTombstone = true, closesSeekerSeat = false),
        closeBlock = null,
    )

    private var closed = 0

    /** Step 5: `ui tap "End the shift now" exact` … `ui tap "Done" exact`. */
    private fun clockOut(facts: ClockOutFacts = cooled, navigation: Dp = THREE_BUTTONS) {
        var state by mutableStateOf<ClockOutState>(ClockOutState.Ready(facts))
        show {
            BetweenTheBars(navigation = navigation) {
                ClockOutScreen(
                    state = state,
                    onClaimAll = { claim -> state = (state as ClockOutState.Ready).copy(claimAll = claim) },
                    onEndEarly = { end -> state = (state as ClockOutState.Ready).copy(endEarly = end) },
                    onConfirm = {
                        val signed = (state as ClockOutState.Ready).shown
                        state = ClockOutState.Done(ClockOutCopy.done(signed.shift, signed.bond, signed.returnedSolLamports, null), SIGNATURE)
                    },
                    onRetry = {},
                    onClose = { closed++ },
                    // The localdev build: no public explorer, so no link under the result.
                    cluster = "solana:localnet",
                )
            }
        }
        // Before anything is chosen there is nothing to sign, and the sheet says nothing is confirmed.
        assertFalse("nothing is confirmed yet", has("Confirmed on-chain"))
        assertFalse("no warning before the choice it belongs to", has("This cannot be undone"))

        tap("End the shift now", exact = true)
        assertTrue("the choice was taken: ${dump().map { it.text }}", (state as ClockOutState.Ready).endEarly)
        // `ui has`: no scrolling. The warning must be on the glass by itself.
        assertTrue("ending early states its consequences where the script can see them: ${dump().map { it.text }}", has("This cannot be undone"))
        // And where a person can read them to the end: the page went to the warning, all of it.
        assertTrue("the whole warning is on the glass", matches("This cannot be undone", exact = false).single().whole)

        tap("End the shift and clock out", exact = true)
        assertTrue("the clock-out is confirmed: ${dump().map { it.text }}", has("Confirmed on-chain. Shift sealed"))
        val said = find("Confirmed on-chain").text
        assertEquals(ClockOutCopy.done(facts.endedEarly().shift, facts.endedEarly().bond, 0uL, null), said)
        assertTrue(said, said.startsWith("Confirmed on-chain. Shift sealed as ended early."))
        assertEquals("one node says it", 1, matches("Confirmed on-chain", exact = false).size)

        assertEquals("one node reads Done", 1, matches("Done", exact = true).size)
        tap("Done", exact = true)
        assertEquals(1, closed)
    }

    /** Step 6: `ui find "Your ORE Automation holds"` … `ui tap "Done" exact`, and the sum the script checks to the lamport. */
    private fun takeItBack(navigation: Dp = THREE_BUTTONS) {
        var state by mutableStateOf<WithdrawState>(WithdrawState.Ready(both))
        show {
            BetweenTheBars(navigation = navigation) {
                WithdrawScreen(
                    state = state,
                    onConnect = {},
                    onRevoke = { revoke -> state = (state as WithdrawState.Ready).copy(revoke = revoke) },
                    onCloseRig = { close -> state = (state as WithdrawState.Ready).copy(closeRig = close) },
                    onConfirm = {
                        val ready = state as WithdrawState.Ready
                        state = WithdrawState.Done(
                            WithdrawCopy.done(ready.facts.revoke.takeIf { ready.revoke }, ready.facts.rigClose.takeIf { ready.closeRig }),
                            SIGNATURE,
                        )
                    },
                    onRetry = {},
                    onClose = { closed++ },
                    cluster = "solana:localnet",
                )
            }
        }
        assertEquals(WithdrawCopy.automation(both), find("Your ORE Automation holds").text)

        tap("Take it back to my wallet", exact = true)
        assertTrue("the first choice was taken", (state as WithdrawState.Ready).let { it.revoke && !it.closeRig })
        tap("Close my rig", exact = true)
        assertTrue("both choices are taken", (state as WithdrawState.Ready).let { it.revoke && it.closeRig })
        // Closing the rig is final: what it costs is on the glass, to its last sentence, before the bar is tapped.
        assertTrue("the whole of what closing costs is on the glass", matches("This cannot be undone", exact = false).single().whole)

        tap("Take SOL back and close the rig", exact = true)
        val done = find("Confirmed on-chain").text
        assertEquals("one node says it", 1, matches("Confirmed on-chain", exact = false).size)
        // What the script does with that line: every "N.N SOL" in it, summed, is what the wallet gains before the fee.
        val promised = Regex("""([0-9]+\.[0-9]+) SOL""").findAll(done).sumOf { Math.round(it.groupValues[1].toDouble() * 1e9) }
        assertEquals(20_004_480L + 2_449_920L, promised)

        assertEquals("one node reads Done", 1, matches("Done", exact = true).size)
        tap("Done", exact = true)
        assertEquals(1, closed)
    }

    @Test
    fun `step 5 on the Redmi - end the shift, see what it costs, clock out, done - without one swipe`() {
        clockOut()
        assertEquals("everything the script needs is within reach of the first screenful", 0, swipes)
    }

    @Test
    fun `step 6 on the Redmi - take the SOL back, close the rig, the sum to the lamport, done - without one swipe`() {
        takeItBack()
        assertEquals("everything the script needs is within reach of the first screenful", 0, swipes)
    }

    @Test
    fun `step 5 with a bond on the shift and ORE to decide about`() {
        // A longer ledger than the emulator run has: the warning names the bond, the claim choices follow.
        clockOut(
            cooled.copy(
                bond = BondOutcome.StaysLocked(100_000_000uL),
                refinedOre = 1_000uL,
                unrefinedOre = 20_000_000uL,
                fullClaim = OreClaimEstimate(1_000uL, 20_000_000uL, 2_000_000uL),
            ),
        )
    }

    // A small phone, with gesture navigation. (With a three-button bar a 568dp screen puts the
    // script's swipe, which starts at 78% of the glass, on the dock: 48dp of bar and 80dp of dock
    // are the bottom 22.5%. The dock stays below that line on any screen from 582dp up; the
    // Redmi's is 820dp. A finger that goes down on the dock does not scroll the page.)

    @Test
    @Config(qualifiers = SMALL)
    fun `step 5 on a small phone`() = clockOut(navigation = GESTURES)

    @Test
    @Config(qualifiers = SMALL)
    fun `step 6 on a small phone`() = takeItBack(navigation = GESTURES)

    @Test
    fun `step 5 at the largest system font`() {
        fontScale = 2f
        clockOut()
    }

    @Test
    fun `step 6 at the largest system font - the script finds the second choice looking down the page, never back up`() {
        fontScale = 2f
        takeItBack()
        // The page is longer than the glass here whatever the words: what the first choice costs
        // pushes the second one under the dock, and the script has to go and look for it.
        assertTrue("the script had to swipe", swipes > 0)
    }

    @Test
    fun `the swipe the script looks with starts in the page, above the dock, on both sheets`() {
        // 78% of the glass, where `ui.py swipe` puts the finger down: it has to land on what scrolls.
        show { BetweenTheBars { ClockOutScreen(ClockOutState.Ready(cooled), {}, {}, {}, {}, {}) } }
        assertTrue(bounds(ClockOutTags.CONFIRM).top.value > root().bottom.value * 0.78f)
        show { BetweenTheBars { WithdrawScreen(WithdrawState.Ready(both), {}, {}, {}, {}, {}, {}) } }
        assertTrue(bounds(WithdrawTags.CONFIRM).top.value > root().bottom.value * 0.78f)
    }

    companion object {
        /** The Redmi 14C: 720 x 1640 px at density 2. */
        const val REDMI = "w360dp-h820dp-xhdpi"

        /** A small phone. */
        const val SMALL = "w320dp-h568dp-xhdpi"

        /** The height of the two navigation bars a phone can have. */
        val THREE_BUTTONS = 48.dp
        val GESTURES = 24.dp
    }
}
