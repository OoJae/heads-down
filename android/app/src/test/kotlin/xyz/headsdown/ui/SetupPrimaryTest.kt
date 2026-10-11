package xyz.headsdown.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.keys.KeySecurityLevel
import xyz.headsdown.feature.oemkeepalive.OemFamily
import xyz.headsdown.feature.oemkeepalive.OemProfile
import xyz.headsdown.feature.oemkeepalive.XiaomiSkin
import xyz.headsdown.feature.reveal.haul.HonestCopy
import xyz.headsdown.rig.RigKeyStatus
import xyz.headsdown.ui.setup.SetupAction
import xyz.headsdown.ui.setup.SetupCopy
import xyz.headsdown.ui.setup.SetupPrimary
import xyz.headsdown.ui.setup.SetupStep

/**
 * The one bar in the setup screen's dock. `scripts/devstack/emulator-smoke.sh` walks setup by
 * tapping these labels, in this order, so the sequence is pinned here word for word.
 */
class SetupPrimaryTest {

    private val ready = RigKeyStatus.Ready(KeySecurityLevel.TRUSTED_ENVIRONMENT, "ab", 3)
    private val xiaomi = OemProfile(OemFamily.XIAOMI, XiaomiSkin.HYPEROS, "OS2.0")
    private val pixel = OemProfile(OemFamily.OTHER)

    /** Does what the bar's action does to the phone, as the view model and Android would. */
    private fun after(state: OnboardingState, action: SetupAction): OnboardingState = when (action) {
        SetupAction.MarkIntroSeen -> state.copy(introSeen = true)
        SetupAction.AllowNotifications -> state.copy(notificationsGranted = true)
        SetupAction.AllowExactAlarm -> state.copy(exactAlarmsAllowed = true)
        SetupAction.AllowBackgroundRunning -> state.copy(batteryUnrestricted = true)
        SetupAction.ConfirmAutostart -> state.copy(autostartConfirmed = true)
        SetupAction.AddTile, SetupAction.ConfirmTileAdded -> state.copy(tileAdded = true)
        SetupAction.CreateRigKey -> state.copy(rigKey = ready)
        SetupAction.Finish -> state
    }

    /** The labels the bar shows from [start] until setup is done, tapping it each time. */
    private fun walk(start: OnboardingState): List<String> {
        val labels = mutableListOf<String>()
        var state = start
        repeat(12) {
            val primary = SetupPrimary.of(state)
            labels += primary.label
            if (primary.action == SetupAction.Finish) return labels
            state = after(state, primary.action)
        }
        error("setup never finished: $labels")
    }

    @Test
    fun `a new phone is walked through setup in the order the end-to-end test taps`() {
        assertEquals(
            listOf(
                "Set up my rig",
                "Allow notifications",
                "Allow exact alarm",
                "Allow background running",
                "Add the tile",
                "Create rig key",
                "Start using Heads Down",
            ),
            walk(OnboardingState(oem = pixel)),
        )
    }

    @Test
    fun `where exact alarms are granted at install the bar goes straight on, as on the emulator`() {
        // Android 13 and later grant USE_EXACT_ALARM with the install: the end-to-end test never taps it.
        assertEquals(
            listOf("Set up my rig", "Allow notifications", "Allow background running", "Add the tile", "Create rig key", "Start using Heads Down"),
            walk(OnboardingState(oem = pixel, exactAlarmsAllowed = true)),
        )
    }

    @Test
    fun `on a Xiaomi phone Autostart is confirmed after background running is allowed`() {
        assertEquals(
            listOf(
                "Set up my rig",
                "Allow notifications",
                "Allow exact alarm",
                "Allow background running",
                "I turned Autostart on",
                "Add the tile",
                "Create rig key",
                "Start using Heads Down",
            ),
            walk(OnboardingState(oem = xiaomi)),
        )
        // Confirmed out of turn (the link in the guide), the bar does not ask for it again.
        val confirmedFirst = OnboardingState(introSeen = true, notificationsGranted = true, exactAlarmsAllowed = true, oem = xiaomi, autostartConfirmed = true)
        assertEquals(listOf("Allow background running", "Add the tile", "Create rig key", "Start using Heads Down"), walk(confirmedFirst))
    }

    @Test
    fun `where Android has no tile prompt the tile is confirmed with Done`() {
        val android12 = OnboardingState(introSeen = true, notificationsGranted = true, exactAlarmsAllowed = true, batteryUnrestricted = true, oem = pixel, tilePromptSupported = false)
        val primary = SetupPrimary.of(android12)
        assertEquals("Done", primary.label)
        assertEquals(SetupAction.ConfirmTileAdded, primary.action)
        assertEquals(SetupStep.Tile, primary.step)
        assertEquals(listOf("Done", "Create rig key", "Start using Heads Down"), walk(android12))
    }

