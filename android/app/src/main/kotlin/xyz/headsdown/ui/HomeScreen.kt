package xyz.headsdown.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.headsdown.core.design.BrandGlyph
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.HdArgb
import xyz.headsdown.core.design.HdDimens
import xyz.headsdown.core.design.components.ActionDock
import xyz.headsdown.core.design.components.BarButton
import xyz.headsdown.core.design.components.BarButtonStyle
import xyz.headsdown.core.design.components.Choice
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
import xyz.headsdown.core.design.components.SkeletonLine
import xyz.headsdown.core.design.components.SlabState
import xyz.headsdown.core.design.components.StatBlock
import xyz.headsdown.core.design.components.StatRow
import xyz.headsdown.core.design.components.TextAction
import xyz.headsdown.feature.oemkeepalive.ExitCause
import xyz.headsdown.feature.oemkeepalive.ShiftHealth
import xyz.headsdown.feature.shift.BreakReason
import xyz.headsdown.feature.shift.CrankLinkStatus
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSnapshot
import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.feature.shift.refusalLine
import xyz.headsdown.feature.shift.spec
import xyz.headsdown.rig.ClockInPolicy
import xyz.headsdown.rig.FocusBondSetting
import xyz.headsdown.surface.tile.TileRenderer
import xyz.headsdown.ui.setup.Body
import xyz.headsdown.ui.setup.HeroFirstScreen
import xyz.headsdown.ui.setup.HeroPage
import xyz.headsdown.ui.setup.PageSection
import xyz.headsdown.ui.theme.HeadsDownTheme
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

object HomeTags {
    const val RIG_WORD = "home-rig-word"
    const val CLOCK_IN = "home-clock-in"
    const val PREVIEW_REVEAL = "home-preview-reveal"
    const val CLOCK_OUT = "home-clock-out"
    const val WITHDRAW = "home-withdraw"
    const val ADD_WIDGET = "home-add-widget"
    const val SENSOR_LAB = "home-sensor-lab"
    const val RIG_DEBUG = "home-rig-debug"
    const val HOW_IT_WORKS = "home-how-it-works"
    const val CRANK_REFUSAL = "home-crank-refusal"
    const val CLOCK_IN_AMOUNTS = "home-clock-in-amounts"
    const val SHIFT_WINDOW = "home-shift-window"
    const val UNFREEZE = "home-unfreeze"
    const val BOND_CARD = "home-focus-bond"
    const val BOND_CHOICE = "home-focus-bond-choice-"

    const val HERO = "home-hero"
    const val DOCK = "home-dock"
    const val SETUP = "home-setup"
    const val HEALTH = "home-health"
    const val ROUNDS_DARK = "home-rounds-dark"

    /** "Runs until" before a shift and while it is armed, "Dark for" while the rig is hot or cooling. */
    const val SHIFT_CLOCK = "home-shift-clock"
    const val CLOCK_IN_CAP = "home-clock-in-cap"
    const val END_SHIFT = "home-end-shift"
    const val FREEZE = "home-freeze"
    const val FREEZE_CONFIRM = "home-freeze-confirm"
    const val FREEZE_YES = "home-freeze-yes"
    const val FREEZE_KEEP = "home-freeze-keep"
    const val HAUL = "home-haul"
}

/** The home screen's words that are new with the redesign, as data, so the honesty test reads each. */
object HomeCopy {
    const val RUNS_UNTIL = "Runs until"
    const val LIMITS = "Tonight's limits"
    const val SOL_AND_RIG = "Your SOL and your rig"
    const val RIG_KEY = "Rig key"
    const val HOW_IT_WORKS = "How it works"
    const val LAST_HAUL = "Last haul"
    const val FREEZE_ASK = "Freezing ends this shift and stops every dig until you unfreeze the rig with your wallet."
    const val FREEZE_YES = "Freeze the rig"
    const val FREEZE_KEEP = "Keep the shift"

    /** The most a shift can place, under the bar that opens the wallet. */
    fun clockInCap(policy: ClockInPolicy): String =
        "Up to ${ClockInPolicy.sol(policy.shiftBudgetLamports)} SOL ${if (policy.day) "this shift" else "tonight"}"

    /** One sentence for the hero: the slab, and what its light says about the rig. */
    fun hero(state: ShiftState): String = when (state) {
        ShiftState.Idle -> "A dark slab lying face-down, with no light under it: the rig is cold."
        is ShiftState.Armed -> "A dark slab lying face-down, with one ember light under it: the rig is armed."
        is ShiftState.Down -> "A dark slab lying face-down, with gold light under it: the rig is hot."
        is ShiftState.Cooling -> "A dark slab lying face-down, with its light going out: the rig is cooling."
        is ShiftState.Broken -> "A dark slab lying face-down, with no light under it: the shift ended."
        is ShiftState.Frozen -> "A dark slab lying face-down, with grey lights under it: the rig is frozen."
    }

