//! v1.2 SKR parameters and pure, allocation-free arithmetic (`INTERFACE.md`
//! §11): bond caps, the Stack finish rule and payout split, and the no-oracle
//! Bury auction price. Everything here is integer-only, checked, and
//! unit-tested on the host, including the conservation property
//! `sum(payouts) + bury == sum(bonds)`.
//!
//! SKR is collateral, bond and gift currency here. Nothing in this module
//! mints, emits or pays anything that did not come from another bond at the
//! same table.

use crate::{error::HdError, token::ONE_SKR};

// ---- Stack --------------------------------------------------------------------

/// Bond cap at an in-person table (SGT-verified seats may bond up to this).
pub const STACK_BOND_CAP: u64 = 2_000 * ONE_SKR;
/// Bond cap at a remote "honor-plus" table (lower: spoofable at a distance).
pub const REMOTE_BOND_CAP: u64 = 1_000 * ONE_SKR;
/// Guests (no SGT verification) may join only tables whose bond is at most this.
pub const GUEST_BOND_CAP: u64 = 500 * ONE_SKR;
/// Fewest seats a table may be opened for.
pub const MIN_SEATS: u8 = 2;
/// Most seats per table (settle takes every seat in one instruction).
pub const MAX_SEATS: u8 = 8;
/// Longest window, in ORE rounds (about 24 to 31 hours).
pub const MAX_STACK_ROUNDS: u64 = 1_440;
/// How far ahead of the live round a window may start (about 7 to 9 days).
pub const MAX_STACK_LEAD_ROUNDS: u64 = 10_080;
/// Pessimistic ORE round length used only to date the refund timeout.
pub const MAX_ROUND_SECS: i64 = 120;
/// Extra time after the pessimistic window end before an unsettled table
/// refunds every bond.
pub const REFUND_GRACE_SECS: i64 = 3 * 86_400;
/// Finishers' share of the forfeits, in bps (the rest, plus rounding dust,
/// goes to the Bury lot).
pub const FINISHER_BPS: u16 = 8_000;
/// Basis-point denominator.
pub const BPS_DENOMINATOR: u16 = 10_000;

/// Rounds in `[start, end]`.
pub fn window_len(start: u64, end: u64) -> u64 {
    end.saturating_sub(start).saturating_add(1)
}

/// A seat finishes iff it never saw a BREAK / FREEZE in its bound shift up to
/// its last check-in, it checked in during `end_round` itself (a heartbeat
/// for `end_round` landed in `end_round`), and it missed at most `grace`
/// window rounds.
pub fn seat_finishes(
    broken: bool,
    last_round: u64,
    checked_rounds: u64,
    start: u64,
    end: u64,
    grace: u32,
) -> bool {
    let gaps = window_len(start, end).saturating_sub(checked_rounds);
    !broken && last_round == end && gaps <= u64::from(grace)
}

/// The outcome of settling a table.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Settlement {
    /// `B`: every seat's bond.
    pub total_bonds: u64,
    /// `W`: the finishers' bonds.
    pub finisher_bonds: u64,
    /// Seats that finished.
    pub finishers: u8,
    /// `sum(payouts)`.
    pub payouts_total: u64,
    /// `B - sum(payouts)`: the Bury lot.
    pub bury: u64,
}

