package xyz.headsdown.feature.reveal

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import xyz.headsdown.feature.reveal.haul.HaulRepository
import xyz.headsdown.feature.reveal.haul.HaulSummary
import xyz.headsdown.feature.reveal.share.RevealShare
import xyz.headsdown.feature.reveal.share.ShareGrid
import xyz.headsdown.feature.reveal.ui.RevealRoute
import xyz.headsdown.feature.reveal.ui.RevealUiState
import xyz.headsdown.surface.haptics.HapticCue
import xyz.headsdown.surface.haptics.HapticMoment
import xyz.headsdown.surface.haptics.Haptics
import java.time.ZoneId
import javax.inject.Inject

/**
 * The morning haul reveal. Opened full-screen over the lock screen by the exact alarm
 * (manifest: `showWhenLocked` + `turnScreenOn`, not exported), or from the home screen.
 *
 * The drumroll plays with the board replay; the Motherlode flourish only if the rig really
 * shared one. Sharing asks to unlock first, because the share sheet cannot show over the lock
 * screen. "Buy the rest at market" is a stub until the Jupiter leg is wired.
 */
@AndroidEntryPoint
class RevealActivity : ComponentActivity() {

    @Inject lateinit var haulRepository: HaulRepository
    @Inject lateinit var haptics: Haptics

    private var state by mutableStateOf<RevealUiState>(RevealUiState.Loading)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        NotificationManagerCompat.from(this).cancel(RevealAlarmReceiver.NOTIFICATION_ID)
        HighRefreshRate.request(this)
        lifecycleScope.launch {
            val haul = runCatching { haulRepository.latest() }.getOrNull()
            state = if (haul == null) RevealUiState.NoHaul else RevealUiState.Ready(haul)
        }
        // "Remove animations" in accessibility settings: show the final board at once.
        val animate = ValueAnimator.areAnimatorsEnabled()
        setContent {
            RevealRoute(
                state = state,
                zone = ZoneId.systemDefault(),
                animate = animate,
                onReplayStarted = { haptics.play(HapticCue.REVEAL_DRUMROLL, HapticMoment.REVEAL) },
                onReplayFinished = { haul ->
                    if (haul.sharedMotherlode) haptics.play(HapticCue.MOTHERLODE_FLOURISH, HapticMoment.REVEAL)
                },
                onBuyRest = { /* Stub: the Jupiter buy leg is wired later. The screen says nothing was bought. */ },
                onShare = ::share,
                onDone = ::finish,
            )
        }
    }

    private fun share(haul: HaulSummary) {
        val open = {
            try {
                startActivity(RevealShare.chooser(this, ShareGrid.from(haul), haul.shiftId))
            } catch (_: ActivityNotFoundException) {
                // No app can receive an image: nothing to do.
            }
        }
        val keyguard = getSystemService<KeyguardManager>()
        if (keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = open()
                },
            )
        } else {
            open()
        }
    }
}
