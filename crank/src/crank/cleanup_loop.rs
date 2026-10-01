//! Permissionless cleanups (INTERFACE v1.2 §11.6, §11.7): `forfeit_focus_bond` for bonds whose
//! shift ended broken (or can never be sealed), and `refund_gift` for gifts past their expiry.
//!
//! Both instructions take no signer: the program fixes where everything goes (a forfeited
//! bond's SKR to the Bury lot and its rents to the owner; an expired gift's lamports to its
//! sender), so the crank can only trigger them and gains nothing. It pays the fee, which is
//! why each sweep is capped (`cleanup.max_per_pass`) and budgeted
//! (`cleanup.max_lamports_per_day`), and why every transaction is simulated first: one the
//! program would refuse is never paid for.
//!
//! A bond whose shift sealed `completed` is the owner's: it is left for `release_focus_bond`
//! and never forfeited (the program would refuse it with `BondNotResolvable`).

use std::time::{Duration, Instant};

use solana_address::Address;

use super::{lock, Crank};
use crate::hd::{self, Rig, ShiftLog};
use crate::rpc;
use crate::sender::Outcome;
use crate::skr::{self, BondResolution, FocusBond, GiftEscrow};
use crate::tx;

/// A sweep runs no more often than this, however many events ask for one.
pub const MIN_SWEEP_GAP: Duration = Duration::from_secs(20);

/// One thing a sweep will do.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum CleanupAction {
    /// `forfeit_focus_bond`: the SKR goes to the Bury lot, the rents to the owner.
    Forfeit {
        /// FocusBond PDA.
        address: Address,
        /// Decoded bond.
        bond: FocusBond,
        /// The reason the program will report (1..=8, or 255 abandoned).
        reason: u8,
    },
    /// `refund_gift`: every lamport goes to the stored sender.
    Refund {
        /// GiftEscrow PDA.
        address: Address,
        /// Decoded gift.
        gift: GiftEscrow,
    },
}

impl CleanupAction {
    /// The account the action closes.
    pub fn address(&self) -> Address {
        match self {
            CleanupAction::Forfeit { address, .. } | CleanupAction::Refund { address, .. } => *address,
        }
    }
}

/// Which bonds to forfeit: those the program's rule resolves as a forfeit (`abandoned`
/// selects the reason-255 ones too). Bonds that would be released, or that are not sealed
/// yet, are left alone.
pub fn plan_forfeits(bonds: &[(Address, FocusBond, Option<ShiftLog>, Option<Rig>)], abandoned: bool) -> Vec<CleanupAction> {
    bonds
        .iter()
        .filter_map(|(address, bond, log, rig)| match skr::resolve_bond(bond, log.as_ref(), rig.as_ref()) {
            BondResolution::Forfeit(reason) if reason != hd::BOND_ABANDONED || abandoned => {
                Some(CleanupAction::Forfeit { address: *address, bond: *bond, reason })
            }
            _ => None,
        })
        .collect()
}

/// Which gifts to refund at cluster time `now_ts`: those whose `expiry_ts` passed at least
/// `grace_secs` ago, oldest expiry first.
pub fn plan_refunds(gifts: &[(Address, GiftEscrow)], now_ts: i64, grace_secs: i64) -> Vec<CleanupAction> {
    let mut due: Vec<&(Address, GiftEscrow)> = gifts.iter().filter(|(_, g)| g.refundable_at(now_ts.saturating_sub(grace_secs.max(0)))).collect();
    due.sort_by_key(|(a, g)| (g.expiry_ts, a.to_bytes()));
    due.into_iter().map(|(address, gift)| CleanupAction::Refund { address: *address, gift: *gift }).collect()
}

impl Crank {
    /// Sweep on a timer, and sooner when a shift ends on the program's log stream.
    pub(crate) async fn cleanup_loop(self: std::sync::Arc<Self>) {
        let every = Duration::from_secs(self.cfg.cleanup.poll_secs.max(1));
        let mut last = Instant::now().checked_sub(every).unwrap_or_else(Instant::now);
        loop {
            let wait = every.saturating_sub(last.elapsed());
            tokio::select! {
                _ = tokio::time::sleep(wait) => {}
                _ = self.cleanup_notify.notified() => {
                    // Let the transaction that ended the shift settle in, and never sweep in a burst.
                    tokio::time::sleep(MIN_SWEEP_GAP.saturating_sub(last.elapsed()).max(Duration::from_secs(2))).await;
                }
            }
            last = Instant::now();
            if self.breaker.is_tripped() || self.is_draining() {
                continue;
            }
            match self.cleanup_sweep().await {
                Ok(n) if n > 0 => tracing::info!(done = n, "cleanup sweep"),
                Ok(_) => {}
                Err(e) => tracing::warn!(error = %e, "cleanup sweep failed"),
            }
        }
    }

