package xyz.headsdown.ui.setup

/** What fills the screen under the app's one activity. */
internal enum class RootScreen { Intro, Setup, Home }

/**
 * Which screen the root shows. A function of five facts, so the decision is tested without the
 * activity, which cannot be composed without Hilt.
 *
 * - Re-reading the introduction comes before everything: it was opened from one of the other two.
 * - Then what the person chose: "Setup" on the home screen ([setupChoice] true), or leaving setup
 *   with "Skip for now" or "Start using Heads Down" (false). Null: they chose nothing.
 * - Otherwise setup is shown while it is due. Once the phone's settings are read ([loaded]) that
 *   is "not everything is done" ([allDone]), so the home screen appears by itself when the last
 *   step completes. Before they are read only one thing is known, whether the introduction had
 *   been read when the app opened ([unreadAtOpen]): someone who had not read it is on first
 *   launch; someone who had is most likely set up and sees the home screen's own loading state.
 */
internal fun rootScreen(
    rereadIntro: Boolean,
    setupChoice: Boolean?,
    loaded: Boolean,
    allDone: Boolean,
    unreadAtOpen: Boolean,
): RootScreen {
    val setupDue = if (loaded) !allDone else unreadAtOpen
    return when {
        rereadIntro -> RootScreen.Intro
        setupChoice ?: setupDue -> RootScreen.Setup
        else -> RootScreen.Home
    }
}
