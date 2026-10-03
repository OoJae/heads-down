/**
 * ORE's own reward arithmetic, reproduced exactly (program/src/checkpoint.rs @ b92c5043), so the
 * indexer can say how much ORE a rig mined and how much SOL came back, per round, without
 * waiting for the user to checkpoint and without trusting anyone's dashboard.
 *
 * For a miner with `d[i]` lamports on square i in a round with winning square `ws`:
 *
 *   ORE    split round (top_miner = SPLIT):  floor(top_miner_reward × d[ws] / T[ws])
 *          solo round:                       top_miner_reward iff top_miner == the miner's authority
 *          plus, when the Motherlode hit:    floor(motherlode × d[ws] / T[ws])
 *   SOL    winning square:  floor(d × (T − max(T/100, 1)) / T)
 *          other squares:   floor(d × (T − adm − max((T − adm)/10, 1)) / T),  adm = max(T/100, 1)
 *   no RNG (winning square unknown to ORE): nothing mined, every lamport refunded.
 *
 * `T[i]` is the Round account's per-square total; `top_miner_reward` is the sum of
 * `Round.rewards` = the round's +1 ORE mint = min(ResetEvent.total_minted, 1 ORE). With only the
 * ResetEvent, T is known for the winning square alone: ORE mined stays exact, and each losing
 * square's return is approximated as floor(d × 891 / 1000), within 2 lamports of ORE's value for
 * any real square (T ≥ 100 lamports); `exact` says which happened.
 *
 * Every figure is unrefined ORE, before ORE's 10% refining fee on claim.
 */
import type { OreRoundAccount } from "../codec/round.ts";
import { roundRng, isRoundReset, sumU64 } from "../codec/round.ts";
import { ONE_ORE, ORE_SPLIT_ADDRESS } from "../constants.ts";
import type { RoundRow } from "../model.ts";

export const SQUARES = 25;

/** What ORE decided about one round, from whichever source the indexer has. */
export interface RoundOutcome {
  roundId: bigint;
  /** 0..24, or null when the round had no entropy (every lamport refunded). */
  winningSquare: number | null;
  split: boolean;
  topMiner: string;
  /** Motherlode payout (atoms), shared pro rata on the winning square; 0 when it did not hit. */
  motherlode: bigint;
  /** The +1 ORE mint for the winning square. */
  topMinerReward: bigint;
  deployedWinningSquare: bigint;
  /** Per-square totals (Round account) when known: makes SOL returned exact. */
  squares: bigint[] | null;
  source: "round-account" | "reset-event";
  resetSignature: string | null;
}

export function outcomeFromReset(r: RoundRow): RoundOutcome {
  return {
    roundId: r.roundId,
    winningSquare: r.winningSquare,
    split: r.topMiner === ORE_SPLIT_ADDRESS,
    topMiner: r.topMiner,
    motherlode: r.motherlode,
    topMinerReward: r.totalMinted < ONE_ORE ? r.totalMinted : ONE_ORE,
    deployedWinningSquare: r.deployedWinningSquare,
    squares: null,
    source: "reset-event",
    resetSignature: r.resetSignature,
  };
}

/** From a Round account read after its reset; null while the round has not been reset. */
export function outcomeFromRoundAccount(acc: OreRoundAccount, resetSignature: string | null = null): RoundOutcome | null {
  if (!isRoundReset(acc)) return null;
  const rng = roundRng(acc.slotHash);
  const ws = rng === null ? null : Number(rng % 25n);
  return {
    roundId: acc.id,
    winningSquare: ws,
    split: acc.topMiner === ORE_SPLIT_ADDRESS,
    topMiner: acc.topMiner,
    motherlode: acc.motherlode,
    topMinerReward: sumU64(acc.rewards),
    deployedWinningSquare: ws === null ? 0n : acc.deployed[ws]!,
    // A no-RNG reset zeroes `deployed`; there is nothing to divide then anyway.
    squares: ws === null ? null : [...acc.deployed],
    source: "round-account",
    resetSignature,
  };
}

/** Per-square lamports of a miner in one round, from its DeployEvents (amount per square × mask). */
export function perSquare(deploys: readonly { amount: bigint; mask: number }[]): bigint[] {
  const d = Array.from({ length: SQUARES }, () => 0n);
  for (const e of deploys) for (let i = 0; i < SQUARES; i++) if ((e.mask >>> i) & 1) d[i]! += e.amount;
  return d;
}

export interface OreMined {
  /** The +1 ORE share (split) or all of it (solo winner). */
  base: bigint;
  motherlode: bigint;
  total: bigint;
}

export function oreMined(authority: string, deployed: readonly bigint[], o: RoundOutcome): OreMined {
  const ws = o.winningSquare;
  const none = { base: 0n, motherlode: 0n, total: 0n };
  if (ws === null) return none;
  const d = deployed[ws] ?? 0n;
  const t = o.deployedWinningSquare;
  if (d === 0n || t === 0n) return none;
  // A share can never exceed the square (ORE asserts round.deployed >= miner.deployed).
  const dd = d > t ? t : d;
  const base = o.split ? (o.topMinerReward * dd) / t : o.topMiner === authority ? o.topMinerReward : 0n;
  const motherlode = o.motherlode > 0n ? (o.motherlode * dd) / t : 0n;
  return { base, motherlode, total: base + motherlode };
}

const max1 = (x: bigint) => (x > 1n ? x : 1n);

/** SOL ORE returns for one square (checkpoint.rs lines 93-97 and 148-153). */
export function squareReturn(d: bigint, total: bigint, winning: boolean): bigint {
  if (d === 0n || total === 0n) return 0n;
  const admin = max1(total / 100n);
  if (winning) return (d * (total > admin ? total - admin : 0n)) / total;
  const afterAdmin = total > admin ? total - admin : 0n;
  const protocol = max1(afterAdmin / 10n);
  const kept = admin + protocol;
  return (d * (total > kept ? total - kept : 0n)) / total;
}

export function solReturned(deployed: readonly bigint[], o: RoundOutcome): { lamports: bigint; exact: boolean } {
  if (o.winningSquare === null) return { lamports: deployed.reduce((a, b) => a + b, 0n), exact: true };
  let lamports = 0n;
  let exact = true;
  for (let i = 0; i < SQUARES; i++) {
    const d = deployed[i] ?? 0n;
    if (d === 0n) continue;
    const winning = i === o.winningSquare;
    const total = o.squares ? o.squares[i]! : winning ? o.deployedWinningSquare : null;
    if (total === null) {
      exact = false;
      lamports += (d * 891n) / 1000n;
    } else {
      lamports += squareReturn(d, total, winning);
    }
  }
  return { lamports, exact };
}
