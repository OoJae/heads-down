//! `dig` (tag 6): batched, permissionless, phone-gated ORE deploys.
//!
//! Data: `n u8 | n x entry(20)` where entry = `hb_ix u8 | hb_sig_index u8 |
//! counter u64 | round_id u64 | lease_rounds u8 | _pad u8` (`hb_ix = 0xFF`
//! reuses the rig's current lease).
//!
//! Accounts (fixed order, `INTERFACE.md`):
//! ```text
//! 0 cranker (signer, w)   4 ore_config (w)  8 ore_program       12.. per rig i (4 each):
//! 1 config                5 round (w)       9 entropy_var (w)        rig (w), authority (w),
//! 2 executor PDA (w)      6 treasury (w)   10 entropy_program        automation (w), miner (w)
//! 3 board (w)             7 system_program 11 instructions sysvar
//! ```
//!
//! Account-validation failures (anything the cranker chose) fail the whole
//! transaction. Everything a *user* can change between the crank's
//! simulation and landing (revoked executor, fee, balance, frozen rig, stale
//! heartbeat, gate, caps, ORE pre-flight) **skips** that rig with a
//! `RigSkipped{rig, round_id, error}` event, so one rig cannot sink a batch.
//! Invariant violations after the CPI (the executor float drained, a debit
//! that breaks a cap, ORE deviating from its pinned semantics) fail the
//! transaction, reverting everything.

use pinocchio::{
    error::ProgramError,
    instruction::seeds,
    sysvars::{rent::Rent, Sysvar},
    AccountView, Address, ProgramResult,
};
use pinocchio_system::instructions::Transfer;

use crate::{
    error::HdError,
    events,
    instructions::{apply_heartbeat, lease_covers, HeartbeatEntry, ENTRY_LEN, NO_HEARTBEAT},
    logic,
    ore::{
        self, DeployAccounts, CHECKPOINT_FEE, ONE_ORE, STRATEGY_DISCRETIONARY, SYSTEM_PROGRAM_ID,
    },
    state::{self, plan_flags, rig_state, Rig},
    util::{clock, load_config, require_signer, require_writable},
    EXECUTOR_ID, EXECUTOR_SEED,
};

/// Shared accounts before the per-rig groups.
pub const FIXED_ACCOUNTS: usize = 12;
/// Accounts per rig.
pub const ACCOUNTS_PER_RIG: usize = 4;
/// Upper bound on rigs per `dig` (bounded loops; a v1 transaction's 64
/// addresses cap real batches lower).
pub const MAX_RIGS_PER_DIG: usize = 32;
/// Float the Executor PDA keeps above rent-exemption before it reimburses a
/// cranker, so later rigs in a batch can still get a CHECKPOINT_FEE top-up.
pub const EXECUTOR_RESERVE: u64 = 10 * CHECKPOINT_FEE;

/// Values shared by every rig in the batch.
struct Ctx {
    now: i64,
    slot: u64,
    board_round: u64,
    pot: u64,
    ema_ev: u128,
    solo_mask: u32,
    executor_fee: u64,
    crank_fee: u64,
    executor_bump: u8,
    rent_floor: u64,
}

/// Outcome of one rig that was not skipped.
struct Dug {
    lamports: u64,
    mask: u32,
}

