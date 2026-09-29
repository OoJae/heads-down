/**
 * Retention cohorts by first shift.
 *
 *  - A rig's cohort is the night of its FIRST `ShiftArmed` event.
 *  - A rig is "active" on a night if it armed a shift, dug, or ended a shift with at least
 *    one dark round during that night (all on-chain events).
 *  - Dk retention of a cohort = share of its rigs active on night cohort + k (exact-day).
 *  - A cell is "mature" only when night cohort + k is complete (strictly before the night that
 *    contains `asOf`); immature cells are null, never extrapolated.
 */
import type { MetricsInput } from "../model.ts";
import { dayLabel, nightIndex } from "./time.ts";

export const RETENTION_DAYS = [1, 7, 14] as const;

export interface CohortCell {
  day: number;
  retained: number | null;
  rate: number | null;
  mature: boolean;
}

export interface CohortRow {
  cohort: string;
  size: number;
  cells: CohortCell[];
}

export interface CohortReport {
  lastCompleteNight: string | null;
  cohorts: CohortRow[];
  /** Size-weighted average over mature cohorts, per day in RETENTION_DAYS. */
  average: { day: number; rate: number | null; cohorts: number; rigs: number }[];
}

/** rig -> set of night indices on which it was active. */
export function activityByRig(input: Pick<MetricsInput, "arms" | "digs" | "ends">, tz: number): Map<string, Set<number>> {
  const out = new Map<string, Set<number>>();
  const add = (rig: string, t: number | null) => {
    if (t === null) return;
    let s = out.get(rig);
    if (!s) out.set(rig, (s = new Set()));
    s.add(nightIndex(t, tz));
  };
  for (const a of input.arms) add(a.rig, a.blockTime);
  for (const d of input.digs) add(d.rig, d.blockTime);
  for (const e of input.ends) if (e.darkRounds > 0n) add(e.rig, e.blockTime);
  return out;
}

/** rig -> night index of its first ShiftArmed. */
export function firstShiftNight(arms: MetricsInput["arms"], tz: number): Map<string, number> {
  const out = new Map<string, number>();
  for (const a of arms) {
    if (a.blockTime === null) continue;
    const n = nightIndex(a.blockTime, tz);
    const prev = out.get(a.rig);
    if (prev === undefined || n < prev) out.set(a.rig, n);
  }
  return out;
}

export function computeCohorts(
  input: Pick<MetricsInput, "arms" | "digs" | "ends">,
  asOf: number,
  tz: number,
  days: readonly number[] = RETENTION_DAYS,
): CohortReport {
  const activity = activityByRig(input, tz);
  const first = firstShiftNight(input.arms, tz);
  const lastComplete = nightIndex(asOf, tz) - 1;
  const byCohort = new Map<number, string[]>();
  for (const [rig, n] of first) {
    if (n > lastComplete + 1) continue; // future-dated (clock skew): ignore
    const list = byCohort.get(n);
    if (list) list.push(rig);
    else byCohort.set(n, [rig]);
  }
  const cohorts: CohortRow[] = [...byCohort.keys()]
    .sort((a, b) => a - b)
    .map((n) => {
      const rigs = byCohort.get(n)!;
      return {
        cohort: dayLabel(n),
        size: rigs.length,
        cells: days.map((k) => {
          const mature = n + k <= lastComplete;
          if (!mature) return { day: k, retained: null, rate: null, mature };
          const retained = rigs.filter((r) => activity.get(r)?.has(n + k)).length;
          return { day: k, retained, rate: retained / rigs.length, mature };
        }),
      };
    });
  const average = days.map((k, i) => {
    let size = 0;
    let kept = 0;
    let count = 0;
    for (const c of cohorts) {
      const cell = c.cells[i]!;
      if (!cell.mature || cell.retained === null) continue;
      size += c.size;
      kept += cell.retained;
      count++;
    }
    return { day: k, rate: size > 0 ? kept / size : null, cohorts: count, rigs: size };
  });
  return { lastCompleteNight: lastComplete >= 0 ? dayLabel(lastComplete) : null, cohorts, average };
}
