package xyz.headsdown.core.chain.swap

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.headsdown.core.chain.FakeTransport
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.accounts.AddressLookupTables
import xyz.headsdown.core.chain.rpc.AccountInfo
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.chain.tx.AddressLookupTable
import xyz.headsdown.core.chain.tx.Instruction

/**
 * Live answers of Jupiter's public Swap API captured on 2026-10-01 (`resources/jupiter`): a
 * quote, the swap instructions for it and the mainnet address lookup tables they name, taken
 * together so they are self-consistent. Public market data and public test keys only.
 */
class JupiterFixture(name: String) {
    private val root: JsonObject = load(name)
    val user: Pubkey = Pubkey.fromBase58(root["request"]!!.jsonObject["user"]!!.jsonPrimitive.content)
    val slot: Long = root["slot"]!!.jsonPrimitive.content.toLong()
    val quoteBody: String = root["quote"].toString()
    val swapBody: String = root["swap_instructions"].toString()
    private val quoteJson = root["quote"]!!.jsonObject

    val request = SwapRequest(
        inputMint = Pubkey.fromBase58(quoteJson["inputMint"]!!.jsonPrimitive.content),
        outputMint = Pubkey.fromBase58(quoteJson["outputMint"]!!.jsonPrimitive.content),
        inAmount = quoteJson["inAmount"]!!.jsonPrimitive.content.toULong(),
        slippageBps = quoteJson["slippageBps"]!!.jsonPrimitive.content.toInt(),
        // The route-size hint is in the captured query string, not echoed in the answer.
        maxAccounts = Regex("maxAccounts=(\\d+)").find(root["request"]!!.jsonObject["quote_url"]!!.jsonPrimitive.content)?.groupValues?.get(1)?.toInt(),
    )
    val quote: SwapQuote get() = JupiterSwapProvider.parseQuote(quoteBody, request)
    val instructions: SwapInstructions get() = JupiterSwapProvider.parseInstructions(swapBody)

    /** The lookup-table accounts as mainnet returned them, by address. */
    val tableAccounts: List<Pair<Pubkey, AccountInfo>> = root["lookup_tables"]!!.jsonArray.map { it.jsonObject }.map { t ->
        Pubkey.fromBase58(t["address"]!!.jsonPrimitive.content) to account(t["value"].toString())
    }
    val tables: List<AddressLookupTable> get() = tableAccounts.map { (address, info) -> AddressLookupTables.decode(address, info) }

    /** The same instructions with one replaced or added, for refusal tests. */
    fun instructionsWith(
        setup: List<Instruction> = instructions.setup,
        swap: Instruction = instructions.swap,
        cleanup: List<Instruction> = instructions.cleanup,
    ) = SwapInstructions(setup, swap, cleanup, instructions.lookupTables, instructions.computeUnitLimit)

    companion object {
        val solOre: JupiterFixture by lazy { JupiterFixture("sol_ore") }
        val skrSol: JupiterFixture by lazy { JupiterFixture("skr_sol") }

        /** The same SKR to SOL swap asked with `maxAccounts=32`: a two-venue route that leaves room for a gift. */
        val skrSolGift: JupiterFixture by lazy { JupiterFixture("skr_sol_gift") }

        fun load(name: String): JsonObject =
            Json.parseToJsonElement(JupiterFixture::class.java.getResource("/jupiter/$name.json")!!.readText()).jsonObject

        /** An RPC account `value`, decoded through the real RPC parsing path. */
        fun account(valueJson: String): AccountInfo = runBlocking {
            SolanaJsonRpc(FakeTransport.result("""{"context":{"slot":1},"value":$valueJson}""")).getAccountInfo(Pubkey.DEFAULT)!!
        }
    }
}

/** One of the four exact-in route instructions, as Jupiter answered for the same SOL→ORE swap. */
class JupiterVariant(val name: String, val user: Pubkey, private val json: JsonObject) {
    private val quoteJson = json["quote"]!!.jsonObject
    val request = SwapRequest(
        inputMint = Pubkey.fromBase58(quoteJson["inputMint"]!!.jsonPrimitive.content),
        outputMint = Pubkey.fromBase58(quoteJson["outputMint"]!!.jsonPrimitive.content),
        inAmount = quoteJson["inAmount"]!!.jsonPrimitive.content.toULong(),
        slippageBps = quoteJson["slippageBps"]!!.jsonPrimitive.content.toInt(),
    )
    val quote: SwapQuote get() = JupiterSwapProvider.parseQuote(quoteJson.toString(), request)
    val instructions: SwapInstructions get() = JupiterSwapProvider.parseInstructions(json["swap_instructions"].toString())

    companion object {
        val all: List<JupiterVariant> by lazy {
            val root = JupiterFixture.load("variants")
            val user = Pubkey.fromBase58(root["user"]!!.jsonPrimitive.content)
            root["variants"]!!.jsonArray.map { it.jsonObject }.map { JupiterVariant(it["name"]!!.jsonPrimitive.content, user, it) }
        }
    }
}

/**
 * The swap provider for tests ("a fake for tests"): answers from memory, records what it was
 * asked, and can be told to have no route or to fail.
 */
class FakeSwapProvider(
    private val outPerIn: Pair<ULong, ULong> = 120uL to 1uL,
    private val buildInstructions: (SwapQuote, Pubkey) -> SwapInstructions,
) : SwapProvider {
    val quoteRequests = mutableListOf<SwapRequest>()
    val instructionRequests = mutableListOf<Pair<SwapQuote, Pubkey>>()

    /** null: "no route". */
    var nextOutAmount: ((SwapRequest) -> ULong?)? = null
    var failQuote: SwapProviderException.Reason? = null
    var failInstructions: SwapProviderException.Reason? = null

    override suspend fun quote(request: SwapRequest): SwapQuote? {
        quoteRequests += request
        failQuote?.let { throw SwapProviderException(it) }
        val out = nextOutAmount.let { f -> if (f != null) f(request) else request.inAmount * outPerIn.first / outPerIn.second } ?: return null
        return SwapQuote(request, out, priceImpactBps = 3, route = listOf("Fake Venue"), platformFeeBps = 0, providerPayload = "{}")
    }

    override suspend fun instructions(quote: SwapQuote, user: Pubkey): SwapInstructions {
        instructionRequests += quote to user
        failInstructions?.let { throw SwapProviderException(it) }
        return buildInstructions(quote, user)
    }
}