    @Test
    fun `while the key is being created the bar says so and takes no tap`() {
        val creating = OnboardingState(introSeen = true, notificationsGranted = true, exactAlarmsAllowed = true, batteryUnrestricted = true, tileAdded = true, creatingKey = true)
        val primary = SetupPrimary.of(creating)
        assertEquals("Creating the key…", primary.label)
        assertTrue(primary.busy)
        assertEquals(SetupAction.CreateRigKey, primary.action)
        // A failed creation offers the same bar again.
        val failed = creating.copy(creatingKey = false, rigKey = RigKeyStatus.Failed("keystore"))
        assertEquals(SetupPrimary("Create rig key", SetupAction.CreateRigKey, SetupStep.RigKey), SetupPrimary.of(failed))
    }

    @Test
    fun `reading what this is comes before any step, whatever the phone already allows`() {
        val allButIntro = OnboardingState(notificationsGranted = true, exactAlarmsAllowed = true, batteryUnrestricted = true, tileAdded = true, rigKey = ready)
        val primary = SetupPrimary.of(allButIntro)
        assertEquals("Set up my rig", primary.label)
        assertEquals(SetupAction.MarkIntroSeen, primary.action)
        assertNull(primary.step)
        assertNull(SetupPrimary.currentStep(allButIntro))
        // A step picked before the introduction was read does not jump the queue.
        assertEquals(SetupAction.MarkIntroSeen, SetupPrimary.of(allButIntro.copy(tileAdded = false), SetupStep.Tile).action)
        assertEquals(listOf("Set up my rig", "Start using Heads Down"), walk(allButIntro))
    }

    @Test
    fun `a step taken out of turn moves the bar, until it is done`() {
        val state = OnboardingState(introSeen = true, oem = pixel)
        assertEquals(SetupStep.Notifications, SetupPrimary.currentStep(state))
        assertEquals("Add the tile", SetupPrimary.of(state, SetupStep.Tile).label)
        assertEquals("Create rig key", SetupPrimary.of(state, SetupStep.RigKey).label)
        // Once the picked step is done the bar is back on the first one that is not.
        val tileDone = state.copy(tileAdded = true)
        assertEquals(SetupStep.Notifications, SetupPrimary.currentStep(tileDone, SetupStep.Tile))
        assertEquals("Allow notifications", SetupPrimary.of(tileDone, SetupStep.Tile).label)
    }

    @Test
    fun `each step is done exactly when the phone says so`() {
        val none = OnboardingState(introSeen = true, oem = xiaomi)
        assertTrue(SetupStep.entries.none { it.isDone(none) })
        assertEquals(listOf(1, 2, 3, 4, 5), SetupStep.entries.map { it.number })
        assertTrue(SetupStep.Notifications.isDone(none.copy(notificationsGranted = true)))
        assertTrue(SetupStep.MorningAlarm.isDone(none.copy(exactAlarmsAllowed = true)))
        // On a Xiaomi phone background running alone does not keep the shift alive.
        assertFalse(SetupStep.KeepAlive.isDone(none.copy(batteryUnrestricted = true)))
        assertTrue(SetupStep.KeepAlive.isDone(none.copy(batteryUnrestricted = true, autostartConfirmed = true)))
        assertTrue(SetupStep.KeepAlive.isDone(none.copy(batteryUnrestricted = true, oem = pixel)))
        assertTrue(SetupStep.Tile.isDone(none.copy(tileAdded = true)))
        assertTrue(SetupStep.RigKey.isDone(none.copy(rigKey = ready)))
        assertFalse(SetupStep.RigKey.isDone(none.copy(rigKey = RigKeyStatus.Failed("x"))))
    }

    @Test
    fun `no label says continue, and every word of setup is honest`() {
        val states = listOf(
            OnboardingState(),
            OnboardingState(introSeen = true, oem = xiaomi),
            OnboardingState(introSeen = true, oem = pixel, tilePromptSupported = false, rigKey = RigKeyStatus.Failed("keystore unavailable")),
            OnboardingState(introSeen = true, rigKey = ready),
        )
        val labels = states.flatMap { state -> listOf(null, *SetupStep.entries.toTypedArray()).map { SetupPrimary.of(state, it).label } } +
            listOf(SetupPrimary.CREATING_RIG_KEY, SetupPrimary.FINISH, SetupPrimary.CONFIRM_AUTOSTART, SetupPrimary.TILE_ADDED_BY_HAND)
        val copy = listOf(
            SetupCopy.TURN, SetupCopy.TURN_ACTION, SetupCopy.HERO_FACE_DOWN, SetupCopy.HERO_TURNED, SetupCopy.SKIP, SetupCopy.HOW_IT_WORKS,
            SetupCopy.TITLE, SetupCopy.LEAD, SetupCopy.OPEN_SETTINGS, SetupCopy.TILE_BY_HAND,
        ) + (0..5).map(SetupCopy::progress) +
            states.flatMap { state -> SetupStep.entries.flatMap { listOf(SetupCopy.title(it), SetupCopy.subtitle(it, state)) } }
        for (text in labels + copy) {
            assertTrue("banned words in: $text", HonestCopy.violations(text).isEmpty())
            // The end-to-end test taps anything on the setup screen that says "Continue".
            assertFalse("\"continue\" in: $text", "continue" in text.lowercase())
            assertFalse("\"Rounds dark\" in: $text", "rounds dark" in text.lowercase())
        }
        assertEquals("2 of 5 steps done", SetupCopy.progress(2))
    }
}
