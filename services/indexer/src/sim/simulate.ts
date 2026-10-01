/**
 * SIMULATION MODE. Generates a realistic, fully deterministic dataset (same seed => byte-identical
 * output) for building and demoing the dashboard before mainnet, and feeds it through the SAME
 * path as real data: raw getTransaction-shaped JSON (real v1.1 instruction data, `Program data:`
 * events, Board-signed ORE Logs) -> extractTransaction -> Store, raw account bytes -> decode +
 * owner/PDA verification -> Store, and raw ORE Round accounts -> decodeOreRound -> Store.
 *
 * Everything lands in the `simulated` dataset, which carries its seed, is flagged
 * `simulated = true` in every API response, and can never be read by a process bound to a real
 * dataset. Addresses and signatures are random bytes: they exist on no chain, and the API emits
 * no explorer links for them.
 *
 * Model (INTERFACE.md v1.1 semantics, ported functions where it matters):
 *  - Rigs register over time (RigRegistered), 27% verify a Seeker Genesis Token, some churned rigs
 *    close (RigClosed). Shifts are armed with a wallet plan (lease 3, 15 split tiles, 0.001 SOL).
 *  - The phone signs a heartbeat each round it lies face-down (98% of rounds). The crank lands it
 *    inside a dig when the Motherlode-aware gate opens and budget remains, and otherwise through
 *    `record_heartbeats` whenever the rig's lease would lapse, so every face-down round is dark.
 *    Leases, dark rounds and gaps use the program's own `grant_lease` / `settle_shift`.
 *  - Digs reserve the Automation fee inside the caps (§6.4); RigDug.lamports excludes the fee and
 *    ShiftEnded.lamports includes it. Pickups and screen-on are phone-signed BREAKs (ShiftBroken)
 *    before the shift is ended. ShiftEnded is followed by ShiftEndedV2; a ShiftLog is written.
 *  - Streaks follow the program's `update_streak`. ORE rounds carry per-square totals; the Round
 *    accounts of rounds Heads Down dug are snapshotted, as the resolver does on real clusters.
 */
import { createHash } from "node:crypto";
import { encodeBase58 } from "../codec/base58.ts";
import { encodeRig, encodeSeekerSeat, encodeShiftLog, ACCOUNT_SIZE, ACCOUNT_TAG, ACCOUNT_VERSION, type RigAccount } from "../codec/accounts.ts";
import { ByteWriter } from "../codec/bytes.ts";
import { HD_ERROR_COST_GATE, type HdEvent } from "../codec/events.ts";
import type { OreResetEvent } from "../codec/ore.ts";
import { encodeOreRound, oreRoundPda, type OreRoundAccount } from "../codec/round.ts";
import { findProgramAddress, seed, addrBytes, u64le } from "../codec/pda.ts";
import type { RawTransaction } from "../codec/tx.ts";
import { ONE_ORE, ORE_SPLIT_ADDRESS } from "../constants.ts";
import { ingestAccountSnapshot, ingestRawTransactions, type IngestContext } from "../ingest.ts";
import { grantLease, settleShift } from "../metrics/lease.ts";
import { INITIAL_STREAK, unixDay, updateStreak, type StreakState } from "../metrics/streak.ts";
import type { RawAccount } from "../sources/rpc.ts";
import { buildDigTx, buildEventTx, buildRecordTx, type DigRigInput, type PlanInput, type RecordRigInput } from "./txbuilder.ts";

const DAY = 86_400;
const STALE_HEARTBEAT = 7;
const LEASE_EXPIRED = 8;
const RIG_NOT_ARMED = 13;
const INSUFFICIENT_AUTOMATION_BALANCE = 28;
const IX_SYSVAR = "Sysvar1nstructions1111111111111111111111111";

/** sfc32 seeded from SHA-256(label): tiny, fast, and identical on every platform. */
export class Rng {
  private a: number;
  private b: number;
  private c: number;
  private d: number;
  readonly label: string;

  constructor(label: string) {
    this.label = label;
    const h = createHash("sha256").update(label).digest();
    this.a = h.readUInt32LE(0);
    this.b = h.readUInt32LE(4);
    this.c = h.readUInt32LE(8);
    this.d = h.readUInt32LE(12);
    for (let i = 0; i < 12; i++) this.u32();
  }

  fork(sub: string): Rng {
    return new Rng(`${this.label}/${sub}`);
  }