    val all: List<String>
        get() = listOf(RUNS_UNTIL, LIMITS, SOL_AND_RIG, RIG_KEY, HOW_IT_WORKS, LAST_HAUL, FREEZE_ASK, FREEZE_YES, FREEZE_KEEP)
}

/** How the rig word is inked. A role, not a colour: the palette under it may change. */
internal enum class RigTone { Muted, Lit, Ember }

internal data class RigLook(
    val word: String,
    val tone: RigTone,
    val line: String,
    val pixels: Int,
    val slab: SlabState,
    /** The line says why the shift ended: it is drawn in ember and read out when it appears. */
    val problem: Boolean = false,
)

internal fun lookOf(state: ShiftState): RigLook = when (state) {
    ShiftState.Idle -> RigLook("Rig cold", RigTone.Muted, "Clock in, then lay your phone face-down.", 0, SlabState.Cold)
    is ShiftState.Armed -> RigLook("Armed", RigTone.Lit, "Lay your phone face-down to start the shift.", 1, SlabState.Armed)
    is ShiftState.Down -> RigLook("Rig hot", RigTone.Lit, "Signing a heartbeat every ORE round.", 5, SlabState.Hot)
    is ShiftState.Cooling -> RigLook("Cooling", RigTone.Ember, "Put it back face-down to keep the shift alive.", 3, SlabState.Cooling)
    is ShiftState.Broken -> RigLook("Rig cold", RigTone.Muted, breakLine(state.reason), 0, SlabState.Broken, problem = true)
    is ShiftState.Frozen -> RigLook("Frozen", RigTone.Muted, "No digs until you unfreeze it with your wallet.", 5, SlabState.Frozen)
}

private fun breakLine(reason: BreakReason) = when (reason) {
    BreakReason.LIFTED -> "Shift ended: the phone was picked up."
    BreakReason.SCREEN_ON -> "Shift ended: the screen stayed on."
    BreakReason.UNPLUGGED -> "Shift ended: the charger was unplugged."
    BreakReason.UNLOCKED -> "Shift ended: the phone was unlocked."
}

private val UNTIL = Regex("""until (\d{2}:\d{2})""")
private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The time a shift runs until, as numerals. Before a shift it is read out of the sentence under
 * the numerals (`ShiftWindow.describe`, "… runs until 06:28 …"), so the two can never disagree;
 * once a shift is armed it is the end of the plan window that shift was armed with. Null when
 * neither is known, and for a frozen rig, which has no shift.
 */
internal fun runsUntil(state: ShiftState, shiftWindow: String?, zone: ZoneId = ZoneId.systemDefault()): String? = when (state) {
    ShiftState.Idle, is ShiftState.Broken -> shiftWindow?.let { UNTIL.find(it)?.groupValues?.get(1) }
    is ShiftState.Armed, is ShiftState.Down, is ShiftState.Cooling ->
        state.spec?.windowEndUnix?.let { HH_MM.format(Instant.ofEpochSecond(it).atZone(zone)) }
    is ShiftState.Frozen -> null
}

/**
 * Since when "Dark for" counts, or null when it is not shown. It counts only while the rig is hot
 * or cooling: a shift that broke has stopped being dark, and a clock that kept running on the home
 * screen would say otherwise.
 */
internal fun darkSince(snapshot: ShiftSnapshot): Long? = when (snapshot.state) {
    is ShiftState.Down, is ShiftState.Cooling -> snapshot.darkSinceWallMillis
    else -> null
}

/** A clock-in is what the dock offers: the rig is cold, its shift broke, or it is frozen. */
private val ShiftState.beforeShift: Boolean
    get() = this is ShiftState.Idle || this is ShiftState.Broken || this is ShiftState.Frozen

/** The hero's share of the first screenful. */
private const val HERO_SHARE_LEAST = 0.34f
private const val HERO_SHARE_MOST = 0.58f

/** Between the first screenful and the name of the first section under it. */
private val SECTION_GAP = 12.dp

/**
 * Above this font scale a pair no longer fits side by side: the four Focus Bond chips become two
 * rows, and the two facts go one to a line.
 */
private const val LARGE_TEXT_ABOVE = 1.3f

/** Above this one the two bars of a running shift go one over the other. */
private const val STACK_BARS_ABOVE = 1.5f

