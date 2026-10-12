package xyz.headsdown.surface.tile

import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import xyz.headsdown.core.wallet.HeadsDownWallet
import xyz.headsdown.core.wallet.PreparedTransactions
import xyz.headsdown.core.wallet.WalletAccount
import xyz.headsdown.core.wallet.WalletCapabilities
import xyz.headsdown.core.wallet.WalletFailures
import xyz.headsdown.core.wallet.WalletResult
import xyz.headsdown.core.wallet.WalletSession
import xyz.headsdown.feature.shift.ShiftController
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.feature.shift.ShiftStatusRepository
import xyz.headsdown.feature.shift.isRunning
import xyz.headsdown.feature.shift.justArmed
import javax.inject.Inject

/**
 * The clock-in transaction the wallet signs (ORE automate + register_rig? + set_caps +
 * arm_shift), built inside the wallet session against a fresh blockhash, plus the shift it arms.
 */
class PreparedClockIn(
    transactions: List<ByteArray>,
    lastValidBlockHeight: Long,
    val spec: ShiftSpec,
    /** What else the transaction does besides arming, in words (a Focus Bond, an unfreeze), or null. */
    val note: String? = null,
) : PreparedTransactions(transactions, lastValidBlockHeight)

/** Builds and finalizes clock-ins. Implemented in the app over core/chain. */
fun interface ClockInTransactions {
    /**
     * Builds the clock-in for [account] in the wallet's preferred transaction version, or
     * returns null when there is nothing to sign on this cluster (heads_down not deployed), in
     * which case a zero-SOL focus shift is armed locally.
     */
    suspend fun prepare(account: WalletAccount, capabilities: WalletCapabilities): PreparedClockIn?

    /**
     * Runs only after every signature confirmed with `err == null`: bind the Rig locally and
     * return the shift to arm (with the on-chain `shift_id`).
     */
    suspend fun confirmed(account: WalletAccount, prepared: PreparedClockIn): ShiftSpec = prepared.spec

    /**
     * Why the last [prepare] built nothing, in fixed words that are safe to show ("Not enough SKR
     * in this wallet for the Focus Bond."), or null when it did not refuse. Never a server's text.
     */
    fun refusal(): String? = null

    /** A line to add after a confirmed clock-in (what else the transaction did), or null. */
    fun note(prepared: PreparedClockIn): String? = null

    /**
     * A clock-in that was handed to the wallet and never heard of again: the wallet sends the
     * transaction itself, so it can land although the session dropped or this app was closed
     * behind the wallet. Returns the shift to arm when the chain shows that clock-in landed for
     * this phone's key and its shift is still open (the Rig is then bound, as after [confirmed]),
     * otherwise null. While the transaction could still land it keeps looking for up to
     * [patienceMillis].
     */
    suspend fun recover(patienceMillis: Long = 0): ShiftSpec? = null

    /**
     * The shift returned by [confirmed] or [recover] has been started. Only now is its record
     * forgotten: a caller that left the foreground could not start it and must find it again.
     */
    fun armed() {}

    /**
     * The wallet itself said it signed nothing for the clock-in [prepare] just built (the user
     * declined). That attempt cannot land, so its record is forgotten and no look at the chain
     * can ever arm it; a record it had replaced, of a clock-in still on its way, comes back.
     */
    suspend fun notSigned() {}
}

/** The trampoline's words that a test reads. */
object TrampolineCopy {
    /** Names the one wallet that has signed for this app, and promises nothing of any other. */
    const val NO_WALLET = "Install a Solana wallet to clock in. This build has been run with Jupiter Mobile."
}

/**
 * Translucent, **non-exported** trampoline between the QS tile (or the home screen) and the
 * wallet. MWA needs a foreground Activity with an `ActivityResultSender` registered in
 * `onCreate`; a TileService cannot host it.
 *
 * Security:
 * - `exported=false`: no other app can start it.
 * - It trusts **no intent extras** and reads nothing from its Intent: the action is decided
 *   solely from local shift state (clock in when cold or frozen, end when running).
 * - A clock-in only arms the shift after the wallet's transactions are **confirmed on-chain
 *   with err == null** ([HeadsDownWallet.signAndSendInSession]), or after the chain shows the
 *   shift this phone prepared armed for this phone's key ([ClockInTransactions.recover]); any
 *   other outcome arms nothing.
 * - Recreated mid-flow (process death, or the phone closing it behind the wallet) it never
 *   replays a wallet request: it only looks on-chain for the clock-in the wallet was handed.
 */
@AndroidEntryPoint
class TrampolineActivity : ComponentActivity() {

    @Inject lateinit var wallet: HeadsDownWallet
    @Inject lateinit var shifts: ShiftController
    @Inject lateinit var repository: ShiftStatusRepository
    @Inject lateinit var clockInTransactions: ClockInTransactions

