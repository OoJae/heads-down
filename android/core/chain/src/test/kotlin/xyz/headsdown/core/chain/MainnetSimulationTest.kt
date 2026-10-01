package xyz.headsdown.core.chain

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import xyz.headsdown.core.chain.accounts.OreAccounts
import xyz.headsdown.core.chain.accounts.OreClaimMath
import xyz.headsdown.core.chain.accounts.SplTokenAccounts
import xyz.headsdown.core.chain.clockout.BuyLeg
import xyz.headsdown.core.chain.clockout.BuyOutcome
import xyz.headsdown.core.chain.clockout.ClockOutRequest
import xyz.headsdown.core.chain.clockout.ClockOutService
import xyz.headsdown.core.chain.gift.SgtLookup
import xyz.headsdown.core.chain.gift.SkrNames
import xyz.headsdown.core.chain.gift.SkrResolution
import xyz.headsdown.core.chain.ix.OreInstructions
import xyz.headsdown.core.chain.rpc.OkHttpJsonRpcTransport
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc
import xyz.headsdown.core.chain.swap.JupiterSwapProvider
import xyz.headsdown.core.chain.swap.OkHttpSwapHttp
import xyz.headsdown.core.chain.swap.SwapRequest
import xyz.headsdown.core.chain.tx.TransactionBuilder
import xyz.headsdown.core.chain.tx.TxVersion
import xyz.headsdown.core.wallet.WalletCapabilities
import java.util.concurrent.TimeUnit

/**
 * OPT-IN, read-only checks against **mainnet** and Jupiter's live API. Nothing is signed or sent:
 * transactions are only simulated (`sigVerify: false`) for a public miner wallet.
 *
 *   HD_MAINNET_RPC=https://api.mainnet-beta.solana.com HD_JUPITER_URL=https://lite-api.jup.ag/swap/v1 \
 *     ./gradlew :core:chain:testDebugUnitTest --tests '*MainnetSimulationTest*'
 *
 * Skipped (not failed) without `HD_MAINNET_RPC`. What they prove on the real cluster:
 * - the phone's `claim_sol` / `claim_ore` run in the live ORE program, and the claim it predicts
 *   is the claim ORE pays;
 * - a live Jupiter quote and its instructions pass the guard, its lookup tables decode, and the
 *   v0 transaction the phone compiles with them is accepted by the runtime and delivers at least
 *   the quote's minimum to the wallet's own ORE account;
 * - the whole clock-out with the buy leg (ORE claim plus swap) is one transaction that simulates;
 * - a `.skr` name resolves to the wallet that owns it, and that wallet's SGT verifies.
 */
class MainnetSimulationTest {

    private val rpcUrl: String? = System.getenv("HD_MAINNET_RPC")
    private val jupiterUrl: String? = System.getenv("HD_JUPITER_URL")

    /** A public mainnet ORE miner (the authority of `fixtures/ore_miner.json`). */
    private val miner = Pubkey.fromBase58(System.getenv("HD_MAINNET_MINER_AUTHORITY") ?: "2Yf3PNJXbdELHD1jPWSaXTWMYQLuH3jYzsAq4L5VWn3X")

    private val http = OkHttpClient.Builder().readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    private fun rpc() = SolanaJsonRpc(OkHttpJsonRpcTransport(rpcUrl!!, http))
    private val v0 = WalletCapabilities(supportsLegacy = true, supportsV0 = true, maxTransactionsPerRequest = 0)