  u32(): number {
    const t = (((this.a + this.b) | 0) + this.d) | 0;
    this.d = (this.d + 1) | 0;
    this.a = this.b ^ (this.b >>> 9);
    this.b = (this.c + (this.c << 3)) | 0;
    this.c = (this.c << 21) | (this.c >>> 11);
    this.c = (this.c + t) | 0;
    return t >>> 0;
  }

  next(): number {
    return this.u32() / 4_294_967_296;
  }

  int(n: number): number {
    return Math.floor(this.next() * n);
  }

  range(lo: number, hi: number): number {
    return lo + this.next() * (hi - lo);
  }

  normal(): number {
    const u = Math.max(this.next(), 1e-12);
    return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * this.next());
  }

  u64(): bigint {
    return (BigInt(this.u32()) << 32n) | BigInt(this.u32());
  }

  bytes(n: number): Uint8Array {
    const out = new Uint8Array(n);
    for (let i = 0; i < n; i++) out[i] = this.u32() & 0xff;
    return out;
  }

  address(): string {
    return encodeBase58(this.bytes(32));
  }

  signature(): string {
    return encodeBase58(this.bytes(64));
  }

  weighted<T>(items: readonly (readonly [T, number])[]): T {
    const total = items.reduce((s, [, w]) => s + w, 0);
    let x = this.next() * total;
    for (const [v, w] of items) {
      if ((x -= w) < 0) return v;
    }
    return items[items.length - 1]![0];
  }
}

export interface SimConfig {
  seed: string;
  rigs: number;
  nights: number;
  /** First night, "YYYY-MM-DD". */
  startDay: string;
  programId: string;
  executorPda: string;
  configPda: string;
}

export const DEFAULT_SIM: Omit<SimConfig, "programId" | "executorPda" | "configPda"> = {
  seed: "heads-down-demo-v1",
  rigs: 120,
  nights: 28,
  startDay: "2026-09-10",
};

interface SimRound {
  id: bigint;
  ts: number;
  soloMask: number;
  winningSquare: number;
  emaEv: bigint;
  emaLamports: bigint;
  potPayout: bigint;
  baseMiners: number;
  /** Other miners' SOL on each square. */
  base: bigint[];
  digs: DigRigInput[];
  /** Replays of an old heartbeat (refused as StaleHeartbeat), in their own transactions. */
  replays: DigRigInput[];
  records: RecordRigInput[];
  /** Heads Down SOL per square and the authorities that placed it. */
  hd: bigint[];
  hdAuthorities: Set<string>;
}

interface SimRig {
  authority: string;
  rig: string;
  bump: number;
  automation: string;
  miner: string;
  tz: number;
  tier: 0 | 1;
  sgtMint: string | null;
  memberNumber: bigint;
  attestationLevel: number;
  joinNight: number;
  joinTs: number;
  maxEv: bigint;
  capShift: bigint;
  focusOnly: boolean;
  counter: bigint;
  shifts: number;
  darkRounds: bigint;
  roundsDug: bigint;
  lamports: bigint;
  streak: StreakState;
  inProgress: { shiftId: number; startRound: bigint; startTs: number; leaseFrom: bigint; leaseTo: bigint; dark: bigint; dug: bigint; spent: bigint; gaps: bigint } | null;
  lastPlan: PlanInput | null;
  lastEnd: number;
  closedAt: number | null;
}

export interface SimOutput {
  txs: RawTransaction[];
  rounds: { event: OreResetEvent; resetSignature: string }[];
  roundStates: { address: string; account: OreRoundAccount; data: Uint8Array }[];
  accounts: RawAccount[];
  contextSlot: number;
  asOf: number;
  teamCrankers: string[];
  /** Simulated market quote (lamports per ORE), served with source "simulated". */
  marketLamportsPerOre: bigint;
}

const REGIONS: readonly (readonly [number, number])[] = [
  [60, 0.45], // WAT: Lagos, Abuja
  [180, 0.1], // EAT: Nairobi
  [480, 0.12], // PHT: Manila
  [540, 0.1], // KST: Seoul
  [-180, 0.1], // BRT: Sao Paulo
  [330, 0.08], // IST
  [0, 0.05],
];

const DIG_LAMPORTS = 1_000_000n;
const SPLIT_TILES = 15n;
const EXECUTOR_FEE = 5_000n;
const CRANK_FEE = 4_000n;
const PLAN_LEASE = 3;
const MAX_RIGS_PER_TX = 6;
const MAX_RECORDS_PER_TX = 27;
const ONE_DAY_SLOTS = 216_000n;

