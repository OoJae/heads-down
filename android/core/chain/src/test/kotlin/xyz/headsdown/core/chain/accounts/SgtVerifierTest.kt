package xyz.headsdown.core.chain.accounts

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.AssociatedToken
import xyz.headsdown.core.chain.FakeTransport
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.rpc.AccountInfo
import xyz.headsdown.core.chain.rpc.SolanaJsonRpc

/** One real mainnet SGT from `crates/sgt-verify/fixtures` (mirrored under `resources/sgt`). */
class SgtFixture(val label: String, val mint: Pubkey, val tokenAccount: Pubkey, val holder: Pubkey, val memberNumber: ULong) {
    val mintInfo: AccountInfo = SgtFixtures.account("$label/mint.json")
    val tokenInfo: AccountInfo = SgtFixtures.account("$label/token_account.json")
}

object SgtFixtures {
    private fun json(path: String): JsonObject =
        Json.parseToJsonElement(javaClass.getResource("/sgt/$path")?.readText() ?: error("sgt/$path missing: run :core:chain:syncGoldenVectors")).jsonObject

    /** The fixture's `account`, decoded through the real RPC parsing path. */
    fun account(path: String): AccountInfo = runBlocking {
        val result = """{"context":{"slot":1},"value":${json(path)["account"]}}"""
        SolanaJsonRpc(FakeTransport.result(result)).getAccountInfo(Pubkey.DEFAULT)!!
    }

    val all: List<SgtFixture> by lazy {
        json("manifest.json")["sgts"]!!.jsonArray.map { it.jsonObject }.map { s ->
            fun str(k: String) = s[k]!!.jsonPrimitive.content
            SgtFixture(str("label"), Pubkey.fromBase58(str("mint")), Pubkey.fromBase58(str("token_account")), Pubkey.fromBase58(str("holder")), str("member_number").toULong())
        }
    }

    val member20: SgtFixture get() = all.single { it.label == "member-20" }

    fun with(info: AccountInfo, data: ByteArray = info.data, owner: Pubkey = info.owner) = AccountInfo(info.lamports, owner, data, info.executable)
}

/**
 * The phone's SGT check on the real mainnet bytes `crates/sgt-verify` is tested on, and on
 * mutations of them: the same verdict and the same rejection as the in-program verifier
 * (`crates/sgt-verify/tests/real_fixtures.rs`, `tests/spoof.rs`).
 */
class SgtVerifierTest {

    private val sgt = SgtFixtures.member20

    // Offsets inside the 450-byte SGT mint (crates/sgt-verify README, "What a real SGT looks like").
    private val supplyAt = 36
    private val mintAuthorityAt = 4
    private val metadataPointerValueAt = 170
    private val permanentDelegateValueAt = 238
    private val closeAuthorityValueAt = 274
    private val memberPointerValueAt = 310
    private val memberValueAt = 378

    private fun verify(
        token: AccountInfo = sgt.tokenInfo,
        mint: AccountInfo = sgt.mintInfo,
        owner: Pubkey = sgt.holder,
        tokenAddress: Pubkey = sgt.tokenAccount,
        mintAddress: Pubkey = sgt.mint,
        anchors: SgtAnchors = SgtAnchors.MAINNET,
    ) = SgtVerifier.verify(tokenAddress, token, mintAddress, mint, owner, anchors)

    private fun rejection(block: () -> Unit): SgtRejection = assertThrows(SgtException::class.java) { block() }.reason

    private fun ByteArray.flip(at: Int): ByteArray = copyOf().also { it[at] = (it[at].toInt() xor 0x01).toByte() }

    @Test
    fun `every real mainnet SGT verifies with its member number`() {
        assertTrue(SgtFixtures.all.size >= 2)
        for (s in SgtFixtures.all) {
            val holding = SgtVerifier.verify(s.tokenAccount, s.tokenInfo, s.mint, s.mintInfo, s.holder)
            assertEquals(s.label, SgtHolding(s.mint, s.tokenAccount, s.memberNumber, frozen = true), holding)
            assertEquals(450, s.mintInfo.size)
            assertEquals(170, s.tokenInfo.size)
            // Real holdings are the holder's Token-2022 ATA (not required, but true).
            assertEquals(s.tokenAccount, AssociatedToken.address(s.holder, s.mint, WellKnown.TOKEN_2022).address)
        }
        assertEquals(20uL, sgt.memberNumber)
    }

