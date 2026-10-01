package xyz.headsdown.core.chain.indexer

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.TlsServer
import xyz.headsdown.core.chain.http.OkHttpJsonHttp
import java.math.BigDecimal
import java.math.BigInteger

/** Contract B against a fake indexer (MockWebServer over TLS). */
class IndexerHaulClientTest {

    private val tls = TlsServer()
    private val client = IndexerHaulClient(OkHttpJsonHttp(tls.url(), tls.client))
    private val rig = HeadsDownProgram.rig(Pubkey.fromBase58("DgmxzQX61DxkAMkAubrgHVJb637fYYTdh7ouVqZGnJrp")).address

    @After
    fun close() = tls.close()

    private fun summary(
        rigAddress: String = rig.toBase58(),
        rounds: String = """[
            {"round_id":422700,"dark":true,"dug_mask":1023,"winning_square":3,"motherlode":false,"split":true},
            {"round_id":"422701","dark":true,"dug_mask":0,"winning_square":null,"motherlode":false,"split":false},
            {"round_id":422702,"dark":false,"dug_mask":0,"winning_square":24,"motherlode":true,"split":false}
        ]""",
        ore: String = "\"582000000\"",
        effective: String = "\"680000000.25\"",
        market: String = "null",
        explorer: String = """{"shift_log":"https://explorer.solana.com/address/x","sample_digs":["https://explorer.solana.com/tx/1","http://insecure.example/tx/2"]}""",
    ) = """{
        "rig":"$rigAddress","shift_id":"7","mode":"night","start_ts":1790636400,"end_ts":1790664000,
        "start_round":422700,"end_round":422702,"rounds":$rounds,
        "dark_rounds":2,"rounds_dug":1,"sol_placed_lamports":"1000000","fees_lamports":5000,
        "ore_mined_atoms":$ore,"effective_lamports_per_ore":$effective,"market_lamports_per_ore":$market,
        "market_source":null,"streak_before":22,"streak_after":23,"break_reason":0,"first_pickup_ts":null,
        "simulated":false,"explorer":$explorer,"unknown_future_field":{"x":1}
    }"""

    private fun respond(code: Int, body: String) =
        tls.server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())

    @Test
    fun `latest parses a full HaulSummary exactly`() = runBlocking {
        respond(200, summary())
        val h = client.latest(rig)!!
        assertEquals("/v1/rigs/${rig.toBase58()}/haul/latest", tls.server.takeRequest().url.encodedPath)
        assertEquals(rig, h.rig)
        assertEquals(7uL, h.shiftId)
        assertEquals(HaulMode.NIGHT, h.mode)
        assertEquals(1_790_636_400L, h.startTs)
        assertEquals(1_790_664_000L, h.endTs)
        assertEquals(3, h.rounds.size)
        assertEquals(HaulRound(422_700uL, dark = true, dugMask = 1023, winningSquare = 3, motherlode = false, split = true), h.rounds[0])
        assertNull(h.rounds[1].winningSquare)
        assertTrue(h.rounds[2].motherlode)
        assertEquals(2uL, h.darkRounds)
        assertEquals(1uL, h.roundsDug)
        assertEquals(1_000_000uL, h.solPlacedLamports)
        assertEquals(5_000uL, h.feesLamports)
        assertEquals(BigInteger.valueOf(582_000_000L), h.oreMinedAtoms)
        assertEquals(BigDecimal("680000000.25"), h.effectiveLamportsPerOre)
        assertNull(h.marketLamportsPerOre)
        assertNull(h.marketSource)
        assertEquals(22L, h.streakBefore)
        assertEquals(23L, h.streakAfter)
        assertEquals(0, h.breakReason)
        assertNull(h.firstPickupTs)
        assertFalse(h.simulated)
        assertEquals("https://explorer.solana.com/address/x", h.explorerShiftLog)
        // Only https links survive.
        assertEquals(listOf("https://explorer.solana.com/tx/1"), h.explorerSampleDigs)
    }

    @Test
    fun `a given shift is fetched by id and 404 means no finished shift`() = runBlocking {
        respond(404, """{"error":"not_found"}""")
        assertNull(client.byShift(rig, 12uL))
        assertEquals("/v1/rigs/${rig.toBase58()}/haul/12", tls.server.takeRequest().url.encodedPath)
        respond(404, "")
        assertNull(client.latest(rig))
    }

    @Test
    fun `server errors and foreign or impossible summaries are refused`() = runBlocking {
        respond(500, "oops")
        assertEquals(500, runCatching { client.latest(rig) }.exceptionOrNull().let { (it as IndexerHttpException).status })
        val other = Pubkey(ByteArray(32) { 4 }).toBase58()
        for (body in listOf(
            summary(rigAddress = other),
            summary(rounds = """[{"round_id":1,"dark":true,"dug_mask":33554432,"winning_square":1,"motherlode":false,"split":false}]"""),
            summary(rounds = """[{"round_id":1,"dark":true,"dug_mask":1,"winning_square":25,"motherlode":false,"split":false}]"""),
            summary(rounds = """[{"round_id":1,"dark":"yes","dug_mask":1,"winning_square":2,"motherlode":false,"split":false}]"""),
            summary(ore = "\"-5\""),
            summary(effective = "\"0\""),
            "not json",
        )) {
            respond(200, body)
            assertTrue(body.take(60), runCatching { client.latest(rig) }.exceptionOrNull() is HaulFormatException)
        }
    }

    @Test
    fun `ore may arrive as atoms or as ORE with 11 decimals, big numbers as strings`() {
        assertEquals(BigInteger.valueOf(582_000_000L), IndexerHaulClient.parse(summary(ore = "\"0.00582000000\""), rig).oreMinedAtoms)
        assertEquals(BigInteger.valueOf(582_000_000L), IndexerHaulClient.parse(summary(ore = "582000000"), rig).oreMinedAtoms)
        assertEquals(BigInteger("123456789012345678901"), IndexerHaulClient.parse(summary(ore = "\"123456789012345678901\""), rig).oreMinedAtoms)
        // More precision than an atom is not an amount.
        assertThrows(HaulFormatException::class.java) { IndexerHaulClient.parse(summary(ore = "\"0.000000000001\""), rig) }
        val big = summary().replace("\"sol_placed_lamports\":\"1000000\"", "\"sol_placed_lamports\":\"18446744073709551615\"")
        assertEquals(ULong.MAX_VALUE, IndexerHaulClient.parse(big, rig).solPlacedLamports)
        val named = summary().replace("\"break_reason\":0", "\"break_reason\":\"unlocked\"")
        assertEquals(8, IndexerHaulClient.parse(named, rig).breakReason)
        val focus = summary().replace("\"mode\":\"night\"", "\"mode\":\"focus_only\"").replace("\"simulated\":false", "\"simulated\":true")
        val parsed = IndexerHaulClient.parse(focus, rig)
        assertEquals(HaulMode.FOCUS_ONLY, parsed.mode)
        assertTrue(parsed.simulated)
    }

    @Test
    fun `the indexer client refuses cleartext endpoints`() {
        assertThrows(IllegalArgumentException::class.java) { OkHttpJsonHttp("http://indexer.example", tls.client) }
    }
}
