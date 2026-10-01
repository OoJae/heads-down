package xyz.headsdown.core.chain.gift

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.ChainServer
import xyz.headsdown.core.chain.FakeChain
import xyz.headsdown.core.chain.Fixtures
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.accounts.AccountLayoutException
import xyz.headsdown.core.chain.accounts.SgtAnchors
import xyz.headsdown.core.chain.accounts.SgtFixtures
import xyz.headsdown.core.chain.swap.JupiterFixture

/**
 * The real mainnet accounts behind one `.skr` name (`fixtures/skr_miner.json`, captured
 * read-only on 2026-10-01): its name record, its owner's Token-2022 accounts and their mints.
 */
object SkrFixture {
    private val root: JsonObject = Fixtures.json("skr_miner")
    val name: String = root["name"]!!.jsonPrimitive.content
    val tldParent: Pubkey = Pubkey.fromBase58(root["tld_parent"]!!.jsonPrimitive.content)
    val nameAccount: Pubkey = Pubkey.fromBase58(root["name_account"]!!.jsonObject["address"]!!.jsonPrimitive.content)
    val owner: Pubkey = Pubkey.fromBase58(root["owner"]!!.jsonPrimitive.content)
    const val SGT_MINT = "8mkSZmNtfykHiGpPhRzdafyEuJYPoaFBip9fksvSsQvT"
    const val SGT_TOKEN_ACCOUNT = "5q7eoUGbdHjC7Fy8QfNDbUr6HJGxd7FLa2F8mU3nuJmB"

    /** Loads the name record, the owner's token accounts and their mints into [chain]. */
    fun load(chain: FakeChain): FakeChain = chain.apply {
        val record = JupiterFixture.account(root["name_account"]!!.jsonObject["value"].toString())
        put(nameAccount, record.owner, record.data, record.lamports.toLong())
        for (entry in root["token_2022_accounts"]!!.jsonArray.map { it.jsonObject }) {
            val info = JupiterFixture.account(entry["account"].toString())
            put(Pubkey.fromBase58(entry["pubkey"]!!.jsonPrimitive.content), info.owner, info.data, info.lamports.toLong())
        }
        for (entry in root["mints"]!!.jsonArray.map { it.jsonObject }) {
            val info = JupiterFixture.account(entry["value"].toString())
            put(Pubkey.fromBase58(entry["address"]!!.jsonPrimitive.content), info.owner, info.data, info.lamports.toLong())
        }
    }
}

/**
 * `.skr` → wallet → SGT, on real mainnet bytes: the AllDomains derivation, the name record
 * checks, and the wallet's Seeker Genesis Token found among its other Token-2022 tokens.
 */
class SkrNamesTest {

    private val servers = mutableListOf<AutoCloseable>()

    @After
    fun close() = servers.forEach { it.close() }

    private fun chain() = SkrFixture.load(FakeChain())

    @Test
    fun `the derivation is AllDomains' - origin, TLD parent and name account`() {
        // tld-parser: "get origin name account should always equal to 3mX9b4AZ…".
        assertEquals("3mX9b4AZaQehNoQGfckVcmgmA6bkBoFcbLj9RMmMyNcU", AllDomains.origin.toBase58())
        assertEquals(SkrFixture.tldParent, AllDomains.skrParent)
        assertEquals("F3A8kuikEiu6k2399oSJ1PWfcJYDHqpwoQ2e8psSDNuF", AllDomains.skrParent.toBase58())
        // The record of miner.skr sits at the derived address on mainnet.
        assertEquals(SkrFixture.nameAccount, AllDomains.skrNameAccount("miner"))
        assertEquals("H3Z8uXkucVTqv8EQQDFmfvLYGR8Xv9VdkT5gvpzAYNZF", AllDomains.skrNameAccount("miner").toBase58())
        // Other names derived the same way and read from mainnet the same day.
        assertEquals("8RcAGEjjfJ1bZr351i1pFeo2MVJP18tDXifc53c6s88d", AllDomains.skrNameAccount("chase").toBase58())
        assertEquals("DX2qRYxzwWgyUC6ew594RY42jnZuHfV7VyRxxjy4dczH", AllDomains.skrNameAccount("gm").toBase58())
        // The NFT record a wrapped name would be owned by.
        assertEquals("CbXLhuoe2Us6z4ZjbuTNSJkwwx96BbZqdbvc5utg6onT", AllDomains.nftRecord(SkrFixture.nameAccount, ".skr").toBase58())
    }

    @Test
    fun `a real name record decodes - owner, parent, no expiry, not transferable`() {
        val record = AllDomains.record(chain().accounts.getValue(SkrFixture.nameAccount))
        assertEquals(SkrFixture.owner, record.owner)
        assertEquals(AllDomains.skrParent, record.parentName)
        assertEquals(0uL, record.expiresAt)
        assertTrue(record.createdAt > 1_700_000_000uL)
        assertTrue(record.nonTransferable)
    }

