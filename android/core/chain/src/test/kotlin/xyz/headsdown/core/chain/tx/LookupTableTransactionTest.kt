package xyz.headsdown.core.chain.tx

import com.solana.transaction.Message
import com.solana.transaction.VersionedMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.TestAccounts
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.accounts.AccountLayoutException
import xyz.headsdown.core.chain.accounts.AddressLookupTables
import xyz.headsdown.core.chain.hex
import xyz.headsdown.core.chain.ix.ComputeBudgetInstructions
import xyz.headsdown.core.chain.swap.JupiterFixture
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * v0 messages with address lookup tables: the wire format byte for byte, an independent parser
 * (web3-solana) reading it back, and a real Jupiter swap that only fits a packet with its real
 * mainnet tables.
 */
class LookupTableTransactionTest {

    private val payer = Pubkey.fromBase58("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")
    private val blockhash = ByteArray(32) { (0x40 + it).toByte() }
    private fun key(b: Int) = Pubkey(ByteArray(32) { b.toByte() })

    private val program = key(0x11)
    private val a = key(0xA1)
    private val b = key(0xB2)
    private val c = key(0xC3)
    private val table = AddressLookupTable(key(0x77), listOf(b, key(0x01), a, program, payer))

    @Test
    fun `a v0 message with one lookup, byte for byte`() {
        val ix = Instruction(program, listOf(AccountMeta.signer(payer), AccountMeta.writable(a), AccountMeta.readonly(b), AccountMeta.writable(c)), byteArrayOf(9))
        val msg = TransactionBuilder.compile(payer, listOf(ix), blockhash, TxVersion.V0, listOf(table))
        // Static: payer, c (writable, not in the table), the program. Loaded: a (writable, index 2), b (read-only, index 0).
        assertEquals(listOf(payer, c, program), msg.accountKeys)
        assertEquals(listOf(a), msg.loadedWritable)
        assertEquals(listOf(b), msg.loadedReadonly)
        assertEquals(listOf(payer, c, program, a, b), msg.allKeys)
        val expected = "80" + "01" + "00" + "01" +
            "03" + payer.bytes.hex() + c.bytes.hex() + program.bytes.hex() +
            blockhash.hex() +
            "01" + "02" + "04" + "00030401" + "01" + "09" +
            "01" + table.address.bytes.hex() + "01" + "02" + "01" + "00"
        assertEquals(expected, msg.serialize().hex())
        // Index space: static keys, then loaded writable, then loaded read-only.
        assertEquals(listOf(true, true, false, true, false), (0..4).map(msg::isWritable))
        assertEquals(listOf(true, false, false, false, false), (0..4).map(msg::isSigner))
    }

    @Test
    fun `the payer, signers and invoked programs are never loaded from a table`() {
        // The table holds the payer and the program too: both must stay in the message.
        val cosigner = key(0x55)
        val all = AddressLookupTable(key(0x78), listOf(payer, program, cosigner, a))
        val ix = Instruction(program, listOf(AccountMeta.signer(payer), AccountMeta.signer(cosigner, writable = false), AccountMeta.writable(a)), byteArrayOf(1))
        val msg = TransactionBuilder.compile(payer, listOf(ix), blockhash, TxVersion.V0, listOf(all))
        assertEquals(listOf(payer, cosigner, program), msg.accountKeys)
        assertEquals(listOf(a), msg.loadedWritable)
        assertEquals(2, msg.numRequiredSignatures)
        // A program that is only passed as an account (a CPI target), not invoked, may be loaded.
        val cpiTarget = key(0x66)
        val withCpi = AddressLookupTable(key(0x79), listOf(cpiTarget))
        val ix2 = Instruction(program, listOf(AccountMeta.signer(payer), AccountMeta.readonly(cpiTarget)), byteArrayOf(1))
        val msg2 = TransactionBuilder.compile(payer, listOf(ix2), blockhash, TxVersion.V0, listOf(withCpi))
        assertEquals(listOf(cpiTarget), msg2.loadedReadonly)
    }

