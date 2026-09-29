package xyz.headsdown.core.chain.tx

import xyz.headsdown.core.chain.Pubkey
import java.io.ByteArrayOutputStream

/** Wire version of the message. v0 is sent without address lookup tables. */
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

/** A compiled, unsigned message: header, ordered keys, blockhash and compiled instructions. */
class CompiledMessage internal constructor(
    val version: TxVersion,
    val numRequiredSignatures: Int,
    val numReadonlySigned: Int,
    val numReadonlyUnsigned: Int,
    val accountKeys: List<Pubkey>,
    recentBlockhash: ByteArray,
    val instructions: List<CompiledInstruction>,
) {
    private val blockhash = recentBlockhash.copyOf()
    val recentBlockhash: ByteArray get() = blockhash.copyOf()

    val feePayer: Pubkey get() = accountKeys.first()

    fun isSigner(index: Int): Boolean = index < numRequiredSignatures

    fun isWritable(index: Int): Boolean = if (index < numRequiredSignatures) {
        index < numRequiredSignatures - numReadonlySigned
    } else {
        index < accountKeys.size - numReadonlyUnsigned
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
        if (version == TxVersion.V0) out.write(ShortVec.encode(0)) // no address table lookups
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
 */
object TransactionBuilder {
    /** Maximum serialized size of a legacy / v0 transaction (`PACKET_DATA_SIZE`). */
    const val PACKET_DATA_SIZE = 1232
    const val SIGNATURE_BYTES = 64

    fun compile(payer: Pubkey, instructions: List<Instruction>, recentBlockhash: ByteArray, version: TxVersion): CompiledMessage {
        require(instructions.isNotEmpty()) { "a transaction needs at least one instruction" }
        require(recentBlockhash.size == 32) { "blockhash is 32 bytes" }

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

        val entries = order.entries.toList()
        fun group(signer: Boolean, writable: Boolean) =
            entries.filter { it.value.signer == signer && it.value.writable == writable }.map { it.key }
        val writableSigners = group(signer = true, writable = true) // payer is first: inserted first
        val readonlySigners = group(signer = true, writable = false)
        val writableUnsigned = group(signer = false, writable = true)
        val readonlyUnsigned = group(signer = false, writable = false)
        val keys = writableSigners + readonlySigners + writableUnsigned + readonlyUnsigned
        require(keys.first() == payer)
        require(keys.size <= 256) { "too many accounts for u8 indexes" }
        val index = keys.withIndex().associate { it.value to it.index }

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
        )
    }

    /** The unsigned wire transaction (zeroed signatures); throws if it exceeds [PACKET_DATA_SIZE]. */
    fun unsignedTransaction(message: CompiledMessage): ByteArray {
        val body = message.serialize()
        val out = ByteArrayOutputStream()
        out.write(ShortVec.encode(message.numRequiredSignatures))
        out.write(ByteArray(SIGNATURE_BYTES * message.numRequiredSignatures))
        out.write(body)
        val tx = out.toByteArray()
        require(tx.size <= PACKET_DATA_SIZE) { "transaction is ${tx.size} bytes, over $PACKET_DATA_SIZE" }
        return tx
    }
}
