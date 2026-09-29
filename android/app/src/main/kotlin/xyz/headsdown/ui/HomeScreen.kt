package xyz.headsdown.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.headsdown.feature.oemkeepalive.ExitCause
import xyz.headsdown.feature.oemkeepalive.ShiftHealth
import xyz.headsdown.feature.shift.BreakReason
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSnapshot
import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.surface.tile.TileRenderer
import xyz.headsdown.ui.theme.HdColors
import xyz.headsdown.ui.theme.HeadsDownTheme
import java.text.DateFormat
import java.util.Date

private data class RigLook(val word: String, val color: Color, val line: String)

private fun lookOf(state: ShiftState): RigLook = when (state) {
    ShiftState.Idle -> RigLook("Rig cold", HdColors.AshMuted, "Clock in, then lay your phone face-down.")
    is ShiftState.Armed -> RigLook("Armed", HdColors.Ember, "Lay your phone face-down to start the shift.")
    is ShiftState.Down -> RigLook("Rig hot", HdColors.Ember, "Signing a heartbeat every ORE round.")
    is ShiftState.Cooling -> RigLook("Cooling", HdColors.Cooling, "Put it back face-down to keep the shift alive.")
    is ShiftState.Broken -> RigLook("Rig cold", HdColors.AshMuted, breakLine(state.reason))
    is ShiftState.Frozen -> RigLook("Frozen", HdColors.Frost, "No digs until you unfreeze it with your wallet.")
}

private fun breakLine(reason: BreakReason) = when (reason) {
    BreakReason.LIFTED -> "Shift ended: the phone was picked up."
    BreakReason.SCREEN_ON -> "Shift ended: the screen stayed on."
    BreakReason.UNPLUGGED -> "Shift ended: the charger was unplugged."
    BreakReason.UNLOCKED -> "Shift ended: the phone was unlocked."
}

@Composable
fun HomeScreen(
    snapshot: ShiftSnapshot,
    onboarding: OnboardingState,
    health: ShiftHealth,
    onClockIn: () -> Unit,
    onEndShift: () -> Unit,
    onFreeze: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Heads Down", style = MaterialTheme.typography.headlineSmall)
                Text("Your phone's night shift · powered by ORE", color = HdColors.AshMuted)
            }
            TextButton(onClick = onOpenSetup) { Text(if (onboarding.allDone) "Setup" else "Finish setup") }
        }

        HealthBanner(health, onOpenSetup)
        RigCard(snapshot, onClockIn, onEndShift, onFreeze)
        HaulCard()

        Text(
            trustFootnote(snapshot, onboarding),
            color = HdColors.AshMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RigCard(snapshot: ShiftSnapshot, onClockIn: () -> Unit, onEndShift: () -> Unit, onFreeze: () -> Unit) {
    val look = lookOf(snapshot.state)
    val hot = snapshot.state is ShiftState.Down
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (hot) 2.dp else 1.dp, if (hot) HdColors.Ember else HdColors.CharcoalOutline),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(look.word, style = MaterialTheme.typography.displaySmall, color = look.color)
            Text(look.line, color = HdColors.Ash)
            val since = snapshot.darkSinceWallMillis
            if (since != null && snapshot.state !is ShiftState.Idle) {
                val now by produceState(System.currentTimeMillis(), since) {
                    while (true) {
                        value = System.currentTimeMillis()
                        delay(30_000)
                    }
                }
                Stat("Dark for", TileRenderer.elapsed(now - since))
            }
            Stat("Rounds dark", snapshot.darkRounds.toString())
            Spacer(Modifier.height(4.dp))
            when (snapshot.state) {
                ShiftState.Idle, is ShiftState.Broken -> Button(
                    onClick = onClockIn,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = HdColors.Ember, contentColor = HdColors.Charcoal),
                ) { Text("Clock in") }
                is ShiftState.Armed, is ShiftState.Down, is ShiftState.Cooling -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onEndShift, modifier = Modifier.weight(1f)) { Text("End shift") }
                    OutlinedButton(onClick = onFreeze, modifier = Modifier.weight(1f)) { Text("Freeze") }
                }
                is ShiftState.Frozen -> Text("Unfreezing needs your wallet and arrives with the on-chain program.", color = HdColors.AshMuted)
            }
        }
    }
}

@Composable
private fun HaulCard() {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, HdColors.OreGold.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Haul", color = HdColors.OreGold, style = MaterialTheme.typography.titleMedium)
            Text("— ORE", color = HdColors.OreGold, style = MaterialTheme.typography.displaySmall)
            Text("Your first haul appears after your first mined shift.", color = HdColors.AshMuted)
        }
    }
}

@Composable
private fun HealthBanner(health: ShiftHealth, onFix: () -> Unit) {
    val message = when (health) {
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
        else -> return
    }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = HdColors.EmberDim.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(message, color = HdColors.Ash)
            TextButton(onClick = onFix) { Text("Fix keep-alive settings") }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = HdColors.AshMuted)
        Text(value, color = HdColors.Ash, fontWeight = FontWeight.SemiBold)
    }
}

private fun trustFootnote(snapshot: ShiftSnapshot, onboarding: OnboardingState): String = buildString {
    append(if (onboarding.rigKeyReady) "Heartbeats are signed by this phone's hardware key. " else "Create your rig key to sign heartbeats. ")
    append("Your rig is not registered on-chain yet, so nothing can be mined or spent; ")
    append("every dig will need a fresh heartbeat from this phone, verified on-chain.")
    if (snapshot.state is ShiftState.Down && !snapshot.signing) append(" (No rig key: this shift is focus-only.)")
}

@Preview
@Composable
private fun HomePreview() = HeadsDownTheme {
    HomeScreen(
        snapshot = ShiftSnapshot(
            ShiftState.Down(ShiftSpec(1, ShiftMode.NIGHT), 0, 0),
            darkRounds = 55,
            darkSinceWallMillis = System.currentTimeMillis() - 72 * 60_000,
        ),
        onboarding = OnboardingState(),
        health = ShiftHealth.NoRecentShift,
        onClockIn = {}, onEndShift = {}, onFreeze = {}, onOpenSetup = {},
    )
}
