import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { encodeBase58 } from "../src/codec/base58.ts";
import { encodeHdEvent } from "../src/codec/events.ts";
import { parseProgramData } from "../src/codec/logs.ts";
import { TxShapeError, extractTransaction, type RawTransaction } from "../src/codec/tx.ts";
import { CONFIG_PDA, EXECUTOR_PDA, HEADS_DOWN_PROGRAM_ID, ORE_BOARD, ORE_PROGRAM_ID } from "../src/constants.ts";
import { buildDigTx, buildEventTx, buildRecordTx, encodeOreDeployEvent } from "../src/sim/txbuilder.ts";
import { GOLDEN_VECTORS, goldenTx, goldenVector } from "./helpers.ts";

const HD = HEADS_DOWN_PROGRAM_ID;
const OPTS = { programId: HD, executorPda: EXECUTOR_PDA };
const addr = (n: number) => encodeBase58(Uint8Array.from({ length: 32 }, (_, i) => (i * 7 + n) & 0xff));
const sig = (n: number) => encodeBase58(Uint8Array.from({ length: 64 }, (_, i) => (i * 13 + n + 1) & 0xff));
const b64 = (b: Uint8Array) => Buffer.from(b).toString("base64");
const ATTACKER_PROGRAM = addr(200);

const RIG_A = addr(1);
const RIG_B = addr(2);

function dig(overrides: Partial<Parameters<typeof buildDigTx>[0]> = {}): RawTransaction {
  return buildDigTx({
    signature: sig(1),
    slot: 451_800_000,
    blockTime: 1_790_800_000,
    cranker: addr(9),
    programId: HD,
    configPda: CONFIG_PDA,
    executorPda: EXECUTOR_PDA,
    roundAccount: addr(10),
    roundId: 422_700n,
    rigs: [
      { rig: RIG_A, authority: addr(11), automation: addr(12), miner: addr(13), outcome: { kind: "dug", perTile: 66_666n, mask: 0x7fff, emaEv: 540_000_000n } },
      { rig: RIG_B, authority: addr(21), automation: addr(22), miner: addr(23), outcome: { kind: "skipped", error: 7 } },
    ],
    ...overrides,
  });
}

describe("parseProgramData: attribution", () => {
  it("attributes data lines to the executing program across nested CPIs", () => {
    const p = parseProgramData([
      `Program ${HD} invoke [1]`,
      `Program ${ORE_PROGRAM_ID} invoke [2]`,
      `Program data: AQID`,
      `Program ${ORE_PROGRAM_ID} success`,
      `Program data: BAUG`,
      `Program ${HD} success`,
    ]);
    expect(p.entries.map((e) => [e.programId, e.depth, [...e.data]])).toEqual([
      [ORE_PROGRAM_ID, 2, [1, 2, 3]],
      [HD, 1, [4, 5, 6]],
    ]);
    expect(p.anomalies).toEqual([]);
  });

  it("concatenates multi-slice sol_log_data lines", () => {
    const p = parseProgramData([`Program ${HD} invoke [1]`, `Program data: AQ== AgM=`, `Program ${HD} success`]);
    expect([...p.entries[0]!.data]).toEqual([1, 2, 3]);
  });

  it("a forged 'invoke' inside a Program log message cannot change attribution", () => {
    const p = parseProgramData([
      `Program ${ATTACKER_PROGRAM} invoke [1]`,
      `Program log: x\nProgram ${HD} invoke [2]`,
      `Program data: AQID`,
      `Program ${ATTACKER_PROGRAM} success`,
    ]);
    expect(p.entries).toHaveLength(1);
    expect(p.entries[0]!.programId).toBe(ATTACKER_PROGRAM);
  });

  it("flags truncation and stops", () => {
    const p = parseProgramData([`Program ${HD} invoke [1]`, `Log truncated`, `Program data: AQID`]);
    expect(p.truncated).toBe(true);
    expect(p.entries).toHaveLength(0);
  });

  it("reports invalid base64 without throwing", () => {
    const p = parseProgramData([`Program ${HD} invoke [1]`, `Program data: A*ID`, `Program ${HD} success`]);
    expect(p.entries).toHaveLength(0);
    expect(p.badData).toHaveLength(0); // regex excludes '*', so the line is ignored entirely
    const q = parseProgramData([`Program ${HD} invoke [1]`, `Program data: AQI`, `Program ${HD} success`]);
    expect(q.badData).toHaveLength(1);
  });
});

