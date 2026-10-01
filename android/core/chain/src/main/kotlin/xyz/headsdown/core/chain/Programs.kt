package xyz.headsdown.core.chain

/** Runtime programs and sysvars (INTERFACE, "External programs and accounts"). */
object WellKnown {
    val SYSTEM_PROGRAM = Pubkey.fromBase58("11111111111111111111111111111111")
    val COMPUTE_BUDGET = Pubkey.fromBase58("ComputeBudget111111111111111111111111111111")
    val INSTRUCTIONS_SYSVAR = Pubkey.fromBase58("Sysvar1nstructions1111111111111111111111111")
    val SECP256R1_SIG_VERIFY = Pubkey.fromBase58("Secp256r1SigVerify1111111111111111111111111")
    val ED25519_SIG_VERIFY = Pubkey.fromBase58("Ed25519SigVerify111111111111111111111111111")
    val TOKEN_2022 = Pubkey.fromBase58("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")

    /** Classic SPL Token: the only token program SKR, ORE and wrapped SOL use (INTERFACE §11.1). */
    val SPL_TOKEN = Pubkey.fromBase58("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    val ASSOCIATED_TOKEN = Pubkey.fromBase58("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
    val ADDRESS_LOOKUP_TABLE = Pubkey.fromBase58("AddressLookupTab1e1111111111111111111111111")

    /** Wrapped SOL: the "SOL" side of a swap. */
    val WRAPPED_SOL_MINT = Pubkey.fromBase58("So11111111111111111111111111111111111111112")
}

/**
 * Associated token accounts: `ATA(owner, mint) = find_program_address([owner, token_program,
 * mint], ATA program)`. Every heads_down vault is one (INTERFACE §11.2), and so are the wallet's
 * own SKR / ORE accounts the phone uses.
 */
object AssociatedToken {
    fun address(owner: Pubkey, mint: Pubkey, tokenProgram: Pubkey = WellKnown.SPL_TOKEN): ProgramAddress =
        Pda.find(listOf(owner.bytes, tokenProgram.bytes, mint.bytes), WellKnown.ASSOCIATED_TOKEN)
}

/**
 * SKR (INTERFACE §11.1): a classic SPL Token mint with 6 decimals. In Heads Down it is bond and
 * gift currency only. The caps are the program's compile-time constants (`program/src/skr.rs`);
 * the golden vectors pin them (`pinned_fork.skr_v1_2`).
 */
object Skr {
    val MINT: Pubkey = Pubkey.fromBase58("SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3")
    const val DECIMALS = 6
    const val ONE_SKR: ULong = 1_000_000uL

    /** Bond cap per seat at an in-person table. */
    const val STACK_BOND_CAP: ULong = 2_000_000_000uL

    /** Bond cap per seat at a remote table. */
    const val REMOTE_BOND_CAP: ULong = 1_000_000_000uL

    /** Above this, a seat needs a verified Seeker rig (SGT re-checked at join). */
    const val GUEST_BOND_CAP: ULong = 500_000_000uL

    /** Largest Focus Bond. */
    const val FOCUS_BOND_CAP: ULong = 5_000_000_000uL

    /** The wallet's own SKR account. */
    fun account(owner: Pubkey): Pubkey = AssociatedToken.address(owner, MINT).address
}

/** Gift a Rig limits (`program/src/skr.rs`). */
object GiftLimits {
    /** Largest gift escrow: 10 SOL. */
    const val MAX_LAMPORTS: ULong = 10_000_000_000uL

    /** Claims are accepted before `created_ts + 30 days`; refunds from then on. */
    const val EXPIRY_SECONDS: Long = 30L * 86_400
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
    private val STACK_SEED = "stack".toByteArray(Charsets.US_ASCII)
    private val STACK_SEAT_SEED = "stackseat".toByteArray(Charsets.US_ASCII)
    private val BOND_SEED = "bond".toByteArray(Charsets.US_ASCII)
    private val GIFT_SEED = "gift".toByteArray(Charsets.US_ASCII)
    private val BURY_SEED = "bury".toByteArray(Charsets.US_ASCII)

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

    // ---- v1.2 (INTERFACE §11.2) ----------------------------------------------------------

    /** `[b"stack", host, table_id u64 LE]`: the host is in the seeds, so a table id cannot be squatted. */
    fun stackTable(host: Pubkey, tableId: ULong): ProgramAddress =
        Pda.find(listOf(STACK_SEED, host.bytes, tableId.leBytes()), ID)

