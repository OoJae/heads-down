//! Everything heads_down knows about ORE, pinned to ORE commit `b92c5043`
//! (`docs/ORE.md`). ORE accounts are Steel accounts: byte 0 is the
//! discriminator, bytes 1..8 are zero, the `repr(C)` struct follows at 8.
//!
//! Every read goes through a checked accessor that first verifies owner ==
//! ORE, exact length and discriminator ("Level 1 layout pins"). Nothing here
//! panics on attacker-controlled bytes.

use pinocchio::{
    cpi::{invoke_signed, Signer},
    error::ProgramError,
    instruction::{seeds, InstructionAccount, InstructionView},
    AccountView, Address,
};

use crate::error::HdError;

/// ORE program `oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv`.
pub const ORE_PROGRAM_ID: Address = Address::new_from_array([
    0x0c, 0x00, 0xda, 0x38, 0xcd, 0x94, 0x4f, 0x5f, 0x9d, 0x39, 0xea, 0xaf, 0xa7, 0xb4, 0x6c, 0xe5,
    0x2b, 0xd7, 0xed, 0xc3, 0xb9, 0xa2, 0x76, 0xa4, 0x72, 0x2c, 0x2e, 0x2a, 0xae, 0x34, 0x89, 0x43,
]);
/// ORE Board `BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi` (`consts.rs:110`).
pub const BOARD_ADDRESS: Address = Address::new_from_array([
    0xa1, 0x4a, 0x68, 0xdb, 0xf8, 0x29, 0x78, 0x86, 0x9a, 0x3d, 0xda, 0xbb, 0x98, 0xc5, 0xe4, 0x45,
    0x0f, 0x47, 0x3c, 0xa0, 0x2b, 0x5d, 0xa1, 0xbb, 0xd7, 0x1a, 0xcc, 0x85, 0x7b, 0x47, 0xac, 0x93,
]);
/// ORE Config `9c9X7aDRAF41faiDs94ELjT19UrGnn72wBW9hPsS4Awy` (`consts.rs:116`).
pub const CONFIG_ADDRESS: Address = Address::new_from_array([
    0x7f, 0xde, 0x74, 0xd7, 0x7e, 0x51, 0x9a, 0xdb, 0x32, 0x02, 0x43, 0xd4, 0x56, 0xe8, 0xf3, 0xd8,
    0xc4, 0xc1, 0xde, 0x30, 0x98, 0xba, 0xd3, 0x74, 0x39, 0x3c, 0x50, 0xd1, 0xc6, 0x0a, 0x00, 0x80,
]);
/// ORE Treasury `45db2FSR4mcXdSVVZbKbwojU6uYDpMyhpEi7cC8nHaWG` (`consts.rs:113`).
pub const TREASURY_ADDRESS: Address = Address::new_from_array([
    0x2d, 0xc2, 0xc0, 0xa9, 0xd0, 0x9b, 0x91, 0xff, 0xce, 0x5d, 0x75, 0xc5, 0xf0, 0xd2, 0x2f, 0xe5,
    0x4a, 0x65, 0xec, 0xcb, 0xf1, 0x3a, 0x18, 0x5d, 0xcf, 0x7d, 0xd5, 0xa1, 0xd8, 0xda, 0x2e, 0x35,
]);
/// ORE entropy Var `BWCaDY96Xe4WkFq1M7UiCCRcChsJ3p51L5KrGzhxgm2E` (`consts.rs:104`).
pub const VAR_ADDRESS: Address = Address::new_from_array([
    0x9c, 0x0f, 0xcc, 0x4a, 0x7c, 0xf0, 0xd9, 0xba, 0x67, 0x33, 0xec, 0xaf, 0x85, 0xf2, 0x41, 0xf6,
    0xe4, 0xfa, 0x40, 0x3f, 0x9b, 0x2c, 0xd3, 0xb5, 0xbb, 0x79, 0xfa, 0x36, 0xea, 0xde, 0x9c, 0xbf,
]);
/// Entropy program `3jSkUuYBoJzQPMEzTvkDFXCZUBksPamrVhrnHR9igu2X` (owner of the Var).
pub const ENTROPY_PROGRAM_ID: Address = Address::new_from_array([
    0x28, 0x96, 0xe2, 0x11, 0xc9, 0x6b, 0x25, 0x35, 0x87, 0x31, 0x67, 0x47, 0x7f, 0xd3, 0x12, 0x92,
    0xf1, 0xf5, 0xbe, 0xa8, 0xc6, 0xb6, 0x8f, 0x42, 0x55, 0x8f, 0xb6, 0x7a, 0x28, 0x88, 0xe1, 0x50,
]);
/// System program.
pub const SYSTEM_PROGRAM_ID: Address = Address::new_from_array([0; 32]);

