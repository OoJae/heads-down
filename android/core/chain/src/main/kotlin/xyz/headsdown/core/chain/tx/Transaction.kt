package xyz.headsdown.core.chain.tx

import xyz.headsdown.core.chain.Pubkey
import java.io.ByteArrayOutputStream

/** Wire version of the message. v0 may load addresses from on-chain lookup tables. */
enum class TxVersion { LEGACY, V0 }

/** Solana `compact-u16` ("short vec" length): 7 bits per byte, little-endian, at most 3 bytes. */
object ShortVec {
    fun encode(value: Int): ByteArray {
        require(value in 0..0xFFFF) { "compact-u16 out of range: $value" }
        val out = ByteArrayOutputStream(3)
        var rem = value
        while (true) {
            val low = rem and 0x7F
            rem = rem ushr 7
            if (rem == 0) {
                out.write(low)
                return out.toByteArray()
            }
            out.write(low or 0x80)
        }
    }
}

/** An instruction with its program and accounts replaced by indexes into the key list. */
class CompiledInstruction(val programIdIndex: Int, accountIndexes: IntArray, data: ByteArray) {
    private val indexes = accountIndexes.copyOf()
    private val bytes = data.copyOf()
    val accountIndexes: IntArray get() = indexes.copyOf()
    val data: ByteArray get() = bytes.copyOf()
}

/**
 * An on-chain address lookup table as a v0 message can use it: its own address and the addresses
 * it holds, in table order (an index into it is a u8). Decoded and checked by
 * `AddressLookupTables` in `accounts`.
 */
class AddressLookupTable(val address: Pubkey, addresses: List<Pubkey>) {
    val addresses: List<Pubkey> = addresses.toList()

    init {
        require(this.addresses.size <= MAX_ADDRESSES) { "a lookup table holds at most $MAX_ADDRESSES addresses" }
    }

    companion object {
        const val MAX_ADDRESSES = 256
    }
}

/** One `MessageAddressTableLookup` of a v0 message: which entries of [table] are loaded, and how. */
class AddressTableLookup(val table: Pubkey, writableIndexes: IntArray, readonlyIndexes: IntArray) {
    private val writable = writableIndexes.copyOf()
    private val readonly = readonlyIndexes.copyOf()
    val writableIndexes: IntArray get() = writable.copyOf()
    val readonlyIndexes: IntArray get() = readonly.copyOf()
}

/** A compiled, unsigned message: header, ordered keys, blockhash and compiled instructions. */
class CompiledMessage internal constructor(
    val version: TxVersion,
    val numRequiredSignatures: Int,
    val numReadonlySigned: Int,
    val numReadonlyUnsigned: Int,
    /** The static keys: what the message itself carries. */
    val accountKeys: List<Pubkey>,
    recentBlockhash: ByteArray,
    val instructions: List<CompiledInstruction>,
    /** v0 only: addresses loaded from lookup tables, after the static keys in index space. */
    val addressTableLookups: List<AddressTableLookup> = emptyList(),
    /** The loaded addresses in index order: every table's writable entries, then every table's read-only ones. */
    val loadedWritable: List<Pubkey> = emptyList(),
    val loadedReadonly: List<Pubkey> = emptyList(),
) {
    private val blockhash = recentBlockhash.copyOf()
    val recentBlockhash: ByteArray get() = blockhash.copyOf()

    val feePayer: Pubkey get() = accountKeys.first()

    /** Every key an instruction index can refer to: static, then loaded writable, then loaded read-only. */
    val allKeys: List<Pubkey> get() = accountKeys + loadedWritable + loadedReadonly

    fun isSigner(index: Int): Boolean = index < numRequiredSignatures

    fun isWritable(index: Int): Boolean = when {
        index < numRequiredSignatures -> index < numRequiredSignatures - numReadonlySigned
        index < accountKeys.size -> index < accountKeys.size - numReadonlyUnsigned
        else -> index < accountKeys.size + loadedWritable.size
    }

    /** The message bytes the wallet signs. */
    fun serialize(): ByteArray {
        val out = ByteArrayOutputStream()
        if (version == TxVersion.V0) out.write(0x80)
        out.write(numRequiredSignatures)
        out.write(numReadonlySigned)
        out.write(numReadonlyUnsigned)
        out.write(ShortVec.encode(accountKeys.size))
        accountKeys.forEach { out.write(it.bytes) }
        out.write(blockhash)
        out.write(ShortVec.encode(instructions.size))
        for (ix in instructions) {
            out.write(ix.programIdIndex)
            val indexes = ix.accountIndexes
            out.write(ShortVec.encode(indexes.size))
            indexes.forEach { out.write(it) }
            val data = ix.data
            out.write(ShortVec.encode(data.size))
            out.write(data)
        }
        if (version == TxVersion.V0) {
            out.write(ShortVec.encode(addressTableLookups.size))
            for (lookup in addressTableLookups) {
                out.write(lookup.table.bytes)
                val writable = lookup.writableIndexes
                out.write(ShortVec.encode(writable.size))
                writable.forEach { out.write(it) }
                val readonly = lookup.readonlyIndexes
                out.write(ShortVec.encode(readonly.size))
                readonly.forEach { out.write(it) }
            }
        }
        return out.toByteArray()
    }
}

/**
 * Compiles instructions into a legacy or v0 message and serializes the unsigned transaction
 * that MWA `signAndSendTransactions` takes (`compact-u16 n | n × 64 zero bytes | message`).
 *
 * Key order: the fee payer first, then writable signers, read-only signers, writable
 * non-signers, read-only non-signers; within a group, first appearance. A key used in several
 * places gets the union of its flags (signer or writable anywhere means signer or writable).
 * Program ids are read-only non-signers. Any valid order is accepted by the runtime; this one
 * is deterministic so golden bytes are stable.
 *
 * **Lookup tables (v0 only).** A key found in one of the given tables is loaded from the first
 * table that holds it instead of being carried in the message, unless it must be static: the fee
 * payer, any signer, and any program an instruction invokes. Loaded keys follow the static ones
 * in index space, every table's writable entries first, then every table's read-only ones, as
 * the runtime resolves them. Tables that end up unused are not referenced.
 */
