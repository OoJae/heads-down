/**
 * The morning haul (shared contract B): everything the phone's reveal shows about one finished
 * shift, computed from chain data only.
 *
 *  - Shift boundaries: ShiftEndedV2 (start_round, end_round, mode, reason, totals) and the
 *    ShiftLog account (start_ts, end_ts) when snapshotted, else the arm / end transaction times
 *    (a transaction's blockTime is the Clock the program read).
 *  - `rounds[].dark`: the program's lease arithmetic replayed over every heartbeat the rig was
 *    granted in the shift (metrics/lease.ts); `dug_mask` from RigDug.
 *  - ORE mined and SOL returned: ORE's checkpoint arithmetic (metrics/oremath.ts) on the rig's own
 *    DeployEvents (signer = Executor PDA) and each dug round's outcome (Round account, else
 *    ResetEvent).
 *  - fees = ShiftEnded.lamports (spent_shift: squares + Automation fees) − Σ RigDug.lamports.
 *  - effective price = (SOL placed − SOL returned + fees) × 10^11 / ORE mined atoms, rounded UP
 *    (a cost is never understated), lamports per whole ORE; null when nothing was mined.
 *
 * A haul is final once every round it needs has been reset: each dug round (it decides the
 * amounts) and, while the shift is fresh, its last round (end_shift runs inside a live round, which
 * resets a minute or two later). Until then {@link computeHaul} returns `{ final: false }` and the
 * API answers 404 with a retry hint rather than a partial haul that would change later.
 *
 * `rounds` is bounded ({@link HAUL_MAX_ROUNDS}): the program puts no limit on how long a shift may
 * stay open, so a shift nobody ended for weeks must not turn into a multi-megabyte response. Its
 * totals still cover every dig; only the list stops.
 */
import { SHIFT_MODE_NAMES } from "../codec/events.ts";
import { reconstructDarkRounds, type AppliedHeartbeat } from "./lease.ts";
import { oreMined, perSquare, solReturned, type RoundOutcome } from "./oremath.ts";

const TWO_53 = 9_007_199_254_740_992n;

/**
 * A haul lists at most this many rounds, counted from start_round: about 3.7 days of 78 s rounds,
 * well beyond any night window. Anyone may end a shift after its window (INTERFACE §11), possibly
 * much later; such a haul keeps exact totals and lists the first HAUL_MAX_ROUNDS rounds.
 */
export const HAUL_MAX_ROUNDS = 4096;

/** The last round a haul lists for a shift spanning [start, end]. */
export function lastListedRound(start: bigint, end: bigint): bigint {
  const cap = start + BigInt(HAUL_MAX_ROUNDS) - 1n;
  return end > cap ? cap : end;
}

/** JSON number when exact in a double, else a decimal string (contract B). */
export function jsonU64(v: bigint): number | string {
  return v >= 0n && v <= TWO_53 ? Number(v) : v.toString();
}

export interface HaulRound {
  round_id: number | string;
  dark: boolean;
  dug_mask: number;
  winning_square: number | null;
  motherlode: boolean;
  split: boolean;
}

/** Contract B, field for field. */
export interface HaulSummary {
  rig: string;
  shift_id: number | string;
  mode: "night" | "day" | "focus_only";
  start_ts: number;
  end_ts: number;
  start_round: number | string;
  end_round: number | string;
  rounds: HaulRound[];
  dark_rounds: number | string;
  rounds_dug: number | string;
  sol_placed_lamports: number | string;
  fees_lamports: number | string;
  ore_mined_atoms: string;
  effective_lamports_per_ore: string | null;
  market_lamports_per_ore: string | null;
  market_source: string | null;
  streak_before: number;
  streak_after: number;
  break_reason: number;
  first_pickup_ts: null;
  simulated: boolean;
  explorer: { shift_log: string | null; sample_digs: string[] };
}

export interface HaulDig {
  signature: string;
  roundId: bigint;
  lamports: bigint;
  mask: number;
}

export interface HaulInput {
  rig: string;
  authority: string | null;
  shiftId: bigint;
  startRound: bigint;
  endRound: bigint;
  mode: number;
  reason: number;
  darkRounds: bigint;
  roundsDug: bigint;
  /** spent_shift (squares + fees). */
  lamports: bigint;
  startTs: bigint;
  endTs: bigint;
  planLease: number | null;
  heartbeats: readonly AppliedHeartbeat[];
  digs: readonly HaulDig[];
  /** The rig authority's Heads Down DeployEvents in the shift's dig transactions. */
  deploys: readonly { signature: string; roundId: bigint; amount: bigint; mask: number; authority: string }[];
  outcomes: ReadonlyMap<bigint, RoundOutcome>;
  streak: { before: number; after: number };
  market: { lamportsPerOre: bigint; source: string } | null;
  simulated: boolean;
  explorer: { shiftLog: string | null; digUrl: (signature: string) => string | null };
  /** Rounds that must have an outcome besides the dug ones (the end round of a fresh shift). */
  alsoRequired?: readonly bigint[];
}

export interface HaulDiagnostics {
  /** Reconstructed dark rounds equal ShiftEnded.dark_rounds. */
  darkMatches: boolean;
  reconstructedDark: number;
  /** SOL returned used Round-account per-square totals for every dug round. */
  solReturnedExact: boolean;
  solReturnedLamports: bigint;
  oreMinedAtoms: bigint;
  /** Σ RigDug.lamports equals Σ DeployEvent amount × squares. */
  deploysMatchDigs: boolean;
  /** Entries in `rounds`; fewer than `roundsInShift` only for a shift longer than HAUL_MAX_ROUNDS. */
  roundsListed: number;
  /** end_round − start_round + 1. */
  roundsInShift: bigint;
}