/// ORE PDA seeds (`consts.rs`).
pub const AUTOMATION_SEED: &[u8] = b"automation";
/// Miner seed.
pub const MINER_SEED: &[u8] = b"miner";
/// Round seed.
pub const ROUND_SEED: &[u8] = b"round";

/// `CHECKPOINT_FEE` (`consts.rs:89`).
pub const CHECKPOINT_FEE: u64 = 10_000;
/// One ORE in grams (`consts.rs:11`).
pub const ONE_ORE: u64 = 100_000_000_000;
/// Discretionary strategy (`state/automation.rs:78`).
pub const STRATEGY_DISCRETIONARY: u64 = 2;
/// ORE `Deploy` instruction tag (`instruction.rs:12`).
pub const DEPLOY_TAG: u8 = 6;
/// Number of board squares.
pub const SQUARES: usize = 25;

/// Steel discriminators and exact sizes (`state/mod.rs:21-28`, verified
/// against live mainnet accounts in `docs/ORE.md` section 1).
pub mod layout {
    /// Automation discriminator / size.
    pub const AUTOMATION_DISC: u8 = 100;
    /// Automation size.
    pub const AUTOMATION_LEN: usize = 160;
    /// Config discriminator.
    pub const CONFIG_DISC: u8 = 101;
    /// Config size.
    pub const CONFIG_LEN: usize = 232;
    /// Miner discriminator.
    pub const MINER_DISC: u8 = 103;
    /// Miner size.
    pub const MINER_LEN: usize = 752;
    /// Treasury discriminator.
    pub const TREASURY_DISC: u8 = 104;
    /// Treasury size.
    pub const TREASURY_LEN: usize = 48;
    /// Board discriminator.
    pub const BOARD_DISC: u8 = 105;
    /// Board size.
    pub const BOARD_LEN: usize = 40;
    /// Round discriminator.
    pub const ROUND_DISC: u8 = 109;
    /// Round size.
    pub const ROUND_LEN: usize = 952;

    /// Board field offsets (`state/board.rs`).
    pub const BOARD_ROUND_ID: usize = 8;
    /// Board start slot.
    pub const BOARD_START_SLOT: usize = 16;
    /// Board end slot.
    pub const BOARD_END_SLOT: usize = 24;
    /// Board production-cost EMA (lamports per whole ORE).
    pub const BOARD_EMA: usize = 32;
    /// Treasury motherlode (grams).
    pub const TREASURY_MOTHERLODE: usize = 8;
    /// Round id.
    pub const ROUND_ID: usize = 8;
    /// Round deployed[25].
    pub const ROUND_DEPLOYED: usize = 16;
    /// Automation amount (per-square cap).
    pub const AUTOMATION_AMOUNT: usize = 8;
    /// Automation authority.
    pub const AUTOMATION_AUTHORITY: usize = 16;
    /// Automation balance.
    pub const AUTOMATION_BALANCE: usize = 48;
    /// Automation executor.
    pub const AUTOMATION_EXECUTOR: usize = 56;
    /// Automation fee.
    pub const AUTOMATION_FEE: usize = 88;
    /// Automation strategy.
    pub const AUTOMATION_STRATEGY: usize = 96;
    /// Automation conditions.min_motherlode (u16, whole ORE).
    pub const AUTOMATION_MIN_MOTHERLODE: usize = 144;
    /// Automation conditions.max_motherlode (u16, whole ORE).
    pub const AUTOMATION_MAX_MOTHERLODE: usize = 146;
    /// Miner authority.
    pub const MINER_AUTHORITY: usize = 8;
    /// Miner checkpoint id.
    pub const MINER_CHECKPOINT_ID: usize = 48;
    /// Miner checkpoint fee reserve.
    pub const MINER_CHECKPOINT_FEE: usize = 56;
    /// Miner deployed[25].
    pub const MINER_DEPLOYED: usize = 64;
    /// Miner round id.
    pub const MINER_ROUND_ID: usize = 664;
    /// Config protocol.round_slots (display only).
    pub const CONFIG_ROUND_SLOTS: usize = 160;
}