    private lateinit var sender: ActivityResultSender

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sender = ActivityResultSender(this) // must be registered before the Activity is STARTED
        val recreated = savedInstanceState != null
        lifecycleScope.launch {
            try {
                if (recreated) {
                    // The wallet's answer died with the first instance. The wallet may have sent
                    // the clock-in all the same: it is on its way, so this looks for a while.
                    recovered(RECREATED_PATIENCE_MILLIS)
                    return@launch
                }
                when (val state = repository.snapshot.value.state) {
                    ShiftState.Idle, is ShiftState.Broken -> clockIn()
                    is ShiftState.Armed, is ShiftState.Down, is ShiftState.Cooling ->
                        if (state.justArmed(SystemClock.elapsedRealtime())) {
                            // Armed under the tap that opened this window (picked up from the
                            // chain a moment ago): that tap asked to clock in, not to end.
                            toast("Clocked in. Lay your phone face-down.")
                        } else {
                            shifts.end()
                            toast("Shift ended. Rig cold.")
                        }
                    // The clock-in transaction unfreezes a frozen rig first: the wallet signs both.
                    is ShiftState.Frozen -> clockIn()
                }
            } finally {
                finish()
            }
        }
    }

    /**
     * One wallet session, one approval: authorize, build the clock-in for the authorized
     * account and the wallet's transaction version, sign and send. The shift is armed only when
     * every signature confirmed with `err == null`.
     */
    private suspend fun clockIn() {
        // A clock-in the wallet already sent is armed here; the wallet is not asked for a second.
        if (recovered(RETRY_PATIENCE_MILLIS)) return
        val result = wallet.signAndSendInSession(sender) { account, capabilities ->
            clockInTransactions.prepare(account, capabilities)
        }
        when (result) {
            WalletResult.NoWalletInstalled -> toast(TrampolineCopy.NO_WALLET)
            is WalletResult.Failed -> {
                val armed = if (result.reason in WalletFailures.NOTHING_SIGNED) {
                    // The wallet says it signed nothing: there is no transaction to look for.
                    clockInTransactions.notSigned()
                    false
                } else {
                    // The session can fail after the wallet sent: the chain is asked before saying so.
                    recovered()
                }
                // A refusal by our own composer says why (it is fixed text); anything else is the wallet's.
                if (!armed) {
                    toast(clockInTransactions.refusal() ?: if (result.reason == WalletFailures.RECONNECT) result.reason else "Wallet: ${result.reason}")
                }
            }
            is WalletResult.Success -> when (val session = result.value) {
                is WalletSession.NothingToSign -> {
                    // Nothing on-chain to arm on this cluster: a zero-SOL focus shift still counts.
                    if (start(ShiftSpec(shiftId = System.currentTimeMillis() / 1000, mode = ShiftMode.FOCUS_ONLY))) {
                        toast("Rig armed (focus only). Lay your phone face-down.")
                    }
                }
                is WalletSession.Submitted ->
                    if (session.report.allConfirmed) {
                        // Not in front any more (the wait for confirmation can be long): nothing
                        // starts here, the record stays, and the home screen arms it on its return.
                        if (start(clockInTransactions.confirmed(session.account, session.prepared))) {
                            val note = clockInTransactions.note(session.prepared)
                            toast(if (note == null) "Clocked in. Lay your phone face-down." else "Clocked in. $note Lay your phone face-down.")
                        }
                    } else if (!recovered()) {
                        toast("Clock-in did not confirm on-chain. Nothing was armed.")
                    }
            }
        }
    }

    /**
     * Looks for a clock-in the wallet was handed earlier and, if the chain shows it landed, arms
     * its shift. True when there was one: the wallet is then not asked again, and no failure is
     * shown, whether or not this window was still in front to start the shift itself.
     */
    private suspend fun recovered(patienceMillis: Long = 0): Boolean {
        if (repository.snapshot.value.state.isRunning) return false
        val spec = clockInTransactions.recover(patienceMillis) ?: return false
        if (start(spec)) toast("Clocked in. Lay your phone face-down.")
        return true
    }

    /**
     * Starts the shift, if this window is still in front: a shift can only start from the
     * foreground, and the waits above outlast a press of Home. True when it started; its record
     * is forgotten only then.
     */
    private fun start(spec: ShiftSpec): Boolean {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return false
        if (!shifts.arm(spec)) return false
        clockInTransactions.armed()
        return true
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        /**
         * Back from the wallet without its answer: if the transaction was sent it lands within
         * seconds. Kept short, because nothing under this window can be touched meanwhile; the
         * home screen goes on looking after it.
         */
        const val RECREATED_PATIENCE_MILLIS = 8_000L

        /** "Clock in" tapped again while an earlier clock-in could still land: one short look first. */
        const val RETRY_PATIENCE_MILLIS = 4_000L
    }
}