    @Test
    fun `miner dot skr resolves to its owner wallet with one account read`() = runBlocking {
        val chain = chain()
        val found = SkrNames(chain.rpc()).resolve("miner.skr") as SkrResolution.Found
        assertEquals("miner.skr", found.name.full)
        assertEquals(SkrFixture.nameAccount, found.nameAccount)
        assertEquals("5atwKLGN5QBhBuBCc3KJ1x1KKQxX52X82Mzyckyi6ws9", found.owner.toBase58())
        assertEquals(listOf("getAccountInfo"), chain.methods)
        // Case and surrounding spaces do not matter; the lookup is for the lower-cased name.
        assertEquals(found, SkrNames(chain.rpc()).resolve("  Miner.SKR "))
    }

    @Test
    fun `unknown, malformed, expired and wrapped names never resolve to a wallet`() = runBlocking {
        val chain = chain()
        val names = SkrNames(chain.rpc()) { 1_790_000_000 }
        assertEquals(SkrResolution.NotFound(SkrName.parse("nobody-here.skr")!!), names.resolve("nobody-here.skr"))
        assertEquals(SkrResolution.Invalid, names.resolve("miner.sol"))
        assertEquals(SkrResolution.Invalid, names.resolve("miner"))
        assertEquals(1, chain.requests.size) // malformed text never reaches the network

        val real = chain.accounts.getValue(SkrFixture.nameAccount)
        // Expired: expires_at in the past.
        val expired = real.data.also { d -> java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(104, 1_780_000_000) }
        chain.put(SkrFixture.nameAccount, real.owner, expired)
        assertEquals(SkrResolution.Unresolvable(SkrName.parse("miner.skr")!!), names.resolve("miner.skr"))
        // Still valid when the expiry is in the future.
        val later = real.data.also { d -> java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(104, 1_795_000_000) }
        chain.put(SkrFixture.nameAccount, real.owner, later)
        assertTrue(names.resolve("miner.skr") is SkrResolution.Found)
        // Wrapped as an NFT: the record's owner is the NFT record, not a wallet.
        val wrapped = real.data.also { d -> AllDomains.nftRecord(SkrFixture.nameAccount, ".skr").bytes.copyInto(d, 40) }
        chain.put(SkrFixture.nameAccount, real.owner, wrapped)
        assertEquals(SkrResolution.Unresolvable(SkrName.parse("miner.skr")!!), names.resolve("miner.skr"))
        // An all-zero owner.
        chain.put(SkrFixture.nameAccount, real.owner, real.data.also { d -> ByteArray(32).copyInto(d, 40) })
        assertTrue(names.resolve("miner.skr") is SkrResolution.Unresolvable)
    }

    @Test
    fun `a record the RPC could have substituted is refused`() {
        val chain = chain()
        val real = chain.accounts.getValue(SkrFixture.nameAccount)
        fun resolveWith(owner: Pubkey = real.owner, data: ByteArray = real.data): Throwable? {
            chain.put(SkrFixture.nameAccount, owner, data)
            return runCatching { runBlocking { SkrNames(chain.rpc()).resolve("miner.skr") } }.exceptionOrNull()
        }
        // Not owned by the name service, not a name record, truncated, or a record of another TLD.
        assertTrue(resolveWith(owner = WellKnown.SYSTEM_PROGRAM) is AccountLayoutException)
        assertTrue(resolveWith(data = real.data.also { it[0] = 0 }) is AccountLayoutException)
        assertTrue(resolveWith(data = real.data.copyOf(199)) is AccountLayoutException)
        assertTrue(resolveWith(data = real.data.also { it[8] = (it[8] + 1).toByte() }) is AccountLayoutException)
        assertNull(resolveWith())
    }

    @Test
    fun `names from untrusted text are parsed strictly`() {
        assertEquals("mum.skr", SkrName.parse("mum.skr")!!.full)
        assertEquals("mum", SkrName.parse("MUM.skr")!!.label)
        assertEquals("a-b_c9", SkrName.parse("a-b_c9.skr")!!.label)
        assertEquals(SkrName.parse("mum.skr"), SkrName.parse(" mum.skr\n"))
        val longest = "a".repeat(59) + ".skr"
        assertEquals(63, SkrName.parse(longest)!!.full.length)
        for (bad in listOf(
            "", ".skr", "mum", "mum.sol", "mum.skr.evil", "sub.mum.skr", "-mum.skr", "_mum.skr", "mu m.skr", "mum.skr/../x", "mum.skr?x=1",
            "mum​.skr", "múm.skr", "mum.SKR.", "https://mum.skr", "a".repeat(60) + ".skr", "x".repeat(65), "mum.skr\u0000",
        )) {
            assertNull("\"$bad\"", SkrName.parse(bad))
        }
    }

