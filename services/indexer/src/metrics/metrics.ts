/**
 * Traction metrics. Every function here is pure (rows in, numbers out) so the math is unit
 * tested, and every metric carries `evidence`: on-chain transactions/accounts a reader can open
 * to check it. u64 amounts stay bigint internally and are serialized as decimal strings.
 */
import type { DeployRow, DigRow, MetricsInput, RigRow, RoundRow } from "../model.ts";
import { HD_ERROR_COST_GATE, hdErrorName, hdErrorRange, skipLabel } from "../codec/events.ts";
import { decodeOreRound } from "../codec/round.ts";
import { oreMined, outcomeFromReset, outcomeFromRoundAccount, perSquare, type RoundOutcome } from "./oremath.ts";
import { findProgramAddress, seed, addrBytes } from "../codec/pda.ts";
import { DEFAULT_ROUND_SECONDS, ONE_ORE, ORE_SPLIT_ADDRESS } from "../constants.ts";
import { DAY, dayLabel, hourOf, median, monthOf, nightIndex } from "./time.ts";
import { computeCohorts, type CohortReport } from "./cohorts.ts";

export interface Evidence {
  label: string;
  kind: "tx" | "account";
  id: string;
}

export interface MetricsOptions {
  asOf: number;
  tzOffsetMinutes: number;
  programId: string;
  executorPda: string;
  /** Crank fee payers operated by the Heads Down team (to count third-party cranks). */
  teamCrankers?: readonly string[];
}

const sumBig = (xs: Iterable<bigint>) => {
  let s = 0n;
  for (const x of xs) s += x;
  return s;
};

function popcount(mask: number): number {
  let m = mask >>> 0;
  let n = 0;
  while (m) {
    m &= m - 1;
    n++;
  }
  return n;
}

/** Latest-first unique sample of up to `n` items. */
function sample<T>(rows: T[], n: number, key: (t: T) => string): T[] {
  const out: T[] = [];
  const seen = new Set<string>();
  for (let i = rows.length - 1; i >= 0 && out.length < n; i--) {
    const k = key(rows[i]!);
    if (seen.has(k)) continue;
    seen.add(k);
    out.push(rows[i]!);
  }
  return out;
}

// ------------------------------------------------------------------ ORE mined

/**
 * ORE credited to a Heads Down deploy by ORE's `checkpoint` (checkpoint.rs @ b92c5043):
 *  - no entropy (winning square null) or square not deployed → 0;
 *  - split round: `top_miner_reward × amount / deployed_winning_square`;
 *  - solo round: the whole `top_miner_reward` iff this authority is the round's top miner;
 *  - plus the Motherlode payout pro rata on the winning square.
 * `top_miner_reward` is the round's +1 ORE mint: min(total_minted, 1 ORE) (reset.rs).
 * The result is unrefined ORE, before the 10% refining fee charged on claim.
 */
export function oreMinedForDeploy(d: Pick<DeployRow, "authority" | "amount" | "mask">, r: RoundRow): bigint {
  return oreMined(d.authority, perSquare([d]), outcomeFromReset(r)).total;
}

/** Round outcomes: the Round-account snapshot when present (exact per square), else the ResetEvent. */
export function roundOutcomes(input: Pick<MetricsInput, "rounds" | "roundStates">): Map<bigint, RoundOutcome> {
  const out = new Map<bigint, RoundOutcome>();
  for (const r of input.rounds) out.set(r.roundId, outcomeFromReset(r));
  for (const st of input.roundStates ?? []) {
    const o = outcomeFromRoundAccount(decodeOreRound(st.data), out.get(st.roundId)?.resetSignature ?? null);
    if (o) out.set(st.roundId, o);
  }
  return out;
}

/** Heads Down deploys grouped per (authority, round): ORE credits a miner per round, not per event. */
export function groupDeploys(deploys: readonly DeployRow[]): { authority: string; roundId: bigint; deploys: DeployRow[] }[] {
  const m = new Map<string, { authority: string; roundId: bigint; deploys: DeployRow[] }>();
  for (const d of deploys) {
    const k = `${d.authority}:${d.roundId}`;
    let g = m.get(k);
    if (!g) m.set(k, (g = { authority: d.authority, roundId: d.roundId, deploys: [] }));
    g.deploys.push(d);
  }
  return [...m.values()];
}

