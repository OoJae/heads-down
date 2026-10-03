package xyz.headsdown.surface.tile

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import xyz.headsdown.core.wallet.HeadsDownWallet
import xyz.headsdown.core.wallet.PreparedTransactions
import xyz.headsdown.core.wallet.WalletAccount
import xyz.headsdown.core.wallet.WalletCapabilities
import xyz.headsdown.core.wallet.WalletResult
import xyz.headsdown.core.wallet.WalletSession
import xyz.headsdown.feature.shift.ShiftController
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.feature.shift.ShiftStatusRepository
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
 *   with err == null** ([HeadsDownWallet.signAndSendInSession]); any other outcome arms nothing.
 * - Recreated mid-flow (process death) it finishes instead of replaying a wallet request.
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
        if (savedInstanceState != null) {
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                when (repository.snapshot.value.state) {
                    ShiftState.Idle, is ShiftState.Broken -> clockIn()
                    is ShiftState.Armed, is ShiftState.Down, is ShiftState.Cooling -> {
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
        val result = wallet.signAndSendInSession(sender) { account, capabilities ->
            clockInTransactions.prepare(account, capabilities)
        }
        when (result) {
            WalletResult.NoWalletInstalled -> toast("Install a Solana wallet (Solflare, Phantom or Seed Vault) to clock in.")
            // A refusal by our own composer says why (it is fixed text); anything else is the wallet's.
            is WalletResult.Failed -> toast(clockInTransactions.refusal() ?: "Wallet: ${result.reason}")
            is WalletResult.Success -> when (val session = result.value) {
                is WalletSession.NothingToSign -> {
                    // Nothing on-chain to arm on this cluster: a zero-SOL focus shift still counts.
                    shifts.arm(ShiftSpec(shiftId = System.currentTimeMillis() / 1000, mode = ShiftMode.FOCUS_ONLY))
                    toast("Rig armed (focus only). Lay your phone face-down.")
                }
                is WalletSession.Submitted ->
                    if (session.report.allConfirmed) {
                        shifts.arm(clockInTransactions.confirmed(session.account, session.prepared))
                        val note = clockInTransactions.note(session.prepared)
                        toast(if (note == null) "Clocked in. Lay your phone face-down." else "Clocked in. $note Lay your phone face-down.")
                    } else {
                        toast("Clock-in did not confirm on-chain. Nothing was armed.")
                    }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
