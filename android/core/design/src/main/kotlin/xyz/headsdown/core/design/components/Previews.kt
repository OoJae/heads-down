package xyz.headsdown.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import xyz.headsdown.core.design.BrandGlyph
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.HdDimens
import xyz.headsdown.core.design.HeadsDownTheme
import xyz.headsdown.core.design.Wordmark

// The component gallery, in both palettes and at the largest font scale. Nothing here is shipped
// behaviour: the functions are private and only Android Studio's preview pane calls them.

@Composable
private fun Sheet(dark: Boolean, content: @Composable () -> Unit) = HeadsDownTheme(darkTheme = dark) {
    Column(
        Modifier.fillMaxWidth().background(Hd.colors.pit).padding(HdDimens.Margin),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
private fun Marks() {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        BrandGlyph(Modifier.size(48.dp))
        BrandGlyph(Modifier.size(24.dp), hull = Hd.colors.chalk, seam = Hd.colors.chalk)
        Wordmark()
    }
    Label("Powered by ORE", color = Hd.colors.seam)
}

@Composable
private fun TypeAndHero() {
    Hero(HeroSpec(SlabState.Hot, heat = 4), contentDescription = "The rig is hot")
    HeroNumerals("0.0194", color = Hd.colors.seam)
    DisplayText("Rig hot")
    DisplayText("Morning haul", style = Hd.type.title)
    BasicText("Signing a heartbeat every ORE round.", style = Hd.type.body.copy(color = Hd.colors.chalk))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        HeatPixels(3, description = "Heat 3 of 5")
        Label("Rig")
    }
    StatRow("Rounds dark", "55")
    StatBlock("Dark for", "1:12")
}

@Composable
private fun Controls() {
    BarButton("Clock in", onClick = {}, modifier = Modifier.fillMaxWidth(), seam = true)
    BarButton("End shift", onClick = {}, modifier = Modifier.fillMaxWidth(), style = BarButtonStyle.Secondary)
    BarButton("Close the rig", onClick = {}, modifier = Modifier.fillMaxWidth(), style = BarButtonStyle.Danger)
    BarButton("Waiting for your wallet…", onClick = {}, modifier = Modifier.fillMaxWidth(), busy = true)
    BarButton("Clock out", onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false)
    Choice("Keep it in my Miner", selected = true, onClick = {})
    Choice("Claim all to my wallet", selected = false, onClick = {})
    Choice("End the shift now", selected = true, onClick = {}, role = Role.Checkbox)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (label in listOf("Off", "10 SKR", "50 SKR")) Choice(label, selected = label == "10 SKR", onClick = {}, compact = true)
    }
    LinkRow("How the night shift works", onClick = {})
    TextAction("See this shift on-chain", onClick = {})
}

@Composable
private fun PlatesAndRows() {
    SectionHeader("Clock out")
    Plate(tone = PlateTone.Slab) {
        LedgerRow("Rent for the shift log", figure = "0.00130048 SOL")
        LedgerRow("Focus Bond", figure = "100 SKR") {
            BasicText("Comes back to your wallet.", style = Hd.type.caption.copy(color = Hd.colors.ash))
        }
        LedgerRow("ORE in your Miner", figure = "0.00020001 ORE", figureColor = Hd.colors.seam)
    }
    Notice("Nothing was spent.")
    Notice("The chain could not be read.", tone = NoticeTone.Problem) { TextAction("Try again", onClick = {}) }
    StepRow(1, "Notifications", subtitle = "Your shift shows as an ongoing notification.", status = StepStatus.Done)
    StepRow(2, "Morning alarm", subtitle = "Your haul reveal opens right after your own alarm rings.", status = StepStatus.Current) {
        BarButton("Allow exact alarm", onClick = {})
    }
    StepRow(3, "Create your rig key", status = StepStatus.Todo)
    SkeletonLine(widthFraction = 0.6f)
    SkeletonBlock(height = 56.dp)
}

@Preview(name = "Marks, type, hero · dark", widthDp = 360)
@Composable
private fun TypeDarkPreview() = Sheet(dark = true) { Marks(); TypeAndHero() }

@Preview(name = "Marks, type, hero · light", widthDp = 360)
@Composable
private fun TypeLightPreview() = Sheet(dark = false) { Marks(); TypeAndHero() }

@Preview(name = "Controls · dark", widthDp = 360)
@Composable
private fun ControlsDarkPreview() = Sheet(dark = true) { Controls() }

@Preview(name = "Controls · light", widthDp = 360)
@Composable
private fun ControlsLightPreview() = Sheet(dark = false) { Controls() }

@Preview(name = "Controls · dark · font scale 2.0", widthDp = 320, fontScale = 2f)
@Composable
private fun ControlsLargeTextPreview() = Sheet(dark = true) { Controls() }

@Preview(name = "Plates and rows · dark", widthDp = 360)
@Composable
private fun PlatesDarkPreview() = Sheet(dark = true) { PlatesAndRows() }

@Preview(name = "Plates and rows · light", widthDp = 360)
@Composable
private fun PlatesLightPreview() = Sheet(dark = false) { PlatesAndRows() }

@Preview(name = "Pinned bar screen · dark", widthDp = 360, heightDp = 640)
@Composable
private fun PinnedBarPreview() = HeadsDownTheme {
    PinnedBarScreen(
        content = {
            DisplayText("Clock out", style = Hd.type.title)
            PlatesAndRows()
        },
        dock = {
            ActionDock(seam = true) {
                BarButton("Clock out", onClick = {}, modifier = Modifier.fillMaxWidth(), seam = true)
                TextAction("Close", onClick = {})
            }
        },
    )
}