/**
 * Open / closed / Seeker rigs from the v1.1 lifecycle events, or null when none are indexed.
 * A rig is open when its latest RigRegistered is later than its latest RigClosed. It is
 * Seeker-tier when a SeekerVerified for it follows that registration and no later
 * SeekerVerified moved the same SGT to another rig (verify_seeker downgrades the previous rig).
 */
export function rigLifecycle(input: Pick<MetricsInput, "registered" | "closedRigs" | "seekers">): { open: Set<string>; closed: Set<string>; registered: Set<string>; seeker: Set<string> } | null {
  const reg = input.registered ?? [];
  if (reg.length === 0) return null;
  const lastReg = new Map<string, number>();
  for (const r of reg) lastReg.set(r.rig, Math.max(lastReg.get(r.rig) ?? -1, r.slot));
  const lastClose = new Map<string, number>();
  for (const c of input.closedRigs ?? []) lastClose.set(c.rig, Math.max(lastClose.get(c.rig) ?? -1, c.slot));
  const open = new Set<string>();
  const closed = new Set<string>();
  for (const [rig, slot] of lastReg) {
    if ((lastClose.get(rig) ?? -1) >= slot) closed.add(rig);
    else open.add(rig);
  }
  // Latest holder of each SGT mint (by slot); it counts only while open and verified after its registration.
  const holder = new Map<string, { rig: string; slot: number }>();
  for (const sv of input.seekers) {
    const slot = sv.slot ?? -1;
    const prev = holder.get(sv.sgtMint);
    if (!prev || slot >= prev.slot) holder.set(sv.sgtMint, { rig: sv.rig, slot });
  }
  const seeker = new Set<string>();
  for (const { rig, slot } of holder.values()) if (open.has(rig) && slot >= (lastReg.get(rig) ?? Infinity)) seeker.add(rig);
  return { open, closed, registered: new Set(lastReg.keys()), seeker };
}

// ------------------------------------------------------------------ share of ORE miners

export interface RoundShare {
  roundId: bigint;
  ts: number;
  totalMiners: bigint;
  hdMiners: number;
  share: number | null;
  hdLamports: bigint;
  resetSignature: string | null;
  sampleDig: string | null;
}

/** Per ORE round: distinct Heads Down authorities that actually deployed / ResetEvent.total_miners. */
export function roundShares(deploys: DeployRow[], rounds: RoundRow[]): RoundShare[] {
  const byRound = new Map<bigint, { authorities: Set<string>; lamports: bigint; sig: string }>();
  for (const d of deploys) {
    if (d.totalSquares <= 0) continue; // ORE does not count a zero-square deploy as a miner
    let e = byRound.get(d.roundId);
    if (!e) byRound.set(d.roundId, (e = { authorities: new Set(), lamports: 0n, sig: d.signature }));
    e.authorities.add(d.authority);
    e.lamports += d.amount * BigInt(d.totalSquares);
  }
  return rounds.map((r) => {
    const e = byRound.get(r.roundId);
    const hd = e?.authorities.size ?? 0;
    return {
      roundId: r.roundId,
      ts: r.ts,
      totalMiners: r.totalMiners,
      hdMiners: hd,
      share: r.totalMiners > 0n ? hd / Number(r.totalMiners) : null,
      hdLamports: e?.lamports ?? 0n,
      resetSignature: r.resetSignature,
      sampleDig: e?.sig ?? null,
    };
  });
}

export interface HourBucket {
  hour: number;
  rounds: number;
  roundsWithHd: number;
  meanShare: number | null;
  maxShare: number | null;
  meanHdMiners: number | null;
  meanTotalMiners: number | null;
  peak: { roundId: string; share: number; hdMiners: number; totalMiners: string; resetSignature: string | null; sampleDig: string | null } | null;
}

export interface ShareByHour {
  tzOffsetMinutes: number;
  windowDays: number;
  from: number;
  to: number;
  rounds: number;
  roundsWithHd: number;
  meanShare: number | null;
  hours: HourBucket[];
}

