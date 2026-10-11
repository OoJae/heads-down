package xyz.headsdown.feature.reveal

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.headsdown.feature.reveal.haul.FakeHaulRepository
import xyz.headsdown.feature.reveal.haul.HaulRepository
import xyz.headsdown.feature.reveal.haul.HaulSummary
import xyz.headsdown.feature.reveal.haul.NightReport
import xyz.headsdown.feature.reveal.haul.NightStage
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
 * shared one. Sharing and clocking out ask to unlock first, because neither the share sheet nor
 * a wallet can show over the lock screen. "Buy the rest at market" is offered only for a haul
 * with a nightly ORE target, which none carries yet, and does nothing: buying ORE is on the
 * clock-out sheet.
 *
 * At the alarm the night's shift is still open on-chain, and a haul is read from a sealed shift.
 * The reveal then says so and offers the clock-out; when the user comes back from it, it reads
 * that shift's haul, asking again until ORE has settled the last round it dug. An older shift's
 * haul is never shown in the night's place.
 */
@AndroidEntryPoint
class RevealActivity : ComponentActivity() {

    @Inject lateinit var haulRepository: HaulRepository
    @Inject lateinit var haptics: Haptics
    @Inject lateinit var clockOut: ClockOutNavigator

    private var state by mutableStateOf<RevealUiState>(RevealUiState.Loading)
    private var reading: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // The reveal is dark whatever the phone's own theme: light icons over the pit on both bars.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        NotificationManagerCompat.from(this).cancel(RevealAlarmReceiver.NOTIFICATION_ID)
        HighRefreshRate.request(this)
        read()
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
                onBuyRest = { /* Not connected: the screen says nothing was bought, and where buying is. */ },
                onShare = ::share,
                onDone = ::finish,
                onOpenExplorer = ::openExplorer,
                onClockOut = { whenUnlocked { startActivity(clockOut.intent(this)) } },
                // The labelled sample, only on request and only when there is no real haul.
                onShowSample = {
                    state = RevealUiState.Ready(FakeHaulRepository.sampleNight(System.currentTimeMillis(), ZoneId.systemDefault()))
                },
            )
        }
    }

    /**
     * Back from the clock-out (or the wallet, or the lock screen): the shift this page was waiting
     * on may be sealed now, and a read that failed may succeed. A haul on the screen stays.
     */
    override fun onResume() {
        super.onResume()
        if (state !is RevealUiState.Ready && reading?.isActive != true) read()
    }

    /** Nothing is asked for while the page is not on screen. */
    override fun onStop() {
        reading?.cancel()
        super.onStop()
    }

    /**
     * Reads the night. No indexer, no rig, no finished shift: "no haul yet", never a sample by
     * default. A sealed shift whose haul is not final, and a chain that could not be read, are
     * asked for again every [READ_EVERY_MILLIS] until [READ_FOR_MILLIS] have passed.
     */
    private fun read() {
        reading?.cancel()
        reading = lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            while (true) {
                val report = try {
                    haulRepository.night()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val more = SystemClock.elapsedRealtime() - started < READ_FOR_MILLIS
                val again = when (report) {
                    is NightReport.Haul -> {
                        state = RevealUiState.Ready(report.summary)
                        false
                    }
                    is NightReport.NotSealed -> {
                        state = RevealUiState.Night(report.night, NightStage.NOT_SEALED)
                        false
                    }
                    is NightReport.NotReadYet -> {
                        state = RevealUiState.Night(report.night, if (more) NightStage.READING else NightStage.NOT_READY)
                        true
                    }
                    is NightReport.Unread -> {
                        state = RevealUiState.Night(report.night, NightStage.UNCHECKED)
                        true
                    }
                    NightReport.None -> {
                        state = RevealUiState.NoHaul
                        false
                    }
                    // The read itself failed. What the page already says about a night stays; a page
                    // that has nothing yet says so, and the read is tried again.
                    null -> {
                        if (state is RevealUiState.Loading) state = RevealUiState.NoHaul
                        true
                    }
                }
                if (!again || !more) break
                delay(READ_EVERY_MILLIS)
            }
        }
    }

    /** Explorer links are https-only by construction (contract B parser, HaulSummary). */
    private fun openExplorer(url: String) {
        if (!url.startsWith("https://")) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            // No browser: nothing to do.
        }
    }

    private fun share(haul: HaulSummary) = whenUnlocked {
        try {
            startActivity(RevealShare.chooser(this, ShareGrid.from(haul), haul.shiftId))
        } catch (_: ActivityNotFoundException) {
            // No app can receive an image: nothing to do.
        }
    }

    /** Runs [open] now, or after the user unlocks when the reveal is showing over the lock screen. */
    private fun whenUnlocked(open: () -> Unit) {
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

    private companion object {
        /** How often a sealed shift's haul is asked for while it is not final. */
        const val READ_EVERY_MILLIS = 5_000L

        /** For how long, by the phone's own clock: a round resets about a minute after it ends, and the indexer follows within seconds. */
        const val READ_FOR_MILLIS = 180_000L
    }
}
