//! `set_caps` (tag 3).
//!
//! Accounts: 0 `[signer]` authority, 1 `[writable]` Rig.
//! Data (40 bytes): `cap_week u64 | cap_shift u64 | cap_round u64 |
//! cap_max_cost u64 | caps_expiry_ts i64`.
//!
//! Wallet-only: the only instruction that can raise spend limits. The armed
//! plan is clamped down to the new caps immediately
//! (`plan_max_ev_cost <= cap_max_cost`, `plan_dig_lamports <= cap_round`).

use pinocchio::{error::ProgramError, AccountView, ProgramResult};

use crate::{
    logic,
    state::{self, Rig},
    util::{clock, require_rig_authority, Reader},
};

/// Handler.
pub fn process(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let [authority, rig, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let mut r = Reader::new(data);
    let cap_week = r.u64()?;
    let cap_shift = r.u64()?;
    let cap_round = r.u64()?;
    let cap_max_cost = r.u64()?;
    let caps_expiry_ts = r.i64()?;
    r.finish()?;

    let mut g = state::load_mut::<Rig>(rig)?;
    require_rig_authority(&g, authority)?;

    g.cap_week.set(cap_week);
    g.cap_shift.set(cap_shift);
    g.cap_round.set(cap_round);
    g.cap_max_cost.set(cap_max_cost);
    g.caps_expiry_ts.set(caps_expiry_ts);
    let v = g.plan_max_ev_cost.get().min(cap_max_cost);
    g.plan_max_ev_cost.set(v);
    let v = g.plan_dig_lamports.get().min(cap_round);
    g.plan_dig_lamports.set(v);

    let (ws, sw) = logic::roll_week(g.week_start_ts.get(), g.spent_week.get(), clock()?.unix_timestamp);
    g.week_start_ts.set(ws);
    g.spent_week.set(sw);
    Ok(())
}
