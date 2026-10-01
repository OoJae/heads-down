package xyz.headsdown.core.chain.tx

import com.solana.transaction.LegacyMessage
import com.solana.transaction.Message
import com.solana.transaction.VersionedMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.hex
import xyz.headsdown.core.chain.hexBytes
import xyz.headsdown.core.chain.ix.ComputeBudgetInstructions
import xyz.headsdown.core.chain.ix.HeadsDownInstructions
import xyz.headsdown.core.chain.ix.OreInstructions
import xyz.headsdown.core.chain.ix.RigCaps
import xyz.headsdown.core.keys.ShiftPlan
import java.io.File
import java.util.Base64

class TransactionBuilderTest {

    private val authority = Pubkey.fromBase58("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")
    private val blockhash = ByteArray(32) { (0x40 + it).toByte() }
    private val p256 = hexBytes("0360fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6")
    private val plan = ShiftPlan(530_000_000uL, 1_000_000uL, 4, 0, 1, 0, 1_790_000_000L, 1_790_028_800L)
    private val caps = RigCaps(500_000_000uL, 120_000_000uL, 2_000_000uL, 670_000_000uL, 1_790_600_000L)

    /** The clock-in shape: CU limit, ORE automate, register_rig, set_caps, arm_shift. */
    private fun clockIn() = listOf(
        ComputeBudgetInstructions.setComputeUnitLimit(300_000),
        OreInstructions.automateHeadsDown(authority, 250_000uL, 20_000_000uL, 10_000uL),
        HeadsDownInstructions.registerRig(authority, p256),
        HeadsDownInstructions.setCaps(authority, caps),
        HeadsDownInstructions.armShift(authority, plan),
    )

    @Test
    fun `compact-u16 matches the Solana encoding`() {
        val cases = mapOf(0 to "00", 1 to "01", 127 to "7f", 128 to "8001", 255 to "ff01", 16_383 to "ff7f", 16_384 to "808001", 65_535 to "ffff03")
        for ((v, hex) in cases) assertEquals("$v", hex, ShortVec.encode(v).hex())
        assertThrows(IllegalArgumentException::class.java) { ShortVec.encode(65_536) }
        assertThrows(IllegalArgumentException::class.java) { ShortVec.encode(-1) }
    }

    @Test
    fun `a minimal legacy transaction, byte for byte`() {
        val program = Pubkey(ByteArray(32) { 0x11 })
        val acct = Pubkey(ByteArray(32) { 0x22 })
        val ix = Instruction(program, listOf(AccountMeta.signer(authority), AccountMeta.writable(acct)), byteArrayOf(9, 8))
        val msg = TransactionBuilder.compile(authority, listOf(ix), blockhash, TxVersion.LEGACY)
        val expected = "01" + "00" + "01" + // header: 1 signer, 0 readonly signed, 1 readonly unsigned (program)
            "03" + authority.bytes.hex() + acct.bytes.hex() + program.bytes.hex() +
            blockhash.hex() +
            "01" + "02" + "02" + "0001" + "02" + "0908"
        assertEquals(expected, msg.serialize().hex())
        val tx = TransactionBuilder.unsignedTransaction(msg)
        assertEquals("01" + "00".repeat(64) + expected, tx.hex())
        // v0 differs only by the 0x80 prefix and the empty lookup-table vector.
        val v0 = TransactionBuilder.compile(authority, listOf(ix), blockhash, TxVersion.V0).serialize()
        assertEquals("80" + expected + "00", v0.hex())
    }

