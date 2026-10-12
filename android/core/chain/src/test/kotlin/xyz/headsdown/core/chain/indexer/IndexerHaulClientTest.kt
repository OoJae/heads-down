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

    private companion object {
        /** An account address and a transaction signature, as an explorer link carries them. */
        const val LOG = "8JCHihWaDyRgVdC82ayAPXFRYPqLZTnjJe85i6iJ1fC9"
        const val SIG = "5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW"
    }

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
        explorer: String = """{"shift_log":"https://explorer.solana.com/address/$LOG","sample_digs":["https://solscan.io/tx/$SIG","http://solscan.io/tx/$SIG"]}""",
        marketSource: String = "null",
    ) = """{
        "rig":"$rigAddress","shift_id":"7","mode":"night","start_ts":1790636400,"end_ts":1790664000,
        "start_round":422700,"end_round":422702,"rounds":$rounds,
        "dark_rounds":2,"rounds_dug":1,"sol_placed_lamports":"1000000","fees_lamports":5000,
        "ore_mined_atoms":$ore,"effective_lamports_per_ore":$effective,"market_lamports_per_ore":$market,
        "market_source":$marketSource,"streak_before":22,"streak_after":23,"break_reason":0,"first_pickup_ts":null,
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
        assertEquals("https://explorer.solana.com/address/$LOG", h.explorerShiftLog)
        // Only https links survive.
        assertEquals(listOf("https://solscan.io/tx/$SIG"), h.explorerSampleDigs)
    }

    @Test
    fun `a link the indexer supplies can only be a page of a known explorer`() = runBlocking {
        // "See this shift on-chain" opens the link with ACTION_VIEW: an https URL can also be a
        // wallet's universal link, so anything but an explorer's tx / account page is dropped.
        val refused = listOf(
            "https://phantom.app/ul/browse/https%3A%2F%2Fevil.example%2Fclaim?ref=x",
            "https://evil.example/tx/$SIG",
            "https://solscan.io.evil.example/tx/$SIG",
            "https://solscan.io/token/$SIG",
            "https://solscan.io/tx/$SIG/extra",
            "https://solscan.io/tx/not-base58!",
            "https://user:pw@solscan.io/tx/$SIG",
            "https://solscan.io:8443/tx/$SIG",
            "http://solscan.io/tx/$SIG",
        )
        for (url in refused) {
            respond(200, summary(explorer = """{"shift_log":"$url","sample_digs":["$url"]}"""))
            val h = client.latest(rig)!!
            assertNull(url, h.explorerShiftLog)
            assertEquals(url, emptyList<String>(), h.explorerSampleDigs)
        }
        // Devnet and localnet links keep their cluster query: it only picks the explorer's view.
        val devnet = "https://solscan.io/account/$LOG?cluster=devnet"
        respond(200, summary(explorer = """{"shift_log":"$devnet","sample_digs":[]}"""))
        assertEquals(devnet, client.latest(rig)!!.explorerShiftLog)
        // The price source is a short plain label or nothing: it is shown next to the price.
        respond(200, summary(marketSource = "\"jupiter-price-v3\""))
        assertEquals("jupiter-price-v3", client.latest(rig)!!.marketSource)
        respond(200, summary(marketSource = "\"api.ore.com/market\""))
        assertEquals("api.ore.com/market", client.latest(rig)!!.marketSource)
        respond(200, summary(marketSource = "\"FREE ORE: tap 'See this shift on-chain' to claim\""))
        assertNull(client.latest(rig)!!.marketSource)
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

    /** `GET /v1/rigs/{rig}/shifts` as the indexer serves it: an envelope, counters as strings, newest first. */
    private fun shiftList(
        rigAddress: String = rig.toBase58(),
        shifts: String = """[
            {"shiftId":"7","endedAt":1791750944,"darkRounds":"5","roundsDug":"5","reason":1,"mode":0,"signature":"$SIG"},
            {"shiftId":"6","endedAt":1791664140,"darkRounds":"412","roundsDug":"0","reason":0,"mode":2,"signature":"$SIG"},
            {"shiftId":5,"endedAt":"1791577740","darkRounds":3,"roundsDug":1,"reason":8,"mode":1,"signature":"not a signature"}
        ]""",
    ) = """{"dataset":{"cluster":"mainnet","program":"x"},"asOf":1791767329,"generatedAt":"2026-10-11T08:28:49Z",
        "data":{"rig":"$rigAddress","shifts":$shifts}}"""

    @Test
    fun `every finished shift of a rig is listed, newest first, with its counters and its sealing transaction`() = runBlocking {
        respond(200, shiftList())
        val shifts = client.shifts(rig)
        assertEquals("/v1/rigs/${rig.toBase58()}/shifts", tls.server.takeRequest().url.encodedPath)
        assertEquals(
            listOf(
                IndexerShift(7uL, 1_791_750_944L, darkRounds = 5uL, roundsDug = 5uL, reason = 1, mode = HaulMode.NIGHT, signature = SIG),
                IndexerShift(6uL, 1_791_664_140L, darkRounds = 412uL, roundsDug = 0uL, reason = 0, mode = HaulMode.FOCUS_ONLY, signature = SIG),
                // Numbers arrive either way; what is not a signature never reaches a link.
                IndexerShift(5uL, 1_791_577_740L, darkRounds = 3uL, roundsDug = 1uL, reason = 8, mode = HaulMode.DAY, signature = null),
            ),
            shifts,
        )
        // A rig with no finished shift, and a rig the indexer has never seen.
        respond(200, shiftList(shifts = "[]"))
        assertEquals(emptyList<IndexerShift>(), client.shifts(rig))
        respond(404, """{"error":"not_found"}""")
        assertEquals(emptyList<IndexerShift>(), client.shifts(rig))
        respond(503, "busy")
        assertEquals(503, runCatching { client.shifts(rig) }.exceptionOrNull().let { (it as IndexerHttpException).status })
    }

    @Test
    fun `a list for another rig, or with an impossible shift in it, is refused`() {
        val other = Pubkey(ByteArray(32) { 4 }).toBase58()
        fun one(shift: String) = shiftList(shifts = "[$shift]")
        for (body in listOf(
            shiftList(rigAddress = other),
            shiftList(rigAddress = "not-base58!"),
            """{"data":{"rig":"${rig.toBase58()}"}}""",
            """{"rig":"${rig.toBase58()}","shifts":[]}""",
            one("""{"shiftId":"-1","endedAt":1,"darkRounds":"0","roundsDug":"0","reason":0,"mode":0,"signature":"$SIG"}"""),
            one("""{"shiftId":"7","endedAt":-5,"darkRounds":"0","roundsDug":"0","reason":0,"mode":0,"signature":"$SIG"}"""),
            one("""{"shiftId":"7","endedAt":9223372036854775807,"darkRounds":"0","roundsDug":"0","reason":0,"mode":0,"signature":"$SIG"}"""),
            one("""{"shiftId":"7","endedAt":1,"darkRounds":"18446744073709551616","roundsDug":"0","reason":0,"mode":0,"signature":"$SIG"}"""),
            one("""{"shiftId":"7","endedAt":1,"darkRounds":"0","roundsDug":"0","reason":256,"mode":0,"signature":"$SIG"}"""),
            one("""{"shiftId":"7","endedAt":1,"darkRounds":"0","roundsDug":"0","reason":0,"mode":3,"signature":"$SIG"}"""),
            one("""{"shiftId":"7","endedAt":1,"darkRounds":"0","reason":0,"mode":0,"signature":"$SIG"}"""),
            one("\"7\""),
            "not json",
            "[]",
        )) {
            assertThrows(body.take(80), HaulFormatException::class.java) { IndexerHaulClient.parseShifts(body, rig) }
        }
        // The largest counter a ShiftLog can hold, and a shift with no signature at all.
        val big = one("""{"shiftId":"18446744073709551615","endedAt":1,"darkRounds":"18446744073709551615","roundsDug":"0","reason":255,"mode":0}""")
        assertEquals(
            listOf(IndexerShift(ULong.MAX_VALUE, 1L, ULong.MAX_VALUE, 0uL, reason = 255, mode = HaulMode.NIGHT, signature = null)),
            IndexerHaulClient.parseShifts(big, rig),
        )
    }

    @Test
    fun `a list longer than the bound keeps the newest and leaves the oldest out`() {
        val many = (0 until IndexerHaulClient.MAX_SHIFTS + 5).joinToString(",") { i ->
            """{"shiftId":"${5_000 - i}","endedAt":${2_000_000_000L - i * 86_400L},"darkRounds":"1","roundsDug":"0","reason":0,"mode":0,"signature":"$SIG"}"""
        }
        val parsed = IndexerHaulClient.parseShifts(shiftList(shifts = "[$many]"), rig)
        assertEquals(IndexerHaulClient.MAX_SHIFTS, parsed.size)
        assertEquals(5_000uL, parsed.first().shiftId)
        assertEquals((5_000 - IndexerHaulClient.MAX_SHIFTS + 1).toULong(), parsed.last().shiftId)
    }

    @Test
    fun `the indexer client refuses cleartext endpoints`() {
        assertThrows(IllegalArgumentException::class.java) { OkHttpJsonHttp("http://indexer.example", tls.client) }
    }
}
