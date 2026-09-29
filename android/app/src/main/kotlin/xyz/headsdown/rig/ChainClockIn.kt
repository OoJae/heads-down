package xyz.headsdown.rig

import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.clockin.ClockInRequest
import xyz.headsdown.core.chain.clockin.ClockInService
import xyz.headsdown.core.keys.RigCounter
import xyz.headsdown.core.wallet.WalletAccount
import xyz.headsdown.core.wallet.WalletCapabilities
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.surface.tile.ClockInTransactions
import xyz.headsdown.surface.tile.PreparedClockIn
import xyz.headsdown.surface.widget.RigWidgetUpdates
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Night Shift the tile arms, until the budget / plan screens exist. Numbers follow
 * docs/ECONOMICS.md §5 and ml/forecaster/RESULTS.md: concentrated 0.001 SOL digs on 4 split
 * tiles, a 0.67 SOL/ORE wallet ceiling (Hunter) with a 0.53 SOL/ORE plan (Steady at the
 * snapshot price), 8 hours. The program re-checks every value against the wallet-signed caps.
 */
object ClockInPolicy {
    const val NIGHT_SECONDS = 8L * 3600

    fun nightShift(): ClockInRequest = ClockInRequest(
        shiftBudgetLamports = 20_000_000uL, // 0.02 SOL: at most 20 digs
        weeklyBudgetLamports = 140_000_000uL,
        capMaxCostPerOre = 670_000_000uL,
        planMaxEvCostPerOre = 530_000_000uL,
        windowSeconds = NIGHT_SECONDS,
    )

    fun plannedRounds(windowSeconds: Long): Int = ((windowSeconds + 77) / 78).toInt()
}

/**
 * [ClockInTransactions] over core/chain: reads Config / Rig / Automation, composes the single
 * clock-in transaction, and after confirmation binds the Rig and lifts the message counter to
 * the on-chain `hb_counter`.
 */
@Singleton
class ChainClockIn @Inject constructor(
    private val service: ClockInService,
    private val rigKeys: RigKeyRepository,
    private val counter: RigCounter,
    private val binding: RigBindingStore,
    private val widgets: RigWidgetUpdates,
) : ClockInTransactions {

    override suspend fun prepare(account: WalletAccount, capabilities: WalletCapabilities): PreparedClockIn? {
        // No rig key means nothing could ever heartbeat: fail the session, send nothing.
        val key = checkNotNull(rigKeys.compressedPublicKey()) { "no rig key" }
        val request = ClockInPolicy.nightShift()
        val prepared = service.prepare(Pubkey(account.publicKey), key, request, capabilities) ?: return null
        // Before any message for this Rig is signed, the local sequence must be above chain's.
        counter.raiseFloor(prepared.plan.hbCounterFloor)
        return PreparedClockIn(
            prepared.transactions,
            prepared.lastValidBlockHeight,
            ShiftSpec(
                shiftId = prepared.plan.expectedShiftId.toLong(),
                mode = ShiftMode.NIGHT,
                plannedRounds = ClockInPolicy.plannedRounds(request.windowSeconds),
            ),
        )
    }

    override suspend fun confirmed(account: WalletAccount, prepared: PreparedClockIn): ShiftSpec {
        val authority = Pubkey(account.publicKey)
        binding.save(authority)
        // Re-read the Rig: its shift_id is authoritative (the prediction is the fallback).
        val rig = runCatching { service.readRig(authority) }.getOrNull()
        rig?.let {
            counter.raiseFloor(it.hbCounter)
            // The streak lives on-chain in the Rig; the widget shows the last value read.
            widgets.onStreak(it.streak.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        }
        val shiftId = rig?.shiftId?.toLong()?.takeIf { it >= 0 } ?: prepared.spec.shiftId
        return prepared.spec.copy(shiftId = shiftId)
    }
}
