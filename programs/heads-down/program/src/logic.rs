//! Pure, allocation-free decision logic shared by the instruction handlers.
//! Everything here is integer-only, checked, and unit-tested on the host.

use crate::{error::HdError, ore::SQUARES};

/// Motherlode odds denominator (`state/round.rs:107-109`: `% 500`).
pub const MOTHERLODE_ODDS: u128 = 500;

/// Motherlode-aware production cost, lamports per ORE (`ml/forecaster`
/// `orelib.ema_ev_lamports`):
///
/// `ema_ev = ema · 6 · 500 · 10^11 / (5 · (500 · 10^11 + pot))`
///
/// i.e. `ema · 1.2 / (1 + pot/500)`: the protocol EMA assumes 1.2 ORE minted
/// per round, while the expected payout is `1 + pot/500`. u128 throughout:
/// `ema < 2^64` and `3000 · 10^11 < 2^49`, so the numerator stays below
/// 2^113; the denominator is at least `2.5 · 10^14`.
pub fn ema_ev(ema: u64, pot: u64) -> u128 {
    let one_ore = crate::ore::ONE_ORE as u128;
    let num = (ema as u128)
        .saturating_mul(6)
        .saturating_mul(MOTHERLODE_ODDS)
        .saturating_mul(one_ore);
    let den = MOTHERLODE_ODDS
        .saturating_mul(one_ore)
        .saturating_add(pot as u128)
        .saturating_mul(5);
    // den >= 5 * 500 * 10^11 > 0.
    num.checked_div(den).unwrap_or(u128::MAX)
}

/// The gate opens iff `ema_ev <= min(plan_max_ev_cost, cap_max_cost)`.
pub fn gate_open(ema_ev: u128, plan_max_ev_cost: u64, cap_max_cost: u64) -> bool {
    ema_ev <= plan_max_ev_cost.min(cap_max_cost) as u128
}

/// Lamports this dig may spend on tiles:
/// `min(plan_dig, min(cap_round, cap_shift − spent_shift, cap_week − spent_week) − fee)`.
///
/// The Automation's fixed fee is debited on the round's first deploy on top
/// of the tile amount, and `spent_*` count the whole debit, so the fee is
/// reserved out of the remaining cap first (`INTERFACE-NOTES.md`): the total
/// debit can then never exceed a wallet-signed cap.
pub fn dig_budget(
    plan_dig: u64,
    cap_round: u64,
    cap_shift: u64,
    spent_shift: u64,
    cap_week: u64,
    spent_week: u64,
    fee: u64,
) -> u64 {
    let headroom = cap_round
        .min(cap_shift.saturating_sub(spent_shift))
        .min(cap_week.saturating_sub(spent_week));
    plan_dig.min(headroom.saturating_sub(fee))
}

/// Pick `split` least-crowded split tiles and `solo` least-crowded solo
/// tiles by `deployed` (ties → lowest index), never choosing a tile in
/// `exclude` (squares the Miner already holds this round; ORE would skip
/// them). `solo_mask` is ORE's `distribution_mask` (bit set = solo).
pub fn select_tiles(
    deployed: &[u64; SQUARES],
    solo_mask: u32,
    exclude: u32,
    split: u8,
    solo: u8,
) -> u32 {
    // Stable insertion sort of the 25 indices by (deployed, index): a strict
    // `>` never moves equal keys past each other, so ties keep index order.
    let mut order = crate::ore::SQUARE_INDICES;
    let key = |i: u8| deployed.get(usize::from(i)).copied().unwrap_or(u64::MAX);
    for i in 1..SQUARES {
        let mut j = i;
        while let Some(prev) = j.checked_sub(1) {
            let (Some(&a), Some(&b)) = (order.get(prev), order.get(j)) else {
                break;
            };
            if key(a) > key(b) {
                order.swap(prev, j);
                j = prev;
            } else {
                break;
            }
        }
    }
    let (mut want_split, mut want_solo) = (split, solo);
    let mut mask = 0u32;
    for &i in order.iter() {
        let bit = 1u32.wrapping_shl(u32::from(i));
        if exclude & bit != 0 {
            continue;
        }
        if solo_mask & bit != 0 {
            if want_solo > 0 {
                mask |= bit;
                want_solo = want_solo.saturating_sub(1);
            }
        } else if want_split > 0 {
            mask |= bit;
            want_split = want_split.saturating_sub(1);
        }
        if want_split == 0 && want_solo == 0 {
            break;
        }
    }
    mask
}

/// Result of granting a heartbeat lease.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct LeaseGrant {
    /// New `lease_from_round`.
    pub from: u64,
    /// New `lease_to_round`.
    pub to: u64,
    /// Rounds newly covered inside the shift (added to dark rounds).
    pub dark_added: u64,
    /// Uncovered rounds skipped over inside the shift (added to gaps).
    pub gap_added: u64,
}