function slotAt(t: number, t0: number): number {
  return 451_000_000 + Math.floor((t - t0) / 0.4);
}

function pickSoloMask(r: Rng): number {
  const idx = Array.from({ length: 25 }, (_, i) => i);
  for (let i = 24; i > 0; i--) {
    const j = r.int(i + 1);
    [idx[i], idx[j]] = [idx[j]!, idx[i]!];
  }
  let m = 0;
  for (let i = 0; i < 10; i++) m |= 1 << idx[i]!;
  return m;
}

/** ORE's fee split for one round (state/round.rs `calculate_fees`). */
function roundFees(deployed: bigint[], ws: number): { admin: bigint; protocol: bigint } {
  let admin = 0n;
  let protocol = 0n;
  deployed.forEach((d, i) => {
    if (d === 0n) return;
    const a = d / 100n > 1n ? d / 100n : 1n;
    admin += a;
    if (i !== ws) protocol += (d - a) / 10n > 1n ? (d - a) / 10n : 1n;
  });
  return { admin, protocol };
}

export function generateSimulation(cfg: SimConfig): SimOutput {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(cfg.startDay)) throw new Error("startDay must be YYYY-MM-DD");
  if (!(cfg.rigs >= 1 && cfg.rigs <= 5000 && cfg.nights >= 1 && cfg.nights <= 120)) {
    throw new Error("simulation size out of range (rigs 1..5000, nights 1..120)");
  }
  const root = new Rng(`heads-down-sim:${cfg.seed}`);
  const startDayIndex = Math.floor(Date.parse(`${cfg.startDay}T00:00:00Z`) / 1000 / DAY);
  const t0 = startDayIndex * DAY + 12 * 3600; // noon UTC on the first night
  const asOf = t0 + cfg.nights * DAY + 3600;

  // ---- ORE rounds with a Motherlode pot and a production-cost EMA ----------------------
  const rr = root.fork("rounds");
  const rounds: SimRound[] = [];
  let pot = 40; // ORE
  let ema = 0.52; // SOL per ORE
  let id = 410_000n;
  for (let t = t0 - 2 * 3600; t < asOf; t += 76 + rr.int(5)) {
    let payout = 0;
    if (rr.next() < 1 / 500) {
      payout = pot;
      pot = 0;
    }
    pot += 0.2;
    // Crowding follows the pot (RESULTS.md section 2): EMA ~0.51 at small pots, ~0.9 at 350 ORE.
    const cost = Math.max(0.2, 0.51 + 3e-6 * pot * pot + rr.normal() * 0.04);
    ema = (cost + 19 * ema) / 20;
    const emaLamports = BigInt(Math.round(ema * 1e9));
    const potBase = BigInt(Math.round(pot * 1e11));
    const emaEv = (emaLamports * 6n * 500n * ONE_ORE) / (5n * (500n * ONE_ORE + potBase));
    const hourUtc = Math.floor((((t % DAY) + DAY) % DAY) / 3600);
    const tile = 0.44e9 * rr.range(0.88, 1.12);
    rounds.push({
      id: id++,
      ts: t,
      soloMask: pickSoloMask(rr),
      winningSquare: rr.int(25),
      emaEv,
      emaLamports,
      potPayout: BigInt(Math.round(payout * 1e11)),
      baseMiners: Math.round(152 + 14 * Math.sin((2 * Math.PI * (hourUtc - 15)) / 24) + rr.range(-6, 6)),
      base: Array.from({ length: 25 }, () => BigInt(Math.round(tile * rr.range(0.85, 1.15)))),
      digs: [],
      replays: [],
      records: [],
      hd: Array.from({ length: 25 }, () => 0n),
      hdAuthorities: new Set(),
    });
  }
  /** Index of the round in progress at time t (Board.round_id = the first round not yet reset). */
  const roundAt = (t: number) => {
    let lo = 0;
    let hi = rounds.length;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (rounds[mid]!.ts < t) lo = mid + 1;
      else hi = mid;
    }
    return lo;
  };

  // ---- rigs --------------------------------------------------------------------------
  const txr = root.fork("txs");
  const teamCrank = root.fork("keys").address();
  const thirdPartyCrank = root.fork("keys2").address();
  const eventTxs: RawTransaction[] = [];
  const shiftLogs: RawAccount[] = [];
  const rigs: SimRig[] = [];
  for (let i = 0; i < cfg.rigs; i++) {
    const r = root.fork(`rig:${i}`);
    const authority = r.address();
    const { address: rig, bump } = findProgramAddress([seed("rig"), addrBytes(authority)], cfg.programId);
    const tier: 0 | 1 = r.next() < 0.27 ? 1 : 0;
    const joinNight = Math.floor(cfg.nights * 0.9 * Math.sqrt(r.next()));
    const s: SimRig = {
      authority,
      rig,
      bump,
      automation: r.address(),
      miner: r.address(),
      tz: r.weighted(REGIONS),
      tier,
      sgtMint: tier === 1 ? r.address() : null,
      memberNumber: BigInt(1 + r.int(150_000)),
      attestationLevel: r.weighted([
        [1, 0.6],
        [2, 0.25],
        [0, 0.15],
      ] as const),
      joinNight,
      joinTs: t0 + joinNight * DAY + 5 * 3600 + r.int(3600),
      maxEv: BigInt(Math.round(r.range(0.53, 0.555) * 1e9)),
      capShift: r.weighted([
        [20_000_000n, 0.4],
        [40_000_000n, 0.4],
        [50_000_000n, 0.2],
      ] as const),
      focusOnly: r.next() < 0.05,
      counter: 0n,
      shifts: 0,
      darkRounds: 0n,
      roundsDug: 0n,
      lamports: 0n,
      streak: INITIAL_STREAK,
      inProgress: null,
      lastPlan: null,
      lastEnd: 0,
      closedAt: null,
    };
    rigs.push(s);

    // register_rig (tier 0 until verify_seeker).
    eventTxs.push(
      buildEventTx({
        signature: txr.signature(), slot: slotAt(s.joinTs, t0), blockTime: s.joinTs, signer: authority, programId: cfg.programId,
        events: [{ kind: "RigRegistered", rig, authority, tier: 0, attestationLevel: s.attestationLevel }],
      }),
    );
    if (s.tier === 1) {
      const t = s.joinTs + 3600 + r.int(3600);
      eventTxs.push(
        buildEventTx({
          signature: txr.signature(), slot: slotAt(t, t0), blockTime: t, signer: authority, programId: cfg.programId,
          events: [{ kind: "SeekerVerified", rig, sgtMint: s.sgtMint!, memberNumber: s.memberNumber }],
        }),
      );
    }

    const plateau = r.range(0.25, 0.8);
    const churnAt = r.next() < 0.15 ? s.joinNight + 2 + r.int(8) : Infinity;
    // Four in ten churned rigs stop for good and close their rig; the rest come back for the odd night.
    const closes = Number.isFinite(churnAt) && r.next() < 0.4;
    for (let k = s.joinNight; k < cfg.nights; k++) {
      const since = k - s.joinNight;
      const p = k >= churnAt ? (closes ? 0 : 0.03) : plateau + (0.92 - plateau) * Math.exp(-since / 3);
      if (since > 0 && r.next() >= p) continue;
      const localMidnight = (startDayIndex + k) * DAY - s.tz * 60;
      const start = Math.round(localMidnight + (22.5 + r.normal() * 0.6) * 3600);
      const plannedEnd = Math.round(start + (8 + r.normal() * 0.5) * 3600);
      if (start >= asOf || start <= s.joinTs + 2 * 3600 || start <= s.lastEnd + 600) continue;
      let breakAt: number | null = null;
      let breakReason = 0;
      const u = r.next();
      if (u < 0.08) {
        breakReason = 1; // pickup
        breakAt = Math.round(start + (plannedEnd - start) * r.range(0.2, 0.8));
      } else if (u < 0.11) {
        breakReason = 2; // screen_on
        breakAt = Math.round(start + (plannedEnd - start) * r.range(0.3, 0.9));
      }
      const plan: PlanInput = {
        maxEvCost: s.maxEv,
        digLamports: DIG_LAMPORTS,
        split: 15,
        solo: 0,
        lease: PLAN_LEASE,
        flags: s.focusOnly ? 1 : 0,
        windowStart: BigInt(start - 600),
        windowEnd: BigInt(plannedEnd - 60),
      };
      s.lastPlan = plan;
      s.shifts++;
      const shiftId = s.shifts;
      eventTxs.push(
        buildEventTx({
          signature: txr.signature(), slot: slotAt(start, t0), blockTime: start, signer: authority, programId: cfg.programId,
          events: [{ kind: "ShiftArmed", rig, shiftId: BigInt(shiftId) }], plan,
        }),
      );
      const startRound = rounds[roundAt(start)]?.id ?? id;
      let leaseFrom = 0n;
      let leaseTo = 0n;
      let dark = 0n;
      let gaps = 0n;
      let dug = 0n;
      let spent = 0n;
      const stopAt = Math.min(breakAt ?? plannedEnd, asOf);
      // 3% of unbroken shifts: the phone goes quiet mid-shift (battery, network) without a BREAK.
      const quietAt = breakAt === null && r.next() < 0.03 ? Math.round(start + (plannedEnd - start) * r.range(0.3, 0.9)) : null;
      let quietTried = false;
      const grant = (round: bigint) => {
        const g = grantLease(leaseFrom, leaseTo, startRound, round, PLAN_LEASE);
        leaseFrom = g.from;
        leaseTo = g.to;
        dark += g.darkAdded;
        gaps += g.gapAdded;
        return g.darkAdded;
      };
      for (let j = roundAt(start); j < rounds.length; j++) {
        const round = rounds[j]!;
        // Every transaction of a round lands within its last ~50 s (see the batching below): never before the arm.
        if (round.ts - 50 <= start) continue;
        if (round.ts - 25 >= stopAt) break;
        const ent = { rig, authority, automation: s.automation, miner: s.miner };
        if (quietAt !== null && round.ts - 25 >= quietAt) {
          // No more heartbeats. A crank whose mirror lags tries the old lease once it has lapsed: LeaseExpired.
          if (!quietTried && leaseTo !== 0n && round.id > leaseTo) {
            round.replays.push({ ...ent, heartbeat: null, outcome: { kind: "skipped", error: LEASE_EXPIRED } });
            quietTried = true;
          }
          continue;
        }
        if (r.next() < 0.002 && s.counter > 0n) {
          // A duplicate / hostile submission replays the last heartbeat: refused, not applied.
          round.replays.push({ ...ent, heartbeat: { counter: s.counter, round: round.id - 1n, lease: PLAN_LEASE }, outcome: { kind: "skipped", error: STALE_HEARTBEAT } });
        }
        if (r.next() < 0.02) continue; // face-down but no heartbeat this round
        s.counter++;
        const hb = { counter: s.counter, round: round.id, lease: PLAN_LEASE };
        const open = round.emaEv <= s.maxEv;
        // Budget with the Automation fee reserved inside the caps (§6.4).
        const headroom = (DIG_LAMPORTS < s.capShift - spent ? DIG_LAMPORTS : s.capShift - spent) - EXECUTOR_FEE;
        const budget = headroom < DIG_LAMPORTS ? headroom : DIG_LAMPORTS;
        const perTile = budget > 0n ? budget / SPLIT_TILES : 0n;
        if (!s.focusOnly && open && perTile > 0n && r.next() < 0.93) {
          if (r.next() < 0.004) {
            // The Automation ran too low for this dig: skipped after the heartbeat (its lease still counts).
            round.digs.push({ ...ent, heartbeat: hb, outcome: { kind: "skipped", error: INSUFFICIENT_AUTOMATION_BALANCE } });
            grant(round.id);
            continue;
          }
          const mask = ~round.soloMask & 0x1ff_ffff;
          round.digs.push({ ...ent, heartbeat: hb, outcome: { kind: "dug", perTile, mask, emaEv: round.emaEv } });
          for (let q = 0; q < 25; q++) if ((mask >>> q) & 1) round.hd[q]! += perTile;
          round.hdAuthorities.add(authority);
          grant(round.id);
          dug++;
          spent += perTile * SPLIT_TILES + EXECUTOR_FEE;
        } else if (!s.focusOnly && !open && r.next() < 0.01) {
          // The crank's mirror said open, the chain said closed: the heartbeat still lands (lease granted).
          round.digs.push({ ...ent, heartbeat: hb, outcome: { kind: "skipped", error: HD_ERROR_COST_GATE } });
          grant(round.id);
        } else if (leaseTo < round.id) {
          const added = grant(round.id);
          round.records.push({ rig, heartbeat: hb, outcome: { kind: "recorded", darkRoundsAdded: added } });
        } else {
          s.counter--; // nothing landed: this heartbeat was never used on chain
        }
      }
      const tEnd = breakAt !== null ? breakAt + 120 + r.int(480) : plannedEnd + 30 + r.int(60);
      if (breakAt !== null && breakAt < asOf) {
        // The phone's BREAK (P-256, it consumes a counter), landed by the crank: break_shift mode 1.
        s.counter++;
        const data = new ByteWriter(13).u8(8).u8(1).u8(breakReason).u64(s.counter).u8(0).u8(0).finish();
        eventTxs.push(
          buildEventTx({
            signature: txr.signature(), slot: slotAt(breakAt, t0), blockTime: breakAt, signer: teamCrank, programId: cfg.programId,
            events: [{ kind: "ShiftBroken", rig, shiftId: BigInt(shiftId), reason: breakReason }],
            ix: { data, accounts: [rig, authority, IX_SYSVAR] },
          }),
        );
        // Half the time a lagging crank still tries the lease in the next round: the rig is Cooling, so RigNotArmed.
        const next = rounds[roundAt(breakAt + 30)];
        if (next && r.next() < 0.5 && next.ts - 20 > breakAt + 2 && next.ts - 20 < tEnd - 2) {
          next.replays.push({ rig, authority, automation: s.automation, miner: s.miner, heartbeat: null, outcome: { kind: "skipped", error: RIG_NOT_ARMED } });
        }
      }
      const endIdx = roundAt(tEnd);
      if (tEnd + 90 >= asOf || endIdx >= rounds.length) {
        s.inProgress = { shiftId, startRound, startTs: start, leaseFrom, leaseTo, dark, dug, spent, gaps };
        s.darkRounds += dark;
        s.roundsDug += dug;
        s.lamports += spent - dug * EXECUTOR_FEE;
        break;
      }
      const endRound = rounds[endIdx]!.id;
      const [settled, settledGaps] = settleShift(dark, gaps, leaseTo, startRound, endRound);
      const reason = breakAt !== null ? breakReason : settled === 0n ? 4 : 0;
      const mode = s.focusOnly ? 2 : 0;
      const summary = { rig, shiftId: BigInt(shiftId), darkRounds: settled, roundsDug: dug, lamports: spent, reason };
      eventTxs.push(
        buildEventTx({
          signature: txr.signature(), slot: slotAt(tEnd, t0), blockTime: tEnd, signer: authority, programId: cfg.programId,
          events: [
            { kind: "ShiftEnded", ...summary },
            { kind: "ShiftEndedV2", ...summary, startRound, endRound, mode },
          ],
        }),
      );
      const log = findProgramAddress([seed("shift"), addrBytes(rig), u64le(BigInt(shiftId))], cfg.programId);
      shiftLogs.push({
        address: log.address,
        owner: cfg.programId,
        data: encodeShiftLog({
          kind: "ShiftLog", bump: log.bump, rig, shiftId: BigInt(shiftId), startRound, endRound, darkRounds: settled, roundsDug: dug,
          lamportsDeployed: spent, breakReason: reason, mode, startTs: BigInt(start), endTs: BigInt(tEnd),
        }),
      });
      void settledGaps;
      s.streak = updateStreak(s.streak, unixDay(BigInt(tEnd)), reason === 0 && settled > 0n);
      s.darkRounds += settled;
      s.roundsDug += dug;
      s.lamports += spent - dug * EXECUTOR_FEE;
      s.lastEnd = tEnd;
    }
    // close_rig, one to three days after the last shift (the rig is Idle and its shift has ended).
    if (closes && !s.inProgress && s.shifts > 0) {
      const t = s.lastEnd + DAY + r.int(2 * DAY);
      if (t < asOf - 3600) {
        s.closedAt = t;
        eventTxs.push(
          buildEventTx({
            signature: txr.signature(), slot: slotAt(t, t0), blockTime: t, signer: authority, programId: cfg.programId,
            events: [{ kind: "RigClosed", rig }],
          }),
        );
      }
    }
  }

  // ---- dig and record_heartbeats transactions, batched per round ------------------------
  const digTxs: RawTransaction[] = [];
  for (const round of rounds) {
    for (let i = 0; i < round.digs.length; i += MAX_RIGS_PER_TX) {
      const t = round.ts - 25 - i / MAX_RIGS_PER_TX;
      const night = Math.floor((t - t0) / DAY);
      const cranker = night >= 14 && txr.next() < 0.12 ? thirdPartyCrank : teamCrank;
      digTxs.push(
        buildDigTx({
          signature: txr.signature(), slot: slotAt(t, t0), blockTime: t, cranker, programId: cfg.programId, configPda: cfg.configPda,
          executorPda: cfg.executorPda, roundAccount: oreRoundPda(round.id), roundId: round.id, rigs: round.digs.slice(i, i + MAX_RIGS_PER_TX),
        }),
      );
    }
    for (let i = 0; i < round.replays.length; i += MAX_RIGS_PER_TX) {
      const t = round.ts - 20 - i / MAX_RIGS_PER_TX;
      digTxs.push(
        buildDigTx({
          signature: txr.signature(), slot: slotAt(t, t0), blockTime: t, cranker: thirdPartyCrank, programId: cfg.programId, configPda: cfg.configPda,
          executorPda: cfg.executorPda, roundAccount: oreRoundPda(round.id), roundId: round.id, rigs: round.replays.slice(i, i + MAX_RIGS_PER_TX),
        }),
      );
    }
    for (let i = 0; i < round.records.length; i += MAX_RECORDS_PER_TX) {
      const t = round.ts - 40 - i / MAX_RECORDS_PER_TX;
      digTxs.push(
        buildRecordTx({
          signature: txr.signature(), slot: slotAt(t, t0), blockTime: t, cranker: teamCrank, programId: cfg.programId,
          boardRound: round.id, rigs: round.records.slice(i, i + MAX_RECORDS_PER_TX),
        }),
      );
    }
  }

  // ---- ORE ResetEvents and the Round accounts of rounds Heads Down dug -------------------
  const out: SimOutput["rounds"] = [];
  const roundStates: SimOutput["roundStates"] = [];
  for (const round of rounds) {
    const deployed = round.base.map((b, i) => b + round.hd[i]!);
    const ws = round.winningSquare;
    const split = ((round.soloMask >>> ws) & 1) === 0;
    const total = deployed.reduce((a, b) => a + b, 0n);
    const { admin, protocol } = roundFees(deployed, ws);
    const topMiner = split ? ORE_SPLIT_ADDRESS : txr.address();
    // rng with rng % 25 == the winning square, as ORE derives it from the slot hash.
    const words = [txr.u64(), txr.u64(), txr.u64()];
    const rng = (txr.u64() / 25n) * 25n + BigInt(ws);
    const slotHash = new ByteWriter(32).u64(rng ^ words[0]! ^ words[1]! ^ words[2]!).u64(words[0]!).u64(words[1]!).u64(words[2]!).finish();
    const startSlot = BigInt(slotAt(round.ts - 96, t0));
    const event: OreResetEvent = {
      kind: "OreReset",
      roundId: round.id,
      startSlot,
      endSlot: startSlot + 240n,
      winningSquare: ws,
      topMiner,
      totalMiners: BigInt(round.baseMiners + round.hdAuthorities.size),
      motherlode: round.potPayout,
      totalDeployed: total,
      totalVaulted: protocol,
      totalWinnings: total - admin - protocol,
      totalMinted: 120_000_000_000n,
      ts: BigInt(round.ts),
      rng,
      deployedWinningSquare: deployed[ws]!,
    };
    out.push({ resetSignature: txr.signature(), event });
    if (round.hdAuthorities.size > 0) {
      const account: OreRoundAccount = {
        id: round.id,
        deployed,
        slotHash,
        expiresAt: startSlot + 240n + ONE_DAY_SLOTS,
        motherlode: round.potPayout,
        rentPayer: teamCrank,
        rewards: [ONE_ORE, ...Array.from({ length: 24 }, () => 0n)],
        totalVaulted: protocol,
        totalReturnedSol: total - admin - protocol,
        totalMiners: event.totalMiners,
        topMiner,
      };
      roundStates.push({ address: oreRoundPda(round.id), account, data: encodeOreRound(account) });
    }
  }

  // ---- account snapshot -----------------------------------------------------------------
  const accounts: RawAccount[] = [...shiftLogs];
  for (const s of rigs) {
    if (s.closedAt !== null) continue; // closed: the account no longer exists
    const ip = s.inProgress;
    const plan = s.lastPlan;
    const acc: RigAccount = {
      kind: "Rig", bump: s.bump, authority: s.authority, p256Pubkey: "02" + Buffer.from(root.fork(`p256:${s.rig}`).bytes(32)).toString("hex"),
      attestationLevel: s.attestationLevel, tier: s.tier, state: ip ? 2 : 0, sgtMint: s.sgtMint, attestationExpirySlot: 0n,
      capWeek: s.capShift * 7n, capShift: s.capShift, capRound: DIG_LAMPORTS, capMaxCost: s.maxEv + 50_000_000n,
      capsExpiryTs: BigInt(asOf + 30 * DAY), planMaxEvCost: plan?.maxEvCost ?? s.maxEv, planDigLamports: DIG_LAMPORTS, planSplitTiles: 15, planSoloTiles: 0,
      planLeaseRounds: PLAN_LEASE, planFlags: s.focusOnly ? 1 : 0, planWindowStartTs: plan?.windowStart ?? 0n, planWindowEndTs: plan?.windowEnd ?? 0n,
      shiftId: BigInt(s.shifts), hbCounter: s.counter, leaseFromRound: ip?.leaseFrom ?? 0n, leaseToRound: ip?.leaseTo ?? 0n, gapCount: Number(ip?.gaps ?? 0n),
      spentShift: ip?.spent ?? 0n, spentWeek: 0n, weekStartTs: BigInt(s.joinTs), lastDugRound: 0n, shiftStartRound: ip?.startRound ?? 0n,
      shiftDarkRounds: ip?.dark ?? 0n, shiftRoundsDug: ip?.dug ?? 0n, lifetimeDarkRounds: s.darkRounds, lifetimeRoundsDug: s.roundsDug,
      lifetimeLamportsDeployed: s.lamports, streak: s.streak.streak, freezesLeft: s.streak.freezesLeft, lastShiftDay: s.streak.lastDay,
      shiftOpen: ip ? 1 : 0, breakReason: 0, oreAutomationBump: 255, oreMinerBump: 255, shiftStartTs: BigInt(ip?.startTs ?? 0),
    };
    accounts.push({ address: s.rig, owner: cfg.programId, data: encodeRig(acc) });
    if (s.tier === 1 && s.sgtMint) {
      const seat = findProgramAddress([seed("seeker"), addrBytes(s.sgtMint)], cfg.programId);
      accounts.push({
        address: seat.address,
        owner: cfg.programId,
        data: encodeSeekerSeat({ kind: "SeekerSeat", bump: seat.bump, sgtMint: s.sgtMint, rig: s.rig, authority: s.authority, memberNumber: s.memberNumber, verifiedSlot: 451_000_000n }),
      });
    }
  }
  const cfgPda = findProgramAddress([seed("config")], cfg.programId);
  const execBump = findProgramAddress([seed("executor")], cfg.programId).bump;
  const config = new ByteWriter(ACCOUNT_SIZE.Config)
    .u8(ACCOUNT_TAG.Config).u8(ACCOUNT_VERSION).u8(cfgPda.bump).skip(5)
    .bytes(root.fork("gov").bytes(32)).bytes(root.fork("reg").bytes(32))
    .u64(CRANK_FEE).u64(EXECUTOR_FEE).u16(0).u8(0).u8(execBump)
    .finish();
  accounts.push({ address: cfgPda.address, owner: cfg.programId, data: config });

  // Simulated market: the protocol EMA at the end, with a premium (a modelling assumption, labelled "simulated").
  const lastEma = rounds[rounds.length - 1]?.emaLamports ?? 520_000_000n;
  const marketLamportsPerOre = (lastEma * 135n) / 100n;

  const txs = [...eventTxs, ...digTxs].sort((a, b) => a.slot - b.slot || (a.transaction.signatures[0]! < b.transaction.signatures[0]! ? -1 : 1));
  return { txs, rounds: out, roundStates, accounts, contextSlot: slotAt(asOf, t0), asOf, teamCrankers: [teamCrank], marketLamportsPerOre };
}