    @Test
    fun `the anchors are Solana Mobile's group and authority, and nothing else passes`() {
        assertEquals("GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te", SgtAnchors.MAINNET.group.toBase58())
        assertEquals("GT2zuHVaZQYZSyQMgJPLzvkmyztfyXg2NJunqFp4p3A4", SgtAnchors.MAINNET.authority.toBase58())
        // A build pointed at a test group rejects real SGTs, as the program's test-group build does.
        val test = SgtAnchors(Pubkey.fromBase58("8zzemreeALfyxLYje3GRzuiVETUfNXK3tDcpJgVVpY8X"), Pubkey.fromBase58("18h7xbzxhau9SNa4uA8r1ypBWBdHwh2RjGtd7HwAE6L"))
        assertEquals(SgtRejection.MINT_AUTHORITY_MISMATCH, rejection { verify(anchors = test) })
        // The group alone swapped: the unforgeable anchor.
        assertEquals(SgtRejection.GROUP_MISMATCH, rejection { verify(anchors = SgtAnchors(test.group, SgtAnchors.MAINNET.authority)) })
    }

    @Test
    fun `ownership is checked before any byte is read`() {
        assertEquals(SgtRejection.TOKEN_ACCOUNT_NOT_TOKEN_2022, rejection { verify(token = SgtFixtures.with(sgt.tokenInfo, owner = WellKnown.SPL_TOKEN)) })
        assertEquals(SgtRejection.MINT_NOT_TOKEN_2022, rejection { verify(mint = SgtFixtures.with(sgt.mintInfo, owner = WellKnown.SPL_TOKEN)) })
        assertEquals(SgtRejection.DUPLICATE_ACCOUNT, rejection { verify(tokenAddress = sgt.mint) })
    }

    @Test
    fun `someone else's SGT, an empty holding and another mint are refused`() {
        val other = Pubkey(ByteArray(32) { 9 })
        assertEquals(SgtRejection.TOKEN_ACCOUNT_OWNER_MISMATCH, rejection { verify(owner = other) })
        // Solana Mobile moved the SGT away: the old account holds 0.
        val emptied = sgt.tokenInfo.data.also { it[64] = 0 }
        assertEquals(SgtRejection.AMOUNT_NOT_ONE, rejection { verify(token = SgtFixtures.with(sgt.tokenInfo, data = emptied)) })
        val two = sgt.tokenInfo.data.also { it[64] = 2 }
        assertEquals(SgtRejection.AMOUNT_NOT_ONE, rejection { verify(token = SgtFixtures.with(sgt.tokenInfo, data = two)) })
        // The other real SGT's mint with this token account.
        val another = SgtFixtures.all.first { it.label != sgt.label }
        assertEquals(SgtRejection.TOKEN_ACCOUNT_MINT_MISMATCH, rejection { verify(mint = another.mintInfo, mintAddress = another.mint) })
        // Uninitialized or undefined state bytes.
        assertEquals(SgtRejection.TOKEN_ACCOUNT_NOT_INITIALIZED, rejection { verify(token = SgtFixtures.with(sgt.tokenInfo, data = sgt.tokenInfo.data.also { it[108] = 0 })) })
        assertEquals(SgtRejection.TOKEN_ACCOUNT_INVALID_STATE, rejection { verify(token = SgtFixtures.with(sgt.tokenInfo, data = sgt.tokenInfo.data.also { it[108] = 3 })) })
        // A thawed (Initialized) holding is still authentic, and reported as not frozen.
        assertEquals(false, verify(token = SgtFixtures.with(sgt.tokenInfo, data = sgt.tokenInfo.data.also { it[108] = 1 })).frozen)
        // Wrapped-SOL shape.
        assertEquals(SgtRejection.NATIVE_TOKEN_ACCOUNT, rejection { verify(token = SgtFixtures.with(sgt.tokenInfo, data = sgt.tokenInfo.data.also { it[109] = 1 })) })
        assertEquals(SgtRejection.TOKEN_ACCOUNT_INVALID_OPTION, rejection { verify(token = SgtFixtures.with(sgt.tokenInfo, data = sgt.tokenInfo.data.also { it[109] = 2 })) })
    }

