package xyz.headsdown.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.headsdown.core.chain.registrar.AttestationOutcome
import xyz.headsdown.core.keys.KeySecurityLevel
import xyz.headsdown.rig.RigKeyStatus
import xyz.headsdown.surface.notification.NotificationAccess
import xyz.headsdown.surface.notification.NotificationPermissionPolicy
import xyz.headsdown.surface.notification.NotificationPermissionStep
import xyz.headsdown.surface.tile.TileAddOutcome
import xyz.headsdown.surface.tile.TilePrompt
import xyz.headsdown.ui.setup.SetupActions
import xyz.headsdown.ui.setup.SetupContent
import xyz.headsdown.ui.setup.SetupStep

/**
 * First launch and setup: what this is, then five steps in the order the night loop depends on
 * them: notifications -> exact alarm -> OEM keep-alive -> Quick Settings tile -> rig key.
 *
 * This is the stateful route: it owns the permission launcher and the notification-permission
 * state machine, and hands the layout to the stateless [SetupContent]. [onCreateRigKey] creates
 * the key (attested by the registrar through a wallet sign-in when it can be); the Activity wires
 * it, because the wallet needs its result sender. [onContinue] leaves setup ("Start using Heads
 * Down", "Skip for now"); [onHowItWorks] opens the night-shift introduction.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingState,
    vm: HomeViewModel,
    onContinue: () -> Unit,
    onCreateRigKey: () -> Unit,
    onHowItWorks: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refresh()
    }
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    // The step the person opened out of turn; null follows the first one that is not done.
    var selectedStep by rememberSaveable { mutableStateOf<SetupStep?>(null) }

    SetupContent(
        state = state,
        actions = SetupActions(
            onMarkIntroSeen = vm::markIntroSeen,
            onAllowNotifications = {
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
            },
            onAllowExactAlarm = { context.launch(vm.exactAlarmSettingsIntent()) },
            onOpenKeepAlive = vm::openKeepAlive,
            onConfirmAutostart = vm::confirmAutostart,
            onAddTile = { TilePrompt.request(context, vm::onTileResult) },
            onConfirmTile = { vm.onTileResult(TileAddOutcome.UNSUPPORTED) },
            onCreateRigKey = onCreateRigKey,
            onFinish = onContinue,
            onHowItWorks = onHowItWorks,
        ),
        selectedStep = selectedStep,
        onSelectStep = { selectedStep = it },
        loading = !loaded,
    )
}

internal fun keepAliveSubtitle(state: OnboardingState): String =
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

internal fun Context.launch(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Some OEM builds strip settings screens; the step simply stays open.
    }
}