/**
 * Home. The first screenful is the slab, the rig's state in one word, and the one thing to do,
 * pinned in the dock; everything else is a section further down the page.
 */
@Composable
fun HomeScreen(
    snapshot: ShiftSnapshot,
    onboarding: OnboardingState,
    health: ShiftHealth,
    onClockIn: () -> Unit,
    onEndShift: () -> Unit,
    onFreeze: () -> Unit,
    onOpenSetup: () -> Unit,
    onHowItWorks: () -> Unit = {},
    onPreviewReveal: () -> Unit = {},
    /** Opens the clock-out screen (seal the shift, take back a bond, claim ORE). Null hides the entry. */
    onClockOut: (() -> Unit)? = null,
    /** Opens the withdraw screen (take SOL back from the ORE Automation, close the rig). Null hides the entry. */
    onWithdraw: (() -> Unit)? = null,
    /** Null where the launcher cannot pin widgets. */
    onAddWidget: (() -> Unit)? = null,
    /** Non-null only in debug builds (the sensor lab does not exist in release). */
    onOpenSensorLab: (() -> Unit)? = null,
    /** Non-null only in debug and localdev builds (the rig debug screen for the devstack). */
    onOpenRigDebug: (() -> Unit)? = null,
    /** The crank intake's acks (contract A): a current refusal is shown under the rig's state. */
    crank: CrankLinkStatus = CrankLinkStatus(),
    /** What a clock-in can move, stated before the wallet opens (ClockInPolicy.disclosure). */
    clockInAmounts: String? = null,
    /** Until when a shift started now would run, and that a pickup before then ends it early. */
    shiftWindow: String? = null,
    /** The Focus Bond locked at the next clock-in, SKR base units (0 = none). */
    bondSkr: ULong = 0uL,
    /** Null hides the Focus Bond section (previews, tests that do not need it). */
    onBondChange: ((ULong) -> Unit)? = null,
    /** The last real haul as the widget prints it ("0.0194 ORE"). Null: there is none yet. */
    lastHaul: String? = null,
    /** The streak as the widget prints it ("Streak 3 nights"). Null: not read from the chain yet. */
    streak: String? = null,
    /** The most a shift can place, under the clock-in bar ([HomeCopy.clockInCap]). Null hides the line. */
    clockInCap: String? = null,
    /** The phone's settings are still being read: still skeleton lines stand where the shift window will. */
    loading: Boolean = false,
    /** Ask the hero to play its entrance (the app has just opened on this screen). */
    heroEnter: Boolean = false,
) {
    val state = snapshot.state
    val look = lookOf(state)
    val scroll = rememberScrollState()
    val haptics = LocalHdHaptics.current
    val running = state is ShiftState.Armed || state is ShiftState.Down || state is ShiftState.Cooling
    // Freezing is asked twice: it takes the wallet to undo. The question closes when the shift
    // does, and is not waiting there when the next one starts.
    var freezeAsked by rememberSaveable { mutableStateOf(false) }
    val askingFreeze = freezeAsked && running
    LaunchedEffect(running) { if (!running) freezeAsked = false }
    // The question dims the page like a sheet, so Back closes it like one: it keeps the shift.
    BackHandler(enabled = askingFreeze) { freezeAsked = false }

    // The rig's word is at the top of the page. When it changes (a clock-in confirmed, the phone
    // laid down or picked up, a freeze) the page goes back to it: what just happened is answered
    // where it is said, not above a page that was scrolled to read something else. A page that
    // is restored with its old position (a rotation) stays where it was.
    val motion = Hd.motion
    var shownWord by remember { mutableStateOf(look.word) }
    LaunchedEffect(look.word) {
        if (look.word != shownWord) {
            shownWord = look.word
            if (motion.reduced) scroll.scrollTo(0) else scroll.animateScrollTo(0, motion.settle())
        }
    }

    HeroPage(
        scrollState = scroll,
        aboveDock = {
            FreezeQuestion(
                visible = askingFreeze,
                onFreeze = {
                    freezeAsked = false
                    onFreeze()
                },
                onKeep = { freezeAsked = false },
            )
        },
        dock = {
            // The line under the page is gold while the rig is lit: armed, or hot.
            ActionDock(Modifier.testTag(HomeTags.DOCK), seam = state is ShiftState.Armed || state is ShiftState.Down) {
                when {
                    state is ShiftState.Frozen ->
                        BarButton("Unfreeze and clock in", onClick = onClockIn, modifier = Modifier.fillMaxWidth().testTag(HomeTags.UNFREEZE))
                    running -> ShiftBars(onEndShift = onEndShift, onFreeze = { freezeAsked = !freezeAsked })
                    else -> {
                        BarButton("Clock in", onClick = onClockIn, modifier = Modifier.fillMaxWidth().testTag(HomeTags.CLOCK_IN))
                        if (clockInCap != null) {
                            BasicText(
                                clockInCap,
                                Modifier.fillMaxWidth().testTag(HomeTags.CLOCK_IN_CAP),
                                style = Hd.type.caption.copy(color = Hd.colors.ash, textAlign = TextAlign.Center),
                            )
                        }
                    }
                }
            }
        },
    ) { viewport ->
        HeroFirstScreen(
            viewport = viewport,
            minShare = HERO_SHARE_LEAST,
            maxShare = HERO_SHARE_MOST,
            // When the first screenful has room to spare, the next section's name shows above the
            // dock, whose rule then lies where that heading's own rule would be.
            peek = SECTION_GAP + with(LocalDensity.current) { Hd.type.label.lineHeight.toDp() } + HdDimens.Rhythm,
            hero = {
                Hero(
                    spec = HeroSpec(
                        state = look.slab,
                        heat = look.pixels,
                        scrollPx = { scroll.value.toFloat() },
                        interactive = true,
                        enter = heroEnter,
                        onKnock = haptics::knock,
                    ),
                    modifier = Modifier.testTag(HomeTags.HERO).semantics { role = Role.Image },
                    contentDescription = HomeCopy.hero(state),
                )
            },
            // Until the phone's settings are read nothing says setup is unfinished: it is not known yet.
            overlay = { BrandRow(setupLabel = if (onboarding.allDone || loading) "Setup" else "Finish setup", onOpenSetup = onOpenSetup) },
            mark = { PoweredByOre() },
            // The hero's zone ends on a rule: the stage above it, the facts below.
            ruled = true,
            notice = {
                healthLine(health)?.let { line ->
                    Notice(
                        line,
                        Modifier.padding(start = HdDimens.Margin, end = HdDimens.Margin, top = 16.dp).testTag(HomeTags.HEALTH),
                        tone = NoticeTone.Problem,
                    ) { TextAction("Fix keep-alive settings", onClick = onOpenSetup) }
                }
            },
            mustFit = {
                Column(
                    Modifier.fillMaxWidth().padding(start = HdDimens.Margin, end = HdDimens.Margin, top = 20.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    RigState(look)
                    ShiftFacts(snapshot, shiftWindow, loading)
                }
            },
            rest = {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = HdDimens.Margin).padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    crank.refusalLine()?.let { Notice(it, Modifier.testTag(HomeTags.CRANK_REFUSAL), tone = NoticeTone.Problem) }
                    // The sentence belongs to the clock-in: it is about a shift started now.
                    if (state is ShiftState.Idle || state is ShiftState.Broken) {
                        when {
                            shiftWindow != null ->
                                BasicText(shiftWindow, Modifier.testTag(HomeTags.SHIFT_WINDOW), style = Hd.type.caption.copy(color = Hd.colors.ash))
                            loading -> {
                                // Still lines where the sentence will be. Nothing moves while the phone is read.
                                SkeletonLine(widthFraction = 0.92f)
                                SkeletonLine(widthFraction = 0.64f)
                            }
                        }
                    }
                }
            },
        )

        Column(Modifier.fillMaxWidth().padding(top = SECTION_GAP, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            if (state.beforeShift && (clockInAmounts != null || state is ShiftState.Frozen)) {
                PageSection(HomeCopy.LIMITS) {
                    clockInAmounts?.let { Body(it, Modifier.testTag(HomeTags.CLOCK_IN_AMOUNTS)) }
                    if (state is ShiftState.Frozen) {
                        Body(
                            "Freezing took the phone's key. Coming back takes your wallet: one approval unfreezes the rig and arms a new shift.",
                            color = Hd.colors.ash,
                        )
                    }
                }
            }

            // The bond is chosen before a shift, never changed during one.
            if (onBondChange != null && state.beforeShift) FocusBond(bondSkr, onBondChange)

            PageSection("HAUL") {
                Haul(lastHaul, streak)
                Column {
                    LinkRow("Open the morning reveal", onClick = onPreviewReveal, modifier = Modifier.testTag(HomeTags.PREVIEW_REVEAL))
                    if (onClockOut != null) {
                        LinkRow("Clock out: seal the shift, see your ORE", onClick = onClockOut, modifier = Modifier.testTag(HomeTags.CLOCK_OUT))
                    }
                }
            }

            if (onWithdraw != null) {
                PageSection(HomeCopy.SOL_AND_RIG) {
                    LinkRow("Take SOL back or close the rig", onClick = onWithdraw, modifier = Modifier.testTag(HomeTags.WITHDRAW))
                }
            }

            PageSection(HomeCopy.RIG_KEY) { Body(trustFootnote(snapshot, onboarding), color = Hd.colors.ash) }

            if (onAddWidget != null) {
                PageSection("HOME SCREEN") {
                    Body("Rig heat and a shift clock that ticks by itself, on your home screen.")
                    BarButton(
                        "Add the Rig widget",
                        onClick = onAddWidget,
                        modifier = Modifier.fillMaxWidth().testTag(HomeTags.ADD_WIDGET),
                        style = BarButtonStyle.Secondary,
                    )
                }
            }

            // A link to the introduction, never its steps in line: one of them is titled like the clock-in bar.
            PageSection(HomeCopy.HOW_IT_WORKS) {
                Column {
                    LinkRow("How the night shift works", onClick = onHowItWorks, modifier = Modifier.testTag(HomeTags.HOW_IT_WORKS))
                    if (onOpenSensorLab != null) {
                        LinkRow("Sensor lab (debug)", onClick = onOpenSensorLab, modifier = Modifier.testTag(HomeTags.SENSOR_LAB))
                    }
                    if (onOpenRigDebug != null) {
                        LinkRow("Rig key and devstack (debug)", onClick = onOpenRigDebug, modifier = Modifier.testTag(HomeTags.RIG_DEBUG))
                    }
                }
            }
        }
    }
}