    @Test
    fun `every pinned mint fact is checked, with the rejection the program gives`() {
        fun mutated(at: Int) = SgtFixtures.with(sgt.mintInfo, data = sgt.mintInfo.data.flip(at))
        val cases = mapOf(
            mintAuthorityAt to SgtRejection.MINT_AUTHORITY_MISMATCH,
            50 to SgtRejection.FREEZE_AUTHORITY_MISMATCH,
            44 to SgtRejection.DECIMALS_NOT_ZERO,
            supplyAt to SgtRejection.SUPPLY_NOT_ONE,
            45 to SgtRejection.MINT_NOT_INITIALIZED,
            100 to SgtRejection.MINT_PADDING_NOT_ZERO,
            165 to SgtRejection.MINT_ACCOUNT_TYPE_MISMATCH,
            metadataPointerValueAt to SgtRejection.METADATA_POINTER_MISMATCH,
            metadataPointerValueAt + 32 to SgtRejection.METADATA_POINTER_MISMATCH,
            permanentDelegateValueAt to SgtRejection.PERMANENT_DELEGATE_MISMATCH,
            closeAuthorityValueAt to SgtRejection.MINT_CLOSE_AUTHORITY_MISMATCH,
            memberPointerValueAt to SgtRejection.GROUP_MEMBER_POINTER_MISMATCH,
            memberPointerValueAt + 32 to SgtRejection.GROUP_MEMBER_POINTER_MISMATCH,
            memberValueAt to SgtRejection.GROUP_MEMBER_MINT_MISMATCH,
            memberValueAt + 32 to SgtRejection.GROUP_MISMATCH,
        )
        for ((at, want) in cases) assertEquals("byte $at", want, rejection { verify(mint = mutated(at)) })
        // Member number 0 is not a member.
        val zero = sgt.mintInfo.data.also { d -> for (i in 0 until 8) d[memberValueAt + 64 + i] = 0 }
        assertEquals(SgtRejection.INVALID_MEMBER_NUMBER, rejection { verify(mint = SgtFixtures.with(sgt.mintInfo, data = zero)) })
        // A COption tag that is neither None nor Some.
        assertEquals(SgtRejection.MINT_INVALID_OPTION, rejection { verify(mint = SgtFixtures.with(sgt.mintInfo, data = sgt.mintInfo.data.also { it[1] = 1 })) })
    }

    @Test
    fun `flipping any single bit of a real mint only ever passes inside the member number`() {
        // crates/sgt-verify's every-bit-flip property, on the phone's port: all 3,600 mutations.
        val real = sgt.mintInfo.data
        val stillVerify = mutableListOf<Int>()
        for (bit in 0 until real.size * 8) {
            val data = real.copyOf().also { it[bit / 8] = (it[bit / 8].toInt() xor (1 shl (bit % 8))).toByte() }
            val holding = SgtVerifier.verifyOrNull(sgt.tokenAccount, sgt.tokenInfo, sgt.mint, SgtFixtures.with(sgt.mintInfo, data = data), sgt.holder)
            if (holding != null) stillVerify += bit / 8
        }
        val memberNumberBytes = (memberValueAt + 64 until memberValueAt + 72).toSet()
        assertTrue(stillVerify.isNotEmpty())
        assertTrue("bytes outside the member number still verify: ${stillVerify.toSet() - memberNumberBytes}", memberNumberBytes.containsAll(stillVerify))
    }

