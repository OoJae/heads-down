package xyz.headsdown.core.chain.withdraw

import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.accounts.OreAutomation
import xyz.headsdown.core.chain.accounts.RigAccount
import xyz.headsdown.core.chain.accounts.RigTier
import xyz.headsdown.core.chain.ix.HeadsDownInstructions
import xyz.headsdown.core.chain.ix.OreInstructions
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.keys.RigSignalState

/** What the user chose. Nothing happens by default: each part is asked for. */
data class WithdrawRequest(
    /** Close the wallet's ORE Automation: ORE sends every lamport in it back to the wallet. */
    val revoke: Boolean = false,
    /** Close the Rig account: its rent goes back to the wallet. */
    val closeRig: Boolean = false,
    /** 0 = no priority fee (the wallet may add its own). */
    val priorityMicroLamports: ULong = 0uL,
)

/** On-chain state read (owner- and PDA-checked) just before composing. */
class WithdrawChainState(
    /** null: the wallet has no ORE Automation. */
    val automation: OreAutomation?,
    /** Every lamport the Automation account holds: its unspent balance and its rent. */
    val automationLamports: ULong,
    /** null: no Rig for this wallet (never registered, or closed). */
    val rig: RigAccount?,
    val rigLamports: ULong,
    /** A Focus Bond is still locked on the rig's current (or last) shift. */
    val bondHeld: Boolean,
    /** The rent a 32-byte RigTombstone keeps when a rig that was used is closed. */
    val tombstoneRent: ULong,
)

/** What closing the Automation hands back. */
data class Revoke(
    /** What reaches the wallet: every lamport in the Automation account. */
    val lamports: ULong,
    /** The part of it that was waiting to be placed on ORE squares (the rest is the account's rent). */
    val balance: ULong,
    /** False: the Automation names another executor, so it is not set up for Heads Down. */
    val headsDown: Boolean,
)

/** What closing the Rig hands back, and what it leaves. */
data class RigClose(
    val rentBackLamports: ULong,
    /**
     * The rig armed a shift or accepted a signed message: a 32-byte tombstone stays at its
     * address with its own rent, so that no old message or shift id can ever be used again.
     */
    val leavesTombstone: Boolean,
    /** A Seeker-tier rig: its SeekerSeat is closed with it. */
    val closesSeekerSeat: Boolean,
)

/** Why the rig cannot be closed now. Fixed text, safe to show. */
enum class CloseBlock(val message: String) {
    SHIFT_OPEN("A shift is still open on this rig. Clock out first."),
    BOND_HELD("A Focus Bond is still locked on this rig. Clock out first to take it back."),
    NOT_IDLE("The rig is not idle. Clock out first."),
}

/** Why a withdrawal cannot be built. [reason] is fixed text, safe to show. */
class WithdrawRefusedException(val reason: String) : IllegalStateException(reason) {
    companion object {
        const val OTHER_AUTHORITY = "This Rig belongs to a different wallet."
    }
}

class WithdrawPlan(
    val instructions: List<Instruction>,
    /** Non-null: ORE's `automate` with no executor is in the transaction. */
    val revoke: Revoke?,
    /** Non-null: `close_rig` is in the transaction. */
    val rigClose: RigClose?,
) {
    val isEmpty: Boolean get() = instructions.isEmpty()
}

/**
 * Composes the two ways out, each only when asked for:
 *
 * `[ORE automate(executor = none)?] [close_rig?]`
 *
 * - **Revoke** closes the wallet's ORE Automation. ORE sends every lamport in it back to the
 *   wallet (`automate.rs:88-97`), and nothing can be dug for the wallet until an Automation
 *   exists again (the next clock-in creates one). It is never refused: the SOL is the user's.
 * - **Close rig** (`close_rig`, INTERFACE §5 tag 14) returns the Rig account's rent. The program
 *   requires Idle or Frozen. The phone asks for more: no open shift and no Focus Bond still
 *   locked, because closing a rig under either forfeits the bond. A rig that armed a shift or
 *   accepted a signed message leaves a 32-byte tombstone (§12.4), which keeps its own rent.
 *
 * Pure function of its inputs.
 */
object WithdrawComposer {

    /** What a revoke would hand back; null when the wallet has no Automation. */
    fun revoke(authority: Pubkey, state: WithdrawChainState): Revoke? = state.automation?.takeIf { it.authority == authority }?.let {
        Revoke(state.automationLamports, it.balance, headsDown = it.executor == HeadsDownProgram.executor.address)
    }

    /** Why the rig cannot be closed now; null when it can, or when there is no rig. */
    fun closeBlock(state: WithdrawChainState): CloseBlock? {
        val rig = state.rig ?: return null
        return when {
            rig.shiftOpen -> CloseBlock.SHIFT_OPEN
            state.bondHeld -> CloseBlock.BOND_HELD
            rig.state != RigSignalState.IDLE && rig.state != RigSignalState.FROZEN -> CloseBlock.NOT_IDLE
            else -> null
        }
    }

    /** What closing the rig would hand back; null when there is no rig or it cannot be closed now. */
    fun rigClose(state: WithdrawChainState): RigClose? {
        val rig = state.rig ?: return null
        if (closeBlock(state) != null) return null
        // INTERFACE §12.4: a full close only for a rig that never armed and never signed.
        val tombstone = rig.shiftId != 0uL || rig.hbCounter != 0uL
        val kept = if (tombstone) state.tombstoneRent else 0uL
        return RigClose(
            rentBackLamports = if (state.rigLamports > kept) state.rigLamports - kept else 0uL,
            leavesTombstone = tombstone,
            closesSeekerSeat = rig.tier == RigTier.SEEKER,
        )
    }

    fun compose(authority: Pubkey, request: WithdrawRequest, state: WithdrawChainState): WithdrawPlan {
        val rig = state.rig
        if (rig != null && rig.authority != authority) throw WithdrawRefusedException(WithdrawRefusedException.OTHER_AUTHORITY)
        val ixs = mutableListOf<Instruction>()

        val revoke = if (request.revoke) revoke(authority, state) else null
        if (revoke != null) ixs += OreInstructions.revoke(authority)

        var close: RigClose? = null
        if (request.closeRig && rig != null) {
            closeBlock(state)?.let { throw WithdrawRefusedException(it.message) }
            close = rigClose(state)
            ixs += HeadsDownInstructions.closeRig(authority, rig.sgtMint.takeIf { rig.tier == RigTier.SEEKER })
        }
        return WithdrawPlan(ixs, revoke, close)
    }

    /** ORE's automate close and close_rig together stay far below this. */
    const val COMPUTE_UNITS = 60_000L
}
