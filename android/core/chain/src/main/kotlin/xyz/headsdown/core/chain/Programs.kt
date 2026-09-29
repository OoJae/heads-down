package xyz.headsdown.core.chain

/** Runtime programs and sysvars (INTERFACE, "External programs and accounts"). */
object WellKnown {
    val SYSTEM_PROGRAM = Pubkey.fromBase58("11111111111111111111111111111111")
    val COMPUTE_BUDGET = Pubkey.fromBase58("ComputeBudget111111111111111111111111111111")
    val INSTRUCTIONS_SYSVAR = Pubkey.fromBase58("Sysvar1nstructions1111111111111111111111111")
    val SECP256R1_SIG_VERIFY = Pubkey.fromBase58("Secp256r1SigVerify1111111111111111111111111")
    val ED25519_SIG_VERIFY = Pubkey.fromBase58("Ed25519SigVerify111111111111111111111111111")
    val TOKEN_2022 = Pubkey.fromBase58("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
}

/**
 * The `heads_down` program and its PDAs (INTERFACE, "PDAs"). Exported as `HeadsDownProgram.ID`
 * per the contract.
 */
object HeadsDownProgram {
    val ID: Pubkey = Pubkey.fromBase58("HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p")

    private val CONFIG_SEED = "config".toByteArray(Charsets.US_ASCII)
    private val EXECUTOR_SEED = "executor".toByteArray(Charsets.US_ASCII)
    private val RIG_SEED = "rig".toByteArray(Charsets.US_ASCII)
    private val SEEKER_SEED = "seeker".toByteArray(Charsets.US_ASCII)
    private val SHIFT_SEED = "shift".toByteArray(Charsets.US_ASCII)

    /** `[b"config"]`, owned by heads_down. */
    val config: ProgramAddress by lazy { Pda.find(listOf(CONFIG_SEED), ID) }

    /** `[b"executor"]`: a data-less, System-owned PDA; the ORE Automation's executor. */
    val executor: ProgramAddress by lazy { Pda.find(listOf(EXECUTOR_SEED), ID) }

    /** `[b"rig", authority]`: `authority` is the wallet that owns the ORE Automation. */
    fun rig(authority: Pubkey): ProgramAddress = Pda.find(listOf(RIG_SEED, authority.bytes), ID)

    /** `[b"seeker", sgt_mint]`. */
    fun seekerSeat(sgtMint: Pubkey): ProgramAddress = Pda.find(listOf(SEEKER_SEED, sgtMint.bytes), ID)

    /** `[b"shift", rig, shift_id u64 LE]`. */
    fun shiftLog(rig: Pubkey, shiftId: ULong): ProgramAddress =
        Pda.find(listOf(SHIFT_SEED, rig.bytes, shiftId.leBytes()), ID)
}

/** ORE v3 (pinned; `docs/ORE.md` §1). Addresses are compile-time constants, never from data. */
object Ore {
    val PROGRAM_ID: Pubkey = Pubkey.fromBase58("oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv")
    val BOARD: Pubkey = Pubkey.fromBase58("BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi")
    val CONFIG: Pubkey = Pubkey.fromBase58("9c9X7aDRAF41faiDs94ELjT19UrGnn72wBW9hPsS4Awy")
    val TREASURY: Pubkey = Pubkey.fromBase58("45db2FSR4mcXdSVVZbKbwojU6uYDpMyhpEi7cC8nHaWG")
    val ENTROPY_VAR: Pubkey = Pubkey.fromBase58("BWCaDY96Xe4WkFq1M7UiCCRcChsJ3p51L5KrGzhxgm2E")
    val ENTROPY_PROGRAM: Pubkey = Pubkey.fromBase58("3jSkUuYBoJzQPMEzTvkDFXCZUBksPamrVhrnHR9igu2X")

    /** Paid into the Miner by `automate` when it has none (`consts.rs:89`). */
    const val CHECKPOINT_FEE: Long = 10_000

    /** ORE amounts use 11 decimals. */
    const val ONE_ORE: Long = 100_000_000_000

    private val AUTOMATION_SEED = "automation".toByteArray(Charsets.US_ASCII)
    private val MINER_SEED = "miner".toByteArray(Charsets.US_ASCII)
    private val ROUND_SEED = "round".toByteArray(Charsets.US_ASCII)

    fun automation(authority: Pubkey): ProgramAddress = Pda.find(listOf(AUTOMATION_SEED, authority.bytes), PROGRAM_ID)

    fun miner(authority: Pubkey): ProgramAddress = Pda.find(listOf(MINER_SEED, authority.bytes), PROGRAM_ID)

    fun round(roundId: ULong): ProgramAddress = Pda.find(listOf(ROUND_SEED, roundId.leBytes()), PROGRAM_ID)
}