export type HaulResult =
  | { final: true; haul: HaulSummary; diagnostics: HaulDiagnostics }
  | { final: false; pendingRounds: bigint[] };

/** ceil(a / b) for a >= 0, b > 0. */
const ceilDiv = (a: bigint, b: bigint) => (a + b - 1n) / b;

export function computeHaul(h: HaulInput): HaulResult {
  const digs = [...h.digs].sort((a, b) => (a.roundId < b.roundId ? -1 : a.roundId > b.roundId ? 1 : 0));
  const pending = [...new Set([...digs.map((d) => d.roundId), ...(h.alsoRequired ?? [])])].filter((r) => !h.outcomes.has(r)).sort((a, b) => (a < b ? -1 : 1));
  if (pending.length > 0) return { final: false, pendingRounds: pending };

  // ---- dark rounds
  const planLease = h.planLease ?? 3;
  const recon = reconstructDarkRounds({ shiftStart: h.startRound, endRound: h.endRound, planLease, heartbeats: h.heartbeats });
  const dark = new Set(recon.rounds);

  // ---- per-round digs, ORE and SOL
  const digByRound = new Map<bigint, HaulDig>();
  for (const d of digs) digByRound.set(d.roundId, d);
  let solPlaced = 0n;
  let solBack = 0n;
  let ore = 0n;
  let exact = true;
  let deployLamports = 0n;
  for (const d of digs) {
    solPlaced += d.lamports;
    const o = h.outcomes.get(d.roundId)!;
    let deploys = h.deploys.filter((e) => e.signature === d.signature && e.roundId === d.roundId);
    // RigDug.lamports = per_tile × popcount(mask) exactly, so it stands in for a missing DeployEvent.
    if (deploys.length === 0) {
      const k = BigInt(popcount(d.mask));
      deploys = k > 0n ? [{ signature: d.signature, roundId: d.roundId, amount: d.lamports / k, mask: d.mask, authority: h.authority ?? "" }] : [];
    }
    for (const e of deploys) deployLamports += e.amount * BigInt(popcount(e.mask));
    const squares = perSquare(deploys);
    // The solo +1 ORE goes to ResetEvent.top_miner: compare with the authority ORE itself recorded.
    const authority = deploys[0]?.authority || h.authority || "";
    ore += oreMined(authority, squares, o).total;
    const back = solReturned(squares, o);
    solBack += back.lamports;
    exact &&= back.exact;
  }
  const fees = h.lamports > solPlaced ? h.lamports - solPlaced : 0n;
  const cost = solPlaced - solBack + fees;
  const effective = ore > 0n ? ceilDiv(cost * 100_000_000_000n, ore) : null;

  // ---- rounds list: every round of the shift (the first HAUL_MAX_ROUNDS of an overlong one)
  const rounds: HaulRound[] = [];
  const listEnd = lastListedRound(h.startRound, h.endRound);
  for (let r = h.startRound; r <= listEnd; r++) {
    const o = h.outcomes.get(r);
    rounds.push({
      round_id: jsonU64(r),
      dark: dark.has(r),
      dug_mask: digByRound.get(r)?.mask ?? 0,
      winning_square: o ? o.winningSquare : null,
      motherlode: o ? o.motherlode > 0n : false,
      split: o ? o.split : false,
    });
  }

  const haul: HaulSummary = {
    rig: h.rig,
    shift_id: jsonU64(h.shiftId),
    mode: SHIFT_MODE_NAMES[h.mode] ?? "night",
    start_ts: Number(h.startTs),
    end_ts: Number(h.endTs),
    start_round: jsonU64(h.startRound),
    end_round: jsonU64(h.endRound),
    rounds,
    dark_rounds: jsonU64(h.darkRounds),
    rounds_dug: jsonU64(h.roundsDug),
    sol_placed_lamports: jsonU64(solPlaced),
    fees_lamports: jsonU64(fees),
    ore_mined_atoms: ore.toString(),
    effective_lamports_per_ore: effective === null ? null : effective.toString(),
    market_lamports_per_ore: h.market ? h.market.lamportsPerOre.toString() : null,
    market_source: h.market ? h.market.source : null,
    streak_before: h.streak.before,
    streak_after: h.streak.after,
    break_reason: h.reason,
    first_pickup_ts: null,
    simulated: h.simulated,
    explorer: {
      shift_log: h.explorer.shiftLog,
      sample_digs: digs
        .slice(-3)
        .reverse()
        .map((d) => h.explorer.digUrl(d.signature))
        .filter((u): u is string => u !== null),
    },
  };
  return {
    final: true,
    haul,
    diagnostics: {
      darkMatches: BigInt(recon.rounds.length) === h.darkRounds,
      reconstructedDark: recon.rounds.length,
      solReturnedExact: exact,
      solReturnedLamports: solBack,
      oreMinedAtoms: ore,
      deploysMatchDigs: deployLamports === solPlaced,
      roundsListed: rounds.length,
      roundsInShift: h.endRound >= h.startRound ? h.endRound - h.startRound + 1n : 0n,
    },
  };
}

function popcount(mask: number): number {
  let m = mask >>> 0;
  let n = 0;
  while (m) {
    m &= m - 1;
    n++;
  }
  return n;
}
