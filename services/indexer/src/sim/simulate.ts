/**
 * SIMULATION MODE. The heads_down program is not deployed yet, so the dashboard needs data to
 * be built and demoed against. This module generates a realistic, fully deterministic dataset
 * (same seed => byte-identical output) and feeds it through the SAME path as real data:
 * raw getTransaction-shaped JSON -> extractTransaction -> Store, and raw account bytes ->
 * decode + owner/PDA verification -> Store.
 *
 * Everything lands in the `simulated` dataset, which carries its seed, is flagged
 * `simulated = true` in every API response, and can never be read by a process bound to a
 * real dataset. Addresses and signatures are random bytes: they do not exist on any chain, and
 * the API emits no explorer links for them.
 *
 * Behavioural model (see README "Simulation model"): rigs join over time, arm a shift most
 * nights in their own time zone (WAT-heavy), habit decays toward a per-rig plateau, some
 * churn; digs are 0.001 SOL chunks on the 15 split tiles, only in rounds where the
 * Motherlode-aware gate (INTERFACE.md "Gate") opens, until the shift budget is spent
 * (ml/forecaster/RESULTS.md: concentrated, gated digs).
 */
import { createHash } from "node:crypto";
import { encodeBase58 } from "../codec/base58.ts";
import { encodeRig, encodeSeekerSeat, ACCOUNT_SIZE, ACCOUNT_TAG, ACCOUNT_VERSION, type RigAccount } from "../codec/accounts.ts";
import { ByteWriter } from "../codec/bytes.ts";
import { HD_ERROR_COST_GATE } from "../codec/events.ts";
import type { OreResetEvent } from "../codec/ore.ts";
import { findProgramAddress, seed, addrBytes } from "../codec/pda.ts";
import type { RawTransaction } from "../codec/tx.ts";
import { ONE_ORE, ORE_SPLIT_ADDRESS } from "../constants.ts";
import { ingestAccountSnapshot, ingestRawTransactions, type IngestContext } from "../ingest.ts";
import type { RawAccount } from "../sources/rpc.ts";
import { buildDigTx, buildEventTx, type DigRigInput } from "./txbuilder.ts";

const DAY = 86_400;
const STALE_HEARTBEAT = 7;

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
  potPayout: bigint;
  baseMiners: number;
  baseTileDeployed: bigint;
  actions: DigRigInput[];
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
  joinNight: number;
  maxEv: bigint;
  capShift: bigint;
  focusOnly: boolean;
  shifts: number;
  darkRounds: bigint;
  roundsDug: bigint;
  lamports: bigint;
  activeNights: number[];
  inProgress: boolean;
}

