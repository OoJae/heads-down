package xyz.headsdown.surface.widget

import java.math.BigDecimal
import java.math.RoundingMode

/** Rendered text for one Rig widget state. */
data class RigWidgetText(
    val heat: RigHeat,
    /** "Rig hot", "Cooling", ... */
    val headline: String,
    /** Shown next to the chronometer while it runs, otherwise the whole status line. */
    val line: String,
    /** Count-up chronometer start (wall clock), only while the rig is hot or cooling. */
    val chronometerSinceWallMillis: Long?,
    val haul: String,
    val streak: String,
    /** One sentence for TalkBack (the chronometer itself is read by the system). */
    val contentDescription: String,
)

/**
 * Copy rules, shared with the notification and tile:
 * - counts and ORE amounts only; no prices, tickers or countdowns on a home screen;
 * - never "earn", "yield", "stake" or "passive income": a rig digs, a night hauls;
 * - a rig that cannot dig says so.
 */
object RigWidgetCopy {

    fun render(state: RigWidgetState): RigWidgetText {
        val rig = state.rig
        val noDigs = rig.focusOnly || !rig.canDig
        val qualifier = when {
            rig.focusOnly -> " · focus only"
            !rig.canDig -> " · no digs"
            else -> ""
        }
        val (headline, line) = when (rig.heat) {
            RigHeat.COLD -> "Rig cold" to (state.lastShiftRounds?.let { "Last shift: ${rounds(it)} dark" } ?: "Clock in, then lay it face-down")
            RigHeat.ARMED -> "Armed" to "Lay it face-down$qualifier"
            RigHeat.HOT -> "Rig hot" to "Dark for$qualifier"
            RigHeat.COOLING -> "Cooling" to "Put it back face-down"
            RigHeat.FROZEN -> "Frozen" to "No digs until you unfreeze it"
        }
        val chrono = rig.darkSinceWallMillis?.takeIf { rig.heat == RigHeat.HOT || rig.heat == RigHeat.COOLING }
        val haul = state.haul?.let { "Last haul ${ore(it.oreAtoms)}" } ?: "No haul yet"
        val streak = state.streakNights?.let { if (it == 1) "Streak 1 night" else "Streak $it nights" } ?: "Streak —"
        val description = buildString {
            append("Heads Down. ").append(headline).append(". ")
            if (rig.heat == RigHeat.HOT && noDigs) append(if (rig.focusOnly) "Focus only. " else "No digs. ")
            if (chrono == null) append(line).append(". ")
            append(haul).append(". ").append(streak).append('.')
        }
        return RigWidgetText(rig.heat, headline, line, chrono, haul, streak, description)
    }

    fun rounds(n: Int): String = if (n == 1) "1 round" else "$n rounds"

    /** ORE with 11 decimals, shown to 4 significant decimals ("0.0194 ORE"); zero is "0 ORE". */
    fun ore(atoms: Long): String {
        if (atoms <= 0) return "0 ORE"
        val value = BigDecimal.valueOf(atoms, ORE_DECIMALS)
        val scale = if (value >= BigDecimal.ONE) 2 else 4
        val shown = value.setScale(scale, RoundingMode.DOWN)
        return if (shown.signum() == 0) "<0.0001 ORE" else "${shown.toPlainString()} ORE"
    }

    private const val ORE_DECIMALS = 11
}
