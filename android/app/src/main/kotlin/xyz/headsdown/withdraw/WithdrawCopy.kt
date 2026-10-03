package xyz.headsdown.withdraw

import xyz.headsdown.core.chain.withdraw.Revoke
import xyz.headsdown.core.chain.withdraw.RigClose
import xyz.headsdown.core.chain.withdraw.WithdrawPlan
import xyz.headsdown.core.chain.withdraw.WithdrawPreview
import xyz.headsdown.rig.ClockInPolicy

/**
 * What the wallet could take back, reduced to what the screen says. Built from a
 * [WithdrawPreview] (chain state read through the checked decoders), or directly in tests.
 */
data class WithdrawFacts(
    /** What closing the ORE Automation hands back; null: the wallet has none. */
    val revoke: Revoke?,
    val hasRig: Boolean,
    val shiftOpen: Boolean,
    /** What closing the rig hands back; null: there is no rig, or it cannot be closed now. */
    val rigClose: RigClose?,
    /** Why the rig cannot be closed now, in fixed words; null when it can, or there is none. */
    val closeBlock: String?,
) {
    val canRevoke: Boolean get() = revoke != null
    val canCloseRig: Boolean get() = rigClose != null

    /** With these choices the wallet has something to sign. */
    fun somethingToSign(revoke: Boolean, closeRig: Boolean): Boolean = (revoke && canRevoke) || (closeRig && canCloseRig)

    companion object {
        fun from(preview: WithdrawPreview) = WithdrawFacts(
            revoke = preview.revoke,
            hasRig = preview.hasRig,
            shiftOpen = preview.shiftOpen,
            rigClose = preview.rigClose,
            closeBlock = preview.closeBlock?.message,
        )
    }
}

/** The words of the withdraw screen: amounts, what stops, and what cannot be undone. */
object WithdrawCopy {

    const val REVOKE_CHOICE = "Take it back to my wallet"
    const val CLOSE_CHOICE = "Close my rig"
    const val NOTHING_CHOSEN = "Choose above what to do. Nothing is signed until you approve it in your wallet."
    const val NEEDS_WALLET =
        "This phone has no rig bound to it yet. Connect the wallet you clocked in with to see what it can take back."

    fun automation(f: WithdrawFacts): String {
        val r = f.revoke ?: return "No ORE Automation for this wallet: no SOL of yours is waiting to be placed."
        val other = if (r.headsDown) "" else " It is set up for another executor, not for Heads Down."
        return "Your ORE Automation holds ${sol(r.lamports)} SOL: ${sol(r.balance)} SOL not yet placed, and the account's rent. " +
            "It is yours, and only your wallet can take it out.$other"
    }

    /** Beside the revoke choice once it is chosen: what moves and what stops. */
    fun revokeDetail(f: WithdrawFacts): String? {
        val r = f.revoke ?: return null
        val open = if (f.shiftOpen) " Your open shift stays open, but no round is dug in it." else ""
        return "ORE closes the Automation and sends ${sol(r.lamports)} SOL to your wallet. Nothing more is dug for you until your next " +
            "clock-in, which sets it up again.$open"
    }

    fun rig(f: WithdrawFacts): String = when {
        !f.hasRig -> "No rig is registered for this wallet."
        f.rigClose != null -> "Your rig can be closed: ${sol(f.rigClose.rentBackLamports)} SOL of account rent comes back to your wallet."
        else -> "Your rig cannot be closed right now. ${f.closeBlock.orEmpty()}".trim()
    }

    /** Beside the close choice once it is chosen: what is erased, what stays, and that it is final. */
    fun closeDetail(f: WithdrawFacts): String? {
        val c = f.rigClose ?: return null
        val tombstone = if (c.leavesTombstone) {
            " A 32-byte record stays at its address so that no old signed message can ever be used again; it keeps a little rent."
        } else {
            ""
        }
        val seat = if (c.closesSeekerSeat) " Its Seeker seat is closed with it: verify again after you register." else ""
        return "Closing erases the rig's streak and lifetime counts on-chain. You can register again at any clock-in.$tombstone$seat " +
            "This cannot be undone."
    }

    fun button(revoke: Boolean, closeRig: Boolean): String = when {
        revoke && closeRig -> "Take SOL back and close the rig"
        revoke -> "Take SOL back"
        closeRig -> "Close the rig"
        else -> "Nothing chosen"
    }

    /** What a confirmed withdrawal did. [plan] is the one the wallet signed. */
    fun done(plan: WithdrawPlan): String = done(plan.revoke, plan.rigClose)

    fun done(revoke: Revoke?, rigClose: RigClose?): String {
        val parts = buildList {
            add("Confirmed on-chain.")
            revoke?.let { add("${sol(it.lamports)} SOL back in your wallet from the ORE Automation.") }
            rigClose?.let { add("Rig closed: ${sol(it.rentBackLamports)} SOL of rent back in your wallet.") }
        }
        return parts.joinToString(" ")
    }

    private fun sol(lamports: ULong): String = ClockInPolicy.sol(lamports)
}