/// Split a table (at most [`MAX_SEATS`] seats). With `F = B - W` forfeited:
///
/// * `W == 0`: every payout is 0 and Bury gets `B`;
/// * otherwise finisher `i` gets `bond_i + floor(F · bps · bond_i / (10⁴ · W))`
///   (u128), every other seat 0, and Bury gets the rest: `bps`'s complement
///   of `F` plus the rounding dust.
///
/// Writes each payout into `out` (same order as `bonds`) and returns the
/// totals. `sum(out) + bury == B` always holds.
pub fn stack_payouts(
    bonds: &[u64],
    finished: &[bool],
    finisher_bps: u16,
    out: &mut [u64],
) -> Result<Settlement, HdError> {
    if bonds.len() != finished.len()
        || bonds.len() != out.len()
        || bonds.len() > usize::from(MAX_SEATS)
        || finisher_bps > BPS_DENOMINATOR
    {
        return Err(HdError::InvalidInstruction);
    }
    let mut total: u64 = 0;
    let mut winners: u64 = 0;
    let mut k: u8 = 0;
    for (b, f) in bonds.iter().zip(finished) {
        total = total.checked_add(*b).ok_or(HdError::MathOverflow)?;
        if *f {
            winners = winners.checked_add(*b).ok_or(HdError::MathOverflow)?;
            k = k.checked_add(1).ok_or(HdError::MathOverflow)?;
        }
    }
    let forfeits = total.checked_sub(winners).ok_or(HdError::MathOverflow)?;
    let mut paid: u64 = 0;
    for ((b, f), o) in bonds.iter().zip(finished).zip(out.iter_mut()) {
        *o = if *f && winners > 0 {
            let num = u128::from(forfeits)
                .checked_mul(u128::from(finisher_bps))
                .and_then(|x| x.checked_mul(u128::from(*b)))
                .ok_or(HdError::MathOverflow)?;
            let den = u128::from(BPS_DENOMINATOR)
                .checked_mul(u128::from(winners))
                .ok_or(HdError::MathOverflow)?;
            let share = u64::try_from(num.checked_div(den).ok_or(HdError::MathOverflow)?)
                .map_err(|_| HdError::MathOverflow)?;
            b.checked_add(share).ok_or(HdError::MathOverflow)?
        } else {
            0
        };
        paid = paid.checked_add(*o).ok_or(HdError::MathOverflow)?;
    }
    let bury = total.checked_sub(paid).ok_or(HdError::MathOverflow)?;
    Ok(Settlement {
        total_bonds: total,
        finisher_bonds: winners,
        finishers: k,
        payouts_total: paid,
        bury,
    })
}

/// Unix time after which a never-settled table refunds every bond:
/// `now + (end_round - board_round + 1) · MAX_ROUND_SECS + REFUND_GRACE_SECS`.
pub fn refund_after(now: i64, board_round: u64, end_round: u64) -> Result<i64, HdError> {
    let rounds = i64::try_from(window_len(board_round, end_round))
        .map_err(|_| HdError::MathOverflow)?;
    rounds
        .checked_mul(MAX_ROUND_SECS)
        .and_then(|s| s.checked_add(REFUND_GRACE_SECS))
        .and_then(|s| s.checked_add(now))
        .ok_or(HdError::MathOverflow)
}

// ---- Focus Bond and gifts ---------------------------------------------------------

/// Largest Focus Bond.
pub const FOCUS_BOND_CAP: u64 = 5_000 * ONE_SKR;
/// Largest gift escrow (10 SOL).
pub const MAX_GIFT_LAMPORTS: u64 = 10_000_000_000;
/// Gift claim window; refunds from `created_ts + GIFT_EXPIRY_SECS`.
pub const GIFT_EXPIRY_SECS: i64 = 30 * 86_400;

// ---- Bury auction ---------------------------------------------------------------------

/// Start price before any sale has cleared, in ORE atoms (1e-11 ORE) per
/// whole SKR: 0.001 ORE per SKR, several times the 2026-09 market.
pub const INITIAL_START_PRICE: u64 = 100_000_000;
/// A new lot restarts at this multiple of the last clearing price.
pub const START_MULTIPLIER: u64 = 4;
/// Highest start price (0.1 ORE per SKR).
pub const MAX_START_PRICE: u64 = 10_000_000_000;
/// Floor price (1e-7 ORE per SKR): lots always sell eventually.
pub const FLOOR_PRICE: u64 = 10_000;
/// Slots from the start price to the floor (about 24 h at 400 ms slots).
pub const WINDOW_SLOTS: u64 = 216_000;

