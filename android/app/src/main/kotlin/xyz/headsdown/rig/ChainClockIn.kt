package xyz.headsdown.rig

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.headsdown.BuildConfig
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.clockin.ClockInComposer
import xyz.headsdown.core.chain.clockin.ClockInRefusedException
import xyz.headsdown.core.chain.clockin.ClockInRequest
import xyz.headsdown.core.chain.clockin.ClockInService
import xyz.headsdown.core.keys.RigCounter
import xyz.headsdown.core.wallet.WalletAccount
import xyz.headsdown.core.wallet.WalletCapabilities
import xyz.headsdown.feature.reveal.RevealScheduler
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.surface.tile.ClockInTransactions
import xyz.headsdown.surface.tile.PreparedClockIn
import xyz.headsdown.surface.widget.RigWidgetUpdates
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the tile arms, until the budget / plan screens exist. The defaults follow
 * docs/ECONOMICS.md §5 and ml/forecaster/RESULTS.md: a Night Shift of concentrated 0.001 SOL digs
 * on 4 split tiles, a 0.67 SOL/ORE wallet ceiling (Hunter) with a 0.53 SOL/ORE plan (Steady at the
 * snapshot price), 8 hours, lease 1. Every value is a build property for demo takes
 * (`-Pheadsdown.policy.*`, see app/build.gradle.kts); the program re-checks all of it against the
 * wallet-signed caps. Budgets are SOL placed; the executor fee is added inside the caps.
 */
data class ClockInPolicy(
    val day: Boolean = false,
    val windowSeconds: Long = NIGHT_SECONDS,
    val leaseRounds: Int = 1,
    val planMaxEvCostPerOre: ULong = 530_000_000uL,
    val capMaxCostPerOre: ULong = 670_000_000uL,
    val digLamports: ULong = 1_000_000uL,
    val splitTiles: Int = 4,
    val soloTiles: Int = 0,
    val shiftBudgetLamports: ULong = 20_000_000uL, // 0.02 SOL: at most 20 digs
    val weeklyBudgetLamports: ULong = 140_000_000uL,
) {
    val mode: ShiftMode get() = if (day) ShiftMode.DAY else ShiftMode.NIGHT

    /**
     * What a clock-in with this policy can move, in words, for the screen that opens the wallet.
     * Every number comes from the policy alone ([ClockInComposer.maxDeposit] is the composer's own
     * bound), so it is true whatever the network answers: the wallet prompt is not the first place
     * the user sees an amount.
     */
    fun disclosure(focusBondSkr: ULong = 0uL): String {
        val request = request()
        val bond = if (focusBondSkr == 0uL) "" else " It also locks your ${FocusBondSetting.label(focusBondSkr)} Focus Bond."
        return "This shift can place up to ${sol(shiftBudgetLamports)} SOL on ORE squares (${sol(weeklyBudgetLamports)} SOL a week). " +
            "Clock-in moves at most ${sol(ClockInComposer.maxDeposit(request))} SOL into your own ORE Automation; " +
            "the first one also pays one-time account rent. Sealing a shift stores a small log on-chain: your wallet pays its rent, " +
            "which can be sent back to it 30 days later." + bond
    }

    fun request(): ClockInRequest = ClockInRequest(
        shiftBudgetLamports = shiftBudgetLamports,
        weeklyBudgetLamports = weeklyBudgetLamports,
        capMaxCostPerOre = capMaxCostPerOre,
        planMaxEvCostPerOre = planMaxEvCostPerOre,
        windowSeconds = windowSeconds,
        digLamports = digLamports,
        splitTiles = splitTiles,
        soloTiles = soloTiles,
        leaseRounds = leaseRounds,
        day = day,
    )

    companion object {
        const val NIGHT_SECONDS = 8L * 3600

        /** The policy this APK was built with (`-Pheadsdown.policy.*`). */
        fun fromBuildConfig() = ClockInPolicy(
            day = BuildConfig.POLICY_DAY,
            windowSeconds = BuildConfig.POLICY_WINDOW_SECONDS,
            leaseRounds = BuildConfig.POLICY_LEASE_ROUNDS,
            planMaxEvCostPerOre = BuildConfig.POLICY_PLAN_MAX_EV_COST.toULong(),
            capMaxCostPerOre = BuildConfig.POLICY_CAP_MAX_COST.toULong(),
            digLamports = BuildConfig.POLICY_DIG_LAMPORTS.toULong(),
            splitTiles = BuildConfig.POLICY_SPLIT_TILES,
            soloTiles = BuildConfig.POLICY_SOLO_TILES,
            shiftBudgetLamports = BuildConfig.POLICY_SHIFT_BUDGET_LAMPORTS.toULong(),
            weeklyBudgetLamports = BuildConfig.POLICY_WEEKLY_BUDGET_LAMPORTS.toULong(),
        )

        fun plannedRounds(windowSeconds: Long): Int = ((windowSeconds + 77) / 78).toInt()

        /** What a confirmed clock-in did besides arming the shift, or null when that is all it did. */
        fun noteFor(unfroze: Boolean, bondLocked: ULong, bondReleased: ULong, bondDeferred: Boolean): String? {
            val parts = buildList {
                if (unfroze) add("Rig unfrozen.")
                if (bondReleased > 0uL) add("Last shift's bond is back: ${FocusBondSetting.label(bondReleased)}.")
                if (bondLocked > 0uL) add("Focus Bond locked: ${FocusBondSetting.label(bondLocked)}.")
                if (bondDeferred) add("No Focus Bond this time: the first clock-in had no room for it.")
            }
            return parts.joinToString(" ").ifEmpty { null }
        }

        /** Lamports as SOL without trailing zeros: 20_000_000 is "0.02", 22_000_000 "0.022". */
        fun sol(lamports: ULong): String {
            val whole = lamports / 1_000_000_000uL
            val frac = (lamports % 1_000_000_000uL).toString().padStart(9, '0').trimEnd('0')
            return if (frac.isEmpty()) whole.toString() else "$whole.$frac"
        }
    }
}

