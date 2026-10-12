package xyz.headsdown.nights

import xyz.headsdown.clockout.ClockOutCopy
import xyz.headsdown.core.chain.accounts.RigAccount
import xyz.headsdown.core.chain.indexer.IndexerShift
import xyz.headsdown.core.keys.ShiftEndReason
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the Rig account holds that this screen shows (INTERFACE §3.2). The account also holds
 * `lifetime_lamports_deployed`: an amount of SOL, so it is not here.
 */
data class RigNights(
    /** `Rig.streak`, as the last sealed shift left it. */
    val streak: Long,
    /** `Rig.freezes_left`, as the last sealed shift left it. */
    val freezesLeft: Int,
    /** `Rig.last_shift_day`: the UTC unix day of the last shift that counted, 0 when none has. */
    val lastShiftDay: Long,
    val lifetimeDarkRounds: ULong,
    val lifetimeRoundsDug: ULong,
) {
    /**
     * The nights off the next sealed shift would find. The program refills them to two when that
     * shift's UTC day is in another 30-day period than [lastShiftDay] (INTERFACE §6.8), and it
     * writes that only then: read in a new period, the account can still hold last period's count.
     */
    fun nightsOffLeft(todayUtcDay: Long): Int {
        val refilled = lastShiftDay == 0L || Math.floorDiv(todayUtcDay, PERIOD_DAYS) != Math.floorDiv(lastShiftDay, PERIOD_DAYS)
        return if (refilled) NIGHTS_OFF_PER_PERIOD else freezesLeft
    }

    companion object {
        const val PERIOD_DAYS = 30L
        const val NIGHTS_OFF_PER_PERIOD = 2

        fun from(rig: RigAccount) = RigNights(
            streak = rig.streak,
            freezesLeft = rig.freezesLeft,
            lastShiftDay = rig.lastShiftDay,
            lifetimeDarkRounds = rig.lifetimeDarkRounds,
            lifetimeRoundsDug = rig.lifetimeRoundsDug,
        )
    }
}

/** One local day of the strip. */
enum class DayMark { COMPLETED, ENDED_EARLY, NO_SHIFT }

/**
 * What the two reads gave, reduced to what the screen says. Built by [NightsModel], or directly
 * in tests. Either half can be missing by itself: the streak is the chain's, the list the indexer's.
 */
data class NightsFacts(
    /** Null: the Rig account could not be read, or there is no rig at the address now ([rigProblem] says which). */
    val rig: RigNights?,
    /** Finished shifts, newest first. Null: the list could not be read, or this build has no indexer ([shiftsProblem]). */
    val shifts: List<IndexerShift>?,
    /** The phone's local day when this was read: the strip's last cell. */
    val today: LocalDate,
    val zone: ZoneId,
    /** The UTC unix day when this was read: the day the program would count a shift sealed now on. */
    val todayUtcDay: Long,
    val rigProblem: String? = null,
    val shiftsProblem: String? = null,
    /** A read failed and can be tried again (not: there is no rig, or no indexer). */
    val incomplete: Boolean = false,
) {
    /** The last [NightsDays.DAYS] local days, the oldest first; null without the list. */
    val days: List<DayMark>? get() = shifts?.let { NightsDays.strip(it, today, zone) }

    /** The shifts the screen lists, and how many older ones it leaves out. */
    val listed: List<IndexerShift> get() = shifts.orEmpty().take(MAX_LISTED)
    val older: Int get() = (shifts.orEmpty().size - MAX_LISTED).coerceAtLeast(0)

    companion object {
        /** A page that scrolls, not a database: about three months of nights. */
        const val MAX_LISTED = 90
    }
}

object NightsDays {
    const val DAYS = 28

    /**
     * One mark per local day in [zone], the oldest first and [today] last. A shift belongs to the
     * day it was sealed on (`ShiftLog.end_ts`). A day with a completed shift is completed,
     * whatever else was sealed on it.
     */
    fun strip(shifts: List<IndexerShift>, today: LocalDate, zone: ZoneId): List<DayMark> {
        val sealedOn = shifts.groupBy { Instant.ofEpochSecond(it.endedAt).atZone(zone).toLocalDate() }
        return (DAYS - 1 downTo 0).map { daysBack ->
            val sealed = sealedOn[today.minusDays(daysBack.toLong())]
            when {
                sealed == null -> DayMark.NO_SHIFT
                sealed.any { it.reason == ShiftEndReason.COMPLETED.wire } -> DayMark.COMPLETED
                else -> DayMark.ENDED_EARLY
            }
        }
    }
}