export function shareByHour(shares: RoundShare[], tz: number, from: number, to: number, windowDays: number): ShareByHour {
  const buckets = Array.from({ length: 24 }, (_, hour) => ({ hour, list: [] as RoundShare[] }));
  const inWindow = shares.filter((s) => s.ts >= from && s.ts <= to && s.share !== null);
  for (const s of inWindow) buckets[hourOf(s.ts, tz)]!.list.push(s);
  const mean = (xs: number[]) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null);
  const hours = buckets.map(({ hour, list }): HourBucket => {
    let peak: RoundShare | null = null;
    for (const s of list) if (s.hdMiners > 0 && (peak === null || s.share! > peak.share!)) peak = s;
    return {
      hour,
      rounds: list.length,
      roundsWithHd: list.filter((s) => s.hdMiners > 0).length,
      meanShare: mean(list.map((s) => s.share!)),
      maxShare: list.length ? Math.max(...list.map((s) => s.share!)) : null,
      meanHdMiners: mean(list.map((s) => s.hdMiners)),
      meanTotalMiners: mean(list.map((s) => Number(s.totalMiners))),
      peak: peak
        ? {
            roundId: peak.roundId.toString(),
            share: peak.share!,
            hdMiners: peak.hdMiners,
            totalMiners: peak.totalMiners.toString(),
            resetSignature: peak.resetSignature,
            sampleDig: peak.sampleDig,
          }
        : null,
    };
  });
  return {
    tzOffsetMinutes: tz,
    windowDays,
    from,
    to,
    rounds: inWindow.length,
    roundsWithHd: inWindow.filter((s) => s.hdMiners > 0).length,
    meanShare: mean(inWindow.map((s) => s.share!)),
    hours,
  };
}

// ------------------------------------------------------------------ digs <-> deploys

const rigPdaCache = new Map<string, string>();
/** Rig PDA `[b"rig", authority]` (cached; deterministic). */
export function rigForAuthority(authority: string, programId: string): string {
  const k = `${programId}:${authority}`;
  let v = rigPdaCache.get(k);
  if (v === undefined) {
    v = findProgramAddress([seed("rig"), addrBytes(authority)], programId).address;
    if (rigPdaCache.size > 100_000) rigPdaCache.clear();
    rigPdaCache.set(k, v);
  }
  return v;
}

export interface PairedDig {
  dig: DigRow;
  deploy: DeployRow | null;
  authority: string | null;
}

/**
 * Pairs each RigDug with the ORE DeployEvent of the same transaction whose authority's Rig PDA
 * is that rig. (Order is not trusted; the PDA relation is.)
 */
export function pairDigs(digs: DigRow[], deploys: DeployRow[], programId: string): { paired: PairedDig[]; orphanDeploys: DeployRow[] } {
  const bySig = new Map<string, DeployRow[]>();
  for (const d of deploys) {
    const l = bySig.get(d.signature);
    if (l) l.push(d);
    else bySig.set(d.signature, [d]);
  }
  const used = new Set<DeployRow>();
  const paired = digs.map((dig): PairedDig => {
    const cands = bySig.get(dig.signature) ?? [];
    const deploy = cands.find((d) => !used.has(d) && rigForAuthority(d.authority, programId) === dig.rig) ?? null;
    if (deploy) used.add(deploy);
    return { dig, deploy, authority: deploy?.authority ?? null };
  });
  return { paired, orphanDeploys: deploys.filter((d) => !used.has(d)) };
}

// ------------------------------------------------------------------ summary

