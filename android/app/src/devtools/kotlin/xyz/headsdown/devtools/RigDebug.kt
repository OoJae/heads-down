package xyz.headsdown.devtools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.headsdown.BuildConfig
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.accounts.HeadsDownAccounts
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.keys.RigCounter
import xyz.headsdown.feature.shift.CrankLinkMonitor
import xyz.headsdown.feature.shift.ShiftController
import xyz.headsdown.feature.shift.refusalLine
import xyz.headsdown.rig.RigBindingStore
import xyz.headsdown.rig.RigKeyRepository
import xyz.headsdown.rig.VoucherStore
import xyz.headsdown.ui.theme.HdColors
import xyz.headsdown.ui.theme.HeadsDownTheme
import xyz.headsdown.ui.theme.PixelLabel
import javax.inject.Inject

/**
 * DEBUG AND LOCALDEV BUILDS ONLY (src/devtools; release has a stub that answers null). Entry
 * point to the rig debug screen.
 */
object RigDebug {
    fun intent(context: Context): Intent? = Intent(context, RigDebugActivity::class.java)
}

object RigDebugTags {
    const val KEY_HEX = "rig-debug-key-hex"
    const val RIG = "rig-debug-rig"
    const val ATTACH = "rig-debug-attach"
    const val SLAB_LAB = "rig-debug-slab-lab"
}

/** Everything the devstack needs from this phone, on one screen. Public data only. */
data class RigDebugInfo(
    /** The 33-byte compressed rig key, hex, or null before the key exists. */
    val keyHex: String?,
    val authority: String?,
    val rig: String?,
    val voucher: String,
    val counter: String,
    val endpoints: List<Pair<String, String>>,
) {
    /** The command that arms a Rig for this phone's key on the local fork. */
    val clockInCommand: String? get() = keyHex?.let { "scripts/devstack/clock-in.sh $it" }
}

/**
 * The rig P-256 public key (33-byte hex) and Rig address for `scripts/devstack/clock-in.sh`, the
 * voucher, counter, crank acks and endpoints, and "attach": bind this phone to the Rig that
 * clock-in.sh armed (read on-chain, key checked) and start the local shift for its `shift_id`.
 */
@AndroidEntryPoint
class RigDebugActivity : ComponentActivity() {
    @Inject lateinit var keys: RigKeyRepository
    @Inject lateinit var binding: RigBindingStore
    @Inject lateinit var vouchers: VoucherStore
    @Inject lateinit var crank: CrankLinkMonitor
    @Inject lateinit var rpc: SolanaJsonRpc
    @Inject lateinit var counter: RigCounter
    @Inject lateinit var shifts: ShiftController

    private var info by mutableStateOf<RigDebugInfo?>(null)
    private var attachResult by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            finish() // never reachable in release: the class is not even compiled there
            return
        }
        setContent {
            HeadsDownTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val status by crank.status.collectAsStateWithLifecycle()
                    RigDebugScreen(
                        info = info,
                        crankLine = status.refusalLine() ?: "Crank: ${status.accepted} frames accepted" + if (status.configured) "" else " (no crank configured)",
                        attachResult = attachResult,
                        onCopy = ::copy,
                        onAttach = ::attach,
                        onOpenSlabLab = { startActivity(SlabLab.intent(this)) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            info = withContext(Dispatchers.Default) {
                val key = keys.compressedPublicKey()
                val authority = binding.authority()
                RigDebugInfo(
                    keyHex = key?.lowerHex(),
                    authority = authority?.toBase58(),
                    rig = authority?.let { HeadsDownProgram.rig(it).address.toBase58() },
                    voucher = vouchers.forKey(key)?.let { "level ${it.level}, expiry slot ${it.expirySlot}" } ?: "none (guest rig)",
                    counter = counter.current().toString(),
                    endpoints = listOf(
                        "cluster" to BuildConfig.SOLANA_CHAIN,
                        "rpc" to BuildConfig.SOLANA_RPC_URL,
                        "crank" to BuildConfig.CRANK_WS_URL.ifEmpty { "(none: local only)" },
                        "registrar" to BuildConfig.REGISTRAR_URL.ifEmpty { "(none: guest rigs)" },
                        "indexer" to BuildConfig.INDEXER_URL.ifEmpty { "(none: no haul)" },
                        "wallet" to if (BuildConfig.SUBMIT_THROUGH_APP_RPC) "signs only; the app submits" else "signs and sends",
                    ),
                )
            }
        }
    }

    private fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied $label", Toast.LENGTH_SHORT).show()
    }

    /** Reads the Rig of [authorityText] on this cluster and, if it is this phone's, arms it here. */
    private fun attach(authorityText: String) {
        lifecycleScope.launch {
            attachResult = "Reading the Rig…"
            val authority = runCatching { Pubkey.fromBase58(authorityText.trim()) }.getOrNull()
            if (authority == null) {
                attachResult = "That is not a base58 wallet address."
                return@launch
            }
            val decision = runCatching {
                withContext(Dispatchers.IO) {
                    val address = HeadsDownProgram.rig(authority).address
                    val rig = HeadsDownAccounts.rigOrNull(address, rpc.getAccountInfo(address))
                    RigAttach.decide(rig, keys.compressedPublicKey(), System.currentTimeMillis() / 1000)
                }
            }.getOrElse { AttachDecision.Refuse("Could not read the Rig (${it.javaClass.simpleName}). Is the devstack up and phone.sh run?") }
            attachResult = when (decision) {
                is AttachDecision.Refuse -> decision.reason
                is AttachDecision.Arm -> {
                    binding.save(authority)
                    counter.raiseFloor(decision.hbCounter)
                    shifts.arm(decision.spec)
                    refresh()
                    "Attached to shift ${decision.spec.shiftId} (${decision.spec.mode}, lease ${decision.spec.leaseRounds}). Lay the phone face-down."
                }
            }
        }
    }
}