    @Test
    fun `several tables keep table order, unused tables are not referenced, the first table wins`() {
        val t1 = AddressLookupTable(key(0x71), listOf(a, key(0x02)))
        val unused = AddressLookupTable(key(0x72), listOf(key(0x03)))
        val t3 = AddressLookupTable(key(0x73), listOf(b, a, c))
        val ix = Instruction(program, listOf(AccountMeta.readonly(c), AccountMeta.writable(b), AccountMeta.readonly(a)), byteArrayOf())
        val msg = TransactionBuilder.compile(payer, listOf(ix), blockhash, TxVersion.V0, listOf(t1, unused, t3))
        assertEquals(listOf(t1.address, t3.address), msg.addressTableLookups.map { it.table })
        // a is in both: loaded from t1. Writable entries of every table first, then read-only ones.
        assertEquals(listOf(b), msg.loadedWritable)
        assertEquals(listOf(a, c), msg.loadedReadonly)
        assertArrayEquals(intArrayOf(), msg.addressTableLookups[0].writableIndexes)
        assertArrayEquals(intArrayOf(0), msg.addressTableLookups[0].readonlyIndexes)
        assertArrayEquals(intArrayOf(0), msg.addressTableLookups[1].writableIndexes)
        assertArrayEquals(intArrayOf(2), msg.addressTableLookups[1].readonlyIndexes)
        // Static: payer, program. Then b (2), a (3), c (4).
        assertArrayEquals(intArrayOf(4, 2, 3), msg.instructions.single().accountIndexes)
    }

    @Test
    fun `lookup tables need a v0 message and may not repeat`() {
        val ix = Instruction(program, listOf(AccountMeta.writable(a)), byteArrayOf())
        assertThrows(IllegalArgumentException::class.java) { TransactionBuilder.compile(payer, listOf(ix), blockhash, TxVersion.LEGACY, listOf(table)) }
        assertThrows(IllegalArgumentException::class.java) { TransactionBuilder.compile(payer, listOf(ix), blockhash, TxVersion.V0, listOf(table, table)) }
        assertThrows(IllegalArgumentException::class.java) { AddressLookupTable(key(1), List(257) { key(it) }) }
        // Without tables a v0 message is unchanged from before: an empty lookup vector.
        val plain = TransactionBuilder.compile(payer, listOf(ix), blockhash, TxVersion.V0)
        assertTrue(plain.addressTableLookups.isEmpty())
        assertEquals("00", plain.serialize().hex().takeLast(2))
    }

    @Test
    fun `web3-solana parses a real Jupiter swap with lookups to the same content`() {
        val fx = JupiterFixture.skrSol
        val instructions = listOf(ComputeBudgetInstructions.setComputeUnitLimit(400_000)) + fx.instructions.all
        val ours = TransactionBuilder.compile(fx.user, instructions, blockhash, TxVersion.V0, fx.tables)
        val parsed = Message.from(ours.serialize()) as VersionedMessage
        assertEquals(ours.numRequiredSignatures, parsed.signatureCount.toInt())
        assertEquals(ours.numReadonlySigned, parsed.readOnlyAccounts.toInt())
        assertEquals(ours.numReadonlyUnsigned, parsed.readOnlyNonSigners.toInt())
        assertEquals(ours.accountKeys.map { it.toBase58() }, parsed.accounts.map { it.base58() })
        assertEquals(ours.addressTableLookups.size, parsed.addressTableLookups.size)
        ours.addressTableLookups.zip(parsed.addressTableLookups).forEach { (mine, theirs) ->
            assertEquals(mine.table.toBase58(), theirs.account.base58())
            assertEquals(mine.writableIndexes.toList(), theirs.writableIndexes.map { it.toInt() })
            assertEquals(mine.readonlyIndexes.toList(), theirs.readOnlyIndexes.map { it.toInt() })
        }
        // Every compiled index resolves back to the original account through the tables.
        val all = ours.allKeys
        instructions.zip(parsed.instructions).forEach { (orig, compiled) ->
            assertEquals(orig.programId, all[compiled.programIdIndex.toInt() and 0xFF])
            assertEquals(orig.accounts.map { it.pubkey }, compiled.accountIndices.map { all[it.toInt() and 0xFF] })
            assertArrayEquals(orig.data, compiled.data)
        }
        // Loaded entries really are those table entries, with the writability the instructions need.
        val tables = fx.tables.associateBy { it.address }
        val writableWanted = instructions.flatMap { it.accounts }.filter { it.isWritable }.map { it.pubkey }.toSet()
        for (lookup in ours.addressTableLookups) {
            val t = tables.getValue(lookup.table)
            lookup.writableIndexes.forEach { assertTrue(t.addresses[it] in writableWanted) }
            lookup.readonlyIndexes.forEach { assertFalse(t.addresses[it] in writableWanted) }
        }
    }

