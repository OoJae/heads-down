package xyz.headsdown.core.chain.rpc

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.FakeTransport
import xyz.headsdown.core.chain.Fixtures
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.wallet.Base58
import xyz.headsdown.core.wallet.Commitment
import xyz.headsdown.core.wallet.ConfirmationOutcome
import xyz.headsdown.core.wallet.ConfirmationPoller
import java.util.Base64

class SolanaJsonRpcTest {

    private val sig = Base58.encode(ByteArray(64) { 7 })

    @Test
    fun `getAccountInfo parses a real mainnet Board read`() = runTest {
        val transport = FakeTransport.result(Fixtures.accountResult("ore_board"))
        val info = SolanaJsonRpc(transport).getAccountInfo(Ore.BOARD)!!
        assertEquals(Ore.PROGRAM_ID, info.owner)
        assertEquals(40, info.size)
        assertEquals(3_205_636uL, info.lamports)
        assertFalse(info.executable)
        // Request shape: base58 address, base64 encoding, commitment.
        val req = transport.requests.single()
        assertEquals("2.0", req["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals("getAccountInfo", transport.lastMethod)
        val params = req["params"]!!.jsonArray
        assertEquals(Ore.BOARD.toBase58(), params[0].jsonPrimitive.content)
        assertEquals("base64", params[1].jsonObject["encoding"]!!.jsonPrimitive.content)
        assertEquals("confirmed", params[1].jsonObject["commitment"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing accounts are null, u64 rentEpoch does not overflow`() = runTest {
        assertNull(SolanaJsonRpc(FakeTransport.result("""{"context":{"slot":1},"value":null}""")).getAccountInfo(Ore.BOARD))
        // The fixture carries rentEpoch = u64::MAX, which does not fit a Long; parsing must not care.
        assertEquals(3_205_636uL, Fixtures.account("ore_board").lamports)
    }

    @Test
    fun `getMultipleAccounts keeps order and nulls, and checks the count`() = runTest {
        val board = Fixtures.json("ore_board")["value"].toString()
        val rpc = SolanaJsonRpc(FakeTransport.result("""{"context":{"slot":1},"value":[$board,null]}"""))
        val out = rpc.getMultipleAccounts(listOf(Ore.BOARD, Ore.TREASURY))
        assertEquals(40, out[0]!!.size)
        assertNull(out[1])
        val short = SolanaJsonRpc(FakeTransport.result("""{"context":{"slot":1},"value":[null]}"""))
        assertThrows(RpcProtocolException::class.java) { kotlinx.coroutines.runBlocking { short.getMultipleAccounts(listOf(Ore.BOARD, Ore.TREASURY)) } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { rpc.getMultipleAccounts(emptyList()) } }
    }

    @Test
    fun `getLatestBlockhash decodes hash and height`() = runTest {
        val hash = Base58.encode(ByteArray(32) { it.toByte() })
        val rpc = SolanaJsonRpc(FakeTransport.result("""{"context":{"slot":451729320},"value":{"blockhash":"$hash","lastValidBlockHeight":430000150}}"""))
        val bh = rpc.getLatestBlockhash()
        assertArrayEquals(ByteArray(32) { it.toByte() }, bh.blockhash)
        assertEquals(430_000_150L, bh.lastValidBlockHeight)
        assertEquals(451_729_320L, bh.contextSlot)
    }

    @Test
    fun `sendTransaction posts base64 and validates the returned signature`() = runTest {
        val transport = FakeTransport.result("\"$sig\"")
        val tx = byteArrayOf(1, 2, 3, 4)
        assertEquals(sig, SolanaJsonRpc(transport).sendTransaction(tx))
        val params = transport.requests.single()["params"]!!.jsonArray
        assertArrayEquals(tx, Base64.getDecoder().decode(params[0].jsonPrimitive.content))
        assertEquals("base64", params[1].jsonObject["encoding"]!!.jsonPrimitive.content)
        assertEquals("false", params[1].jsonObject["skipPreflight"]!!.jsonPrimitive.content)
        val bad = SolanaJsonRpc(FakeTransport.result("\"not-a-signature\""))
        assertThrows(RpcProtocolException::class.java) { kotlinx.coroutines.runBlocking { bad.sendTransaction(tx) } }
    }

    @Test
    fun `signature statuses carry err as raw JSON and null when unknown`() = runTest {
        val rpc = SolanaJsonRpc(
            FakeTransport.result(
                """{"context":{"slot":9},"value":[
                   {"slot":100,"confirmations":null,"err":null,"confirmationStatus":"finalized","status":{"Ok":null}},
                   {"slot":101,"confirmations":1,"err":{"InstructionError":[3,{"Custom":7}]},"confirmationStatus":"confirmed"},
                   null]}""",
            ),
        )
        val out = rpc.getSignatureStatuses(listOf(sig, sig, sig))
        assertEquals(Commitment.FINALIZED, out[0]!!.confirmationStatus)
        assertNull(out[0]!!.err)
        assertEquals("""{"InstructionError":[3,{"Custom":7}]}""", out[1]!!.err)
        assertNull(out[2])
    }

    @Test
    fun `plugs into the existing confirmation poller`() = runTest {
        // Status confirmed with err == null -> Confirmed; the poller never calls getBlockHeight.
        val rpc = SolanaJsonRpc(FakeTransport.result("""{"context":{"slot":9},"value":[{"slot":5,"confirmations":0,"err":null,"confirmationStatus":"confirmed"}]}"""))
        val outcome = ConfirmationPoller(rpc).await(sig, lastValidBlockHeight = 100)
        assertEquals(ConfirmationOutcome.Confirmed(sig, 5, Commitment.CONFIRMED), outcome)
        // An on-chain error is never success.
        val failed = SolanaJsonRpc(FakeTransport.result("""{"context":{"slot":9},"value":[{"slot":5,"err":{"InstructionError":[0,"Custom"]},"confirmationStatus":"confirmed"}]}"""))
        assertTrue(ConfirmationPoller(failed).await(sig, 100) is ConfirmationOutcome.FailedOnChain)
    }

    @Test
    fun `getBalance, getBlockHeight and getProgramAccounts with memcmp`() = runTest {
        assertEquals(179_977_533_428uL, SolanaJsonRpc(FakeTransport.result("""{"context":{"slot":1},"value":179977533428}""")).getBalance(Ore.TREASURY))
        assertEquals(42L, SolanaJsonRpc(FakeTransport.result("42")).getBlockHeight(Commitment.CONFIRMED))

        val automation = Fixtures.json("ore_automation")
        val transport = FakeTransport.result("""[{"pubkey":"${automation["address"]!!.jsonPrimitive.content}","account":${automation["value"]}}]""")
        val authority = Pubkey.fromBase58("2Yf3PNJXbdELHD1jPWSaXTWMYQLuH3jYzsAq4L5VWn3X")
        val out = SolanaJsonRpc(transport).getProgramAccounts(
            Ore.PROGRAM_ID,
            listOf(AccountFilter.DataSize(160), AccountFilter.Memcmp(16, authority.bytes)),
        )
        assertEquals(Fixtures.address("ore_automation"), out.single().pubkey)
        assertEquals(160, out.single().account.size)
        val filters = transport.requests.single()["params"]!!.jsonArray[1].jsonObject["filters"] as JsonArray
        assertEquals("160", filters[0].jsonObject["dataSize"]!!.jsonPrimitive.content)
        val memcmp = filters[1].jsonObject["memcmp"]!!.jsonObject
        assertEquals("16", memcmp["offset"]!!.jsonPrimitive.content)
        assertEquals(authority.toBase58(), memcmp["bytes"]!!.jsonPrimitive.content)
        assertEquals("base58", memcmp["encoding"]!!.jsonPrimitive.content)
    }

    @Test
    fun `JSON-RPC errors surface as RpcErrorException with truncated detail`() = runTest {
        val long = "x".repeat(5_000)
        val rpc = SolanaJsonRpc(FakeTransport { req -> """{"jsonrpc":"2.0","id":${req["id"]},"error":{"code":-32002,"message":"$long"}}""" })
        val e = runCatching { rpc.getBlockHeight(Commitment.CONFIRMED) }.exceptionOrNull() as RpcErrorException
        assertEquals(-32002L, e.code)
        assertEquals(200, e.detail.length)
        assertFalse("the message never carries server text", e.message!!.contains("xxx"))
    }

    @Test
    fun `hostile or malformed responses fail closed with a typed error`() = runTest {
        val cases = mapOf(
            "not json" to { _: String -> "<html>" },
            "array root" to { _: String -> "[]" },
            "wrong id" to { _: String -> """{"jsonrpc":"2.0","id":999999,"result":42}""" },
            "missing id" to { _: String -> """{"jsonrpc":"2.0","result":42}""" },
            "no result" to { id: String -> """{"jsonrpc":"2.0","id":$id}""" },
            "string height" to { id: String -> """{"jsonrpc":"2.0","id":$id,"result":"42"}""" },
            "fractional height" to { id: String -> """{"jsonrpc":"2.0","id":$id,"result":4.2}""" },
        )
        for ((name, body) in cases) {
            val rpc = SolanaJsonRpc(FakeTransport { req -> body(req["id"].toString()) })
            val e = runCatching { rpc.getBlockHeight(Commitment.CONFIRMED) }.exceptionOrNull()
            assertTrue("$name -> $e", e is RpcProtocolException)
        }
        val accountCases = listOf(
            """{"context":{"slot":1},"value":{"data":["AA==","base58"],"executable":false,"lamports":1,"owner":"${Ore.PROGRAM_ID}"}}""",
            """{"context":{"slot":1},"value":{"data":["!!!","base64"],"executable":false,"lamports":1,"owner":"${Ore.PROGRAM_ID}"}}""",
            """{"context":{"slot":1},"value":{"data":["AA==","base64"],"executable":false,"lamports":-1,"owner":"${Ore.PROGRAM_ID}"}}""",
            """{"context":{"slot":1},"value":{"data":["AA==","base64"],"executable":"no","lamports":1,"owner":"${Ore.PROGRAM_ID}"}}""",
            """{"context":{"slot":1},"value":{"data":["AA==","base64"],"executable":false,"lamports":1,"owner":"short"}}""",
            """{"context":{"slot":1},"value":{"data":["AA==","base64"],"executable":false,"lamports":1}}""",
            """{"context":{"slot":1},"value":[]}""",
        )
        for (c in accountCases) {
            val e = runCatching { SolanaJsonRpc(FakeTransport.result(c)).getAccountInfo(Ore.BOARD) }.exceptionOrNull()
            assertTrue("$c -> $e", e is RpcProtocolException)
        }
    }

    @Test
    fun `an oversized base58 field is refused before decoding`() = runTest {
        // Base58 decoding is quadratic; a hostile node must not be able to stall the client.
        val huge = "2".repeat(200_000)
        val started = System.nanoTime()
        val e = runCatching { SolanaJsonRpc(FakeTransport.result("\"$huge\"")).sendTransaction(byteArrayOf(1)) }.exceptionOrNull()
        assertTrue("$e", e is RpcProtocolException)
        assertTrue("fails fast", System.nanoTime() - started < 2_000_000_000L)
    }

    @Test
    fun `request ids increase so replies cannot be cross-wired`() = runTest {
        val transport = FakeTransport.result("1")
        val rpc = SolanaJsonRpc(transport)
        repeat(3) { rpc.getBlockHeight(Commitment.CONFIRMED) }
        val ids = transport.requests.map { it["id"]!!.jsonPrimitive.content.toLong() }
        assertEquals(ids.sorted().distinct(), ids)
    }

    @Test
    fun `getTokenAccountsByOwner asks by token program and returns keyed accounts`() = runTest {
        // The real Token-2022 accounts of a mainnet wallet (fixtures/skr_miner.json).
        val accounts = Fixtures.json("skr_miner")["token_2022_accounts"].toString()
        val owner = Pubkey.fromBase58(Fixtures.json("skr_miner")["owner"]!!.jsonPrimitive.content)
        val transport = FakeTransport.result("""{"context":{"slot":452137103},"value":$accounts}""")
        val out = SolanaJsonRpc(transport).getTokenAccountsByOwner(owner, xyz.headsdown.core.chain.WellKnown.TOKEN_2022)
        assertEquals(2, out.size)
        assertEquals("5q7eoUGbdHjC7Fy8QfNDbUr6HJGxd7FLa2F8mU3nuJmB", out[0].pubkey.toBase58())
        assertEquals(170, out[0].account.size)
        assertEquals(xyz.headsdown.core.chain.WellKnown.TOKEN_2022, out[0].account.owner)
        assertEquals("getTokenAccountsByOwner", transport.lastMethod)
        val params = transport.requests.single()["params"]!!.jsonArray
        assertEquals(owner.toBase58(), params[0].jsonPrimitive.content)
        assertEquals("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb", params[1].jsonObject["programId"]!!.jsonPrimitive.content)
        assertEquals("base64", params[2].jsonObject["encoding"]!!.jsonPrimitive.content)
        // An empty wallet, and a malformed entry.
        assertTrue(SolanaJsonRpc(FakeTransport.result("""{"context":{"slot":1},"value":[]}""")).getTokenAccountsByOwner(owner, Ore.PROGRAM_ID).isEmpty())
        val bad = SolanaJsonRpc(FakeTransport.result("""{"context":{"slot":1},"value":[{"pubkey":"x","account":null}]}"""))
        assertTrue(runCatching { bad.getTokenAccountsByOwner(owner, Ore.PROGRAM_ID) }.exceptionOrNull() is RpcProtocolException)
    }

    @Test
    fun `simulateTransaction is unsigned, returns post-state accounts and never throws for a failing transaction`() = runTest {
        val board = Fixtures.json("ore_board")["value"].toString()
        val ok = FakeTransport.result(
            """{"context":{"slot":7},"value":{"err":null,"logs":["Program log: a","Program log: b"],"accounts":[$board,null],"unitsConsumed":123456,"returnData":null}}""",
        )
        val result = SolanaJsonRpc(ok).simulateTransaction(byteArrayOf(1, 2, 3), listOf(Ore.BOARD, Ore.TREASURY))
        assertTrue(result.succeeded)
        assertNull(result.err)
        assertEquals(listOf("Program log: a", "Program log: b"), result.logs)
        assertEquals(40, result.accounts[0]!!.size)
        assertNull(result.accounts[1])
        assertEquals(123_456L, result.unitsConsumed)
        val params = ok.requests.single()["params"]!!.jsonArray
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), params[0].jsonPrimitive.content)
        val config = params[1].jsonObject
        // The wallet has not signed yet: signatures are not verified, and the real blockhash is kept.
        assertEquals("false", config["sigVerify"]!!.jsonPrimitive.content)
        assertEquals("false", config["replaceRecentBlockhash"]!!.jsonPrimitive.content)
        assertEquals("base64", config["encoding"]!!.jsonPrimitive.content)
        assertEquals(listOf(Ore.BOARD.toBase58(), Ore.TREASURY.toBase58()), config["accounts"]!!.jsonObject["addresses"]!!.jsonArray.map { it.jsonPrimitive.content })

        // A transaction that would fail: err is carried (bounded), nothing is thrown.
        val failing = SolanaJsonRpc(FakeTransport.result("""{"context":{"slot":7},"value":{"err":{"InstructionError":[2,{"Custom":6001}]},"logs":null,"accounts":null}}"""))
        val failed = failing.simulateTransaction(byteArrayOf(9))
        assertFalse(failed.succeeded)
        assertEquals("""{"InstructionError":[2,{"Custom":6001}]}""", failed.err)
        assertTrue(failed.logs.isEmpty() && failed.accounts.isEmpty())
        assertNull(failed.unitsConsumed)

        // Fewer accounts than asked for is a protocol error; logs are bounded.
        val short = SolanaJsonRpc(FakeTransport.result("""{"context":{"slot":7},"value":{"err":null,"logs":[],"accounts":[null]}}"""))
        assertTrue(runCatching { short.simulateTransaction(byteArrayOf(1), listOf(Ore.BOARD, Ore.TREASURY)) }.exceptionOrNull() is RpcProtocolException)
        val noisy = "\"" + "x".repeat(5_000) + "\""
        val many = SolanaJsonRpc(FakeTransport.result("""{"context":{"slot":7},"value":{"err":null,"logs":[${List(400) { noisy }.joinToString(",")}]}}"""))
        val bounded = many.simulateTransaction(byteArrayOf(1))
        assertEquals(256, bounded.logs.size)
        assertEquals(400, bounded.logs[0].length)
    }

    @Test
    fun `getMinimumBalanceForRentExemption returns lamports for a size`() = runTest {
        val transport = FakeTransport.result("2004480")
        assertEquals(2_004_480uL, SolanaJsonRpc(transport).getMinimumBalanceForRentExemption(160))
        assertEquals("160", transport.requests.single()["params"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue(runCatching { SolanaJsonRpc(FakeTransport.result("\"2004480\"")).getMinimumBalanceForRentExemption(160) }.exceptionOrNull() is RpcProtocolException)
    }

    @Test
    fun `argument limits`() {
        val rpc = SolanaJsonRpc(FakeTransport.result("null"))
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { rpc.simulateTransaction(ByteArray(0)) } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { rpc.simulateTransaction(byteArrayOf(1), List(17) { Ore.BOARD }) } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { rpc.getMinimumBalanceForRentExemption(-1) } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { rpc.getMultipleAccounts(List(101) { Ore.BOARD }) } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { rpc.getSignatureStatuses(emptyList()) } }
        assertThrows(IllegalArgumentException::class.java) { AccountFilter.Memcmp(-1, byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { AccountFilter.Memcmp(0, ByteArray(0)) }
    }
}
