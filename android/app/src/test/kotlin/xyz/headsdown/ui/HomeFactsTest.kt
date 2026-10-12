package xyz.headsdown.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.math.BigInteger
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
    fun `the gate line names ORE's figure, the ceiling fixed in the build, and what the rig would do at it`() {
        fun gate(lamportsPerOre: Long, policy: ClockInPolicy = night) = HomeCopy.gate(BigInteger.valueOf(lamportsPerOre), policy)
        // The program's own live vector, 0.629818854 SOL per ORE: over a Steady plan, under a Hunter one.
        assertEquals(
            "ORE's cost figure is 0.62 SOL per ORE. The ceiling fixed in this build is 0.53. At that figure the rig would wait.",
            gate(629_818_854),
        )
        assertEquals(
            "ORE's cost figure is 0.62 SOL per ORE. The ceiling fixed in this build is 0.67. At that figure the rig would dig.",
            gate(629_818_854, night.copy(planMaxEvCostPerOre = 670_000_000uL)),
        )
        // The figure is rounded down, never up; at the ceiling itself the gate is open, as on-chain.
        assertEquals("ORE's cost figure is 0.52 SOL per ORE. The ceiling fixed in this build is 0.53. At that figure the rig would dig.", gate(529_999_999))
        assertEquals("ORE's cost figure is 0.53 SOL per ORE. The ceiling fixed in this build is 0.53. At that figure the rig would dig.", gate(530_000_000))
        // A hair over it the rig waits, and the figure is printed far enough to show it is over.
        assertEquals("ORE's cost figure is 0.534 SOL per ORE. The ceiling fixed in this build is 0.53. At that figure the rig would wait.", gate(534_900_000))
        assertEquals("ORE's cost figure is 0.530000001 SOL per ORE. The ceiling fixed in this build is 0.53. At that figure the rig would wait.", gate(530_000_001))
        // The ceiling is printed exactly: two decimals at least, and every one it has.
        assertTrue(gate(1, night.copy(planMaxEvCostPerOre = 1_000_000_000uL, capMaxCostPerOre = 1_000_000_000uL)).contains("in this build is 1.00. "))
        assertTrue(gate(1, night.copy(planMaxEvCostPerOre = 535_000_000uL)).contains("in this build is 0.535. "))
        // It is the tighter of the plan's and the wallet's, which is what the program compares with.
        assertTrue(gate(1, night.copy(planMaxEvCostPerOre = 700_000_000uL)).contains("in this build is 0.67. "))
        // A figure above a u64 (ORE's EMA at its largest, pot empty) is still a figure, and the rig waits.
        val huge = HomeCopy.gate(BigInteger("22136092888451461938"), night)
        assertTrue(huge, huge.startsWith("ORE's cost figure is 22136092888.45 SOL per ORE. ") && huge.endsWith("the rig would wait."))

        // ECONOMICS §4: ORE's figure, never the reader's cost, a price or a saving, and no market price.
        for (line in listOf(gate(629_818_854), gate(1), huge)) {
            assertTrue(line, line.startsWith("ORE's cost figure is "))
            assertTrue("banned words in: $line", HonestCopy.violations(line).isEmpty())
            for (word in listOf("price", "saving", "save", "cheaper", "your cost", "market", "worth", "$")) {
                assertFalse("'$word' in: $line", word in line.lowercase())
            }
        }
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

    // ------------------------------------------------------------------ a shift that ran its window

    @Test
    fun `a rig that stops after its window ran a complete shift - one that stops inside it did not`() {
        val end = Instant.parse("2026-10-12T06:28:00Z").epochSecond
        val windowed = spec.copy(windowEndUnix = end)
        fun at(unix: Long) = unix * 1000
        // A break is stamped on the elapsed clock: this one 40 s after the phone started counting.
        val brokeAt = 40_000L
        fun broken(reason: BreakReason = BreakReason.SCREEN_ON) = ShiftSnapshot(ShiftState.Broken(windowed, brokeAt, reason), darkRounds = 212)

        // The alarm lights the screen two minutes after the window: read at once, and an hour later.
        assertTrue(ranItsWindow(broken(), at(end + 130), brokeAt))
        assertTrue(ranItsWindow(broken(), at(end + 130 + 3_600), brokeAt + 3_600_000))
        // The window's last second still sends a BREAK (ShiftSpec.breakStillUseful); the next one does not.
        assertFalse(ranItsWindow(broken(), at(end), brokeAt))
        assertFalse(ranItsWindow(broken(), at(end) + 999, brokeAt))
        assertTrue(ranItsWindow(broken(), at(end + 1), brokeAt))
        // A pickup at 03:00 is a pickup, however long after the window the screen is read.
        val threeAm = Instant.parse("2026-10-12T03:00:00Z").epochSecond
        for (later in listOf(0L, 3_600L, 5 * 3_600L, 24 * 3_600L)) {
            assertFalse("read $later s on", ranItsWindow(broken(BreakReason.LIFTED), at(threeAm + later), brokeAt + later * 1000))
        }
        // A cooling rig is judged now: its grace can only end later.
        val cooling = ShiftSnapshot(ShiftState.Cooling(windowed, 0, 10_000, CoolReason.SCREEN_ON, 0), darkRounds = 212)
        assertFalse(ranItsWindow(cooling, at(end), 5_000))
        assertTrue(ranItsWindow(cooling, at(end + 1), 5_000))
        // No round signed dark, or no window known: never complete.
        assertFalse(ranItsWindow(broken().copy(darkRounds = 0), at(end + 130), brokeAt))
        assertFalse(ranItsWindow(ShiftSnapshot(ShiftState.Broken(spec, brokeAt, BreakReason.SCREEN_ON), darkRounds = 212), at(end + 130), brokeAt))
        // And no other state is.
        for (state in listOf(ShiftState.Idle, ShiftState.Armed(windowed, 0), ShiftState.Down(windowed, 0, 0), ShiftState.Frozen(7, 0))) {
            assertFalse("$state", ranItsWindow(ShiftSnapshot(state, darkRounds = 212), at(end + 130), 0))
        }
    }

    @Test
    fun `a complete shift is said calmly - no ember, no reason, and the one thing left to do`() {
        assertEquals("Rig cold", CompleteLook.word)
        assertEquals(RigTone.Muted, CompleteLook.tone)
        assertEquals(0, CompleteLook.pixels)
        assertFalse(CompleteLook.problem)
        assertTrue(CompleteLook.complete)
        assertEquals("Shift complete: clock out to seal it and see the haul.", CompleteLook.line)
        // A break's look is never the complete one, and says so in its own words.
        for (reason in BreakReason.entries) assertFalse(lookOf(ShiftState.Broken(spec, 0, reason)).complete)
        // The hero's sentence has the shape of the six others, and is none of them.
        val hero = HomeCopy.HERO_COMPLETE
        assertTrue(hero, hero.startsWith("A dark slab lying face-down, ") && hero.endsWith(".") && hero.count { it == '.' } == 1)
        assertFalse(hero in everyState.map(HomeCopy::hero))
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
