package xyz.headsdown.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.headsdown.rig.FocusBondSetting
import xyz.headsdown.feature.oemkeepalive.ExitCause
import xyz.headsdown.feature.oemkeepalive.ShiftHealth
import xyz.headsdown.feature.shift.BreakReason
import xyz.headsdown.feature.shift.CrankLinkStatus
import xyz.headsdown.feature.shift.refusalLine
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSnapshot
import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.surface.tile.TileRenderer
import xyz.headsdown.ui.theme.HdColors
import xyz.headsdown.ui.theme.HeadsDownTheme
import xyz.headsdown.ui.theme.PixelLabel
import java.text.DateFormat
import java.util.Date

object HomeTags {
    const val RIG_WORD = "home-rig-word"
    const val CLOCK_IN = "home-clock-in"
    const val PREVIEW_REVEAL = "home-preview-reveal"
    const val CLOCK_OUT = "home-clock-out"
    const val ADD_WIDGET = "home-add-widget"
    const val SENSOR_LAB = "home-sensor-lab"
    const val RIG_DEBUG = "home-rig-debug"
    const val HOW_IT_WORKS = "home-how-it-works"
    const val CRANK_REFUSAL = "home-crank-refusal"
    const val CLOCK_IN_AMOUNTS = "home-clock-in-amounts"
    const val UNFREEZE = "home-unfreeze"
    const val BOND_CARD = "home-focus-bond"
    const val BOND_CHOICE = "home-focus-bond-choice-"
}

internal data class RigLook(val word: String, val color: Color, val line: String, val pixels: Int)

internal fun lookOf(state: ShiftState): RigLook = when (state) {
    ShiftState.Idle -> RigLook("Rig cold", HdColors.AshMuted, "Clock in, then lay your phone face-down.", 0)
    is ShiftState.Armed -> RigLook("Armed", HdColors.Ember, "Lay your phone face-down to start the shift.", 1)
    is ShiftState.Down -> RigLook("Rig hot", HdColors.Ember, "Signing a heartbeat every ORE round.", 5)
    is ShiftState.Cooling -> RigLook("Cooling", HdColors.Cooling, "Put it back face-down to keep the shift alive.", 3)
    is ShiftState.Broken -> RigLook("Rig cold", HdColors.AshMuted, breakLine(state.reason), 0)
    is ShiftState.Frozen -> RigLook("Frozen", HdColors.Frost, "No digs until you unfreeze it with your wallet.", 5)
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
    onHowItWorks: () -> Unit = {},
    onPreviewReveal: () -> Unit = {},
    /** Opens the clock-out screen (seal the shift, take back a bond, claim ORE). Null hides the entry. */
    onClockOut: (() -> Unit)? = null,
    /** Null where the launcher cannot pin widgets. */
    onAddWidget: (() -> Unit)? = null,
    /** Non-null only in debug builds (the sensor lab does not exist in release). */
    onOpenSensorLab: (() -> Unit)? = null,
    /** Non-null only in debug and localdev builds (the rig debug screen for the devstack). */
    onOpenRigDebug: (() -> Unit)? = null,
    /** The crank intake's acks (contract A): a current refusal is shown on the rig card. */
    crank: CrankLinkStatus = CrankLinkStatus(),
    /** What a clock-in can move, stated before the wallet opens (ClockInPolicy.disclosure). */
    clockInAmounts: String? = null,
    /** The Focus Bond locked at the next clock-in, SKR base units (0 = none). */
    bondSkr: ULong = 0uL,
    /** Null hides the Focus Bond card (previews, tests that do not need it). */
    onBondChange: ((ULong) -> Unit)? = null,
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
            PixelMark(Modifier.size(28.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("HEADS DOWN", style = PixelLabel, color = HdColors.AshMuted)
                Text(
                    "Your phone's night shift",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
            TextButton(onClick = onOpenSetup) { Text(if (onboarding.allDone) "Setup" else "Finish setup") }
        }

        HealthBanner(health, onOpenSetup)
        RigCard(snapshot, crank, clockInAmounts, onClockIn, onEndShift, onFreeze)
        // The bond is chosen before a shift, never changed during one.
        if (onBondChange != null && (snapshot.state is ShiftState.Idle || snapshot.state is ShiftState.Broken || snapshot.state is ShiftState.Frozen)) {
            FocusBondCard(bondSkr, onBondChange)
        }
        HaulCard(onPreviewReveal, onClockOut)
        if (onAddWidget != null) WidgetCard(onAddWidget)

        Text(
            trustFootnote(snapshot, onboarding),
            color = HdColors.AshMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onHowItWorks, modifier = Modifier.testTag(HomeTags.HOW_IT_WORKS)) { Text("How the night shift works") }
            if (onOpenSensorLab != null) {
                TextButton(onClick = onOpenSensorLab, modifier = Modifier.testTag(HomeTags.SENSOR_LAB)) {
                    Text("Sensor lab (debug)", color = HdColors.Cooling)
                }
            }
        }
        if (onOpenRigDebug != null) {
            TextButton(onClick = onOpenRigDebug, modifier = Modifier.testTag(HomeTags.RIG_DEBUG)) {
                Text("Rig key and devstack (debug)", color = HdColors.Cooling)
            }
        }
        Text("Powered by ORE", style = PixelLabel, color = HdColors.AshMuted)
    }
}

