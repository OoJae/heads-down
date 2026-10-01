import { describe, expect, it } from "vitest";
import { encodeBase58 } from "../src/codec/base58.ts";
import { EXECUTOR_PDA, HEADS_DOWN_PROGRAM_ID, ONE_ORE, ORE_SPLIT_ADDRESS } from "../src/constants.ts";
import { computeCohorts } from "../src/metrics/cohorts.ts";
import {
  computeMilestones,
  computeSummary,
  measuredRoundSeconds,
  monthlyReport,
  oreMinedForDeploy,
  pairDigs,
  recentDigs,
  rigForAuthority,
  roundShares,
  shareByHour,
} from "../src/metrics/metrics.ts";
import { dayLabel, hourOf, nightIndex, nightOf } from "../src/metrics/time.ts";
import type { ArmRow, DeployRow, DigRow, EndRow, MetricsInput, RoundRow, SkipRow } from "../src/model.ts";

const P = HEADS_DOWN_PROGRAM_ID;
const WAT = 60;
const addr = (n: number) => encodeBase58(Uint8Array.from({ length: 32 }, (_, i) => (i * 7 + n) & 0xff));
const sig = (n: number) => encodeBase58(Uint8Array.from({ length: 64 }, (_, i) => (i * 13 + n + 1) & 0xff));
/** 2026-10-01 00:00:00 UTC */
const T0 = Date.UTC(2026, 9, 1) / 1000;
const H = 3600;
const D = 86_400;

const empty = (): MetricsInput => ({ digs: [], skips: [], arms: [], ends: [], seekers: [], deploys: [], rounds: [], rigs: [], seats: [], config: null });

function round(id: number, over: Partial<RoundRow> = {}): RoundRow {
  return {
    roundId: BigInt(id),
    ts: T0 + id * 78,
    winningSquare: 3,
    topMiner: ORE_SPLIT_ADDRESS,
    totalMiners: 100n,
    motherlode: 0n,
    totalDeployed: 10_000_000_000n,
    totalMinted: 120_000_000_000n,
    deployedWinningSquare: 400_000_000n,
    resetSignature: sig(9000 + id),
    ...over,
  };
}

describe("night bucketing", () => {
  it("uses a local-noon boundary", () => {
    // 2026-10-01 10:59 UTC = 11:59 WAT → still the night of Sep 30
    expect(nightOf(T0 + 10 * H + 59 * 60, WAT)).toBe("2026-09-30");
    // 11:00 UTC = 12:00 WAT → night of Oct 1
    expect(nightOf(T0 + 11 * H, WAT)).toBe("2026-10-01");
    // 23:30 WAT Oct 1 and 06:10 WAT Oct 2 are the same night
    expect(nightOf(T0 + 22 * H + 30 * 60, WAT)).toBe(nightOf(T0 + D + 5 * H + 10 * 60, WAT));
  });

  it("hours honour negative and positive offsets", () => {
    expect(hourOf(T0, 0)).toBe(0);
    expect(hourOf(T0, WAT)).toBe(1);
    expect(hourOf(T0, -180)).toBe(21);
    expect(hourOf(T0, 540)).toBe(9);
  });
});

