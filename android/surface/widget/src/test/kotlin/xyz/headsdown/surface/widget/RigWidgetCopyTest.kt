package xyz.headsdown.surface.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RigWidgetCopyTest {

    @Test
    fun `headline per heat`() {
        val headlines = RigHeat.entries.associateWith { RigWidgetCopy.render(RigWidgetState(rig = WidgetRig(it))).headline }
        assertEquals(
            mapOf(
                RigHeat.COLD to "Rig cold",
                RigHeat.ARMED to "Armed",
                RigHeat.HOT to "Rig hot",
                RigHeat.COOLING to "Cooling",
                RigHeat.FROZEN to "Frozen",
            ),
            headlines,
        )
    }

    @Test
    fun `hot rig runs the chronometer and cold rig does not`() {
        val hot = RigWidgetCopy.render(RigWidgetState(rig = WidgetRig(RigHeat.HOT, darkSinceWallMillis = 5L)))
        assertEquals(5L, hot.chronometerSinceWallMillis)
        assertEquals("Dark for", hot.line)
        val cold = RigWidgetCopy.render(RigWidgetState(rig = WidgetRig(RigHeat.COLD, darkSinceWallMillis = 5L)))
        assertNull(cold.chronometerSinceWallMillis)
    }

    @Test
    fun `a rig that cannot dig says so`() {
        val local = RigWidgetCopy.render(RigWidgetState(rig = WidgetRig(RigHeat.HOT, darkSinceWallMillis = 1, canDig = false)))
        assertEquals("Dark for · no digs", local.line)
        assertTrue(local.contentDescription.contains("No digs"))
        val focus = RigWidgetCopy.render(RigWidgetState(rig = WidgetRig(RigHeat.ARMED, focusOnly = true)))
        assertEquals("Lay it face-down · focus only", focus.line)
    }

    @Test
    fun `cold rig reports the last shift`() {
        assertEquals(
            "Last shift: 312 rounds dark",
            RigWidgetCopy.render(RigWidgetState(lastShiftRounds = 312)).line,
        )
        assertEquals("Last shift: 1 round dark", RigWidgetCopy.render(RigWidgetState(lastShiftRounds = 1)).line)
        assertEquals("Clock in, then lay it face-down", RigWidgetCopy.render(RigWidgetState()).line)
    }

    @Test
    fun `haul and streak lines`() {
        val t = RigWidgetCopy.render(RigWidgetState(haul = WidgetHaul(1_940_000_000L, 0), streakNights = 23))
        assertEquals("Last haul 0.0194 ORE", t.haul)
        assertEquals("Streak 23 nights", t.streak)
        val empty = RigWidgetCopy.render(RigWidgetState())
        assertEquals("No haul yet", empty.haul)
        assertEquals("Streak —", empty.streak)
        assertEquals("Streak 1 night", RigWidgetCopy.render(RigWidgetState(streakNights = 1)).streak)
    }

    @Test
    fun `ore formatting uses 11 decimals`() {
        assertEquals("0 ORE", RigWidgetCopy.ore(0))
        assertEquals("<0.0001 ORE", RigWidgetCopy.ore(5_000_000L)) // 0.00005 ORE
        assertEquals("0.0194 ORE", RigWidgetCopy.ore(1_949_999_999L))
        assertEquals("1.00 ORE", RigWidgetCopy.ore(100_000_000_000L))
        assertEquals("12.34 ORE", RigWidgetCopy.ore(1_234_567_000_000L))
    }

    @Test
    fun `no banned words and no prices anywhere in the widget copy`() {
        val states = buildList {
            RigHeat.entries.forEach { heat ->
                listOf(true, false).forEach { canDig ->
                    listOf(true, false).forEach { focus ->
                        add(
                            RigWidgetState(
                                rig = WidgetRig(heat, darkSinceWallMillis = 1, darkRounds = 3, focusOnly = focus, canDig = canDig),
                                lastShiftRounds = 9,
                                haul = WidgetHaul(123_456_789L, 0),
                                streakNights = 4,
                            ),
                        )
                    }
                }
            }
            add(RigWidgetState())
        }
        states.map(RigWidgetCopy::render).forEach { t ->
            val all = listOf(t.headline, t.line, t.haul, t.streak, t.contentDescription).joinToString(" | ")
            BANNED.forEach { word -> assertTrue("'$word' in: $all", !all.lowercase().contains(word)) }
            assertTrue("no currency in: $all", !all.contains('$') && !all.contains("SOL"))
        }
        listOf(CrewPlaceholderCopy.TITLE, CrewPlaceholderCopy.TAG, CrewPlaceholderCopy.LINE, CrewPlaceholderCopy.DESCRIPTION)
            .forEach { s -> BANNED.forEach { word -> assertTrue(s, !s.lowercase().contains(word)) } }
    }

    private companion object {
        val BANNED = listOf("earn", "yield", "stake", "staking", "passive income", "proof of focus", "profit", "apy", "guaranteed")
    }
}