export interface Summary {
  asOf: number;
  tzOffsetMinutes: number;
  lastCompleteNight: string | null;
  rigs: {
    total: number;
    seeker: number;
    guest: number;
    closed: number;
    /**
     * "lifecycle": RigRegistered minus RigClosed events (v1.1), Seeker tier from SeekerVerified.
     * "accounts": the Rig account snapshot (no lifecycle events indexed).
     * "events": distinct rigs seen in any event (neither is available).
     */
    basis: "lifecycle" | "accounts" | "events";
    /** Rigs ever registered (lifecycle basis), else null. */
    everRegistered: number | null;
    /** The account snapshot, as an independent count of the same thing (null without a snapshot). */
    crossCheck: { accounts: number | null; accountsSeeker: number | null; matches: boolean | null };
    evidence: Evidence[];
  };
  nightlyActive: {
    lastNight: string | null;
    rigs: number;
    peak: number;
    series: { night: string; rigs: number; seeker: number }[];
    evidence: Evidence[];
  };
  darkHours: {
    hours: number;
    darkRounds: string;
    roundSeconds: number;
    roundSecondsBasis: "measured" | "fallback";
    shiftsEnded: number;
    evidence: Evidence[];
  };
  roundsDug: { rigRounds: number; distinctRounds: number; evidence: Evidence[] };
  solDeployed: {
    lamports: string;
    oreDeployEventLamports: string;
    consistent: boolean;
    evidence: Evidence[];
  };
  ore: {
    mined: { amount: string; digsPendingRound: number; basis: string; evidence: Evidence[] };
    bought: { amount: null; status: "not_shipped"; note: string };
    buried: { amount: null; status: "not_shipped"; note: string };
  };
  gate: {
    openRate: number | null;
    opened: number;
    closedByCostGate: number;
    digShareOfDarkRounds: number | null;
    evidence: Evidence[];
  };
  skips: { code: number; name: string; range: string; label: string; count: number }[];
  crankers: { distinct: number; thirdParty: number | null };
  programPaused: boolean | null;
  consistency: {
    digsWithoutDeploy: number;
    deploysWithoutDig: number;
    lamportsMismatch: number;
    roundsDugExceedsDark: number;
    seekerTierWithoutSeat: number;
    hdMinersExceedTotal: number;
  };
}

function isOpen(r: RigRow) {
  return !r.closed;
}

export function measuredRoundSeconds(rounds: RoundRow[]): number | null {
  const deltas: number[] = [];
  for (let i = 1; i < rounds.length; i++) {
    const a = rounds[i - 1]!;
    const b = rounds[i]!;
    if (b.roundId === a.roundId + 1n && b.ts > a.ts) deltas.push(b.ts - a.ts);
  }
  const m = median(deltas);
  return m !== null && m >= 30 && m <= 600 ? m : null;
}

