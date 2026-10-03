package xyz.headsdown.core.chain.ix

import xyz.headsdown.core.chain.HeadsDownProgram
import xyz.headsdown.core.chain.Ore
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.WellKnown
import xyz.headsdown.core.chain.tx.AccountMeta
import xyz.headsdown.core.chain.tx.DataWriter
import xyz.headsdown.core.chain.tx.Instruction
import xyz.headsdown.core.keys.P256
import xyz.headsdown.core.keys.ShiftEndReason
import xyz.headsdown.core.keys.ShiftPlan

/** Wallet-signed caps (`Rig.cap_*`). Lamports, lamports per ORE, and unix seconds. */
data class RigCaps(
    val capWeek: ULong,
    val capShift: ULong,
    /** Per round, **including** the Automation fee (INTERFACE v1.1 §6.4). */
    val capRound: ULong,
    /** Ceiling on the pot-adjusted cost `ema_ev`, lamports per ORE. */
    val capMaxCost: ULong,
    val capsExpiryTs: Long,
) {
    init {
        // The program does not relate the caps to each other; the client keeps them coherent.
        require(capRound <= capShift && capShift <= capWeek) { "caps must satisfy round <= shift <= week" }
        require(capsExpiryTs > 0) { "caps need an expiry" }
    }

    /** INTERFACE: the plan must fit the caps (`plan_max_ev_cost <= cap_max_cost`, `dig <= cap_round`). */
    fun admits(plan: ShiftPlan): Boolean =
        plan.maxEvCost <= capMaxCost && plan.digLamports <= capRound
}

/**
 * The P-256 authorization tail for instructions a rig key may sign instead of the wallet
 * (PLAN / BREAK / FREEZE): `counter u64 | p256_ix u8 | p256_sig_index u8`, **counter first**
 * (INTERFACE v1.1 §5). [precompileIx] is the absolute top-level index of the
 * `Secp256r1SigVerify` instruction in the transaction (compute-budget instructions count).
 */
data class P256Auth(val counter: ULong, val precompileIx: Int, val sigIndex: Int) {
    init {
        require(precompileIx in 0..0xFE) { "precompile instruction index must be 0..254" }
        require(sigIndex in 0 until Secp256r1Instructions.MAX_SIGNATURES) { "signature index must be 0..7" }
    }
}

/**
 * A registrar voucher referenced by `register_rig` / `rotate_key` (INTERFACE v1.1 §4.2): the
 * Ed25519SigVerify instruction at top-level index [ed25519Ix] carries the registrar's signature
 * over the 111-byte `HDreg` preimage. Level 0 is refused on-chain: register without a voucher.
 */
data class RegistrarAttestation(val ed25519Ix: Int, val ed25519SigIndex: Int, val level: Int, val expirySlot: ULong) {
    init {
        require(ed25519Ix in 0..0xFE) { "Ed25519 instruction index must be 0..254" }
        require(ed25519SigIndex in 0..0xFF) { "Ed25519 signature index must be a u8" }
        require(level in 1..2) { "attestation level is 1 (TEE) or 2 (StrongBox); level 0 registers without a voucher" }
    }
}

/**
 * `heads_down` instruction builders, byte for byte `programs/heads-down/INTERFACE.md` v1.1 §5 and
 * the executed golden vectors in `programs/heads-down/vectors/instructions.json` (checked by
 * `GoldenInstructionsTest` against a drift-checked copy in the test resources).
 *
 * Account order is the program's: the wallet path of `arm_shift` / `break_shift` / `freeze_rig`
 * / `unfreeze_rig` is `rig (w) | authority (s)`; the P-256 path passes the authority **unsigned**
 * (it must still equal `rig.authority`) and the fee payer is not an instruction account.
 *
 * Security properties the builders keep:
 * - the Rig address is always the PDA `[b"rig", authority]`, never a free parameter;
 * - no bump, executor address or amount the program must derive itself is ever sent;
 * - every value is range-checked, so an invalid instruction is never built.
 */
object HeadsDownInstructions {
    const val TAG_REGISTER_RIG = 1
    const val TAG_VERIFY_SEEKER = 2
    const val TAG_SET_CAPS = 3
    const val TAG_ROTATE_KEY = 4
    const val TAG_ARM_SHIFT = 5
    const val TAG_BREAK_SHIFT = 8
    const val TAG_FREEZE_RIG = 9
    const val TAG_UNFREEZE_RIG = 10
    const val TAG_END_SHIFT = 11
    const val TAG_CLOSE_RIG = 14
    const val TAG_CLOSE_SHIFT_LOG = 31

