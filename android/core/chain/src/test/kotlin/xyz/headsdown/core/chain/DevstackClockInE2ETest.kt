package xyz.headsdown.core.chain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import xyz.headsdown.core.chain.accounts.HeadsDownAccounts
import xyz.headsdown.core.chain.accounts.OreAccounts
import xyz.headsdown.core.chain.accounts.RigAccount
import xyz.headsdown.core.chain.clockin.ClockInRequest
import xyz.headsdown.core.chain.clockin.ClockInService
import xyz.headsdown.core.chain.rpc.JsonRpcTransport
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.chain.uplink.HeartbeatJson
import xyz.headsdown.core.keys.CounterStore
import xyz.headsdown.core.keys.DerSigner
import xyz.headsdown.core.keys.P256
import xyz.headsdown.core.keys.RigCounter
import xyz.headsdown.core.keys.RigMessageSigner
import xyz.headsdown.core.keys.RigSignalState
import xyz.headsdown.core.wallet.ConfirmationOutcome
import xyz.headsdown.core.wallet.ConfirmationPoller
import xyz.headsdown.core.wallet.WalletCapabilities
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * OPT-IN end-to-end run against a live devstack (`scripts/devstack/up.sh`): the phone's own code
 * clocks in on the local mainnet fork and the real hd-crank digs its heartbeat.
 *
 *   HD_DEVSTACK_RPC=http://127.0.0.1:8899 HD_DEVSTACK_CRANK_WS=ws://127.0.0.1:8787/ws \
 *     ./gradlew :core:chain:testDebugUnitTest --tests '*DevstackClockInE2ETest*'
 *
 * Skipped (not failed) without those variables. What it proves on the real validator and program:
 * the composer's single clock-in transaction (ORE automate with `fee = Config.executor_fee`,
 * register_rig, set_caps with the executor fee inside every cap, arm_shift with the ORE Board)
 * lands; a contract-A heartbeat frame signed exactly like the Keystore key is accepted by the
 * crank; and the crank's dig places the WHOLE planned dig (the fee is reserved inside `cap_round`,
 * not taken out of the dig), debiting dig + fee.
 */
class DevstackClockInE2ETest {

