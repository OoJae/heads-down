/**
 * The morning haul (contract B), end to end through the store and the API, on a hand-built shift
 * whose every number is computed by hand below.
 *
 * Rig R (authority A), plan lease 2, rounds 1000..1004:
 *   round 1000  dig, fresh heartbeat #1 (round 1000, lease 2) -> lease [1000, 1001]; 100,000 on squares {0,1,2}
 *   round 1002  record_heartbeats #2 (round 1002, lease 2)    -> lease [1002, 1003]
 *   round 1003  dig reusing the lease;                        50,000 on square {5}
 *   end_shift in round 1004 after the window: completed, dark 4, dug 2, spent 360,000 (350,000 + 2 fees of 5,000)
 *
 * Round 1000 (Round account): split, winning square 0, T0 = 1,000,000, T1 = 400,000, T2 = 10,000,000
 *   ORE  1e11 × 100,000 / 1,000,000                                  = 10,000,000,000
 *   SOL  sq0 100,000 × (1,000,000 − 10,000) / 1,000,000              =  99,000
 *        sq1 100,000 × (400,000 − 4,000 − 39,600) / 400,000          =  89,100
 *        sq2 100,000 × (10,000,000 − 100,000 − 990,000) / 10,000,000 =  89,100
 * Round 1003 (ResetEvent only): solo, winning square 5, top_miner = A, Motherlode 2 ORE, T5 = 200,000
 *   ORE  1 ORE + 2e11 × 50,000 / 200,000                             = 150,000,000,000
 *   SOL  50,000 × (200,000 − 2,000) / 200,000                        =  49,500
 * ORE mined 160,000,000,000; SOL placed 350,000; returned 326,700; fees 10,000
 * effective = ceil((350,000 − 326,700 + 10,000) × 1e11 / 1.6e11) = ceil(20,812.5) = 20,813 lamports/ORE
 */
import type { AddressInfo } from "node:net";
import type http from "node:http";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { openApiDocument } from "../src/api/openapi.ts";
import { createApiServer } from "../src/api/server.ts";
import { shiftLogAddress } from "../src/api/haul.ts";
import { HAUL_MAX_ROUNDS, lastListedRound } from "../src/metrics/haul.ts";
import { encodeOreRound, oreRoundPda, type OreRoundAccount } from "../src/codec/round.ts";
import { extractTransaction, type RawTransaction } from "../src/codec/tx.ts";
import { ByteWriter } from "../src/codec/bytes.ts";
import { findProgramAddress, seed, addrBytes } from "../src/codec/pda.ts";
import { CONFIG_PDA, EXECUTOR_PDA, HEADS_DOWN_PROGRAM_ID, ONE_ORE, ORE_SPLIT_ADDRESS } from "../src/constants.ts";
import { MarketPrice, fixedSource } from "../src/sources/market.ts";
import { buildDigTx, buildEventTx, buildRecordTx, type PlanInput } from "../src/sim/txbuilder.ts";
import { migrate, openDb, type Db } from "../src/store/db.ts";
import { Store } from "../src/store/store.ts";
import type { OreResetEvent } from "../src/codec/ore.ts";
import { addr, sig } from "./helpers.ts";

const HD = HEADS_DOWN_PROGRAM_ID;
const OPTS = { programId: HD, executorPda: EXECUTOR_PDA };
const A = addr(11);
const R = findProgramAddress([seed("rig"), addrBytes(A)], HD).address;
const T = 1_790_800_000;
const PLAN: PlanInput = { maxEvCost: 900_000_000n, digLamports: 300_000n, split: 3, solo: 1, lease: 2, flags: 0, windowStart: BigInt(T - 60), windowEnd: BigInt(T + 500) };

let db: Db;
let base = "";
/** The API's clock: well after every shift unless a test moves it. */
let clock = T + 86_400;
let store: Store;
const servers: http.Server[] = [];

function reset(roundId: bigint, ws: number, topMiner: string, motherlode: bigint, dws: bigint): { event: OreResetEvent; resetSignature: string } {
  return {
    resetSignature: sig(900 + Number(roundId - 1000n)),
    event: {
      kind: "OreReset", roundId, startSlot: 1n, endSlot: 2n, winningSquare: ws, topMiner, totalMiners: 50n, motherlode, totalDeployed: 20_000_000n,
      totalVaulted: 1n, totalWinnings: 1n, totalMinted: 120_000_000_000n, ts: BigInt(T + Number(roundId - 1000n) * 80), rng: BigInt(ws), deployedWinningSquare: dws,
    },
  };
}

