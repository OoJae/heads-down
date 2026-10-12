package xyz.headsdown.ui

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.robolectric.Shadows.shadowOf
import xyz.headsdown.core.chain.uplink.AckReason
import xyz.headsdown.core.keys.KeySecurityLevel
import xyz.headsdown.feature.oemkeepalive.ExitCause
import xyz.headsdown.feature.oemkeepalive.OemFamily
import xyz.headsdown.feature.oemkeepalive.OemProfile
import xyz.headsdown.feature.oemkeepalive.ShiftHealth
import xyz.headsdown.feature.oemkeepalive.XiaomiSkin
import xyz.headsdown.feature.shift.BreakReason
import xyz.headsdown.feature.shift.CoolReason
import xyz.headsdown.feature.shift.CrankAck
import xyz.headsdown.feature.shift.CrankLinkStatus
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSnapshot
import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.rig.ClockInPolicy
import xyz.headsdown.rig.RigKeyStatus
import xyz.headsdown.ui.setup.SetupActions
import xyz.headsdown.ui.setup.SetupContent
import xyz.headsdown.ui.setup.SetupStep
import xyz.headsdown.ui.theme.HeadsDownTheme

/** One state of the home screen, as the tests of the rebuilt screens compose it. */
data class HomeCase(
    val name: String,
    val snapshot: ShiftSnapshot = ShiftSnapshot.IDLE,
    val health: ShiftHealth = ShiftHealth.NoRecentShift,
    val crank: CrankLinkStatus = CrankLinkStatus(),
    val loading: Boolean = false,
    val lastHaul: String? = null,
    val streak: String? = null,
    /** When the screen is read, on the wall clock: inside the shift's plan window unless the case says otherwise. */
    val nowWallMillis: Long = Screens.IN_WINDOW_MILLIS,
)

/** One state of the setup screen. */
data class SetupCase(
    val name: String,
    val state: OnboardingState,
    val selected: SetupStep? = null,
    val loading: Boolean = false,
)

/** The states of the two screens, with everything wired the way MainActivity wires it. */
object Screens {
    const val WINDOW = "A shift you start now runs until 06:28, two minutes before your alarm. Picking the phone up before then ends the shift early."
    val policy = ClockInPolicy()
    val amounts = policy.disclosure(10_000_000uL)
    val cap = HomeCopy.clockInCap(policy)

    /**
     * The end of the shift's plan window, and the two moments a screen is read at: an hour before
     * it, and just after the alarm that rings two minutes past it. The clock is fixed, so neither
     * depends on the day the tests run.
     */
    const val WINDOW_END_UNIX = 1_791_700_080L
    const val IN_WINDOW_MILLIS = (WINDOW_END_UNIX - 3_600) * 1000
    const val AFTER_WINDOW_MILLIS = (WINDOW_END_UNIX + 130) * 1000

    private val spec = ShiftSpec(1, ShiftMode.NIGHT, windowEndUnix = WINDOW_END_UNIX)
    private val ready = RigKeyStatus.Ready(KeySecurityLevel.TRUSTED_ENVIRONMENT, "3fa9c1d2", 3, voucherLevel = 1)
    val setUp = OnboardingState(
        introSeen = true, notificationsGranted = true, exactAlarmsAllowed = true, batteryUnrestricted = true,
        tileAdded = true, rigKey = ready, rigRegistered = true,
    )

    private val hot = ShiftSnapshot(
        ShiftState.Down(spec, 0, 0),
        darkRounds = 55,
        darkSinceWallMillis = System.currentTimeMillis() - 72 * 60_000,
        signing = true,
    )
    private val refused = CrankLinkStatus(
        configured = true,
        accepted = 3,
        lastAck = CrankAck(9uL, "heartbeat", ok = false, reason = AckReason.BAD_SIGNATURE, atWallMillis = 0),
    )
    private val killed = ShiftHealth.KilledByOs(1_791_690_000_000, ExitCause.KILLED_BY_SYSTEM_OR_OEM, 41)

