package xyz.headsdown.haul

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.accounts.HeadsDownAccounts
import xyz.headsdown.core.chain.accounts.RigSlot
import xyz.headsdown.core.chain.accounts.RigTombstoneAccount
import xyz.headsdown.core.chain.http.OkHttpJsonHttp
import xyz.headsdown.core.chain.indexer.IndexerHaulClient
import xyz.headsdown.core.chain.rpc.AccountInfo
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.feature.reveal.haul.HaulProvenance
import xyz.headsdown.feature.reveal.haul.HonestCopy
import xyz.headsdown.feature.reveal.haul.NightReport
import xyz.headsdown.feature.reveal.haul.OpenNight
import xyz.headsdown.feature.reveal.haul.RevealCopyBuilder
import xyz.headsdown.feature.shift.RigBinding
import xyz.headsdown.feature.shift.RigBindingProvider
import xyz.headsdown.feature.shift.ShiftMode
import xyz.headsdown.feature.shift.ShiftRecord
import xyz.headsdown.surface.widget.RigWidgetUpdates
import xyz.headsdown.surface.widget.WidgetHaul
import xyz.headsdown.surface.widget.WidgetRig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneId

/** The morning haul from a fake indexer (contract B over TLS) to the reveal and the widget. */
class IndexerHaulRepositoryTest {