/// Handler.
pub fn process(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    let (&n_rigs, entries) = data.split_first().ok_or(HdError::InvalidInstruction)?;
    let n = usize::from(n_rigs);
    if n == 0 || n > MAX_RIGS_PER_DIG || entries.len() != n.saturating_mul(ENTRY_LEN) {
        return Err(HdError::InvalidInstruction.into());
    }
    if accounts.len() != FIXED_ACCOUNTS.saturating_add(n.saturating_mul(ACCOUNTS_PER_RIG)) {
        return Err(HdError::InvalidInstruction.into());
    }
    let (fixed, rigs) = accounts.split_at_mut(FIXED_ACCOUNTS);
    let [cranker, config, executor, board, ore_config, round, treasury, system_program, ore_program, entropy_var, entropy_program, ix_sysvar] =
        fixed
    else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };

    // ---- shared account validation (fail the transaction) ----------------
    require_signer(cranker)?;
    require_writable(cranker)?;
    let (executor_fee, crank_fee, executor_bump) = {
        let c = load_config(config)?;
        if c.paused != 0 {
            return Err(HdError::Paused.into());
        }
        (c.executor_fee.get(), c.crank_fee.get(), c.executor_bump)
    };
    if executor.address() != &EXECUTOR_ID
        || !executor.owned_by(&SYSTEM_PROGRAM_ID)
        || !executor.is_data_empty()
        || !executor.is_writable()
    {
        return Err(HdError::InvalidExecutor.into());
    }
    let b = ore::read_board(board)?;
    ore::check_config(ore_config)?;
    let pot = ore::read_motherlode(treasury)?;
    ore::check_round(round, b.round_id)?;
    if system_program.address() != &SYSTEM_PROGRAM_ID
        || ore_program.address() != &ore::ORE_PROGRAM_ID
        || entropy_var.address() != &ore::VAR_ADDRESS
        || entropy_program.address() != &ore::ENTROPY_PROGRAM_ID
    {
        return Err(HdError::InvalidOreAccount.into());
    }
    for acc in [&*board, &*ore_config, &*round, &*treasury, &*entropy_var] {
        require_writable(acc)?;
    }
    p256_introspect::check_instructions_sysvar(ix_sysvar)?;

    // ---- duplicate rigs (fail) --------------------------------------------
    for i in 1..n {
        let a = rigs
            .get(i.saturating_mul(ACCOUNTS_PER_RIG))
            .ok_or(HdError::InvalidInstruction)?;
        for j in 0..i {
            let bj = rigs
                .get(j.saturating_mul(ACCOUNTS_PER_RIG))
                .ok_or(HdError::InvalidInstruction)?;
            if a.address() == bj.address() {
                return Err(HdError::DuplicateRig.into());
            }
        }
    }

    let clk = clock()?;
    let ctx = Ctx {
        now: clk.unix_timestamp,
        slot: clk.slot,
        board_round: b.round_id,
        pot,
        ema_ev: logic::ema_ev(b.ema, pot),
        solo_mask: ore::distribution_mask(b.round_id),
        executor_fee,
        crank_fee,
        executor_bump,
        rent_floor: Rent::get()?.try_minimum_balance(0)?,
    };

    for (i, group) in rigs.chunks_exact_mut(ACCOUNTS_PER_RIG).enumerate() {
        let start = i.saturating_mul(ENTRY_LEN);
        let entry = HeartbeatEntry::parse(
            entries
                .get(start..start.saturating_add(ENTRY_LEN))
                .ok_or(HdError::InvalidInstruction)?,
        )?;
        let [rig, authority, automation, miner] = group else {
            return Err(ProgramError::NotEnoughAccountKeys);
        };
        let rig_address = *rig.address();
        let shared = Shared {
            cranker,
            executor,
            board,
            ore_config,
            round,
            treasury,
            system_program,
            ore_program,
            entropy_var,
            entropy_program,
            ix_sysvar,
        };
        match dig_one(&ctx, &shared, rig, authority, automation, miner, &entry)? {
            Ok(d) => events::rig_dug(
                &rig_address,
                ctx.board_round,
                d.lamports,
                d.mask,
                u64::try_from(ctx.ema_ev).unwrap_or(u64::MAX),
            ),
            Err(code) => events::rig_skipped(&rig_address, ctx.board_round, code),
        }
    }
    Ok(())
}

struct Shared<'a> {
    cranker: &'a AccountView,
    executor: &'a AccountView,
    board: &'a AccountView,
    ore_config: &'a AccountView,
    round: &'a AccountView,
    treasury: &'a AccountView,
    system_program: &'a AccountView,
    ore_program: &'a AccountView,
    entropy_var: &'a AccountView,
    entropy_program: &'a AccountView,
    ix_sysvar: &'a AccountView,
}

macro_rules! skip {
    ($e:expr) => {
        return Ok(Err($e.code()))
    };
}