export function computeSummary(input: MetricsInput, opts: MetricsOptions): Summary {
  const tz = opts.tzOffsetMinutes;
  const lastComplete = nightIndex(opts.asOf, tz) - 1;
  const ev = {
    tx: (label: string, id: string): Evidence => ({ label, kind: "tx", id }),
    acct: (label: string, id: string): Evidence => ({ label, kind: "account", id }),
  };

  // ---- rigs
  const openRigs = input.rigs.filter(isOpen);
  const haveAccounts = input.rigs.length > 0;
  const accountsSeeker = openRigs.filter((r) => r.tier === 1).length;
  const lifecycle = rigLifecycle(input);
  let rigs: Summary["rigs"];
  let seekerSet: Set<string>;
  if (lifecycle) {
    const matches = haveAccounts ? openRigs.length === lifecycle.open.size && accountsSeeker === lifecycle.seeker.size : null;
    rigs = {
      total: lifecycle.open.size,
      seeker: lifecycle.seeker.size,
      guest: lifecycle.open.size - lifecycle.seeker.size,
      closed: lifecycle.closed.size,
      basis: "lifecycle",
      everRegistered: lifecycle.registered.size,
      crossCheck: { accounts: haveAccounts ? openRigs.length : null, accountsSeeker: haveAccounts ? accountsSeeker : null, matches },
      evidence: [
        ...sample(input.registered ?? [], 2, (r) => r.signature).map((r) => ev.tx("RigRegistered", r.signature)),
        ...sample(input.closedRigs ?? [], 1, (r) => r.signature).map((r) => ev.tx("RigClosed", r.signature)),
        ...sample(input.seekers, 1, (r) => r.signature).map((r) => ev.tx("SeekerVerified", r.signature)),
        ...(haveAccounts ? [ev.acct("heads_down program (all Rig accounts)", opts.programId)] : []),
      ],
    };
    seekerSet = lifecycle.seeker;
  } else if (haveAccounts) {
    rigs = {
      total: openRigs.length,
      seeker: accountsSeeker,
      guest: openRigs.length - accountsSeeker,
      closed: input.rigs.length - openRigs.length,
      basis: "accounts",
      everRegistered: null,
      crossCheck: { accounts: openRigs.length, accountsSeeker, matches: null },
      evidence: [
        ev.acct("heads_down program (all Rig accounts)", opts.programId),
        ...sample(openRigs.filter((r) => r.tier === 1), 2, (r) => r.address).map((r) => ev.acct("Seeker-tier Rig account", r.address)),
        ...sample(openRigs.filter((r) => r.tier === 0), 1, (r) => r.address).map((r) => ev.acct("Guest Rig account", r.address)),
      ],
    };
    seekerSet = new Set(openRigs.filter((r) => r.tier === 1).map((r) => r.address));
  } else {
    const all = new Set<string>([...input.arms, ...input.digs, ...input.ends, ...input.seekers].map((e) => e.rig));
    const seekerRigs = new Set(input.seekers.map((s) => s.rig));
    rigs = {
      total: all.size,
      seeker: seekerRigs.size,
      guest: all.size - seekerRigs.size,
      closed: 0,
      basis: "events",
      everRegistered: null,
      crossCheck: { accounts: null, accountsSeeker: null, matches: null },
      evidence: sample(input.seekers, 2, (s) => s.signature).map((s) => ev.tx("SeekerVerified", s.signature)),
    };
    seekerSet = seekerRigs;
  }

  // ---- nightly active (and per-night seeker split)
  const perNight = new Map<number, Set<string>>();
  const mark = (rig: string, t: number | null) => {
    if (t === null) return;
    const n = nightIndex(t, tz);
    let s = perNight.get(n);
    if (!s) perNight.set(n, (s = new Set()));
    s.add(rig);
  };
  for (const a of input.arms) mark(a.rig, a.blockTime);
  for (const d of input.digs) mark(d.rig, d.blockTime);
  for (const e of input.ends) if (e.darkRounds > 0n) mark(e.rig, e.blockTime);
  const series: Summary["nightlyActive"]["series"] = [];
  for (let n = lastComplete - 29; n <= lastComplete; n++) {
    const s = perNight.get(n);
    series.push({ night: dayLabel(n), rigs: s?.size ?? 0, seeker: s ? [...s].filter((r) => seekerSet.has(r)).length : 0 });
  }
  const lastNightDigs = input.digs.filter((d) => d.blockTime !== null && nightIndex(d.blockTime, tz) === lastComplete);

  // ---- dark hours
  const darkRounds = sumBig(input.ends.map((e) => e.darkRounds));
  const measured = measuredRoundSeconds(input.rounds);
  const roundSeconds = measured ?? DEFAULT_ROUND_SECONDS;

  // ---- digs / SOL
  const { paired, orphanDeploys } = pairDigs(input.digs, input.deploys, opts.programId);
  const digLamports = sumBig(input.digs.map((d) => d.lamports));
  const deployLamports = sumBig(input.deploys.map((d) => d.amount * BigInt(d.totalSquares)));
  const lamportsMismatch = paired.filter((p) => p.deploy && p.deploy.amount * BigInt(p.deploy.totalSquares) !== p.dig.lamports).length;

  // ---- ORE mined: per (authority, round), ORE's checkpoint rules on that round's outcome
  const outcomes = roundOutcomes(input);
  let mined = 0n;
  let pending = 0;
  let minedSample: { dig: string; reset: string | null } | null = null;
  for (const g of groupDeploys(input.deploys)) {
    const o = outcomes.get(g.roundId);
    if (!o) {
      pending += g.deploys.length;
      continue;
    }
    const m = oreMined(g.authority, perSquare(g.deploys), o).total;
    mined += m;
    if (m > 0n) minedSample = { dig: g.deploys[g.deploys.length - 1]!.signature, reset: o.resetSignature };
  }

  // ---- gate
  const costGateSkips = input.skips.filter((s) => s.errorCode === HD_ERROR_COST_GATE);
  const opened = input.digs.length;
  const evaluations = opened + costGateSkips.length;
  const dugInShifts = sumBig(input.ends.map((e) => e.roundsDug));

  // ---- skips by error
  const skipCounts = new Map<number, number>();
  for (const s of input.skips) skipCounts.set(s.errorCode, (skipCounts.get(s.errorCode) ?? 0) + 1);

  // ---- crankers
  const payers = new Set(input.digs.map((d) => d.feePayer));
  const team = opts.teamCrankers && opts.teamCrankers.length > 0 ? new Set(opts.teamCrankers) : null;

  // ---- consistency
  const seatRigs = new Set(input.seats.filter((s) => !s.closed).map((s) => s.rig));
  const shares = roundShares(input.deploys, input.rounds);

  return {
    asOf: opts.asOf,
    tzOffsetMinutes: tz,
    lastCompleteNight: lastComplete >= 0 ? dayLabel(lastComplete) : null,
    rigs,
    nightlyActive: {
      lastNight: lastComplete >= 0 ? dayLabel(lastComplete) : null,
      rigs: series[series.length - 1]?.rigs ?? 0,
      peak: Math.max(0, ...series.map((s) => s.rigs)),
      series,
      evidence: sample(lastNightDigs, 3, (d) => d.signature).map((d) => ev.tx("dig last night", d.signature)),
    },
    darkHours: {
      hours: (Number(darkRounds) * roundSeconds) / 3600,
      darkRounds: darkRounds.toString(),
      roundSeconds,
      roundSecondsBasis: measured !== null ? "measured" : "fallback",
      shiftsEnded: input.ends.length,
      evidence: sample(input.ends, 3, (e) => e.signature).map((e) => ev.tx("ShiftEnded", e.signature)),
    },
    roundsDug: {
      rigRounds: input.digs.length,
      distinctRounds: new Set(input.digs.map((d) => d.roundId)).size,
      evidence: [
        ev.acct("Executor PDA (every dig lists it)", opts.executorPda),
        ...sample(input.digs, 2, (d) => d.signature).map((d) => ev.tx("RigDug", d.signature)),
      ],
    },
    solDeployed: {
      lamports: digLamports.toString(),
      oreDeployEventLamports: deployLamports.toString(),
      consistent: digLamports === deployLamports,
      evidence: [
        ev.acct("Executor PDA: signer of every Heads Down ORE DeployEvent", opts.executorPda),
        ...sample(input.deploys, 2, (d) => d.signature).map((d) => ev.tx("ORE DeployEvent (signer = Executor PDA)", d.signature)),
      ],
    },
    ore: {
      mined: {
        amount: mined.toString(),
        digsPendingRound: pending,
        basis: "Computed per dig from ORE's ResetEvent for that round (checkpoint.rs rules); unrefined, before ORE's 10% refining fee on claim.",
        evidence: minedSample
          ? [ev.tx("dig that mined ORE", minedSample.dig), ...(minedSample.reset ? [ev.tx("ORE reset of that round", minedSample.reset)] : [])]
          : [],
      },
      bought: { amount: null, status: "not_shipped", note: "Clock-out buy leg not shipped yet. Placeholder, not zero." },
      buried: { amount: null, status: "not_shipped", note: "Bury auction not shipped yet. Placeholder, not zero." },
    },
    gate: {
      openRate: evaluations > 0 ? opened / evaluations : null,
      opened,
      closedByCostGate: costGateSkips.length,
      digShareOfDarkRounds: darkRounds > 0n ? Number(dugInShifts) / Number(darkRounds) : null,
      evidence: [
        ...sample(input.digs, 1, (d) => d.signature).map((d) => ev.tx("gate open: RigDug", d.signature)),
        ...sample(costGateSkips, 1, (s) => s.signature).map((s) => ev.tx("gate closed: RigSkipped(CostGate)", s.signature)),
      ],
    },
    skips: [...skipCounts.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([code, count]) => ({ code, name: hdErrorName(code), range: hdErrorRange(code), label: skipLabel(code), count })),
    crankers: { distinct: payers.size, thirdParty: team ? [...payers].filter((p) => !team.has(p)).length : null },
    programPaused: input.config ? input.config.paused : null,
    consistency: {
      digsWithoutDeploy: paired.filter((p) => p.deploy === null).length,
      deploysWithoutDig: orphanDeploys.length,
      lamportsMismatch,
      roundsDugExceedsDark: input.ends.filter((e) => e.roundsDug > e.darkRounds).length,
      seekerTierWithoutSeat: openRigs.filter((r) => r.tier === 1 && !seatRigs.has(r.address)).length,
      hdMinersExceedTotal: shares.filter((s) => BigInt(s.hdMiners) > s.totalMiners).length,
    },
  };
}