/// Canonical description of every ORE layout fact heads_down relies on. Its
/// sha256 is `Config.ore_layout_hash`; `initialize_config` refuses any other
/// value, so a Config can only exist for a binary whose pins match.
/// Format: `"heads_down/ore-layout/v1"` then `(disc u8, len u16 LE)` for
/// Automation, Config, Miner, Treasury, Board, Round, then every field offset
/// read, as u16 LE, in the order of [`layout`].
pub const LAYOUT_PREIMAGE: [u8; 24 + 6 * 3 + 2 * 18] = {
    use layout::*;
    let mut out = [0u8; 24 + 6 * 3 + 2 * 18];
    let tag = b"heads_down/ore-layout/v1";
    let mut i = 0;
    while i < 24 {
        out[i] = tag[i];
        i += 1;
    }
    let pairs: [(u8, usize); 6] = [
        (AUTOMATION_DISC, AUTOMATION_LEN),
        (CONFIG_DISC, CONFIG_LEN),
        (MINER_DISC, MINER_LEN),
        (TREASURY_DISC, TREASURY_LEN),
        (BOARD_DISC, BOARD_LEN),
        (ROUND_DISC, ROUND_LEN),
    ];
    let mut p = 0;
    let mut o = 24;
    while p < 6 {
        out[o] = pairs[p].0;
        let len = (pairs[p].1 as u16).to_le_bytes();
        out[o + 1] = len[0];
        out[o + 2] = len[1];
        o += 3;
        p += 1;
    }
    let offsets: [usize; 18] = [
        BOARD_ROUND_ID,
        BOARD_START_SLOT,
        BOARD_END_SLOT,
        BOARD_EMA,
        TREASURY_MOTHERLODE,
        ROUND_ID,
        ROUND_DEPLOYED,
        AUTOMATION_AMOUNT,
        AUTOMATION_AUTHORITY,
        AUTOMATION_BALANCE,
        AUTOMATION_EXECUTOR,
        AUTOMATION_FEE,
        AUTOMATION_STRATEGY,
        AUTOMATION_MIN_MOTHERLODE,
        MINER_CHECKPOINT_ID,
        MINER_CHECKPOINT_FEE,
        MINER_DEPLOYED,
        MINER_ROUND_ID,
    ];
    let mut k = 0;
    while k < 18 {
        let b = (offsets[k] as u16).to_le_bytes();
        out[o] = b[0];
        out[o + 1] = b[1];
        o += 2;
        k += 1;
    }
    out
};

/// sha256 of [`LAYOUT_PREIMAGE`].
pub fn layout_hash() -> [u8; 32] {
    crate::hash::sha256(&[&LAYOUT_PREIMAGE])
}

// ---- checked little-endian readers -----------------------------------------

#[inline]
pub(crate) fn rd_u64(d: &[u8], off: usize) -> Result<u64, HdError> {
    let end = off.checked_add(8).ok_or(HdError::InvalidOreAccount)?;
    let b: [u8; 8] = d
        .get(off..end)
        .and_then(|s| s.try_into().ok())
        .ok_or(HdError::InvalidOreAccount)?;
    Ok(u64::from_le_bytes(b))
}