    @Test
    fun `keys are grouped, deduplicated and flag-merged with the payer first`() {
        val msg = TransactionBuilder.compile(authority, clockIn(), blockhash, TxVersion.V0)
        val keys = msg.accountKeys
        assertEquals(authority, keys[0])
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(1, msg.numRequiredSignatures)
        assertEquals(0, msg.numReadonlySigned)
        // Writable: authority, automation, executor, miner, rig. Read-only: the rest.
        val writable = keys.indices.filter(msg::isWritable).map { keys[it] }.toSet()
        assertEquals(
            setOf(authority, Ore.automation(authority).address, HeadsDownProgram.executor.address, Ore.miner(authority).address, HeadsDownProgram.rig(authority).address),
            writable,
        )
        val readonly = keys.toSet() - writable
        assertEquals(
            setOf(WellKnown.COMPUTE_BUDGET, Ore.PROGRAM_ID, HeadsDownProgram.ID, WellKnown.SYSTEM_PROGRAM, HeadsDownProgram.config.address, Ore.BOARD),
            readonly,
        )
        assertEquals(readonly.size, msg.numReadonlyUnsigned)
        // A key read-only in one instruction and writable in another is writable.
        val a = Pubkey(ByteArray(32) { 1 })
        val p = Pubkey(ByteArray(32) { 2 })
        val merged = TransactionBuilder.compile(
            authority,
            listOf(Instruction(p, listOf(AccountMeta.readonly(a)), byteArrayOf()), Instruction(p, listOf(AccountMeta.writable(a)), byteArrayOf())),
            blockhash, TxVersion.LEGACY,
        )
        assertTrue(merged.isWritable(merged.accountKeys.indexOf(a)))
    }

    @Test
    fun `web3-solana parses our legacy and v0 messages to the same content`() {
        for (version in TxVersion.entries) {
            val ours = TransactionBuilder.compile(authority, clockIn(), blockhash, version)
            val parsed = Message.from(ours.serialize())
            when (version) {
                TxVersion.LEGACY -> assertTrue(parsed is LegacyMessage)
                TxVersion.V0 -> assertTrue(parsed is VersionedMessage && parsed.addressTableLookups.isEmpty())
            }
            assertEquals(ours.numRequiredSignatures, parsed.signatureCount.toInt())
            assertEquals(ours.numReadonlySigned, parsed.readOnlyAccounts.toInt())
            assertEquals(ours.numReadonlyUnsigned, parsed.readOnlyNonSigners.toInt())
            assertEquals(ours.accountKeys.map { it.toBase58() }, parsed.accounts.map { it.base58() })
            assertArrayEquals(blockhash, parsed.blockhash.bytes)
            assertEquals(clockIn().size, parsed.instructions.size)
            // Every compiled instruction resolves back to the original program, metas and data.
            clockIn().zip(parsed.instructions).forEach { (orig, compiled) ->
                assertEquals(orig.programId.toBase58(), parsed.accounts[compiled.programIdIndex.toInt()].base58())
                assertEquals(orig.accounts.map { it.pubkey.toBase58() }, compiled.accountIndices.map { parsed.accounts[it.toInt() and 0xFF].base58() })
                assertArrayEquals(orig.data, compiled.data)
            }
        }
    }

    @Test
    fun `the clock-in transaction fits one packet in both versions`() {
        for (version in TxVersion.entries) {
            val tx = TransactionBuilder.unsignedTransaction(TransactionBuilder.compile(authority, clockIn(), blockhash, version))
            assertTrue("$version is ${tx.size} B", tx.size <= TransactionBuilder.PACKET_DATA_SIZE)
            // Leave a sample for `solana decode-transaction <base64> base64` (independent decoder).
            File(System.getProperty("user.dir"), "build/tx-samples").apply { mkdirs() }
                .resolve("clock_in_${version.name.lowercase()}.b64").writeText(Base64.getEncoder().encodeToString(tx))
        }
    }

    @Test
    fun `oversized, empty and malformed inputs are refused`() {
        val big = Instruction(HeadsDownProgram.ID, listOf(AccountMeta.signer(authority)), ByteArray(1_200))
        val msg = TransactionBuilder.compile(authority, listOf(big), blockhash, TxVersion.LEGACY)
        assertThrows(IllegalArgumentException::class.java) { TransactionBuilder.unsignedTransaction(msg) }
        assertThrows(IllegalArgumentException::class.java) { TransactionBuilder.compile(authority, emptyList(), blockhash, TxVersion.LEGACY) }
        assertThrows(IllegalArgumentException::class.java) { TransactionBuilder.compile(authority, clockIn(), ByteArray(31), TxVersion.LEGACY) }
        // A program id may not double as a writable account.
        val bad = Instruction(HeadsDownProgram.ID, listOf(AccountMeta.writable(HeadsDownProgram.ID)), byteArrayOf())
        assertThrows(IllegalArgumentException::class.java) { TransactionBuilder.compile(authority, listOf(bad), blockhash, TxVersion.LEGACY) }
    }
}