object TransactionBuilder {
    /** Maximum serialized size of a legacy / v0 transaction (`PACKET_DATA_SIZE`). */
    const val PACKET_DATA_SIZE = 1232
    const val SIGNATURE_BYTES = 64

    fun compile(
        payer: Pubkey,
        instructions: List<Instruction>,
        recentBlockhash: ByteArray,
        version: TxVersion,
        lookupTables: List<AddressLookupTable> = emptyList(),
    ): CompiledMessage {
        require(instructions.isNotEmpty()) { "a transaction needs at least one instruction" }
        require(recentBlockhash.size == 32) { "blockhash is 32 bytes" }
        require(lookupTables.isEmpty() || version == TxVersion.V0) { "lookup tables need a v0 message" }
        require(lookupTables.map { it.address }.toSet().size == lookupTables.size) { "a lookup table is listed twice" }

        class Flags(var signer: Boolean, var writable: Boolean)
        val order = LinkedHashMap<Pubkey, Flags>()
        order[payer] = Flags(signer = true, writable = true)
        val programs = LinkedHashSet<Pubkey>()
        for (ix in instructions) {
            for (m in ix.accounts) {
                val f = order.getOrPut(m.pubkey) { Flags(signer = false, writable = false) }
                f.signer = f.signer || m.isSigner
                f.writable = f.writable || m.isWritable
            }
            programs += ix.programId
        }
        for (p in programs) {
            val f = order.getOrPut(p) { Flags(signer = false, writable = false) }
            require(!f.writable && !f.signer) { "program $p cannot also be a writable or signer account" }
        }

        // Which table, if any, each key is loaded from: never the payer, a signer or an invoked program.
        val tableOf = HashMap<Pubkey, Int>()
        if (lookupTables.isNotEmpty()) {
            for ((key, flags) in order) {
                if (flags.signer || key in programs) continue
                val table = lookupTables.indexOfFirst { key in it.addresses }
                if (table >= 0) tableOf[key] = table
            }
        }

        val static = order.entries.filter { it.key !in tableOf }
        fun group(signer: Boolean, writable: Boolean) =
            static.filter { it.value.signer == signer && it.value.writable == writable }.map { it.key }
        val writableSigners = group(signer = true, writable = true) // payer is first: inserted first
        val readonlySigners = group(signer = true, writable = false)
        val writableUnsigned = group(signer = false, writable = true)
        val readonlyUnsigned = group(signer = false, writable = false)
        val keys = writableSigners + readonlySigners + writableUnsigned + readonlyUnsigned
        require(keys.first() == payer)

        val lookups = ArrayList<AddressTableLookup>()
        val loadedWritable = ArrayList<Pubkey>()
        val loadedReadonly = ArrayList<Pubkey>()
        lookupTables.forEachIndexed { t, table ->
            val mine = order.entries.filter { tableOf[it.key] == t }
            if (mine.isEmpty()) return@forEachIndexed
            val writable = mine.filter { it.value.writable }.map { it.key }
            val readonly = mine.filterNot { it.value.writable }.map { it.key }
            lookups += AddressTableLookup(
                table.address,
                writable.map { table.addresses.indexOf(it) }.toIntArray(),
                readonly.map { table.addresses.indexOf(it) }.toIntArray(),
            )
            loadedWritable += writable
            loadedReadonly += readonly
        }

        val all = keys + loadedWritable + loadedReadonly
        require(all.size <= 256) { "too many accounts for u8 indexes" }
        val index = all.withIndex().associate { it.value to it.index }

        val compiled = instructions.map { ix ->
            CompiledInstruction(
                programIdIndex = index.getValue(ix.programId),
                accountIndexes = ix.accounts.map { index.getValue(it.pubkey) }.toIntArray(),
                data = ix.data,
            )
        }
        return CompiledMessage(
            version = version,
            numRequiredSignatures = writableSigners.size + readonlySigners.size,
            numReadonlySigned = readonlySigners.size,
            numReadonlyUnsigned = readonlyUnsigned.size,
            accountKeys = keys,
            recentBlockhash = recentBlockhash,
            instructions = compiled,
            addressTableLookups = lookups,
            loadedWritable = loadedWritable,
            loadedReadonly = loadedReadonly,
        )
    }

    /** The unsigned wire transaction (zeroed signatures); throws if it exceeds [PACKET_DATA_SIZE]. */
    fun unsignedTransaction(message: CompiledMessage): ByteArray {
        val tx = unsignedBytes(message)
        require(tx.size <= PACKET_DATA_SIZE) { "transaction is ${tx.size} bytes, over $PACKET_DATA_SIZE" }
        return tx
    }

    /** The serialized size of the unsigned transaction, whether or not it fits a packet. */
    fun unsignedSize(message: CompiledMessage): Int = unsignedBytes(message).size

    /** True when the unsigned transaction fits one packet. */
    fun fits(message: CompiledMessage): Boolean = unsignedSize(message) <= PACKET_DATA_SIZE

    private fun unsignedBytes(message: CompiledMessage): ByteArray {
        val body = message.serialize()
        val out = ByteArrayOutputStream()
        out.write(ShortVec.encode(message.numRequiredSignatures))
        out.write(ByteArray(SIGNATURE_BYTES * message.numRequiredSignatures))
        out.write(body)
        return out.toByteArray()
    }
}