    /** Authorization-mode byte after the tag of dual-path instructions. */
    const val AUTH_WALLET = 0
    const val AUTH_P256 = 1

    /** `register_rig` / `rotate_key` data length without / with a voucher. */
    const val KEY_BODY_BYTES = 35
    const val KEY_BODY_ATTESTED_BYTES = 46

    /** The P-256 tail: `counter u64 | p256_ix u8 | p256_sig_index u8`. */
    const val P256_TAIL_BYTES = 10

    private val programId get() = HeadsDownProgram.ID

    /**
     * `register_rig` (tag 1), 35 or 46 bytes:
     * `tag | p256_pubkey[33] | has_attestation u8 [| ed25519_ix u8 | ed25519_sig_index u8 | level u8 | expiry_slot u64]`.
     *
     * Accounts: `authority (s,w: pays rent) | rig (w) | config | system [| instructions sysvar, with a voucher]`.
     */
    fun registerRig(authority: Pubkey, p256Pubkey: ByteArray, attestation: RegistrarAttestation? = null): Instruction {
        val accounts = mutableListOf(
            AccountMeta.signer(authority),
            AccountMeta.writable(rigOf(authority)),
            AccountMeta.readonly(HeadsDownProgram.config.address),
            AccountMeta.readonly(WellKnown.SYSTEM_PROGRAM),
        )
        if (attestation != null) accounts += AccountMeta.readonly(WellKnown.INSTRUCTIONS_SYSVAR)
        return Instruction(programId, accounts, keyBody(TAG_REGISTER_RIG, p256Pubkey, attestation))
    }

    /**
     * `rotate_key` (tag 4), same body as [registerRig]. Used when the Rig exists but this install
     * holds a different Keystore key (reinstall, new phone), or to attach a voucher to the same
     * key. Without a voucher the rig's attestation level resets to 0. `hb_counter` is kept.
     *
     * Accounts: `authority (s) | rig (w) | config [| instructions sysvar, with a voucher]`.
     */
    fun rotateKey(authority: Pubkey, p256Pubkey: ByteArray, attestation: RegistrarAttestation? = null): Instruction {
        val accounts = mutableListOf(
            AccountMeta.signer(authority, writable = false),
            AccountMeta.writable(rigOf(authority)),
            AccountMeta.readonly(HeadsDownProgram.config.address),
        )
        if (attestation != null) accounts += AccountMeta.readonly(WellKnown.INSTRUCTIONS_SYSVAR)
        return Instruction(programId, accounts, keyBody(TAG_ROTATE_KEY, p256Pubkey, attestation))
    }

    /**
     * `verify_seeker` (tag 2), 1 byte. The wallet proves it holds a Seeker Genesis Token.
     *
     * Accounts: `authority (s,w) | rig (w) | seeker_seat (w) = ["seeker", mint] | sgt token account |
     * sgt mint | system [| previous rig (w), when the seat points at another rig]`.
     */
    fun verifySeeker(authority: Pubkey, sgtMint: Pubkey, sgtTokenAccount: Pubkey, previousRig: Pubkey? = null): Instruction {
        val accounts = mutableListOf(
            AccountMeta.signer(authority),
            AccountMeta.writable(rigOf(authority)),
            AccountMeta.writable(HeadsDownProgram.seekerSeat(sgtMint).address),
            AccountMeta.readonly(sgtTokenAccount),
            AccountMeta.readonly(sgtMint),
            AccountMeta.readonly(WellKnown.SYSTEM_PROGRAM),
        )
        if (previousRig != null) {
            // Only a seat that points at ANOTHER rig needs it (INTERFACE v1.1 §5, verify_seeker).
            require(previousRig != rigOf(authority)) { "previous rig must not be the authority's own rig" }
            accounts += AccountMeta.writable(previousRig)
        }
        return Instruction(programId, accounts, byteArrayOf(TAG_VERIFY_SEEKER.toByte()))
    }

    /**
     * `set_caps` (tag 3), 41 bytes:
     * `tag | cap_week u64 | cap_shift u64 | cap_round u64 | cap_max_cost u64 | caps_expiry_ts i64`.
     *
     * Accounts: `authority (s) | rig (w)`.
     */
    fun setCaps(authority: Pubkey, caps: RigCaps): Instruction {
        val data = DataWriter(41)
            .u8(TAG_SET_CAPS)
            .u64(caps.capWeek)
            .u64(caps.capShift)
            .u64(caps.capRound)
            .u64(caps.capMaxCost)
            .i64(caps.capsExpiryTs)
            .build()
        return Instruction(
            programId,
            listOf(AccountMeta.signer(authority, writable = false), AccountMeta.writable(rigOf(authority))),
            data,
        )
    }

