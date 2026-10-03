/**
 * The morning-haul wording and arithmetic the dashboard shares with the phone's reveal
 * (android/feature/reveal: HaulMath.verdict, RevealCopyBuilder). Mining is one route to ORE,
 * never income: every line here says which route was cheaper, or that there is nothing to compare.
 */
import type { HaulSummary, U64 } from "./types";

const big = (v: U64 | string | null): bigint | null => (v === null ? null : BigInt(v));

export type RouteVerdict =
  | { kind: "mining_cheaper"; percent: number }
  | { kind: "buying_cheaper"; percent: number }
  | { kind: "about_market" }
  | { kind: "gate_closed"; market: boolean }
  | { kind: "nothing_mined" }
  | { kind: "focus_only" }
  | { kind: "no_market" };

/** Integer port of HaulMath.verdict (tenths of a percent, half-percent dead band). */
export function verdict(h: HaulSummary): RouteVerdict {
  if (h.mode === "focus_only") return { kind: "focus_only" };
  const digs = h.rounds.filter((r) => r.dug_mask !== 0).length;
  if (digs === 0 && big(h.sol_placed_lamports) === 0n) return { kind: "gate_closed", market: h.market_lamports_per_ore !== null };
  const effective = big(h.effective_lamports_per_ore);
  if (effective === null) return { kind: "nothing_mined" };
  const market = big(h.market_lamports_per_ore);
  if (market === null || market === 0n) return { kind: "no_market" };
  const tenths = ((effective - market) * 1000n) / market; // truncates toward zero, as Kotlin Long division
  const round = (t: bigint) => Number((t + 5n) / 10n);
  if (tenths <= -5n) return { kind: "mining_cheaper", percent: round(-tenths) };
  if (tenths >= 5n) return { kind: "buying_cheaper", percent: round(tenths) };
  return { kind: "about_market" };
}

export function verdictLine(v: RouteVerdict, when: string): string {
  switch (v.kind) {
    case "mining_cheaper":
      return `Mining was the cheaper route ${when}: ${v.percent}% below market.`;
    case "buying_cheaper":
      return `Buying was the cheaper route ${when}: mining cost ${v.percent}% more than market.`;
    case "about_market":
      return "Mining came out at about the market price.";
    case "gate_closed":
      return v.market
        ? "The price gate stayed closed, so nothing was placed. Buying was the cheaper route."
        : "The price gate stayed closed, so nothing was placed.";
    case "nothing_mined":
      return `None of the rig's tiles came up ${when}, so there is no price per ORE yet.`;
    case "focus_only":
      return "Focus-only shift: no SOL placed. It still counts for the streak.";
    case "no_market":
      return "No market quote right now, so there is nothing to compare against.";
  }
}

export function streakLine(before: number, after: number): string {
  if (after > before) return `Streak ${before} → ${after}`;
  if (after === before) return `Streak holds at ${after}`;
  return `Streak reset to ${after}`;
}

/** "7 h 40 m", or "45 m" under an hour (HaulFormat.duration). */
export function duration(seconds: number): string {
  const minutes = Math.floor(Math.max(0, seconds) / 60);
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h === 0 ? `${m} m` : `${h} h ${String(m).padStart(2, "0")} m`;
}

/** HH:MM in UTC (the dashboard does not know the rig's time zone, by design). */
export function clockUtc(ts: number): string {
  return new Date(ts * 1000).toISOString().slice(11, 16);
}

export interface RoundView {
  roundId: string;
  dark: boolean;
  dugMask: number;
  winningSquare: number | null;
  motherlode: boolean;
  split: boolean;
  dug: boolean;
  /** The winning square is one the rig dug. */
  hit: boolean;
}

export function roundViews(h: HaulSummary): RoundView[] {
  return h.rounds.map((r) => {
    const dug = r.dug_mask !== 0;
    return {
      roundId: String(r.round_id),
      dark: r.dark,
      dugMask: r.dug_mask,
      winningSquare: r.winning_square,
      motherlode: r.motherlode,
      split: r.split,
      dug,
      hit: dug && r.winning_square !== null && ((r.dug_mask >>> r.winning_square) & 1) === 1,
    };
  });
}

/** A Motherlode round the rig dug, but on other tiles (the reveal's "near miss"). */
export function motherlodeNearMiss(rounds: RoundView[]): RoundView | null {
  return rounds.find((r) => r.motherlode && r.dug && !r.hit) ?? null;
}

export const BREAK_REASONS: readonly string[] = [
  "completed",
  "picked up",
  "screen turned on",
  "frozen",
  "phone went quiet (lease lapsed)",
  "budget used up",
  "ended by hand",
  "unplugged",
  "unlocked",
];

export function breakReasonText(code: number): string {
  return BREAK_REASONS[code] ?? `reason ${code}`;
}