describe("retention cohorts", () => {
  const n0 = nightIndex(T0 + 22 * H, WAT); // night of Oct 1 (22:00 UTC = 23:00 WAT)
  const at = (night: number) => (night * D) + 11 * H + 11 * H; // 22:00 UTC on that day label
  const arm = (rig: string, night: number): ArmRow => ({ signature: sig(night), blockTime: at(night), rig, shiftId: 1n });
  const dig = (rig: string, night: number): DigRow => ({ signature: sig(night + 50), idx: 0, slot: 1, blockTime: at(night) + 3 * H, rig, roundId: 1n, lamports: 1n, mask: 1, emaEv: 1n, feePayer: addr(9) });
  const end = (rig: string, night: number, dark: bigint): EndRow => ({ signature: sig(night + 90), blockTime: at(night) + 8 * H, rig, shiftId: 1n, darkRounds: dark, roundsDug: 0n, lamports: 0n, reason: 0 });

  it("computes exact-day D1/D7/D14 with immature cells left null", () => {
    const [A, B, C, E] = [addr(1), addr(2), addr(3), addr(4)];
    const input = {
      arms: [arm(A, n0), arm(B, n0), arm(C, n0), arm(A, n0 + 7), arm(E, n0 + 1)],
      digs: [dig(A, n0 + 1)],
      ends: [end(B, n0 + 1, 5n), end(C, n0 + 1, 0n) /* zero dark rounds: not active */],
    };
    const asOf = at(n0 + 9); // nights up to n0+8 complete
    const rep = computeCohorts(input, asOf, WAT);
    expect(rep.lastCompleteNight).toBe(dayLabel(n0 + 8));
    expect(rep.cohorts.map((c) => [c.cohort, c.size])).toEqual([
      [dayLabel(n0), 3],
      [dayLabel(n0 + 1), 1],
    ]);
    const c0 = rep.cohorts[0]!.cells;
    expect(c0[0]).toEqual({ day: 1, retained: 2, rate: 2 / 3, mature: true }); // A (dig) and B (dark end)
    expect(c0[1]).toEqual({ day: 7, retained: 1, rate: 1 / 3, mature: true }); // A re-armed
    expect(c0[2]).toEqual({ day: 14, retained: null, rate: null, mature: false });
    const c1 = rep.cohorts[1]!.cells;
    expect(c1[0]).toEqual({ day: 1, retained: 0, rate: 0, mature: true });
    expect(c1[1]!.mature).toBe(true); // n0+1+7 = n0+8 is complete
    // size-weighted average over mature cohorts: D1 = (2 + 0) / (3 + 1)
    expect(rep.average[0]).toEqual({ day: 1, rate: 0.5, cohorts: 2, rigs: 4 });
    expect(rep.average[2]).toEqual({ day: 14, rate: null, cohorts: 0, rigs: 0 });
  });

  it("uses each rig's FIRST shift as its cohort, regardless of event order", () => {
    const A = addr(1);
    const rep = computeCohorts({ arms: [arm(A, n0 + 3), arm(A, n0)], digs: [], ends: [] }, at(n0 + 20), WAT);
    expect(rep.cohorts.map((c) => c.cohort)).toEqual([dayLabel(n0)]);
    expect(rep.cohorts[0]!.cells[0]!.retained).toBe(0);
  });

  it("an empty dataset has no cohorts and null averages", () => {
    const rep = computeCohorts({ arms: [], digs: [], ends: [] }, T0, WAT);
    expect(rep.cohorts).toEqual([]);
    expect(rep.average.every((a) => a.rate === null)).toBe(true);
  });
});

describe("ORE mined per deploy (checkpoint.rs rules)", () => {
  const dep = (over: Partial<DeployRow> = {}): DeployRow => ({ signature: sig(1), idx: 0, blockTime: T0, authority: addr(11), amount: 66_666n, mask: 1 << 3, roundId: 1n, totalSquares: 1, ts: T0, ...over });

  it("split round: pro rata of the +1 ORE on the winning square", () => {
    // floor(1e11 * 66_666 / 400_000_000) = 16_666_500
    expect(oreMinedForDeploy(dep(), round(1))).toBe(16_666_500n);
  });

  it("losing square mines nothing", () => {
    expect(oreMinedForDeploy(dep({ mask: 1 << 4 }), round(1))).toBe(0n);
  });

  it("solo round: all or nothing on top_miner", () => {
    expect(oreMinedForDeploy(dep(), round(1, { topMiner: addr(11) }))).toBe(ONE_ORE);
    expect(oreMinedForDeploy(dep(), round(1, { topMiner: addr(12) }))).toBe(0n);
  });

  it("adds the Motherlode pro rata", () => {
    const ml = 200n * ONE_ORE;
    // 16_666_500 + floor(2e13 * 66_666 / 4e8) = 16_666_500 + 3_333_300_000
    expect(oreMinedForDeploy(dep(), round(1, { motherlode: ml }))).toBe(16_666_500n + 3_333_300_000n);
  });

  it("no entropy → nothing mined (refund round); caps the reward at the actual mint", () => {
    expect(oreMinedForDeploy(dep(), round(1, { winningSquare: null }))).toBe(0n);
    expect(oreMinedForDeploy(dep(), round(1, { totalMinted: ONE_ORE / 2n, topMiner: addr(11) }))).toBe(ONE_ORE / 2n);
    expect(oreMinedForDeploy(dep(), round(1, { deployedWinningSquare: 0n }))).toBe(0n);
  });
});

