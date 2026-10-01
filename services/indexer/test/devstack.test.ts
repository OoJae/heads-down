/**
 * A real shift of the REAL heads_down v1.1 program and the real hd-crank on the local mainnet fork
 * (scripts/devstack), captured 2026-10-01 (test/fixtures/devstack-shift-localnet.json): clock-in,
 * three crank digs, the wallet's end_shift, the ORE Round accounts of the shift, and the haul the
 * live indexer served for it. This test re-derives that haul offline from the raw transactions and
 * checks the `decode` output used for demo captions.
 *
 * A second capture (test/fixtures/devstack-reregister-localnet.json) is one wallet that clocks in,
 * ends its shift, closes its rig and clocks in again: a real RigClosed, a rig registered twice, and
 * shift ids that restart at 1.
 */
import { readFileSync } from "node:fs";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { listRigShifts, loadHaul, shiftLogAddress } from "../src/api/haul.ts";
import { explorerUrl } from "../src/api/explorer.ts";
import { decodeOreRound } from "../src/codec/round.ts";
import { extractTransaction, type RawTransaction } from "../src/codec/tx.ts";
import { EXECUTOR_PDA, HEADS_DOWN_PROGRAM_ID } from "../src/constants.ts";
import { describeTransaction, formatDescribed } from "../src/decode.ts";
import { computeSummary, rigLifecycle } from "../src/metrics/metrics.ts";
import { MarketPrice, fixedSource } from "../src/sources/market.ts";
import { migrate, openDb, type Db } from "../src/store/db.ts";
import { Store } from "../src/store/store.ts";

interface Fixture {
  rig: string;
  authority: string;
  transactions: { name: string; signature: string; tx: RawTransaction }[];
  haul: Record<string, unknown> & { market_lamports_per_ore: string; market_source: string };
  haul_checks: string;
  round_accounts: { round_id: string; address: string; owner: string; data_base64: string }[];
  round_accounts_context_slot: number;
}
const f = JSON.parse(readFileSync(new URL("./fixtures/devstack-shift-localnet.json", import.meta.url), "utf8")) as Fixture;
const OPTS = { programId: HEADS_DOWN_PROGRAM_ID, executorPda: EXECUTOR_PDA };
const tx = (name: string) => f.transactions.find((t) => t.name === name)!.tx;
const LOCAL_RPC = "http://127.0.0.1:18899";

