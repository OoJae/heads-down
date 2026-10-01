package xyz.headsdown.core.chain.ix

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import xyz.headsdown.core.chain.AssociatedToken
import xyz.headsdown.core.chain.Golden
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.arr
import xyz.headsdown.core.chain.hex
import xyz.headsdown.core.chain.pubkey
import xyz.headsdown.core.chain.str
import xyz.headsdown.core.chain.tx.AccountMeta

/**
 * ORE's `claim_sol` / `claim_ore`, against ORE's own builders at the pinned commit `b92c5043`
 * (`api/src/sdk.rs:62-106`, `api/src/instruction.rs`): the tag, the `bps u64` body and the
 * ordered account metas. `MainnetSimulationTest` (opt-in) runs the same instructions through the
 * live ORE program.
 */
class OreClaimInstructionsTest {

    /** The authority of the mainnet Miner fixture (`fixtures/ore_miner.json`). */
    private val authority = Pubkey.fromBase58("2Yf3PNJXbdELHD1jPWSaXTWMYQLuH3jYzsAq4L5VWn3X")
    private val miner = Pubkey.fromBase58("4gWunDA4TQ9noMeGLR9ovNiWySFJbU8tSmECvLMp4EXz")

    @Test
    fun `claim_sol is tag 3 with ORE's five accounts`() {
        val ix = OreInstructions.claimSol(authority)
        assertEquals(Ore.PROGRAM_ID, ix.programId)
        assertEquals("03", ix.data.hex())
        assertEquals(
            listOf(
                AccountMeta.signer(authority),
                AccountMeta.writable(Ore.BOARD),
                AccountMeta.writable(miner),
                AccountMeta.readonly(WellKnown.SYSTEM_PROGRAM),
                AccountMeta.readonly(Ore.PROGRAM_ID),
            ),
            ix.accounts,
        )
    }

    @Test
    fun `claim_ore is tag 4, bps as u64 LE, with ORE's eleven accounts`() {
        val all = OreInstructions.claimOre(authority, 10_000)
        assertEquals(Ore.PROGRAM_ID, all.programId)
        assertEquals("04" + "1027000000000000", all.data.hex())
        assertEquals(OreInstructions.CLAIM_ORE_BYTES, all.dataSize)
        assertEquals("04" + "c409000000000000", OreInstructions.claimOre(authority, 2_500).data.hex())
        assertEquals("04" + "0100000000000000", OreInstructions.claimOre(authority, 1).data.hex())
        assertEquals(
            listOf(
                AccountMeta.signer(authority),
                AccountMeta.writable(Ore.BOARD),
                AccountMeta.writable(miner),
                AccountMeta.writable(Ore.MINT),
                AccountMeta.writable(AssociatedToken.address(authority, Ore.MINT).address),
                AccountMeta.writable(Ore.TREASURY),
                AccountMeta.writable(Ore.treasuryTokens),
                AccountMeta.readonly(WellKnown.SYSTEM_PROGRAM),
                AccountMeta.readonly(WellKnown.SPL_TOKEN),
                AccountMeta.readonly(WellKnown.ASSOCIATED_TOKEN),
                AccountMeta.readonly(Ore.PROGRAM_ID),
            ),
            all.accounts,
        )
    }

    @Test
    fun `the Treasury's ORE account is the one the fork's bury path used`() {
        // bury_auction_buy ran against the live ORE program with this account as ATA(Treasury, ORE).
        val accounts = Golden.vectors.getValue("bury_auction_buy").arr("accounts").map { it.jsonObject }
        assertEquals(accounts.single { it.str("role") == "ore_treasury_ore" }.pubkey("pubkey"), Ore.treasuryTokens)
        assertEquals(accounts.single { it.str("role") == "ore_mint" }.pubkey("pubkey"), Ore.MINT)
        assertEquals(accounts.single { it.str("role") == "ore_treasury" }.pubkey("pubkey"), Ore.TREASURY)
    }

    @Test
    fun `a claim share outside 1 to 10000 bps is never built`() {
        // 0 would claim nothing and still create the ORE token account: "keep unrefined" sends no instruction.
        assertThrows(IllegalArgumentException::class.java) { OreInstructions.claimOre(authority, 0) }
        assertThrows(IllegalArgumentException::class.java) { OreInstructions.claimOre(authority, 10_001) }
        assertThrows(IllegalArgumentException::class.java) { OreInstructions.claimOre(authority, -1) }
    }
}