    /**
     * `[b"stackseat", table, key]`: `key` is the rig's SGT mint at a remote table (one seat per
     * Seeker) and the rig address at an in-person table (one seat per rig).
     */
    fun stackSeat(table: Pubkey, key: Pubkey): ProgramAddress =
        Pda.find(listOf(STACK_SEAT_SEED, table.bytes, key.bytes), ID)

    /** `[b"bond", rig, shift_id u64 LE]`: one Focus Bond per shift. */
    fun focusBond(rig: Pubkey, shiftId: ULong): ProgramAddress =
        Pda.find(listOf(BOND_SEED, rig.bytes, shiftId.leBytes()), ID)

    /** `[b"gift", sender, nonce u64 LE]`: the sender is in the seeds; the recipient is stored. */
    fun giftEscrow(sender: Pubkey, nonce: ULong): ProgramAddress =
        Pda.find(listOf(GIFT_SEED, sender.bytes, nonce.leBytes()), ID)

    /** `[b"bury"]`: where every SKR forfeit goes to be auctioned for ORE that ORE's `bury` burns. */
    val buryVault: ProgramAddress by lazy { Pda.find(listOf(BURY_SEED), ID) }

    /** The SKR vault of a table, bond or the BuryVault: the canonical SPL Token ATA of that PDA. */
    fun skrVault(ownerPda: Pubkey): Pubkey = AssociatedToken.address(ownerPda, Skr.MINT).address
}

/** ORE v3 (pinned; `docs/ORE.md` §1). Addresses are compile-time constants, never from data. */
object Ore {
    val PROGRAM_ID: Pubkey = Pubkey.fromBase58("oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv")
    val BOARD: Pubkey = Pubkey.fromBase58("BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi")
    val CONFIG: Pubkey = Pubkey.fromBase58("9c9X7aDRAF41faiDs94ELjT19UrGnn72wBW9hPsS4Awy")
    val TREASURY: Pubkey = Pubkey.fromBase58("45db2FSR4mcXdSVVZbKbwojU6uYDpMyhpEi7cC8nHaWG")
    val ENTROPY_VAR: Pubkey = Pubkey.fromBase58("BWCaDY96Xe4WkFq1M7UiCCRcChsJ3p51L5KrGzhxgm2E")
    val ENTROPY_PROGRAM: Pubkey = Pubkey.fromBase58("3jSkUuYBoJzQPMEzTvkDFXCZUBksPamrVhrnHR9igu2X")

    /** The ORE mint: classic SPL Token, 11 decimals (`consts.rs:71`). */
    val MINT: Pubkey = Pubkey.fromBase58("oreoU2P8bN6jkk3jbaiVxYnG1dCXcYxwhwyK9jSybcp")
    const val DECIMALS = 11

    /** Paid into the Miner by `automate` when it has none (`consts.rs:89`). */
    const val CHECKPOINT_FEE: Long = 10_000

    /** ORE amounts use 11 decimals. */
    const val ONE_ORE: Long = 100_000_000_000

    /** `claim_ore` basis points: 10,000 claims everything (`consts.rs:83`). */
    const val DENOMINATOR_BPS = 10_000

    private val AUTOMATION_SEED = "automation".toByteArray(Charsets.US_ASCII)
    private val MINER_SEED = "miner".toByteArray(Charsets.US_ASCII)
    private val ROUND_SEED = "round".toByteArray(Charsets.US_ASCII)

    fun automation(authority: Pubkey): ProgramAddress = Pda.find(listOf(AUTOMATION_SEED, authority.bytes), PROGRAM_ID)

    fun miner(authority: Pubkey): ProgramAddress = Pda.find(listOf(MINER_SEED, authority.bytes), PROGRAM_ID)

    fun round(roundId: ULong): ProgramAddress = Pda.find(listOf(ROUND_SEED, roundId.leBytes()), PROGRAM_ID)

    /** The Treasury's ORE token account: where `claim_ore` pays from (`sdk.rs::claim_ore`). */
    val treasuryTokens: Pubkey by lazy { AssociatedToken.address(TREASURY, MINT).address }

    /** The wallet's own ORE account: where `claim_ore` and the buy leg deliver. */
    fun account(owner: Pubkey): Pubkey = AssociatedToken.address(owner, MINT).address
}
