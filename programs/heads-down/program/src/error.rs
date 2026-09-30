//! Program errors (`ProgramError::Custom(code)`).
//!
//! Codes 0..=31 are fixed by `INTERFACE.md` §8 (24..=31 were added for the
//! `dig` pre-flight skips and state-machine violations; they are frozen in
//! v1.1). Codes 32..=48 are the additive v1.2 SKR codes (§11.7). Errors
//! raised inside the shared crates keep their own namespaces:
//! `p256-introspect` = `0x2560_00xx`, `sgt-verify` = `0x5347_00xx`.

use pinocchio::error::ProgramError;

/// Every error this program raises itself.
#[repr(u32)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HdError {
    /// Unknown tag, wrong data length, malformed field, wrong account count.
    InvalidInstruction = 0,
    /// The Motherlode-aware production-cost gate is closed.
    CostGate = 1,
    /// Not the Executor PDA, the executor float was drained, or the rig's
    /// ORE Automation does not name the Executor PDA.
    InvalidExecutor = 2,
    /// An ORE account failed its address / owner / length / discriminator /
    /// relationship check, or ORE behaved outside its pinned semantics.
    InvalidOreAccount = 3,
    /// A heads_down account has the wrong owner, tag, version or size.
    InvalidAccountTag = 4,
    /// Signer or authority mismatch.
    Unauthorized = 5,
    /// Heartbeat fields are invalid (future round, zero lease, no precompile).
    InvalidHeartbeat = 6,
    /// P-256 counter is not strictly greater than the rig's last counter.
    StaleHeartbeat = 7,
    /// No heartbeat lease covers the current ORE round.
    LeaseExpired = 8,
    /// The rig already dug in this ORE round.
    AlreadyDugRound = 9,
    /// Wallet-signed caps have expired.
    CapsExpired = 10,
    /// Now is outside the armed plan window.
    OutsideWindow = 11,
    /// No budget left under the per-round / per-shift / per-week caps.
    BudgetExhausted = 12,
    /// The rig is not in a state that can dig.
    RigNotArmed = 13,
    /// The rig is frozen.
    RigFrozen = 14,
    /// The plan exceeds the wallet-signed caps.
    PlanExceedsCaps = 15,
    /// SGT / seat relationship mismatch (the SGT checks themselves return
    /// `sgt-verify`'s precise `0x5347_xxxx` codes).
    InvalidSgt = 16,
    /// The seat points at another rig that was not supplied for re-pointing.
    SeatTaken = 17,
    /// Config is paused (circuit breaker).
    Paused = 18,
    /// The config timelock has not elapsed.
    TimelockNotElapsed = 19,
    /// Checked arithmetic overflowed.
    MathOverflow = 20,
    /// The Ed25519 registrar attestation is missing, malformed or expired.
    InvalidAttestation = 21,
    /// The same rig appears twice in a batch.
    DuplicateRig = 22,
    /// The Automation's strategy is not Discretionary or its fee is not
    /// `config.executor_fee`.
    StrategyMismatch = 23,

    // ---- added after v1, frozen in INTERFACE.md v1.1 ----------------------
    /// The instruction is not valid in the rig's current state (arm while a
    /// shift is open, end a shift that is not open, unfreeze a live rig, ...).
    InvalidRigState = 24,
    /// Pre-flight: ORE's round window is closed (`start_slot <= slot < end_slot`).
    RoundNotActive = 25,
    /// Pre-flight: the Miner has not checkpointed its previous round (ORE
    /// would `assert!`-panic and abort the whole batch).
    MinerNotCheckpointed = 26,
    /// Pre-flight: the Automation's Motherlode conditions fail, so ORE would
    /// silently return without deploying.
    MotherlodeCondition = 27,
    /// Pre-flight: the Automation cannot cover `per_tile * k + fee`.
    InsufficientAutomationBalance = 28,
    /// Post-CPI: ORE returned Ok but deployed nothing.
    OreNoOp = 29,
    /// The armed plan is focus-only; it never deploys.
    FocusOnly = 30,
    /// Pre-flight: the Executor PDA cannot fund ORE's CHECKPOINT_FEE top-up
    /// and stay rent-exempt.
    ExecutorUnderfunded = 31,

    // ---- added in INTERFACE.md v1.2 (SKR), additive ------------------------
    /// An SKR / ORE token account is not a classic SPL Token account (for
    /// example Token-2022), has the wrong length, mint, owner field or
    /// state, is not the canonical ATA where one is required, or carries a
    /// delegate / close authority.
    InvalidTokenAccount = 32,
    /// A bond, gift or purchase amount is zero or above its cap.
    AmountOutOfRange = 33,
    /// `open_stack` parameters are invalid (window, grace, seats, flags).
    InvalidStackParams = 34,
    /// The table does not take joins: it started, is full, or is not open.
    StackJoinClosed = 35,
    /// The rig does not meet the table's tier / attestation / SGT rule.
    StackIneligible = 36,
    /// `settle_stack` before `Board.round_id > end_round`.
    StackNotEnded = 37,
    /// The table is not in the state the instruction needs.
    InvalidStackState = 38,
    /// A seat does not belong to this table or rig, a seat is missing or
    /// repeated at settle, or the seat address is not canonical.
    StackSeatMismatch = 39,
    /// Check-in skip: the rig is in another shift than the one the seat is
    /// bound to.
    StackShiftMismatch = 40,
    /// Check-in skip: the rig's plan allows leases longer than one round.
    StackLeaseTooLong = 41,
    /// Check-in result: the bound shift recorded a BREAK or FREEZE; the seat
    /// is broken for good.
    StackSeatBroken = 42,
    /// The bonded shift's ShiftLog does not allow this release / forfeit
    /// (not sealed yet, or sealed with the other outcome).
    BondNotResolvable = 43,
    /// The claimer is not the gift's recipient (or its SGT's holder).
    GiftNotClaimable = 44,
    /// `refund_gift` before expiry, or `claim_gift` at or after it.
    GiftExpiry = 45,
    /// The Bury lot is empty or smaller than the requested amount.
    AuctionEmpty = 46,
    /// The current price exceeds the buyer's `max_ore` (slippage guard).
    PriceAboveMax = 47,
    /// After the ORE `bury` CPI, the vault did not lose exactly the paid ORE
    /// or the ORE supply did not drop by the burned 90%.
    BuryMismatch = 48,
}

impl HdError {
    /// The `ProgramError::Custom` code.
    #[inline]
    pub const fn code(self) -> u32 {
        self as u32
    }
}

impl From<HdError> for ProgramError {
    #[inline]
    fn from(e: HdError) -> Self {
        ProgramError::Custom(e as u32)
    }
}

/// The `u32` carried by a `RigSkipped` event for `err`: the custom code when
/// there is one, otherwise a stable code for the builtin variant.
pub fn skip_code(err: &ProgramError) -> u32 {
    match err {
        ProgramError::Custom(c) => *c,
        other => u32::MAX.saturating_sub(builtin_index(other)),
    }
}

fn builtin_index(err: &ProgramError) -> u32 {
    match err {
        ProgramError::InvalidArgument => 1,
        ProgramError::InvalidInstructionData => 2,
        ProgramError::InvalidAccountData => 3,
        ProgramError::AccountBorrowFailed => 4,
        ProgramError::MissingRequiredSignature => 5,
        ProgramError::ArithmeticOverflow => 6,
        _ => 0,
    }
}