/// Grant the lease `[hb_round, hb_round + lease − 1]` on top of the current
/// one. Leases only move forward: a heartbeat whose lease ends at or before
/// the current `lease_to` consumes its counter but changes nothing.
/// Coverage is counted from `shift_start` (rounds before arming never count).
/// `cur_to == 0` means no lease yet in this shift.
pub fn grant_lease(
    cur_from: u64,
    cur_to: u64,
    shift_start: u64,
    hb_round: u64,
    lease: u8,
) -> Result<LeaseGrant, HdError> {
    let span = u64::from(lease)
        .checked_sub(1)
        .ok_or(HdError::InvalidHeartbeat)?;
    let new_to = hb_round.checked_add(span).ok_or(HdError::MathOverflow)?;
    if cur_to != 0 && new_to <= cur_to {
        return Ok(LeaseGrant {
            from: cur_from,
            to: cur_to,
            dark_added: 0,
            gap_added: 0,
        });
    }
    // Last round already accounted for (covered or counted as a gap).
    let covered_end = if cur_to != 0 && cur_to >= shift_start {
        cur_to
    } else {
        shift_start.saturating_sub(1)
    };
    let start = hb_round.max(shift_start);
    let first_new = start.max(covered_end.saturating_add(1));
    let dark_added = if new_to >= first_new {
        new_to.saturating_sub(first_new).saturating_add(1)
    } else {
        0
    };
    let gap_added = start.saturating_sub(covered_end.saturating_add(1));
    Ok(LeaseGrant {
        from: hb_round,
        to: new_to,
        dark_added,
        gap_added,
    })
}

/// Close out the dark-round and gap counters at `end_round` (inclusive):
/// lease rounds past the end were counted but never happened; rounds after
/// the last lease up to the end are gaps.
pub fn settle_shift(
    dark: u64,
    gaps: u64,
    lease_to: u64,
    shift_start: u64,
    end_round: u64,
) -> (u64, u64) {
    let dark = dark.saturating_sub(lease_to.saturating_sub(end_round));
    let covered_end = if lease_to != 0 && lease_to >= shift_start {
        lease_to
    } else {
        shift_start.saturating_sub(1)
    };
    let gaps = gaps.saturating_add(end_round.saturating_sub(covered_end));
    (dark, gaps)
}

/// Days in a streak-freeze refill period.
pub const FREEZE_PERIOD_DAYS: i64 = 30;
/// Freezes granted per period.
pub const FREEZES_PER_PERIOD: u8 = 2;

/// Streak after a shift that ended on unix day `today`.
///
/// * Freezes refill to 2 whenever `today` is in a new 30-day period.
/// * A non-qualifying shift changes nothing else (it neither extends nor
///   breaks the streak; a missed day is judged at the next qualifying one).
/// * Same day: no change. Next day: +1. Missed `m` days: covered by `m`
///   freezes if available (+1), else the streak restarts at 1.
///
/// Returns `(streak, freezes_left, last_shift_day)`.
pub fn update_streak(
    streak: u32,
    freezes_left: u8,
    last_day: i64,
    today: i64,
    qualifies: bool,
) -> (u32, u8, i64) {
    let mut freezes = freezes_left;
    if last_day == 0
        || today.div_euclid(FREEZE_PERIOD_DAYS) != last_day.div_euclid(FREEZE_PERIOD_DAYS)
    {
        freezes = FREEZES_PER_PERIOD;
    }
    if !qualifies || today < last_day {
        return (streak, freezes, last_day);
    }
    if streak == 0 || last_day == 0 {
        return (1, freezes, today);
    }
    if today == last_day {
        return (streak, freezes, today);
    }
    let missed = today.saturating_sub(last_day).saturating_sub(1);
    if missed == 0 {
        return (streak.saturating_add(1), freezes, today);
    }
    if missed <= i64::from(freezes) {
        // missed <= freezes <= 255
        let used = u8::try_from(missed).unwrap_or(u8::MAX);
        return (
            streak.saturating_add(1),
            freezes.saturating_sub(used),
            today,
        );
    }
    (1, freezes, today)
}

/// Seconds in the spend week.
pub const WEEK_SECONDS: i64 = 7 * 24 * 60 * 60;