@Composable
private fun RigDebugScreen(
    info: RigDebugInfo?,
    crankLine: String,
    attachResult: String?,
    onCopy: (String, String) -> Unit,
    onAttach: (String) -> Unit,
    onOpenSlabLab: () -> Unit,
) {
    var authority by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("RIG DEBUG · DEVSTACK", style = PixelLabel, color = HdColors.Cooling)
        Text("Rig key and devstack", style = MaterialTheme.typography.headlineSmall)
        // The slab lab: renderer, states and the frame-time readout, on this phone's own GPU.
        OutlinedButton(onClick = onOpenSlabLab, modifier = Modifier.testTag(RigDebugTags.SLAB_LAB)) { Text("Slab lab") }
        if (info == null) {
            Text("Reading…", color = HdColors.AshMuted)
            return@Column
        }

        Label("Rig P-256 public key (33 bytes, compressed)")
        Mono(info.keyHex ?: "No rig key yet: create it in setup.", Modifier.testTag(RigDebugTags.KEY_HEX))
        info.keyHex?.let { hex -> OutlinedButton(onClick = { onCopy("rig key", hex) }) { Text("Copy key") } }
        info.clockInCommand?.let { cmd ->
            Label("On the Mac, with the devstack up")
            Mono(cmd)
            OutlinedButton(onClick = { onCopy("clock-in command", cmd) }) { Text("Copy command") }
        }

        Label("Bound wallet and Rig address")
        Mono(info.authority ?: "Not bound yet: clock in, or attach below.")
        Mono(info.rig ?: "-", Modifier.testTag(RigDebugTags.RIG))
        info.rig?.let { rig -> OutlinedButton(onClick = { onCopy("rig address", rig) }) { Text("Copy rig address") } }

        Label("Registrar voucher")
        Text(info.voucher, color = HdColors.Ash)
        Label("Message counter (next message uses one more)")
        Text(info.counter, color = HdColors.Ash)
        Label("Crank intake")
        Text(crankLine, color = HdColors.Ash)

        Label("Attach to the Rig clock-in.sh armed (paste its authority)")
        OutlinedTextField(value = authority, onValueChange = { authority = it }, singleLine = true, label = { Text("authority (base58)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onAttach(authority) }, enabled = authority.isNotBlank(), modifier = Modifier.testTag(RigDebugTags.ATTACH)) {
            Text("Attach and arm")
        }
        attachResult?.let { Text(it, color = HdColors.Cooling) }

        Label("Endpoints")
        info.endpoints.forEach { (name, value) -> Mono("$name  $value") }
    }
}

@Composable
private fun Label(text: String) = Text(text, color = HdColors.AshMuted, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Mono(text: String, modifier: Modifier = Modifier) = SelectionContainer {
    Text(text, color = HdColors.Ash, fontFamily = FontFamily.Monospace, modifier = modifier)
}