/// One rig. `Err(_)` fails the transaction; `Ok(Err(code))` skips the rig.
#[inline(never)]
fn dig_one(
    ctx: &Ctx,
    s: &Shared<'_>,
    rig: &mut AccountView,
    authority: &AccountView,
    automation: &AccountView,
    miner: &AccountView,
    entry: &HeartbeatEntry,
) -> Result<Result<Dug, u32>, ProgramError> {
    // ---- 1. accounts (fail) ---------------------------------------------
    let rig_address = *rig.address();
    // A rig closed after the crank planned this batch (a tombstone, or nothing
    // at all at the PDA) is skipped like any other rig that cannot be dug. As a
    // failure it let one wallet, by closing its rig just before the batch
    // landed, make every other rig in the batch miss the round.
    if !rig.owned_by(&crate::ID) || rig.data_len() != core::mem::size_of::<Rig>() {
        skip!(HdError::InvalidAccountTag);
    }
    let mut g = state::load_mut::<Rig>(rig)?;
    if authority.address().as_array() != &g.authority {
        return Err(HdError::Unauthorized.into());
    }
    // Re-derive from rig.authority with the canonical bumps found at
    // registration: one SHA-256 each instead of a bump search (the bump is
    // never caller-supplied; ORE re-checks the seeds inside the CPI too).
    let auto_pda = Address::derive_address(
        &[ore::AUTOMATION_SEED, &g.authority],
        Some(g.ore_automation_bump),
        &ore::ORE_PROGRAM_ID,
    );
    let miner_pda = Address::derive_address(
        &[ore::MINER_SEED, &g.authority],
        Some(g.ore_miner_bump),
        &ore::ORE_PROGRAM_ID,
    );
    if automation.address() != &auto_pda || miner.address() != &miner_pda {
        return Err(HdError::InvalidOreAccount.into());
    }
    for acc in [authority, automation, miner] {
        require_writable(acc)?;
    }

    // ---- 1b. user-controlled ORE state (skip) ---------------------------
    let Some(auto) = ore::read_automation(automation)? else {
        skip!(HdError::InvalidExecutor); // revoked / closed
    };
    if auto.authority != g.authority {
        return Err(HdError::InvalidOreAccount.into());
    }
    if auto.executor != *EXECUTOR_ID.as_array() {
        skip!(HdError::InvalidExecutor);
    }
    if auto.strategy != STRATEGY_DISCRETIONARY || auto.fee != ctx.executor_fee {
        skip!(HdError::StrategyMismatch);
    }
    let m = match ore::read_miner(miner)? {
        Some(m) if m.authority == g.authority => m,
        _ => skip!(HdError::InvalidOreAccount),
    };

    // ---- 2. state + lease ---------------------------------------------------
    match g.state {
        rig_state::ARMED | rig_state::DOWN => {}
        rig_state::COOLING if entry.hb_ix != NO_HEARTBEAT => {}
        rig_state::FROZEN => skip!(HdError::RigFrozen),
        _ => skip!(HdError::RigNotArmed),
    }
    if entry.hb_ix != NO_HEARTBEAT {
        if let Err(code) =
            apply_heartbeat(&mut g, &rig_address, s.ix_sysvar, entry, ctx.board_round)
        {
            return Ok(Err(code));
        }
    }
    if !lease_covers(&g, ctx.board_round) {
        skip!(HdError::LeaseExpired);
    }

    // ---- 3. idempotency ---------------------------------------------------
    if g.last_dug_round.get() == ctx.board_round {
        skip!(HdError::AlreadyDugRound);
    }

    // ---- 4. gate ------------------------------------------------------------
    if ctx.now > g.caps_expiry_ts.get() {
        skip!(HdError::CapsExpired);
    }
    if ctx.now < g.plan_window_start_ts.get() || ctx.now > g.plan_window_end_ts.get() {
        skip!(HdError::OutsideWindow);
    }
    if g.plan_flags & plan_flags::FOCUS_ONLY != 0 {
        skip!(HdError::FocusOnly);
    }
    if !logic::gate_open(ctx.ema_ev, g.plan_max_ev_cost.get(), g.cap_max_cost.get()) {
        skip!(HdError::CostGate);
    }

    // ---- 5. tiles + amount --------------------------------------------------
    let (week_start, spent_week) =
        logic::roll_week(g.week_start_ts.get(), g.spent_week.get(), ctx.now);
    g.week_start_ts.set(week_start);
    g.spent_week.set(spent_week);

    let same_round = m.round_id == ctx.board_round;
    let held = if same_round { m.deployed_mask() } else { 0 };
    let deployed = ore::read_round_deployed(s.round)?;
    let mask = logic::select_tiles(
        &deployed,
        ctx.solo_mask,
        held,
        g.plan_split_tiles,
        g.plan_solo_tiles,
    );
    let k = u64::from(mask.count_ones());
    let budget = logic::dig_budget(
        g.plan_dig_lamports.get(),
        g.cap_round.get(),
        g.cap_shift.get(),
        g.spent_shift.get(),
        g.cap_week.get(),
        spent_week,
        auto.fee,
    );
    let per_tile = budget.checked_div(k).unwrap_or(0).min(auto.amount);
    if per_tile == 0 {
        skip!(HdError::BudgetExhausted);
    }
    let total = per_tile.checked_mul(k).ok_or(HdError::MathOverflow)?;
    let sum_before = if same_round { m.deployed_sum()? } else { 0 };
    let first_deploy = sum_before == 0;
    let fee_due = if first_deploy { auto.fee } else { 0 };
    let need = total.checked_add(fee_due).ok_or(HdError::MathOverflow)?;
    if auto.balance < need {
        skip!(HdError::InsufficientAutomationBalance);
    }

    // ---- 5b. pre-flight every ORE abort / silent no-op ----------------------
    let live = ore::read_board(s.board)?;
    if !(ctx.slot >= live.start_slot && ctx.slot < live.end_slot) {
        skip!(HdError::RoundNotActive);
    }
    if !(same_round || m.checkpoint_id == m.round_id) {
        skip!(HdError::MinerNotCheckpointed);
    }
    let max_ml = u64::from(auto.max_motherlode)
        .checked_mul(ONE_ORE)
        .ok_or(HdError::MathOverflow)?;
    let min_ml = u64::from(auto.min_motherlode)
        .checked_mul(ONE_ORE)
        .ok_or(HdError::MathOverflow)?;
    if ctx.pot > max_ml || ctx.pot < min_ml {
        skip!(HdError::MotherlodeCondition);
    }
    let checkpoint_due = m.checkpoint_fee == 0;
    let exec_before = s.executor.lamports();
    if checkpoint_due
        && exec_before
            < ctx
                .rent_floor
                .checked_add(CHECKPOINT_FEE)
                .ok_or(HdError::MathOverflow)?
    {
        skip!(HdError::ExecutorUnderfunded);
    }

    // ---- 6. CPI ORE deploy, signed by the Executor PDA ---------------------
    let balance_before = auto.balance;
    ore::cpi_deploy(
        &DeployAccounts {
            executor: s.executor,
            authority,
            automation,
            board: s.board,
            config: s.ore_config,
            miner,
            round: s.round,
            treasury: s.treasury,
            system_program: s.system_program,
            ore_program: s.ore_program,
            var: s.entropy_var,
            entropy_program: s.entropy_program,
        },
        per_tile,
        mask,
        ctx.executor_bump,
    )?;

    // ---- 7. reload + invariants ---------------------------------------------
    let exec_after = s.executor.lamports();
    if exec_after.saturating_add(CHECKPOINT_FEE) < exec_before
        || (!checkpoint_due && exec_after < exec_before)
    {
        // Something other than ORE's CHECKPOINT_FEE top-up left the float.
        return Err(HdError::InvalidExecutor.into());
    }
    if !s.executor.owned_by(&SYSTEM_PROGRAM_ID) || !s.executor.is_data_empty() {
        return Err(HdError::InvalidExecutor.into());
    }
    let cp_paid = if checkpoint_due { CHECKPOINT_FEE } else { 0 };
    let fee_received = exec_after
        .saturating_add(cp_paid)
        .saturating_sub(exec_before);

    let m_after = match ore::read_miner(miner)? {
        Some(x) if x.authority == g.authority => x,
        _ => return Err(HdError::InvalidOreAccount.into()),
    };
    let deployed_now = if m_after.round_id == ctx.board_round {
        // ORE reset / extended the Miner for this round: the new SOL on it.
        m_after
            .deployed_sum()?
            .checked_sub(sum_before)
            .ok_or(HdError::InvalidOreAccount)?
    } else if m_after.round_id == m.round_id {
        // ORE returned before touching the Miner (e.g. its Motherlode
        // no-op, `deploy.rs:79-84`): nothing was deployed.
        0
    } else {
        return Err(HdError::InvalidOreAccount.into());
    };
    let debit = match ore::read_automation(automation)? {
        Some(a) => {
            let d = balance_before
                .checked_sub(a.balance)
                .ok_or(HdError::InvalidOreAccount)?;
            // The Automation may only have paid the tiles plus the one fee
            // the Executor received.
            if d != deployed_now
                .checked_add(fee_received)
                .ok_or(HdError::MathOverflow)?
            {
                return Err(HdError::InvalidOreAccount.into());
            }
            d
        }
        // Closed by ORE during the CPI (balance fell below one more square,
        // `deploy.rs:350-352`): what left for ORE is the tiles plus the fee;
        // the rest went back to the authority.
        None => deployed_now
            .checked_add(fee_received)
            .ok_or(HdError::MathOverflow)?,
    };
    if deployed_now > total || debit > need {
        return Err(HdError::InvalidOreAccount.into());
    }

    // Spend accounting: the whole debit, and never past a wallet-signed cap.
    let spent_shift = g
        .spent_shift
        .get()
        .checked_add(debit)
        .ok_or(HdError::MathOverflow)?;
    let spent_week = spent_week.checked_add(debit).ok_or(HdError::MathOverflow)?;
    if debit > g.cap_round.get() || spent_shift > g.cap_shift.get() || spent_week > g.cap_week.get()
    {
        return Err(HdError::BudgetExhausted.into());
    }
    g.spent_shift.set(spent_shift);
    g.spent_week.set(spent_week);
    if deployed_now == 0 {
        // ORE returned Ok without deploying: not a dig, no reimbursement.
        return Ok(Err(HdError::OreNoOp.code()));
    }
    g.last_dug_round.set(ctx.board_round);
    let v = g.shift_rounds_dug.get().saturating_add(1);
    g.shift_rounds_dug.set(v);
    let v = g.lifetime_rounds_dug.get().saturating_add(1);
    g.lifetime_rounds_dug.set(v);
    let v = g
        .lifetime_lamports_deployed
        .get()
        .saturating_add(deployed_now);
    g.lifetime_lamports_deployed.set(v);
    drop(g);

    // ---- 8. reimburse the cranker ------------------------------------------
    // Only out of the fee the Executor received in THIS dig. ORE charges the
    // Automation fee on a miner's first deploy of a round only (`deploy.rs:338-342`),
    // so a rig whose owner already deployed by hand this round pays the Executor
    // nothing, and a cranker must not be able to draw the shared float for it
    // (audit: reimbursement without a fee drains the Executor).
    if fee_received >= ctx.crank_fee {
        reimburse(ctx, s)?;
    }

    Ok(Ok(Dug {
        lamports: deployed_now,
        mask,
    }))
}

/// Pay `config.crank_fee` from the Executor PDA to the cranker if the PDA
/// stays at or above rent-exemption plus [`EXECUTOR_RESERVE`].
fn reimburse(ctx: &Ctx, s: &Shared<'_>) -> ProgramResult {
    if ctx.crank_fee == 0 || s.cranker.address() == s.executor.address() {
        return Ok(());
    }
    let floor = ctx
        .rent_floor
        .checked_add(EXECUTOR_RESERVE)
        .and_then(|f| f.checked_add(ctx.crank_fee))
        .ok_or(HdError::MathOverflow)?;
    if s.executor.lamports() < floor {
        return Ok(());
    }
    let bump = [ctx.executor_bump];
    let signer_seeds = seeds!(EXECUTOR_SEED, &bump);
    let signer = [pinocchio::cpi::Signer::from(&signer_seeds)];
    Transfer {
        from: s.executor,
        to: s.cranker,
        lamports: ctx.crank_fee,
    }
    .invoke_signed(&signer)
}
