package xyz.headsdown.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import xyz.headsdown.core.chain.registrar.AttestationOutcome
import xyz.headsdown.core.keys.KeySecurityLevel
import xyz.headsdown.feature.oemkeepalive.KeepAliveGuide
import xyz.headsdown.feature.oemkeepalive.KeepAliveStep
import xyz.headsdown.rig.RigKeyStatus
import xyz.headsdown.surface.notification.NotificationAccess
import xyz.headsdown.surface.notification.NotificationPermissionPolicy
import xyz.headsdown.surface.notification.NotificationPermissionStep
import xyz.headsdown.surface.tile.TilePrompt
import xyz.headsdown.ui.theme.HdColors

/**
 * Five steps, in the order the night loop depends on them:
 * notifications -> exact alarm -> OEM keep-alive -> Quick Settings tile -> rig key.
 *
 * [onCreateRigKey] creates the key (attested by the registrar through a wallet sign-in when it
 * can be); the Activity wires it, because the wallet needs its result sender.
 */
@Composable
fun OnboardingScreen(state: OnboardingState, vm: HomeViewModel, onContinue: () -> Unit, onCreateRigKey: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refresh()
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Set up your rig", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Five steps so your phone can take the night shift. If anything fails, the rig simply goes cold: nothing is spent.",
            color = HdColors.AshMuted,
        )

        StepCard(1, "Notifications", "Your shift shows as an ongoing notification, and the morning reveal needs one.", state.notificationsGranted) {
            Button(onClick = {
                val step = NotificationPermissionPolicy.nextStep(
                    sdkInt = HomeViewModel.sdkInt,
                    granted = state.notificationsGranted,
                    shouldShowRationale = activity != null &&
                        ActivityCompat.shouldShowRequestPermissionRationale(activity, NotificationPermissionPolicy.PERMISSION),
                    askedBefore = vm.notificationAskedBefore,
                )
                when (step) {
                    NotificationPermissionStep.GRANTED -> vm.refresh()
                    NotificationPermissionStep.REQUEST, NotificationPermissionStep.EXPLAIN_THEN_REQUEST -> {
                        vm.onNotificationPermissionAsked()
                        notificationLauncher.launch(NotificationPermissionPolicy.PERMISSION)
                    }
                    NotificationPermissionStep.OPEN_SETTINGS -> context.launch(NotificationAccess.appNotificationSettings(context))
                }
            }) { Text("Allow notifications") }
        }

        StepCard(2, "Morning alarm", "Your haul reveal opens right after your own alarm rings.", state.exactAlarmsAllowed) {
            Button(onClick = { context.launch(vm.exactAlarmSettingsIntent()) }) { Text("Allow exact alarm") }
        }

        StepCard(3, "Keep the shift alive", keepAliveSubtitle(state), state.keepAliveDone) {
            val oem = state.oem
            if (oem != null) {
                KeepAliveGuide.steps(oem).forEach { guide ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(guide.title, style = MaterialTheme.typography.titleMedium)
                        Text(guide.detail, color = HdColors.AshMuted, style = MaterialTheme.typography.bodyMedium)
                        guide.action?.let { action ->
                            OutlinedButton(onClick = { vm.openKeepAlive(action) }, modifier = Modifier.padding(top = 6.dp)) {
                                Text(if (action == KeepAliveStep.IGNORE_BATTERY_OPTIMIZATIONS) "Allow background running" else "Open settings")
                            }
                        }
                        if (guide.action == KeepAliveStep.AUTOSTART && !state.autostartConfirmed) {
                            TextButton(onClick = vm::confirmAutostart) { Text("I turned Autostart on") }
                        }
                    }
                }
            }
        }

        StepCard(4, "Quick Settings tile", "Clock in from anywhere: swipe down, tap Heads Down next to Do Not Disturb.", state.tileAdded) {
            if (state.tilePromptSupported) {
                Button(onClick = { TilePrompt.request(context, vm::onTileResult) }) { Text("Add the tile") }
            } else {
                Text("Swipe down twice, tap the pencil, and drag Heads Down next to Do Not Disturb.", color = HdColors.AshMuted)
                TextButton(onClick = { vm.onTileResult(xyz.headsdown.surface.tile.TileAddOutcome.UNSUPPORTED) }) { Text("Done") }
            }
        }

        StepCard(5, "Create your rig key", rigKeySubtitle(state.rigKey, state.attestation), state.rigKeyReady) {
            Button(onClick = onCreateRigKey, enabled = !state.creatingKey) {
                Text(if (state.creatingKey) "Creating the key…" else "Create rig key")
            }
        }

        Spacer(Modifier.height(4.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.allDone) "Start using Heads Down" else "Skip for now")
        }
    }
}