    /**
     * `arm_shift` (tag 5), wallet path, 38 bytes: `tag | mode=0 | plan(36)` where plan is
     * `max_ev_cost u64 | dig_lamports u64 | split u8 | solo u8 | lease u8 | flags u8 |
     * window_start i64 | window_end i64` (the PLAN preimage field order).
     *
     * Accounts: `rig (w) | authority (s) | ORE Board`.
     */
    fun armShift(authority: Pubkey, plan: ShiftPlan): Instruction {
        val data = DataWriter(2 + ShiftPlan.ENCODED_BYTES)
            .u8(TAG_ARM_SHIFT)
            .u8(AUTH_WALLET)
            .bytes(plan.encode())
            .build()
        return Instruction(
            programId,
            listOf(
                AccountMeta.writable(rigOf(authority)),
                AccountMeta.signer(authority, writable = false),
                AccountMeta.readonly(Ore.BOARD),
            ),
            data,
        )
    }

    /**
     * `arm_shift` (tag 5), P-256 PLAN path, 48 bytes: `tag | mode=1 | plan(36) | counter u64 |
     * p256_ix u8 | p256_sig_index u8`.
     *
     * Accounts: `rig (w) | authority (unsigned) | ORE Board | instructions sysvar`.
     */
    fun armShiftP256(authority: Pubkey, plan: ShiftPlan, auth: P256Auth): Instruction {
        val data = DataWriter(2 + ShiftPlan.ENCODED_BYTES + P256_TAIL_BYTES)
            .u8(TAG_ARM_SHIFT)
            .u8(AUTH_P256)
            .bytes(plan.encode())
            .p256Tail(auth)
            .build()
        return Instruction(
            programId,
            listOf(
                AccountMeta.writable(rigOf(authority)),
                AccountMeta.readonly(authority),
                AccountMeta.readonly(Ore.BOARD),
                AccountMeta.readonly(WellKnown.INSTRUCTIONS_SYSVAR),
            ),
            data,
        )
    }

    /** `break_shift` (tag 8), wallet path: `tag | mode=0 | reason u8`. Accounts: `rig (w) | authority (s)`. */
    fun breakShift(authority: Pubkey, reason: ShiftEndReason): Instruction {
        requireBreakReason(reason)
        return walletRigInstruction(authority, DataWriter(3).u8(TAG_BREAK_SHIFT).u8(AUTH_WALLET).u8(reason.wire).build())
    }

    /**
     * `break_shift` (tag 8), P-256 BREAK path, 13 bytes: `tag | mode=1 | reason u8 | counter u64 |
     * p256_ix u8 | p256_sig_index u8`. Accounts: `rig (w) | authority (unsigned) | instructions sysvar`.
     */
    fun breakShiftP256(authority: Pubkey, reason: ShiftEndReason, auth: P256Auth): Instruction {
        requireBreakReason(reason)
        return p256RigInstruction(authority, DataWriter(13).u8(TAG_BREAK_SHIFT).u8(AUTH_P256).u8(reason.wire).p256Tail(auth).build())
    }

    /** `freeze_rig` (tag 9), wallet path: `tag | mode=0 | reason u8` (the program ignores the reason). */
    fun freezeRig(authority: Pubkey, reason: ShiftEndReason = ShiftEndReason.FREEZE): Instruction =
        walletRigInstruction(authority, DataWriter(3).u8(TAG_FREEZE_RIG).u8(AUTH_WALLET).u8(reason.wire).build())

    /** `freeze_rig` (tag 9), P-256 FREEZE path: same shape as [breakShiftP256]; the signed reason is 3. */
    fun freezeRigP256(authority: Pubkey, auth: P256Auth, reason: ShiftEndReason = ShiftEndReason.FREEZE): Instruction =
        p256RigInstruction(authority, DataWriter(13).u8(TAG_FREEZE_RIG).u8(AUTH_P256).u8(reason.wire).p256Tail(auth).build())

    /** `unfreeze_rig` (tag 10): `tag`. Wallet only. Accounts: `rig (w) | authority (s)`. */
    fun unfreezeRig(authority: Pubkey): Instruction = walletRigInstruction(authority, byteArrayOf(TAG_UNFREEZE_RIG.toByte()))

