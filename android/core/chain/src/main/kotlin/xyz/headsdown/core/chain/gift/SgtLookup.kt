package xyz.headsdown.core.chain.gift

import xyz.headsdown.core.chain.AssociatedToken
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.accounts.SgtAnchors
import xyz.headsdown.core.chain.accounts.SgtHolding
import xyz.headsdown.core.chain.accounts.SgtVerifier
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc

/**
 * Finds the Seeker Genesis Token a wallet holds, with the same rules as the program
 * (`crates/sgt-verify`, ported in [SgtVerifier]): `getTokenAccountsByOwner` for Token-2022, then
 * for every account holding exactly one token, the mint is fetched and the pair verified against
 * the SGT group and authority. A wallet full of other Token-2022 tokens yields none of them.
 *
 * The result decides what the phone shows and builds (which mint a gift is addressed to, which
 * accounts a claim or a join passes). The program verifies again, at that moment.
 */
class SgtLookup(
    private val rpc: SolanaJsonRpc,
    private val anchors: SgtAnchors = SgtAnchors.MAINNET,
) {
    /** Every SGT [owner] holds now, lowest member number first. Usually none or one. */
    suspend fun holdings(owner: Pubkey): List<SgtHolding> {
        val candidates = rpc.getTokenAccountsByOwner(owner, WellKnown.TOKEN_2022)
            .mapNotNull { keyed -> SgtVerifier.peekToken2022Account(keyed.account)?.takeIf { it.second == 1uL }?.let { keyed to it.first } }
            .take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return emptyList()
        val mints = rpc.getMultipleAccounts(candidates.map { it.second })
        return candidates.mapIndexedNotNull { i, (token, mint) ->
            val mintInfo = mints[i] ?: return@mapIndexedNotNull null
            SgtVerifier.verifyOrNull(token.pubkey, token.account, mint, mintInfo, owner, anchors)
        }.sortedBy { it.memberNumber }
    }

    /**
     * [owner]'s holding of the SGT [mint], or null when it does not hold it (any more). Real
     * holdings are the wallet's Token-2022 associated token account; any other account of the
     * wallet holding it is found too.
     */
    suspend fun holdingOf(owner: Pubkey, mint: Pubkey): SgtHolding? {
        val ata = AssociatedToken.address(owner, mint, WellKnown.TOKEN_2022).address
        val (tokenInfo, mintInfo) = rpc.getMultipleAccounts(listOf(ata, mint))
        if (tokenInfo != null && mintInfo != null) {
            SgtVerifier.verifyOrNull(ata, tokenInfo, mint, mintInfo, owner, anchors)?.let { return it }
        }
        return holdings(owner).firstOrNull { it.mint == mint }
    }

    companion object {
        /** A wallet with more one-token Token-2022 accounts than this is not looked through further. */
        const val MAX_CANDIDATES = 50
    }
}
