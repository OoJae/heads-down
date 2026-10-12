package xyz.headsdown.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import xyz.headsdown.core.design.BrandGlyph
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.HdArgb
import xyz.headsdown.core.design.HdDimens
import xyz.headsdown.core.design.components.ActionDock
import xyz.headsdown.core.design.components.BarButton
import xyz.headsdown.core.design.components.DisplayText
import xyz.headsdown.core.design.components.Label
import xyz.headsdown.core.design.components.PinnedBarScreen
import xyz.headsdown.core.design.components.Plate
import xyz.headsdown.core.design.components.PlateTone
import xyz.headsdown.core.design.components.ProvideCappedFontScale
import xyz.headsdown.ui.setup.Body
import xyz.headsdown.ui.theme.HeadsDownTheme

/** The ritual, step by step, in plain words. Kept as data so the honesty test can read it. */
object NightShiftCopy {
    const val TITLE = "Your phone's night shift"
    const val LEAD = "At night your phone does one small job: it proves, once per ORE round, that it is still " +
        "lying face-down. Only then may your own ORE Automation dig."

    data class Step(val title: String, val body: String)

    val STEPS = listOf(
        Step(
            "Clock in",
            "At bedtime, tap the Heads Down tile and approve one wallet transaction. It funds a capped shift " +
                "inside your own ORE Automation. The caps are yours; the phone can only tighten them.",
        ),
        Step(
            "Heads down",
            "Lay the phone face-down on the charger. While it stays dark, it signs a heartbeat about once " +
                "a minute with a key that never leaves the phone's Android Keystore.",
        ),
        Step(
            "The rig digs, or it waits",
            "A crank may dig for you only in rounds with a fresh heartbeat from this phone, and only when the " +
                "price gate says mining is the cheaper route. Otherwise nothing is placed.",
        ),
        Step(
            "Pick it up and it goes cold",
            "Lifting the phone or unplugging it cools the rig at once, and the shift ends if it is not back " +
                "face-down within 10 seconds; unlocking ends it straight away. If Android closes the app, " +
                "there are no heartbeats and no digs, and nothing is lost.",
        ),
        Step(
            "Morning haul",
            "After your alarm a page opens: the shift is over and not sealed yet. Clock out there and the haul " +
                "appears: what the rig dug, what you paid per ORE, and the market price next to it.",
        ),
    )

    const val NOT_TITLE = "What it is not"
    const val NOT_BODY = "This is not income. Mining is one way to accumulate ORE by the cheaper route. Some nights " +
        "buying is cheaper, some nights the gate stays closed, and the app tells you which."

    const val CONTINUE = "Set up my rig"

    val allText: List<String>
        get() = listOf(TITLE, LEAD, NOT_TITLE, NOT_BODY, CONTINUE) + STEPS.flatMap { listOf(it.title, it.body) }
}

const val INTRO_CONTINUE_TAG = "intro-continue"

/**
 * What "your phone's night shift" means and the face-down ritual, honestly (no income language;
 * what happens when things fail). Opened from setup and from the home screen; its one bar goes
 * back to where the person came from.
 */
@Composable
fun NightShiftIntro(onContinue: () -> Unit, continueLabel: String = NightShiftCopy.CONTINUE) {
    PinnedBarScreen(
        content = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                BrandGlyph(Modifier.size(28.dp))
                Label("HEADS DOWN", color = Hd.colors.chalk)
            }
            DisplayText(NightShiftCopy.TITLE, Modifier.semantics { heading() }, style = Hd.type.title)
            Body(NightShiftCopy.LEAD)

            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                NightShiftCopy.STEPS.forEachIndexed { i, step -> RitualStep(i + 1, step) }
            }

            Plate(Modifier.fillMaxWidth(), tone = PlateTone.Slab) {
                Label(NightShiftCopy.NOT_TITLE)
                Body(NightShiftCopy.NOT_BODY)
            }
        },
        dock = {
            ActionDock {
                BarButton(continueLabel, onClick = onContinue, modifier = Modifier.fillMaxWidth().testTag(INTRO_CONTINUE_TAG))
            }
        },
    )
}

/** One step of the ritual: a square badge with its number, the step's name, and what happens in it. */
@Composable
private fun RitualStep(index: Int, step: NightShiftCopy.Step) {
    val colors = Hd.colors
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Every badge is the outlined one. On the setup screen a filled badge says "done"; a
        // filled one here, on a step of a ritual that is only being described, would say the same.
        Box(
            Modifier.padding(top = 2.dp).size(28.dp).border(HdDimens.Hairline, colors.ash, Hd.shapes.chip),
            contentAlignment = Alignment.Center,
        ) {
            // The number is the badge's drawing; the order of the steps already says it. Like the
            // badge it does not grow with the font, or a digit would be cut by its square.
            ProvideCappedFontScale(max = 1f) {
                BasicText("$index", Modifier.clearAndSetSemantics { }, style = Hd.type.label.copy(color = colors.ash))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(step.title, style = Hd.type.headline.copy(color = colors.chalk))
            Body(step.body, color = colors.ash)
        }
    }
}

@Preview(widthDp = 360, heightDp = 1300, backgroundColor = HdArgb.PREVIEW_PIT, showBackground = true)
@Composable
private fun NightShiftIntroPreview() = HeadsDownTheme { NightShiftIntro(onContinue = {}) }