    @Test
    fun `a real Jupiter swap fits one packet only with its lookup tables`() {
        for (fx in listOf(JupiterFixture.solOre, JupiterFixture.skrSol)) {
            val instructions = fx.instructions.all
            val bare = TransactionBuilder.compile(fx.user, instructions, blockhash, TxVersion.V0)
            val loaded = TransactionBuilder.compile(fx.user, instructions, blockhash, TxVersion.V0, fx.tables)
            assertTrue("${bare.accountKeys.size} static keys", loaded.accountKeys.size < bare.accountKeys.size)
            assertTrue(TransactionBuilder.fits(loaded))
            val tx = TransactionBuilder.unsignedTransaction(loaded)
            assertEquals(TransactionBuilder.unsignedSize(loaded), tx.size)
            // Leave samples for `solana decode-transaction <base64> base64` (independent decoder).
            File(System.getProperty("user.dir"), "build/tx-samples").apply { mkdirs() }
                .resolve("jupiter_${fx.request.inAmount}_v0_alt.b64").writeText(Base64.getEncoder().encodeToString(tx))
        }
        // The 63-account SKR route cannot be sent without its tables.
        val big = JupiterFixture.skrSol
        val bare = TransactionBuilder.compile(big.user, big.instructions.all, blockhash, TxVersion.V0)
        assertFalse(TransactionBuilder.fits(bare))
        assertThrows(IllegalArgumentException::class.java) { TransactionBuilder.unsignedTransaction(bare) }
    }

    // ------------------------------------------------------------------ the table account

    private fun tableBytes(addresses: List<Pubkey>, type: Int = 1, deactivation: Long = -1, lastExtendedSlot: Long = 100, lastExtendedStart: Int = 0): ByteArray =
        ByteBuffer.allocate(56 + 32 * addresses.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, type); putLong(4, deactivation); putLong(12, lastExtendedSlot); put(20, lastExtendedStart.toByte())
            position(56); addresses.forEach { put(it.bytes) }
        }.array()

    private fun alt(data: ByteArray, owner: Pubkey = WellKnown.ADDRESS_LOOKUP_TABLE) = TestAccounts.info(owner, data)

    @Test
    fun `real mainnet lookup tables decode to whole address lists`() {
        val (address, info) = JupiterFixture.solOre.tableAccounts.single()
        val decoded = AddressLookupTables.decode(address, info)
        assertEquals("DnwaKnJk8zmnSywMErVBKDtfZGMCNWvGkxZ4eyAUXpSe", decoded.address.toBase58())
        assertEquals(250, decoded.addresses.size)
        assertEquals((info.size - 56) / 32, decoded.addresses.size)
        assertEquals(3, JupiterFixture.skrSol.tables.size)
    }

    @Test
    fun `a lookup table must be owned, whole, initialized and active`() {
        val addresses = listOf(a, b, c)
        val good = tableBytes(addresses)
        assertEquals(addresses, AddressLookupTables.decode(table.address, alt(good)).addresses)
        fun rejects(info: xyz.headsdown.core.chain.rpc.AccountInfo) = assertThrows(AccountLayoutException::class.java) { AddressLookupTables.decode(table.address, info) }
        rejects(alt(good, owner = WellKnown.SYSTEM_PROGRAM))
        rejects(alt(good.copyOf(good.size - 1)))
        rejects(alt(good.copyOf(40)))
        rejects(alt(tableBytes(addresses, type = 0)))
        // Being deactivated: its lookups could stop resolving before the transaction lands.
        rejects(alt(tableBytes(addresses, deactivation = 451_000_000)))
        assertTrue(AddressLookupTables.decode(table.address, alt(tableBytes(emptyList()))).addresses.isEmpty())
    }

    @Test
    fun `addresses appended in the slot the table was read at are not used yet`() {
        val addresses = listOf(a, b, c)
        // Extended at slot 100 from index 2: at slot 100 only the first two are active.
        val bytes = tableBytes(addresses, lastExtendedSlot = 100, lastExtendedStart = 2)
        assertEquals(listOf(a, b), AddressLookupTables.decode(table.address, alt(bytes), slot = 100uL).addresses)
        assertEquals(addresses, AddressLookupTables.decode(table.address, alt(bytes), slot = 101uL).addresses)
        assertEquals(addresses, AddressLookupTables.decode(table.address, alt(bytes)).addresses)
    }
}