    /// Read every FocusBond with the ShiftLog slot and the Rig that resolve it.
    async fn load_bonds(&self) -> anyhow::Result<Vec<(Address, FocusBond, Option<ShiftLog>, Option<Rig>)>> {
        let accounts = self.rpc.get_program_accounts(&self.program_id, &rpc::focus_bond_filters()).await?;
        let bonds: Vec<(Address, FocusBond)> =
            accounts.into_iter().filter_map(|(a, acc)| FocusBond::decode(&self.program_id, &acc.owner, &acc.data).ok().map(|b| (a, b))).collect();
        let mut keys = Vec::with_capacity(2 * bonds.len());
        for (_, b) in &bonds {
            keys.push(hd::shift_log_pda(&self.program_id, &b.rig, b.shift_id).0);
            keys.push(b.rig);
        }
        let accs = self.rpc.get_multiple_accounts(&keys).await?;
        let mut it = accs.into_iter();
        Ok(bonds
            .into_iter()
            .map(|(a, b)| {
                let log = it.next().flatten().and_then(|acc| ShiftLog::decode(&self.program_id, &acc.owner, &acc.data).ok());
                let rig = it.next().flatten().and_then(|acc| Rig::decode(&self.program_id, &acc.owner, &acc.data).ok());
                (a, b, log, rig)
            })
            .collect())
    }