@Composable
private fun RigCard(
    snapshot: ShiftSnapshot,
    crank: CrankLinkStatus,
    clockInAmounts: String?,
    onClockIn: () -> Unit,
    onEndShift: () -> Unit,
    onFreeze: () -> Unit,
) {
    val look = lookOf(snapshot.state)
    val hot = snapshot.state is ShiftState.Down
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (hot) BorderStroke(2.dp, emberBreath()) else BorderStroke(1.dp, HdColors.CharcoalOutline),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeatPixels(look.pixels, look.color)
                Spacer(Modifier.size(10.dp))
                Text("RIG", style = PixelLabel, color = HdColors.AshMuted)
            }
            Text(
                look.word,
                style = MaterialTheme.typography.displaySmall,
                color = look.color,
                modifier = Modifier.testTag(HomeTags.RIG_WORD),
            )
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
            crank.refusalLine()?.let {
                Text(it, color = HdColors.Cooling, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(HomeTags.CRANK_REFUSAL))
            }
            Spacer(Modifier.height(4.dp))
            when (snapshot.state) {
                ShiftState.Idle, is ShiftState.Broken -> {
                    Button(
                        onClick = onClockIn,
                        modifier = Modifier.fillMaxWidth().testTag(HomeTags.CLOCK_IN),
                        colors = ButtonDefaults.buttonColors(containerColor = HdColors.Ember, contentColor = HdColors.Charcoal),
                    ) { Text("Clock in") }
                    clockInAmounts?.let {
                        Text(
                            it,
                            color = HdColors.AshMuted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag(HomeTags.CLOCK_IN_AMOUNTS),
                        )
                    }
                }
                is ShiftState.Armed, is ShiftState.Down, is ShiftState.Cooling -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onEndShift, modifier = Modifier.weight(1f)) { Text("End shift") }
                    OutlinedButton(onClick = onFreeze, modifier = Modifier.weight(1f)) { Text("Freeze") }
                }
                is ShiftState.Frozen -> {
                    Button(
                        onClick = onClockIn,
                        modifier = Modifier.fillMaxWidth().testTag(HomeTags.UNFREEZE),
                        colors = ButtonDefaults.buttonColors(containerColor = HdColors.Frost, contentColor = HdColors.Charcoal),
                    ) { Text("Unfreeze and clock in") }
                    Text(
                        "Freezing took the phone's key. Coming back takes your wallet: one approval unfreezes the rig and arms a new shift.",
                        color = HdColors.AshMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * SKR behind tonight's shift. The copy says what happens to it in both outcomes; it is never
 * described as growing, and it cannot: a completed shift returns exactly what was locked.
 */
@Composable
private fun FocusBondCard(bondSkr: ULong, onBondChange: (ULong) -> Unit) {
    Card(
        modifier = Modifier.testTag(HomeTags.BOND_CARD),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, HdColors.CharcoalOutline),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("FOCUS BOND", style = PixelLabel, color = HdColors.AshMuted)
            Text(
                "Put SKR behind tonight's shift. Finish the shift and the same SKR comes back at your next clock-in. " +
                    "Break it and the SKR goes to the Bury auction, where it is sold for ORE that ORE burns. It never goes to Heads Down.",
                color = HdColors.Ash,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "A night when none of this phone's heartbeats reach the chain (no network, or the relay is down) also counts as broken.",
                color = HdColors.AshMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in FocusBondSetting.CHOICES) {
                    val label = FocusBondSetting.label(choice)
                    val tag = Modifier.testTag(HomeTags.BOND_CHOICE + label).semantics {
                        contentDescription = if (choice == bondSkr) "$label, selected" else label
                    }
                    if (choice == bondSkr) {
                        Button(
                            onClick = {},
                            modifier = tag,
                            colors = ButtonDefaults.buttonColors(containerColor = HdColors.Ember, contentColor = HdColors.Charcoal),
                        ) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onBondChange(choice) }, modifier = tag) { Text(label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HaulCard(onPreviewReveal: () -> Unit, onClockOut: (() -> Unit)?) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, HdColors.OreGold.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("HAUL", style = PixelLabel, color = HdColors.OreGold)
            Text("— ORE", color = HdColors.OreGold, style = MaterialTheme.typography.displaySmall)
            Text("Your first haul appears after your first mined shift.", color = HdColors.AshMuted)
            TextButton(onClick = onPreviewReveal, modifier = Modifier.testTag(HomeTags.PREVIEW_REVEAL)) {
                Text("Open the morning reveal", color = HdColors.OreGold)
            }
            if (onClockOut != null) {
                OutlinedButton(onClick = onClockOut, modifier = Modifier.fillMaxWidth().testTag(HomeTags.CLOCK_OUT)) {
                    Text("Clock out: seal the shift, see your ORE")
                }
            }
        }
    }
}

@Composable
private fun WidgetCard(onAddWidget: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, HdColors.CharcoalOutline),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("HOME SCREEN", style = PixelLabel, color = HdColors.AshMuted)
            Text("Rig heat and a shift clock that ticks by itself, on your home screen.", color = HdColors.Ash)
            OutlinedButton(onClick = onAddWidget, modifier = Modifier.testTag(HomeTags.ADD_WIDGET)) { Text("Add the Rig widget") }
        }
    }
}