#[inline]
pub(crate) fn rd_u16(d: &[u8], off: usize) -> Result<u16, HdError> {
    let end = off.checked_add(2).ok_or(HdError::InvalidOreAccount)?;
    let b: [u8; 2] = d
        .get(off..end)
        .and_then(|s| s.try_into().ok())
        .ok_or(HdError::InvalidOreAccount)?;
    Ok(u16::from_le_bytes(b))
}

#[inline]
pub(crate) fn rd_addr(d: &[u8], off: usize) -> Result<[u8; 32], HdError> {
    let end = off.checked_add(32).ok_or(HdError::InvalidOreAccount)?;
    d.get(off..end)
        .and_then(|s| s.try_into().ok())
        .ok_or(HdError::InvalidOreAccount)
}

#[inline]
fn rd_u64x25(d: &[u8], off: usize) -> Result<[u64; SQUARES], HdError> {
    let mut out = [0u64; SQUARES];
    let mut at = off;
    for v in out.iter_mut() {
        *v = rd_u64(d, at)?;
        at = at.checked_add(8).ok_or(HdError::InvalidOreAccount)?;
    }
    Ok(out)
}

/// `true` when `account` is an ORE account of the given discriminator and
/// exact length.
#[inline]
pub fn is_ore_account(account: &AccountView, disc: u8, len: usize) -> bool {
    if !account.owned_by(&ORE_PROGRAM_ID) || account.data_len() != len {
        return false;
    }
    match account.try_borrow() {
        Ok(d) => d.first() == Some(&disc),
        Err(_) => false,
    }
}

#[inline]
fn check(account: &AccountView, disc: u8, len: usize) -> Result<(), HdError> {
    if is_ore_account(account, disc, len) {
        Ok(())
    } else {
        Err(HdError::InvalidOreAccount)
    }
}

/// The Board fields `dig` reads.
#[derive(Clone, Copy, Debug)]
pub struct Board {
    /// Current round id.
    pub round_id: u64,
    /// First slot of the round (after `reset`: `reset_slot + 1`).
    pub start_slot: u64,
    /// End slot (`u64::MAX` until the round's first deploy).
    pub end_slot: u64,
    /// `production_cost_ema`, lamports per whole ORE.
    pub ema: u64,
}

/// Read the Board: address, owner, length, discriminator, and sanity
/// (`ema > 0`, `end_slot == u64::MAX || end_slot > start_slot`).
pub fn read_board(account: &AccountView) -> Result<Board, HdError> {
    if account.address() != &BOARD_ADDRESS {
        return Err(HdError::InvalidOreAccount);
    }
    check(account, layout::BOARD_DISC, layout::BOARD_LEN)?;
    let d = account
        .try_borrow()
        .map_err(|_| HdError::InvalidOreAccount)?;
    let b = Board {
        round_id: rd_u64(&d, layout::BOARD_ROUND_ID)?,
        start_slot: rd_u64(&d, layout::BOARD_START_SLOT)?,
        end_slot: rd_u64(&d, layout::BOARD_END_SLOT)?,
        ema: rd_u64(&d, layout::BOARD_EMA)?,
    };
    if b.ema == 0 || (b.end_slot != u64::MAX && b.end_slot <= b.start_slot) {
        return Err(HdError::InvalidOreAccount);
    }
    Ok(b)
}

/// `Treasury.motherlode` (grams), after address / owner / length / disc checks.
pub fn read_motherlode(account: &AccountView) -> Result<u64, HdError> {
    if account.address() != &TREASURY_ADDRESS {
        return Err(HdError::InvalidOreAccount);
    }
    check(account, layout::TREASURY_DISC, layout::TREASURY_LEN)?;
    let d = account
        .try_borrow()
        .map_err(|_| HdError::InvalidOreAccount)?;
    rd_u64(&d, layout::TREASURY_MOTHERLODE)
}

/// ORE Config: address, owner, length, disc.
pub fn check_config(account: &AccountView) -> Result<(), HdError> {
    if account.address() != &CONFIG_ADDRESS {
        return Err(HdError::InvalidOreAccount);
    }
    check(account, layout::CONFIG_DISC, layout::CONFIG_LEN)
}

