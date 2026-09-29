package xyz.headsdown.core.chain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.headsdown.core.chain.rpc.AccountInfo
import xyz.headsdown.core.chain.rpc.JsonRpcTransport
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc

/** Scripted JSON-RPC transport: records each request and answers with [reply]. */
class FakeTransport(private val reply: (JsonObject) -> String) : JsonRpcTransport {
    val requests = mutableListOf<JsonObject>()

    override suspend fun post(body: String): String {
        val request = Json.parseToJsonElement(body).jsonObject
        requests += request
        return reply(request)
    }

    val lastMethod: String get() = requests.last()["method"]!!.jsonPrimitive.content

    companion object {
        /** Answers every request with `result` (raw JSON), echoing the request id. */
        fun result(resultJson: String) = FakeTransport { req -> """{"jsonrpc":"2.0","id":${req["id"]},"result":$resultJson}""" }

        /** Answers requests in order with each raw `result` JSON. */
        fun results(vararg resultJson: String): FakeTransport {
            val queue = ArrayDeque(resultJson.toList())
            return FakeTransport { req -> """{"jsonrpc":"2.0","id":${req["id"]},"result":${queue.removeFirst()}}""" }
        }
    }
}

/** Real mainnet account reads (2026-09-29) under `src/test/resources/fixtures/`. */
object Fixtures {
    fun json(name: String): JsonObject =
        Json.parseToJsonElement(javaClass.getResource("/fixtures/$name.json")!!.readText()).jsonObject

    fun address(name: String): Pubkey = Pubkey.fromBase58(json(name)["address"]!!.jsonPrimitive.content)

    /** The fixture's `value` wrapped as a getAccountInfo result. */
    fun accountResult(name: String): String = """{"context":{"slot":1},"value":${json(name)["value"]}}"""

    /** Decoded through the real RPC parsing path. */
    suspend fun account(name: String): AccountInfo =
        SolanaJsonRpc(FakeTransport.result(accountResult(name))).getAccountInfo(address(name))!!

    fun accountWith(info: AccountInfo, data: ByteArray = info.data, owner: Pubkey = info.owner) =
        AccountInfo(info.lamports, owner, data, info.executable)
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun hexBytes(s: String): ByteArray {
    val clean = s.replace(" ", "")
    require(clean.length % 2 == 0)
    return ByteArray(clean.length / 2) { clean.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
