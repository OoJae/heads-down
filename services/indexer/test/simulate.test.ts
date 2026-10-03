import { createHash } from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { CONFIG_PDA, EXECUTOR_PDA, HEADS_DOWN_PROGRAM_ID } from "../src/constants.ts";
import { computeSummary, computeMilestones, recentDigs, cohortReport } from "../src/metrics/metrics.ts";
import { listRigShifts, loadHaul } from "../src/api/haul.ts";
import { Rng, generateSimulation, runSimulation, type SimConfig } from "../src/sim/simulate.ts";
import { migrate, openDb, type Db } from "../src/store/db.ts";
import { Store } from "../src/store/store.ts";

// Large enough to contain every kind of traffic the simulator models, including a closed rig.
const base: SimConfig = {
  seed: "unit-test",
  rigs: 20,
  nights: 14,
  startDay: "2026-09-10",
  programId: HEADS_DOWN_PROGRAM_ID,
  executorPda: EXECUTOR_PDA,
  configPda: CONFIG_PDA,
};

const fingerprint = (x: unknown) =>
  createHash("sha256")
    .update(JSON.stringify(x, (_k, v) => (typeof v === "bigint" ? v.toString() : v instanceof Uint8Array ? Buffer.from(v).toString("hex") : v)))
    .digest("hex");

describe("simulation determinism", () => {
  it("the same seed produces byte-identical output; a different seed does not", () => {
    const a = generateSimulation(base);
    const b = generateSimulation(base);
    expect(fingerprint(a)).toBe(fingerprint(b));
    const c = generateSimulation({ ...base, seed: "other" });
    expect(fingerprint(c)).not.toBe(fingerprint(a));
  });

  it("Rng is platform-independent (pinned first outputs)", () => {
    // sfc32 over SHA-256("pin"): pure 32-bit integer math, so these never change.
    const r = new Rng("pin");
    expect([r.u32(), r.u32(), r.u32()]).toEqual([4204019318, 1411623521, 4057628169]);
    expect(new Rng("pin").fork("x").label).toBe("pin/x");
  });

  it("validates its configuration", () => {
    expect(() => generateSimulation({ ...base, rigs: 0 })).toThrow();
    expect(() => generateSimulation({ ...base, startDay: "Sept 10" })).toThrow();
  });
});

