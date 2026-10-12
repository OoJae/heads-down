package xyz.headsdown.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import xyz.headsdown.core.design.BrandGlyph
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.HdArgb
import xyz.headsdown.core.design.HdDimens
import xyz.headsdown.core.design.HeadsDownTheme
import xyz.headsdown.core.design.components.ActionDock
import xyz.headsdown.core.design.components.BarButton
import xyz.headsdown.core.design.components.DisplayText
import xyz.headsdown.core.design.components.HeatPixels
import xyz.headsdown.core.design.components.Hero
import xyz.headsdown.core.design.components.HeroSpec
import xyz.headsdown.core.design.components.Label
import xyz.headsdown.core.design.components.LinkRow
import xyz.headsdown.core.design.components.LocalHdHaptics
import xyz.headsdown.core.design.components.Notice
import xyz.headsdown.core.design.components.NoticeTone
import xyz.headsdown.core.design.components.Plate
import xyz.headsdown.core.design.components.PlateTone
import xyz.headsdown.core.design.components.SectionHeader
import xyz.headsdown.core.design.components.SkeletonBlock
import xyz.headsdown.core.design.components.SkeletonLine
import xyz.headsdown.core.design.components.SlabState
import xyz.headsdown.core.design.components.StepRow
import xyz.headsdown.core.design.components.StepStatus
import xyz.headsdown.core.design.components.TextAction
import xyz.headsdown.feature.oemkeepalive.KeepAliveGuide
import xyz.headsdown.feature.oemkeepalive.KeepAliveStep
import xyz.headsdown.rig.RigKeyStatus
import xyz.headsdown.ui.INTRO_CONTINUE_TAG
import xyz.headsdown.ui.NightShiftCopy
import xyz.headsdown.ui.OnboardingState
import xyz.headsdown.ui.keepAliveSubtitle
import xyz.headsdown.ui.rigKeySubtitle
import kotlin.math.roundToInt

object SetupTags {
    /** The bar in the dock, once the introduction was read. Before that it is [INTRO_CONTINUE_TAG]. */
    const val PRIMARY = "setup-primary"
    const val DOCK = "setup-dock"
    const val SKIP = "setup-skip"
    const val HERO = "setup-hero"
    const val TURN = "setup-turn-it-over"
    const val HOW_IT_WORKS = "setup-how-it-works"
    const val PROGRESS = "setup-progress"

    /** Followed by the step's number, 1 to 5. */
    const val STEP = "setup-step-"
    const val OPEN_SETTINGS = "setup-open-settings-"
    const val CONFIRM_AUTOSTART = "setup-confirm-autostart"
}

/** The setup screen's own words, as data, so the honesty test reads every one of them. */
object SetupCopy {
    const val TURN = "Turn it over."
    const val TURN_ACTION = "turn the slab over"
    const val HERO_FACE_DOWN = "A dark slab lying face-down, with no light under it."
    const val HERO_TURNED = "A dark slab turned over, with gold light on its underside."
    const val SKIP = "Skip for now"
    const val HOW_IT_WORKS = "How the night shift works"
    const val TITLE = "Set up your rig"
    const val LEAD = "Five steps so your phone can take the night shift. If anything fails, the rig simply goes cold: nothing is spent."
    const val OPEN_SETTINGS = "Open settings"
    const val TILE_BY_HAND = "Swipe down twice, tap the pencil, and drag Heads Down next to Do Not Disturb."

    fun progress(done: Int): String = "$done of ${SetupStep.entries.size} steps done"

    fun title(step: SetupStep): String = when (step) {
        SetupStep.Notifications -> "Notifications"
        SetupStep.MorningAlarm -> "Morning alarm"
        SetupStep.KeepAlive -> "Keep the shift alive"
        SetupStep.Tile -> "Quick Settings tile"
        SetupStep.RigKey -> "Create your rig key"
    }

    fun subtitle(step: SetupStep, state: OnboardingState): String = when (step) {
        SetupStep.Notifications -> "Your shift shows as an ongoing notification, and the morning reveal needs one."
        SetupStep.MorningAlarm -> "After your own alarm rings, a page opens where you clock out and see the haul."
        SetupStep.KeepAlive -> keepAliveSubtitle(state)
        SetupStep.Tile -> "Clock in from anywhere: swipe down, tap Heads Down next to Do Not Disturb."
        SetupStep.RigKey -> rigKeySubtitle(state.rigKey, state.attestation)
    }
}