/** Generates and ingests a simulation into a Store bound to the `simulated` dataset. */
export async function runSimulation(ctx: IngestContext, cfg: SimConfig, log?: (m: string) => void): Promise<SimOutput> {
  if (ctx.store.dataset !== "simulated") throw new Error("refusing to write simulated data into a real dataset");
  const sim = generateSimulation(cfg);
  log?.(`simulated ${sim.txs.length} txs, ${sim.rounds.length} ORE rounds, ${sim.roundStates.length} Round accounts, ${sim.accounts.length} accounts`);
  for (let i = 0; i < sim.rounds.length; i += 5000) await ctx.store.upsertRounds(sim.rounds.slice(i, i + 5000), "simulated");
  for (let i = 0; i < sim.txs.length; i += 1000) await ingestRawTransactions(ctx, sim.txs.slice(i, i + 1000), "simulated");
  await ingestAccountSnapshot(ctx, sim.accounts, sim.contextSlot);
  const states = sim.roundStates.map((s) => ({ ...s, contextSlot: sim.contextSlot }));
  for (let i = 0; i < states.length; i += 2000) await ctx.store.upsertRoundStates(states.slice(i, i + 2000), "simulated");
  await ctx.store.setSimAsOf(sim.asOf);
  return sim;
}

export type { HdEvent };
