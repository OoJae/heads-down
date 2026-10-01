//! Focus Bond (v1.2, SKR): a solo commitment on one shift. Tags 20
//! `lock_focus_bond`, 21 `release_focus_bond`, 22 `forfeit_focus_bond`;
//! `INTERFACE.md` §11.4 is the contract.
//!
//! The wallet locks SKR on its rig's **open** shift (armed, no BREAK or
//! FREEZE yet). The bond follows that shift's ShiftLog, identified by
//! `(rig, shift_id, start_round, start_ts)`:
//!
//! * sealed `completed` (0) → anyone may release it, to the owner only;
//! * sealed with any other reason → anyone may forfeit it into the Bury lot;
//! * never sealable (the rig was closed while the shift was open) → anyone
//!   may forfeit it ("abandoned", reason 255).
//!
//! A soft break (pickup, screen-on, unplugged) that resumed with a fresh
//! heartbeat still seals as `completed` under the v1.1 rules, so it keeps the
//! bond; hard breaks, freezes and lease lapses lose it. Nothing ever goes to
//! the team or to another player.

use pinocchio::{
    cpi::Signer, error::ProgramError, instruction::seeds, AccountView, Address, ProgramResult,
};

use crate::{
    error::HdError,
    events::{self, log_data},
    instructions::bury,
    ore::SYSTEM_PROGRAM_ID,
    pda,
    skr::FOCUS_BOND_CAP,
    state::{self, break_reason, rig_state, FocusBond, Header, Rig, ShiftLog, U64},
    token::{self, SKR_MINT},
    util::{clock, require_signer, Reader},
    BURY_ID, ID, BOND_SEED, SHIFT_SEED,
};

/// Sign as the bond PDA `["bond", rig, shift_id, bump]` and run `f`.
fn with_bond_signer<R>(
    rig: &[u8; 32],
    shift_id: u64,
    bump: u8,
    f: impl FnOnce(&[Signer]) -> R,
) -> R {
    let id = shift_id.to_le_bytes();
    let b = [bump];
    let signer_seeds = seeds!(BOND_SEED, rig, &id, &b);
    let signer = [Signer::from(&signer_seeds)];
    f(&signer)
}

/// The fields every resolution needs.
#[derive(Clone, Copy)]
struct Bond {
    rig: [u8; 32],
    authority: [u8; 32],
    vault: [u8; 32],
    shift_id: u64,
    start_round: u64,
    start_ts: i64,
    bump: u8,
}

fn load_bond(bond: &AccountView, authority: &AccountView) -> Result<Bond, ProgramError> {
    let b = state::load::<FocusBond>(bond)?;
    if authority.address().as_array() != &b.authority {
        return Err(HdError::Unauthorized.into());
    }
    Ok(Bond {
        rig: b.rig,
        authority: b.authority,
        vault: b.vault,
        shift_id: b.shift_id.get(),
        start_round: b.shift_start_round.get(),
        start_ts: b.shift_start_ts.get(),
        bump: b.header.bump,
    })
}

/// Empty the vault into `destination`, close the vault ATA and the bond
/// account (both rents to the stored authority). Returns the SKR moved.
fn drain_and_close(
    b: &Bond,
    bond: &mut AccountView,
    vault: &AccountView,
    destination: &AccountView,
    authority: &mut AccountView,
) -> Result<u64, ProgramError> {
    let bond_address = *bond.address();
    let amount = token::check_vault(vault, &b.vault, &SKR_MINT, &bond_address)?.amount;
    with_bond_signer(&b.rig, b.shift_id, b.bump, |signer| -> ProgramResult {
        if amount > 0 {
            token::transfer(vault, destination, bond, amount, signer)?;
        }
        token::close_account(vault, authority, bond, signer)
    })?;
    pda::close_account(bond, authority)?;
    Ok(amount)
}

// ---- 20 lock_focus_bond ----------------------------------------------------------------