    private val rpcUrl: String? = System.getenv("HD_DEVSTACK_RPC")
    private val crankUrl: String? = System.getenv("HD_DEVSTACK_CRANK_WS")
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    /** JSON-RPC over plain HTTP, loopback only (the app's equivalent lives in src/localdev). */
    private inner class LoopbackRpc(url: String) : JsonRpcTransport {
        private val target = url.toHttpUrl().also { require(it.host == "127.0.0.1" || it.host == "localhost") }

        override suspend fun post(body: String): String = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(target).post(body.toRequestBody("application/json".toMediaType())).build())
                .execute().use { it.body.string() }
        }
    }

    @Test(timeout = 900_000)
    fun `the phone's clock-in lands and the crank digs its heartbeat with the fee inside the caps`() = runBlocking {
        assumeTrue("set HD_DEVSTACK_RPC and HD_DEVSTACK_CRANK_WS to run against a devstack", rpcUrl != null && crankUrl != null)
        val transport = LoopbackRpc(rpcUrl!!)
        val rpc = SolanaJsonRpc(transport)

        // A fresh wallet (Ed25519) and rig key (P-256, signed like Android Keystore's SHA256withECDSA).
        val wallet = Ed25519PrivateKeyParameters(ByteArray(32).also(SecureRandom()::nextBytes), 0)
        val authority = Pubkey(wallet.generatePublicKey().encoded)
        val rigKeys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val p256 = P256.compress(rigKeys.public as ECPublicKey)
        airdrop(transport, rpc, authority, 2_000_000_000L)

        // Clock in: one transaction from the phone's composer, signed by the wallet, sent by the app.
        val config = HeadsDownAccounts.config(HeadsDownProgram.config.address, rpc.getAccountInfo(HeadsDownProgram.config.address)!!)
        val request = ClockInRequest(
            shiftBudgetLamports = 20_000_000uL,
            weeklyBudgetLamports = 140_000_000uL,
            capMaxCostPerOre = 2_000_000_000uL, // the fork's ema_ev decays; keep the gate open
            planMaxEvCostPerOre = 2_000_000_000uL,
            windowSeconds = 3_600,
            digLamports = 1_000_000uL,
            splitTiles = 4,
            leaseRounds = 1,
        )
        val prepared = ClockInService(rpc).prepare(authority, p256, request, WalletCapabilities.LEGACY_ONLY)!!
        val unsigned = prepared.transactions.single()
        val message = unsigned.copyOfRange(1 + 64, unsigned.size)
        val signature = Ed25519Signer().apply { init(true, wallet); update(message, 0, message.size) }.generateSignature()
        val signed = byteArrayOf(1) + signature + message
        val sent = rpc.sendTransaction(signed)
        val outcome = ConfirmationPoller(rpc, pollIntervalMillis = 500).await(sent, prepared.lastValidBlockHeight)
        assertTrue("clock-in: $outcome", outcome is ConfirmationOutcome.Confirmed)
        println("clock-in landed: $sent (${unsigned.size} bytes, ${prepared.plan.instructions.size} instructions)")

        val rigAddress = HeadsDownProgram.rig(authority).address
        var rig = readRig(rpc, rigAddress)
        assertEquals(RigSignalState.ARMED, rig.state)
        assertEquals(1uL, rig.shiftId)
        assertTrue(rig.shiftOpen)
        assertEquals(1_000_000uL + config.executorFee, rig.capRound)
        assertEquals(20_000_000uL + 20uL * config.executorFee, rig.capShift)
        val automation = OreAccounts.automation(Ore.automation(authority).address, rpc.getAccountInfo(Ore.automation(authority).address)!!)
        assertEquals(config.executorFee, automation.fee)
        assertEquals(HeadsDownProgram.executor.address, automation.executor)
        assertEquals(250_000uL, automation.amount)
        val balanceBefore = automation.balance

        // Heartbeats: contract-A frames to the real crank, one per new ORE round, until it digs.
        var counter = 0uL
        val signer = RigMessageSigner(
            DerSigner { m -> Signature.getInstance("SHA256withECDSA").run { initSign(rigKeys.private); update(m); sign() } },
            p256,
            RigCounter(object : CounterStore {
                override fun load() = counter
                override fun store(value: ULong): Boolean { counter = value; return true }
            }),
        )
        val replies = LinkedBlockingQueue<String>()
        val socket = http.newWebSocket(
            Request.Builder().url(crankUrl!!.replaceFirst("ws://", "http://")).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { replies.put(text) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { replies.put("FAILURE ${t.javaClass.simpleName}") }
            },
        )
        var lastRound: ULong? = null
        var acked = 0
        val deadline = System.currentTimeMillis() + 10 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            val board = OreAccounts.board(Ore.BOARD, rpc.getAccountInfo(Ore.BOARD)!!)
            if (board.roundId != lastRound) {
                lastRound = board.roundId
                val hb = signer.heartbeat(HeadsDownProgram.ID.bytes, rigAddress.bytes, rig.shiftId, board.roundId, 1)
                assertTrue(socket.send(HeartbeatJson.encode(hb)))
                val reply = replies.poll(15, TimeUnit.SECONDS) ?: error("no reply from the crank")
                val ack = Json.parseToJsonElement(reply).jsonObject
                println("heartbeat #${hb.payload.counter} for round ${board.roundId}: crank replied $reply")
                assertEquals("ack", ack.text("type"))
                assertEquals("${hb.payload.counter}", ack.text("counter"))
                // Contract A answers ok/reason; the pre-contract crank answered status.
                assertTrue(reply, ack.text("ok") == "true" || ack.text("status") == "accepted")
                acked++
            }
            rig = readRig(rpc, rigAddress)
            if (rig.lifetimeRoundsDug >= 1uL) break
            delay(2_000)
        }
        socket.close(1000, null)
        assertTrue("the crank never dug the rig (acked $acked heartbeats)", rig.lifetimeRoundsDug >= 1uL)

        // The whole 0.001 SOL dig was placed: the executor fee came from inside cap_round.
        assertEquals(1_000_000uL, rig.lifetimeLamportsDeployed)
        assertEquals(1_000_000uL + config.executorFee, rig.spentShift)
        val after = OreAccounts.automation(Ore.automation(authority).address, rpc.getAccountInfo(Ore.automation(authority).address)!!)
        assertEquals(balanceBefore - 1_000_000uL - config.executorFee, after.balance)
        println("crank dug round ${rig.lastDugRound}: 1000000 lamports on squares, debit ${balanceBefore - after.balance} (fee ${config.executorFee})")
    }

    private suspend fun readRig(rpc: SolanaJsonRpc, address: Pubkey): RigAccount =
        HeadsDownAccounts.rig(address, rpc.getAccountInfo(address)!!)

    private suspend fun airdrop(transport: JsonRpcTransport, rpc: SolanaJsonRpc, to: Pubkey, lamports: Long) {
        transport.post("""{"jsonrpc":"2.0","id":1,"method":"requestAirdrop","params":["$to",$lamports]}""")
        repeat(120) {
            if (rpc.getBalance(to) >= lamports.toULong()) return
            delay(500)
        }
        error("airdrop did not land")
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