describe("share of ORE miners", () => {
  it("counts distinct authorities per round, skips zero-square deploys, buckets by local hour", () => {
    const r1 = round(1, { ts: T0 + 30, totalMiners: 100n });
    const r2 = round(2, { ts: T0 + 100, totalMiners: 200n });
    const r3 = round(3, { ts: T0 + 200, totalMiners: 0n }); // corrupt/empty round: excluded
    const d = (n: number, authority: string, round: number, squares = 15): DeployRow => ({
      signature: sig(n), idx: 0, blockTime: T0, authority, amount: 1000n, mask: squares ? 0x7fff : 0, roundId: BigInt(round), totalSquares: squares, ts: T0,
    });
    const deploys = [
      ...Array.from({ length: 10 }, (_, i) => d(i, addr(100 + i), 1)),
      d(50, addr(100), 1), // same authority twice in round 1
      d(51, addr(200), 2, 0), // zero squares: not a miner
    ];
    const shares = roundShares(deploys, [r1, r2, r3]);
    expect(shares.map((s) => [s.hdMiners, s.share])).toEqual([
      [10, 0.1],
      [0, 0],
      [0, null],
    ]);
    const byHour = shareByHour(shares, 0, T0, T0 + D, 1);
    const h0 = byHour.hours[0]!;
    expect(h0.rounds).toBe(2);
    expect(h0.roundsWithHd).toBe(1);
    expect(h0.meanShare).toBeCloseTo(0.05);
    expect(h0.maxShare).toBe(0.1);
    expect(h0.peak?.roundId).toBe("1");
    expect(h0.peak?.resetSignature).toBe(sig(9001));
    expect(byHour.hours[1]!.rounds).toBe(0);
    expect(byHour.hours[1]!.meanShare).toBeNull();
    // Same rounds seen from UTC+01:00 land in hour 1.
    expect(shareByHour(shares, 60, T0, T0 + D, 1).hours[1]!.rounds).toBe(2);
  });
});

describe("pairing digs with ORE DeployEvents", () => {
  it("pairs by Rig PDA of the deploy authority, not by order", () => {
    const [a1, a2] = [addr(11), addr(12)];
    const [r1, r2] = [rigForAuthority(a1, P), rigForAuthority(a2, P)];
    const digs: DigRow[] = [
      { signature: sig(1), idx: 0, slot: 1, blockTime: T0, rig: r1, roundId: 5n, lamports: 150n, mask: 0x7fff, emaEv: 1n, feePayer: addr(9) },
      { signature: sig(1), idx: 1, slot: 1, blockTime: T0, rig: r2, roundId: 5n, lamports: 30n, mask: 0x7, emaEv: 1n, feePayer: addr(9) },
      { signature: sig(2), idx: 0, slot: 2, blockTime: T0, rig: r1, roundId: 6n, lamports: 150n, mask: 0x7fff, emaEv: 1n, feePayer: addr(9) },
    ];
    const deploys: DeployRow[] = [
      { signature: sig(1), idx: 0, blockTime: T0, authority: a2, amount: 10n, mask: 0x7, roundId: 5n, totalSquares: 3, ts: T0 },
      { signature: sig(1), idx: 1, blockTime: T0, authority: a1, amount: 10n, mask: 0x7fff, roundId: 5n, totalSquares: 15, ts: T0 },
      { signature: sig(3), idx: 0, blockTime: T0, authority: a1, amount: 1n, mask: 1, roundId: 7n, totalSquares: 1, ts: T0 },
    ];
    const { paired, orphanDeploys } = pairDigs(digs, deploys, P);
    expect(paired.map((p) => p.authority)).toEqual([a1, a2, null]);
    expect(orphanDeploys.map((d) => d.signature)).toEqual([sig(3)]);
  });
});