// ------------------------------------------------------------------ recent digs feed

export interface FeedItem {
  signature: string;
  blockTime: number | null;
  rig: string;
  authority: string | null;
  tier: number | null;
  roundId: string;
  lamports: string;
  squares: number;
  emaEv: string;
}

export function recentDigs(input: MetricsInput, programId: string, limit: number): FeedItem[] {
  const tiers = new Map(input.rigs.map((r) => [r.address, r.tier]));
  const last = input.digs.slice(-limit * 4); // pairing only needs the tail
  const lastSigs = new Set(last.map((d) => d.signature));
  const { paired } = pairDigs(last, input.deploys.filter((d) => lastSigs.has(d.signature)), programId);
  return paired
    .slice(-limit)
    .reverse()
    .map((p) => ({
      signature: p.dig.signature,
      blockTime: p.dig.blockTime,
      rig: p.dig.rig,
      authority: p.authority,
      tier: tiers.get(p.dig.rig) ?? null,
      roundId: p.dig.roundId.toString(),
      lamports: p.dig.lamports.toString(),
      squares: popcount(p.dig.mask),
      emaEv: p.dig.emaEv.toString(),
    }));
}

// ------------------------------------------------------------------ cohorts (re-export)

export function cohortReport(input: MetricsInput, opts: Pick<MetricsOptions, "asOf" | "tzOffsetMinutes">): CohortReport {
  return computeCohorts(input, opts.asOf, opts.tzOffsetMinutes);
}