export interface SimOutput {
  txs: RawTransaction[];
  rounds: { event: OreResetEvent; resetSignature: string }[];
  accounts: RawAccount[];
  contextSlot: number;
  asOf: number;
  teamCrankers: string[];
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
const PER_TILE = DIG_LAMPORTS / SPLIT_TILES; // 66_666
const MAX_RIGS_PER_TX = 6;

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
    const hourUtc = Math.floor(((t % DAY) + DAY) % DAY / 3600);
    rounds.push({
      id: id++,
      ts: t,
      soloMask: pickSoloMask(rr),
      winningSquare: rr.int(25),
      emaEv,
      potPayout: BigInt(Math.round(payout * 1e11)),
      baseMiners: Math.round(152 + 14 * Math.sin((2 * Math.PI * (hourUtc - 15)) / 24) + rr.range(-6, 6)),
      baseTileDeployed: BigInt(Math.round(0.44e9 * rr.range(0.88, 1.12))),
      actions: [],
      hdAuthorities: new Set(),
    });
  }
  const firstRoundAtOrAfter = (t: number) => {
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
  const rigs: SimRig[] = [];
  for (let i = 0; i < cfg.rigs; i++) {
    const r = root.fork(`rig:${i}`);
    const authority = r.address();
    const { address: rig, bump } = findProgramAddress([seed("rig"), addrBytes(authority)], cfg.programId);
    const tier: 0 | 1 = r.next() < 0.27 ? 1 : 0;
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
      joinNight: Math.floor(cfg.nights * 0.9 * Math.sqrt(r.next())),
      maxEv: BigInt(Math.round(r.range(0.53, 0.555) * 1e9)),
      capShift: r.weighted([
        [20_000_000n, 0.4],
        [40_000_000n, 0.4],
        [50_000_000n, 0.2],
      ] as const),
      focusOnly: r.next() < 0.05,
      shifts: 0,
      darkRounds: 0n,
      roundsDug: 0n,
      lamports: 0n,
      activeNights: [],
      inProgress: false,
    };
    rigs.push(s);

    if (s.tier === 1) {
      const t = t0 + s.joinNight * DAY + 6 * 3600 + r.int(3600);
      eventTxs.push(
        buildEventTx({
          signature: txr.signature(), slot: slotAt(t, t0), blockTime: t, signer: authority, programId: cfg.programId,
          events: [{ kind: "SeekerVerified", rig, sgtMint: s.sgtMint!, memberNumber: s.memberNumber }],
        }),
      );
    }

    const plateau = r.range(0.25, 0.8);
    const churnAt = r.next() < 0.15 ? s.joinNight + 2 + r.int(8) : Infinity;
    for (let k = s.joinNight; k < cfg.nights; k++) {
      const since = k - s.joinNight;
      const p = k >= churnAt ? 0.03 : plateau + (0.92 - plateau) * Math.exp(-since / 3);
      if (since > 0 && r.next() >= p) continue;
      const localMidnight = (startDayIndex + k) * DAY - s.tz * 60;
      const start = Math.round(localMidnight + (22.5 + r.normal() * 0.6) * 3600);
      let dur = (8 + r.normal() * 0.5) * 3600;
      let reason = 0;
      const u = r.next();
      if (u < 0.08) {
        reason = 1; // pickup
        dur *= r.range(0.2, 0.8);
      } else if (u < 0.11) {
        reason = 2; // screen_on
        dur *= r.range(0.3, 0.9);
      }
      const end = Math.round(start + dur);
      if (start >= asOf) continue;
      s.shifts++;
      s.activeNights.push(k);
      eventTxs.push(
        buildEventTx({
          signature: txr.signature(), slot: slotAt(start, t0), blockTime: start, signer: authority, programId: cfg.programId,
          events: [{ kind: "ShiftArmed", rig, shiftId: BigInt(s.shifts) }],
        }),
      );
      let dark = 0n;
      let dug = 0n;
      let spent = 0n;
      const stop = Math.min(end, asOf);
      for (let j = firstRoundAtOrAfter(start); j < rounds.length && rounds[j]!.ts <= stop; j++) {
        const round = rounds[j]!;
        if (r.next() < 0.02) continue; // heartbeat gap: no lease this round
        dark++;
        if (s.focusOnly) continue;
        const open = round.emaEv <= s.maxEv;
        if (open && spent + DIG_LAMPORTS <= s.capShift) {
          if (r.next() < 0.93) {
            const mask = ~round.soloMask & 0x1ff_ffff;
            round.actions.push({ rig, authority, automation: s.automation, miner: s.miner, outcome: { kind: "dug", perTile: PER_TILE, mask, emaEv: round.emaEv } });
            round.hdAuthorities.add(authority);
            dug++;
            spent += PER_TILE * SPLIT_TILES;
          }
        } else if (!open && r.next() < 0.01) {
          round.actions.push({ rig, authority, automation: s.automation, miner: s.miner, outcome: { kind: "skipped", error: HD_ERROR_COST_GATE } });
        } else if (r.next() < 0.002) {
          round.actions.push({ rig, authority, automation: s.automation, miner: s.miner, outcome: { kind: "skipped", error: STALE_HEARTBEAT } });
        }
      }
      s.darkRounds += dark;
      s.roundsDug += dug;
      s.lamports += spent;
      if (end + 90 >= asOf) {
        s.inProgress = true;
        continue;
      }
      const tEnd = end + 30 + r.int(60);
      eventTxs.push(
        buildEventTx({
          signature: txr.signature(), slot: slotAt(tEnd, t0), blockTime: tEnd, signer: authority, programId: cfg.programId,
          events: [{ kind: "ShiftEnded", rig, shiftId: BigInt(s.shifts), darkRounds: dark, roundsDug: dug, lamports: spent, reason }],
        }),
      );
    }
  }

  // ---- dig transactions, batched per round ---------------------------------------------
  const digTxs: RawTransaction[] = [];
  for (const round of rounds) {
    for (let i = 0; i < round.actions.length; i += MAX_RIGS_PER_TX) {
      const t = round.ts - 25 - i;
      const night = Math.floor((t - t0) / DAY);
      const cranker = night >= 14 && txr.next() < 0.12 ? thirdPartyCrank : teamCrank;
      digTxs.push(
        buildDigTx({
          signature: txr.signature(), slot: slotAt(t, t0), blockTime: t, cranker, programId: cfg.programId, configPda: cfg.configPda,
          executorPda: cfg.executorPda, roundAccount: txr.address(), roundId: round.id, rigs: round.actions.slice(i, i + MAX_RIGS_PER_TX),
        }),
      );
    }
  }

  // ---- ORE ResetEvents ------------------------------------------------------------------
  const out: SimOutput["rounds"] = [];
  for (const round of rounds) {
    const hd = round.hdAuthorities.size;
    const hdOnSquare = (round.soloMask >>> round.winningSquare) & 1 ? 0n : BigInt(hd) * PER_TILE;
    const dws = round.baseTileDeployed + hdOnSquare;
    const total = round.baseTileDeployed * 25n + BigInt(hd) * PER_TILE * SPLIT_TILES;
    const admin = total / 100n;
    const vaulted = ((total - dws) * 99n) / 1000n;
    const split = ((round.soloMask >>> round.winningSquare) & 1) === 0;
    out.push({
      resetSignature: txr.signature(),
      event: {
        kind: "OreReset",
        roundId: round.id,
        startSlot: BigInt(slotAt(round.ts - 96, t0)),
        endSlot: BigInt(slotAt(round.ts - 96, t0) + 240),
        winningSquare: round.winningSquare,
        topMiner: split ? ORE_SPLIT_ADDRESS : txr.address(),
        totalMiners: BigInt(round.baseMiners + hd),
        motherlode: round.potPayout,
        totalDeployed: total,
        totalVaulted: vaulted,
        totalWinnings: total - admin - vaulted,
        totalMinted: 120_000_000_000n,
        ts: BigInt(round.ts),
        rng: txr.u64(),
        deployedWinningSquare: dws,
      },
    });
  }

  // ---- account snapshot -----------------------------------------------------------------
  const accounts: RawAccount[] = [];
  const lastNight = cfg.nights - 1;
  for (const s of rigs) {
    let streak = 0;
    for (let k = lastNight, i = s.activeNights.length - 1; i >= 0 && s.activeNights[i] === k; k--, i--) streak++;
    const acc: RigAccount = {
      kind: "Rig", bump: s.bump, authority: s.authority, p256Pubkey: "02" + Buffer.from(root.fork(`p256:${s.rig}`).bytes(32)).toString("hex"),
      attestationLevel: 1, tier: s.tier, state: s.inProgress ? 2 : 0, sgtMint: s.sgtMint, attestationExpirySlot: 0n,
      capWeek: s.capShift * 7n, capShift: s.capShift, capRound: DIG_LAMPORTS, capMaxCost: s.maxEv + 50_000_000n,
      capsExpiryTs: BigInt(asOf + 30 * DAY), planMaxEvCost: s.maxEv, planDigLamports: DIG_LAMPORTS, planSplitTiles: 15, planSoloTiles: 0,
      planLeaseRounds: 3, planFlags: s.focusOnly ? 1 : 0, planWindowStartTs: 0n, planWindowEndTs: 0n, shiftId: BigInt(s.shifts),
      hbCounter: s.darkRounds, leaseFromRound: 0n, leaseToRound: 0n, gapCount: 0, spentShift: 0n, spentWeek: 0n, weekStartTs: 0n,
      lastDugRound: 0n, shiftStartRound: 0n, shiftDarkRounds: 0n, shiftRoundsDug: 0n, lifetimeDarkRounds: s.darkRounds,
      lifetimeRoundsDug: s.roundsDug, lifetimeLamportsDeployed: s.lamports, streak, freezesLeft: 2,
      lastShiftDay: BigInt(startDayIndex + (s.activeNights.at(-1) ?? 0)),
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
    .u64(5_000n).u64(5_000n).u16(0).u8(0).u8(execBump)
    .finish();
  accounts.push({ address: cfgPda.address, owner: cfg.programId, data: config });

  const txs = [...eventTxs, ...digTxs].sort((a, b) => a.slot - b.slot || (a.transaction.signatures[0]! < b.transaction.signatures[0]! ? -1 : 1));
  return { txs, rounds: out, accounts, contextSlot: slotAt(asOf, t0), asOf, teamCrankers: [teamCrank] };
}

/** Generates and ingests a simulation into a Store bound to the `simulated` dataset. */
export async function runSimulation(ctx: IngestContext, cfg: SimConfig, log?: (m: string) => void): Promise<SimOutput> {
  if (ctx.store.dataset !== "simulated") throw new Error("refusing to write simulated data into a real dataset");
  const sim = generateSimulation(cfg);
  log?.(`simulated ${sim.txs.length} txs, ${sim.rounds.length} ORE rounds, ${sim.accounts.length} accounts`);
  for (let i = 0; i < sim.rounds.length; i += 5000) await ctx.store.upsertRounds(sim.rounds.slice(i, i + 5000), "simulated");
  for (let i = 0; i < sim.txs.length; i += 1000) await ingestRawTransactions(ctx, sim.txs.slice(i, i + 1000), "simulated");
  await ingestAccountSnapshot(ctx, sim.accounts, sim.contextSlot);
  await ctx.store.setSimAsOf(sim.asOf);
  return sim;
}