/**
 * The brand and the way to setup, over the top of the hero: the mark, the name over the heading,
 * and the action at the far end.
 *
 * Where each goes is measured, not guessed from the font scale. The heading keeps its place under
 * the name while the line has room for it beside the action with a gap between the two. When it
 * has not (a larger font, or the longer "Finish setup") it takes the line under the row, at the
 * margin; it is never run into the action. A name that is too long for what the action leaves
 * wraps under itself, word by word, before the action is moved: only when not even its longest
 * word fits there does the action go under the name, and the heading under that. Each step keeps
 * the band as short as it can be, because the rig's state has to stay on the first screenful.
 */
@Composable
private fun BrandRow(setupLabel: String, onOpenSetup: () -> Unit) {
    Layout(
        content = {
            BrandGlyph(Modifier.layoutId(BrandSlot.Glyph).size(28.dp))
            Label("HEADS DOWN", Modifier.layoutId(BrandSlot.Name), color = Hd.colors.chalk)
            BasicText(
                "Your phone's night shift",
                Modifier.layoutId(BrandSlot.Heading).semantics { heading() },
                style = Hd.type.caption.copy(color = Hd.colors.ash),
            )
            TextAction(setupLabel, onClick = onOpenSetup, modifier = Modifier.layoutId(BrandSlot.Action).testTag(HomeTags.SETUP))
        },
        modifier = Modifier.fillMaxWidth().padding(start = HdDimens.Margin, end = HdDimens.Margin, top = 4.dp, bottom = HdDimens.Rhythm),
    ) { measurables, constraints ->
        fun slot(id: BrandSlot) = measurables.first { it.layoutId == id }
        val width = constraints.maxWidth
        val glyph = slot(BrandSlot.Glyph).measure(Constraints())
        val action = slot(BrandSlot.Action).measure(Constraints(maxWidth = width))
        val indent = glyph.width + 12.dp.roundToPx()
        // What the line has left for words beside the action, a clear gap kept between them.
        val beside = width - indent - BRAND_GAP.roundToPx() - action.width
        // The name shares the line with the action while its longest word fits there.
        val nameBeside = slot(BrandSlot.Name).minIntrinsicWidth(Constraints.Infinity) <= beside
        val nameOnOneLine = slot(BrandSlot.Name).maxIntrinsicWidth(Constraints.Infinity) <= beside
        val name = slot(BrandSlot.Name).measure(Constraints(maxWidth = (if (nameBeside) beside else width - indent).coerceAtLeast(0)))
        val headingBeside = nameOnOneLine && slot(BrandSlot.Heading).maxIntrinsicWidth(Constraints.Infinity) <= beside
        val heading = slot(BrandSlot.Heading).measure(Constraints(maxWidth = if (headingBeside) beside else width))
        val touch = HdDimens.Touch.roundToPx()

        // The first line: the mark and the name, with the action at its end when it fits there.
        val words = name.height + if (headingBeside) heading.height else 0
        val line = maxOf(touch, words, if (nameBeside) action.height else 0)
        val height = line + (if (nameBeside) 0 else action.height) + (if (headingBeside) 0 else heading.height)
        layout(width, height) {
            glyph.placeRelative(0, (line - glyph.height) / 2)
            name.placeRelative(indent, (line - words) / 2)
            when {
                headingBeside -> {
                    heading.placeRelative(indent, (line - words) / 2 + name.height)
                    action.placeRelative(width - action.width, (line - action.height) / 2)
                }
                nameBeside -> {
                    action.placeRelative(width - action.width, (line - action.height) / 2)
                    heading.placeRelative(0, line)
                }
                else -> {
                    action.placeRelative(0, line)
                    heading.placeRelative(0, line + action.height)
                }
            }
        }
    }
}