    val idle = HomeCase("idle")
    val armed = HomeCase("armed", ShiftSnapshot(ShiftState.Armed(spec, 0), signing = true))
    val down = HomeCase("hot", hot)
    val cooling = HomeCase("cooling", hot.copy(state = ShiftState.Cooling(spec, 0, 10_000, CoolReason.LIFTED, 0)))
    val broken = HomeCase("broken", ShiftSnapshot(ShiftState.Broken(spec, 0, BreakReason.UNPLUGGED), darkRounds = 212, darkSinceWallMillis = 5))
    val frozen = HomeCase("frozen", ShiftSnapshot(ShiftState.Frozen(3, 0), darkRounds = 12))

    /** The same two stops after the window: the alarm lit the screen of a shift that ran its whole night. */
    val complete = broken.copy(name = "complete", nowWallMillis = AFTER_WINDOW_MILLIS)
    val completeCooling = cooling.copy(name = "complete, still cooling", nowWallMillis = AFTER_WINDOW_MILLIS)
    val banner = HomeCase("health banner", health = killed)
    val noGoodbye = HomeCase("stopped without a record", health = ShiftHealth.DiedWithoutRecord(0, 7))
    val refusal = HomeCase("crank refusal", crank = refused)
    val loading = HomeCase("loading", loading = true)
    val haul = HomeCase("with a haul", lastHaul = "0.0194 ORE", streak = "Streak 3 nights")

    /** The four the brief names, then the rest. */
    val restingStates = listOf(idle, armed, broken, frozen)
    val homeCases = listOf(idle, armed, down, cooling, broken, frozen, banner, noGoodbye, refusal, loading, haul)

    private val xiaomi = OemProfile(OemFamily.XIAOMI, XiaomiSkin.HYPEROS, "OS2.0")
    private val pixel = OemProfile(OemFamily.OTHER)

    val firstLaunch = SetupCase("first launch", OnboardingState())
    val firstStep = SetupCase("first step", OnboardingState(introSeen = true, oem = pixel))
    val keepAliveXiaomi = SetupCase("keep-alive on a Xiaomi phone", OnboardingState(introSeen = true, notificationsGranted = true, exactAlarmsAllowed = true, oem = xiaomi))
    val autostartLeft = SetupCase("Autostart left to confirm", keepAliveXiaomi.state.copy(batteryUnrestricted = true))
    val tileByHand = SetupCase("tile by hand", OnboardingState(introSeen = true, notificationsGranted = true, exactAlarmsAllowed = true, batteryUnrestricted = true, oem = pixel, tilePromptSupported = false))
    val rigKey = SetupCase("rig key", setUp.copy(rigKey = RigKeyStatus.Missing, rigRegistered = false, oem = pixel))
    val creatingKey = SetupCase("creating the key", rigKey.state.copy(creatingKey = true))
    val keyFailed = SetupCase("key failed", rigKey.state.copy(rigKey = RigKeyStatus.Failed("keystore unavailable")))
    val outOfTurn = SetupCase("a step out of turn", firstStep.state, selected = SetupStep.Tile)
    val allDone = SetupCase("all done", setUp.copy(oem = pixel))
    val setupLoading = SetupCase("loading", OnboardingState(introSeen = true), loading = true)

    val setupCases = listOf(firstLaunch, firstStep, keepAliveXiaomi, autostartLeft, tileByHand, rigKey, creatingKey, keyFailed, outOfTurn, allDone, setupLoading)

