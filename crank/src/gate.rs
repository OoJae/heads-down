//! The Motherlode-aware cost gate, integer for integer the same as `heads_down::dig` step 4
//! (`programs/heads-down/INTERFACE.md`):
//!
//! ```text
//! ema_ev = ema · 6 · 500 · 10^11 / (5 · (500 · 10^11 + pot))
//! dig iff ema_ev ≤ min(plan_max_ev_cost, cap_max_cost)
//! ```
//!
//! `ema = Board.production_cost_ema` (lamports per whole ORE) and `pot = Treasury.motherlode`
//! (ORE base units, 11 decimals). It is `ema × 1.2 / (1 + pot/500 ORE)`: the protocol EMA
//! assumes 1.2 ORE minted per round, while the expected reward is `1 + pot/500`
//! (`ml/forecaster/RESULTS.md` section 2). One `u128` multiplication and one floor division,
//! no intermediate rounding, exactly as `ml/forecaster/orelib.py::ema_ev_lamports`.

use crate::ore::ONE_ORE;

/// Motherlode odds (`state/round.rs:107-109`: `rng.reverse_bits() % 500 == 0`).
pub const MOTHERLODE_ODDS: u128 = 500;

/// `ema · 6 · 500 · 10^11 / (5 · (500 · 10^11 + pot))`, floor, in `u128` with checked
/// arithmetic.
///
/// Returns `None` when the quotient does not fit in a `u64`. That only happens for
/// `ema > u64::MAX / 1.2`-ish with a small pot; since every cap is a `u64`, such an
/// `ema_ev` exceeds any cap and the gate is closed either way. See `INTERFACE-NOTES.md`
/// (the contract does not say whether the program reports `MathOverflow` or `CostGate`;
/// both skip the rig).
pub fn ema_ev(ema: u64, pot: u64) -> Option<u64> {
    let one_ore = u128::from(ONE_ORE);
    // ema < 2^64 and 6 · 500 · 10^11 = 3·10^14 < 2^49, so the product is < 2^113: it
    // cannot overflow u128, but it is still checked so the code stays obviously safe.
    let num = u128::from(ema)
        .checked_mul(6)?
        .checked_mul(MOTHERLODE_ODDS)?
        .checked_mul(one_ore)?;
    // 500·10^11 + pot < 2^64 + 2^46; times 5 < 2^67. Never zero (the constant term is > 0).
    let den = MOTHERLODE_ODDS
        .checked_mul(one_ore)?
        .checked_add(u128::from(pot))?
        .checked_mul(5)?;
    let q = num.checked_div(den)?;
    u64::try_from(q).ok()
}

/// The ceiling a rig's gate compares against: `min(plan_max_ev_cost, cap_max_cost)`.
pub fn ceiling(plan_max_ev_cost: u64, cap_max_cost: u64) -> u64 {
    plan_max_ev_cost.min(cap_max_cost)
}

/// `ema_ev ≤ min(plan_max_ev_cost, cap_max_cost)`; closed on overflow.
pub fn gate_open(ema: u64, pot: u64, plan_max_ev_cost: u64, cap_max_cost: u64) -> bool {
    match ema_ev(ema, pot) {
        Some(v) => v <= ceiling(plan_max_ev_cost, cap_max_cost),
        None => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Exact rational reference: compare `ema_ev` against `num/den` without division.
    fn check_floor(ema: u64, pot: u64, got: u64) {
        let num = u128::from(ema) * 3 * 100_000_000_000_000u128;
        let den = 5 * (50_000_000_000_000u128 + u128::from(pot));
        let g = u128::from(got);
        assert!(g * den <= num, "not a lower bound: ema={ema} pot={pot}");
        assert!((g + 1) * den > num, "not the floor: ema={ema} pot={pot}");
    }

    #[test]
    fn pot_zero_is_ema_times_six_fifths() {
        for ema in [1u64, 4, 5, 6, 999, 918_782_720, 1_000_000_007] {
            let v = ema_ev(ema, 0).unwrap();
            assert_eq!(u128::from(v), u128::from(ema) * 6 / 5);
            check_floor(ema, 0, v);
        }
        assert_eq!(ema_ev(0, 0), Some(0));
    }

    #[test]
    fn overflow_closes_the_gate() {
        // ema · 1.2 > u64::MAX with no pot: does not fit.
        assert_eq!(ema_ev(u64::MAX, 0), None);
        assert!(!gate_open(u64::MAX, 0, u64::MAX, u64::MAX));
        // Largest ema that still fits with pot 0: floor(u64::MAX · 5 / 6).
        let max_ok = (u128::from(u64::MAX) * 5 / 6) as u64;
        assert!(ema_ev(max_ok, 0).is_some());
        assert_eq!(ema_ev(max_ok + 2, 0), None);
    }

    #[test]
    fn huge_pot_drives_ema_ev_down() {
        let v = ema_ev(u64::MAX, u64::MAX).unwrap();
        check_floor(u64::MAX, u64::MAX, v);
        assert_eq!(ema_ev(1, u64::MAX), Some(0));
        assert!(gate_open(1, u64::MAX, 0, 0), "ema_ev 0 <= 0");
        assert!(!gate_open(1_000_000_000, u64::MAX, 0, 0), "a billion-lamport ema is still > 0");
    }

    #[test]
    fn monotone_in_pot_and_ema() {
        let mut last = u64::MAX;
        for pot in (0..2_000u64).map(|k| k * 25 * ONE_ORE / 10) {
            let v = ema_ev(918_782_720, pot).unwrap();
            assert!(v <= last);
            last = v;
        }
        assert!(ema_ev(10, 5) <= ema_ev(11, 5));
    }

    #[test]
    fn gate_is_inclusive_and_uses_the_tighter_cap() {
        let (ema, pot) = (918_782_720, 344 * ONE_ORE);
        let v = ema_ev(ema, pot).unwrap();
        assert!(gate_open(ema, pot, v, v));
        assert!(!gate_open(ema, pot, v - 1, u64::MAX));
        assert!(!gate_open(ema, pot, u64::MAX, v - 1));
        assert!(gate_open(ema, pot, u64::MAX, v));
    }

    #[test]
    fn pseudo_random_inputs_are_exact_floors() {
        // xorshift; deterministic.
        let mut s = 0x9E37_79B9_7F4A_7C15u64;
        let mut next = || {
            s ^= s << 13;
            s ^= s >> 7;
            s ^= s << 17;
            s
        };
        for _ in 0..20_000 {
            let ema = next() >> (next() % 64);
            let pot = next() >> (next() % 64);
            if let Some(v) = ema_ev(ema, pot) {
                check_floor(ema, pot, v);
            } else {
                let num = u128::from(ema) * 300_000_000_000_000u128;
                let den = 5 * (50_000_000_000_000u128 + u128::from(pot));
                assert!(num / den > u128::from(u64::MAX));
            }
        }
    }
}
