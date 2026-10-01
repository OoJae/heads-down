//! **TEST FIXTURE. Never deploy.** A mock of `heads_down::dig` (tag 6) written from the frozen
//! contract `programs/heads-down/INTERFACE.md` v1.1, so the crank's dig transactions can be
//! executed against the live mainnet ORE binary in LiteSVM and on a local validator without
//! building the real program. The real program is the security boundary and the reference:
//! `cargo test --features real-program` runs the same suite against it.
//!
//! It follows v1.1 §6: the account checks that fail the transaction, the per-rig order of the
//! skip checks, the precise skip codes (0..=31, and p256-introspect's `0x2560_00xx` unchanged),
//! Cooling digging only with a fresh heartbeat in the same entry, "leases only move forward",
//! the fee reserved inside every cap (`budget = min(plan_dig, min(caps) − fee)`), `k =
//! popcount(mask)` with the Miner's held squares excluded, the fee due only on the rig's first
//! deploy of the round, the ORE pre-flights (25, 26, 27, 28, 31), the post-CPI accounting
//! (`RigDug.lamports` = SOL on squares; `spent_*` = the whole debit; 29 on an ORE no-op) and
//! the reimbursement floor `rent(0) + 100,000 + crank_fee`. It implements `dig` only.
#![no_std]

use p256_introspect::verify_secp256r1_signature;
use pinocchio::{
    cpi::{invoke_signed, Signer},
    error::ProgramError,
    instruction::{seeds, InstructionAccount, InstructionView},
    no_allocator, nostd_panic_handler, program_entrypoint,
    sysvars::{clock::Clock, Sysvar},
    AccountView, Address, ProgramResult,
};
use pinocchio_system::instructions::Transfer;

program_entrypoint!(process_instruction);
no_allocator!();
nostd_panic_handler!();

const ORE: Address = Address::from_str_const("oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv");
const BOARD: Address = Address::from_str_const("BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi");
const ORE_CONFIG: Address = Address::from_str_const("9c9X7aDRAF41faiDs94ELjT19UrGnn72wBW9hPsS4Awy");
const TREASURY: Address = Address::from_str_const("45db2FSR4mcXdSVVZbKbwojU6uYDpMyhpEi7cC8nHaWG");
const VAR: Address = Address::from_str_const("BWCaDY96Xe4WkFq1M7UiCCRcChsJ3p51L5KrGzhxgm2E");
const ENTROPY: Address = Address::from_str_const("3jSkUuYBoJzQPMEzTvkDFXCZUBksPamrVhrnHR9igu2X");
const SYSTEM: Address = Address::from_str_const("11111111111111111111111111111111");
const IX_SYSVAR: Address = Address::from_str_const("Sysvar1nstructions1111111111111111111111111");

const CHECKPOINT_FEE: u64 = 10_000;
const EXECUTOR_RESERVE: u64 = 10 * CHECKPOINT_FEE;
const RENT_EXEMPT_ZERO: u64 = 890_880;
const WEEK_SECS: i64 = 7 * 24 * 3600;
const ONE_ORE: u64 = 100_000_000_000;

// INTERFACE v1.1 §8 error codes.
const E_INVALID_INSTRUCTION: u32 = 0;
const E_COST_GATE: u32 = 1;
const E_INVALID_EXECUTOR: u32 = 2;
const E_INVALID_ORE_ACCOUNT: u32 = 3;
const E_INVALID_ACCOUNT_TAG: u32 = 4;
const E_UNAUTHORIZED: u32 = 5;
const E_INVALID_HEARTBEAT: u32 = 6;
const E_STALE_HEARTBEAT: u32 = 7;
const E_LEASE_EXPIRED: u32 = 8;
const E_ALREADY_DUG: u32 = 9;
const E_CAPS_EXPIRED: u32 = 10;
const E_OUTSIDE_WINDOW: u32 = 11;
const E_BUDGET: u32 = 12;
const E_NOT_ARMED: u32 = 13;
const E_FROZEN: u32 = 14;
const E_PAUSED: u32 = 18;
const E_MATH: u32 = 20;
const E_DUPLICATE_RIG: u32 = 22;
const E_STRATEGY: u32 = 23;
const E_ROUND_NOT_ACTIVE: u32 = 25;
const E_MINER_NOT_CHECKPOINTED: u32 = 26;
const E_MOTHERLODE: u32 = 27;
const E_INSUFFICIENT_BALANCE: u32 = 28;
const E_ORE_NOOP: u32 = 29;
const E_FOCUS_ONLY: u32 = 30;
const E_EXECUTOR_UNDERFUNDED: u32 = 31;