    /// One sweep: forfeit what is forfeitable, refund what expired, within the caps. Returns
    /// how many transactions landed.
    pub async fn cleanup_sweep(&self) -> anyhow::Result<usize> {
        let c = &self.cfg.cleanup;
        let mut actions: Vec<CleanupAction> = Vec::new();
        if c.forfeit_focus_bonds {
            actions.extend(plan_forfeits(&self.load_bonds().await?, c.forfeit_abandoned_bonds));
        }
        if c.refund_gifts {
            let accounts = self.rpc.get_program_accounts(&self.program_id, &rpc::gift_escrow_filters()).await?;
            let gifts: Vec<(Address, GiftEscrow)> =
                accounts.into_iter().filter_map(|(a, acc)| GiftEscrow::decode(&self.program_id, &acc.owner, &acc.data).ok().map(|g| (a, g))).collect();
            // The program compares `expiry_ts` with the cluster clock: so does the crank.
            let now = match self.rpc.get_cluster_unix_timestamp().await {
                Ok(t) => t,
                Err(_) => self.chain.borrow().unix_now(),
            };
            actions.extend(plan_refunds(&gifts, now, c.gift_grace_secs));
        }
        {
            let mut tried = lock(&self.cleanup_tried);
            let retry = Duration::from_secs(c.retry_secs);
            tried.retain(|_, at| at.elapsed() < retry);
            actions.retain(|a| !tried.contains_key(&a.address()));
        }
        let price = self.cfg.dig.cu_price_micro_lamports;
        let mut done = 0usize;
        for action in actions.into_iter().take(c.max_per_pass) {
            let (cu, ix, label) = match &action {
                CleanupAction::Forfeit { address, bond, .. } => {
                    // A forfeit moves the SKR into the Bury lot, which must exist. If it cannot
                    // be made now, the refunds of this sweep still go ahead.
                    match self.ensure_bury_vault().await {
                        Ok(true) => {}
                        Ok(false) => {
                            self.metrics.cleanup_failed.inc("bury_vault");
                            continue;
                        }
                        Err(e) => {
                            self.metrics.cleanup_failed.inc("bury_vault");
                            tracing::warn!(error = %e, "the Bury vault could not be created; the forfeit waits for the next sweep");
                            continue;
                        }
                    }
                    (c.forfeit_cu_limit, skr::forfeit_focus_bond_ix(&self.program_id, address, bond), "forfeit_focus_bond")
                }
                CleanupAction::Refund { address, gift } => (c.refund_cu_limit, skr::refund_gift_ix(&self.program_id, address, &gift.sender), "refund_gift"),
            };
            let fee = tx::fee_for(0, cu, price, tx::LAMPORTS_PER_SIGNATURE);
            if !self.cleanup_budget.try_take(fee) {
                self.metrics.cleanup_failed.inc("budget");
                tracing::warn!(fee, "the cleanup fee budget is spent for today");
                break;
            }
            lock(&self.cleanup_tried).insert(action.address(), Instant::now());
            match self.send_signed(&tx::with_compute_budget(cu, price, [ix])).await {
                Ok((sig, Outcome::Landed { err: None, slot })) => {
                    done += 1;
                    self.metrics.cleanup_fees_lamports.add(fee);
                    match &action {
                        CleanupAction::Forfeit { address, bond, reason } => {
                            self.metrics.bonds_forfeited.inc(hd::bond_reason_name(*reason));
                            self.metrics.bonds_forfeited_skr.add(bond.amount);
                            tracing::info!(%sig, slot, bond = %address, rig = %bond.rig, shift = bond.shift_id, skr = bond.amount, reason = hd::bond_reason_name(*reason), "forfeited a Focus Bond to the Bury lot (permissionless; the rents went to its owner)");
                        }
                        CleanupAction::Refund { address, gift } => {
                            self.metrics.gifts_refunded.inc();
                            self.metrics.gifts_refunded_lamports.add(gift.lamports);
                            tracing::info!(%sig, slot, gift = %address, sender = %gift.sender, lamports = gift.lamports, "refunded an expired gift to its sender (permissionless)");
                        }
                    }
                }
                Ok((sig, other)) => {
                    self.metrics.cleanup_failed.inc("onchain");
                    tracing::warn!(%sig, account = %action.address(), outcome = ?other, "{label} did not land");
                }
                Err(e) => {
                    // Refused in simulation (someone else resolved it, or it is not due on the
                    // cluster's clock yet): nothing was sent, nothing was paid.
                    self.cleanup_budget.refund(fee);
                    self.metrics.cleanup_failed.inc("simulation");
                    tracing::debug!(account = %action.address(), error = %e, "{label} not sent");
                }
            }
        }
        Ok(done)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bond(i: u8, shift: u64) -> FocusBond {
        FocusBond {
            bump: 255,
            rig: Address::new_from_array([i; 32]),
            authority: Address::new_from_array([0x40 + i; 32]),
            vault: Address::new_from_array([0x80 + i; 32]),
            shift_id: shift,
            amount: 100,
            shift_start_round: 50,
            shift_start_ts: 1_000,
            locked_ts: 1_010,
        }
    }

    fn log(b: &FocusBond, reason: u8) -> ShiftLog {
        ShiftLog {
            rig: b.rig,
            shift_id: b.shift_id,
            start_round: b.shift_start_round,
            end_round: 99,
            dark_rounds: 10,
            rounds_dug: 0,
            lamports_deployed: 0,
            break_reason: reason,
            mode: 0,
            start_ts: b.shift_start_ts,
            end_ts: 2_000,
        }
    }

    #[test]
    fn only_broken_and_abandoned_bonds_are_forfeited() {
        let a = Address::new_from_array;
        let (completed, broken, open, abandoned) = (bond(1, 1), bond(2, 1), bond(3, 1), bond(4, 1));
        let live = Rig { shift_id: 1, shift_start_round: 50, shift_start_ts: 1_000, shift_open: true, ..Rig::default() };
        let bonds = vec![
            (a([11; 32]), completed, Some(log(&completed, 0)), Some(live.clone())),
            (a([12; 32]), broken, Some(log(&broken, hd::reason::UNLOCKED)), Some(live.clone())),
            (a([13; 32]), open, None, Some(live.clone())),
            (a([14; 32]), abandoned, None, None),
        ];
        let plan = plan_forfeits(&bonds, true);
        assert_eq!(
            plan,
            vec![
                CleanupAction::Forfeit { address: a([12; 32]), bond: broken, reason: 8 },
                CleanupAction::Forfeit { address: a([14; 32]), bond: abandoned, reason: 255 },
            ],
            "a completed bond is the owner's to release; an open shift is not resolvable yet"
        );
        assert_eq!(plan_forfeits(&bonds, false).len(), 1, "abandoned bonds are opt-out");
        assert_eq!(plan[0].address(), a([12; 32]));
    }

    #[test]
    fn gifts_are_refunded_from_expiry_plus_grace_oldest_first() {
        let a = Address::new_from_array;
        let gift = |i: u8, expiry: i64| GiftEscrow {
            bump: 255,
            sender: a([i; 32]),
            recipient: a([0x50 + i; 32]),
            nonce: u64::from(i),
            lamports: 500,
            created_ts: expiry - 30 * 86_400,
            expiry_ts: expiry,
            recipient_kind: 0,
        };
        let gifts = vec![(a([21; 32]), gift(1, 2_000)), (a([22; 32]), gift(2, 1_000)), (a([23; 32]), gift(3, 3_000))];
        // At 2,060 with 60 s of grace: the gifts that expired at or before 2,000.
        let plan = plan_refunds(&gifts, 2_060, 60);
        assert_eq!(plan.iter().map(CleanupAction::address).collect::<Vec<_>>(), vec![a([22; 32]), a([21; 32])]);
        assert!(plan_refunds(&gifts, 2_059, 60).len() == 1, "one second short of the grace");
        assert!(plan_refunds(&gifts, 999, 0).is_empty(), "nothing before expiry_ts");
        assert_eq!(plan_refunds(&gifts, 1_000, 0).len(), 1, "from expiry_ts, inclusive, as the program");
        assert_eq!(plan_refunds(&gifts, i64::MIN, 60).len(), 0, "no overflow");
        // The refund names the stored sender, never the crank.
        let CleanupAction::Refund { gift: g, .. } = &plan[0] else { panic!() };
        assert_eq!(g.sender, a([2; 32]));
    }
}
