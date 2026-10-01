package xyz.headsdown.devtools

import xyz.headsdown.core.chain.accounts.RigAccount
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.core.keys.ShiftPlan
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.rig.ClockInPolicy

/** What attaching this phone to a Rig armed by `scripts/devstack/clock-in.sh` would do. */
sealed interface AttachDecision {
    /** Bind to the rig, raise the counter above [hbCounter], and arm [spec] locally. */
    data class Arm(val spec: ShiftSpec, val hbCounter: ULong) : AttachDecision

    data class Refuse(val reason: String) : AttachDecision
}

/**
 * DEBUG / LOCALDEV ONLY. The devstack's `clock-in.sh <p256 hex>` arms a Rig for this phone's key
 * with a Mac-held dev wallet; the phone then only streams heartbeats. This decides, from the Rig
 * as read on-chain, whether the phone may attach to it: the key must be this phone's, and the
 * shift must be open and inside its plan window. Heartbeats then bind to the on-chain `shift_id`
 * and the plan's lease.
 */
object RigAttach {
    private val LIVE = setOf(RigSignalState.ARMED, RigSignalState.DOWN, RigSignalState.COOLING)

    fun decide(rig: RigAccount?, myKey: ByteArray?, nowUnix: Long): AttachDecision {
        if (myKey == null) return AttachDecision.Refuse("Create the rig key first (setup, step 5).")
        if (rig == null) return AttachDecision.Refuse("No Rig for that wallet on this cluster. Run scripts/devstack/clock-in.sh with this phone's key.")
        if (!rig.p256Pubkey.toByteArray().contentEquals(myKey)) {
            return AttachDecision.Refuse("That Rig is registered with another P-256 key, not this phone's.")
        }
        if (rig.state !in LIVE || !rig.shiftOpen) {
            return AttachDecision.Refuse("That Rig has no shift armed (state ${rig.state}). Run clock-in.sh again.")
        }
        if (nowUnix > rig.planWindowEndTs) return AttachDecision.Refuse("That Rig's plan window has ended. Run clock-in.sh again.")
        val mode = when {
            rig.planFlags and ShiftPlan.FLAG_FOCUS_ONLY != 0 -> ShiftMode.FOCUS_ONLY
            rig.planFlags and ShiftPlan.FLAG_DAY != 0 -> ShiftMode.DAY
            else -> ShiftMode.NIGHT
        }
        val shiftId = rig.shiftId.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()
            ?: return AttachDecision.Refuse("That Rig's shift id is out of range.")
        val spec = ShiftSpec(
            shiftId = shiftId,
            mode = mode,
            plannedRounds = ClockInPolicy.plannedRounds((rig.planWindowEndTs - nowUnix).coerceAtLeast(78)),
            leaseRounds = rig.planLeaseRounds.coerceIn(1, 3),
            windowEndUnix = rig.planWindowEndTs,
        )
        return AttachDecision.Arm(spec, rig.hbCounter)
    }
}

/** Hex of the 33-byte compressed key, lower case (what clock-in.sh takes). */
fun ByteArray.lowerHex(): String = joinToString("") { "%02x".format(it) }