private enum class BrandSlot { Glyph, Name, Heading, Action }

/** The least clear space between the brand's words and the action at the end of their line. */
private val BRAND_GAP = 16.dp

/** The line this rig stands on, at the hero's lower left: a stroke of ORE gold, then the words. */
@Composable
private fun PoweredByOre() {
    Row(
        Modifier.padding(start = HdDimens.Margin, end = HdDimens.Margin, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(HdDimens.Rhythm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Gold as ink here, so the stroke is still there on the light palette's paper.
        Box(Modifier.size(width = 16.dp, height = HdDimens.SeamLine).background(Hd.colors.seam))
        Label("Powered by ORE", color = Hd.colors.chalk)
    }
}

/** The rig in one word, with the sentence that says what to do about it. */
@Composable
private fun RigState(look: RigLook) {
    val colors = Hd.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("RIG")
            HeatPixels(
                lit = look.pixels,
                // Hot is ORE gold (as ink, so it reads on paper too); a frozen rig's heat is ash.
                color = when (look.slab) {
                    SlabState.Hot -> colors.seam
                    SlabState.Frozen -> colors.ash
                    else -> colors.ember
                },
                description = "Heat ${look.pixels} of 5",
            )
        }
        DisplayText(
            look.word,
            modifier = Modifier.testTag(HomeTags.RIG_WORD).semantics { liveRegion = LiveRegionMode.Polite },
            color = when (look.tone) {
                RigTone.Muted -> colors.ash
                RigTone.Lit -> colors.chalk
                RigTone.Ember -> colors.ember
            },
        )
        Body(
            look.line,
            modifier = if (look.problem) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
            color = if (look.problem) colors.ember else colors.chalk,
        )
    }
}