describe("simulation through the real ingest path", () => {
  let db: Db;
  beforeAll(async () => {
    db = await openDb("pglite://memory");
    await migrate(db);
  });
  afterAll(async () => db.close());

  it("refuses to write into a real dataset", async () => {
    const store = await Store.bind(db, { name: "mainnet", programId: HEADS_DOWN_PROGRAM_ID, executorPda: EXECUTOR_PDA });
    await expect(runSimulation({ store, programId: HEADS_DOWN_PROGRAM_ID, executorPda: EXECUTOR_PDA }, base)).rejects.toThrow(/real dataset/);
  });

  it("ingests with zero decode problems and internally consistent metrics", async () => {
    const store = await Store.bind(db, { name: "simulated", programId: HEADS_DOWN_PROGRAM_ID, executorPda: EXECUTOR_PDA, simSeed: base.seed });
    const ctx = { store, programId: HEADS_DOWN_PROGRAM_ID, executorPda: EXECUTOR_PDA };
    const sim = await runSimulation(ctx, base);
    const health = await store.health();
    expect(health.problems).toEqual([]);
    expect(health.txs).toBe(sim.txs.length);
    const info = await store.info();
    expect(info).toMatchObject({ simulated: true, simSeed: "unit-test", simAsOf: sim.asOf });

    const input = await store.loadMetricsInput();
    const opts = { asOf: sim.asOf, tzOffsetMinutes: 60, programId: HEADS_DOWN_PROGRAM_ID, executorPda: EXECUTOR_PDA, teamCrankers: sim.teamCrankers };
    const s = computeSummary(input, opts);
    // Rigs are counted from RigRegistered / RigClosed, and the account snapshot agrees.
    expect(s.rigs.basis).toBe("lifecycle");
    expect(s.rigs.everRegistered).toBe(base.rigs);
    expect(s.rigs.closed).toBeGreaterThan(0);
    expect(input.closedRigs).toHaveLength(s.rigs.closed);
    expect(s.rigs.total).toBe(base.rigs - s.rigs.closed);
    expect(s.rigs.crossCheck).toEqual({ accounts: s.rigs.total, accountsSeeker: s.rigs.seeker, matches: true });
    expect(s.rigs.seeker + s.rigs.guest).toBe(s.rigs.total);
    expect(input.registered).toHaveLength(base.rigs);
    expect(s.roundsDug.rigRounds).toBeGreaterThan(0);
    expect(s.solDeployed.consistent).toBe(true);
    expect(s.consistency).toEqual({
      digsWithoutDeploy: 0, deploysWithoutDig: 0, lamportsMismatch: 0, roundsDugExceedsDark: 0, seekerTierWithoutSeat: 0, hdMinersExceedTotal: 0,
    });
    expect(BigInt(s.ore.mined.amount)).toBeGreaterThan(0n);
    expect(s.ore.mined.digsPendingRound).toBe(0);
    expect(s.gate.openRate).not.toBeNull();
    // The Rig accounts' lifetime counters agree with the events (a closed rig's account is gone, its events are not).
    const open = new Set(input.rigs.filter((r) => !r.closed).map((r) => r.address));
    expect(open.size).toBe(s.rigs.total);
    const lifetime = input.rigs.reduce((a, r) => a + r.lifetimeRoundsDug, 0n);
    expect(lifetime).toBe(BigInt(input.digs.filter((d) => open.has(d.rig)).length));
    expect(input.digs.some((d) => !open.has(d.rig))).toBe(true);
    // Feeds, cohorts and milestones all compute.
    expect(recentDigs(input, HEADS_DOWN_PROGRAM_ID, 10)).toHaveLength(10);
    expect(cohortReport(input, opts).cohorts.length).toBeGreaterThan(0);
    expect(computeMilestones(input, s, opts).milestones).toHaveLength(3);

    // Every ended shift has a final haul whose replayed leases give exactly ShiftEnded.dark_rounds,
    // with SOL returned from the Round accounts and the rig's DeployEvents matching its RigDugs.
    const deps = { store, dataset: "simulated" as const, simulated: true, programId: HEADS_DOWN_PROGRAM_ID, market: null, link: () => null };
    let hauls = 0;
    let mined = 0n;
    const reasons = new Set<number>();
    for (const rig of new Set(input.registered!.map((r) => r.rig))) {
      for (const sh of await listRigShifts(store, rig)) {
        const h = await loadHaul(deps, rig, BigInt(sh.shiftId));
        expect(h.status, `${rig} shift ${sh.shiftId}`).toBe(200);
        if (h.status !== 200) continue;
        expect(h.diagnostics, `${rig} shift ${sh.shiftId}`).toMatchObject({ darkMatches: true, solReturnedExact: true, deploysMatchDigs: true });
        expect(h.haul.rounds.filter((r) => r.dark).length).toBe(Number(h.haul.dark_rounds));
        expect(h.haul.rounds.filter((r) => r.dug_mask !== 0).length).toBe(Number(h.haul.rounds_dug));
        expect(h.haul.explorer).toEqual({ shift_log: null, sample_digs: [] });
        mined += BigInt(h.haul.ore_mined_atoms);
        reasons.add(h.haul.break_reason);
        hauls++;
      }
    }
    expect(hauls).toBe(input.ends.length);
    expect(mined).toBeGreaterThan(0n);
    // Completed shifts and phone-signed breaks (1 pickup, 2 screen_on) both occur.
    expect(reasons.has(0)).toBe(true);
    expect(reasons.has(1) || reasons.has(2)).toBe(true);
    // The skips a dashboard has to explain are all present: gate closed, replay refused, quiet phone.
    const codes = new Set(input.skips.map((k) => k.errorCode));
    for (const c of [1, 7, 8]) expect(codes.has(c), `skip code ${c}`).toBe(true);
  });
});