    /**
     * `end_shift` (tag 11): `tag`. Callable by the authority at any time, or by anyone once the
     * plan window has ended and the lease expired. [shiftId] is the Rig's current `shift_id`: the
     * ShiftLog it writes is `[b"shift", rig, shift_id]`.
     *
     * Accounts: `caller (s,w: pays the ShiftLog rent) | rig (w) | shift_log (w) | ORE Board | system`.
     */
    fun endShift(caller: Pubkey, rig: Pubkey, shiftId: ULong): Instruction = Instruction(
        programId,
        listOf(
            AccountMeta.signer(caller),
            AccountMeta.writable(rig),
            AccountMeta.writable(HeadsDownProgram.shiftLog(rig, shiftId).address),
            AccountMeta.readonly(Ore.BOARD),
            AccountMeta.readonly(WellKnown.SYSTEM_PROGRAM),
        ),
        byteArrayOf(TAG_END_SHIFT.toByte()),
    )

    /**
     * `close_shift_log` (tag 31, v1.3): `tag`. Anyone may send it from 30 days after the shift
     * ended; the log's rent (1,781,760 lamports) goes only to [rentRecipient], which must be the
     * address that paid it: the wallet that ended its own shift, or the crank that sealed it. For a
     * log sealed before v1.3 it is the rig's authority. The shift's Focus Bond, if any, must be
     * released or forfeited first.
     *
     * Accounts: `shift_log (w) | rent_recipient (w) | focus_bond PDA of (rig, shift_id)`.
     */
    fun closeShiftLog(rig: Pubkey, shiftId: ULong, rentRecipient: Pubkey): Instruction = Instruction(
        programId,
        listOf(
            AccountMeta.writable(HeadsDownProgram.shiftLog(rig, shiftId).address),
            AccountMeta.writable(rentRecipient),
            AccountMeta.readonly(HeadsDownProgram.focusBond(rig, shiftId).address),
        ),
        byteArrayOf(TAG_CLOSE_SHIFT_LOG.toByte()),
    )

    /**
     * `close_rig` (tag 14): `tag`. Requires state Idle/Frozen; rent returns to the authority.
     * Accounts: `authority (s,w) | rig (w) [| seeker_seat (w) for a seeker-tier rig]`.
     */
    fun closeRig(authority: Pubkey, sgtMint: Pubkey? = null): Instruction {
        val accounts = mutableListOf(
            AccountMeta.signer(authority),
            AccountMeta.writable(rigOf(authority)),
        )
        if (sgtMint != null) accounts += AccountMeta.writable(HeadsDownProgram.seekerSeat(sgtMint).address)
        return Instruction(programId, accounts, byteArrayOf(TAG_CLOSE_RIG.toByte()))
    }

    // ------------------------------------------------------------------------------ helpers

    private fun rigOf(authority: Pubkey): Pubkey = HeadsDownProgram.rig(authority).address

    private fun requireBreakReason(reason: ShiftEndReason) =
        require(reason.isBreakReason) { "break_shift accepts reasons 1, 2, 4, 5, 6, 7, 8 (was ${reason.wire})" }

    private fun keyBody(tag: Int, p256Pubkey: ByteArray, attestation: RegistrarAttestation?): ByteArray {
        require(p256Pubkey.size == P256.COMPRESSED_PUBLIC_KEY_BYTES) { "p256 key must be 33-byte compressed" }
        P256.decompress(p256Pubkey) // throws unless it is a point on P-256 (02/03 prefix included)
        if (attestation == null) {
            return DataWriter(KEY_BODY_BYTES).u8(tag).bytes(p256Pubkey).u8(0).build()
        }
        return DataWriter(KEY_BODY_ATTESTED_BYTES)
            .u8(tag)
            .bytes(p256Pubkey)
            .u8(1)
            .u8(attestation.ed25519Ix)
            .u8(attestation.ed25519SigIndex)
            .u8(attestation.level)
            .u64(attestation.expirySlot)
            .build()
    }

    private fun walletRigInstruction(authority: Pubkey, data: ByteArray) = Instruction(
        programId,
        listOf(
            AccountMeta.writable(rigOf(authority)),
            AccountMeta.signer(authority, writable = false),
        ),
        data,
    )

    private fun p256RigInstruction(authority: Pubkey, data: ByteArray) = Instruction(
        programId,
        listOf(
            AccountMeta.writable(rigOf(authority)),
            AccountMeta.readonly(authority),
            AccountMeta.readonly(WellKnown.INSTRUCTIONS_SYSVAR),
        ),
        data,
    )

    /** INTERFACE v1.1: `counter u64 | p256_ix u8 | p256_sig_index u8` (counter first). */
    private fun DataWriter.p256Tail(auth: P256Auth) = u64(auth.counter).u8(auth.precompileIx).u8(auth.sigIndex)
}