fn err(code: u32) -> ProgramError {
    ProgramError::Custom(code)
}

enum RigErr {
    Skip(u32),
    Fatal(ProgramError),
}

impl From<ProgramError> for RigErr {
    fn from(e: ProgramError) -> Self {
        RigErr::Fatal(e)
    }
}

fn skip_code(e: ProgramError) -> u32 {
    match e {
        ProgramError::Custom(c) => c,
        _ => u32::MAX,
    }
}

fn rd<const N: usize>(d: &[u8], off: usize) -> Result<[u8; N], ProgramError> {
    d.get(off..off + N)
        .and_then(|s| s.try_into().ok())
        .ok_or(ProgramError::InvalidAccountData)
}
fn rd_u64(d: &[u8], off: usize) -> Result<u64, ProgramError> {
    rd::<8>(d, off).map(u64::from_le_bytes)
}
fn rd_i64(d: &[u8], off: usize) -> Result<i64, ProgramError> {
    rd::<8>(d, off).map(i64::from_le_bytes)
}
fn rd_u32(d: &[u8], off: usize) -> Result<u32, ProgramError> {
    rd::<4>(d, off).map(u32::from_le_bytes)
}
fn rd_u16(d: &[u8], off: usize) -> Result<u16, ProgramError> {
    rd::<2>(d, off).map(u16::from_le_bytes)
}
fn rd_u8(d: &[u8], off: usize) -> Result<u8, ProgramError> {
    d.get(off).copied().ok_or(ProgramError::InvalidAccountData)
}
fn wr(d: &mut [u8], off: usize, v: &[u8]) -> Result<(), ProgramError> {
    d.get_mut(off..off + v.len())
        .ok_or(ProgramError::InvalidAccountData)?
        .copy_from_slice(v);
    Ok(())
}

fn sha256(parts: &[&[u8]]) -> [u8; 32] {
    let mut out = [0u8; 32];
    #[cfg(target_os = "solana")]
    unsafe {
        pinocchio::syscalls::sol_sha256(parts.as_ptr() as *const u8, parts.len() as u64, out.as_mut_ptr());
    }
    #[cfg(not(target_os = "solana"))]
    let _ = parts;
    out
}

fn keccak(parts: &[&[u8]]) -> [u8; 32] {
    let mut out = [0u8; 32];
    #[cfg(target_os = "solana")]
    unsafe {
        pinocchio::syscalls::sol_keccak256(parts.as_ptr() as *const u8, parts.len() as u64, out.as_mut_ptr());
    }
    #[cfg(not(target_os = "solana"))]
    let _ = parts;
    out
}

fn log_data(bytes: &[u8]) {
    #[cfg(target_os = "solana")]
    unsafe {
        let parts: [&[u8]; 1] = [bytes];
        pinocchio::syscalls::sol_log_data(parts.as_ptr() as *const u8, 1);
    }
    #[cfg(not(target_os = "solana"))]
    let _ = bytes;
}

/// ORE `distribution_mask` (`state/round.rs:125-164`): bit = 1 marks a solo square.
fn distribution_mask(round_id: u64) -> u32 {
    let mut rnd = keccak(&[&round_id.to_le_bytes()]);
    let mut idx = [0u8; 25];
    for (i, v) in idx.iter_mut().enumerate() {
        *v = i as u8;
    }
    let mut off = 0usize;
    let mut i = 24usize;
    while i >= 1 {
        if off + 2 > 32 {
            rnd = keccak(&[&rnd]);
            off = 0;
        }
        let r = u16::from_le_bytes([rnd[off], rnd[off + 1]]);
        let j = (r as usize) % (i + 1);
        idx.swap(i, j);
        off += 2;
        i -= 1;
    }
    let mut m = 0u32;
    for &k in &idx[..10] {
        m |= 1 << k;
    }
    m
}

