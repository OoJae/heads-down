package xyz.headsdown.ui.setup

import xyz.headsdown.ui.NightShiftCopy
import xyz.headsdown.ui.OnboardingState

/** The five steps, in the order the night loop depends on them. */
enum class SetupStep {
    Notifications,
    MorningAlarm,
    KeepAlive,
    Tile,
    RigKey,
    ;

    /** 1..5, as the step is numbered on screen. */
    val number: Int get() = ordinal + 1

    fun isDone(state: OnboardingState): Boolean = when (this) {
        Notifications -> state.notificationsGranted
        MorningAlarm -> state.exactAlarmsAllowed
        KeepAlive -> state.keepAliveDone
        Tile -> state.tileAdded
        RigKey -> state.rigKeyReady
    }
}

/** What the bar in the setup screen's dock does when it is tapped. */
enum class SetupAction {
    MarkIntroSeen,
    AllowNotifications,
    AllowExactAlarm,
    AllowBackgroundRunning,
    ConfirmAutostart,
    AddTile,
    ConfirmTileAdded,
    CreateRigKey,
    Finish,
}

/**
 * The one bar in the setup screen's dock: the next real thing to do, under the label that action
 * has always had. The end-to-end test taps these labels in this order, so they are the contract.
 *
 * @param step the step the action belongs to (null: reading the introduction, or leaving setup).
 * @param busy the action is running; the label says so and the bar ignores taps.
 */
data class SetupPrimary(
    val label: String,
    val action: SetupAction,
    val step: SetupStep? = null,
    val busy: Boolean = false,
) {
    companion object {
        const val ALLOW_NOTIFICATIONS = "Allow notifications"
        const val ALLOW_EXACT_ALARM = "Allow exact alarm"
        const val ALLOW_BACKGROUND_RUNNING = "Allow background running"
        const val CONFIRM_AUTOSTART = "I turned Autostart on"
        const val ADD_TILE = "Add the tile"
        const val TILE_ADDED_BY_HAND = "Done"
        const val CREATE_RIG_KEY = "Create rig key"
        const val CREATING_RIG_KEY = "Creating the key…"
        const val FINISH = "Start using Heads Down"

        /**
         * The step the dock acts on: the one the person picked while it is still open, otherwise
         * the first that is not done. Null before the introduction was read, and when all five are.
         */
        fun currentStep(state: OnboardingState, selectedStep: SetupStep? = null): SetupStep? {
            if (!state.introSeen) return null
            return selectedStep?.takeIf { !it.isDone(state) } ?: SetupStep.entries.firstOrNull { !it.isDone(state) }
        }

        fun of(state: OnboardingState, selectedStep: SetupStep? = null): SetupPrimary {
            // Reading what this is comes before anything is asked of the phone.
            if (!state.introSeen) return SetupPrimary(NightShiftCopy.CONTINUE, SetupAction.MarkIntroSeen)
            val step = currentStep(state, selectedStep) ?: return SetupPrimary(FINISH, SetupAction.Finish)
            return when (step) {
                SetupStep.Notifications -> SetupPrimary(ALLOW_NOTIFICATIONS, SetupAction.AllowNotifications, step)
                SetupStep.MorningAlarm -> SetupPrimary(ALLOW_EXACT_ALARM, SetupAction.AllowExactAlarm, step)
                // Android can say whether the app may run in the background; Autostart cannot be
                // read back, so once the first is allowed the person confirms the second.
                SetupStep.KeepAlive -> if (!state.batteryUnrestricted) {
                    SetupPrimary(ALLOW_BACKGROUND_RUNNING, SetupAction.AllowBackgroundRunning, step)
                } else {
                    SetupPrimary(CONFIRM_AUTOSTART, SetupAction.ConfirmAutostart, step)
                }
                // Android 12 has no prompt for a tile: it is added by hand and confirmed here.
                SetupStep.Tile -> if (state.tilePromptSupported) {
                    SetupPrimary(ADD_TILE, SetupAction.AddTile, step)
                } else {
                    SetupPrimary(TILE_ADDED_BY_HAND, SetupAction.ConfirmTileAdded, step)
                }
                SetupStep.RigKey -> if (state.creatingKey) {
                    SetupPrimary(CREATING_RIG_KEY, SetupAction.CreateRigKey, step, busy = true)
                } else {
                    SetupPrimary(CREATE_RIG_KEY, SetupAction.CreateRigKey, step)
                }
            }
        }
    }
}