    @Test(timeout = 120_000)
    fun `claim_sol and claim_ore run in the live ORE program and pay what the phone predicts`() = runBlocking {
        assumeTrue("set HD_MAINNET_RPC to simulate against mainnet", rpcUrl != null)
        val rpc = rpc()
        val minerAddress = Ore.miner(miner).address
        val oreAccount = Ore.account(miner)
        val read = rpc.getMultipleAccounts(listOf(minerAddress, Ore.TREASURY, oreAccount))
        val before = OreAccounts.miner(minerAddress, read[0]!!)
        val treasury = OreAccounts.treasury(Ore.TREASURY, read[1]!!)
        val heldBefore = SplTokenAccounts.userBalance(read[2], Ore.MINT, miner)
        val bps = 100 // 1 %
        val estimate = OreClaimMath.estimate(before, treasury, bps)
        assumeTrue("the miner holds no ORE to claim", estimate.refined + estimate.unrefined > 0uL)

        val blockhash = rpc.getLatestBlockhash()
        val instructions = listOf(OreInstructions.claimSol(miner), OreInstructions.claimOre(miner, bps))
        val tx = TransactionBuilder.unsignedTransaction(TransactionBuilder.compile(miner, instructions, blockhash.blockhash, TxVersion.V0))
        val result = rpc.simulateTransaction(tx, listOf(oreAccount, minerAddress))
        assertNull("claim simulation failed: ${result.err} ${result.logs.takeLast(6)}", result.err)
        assertTrue(result.logs.any { it.contains("Claiming") && it.contains("ORE") })

        val heldAfter = SplTokenAccounts.userBalance(result.accounts[0], Ore.MINT, miner)
        val after = OreAccounts.miner(minerAddress, result.accounts[1]!!)
        val received = heldAfter - heldBefore
        // The Miner's unrefined balance fell by exactly the claimed share.
        assertEquals(before.rewardsOre - estimate.unrefined, after.rewardsOre)
        // What arrived is what the phone predicted (the Treasury factor can tick between the read
        // and the simulation, which only ever adds a few atoms of refined ORE).
        assertTrue("received $received, predicted ${estimate.received}", received >= estimate.received)
        assertTrue("received $received, predicted ${estimate.received}", received - estimate.received <= estimate.received / 1_000uL + 10uL)
        println("mainnet claim_ore(1%): unrefined ${estimate.unrefined}, refined ${estimate.refined}, fee ${estimate.fee}, received $received atoms; ${result.unitsConsumed} CU")
    }

    @Test(timeout = 180_000)
    fun `a live Jupiter buy leg joins the clock-out in one v0 transaction that the cluster accepts`() = runBlocking {
        assumeTrue("set HD_MAINNET_RPC and HD_JUPITER_URL", rpcUrl != null && jupiterUrl != null)
        val rpc = rpc()
        val provider = JupiterSwapProvider(OkHttpSwapHttp(jupiterUrl!!, http))
        val service = ClockOutService(rpc, provider)
        val spend = 15_200_000uL // 0.0152 SOL
        assumeTrue("the wallet cannot cover the swap", rpc.getBalance(miner) > spend + 10_000_000uL)

        val quote = service.quoteBuy(spend)
        assertNotNull("no live quote for SOL to ORE", quote)
        assertEquals(SwapRequest(WellKnown.WRAPPED_SOL_MINT, Ore.MINT, spend, 50), quote!!.request)
        // Leave room for the market to move between the two quotes: agree to 1 % less.
        val agreed = quote.minOutAmount - quote.minOutAmount / 100uL
        val prepared = service.prepare(miner, ClockOutRequest(claimOreBps = 100, buy = BuyLeg(spend, agreed, 50)), v0)
        assertNotNull(prepared)
        val buy = prepared!!.buy
        assertTrue("the live buy leg was skipped: $buy", buy is BuyOutcome.Included)
        prepared.transactions.forEach { assertTrue(it.size <= TransactionBuilder.PACKET_DATA_SIZE) }
        assertFalse(prepared.plan.isEmpty)
        println(
            "mainnet clock-out + buy: ${prepared.transactions.size} transaction(s) of ${prepared.transactions.map { it.size }} bytes, " +
                "own transaction for the swap: ${(buy as BuyOutcome.Included).ownTransaction}, route ${buy.quote.route}, " +
                "min out ${buy.quote.minOutAmount} atoms, impact ${buy.quote.priceImpactBps} bps",
        )
    }

    @Test(timeout = 120_000)
    fun `a dot-skr name resolves to its owner wallet, whose Seeker Genesis Token verifies`() = runBlocking {
        assumeTrue("set HD_MAINNET_RPC to resolve against mainnet", rpcUrl != null)
        val rpc = rpc()
        val name = System.getenv("HD_MAINNET_SKR_NAME") ?: "miner.skr"
        val resolved = SkrNames(rpc).resolve(name)
        assertTrue("$name did not resolve: $resolved", resolved is SkrResolution.Found)
        val owner = (resolved as SkrResolution.Found).owner
        val sgts = SgtLookup(rpc).holdings(owner)
        println("mainnet $name -> $owner, SGT holdings: ${sgts.map { "#${it.memberNumber} ${it.mint}" }}")
        assertTrue("the owner of $name holds no verifiable SGT", sgts.isNotEmpty())
        // The gift path looks the holding up again by mint, as a claim does.
        assertEquals(sgts.first(), SgtLookup(rpc).holdingOf(owner, sgts.first().mint))
        // A name nobody registered.
        assertTrue(SkrNames(rpc).resolve("no-such-name-heads-down-0001.skr") is SkrResolution.NotFound)
    }
}