/// `lock_focus_bond` accounts:
/// 0. `[signer, writable]` authority (`rig.authority`; pays the bond rent,
///    signs the transfer)
/// 1. `[]` Rig
/// 2. `[writable]` FocusBond PDA `["bond", rig, shift_id u64 LE]`
/// 3. `[writable]` authority's SKR token account (source)
/// 4. `[writable]` bond SKR vault = `ATA(bond, SKR mint)` (created beforehand)
/// 5. `[]` ShiftLog PDA `["shift", rig, shift_id]` (must not exist yet)
/// 6. `[]` SPL Token program
/// 7. `[]` System program
///
/// Data: `shift_id u64 | amount u64` (16 bytes after the tag).
pub fn process_lock(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [authority, rig, bond, source, vault, shift_log, token_program, system_program, ..] =
        accounts
    else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let mut r = Reader::new(data);
    let shift_id = r.u64()?;
    let amount = r.u64()?;
    r.finish()?;
    require_signer(authority)?;
    token::check_token_program(token_program)?;
    if amount == 0 || amount > FOCUS_BOND_CAP {
        return Err(HdError::AmountOutOfRange.into());
    }
    let (start_round, start_ts) = {
        let g = state::load::<Rig>(rig)?;
        if authority.address().as_array() != &g.authority {
            return Err(HdError::Unauthorized.into());
        }
        // An open, clean shift: armed and not yet broken or frozen.
        if g.shift_open != 1
            || g.shift_id.get() != shift_id
            || g.break_reason != 0
            || !(g.state == rig_state::ARMED || g.state == rig_state::DOWN)
        {
            return Err(HdError::InvalidRigState.into());
        }
        (g.shift_start_round.get(), g.shift_start_ts.get())
    };
    let rig_address = *rig.address();
    let id = shift_id.to_le_bytes();
    // The shift's log slot must be free, so the log that end_shift writes is
    // this shift's.
    let (log_pda, _) = pda::find(&[SHIFT_SEED, rig_address.as_ref(), &id], &ID);
    if shift_log.address() != &log_pda {
        return Err(ProgramError::InvalidSeeds);
    }
    if !shift_log.owned_by(&SYSTEM_PROGRAM_ID) || !shift_log.is_data_empty() {
        return Err(HdError::BondNotResolvable.into());
    }
    let (bond_pda, bump) = pda::find(&[BOND_SEED, rig_address.as_ref(), &id], &ID);
    if bond.address() != &bond_pda {
        return Err(ProgramError::InvalidSeeds);
    }
    let vault_address = token::check_new_vault(vault, &SKR_MINT, &bond_pda)?;
    token::check_user_account(source, &SKR_MINT, authority.address().as_array())?;
    let before = token::balance(vault)?;

    let bump_seed = [bump];
    let signer_seeds = seeds!(BOND_SEED, rig_address.as_ref(), &id, &bump_seed);
    pda::create_pda_account(
        authority,
        bond,
        system_program,
        core::mem::size_of::<FocusBond>(),
        &signer_seeds,
    )?;
    token::transfer(source, vault, authority, amount, &[])?;
    let expected = before.checked_add(amount).ok_or(HdError::MathOverflow)?;
    if token::balance(vault)? != expected {
        return Err(HdError::InvalidTokenAccount.into());
    }
    let now = clock()?.unix_timestamp;
    {
        let mut b = state::load_uninit_mut::<FocusBond>(bond)?;
        b.header = Header::new(state::tag::FOCUS_BOND, bump);
        b.rig = *rig_address.as_array();
        b.authority = *authority.address().as_array();
        b.vault = vault_address;
        b.shift_id = U64::new(shift_id);
        b.amount = U64::new(amount);
        b.shift_start_round = U64::new(start_round);
        b.shift_start_ts.set(start_ts);
        b.locked_ts.set(now);
    }
    log_data(&events::focus_bond_locked_bytes(
        &bond_pda,
        &rig_address,
        authority.address(),
        shift_id,
        amount,
    ));
    Ok(())
}

// ---- 21 release_focus_bond --------------------------------------------------------------

