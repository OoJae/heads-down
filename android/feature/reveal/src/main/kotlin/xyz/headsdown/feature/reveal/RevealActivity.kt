package xyz.headsdown.feature.reveal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat

/** Placeholder haul until the indexer / ShiftLog is wired in. */
data class HaulSummary(
    val roundsDark: String = "—",
    val roundsDug: String = "—",
    val oreMined: String = "—",
    val effectivePrice: String = "—",
)

/**
 * STUB: the morning haul reveal. Opened full-screen over the lock screen by the exact alarm
 * (manifest: `showWhenLocked` + `turnScreenOn`, not exported). The board replay, near
 * misses and the one-tap clock-out arrive with the on-chain program and indexer.
 */
class RevealActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationManagerCompat.from(this).cancel(RevealAlarmReceiver.NOTIFICATION_ID)
        setContent { RevealScreen(HaulSummary(), onDone = ::finish) }
    }
}

private val Charcoal = Color(0xFF121314)
private val OreGold = Color(0xFFF2B233)
private val Ember = Color(0xFFFF6A1A)

@Composable
fun RevealScreen(summary: HaulSummary, onDone: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = Charcoal, primary = OreGold, secondary = Ember)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Charcoal)
                .safeDrawingPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Morning haul", color = OreGold, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Last night's shift", color = Color(0xFFB9B4AE), fontSize = 16.sp)
            Spacer(Modifier.height(32.dp))
            Stat("Rounds dark", summary.roundsDark)
            Stat("Rounds dug", summary.roundsDug)
            Stat("ORE mined", summary.oreMined)
            Stat("Effective price per ORE", summary.effectivePrice)
            Spacer(Modifier.height(32.dp))
            Text(
                "The board replay and clock-out arrive with the on-chain program. This build has not spent anything.",
                color = Color(0xFF8C8680),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = OreGold, contentColor = Charcoal),
            ) { Text("Done") }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFFECE7E1), fontSize = 16.sp)
        Text(value, color = OreGold, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Preview
@Composable
private fun RevealPreview() = RevealScreen(HaulSummary(roundsDark = "312", roundsDug = "288"), onDone = {})
