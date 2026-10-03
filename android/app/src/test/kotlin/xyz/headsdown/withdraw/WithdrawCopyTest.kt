package xyz.headsdown.withdraw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.withdraw.CloseBlock
import xyz.headsdown.core.chain.withdraw.Revoke
import xyz.headsdown.core.chain.withdraw.RigClose
import xyz.headsdown.feature.reveal.haul.HonestCopy

/** The withdraw screen's words: amounts, what stops, what stays, and what cannot be undone. */
class WithdrawCopyTest {

    private val revoke = Revoke(lamports = 20_004_480uL, balance = 18_000_000uL, headsDown = true)
    private val close = RigClose(rentBackLamports = 2_449_920uL, leavesTombstone = true, closesSeekerSeat = false)

    private fun facts(
        revoke: Revoke? = this.revoke,
        hasRig: Boolean = true,
        shiftOpen: Boolean = false,
        rigClose: RigClose? = close,
        closeBlock: String? = null,
    ) = WithdrawFacts(revoke, hasRig, shiftOpen, rigClose, closeBlock)

    @Test
    fun `the Automation line states the whole amount and the part not yet placed`() {
        assertEquals(
            "Your ORE Automation holds 0.02000448 SOL: 0.018 SOL not yet placed, and the account's rent. " +
                "It is yours, and only your wallet can take it out.",
            WithdrawCopy.automation(facts()),
        )
        assertTrue(WithdrawCopy.automation(facts(revoke = revoke.copy(headsDown = false))).endsWith("It is set up for another executor, not for Heads Down."))
        assertEquals("No ORE Automation for this wallet: no SOL of yours is waiting to be placed.", WithdrawCopy.automation(facts(revoke = null)))
        assertNull(WithdrawCopy.revokeDetail(facts(revoke = null)))
    }

    @Test
    fun `taking the SOL back says what arrives and that digging stops until the next clock-in`() {
        val detail = WithdrawCopy.revokeDetail(facts())!!
        assertTrue(detail, detail.contains("sends 0.02000448 SOL to your wallet"))
        assertTrue(detail, detail.contains("Nothing more is dug for you until your next clock-in"))
        assertFalse(detail, detail.contains("open shift"))
        val open = WithdrawCopy.revokeDetail(facts(shiftOpen = true))!!
        assertTrue(open, open.endsWith("Your open shift stays open, but no round is dug in it."))
    }

    @Test
    fun `the rig line says what comes back, or why it cannot be closed`() {
        assertEquals("Your rig can be closed: 0.00244992 SOL of account rent comes back to your wallet.", WithdrawCopy.rig(facts()))
        assertEquals("No rig is registered for this wallet.", WithdrawCopy.rig(facts(hasRig = false, rigClose = null)))
        assertEquals(
            "Your rig cannot be closed right now. A shift is still open on this rig. Clock out first.",
            WithdrawCopy.rig(facts(rigClose = null, closeBlock = CloseBlock.SHIFT_OPEN.message)),
        )
    }

    @Test
    fun `closing says what is erased, what stays, and that it is final`() {
        val detail = WithdrawCopy.closeDetail(facts())!!
        assertTrue(detail, detail.contains("erases the rig's streak and lifetime counts"))
        assertTrue(detail, detail.contains("A 32-byte record stays"))
        assertTrue(detail, detail.endsWith("This cannot be undone."))
        assertFalse(detail, detail.contains("Seeker"))
        val full = WithdrawCopy.closeDetail(facts(rigClose = RigClose(2_672_640uL, leavesTombstone = false, closesSeekerSeat = true)))!!
        assertFalse(full, full.contains("32-byte"))
        assertTrue(full, full.contains("Its Seeker seat is closed with it"))
        assertNull(WithdrawCopy.closeDetail(facts(rigClose = null)))
    }

    @Test
    fun `the button names exactly what was chosen`() {
        assertEquals("Take SOL back", WithdrawCopy.button(revoke = true, closeRig = false))
        assertEquals("Close the rig", WithdrawCopy.button(revoke = false, closeRig = true))
        assertEquals("Take SOL back and close the rig", WithdrawCopy.button(revoke = true, closeRig = true))
        assertEquals("Nothing chosen", WithdrawCopy.button(revoke = false, closeRig = false))
    }

    @Test
    fun `there is something to sign only for a choice that can be carried out`() {
        val f = facts(rigClose = null, closeBlock = CloseBlock.BOND_HELD.message)
        assertTrue(f.somethingToSign(revoke = true, closeRig = false))
        assertFalse(f.somethingToSign(revoke = false, closeRig = true))
        assertFalse(f.somethingToSign(revoke = false, closeRig = false))
        assertFalse(facts(revoke = null).somethingToSign(revoke = true, closeRig = false))
        assertTrue(facts(revoke = null).somethingToSign(revoke = true, closeRig = true))
    }

    @Test
    fun `the closing line lists only what the signed plan did`() {
        assertEquals("Confirmed on-chain. 0.02000448 SOL back in your wallet from the ORE Automation.", WithdrawCopy.done(revoke, null))
        assertEquals("Confirmed on-chain. Rig closed: 0.00244992 SOL of rent back in your wallet.", WithdrawCopy.done(null, close))
        assertEquals(
            "Confirmed on-chain. 0.02000448 SOL back in your wallet from the ORE Automation. Rig closed: 0.00244992 SOL of rent back in your wallet.",
            WithdrawCopy.done(revoke, close),
        )
    }

    @Test
    fun `every sentence the screen can show passes the honest-copy rule`() {
        val all = buildList {
            for (r in listOf(null, revoke, revoke.copy(headsDown = false))) {
                for (open in listOf(false, true)) {
                    for (c in listOf(null, close, RigClose(1uL, leavesTombstone = false, closesSeekerSeat = true))) {
                        val f = facts(revoke = r, shiftOpen = open, rigClose = c, closeBlock = if (c == null) CloseBlock.BOND_HELD.message else null)
                        add(WithdrawCopy.automation(f)); add(WithdrawCopy.rig(f))
                        WithdrawCopy.revokeDetail(f)?.let(::add); WithdrawCopy.closeDetail(f)?.let(::add)
                        add(WithdrawCopy.done(r, c))
                    }
                }
            }
            CloseBlock.entries.forEach { add(it.message) }
            add(WithdrawCopy.rig(facts(hasRig = false, rigClose = null)))
            addAll(listOf(WithdrawCopy.REVOKE_CHOICE, WithdrawCopy.CLOSE_CHOICE, WithdrawCopy.NOTHING_CHOSEN, WithdrawCopy.NEEDS_WALLET))
            addAll(listOf(true, false).flatMap { a -> listOf(true, false).map { b -> WithdrawCopy.button(a, b) } })
            addAll(
                listOf(
                    WithdrawModel.UNREADABLE, WithdrawModel.OTHER_WALLET, WithdrawModel.NO_WALLET, WithdrawModel.NOTHING_LEFT,
                    WithdrawModel.NOT_LANDED, WithdrawModel.NOT_CONFIRMED,
                ),
            )
        }
        assertTrue(all.size > 80)
        all.forEach { assertEquals(it, emptyList<String>(), HonestCopy.violations(it)) }
    }
}