// ------------------------------------------------------------------ milestones

export interface MilestoneMetric {
  key: string;
  label: string;
  value: number | null;
  target: number;
  stretch: number | null;
  unit: "rigs" | "share";
  note?: string;
}

export interface Milestones {
  tzOffsetMinutes: number;
  shareWindow: { fromHour: number; toHour: number; nights: number };
  milestones: { id: "M1" | "M2" | "M3"; title: string; deadline: string; metrics: MilestoneMetric[]; manual: string[] }[];
}

/** docs/ORE.md section 9. Values are measured; "manual" items cannot be measured from chain data. */
export function computeMilestones(input: MetricsInput, summary: Summary, opts: MetricsOptions): Milestones {
  const tz = opts.tzOffsetMinutes;
  const shares = roundShares(input.deploys, input.rounds);
  const nights = 7;
  const lastComplete = nightIndex(opts.asOf, tz) - 1;
  const windowShares = shares.filter((s) => {
    const h = hourOf(s.ts, tz);
    const n = nightIndex(s.ts, tz);
    return h >= 0 && h < 6 && n > lastComplete - nights && n <= lastComplete && s.share !== null;
  });
  const nightShare = windowShares.length ? windowShares.reduce((a, s) => a + s.share!, 0) / windowShares.length : null;
  return {
    tzOffsetMinutes: tz,
    shareWindow: { fromHour: 0, toHour: 6, nights },
    milestones: [
      {
        id: "M1",
        title: "Launch",
        deadline: "results + 30 days",
        metrics: [
          { key: "rigs", label: "Registered rigs", value: summary.rigs.total, target: 250, stretch: null, unit: "rigs" },
          { key: "seeker_rigs", label: "SGT-verified rigs", value: summary.rigs.seeker, target: 50, stretch: 250, unit: "rigs" },
        ],
        manual: ["Live on the Solana dApp Store", "Trustless PDA executor on mainnet", "THREAT_MODEL and audit report public"],
      },
      {
        id: "M2",
        title: "Adoption",
        deadline: "results + 90 days",
        metrics: [
          { key: "rigs", label: "Rigs", value: summary.rigs.total, target: 1000, stretch: null, unit: "rigs" },
          { key: "nightly_active", label: "Nightly active rigs (last complete night)", value: summary.nightlyActive.rigs, target: 300, stretch: null, unit: "rigs" },
          {
            key: "night_share",
            label: "Share of unique ORE miners per round, 00:00-06:00 (mean, last 7 nights)",
            value: nightShare,
            target: 0.15,
            stretch: 0.25,
            unit: "share",
            note: "Measured in one fixed time zone. There is no location data, so regions are time-zone windows, not places.",
          },
        ],
        manual: [],
      },
      {
        id: "M3",
        title: "Durability",
        deadline: "results + 180 days",
        metrics: [],
        manual: [
          "Immutable v1 (upgrade authority revoked)",
          `Third-party crank landing digs (distinct dig fee payers: ${summary.crankers.distinct})`,
          "Cumulative ORE mined, bought and buried published monthly (see monthly.csv)",
        ],
      },
    ],
  };
}