    @Test
    fun `malformed lengths and TLV are refused, never a crash`() {
        val mint = sgt.mintInfo.data
        // Truncated inside the last extension's value.
        assertEquals(SgtRejection.MALFORMED_TLV, rejection { verify(mint = SgtFixtures.with(sgt.mintInfo, data = mint.copyOf(440))) })
        // A type with no room for its length.
        assertEquals(SgtRejection.MALFORMED_TLV, rejection { verify(mint = SgtFixtures.with(sgt.mintInfo, data = mint.copyOf(377))) })
        // Cut right after an extension: the group-member extension is simply missing.
        assertEquals(SgtRejection.MISSING_GROUP_MEMBER, rejection { verify(mint = SgtFixtures.with(sgt.mintInfo, data = mint.copyOf(374))) })
        // Base mint without extensions, a multisig-sized blob, and lengths that are no mint at all.
        assertEquals(SgtRejection.MINT_MISSING_EXTENSIONS, rejection { verify(mint = SgtFixtures.with(sgt.mintInfo, data = mint.copyOf(82))) })
        assertEquals(SgtRejection.MINT_INVALID_LENGTH, rejection { verify(mint = SgtFixtures.with(sgt.mintInfo, data = mint.copyOf(355))) })
        assertEquals(SgtRejection.MINT_INVALID_LENGTH, rejection { verify(mint = SgtFixtures.with(sgt.mintInfo, data = mint.copyOf(120))) })
        assertEquals(SgtRejection.MINT_INVALID_LENGTH, rejection { verify(mint = SgtFixtures.with(sgt.mintInfo, data = ByteArray(0))) })
        // A fixed-size extension with the wrong length (the walk itself is well-formed).
        val shortMember = mint.copyOf().also { it[376] = 71 }
        assertEquals(SgtRejection.INVALID_EXTENSION_LENGTH, rejection { verify(mint = SgtFixtures.with(sgt.mintInfo, data = shortMember)) })
        assertEquals(SgtRejection.INVALID_EXTENSION_LENGTH, rejection { verify(mint = SgtFixtures.with(sgt.mintInfo, data = shortMember.copyOf(449))) })
        // The same extension twice.
        val twice = mint + mint.copyOfRange(374, 450)
        assertEquals(SgtRejection.DUPLICATE_EXTENSION, rejection { verify(mint = SgtFixtures.with(sgt.mintInfo, data = twice)) })
        // Token account: wrong lengths and the account-type byte.
        val token = sgt.tokenInfo.data
        assertEquals(SgtRejection.TOKEN_ACCOUNT_INVALID_LENGTH, rejection { verify(token = SgtFixtures.with(sgt.tokenInfo, data = token.copyOf(164))) })
        assertEquals(SgtRejection.TOKEN_ACCOUNT_INVALID_LENGTH, rejection { verify(token = SgtFixtures.with(sgt.tokenInfo, data = token.copyOf(355))) })
        assertEquals(SgtRejection.TOKEN_ACCOUNT_TYPE_MISMATCH, rejection { verify(token = SgtFixtures.with(sgt.tokenInfo, data = token.also { it[165] = 1 })) })
        // A plain 165-byte Token-2022 account (no extensions) holding it is still a holding.
        assertNotNull(SgtVerifier.verifyOrNull(sgt.tokenAccount, SgtFixtures.with(sgt.tokenInfo, data = token.copyOf(165)), sgt.mint, sgt.mintInfo, sgt.holder))
    }

    @Test
    fun `peek reads mint and amount of Token-2022 accounts only`() {
        assertEquals(sgt.mint to 1uL, SgtVerifier.peekToken2022Account(sgt.tokenInfo))
        assertNull(SgtVerifier.peekToken2022Account(SgtFixtures.with(sgt.tokenInfo, owner = WellKnown.SPL_TOKEN)))
        assertNull(SgtVerifier.peekToken2022Account(SgtFixtures.with(sgt.tokenInfo, data = ByteArray(10))))
        // The group itself is a mint, not a token account.
        assertNull(SgtVerifier.peekToken2022Account(SgtFixtures.account("group.json")))
    }

    @Test
    fun `the group mint itself is not an SGT`() {
        // Supply 0 and no TokenGroupMember: the collection is not one of its members.
        val group = SgtFixtures.account("group.json")
        val reason = rejection { verify(mint = group, mintAddress = SgtAnchors.MAINNET.group, token = SgtFixtures.with(sgt.tokenInfo, data = sgt.tokenInfo.data.also { d -> SgtAnchors.MAINNET.group.bytes.copyInto(d, 0) })) }
        assertEquals(SgtRejection.SUPPLY_NOT_ONE, reason)
    }
}
