/**
 * Heartbeat leases and dark rounds, reproduced exactly from the program
 * (programs/heads-down/program/src/logic.rs `grant_lease`, `settle_shift`; INTERFACE.md §6.3).
 *
 * A verified heartbeat for round `h` with `lease_rounds` L grants [h, h + min(L, plan_lease) − 1].
 * Leases only move forward; the rounds a grant newly covers, counted from the shift's first round,
 * are dark. At `end_shift`, rounds past `end_round` are dropped. Replaying every applied heartbeat
 * of a shift (ordered by its P-256 counter, which strictly increases per rig) therefore gives
 * the exact set of dark rounds, whose size must equal ShiftEnded.dark_rounds.
 */

export interface LeaseGrant {
  from: bigint;
  to: bigint;
  darkAdded: bigint;
  gapAdded: bigint;
}

export class LeaseError extends Error {}

const max = (a: bigint, b: bigint) => (a > b ? a : b);
const satSub = (a: bigint, b: bigint) => (a > b ? a - b : 0n);
const U64_MAX = (1n << 64n) - 1n;

/** `logic::grant_lease`. Throws on a zero lease (InvalidHeartbeat) or u64 overflow (MathOverflow). */
export function grantLease(curFrom: bigint, curTo: bigint, shiftStart: bigint, hbRound: bigint, lease: number): LeaseGrant {
  if (!Number.isInteger(lease) || lease < 1) throw new LeaseError("InvalidHeartbeat: zero lease");
  const newTo = hbRound + BigInt(lease - 1);
  if (newTo > U64_MAX) throw new LeaseError("MathOverflow");
  if (curTo !== 0n && newTo <= curTo) return { from: curFrom, to: curTo, darkAdded: 0n, gapAdded: 0n };
  const coveredEnd = curTo !== 0n && curTo >= shiftStart ? curTo : satSub(shiftStart, 1n);
  const start = max(hbRound, shiftStart);
  const firstNew = max(start, coveredEnd + 1n);
  const darkAdded = newTo >= firstNew ? newTo - firstNew + 1n : 0n;
  const gapAdded = satSub(start, coveredEnd + 1n);
  return { from: hbRound, to: newTo, darkAdded, gapAdded };
}

/** `logic::settle_shift`: returns [dark, gaps] at end_round (inclusive). */
export function settleShift(dark: bigint, gaps: bigint, leaseTo: bigint, shiftStart: bigint, endRound: bigint): [bigint, bigint] {
  const d = satSub(dark, satSub(leaseTo, endRound));
  const coveredEnd = leaseTo !== 0n && leaseTo >= shiftStart ? leaseTo : satSub(shiftStart, 1n);
  return [d, gaps + satSub(endRound, coveredEnd)];
}

export interface AppliedHeartbeat {
  counter: bigint;
  hbRound: bigint;
  leaseRounds: number;
}

export interface DarkReconstruction {
  /** Dark rounds, ascending, all within [shiftStart, endRound]. */
  rounds: bigint[];
  /** What the program's own counters give for the same heartbeats (must equal rounds.length). */
  darkRounds: bigint;
  gaps: bigint;
}

export function reconstructDarkRounds(opts: {
  shiftStart: bigint;
  endRound: bigint;
  planLease: number;
  heartbeats: readonly AppliedHeartbeat[];
}): DarkReconstruction {
  const hbs = [...opts.heartbeats].sort((a, b) => (a.counter < b.counter ? -1 : a.counter > b.counter ? 1 : 0));
  let from = 0n;
  let to = 0n;
  let dark = 0n;
  let gaps = 0n;
  const rounds: bigint[] = [];
  for (const h of hbs) {
    const lease = Math.min(h.leaseRounds, opts.planLease);
    // The program refuses a zero lease, so such an entry cannot have been applied: skip it rather than
    // throw from a read path (a wrong `applied` flag then shows up as a dark-round mismatch).
    if (!Number.isInteger(lease) || lease < 1 || h.hbRound + BigInt(lease - 1) > U64_MAX) continue;
    const g = grantLease(from, to, opts.shiftStart, h.hbRound, lease);
    for (let r = g.to - g.darkAdded + 1n; r <= g.to && g.darkAdded > 0n; r++) rounds.push(r);
    from = g.from;
    to = g.to;
    dark += g.darkAdded;
    gaps += g.gapAdded;
  }
  const [settledDark, settledGaps] = settleShift(dark, gaps, to, opts.shiftStart, opts.endRound);
  return { rounds: rounds.filter((r) => r <= opts.endRound), darkRounds: settledDark, gaps: settledGaps };
}