/// Start price for a new lot: `INITIAL_START_PRICE` before the first sale,
/// then `START_MULTIPLIER x last_clear`, clamped to
/// `[FLOOR_PRICE, MAX_START_PRICE]`.
pub fn restart_price(last_clear: u64) -> u64 {
    if last_clear == 0 {
        return INITIAL_START_PRICE;
    }
    last_clear
        .saturating_mul(START_MULTIPLIER)
        .clamp(FLOOR_PRICE, MAX_START_PRICE)
}

/// Linear decay from `start` (at `start_slot`) to `floor` (at `start_slot +
/// window` and after), computed in u128. A start below the floor, or a zero
/// window, is the floor.
pub fn auction_price(start: u64, floor: u64, start_slot: u64, window: u64, now_slot: u64) -> u64 {
    if start <= floor || window == 0 {
        return floor;
    }
    let elapsed = now_slot.saturating_sub(start_slot);
    if elapsed >= window {
        return floor;
    }
    let span = u128::from(start.saturating_sub(floor));
    let drop = span
        .saturating_mul(u128::from(elapsed))
        .checked_div(u128::from(window))
        .unwrap_or(span);
    // drop <= span < 2^64, so this cannot fail.
    start.saturating_sub(u64::try_from(drop).unwrap_or(u64::MAX))
}

/// ORE atoms owed for `skr_amount` base units at `price` ORE atoms per whole
/// SKR, rounded **up** (in the lot's favour): `ceil(skr_amount · price / 10⁶)`.
pub fn purchase_cost(skr_amount: u64, price: u64) -> Result<u64, HdError> {
    let num = u128::from(skr_amount)
        .checked_mul(u128::from(price))
        .ok_or(HdError::MathOverflow)?;
    let one = u128::from(ONE_SKR);
    let q = num.checked_div(one).ok_or(HdError::MathOverflow)?;
    let r = num.checked_rem(one).ok_or(HdError::MathOverflow)?;
    let cost = if r == 0 {
        q
    } else {
        q.checked_add(1).ok_or(HdError::MathOverflow)?
    };
    u64::try_from(cost).map_err(|_| HdError::MathOverflow)
}