/// `release_focus_bond` accounts:
/// 0. `[writable]` FocusBond (closed)
/// 1. `[]` ShiftLog of the bonded shift (sealed `completed`)
/// 2. `[writable]` bond SKR vault (emptied and closed)
/// 3. `[writable]` authority's SKR token account (owner field == bond.authority)
/// 4. `[writable]` bond authority (receives both rents)
/// 5. `[]` SPL Token program
///
/// Data: empty. Permissionless: the SKR goes only to the stored owner.
pub fn process_release(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [bond, shift_log, vault, destination, authority, token_program, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    Reader::new(data).finish()?;
    token::check_token_program(token_program)?;
    let b = load_bond(bond, authority)?;
    {
        let log = state::load::<ShiftLog>(shift_log)?;
        if !same_shift(&log, &b) || log.break_reason != break_reason::COMPLETED {
            return Err(HdError::BondNotResolvable.into());
        }
    }
    token::check_user_account(destination, &SKR_MINT, &b.authority)?;
    let bond_address = *bond.address();
    let amount = drain_and_close(&b, bond, vault, destination, authority)?;
    log_data(&events::focus_bond_released_bytes(
        &bond_address,
        &Address::new_from_array(b.rig),
        b.shift_id,
        amount,
    ));
    Ok(())
}

/// `log` is the bonded shift's ShiftLog (same rig, id, start round and time).
fn same_shift(log: &ShiftLog, b: &Bond) -> bool {
    log.rig == b.rig
        && log.shift_id.get() == b.shift_id
        && log.start_round.get() == b.start_round
        && log.start_ts.get() == b.start_ts
}

// ---- 22 forfeit_focus_bond ----------------------------------------------------------------

/// `forfeit_focus_bond` accounts:
/// 0. `[writable]` FocusBond (closed)
/// 1. `[]` ShiftLog PDA `["shift", bond.rig, bond.shift_id]` (sealed, or empty)
/// 2. `[]` the bond's Rig address (read when the log is empty; may be closed)
/// 3. `[writable]` bond SKR vault (emptied and closed)
/// 4. `[writable]` BuryVault PDA
/// 5. `[writable]` BuryVault SKR ATA
/// 6. `[writable]` bond authority (receives both rents)
/// 7. `[]` SPL Token program
///
/// Data: empty. Permissionless, so nobody (the owner included) can hold a
/// broken bond hostage. The SKR becomes a Bury lot.
pub fn process_forfeit(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [bond, shift_log, rig, vault, bury_vault, bury_skr, authority, token_program, ..] =
        accounts
    else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    Reader::new(data).finish()?;
    token::check_token_program(token_program)?;
    let b = load_bond(bond, authority)?;
    let id = b.shift_id.to_le_bytes();
    let (log_pda, _) = pda::find(&[SHIFT_SEED, &b.rig, &id], &ID);
    if shift_log.address() != &log_pda || rig.address().as_array() != &b.rig {
        return Err(ProgramError::InvalidSeeds);
    }
    let reason = if state::is_initialized::<ShiftLog>(shift_log) {
        let log = state::load::<ShiftLog>(shift_log)?;
        if same_shift(&log, &b) {
            if log.break_reason == break_reason::COMPLETED {
                // Completed: release, never forfeit.
                return Err(HdError::BondNotResolvable.into());
            }
            log.break_reason
        } else {
            // The slot was free at lock, so another shift's log here means
            // the rig was closed and re-registered: the bonded shift can
            // never be sealed.
            events::BOND_ABANDONED
        }
    } else {
        // Not sealed yet: only forfeitable if the bonded shift is gone
        // (the rig was closed while it was open).
        let alive = state::is_initialized::<Rig>(rig) && {
            let g = state::load::<Rig>(rig)?;
            g.shift_id.get() == b.shift_id
                && g.shift_start_round.get() == b.start_round
                && g.shift_start_ts.get() == b.start_ts
        };
        if alive {
            return Err(HdError::BondNotResolvable.into());
        }
        events::BOND_ABANDONED
    };

    let skr_vault = bury::check_accounts(bury_vault, bury_skr)?;
    let bury_before = token::check_vault(bury_skr, &skr_vault, &SKR_MINT, &BURY_ID)?.amount;
    let bond_address = *bond.address();
    let amount = drain_and_close(&b, bond, vault, bury_skr, authority)?;
    let expected = bury_before
        .checked_add(amount)
        .ok_or(HdError::MathOverflow)?;
    if token::balance(bury_skr)? != expected {
        return Err(HdError::InvalidTokenAccount.into());
    }
    if amount > 0 {
        bury::add_lot(bury_vault, amount, &bond_address, events::LOT_FROM_BOND)?;
    }
    log_data(&events::focus_bond_forfeited_bytes(
        &bond_address,
        &Address::new_from_array(b.rig),
        b.shift_id,
        amount,
        reason,
    ));
    Ok(())
}