/// The program's `logic::select_tiles`: stable order by `deployed` (ties → lowest index),
/// skipping `exclude`, taking `solo` solo squares and `split` split squares.
fn select_tiles(deployed: &[u64; 25], solo_mask: u32, exclude: u32, split: u8, solo: u8) -> u32 {
    let mut order = [0usize; 25];
    for (i, v) in order.iter_mut().enumerate() {
        *v = i;
    }
    for i in 1..25 {
        let mut j = i;
        while j > 0 && deployed[order[j - 1]] > deployed[order[j]] {
            order.swap(j - 1, j);
            j -= 1;
        }
    }
    let (mut want_split, mut want_solo) = (split, solo);
    let mut mask = 0u32;
    for &i in order.iter() {
        let bit = 1u32 << i;
        if exclude & bit != 0 {
            continue;
        }
        if solo_mask & bit != 0 {
            if want_solo > 0 {
                mask |= bit;
                want_solo -= 1;
            }
        } else if want_split > 0 {
            mask |= bit;
            want_split -= 1;
        }
        if want_split == 0 && want_solo == 0 {
            break;
        }
    }
    mask
}

/// `ema · 6 · 500 · 10^11 / (5 · (500 · 10^11 + pot))`, u128.
fn ema_ev(ema: u64, pot: u64) -> u128 {
    let num = (ema as u128).saturating_mul(6 * 500 * 100_000_000_000);
    let den = (500u128 * 100_000_000_000).saturating_add(pot as u128).saturating_mul(5);
    num / den
}

fn check_ore(acc: &AccountView, len: usize, disc: u8) -> Result<(), ProgramError> {
    if !acc.owned_by(&ORE) {
        return Err(err(E_INVALID_ORE_ACCOUNT));
    }
    let d = acc.try_borrow()?;
    if d.len() != len || d.first() != Some(&disc) {
        return Err(err(E_INVALID_ORE_ACCOUNT));
    }
    Ok(())
}

fn is_ore(acc: &AccountView, len: usize, disc: u8) -> bool {
    acc.owned_by(&ORE) && acc.data_len() == len && acc.try_borrow().map(|d| d.first() == Some(&disc)).unwrap_or(false)
}

pub fn process_instruction(program_id: &Address, accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    match data.split_first() {
        Some((&6, args)) => dig(program_id, accounts, args),
        _ => Err(err(E_INVALID_INSTRUCTION)),
    }
}

struct Ctx<'a> {
    program_id: &'a Address,
    cranker: &'a AccountView,
    executor: &'a AccountView,
    board: &'a AccountView,
    ore_config: &'a AccountView,
    round: &'a AccountView,
    treasury: &'a AccountView,
    system: &'a AccountView,
    ore_program: &'a AccountView,
    var: &'a AccountView,
    entropy: &'a AccountView,
    ix_sysvar: &'a AccountView,
    round_id: u64,
    ema_ev: u128,
    solo_mask: u32,
    pot: u64,
    executor_fee: u64,
    crank_fee: u64,
    executor_bump: u8,
    slot: u64,
    now: i64,
}