/**
 * Two facts side by side. On the left the shift's clock: "Runs until" before a shift and while it
 * is armed, "Dark for" while the rig is hot or cooling. On the right, always, the rounds dark.
 */
@Composable
private fun ShiftFacts(snapshot: ShiftSnapshot, shiftWindow: String?, loading: Boolean) {
    val since = darkSince(snapshot)
    val until = runsUntil(snapshot.state, shiftWindow)
    // One tick every half minute, and only while it is composed: hot or cooling.
    val darkFor = if (since == null) {
        null
    } else {
        val now by produceState(System.currentTimeMillis(), since) {
            while (true) {
                value = System.currentTimeMillis()
                delay(30_000)
            }
        }
        TileRenderer.elapsed(now - since)
    }
    val rounds = snapshot.darkRounds.toString()
    // The time is on its way: its place is kept, so "Rounds dark" does not jump when it lands.
    val waiting = loading && (snapshot.state is ShiftState.Idle || snapshot.state is ShiftState.Broken)

    if (LocalDensity.current.fontScale > LARGE_TEXT_ABOVE) {
        // A large font wraps both labels of the pair and the block grows by two lines. One fact
        // to a line keeps them short, and keeps "Rounds dark" above the dock.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HdDimens.Rhythm)) {
            val clock = Modifier.testTag(HomeTags.SHIFT_CLOCK)
            when {
                darkFor != null -> StatRow("Dark for", darkFor, clock)
                until != null -> StatRow(HomeCopy.RUNS_UNTIL, until, clock)
                waiting -> Row(clock.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Body(HomeCopy.RUNS_UNTIL, color = Hd.colors.ash)
                    Box(Modifier.width(72.dp)) { SkeletonLine(height = 16.dp) }
                }
            }
            StatRow("Rounds dark", rounds, Modifier.testTag(HomeTags.ROUNDS_DARK))
        }
        return
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val clock = Modifier.weight(1f).testTag(HomeTags.SHIFT_CLOCK)
        when {
            darkFor != null -> StatBlock("Dark for", darkFor, clock)
            until != null -> StatBlock(HomeCopy.RUNS_UNTIL, until, clock)
            waiting ->
                Column(clock, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label(HomeCopy.RUNS_UNTIL)
                    SkeletonLine(widthFraction = 0.5f, height = 24.dp)
                }
        }
        StatBlock("Rounds dark", rounds, Modifier.weight(1f).testTag(HomeTags.ROUNDS_DARK))
    }
}

