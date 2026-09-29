package xyz.headsdown.core.chain.tx

import xyz.headsdown.core.chain.Pubkey
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One account reference of an instruction. */
data class AccountMeta(val pubkey: Pubkey, val isSigner: Boolean, val isWritable: Boolean) {
    companion object {
        fun signer(pubkey: Pubkey, writable: Boolean = true) = AccountMeta(pubkey, isSigner = true, isWritable = writable)
        fun writable(pubkey: Pubkey) = AccountMeta(pubkey, isSigner = false, isWritable = true)
        fun readonly(pubkey: Pubkey) = AccountMeta(pubkey, isSigner = false, isWritable = false)
    }
}

/** An instruction: program, ordered account metas and opaque data. */
class Instruction(val programId: Pubkey, accounts: List<AccountMeta>, data: ByteArray) {
    val accounts: List<AccountMeta> = accounts.toList()
    private val bytes = data.copyOf()
    val data: ByteArray get() = bytes.copyOf()
    val dataSize: Int get() = bytes.size

    override fun equals(other: Any?): Boolean =
        other is Instruction && programId == other.programId && accounts == other.accounts && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = (programId.hashCode() * 31 + accounts.hashCode()) * 31 + bytes.contentHashCode()

    override fun toString(): String = "Instruction(program=$programId, accounts=${accounts.size}, data=${bytes.size} B)"
}

/** Little-endian instruction-data writer with an exact final size check. */
internal class DataWriter(size: Int) {
    private val buf: ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    fun u8(v: Int) = apply {
        require(v in 0..0xFF) { "u8 out of range: $v" }
        buf.put(v.toByte())
    }

    fun u16(v: Int) = apply {
        require(v in 0..0xFFFF) { "u16 out of range: $v" }
        buf.putShort(v.toShort())
    }

    fun u32(v: Long) = apply {
        require(v in 0..0xFFFF_FFFFL) { "u32 out of range: $v" }
        buf.putInt(v.toInt())
    }

    fun u64(v: ULong) = apply { buf.putLong(v.toLong()) }

    fun i64(v: Long) = apply { buf.putLong(v) }

    fun bytes(v: ByteArray) = apply { buf.put(v) }

    fun build(): ByteArray {
        check(buf.remaining() == 0) { "instruction data under-filled by ${buf.remaining()} bytes" }
        return buf.array()
    }
}