fn dig(program_id: &Address, accounts: &mut [AccountView], args: &[u8]) -> ProgramResult {
    let (&n, body) = args.split_first().ok_or(err(E_INVALID_INSTRUCTION))?;
    let n = n as usize;
    if n == 0 || n > 32 || body.len() != n * 20 || accounts.len() != 12 + 4 * n {
        return Err(err(E_INVALID_INSTRUCTION));
    }
    let (fixed, rigs) = accounts.split_at_mut(12);
    let [cranker, config, executor, board, ore_config, round, treasury, system, ore_program, var, entropy, ix_sysvar] =
        fixed
    else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    if !cranker.is_signer() {
        return Err(ProgramError::MissingRequiredSignature);
    }
    let (executor_fee, crank_fee, paused, executor_bump) = {
        if !config.owned_by(program_id) {
            return Err(err(E_INVALID_ACCOUNT_TAG));
        }
        let d = config.try_borrow()?;
        if d.len() != 256 || d[0] != 1 || d[1] != 1 {
            return Err(err(E_INVALID_ACCOUNT_TAG));
        }
        let cfg_pda = Address::create_program_address(&[b"config", &[d[2]]], program_id)
            .map_err(|_| err(E_INVALID_ACCOUNT_TAG))?;
        if config.address() != &cfg_pda {
            return Err(err(E_INVALID_ACCOUNT_TAG));
        }
        (rd_u64(&d, 80)?, rd_u64(&d, 72)?, d[90] != 0, d[91])
    };
    // v1.1 §6.6: pausing fails the whole transaction before any rig.
    if paused {
        return Err(err(E_PAUSED));
    }
    let exec_pda = Address::create_program_address(&[b"executor", &[executor_bump]], program_id)
        .map_err(|_| err(E_INVALID_EXECUTOR))?;
    if executor.address() != &exec_pda || !executor.owned_by(&SYSTEM) || executor.data_len() != 0 {
        return Err(err(E_INVALID_EXECUTOR));
    }
    if ore_program.address() != &ORE
        || board.address() != &BOARD
        || treasury.address() != &TREASURY
        || ore_config.address() != &ORE_CONFIG
        || var.address() != &VAR
        || entropy.address() != &ENTROPY
        || system.address() != &SYSTEM
    {
        return Err(err(E_INVALID_ORE_ACCOUNT));
    }
    if ix_sysvar.address() != &IX_SYSVAR {
        return Err(err(0x2560_0001));
    }
    check_ore(board, 40, 105)?;
    check_ore(treasury, 48, 104)?;
    check_ore(round, 952, 109)?;
    let (round_id, ema) = {
        let d = board.try_borrow()?;
        (rd_u64(&d, 8)?, rd_u64(&d, 32)?)
    };
    let pot = rd_u64(&treasury.try_borrow()?, 8)?;
    let round_pda = Address::find_program_address(&[b"round", &round_id.to_le_bytes()], &ORE).0;
    {
        let d = round.try_borrow()?;
        if round.address() != &round_pda || rd_u64(&d, 8)? != round_id {
            return Err(err(E_INVALID_ORE_ACCOUNT));
        }
    }
    for i in 0..n {
        for j in (i + 1)..n {
            if rigs[4 * i].address() == rigs[4 * j].address() {
                return Err(err(E_DUPLICATE_RIG));
            }
        }
    }
    let clock = Clock::get()?;
    let ctx = Ctx {
        program_id,
        cranker: &*cranker,
        executor: &*executor,
        board: &*board,
        ore_config: &*ore_config,
        round: &*round,
        treasury: &*treasury,
        system: &*system,
        ore_program: &*ore_program,
        var: &*var,
        entropy: &*entropy,
        ix_sysvar: &*ix_sysvar,
        round_id,
        ema_ev: ema_ev(ema, pot),
        solo_mask: distribution_mask(round_id),
        pot,
        executor_fee,
        crank_fee,
        executor_bump,
        slot: clock.slot,
        now: clock.unix_timestamp,
    };
    for i in 0..n {
        let entry = &body[i * 20..(i + 1) * 20];
        let group = &mut rigs[4 * i..4 * i + 4];
        let rig_addr = *group[0].address();
        match dig_one(&ctx, group, entry) {
            Ok((lamports, mask)) => {
                let mut ev = [0u8; 61];
                ev[0] = 1;
                ev[1..33].copy_from_slice(rig_addr.as_ref());
                ev[33..41].copy_from_slice(&round_id.to_le_bytes());
                ev[41..49].copy_from_slice(&lamports.to_le_bytes());
                ev[49..53].copy_from_slice(&mask.to_le_bytes());
                ev[53..61].copy_from_slice(&u64::try_from(ctx.ema_ev).unwrap_or(u64::MAX).to_le_bytes());
                log_data(&ev);
            }
            Err(RigErr::Skip(code)) => {
                let mut ev = [0u8; 45];
                ev[0] = 2;
                ev[1..33].copy_from_slice(rig_addr.as_ref());
                ev[33..41].copy_from_slice(&round_id.to_le_bytes());
                ev[41..45].copy_from_slice(&code.to_le_bytes());
                log_data(&ev);
            }
            Err(RigErr::Fatal(e)) => return Err(e),
        }
    }
    Ok(())
}

