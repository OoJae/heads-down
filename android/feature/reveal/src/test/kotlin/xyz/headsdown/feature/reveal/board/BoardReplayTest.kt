package xyz.headsdown.feature.reveal.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.feature.reveal.haul.Board
import xyz.headsdown.feature.reveal.haul.FakeHaulRepository
import xyz.headsdown.feature.reveal.haul.RoundReplay
import java.time.ZoneId

class BoardReplayTest {
    private val night = FakeHaulRepository.sampleNight(1_790_000_000_000L, ZoneId.of("UTC"))

    @Test
    fun `a full night replays in three seconds, short shifts a bit faster`() {
        assertEquals(3_000L, BoardReplay.durationFor(312))
        assertEquals(1_200L, BoardReplay.durationFor(5))
        assertEquals(2_000L, BoardReplay.durationFor(50))
        assertEquals(3_000L, BoardReplay(night.rounds).durationMillis)
    }

    @Test
    fun `starts empty and ends with every dig on its tile`() {
        val replay = BoardReplay(night.rounds)
        val first = replay.frameAt(0)
        assertEquals(0, first.roundsShown)
        assertTrue(first.heat.all { it == 0 })

        val last = replay.finalFrame()
        assertTrue(last.done)
        assertEquals(312, last.roundsShown)
        val expected = (0 until Board.TILES).map { t -> night.rounds.count { it.dugTile(t) } }
        assertEquals(expected, last.heat)
        assertEquals(18 * 4, last.heat.sum())
        assertTrue("flashes are gone at the end", last.flashes.isEmpty())
    }

    @Test
    fun `progress is monotonic and frame-rate independent`() {
        val replay = BoardReplay(night.rounds)
        var previous = 0
        // Sample at 120 Hz.
        var t = 0.0
        while (t <= replay.durationMillis) {
            val f = replay.frameAt(t.toLong())
            assertTrue(f.roundsShown >= previous)
            previous = f.roundsShown
            t += 1000.0 / 120
        }
        // A 60 Hz and a 120 Hz sampler agree on any shared instant.
        listOf(0L, 500L, 1_000L, 2_999L).forEach { ms ->
            assertEquals(BoardReplay(night.rounds).frameAt(ms), replay.frameAt(ms))
        }
    }

    @Test
    fun `winning tiles flash, gold when the rig's tile came up`() {
        val rounds = listOf(
            RoundReplay(1, dugMask = 1 shl 3, winningTile = 3), // hit
            RoundReplay(2, dugMask = 0, winningTile = 7), // sat out
            RoundReplay(3, dugMask = 1 shl 1, winningTile = 2, motherlode = true), // near miss
        )
        val replay = BoardReplay(rounds, durationMillis = 300, flashMillis = 160)
        // Just after round 1 is replayed.
        val f1 = replay.frameAt(101)
        assertEquals(1, f1.roundsShown)
        assertEquals(listOf(TileFlash(3, FlashKind.HIT, f1.flashes.single().strength)), f1.flashes)
        assertEquals(1 shl 3, f1.litNow)
        // After round 2: both flash, newest first and strongest.
        val f2 = replay.frameAt(201)
        assertEquals(listOf(7, 3), f2.flashes.map { it.tile })
        assertEquals(FlashKind.WINNER, f2.flashes.first().kind)
        assertTrue(f2.flashes[0].strength > f2.flashes[1].strength)
        assertEquals(setOf(3), replay.hitTiles)
    }

    @Test
    fun `a motherlode share flashes as such`() {
        val replay = BoardReplay(listOf(RoundReplay(1, dugMask = 1 shl 9, winningTile = 9, motherlode = true)), durationMillis = 100)
        assertEquals(FlashKind.MOTHERLODE_HIT, replay.frameAt(100).flashes.single().kind)
    }

    @Test
    fun `an empty night is a still, empty board`() {
        val replay = BoardReplay(emptyList())
        val f = replay.frameAt(500)
        assertEquals(0, f.totalRounds)
        assertTrue(f.done)
        assertTrue(f.heat.all { it == 0 })
    }
}
