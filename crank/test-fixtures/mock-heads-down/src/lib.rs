//! **TEST FIXTURE. Never deploy.** A mock of `heads_down::dig` (tag 6) written only from
//! `programs/heads-down/INTERFACE.md`, so the crank's dig transactions can be executed
//! against the live mainnet ORE binary in LiteSVM before the real program lands.
//!
//! It follows the contract's account order, entry layout, HEARTBEAT preimage + SHA-256 +
//! p256-introspect verification, lease/idempotency/gate/amount rules, least-crowded tile
//! choice, the Executor-PDA-signed ORE `deploy` CPI with post-CPI reload, crank
//! reimbursement, and the RigDug / RigSkipped events. Where INTERFACE.md leaves a detail
//! open (skip codes for some cases, event byte packing), the choice matches
//! `crank/INTERFACE-NOTES.md`. It is deliberately not hardened beyond what the tests need:
//! the real program is the security boundary.
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

const CHECKPOINT_FEE: u64 = 10_000;
const RENT_EXEMPT_ZERO: u64 = 890_880;
const WEEK_SECS: i64 = 7 * 24 * 3600;

// INTERFACE.md error codes.
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

/// `split` least-crowded split squares + `solo` least-crowded solo squares, ties → lowest index.
fn select_tiles(round_id: u64, deployed: &[u64; 25], split: u8, solo: u8) -> u32 {
    let solo_mask = distribution_mask(round_id);
    let mut mask = 0u32;
    for (want_solo, count) in [(false, split), (true, solo)] {
        for _ in 0..count {
            let mut best: Option<usize> = None;
            for i in 0..25 {
                let is_solo = solo_mask & (1 << i) != 0;
                if is_solo != want_solo || mask & (1 << i) != 0 {
                    continue;
                }
                if best.map_or(true, |b| deployed[i] < deployed[b]) {
                    best = Some(i);
                }
            }
            match best {
                Some(b) => mask |= 1 << b,
                None => break,
            }
        }
    }
    mask
}

fn ema_ev(ema: u64, pot: u64) -> Option<u64> {
    let num = (ema as u128).checked_mul(6 * 500 * 100_000_000_000)?;
    let den = (500u128 * 100_000_000_000).checked_add(pot as u128)?.checked_mul(5)?;
    u64::try_from(num / den).ok()
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
    start_slot: u64,
    end_slot: u64,
    ema_ev: Option<u64>,
    pot: u64,
    executor_fee: u64,
    crank_fee: u64,
    paused: bool,
    executor_bump: u8,
    slot: u64,
    now: i64,
    deployed: [u64; 25],
}