/** A slow ember breath around a hot rig. Composed only while hot, so a cold screen is still. */
@Composable
private fun emberBreath(): Color {
    val glow by rememberInfiniteTransition(label = "rig-glow").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Reverse),
        label = "rig-glow-alpha",
    )
    return HdColors.Ember.copy(alpha = glow)
}

/** Five pixels that fill with heat, as on the widget. */
@Composable
private fun HeatPixels(lit: Int, color: Color) {
    Canvas(
        Modifier
            .size(width = 38.dp, height = 6.dp)
            .semantics { contentDescription = "Heat $lit of 5" },
    ) {
        val px = size.height
        val gap = (size.width - 5 * px) / 4
        repeat(5) { i ->
            drawRect(
                color = if (i < lit) color else HdColors.CharcoalOutline,
                topLeft = Offset(i * (px + gap), 0f),
                size = Size(px, px),
            )
        }
    }
}

/** The pixel mark: a face-down phone (ember) under an ORE-gold heartbeat pixel. */
@Composable
private fun PixelMark(modifier: Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val p = size.minDimension / 7
            fun px(x: Int, y: Int, c: Color) = drawRect(c, Offset(x * p, y * p), Size(p, p))
            // heartbeat
            px(1, 2, HdColors.OreGold); px(2, 2, HdColors.OreGold); px(3, 1, HdColors.OreGold)
            px(4, 3, HdColors.OreGold); px(5, 2, HdColors.OreGold)
            // phone, face-down
            for (x in 0..6) px(x, 5, HdColors.Ember)
            px(0, 4, HdColors.EmberDim); px(6, 4, HdColors.EmberDim)
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
    if (onboarding.rigRegistered) {
        append("Your rig is registered on-chain: every dig needs a fresh heartbeat from this phone, verified on-chain, ")
        append("inside the caps your wallet signed.")
    } else {
        append("Your rig is not registered on-chain yet, so nothing can be mined or spent; ")
        append("every dig will need a fresh heartbeat from this phone, verified on-chain.")
    }
    if (snapshot.state is ShiftState.Down && !snapshot.signing) append(" (No rig key: this shift is focus-only.)")
}

@Preview(widthDp = 400, heightDp = 1100)
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

@Preview(widthDp = 400, heightDp = 1000)
@Composable
private fun HomeColdPreview() = HeadsDownTheme {
    HomeScreen(
        snapshot = ShiftSnapshot.IDLE,
        onboarding = OnboardingState(),
        health = ShiftHealth.NoRecentShift,
        onClockIn = {}, onEndShift = {}, onFreeze = {}, onOpenSetup = {},
    )
}
