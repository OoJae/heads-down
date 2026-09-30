//! Stack (v1.2, SKR): an SKR-bonded self-control contest. `INTERFACE.md`
//! §11.3 is the contract; the tags are 15 `open_stack`, 16 `join_stack`,
//! 17 `stack_checkin`, 18 `settle_stack`, 19 `claim_stack`.
//!
//! A table bonds the same SKR amount per seat for a window of ORE rounds
//! `[start_round, end_round]`. Each seat proves every round with a
//! `stack_checkin`: a P-256 HEARTBEAT for that round that **landed in that
//! round**, either verified inside the check-in or already applied by `dig`
//! / `record_heartbeats` in the same round (the Rig's lease then ends at the
//! live round). At settle a seat **finishes** iff:
//!
//! * no BREAK or FREEZE was recorded in its bound shift up to its last
//!   check-in (`rig.break_reason == 0` and state Armed/Down at every
//!   check-in; `break_reason` only ever goes from 0 to non-zero inside a
//!   shift, so one clean check-in proves every earlier moment of the shift
//!   clean). A seat binds to the rig's shift at its first counted check-in,
//!   and never to a shift that already recorded a break;
//! * it checked in during `end_round` itself (a lease covering `end_round`);
//! * it missed at most `grace_gaps` window rounds.
//!
//! Finishers split 80% of the forfeits pro rata (bury-only tables: 0%); the
//! rest, and every bond if nobody finishes, becomes a Bury lot. Claims are
//! pull-based and close the seat; a table nobody settles by
//! `refund_after_ts` refunds every bond.

use pinocchio::{
    cpi::Signer, error::ProgramError, instruction::seeds, AccountView, Address, ProgramResult,
};

use crate::{
    error::HdError,
    events::{self, log_data},
    instructions::{apply_heartbeat, bury, HeartbeatEntry, ENTRY_LEN, NO_HEARTBEAT},
    ore, pda,
    skr::{self, GUEST_BOND_CAP, MAX_SEATS, MIN_SEATS},
    state::{
        self, rig_state, seat_outcome, stack_flags, stack_status, Header, Rig, StackSeat,
        StackTable, U32, U64,
    },
    token::{self, SKR_MINT},
    util::{clock, require_signer, require_writable, Reader},
    ID, STACK_SEAT_SEED, STACK_SEED,
};

/// Accounts before the `(seat, rig)` pairs of `stack_checkin`.
pub const CHECKIN_FIXED_ACCOUNTS: usize = 3;
/// Accounts before the seats of `settle_stack`.
pub const SETTLE_FIXED_ACCOUNTS: usize = 6;

/// Sign as the table PDA `["stack", host, table_id, bump]` and run `f`.
fn with_table_signer<R>(
    host: &[u8; 32],
    table_id: u64,
    bump: u8,
    f: impl FnOnce(&[Signer]) -> R,
) -> R {
    let id = table_id.to_le_bytes();
    let b = [bump];
    let signer_seeds = seeds!(STACK_SEED, host, &id, &b);
    let signer = [Signer::from(&signer_seeds)];
    f(&signer)
}

// ---- 15 open_stack -----------------------------------------------------------------

