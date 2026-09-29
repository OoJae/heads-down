package xyz.headsdown.surface.tile

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import xyz.headsdown.core.wallet.HeadsDownWallet
import xyz.headsdown.core.wallet.WalletAccount
import xyz.headsdown.core.wallet.WalletResult
import xyz.headsdown.feature.shift.ShiftController
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.feature.shift.ShiftStatusRepository
import javax.inject.Inject

/** A clock-in the wallet must sign: refuel + arm_shift, built against a fresh blockhash. */
class PreparedClockIn(
    val spec: ShiftSpec,
    val transactions: List<ByteArray>,
    val lastValidBlockHeight: Long,
)

/**
 * Builds the clock-in transactions for [account], or returns null when there is nothing to
 * sign yet (no deployed program / focus-only), in which case a zero-SOL focus shift is armed.
 */
fun interface ClockInTransactions {
    suspend fun prepare(account: WalletAccount): PreparedClockIn?
}

/**
 * Translucent, **non-exported** trampoline between the QS tile (or the home screen) and the
 * wallet. MWA needs a foreground Activity with an `ActivityResultSender` registered in
 * `onCreate`; a TileService cannot host it.
 *
 * Security:
 * - `exported=false`: no other app can start it.
 * - It trusts **no intent extras** and reads nothing from its Intent: the action is decided
 *   solely from local shift state (clock in when cold, end when running).
 * - A clock-in only arms the shift after the wallet's transactions are **confirmed on-chain
 *   with err == null** ([HeadsDownWallet.signAndSend]); any other outcome arms nothing.
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
                    is ShiftState.Frozen -> toast("Rig frozen. Open Heads Down to unfreeze with your wallet.")
                }
            } finally {
                finish()
            }
        }
    }

    private suspend fun clockIn() {
        val account = when (val connected = wallet.connect(sender)) {
            is WalletResult.Success -> connected.value
            WalletResult.NoWalletInstalled -> return toast("Install a Solana wallet (Solflare, Phantom or Seed Vault) to clock in.")
            is WalletResult.Failed -> return toast("Wallet: ${connected.reason}")
        }
        val prepared = clockInTransactions.prepare(account)
        if (prepared == null) {
            // Nothing to sign yet: a focus-only shift (zero SOL) still counts for the streak.
            shifts.arm(ShiftSpec(shiftId = System.currentTimeMillis() / 1000, mode = ShiftMode.FOCUS_ONLY))
            return toast("Rig armed (focus only). Lay your phone face-down.")
        }
        when (val sent = wallet.signAndSend(sender, prepared.transactions, prepared.lastValidBlockHeight)) {
            is WalletResult.Success ->
                if (sent.value.allConfirmed) {
                    shifts.arm(prepared.spec)
                    toast("Clocked in. Lay your phone face-down.")
                } else {
                    toast("Clock-in did not confirm on-chain. Nothing was armed.")
                }
            WalletResult.NoWalletInstalled -> toast("No wallet found.")
            is WalletResult.Failed -> toast("Wallet: ${sent.reason}")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
