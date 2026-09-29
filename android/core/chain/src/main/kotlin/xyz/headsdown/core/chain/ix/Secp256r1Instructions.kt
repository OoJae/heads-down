package xyz.headsdown.core.chain.ix

import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.tx.DataWriter
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.keys.P256
import xyz.headsdown.core.keys.RigMessageFormat
import xyz.headsdown.core.keys.SignedRigMessage

/**
 * `Secp256r1SigVerify` precompile instruction (SIMD-0075), byte-identical to
 * `p256_introspect::client::build_instruction_data`:
 *
 * ```
 * [0] num_signatures u8 (1..=8) | [1] padding 0 | n × 14-byte offsets records | payload
 * record: signature_offset, signature_ix, public_key_offset, public_key_ix,
 *         message_offset, message_size, message_ix   (7 × u16 LE)
 * payload per entry: pubkey(33) ‖ signature(64) ‖ message(32)
 * ```
 * Every `*_ix` field is [CURRENT_INSTRUCTION] (0xFFFF = "this instruction"), which is what
 * `p256-introspect` requires: offsets may never point into another instruction.
 */
object Secp256r1Instructions {
    const val MAX_SIGNATURES = 8
    const val CURRENT_INSTRUCTION = 0xFFFF
    private const val OFFSETS_START = 2
    private const val OFFSETS_SIZE = 14

    fun verify(entries: List<SignedRigMessage<*>>): Instruction {
        require(entries.size in 1..MAX_SIGNATURES) { "1..$MAX_SIGNATURES signatures per precompile instruction" }
        val entrySize = P256.COMPRESSED_PUBLIC_KEY_BYTES + P256.RAW_SIGNATURE_BYTES + RigMessageFormat.DIGEST_BYTES
        val headerLen = OFFSETS_START + entries.size * OFFSETS_SIZE
        val total = headerLen + entries.size * entrySize
        val w = DataWriter(total).u8(entries.size).u8(0)
        entries.forEachIndexed { i, e ->
            require(P256.isLowS(e.signature)) { "the precompile rejects high-S" }
            val keyOffset = headerLen + i * entrySize
            val sigOffset = keyOffset + P256.COMPRESSED_PUBLIC_KEY_BYTES
            val msgOffset = sigOffset + P256.RAW_SIGNATURE_BYTES
            w.u16(sigOffset).u16(CURRENT_INSTRUCTION)
                .u16(keyOffset).u16(CURRENT_INSTRUCTION)
                .u16(msgOffset).u16(RigMessageFormat.DIGEST_BYTES).u16(CURRENT_INSTRUCTION)
        }
        for (e in entries) w.bytes(e.publicKey).bytes(e.signature).bytes(e.message)
        return Instruction(WellKnown.SECP256R1_SIG_VERIFY, emptyList(), w.build())
    }
}
