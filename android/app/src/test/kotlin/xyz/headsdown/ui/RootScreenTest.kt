package xyz.headsdown.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.headsdown.ui.setup.RootScreen
import xyz.headsdown.ui.setup.rootScreen

/**
 * Which screen the app's root shows, as the pure function MainActivity calls. The activity itself
 * needs Hilt to be composed; what it decides does not.
 */
class RootScreenTest {

    private fun screen(
        rereadIntro: Boolean = false,
        setupChoice: Boolean? = null,
        loaded: Boolean = true,
        allDone: Boolean = false,
        unreadAtOpen: Boolean = false,
    ) = rootScreen(rereadIntro, setupChoice, loaded, allDone, unreadAtOpen)

    @Test
    fun `first launch is the setup screen, before and after the phone's settings are read`() {
        assertEquals(RootScreen.Setup, screen(loaded = false, unreadAtOpen = true))
        assertEquals(RootScreen.Setup, screen(loaded = true, unreadAtOpen = true))
    }

    @Test
    fun `Set up my rig tapped before the settings are read does not put the home screen up in between`() {
        // The tap marks the introduction read. What was known when the app opened still decides
        // until the settings arrive, and then setup is simply not done yet.
        assertEquals(RootScreen.Setup, screen(loaded = false, unreadAtOpen = true))
        assertEquals(RootScreen.Setup, screen(loaded = true, allDone = false, unreadAtOpen = true))
    }

    @Test
    fun `someone who read the introduction sees the home screen while the settings are read`() {
        assertEquals(RootScreen.Home, screen(loaded = false, unreadAtOpen = false))
        // Set up: it stays. Not set up after all: setup takes its place.
        assertEquals(RootScreen.Home, screen(loaded = true, allDone = true))
        assertEquals(RootScreen.Setup, screen(loaded = true, allDone = false))
    }

    @Test
    fun `when the last step completes and nothing was chosen, the home screen appears by itself`() {
        assertEquals(RootScreen.Setup, screen(allDone = false, unreadAtOpen = true))
        assertEquals(RootScreen.Home, screen(allDone = true, unreadAtOpen = true))
    }

    @Test
    fun `what the person chose wins over what is due`() {
        // "Skip for now": home, with setup unfinished.
        assertEquals(RootScreen.Home, screen(setupChoice = false, allDone = false))
        assertEquals(RootScreen.Home, screen(setupChoice = false, loaded = false, unreadAtOpen = true))
        // "Setup" on the home screen: setup, with everything done, until they leave it.
        assertEquals(RootScreen.Setup, screen(setupChoice = true, allDone = true))
    }

    @Test
    fun `re-reading the introduction comes before both, and gives way to where it was opened from`() {
        for (choice in listOf(null, true, false)) {
            for (done in listOf(true, false)) {
                assertEquals(RootScreen.Intro, screen(rereadIntro = true, setupChoice = choice, allDone = done))
            }
        }
        assertEquals(RootScreen.Setup, screen(rereadIntro = false, allDone = false))
        assertEquals(RootScreen.Home, screen(rereadIntro = false, allDone = true))
    }
}