    private val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
    private val server = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        start()
    }
    private val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
    private val indexer = IndexerHaulClient(OkHttpJsonHttp("https://localhost:${server.port}", client))

    private val authority = Pubkey.fromBase58("DgmxzQX61DxkAMkAubrgHVJb637fYYTdh7ouVqZGnJrp")
    private val rig = HeadsDownProgram.rig(authority).address
    private val bound = RigBindingProvider { RigBinding(HeadsDownProgram.ID.bytes, rig.bytes) }

    private class Widgets : RigWidgetUpdates {
        val hauls = mutableListOf<WidgetHaul>()
        val streaks = mutableListOf<Int>()
        override fun onRig(rig: WidgetRig) = Unit
        override fun onHaul(haul: WidgetHaul) { hauls += haul }
        override fun onStreak(nights: Int) { streaks += nights }
    }

    private val widgets = Widgets()
    private val start = 1_790_636_400L
    private val end = 1_790_664_000L

    @After
    fun close() = server.close()

    /** The journal after shift [id]: armed at [start], 371 heartbeats, the last two minutes before [end]. */
    private fun journaled(id: Long, pickup: Long? = null) = ShiftRecord(
        shiftId = id,
        mode = ShiftMode.NIGHT,
        armedAtWallMillis = start * 1000,
        lastHeartbeatWallMillis = (end - 120) * 1000,
        darkRounds = 371,
        endedAtWallMillis = null,
        endReason = null,
        firstPickupWallMillis = pickup,
    )

    private val nightEight = OpenNight(shiftId = 8, armedAtWallMillis = start * 1000, lastHeartbeatWallMillis = (end - 120) * 1000, roundsSigned = 371)

    private fun repo(last: ShiftRecord?, onChain: suspend (Pubkey, Long) -> ShiftOnChain = { _, _ -> error("the Rig is not read") }) =
        IndexerHaulRepository(indexer, bound, { last }, widgets, onChain)

    private fun summary(simulated: Boolean = false, firstPickup: String = "null", shiftId: Long = 7) = """{
        "rig":"$rig","shift_id":$shiftId,"mode":"night","start_ts":$start,"end_ts":$end,"start_round":422700,"end_round":422703,
        "rounds":[
          {"round_id":422700,"dark":true,"dug_mask":1023,"winning_square":3,"motherlode":false,"split":true},
          {"round_id":422701,"dark":true,"dug_mask":0,"winning_square":9,"motherlode":false,"split":false},
          {"round_id":422702,"dark":false,"dug_mask":0,"winning_square":24,"motherlode":true,"split":false},
          {"round_id":422703,"dark":true,"dug_mask":1,"winning_square":null,"motherlode":false,"split":false}
        ],
        "dark_rounds":3,"rounds_dug":2,"sol_placed_lamports":2000000,"fees_lamports":"10000",
        "ore_mined_atoms":"582000000","effective_lamports_per_ore":"680000000.4","market_lamports_per_ore":"758000000",
        "market_source":"jupiter","streak_before":22,"streak_after":23,"break_reason":0,"first_pickup_ts":$firstPickup,
        "simulated":$simulated,"explorer":{"shift_log":"https://explorer.solana.com/address/$rig?cluster=devnet","sample_digs":[]}
    }"""

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())

    @Test
    fun `a real haul reaches the reveal and the widget, with the phone's first pickup`() = runBlocking {
        respond(200, summary())
        val pickup = (end - 120) * 1000
        val repo = IndexerHaulRepository(indexer, bound, lastShift = { journaled(7, pickup) }, widgets = widgets)
        val h = repo.latest()!!
        assertEquals("/v1/rigs/$rig/haul/latest", server.takeRequest().url.encodedPath)
        assertEquals(HaulProvenance.ON_CHAIN, h.provenance)
        assertEquals(7L, h.shiftId)
        assertEquals(start * 1000, h.startedAtWallMillis)
        assertEquals(end * 1000, h.endedAtWallMillis)
        // The replay keeps the dark (or dug) rounds; the counts come from the ShiftLog.
        assertEquals(listOf(422_700L, 422_701L, 422_703L), h.rounds.map { it.roundId })
        assertEquals(3, h.roundsDark)
        assertEquals(2, h.digs)
        assertEquals(null, h.rounds.last().winningTile)
        assertEquals(2_000_000L, h.solPlacedLamports)
        assertEquals(10_000L, h.feesLamports)
        assertEquals(582_000_000L, h.oreMinedAtoms)
        assertEquals(680_000_000L, h.effectiveLamportsPerOre)
        assertEquals(758_000_000L, h.marketLamportsPerOre)
        assertEquals(23, h.streak.after)
        assertEquals(pickup, h.firstPickupWallMillis)
        assertTrue(h.explorerUrl!!.startsWith("https://explorer.solana.com/"))
        // The widget gets the real haul and the streak.
        assertEquals(listOf(WidgetHaul(582_000_000L, end * 1000)), widgets.hauls)
        assertEquals(listOf(23), widgets.streaks)
        // And the reveal copy is honest.
        RevealCopyBuilder.build(h, ZoneId.of("UTC")).allText.forEach { assertTrue(it, HonestCopy.violations(it).isEmpty()) }
    }

    @Test
    fun `a simulated haul is labelled and never reaches the widget`() = runBlocking {
        respond(200, summary(simulated = true))
        val h = IndexerHaulRepository(indexer, bound, { null }, widgets).latest()!!
        assertEquals(HaulProvenance.SIMULATED, h.provenance)
        assertTrue(widgets.hauls.isEmpty() && widgets.streaks.isEmpty())
    }

    @Test
    fun `an indexer pickup wins, and a pickup from another night is dropped`() = runBlocking {
        respond(200, summary(firstPickup = "${end + 60}"))
        assertEquals((end + 60) * 1000, IndexerHaulRepository(indexer, bound, { journaled(7, 1L) }, widgets).latest()!!.firstPickupWallMillis)
        respond(200, summary())
        assertNull(IndexerHaulRepository(indexer, bound, { journaled(7, (start - 3 * 86_400) * 1000) }, widgets).latest()!!.firstPickupWallMillis)
        // The journal has moved on to another shift: its pickup is not this haul's.
        respond(200, summary())
        assertNull(IndexerHaulRepository(indexer, bound, { journaled(8, (end - 120) * 1000) }, widgets).latest()!!.firstPickupWallMillis)
    }

    @Test
    fun `no indexer, no bound rig, no finished shift or an outage all mean no haul yet`() = runBlocking {
        assertNull(IndexerHaulRepository(null, bound, { null }, widgets).latest())
        assertNull(IndexerHaulRepository(indexer, { RigBinding.UNREGISTERED }, { null }, widgets).latest())
        respond(404, """{"error":"not_found"}""")
        assertNull(IndexerHaulRepository(indexer, bound, { null }, widgets).latest())
        respond(503, "")
        assertTrue(runCatching { IndexerHaulRepository(indexer, bound, { null }, widgets).latest() }.isFailure)
        assertTrue(widgets.hauls.isEmpty())
    }

    // --- the night the reveal opens on ---

    @Test
    fun `a night that is over and not sealed is reported as itself, and the indexer is not even asked`() = runBlocking {
        val asked = mutableListOf<Long>()
        val report = repo(journaled(8)) { address, id ->
            assertEquals(rig, address)
            asked += id
            ShiftOnChain.ENDED_OPEN
        }.night()
        assertEquals(NightReport.NotSealed(nightEight), report)
        assertEquals(listOf(8L), asked)
        // At the alarm the chain is the whole answer: no older haul was read, shown or sent to the widget.
        assertEquals(0, server.requestCount)
        assertTrue(widgets.hauls.isEmpty() && widgets.streaks.isEmpty())
        // A first night, with no sealed shift at all, is the same: not sealed, rather than "no haul yet".
        assertEquals(NightReport.NotSealed(nightEight.copy(shiftId = 1)), repo(journaled(1)) { _, _ -> ShiftOnChain.ENDED_OPEN }.night())
    }

    @Test
    fun `once sealed the night's own haul is asked for by its shift id, until it is final`() = runBlocking {
        val repo = repo(journaled(8)) { _, _ -> ShiftOnChain.SEALED }
        // Sealed a moment ago: ORE has not settled the last round it dug.
        respond(404, """{"error":"shift 8 ended but its haul is not final yet"}""")
        assertEquals(NightReport.NotReadYet(nightEight), repo.night())
        assertEquals("/v1/rigs/$rig/haul/8", server.takeRequest().url.encodedPath)
        // One failed read among many is the same as "not yet": asked again, never "no haul".
        respond(503, "")
        assertEquals(NightReport.NotReadYet(nightEight), repo.night())
        // The round settled: the haul is the night's.
        respond(200, summary(shiftId = 8))
        val report = repo.night() as NightReport.Haul
        assertEquals(8L, report.summary.shiftId)
        assertEquals(listOf(23), widgets.streaks)
        assertEquals(3, server.requestCount) // never /haul/latest: that could be an older night's
    }

    @Test
    fun `a shift still being worked does not hide the last sealed night`() = runBlocking {
        // Shift 9 was armed this evening; the reveal is opened from the home screen.
        respond(200, summary(shiftId = 8))
        val report = repo(journaled(9)) { _, _ -> ShiftOnChain.RUNNING }.night() as NightReport.Haul
        assertEquals(8L, report.summary.shiftId)
        assertEquals("/v1/rigs/$rig/haul/latest", server.takeRequest().url.encodedPath)
        // And with no sealed night before it, there is simply none yet.
        respond(404, """{"error":"no finished shift for this rig"}""")
        assertEquals(NightReport.None, repo(journaled(1)) { _, _ -> ShiftOnChain.RUNNING }.night())
    }

    @Test
    fun `no journal, or a shift that never was on-chain, shows the last sealed night`() = runBlocking {
        // A new install of an old rig: the Rig is not read, the latest sealed shift is all there is.
        respond(200, summary(shiftId = 8))
        assertEquals(8L, (repo(null).night() as NightReport.Haul).summary.shiftId)
        respond(404, """{"error":"no finished shift for this rig"}""")
        assertEquals(NightReport.None, repo(null).night())
        // A focus-only shift from the tile has a local id far above the rig's counter.
        respond(200, summary(shiftId = 7))
        val focus = journaled(1_790_640_000)
        assertEquals(7L, (repo(focus) { _, _ -> ShiftOnChain.NOT_ON_CHAIN }.night() as NightReport.Haul).summary.shiftId)
    }

    @Test
    fun `a chain that cannot be read is said to be unread, not sealed and not empty`() = runBlocking {
        val report = repo(journaled(8)) { _, _ -> throw java.io.IOException("offline") }.night()
        assertEquals(NightReport.Unread(nightEight), report)
        assertEquals(0, server.requestCount)
        assertTrue(widgets.hauls.isEmpty())
    }

    @Test
    fun `no indexer or no bound rig is no night`() = runBlocking {
        assertEquals(NightReport.None, IndexerHaulRepository(null, bound, { journaled(8) }, widgets).night())
        assertEquals(NightReport.None, IndexerHaulRepository(indexer, { RigBinding.UNREGISTERED }, { journaled(8) }, widgets).night())
    }

    // --- where a shift stands, from what the Rig address holds ---

    private val now = 1_790_650_000L

    private fun live(shiftId: Long, state: RigSignalState = RigSignalState.DOWN, open: Boolean = true, windowEnd: Long = now + 3_600): RigSlot {
        val pda = HeadsDownProgram.rig(authority)
        val key = byteArrayOf(0x03) + ByteArray(32) { 0x21 }
        val data = ByteBuffer.allocate(384).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0, 2); put(1, 1); put(2, pda.bump.toByte())
            position(8); put(authority.bytes)
            position(40); put(key)
            put(75, state.wire.toByte())
            put(178, 3)
            putLong(192, windowEnd)
            putLong(200, shiftId)
            put(336, if (open) 1 else 0)
        }.array()
        return RigSlot.Live(HeadsDownAccounts.rig(pda.address, AccountInfo(1uL, HeadsDownProgram.ID, data, false)))
    }

    @Test
    fun `an open shift is running inside its window and over after it, or once it broke`() {
        assertEquals(ShiftOnChain.RUNNING, ShiftOnChain.of(live(8), 8, now))
        assertEquals(ShiftOnChain.RUNNING, ShiftOnChain.of(live(8, RigSignalState.ARMED), 8, now))
        assertEquals(ShiftOnChain.RUNNING, ShiftOnChain.of(live(8, windowEnd = now), 8, now))
        // The alarm: the window is over, the rig is still Down on-chain, nobody has sealed it.
        assertEquals(ShiftOnChain.ENDED_OPEN, ShiftOnChain.of(live(8, windowEnd = now - 180), 8, now))
        // A pickup at three in the morning: broken inside its window, and over all the same.
        assertEquals(ShiftOnChain.ENDED_OPEN, ShiftOnChain.of(live(8, RigSignalState.BROKEN), 8, now))
        assertEquals(ShiftOnChain.ENDED_OPEN, ShiftOnChain.of(live(8, RigSignalState.COOLING), 8, now))
        assertEquals(ShiftOnChain.ENDED_OPEN, ShiftOnChain.of(live(8, RigSignalState.FROZEN), 8, now))
    }

    @Test
    fun `a shift the rig has closed or moved past is sealed, also when the rig itself was closed since`() {
        assertEquals(ShiftOnChain.SEALED, ShiftOnChain.of(live(8, RigSignalState.IDLE, open = false), 8, now))
        assertEquals(ShiftOnChain.SEALED, ShiftOnChain.of(live(9), 8, now))
        val pda = HeadsDownProgram.rig(authority).address
        assertEquals(ShiftOnChain.SEALED, ShiftOnChain.of(RigSlot.Closed(RigTombstoneAccount(pda, shiftId = 8uL, hbCounter = 52uL, lastDugRound = 0uL)), 8, now))
        // An id the rig never reached is not a shift of this rig's: nothing is sealed, nothing is open.
        assertEquals(ShiftOnChain.NOT_ON_CHAIN, ShiftOnChain.of(live(7), 8, now))
        assertEquals(ShiftOnChain.NOT_ON_CHAIN, ShiftOnChain.of(RigSlot.Closed(RigTombstoneAccount(pda, 7uL, 52uL, 0uL)), 8, now))
        assertEquals(ShiftOnChain.NOT_ON_CHAIN, ShiftOnChain.of(RigSlot.Empty, 8, now))
        assertEquals(ShiftOnChain.NOT_ON_CHAIN, ShiftOnChain.of(live(7), 1_790_640_000, now))
    }
}
