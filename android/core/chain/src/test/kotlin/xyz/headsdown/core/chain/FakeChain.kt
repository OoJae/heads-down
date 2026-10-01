package xyz.headsdown.core.chain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import xyz.headsdown.core.chain.rpc.AccountInfo
import xyz.headsdown.core.chain.rpc.JsonRpcTransport
import xyz.headsdown.core.chain.rpc.OkHttpJsonRpcTransport
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.wallet.Base58
import java.io.Closeable
import java.util.Base64

/**
 * An in-memory cluster for service tests: a set of accounts answered through the JSON-RPC
 * methods the app uses, so a whole flow (read, compose, simulate) runs against it. Requests are
 * recorded. It answers through [transport] (in process) or through [ChainServer] (a real HTTPS
 * round trip through OkHttp and MockWebServer).
 */
class FakeChain {
    val accounts = LinkedHashMap<Pubkey, AccountInfo>()
    var slot: Long = 451_700_010
    var blockhash: ByteArray = ByteArray(32) { 7 }
    var lastValidBlockHeight: Long = 5_000
    val requests = mutableListOf<JsonObject>()

    /** Transactions handed to `simulateTransaction`, in order. */
    val simulated = mutableListOf<ByteArray>()

    /**
     * What a simulated transaction does: returns the error JSON (null = success) and may change
     * [accounts] to the post-state it wants reported. Default: success, nothing changes.
     */
    var onSimulate: (transaction: ByteArray) -> String? = { null }

    fun put(address: Pubkey, owner: Pubkey, data: ByteArray, lamports: Long = 1_000_000): FakeChain = apply {
        accounts[address] = AccountInfo(lamports.toULong(), owner, data, executable = false)
    }

    fun wallet(address: Pubkey, lamports: Long): FakeChain = put(address, WellKnown.SYSTEM_PROGRAM, ByteArray(0), lamports)

    fun remove(address: Pubkey): FakeChain = apply { accounts.remove(address) }

    val methods: List<String> get() = requests.map { it["method"]!!.jsonPrimitive.content }

    val transport = JsonRpcTransport { body -> reply(Json.parseToJsonElement(body).jsonObject) }

    fun rpc(): SolanaJsonRpc = SolanaJsonRpc(transport)

    /** One JSON-RPC response for [request]. */
    @Synchronized
    fun reply(request: JsonObject): String {
        requests += request
        val id = request["id"]
        val params = request["params"] as? JsonArray ?: JsonArray(emptyList())
        val result = when (val method = request["method"]!!.jsonPrimitive.content) {
            "getAccountInfo" -> context(account(key(params[0].jsonPrimitive.content)))
            "getMultipleAccounts" -> context("[" + params[0].jsonArray.joinToString(",") { account(key(it.jsonPrimitive.content)) } + "]")
            "getBalance" -> context((accounts[key(params[0].jsonPrimitive.content)]?.lamports ?: 0uL).toString())
            "getLatestBlockhash" -> context("""{"blockhash":"${Base58.encode(blockhash)}","lastValidBlockHeight":$lastValidBlockHeight}""")
            "getBlockHeight" -> "100"
            "getMinimumBalanceForRentExemption" -> ((128 + params[0].jsonPrimitive.content.toLong()) * 6_960).toString()
            "getProgramAccounts" -> programAccounts(key(params[0].jsonPrimitive.content), params[1].jsonObject["filters"]?.jsonArray ?: JsonArray(emptyList()))
            "getTokenAccountsByOwner" -> {
                val owner = key(params[0].jsonPrimitive.content)
                val program = key(params[1].jsonObject["programId"]!!.jsonPrimitive.content)
                context(keyed(accounts.filter { (_, a) -> a.owner == program && a.size >= 64 && Pubkey(a.data.copyOfRange(32, 64)) == owner }))
            }
            "simulateTransaction" -> {
                val tx = Base64.getDecoder().decode(params[0].jsonPrimitive.content)
                simulated += tx
                val err = onSimulate(tx)
                val wanted = params[1].jsonObject["accounts"]?.jsonObject?.get("addresses")?.jsonArray?.map { key(it.jsonPrimitive.content) }
                val post = wanted?.let { "[" + it.joinToString(",") { a -> account(a) } + "]" } ?: "null"
                context("""{"err":${err ?: "null"},"logs":[],"accounts":$post,"unitsConsumed":150000}""")
            }
            else -> error("FakeChain does not answer $method")
        }
        return """{"jsonrpc":"2.0","id":$id,"result":$result}"""
    }

    private fun key(text: String) = Pubkey.fromBase58(text)

    private fun context(value: String) = """{"context":{"slot":$slot},"value":$value}"""

    private fun account(address: Pubkey): String = accounts[address]?.let(::json) ?: "null"

    private fun json(a: AccountInfo): String =
        """{"data":["${Base64.getEncoder().encodeToString(a.data)}","base64"],"executable":false,"lamports":${a.lamports},"owner":"${a.owner}","rentEpoch":18446744073709551615,"space":${a.size}}"""

    private fun keyed(found: Map<Pubkey, AccountInfo>): String =
        "[" + found.entries.joinToString(",") { (k, a) -> """{"pubkey":"$k","account":${json(a)}}""" } + "]"

    private fun programAccounts(program: Pubkey, filters: JsonArray): String {
        val found = accounts.filter { (_, a) ->
            a.owner == program && filters.all { f ->
                val o = f.jsonObject
                val size = o["dataSize"]?.jsonPrimitive?.content?.toInt()
                val memcmp = o["memcmp"]?.jsonObject
                when {
                    size != null -> a.size == size
                    memcmp != null -> {
                        val offset = memcmp["offset"]!!.jsonPrimitive.content.toInt()
                        val bytes = Base58.decode(memcmp["bytes"]!!.jsonPrimitive.content)
                        a.size >= offset + bytes.size && a.data.copyOfRange(offset, offset + bytes.size).contentEquals(bytes)
                    }
                    else -> true
                }
            }
        }
        return keyed(found)
    }
}

/**
 * [FakeChain] behind a real HTTPS endpoint: JSON-RPC bodies go through OkHttp, TLS and
 * MockWebServer, as in the app, and come back from the fake chain.
 */
class ChainServer(val chain: FakeChain = FakeChain()) : Closeable {
    private val tls = TlsServer()

    init {
        tls.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = try {
                val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                MockResponse.Builder().addHeader("Content-Type", "application/json").body(chain.reply(body)).build()
            } catch (e: Exception) {
                MockResponse.Builder().code(500).body("fake chain: ${e.message}").build()
            }
        }
    }

    fun rpc(): SolanaJsonRpc = SolanaJsonRpc(OkHttpJsonRpcTransport(tls.url("/"), tls.client))

    val requestCount: Int get() = tls.server.requestCount

    override fun close() = tls.close()
}