/// Check the Round: owner, length, disc, `id == round_id`, and address ==
/// `PDA(["round", round_id])` under ORE.
pub fn check_round(account: &AccountView, round_id: u64) -> Result<(), HdError> {
    check(account, layout::ROUND_DISC, layout::ROUND_LEN)?;
    {
        let d = account
            .try_borrow()
            .map_err(|_| HdError::InvalidOreAccount)?;
        if rd_u64(&d, layout::ROUND_ID)? != round_id {
            return Err(HdError::InvalidOreAccount);
        }
    }
    let id = round_id.to_le_bytes();
    let (pda, _) = crate::pda::find(&[ROUND_SEED, &id], &ORE_PROGRAM_ID);
    if account.address() != &pda {
        return Err(HdError::InvalidOreAccount);
    }
    Ok(())
}

/// Live `Round.deployed[25]` (re-read before every rig: it changes with
/// every deploy).
pub fn read_round_deployed(account: &AccountView) -> Result<[u64; SQUARES], HdError> {
    check(account, layout::ROUND_DISC, layout::ROUND_LEN)?;
    let d = account
        .try_borrow()
        .map_err(|_| HdError::InvalidOreAccount)?;
    rd_u64x25(&d, layout::ROUND_DEPLOYED)
}

/// The Automation fields `dig` reads.
#[derive(Clone, Copy, Debug)]
pub struct Automation {
    /// Per-square cap.
    pub amount: u64,
    /// Authority (the user's wallet).
    pub authority: [u8; 32],
    /// Lamports left.
    pub balance: u64,
    /// Executor.
    pub executor: [u8; 32],
    /// Fixed fee (Discretionary).
    pub fee: u64,
    /// Strategy.
    pub strategy: u64,
    /// `conditions.min_motherlode` (whole ORE).
    pub min_motherlode: u16,
    /// `conditions.max_motherlode` (whole ORE).
    pub max_motherlode: u16,
}

/// Read an Automation, or `None` if the account is not a live ORE
/// Automation (revoked / closed / never created).
pub fn read_automation(account: &AccountView) -> Result<Option<Automation>, HdError> {
    if !is_ore_account(account, layout::AUTOMATION_DISC, layout::AUTOMATION_LEN) {
        return Ok(None);
    }
    let d = account
        .try_borrow()
        .map_err(|_| HdError::InvalidOreAccount)?;
    Ok(Some(Automation {
        amount: rd_u64(&d, layout::AUTOMATION_AMOUNT)?,
        authority: rd_addr(&d, layout::AUTOMATION_AUTHORITY)?,
        balance: rd_u64(&d, layout::AUTOMATION_BALANCE)?,
        executor: rd_addr(&d, layout::AUTOMATION_EXECUTOR)?,
        fee: rd_u64(&d, layout::AUTOMATION_FEE)?,
        strategy: rd_u64(&d, layout::AUTOMATION_STRATEGY)?,
        min_motherlode: rd_u16(&d, layout::AUTOMATION_MIN_MOTHERLODE)?,
        max_motherlode: rd_u16(&d, layout::AUTOMATION_MAX_MOTHERLODE)?,
    }))
}

/// The Miner fields `dig` reads.
#[derive(Clone, Copy, Debug)]
pub struct Miner {
    /// Authority.
    pub authority: [u8; 32],
    /// Last checkpointed round.
    pub checkpoint_id: u64,
    /// Checkpoint fee reserve.
    pub checkpoint_fee: u64,
    /// Per-square SOL in `round_id`.
    pub deployed: [u64; SQUARES],
    /// Round the `deployed` array belongs to.
    pub round_id: u64,
}

impl Miner {
    /// Sum of `deployed` (checked).
    pub fn deployed_sum(&self) -> Result<u64, HdError> {
        self.deployed
            .iter()
            .try_fold(0u64, |acc, v| acc.checked_add(*v))
            .ok_or(HdError::MathOverflow)
    }

    /// Mask of squares with SOL in `deployed`.
    pub fn deployed_mask(&self) -> u32 {
        let mut m = 0u32;
        for (i, v) in self.deployed.iter().enumerate() {
            if *v > 0 {
                m |= 1u32 << (i as u32 & 31);
            }
        }
        m
    }
}