    // ------------------------------------------------------------------------- wallet → SGT

    @Test
    fun `the wallet's SGT is found among its other Token-2022 tokens`() = runBlocking {
        val chain = chain()
        val holdings = SgtLookup(chain.rpc()).holdings(SkrFixture.owner)
        // Two Token-2022 accounts hold exactly one token; only one is a Seeker Genesis Token.
        assertEquals(1, holdings.size)
        val sgt = holdings.single()
        assertEquals(SkrFixture.SGT_MINT, sgt.mint.toBase58())
        assertEquals(SkrFixture.SGT_TOKEN_ACCOUNT, sgt.tokenAccount.toBase58())
        assertTrue(sgt.memberNumber >= 1uL)
        assertTrue(sgt.frozen)
        assertEquals(listOf("getTokenAccountsByOwner", "getMultipleAccounts"), chain.methods)
        // The same holding, looked up by mint (what a claim or a join does).
        assertEquals(sgt, SgtLookup(chain.rpc()).holdingOf(SkrFixture.owner, sgt.mint))
    }

    @Test
    fun `no SGT, someone else's wallet and a test-group build find nothing`() = runBlocking {
        val chain = chain()
        val stranger = Pubkey(ByteArray(32) { 3 })
        assertTrue(SgtLookup(chain.rpc()).holdings(stranger).isEmpty())
        assertNull(SgtLookup(chain.rpc()).holdingOf(stranger, Pubkey.fromBase58(SkrFixture.SGT_MINT)))
        // The decoy: a one-token Token-2022 account whose mint is not in the SGT group.
        val decoy = Pubkey.fromBase58("FfrLtQ2Zz6dbajCfKabXJeYtWqYmBwhw3GDJT4aJDTRX")
        assertNull(SgtLookup(chain.rpc()).holdingOf(SkrFixture.owner, decoy))
        // Anchored to another group (a devnet build of the program): mainnet SGTs are not accepted.
        val test = SgtAnchors(Pubkey.fromBase58("8zzemreeALfyxLYje3GRzuiVETUfNXK3tDcpJgVVpY8X"), Pubkey.fromBase58("18h7xbzxhau9SNa4uA8r1ypBWBdHwh2RjGtd7HwAE6L"))
        assertTrue(SgtLookup(chain.rpc(), test).holdings(SkrFixture.owner).isEmpty())
        // Moved away by Solana Mobile: the old account holds 0 and is no longer a holding.
        val token = Pubkey.fromBase58(SkrFixture.SGT_TOKEN_ACCOUNT)
        val info = chain.accounts.getValue(token)
        chain.put(token, info.owner, info.data.also { it[64] = 0 }, info.lamports.toLong())
        assertTrue(SgtLookup(chain.rpc()).holdings(SkrFixture.owner).isEmpty())
    }

    @Test
    fun `a holding in a non-associated account is still found by mint`() = runBlocking {
        // crates/sgt-verify does not require the ATA; the lookup falls back to listing the wallet.
        val sgt = SgtFixtures.member20
        val odd = Pubkey(ByteArray(32) { 0x61 })
        val chain = FakeChain().apply {
            put(odd, sgt.tokenInfo.owner, sgt.tokenInfo.data)
            put(sgt.mint, sgt.mintInfo.owner, sgt.mintInfo.data)
        }
        val holding = SgtLookup(chain.rpc()).holdingOf(sgt.holder, sgt.mint)!!
        assertEquals(odd, holding.tokenAccount)
        assertEquals(20uL, holding.memberNumber)
    }

    @Test
    fun `over HTTPS - a dot skr name resolves to a wallet and its SGT mint`() = runBlocking {
        val server = ChainServer(chain()).also { servers += it }
        val rpc = server.rpc()
        val found = SkrNames(rpc).resolve("miner.skr") as SkrResolution.Found
        val sgt = SgtLookup(rpc).holdings(found.owner).single()
        assertEquals(SkrFixture.SGT_MINT, sgt.mint.toBase58())
        assertEquals(listOf("getAccountInfo", "getTokenAccountsByOwner", "getMultipleAccounts"), server.chain.methods)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `names are value objects and never built from unchecked text`() {
        assertEquals(SkrName.parse("gm.skr"), SkrName.parse("GM.skr"))
        assertEquals("gm.skr", SkrName.parse("gm.skr").toString())
        assertThrows(NullPointerException::class.java) { SkrName.parse("gm.sol")!! }
    }
}