private fun keepAliveSubtitle(state: OnboardingState): String =
    if (state.oem?.needsAutostartStep == true) {
        "HyperOS closes background apps aggressively. These settings keep your shift running until morning."
    } else {
        "Android may pause background apps. This keeps your shift running until morning."
    }

internal fun rigKeySubtitle(status: RigKeyStatus, attestation: AttestationOutcome? = null): String = when (status) {
    RigKeyStatus.Missing ->
        "A P-256 key that never leaves this phone's Android Keystore signs every heartbeat. Your wallet may ask you to sign in, so the registrar can vouch for the key."
    is RigKeyStatus.Ready -> {
        val where = when (status.securityLevel) {
            KeySecurityLevel.STRONGBOX -> "StrongBox"
            KeySecurityLevel.TRUSTED_ENVIRONMENT -> "TEE"
            KeySecurityLevel.SOFTWARE_OR_UNKNOWN -> "software (not accepted for a verified rig)"
        }
        val vouched = when {
            status.voucherLevel == 2 -> "registrar-attested (StrongBox)"
            status.voucherLevel == 1 -> "registrar-attested (TEE)"
            attestation == AttestationOutcome.LEVEL_ZERO -> "registrar could not attest this key: guest rig"
            attestation == AttestationOutcome.SIGN_IN_DECLINED -> "no wallet sign-in: guest rig"
            attestation == AttestationOutcome.REJECTED -> "registrar refused the attestation: guest rig"
            else -> "guest rig (registrar not reached)"
        }
        "Rig key ready · $where · $vouched · ${status.attestationCertificates} attestation certs · ${status.fingerprint}…"
    }
    is RigKeyStatus.Failed -> "Key creation failed (${status.reason}). Try again."
}

/**
 * Who signs the heartbeats, for the home screen's footnote. It names secure hardware only when
 * Android reports the key lives there: an emulator, or a phone without a TEE, holds it in software.
 */
internal fun rigKeyLine(status: RigKeyStatus): String = when (status) {
    is RigKeyStatus.Ready -> when (status.securityLevel) {
        KeySecurityLevel.STRONGBOX,
        KeySecurityLevel.TRUSTED_ENVIRONMENT,
        -> "Heartbeats are signed by a key held in this phone's secure hardware."
        KeySecurityLevel.SOFTWARE_OR_UNKNOWN ->
            "Heartbeats are signed by a key in this phone's Android Keystore, which is not hardware-backed on this device."
    }
    else -> "Create your rig key to sign heartbeats."
}

@Composable
private fun StepCard(index: Int, title: String, subtitle: String, done: Boolean, actions: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (done) HdColors.EmberDim else HdColors.CharcoalOutline),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (done) HdColors.Ember else HdColors.CharcoalOutline,
                    modifier = Modifier.size(28.dp),
                ) {
                    Text(
                        if (done) "✓" else "$index",
                        modifier = Modifier.padding(top = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = if (done) HdColors.Charcoal else HdColors.Ash,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(subtitle, color = HdColors.AshMuted, style = MaterialTheme.typography.bodyMedium)
            if (!done) actions()
        }
    }
}

internal fun Context.launch(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Some OEM builds strip settings screens; the step simply stays open.
    }
}