    @Composable
    fun Home(case: HomeCase, onFreeze: () -> Unit = {}, onClockIn: () -> Unit = {}, onboarding: OnboardingState = setUp) = HomeScreen(
        snapshot = case.snapshot,
        onboarding = onboarding,
        health = case.health,
        onClockIn = onClockIn,
        onEndShift = {},
        onFreeze = onFreeze,
        onOpenSetup = {},
        onClockOut = {},
        onWithdraw = {},
        onAddWidget = {},
        onOpenSensorLab = {},
        onOpenRigDebug = {},
        crank = case.crank,
        clockInAmounts = amounts,
        shiftWindow = if (case.loading) null else WINDOW,
        bondSkr = 10_000_000uL,
        onBondChange = {},
        lastHaul = case.lastHaul,
        streak = case.streak,
        clockInCap = cap,
        loading = case.loading,
        // Every stop in these cases is stamped 0 on the elapsed clock: the rig stopped as the screen is read.
        nowWallMillis = { case.nowWallMillis },
        nowElapsedMillis = { 0L },
    )

    @Composable
    fun Setup(case: SetupCase, actions: SetupActions = SetupActions()) =
        SetupContent(case.state, actions, selectedStep = case.selected, loading = case.loading)
}

/** One text as `scripts/devstack/emulator/ui.py` reads it off a screen, with where it is. */
data class UiText(val text: String, val bounds: Rect) {
    /** On screen right now: uiautomator only lists what has a visible area. */
    val visible: Boolean get() = bounds.width > 0f && bounds.height > 0f
}

/**
 * What the tests of the rebuilt screens share: a compose rule on a host activity that Robolectric
 * knows (see HomeAndIntroTest), a screen at a chosen font scale, and the screen read the way the
 * end-to-end script reads it.
 */
abstract class ScreenTest {
    protected val rule = createComposeRule()

    private val hostActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(rule)

    /**
     * Robolectric's window has no system bars. With [bars] the screen is given the room a phone
     * gives it: 24dp less at the top for the status bar, 48dp less at the bottom for a
     * three-button navigation bar. That is tighter than the phone, where the hero's zone may
     * still use the strip under the status bar.
     */
    protected fun show(fontScale: Float = 1f, bars: Boolean = false, content: @Composable () -> Unit) = show({ fontScale }, bars, content)

    /** The same, at a font scale the test can change while the screen is up ([fontScale] reads a state). */
    protected fun show(fontScale: () -> Float, bars: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            val device = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(device.density, fontScale())) {
                HeadsDownTheme {
                    Box(Modifier.fillMaxSize().padding(top = if (bars) STATUS_BAR else 0.dp, bottom = if (bars) NAVIGATION_BAR else 0.dp)) { content() }
                }
            }
        }
        rule.waitForIdle()
    }

    protected fun Float.toDp(): Dp = with(rule.density) { this@toDp.toDp() }

    protected fun rootHeight(): Dp = rule.onRoot().fetchSemanticsNode().size.height.toFloat().toDp()

    /**
     * Every node's text, or failing that its content description, in the order of the tree: what
     * `ui.py` makes of a uiautomator dump. Compose gives accessibility the unmerged tree, a node's
     * own first text, and the bounds left after clipping.
     */
    protected fun uiTexts(): List<UiText> {
        val out = mutableListOf<UiText>()
        fun walk(node: SemanticsNode) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
                ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            if (!text.isNullOrEmpty()) out += UiText(text, node.boundsInRoot)
            node.children.forEach(::walk)
        }
        walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    /** `ui.py has <text> [exact]`: on screen right now, no scrolling. A plain match ignores case. */
    protected fun uiHas(text: String, exact: Boolean = false): Boolean = uiMatches(text, exact).any { it.visible }

    /** `ui.py`'s matches, visible or not. */
    protected fun uiMatches(text: String, exact: Boolean = false): List<UiText> =
        uiTexts().filter { if (exact) it.text == text else text.lowercase() in it.text.lowercase() }

    /** Every string a screen reader or a script can get from the screen: texts, descriptions, state descriptions. */
    protected fun spokenTexts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            node.config.getOrNull(SemanticsProperties.StateDescription)?.let { out += it }
            node.children.forEach(::walk)
        }
        walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    companion object {
        val STATUS_BAR = 24.dp
        val NAVIGATION_BAR = 48.dp
    }
}