/// Read a Miner, or `None` if not a live ORE Miner.
pub fn read_miner(account: &AccountView) -> Result<Option<Miner>, HdError> {
    if !is_ore_account(account, layout::MINER_DISC, layout::MINER_LEN) {
        return Ok(None);
    }
    let d = account
        .try_borrow()
        .map_err(|_| HdError::InvalidOreAccount)?;
    Ok(Some(Miner {
        authority: rd_addr(&d, layout::MINER_AUTHORITY)?,
        checkpoint_id: rd_u64(&d, layout::MINER_CHECKPOINT_ID)?,
        checkpoint_fee: rd_u64(&d, layout::MINER_CHECKPOINT_FEE)?,
        deployed: rd_u64x25(&d, layout::MINER_DEPLOYED)?,
        round_id: rd_u64(&d, layout::MINER_ROUND_ID)?,
    }))
}

/// ORE's `Round::distribution_mask` (`state/round.rs:125-164`), bit for bit:
/// keccak256(round_id LE) seeds a Fisher-Yates shuffle of 0..25 using
/// successive u16 LE draws; the first 10 shuffled indices are the solo tiles
/// (bit set = solo, bit clear = split).
pub fn distribution_mask(round_id: u64) -> u32 {
    const BITS: usize = 10;
    let mut indices = [0u8; SQUARES];
    for (i, v) in indices.iter_mut().enumerate() {
        *v = i as u8;
    }
    let mut randomness = crate::hash::keccak256(&[&round_id.to_le_bytes()]);
    let mut offset = 0usize;
    // for i in (1..25).rev()
    let mut i = SQUARES - 1;
    while i >= 1 {
        if offset + 2 > randomness.len() {
            randomness = crate::hash::keccak256(&[&randomness]);
            offset = 0;
        }
        let r = match randomness.get(offset..offset + 2) {
            Some(&[a, b]) => u16::from_le_bytes([a, b]),
            _ => 0,
        };
        let j = (r as usize) % (i + 1);
        indices.swap(i, j);
        offset += 2;
        i -= 1;
    }
    let mut mask = 0u32;
    for &idx in indices.iter().take(BITS) {
        mask |= 1u32 << (idx as u32 & 31);
    }
    mask
}

/// The 12 accounts of ORE `deploy` (`deploy.rs:17`, `sdk.rs:110-157`):
/// signer, authority, automation, board, config, miner, round, treasury,
/// system, ORE, then the entropy tail var, entropy program. Always all 12:
/// ORE does `accounts.split_at(10)` and panics on fewer.
pub struct DeployAccounts<'a> {
    /// Executor PDA (signs via seeds).
    pub executor: &'a AccountView,
    /// `rig.authority` — never taken from instruction data.
    pub authority: &'a AccountView,
    /// Automation.
    pub automation: &'a AccountView,
    /// Board.
    pub board: &'a AccountView,
    /// ORE Config.
    pub config: &'a AccountView,
    /// Miner.
    pub miner: &'a AccountView,
    /// Round.
    pub round: &'a AccountView,
    /// Treasury.
    pub treasury: &'a AccountView,
    /// System program.
    pub system_program: &'a AccountView,
    /// ORE program.
    pub ore_program: &'a AccountView,
    /// Entropy Var.
    pub var: &'a AccountView,
    /// Entropy program.
    pub entropy_program: &'a AccountView,
}

