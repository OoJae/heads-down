package xyz.headsdown.core.chain

import com.solana.transaction.Message
import xyz.headsdown.core.chain.swap.JupiterGuard

/** One instruction of a serialized transaction, with its accounts resolved (static keys only). */
class DecodedInstruction(val program: Pubkey, val accounts: List<Pubkey>, val data: ByteArray) {
    val tag: Int get() = data[0].toInt() and 0xFF
}

/**
 * Reads back what a service handed to the wallet, with web3-solana's own message parser (the
 * library MWA wallets use), so a test asserts on the transaction itself rather than on the plan
 * that produced it.
 */
object TxInspect {
    /** The instructions of an unsigned transaction (one empty signature slot, then the message). */
    fun instructions(tx: ByteArray): List<DecodedInstruction> {
        val message = Message.from(tx.copyOfRange(1 + 64 * tx[0], tx.size))
        val keys = message.accounts.map { Pubkey(it.bytes) }
        return message.instructions.map { ix ->
            DecodedInstruction(
                program = keys[ix.programIdIndex.toInt() and 0xFF],
                // An index past the static keys is a lookup-table entry: not resolved here.
                accounts = ix.accountIndices.map { keys.getOrElse(it.toInt() and 0xFF) { Pubkey.DEFAULT } },
                data = ix.data,
            )
        }
    }

    /** `hd:<tag>`, `ore:<tag>`, `ata`, `cb`, `system`, `token`, `jupiter`, `ed25519`, or the program address. */
    fun tags(tx: ByteArray): List<String> = instructions(tx).map {
        when (it.program) {
            HeadsDownProgram.ID -> "hd:${it.tag}"
            Ore.PROGRAM_ID -> "ore:${it.tag}"
            WellKnown.ASSOCIATED_TOKEN -> "ata"
            WellKnown.COMPUTE_BUDGET -> "cb"
            WellKnown.SYSTEM_PROGRAM -> "system"
            WellKnown.SPL_TOKEN -> "token"
            WellKnown.ED25519_SIG_VERIFY -> "ed25519"
            JupiterGuard.JUPITER_V6 -> "jupiter"
            else -> it.program.toBase58()
        }
    }
}