describe("real devstack transactions (heads_down v1.1 + hd-crank on a mainnet-ORE fork)", () => {
  it("clock-in: register_rig + set_caps + arm_shift in one wallet transaction", () => {
    const x = extractTransaction(tx("clock_in"), OPTS);
    expect(x.problems).toEqual([]);
    expect(x.hdInstructions.map((i) => i.ix.name)).toEqual(["register_rig", "set_caps", "arm_shift"]);
    expect(x.hdEvents.map((e) => e.event.kind)).toEqual(["RigRegistered", "ShiftArmed"]);
    expect(x.hdEvents[0]!.event).toMatchObject({ kind: "RigRegistered", rig: f.rig, authority: f.authority, tier: 0, attestationLevel: 0 });
    expect(x.armPlans).toHaveLength(1);
    expect(x.armPlans[0]!.plan).toMatchObject({ mode: 0, lease: 3, split: 10, solo: 0, digLamports: 1_000_000n });
  });

  it("digs: the crank lands a fresh heartbeat once, then reuses the lease it granted", () => {
    const xs = ["dig_1", "dig_2", "dig_3"].map((n) => extractTransaction(tx(n), OPTS));
    for (const x of xs) {
      expect(x.problems).toEqual([]);
      expect(x.hdInstructions.map((i) => i.ix.name)).toEqual(["dig"]);
      expect(x.hdEvents.map((e) => e.event.kind)).toEqual(["RigDug"]);
      expect(x.oreDeploys).toHaveLength(1);
      expect(x.oreDeploys[0]!.event).toMatchObject({ authority: f.authority, signer: EXECUTOR_PDA, amount: 100_000n, totalSquares: 10 });
      const dug = x.hdEvents[0]!.event as { lamports: bigint; mask: number };
      expect(dug.lamports).toBe(1_000_000n); // RigDug.lamports: SOL on squares, no fee (v1.1)
      expect(x.oreDeploys[0]!.event.mask).toBe(dug.mask);
    }
    expect(xs.map((x) => [x.heartbeats[0]!.fresh, x.heartbeats[0]!.applied])).toEqual([
      [true, true],
      [false, false],
      [false, false],
    ]);
    expect(xs[0]!.heartbeats[0]).toMatchObject({ kind: "dig", rig: f.rig, authority: f.authority, counter: 1n, leaseRounds: 3 });
  });

  it("end_shift: ShiftEndedV2 is kept and its ShiftEnded twin dropped", () => {
    const x = extractTransaction(tx("end_shift"), OPTS);
    expect(x.problems).toEqual([]);
    expect(x.hdEvents.map((e) => e.event.kind)).toEqual(["ShiftEndedV2"]);
    expect(x.hdEvents[0]!.event).toMatchObject({ shiftId: 1n, darkRounds: 3n, roundsDug: 3n, lamports: 3_030_000n, reason: 0, mode: 0 });
  });

  it("decode prints each instruction, heartbeat and event in words (demo captions)", () => {
    const text = formatDescribed(describeTransaction(tx("dig_1"), OPTS), (s) => explorerUrl("localnet", "tx", s, LOCAL_RPC));
    expect(text).toContain("status: success");
    expect(text).toContain("heads_down dig");
    expect(text).toMatch(/heartbeat #1 signed for round 422789, lease 3 -> applied \(lease granted\)/);
    expect(text).toMatch(/RigDug: rig \S+ dug ORE round 422789: 1000000 lamports on 10 squares/);
    expect(text).toMatch(/ORE DeployEvent \(signer = Executor PDA\): wallet \S+ placed 100000 lamports on each of 10 squares in round 422789/);
    expect(text).toContain("rig[0]");
    const reuse = formatDescribed(describeTransaction(tx("dig_2"), OPTS), () => null);
    expect(reuse).toMatch(/reuses its current lease \(no heartbeat, hb_ix 0xff\)/);
    const clock = describeTransaction(tx("clock_in"), OPTS);
    expect(clock.events.map((e) => e.text)).toEqual([
      expect.stringMatching(/^RigRegistered: rig \S+ for wallet \S+ \(tier guest, attestation level 0\)$/),
      expect.stringMatching(/^ShiftArmed: rig \S+ armed shift 1$/),
    ]);
    const end = describeTransaction(tx("end_shift"), OPTS);
    expect(end.events[0]!.text).toMatch(/ShiftEndedV2: rig \S+ shift 1 \(rounds 422789\.\.422792, night\): 3 dark rounds, 3 dug, 3030000 lamports spent, reason completed/);
  });

  it("decode shows a failed transaction's instructions and names its error", () => {
    // In the captured dig, instruction 3 is heads_down's (0-1 compute budget, 2 the secp256r1 precompile).
    const failed = structuredClone(tx("dig_1")) as RawTransaction;
    failed.meta!.err = { InstructionError: [3, { Custom: 18 }] };
    failed.meta!.logMessages = [`Program ${HEADS_DOWN_PROGRAM_ID} invoke [1]`, `Program ${HEADS_DOWN_PROGRAM_ID} failed: custom program error: 0x12`];
    const d = describeTransaction(failed, OPTS);
    expect(d.status).toBe("failed");
    expect(d.failure).toBe("instruction 3 failed: Paused (18, heads_down)");
    expect(d.instructions.map((i) => i.name)).toEqual(["dig"]);
    expect(d.instructions[0]!.heartbeats).toEqual([expect.stringMatching(/heartbeat #1 signed for round 422789, lease 3$/)]);
    expect(d.events).toEqual([]);
    expect(d.ore).toEqual([]);
    // Without logs, the failing instruction's own program decides whose code it is.
    failed.meta!.logMessages = null;
    expect(describeTransaction(failed, OPTS).failure).toBe("instruction 3 failed: Paused (18, heads_down)");
  });

  it("decode never gives a heads_down name to another program's error code", () => {
    const ORE = "oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv";
    // ORE fails inside the dig's CPI with ITS custom error 1; the same code surfaces from heads_down's frame.
    const cpi = structuredClone(tx("dig_1")) as RawTransaction;
    cpi.meta!.err = { InstructionError: [3, { Custom: 1 }] };
    cpi.meta!.logMessages = [
      `Program ${HEADS_DOWN_PROGRAM_ID} invoke [1]`,
      `Program ${ORE} invoke [2]`,
      // A log line a program wrote itself must not be read as the runtime's failure line.
      `Program log: Program ${HEADS_DOWN_PROGRAM_ID} failed: custom program error: 0x1`,
      `Program ${ORE} failed: custom program error: 0x1`,
      `Program ${HEADS_DOWN_PROGRAM_ID} failed: custom program error: 0x1`,
    ];
    expect(describeTransaction(cpi, OPTS).failure).toBe(`instruction 3 failed: custom error 1 (0x1) raised by ${ORE}, not by heads_down`);
    // A failure in another top-level instruction (here the precompile at index 2), no logs.
    const pre = structuredClone(tx("dig_1")) as RawTransaction;
    pre.meta!.err = { InstructionError: [2, { Custom: 2 }] };
    pre.meta!.logMessages = [];
    expect(describeTransaction(pre, OPTS).failure).toBe("instruction 2 failed: custom error 2 (0x2) raised by Secp256r1SigVerify1111111111111111111111111, not by heads_down");
    // Non-custom errors are printed as the runtime gave them.
    pre.meta!.err = { InstructionError: [3, "ProgramFailedToComplete"] };
    expect(describeTransaction(pre, OPTS).failure).toBe("instruction 3 failed: ProgramFailedToComplete");
    pre.meta!.err = "BlockhashNotFound";
    expect(describeTransaction(pre, OPTS).failure).toBe('"BlockhashNotFound"');
  });
});

describe("the live haul, re-derived offline", () => {
  let db: Db;
  beforeAll(async () => {
    db = await openDb("pglite://memory");
    await migrate(db);
  });
  afterAll(async () => db.close());

  it("matches the haul the devstack indexer served, field for field", async () => {
    const store = await Store.bind(db, { name: "localnet", ...OPTS });
    expect(await store.ingestTxs(f.transactions.map((t) => extractTransaction(t.tx, OPTS)), "fixture")).toBe(5);
    await store.upsertRoundStates(
      f.round_accounts.map((r) => {
        const data = Uint8Array.from(Buffer.from(r.data_base64, "base64"));
        return { address: r.address, account: decodeOreRound(data), data, contextSlot: f.round_accounts_context_slot };
      }),
      "fixture",
    );
    const h = await loadHaul(
      {
        store,
        dataset: "localnet",
        simulated: false,
        programId: HEADS_DOWN_PROGRAM_ID,
        market: new MarketPrice([fixedSource(BigInt(f.haul.market_lamports_per_ore), f.haul.market_source)]),
        link: (kind, id) => explorerUrl("localnet", kind, id, LOCAL_RPC),
      },
      f.rig,
      "latest",
    );
    expect(h.status).toBe(200);
    if (h.status !== 200) return;
    expect(h.haul).toEqual(f.haul);
    expect(h.diagnostics).toMatchObject({ darkMatches: true, solReturnedExact: true, deploysMatchDigs: true });
    expect(f.haul_checks).toBe("dark=match; sol-returned=exact; deploys=match");
    // By hand, from the fixture's Round accounts (the devstack driver is the only other miner, 10_000 a square):
    //   422789  split, winning square 9: the rig's 100_000 is all of it       -> 1 ORE
    //           SOL back: 99_000 (winning) + 9 x 89_100 (T = 100_000 or 110_000) = 900_900
    //   422790  winning square 18: the rig is not on it                       -> 0 ORE
    //           SOL back: 10 x 89_100                                         = 891_000
    //   422791  split, winning square 15: again the rig's alone               -> 1 ORE
    //           SOL back: 99_000 + 9 x 89_100                                 = 900_900
    //   422792  the end round: not dug, not dark (the lease ran 422789..422791)
    //   placed 3_000_000, back 2_692_800, fees 3_030_000 - 3_000_000 = 30_000
    //   effective = (3_000_000 - 2_692_800 + 30_000) / 2 ORE = 168_600 lamports per ORE
    expect(h.haul).toMatchObject({
      start_round: 422789,
      end_round: 422792,
      dark_rounds: 3,
      rounds_dug: 3,
      sol_placed_lamports: 3_000_000,
      fees_lamports: 30_000,
      ore_mined_atoms: "200000000000",
      effective_lamports_per_ore: "168600",
      break_reason: 0,
      streak_before: 0,
      streak_after: 1,
      simulated: false,
    });
    expect(h.haul.rounds).toEqual([
      { round_id: 422789, dark: true, dug_mask: 25315900, winning_square: 9, motherlode: false, split: true },
      { round_id: 422790, dark: true, dug_mask: 25184027, winning_square: 18, motherlode: false, split: false },
      { round_id: 422791, dark: true, dug_mask: 10652400, winning_square: 15, motherlode: false, split: true },
      { round_id: 422792, dark: false, dug_mask: 0, winning_square: 14, motherlode: false, split: true },
    ]);
    expect(h.diagnostics.solReturnedLamports).toBe(2_692_800n);
    expect((await store.health()).problems).toEqual([]);
  });
});

describe("a rig closed and registered again (real devstack transactions)", () => {
  interface Rereg extends Fixture {
    shift_log: string;
    refused_end_shift: { err: unknown; logs: string[] };
  }
  const g = JSON.parse(readFileSync(new URL("./fixtures/devstack-reregister-localnet.json", import.meta.url), "utf8")) as Rereg;
  const of = (name: string) => g.transactions.find((t) => t.name === name)!.tx;
  let db: Db;
  beforeAll(async () => {
    db = await openDb("pglite://memory");
    await migrate(db);
  });
  afterAll(async () => db.close());

  it("decodes close_rig, and both registrations arm shift 1", () => {
    const xs = g.transactions.map((t) => extractTransaction(t.tx, OPTS));
    expect(xs.flatMap((x) => x.problems)).toEqual([]);
    expect(g.transactions.map((t) => t.name)).toEqual(["clock_in_first", "end_shift_first", "close_rig", "clock_in_again"]);
    expect(xs.map((x) => x.hdEvents.map((e) => e.event.kind))).toEqual([["RigRegistered", "ShiftArmed"], ["ShiftEndedV2"], ["RigClosed"], ["RigRegistered", "ShiftArmed"]]);
    expect(xs[2]!.hdInstructions.map((i) => i.ix.name)).toEqual(["close_rig"]);
    expect(xs[2]!.hdEvents[0]!.event).toEqual({ kind: "RigClosed", rig: g.rig });
    // The new Rig account starts over: shift ids restart with it.
    expect(xs[0]!.hdEvents[1]!.event).toEqual({ kind: "ShiftArmed", rig: g.rig, shiftId: 1n });
    expect(xs[3]!.hdEvents[1]!.event).toEqual({ kind: "ShiftArmed", rig: g.rig, shiftId: 1n });
    const text = formatDescribed(describeTransaction(of("close_rig"), OPTS), () => null);
    expect(text).toContain("heads_down close_rig");
    expect(text).toMatch(/RigClosed: rig \S+$/m);
  });

  it("counts the rig once and open, and serves the haul of the shift it ended", async () => {
    const store = await Store.bind(db, { name: "localnet", ...OPTS });
    expect(await store.ingestTxs(g.transactions.map((t) => extractTransaction(t.tx, OPTS)), "fixture")).toBe(4);
    await store.upsertRoundStates(
      g.round_accounts.map((r) => {
        const data = Uint8Array.from(Buffer.from(r.data_base64, "base64"));
        return { address: r.address, account: decodeOreRound(data), data, contextSlot: g.round_accounts_context_slot };
      }),
      "fixture",
    );
    const input = await store.loadMetricsInput();
    expect(input.registered).toHaveLength(2);
    expect(input.closedRigs).toHaveLength(1);
    const life = rigLifecycle(input)!;
    expect([...life.open]).toEqual([g.rig]);
    expect(life.closed.size).toBe(0);
    expect(life.registered.size).toBe(1);
    const endTs = Number(g.haul.end_ts);
    const s = computeSummary(input, { asOf: endTs + 60, tzOffsetMinutes: 60, programId: HEADS_DOWN_PROGRAM_ID, executorPda: EXECUTOR_PDA, teamCrankers: [] });
    expect(s.rigs).toMatchObject({ total: 1, guest: 1, seeker: 0, closed: 0, basis: "lifecycle", everRegistered: 1 });

    const h = await loadHaul(
      {
        store,
        dataset: "localnet",
        simulated: false,
        programId: HEADS_DOWN_PROGRAM_ID,
        market: new MarketPrice([fixedSource(BigInt(g.haul.market_lamports_per_ore), g.haul.market_source)]),
        link: (kind, id) => explorerUrl("localnet", kind, id, LOCAL_RPC),
        now: () => endTs + 60, // fresh: the end round must be resolved, and its Round account is in the fixture
      },
      g.rig,
      "latest",
    );
    expect(h.status).toBe(200);
    if (h.status !== 200) return;
    expect(h.haul).toEqual(g.haul);
    expect(g.haul_checks).toBe("dark=match; sol-returned=exact; deploys=match");
    // Ended by the wallet one slot after arming: no heartbeat, so no dark round and reason lease_lapse.
    expect(h.haul).toMatchObject({
      shift_id: 1, start_round: 422812, end_round: 422812, dark_rounds: 0, rounds_dug: 0, sol_placed_lamports: 0, fees_lamports: 0,
      ore_mined_atoms: "0", effective_lamports_per_ore: null, break_reason: 4, streak_before: 0, streak_after: 0,
    });
    expect(h.haul.rounds).toEqual([{ round_id: 422812, dark: false, dug_mask: 0, winning_square: 16, motherlode: false, split: false }]);
    expect(h.haul.explorer.shift_log).toContain(g.shift_log);
    // The second shift 1 is still open on chain, so one finished shift is listed.
    expect(await listRigShifts(store, g.rig)).toHaveLength(1);
    expect((await store.health()).problems).toEqual([]);
  });

  it("records what the program answered when the second shift 1 was ended: its ShiftLog PDA already exists", () => {
    // Shift ids restart with the new Rig account, the ShiftLog address is ["shift", rig, shift_id], and end_shift
    // creates it init-only: the ShiftLog of the first shift 1 is in the way. A program-side finding, kept here as data.
    expect(g.refused_end_shift.err).toEqual({ InstructionError: [0, "AccountAlreadyInitialized"] });
    expect(shiftLogAddress(g.rig, 1n, HEADS_DOWN_PROGRAM_ID)).toBe(g.shift_log);
    expect(extractTransaction(of("end_shift_first"), OPTS).hdInstructions[0]!.accounts[2]).toBe(g.shift_log);
    const refused = structuredClone(of("end_shift_first")) as RawTransaction;
    refused.meta!.err = g.refused_end_shift.err;
    refused.meta!.logMessages = g.refused_end_shift.logs;
    const d = describeTransaction(refused, OPTS);
    expect(d.failure).toBe("instruction 0 failed: AccountAlreadyInitialized");
    expect(d.instructions.map((i) => i.name)).toEqual(["end_shift"]);
    expect(d.events).toEqual([]);
  });
});