/// `open_stack` accounts:
/// 0. `[signer, writable]` host (pays the table rent)
/// 1. `[writable]` StackTable PDA `["stack", host, table_id u64 LE]`
/// 2. `[]` table SKR vault = `ATA(table, SKR mint)` (created beforehand)
/// 3. `[]` ORE Board
/// 4. `[]` System program
///
/// Data: `table_id u64 | bond u64 | start_round u64 | end_round u64 |
/// grace_gaps u32 | flags u8 | max_seats u8` (38 bytes after the tag).
pub fn process_open(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [host, table, vault, board, system_program, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let mut r = Reader::new(data);
    let table_id = r.u64()?;
    let bond = r.u64()?;
    let start_round = r.u64()?;
    let end_round = r.u64()?;
    let grace_gaps = r.u32()?;
    let flags = r.u8()?;
    let max_seats = r.u8()?;
    r.finish()?;
    require_signer(host)?;

    if flags & !stack_flags::ALL != 0 || !(MIN_SEATS..=MAX_SEATS).contains(&max_seats) {
        return Err(HdError::InvalidStackParams.into());
    }
    let remote = flags & stack_flags::REMOTE != 0;
    // Remote tables are always attested-only.
    let flags = if remote {
        flags | stack_flags::ATTESTED_ONLY
    } else {
        flags
    };
    let cap = if remote {
        skr::REMOTE_BOND_CAP
    } else {
        skr::STACK_BOND_CAP
    };
    if bond == 0 || bond > cap {
        return Err(HdError::AmountOutOfRange.into());
    }
    let b = ore::read_board(board)?;
    let len = skr::window_len(start_round, end_round);
    if start_round <= b.round_id
        || end_round < start_round
        || start_round.saturating_sub(b.round_id) > skr::MAX_STACK_LEAD_ROUNDS
        || len > skr::MAX_STACK_ROUNDS
        || u64::from(grace_gaps) >= len
    {
        return Err(HdError::InvalidStackParams.into());
    }

    let id = table_id.to_le_bytes();
    let (table_pda, bump) = pda::find(&[STACK_SEED, host.address().as_ref(), &id], &ID);
    if table.address() != &table_pda {
        return Err(ProgramError::InvalidSeeds);
    }
    let vault_address = token::check_new_vault(vault, &SKR_MINT, &table_pda)?;

    let bump_seed = [bump];
    let signer_seeds = seeds!(STACK_SEED, host.address().as_ref(), &id, &bump_seed);
    pda::create_pda_account(
        host,
        table,
        system_program,
        core::mem::size_of::<StackTable>(),
        &signer_seeds,
    )?;
    let now = clock()?.unix_timestamp;
    let refund_after = skr::refund_after(now, b.round_id, end_round)?;
    {
        let mut t = state::load_uninit_mut::<StackTable>(table)?;
        t.header = Header::new(state::tag::STACK_TABLE, bump);
        t.host = *host.address().as_array();
        t.vault = vault_address;
        t.table_id = U64::new(table_id);
        t.bond = U64::new(bond);
        t.start_round = U64::new(start_round);
        t.end_round = U64::new(end_round);
        t.grace_gaps = U32::new(grace_gaps);
        t.flags = flags;
        t.max_seats = max_seats;
        t.status = stack_status::OPEN;
        t.refund_after_ts.set(refund_after);
        t.opened_ts.set(now);
        t.opened_round = U64::new(b.round_id);
    }
    log_data(&events::stack_opened_bytes(
        &table_pda,
        host.address(),
        &events::StackOpened {
            table_id,
            bond,
            start_round,
            end_round,
            grace_gaps,
            flags,
            max_seats,
        },
    ));
    Ok(())
}

// ---- 16 join_stack -----------------------------------------------------------------

/// `join_stack` accounts:
/// 0. `[signer, writable]` authority (`rig.authority`; pays the seat rent,
///    signs the bond transfer)
/// 1. `[]` Rig
/// 2. `[writable]` StackTable
/// 3. `[writable]` StackSeat PDA `["stackseat", table, key]`, `key` = the
///    rig's SGT mint at remote tables, else the rig address
/// 4. `[writable]` authority's SKR token account (source)
/// 5. `[writable]` table SKR vault
/// 6. `[]` ORE Board
/// 7. `[]` SPL Token program
/// 8. `[]` System program
/// 9. `[]` SGT token account (Token-2022), 10. `[]` SGT mint: only when the
///    table needs a verified Seeker (remote, or bond above the guest cap)
///
/// Data: empty. The bond is the table's.
pub fn process_join(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [authority, rig, table, seat, source, vault, board, token_program, system_program, rest @ ..] =
        accounts
    else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    Reader::new(data).finish()?;
    require_signer(authority)?;
    token::check_token_program(token_program)?;
    let clk = clock()?;
    let board_round = ore::read_board(board)?.round_id;

    let (tier, rig_sgt, level, expiry) = {
        let g = state::load::<Rig>(rig)?;
        if authority.address().as_array() != &g.authority {
            return Err(HdError::Unauthorized.into());
        }
        (
            g.tier,
            g.sgt_mint,
            g.attestation_level,
            g.attestation_expiry_slot.get(),
        )
    };
    let (bond, flags, vault_address, seat_index) = {
        let t = state::load::<StackTable>(table)?;
        if t.status != stack_status::OPEN
            || board_round >= t.start_round.get()
            || t.seat_count >= t.max_seats
        {
            return Err(HdError::StackJoinClosed.into());
        }
        (t.bond.get(), t.flags, t.vault, t.seat_count)
    };
    require_writable(table)?;

    let remote = flags & stack_flags::REMOTE != 0;
    if flags & stack_flags::ATTESTED_ONLY != 0 && (level == 0 || expiry <= clk.slot) {
        return Err(HdError::StackIneligible.into());
    }
    let needs_sgt = remote || bond > GUEST_BOND_CAP;
    let sgt_mint = if needs_sgt {
        if tier != 1 {
            return Err(HdError::StackIneligible.into());
        }
        let [sgt_account, sgt_mint_account, ..] = rest else {
            return Err(ProgramError::NotEnoughAccountKeys);
        };
        // Re-verify the SGT now: a stale tier is never trusted for value.
        let info = sgt_verify::verify_sgt(sgt_account, sgt_mint_account, authority.address())?;
        if info.mint.as_array() != &rig_sgt {
            return Err(HdError::StackIneligible.into());
        }
        rig_sgt
    } else {
        [0u8; 32]
    };
    let key: [u8; 32] = if remote {
        sgt_mint
    } else {
        *rig.address().as_array()
    };
    let (seat_pda, seat_bump) =
        pda::find(&[STACK_SEAT_SEED, table.address().as_ref(), &key], &ID);
    if seat.address() != &seat_pda {
        return Err(ProgramError::InvalidSeeds);
    }

    token::check_user_account(source, &SKR_MINT, authority.address().as_array())?;
    let before = token::check_vault(vault, &vault_address, &SKR_MINT, table.address())?.amount;

    let bump_seed = [seat_bump];
    let signer_seeds = seeds!(STACK_SEAT_SEED, table.address().as_ref(), &key, &bump_seed);
    pda::create_pda_account(
        authority,
        seat,
        system_program,
        core::mem::size_of::<StackSeat>(),
        &signer_seeds,
    )?;
    token::transfer(source, vault, authority, bond, &[])?;
    let expected = before.checked_add(bond).ok_or(HdError::MathOverflow)?;
    if token::balance(vault)? != expected {
        return Err(HdError::InvalidTokenAccount.into());
    }

    let table_address = *table.address();
    let rig_address = *rig.address();
    {
        let mut s = state::load_uninit_mut::<StackSeat>(seat)?;
        s.header = Header::new(state::tag::STACK_SEAT, seat_bump);
        s.table = *table_address.as_array();
        s.rig = *rig_address.as_array();
        s.authority = *authority.address().as_array();
        s.sgt_mint = sgt_mint;
        s.bond = U64::new(bond);
        s.seat_index = seat_index;
        s.sgt_verified = u8::from(needs_sgt);
    }
    {
        let mut t = state::load_mut::<StackTable>(table)?;
        t.seat_count = seat_index.checked_add(1).ok_or(HdError::MathOverflow)?;
        let total = t
            .total_bonds
            .get()
            .checked_add(bond)
            .ok_or(HdError::MathOverflow)?;
        t.total_bonds.set(total);
    }
    log_data(&events::stack_joined_bytes(
        &table_address,
        &rig_address,
        authority.address(),
        &sgt_mint,
        bond,
        seat_index,
    ));
    Ok(())
}

// ---- 17 stack_checkin ----------------------------------------------------------------

/// What one check-in did to its seat.
struct Checkin {
    /// 0 = counted, else the reason it did not count.
    result: u32,
    /// The seat is broken for good.
    broken: bool,
    /// Bind the seat to this shift (if unbound).
    bind: Option<u64>,
    /// Count `board_round`.
    count: bool,
    /// Dark rounds a heartbeat verified here added (for HeartbeatsRecorded).
    heartbeat: Option<u64>,
}

impl Checkin {
    fn skip(code: u32) -> Self {
        Self {
            result: code,
            broken: false,
            bind: None,
            count: false,
            heartbeat: None,
        }
    }
}

/// The per-seat rule, in order (INTERFACE.md §11.3). Nothing is written to
/// the rig unless a heartbeat in the entry verifies.
fn checkin_one(
    g: &mut Rig,
    rig_address: &Address,
    ix_sysvar: &AccountView,
    entry: &HeartbeatEntry,
    board_round: u64,
    seat_shift: u64,
    seat_broken: bool,
) -> Checkin {
    if seat_broken {
        return Checkin::skip(HdError::StackSeatBroken.code());
    }
    if seat_shift != 0 && g.shift_id.get() != seat_shift {
        return Checkin::skip(HdError::StackShiftMismatch.code());
    }
    if g.shift_open != 1 {
        return Checkin::skip(HdError::RigNotArmed.code());
    }
    if g.break_reason != 0 || !(g.state == rig_state::ARMED || g.state == rig_state::DOWN) {
        if seat_shift == 0 {
            // Never bind to a shift that already recorded a BREAK / FREEZE
            // (say, a pickup before the window): the player ends it and arms
            // a fresh one; only breaks after binding break the seat.
            return Checkin::skip(HdError::InvalidRigState.code());
        }
        return Checkin {
            result: HdError::StackSeatBroken.code(),
            broken: true,
            bind: None,
            count: false,
            heartbeat: None,
        };
    }
    if g.plan_lease_rounds != 1 {
        return Checkin::skip(HdError::StackLeaseTooLong.code());
    }
    let mut heartbeat = None;
    if entry.hb_ix != NO_HEARTBEAT {
        match apply_heartbeat(g, rig_address, ix_sysvar, entry, board_round) {
            Ok(dark) => heartbeat = Some(dark),
            Err(code) => return Checkin::skip(code),
        }
    }
    // With one-round leases, `lease_to == Board.round_id` means a heartbeat
    // for this very round was applied during this round (a heartbeat can
    // never be for a future round, and leases only move forward).
    if g.lease_to_round.get() != board_round || g.lease_from_round.get() != board_round {
        return Checkin {
            heartbeat,
            ..Checkin::skip(HdError::LeaseExpired.code())
        };
    }
    Checkin {
        result: 0,
        broken: false,
        bind: Some(g.shift_id.get()),
        count: true,
        heartbeat,
    }
}

/// `stack_checkin` accounts:
/// 0. `[]` ORE Board
/// 1. `[]` Instructions sysvar
/// 2. `[]` StackTable
///
/// then `[writable]` seat_i, `[writable]` rig_i, one pair per entry.
///
/// Data: `n u8 (1..=8) | n x entry(20)`, the `dig` entry; `hb_ix = 0xFF`
/// observes the lease a `dig` / `record_heartbeats` already applied in this
/// round instead of verifying a heartbeat here.
///
/// Permissionless. Fails the transaction for account problems (wrong table,
/// seat / rig mismatch, duplicates) or when the table is not open or the
/// live round is outside the window; otherwise emits one `StackCheckin` per
/// seat with its result code (and `HeartbeatsRecorded` when a heartbeat was
/// verified here).
pub fn process_checkin(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let (&n_seats, entries) = data.split_first().ok_or(HdError::InvalidInstruction)?;
    let n = usize::from(n_seats);
    if n == 0 || n > usize::from(MAX_SEATS) || entries.len() != n.saturating_mul(ENTRY_LEN) {
        return Err(HdError::InvalidInstruction.into());
    }
    if accounts.len() != CHECKIN_FIXED_ACCOUNTS.saturating_add(n.saturating_mul(2)) {
        return Err(HdError::InvalidInstruction.into());
    }
    let (fixed, pairs) = accounts.split_at_mut(CHECKIN_FIXED_ACCOUNTS);
    let [board, ix_sysvar, table] = fixed else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let board_round = ore::read_board(board)?.round_id;
    p256_introspect::check_instructions_sysvar(ix_sysvar)?;
    {
        let t = state::load::<StackTable>(table)?;
        if t.status != stack_status::OPEN
            || board_round < t.start_round.get()
            || board_round > t.end_round.get()
        {
            return Err(HdError::InvalidStackState.into());
        }
    }
    let table_address = *table.address();

    // Seats and rigs are each unique in the batch.
    for i in 1..n {
        let at = i.saturating_mul(2);
        let (seat_i, rig_i) = (
            pairs.get(at).ok_or(HdError::InvalidInstruction)?,
            pairs
                .get(at.saturating_add(1))
                .ok_or(HdError::InvalidInstruction)?,
        );
        for j in 0..i {
            let bt = j.saturating_mul(2);
            let (seat_j, rig_j) = (
                pairs.get(bt).ok_or(HdError::InvalidInstruction)?,
                pairs
                    .get(bt.saturating_add(1))
                    .ok_or(HdError::InvalidInstruction)?,
            );
            if seat_i.address() == seat_j.address() || rig_i.address() == rig_j.address() {
                return Err(HdError::DuplicateRig.into());
            }
        }
    }

    for (i, pair) in pairs.chunks_exact_mut(2).enumerate() {
        let start = i.saturating_mul(ENTRY_LEN);
        let entry = HeartbeatEntry::parse(
            entries
                .get(start..start.saturating_add(ENTRY_LEN))
                .ok_or(HdError::InvalidInstruction)?,
        )?;
        let [seat, rig] = pair else {
            return Err(ProgramError::NotEnoughAccountKeys);
        };
        let (seat_shift, seat_broken) = {
            let s = state::load::<StackSeat>(seat)?;
            if &s.table != table_address.as_array() || &s.rig != rig.address().as_array() {
                return Err(HdError::StackSeatMismatch.into());
            }
            (s.shift_id.get(), s.broken != 0)
        };
        require_writable(seat)?;
        let rig_address = *rig.address();
        let c = {
            let mut g = state::load_mut::<Rig>(rig)?;
            checkin_one(
                &mut g,
                &rig_address,
                ix_sysvar,
                &entry,
                board_round,
                seat_shift,
                seat_broken,
            )
        };
        let checked = {
            let mut s = state::load_mut::<StackSeat>(seat)?;
            if c.broken {
                s.broken = 1;
            }
            if let Some(shift) = c.bind {
                if s.shift_id.get() == 0 {
                    s.shift_id.set(shift);
                }
            }
            if c.count && s.last_round.get() < board_round {
                let v = s
                    .checked_rounds
                    .get()
                    .checked_add(1)
                    .ok_or(HdError::MathOverflow)?;
                s.checked_rounds.set(v);
                s.last_round.set(board_round);
            }
            s.checked_rounds.get()
        };
        if let Some(dark) = c.heartbeat {
            events::heartbeats_recorded(&rig_address, board_round, dark);
        }
        log_data(&events::stack_checkin_bytes(
            &table_address,
            &rig_address,
            board_round,
            checked,
            c.result,
        ));
    }
    Ok(())
}

// ---- 18 settle_stack -------------------------------------------------------------------

/// `settle_stack` accounts:
/// 0. `[writable]` StackTable
/// 1. `[]` ORE Board
/// 2. `[writable]` table SKR vault
/// 3. `[writable]` BuryVault PDA
/// 4. `[writable]` BuryVault SKR ATA
/// 5. `[]` SPL Token program
///
/// then `[writable]` every StackSeat of the table (any order, each once).
///
/// Data: empty. Permissionless once `Board.round_id > end_round`. Accounts
/// 3 and 4 are read only when something goes to Bury.
pub fn process_settle(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    Reader::new(data).finish()?;
    if accounts.len() < SETTLE_FIXED_ACCOUNTS {
        return Err(ProgramError::NotEnoughAccountKeys);
    }
    let (fixed, seats) = accounts.split_at_mut(SETTLE_FIXED_ACCOUNTS);
    let [table, board, vault, bury_vault, bury_skr, token_program] = fixed else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    token::check_token_program(token_program)?;
    let board_round = ore::read_board(board)?.round_id;
    let (host, table_id, bump, vault_address, n, start, end, grace, flags, total_bonds) = {
        let t = state::load::<StackTable>(table)?;
        if t.status != stack_status::OPEN {
            return Err(HdError::InvalidStackState.into());
        }
        if board_round <= t.end_round.get() {
            return Err(HdError::StackNotEnded.into());
        }
        (
            t.host,
            t.table_id.get(),
            t.header.bump,
            t.vault,
            usize::from(t.seat_count),
            t.start_round.get(),
            t.end_round.get(),
            t.grace_gaps.get(),
            t.flags,
            t.total_bonds.get(),
        )
    };
    require_writable(table)?;
    if seats.len() != n || n > usize::from(MAX_SEATS) {
        return Err(HdError::StackSeatMismatch.into());
    }
    for i in 1..n {
        let a = seats.get(i).ok_or(HdError::InvalidInstruction)?;
        for j in 0..i {
            if seats.get(j).ok_or(HdError::InvalidInstruction)?.address() == a.address() {
                return Err(HdError::StackSeatMismatch.into());
            }
        }
    }

    let table_address = *table.address();
    let mut bonds = [0u64; MAX_SEATS as usize];
    let mut finished = [false; MAX_SEATS as usize];
    for (i, seat) in seats.iter().enumerate() {
        let s = state::load::<StackSeat>(seat)?;
        if &s.table != table_address.as_array() {
            return Err(HdError::StackSeatMismatch.into());
        }
        require_writable(seat)?;
        *bonds.get_mut(i).ok_or(HdError::InvalidInstruction)? = s.bond.get();
        *finished.get_mut(i).ok_or(HdError::InvalidInstruction)? = skr::seat_finishes(
            s.broken != 0,
            s.last_round.get(),
            s.checked_rounds.get(),
            start,
            end,
            grace,
        );
    }
    let bps = if flags & stack_flags::BURY_ONLY != 0 {
        0
    } else {
        skr::FINISHER_BPS
    };
    let mut payouts = [0u64; MAX_SEATS as usize];
    let st = skr::stack_payouts(
        bonds.get(..n).ok_or(HdError::InvalidInstruction)?,
        finished.get(..n).ok_or(HdError::InvalidInstruction)?,
        bps,
        payouts.get_mut(..n).ok_or(HdError::InvalidInstruction)?,
    )?;
    // Every seat was passed: the bonds add up to the table's total.
    if st.total_bonds != total_bonds {
        return Err(HdError::StackSeatMismatch.into());
    }

    for (i, seat) in seats.iter_mut().enumerate() {
        let mut s = state::load_mut::<StackSeat>(seat)?;
        s.payout
            .set(*payouts.get(i).ok_or(HdError::InvalidInstruction)?);
        s.outcome = if *finished.get(i).ok_or(HdError::InvalidInstruction)? {
            seat_outcome::FINISHED
        } else {
            seat_outcome::FORFEITED
        };
    }
    {
        let mut t = state::load_mut::<StackTable>(table)?;
        t.status = stack_status::SETTLED;
        t.finishers = st.finishers;
        t.finisher_bonds.set(st.finisher_bonds);
        t.payouts_total.set(st.payouts_total);
        t.bury_amount.set(st.bury);
    }

    if st.bury > 0 {
        let skr_vault = bury::check_accounts(bury_vault, bury_skr)?;
        let before = token::check_vault(vault, &vault_address, &SKR_MINT, &table_address)?.amount;
        if before < st.bury {
            return Err(HdError::InvalidTokenAccount.into());
        }
        let bury_before = token::check_vault(bury_skr, &skr_vault, &SKR_MINT, &crate::BURY_ID)?.amount;
        with_table_signer(&host, table_id, bump, |signer| {
            token::transfer(vault, bury_skr, table, st.bury, signer)
        })?;
        let expected = bury_before
            .checked_add(st.bury)
            .ok_or(HdError::MathOverflow)?;
        if token::balance(bury_skr)? != expected {
            return Err(HdError::InvalidTokenAccount.into());
        }
        bury::add_lot(bury_vault, st.bury, &table_address, events::LOT_FROM_STACK)?;
    }
    log_data(&events::stack_settled_bytes(
        &table_address,
        st.total_bonds,
        st.finisher_bonds,
        st.payouts_total,
        st.bury,
        u8::try_from(n).map_err(|_| HdError::MathOverflow)?,
        st.finishers,
    ));
    Ok(())
}

// ---- 19 claim_stack ---------------------------------------------------------------------

/// `claim_stack` accounts:
/// 0. `[writable]` StackTable
/// 1. `[writable]` StackSeat (closed by this instruction)
/// 2. `[writable]` seat authority (receives the seat rent; `== seat.authority`)
/// 3. `[writable]` seat authority's SKR token account (owner field ==
///    `seat.authority`; read only when something is paid)
/// 4. `[writable]` table SKR vault
/// 5. `[]` SPL Token program
///
/// Data: empty. Permissionless: the SKR and the rent go only to the stored
/// authority. A settled table pays `seat.payout` (kind 0); an unsettled table
/// past `refund_after_ts` becomes Refunding and pays back `seat.bond`
/// (kind 1). The seat is closed, so it can never claim twice.
pub fn process_claim(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [table, seat, authority, destination, vault, token_program, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    Reader::new(data).finish()?;
    token::check_token_program(token_program)?;
    let now = clock()?.unix_timestamp;
    let (status, host, table_id, bump, vault_address) = {
        let mut t = state::load_mut::<StackTable>(table)?;
        if t.status == stack_status::OPEN {
            if now <= t.refund_after_ts.get() {
                return Err(HdError::InvalidStackState.into());
            }
            t.status = stack_status::REFUNDING;
        }
        (t.status, t.host, t.table_id.get(), t.header.bump, t.vault)
    };
    let table_address = *table.address();
    let (rig, seat_authority, amount, kind) = {
        let s = state::load::<StackSeat>(seat)?;
        if &s.table != table_address.as_array() {
            return Err(HdError::StackSeatMismatch.into());
        }
        let (amount, kind) = if status == stack_status::SETTLED {
            (s.payout.get(), events::CLAIM_PAYOUT)
        } else {
            (s.bond.get(), events::CLAIM_REFUND)
        };
        (s.rig, s.authority, amount, kind)
    };
    if authority.address().as_array() != &seat_authority {
        return Err(HdError::Unauthorized.into());
    }

    if amount > 0 {
        token::check_user_account(destination, &SKR_MINT, &seat_authority)?;
        let before = token::check_vault(vault, &vault_address, &SKR_MINT, &table_address)?.amount;
        if before < amount {
            return Err(HdError::InvalidTokenAccount.into());
        }
        with_table_signer(&host, table_id, bump, |signer| {
            token::transfer(vault, destination, table, amount, signer)
        })?;
        let expected = before.checked_sub(amount).ok_or(HdError::MathOverflow)?;
        if token::balance(vault)? != expected {
            return Err(HdError::InvalidTokenAccount.into());
        }
    }
    {
        let mut t = state::load_mut::<StackTable>(table)?;
        t.claimed_count = t
            .claimed_count
            .checked_add(1)
            .ok_or(HdError::MathOverflow)?;
        let v = t
            .claimed_total
            .get()
            .checked_add(amount)
            .ok_or(HdError::MathOverflow)?;
        // Conservation: claims never exceed what the outcome assigned.
        let cap = if status == stack_status::SETTLED {
            t.payouts_total.get()
        } else {
            t.total_bonds.get()
        };
        if v > cap {
            return Err(HdError::MathOverflow.into());
        }
        t.claimed_total.set(v);
    }
    pda::close_account(seat, authority)?;
    log_data(&events::stack_claimed_bytes(
        &table_address,
        &Address::new_from_array(rig),
        authority.address(),
        amount,
        kind,
    ));
    Ok(())
}
