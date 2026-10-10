package xyz.headsdown.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import xyz.headsdown.ui.theme.HdColors
import xyz.headsdown.ui.theme.HeadsDownTheme
import xyz.headsdown.ui.theme.PixelLabel

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
            "Lay the phone face-down on the charger. While it stays dark, it signs a heartbeat about every " +
                "78 seconds with a key that never leaves the phone's Android Keystore.",
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
            "Your alarm opens the haul: what the rig dug, what you paid per ORE, and the market price next to it.",
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
 * The first onboarding page: what "your phone's night shift" means and the face-down ritual,
 * honestly (no income language; what happens when things fail).
 */
@Composable
fun NightShiftIntro(onContinue: () -> Unit, continueLabel: String = NightShiftCopy.CONTINUE) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("HEADS DOWN", style = PixelLabel, color = HdColors.AshMuted)
        Text(
            NightShiftCopy.TITLE,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(NightShiftCopy.LEAD, color = HdColors.Ash, style = MaterialTheme.typography.bodyMedium)

        NightShiftCopy.STEPS.forEachIndexed { i, step -> RitualStep(i + 1, step) }

        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, HdColors.OreGold.copy(alpha = 0.6f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(NightShiftCopy.NOT_TITLE, color = HdColors.OreGold, style = MaterialTheme.typography.titleMedium)
                Text(NightShiftCopy.NOT_BODY, color = HdColors.Ash, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().testTag(INTRO_CONTINUE_TAG),
            colors = ButtonDefaults.buttonColors(containerColor = HdColors.Ember, contentColor = HdColors.Charcoal),
        ) { Text(continueLabel) }
    }
}

@Composable
private fun RitualStep(index: Int, step: NightShiftCopy.Step) {
    Row(verticalAlignment = Alignment.Top) {
        // A square pixel badge instead of a circle: the rig's pixel look.
        Box(
            Modifier
                .size(28.dp)
                .background(if (index == 2) HdColors.Ember else HdColors.CharcoalOutline, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$index",
                textAlign = TextAlign.Center,
                color = if (index == 2) HdColors.Charcoal else HdColors.Ash,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(step.title, style = MaterialTheme.typography.titleMedium)
            Text(step.body, color = HdColors.AshMuted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Preview(widthDp = 400, heightDp = 1200)
@Composable
private fun NightShiftIntroPreview() = HeadsDownTheme { NightShiftIntro(onContinue = {}) }
