package xyz.headsdown.surface.notification

/** Coarse rig phase for surfaces. Deliberately independent of feature:shift's types. */
enum class RigPhase { ARMED, HOT, COOLING, COLD, FROZEN }

data class RigNotificationState(
    val phase: RigPhase,
    /** Heartbeats signed this shift: rounds the rig was dark. A count, never a price. */
    val darkRounds: Int = 0,
    /** Planned window in rounds, when the shift has one (Night Shift). */
    val plannedRounds: Int? = null,
    /** Wall-clock start of the dark time, for a count-up chronometer. */
    val darkSinceWallMillis: Long? = null,
    val focusOnly: Boolean = false,
    /** The rig cooled or stopped after the shift's plan window had ended: nothing ended that shift early. */
    val complete: Boolean = false,
)

/** Rendered text and behaviour for one [RigNotificationState]. */
data class NotificationCopy(
    val title: String,
    val text: String,
    /** Status-bar chip text (Android 16 Live Update). Short. */
    val chip: String?,
    /** Ongoing + user-initiated: eligible for promotion (Live Update / AOD chip). */
    val promote: Boolean,
    val ongoing: Boolean,
    /** Count-up only. Countdowns are forbidden in promoted notifications. */
    val countUpChronometer: Boolean,
    val progress: Int?,
    val progressMax: Int?,
)

/**
 * Copy rules (Google's Live Update policy + Heads Down's positioning):
 * - no price tickers, no currency amounts, no "earn"/"yield" language;
 * - no countdowns (the cooling grace window is described, never counted down);
 * - only the user-started shift (armed / hot / cooling) is ongoing and promotable.
 */
object RigNotificationCopy {
    /** A shift that ran its whole window, in the home screen's words. True before the clock-out and after it. */
    const val COMPLETE_TITLE = "Shift complete"
    const val COMPLETE_TEXT = "It ran its whole window"

    fun from(state: RigNotificationState): NotificationCopy {
        val rounds = state.darkRounds.coerceAtLeast(0)
        val roundsText = if (rounds == 1) "1 round dark" else "$rounds rounds dark"
        val suffix = if (state.focusOnly) " · focus only" else ""
        return when (state.phase) {
            RigPhase.ARMED -> NotificationCopy(
                title = "Rig armed",
                text = "Lay your phone face-down to start the shift$suffix",
                chip = "armed",
                promote = true, ongoing = true, countUpChronometer = false,
                progress = null, progressMax = null,
            )
            RigPhase.HOT -> {
                val max = state.plannedRounds?.takeIf { it > 0 }
                NotificationCopy(
                    title = "Rig hot",
                    text = roundsText + suffix,
                    chip = "hot",
                    promote = true, ongoing = true,
                    countUpChronometer = state.darkSinceWallMillis != null,
                    progress = max?.let { rounds.coerceAtMost(it) },
                    progressMax = max,
                )
            }
            // After the window there is nothing to put back and nothing that ended early: the
            // words change, the notification's behaviour (its phase's) does not.
            RigPhase.COOLING -> NotificationCopy(
                title = if (state.complete) COMPLETE_TITLE else "Rig cooling",
                text = if (state.complete) "$COMPLETE_TEXT · $roundsText" else "Put it back face-down to keep the shift alive",
                chip = if (state.complete) "complete" else "cooling",
                promote = true, ongoing = true, countUpChronometer = false,
                progress = null, progressMax = null,
            )
            RigPhase.COLD -> NotificationCopy(
                title = if (state.complete) COMPLETE_TITLE else "Rig cold",
                text = if (state.complete) "$COMPLETE_TEXT · $roundsText" else "Shift ended · $roundsText",
                chip = null,
                promote = false, ongoing = false, countUpChronometer = false,
                progress = null, progressMax = null,
            )
            RigPhase.FROZEN -> NotificationCopy(
                title = "Rig frozen",
                text = "No digs until you unfreeze it with your wallet",
                chip = null,
                promote = false, ongoing = false, countUpChronometer = false,
                progress = null, progressMax = null,
            )
        }
    }
}