/** What the setup screen can ask for. The stateful route (`OnboardingScreen`) wires each to the phone. */
class SetupActions(
    val onMarkIntroSeen: () -> Unit = {},
    val onAllowNotifications: () -> Unit = {},
    val onAllowExactAlarm: () -> Unit = {},
    val onOpenKeepAlive: (KeepAliveStep) -> Unit = {},
    val onConfirmAutostart: () -> Unit = {},
    val onAddTile: () -> Unit = {},
    /** The tile was added by hand, where Android has no prompt for it. */
    val onConfirmTile: () -> Unit = {},
    val onCreateRigKey: () -> Unit = {},
    /** Leave setup: "Start using Heads Down" when everything is done, "Skip for now" before. */
    val onFinish: () -> Unit = {},
    val onHowItWorks: () -> Unit = {},
) {
    fun perform(action: SetupAction) = when (action) {
        SetupAction.MarkIntroSeen -> onMarkIntroSeen()
        SetupAction.AllowNotifications -> onAllowNotifications()
        SetupAction.AllowExactAlarm -> onAllowExactAlarm()
        SetupAction.AllowBackgroundRunning -> onOpenKeepAlive(KeepAliveStep.IGNORE_BATTERY_OPTIMIZATIONS)
        SetupAction.ConfirmAutostart -> onConfirmAutostart()
        SetupAction.AddTile -> onAddTile()
        SetupAction.ConfirmTileAdded -> onConfirmTile()
        SetupAction.CreateRigKey -> onCreateRigKey()
        SetupAction.Finish -> onFinish()
    }
}

/** Where a layout was last placed. A plain holder: the layout writes it, an effect reads it later. */
private class Placed {
    var coordinates: LayoutCoordinates? = null
}

/** A step's row inside the list: how far down it starts and how tall it is, in pixels. */
private class RowPlace {
    val top = mutableIntStateOf(0)
    val height = mutableIntStateOf(0)
}

/** The hero's share of the screen for someone who has not read the introduction, and for someone who has. */
private const val HERO_SHARE_UNREAD = 0.44f
private const val HERO_SHARE_READ = 0.26f

/** How long the dock's bar ignores taps after its action changed under the thumb. */
private const val REST_AFTER_CHANGE_MILLIS = 300L

/**
 * First launch and setup, in one page. The top is the slab and what this app is, in the words of
 * the night-shift introduction (the lead, and "What it is not" before any rig exists); under it,
 * the five steps. The dock holds one bar, and it is always the next real thing to do
 * ([SetupPrimary.of]): "Set up my rig" until the page was read, then each step's own action.
 *
 * Stateless: [state] in, [actions] out. A step that arrives done shows as done; the first one that
 * is not is the current step, opened, unless the person picked another ([selectedStep]).
 *
 * @param loading the phone's settings have not been read yet: the steps are still skeleton lines,
 *   never a guess that everything is still to do.
 */