/**
 * [ClockInTransactions] over core/chain: reads Config / Rig / Automation, composes the single
 * clock-in transaction (with the registrar voucher for this key, when one is held), and after
 * confirmation binds the Rig and lifts the message counter to the on-chain `hb_counter`.
 *
 * What it hands the wallet it also writes down ([PendingClockInStore]): the wallet sends the
 * transaction itself, and when its answer never reaches this app, [recover] finds the clock-in
 * on-chain instead of the user being asked to approve, and pay for, a second one.
 */
@Singleton
class ChainClockIn @Inject constructor(
    private val service: ClockInService,
    private val rigKeys: RigKeyRepository,
    private val counter: RigCounter,
    private val binding: RigBindingStore,
    private val widgets: RigWidgetUpdates,
    private val vouchers: VoucherStore,
    private val policy: ClockInPolicy,
    private val focusBond: FocusBondSetting,
    private val reveal: RevealScheduler,
    private val pending: PendingClockInStore,
) : ClockInTransactions {

    @Volatile private var lastRefusal: String? = null

    /** One look at the chain at a time: the home screen and the trampoline both ask. */
    private val recovering = Mutex()

    /** The record the last [prepare] wrote over, if any: it comes back when the wallet signs nothing. */
    @Volatile private var replaced: PendingClockIn? = null

    override fun refusal(): String? = lastRefusal

    override fun note(prepared: PreparedClockIn): String? = prepared.note

    override suspend fun prepare(account: WalletAccount, capabilities: WalletCapabilities): PreparedClockIn? {
        // No rig key means nothing could ever heartbeat: fail the session, send nothing.
        lastRefusal = null
        val key = checkNotNull(rigKeys.compressedPublicKey()) { "no rig key" }
        // A Night Shift ends just before the user's own alarm, so waking up is not a BREAK.
        val window = ShiftWindow.seconds(policy, System.currentTimeMillis(), reveal.nextSystemAlarmWallMillis())
        val request = policy.request().copy(windowSeconds = window, focusBondSkr = focusBond.amount.value)
        val authority = Pubkey(account.publicKey)
        // The composer uses it only if it covers this wallet and key and the program will accept it.
        val voucher = vouchers.forKey(key)
        val prepared = try {
            service.prepare(authority, key, request, capabilities, voucher) ?: return null
        } catch (e: ClockInRefusedException) {
            // Fixed text from our own composer: the trampoline shows it instead of a generic failure.
            lastRefusal = e.reason.message
            throw e
        }
        // The read this transaction was built from is newer than the last look at the chain. If
        // it shows the clock-in this phone already handed the wallet, landed and open, building
        // over it would seal that shift and charge a second one. Nothing is signed: the session
        // ends with nothing sent, the record stays, and the look after it arms that shift.
        val waiting = pending.load()
        if (waiting != null && ClockInRecovery.landedUnder(waiting, authority, key, prepared.plan.endsPreviousShift, prepared.plan.expectedShiftId)) {
            lastRefusal = ALREADY_LANDED
            throw IllegalStateException("the clock-in already handed to the wallet has landed")
        }
        // Before any message for this Rig is signed, the local sequence must be above chain's.
        counter.raiseFloor(prepared.plan.hbCounterFloor)
        val spec = ShiftSpec(
            shiftId = prepared.plan.expectedShiftId.toLong(),
            mode = policy.mode,
            plannedRounds = ClockInPolicy.plannedRounds(request.windowSeconds),
            // Heartbeats lease what the plan allows; BREAKs stop at the plan window's end.
            leaseRounds = prepared.plan.plan.leaseRounds,
            windowEndUnix = prepared.plan.plan.windowEndTs,
        )
        // Written down before the wallet is asked to sign: from here the transaction can land
        // whether or not this app is still there to hear so.
        replaced = waiting
        pending.save(PendingClockIn(authority, key, spec, prepared.lastValidBlockHeight, System.currentTimeMillis() / 1000))
        return PreparedClockIn(
            prepared.transactions,
            prepared.lastValidBlockHeight,
            note = ClockInPolicy.noteFor(prepared.plan.unfreezes, prepared.plan.bondLocked, prepared.plan.bondReleased, prepared.bondDeferred),
            spec = spec,
        )
    }

    override suspend fun confirmed(account: WalletAccount, prepared: PreparedClockIn): ShiftSpec {
        val authority = Pubkey(account.publicKey)
        binding.save(authority)
        // The shift id the phone signs for is the one this clock-in armed: the Rig's id before the
        // transaction, plus one. A re-read can only confirm it. It is never adopted from the RPC:
        // a node answering with a later id would have the phone sign BREAKs for a shift that has
        // not started, which anyone could replay into it.
        val rig = runCatching { service.readRig(authority) }.getOrNull()
        rig?.takeIf { it.shiftId.toLong() == prepared.spec.shiftId }?.let {
            counter.raiseFloor(it.hbCounter)
            // The streak lives on-chain in the Rig; the widget shows the last value read.
            widgets.onStreak(it.streak.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        }
        return prepared.spec
    }

    override suspend fun recover(patienceMillis: Long): ShiftSpec? = recovering.withLock { look(patienceMillis) }

    private suspend fun look(patienceMillis: Long): ShiftSpec? {
        val started = SystemClock.elapsedRealtime()
        while (true) {
            val waiting = pending.load() ?: return null
            val decision = try {
                // The height first, then the Rig: a transaction that does not show in a read made
                // after the height had passed its last valid block cannot land any more.
                val height = runCatching { service.blockHeight() }.getOrNull()
                val rig = service.readRig(waiting.authority)
                ClockInRecovery.decide(waiting, rig, rigKeys.compressedPublicKey(), System.currentTimeMillis() / 1000, height)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Recovery.Wait // the chain could not be read: nothing is known, nothing is forgotten
            }
            when (decision) {
                is Recovery.Arm -> {
                    // What a confirmed clock-in does: bind the Rig, lift the counter above chain's.
                    binding.save(waiting.authority)
                    counter.raiseFloor(decision.hbCounter)
                    widgets.onStreak(decision.streak.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
                    // The record stays until the shift has been started ([armed]): a caller that
                    // is no longer in the foreground cannot start it, and must find it again.
                    return decision.spec
                }
                is Recovery.Drop -> {
                    pending.clear()
                    return null
                }
                Recovery.Wait -> {
                    if (SystemClock.elapsedRealtime() - started >= patienceMillis) return null
                    delay(RECOVER_POLL_MILLIS)
                }
            }
        }
    }

    override fun armed() = pending.clear()

    override suspend fun notSigned() = recovering.withLock {
        // The attempt the wallet declined cannot land. One it had replaced still can.
        replaced?.let(pending::save) ?: pending.clear()
        replaced = null
    }

    private companion object {
        const val RECOVER_POLL_MILLIS = 2_000L

        /** Shown only if the look that follows cannot read the chain; fixed words, like every refusal. */
        const val ALREADY_LANDED = "Your last clock-in went through. Open Heads Down and it is picked up."
    }
}