/** While a shift runs: two bars, side by side until the font is too large for that. */
@Composable
private fun ShiftBars(onEndShift: () -> Unit, onFreeze: () -> Unit) {
    val end = Modifier.testTag(HomeTags.END_SHIFT)
    val freeze = Modifier.testTag(HomeTags.FREEZE)
    if (LocalDensity.current.fontScale > STACK_BARS_ABOVE) {
        BarButton("End shift", onClick = onEndShift, modifier = end.fillMaxWidth(), style = BarButtonStyle.Secondary)
        BarButton("Freeze", onClick = onFreeze, modifier = freeze.fillMaxWidth(), style = BarButtonStyle.Secondary)
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BarButton("End shift", onClick = onEndShift, modifier = end.weight(1f), style = BarButtonStyle.Secondary)
            BarButton("Freeze", onClick = onFreeze, modifier = freeze.weight(1f), style = BarButtonStyle.Secondary)
        }
    }
}

/**
 * "Freeze" asks before it acts: a plate that rises from the dock, over the foot of the dimmed
 * page, with one sentence and two bars. Nothing on the page moves for it.
 */
@Composable
private fun BoxScope.FreezeQuestion(visible: Boolean, onFreeze: () -> Unit, onKeep: () -> Unit) {
    val motion = Hd.motion
    val colors = Hd.colors
    // The page dims behind the question, and a tap on the dimmed page keeps the shift. To a screen
    // reader the dimming is nothing: "Keep the shift" is the way out it is offered.
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.matchParentSize(),
        enter = if (motion.reduced) EnterTransition.None else fadeIn(motion.settle()),
        exit = if (motion.reduced) ExitTransition.None else fadeOut(motion.settle()),
    ) {
        Box(Modifier.fillMaxSize().background(colors.scrim).pointerInput(onKeep) { detectTapGestures { onKeep() } })
    }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = if (motion.reduced) EnterTransition.None else fadeIn(motion.settle()) + expandVertically(motion.settle(), expandFrom = Alignment.Top),
        exit = if (motion.reduced) ExitTransition.None else fadeOut(motion.settle()) + shrinkVertically(motion.settle(), shrinkTowards = Alignment.Top),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.pit)
                .drawBehind { drawRect(colors.hairline, Offset.Zero, Size(size.width, HdDimens.Hairline.toPx())) }
                .padding(horizontal = HdDimens.Margin, vertical = 12.dp),
        ) {
            Plate(Modifier.fillMaxWidth().testTag(HomeTags.FREEZE_CONFIRM), tone = PlateTone.Slab) {
                Body(HomeCopy.FREEZE_ASK, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Spacer(Modifier.height(4.dp))
                BarButton(
                    HomeCopy.FREEZE_YES,
                    onClick = onFreeze,
                    modifier = Modifier.fillMaxWidth().testTag(HomeTags.FREEZE_YES),
                    style = BarButtonStyle.Danger,
                )
                BarButton(
                    HomeCopy.FREEZE_KEEP,
                    onClick = onKeep,
                    modifier = Modifier.fillMaxWidth().testTag(HomeTags.FREEZE_KEEP),
                    style = BarButtonStyle.Secondary,
                )
            }
        }
    }
}

/**
 * SKR behind tonight's shift. The copy says what happens to it in both outcomes; it is never
 * described as growing, and it cannot: a completed shift returns exactly what was locked.
 */