/** The words of the night log. It is read-only, and it names no amount of SOL or ORE. */
object NightsCopy {
    /** The link on the home screen, under the last haul. */
    const val LINK = "All nights"

    const val EYEBROW = "Your rig"
    const val TITLE = "Nights"
    const val LOADING = "Reading your rig and its finished shifts…"
    const val EMPTY = "No finished shift yet."
    const val NO_RIG = "This phone has no rig bound to it yet: there is no night to list."
    const val UNREADABLE = "Could not read the chain or the indexer. Check the connection and try again."
    const val RIG_UNREADABLE = "Could not read the rig's account on-chain just now: the streak is not shown."
    const val RIG_GONE = "No rig is registered at this wallet's address now: there is no streak to show."
    const val LIST_UNREADABLE = "Could not read the list of finished shifts just now."
    const val NO_INDEXER = "This build has no indexer: the list of finished shifts cannot be read."

    const val STREAK = "Streak"
    const val STREAK_RULE =
        "A night counts when its shift completes with at least one round dark. A shift that ended early " +
            "neither adds to the streak nor ends it. Two nights off cover missed days: they are refilled " +
            "when the 30-day period changes. The chain counts the UTC day a shift is sealed on."
    const val LIFETIME = "All shifts"
    const val ROUNDS_DARK = "Rounds dark"
    const val DIGS = "Digs"

    /** Where each half comes from: the phone reads the account itself, and takes the list from the indexer. */
    const val RIG_SOURCE = "The streak and these counts are read from your rig's account on-chain."
    const val LIST_SOURCE = "The days and the list come from the Heads Down indexer, which reads the sealed shifts."
    const val LAST_DAYS = "Last 28 days"
    const val LEGEND = "One cell a day, today last, by the day a shift was sealed. Filled: completed. Outlined: ended early. A line: no shift."
    const val COMPLETED = "Completed"
    const val ENDED_EARLY = "Ended early"

    fun streak(nights: Long): String = if (nights == 1L) "1 night" else "$nights nights"

    fun nightsOff(left: Int): String = "Nights off left: $left"

    /**
     * How a shift ended, from `ShiftLog.break_reason`. The reasons are in the words the home
     * screen and the clock-out use ("the phone was picked up"). A code this build does not know
     * did not complete, and no more is said of it.
     */
    fun howItEnded(reason: Int): String = when (val known = ShiftEndReason.entries.firstOrNull { it.wire == reason }) {
        ShiftEndReason.COMPLETED -> COMPLETED
        null -> ENDED_EARLY
        else -> "$ENDED_EARLY: ${ClockOutCopy.reason(known)}"
    }

    fun counts(darkRounds: ULong, roundsDug: ULong): String =
        "$darkRounds ${if (darkRounds == 1uL) "round" else "rounds"} dark · $roundsDug ${if (roundsDug == 1uL) "dig" else "digs"}"

    /** When the shift was sealed (`ShiftLog.end_ts` is the time of `end_shift`), in the phone's zone. */
    fun sealed(endedAtUnix: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "Sealed ${WHEN.format(Instant.ofEpochSecond(endedAtUnix).atZone(zone))}"

    /** The strip in words, for a screen reader: the cells themselves say nothing. */
    fun strip(days: List<DayMark>): String {
        val completed = days.count { it == DayMark.COMPLETED }
        val early = days.count { it == DayMark.ENDED_EARLY }
        val none = days.count { it == DayMark.NO_SHIFT }
        return "Last ${days.size} days: $completed completed, $early ended early, $none with no shift."
    }

    fun older(count: Int): String = if (count == 1) "1 older shift is not listed here." else "$count older shifts are not listed here."

    private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH)

    /** Every fixed string, so the honesty test reads each one. */
    val all: List<String> = listOf(
        LINK, EYEBROW, TITLE, LOADING, EMPTY, NO_RIG, UNREADABLE, RIG_UNREADABLE, RIG_GONE, LIST_UNREADABLE, NO_INDEXER,
        STREAK, STREAK_RULE, LIFETIME, ROUNDS_DARK, DIGS, RIG_SOURCE, LIST_SOURCE, LAST_DAYS, LEGEND, COMPLETED, ENDED_EARLY,
    )
}
