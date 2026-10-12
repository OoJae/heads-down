package xyz.headsdown.nights

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.accounts.AccountLayoutException
import xyz.headsdown.core.chain.indexer.HaulMode
import xyz.headsdown.core.chain.indexer.IndexerHttpException
import xyz.headsdown.core.chain.indexer.IndexerShift
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The night log's logic: two reads, each of which may fail by itself, and nothing ever sent. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NightsModelTest {

    private val authority = Pubkey(ByteArray(32) { 7 })
    private val tokyo = ZoneId.of("Asia/Tokyo")

    /** 15:30 UTC on 4 October 2026: already the 5th in Tokyo, and UTC unix day 20730. */
    private val now = Instant.parse("2026-10-04T15:30:00Z").toEpochMilli()

    private val rig = RigNights(streak = 3, freezesLeft = 1, lastShiftDay = 20_730, lifetimeDarkRounds = 1_240uL, lifetimeRoundsDug = 318uL)

    private fun shift(id: Int, endedAt: Long, reason: Int = 0) =
        IndexerShift(id.toULong(), endedAt, darkRounds = 5uL, roundsDug = 5uL, reason = reason, mode = HaulMode.NIGHT, signature = null)

    private val newest = shift(7, 1_791_127_000, reason = 1)
    private val middle = shift(6, 1_791_040_000)
    private val oldest = shift(5, 1_790_950_000, reason = 8)

    private class FakeChain(var rig: RigNights?, var shifts: List<IndexerShift>?) : NightsChain {
        val asked = mutableListOf<Pubkey>()
        var rigFails: Exception? = null
        var shiftsFail: Exception? = null

        override suspend fun rig(authority: Pubkey): RigNights? {
            asked += authority
            rigFails?.let { throw it }
            return rig
        }

        override suspend fun shifts(authority: Pubkey): List<IndexerShift>? {
            asked += authority
            shiftsFail?.let { throw it }
            return shifts
        }
    }

    private fun kotlinx.coroutines.test.TestScope.model(chain: NightsChain, bound: Pubkey? = authority) =
        NightsModel(chain, { bound }, this, nowMillis = { now }, zone = { tokyo })

    @Test
    fun `a phone with no bound rig reads nothing`() = runTest {
        val chain = FakeChain(rig, listOf(newest))
        val model = model(chain, bound = null)
        model.load()
        advanceUntilIdle()
        assertEquals(NightsState.NoRig, model.state.value)
        assertTrue(chain.asked.isEmpty())
    }

    @Test
    fun `both reads are for the bound wallet, and the shifts are put newest first`() = runTest {
        // Not in order, and two sealed in the same second: the later shift id is the newer.
        val twin = shift(8, newest.endedAt)
        val chain = FakeChain(rig, listOf(oldest, newest, twin, middle))
        val model = model(chain)
        assertEquals(NightsState.Loading, model.state.value)
        model.load()
        advanceUntilIdle()
        val facts = (model.state.value as NightsState.Ready).facts
        assertEquals(listOf(authority, authority), chain.asked)
        assertEquals(rig, facts.rig)
        assertEquals(listOf(twin, newest, middle, oldest), facts.shifts)
        assertNull(facts.rigProblem)
        assertNull(facts.shiftsProblem)
        assertFalse(facts.incomplete)
        // The strip ends on the phone's day, the nights off are counted on the chain's.
        assertEquals(LocalDate.of(2026, 10, 5), facts.today)
        assertEquals(tokyo, facts.zone)
        assertEquals(20_730L, facts.todayUtcDay)
        assertEquals(28, facts.days!!.size)
    }

    @Test
    fun `the streak line is the Rig account's, with the nights off the rule leaves`() = runTest {
        val model = model(FakeChain(rig, emptyList()))
        model.load()
        advanceUntilIdle()
        val facts = (model.state.value as NightsState.Ready).facts
        val read = facts.rig!!
        assertEquals("3 nights", NightsCopy.streak(read.streak))
        assertEquals("Nights off left: 1", NightsCopy.nightsOff(read.nightsOffLeft(facts.todayUtcDay)))
        // The same account read a period later: the two are back, though the account still says one.
        val stale = model(FakeChain(rig.copy(lastShiftDay = 20_729), emptyList()))
        stale.load()
        advanceUntilIdle()
        val later = (stale.state.value as NightsState.Ready).facts
        assertEquals("Nights off left: 2", NightsCopy.nightsOff(later.rig!!.nightsOffLeft(later.todayUtcDay)))
        // No finished shift is not a problem: the list is there, and empty.
        assertEquals(emptyList<IndexerShift>(), facts.shifts)
        assertTrue(facts.days!!.all { it == DayMark.NO_SHIFT })
        assertFalse(facts.incomplete)
    }

    @Test
    fun `when the chain cannot be read the list is still shown, and the other way round`() = runTest {
        val chain = FakeChain(rig, listOf(newest))
        chain.rigFails = AccountLayoutException("Rig bump is not canonical")
        val model = model(chain)
        model.load()
        advanceUntilIdle()
        var facts = (model.state.value as NightsState.Ready).facts
        assertNull(facts.rig)
        assertEquals(NightsCopy.RIG_UNREADABLE, facts.rigProblem)
        assertEquals(listOf(newest), facts.shifts)
        assertTrue("it can be tried again", facts.incomplete)

        chain.rigFails = null
        chain.shiftsFail = IndexerHttpException(503)
        model.load()
        advanceUntilIdle()
        facts = (model.state.value as NightsState.Ready).facts
        assertEquals(rig, facts.rig)
        assertNull(facts.shifts)
        assertNull(facts.days)
        assertEquals(NightsCopy.LIST_UNREADABLE, facts.shiftsProblem)
        assertTrue(facts.incomplete)
    }

    @Test
    fun `no rig at the address, or a build with no indexer, is said and is not something to try again`() = runTest {
        val model = model(FakeChain(rig = null, shifts = null))
        model.load()
        advanceUntilIdle()
        val facts = (model.state.value as NightsState.Ready).facts
        assertEquals(NightsCopy.RIG_GONE, facts.rigProblem)
        assertEquals(NightsCopy.NO_INDEXER, facts.shiftsProblem)
        assertFalse(facts.incomplete)
    }

    @Test
    fun `when neither can be read it says so in its own words, and trying again reads both`() = runTest {
        val chain = FakeChain(rig, listOf(newest))
        chain.rigFails = IOException("secret-host.example refused")
        chain.shiftsFail = IOException("secret-host.example refused")
        val model = model(chain)
        model.load()
        advanceUntilIdle()
        val unavailable = model.state.value as NightsState.Unavailable
        assertEquals(NightsCopy.UNREADABLE, unavailable.message)
        assertFalse("secret-host" in unavailable.message)

        chain.rigFails = null
        chain.shiftsFail = null
        model.load()
        advanceUntilIdle()
        assertEquals(listOf(newest), (model.state.value as NightsState.Ready).facts.shifts)
    }
}