/// ORE `bury`'s split of `ore` (`bury.rs:45`, `66`): `(burned, shared)` =
/// `(ore - ore/10, ore/10)`.
pub fn bury_split(ore: u64) -> (u64, u64) {
    let shared = ore.checked_div(10).unwrap_or(0);
    (ore.saturating_sub(shared), shared)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn settle(bonds: &[u64], finished: &[bool], bps: u16) -> (Vec<u64>, Settlement) {
        let mut out = vec![0u64; bonds.len()];
        let s = stack_payouts(bonds, finished, bps, &mut out).unwrap();
        (out, s)
    }

    #[test]
    fn everyone_finishing_gets_exactly_their_bond_back() {
        let (out, s) = settle(&[100, 100, 100], &[true, true, true], FINISHER_BPS);
        assert_eq!(out, vec![100, 100, 100]);
        assert_eq!((s.bury, s.finishers, s.payouts_total), (0, 3, 300));
    }

    #[test]
    fn nobody_finishing_sends_everything_to_bury() {
        let (out, s) = settle(&[100, 100], &[false, false], FINISHER_BPS);
        assert_eq!(out, vec![0, 0]);
        assert_eq!((s.bury, s.finishers, s.finisher_bonds), (200, 0, 0));
    }

    #[test]
    fn forfeits_split_80_20_with_dust_to_bury() {
        // 4 x 100, 3 finish: F = 100, 80 to finishers = 26 each (floor), bury
        // gets 20 + 2 dust.
        let (out, s) = settle(&[100; 4], &[true, true, false, true], FINISHER_BPS);
        assert_eq!(out, vec![126, 126, 0, 126]);
        assert_eq!(s.bury, 22);
        assert_eq!(s.payouts_total + s.bury, 400);
        // Real SKR amounts: 4 x 200 SKR, 1 finisher takes 200 + 480.
        let bond = 200 * ONE_SKR;
        let (out, s) = settle(&[bond; 4], &[false, true, false, false], FINISHER_BPS);
        assert_eq!(out[1], bond + 480 * ONE_SKR);
        assert_eq!(s.bury, 120 * ONE_SKR);
    }

    #[test]
    fn bury_only_tables_return_bonds_and_bury_every_forfeit() {
        let (out, s) = settle(&[50, 50, 50], &[true, false, true], 0);
        assert_eq!(out, vec![50, 0, 50]);
        assert_eq!(s.bury, 50);
    }

    #[test]
    fn conservation_holds_for_every_small_table() {
        // Every finisher subset of 1..=8 seats, with uneven bonds.
        let mut seed = 0x1234_5678_9abc_def0u64;
        let mut next = || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            seed
        };
        for n in 1..=8usize {
            for mask in 0..(1u32 << n) {
                for bps in [0u16, 8_000, 10_000, 1] {
                    let bonds: Vec<u64> = (0..n).map(|_| 1 + next() % 5_000_000_000).collect();
                    let finished: Vec<bool> = (0..n).map(|i| mask & (1 << i) != 0).collect();
                    let (out, s) = settle(&bonds, &finished, bps);
                    let total: u64 = bonds.iter().sum();
                    assert_eq!(out.iter().sum::<u64>() + s.bury, total);
                    let forfeits = total - s.finisher_bonds;
                    let pool = (u128::from(forfeits) * u128::from(bps) / 10_000) as u64;
                    let k = finished.iter().filter(|f| **f).count() as u64;
                    if k == 0 {
                        assert_eq!(s.bury, total);
                    } else {
                        // Bury gets the complement plus dust below one unit
                        // per finisher.
                        assert!(s.bury >= forfeits - pool);
                        assert!(s.bury <= forfeits - pool + k);
                    }
                    for i in 0..n {
                        if finished[i] {
                            assert!(out[i] >= bonds[i] && out[i] <= bonds[i] + forfeits);
                        } else {
                            assert_eq!(out[i], 0);
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn payout_math_rejects_bad_shapes_and_overflow() {
        let mut out = [0u64; 2];
        assert_eq!(
            stack_payouts(&[1, 2], &[true], FINISHER_BPS, &mut out),
            Err(HdError::InvalidInstruction)
        );
        assert_eq!(
            stack_payouts(&[1, 2], &[true, true], 10_001, &mut out),
            Err(HdError::InvalidInstruction)
        );
        assert_eq!(
            stack_payouts(&[u64::MAX, 1], &[true, false], FINISHER_BPS, &mut out),
            Err(HdError::MathOverflow)
        );
        let mut nine = [0u64; 9];
        assert_eq!(
            stack_payouts(&[1; 9], &[true; 9], FINISHER_BPS, &mut nine),
            Err(HdError::InvalidInstruction)
        );
        // Bonds near u64::MAX overflow the u128 product: a clean error, never
        // a panic (bonds are capped at STACK_BOND_CAP long before that).
        let half = u64::MAX / 2;
        assert_eq!(
            stack_payouts(&[half, half], &[true, false], FINISHER_BPS, &mut out),
            Err(HdError::MathOverflow)
        );
        // The largest real table: 8 seats at the cap, one finisher.
        let mut eight = [0u64; 8];
        let mut finished = [false; 8];
        finished[3] = true;
        let s = stack_payouts(&[STACK_BOND_CAP; 8], &finished, FINISHER_BPS, &mut eight).unwrap();
        assert_eq!(eight[3], STACK_BOND_CAP + 7 * STACK_BOND_CAP * 8 / 10);
        assert_eq!(s.payouts_total + s.bury, 8 * STACK_BOND_CAP);
    }

    #[test]
    fn the_finish_rule() {
        // Window 100..=109 (10 rounds), grace 2.
        assert!(seat_finishes(false, 109, 10, 100, 109, 2));
        assert!(seat_finishes(false, 109, 8, 100, 109, 2));
        assert!(!seat_finishes(false, 109, 7, 100, 109, 2)); // 3 gaps
        assert!(!seat_finishes(false, 108, 10, 100, 109, 2)); // missed the end round
        assert!(!seat_finishes(true, 109, 10, 100, 109, 2)); // broke
        assert!(!seat_finishes(false, 0, 0, 100, 109, 9)); // never checked in
        assert_eq!(window_len(100, 109), 10);
        assert_eq!(window_len(5, 5), 1);
    }

    #[test]
    fn refund_timeout_is_pessimistic_and_checked() {
        assert_eq!(
            refund_after(1_000, 10, 19),
            Ok(1_000 + 10 * MAX_ROUND_SECS + REFUND_GRACE_SECS)
        );
        assert_eq!(refund_after(i64::MAX, 0, 0), Err(HdError::MathOverflow));
    }

    #[test]
    fn the_dutch_price_decays_linearly_to_the_floor() {
        let (s, f, w) = (1_000_000u64, 10_000u64, 100u64);
        assert_eq!(auction_price(s, f, 50, w, 50), s);
        assert_eq!(auction_price(s, f, 50, w, 40), s); // clock before start
        assert_eq!(auction_price(s, f, 50, w, 100), s - (s - f) / 2);
        assert_eq!(auction_price(s, f, 50, w, 149), s - (s - f) * 99 / 100);
        assert_eq!(auction_price(s, f, 50, w, 150), f);
        assert_eq!(auction_price(s, f, 50, w, u64::MAX), f);
        assert_eq!(auction_price(5, f, 0, w, 0), f); // start below floor
        assert_eq!(auction_price(s, f, 0, 0, 0), f); // zero window
        // Monotone non-increasing.
        let mut prev = u64::MAX;
        for t in 0..=w + 5 {
            let p = auction_price(s, f, 0, w, t);
            assert!(p <= prev && p >= f);
            prev = p;
        }
        assert_eq!(
            auction_price(MAX_START_PRICE, FLOOR_PRICE, 0, WINDOW_SLOTS, WINDOW_SLOTS / 2),
            MAX_START_PRICE - (MAX_START_PRICE - FLOOR_PRICE) / 2
        );
    }

    #[test]
    fn restarts_anchor_to_the_last_clearing_price() {
        assert_eq!(restart_price(0), INITIAL_START_PRICE);
        assert_eq!(restart_price(21_000_000), 84_000_000);
        assert_eq!(restart_price(1), FLOOR_PRICE);
        assert_eq!(restart_price(u64::MAX), MAX_START_PRICE);
    }

    #[test]
    fn purchases_round_up_in_the_lots_favour() {
        // 1 SKR at 21,000,000 atoms per SKR.
        assert_eq!(purchase_cost(ONE_SKR, 21_000_000), Ok(21_000_000));
        // 1 base unit costs at least one atom.
        assert_eq!(purchase_cost(1, 10_000), Ok(1));
        assert_eq!(purchase_cost(1, 1_000_001), Ok(2));
        assert_eq!(purchase_cost(3, 333_334), Ok(2)); // 1.000002 -> 2
        assert_eq!(purchase_cost(0, 5), Ok(0));
        // 5,000 SKR at the max start price: 5e9 * 1e10 / 1e6 = 5e13 atoms.
        assert_eq!(
            purchase_cost(5_000 * ONE_SKR, MAX_START_PRICE),
            Ok(500 * 100_000_000_000)
        );
        assert_eq!(
            purchase_cost(u64::MAX, u64::MAX),
            Err(HdError::MathOverflow)
        );
    }

    #[test]
    fn bury_splits_90_10_like_ore() {
        assert_eq!(bury_split(100), (90, 10));
        assert_eq!(bury_split(9), (9, 0));
        assert_eq!(bury_split(21_000_000), (18_900_000, 2_100_000));
    }
}