describe("extractTransaction", () => {
  it("extracts RigDug, RigSkipped and the executor-signed DeployEvent from a dig", () => {
    const x = extractTransaction(dig(), OPTS);
    expect(x.failed).toBe(false);
    expect(x.hdEvents.map((e) => e.event.kind)).toEqual(["RigDug", "RigSkipped"]);
    expect(x.hdEvents.map((e) => e.index)).toEqual([0, 1]);
    expect(x.oreDeploys).toHaveLength(1);
    const d = x.oreDeploys[0]!.event;
    expect(d.signer).toBe(EXECUTOR_PDA);
    expect(d.authority).toBe(addr(11));
    expect(d.amount * BigInt(d.totalSquares)).toBe(999_990n);
    expect(d.strategy).toBe(2n);
    expect(x.problems).toEqual([]);
  });

  it("a failed transaction contributes no events", () => {
    const x = extractTransaction(dig({ failed: true }), OPTS);
    expect(x.failed).toBe(true);
    expect(x.hdEvents).toEqual([]);
    expect(x.oreDeploys).toEqual([]);
  });

  it("ignores heads_down-shaped events emitted by another program", () => {
    const forged = encodeHdEvent({ kind: "RigDug", rig: RIG_A, roundId: 1n, lamports: 10n ** 15n, mask: 1, emaEv: 0n });
    const tx: RawTransaction = {
      slot: 1,
      blockTime: 1,
      transaction: { signatures: [sig(3)], message: { accountKeys: [addr(9), ATTACKER_PROGRAM, HD] } },
      meta: {
        err: null,
        logMessages: [
          `Program ${ATTACKER_PROGRAM} invoke [1]`,
          `Program data: ${b64(forged)}`,
          `Program ${ATTACKER_PROGRAM} success`,
        ],
        innerInstructions: [],
      },
    };
    expect(extractTransaction(tx, OPTS).hdEvents).toEqual([]);
  });

  it("ignores ORE deploys signed by anyone but the Executor PDA", () => {
    const x = extractTransaction(dig({ executorPda: addr(77) }), OPTS);
    expect(x.oreDeploys).toEqual([]);
  });

  it("rejects an ORE Log whose account is not the Board (forged DeployEvent)", () => {
    const ev = encodeOreDeployEvent({ authority: addr(11), signer: EXECUTOR_PDA, amount: 1n, mask: 1, roundId: 5n, strategy: 2n, ts: 1n });
    const data = new Uint8Array(121);
    data[0] = 8;
    data.set(ev, 1);
    const tx: RawTransaction = {
      slot: 1,
      blockTime: 1,
      transaction: { signatures: [sig(4)], message: { accountKeys: [addr(9), ORE_PROGRAM_ID, addr(50)] } },
      meta: {
        err: null,
        logMessages: [],
        innerInstructions: [{ index: 0, instructions: [{ programIdIndex: 1, accounts: [2], data: encodeBase58(data) }] }],
      },
    };
    const x = extractTransaction(tx, OPTS);
    expect(x.oreDeploys).toEqual([]);
    expect(x.problems.map((p) => p.code)).toContain("NOT_BOARD_SIGNED");
  });

  it("resolves ORE through address-lookup-table (loadedAddresses) keys", () => {
    const ev = encodeOreDeployEvent({ authority: addr(11), signer: EXECUTOR_PDA, amount: 7n, mask: 3, roundId: 5n, strategy: 2n, ts: 1n });
    const data = new Uint8Array(121);
    data[0] = 8;
    data.set(ev, 1);
    const tx: RawTransaction = {
      slot: 1,
      blockTime: 1,
      version: 0,
      transaction: { signatures: [sig(5)], message: { accountKeys: [addr(9)] } },
      meta: {
        err: null,
        logMessages: [],
        innerInstructions: [{ index: 0, instructions: [{ programIdIndex: 2, accounts: [1], data: encodeBase58(data) }] }],
        loadedAddresses: { writable: [ORE_BOARD], readonly: [ORE_PROGRAM_ID] },
      },
    };
    expect(extractTransaction(tx, OPTS).oreDeploys).toHaveLength(1);
  });

  it("records malformed heads_down events as problems instead of dropping the transaction", () => {
    const tx = buildEventTx({ signature: sig(6), slot: 2, blockTime: 2, signer: addr(9), programId: HD, events: [] });
    tx.meta!.logMessages!.splice(1, 0, `Program data: ${b64(new Uint8Array([1, 2, 3]))}`, `Program data: ${b64(new Uint8Array([42, 0]))}`);
    const x = extractTransaction(tx, OPTS);
    expect(x.hdEvents).toEqual([]);
    expect(x.problems.map((p) => p.code)).toEqual(["BAD_LENGTH"]);
    expect(x.unknownHdEventTags).toEqual([42]);
  });

  it("rejects structurally invalid envelopes with TxShapeError", () => {
    const bad = dig();
    bad.transaction.signatures = ["nope"];
    expect(() => extractTransaction(bad, OPTS)).toThrow(TxShapeError);
    const bad2 = dig();
    bad2.meta!.innerInstructions![0]!.instructions[0]!.programIdIndex = 999;
    expect(() => extractTransaction(bad2, OPTS)).toThrow(TxShapeError);
    const bad3 = dig();
    (bad3 as { slot: number }).slot = -1;
    expect(() => extractTransaction(bad3, OPTS)).toThrow(TxShapeError);
  });

  it("parses the real mainnet manual deploy (not Heads Down: signer != executor)", () => {
    const tx = JSON.parse(readFileSync(new URL("./fixtures/ore-deploy-manual-mainnet.json", import.meta.url), "utf8"));
    const x = extractTransaction(tx, OPTS);
    expect(x.failed).toBe(false);
    expect(x.oreDeploys).toEqual([]); // manual deploy by a wallet, correctly not attributed
    expect(x.problems).toEqual([]);
    // With the wallet as "executor" the very same bytes are accepted, proving the path works.
    const y = extractTransaction(tx, { programId: HD, executorPda: "9FsGp26UkKndmewwV5sNTPXfBoNP1BxywifBrpWrxpVP" });
    expect(y.oreDeploys.map((d) => d.event.roundId)).toEqual([422_675n]);
  });

  it("keeps ShiftEndedV2 and drops the ShiftEnded it supersedes (one shift is one row)", () => {
    const summary = { rig: RIG_A, shiftId: 3n, darkRounds: 9n, roundsDug: 2n, lamports: 2_010_000n, reason: 0 };
    const tx = buildEventTx({
      signature: sig(20), slot: 1, blockTime: 1, signer: addr(9), programId: HD,
      events: [{ kind: "ShiftEnded", ...summary }, { kind: "ShiftEndedV2", ...summary, startRound: 100n, endRound: 120n, mode: 1 }],
    });
    const x = extractTransaction(tx, OPTS);
    expect(x.hdEvents.map((e) => e.event.kind)).toEqual(["ShiftEndedV2"]);
    expect(x.hdEvents[0]!.index).toBe(1);
    expect(x.problems).toEqual([]);
    expect(x.hdInstructions.map((i) => i.ix.name)).toEqual(["end_shift"]);
    // A v1 program (tag 4 alone) is still counted.
    const v1 = buildEventTx({ signature: sig(21), slot: 1, blockTime: 1, signer: addr(9), programId: HD, events: [{ kind: "ShiftEnded", ...summary }] });
    expect(extractTransaction(v1, OPTS).hdEvents.map((e) => e.event.kind)).toEqual(["ShiftEnded"]);
    // Disagreeing twins: V2 kept, the disagreement recorded.
    const odd = buildEventTx({
      signature: sig(22), slot: 1, blockTime: 1, signer: addr(9), programId: HD,
      events: [{ kind: "ShiftEnded", ...summary, darkRounds: 8n }, { kind: "ShiftEndedV2", ...summary, startRound: 100n, endRound: 120n, mode: 1 }],
    });
    const y = extractTransaction(odd, OPTS);
    expect(y.hdEvents.map((e) => e.event.kind)).toEqual(["ShiftEndedV2"]);
    expect(y.problems.map((p) => p.code)).toEqual(["SHIFT_ENDED_MISMATCH"]);
  });

  it("decodes the dig instruction and tells which heartbeats were applied (their lease granted)", () => {
    const tx = buildDigTx({
      signature: sig(30), slot: 9, blockTime: 9, cranker: addr(9), programId: HD, configPda: CONFIG_PDA, executorPda: EXECUTOR_PDA,
      roundAccount: addr(10), roundId: 500n,
      rigs: [
        { rig: addr(1), authority: addr(11), automation: addr(12), miner: addr(13), heartbeat: { counter: 7n, round: 500n, lease: 3 }, outcome: { kind: "dug", perTile: 10n, mask: 1, emaEv: 1n } },
        { rig: addr(2), authority: addr(21), automation: addr(22), miner: addr(23), heartbeat: { counter: 4n, round: 499n, lease: 2 }, outcome: { kind: "skipped", error: 1 } }, // CostGate: after the heartbeat
        { rig: addr(3), authority: addr(31), automation: addr(32), miner: addr(33), heartbeat: { counter: 2n, round: 500n, lease: 3 }, outcome: { kind: "skipped", error: 7 } }, // StaleHeartbeat: refused
        { rig: addr(4), authority: addr(41), automation: addr(42), miner: addr(43), heartbeat: null, outcome: { kind: "dug", perTile: 10n, mask: 2, emaEv: 1n } }, // lease reuse
        { rig: addr(5), authority: addr(51), automation: addr(52), miner: addr(53), heartbeat: { counter: 9n, round: 500n, lease: 1 }, outcome: { kind: "skipped", error: 0x2560_000e } }, // bad signature
      ],
    });
    const x = extractTransaction(tx, OPTS);
    expect(x.problems).toEqual([]);
    expect(x.hdInstructions).toHaveLength(1);
    expect(x.hdInstructions[0]!.ix.name).toBe("dig");
    expect(x.hdInstructions[0]!.events).toHaveLength(5);
    expect(x.heartbeats.map((h) => [h.rig, h.fresh, h.applied, h.counter, h.hbRound, h.leaseRounds, h.boardRound, h.authority])).toEqual([
      [addr(1), true, true, 7n, 500n, 3, 500n, addr(11)],
      [addr(2), true, true, 4n, 499n, 2, 500n, addr(21)],
      [addr(3), true, false, 2n, 500n, 3, 500n, addr(31)],
      [addr(4), false, false, 0n, 0n, 0, 500n, addr(41)],
      [addr(5), true, false, 9n, 500n, 1, 500n, addr(51)],
    ]);
  });

  it("matches record_heartbeats entries to HeartbeatsRecorded / RigSkipped", () => {
    const tx = buildRecordTx({
      signature: sig(31), slot: 9, blockTime: 9, cranker: addr(9), programId: HD, boardRound: 600n,
      rigs: [
        { rig: addr(1), heartbeat: { counter: 3n, round: 600n, lease: 3 }, outcome: { kind: "recorded", darkRoundsAdded: 3n } },
        { rig: addr(2), heartbeat: { counter: 1n, round: 600n, lease: 3 }, outcome: { kind: "skipped", error: 14 } },
      ],
    });
    const x = extractTransaction(tx, OPTS);
    expect(x.heartbeats.map((h) => [h.kind, h.rig, h.applied, h.authority])).toEqual([
      ["record", addr(1), true, null],
      ["record", addr(2), false, null],
    ]);
    expect(x.hdEvents.map((e) => e.event.kind)).toEqual(["HeartbeatsRecorded", "RigSkipped"]);
  });

  it("reads stack_checkin from the program's golden vectors: verify mode applies heartbeats, observe mode none", () => {
    const x = extractTransaction(goldenTx("stack_checkin_heartbeat", sig(40), 40), OPTS);
    expect(x.problems).toEqual([]);
    expect(x.hdInstructions.map((h) => h.ix.name)).toEqual(["stack_checkin"]);
    // A heartbeat verified at a table logs HeartbeatsRecorded (v1.1 bytes), then the seat's StackCheckin.
    expect(x.hdEvents.map((e) => e.event.kind)).toEqual(["HeartbeatsRecorded", "HeartbeatsRecorded", "HeartbeatsRecorded"]);
    expect(x.hdExtEvents.map((e) => [e.event.name, e.event.fields.result, e.event.fields.round_id])).toEqual(Array(3).fill(["StackCheckin", 0, 422_702n]));
    expect([...x.hdEvents, ...x.hdExtEvents].map((e) => e.index).sort((a, b) => a - b)).toEqual([0, 1, 2, 3, 4, 5]);
    const rigs = goldenVector("stack_checkin_heartbeat").accounts.filter((a) => a.role.startsWith("rig[")).map((a) => a.pubkey);
    expect(rigs).toHaveLength(3);
    // Stored as `record`: the program applies it exactly as record_heartbeats does, so the haul counts its lease.
    expect(x.heartbeats.map((h) => [h.kind, h.rig, h.fresh, h.applied, h.boardRound, h.hbRound, h.leaseRounds, h.authority])).toEqual(
      rigs.map((r) => ["record", r, true, true, 422_702n, 422_702n, 1, null]),
    );

    const o = extractTransaction(goldenTx("stack_checkin_observe", sig(41), 41), OPTS);
    expect(o.problems).toEqual([]);
    expect(o.hdEvents).toEqual([]);
    expect(o.hdExtEvents.map((e) => e.event.fields.result)).toEqual([0, 0, 42]); // the third seat is broken
    expect(o.heartbeats.map((h) => [h.fresh, h.applied, h.boardRound])).toEqual(Array(3).fill([false, false, 422_703n]));
  });

  it("keeps the SKR and v1.3 events of every golden vector apart from the v1.1 events", () => {
    for (const v of GOLDEN_VECTORS.instructions.filter((i) => i.tag >= 15)) {
      const x = extractTransaction(goldenTx(v.name, sig(50 + v.step), v.step), OPTS);
      expect(x.problems, v.name).toEqual([]);
      expect(x.unknownHdEventTags, v.name).toEqual([]);
      const want = (v.litesvm.events ?? []).filter((e) => Number(e.fields.tag) >= 11).map((e) => e.event);
      expect(x.hdExtEvents.map((e) => e.event.name), v.name).toEqual(want);
    }
  });

  it("an undecodable heads_down instruction or truncated logs are reported, never guessed", () => {
    const tx = buildEventTx({ signature: sig(32), slot: 1, blockTime: 1, signer: addr(9), programId: HD, events: [], ix: { data: Uint8Array.of(6, 1), accounts: [] } });
    const x = extractTransaction(tx, OPTS);
    expect(x.problems.map((p) => p.code)).toEqual(["IX_BAD_LENGTH"]);
    const t = dig();
    t.meta!.logMessages = [...t.meta!.logMessages!.slice(0, 3), "Log truncated"];
    const y = extractTransaction(t, OPTS);
    expect(y.logsTruncated).toBe(true);
    expect(y.problems.map((p) => p.code)).toContain("IX_LOG_MISMATCH");
    expect(y.heartbeats.every((h) => h.applied === null)).toBe(true);
  });

  it("parses the real mainnet v1 reset transaction and its ResetEvent", () => {
    const tx = JSON.parse(readFileSync(new URL("./fixtures/ore-reset-mainnet-v1.json", import.meta.url), "utf8"));
    const x = extractTransaction(tx, OPTS);
    expect(x.oreResets).toHaveLength(1);
    expect(x.oreResets[0]!.event.roundId).toBe(422_680n);
    expect(x.oreResets[0]!.event.totalMiners).toBe(170n);
  });
});