struct MinerView {
    authority: Address,
    checkpoint_id: u64,
    checkpoint_fee: u64,
    round_id: u64,
    sum: u64,
    mask: u32,
}

fn read_miner(miner: &AccountView) -> Result<Option<MinerView>, ProgramError> {
    if !is_ore(miner, 752, 103) {
        return Ok(None);
    }
    let d = miner.try_borrow()?;
    let mut sum = 0u64;
    let mut mask = 0u32;
    for i in 0..25 {
        let v = rd_u64(&d, 64 + 8 * i)?;
        sum = sum.checked_add(v).ok_or(err(E_MATH))?;
        if v > 0 {
            mask |= 1 << i;
        }
    }
    Ok(Some(MinerView {
        authority: Address::new_from_array(rd::<32>(&d, 8)?),
        checkpoint_id: rd_u64(&d, 48)?,
        checkpoint_fee: rd_u64(&d, 56)?,
        round_id: rd_u64(&d, 664)?,
        sum,
        mask,
    }))
}

#[allow(clippy::too_many_lines)]
fn dig_one(c: &Ctx<'_>, group: &mut [AccountView], entry: &[u8]) -> Result<(u64, u32), RigErr> {
    let [rig, authority, automation, miner] = group else {
        return Err(RigErr::Fatal(ProgramError::NotEnoughAccountKeys));
    };
    // ---- 1. accounts (fail the transaction) -----------------------------------------------
    if !rig.owned_by(c.program_id) {
        return Err(RigErr::Fatal(err(E_INVALID_ACCOUNT_TAG)));
    }
    let (rig_authority, pubkey) = {
        let d = rig.try_borrow()?;
        if d.len() != 384 || d[0] != 2 || d[1] != 1 {
            return Err(RigErr::Fatal(err(E_INVALID_ACCOUNT_TAG)));
        }
        (Address::new_from_array(rd::<32>(&d, 8)?), rd::<33>(&d, 40)?)
    };
    if authority.address() != &rig_authority {
        return Err(RigErr::Fatal(err(E_UNAUTHORIZED)));
    }
    let auto_pda = Address::find_program_address(&[b"automation", rig_authority.as_ref()], &ORE).0;
    let miner_pda = Address::find_program_address(&[b"miner", rig_authority.as_ref()], &ORE).0;
    if automation.address() != &auto_pda || miner.address() != &miner_pda {
        return Err(RigErr::Fatal(err(E_INVALID_ORE_ACCOUNT)));
    }
    // ---- 1b. user-controlled ORE state (skip) -------------------------------------------------
    if !is_ore(automation, 160, 100) {
        return Err(RigErr::Skip(E_INVALID_EXECUTOR)); // closed / revoked
    }
    let (amount, balance_before, fee, min_ml, max_ml) = {
        let d = automation.try_borrow()?;
        let a_auth = Address::new_from_array(rd::<32>(&d, 16)?);
        if a_auth != rig_authority {
            return Err(RigErr::Fatal(err(E_INVALID_ORE_ACCOUNT)));
        }
        let a_exec = Address::new_from_array(rd::<32>(&d, 56)?);
        if a_exec != *c.executor.address() {
            return Err(RigErr::Skip(E_INVALID_EXECUTOR));
        }
        let fee = rd_u64(&d, 88)?;
        if rd_u64(&d, 96)? != 2 || fee != c.executor_fee {
            return Err(RigErr::Skip(E_STRATEGY));
        }
        (rd_u64(&d, 8)?, rd_u64(&d, 48)?, fee, rd_u16(&d, 144)?, rd_u16(&d, 146)?)
    };
    let m = match read_miner(miner)? {
        Some(m) if m.authority == rig_authority => m,
        _ => return Err(RigErr::Skip(E_INVALID_ORE_ACCOUNT)),
    };

    // ---- 2. state + heartbeat + lease -----------------------------------------------------------
    let hb_ix = entry[0];
    let rig_key = *rig.address();
    let state = rd_u8(&rig.try_borrow()?, 75)?;
    match state {
        1 | 2 => {}
        3 if hb_ix != 0xFF => {}
        5 => return Err(RigErr::Skip(E_FROZEN)),
        _ => return Err(RigErr::Skip(E_NOT_ARMED)),
    }
    if hb_ix != 0xFF {
        let counter = rd_u64(entry, 2)?;
        let hb_round = rd_u64(entry, 10)?;
        let lease_rounds = entry[18];
        if hb_round > c.round_id || lease_rounds == 0 {
            return Err(RigErr::Skip(E_INVALID_HEARTBEAT));
        }
        let mut d = rig.try_borrow_mut()?;
        if counter <= rd_u64(&d, 208)? {
            return Err(RigErr::Skip(E_STALE_HEARTBEAT));
        }
        let shift_id = rd_u64(&d, 200)?;
        let mut pre = [0u8; 94];
        pre[0..4].copy_from_slice(b"HDv1");
        pre[4..36].copy_from_slice(c.program_id.as_ref());
        pre[36..68].copy_from_slice(rig_key.as_ref());
        pre[68] = 1;
        pre[69..77].copy_from_slice(&counter.to_le_bytes());
        pre[77..85].copy_from_slice(&shift_id.to_le_bytes());
        pre[85..93].copy_from_slice(&hb_round.to_le_bytes());
        pre[93] = lease_rounds;
        let digest = sha256(&[&pre]);
        if let Err(e) = verify_secp256r1_signature(c.ix_sysvar, u16::from(hb_ix), entry[1], &pubkey, &digest) {
            return Err(RigErr::Skip(skip_code(e)));
        }
        // Grant the lease: leases only move forward; dark rounds and gaps from shift start.
        let lease = lease_rounds.min(rd_u8(&d, 178)?);
        let (cur_from, cur_to) = (rd_u64(&d, 216)?, rd_u64(&d, 224)?);
        let shift_start = rd_u64(&d, 272)?;
        let new_to = hb_round
            .checked_add(u64::from(lease).checked_sub(1).ok_or(RigErr::Skip(E_INVALID_HEARTBEAT))?)
            .ok_or(RigErr::Skip(E_MATH))?;
        if !(cur_to != 0 && new_to <= cur_to) {
            let covered_end = if cur_to != 0 && cur_to >= shift_start { cur_to } else { shift_start.saturating_sub(1) };
            let start = hb_round.max(shift_start);
            let first_new = start.max(covered_end.saturating_add(1));
            let dark_added = if new_to >= first_new { new_to - first_new + 1 } else { 0 };
            let gap_added = start.saturating_sub(covered_end.saturating_add(1));
            let dark = rd_u64(&d, 280)?.saturating_add(dark_added);
            let gaps = u64::from(rd_u32(&d, 232)?).saturating_add(gap_added);
            wr(&mut d, 216, &hb_round.to_le_bytes())?;
            wr(&mut d, 224, &new_to.to_le_bytes())?;
            wr(&mut d, 280, &dark.to_le_bytes())?;
            wr(&mut d, 232, &u32::try_from(gaps).unwrap_or(u32::MAX).to_le_bytes())?;
        }
        let _ = cur_from;
        wr(&mut d, 208, &counter.to_le_bytes())?;
        if state == 1 || state == 3 {
            wr(&mut d, 75, &[2])?;
        }
    }
    let d = rig.try_borrow()?;
    let (lease_from, lease_to) = (rd_u64(&d, 216)?, rd_u64(&d, 224)?);
    if !(lease_to != 0 && lease_from <= c.round_id && c.round_id <= lease_to) {
        return Err(RigErr::Skip(E_LEASE_EXPIRED));
    }
    // ---- 3. idempotency ---------------------------------------------------------------------------
    if rd_u64(&d, 264)? == c.round_id {
        return Err(RigErr::Skip(E_ALREADY_DUG));
    }
    // ---- 4. caps, window, focus-only, gate --------------------------------------------------------
    let cap_week = rd_u64(&d, 120)?;
    let cap_shift = rd_u64(&d, 128)?;
    let cap_round = rd_u64(&d, 136)?;
    let cap_max_cost = rd_u64(&d, 144)?;
    let caps_expiry = rd_i64(&d, 152)?;
    let plan_max_ev = rd_u64(&d, 160)?;
    let plan_dig = rd_u64(&d, 168)?;
    let split = rd_u8(&d, 176)?;
    let solo = rd_u8(&d, 177)?;
    let flags = rd_u8(&d, 179)?;
    let w_start = rd_i64(&d, 184)?;
    let w_end = rd_i64(&d, 192)?;
    let spent_shift = rd_u64(&d, 240)?;
    let mut spent_week = rd_u64(&d, 248)?;
    let mut week_start = rd_i64(&d, 256)?;
    drop(d);
    if c.now > caps_expiry {
        return Err(RigErr::Skip(E_CAPS_EXPIRED));
    }
    if c.now < w_start || c.now > w_end {
        return Err(RigErr::Skip(E_OUTSIDE_WINDOW));
    }
    if flags & 1 != 0 {
        return Err(RigErr::Skip(E_FOCUS_ONLY));
    }
    if c.ema_ev > u128::from(plan_max_ev.min(cap_max_cost)) {
        return Err(RigErr::Skip(E_COST_GATE));
    }
    // ---- 5. squares + amount (the fee reserved inside every cap) ------------------------------------
    if week_start == 0 || c.now.saturating_sub(week_start) >= WEEK_SECS {
        spent_week = 0;
        week_start = c.now;
    }
    let same_round = m.round_id == c.round_id;
    let held = if same_round { m.mask } else { 0 };
    let mut deployed = [0u64; 25];
    {
        let d = c.round.try_borrow()?;
        for (i, v) in deployed.iter_mut().enumerate() {
            *v = rd_u64(&d, 16 + 8 * i)?;
        }
    }
    let mask = select_tiles(&deployed, c.solo_mask, held, split, solo);
    let k = u64::from(mask.count_ones());
    let headroom = cap_round.min(cap_shift.saturating_sub(spent_shift)).min(cap_week.saturating_sub(spent_week));
    let budget = plan_dig.min(headroom.saturating_sub(fee));
    let per_tile = budget.checked_div(k).unwrap_or(0).min(amount);
    if per_tile == 0 {
        return Err(RigErr::Skip(E_BUDGET));
    }
    let total = per_tile.checked_mul(k).ok_or(RigErr::Fatal(err(E_MATH)))?;
    let sum_before = if same_round { m.sum } else { 0 };
    let fee_due = if sum_before == 0 { fee } else { 0 };
    let need = total.checked_add(fee_due).ok_or(RigErr::Fatal(err(E_MATH)))?;
    if balance_before < need {
        return Err(RigErr::Skip(E_INSUFFICIENT_BALANCE));
    }
    // ---- 5b. ORE pre-flight -----------------------------------------------------------------------
    {
        let d = c.board.try_borrow()?;
        let (start, end) = (rd_u64(&d, 16)?, rd_u64(&d, 24)?);
        if !(c.slot >= start && c.slot < end) {
            return Err(RigErr::Skip(E_ROUND_NOT_ACTIVE));
        }
    }
    if !(same_round || m.checkpoint_id == m.round_id) {
        return Err(RigErr::Skip(E_MINER_NOT_CHECKPOINTED));
    }
    if c.pot > u64::from(max_ml).saturating_mul(ONE_ORE) || c.pot < u64::from(min_ml).saturating_mul(ONE_ORE) {
        return Err(RigErr::Skip(E_MOTHERLODE));
    }
    let checkpoint_due = m.checkpoint_fee == 0;
    let exec_before = c.executor.lamports();
    if checkpoint_due && exec_before < RENT_EXEMPT_ZERO + CHECKPOINT_FEE {
        return Err(RigErr::Skip(E_EXECUTOR_UNDERFUNDED));
    }
    // ---- 6. CPI ORE deploy, signed by the Executor PDA ------------------------------------------------
    let mut data = [0u8; 13];
    data[0] = 6;
    data[1..9].copy_from_slice(&per_tile.to_le_bytes());
    data[9..13].copy_from_slice(&mask.to_le_bytes());
    let metas = [
        InstructionAccount::writable_signer(c.executor.address()),
        InstructionAccount::writable(authority.address()),
        InstructionAccount::writable(automation.address()),
        InstructionAccount::writable(c.board.address()),
        InstructionAccount::writable(c.ore_config.address()),
        InstructionAccount::writable(miner.address()),
        InstructionAccount::writable(c.round.address()),
        InstructionAccount::writable(c.treasury.address()),
        InstructionAccount::readonly(c.system.address()),
        InstructionAccount::readonly(c.ore_program.address()),
        InstructionAccount::writable(c.var.address()),
        InstructionAccount::readonly(c.entropy.address()),
    ];
    let ix = InstructionView { program_id: &ORE, data: &data, accounts: &metas };
    let bump = [c.executor_bump];
    let signer_seeds = seeds!(b"executor", &bump);
    invoke_signed(
        &ix,
        &[
            c.executor, &*authority, &*automation, c.board, c.ore_config, &*miner, c.round, c.treasury, c.system,
            c.ore_program, c.var, c.entropy,
        ],
        &[Signer::from(&signer_seeds)],
    )?;
    // ---- 7. reload + accounting -------------------------------------------------------------------
    let exec_after = c.executor.lamports();
    if exec_after.saturating_add(CHECKPOINT_FEE) < exec_before || (!checkpoint_due && exec_after < exec_before) {
        return Err(RigErr::Fatal(err(E_INVALID_EXECUTOR)));
    }
    let cp_paid = if checkpoint_due { CHECKPOINT_FEE } else { 0 };
    let fee_received = exec_after.saturating_add(cp_paid).saturating_sub(exec_before);
    let m_after = match read_miner(miner)? {
        Some(x) if x.authority == rig_authority => x,
        _ => return Err(RigErr::Fatal(err(E_INVALID_ORE_ACCOUNT))),
    };
    let deployed_now = if m_after.round_id == c.round_id {
        m_after.sum.checked_sub(sum_before).ok_or(RigErr::Fatal(err(E_INVALID_ORE_ACCOUNT)))?
    } else if m_after.round_id == m.round_id {
        0
    } else {
        return Err(RigErr::Fatal(err(E_INVALID_ORE_ACCOUNT)));
    };
    let debit = if is_ore(automation, 160, 100) {
        let after = rd_u64(&automation.try_borrow()?, 48)?;
        balance_before.checked_sub(after).ok_or(RigErr::Fatal(err(E_INVALID_ORE_ACCOUNT)))?
    } else {
        deployed_now.saturating_add(fee_received)
    };
    if deployed_now > total || debit > need {
        return Err(RigErr::Fatal(err(E_INVALID_ORE_ACCOUNT)));
    }
    let new_spent_shift = spent_shift.checked_add(debit).ok_or(RigErr::Fatal(err(E_MATH)))?;
    let new_spent_week = spent_week.checked_add(debit).ok_or(RigErr::Fatal(err(E_MATH)))?;
    if debit > cap_round || new_spent_shift > cap_shift || new_spent_week > cap_week {
        return Err(RigErr::Fatal(err(E_BUDGET)));
    }
    {
        let mut d = rig.try_borrow_mut()?;
        wr(&mut d, 240, &new_spent_shift.to_le_bytes())?;
        wr(&mut d, 248, &new_spent_week.to_le_bytes())?;
        wr(&mut d, 256, &week_start.to_le_bytes())?;
        if deployed_now > 0 {
            wr(&mut d, 264, &c.round_id.to_le_bytes())?;
            let dug = rd_u64(&d, 288)?.saturating_add(1);
            wr(&mut d, 288, &dug.to_le_bytes())?;
            let life = rd_u64(&d, 304)?.saturating_add(1);
            wr(&mut d, 304, &life.to_le_bytes())?;
            let lamports = rd_u64(&d, 312)?.saturating_add(deployed_now);
            wr(&mut d, 312, &lamports.to_le_bytes())?;
        }
    }
    if deployed_now == 0 {
        return Err(RigErr::Skip(E_ORE_NOOP)); // no reimbursement, no dig counters
    }
    // ---- 8. reimburse the cranker, keeping rent + EXECUTOR_RESERVE ------------------------------------
    if c.crank_fee > 0 && c.cranker.address() != c.executor.address() && c.executor.lamports() >= RENT_EXEMPT_ZERO + EXECUTOR_RESERVE + c.crank_fee {
        Transfer { from: c.executor, to: c.cranker, lamports: c.crank_fee }.invoke_signed(&[Signer::from(&signer_seeds)])?;
    }
    Ok((deployed_now, mask))
}