@Composable
fun SetupContent(
    state: OnboardingState,
    actions: SetupActions,
    modifier: Modifier = Modifier,
    selectedStep: SetupStep? = null,
    onSelectStep: (SetupStep) -> Unit = {},
    loading: Boolean = false,
) {
    val motion = Hd.motion
    val haptics = LocalHdHaptics.current
    val density = LocalDensity.current
    val primary = SetupPrimary.of(state, selectedStep)
    val current = if (loading) null else SetupPrimary.currentStep(state, selectedStep)
    // A page opened with the introduction already read starts at its end, where the steps are.
    val scroll = rememberScrollState(initial = if (state.introSeen) Int.MAX_VALUE else 0)
    var turned by rememberSaveable { mutableStateOf(false) }

    // The hero's share is settled when the page opens: large for someone who has not read what
    // this is, small for someone who has. It does not change under a person who is reading:
    // "Set up my rig" turns the page to the steps, it does not resize what is above them.
    val openedRead = remember { state.introSeen }
    val heroShare = if (openedRead) HERO_SHARE_READ else HERO_SHARE_UNREAD

    // The bar's label changes in place, under the thumb that just tapped it. For a moment after
    // each change it ignores taps, so the second half of a double tap cannot start the next step.
    var resting by remember { mutableStateOf(false) }
    var shownAction by remember { mutableStateOf(primary.action) }
    LaunchedEffect(primary.action) {
        if (primary.action != shownAction) {
            shownAction = primary.action
            resting = true
            delay(REST_AFTER_CHANGE_MILLIS)
            resting = false
        }
    }

    // Where the steps are on the page, and how tall each row is: written by the layout, read by
    // the effect that turns the page to them. Nothing recomposes for either.
    val stepsPlace = remember { Placed() }
    val rows = remember { SetupStep.entries.map { RowPlace() } }
    val rowInView = remember { SetupStep.entries.associateWith { BringIntoViewRequester() } }
    var stepsShown by remember { mutableStateOf(false) }

    HeroPage(
        scrollState = scroll,
        modifier = modifier,
        // The page turns itself to the steps, away from its own top. What is up there (the brand,
        // and the only way past setup) stays in sight, pinned, wherever the page is.
        pinned = { SetupHeader(showSkip = state.introSeen && !state.allDone, onSkip = actions.onFinish) },
        dock = {
            ActionDock(Modifier.testTag(SetupTags.DOCK)) {
                if (loading && state.introSeen) {
                    // Which step is next is not known yet: a still block, not a guess.
                    SkeletonBlock(height = HdDimens.Bar)
                } else {
                    BarButton(
                        label = primary.label,
                        onClick = { if (!resting) actions.perform(primary.action) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(if (primary.action == SetupAction.MarkIntroSeen) INTRO_CONTINUE_TAG else SetupTags.PRIMARY),
                        busy = primary.busy,
                    )
                }
            }
        },
    ) { viewport ->
        // Once the introduction is read the page turns to the steps: their heading under the
        // status bar, and as much of the list as fits under it. After that the current step is
        // kept in view, whatever the size of the text, and again when the window's own size changes.
        LaunchedEffect(state.introSeen, current, viewport) {
            if (!state.introSeen) return@LaunchedEffect
            // Nothing has a place before the page was laid out once.
            snapshotFlow { scroll.viewportSize }.first { it > 0 }
            // The page scrolls under the status bar: what is brought to the top stops short of it,
            // and what is brought to the bottom stops short of the dock.
            val lead = viewport.topInsetPx + with(density) { 12.dp.toPx() }
            val foot = with(density) { 16.dp.toPx() }
            if (!stepsShown) {
                stepsShown = true
                val stepsTop = stepsPlace.coordinates?.takeIf { it.isAttached }?.positionInParent()?.y
                if (stepsTop != null) {
                    // The heading at the top; further down only when the current step would end under the dock.
                    var target = stepsTop - lead
                    current?.let { rows[it.ordinal] }?.let { row ->
                        val rowTop = stepsTop + row.top.intValue
                        val rowBottom = rowTop + row.height.intValue + foot
                        if (rowBottom > target + viewport.heightPx) target = minOf(rowTop - lead, rowBottom - viewport.heightPx)
                    }
                    val to = target.roundToInt().coerceAtLeast(0)
                    // Arriving at the steps is not a motion. Being turned to them by "Set up my rig" is one.
                    if (openedRead || motion.reduced) scroll.scrollTo(to) else scroll.animateScrollTo(to, motion.settle())
                }
            }
            val step = current ?: return@LaunchedEffect
            val row = rows[step.ordinal]
            // Now, and again whenever the row moves or changes height: a step above it closed, it
            // opened, the font grew. The scroller moves no further than it must, and not at all
            // for a row already in view.
            snapshotFlow { row.top.intValue to row.height.intValue }.collectLatest { (_, height) ->
                if (height > 0) rowInView.getValue(step).bringIntoView(Rect(0f, -lead, 1f, height + foot))
            }
        }

        HeroFirstScreen(
            viewport = viewport,
            minShare = HERO_SHARE_READ,
            maxShare = heroShare,
            hero = {
                Hero(
                    spec = HeroSpec(
                        state = if (turned) SlabState.Hot else SlabState.Cold,
                        heat = if (turned) HeroSpec.MAX_HEAT else 0,
                        scrollPx = { scroll.value.toFloat() },
                        interactive = true,
                        enter = !state.introSeen,
                        // Turned by hand (the live hero) or by the heading below: the same thing.
                        onFlipped = {
                            turned = true
                            haptics.snap()
                        },
                        onKnock = haptics::knock,
                    ),
                    modifier = Modifier.testTag(SetupTags.HERO).semantics { role = Role.Image },
                    contentDescription = if (turned) SetupCopy.HERO_TURNED else SetupCopy.HERO_FACE_DOWN,
                )
            },
            // The header is pinned over the page (the viewport already counts it as covered).
            overlay = {},
            // The statement and the lead keep their place under the slab they are about; what it is
            // not comes straight after, on the same screenful wherever the font leaves room.
            mustFit = {
                Pitch(
                    onTurn = {
                        turned = !turned
                        haptics.snap()
                    },
                )
            },
            rest = { WhatItIsNot() },
        )

        LinkRow(
            SetupCopy.HOW_IT_WORKS,
            onClick = actions.onHowItWorks,
            modifier = Modifier.padding(horizontal = HdDimens.Margin).testTag(SetupTags.HOW_IT_WORKS),
        )
        Spacer(Modifier.height(28.dp))

        Column(
            Modifier.fillMaxWidth().onPlaced { stepsPlace.coordinates = it }.padding(horizontal = HdDimens.Margin),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val done = SetupStep.entries.count { it.isDone(state) }
            Box(Modifier.fillMaxWidth()) {
                SectionHeader(SetupCopy.TITLE)
                if (!loading) {
                    HeatPixels(
                        lit = done,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp)
                            .testTag(SetupTags.PROGRESS)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        total = SetupStep.entries.size,
                        color = Hd.colors.chalk,
                        description = SetupCopy.progress(done),
                    )
                }
            }
            Body(SetupCopy.LEAD, color = Hd.colors.ash)
            if (loading) {
                // Still lines where the five steps will be: nothing moves while the phone is read.
                for (width in listOf(0.7f, 0.5f, 0.8f, 0.6f, 0.65f)) SkeletonLine(Modifier.padding(vertical = 14.dp), widthFraction = width)
            } else {
                for (step in SetupStep.entries) {
                    Step(
                        step = step,
                        state = state,
                        current = current,
                        primary = primary,
                        actions = actions,
                        onSelect = onSelectStep,
                        modifier = Modifier
                            .onPlaced { rows[step.ordinal].top.intValue = it.positionInParent().y.roundToInt() }
                            .onSizeChanged { rows[step.ordinal].height.intValue = it.height }
                            .bringIntoViewRequester(rowInView.getValue(step)),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The brand, and the way past setup once the introduction was read. Pinned to the top of the
 * page: over the hero when the page is at its top, over the steps once it has turned to them.
 */
@Composable
private fun SetupHeader(showSkip: Boolean, onSkip: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = HdDimens.Margin, vertical = 4.dp)
            .defaultMinSize(minHeight = HdDimens.Touch),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandGlyph(Modifier.size(28.dp))
        // On a narrow line the name gives way: it wraps under itself, and the action is never cut.
        Label("HEADS DOWN", Modifier.weight(1f), color = Hd.colors.chalk)
        if (showSkip) TextAction(SetupCopy.SKIP, onClick = onSkip, modifier = Modifier.testTag(SetupTags.SKIP))
    }
}

/**
 * What this is, before anything is asked of the phone: the statement and the lead of the
 * night-shift introduction. [WhatItIsNot] follows; on first launch all three are on the first
 * screenful at the normal font.
 */
@Composable
private fun Pitch(onTurn: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = HdDimens.Margin, end = HdDimens.Margin, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // A heading and a button: tapping it turns the slab over, so nothing depends on tilting a phone.
        DisplayText(
            SetupCopy.TURN,
            modifier = Modifier
                .testTag(SetupTags.TURN)
                .semantics { heading() }
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClickLabel = SetupCopy.TURN_ACTION,
                    role = Role.Button,
                    onClick = onTurn,
                ),
        )
        Body(NightShiftCopy.LEAD)
    }
}

/** "This is not income…": said on this screen, before any rig can be registered. */
@Composable
private fun WhatItIsNot() {
    Plate(
        Modifier.fillMaxWidth().padding(start = HdDimens.Margin, end = HdDimens.Margin, bottom = 16.dp),
        tone = PlateTone.Slab,
    ) {
        Label(NightShiftCopy.NOT_TITLE)
        Body(NightShiftCopy.NOT_BODY)
    }
}

@Composable
private fun Step(
    step: SetupStep,
    state: OnboardingState,
    current: SetupStep?,
    primary: SetupPrimary,
    actions: SetupActions,
    onSelect: (SetupStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    val done = step.isDone(state)
    val open = step == current
    val status = when {
        done -> StepStatus.Done
        open -> StepStatus.Current
        else -> StepStatus.Todo
    }
    val keyFailed = step == SetupStep.RigKey && state.rigKey is RigKeyStatus.Failed
    val oem = state.oem
    val content: (@Composable ColumnScope.() -> Unit)? = when {
        // A problem is ember and says what happened; the sentence is the step's own.
        keyFailed -> {
            { Notice(SetupCopy.subtitle(step, state), tone = NoticeTone.Problem) }
        }
        !open -> null
        step == SetupStep.KeepAlive && oem != null -> {
            {
                for (guide in KeepAliveGuide.steps(oem)) {
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        BasicText(guide.title, style = Hd.type.button.copy(color = Hd.colors.chalk))
                        Body(guide.detail, color = Hd.colors.ash)
                        // "Allow background running" is the dock's bar; the other settings open from here.
                        val opens = guide.action?.takeIf { it != KeepAliveStep.IGNORE_BATTERY_OPTIMIZATIONS }
                        // Once it is the dock's bar it is not offered twice.
                        val confirms = guide.action == KeepAliveStep.AUTOSTART && !state.autostartConfirmed &&
                            primary.action != SetupAction.ConfirmAutostart
                        if (opens != null || confirms) {
                            // What this sub-step offers, on one line while the line has room for both.
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                if (opens != null) {
                                    TextAction(
                                        SetupCopy.OPEN_SETTINGS,
                                        onClick = { actions.onOpenKeepAlive(opens) },
                                        modifier = Modifier.testTag(SetupTags.OPEN_SETTINGS + opens.name),
                                    )
                                }
                                if (confirms) {
                                    TextAction(
                                        SetupPrimary.CONFIRM_AUTOSTART,
                                        onClick = actions.onConfirmAutostart,
                                        modifier = Modifier.testTag(SetupTags.CONFIRM_AUTOSTART),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        step == SetupStep.Tile && !state.tilePromptSupported -> {
            { Body(SetupCopy.TILE_BY_HAND, color = Hd.colors.ash) }
        }
        else -> null
    }
    StepRow(
        index = step.number,
        title = SetupCopy.title(step),
        modifier = modifier.testTag(SetupTags.STEP + step.number),
        // After a failure the step still says what it is for; what went wrong is the notice under it.
        subtitle = if (keyFailed) rigKeySubtitle(RigKeyStatus.Missing) else SetupCopy.subtitle(step, state),
        status = status,
        // A step still to do can be taken out of turn; the dock then carries its action.
        onClick = if (state.introSeen && !done && !open) ({ onSelect(step) }) else null,
        content = content,
    )
}

@Preview(widthDp = 360, heightDp = 800, backgroundColor = HdArgb.PREVIEW_PIT, showBackground = true)
@Composable
private fun SetupFirstLaunchPreview() = HeadsDownTheme { SetupContent(OnboardingState(), SetupActions()) }

@Preview(widthDp = 360, heightDp = 800, backgroundColor = HdArgb.PREVIEW_PIT, showBackground = true)
@Composable
private fun SetupStepsPreview() = HeadsDownTheme {
    SetupContent(OnboardingState(introSeen = true, notificationsGranted = true, exactAlarmsAllowed = true), SetupActions())
}
