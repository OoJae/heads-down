import { createHash } from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { CONFIG_PDA, EXECUTOR_PDA, HEADS_DOWN_PROGRAM_ID } from "../src/constants.ts";
import { computeSummary, computeMilestones, recentDigs, cohortReport } from "../src/metrics/metrics.ts";
import { Rng, generateSimulation, runSimulation, type SimConfig } from "../src/sim/simulate.ts";
import { migrate, openDb, type Db } from "../src/store/db.ts";
import { Store } from "../src/store/store.ts";

const base: SimConfig = {
  seed: "unit-test",
  rigs: 14,
  nights: 12,
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
    expect(s.rigs.total).toBe(base.rigs);
    expect(s.rigs.basis).toBe("accounts");
    expect(s.rigs.seeker + s.rigs.guest).toBe(base.rigs);
    expect(s.roundsDug.rigRounds).toBeGreaterThan(0);
    expect(s.solDeployed.consistent).toBe(true);
    expect(s.consistency).toEqual({
      digsWithoutDeploy: 0, deploysWithoutDig: 0, lamportsMismatch: 0, roundsDugExceedsDark: 0, seekerTierWithoutSeat: 0, hdMinersExceedTotal: 0,
    });
    expect(BigInt(s.ore.mined.amount)).toBeGreaterThan(0n);
    expect(s.ore.mined.digsPendingRound).toBe(0);
    expect(s.gate.openRate).not.toBeNull();
    // The Rig accounts' lifetime counters agree with the events.
    const lifetime = input.rigs.reduce((a, r) => a + r.lifetimeRoundsDug, 0n);
    expect(lifetime).toBe(BigInt(input.digs.length));
    // Feeds, cohorts and milestones all compute.
    expect(recentDigs(input, HEADS_DOWN_PROGRAM_ID, 10)).toHaveLength(10);
    expect(cohortReport(input, opts).cohorts.length).toBeGreaterThan(0);
    expect(computeMilestones(input, s, opts).milestones).toHaveLength(3);
  });
});