beforeAll(async () => {
  db = await openDb("pglite://memory");
  await migrate(db);
  store = await Store.bind(db, { name: "mainnet", programId: HD, executorPda: EXECUTOR_PDA });
  const txs: RawTransaction[] = [
    buildEventTx({ signature: sig(1), slot: 10, blockTime: T - 3600, signer: A, programId: HD, events: [{ kind: "RigRegistered", rig: R, authority: A, tier: 0, attestationLevel: 2 }] }),
    buildEventTx({ signature: sig(2), slot: 100, blockTime: T, signer: A, programId: HD, events: [{ kind: "ShiftArmed", rig: R, shiftId: 1n }], plan: PLAN }),
    buildDigTx({
      signature: sig(3), slot: 110, blockTime: T + 40, cranker: addr(9), programId: HD, configPda: CONFIG_PDA, executorPda: EXECUTOR_PDA,
      roundAccount: oreRoundPda(1000n), roundId: 1000n,
      rigs: [{ rig: R, authority: A, automation: addr(12), miner: addr(13), heartbeat: { counter: 1n, round: 1000n, lease: 2 }, outcome: { kind: "dug", perTile: 100_000n, mask: 0b111, emaEv: 1n } }],
    }),
    buildRecordTx({ signature: sig(4), slot: 130, blockTime: T + 200, cranker: addr(9), programId: HD, boardRound: 1002n, rigs: [{ rig: R, heartbeat: { counter: 2n, round: 1002n, lease: 2 }, outcome: { kind: "recorded", darkRoundsAdded: 2n } }] }),
    buildDigTx({
      signature: sig(5), slot: 140, blockTime: T + 280, cranker: addr(9), programId: HD, configPda: CONFIG_PDA, executorPda: EXECUTOR_PDA,
      roundAccount: oreRoundPda(1003n), roundId: 1003n,
      rigs: [{ rig: R, authority: A, automation: addr(12), miner: addr(13), heartbeat: null, outcome: { kind: "dug", perTile: 50_000n, mask: 1 << 5, emaEv: 1n } }],
    }),
    buildEventTx({
      signature: sig(6), slot: 200, blockTime: T + 600, signer: A, programId: HD,
      events: [
        { kind: "ShiftEnded", rig: R, shiftId: 1n, darkRounds: 4n, roundsDug: 2n, lamports: 360_000n, reason: 0 },
        { kind: "ShiftEndedV2", rig: R, shiftId: 1n, darkRounds: 4n, roundsDug: 2n, lamports: 360_000n, reason: 0, startRound: 1000n, endRound: 1004n, mode: 0 },
      ],
    }),
  ];
  expect(await store.ingestTxs(txs.map((t) => extractTransaction(t, OPTS)), "test")).toBe(6);
  await store.upsertRounds(
    [
      reset(1001n, 7, ORE_SPLIT_ADDRESS, 0n, 1n),
      reset(1002n, 3, ORE_SPLIT_ADDRESS, 0n, 1n),
      reset(1003n, 5, A, 2n * ONE_ORE, 200_000n),
      reset(1004n, 9, addr(77), 0n, 1n),
    ],
    "test",
  );
  const r1000: OreRoundAccount = {
    id: 1000n,
    deployed: Array.from({ length: 25 }, (_, i) => (i === 0 ? 1_000_000n : i === 1 ? 400_000n : i === 2 ? 10_000_000n : 500_000n)),
    // rng = 25 → winning square 0
    slotHash: new ByteWriter(32).u64(25n).u64(0n).u64(0n).u64(0n).finish(),
    expiresAt: 10_000n,
    motherlode: 0n,
    rentPayer: addr(3),
    rewards: [ONE_ORE, ...Array.from({ length: 24 }, () => 0n)],
    totalVaulted: 1n,
    totalReturnedSol: 1n,
    totalMiners: 40n,
    topMiner: ORE_SPLIT_ADDRESS,
  };
  await store.upsertRoundStates([{ address: oreRoundPda(1000n), account: r1000, data: encodeOreRound(r1000), contextSlot: 300 }], "test");

  const server = createApiServer({
    store, info: await store.info(), defaultTzOffsetMinutes: 60, teamCrankers: [], market: new MarketPrice([fixedSource(800_000_000n, "test-fixed")]),
    now: () => clock,
  });
  servers.push(server);
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
afterAll(async () => {
  for (const s of servers) await new Promise((r) => s.close(r));
  await db.close();
});

describe("GET /v1/rigs/{rig}/haul/latest (hand-computed shift)", () => {
  it("returns contract B exactly", async () => {
    const res = await fetch(`${base}/v1/rigs/${R}/haul/latest`);
    expect(res.status).toBe(200);
    expect(res.headers.get("x-headsdown-dataset")).toBe("mainnet");
    expect(res.headers.get("x-headsdown-haul-checks")).toBe("dark=match; sol-returned=exact; deploys=match");
    const h = (await res.json()) as Record<string, unknown>;
    expect(Object.keys(h)).toEqual([
      "rig", "shift_id", "mode", "start_ts", "end_ts", "start_round", "end_round", "rounds", "dark_rounds", "rounds_dug", "sol_placed_lamports",
      "fees_lamports", "ore_mined_atoms", "effective_lamports_per_ore", "market_lamports_per_ore", "market_source", "streak_before", "streak_after",
      "break_reason", "first_pickup_ts", "simulated", "explorer",
    ]);
    expect(h).toEqual({
      rig: R,
      shift_id: 1,
      mode: "night",
      start_ts: T,
      end_ts: T + 600,
      start_round: 1000,
      end_round: 1004,
      rounds: [
        { round_id: 1000, dark: true, dug_mask: 0b111, winning_square: 0, motherlode: false, split: true },
        { round_id: 1001, dark: true, dug_mask: 0, winning_square: 7, motherlode: false, split: true },
        { round_id: 1002, dark: true, dug_mask: 0, winning_square: 3, motherlode: false, split: true },
        { round_id: 1003, dark: true, dug_mask: 32, winning_square: 5, motherlode: true, split: false },
        { round_id: 1004, dark: false, dug_mask: 0, winning_square: 9, motherlode: false, split: false },
      ],
      dark_rounds: 4,
      rounds_dug: 2,
      sol_placed_lamports: 350_000,
      fees_lamports: 10_000,
      ore_mined_atoms: "160000000000",
      effective_lamports_per_ore: "20813",
      market_lamports_per_ore: "800000000",
      market_source: "test-fixed",
      streak_before: 0,
      streak_after: 1,
      break_reason: 0,
      first_pickup_ts: null,
      simulated: false,
      explorer: {
        shift_log: `https://solscan.io/account/${shiftLogAddress(R, 1n, HD)}`,
        sample_digs: [`https://solscan.io/tx/${sig(5)}`, `https://solscan.io/tx/${sig(3)}`],
      },
    });
    expect(await fetch(`${base}/v1/rigs/${R}/haul/1`).then((r) => r.json())).toEqual(h);
  });

  it("matches the OpenAPI HaulSummary schema", async () => {
    const h = (await fetch(`${base}/v1/rigs/${R}/haul/latest`).then((r) => r.json())) as Record<string, unknown>;
    const schema = openApiDocument.components.schemas.HaulSummary as unknown as { required: string[] };
    expect(Object.keys(h).sort()).toEqual([...schema.required].sort());
    const p = (openApiDocument.paths as Record<string, unknown>)["/v1/rigs/{rig}/haul/latest"];
    expect(p).toBeDefined();
  });

  it("lists the rig's shifts (enveloped)", async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const body = (await fetch(`${base}/v1/rigs/${R}/shifts`).then((r) => r.json())) as any;
    expect(body.dataset.name).toBe("mainnet");
    expect(body.data.shifts).toEqual([{ shiftId: "1", endedAt: T + 600, darkRounds: "4", roundsDug: "2", reason: 0, mode: 0, signature: sig(6) }]);
  });

  it("404s honestly: no finished shift, unknown shift, and a shift not final yet (with Retry-After)", async () => {
    const other = findProgramAddress([seed("rig"), addrBytes(addr(12))], HD).address;
    const none = await fetch(`${base}/v1/rigs/${other}/haul/latest`);
    expect(none.status).toBe(404);
    expect(await none.json()).toEqual({ error: "no finished shift for this rig" });
    expect((await fetch(`${base}/v1/rigs/${R}/haul/2`)).status).toBe(404);
    // A second shift whose dug round has not been reset yet.
    await store.ingestTxs(
      [
        buildEventTx({ signature: sig(7), slot: 300, blockTime: T + 9000, signer: A, programId: HD, events: [{ kind: "ShiftArmed", rig: R, shiftId: 2n }], plan: PLAN }),
        buildDigTx({
          signature: sig(8), slot: 310, blockTime: T + 9040, cranker: addr(9), programId: HD, configPda: CONFIG_PDA, executorPda: EXECUTOR_PDA,
          roundAccount: oreRoundPda(2000n), roundId: 2000n,
          rigs: [{ rig: R, authority: A, automation: addr(12), miner: addr(13), heartbeat: { counter: 3n, round: 2000n, lease: 2 }, outcome: { kind: "dug", perTile: 1_000n, mask: 1, emaEv: 1n } }],
        }),
        buildEventTx({
          signature: sig(9), slot: 320, blockTime: T + 9100, signer: A, programId: HD,
          events: [{ kind: "ShiftEndedV2", rig: R, shiftId: 2n, darkRounds: 1n, roundsDug: 1n, lamports: 6_000n, reason: 6, startRound: 2000n, endRound: 2000n, mode: 1 }],
        }),
      ].map((t) => extractTransaction(t, OPTS)),
      "test",
    );
    const pending = await fetch(`${base}/v1/rigs/${R}/haul/latest`);
    expect(pending.status).toBe(404);
    expect(pending.headers.get("retry-after")).toBe("30");
    expect(((await pending.json()) as { error: string }).error).toMatch(/not final yet: waiting for ORE round\(s\) 2000 to reset/);
    // The earlier shift is still served by id.
    expect((await fetch(`${base}/v1/rigs/${R}/haul/1`)).status).toBe(200);
    // Once the round resets, the latest haul is final: a day shift, ended by hand (reason 6), no streak change.
    await store.upsertRounds([reset(2000n, 1, ORE_SPLIT_ADDRESS, 0n, 10_000n)], "test");
    const h2 = await fetch(`${base}/v1/rigs/${R}/haul/latest`).then((r) => r.json());
    expect(h2).toMatchObject({ shift_id: 2, mode: "day", break_reason: 6, streak_before: 1, streak_after: 1, ore_mined_atoms: "0", effective_lamports_per_ore: null, rounds_dug: 1 });
  });

  it("waits for a fresh shift's last round to reset, so a served haul never changes", async () => {
    await store.ingestTxs(
      [
        buildEventTx({ signature: sig(10), slot: 400, blockTime: T + 20_000, signer: A, programId: HD, events: [{ kind: "ShiftArmed", rig: R, shiftId: 3n }], plan: PLAN }),
        buildEventTx({
          signature: sig(11), slot: 420, blockTime: T + 20_500, signer: A, programId: HD,
          events: [{ kind: "ShiftEndedV2", rig: R, shiftId: 3n, darkRounds: 0n, roundsDug: 0n, lamports: 0n, reason: 4, startRound: 3000n, endRound: 3001n, mode: 0 }],
        }),
      ].map((t) => extractTransaction(t, OPTS)),
      "test",
    );
    clock = T + 20_600; // 100 s after end_shift: round 3001 (live at end_shift) has not reset yet
    const fresh = await fetch(`${base}/v1/rigs/${R}/haul/3`);
    expect(fresh.status).toBe(404);
    expect(((await fresh.json()) as { error: string }).error).toMatch(/waiting for ORE round\(s\) 3001 to reset/);
    clock = T + 20_500 + 1_800; // past the wait: an undug round's outcome is display-only, so serve without it
    const later = await fetch(`${base}/v1/rigs/${R}/haul/3`);
    expect(later.status).toBe(200);
    const h3 = (await later.json()) as { rounds: { winning_square: number | null }[]; break_reason: number; effective_lamports_per_ore: string | null };
    expect(h3.rounds.map((r) => r.winning_square)).toEqual([null, null]);
    expect(h3.break_reason).toBe(4);
    expect(h3.effective_lamports_per_ore).toBeNull();
    clock = T + 86_400;
  });

  it("bounds `rounds` for a shift nobody ended for days; the totals still cover every dig", async () => {
    // Shift 4: armed in round 5000, dug in rounds 5001 and 14000, ended by a third party in round 14999
    // (anyone may, after the window): 10,000 rounds. By hand:
    //   5001   1,000 on square 0, split, winning square 0, T0 = 10,000 -> ORE 1e11 x 1,000 / 10,000 = 1e10; SOL back 1,000 x 9,900 / 10,000 = 990
    //   14000  2,000 on square 0, split, winning square 0, T0 = 4,000  -> ORE 1e11 x 2,000 / 4,000  = 5e10; SOL back 2,000 x 3,960 / 4,000  = 1,980
    //   placed 3,000; back 2,970; spent 13,000 -> fees 10,000; effective = ceil(10,030 x 1e11 / 6e10) = 16,717
    const dig = (n: number, slot: number, roundId: bigint, counter: bigint, perTile: bigint) =>
      buildDigTx({
        signature: sig(n), slot, blockTime: T + 30_000 + Number(roundId - 5000n) * 80, cranker: addr(9), programId: HD, configPda: CONFIG_PDA, executorPda: EXECUTOR_PDA,
        roundAccount: oreRoundPda(roundId), roundId,
        rigs: [{ rig: R, authority: A, automation: addr(12), miner: addr(13), heartbeat: { counter, round: roundId, lease: 1 }, outcome: { kind: "dug", perTile, mask: 1, emaEv: 1n } }],
      });
    const summary = { rig: R, shiftId: 4n, darkRounds: 2n, roundsDug: 2n, lamports: 13_000n, reason: 0 };
    await store.ingestTxs(
      [
        buildEventTx({ signature: sig(12), slot: 500, blockTime: T + 30_000, signer: A, programId: HD, events: [{ kind: "ShiftArmed", rig: R, shiftId: 4n }], plan: PLAN }),
        dig(13, 510, 5001n, 10n, 1_000n),
        dig(14, 520, 14_000n, 11n, 2_000n),
        buildEventTx({
          signature: sig(15), slot: 600, blockTime: T + 830_000, signer: addr(55), programId: HD,
          events: [{ kind: "ShiftEnded", ...summary }, { kind: "ShiftEndedV2", ...summary, startRound: 5000n, endRound: 14_999n, mode: 0 }],
        }),
      ].map((t) => extractTransaction(t, OPTS)),
      "test",
    );
    await store.upsertRounds([reset(5001n, 0, ORE_SPLIT_ADDRESS, 0n, 10_000n), reset(14_000n, 0, ORE_SPLIT_ADDRESS, 0n, 4_000n)], "test");
    clock = T + 830_100; // fresh, yet the unlisted end round (14999, never reset here) is not waited for
    const res = await fetch(`${base}/v1/rigs/${R}/haul/4`);
    expect(res.status).toBe(200);
    expect(res.headers.get("x-headsdown-haul-checks")).toBe(`dark=match; sol-returned=exact; deploys=match; rounds=first ${HAUL_MAX_ROUNDS} of 10000`);
    const h = (await res.json()) as { rounds: { round_id: number; dark: boolean; dug_mask: number; winning_square: number | null }[] } & Record<string, unknown>;
    expect(HAUL_MAX_ROUNDS).toBe(4096);
    expect(h.rounds).toHaveLength(4096);
    expect([h.rounds[0]!.round_id, h.rounds.at(-1)!.round_id]).toEqual([5000, 9095]);
    expect(h.rounds.filter((r) => r.dark).map((r) => r.round_id)).toEqual([5001]);
    expect(h.rounds.filter((r) => r.dug_mask !== 0)).toEqual([{ round_id: 5001, dark: true, dug_mask: 1, winning_square: 0, motherlode: false, split: true }]);
    expect(h).toMatchObject({
      shift_id: 4,
      start_round: 5000,
      end_round: 14_999,
      dark_rounds: 2,
      rounds_dug: 2,
      sol_placed_lamports: 3_000,
      fees_lamports: 10_000,
      ore_mined_atoms: "60000000000",
      effective_lamports_per_ore: "16717",
      break_reason: 0,
      streak_before: 1,
      streak_after: 1, // ten days after shift 1: more missed days than freezes, so the streak restarts at 1
    });
    expect(lastListedRound(5000n, 14_999n)).toBe(9095n);
    expect(lastListedRound(5000n, 5003n)).toBe(5003n);

    // The resolver asks for dug rounds first, then only for rounds a haul lists (never the 5,904 beyond).
    expect(await store.roundsToResolve(10, true)).toEqual([14_000n, 5001n, 2000n, 1003n, 9095n, 9094n, 9093n, 9092n, 9091n, 9090n]);
    expect(await store.roundsToResolve(10, true, 3)).toEqual([14_000n, 5001n, 2000n, 1003n, 5002n, 5000n, 3001n, 3000n]);
    expect(await store.roundsToResolve(10, false)).toEqual([14_000n, 5001n, 2000n, 1003n]);
    clock = T + 86_400;
  });

  it("validates the rig and the shift id", async () => {
    expect((await fetch(`${base}/v1/rigs/not-base58!/haul/latest`)).status).toBe(400);
    expect((await fetch(`${base}/v1/rigs/${R}/haul/abc`)).status).toBe(400);
    expect((await fetch(`${base}/v1/rigs/${R}/haul/99999999999999999999`)).status).toBe(400);
    expect((await fetch(`${base}/v1/rigs/${R}/haul/latest`, { method: "POST" })).status).toBe(405);
  });
});
