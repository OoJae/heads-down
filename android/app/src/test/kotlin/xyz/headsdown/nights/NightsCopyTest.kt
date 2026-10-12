package xyz.headsdown.nights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.indexer.HaulMode
import xyz.headsdown.core.chain.indexer.IndexerShift
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.feature.reveal.haul.HonestCopy
import xyz.headsdown.feature.shift.BreakReason
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftSpec
import xyz.headsdown.feature.shift.ShiftState
import xyz.headsdown.ui.lookOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The night log's words and its strip of days: what a shift's reason, its counts and its day become. */
class NightsCopyTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val lagos = ZoneId.of("Africa/Lagos")
    private val utc = ZoneId.of("UTC")

    private fun unix(iso: String) = Instant.parse(iso).epochSecond

    private fun shift(id: Int, sealedAt: String, reason: Int = 0, dark: Int = 5, dug: Int = 2) =
        IndexerShift(id.toULong(), unix(sealedAt), dark.toULong(), dug.toULong(), reason, HaulMode.NIGHT, signature = null)

    @Test
    fun `how a shift ended is said in words, for every reason the program writes`() {
        assertEquals(
            listOf(
                "Completed",
                "Ended early: the phone was picked up",
                "Ended early: the screen stayed on",
                "Ended early: the rig was frozen",
                "Ended early: no heartbeat was recorded on-chain",
                "Ended early: the shift's budget was used up",
                "Ended early: ended by hand inside its window",
                "Ended early: the charger was unplugged",
                "Ended early: the phone was unlocked",
            ),
            (0..8).map(NightsCopy::howItEnded),
        )
        assertEquals("every reason has its own words", ShiftEndReason.entries.size, ShiftEndReason.entries.map { NightsCopy.howItEnded(it.wire) }.toSet().size)
        // A code a later program could write: it did not complete, and nothing is made up about it.
        assertEquals("Ended early", NightsCopy.howItEnded(9))
        assertEquals("Ended early", NightsCopy.howItEnded(255))
    }

    @Test
    fun `the four reasons the home screen names are in the home screen's words`() {
        val spec = ShiftSpec(1, ShiftMode.NIGHT)
        val pairs = listOf(
            BreakReason.LIFTED to ShiftEndReason.PICKUP,
            BreakReason.SCREEN_ON to ShiftEndReason.SCREEN_ON,
            BreakReason.UNPLUGGED to ShiftEndReason.UNPLUGGED,
            BreakReason.UNLOCKED to ShiftEndReason.UNLOCKED,
        )
        for ((home, chain) in pairs) {
            val homeLine = lookOf(ShiftState.Broken(spec, 0, home)).line
            assertEquals(homeLine, NightsCopy.howItEnded(chain.wire).replace("Ended early: ", "Shift ended: ") + ".")
        }
    }

    @Test
    fun `the counts are two whole numbers, and one of a thing is singular`() {
        assertEquals("5 rounds dark · 5 digs", NightsCopy.counts(5uL, 5uL))
        assertEquals("412 rounds dark · 0 digs", NightsCopy.counts(412uL, 0uL))
        assertEquals("1 round dark · 1 dig", NightsCopy.counts(1uL, 1uL))
        assertEquals("0 rounds dark · 0 digs", NightsCopy.counts(0uL, 0uL))
        assertEquals("18446744073709551615 rounds dark · 2 digs", NightsCopy.counts(ULong.MAX_VALUE, 2uL))
    }

    @Test
    fun `a shift's time is the local date and time it was sealed`() {
        val sealedAt = unix("2026-09-30T22:30:00Z")
        // East of UTC the same instant is already the next day, and the next month.
        assertEquals("Sealed Thu 1 Oct 2026, 07:30", NightsCopy.sealed(sealedAt, tokyo))
        assertEquals("Sealed Wed 30 Sep 2026, 23:30", NightsCopy.sealed(sealedAt, lagos))
        assertEquals("Sealed Wed 30 Sep 2026, 22:30", NightsCopy.sealed(sealedAt, utc))
    }

    @Test
    fun `the streak is in nights, with the nights off the next sealed shift would find`() {
        assertEquals("0 nights", NightsCopy.streak(0))
        assertEquals("1 night", NightsCopy.streak(1))
        assertEquals("23 nights", NightsCopy.streak(23))
        assertEquals("Nights off left: 2", NightsCopy.nightsOff(2))
        assertEquals("Nights off left: 0", NightsCopy.nightsOff(0))

        // Day 20737 (11 October 2026) is in 30-day period 691, which runs from day 20730 to 20759.
        val rig = RigNights(streak = 4, freezesLeft = 1, lastShiftDay = 20_737, lifetimeDarkRounds = 0uL, lifetimeRoundsDug = 0uL)
        assertEquals(1, rig.nightsOffLeft(todayUtcDay = 20_737))
        assertEquals(1, rig.nightsOffLeft(todayUtcDay = 20_759))
        // The period changed: the program refills them at the next sealed shift, whatever the account still holds.
        assertEquals(2, rig.nightsOffLeft(todayUtcDay = 20_760))
        assertEquals(2, rig.copy(freezesLeft = 0).nightsOffLeft(todayUtcDay = 20_790))
        assertEquals(0, rig.copy(freezesLeft = 0).nightsOffLeft(todayUtcDay = 20_738))
        // No shift has counted yet: a new rig has both.
        assertEquals(2, rig.copy(streak = 0, freezesLeft = 0, lastShiftDay = 0).nightsOffLeft(todayUtcDay = 20_737))
    }

    @Test
    fun `the strip is 28 local days, across a month boundary, in a zone east of UTC`() {
        val today = LocalDate.of(2026, 10, 5)
        val shifts = listOf(
            // Tokyo is nine hours ahead: 15:30 UTC on the 4th is already today there.
            shift(9, "2026-10-04T15:30:00Z", reason = 0),
            // Two shifts sealed on one local day (3 October): the completed one marks the day.
            shift(8, "2026-10-03T01:00:00Z", reason = 8),
            shift(7, "2026-10-02T21:00:00Z", reason = 0),
            // 22:30 UTC on 30 September is 1 October in Tokyo.
            shift(6, "2026-09-30T22:30:00Z", reason = 0),
            shift(5, "2026-09-29T20:00:00Z", reason = 1),
            // The oldest cell is 8 September; an hour before its local midnight is outside the strip.
            shift(4, "2026-09-07T16:00:00Z", reason = 2),
            shift(3, "2026-09-07T14:00:00Z", reason = 0),
        )
        val days = NightsDays.strip(shifts, today, tokyo)
        assertEquals(28, days.size)
        val marked = days.withIndex().filter { it.value != DayMark.NO_SHIFT }.associate { today.minusDays(27L - it.index) to it.value }
        assertEquals(
            mapOf(
                LocalDate.of(2026, 9, 8) to DayMark.ENDED_EARLY,
                LocalDate.of(2026, 9, 30) to DayMark.ENDED_EARLY,
                LocalDate.of(2026, 10, 1) to DayMark.COMPLETED,
                LocalDate.of(2026, 10, 3) to DayMark.COMPLETED,
                LocalDate.of(2026, 10, 5) to DayMark.COMPLETED,
            ),
            marked,
        )
        assertEquals(DayMark.ENDED_EARLY, days.first())
        assertEquals(DayMark.COMPLETED, days.last())
        assertEquals("Last 28 days: 3 completed, 2 ended early, 23 with no shift.", NightsCopy.strip(days))

        // The same shifts read in UTC fall on other days: the strip is the phone's calendar, not the chain's.
        val inUtc = NightsDays.strip(shifts, today, utc)
        assertEquals(DayMark.COMPLETED, inUtc[27 - 5]) // 30 September
        assertEquals(DayMark.NO_SHIFT, inUtc[27 - 4]) // 1 October
        assertEquals(DayMark.NO_SHIFT, inUtc.last()) // nothing sealed on 5 October UTC
        // A shift sealed after today is on no cell.
        assertTrue(NightsDays.strip(listOf(shift(1, "2026-10-06T03:00:00Z")), today, tokyo).all { it == DayMark.NO_SHIFT })
        assertEquals("Last 28 days: 0 completed, 0 ended early, 28 with no shift.", NightsCopy.strip(NightsDays.strip(emptyList(), today, tokyo)))
    }

    @Test
    fun `the page lists the newest ninety and says how many older ones it leaves out`() {
        val day = 86_400L
        val many = (0 until 95).map { IndexerShift((200 - it).toULong(), 1_791_000_000L - it * day, 1uL, 0uL, 0, HaulMode.NIGHT, null) }
        val facts = NightsFacts(rig = null, shifts = many, today = LocalDate.of(2026, 10, 5), zone = utc, todayUtcDay = 20_731)
        assertEquals(90, facts.listed.size)
        assertEquals(200uL, facts.listed.first().shiftId)
        assertEquals(5, facts.older)
        assertEquals("5 older shifts are not listed here.", NightsCopy.older(5))
        assertEquals("1 older shift is not listed here.", NightsCopy.older(1))
        assertEquals(0, facts.copy(shifts = many.take(3)).older)
        // Without the list there is no strip, and nothing to list.
        assertNull(facts.copy(shifts = null).days)
        assertTrue(facts.copy(shifts = null).listed.isEmpty())
    }

    @Test
    fun `no word of the night log is banned, and none names SOL or ORE`() {
        val generated = (0..9).map(NightsCopy::howItEnded) +
            listOf(
                NightsCopy.streak(3), NightsCopy.nightsOff(2), NightsCopy.counts(5uL, 5uL), NightsCopy.older(4),
                NightsCopy.sealed(1_791_750_944L, lagos), NightsCopy.strip(List(28) { DayMark.NO_SHIFT }),
            )
        for (text in NightsCopy.all + generated) {
            assertTrue("banned words in: $text", HonestCopy.violations(text).isEmpty())
            assertFalse("an amount's unit in: $text", Regex("\\b(SOL|ORE|SKR|lamports?)\\b").containsMatchIn(text))
            assertFalse("an exclamation mark in: $text", "!" in text)
        }
        assertEquals("All nights", NightsCopy.LINK)
        assertEquals("No finished shift yet.", NightsCopy.EMPTY)
        // The rule is INTERFACE §6.8's: two nights off, a 30-day period, and a shift that did not complete changes nothing.
        assertTrue("neither adds to the streak nor ends it" in NightsCopy.STREAK_RULE)
        assertTrue("Two nights off" in NightsCopy.STREAK_RULE && "30-day period" in NightsCopy.STREAK_RULE)
    }
}