// ------------------------------------------------------------------ monthly report rows

export interface MonthlyRow {
  month: string;
  rigsFirstSeen: number;
  rigsCumulative: number;
  seekerVerifiedCumulative: number;
  nightlyActiveAvg: number;
  nightlyActivePeak: number;
  rigRoundsDug: number;
  lamportsDeployed: bigint;
  oreMined: bigint;
  hdShareMean: number | null;
}

export function monthlyReport(input: MetricsInput, opts: MetricsOptions): MonthlyRow[] {
  const tz = opts.tzOffsetMinutes;
  const firstSeen = new Map<string, number>();
  const see = (rig: string, t: number | null) => {
    if (t === null) return;
    const p = firstSeen.get(rig);
    if (p === undefined || t < p) firstSeen.set(rig, t);
  };
  for (const e of [...input.arms, ...input.digs, ...input.ends, ...input.seekers]) see(e.rig, e.blockTime);
  const seekerFirst = new Map<string, number>();
  for (const s of input.seekers) if (s.blockTime !== null && !seekerFirst.has(s.rig)) seekerFirst.set(s.rig, s.blockTime);

  const months = new Set<string>();
  for (const t of firstSeen.values()) months.add(monthOf(t));
  for (const d of input.digs) if (d.blockTime !== null) months.add(monthOf(d.blockTime));
  const sorted = [...months].sort();

  const perNight = new Map<number, Set<string>>();
  for (const e of [...input.arms, ...input.digs]) {
    if (e.blockTime === null) continue;
    const n = nightIndex(e.blockTime, tz);
    let s = perNight.get(n);
    if (!s) perNight.set(n, (s = new Set()));
    s.add(e.rig);
  }
  const outcomes = roundOutcomes(input);
  const shares = roundShares(input.deploys, input.rounds);

  return sorted.map((month) => {
    const inMonth = (t: number | null) => t !== null && monthOf(t) === month;
    const upTo = (t: number) => monthOf(t) <= month;
    const nightCounts = [...perNight.entries()].filter(([n]) => dayLabel(n).startsWith(month)).map(([, s]) => s.size);
    const deploys = input.deploys.filter((d) => inMonth(d.blockTime ?? d.ts));
    const monthShares = shares.filter((s) => inMonth(s.ts) && s.share !== null);
    return {
      month,
      rigsFirstSeen: [...firstSeen.values()].filter((t) => monthOf(t) === month).length,
      rigsCumulative: [...firstSeen.values()].filter(upTo).length,
      seekerVerifiedCumulative: [...seekerFirst.values()].filter(upTo).length,
      nightlyActiveAvg: nightCounts.length ? nightCounts.reduce((a, b) => a + b, 0) / nightCounts.length : 0,
      nightlyActivePeak: nightCounts.length ? Math.max(...nightCounts) : 0,
      rigRoundsDug: input.digs.filter((d) => inMonth(d.blockTime)).length,
      lamportsDeployed: sumBig(input.digs.filter((d) => inMonth(d.blockTime)).map((d) => d.lamports)),
      oreMined: sumBig(
        groupDeploys(deploys).map((g) => {
          const o = outcomes.get(g.roundId);
          return o ? oreMined(g.authority, perSquare(g.deploys), o).total : 0n;
        }),
      ),
      hdShareMean: monthShares.length ? monthShares.reduce((a, s) => a + s.share!, 0) / monthShares.length : null,
    };
  });
}

export { DAY };