describe("summary", () => {
  function scenario(): MetricsInput {
    const auth = [addr(11), addr(12), addr(13)];
    const rigs = auth.map((a) => rigForAuthority(a, P));
    const night = T0 + 22 * H; // 23:00 WAT Oct 1
    const input = empty();
    input.rigs = rigs.map((address, i) => ({
      address, authority: auth[i]!, tier: i === 0 ? 1 : 0, state: 0, sgtMint: i === 0 ? addr(70) : null,
      lifetimeDarkRounds: 0n, lifetimeRoundsDug: 0n, lifetimeLamportsDeployed: 0n, streak: 0, closed: i === 2,
    }));
    input.seats = [{ address: addr(80), sgtMint: addr(70), rig: rigs[0]!, memberNumber: 7n, closed: false }];
    input.arms = rigs.slice(0, 2).map((rig, i) => ({ signature: sig(100 + i), blockTime: night, rig, shiftId: 1n }));
    input.rounds = [round(10, { ts: night + 60 }), round(11, { ts: night + 138 }), round(12, { ts: night + 216 })];
    input.digs = [
      { signature: sig(200), idx: 0, slot: 1, blockTime: night + 100, rig: rigs[0]!, roundId: 10n, lamports: 999_990n, mask: 0x7fff, emaEv: 5n, feePayer: addr(9) },
      { signature: sig(200), idx: 1, slot: 1, blockTime: night + 100, rig: rigs[1]!, roundId: 10n, lamports: 999_990n, mask: 0x7fff, emaEv: 5n, feePayer: addr(9) },
      { signature: sig(201), idx: 0, slot: 2, blockTime: night + 170, rig: rigs[0]!, roundId: 11n, lamports: 999_990n, mask: 0x7fff, emaEv: 5n, feePayer: addr(8) },
    ];
    input.deploys = [
      { signature: sig(200), idx: 0, blockTime: night + 100, authority: auth[0]!, amount: 66_666n, mask: 0x7fff, roundId: 10n, totalSquares: 15, ts: night + 100 },
      { signature: sig(200), idx: 1, blockTime: night + 100, authority: auth[1]!, amount: 66_666n, mask: 0x7fff, roundId: 10n, totalSquares: 15, ts: night + 100 },
      { signature: sig(201), idx: 0, blockTime: night + 170, authority: auth[0]!, amount: 66_666n, mask: 0x7fff, roundId: 11n, totalSquares: 15, ts: night + 170 },
    ];
    input.skips = [
      { signature: sig(202), blockTime: night + 250, rig: rigs[0]!, roundId: 12n, errorCode: 1 } satisfies SkipRow,
      { signature: sig(203), blockTime: night + 250, rig: rigs[1]!, roundId: 12n, errorCode: 7 } satisfies SkipRow,
    ];
    input.ends = [
      { signature: sig(300), blockTime: night + 8 * H, rig: rigs[0]!, shiftId: 1n, darkRounds: 360n, roundsDug: 2n, lamports: 1_999_980n, reason: 0 },
      { signature: sig(301), blockTime: night + 8 * H, rig: rigs[1]!, shiftId: 1n, darkRounds: 40n, roundsDug: 1n, lamports: 999_990n, reason: 1 },
    ];
    return input;
  }

  const opts = { asOf: T0 + 3 * D, tzOffsetMinutes: WAT, programId: P, executorPda: EXECUTOR_PDA, teamCrankers: [addr(9)] };

  it("computes the headline numbers", () => {
    const s = computeSummary(scenario(), opts);
    expect(s.rigs).toMatchObject({ total: 2, seeker: 1, guest: 1, closed: 1, basis: "accounts" });
    expect(s.roundsDug).toMatchObject({ rigRounds: 3, distinctRounds: 2 });
    expect(s.solDeployed.lamports).toBe("2999970");
    expect(s.solDeployed.oreDeployEventLamports).toBe("2999970");
    expect(s.solDeployed.consistent).toBe(true);
    // 400 dark rounds × 78 s (median delta of rounds 10→11→12) / 3600
    expect(s.darkHours.roundSeconds).toBe(78);
    expect(s.darkHours.roundSecondsBasis).toBe("measured");
    expect(s.darkHours.hours).toBeCloseTo((400 * 78) / 3600);
    // gate: 3 digs vs 1 CostGate skip (StaleHeartbeat is not a gate decision)
    expect(s.gate.openRate).toBe(0.75);
    expect(s.gate.closedByCostGate).toBe(1);
    expect(s.gate.digShareOfDarkRounds).toBeCloseTo(3 / 400);
    expect(s.skips).toEqual([
      { code: 1, name: "CostGate", range: "heads_down", label: "price gate closed: mining cost more than the plan allows", count: 1 },
      { code: 7, name: "StaleHeartbeat", range: "heads_down", label: "replay rejected: heartbeat counter not newer", count: 1 },
    ]);
    expect(s.crankers).toEqual({ distinct: 2, thirdParty: 1 });
    expect(s.consistency).toEqual({
      digsWithoutDeploy: 0, deploysWithoutDig: 0, lamportsMismatch: 0, roundsDugExceedsDark: 0, seekerTierWithoutSeat: 0, hdMinersExceedTotal: 0,
    });
    // ORE mined: rounds 10 and 11 are split rounds won on square 3, which every dig covered.
    const perDig = (ONE_ORE * 66_666n) / 400_000_000n;
    expect(s.ore.mined.amount).toBe((perDig * 3n).toString());
    expect(s.ore.bought).toMatchObject({ amount: null, status: "not_shipped" });
    expect(s.ore.buried).toMatchObject({ amount: null, status: "not_shipped" });
    // nightly actives: the night of Oct 1 had 2 rigs
    const oct1 = s.nightlyActive.series.find((x) => x.night === "2026-10-01");
    expect(oct1).toEqual({ night: "2026-10-01", rigs: 2, seeker: 1 });
    expect(s.nightlyActive.lastNight).toBe("2026-10-02");
    expect(s.nightlyActive.rigs).toBe(0);
    expect(s.nightlyActive.peak).toBe(2);
    // every metric carries evidence
    expect(s.rigs.evidence.length).toBeGreaterThan(0);
    expect(s.solDeployed.evidence[0]).toEqual({ label: expect.any(String), kind: "account", id: EXECUTOR_PDA });
  });

  it("flags inconsistencies instead of hiding them", () => {
    const input = scenario();
    input.deploys = input.deploys.slice(1); // lose one DeployEvent
    input.digs[1] = { ...input.digs[1]!, lamports: 1n }; // lamports disagree with its deploy
    input.ends[0] = { ...input.ends[0]!, roundsDug: 999n };
    input.rigs[1] = { ...input.rigs[1]!, tier: 1 }; // seeker tier without a seat
    const s = computeSummary(input, opts);
    expect(s.solDeployed.consistent).toBe(false);
    expect(s.consistency.digsWithoutDeploy).toBe(1);
    expect(s.consistency.lamportsMismatch).toBe(1);
    expect(s.consistency.roundsDugExceedsDark).toBe(1);
    expect(s.consistency.seekerTierWithoutSeat).toBe(1);
  });

  it("an empty dataset produces zeros and nulls, never NaN", () => {
    const s = computeSummary(empty(), opts);
    expect(JSON.stringify(s)).not.toMatch(/NaN|Infinity/);
    expect(s.gate.openRate).toBeNull();
    expect(s.darkHours.roundSecondsBasis).toBe("fallback");
    expect(s.rigs.basis).toBe("events");
  });

  it("recent digs pair authority and tier, newest first", () => {
    const feed = recentDigs(scenario(), P, 2);
    expect(feed.map((f) => f.signature)).toEqual([sig(201), sig(200)]);
    expect(feed[0]).toMatchObject({ authority: addr(11), tier: 1, squares: 15, lamports: "999990", roundId: "11" });
  });

  it("milestones use measured values and keep unmeasurable items manual", () => {
    const input = scenario();
    const m = computeMilestones(input, computeSummary(input, opts), opts);
    const m1 = m.milestones[0]!;
    expect(m1.metrics.map((x) => [x.key, x.value, x.target])).toEqual([
      ["rigs", 2, 250],
      ["seeker_rigs", 1, 50],
    ]);
    expect(m1.manual.length).toBeGreaterThan(0);
    // 00:00-06:00 WAT share over the last 7 nights: no rounds there in this scenario
    expect(m.milestones[1]!.metrics[2]!.value).toBeNull();
  });

  it("monthly report aggregates by UTC month", () => {
    const rows = monthlyReport(scenario(), opts);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ month: "2026-10", rigsCumulative: 2, rigRoundsDug: 3, lamportsDeployed: 2_999_970n });
  });

  it("measures round length only from consecutive rounds", () => {
    expect(measuredRoundSeconds([round(1, { ts: 0 }), round(3, { ts: 500 })])).toBeNull();
    expect(measuredRoundSeconds([round(1, { ts: 0 }), round(2, { ts: 77 }), round(3, { ts: 156 })])).toBe(78);
  });
});