/// CPI ORE `deploy(amount, mask)` signed by the Executor PDA. The program id
/// is the pinned constant, never an account the caller chose.
pub fn cpi_deploy(
    a: &DeployAccounts<'_>,
    amount: u64,
    mask: u32,
    executor_bump: u8,
) -> Result<(), ProgramError> {
    let mut data = [0u8; 13];
    data[0] = DEPLOY_TAG;
    let (amt, rest) = data[1..].split_at_mut(8);
    amt.copy_from_slice(&amount.to_le_bytes());
    rest.copy_from_slice(&mask.to_le_bytes());

    let metas = [
        InstructionAccount::writable_signer(a.executor.address()),
        InstructionAccount::writable(a.authority.address()),
        InstructionAccount::writable(a.automation.address()),
        InstructionAccount::writable(a.board.address()),
        InstructionAccount::writable(a.config.address()),
        InstructionAccount::writable(a.miner.address()),
        InstructionAccount::writable(a.round.address()),
        InstructionAccount::writable(a.treasury.address()),
        InstructionAccount::readonly(a.system_program.address()),
        InstructionAccount::readonly(a.ore_program.address()),
        InstructionAccount::writable(a.var.address()),
        InstructionAccount::readonly(a.entropy_program.address()),
    ];
    let ix = InstructionView {
        program_id: &ORE_PROGRAM_ID,
        data: &data,
        accounts: &metas,
    };
    let bump = [executor_bump];
    let signer_seeds = seeds!(crate::EXECUTOR_SEED, &bump);
    let signer = Signer::from(&signer_seeds);
    invoke_signed(
        &ix,
        &[
            a.executor,
            a.authority,
            a.automation,
            a.board,
            a.config,
            a.miner,
            a.round,
            a.treasury,
            a.system_program,
            a.ore_program,
            a.var,
            a.entropy_program,
        ],
        &[signer],
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use sha3::{Digest, Keccak256};

    /// `Round::distribution_mask` transcribed verbatim from ORE
    /// `api/src/state/round.rs:125-164` (commit b92c5043), with ORE's
    /// `solana_program::keccak::hashv` replaced by the `sha3` crate's
    /// Keccak-256 (an implementation independent of the program's hasher).
    #[allow(clippy::needless_range_loop)]
    fn ore_reference(id: u64) -> u32 {
        const BITS: u32 = 10;
        let keccak = |data: &[u8]| -> [u8; 32] { Keccak256::digest(data).into() };
        let rng = keccak(id.to_le_bytes().as_ref());
        let mut indices: [u8; 25] = [0; 25];
        for i in 0..25 {
            indices[i] = i as u8;
        }
        let mut randomness = rng;
        let mut random_offset = 0;
        for i in (1..25).rev() {
            if random_offset + 2 > randomness.len() {
                randomness = keccak(&randomness);
                random_offset = 0;
            }
            let mut two_bytes = [0u8; 2];
            two_bytes.copy_from_slice(&randomness[random_offset..random_offset + 2]);
            let r = u16::from_le_bytes(two_bytes);
            let j = (r as usize) % (i + 1);
            indices.swap(i, j);
            random_offset += 2;
        }
        let mut mask: u32 = 0;
        for &idx in &indices[..BITS as usize] {
            mask |= 1 << idx;
        }
        mask
    }

    #[test]
    fn distribution_mask_matches_ore_bit_for_bit() {
        // ORE's own unit properties (round.rs:191-240) plus equality with the
        // transcription over 20k ids, including the live fixture round.
        let mut seen = std::collections::HashSet::new();
        for id in (0..10_000u64)
            .chain(422_000..432_000)
            .chain([u64::MAX, 422_685])
        {
            let m = distribution_mask(id);
            assert_eq!(m, ore_reference(id), "round {id}");
            assert_eq!(m.count_ones(), 10, "round {id}");
            assert_eq!(m >> 25, 0, "round {id}");
            if id < 100 {
                seen.insert(m);
            }
        }
        assert!(seen.len() > 50);
    }

    #[test]
    fn readers_never_panic_on_short_data() {
        assert!(rd_u64(&[0u8; 7], 0).is_err());
        assert!(rd_u64(&[0u8; 8], usize::MAX).is_err());
        assert!(rd_u16(&[0u8; 1], 0).is_err());
        assert!(rd_addr(&[0u8; 31], 0).is_err());
        assert!(rd_u64x25(&[0u8; 199], 0).is_err());
        assert_eq!(
            rd_u64x25(&[1u8; 200], 0).unwrap()[24],
            u64::from_le_bytes([1; 8])
        );
    }
}
