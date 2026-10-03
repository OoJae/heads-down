/**
 * Streaks, reproduced exactly from the program (logic.rs `update_streak`, end_shift.rs;
 * INTERFACE.md §6.8), so the haul can show the streak before and after any shift.
 *
 * A shift qualifies iff its reason is `completed` (0) and it had at least one dark round. Days are
 * UTC unix days of the shift's end time. Freezes refill to 2 whenever the 30-day period changes.
 * Next day: +1. `m` missed days with m <= freezes: +1 and freezes -= m. Same day: unchanged.
 * Otherwise the streak restarts at 1. A non-qualifying shift neither extends nor breaks it.
 * `register_rig` starts every rig at streak 0, 2 freezes, last day 0.
 */

export const FREEZE_PERIOD_DAYS = 30n;
export const FREEZES_PER_PERIOD = 2;
const DAY = 86_400n;

export interface StreakState {
  streak: number;
  freezesLeft: number;
  lastDay: bigint;
}

export const INITIAL_STREAK: StreakState = { streak: 0, freezesLeft: FREEZES_PER_PERIOD, lastDay: 0n };

/** Floor division that matches Rust's `div_euclid` for a positive divisor. */
function divEuclid(a: bigint, b: bigint): bigint {
  const q = a / b;
  return a % b < 0n ? q - 1n : q;
}

/** `logic::update_streak(streak, freezes_left, last_day, today, qualifies)`. */
export function updateStreak(s: StreakState, today: bigint, qualifies: boolean): StreakState {
  let freezes = s.freezesLeft;
  if (s.lastDay === 0n || divEuclid(today, FREEZE_PERIOD_DAYS) !== divEuclid(s.lastDay, FREEZE_PERIOD_DAYS)) freezes = FREEZES_PER_PERIOD;
  if (!qualifies || today < s.lastDay) return { streak: s.streak, freezesLeft: freezes, lastDay: s.lastDay };
  if (s.streak === 0 || s.lastDay === 0n) return { streak: 1, freezesLeft: freezes, lastDay: today };
  if (today === s.lastDay) return { streak: s.streak, freezesLeft: freezes, lastDay: today };
  const missed = today - s.lastDay - 1n;
  const inc = Math.min(s.streak + 1, 0xffff_ffff);
  if (missed === 0n) return { streak: inc, freezesLeft: freezes, lastDay: today };
  if (missed <= BigInt(freezes)) return { streak: inc, freezesLeft: freezes - Number(missed), lastDay: today };
  return { streak: 1, freezesLeft: freezes, lastDay: today };
}

export const unixDay = (ts: bigint) => divEuclid(ts, DAY);

export interface EndedShift {
  shiftId: bigint;
  endTs: bigint;
  reason: number;
  darkRounds: bigint;
  /** Order of events on chain (slot, then position); a RigRegistered in between resets the state. */
  order: bigint;
}

/** Streak before and after each ended shift, keyed by shift_id (per registration epoch). */
export function replayStreaks(shifts: readonly EndedShift[], registrations: readonly bigint[] = []): Map<bigint, { before: number; after: number }> {
  const events = [
    ...registrations.map((order) => ({ order, reg: true as const })),
    ...shifts.map((s) => ({ order: s.order, reg: false as const, s })),
  ].sort((a, b) => (a.order < b.order ? -1 : a.order > b.order ? 1 : a.reg === b.reg ? 0 : a.reg ? -1 : 1));
  const out = new Map<bigint, { before: number; after: number }>();
  let state = INITIAL_STREAK;
  for (const e of events) {
    if (e.reg) {
      state = INITIAL_STREAK;
      continue;
    }
    const before = state.streak;
    state = updateStreak(state, unixDay(e.s.endTs), e.s.reason === 0 && e.s.darkRounds > 0n);
    out.set(e.s.shiftId, { before, after: state.streak });
  }
  return out;
}
