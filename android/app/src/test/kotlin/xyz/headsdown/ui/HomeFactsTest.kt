package xyz.headsdown.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.feature.oemkeepalive.ExitCause
import xyz.headsdown.feature.oemkeepalive.ShiftHealth
import xyz.headsdown.feature.reveal.haul.HonestCopy
import xyz.headsdown.feature.shift.BreakReason
import xyz.headsdown.feature.shift.CoolReason
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSnapshot
import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.rig.ClockInPolicy
import xyz.headsdown.rig.ShiftWindow
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** The facts the home screen prints as numerals, as pure functions of what it is given. */
class HomeFactsTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val night = ClockInPolicy()
    private val day = ClockInPolicy(day = true, windowSeconds = 50 * 60L)

    /** 22:40 on a fixed evening, UTC. */
    private val now = ZonedDateTime.of(2026, 10, 11, 22, 40, 0, 0, utc).toInstant().toEpochMilli()
    private val alarm = ZonedDateTime.of(2026, 10, 12, 6, 30, 0, 0, utc).toInstant().toEpochMilli()

    private val spec = ShiftSpec(7, ShiftMode.NIGHT)
    private val everyState = listOf(
        ShiftState.Idle,
        ShiftState.Armed(spec, 0),
        ShiftState.Down(spec, 0, 0),
        ShiftState.Cooling(spec, 0, 10_000, CoolReason.LIFTED, 0),
        ShiftState.Broken(spec, 0, BreakReason.LIFTED),
        ShiftState.Frozen(7, 0),
    )

    // ------------------------------------------------------------------ "Runs until"

    @Test
    fun `the end time is read out of each of the three shift-window sentences`() {
        // With an alarm in the morning: two minutes before it.
        val beforeAlarm = ShiftWindow.describe(night, now, alarm, utc)
        assertTrue(beforeAlarm, "two minutes before your alarm" in beforeAlarm)
        assertEquals("06:28", runsUntil(ShiftState.Idle, beforeAlarm, utc))

        // A night shift with no usable alarm: the policy's eight hours.
        val noAlarm = ShiftWindow.describe(night, now, null, utc)
        assertTrue(noAlarm, "Set your alarm first" in noAlarm)
        assertEquals("06:40", runsUntil(ShiftState.Idle, noAlarm, utc))

        // A day shift: its fixed length, whatever the alarm.
        val desk = ShiftWindow.describe(day, now, alarm, utc)
        assertTrue(desk, desk.endsWith("ends the shift early."))
        assertEquals("23:30", runsUntil(ShiftState.Idle, desk, utc))
    }

    @Test
    fun `the numerals and the sentence under them cannot disagree`() {
        for (policy in listOf(night, day)) {
            for (nextAlarm in listOf(alarm, null)) {
                val sentence = ShiftWindow.describe(policy, now, nextAlarm, utc)
                val time = runsUntil(ShiftState.Idle, sentence, utc)
                assertNotNull(sentence, time)
                assertTrue("$time is the time the sentence names: $sentence", "runs until $time" in sentence)
            }
        }
    }

    @Test
    fun `a shift that broke is offered a new one, so it reads the sentence too`() {
        val sentence = ShiftWindow.describe(night, now, alarm, utc)
        // The broken shift's own window (ending 07:00) is over: the time shown is the next shift's.
        val broken = ShiftState.Broken(spec.copy(windowEndUnix = Instant.parse("2026-10-12T07:00:00Z").epochSecond), 0, BreakReason.UNPLUGGED)
        assertEquals("06:28", runsUntil(broken, sentence, utc))
    }

    @Test
    fun `once a shift is armed the time is the end of the plan window it was armed with`() {
        val armedWith = spec.copy(windowEndUnix = Instant.parse("2026-10-12T06:28:00Z").epochSecond)
        // The sentence is about a shift started now; an armed shift does not read it.
        val later = "A shift you start now runs until 09:15. Picking the phone up before then ends the shift early."
        assertEquals("06:28", runsUntil(ShiftState.Armed(armedWith, 0), later, utc))
        assertEquals("06:28", runsUntil(ShiftState.Down(armedWith, 0, 0), later, utc))
        assertEquals("06:28", runsUntil(ShiftState.Cooling(armedWith, 0, 1, CoolReason.SCREEN_ON, 0), null, utc))
        // In the phone's own zone, not UTC.
        assertEquals("08:28", runsUntil(ShiftState.Armed(armedWith, 0), null, ZoneId.of("Europe/Paris")))
    }

    @Test
    fun `no time is shown when none is known, and never for a frozen rig`() {
        assertNull(runsUntil(ShiftState.Idle, null, utc))
        assertNull(runsUntil(ShiftState.Idle, "The shift window could not be worked out.", utc))
        // A shift attached without its plan (the debug screen) carries no window end.
        assertNull(runsUntil(ShiftState.Armed(spec, 0), ShiftWindow.describe(night, now, alarm, utc), utc))
        assertNull(runsUntil(ShiftState.Frozen(7, 0), ShiftWindow.describe(night, now, alarm, utc), utc))
    }

    // ------------------------------------------------------------------ "Dark for"

    @Test
    fun `dark for counts only while the rig is hot or cooling`() {
        val since = 1_000_000L
        for (state in everyState) {
            val counted = darkSince(ShiftSnapshot(state, darkRounds = 9, darkSinceWallMillis = since))
            when (state) {
                is ShiftState.Down, is ShiftState.Cooling -> assertEquals("$state", since, counted)
                // Before the first round, after a break and when frozen there is no clock to run.
                else -> assertNull("$state", counted)
            }
        }
    }

    @Test
    fun `a shift that broke stops the count, whatever the snapshot still carries`() {
        // The service keeps the start of the dark time in the snapshot after a break; the home
        // screen used to go on counting from it.
        val broken = ShiftSnapshot(ShiftState.Broken(spec, 0, BreakReason.LIFTED), darkRounds = 212, darkSinceWallMillis = 5L)
        assertNull(darkSince(broken))
        // A rig that is hot but has not signed its first round has nothing to count from.
        assertNull(darkSince(ShiftSnapshot(ShiftState.Down(spec, 0, 0), darkSinceWallMillis = null)))
    }

    // ------------------------------------------------------------------ the words

    @Test
    fun `the line under the clock-in bar states the most a shift can place`() {
        assertEquals("Up to 0.02 SOL tonight", HomeCopy.clockInCap(night))
        assertEquals("Up to 0.005 SOL tonight", HomeCopy.clockInCap(night.copy(shiftBudgetLamports = 5_000_000uL)))
        assertEquals("Up to 0.02 SOL this shift", HomeCopy.clockInCap(day))
        // The same number the disclosure sentence opens with, and no second "Clock in" on the screen.
        assertTrue(night.disclosure().startsWith("This shift can place up to 0.02 SOL"))
        assertTrue("clock in" !in HomeCopy.clockInCap(night).lowercase())
    }

    @Test
    fun `the rig's word, heat and slab follow the shift state`() {
        val looks = everyState.map(::lookOf)
        assertEquals(listOf("Rig cold", "Armed", "Rig hot", "Cooling", "Rig cold", "Frozen"), looks.map { it.word })
        assertEquals(listOf(0, 1, 5, 3, 0, 5), looks.map { it.pixels })
        assertEquals(
            listOf(RigTone.Muted, RigTone.Lit, RigTone.Lit, RigTone.Ember, RigTone.Muted, RigTone.Muted),
            looks.map { it.tone },
        )
        // Only a break is a problem, and it says why in words: ember never speaks alone.
        assertEquals(listOf(false, false, false, false, true, false), looks.map { it.problem })
        for (reason in BreakReason.entries) {
            assertTrue(lookOf(ShiftState.Broken(spec, 0, reason)).line.startsWith("Shift ended: "))
        }
    }

    @Test
    fun `the banner speaks only for a shift that did not end by itself`() {
        assertNull(healthLine(ShiftHealth.NoRecentShift))
        assertNull(healthLine(ShiftHealth.StillRunning))
        assertNull(healthLine(ShiftHealth.EndedNormally("lifted")))
        val killed = healthLine(ShiftHealth.KilledByOs(0, ExitCause.KILLED_BY_SYSTEM_OR_OEM, 41))
        assertTrue(killed!!, killed.startsWith("Last night's shift ended early: Android stopped Heads Down at ") && killed.endsWith(" after 41 rounds. Nothing was spent."))
        assertTrue("Heads Down crashed" in healthLine(ShiftHealth.KilledByOs(0, ExitCause.ANR, 3))!!)
        assertTrue("Heads Down was closed" in healthLine(ShiftHealth.KilledByOs(0, ExitCause.USER_STOPPED, 3))!!)
        assertEquals(
            "Last night's shift stopped without a goodbye (reboot or battery?) after 7 rounds.",
            healthLine(ShiftHealth.DiedWithoutRecord(0, 7)),
        )
    }

    @Test
    fun `every new word on the home screen is honest, and the hero has one sentence per state`() {
        val heroes = everyState.map(HomeCopy::hero)
        assertEquals("six different sentences", 6, heroes.toSet().size)
        for (text in HomeCopy.all + heroes + HomeCopy.clockInCap(night) + HomeCopy.clockInCap(day)) {
            assertTrue("banned words in: $text", HonestCopy.violations(text).isEmpty())
        }
        for (hero in heroes) {
            assertTrue(hero, hero.startsWith("A dark slab lying face-down, ") && hero.endsWith("."))
            assertEquals("one sentence: $hero", 1, hero.count { it == '.' })
        }
    }
}
