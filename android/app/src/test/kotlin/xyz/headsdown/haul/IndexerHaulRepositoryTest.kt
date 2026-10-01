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
import xyz.headsdown.core.chain.http.OkHttpJsonHttp
import xyz.headsdown.core.chain.indexer.IndexerHaulClient
import xyz.headsdown.feature.reveal.haul.HaulProvenance
import xyz.headsdown.feature.reveal.haul.HonestCopy
import xyz.headsdown.feature.reveal.haul.RevealCopyBuilder
import xyz.headsdown.feature.shift.RigBinding
import xyz.headsdown.feature.shift.RigBindingProvider
import xyz.headsdown.surface.widget.RigWidgetUpdates
import xyz.headsdown.surface.widget.WidgetHaul
import xyz.headsdown.surface.widget.WidgetRig
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

    private fun summary(simulated: Boolean = false, firstPickup: String = "null") = """{
        "rig":"$rig","shift_id":7,"mode":"night","start_ts":$start,"end_ts":$end,"start_round":422700,"end_round":422703,
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
        val repo = IndexerHaulRepository(indexer, bound, firstPickup = { id -> if (id == 7L) pickup else null }, widgets = widgets)
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
        assertEquals((end + 60) * 1000, IndexerHaulRepository(indexer, bound, { 1L }, widgets).latest()!!.firstPickupWallMillis)
        respond(200, summary())
        assertNull(IndexerHaulRepository(indexer, bound, { (start - 3 * 86_400) * 1000 }, widgets).latest()!!.firstPickupWallMillis)
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
}