/// Roll the 7-day spend week: `(week_start_ts, spent_week)`.
pub fn roll_week(week_start: i64, spent_week: u64, now: i64) -> (i64, u64) {
    if week_start == 0 || now.saturating_sub(week_start) >= WEEK_SECONDS {
        (now, 0)
    } else {
        (week_start, spent_week)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Values from `ml/forecaster/orelib.py::ema_ev_lamports` (integer
    /// floor division), including the live fixture of 2026-09-29.
    #[test]
    fn ema_ev_matches_the_python_reference() {
        let one = crate::ore::ONE_ORE;
        let cases: [(u64, u64, u128); 5] = [
            // pot = 0: ema * 6/5
            (1_000_000_000, 0, 1_200_000_000),
            // pot = 500 ORE: ema * 1.2 / 2
            (1_000_000_000, 500 * one, 600_000_000),
            // live Board/Treasury fixture (round 422,685): ema 903,580,117, pot 360.8 ORE
            (903_580_117, 36_080_000_000_000, 629_818_854),
            (1, 0, 1),
            (0, 123 * one, 0),
        ];
        for (ema, pot, want) in cases {
            assert_eq!(ema_ev(ema, pot), want, "ema {ema} pot {pot}");
        }
        // Extreme inputs never overflow or panic.
        assert!(ema_ev(u64::MAX, 0) > u64::MAX as u128);
        assert_eq!(ema_ev(u64::MAX, u64::MAX), 59_999_837_370_114);
    }

    #[test]
    fn gate_uses_the_tighter_of_plan_and_cap() {
        assert!(gate_open(100, 100, 200));
        assert!(!gate_open(101, 100, 200));
        assert!(!gate_open(101, 200, 100));
        assert!(gate_open(0, 0, 0));
        assert!(!gate_open(u64::MAX as u128 + 1, u64::MAX, u64::MAX));
    }

    #[test]
    fn budget_reserves_the_fee_inside_every_cap() {
        // Plan-limited.
        assert_eq!(dig_budget(1_000, 10_000, 10_000, 0, 10_000, 0, 5), 1_000);
        // Round cap minus fee.
        assert_eq!(dig_budget(10_000, 1_000, 10_000, 0, 10_000, 0, 5), 995);
        // Shift headroom minus fee.
        assert_eq!(dig_budget(10_000, 10_000, 10_000, 9_000, 10_000, 0, 5), 995);
        // Week headroom minus fee.
        assert_eq!(dig_budget(10_000, 10_000, 10_000, 0, 10_000, 9_500, 5), 495);
        // Exhausted, or the fee alone exceeds the headroom.
        assert_eq!(dig_budget(10_000, 10_000, 10_000, 10_000, 10_000, 0, 5), 0);
        assert_eq!(dig_budget(10_000, 4, 10_000, 0, 10_000, 0, 5), 0);
        // Spent above cap (caps lowered mid-shift) saturates to 0.
        assert_eq!(dig_budget(10_000, 10_000, 1_000, 5_000, 10_000, 0, 5), 0);
        // Max values.
        assert_eq!(
            dig_budget(u64::MAX, u64::MAX, u64::MAX, 0, u64::MAX, 0, u64::MAX),
            0
        );
        assert_eq!(
            dig_budget(u64::MAX, u64::MAX, u64::MAX, 0, u64::MAX, 0, 0),
            u64::MAX
        );
    }

    #[test]
    fn tiles_are_least_crowded_split_then_solo_with_low_index_ties() {
        let mut deployed = [100u64; SQUARES];
        deployed[3] = 5;
        deployed[7] = 5;
        deployed[1] = 50;
        deployed[20] = 1;
        let solo_mask = (1 << 20) | (1 << 21) | (1 << 22);
        // 2 split tiles: 3 and 7 (tie at 5, lowest index first... both chosen).
        assert_eq!(
            select_tiles(&deployed, solo_mask, 0, 2, 0),
            (1 << 3) | (1 << 7)
        );
        // 3 split: then 1 (50).
        assert_eq!(
            select_tiles(&deployed, solo_mask, 0, 3, 0),
            (1 << 3) | (1 << 7) | (1 << 1)
        );
        // 1 solo: 20 (least crowded solo).
        assert_eq!(select_tiles(&deployed, solo_mask, 0, 0, 1), 1 << 20);
        // Ties among equal tiles resolve to the lowest index.
        assert_eq!(select_tiles(&[9; SQUARES], 0, 0, 2, 0), 0b11);
        // Excluded squares are skipped.
        assert_eq!(
            select_tiles(&deployed, solo_mask, 1 << 3, 2, 0),
            (1 << 7) | (1 << 1)
        );
        // Asking for more than exists returns what exists.
        assert_eq!(select_tiles(&deployed, solo_mask, 0, 0, 10).count_ones(), 3);
        assert_eq!(
            select_tiles(&deployed, solo_mask, 0, 255, 255).count_ones(),
            25
        );
        assert_eq!(select_tiles(&deployed, solo_mask, 0, 0, 0), 0);
    }

    #[test]
    fn leases_extend_forward_and_count_dark_rounds_and_gaps() {
        // First heartbeat at the shift's first round, lease 3: rounds 100..=102.
        let g = grant_lease(0, 0, 100, 100, 3).unwrap();
        assert_eq!(
            g,
            LeaseGrant {
                from: 100,
                to: 102,
                dark_added: 3,
                gap_added: 0
            }
        );
        // Overlapping renewal at 101 (lease 3 → 103): one new round.
        let g2 = grant_lease(g.from, g.to, 100, 101, 3).unwrap();
        assert_eq!(
            g2,
            LeaseGrant {
                from: 101,
                to: 103,
                dark_added: 1,
                gap_added: 0
            }
        );
        // A stale heartbeat that does not extend: nothing changes.
        let g3 = grant_lease(g2.from, g2.to, 100, 99, 3).unwrap();
        assert_eq!(
            g3,
            LeaseGrant {
                from: 101,
                to: 103,
                dark_added: 0,
                gap_added: 0
            }
        );
        // After a gap (104..=106 uncovered) a heartbeat at 107, lease 1.
        let g4 = grant_lease(g2.from, g2.to, 100, 107, 1).unwrap();
        assert_eq!(
            g4,
            LeaseGrant {
                from: 107,
                to: 107,
                dark_added: 1,
                gap_added: 3
            }
        );
        // First heartbeat after arming comes late: gap from shift start.
        let g5 = grant_lease(0, 0, 100, 104, 1).unwrap();
        assert_eq!(
            g5,
            LeaseGrant {
                from: 104,
                to: 104,
                dark_added: 1,
                gap_added: 4
            }
        );
        // A heartbeat signed before the shift started only counts from the start.
        let g6 = grant_lease(0, 0, 100, 98, 3).unwrap();
        assert_eq!(
            g6,
            LeaseGrant {
                from: 98,
                to: 100,
                dark_added: 1,
                gap_added: 0
            }
        );
        // Zero lease is invalid; overflow is an error, not a panic.
        assert_eq!(grant_lease(0, 0, 1, 5, 0), Err(HdError::InvalidHeartbeat));
        assert_eq!(
            grant_lease(0, 0, 1, u64::MAX, 3),
            Err(HdError::MathOverflow)
        );
    }

    #[test]
    fn settling_trims_future_lease_rounds_and_adds_trailing_gaps() {
        // Lease to 105 counted, shift ends at 103: 2 rounds never happened.
        assert_eq!(settle_shift(6, 0, 105, 100, 103), (4, 0));
        // Lease ended at 102, shift ends at 110: 8 trailing gap rounds.
        assert_eq!(settle_shift(3, 1, 102, 100, 110), (3, 9));
        // No lease ever: every round from start to end is a gap.
        assert_eq!(settle_shift(0, 0, 0, 100, 104), (0, 5));
    }

    #[test]
    fn streak_rules() {
        // First qualifying shift.
        assert_eq!(update_streak(0, 0, 0, 20_000, true), (1, 2, 20_000));
        // Next day.
        assert_eq!(update_streak(1, 2, 20_000, 20_001, true), (2, 2, 20_001));
        // Same day: unchanged.
        assert_eq!(update_streak(2, 2, 20_001, 20_001, true), (2, 2, 20_001));
        // One missed day, covered by a freeze.
        assert_eq!(update_streak(2, 2, 20_001, 20_003, true), (3, 1, 20_003));
        // Two missed days, both freezes.
        assert_eq!(update_streak(3, 2, 20_003, 20_006, true), (4, 0, 20_006));
        // Missed more days than freezes: restart.
        assert_eq!(update_streak(4, 0, 20_006, 20_008, true), (1, 0, 20_008));
        // Not qualifying: nothing but the refill check.
        assert_eq!(update_streak(4, 1, 20_006, 20_007, false), (4, 1, 20_006));
        // New 30-day period refills to 2 before judging.
        assert_eq!(update_streak(5, 0, 20_009, 20_011, true), (6, 1, 20_011));
        // Clock going backwards changes nothing.
        assert_eq!(update_streak(5, 1, 20_011, 20_010, true), (5, 1, 20_011));
    }

    #[test]
    fn week_rolls_after_seven_days() {
        assert_eq!(roll_week(0, 99, 1_000), (1_000, 0));
        assert_eq!(roll_week(1_000, 99, 1_000 + WEEK_SECONDS - 1), (1_000, 99));
        assert_eq!(
            roll_week(1_000, 99, 1_000 + WEEK_SECONDS),
            (1_000 + WEEK_SECONDS, 0)
        );
    }
}