fn dig(program_id: &Address, accounts: &mut [AccountView], args: &[u8]) -> ProgramResult {
    let (&n, body) = args.split_first().ok_or(err(E_INVALID_INSTRUCTION))?;
    let n = n as usize;
    if n == 0 || body.len() != n * 20 || accounts.len() != 12 + 4 * n {
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
    check_ore(board, 40, 105)?;
    check_ore(treasury, 48, 104)?;
    check_ore(round, 952, 109)?;
    let (round_id, start_slot, end_slot, ema) = {
        let d = board.try_borrow()?;
        (rd_u64(&d, 8)?, rd_u64(&d, 16)?, rd_u64(&d, 24)?, rd_u64(&d, 32)?)
    };
    let pot = rd_u64(&treasury.try_borrow()?, 8)?;
    let round_pda = Address::find_program_address(&[b"round", &round_id.to_le_bytes()], &ORE).0;
    let mut deployed = [0u64; 25];
    {
        let d = round.try_borrow()?;
        if round.address() != &round_pda || rd_u64(&d, 8)? != round_id {
            return Err(err(E_INVALID_ORE_ACCOUNT));
        }
        for (i, v) in deployed.iter_mut().enumerate() {
            *v = rd_u64(&d, 16 + 8 * i)?;
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
    let mut ctx = Ctx {
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
        start_slot,
        end_slot,
        ema_ev: ema_ev(ema, pot),
        pot,
        executor_fee,
        crank_fee,
        paused,
        executor_bump,
        slot: clock.slot,
        now: clock.unix_timestamp,
        deployed,
    };
    for i in 0..n {
        let entry = &body[i * 20..(i + 1) * 20];
        let group = &mut rigs[4 * i..4 * i + 4];
        let rig_addr = *group[0].address();
        match dig_one(&mut ctx, group, entry) {
            Ok((lamports, mask)) => {
                let mut ev = [0u8; 61];
                ev[0] = 1;
                ev[1..33].copy_from_slice(rig_addr.as_ref());
                ev[33..41].copy_from_slice(&round_id.to_le_bytes());
                ev[41..49].copy_from_slice(&lamports.to_le_bytes());
                ev[49..53].copy_from_slice(&mask.to_le_bytes());
                ev[53..61].copy_from_slice(&ctx.ema_ev.unwrap_or(u64::MAX).to_le_bytes());
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

fn dig_one(c: &mut Ctx<'_>, group: &mut [AccountView], entry: &[u8]) -> Result<(u64, u32), RigErr> {
    let [rig, authority, automation, miner] = group else {
        return Err(RigErr::Fatal(ProgramError::NotEnoughAccountKeys));
    };
    // 1. Account validation (fails the transaction).
    if !rig.owned_by(c.program_id) {
        return Err(RigErr::Fatal(err(E_INVALID_ACCOUNT_TAG)));
    }
    let (rig_authority, pubkey, shift_id) = {
        let d = rig.try_borrow()?;
        if d.len() != 384 || d[0] != 2 || d[1] != 1 {
            return Err(RigErr::Fatal(err(E_INVALID_ACCOUNT_TAG)));
        }
        (Address::new_from_array(rd::<32>(&d, 8)?), rd::<33>(&d, 40)?, rd_u64(&d, 200)?)
    };
    let rig_pda = Address::find_program_address(&[b"rig", rig_authority.as_ref()], c.program_id).0;
    if rig.address() != &rig_pda || authority.address() != &rig_authority {
        return Err(RigErr::Fatal(err(E_UNAUTHORIZED)));
    }
    let auto_pda = Address::find_program_address(&[b"automation", rig_authority.as_ref()], &ORE).0;
    let miner_pda = Address::find_program_address(&[b"miner", rig_authority.as_ref()], &ORE).0;
    if automation.address() != &auto_pda || miner.address() != &miner_pda {
        return Err(RigErr::Fatal(err(E_INVALID_ORE_ACCOUNT)));
    }

    // 2. Lease.
    let hb_ix = entry[0];
    if hb_ix != 0xFF {
        let counter = rd_u64(entry, 2)?;
        let hb_round = rd_u64(entry, 10)?;
        let lease_rounds = entry[18];
        let mut pre = [0u8; 94];
        pre[0..4].copy_from_slice(b"HDv1");
        pre[4..36].copy_from_slice(c.program_id.as_ref());
        pre[36..68].copy_from_slice(rig.address().as_ref());
        pre[68] = 1;
        pre[69..77].copy_from_slice(&counter.to_le_bytes());
        pre[77..85].copy_from_slice(&shift_id.to_le_bytes());
        pre[85..93].copy_from_slice(&hb_round.to_le_bytes());
        pre[93] = lease_rounds;
        let digest = sha256(&[&pre]);
        if verify_secp256r1_signature(c.ix_sysvar, u16::from(hb_ix), entry[1], &pubkey, &digest).is_err() {
            return Err(RigErr::Skip(E_INVALID_HEARTBEAT));
        }
        let mut d = rig.try_borrow_mut()?;
        let (hb_counter, plan_lease) = (rd_u64(&d, 208)?, rd_u8(&d, 178)?);
        if counter <= hb_counter {
            return Err(RigErr::Skip(E_STALE_HEARTBEAT));
        }
        if hb_round > c.round_id {
            return Err(RigErr::Skip(E_INVALID_HEARTBEAT));
        }
        let len = lease_rounds.min(plan_lease);
        if len == 0 {
            return Err(RigErr::Skip(E_INVALID_HEARTBEAT));
        }
        let to = hb_round.checked_add(u64::from(len) - 1).ok_or(RigErr::Skip(E_MATH))?;
        wr(&mut d, 208, &counter.to_le_bytes())?;
        wr(&mut d, 216, &hb_round.to_le_bytes())?;
        wr(&mut d, 224, &to.to_le_bytes())?;
    }
    let (state, lease_from, lease_to, last_dug) = {
        let d = rig.try_borrow()?;
        (rd_u8(&d, 75)?, rd_u64(&d, 216)?, rd_u64(&d, 224)?, rd_u64(&d, 264)?)
    };
    if !(lease_from <= c.round_id && c.round_id <= lease_to) {
        return Err(RigErr::Skip(E_LEASE_EXPIRED));
    }
    match state {
        1 | 2 => {}
        5 => return Err(RigErr::Skip(E_FROZEN)),
        _ => return Err(RigErr::Skip(E_NOT_ARMED)),
    }
    // 3. Idempotency.
    if last_dug == c.round_id {
        return Err(RigErr::Skip(E_ALREADY_DUG));
    }
    // 4. Gate, caps, window, pause.
    let d = rig.try_borrow()?;
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
    if c.paused {
        return Err(RigErr::Skip(E_PAUSED));
    }
    if flags & 1 != 0 {
        return Err(RigErr::Skip(E_NOT_ARMED));
    }
    if c.now > caps_expiry {
        return Err(RigErr::Skip(E_CAPS_EXPIRED));
    }
    if c.now < w_start || c.now > w_end {
        return Err(RigErr::Skip(E_OUTSIDE_WINDOW));
    }
    match c.ema_ev {
        Some(v) if v <= plan_max_ev.min(cap_max_cost) => {}
        _ => return Err(RigErr::Skip(E_COST_GATE)),
    }
    if c.now >= week_start.saturating_add(WEEK_SECS) {
        spent_week = 0;
        week_start = c.now;
    }
    // 5. Amount + ORE pre-flight.
    let dig_lamports = plan_dig
        .min(cap_round)
        .min(cap_shift.saturating_sub(spent_shift))
        .min(cap_week.saturating_sub(spent_week));
    let k = u64::from(split) + u64::from(solo);
    if k == 0 {
        return Err(RigErr::Skip(E_BUDGET));
    }
    if check_ore(automation, 160, 100).is_err() {
        return Err(RigErr::Skip(E_STRATEGY));
    }
    let (amount, balance_before, fee) = {
        let d = automation.try_borrow()?;
        let a_auth = Address::new_from_array(rd::<32>(&d, 16)?);
        let a_exec = Address::new_from_array(rd::<32>(&d, 56)?);
        let strategy = rd_u64(&d, 96)?;
        let fee = rd_u64(&d, 88)?;
        if a_exec != *c.executor.address() || a_auth != rig_authority {
            return Err(RigErr::Skip(E_INVALID_EXECUTOR));
        }
        if strategy != 2 || fee != c.executor_fee {
            return Err(RigErr::Skip(E_STRATEGY));
        }
        let min_ml = u64::from(rd_u16(&d, 144)?) * 100_000_000_000;
        let max_ml = u64::from(rd_u16(&d, 146)?) * 100_000_000_000;
        if c.pot > max_ml || c.pot < min_ml {
            return Err(RigErr::Skip(E_COST_GATE));
        }
        (rd_u64(&d, 8)?, rd_u64(&d, 48)?, fee)
    };
    let per_tile = (dig_lamports / k).min(amount);
    if per_tile == 0 {
        return Err(RigErr::Skip(E_BUDGET));
    }
    let need = per_tile.checked_mul(k).and_then(|v| v.checked_add(fee)).ok_or(RigErr::Skip(E_MATH))?;
    if balance_before < need {
        return Err(RigErr::Skip(E_BUDGET));
    }
    if c.end_slot != u64::MAX && !(c.start_slot <= c.slot && c.slot < c.end_slot) {
        return Err(RigErr::Skip(E_OUTSIDE_WINDOW));
    }
    if check_ore(miner, 752, 103).is_err() {
        return Err(RigErr::Skip(E_INVALID_ORE_ACCOUNT));
    }
    {
        let d = miner.try_borrow()?;
        let m_auth = Address::new_from_array(rd::<32>(&d, 8)?);
        let checkpoint_id = rd_u64(&d, 48)?;
        let m_round = rd_u64(&d, 664)?;
        if m_auth != rig_authority || !(m_round == c.round_id || checkpoint_id == m_round) {
            return Err(RigErr::Skip(E_INVALID_ORE_ACCOUNT));
        }
    }
    if c.executor.lamports() < RENT_EXEMPT_ZERO + CHECKPOINT_FEE {
        return Err(RigErr::Skip(E_INVALID_EXECUTOR));
    }
    // 6. Tiles.
    let mask = select_tiles(c.round_id, &c.deployed, split, solo);
    // 7. CPI ORE deploy, signed by the Executor PDA; authority always rig.authority.
    let exec_before = c.executor.lamports();
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
    // Reload after the CPI: the Automation may have closed; count only the real debit.
    let balance_after = if automation.owned_by(&ORE) && automation.data_len() == 160 {
        rd_u64(&automation.try_borrow()?, 48)?
    } else {
        0
    };
    let spent = balance_before.saturating_sub(balance_after);
    if c.executor.lamports().saturating_add(CHECKPOINT_FEE) < exec_before {
        return Err(RigErr::Fatal(err(E_INVALID_EXECUTOR)));
    }
    {
        let d = c.round.try_borrow()?;
        for (i, v) in c.deployed.iter_mut().enumerate() {
            *v = rd_u64(&d, 16 + 8 * i)?;
        }
    }
    {
        let mut d = rig.try_borrow_mut()?;
        wr(&mut d, 75, &[2])?;
        wr(&mut d, 240, &spent_shift.saturating_add(spent).to_le_bytes())?;
        wr(&mut d, 248, &spent_week.saturating_add(spent).to_le_bytes())?;
        wr(&mut d, 256, &week_start.to_le_bytes())?;
        wr(&mut d, 264, &c.round_id.to_le_bytes())?;
        let dug = rd_u64(&d, 288)?.saturating_add(1);
        wr(&mut d, 288, &dug.to_le_bytes())?;
        let life = rd_u64(&d, 304)?.saturating_add(1);
        wr(&mut d, 304, &life.to_le_bytes())?;
        let lamports = rd_u64(&d, 312)?.saturating_add(spent);
        wr(&mut d, 312, &lamports.to_le_bytes())?;
    }
    if spent == 0 {
        return Err(RigErr::Skip(E_BUDGET)); // ORE no-op: no reimbursement
    }
    // 8. Reimburse the cranker, keeping the Executor float.
    if c.executor.lamports() >= RENT_EXEMPT_ZERO + CHECKPOINT_FEE + c.crank_fee {
        Transfer { from: c.executor, to: c.cranker, lamports: c.crank_fee }
            .invoke_signed(&[Signer::from(&signer_seeds)])?;
    }
    Ok((spent, mask))
}