@Composable
private fun FocusBond(bondSkr: ULong, onBondChange: (ULong) -> Unit) {
    PageSection("FOCUS BOND", Modifier.testTag(HomeTags.BOND_CARD)) {
        Body(
            "Put SKR behind tonight's shift. Finish the shift and the same SKR comes back at your next clock-in. " +
                "Break it and the SKR goes to the Bury auction, where it is sold for ORE that ORE burns. It never goes to Heads Down.",
        )
        Body(
            "A night when none of this phone's heartbeats reach the chain (no network, or the relay is down) also counts as broken.",
            color = Hd.colors.ash,
        )
        val largeText = LocalDensity.current.fontScale > LARGE_TEXT_ABOVE
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Four in a row where a 360dp phone at the normal font has room; otherwise two rows of two.
            val perRow = if (!largeText && maxWidth >= 320.dp) 4 else 2
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(HdDimens.Rhythm)) {
                for (row in FocusBondSetting.CHOICES.chunked(perRow)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HdDimens.Rhythm)) {
                        for (choice in row) {
                            val label = FocusBondSetting.label(choice)
                            Choice(
                                label = label,
                                selected = choice == bondSkr,
                                // Tapping the bond already chosen changes nothing.
                                onClick = { if (choice != bondSkr) onBondChange(choice) },
                                // In one row a chip is as wide as its label is long; "Off" still gets a finger's width.
                                modifier = Modifier
                                    .weight(if (perRow == 4) label.length.coerceAtLeast(4).toFloat() else 1f)
                                    .testTag(HomeTags.BOND_CHOICE + label),
                                compact = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The last haul in ORE gold on a raised plate, or the line that says there is none yet. */
@Composable
private fun Haul(lastHaul: String?, streak: String?) {
    Plate(
        Modifier.fillMaxWidth().testTag(HomeTags.HAUL).semantics(mergeDescendants = true) {},
        tone = PlateTone.Slab,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (lastHaul != null) {
            Label(HomeCopy.LAST_HAUL)
            // Not one line at any price: an amount that is too wide wraps, it is never cut.
            DisplayText(lastHaul, color = Hd.colors.seam, caps = false)
        } else {
            DisplayText("— ORE", color = Hd.colors.ash, caps = false)
            Body("Your first haul appears after your first mined shift.", color = Hd.colors.ash)
        }
        if (streak != null) Label(streak, Modifier.padding(top = 4.dp))
    }
}

/** What happened to last night's shift when it did not end by itself, or null when there is nothing to say. */
internal fun healthLine(health: ShiftHealth): String? = when (health) {
    is ShiftHealth.KilledByOs -> {
        val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(health.diedAtWallMillis))
        val who = when (health.cause) {
            ExitCause.CRASH, ExitCause.ANR -> "Heads Down crashed"
            ExitCause.USER_STOPPED -> "Heads Down was closed"
            else -> "Android stopped Heads Down"
        }
        "Last night's shift ended early: $who at $at after ${health.darkRounds} rounds. Nothing was spent."
    }
    is ShiftHealth.DiedWithoutRecord ->
        "Last night's shift stopped without a goodbye (reboot or battery?) after ${health.darkRounds} rounds."
    else -> null
}

private fun trustFootnote(snapshot: ShiftSnapshot, onboarding: OnboardingState): String = buildString {
    append(rigKeyLine(onboarding.rigKey))
    append(' ')
    if (onboarding.rigRegistered) {
        append("Your rig is registered on-chain: every dig needs a fresh heartbeat from this phone, verified on-chain, ")
        append("inside the caps your wallet signed.")
    } else {
        append("Your rig is not registered on-chain yet, so nothing can be mined or spent; ")
        append("every dig will need a fresh heartbeat from this phone, verified on-chain.")
    }
    if (snapshot.state is ShiftState.Down && !snapshot.signing) append(" (No rig key: this shift is focus-only.)")
}

@Preview(widthDp = 360, heightDp = 800, backgroundColor = HdArgb.PREVIEW_PIT, showBackground = true)
@Composable
private fun HomeHotPreview() = HeadsDownTheme {
    HomeScreen(
        snapshot = ShiftSnapshot(
            ShiftState.Down(ShiftSpec(1, ShiftMode.NIGHT), 0, 0),
            darkRounds = 55,
            darkSinceWallMillis = System.currentTimeMillis() - 72 * 60_000,
        ),
        onboarding = OnboardingState(),
        health = ShiftHealth.NoRecentShift,
        onClockIn = {}, onEndShift = {}, onFreeze = {}, onOpenSetup = {},
        onAddWidget = {}, onOpenSensorLab = {},
    )
}

@Preview(widthDp = 360, heightDp = 800, backgroundColor = HdArgb.PREVIEW_PIT, showBackground = true)
@Composable
private fun HomeColdPreview() = HeadsDownTheme {
    HomeScreen(
        snapshot = ShiftSnapshot.IDLE,
        onboarding = OnboardingState(),
        health = ShiftHealth.NoRecentShift,
        onClockIn = {}, onEndShift = {}, onFreeze = {}, onOpenSetup = {},
        shiftWindow = "A shift you start now runs until 06:28, two minutes before your alarm. Picking the phone up before then ends the shift early.",
        clockInCap = "Up to 0.02 SOL tonight",
    )
}
