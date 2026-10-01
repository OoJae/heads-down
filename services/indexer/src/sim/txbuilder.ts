/**
 * Builds `getTransaction`-shaped JSON for heads_down transactions, byte-for-byte in the form the
 * real program produces (INTERFACE.md v1.1): real instruction data and account lists for the
 * heads_down instruction, `Program data:` lines for its events, and ORE's DeployEvent as an inner
 * `Log` instruction signed by the Board.
 *
 * Used by the simulator (so simulated data goes through the exact same decode path as real data)
 * and by tests. Nothing here is ever presented as real: the simulator tags every row with
 * dataset = "simulated".
 */
import { encodeBase58, decodeBase58 } from "../codec/base58.ts";
import { ByteWriter } from "../codec/bytes.ts";
import { encodeHdEvent, type HdEvent } from "../codec/events.ts";
import { findProgramAddress, seed, addrBytes, u64le } from "../codec/pda.ts";
import type { RawInstruction, RawTransaction } from "../codec/tx.ts";
import { ORE_BOARD, ORE_CONFIG, ORE_PROGRAM_ID, ORE_TREASURY, ORE_LOG_IX_TAG } from "../constants.ts";

const SYSTEM = "11111111111111111111111111111111";
const COMPUTE_BUDGET = "ComputeBudget111111111111111111111111111111";
const SECP256R1 = "Secp256r1SigVerify1111111111111111111111111";
const IX_SYSVAR = "Sysvar1nstructions1111111111111111111111111";
const ENTROPY_VAR = "BWCaDY96Xe4WkFq1M7UiCCRcChsJ3p51L5KrGzhxgm2E";
const ENTROPY_PROGRAM = "3jSkUuYBoJzQPMEzTvkDFXCZUBksPamrVhrnHR9igu2X";

export interface OreDeployInput {
  authority: string;
  signer: string;
  amount: bigint;
  mask: number;
  roundId: bigint;
  strategy: bigint;
  ts: bigint;
}

export function encodeOreDeployEvent(d: OreDeployInput): Uint8Array {
  let squares = 0;
  for (let i = 0; i < 25; i++) if (d.mask & (1 << i)) squares++;
  return new ByteWriter(120)
    .u64(2n)
    .bytes(decodeBase58(d.authority, 32))
    .u64(d.amount)
    .u64(BigInt(d.mask))
    .u64(d.roundId)
    .bytes(decodeBase58(d.signer, 32))
    .u64(d.strategy)
    .u64(BigInt(squares))
    .i64(d.ts)
    .finish();
}

function b64(b: Uint8Array): string {
  return Buffer.from(b).toString("base64");
}

class KeyTable {
  readonly keys: string[] = [];
  idx(k: string): number {
    let i = this.keys.indexOf(k);
    if (i < 0) {
      i = this.keys.length;
      this.keys.push(k);
    }
    return i;
  }
}

// ------------------------------------------------------------------ instruction data (v1.1 §5)

/** A heartbeat carried by a dig / record_heartbeats entry. null = reuse the current lease (hb_ix 0xFF). */
export interface EntryHeartbeat {
  counter: bigint;
  round: bigint;
  lease: number;
}

export function heartbeatEntriesData(tag: 6 | 7, entries: (EntryHeartbeat | null)[], hbIx: number): Uint8Array {
  const w = new ByteWriter(2 + 20 * entries.length).u8(tag).u8(entries.length);
  entries.forEach((e, i) => {
    if (e === null) w.u8(0xff).u8(0).u64(0n).u64(0n).u8(0).u8(0);
    else w.u8(hbIx).u8(i).u64(e.counter).u64(e.round).u8(e.lease).u8(0);
  });
  return w.finish();
}

export interface PlanInput {
  maxEvCost: bigint;
  digLamports: bigint;
  split: number;
  solo: number;
  lease: number;
  flags: number;
  windowStart: bigint;
  windowEnd: bigint;
}

export const DEFAULT_PLAN: PlanInput = {
  maxEvCost: 700_000_000n,
  digLamports: 1_000_000n,
  split: 10,
  solo: 0,
  lease: 3,
  flags: 0,
  windowStart: 0n,
  windowEnd: 4_102_444_800n,
};

export function armShiftData(p: PlanInput): Uint8Array {
  return new ByteWriter(38)
    .u8(5)
    .u8(0)
    .u64(p.maxEvCost)
    .u64(p.digLamports)
    .u8(p.split)
    .u8(p.solo)
    .u8(p.lease)
    .u8(p.flags)
    .i64(p.windowStart)
    .i64(p.windowEnd)
    .finish();
}

const shiftLogPda = (rig: string, shiftId: bigint, programId: string) =>
  findProgramAddress([seed("shift"), addrBytes(rig), u64le(shiftId)], programId).address;
const configPda = (programId: string) => findProgramAddress([seed("config")], programId).address;

/** The heads_down instruction whose execution logs `events` (the first event decides). */
function defaultIx(events: HdEvent[], signer: string, programId: string, plan?: PlanInput): { data: Uint8Array; accounts: string[] } {
  const ev = events[0];
  // No event: an instruction that logs none (set_caps).
  if (!ev) return { data: new ByteWriter(41).u8(3).u64(0n).u64(0n).u64(0n).u64(0n).i64(0n).finish(), accounts: [signer, signer] };
  switch (ev.kind) {
    case "RigRegistered": {
      const w = new ByteWriter(35).u8(1).u8(2).bytes(decodeBase58(ev.rig, 32)).u8(0);
      return { data: w.finish(), accounts: [signer, ev.rig, configPda(programId), SYSTEM] };
    }
    case "SeekerVerified": {
      const seat = findProgramAddress([seed("seeker"), addrBytes(ev.sgtMint)], programId).address;
      return { data: Uint8Array.of(2), accounts: [signer, ev.rig, seat, ev.sgtMint, ev.sgtMint, SYSTEM] };
    }
    case "ShiftArmed":
      return { data: armShiftData(plan ?? DEFAULT_PLAN), accounts: [ev.rig, signer, ORE_BOARD] };
    case "ShiftEnded":
    case "ShiftEndedV2":
      return { data: Uint8Array.of(11), accounts: [signer, ev.rig, shiftLogPda(ev.rig, ev.shiftId, programId), ORE_BOARD, SYSTEM] };
    case "ShiftBroken":
      // A FREEZE that interrupts a shift logs ShiftBroken(3) from freeze_rig; every other reason is a BREAK.
      return { data: ev.reason === 3 ? Uint8Array.of(9, 0, 3) : Uint8Array.of(8, 0, ev.reason), accounts: [ev.rig, signer] };
    case "RigClosed":
      return { data: Uint8Array.of(14), accounts: [signer, ev.rig] };
    default:
      throw new Error(`buildEventTx cannot infer the instruction for ${ev.kind}; pass ix`);
  }
}

// ------------------------------------------------------------------ dig

export interface DigRigInput {
  rig: string;
  authority: string;
  automation: string;
  miner: string;
  /** The heartbeat this entry carries (default: a fresh heartbeat for the dig's round, lease 3). */
  heartbeat?: EntryHeartbeat | null;
  /** Either a real dig (RigDug + ORE DeployEvent) or a skip (RigSkipped). */
  outcome:
    | { kind: "dug"; perTile: bigint; mask: number; emaEv: bigint }
    | { kind: "skipped"; error: number };
}

export interface DigTxInput {
  signature: string;
  slot: number;
  blockTime: number;
  cranker: string;
  programId: string;
  configPda: string;
  executorPda: string;
  roundAccount: string;
  roundId: bigint;
  rigs: DigRigInput[];
  /** Simulate a failed transaction (meta.err set). */
  failed?: boolean;
}

export function buildDigTx(input: DigTxInput): RawTransaction {
  const t = new KeyTable();
  // Fixed account order of `dig` (INTERFACE.md §5).
  const fixed = [
    input.cranker,
    input.configPda,
    input.executorPda,
    ORE_BOARD,
    ORE_CONFIG,
    input.roundAccount,
    ORE_TREASURY,
    SYSTEM,
    ORE_PROGRAM_ID,
    ENTROPY_VAR,
    ENTROPY_PROGRAM,
    IX_SYSVAR,
  ];
  for (const k of fixed) t.idx(k);
  for (const r of input.rigs) {
    t.idx(r.rig);
    t.idx(r.authority);
    t.idx(r.automation);
    t.idx(r.miner);
  }
  const hd = t.idx(input.programId);
  const cb = t.idx(COMPUTE_BUDGET);
  const secp = t.idx(SECP256R1);
  const ore = t.idx(ORE_PROGRAM_ID);
  const board = t.idx(ORE_BOARD);

  const logs: string[] = [
    `Program ${COMPUTE_BUDGET} invoke [1]`,
    `Program ${COMPUTE_BUDGET} success`,
    `Program ${input.programId} invoke [1]`,
  ];
  const inner: RawInstruction[] = [];
  for (const r of input.rigs) {
    let ev: HdEvent;
    if (r.outcome.kind === "dug") {
      const { perTile, mask, emaEv } = r.outcome;
      let squares = 0;
      for (let i = 0; i < 25; i++) if (mask & (1 << i)) squares++;
      const deployData = new ByteWriter(13).u8(6).u64(perTile).u32(mask).finish();
      inner.push({ programIdIndex: ore, accounts: [t.idx(input.executorPda), t.idx(r.authority), t.idx(r.automation), board], data: encodeBase58(deployData), stackHeight: 2 });
      const logData = new Uint8Array(121);
      logData[0] = ORE_LOG_IX_TAG;
      logData.set(
        encodeOreDeployEvent({
          authority: r.authority,
          signer: input.executorPda,
          amount: perTile,
          mask,
          roundId: input.roundId,
          strategy: 2n,
          ts: BigInt(input.blockTime),
        }),
        1,
      );
      inner.push({ programIdIndex: ore, accounts: [board], data: encodeBase58(logData), stackHeight: 3 });
      logs.push(
        `Program ${ORE_PROGRAM_ID} invoke [2]`,
        `Program ${ORE_PROGRAM_ID} invoke [3]`,
        `Program ${ORE_PROGRAM_ID} consumed 443 of 150000 compute units`,
        `Program ${ORE_PROGRAM_ID} success`,
        `Program log: Round #${input.roundId}: deploying ${Number(perTile * BigInt(squares)) / 1e9} SOL to ${squares} squares`,
        `Program ${ORE_PROGRAM_ID} consumed 26000 of 160000 compute units`,
        `Program ${ORE_PROGRAM_ID} success`,
      );
      ev = { kind: "RigDug", rig: r.rig, roundId: input.roundId, lamports: perTile * BigInt(squares), mask, emaEv };
    } else {
      ev = { kind: "RigSkipped", rig: r.rig, roundId: input.roundId, error: r.outcome.error };
    }
    logs.push(`Program data: ${b64(encodeHdEvent(ev))}`);
  }
  logs.push(`Program ${input.programId} consumed ${30000 * input.rigs.length} of 1400000 compute units`);
  if (input.failed) logs.push(`Program ${input.programId} failed: custom program error: 0x7`);
  else logs.push(`Program ${input.programId} success`);

  // Default: a fresh heartbeat signed for this round, lease 3, with a counter that grows with the round.
  const entries = input.rigs.map((r, i) =>
    r.heartbeat === undefined ? { counter: input.roundId * 64n + BigInt(i) + 1n, round: input.roundId, lease: 3 } : r.heartbeat,
  );
  const digAccounts = [...fixed, ...input.rigs.flatMap((r) => [r.rig, r.authority, r.automation, r.miner])].map((k) => t.idx(k));
  return {
    slot: input.slot,
    blockTime: input.blockTime,
    version: 0,
    transaction: {
      signatures: [input.signature],
      message: {
        accountKeys: t.keys,
        instructions: [
          { programIdIndex: cb, accounts: [], data: "3DTZbgwsozUF" },
          { programIdIndex: secp, accounts: [], data: "1" },
          { programIdIndex: hd, accounts: digAccounts, data: encodeBase58(heartbeatEntriesData(6, entries, 1)) },
        ],
      },
    },
    meta: {
      err: input.failed ? { InstructionError: [2, { Custom: 7 }] } : null,
      logMessages: logs,
      innerInstructions: inner.length > 0 ? [{ index: 2, instructions: inner }] : [],
      loadedAddresses: { writable: [], readonly: [] },
    },
  };
}

// ------------------------------------------------------------------ record_heartbeats

export interface RecordRigInput {
  rig: string;
  heartbeat: EntryHeartbeat;
  /** Accepted (HeartbeatsRecorded with the dark rounds it added) or refused (RigSkipped). */
  outcome: { kind: "recorded"; darkRoundsAdded: bigint } | { kind: "skipped"; error: number };
}

export function buildRecordTx(input: {
  signature: string;
  slot: number;
  blockTime: number;
  cranker: string;
  programId: string;
  boardRound: bigint;
  rigs: RecordRigInput[];
}): RawTransaction {
  const t = new KeyTable();
  const accounts = [ORE_BOARD, IX_SYSVAR, ...input.rigs.map((r) => r.rig)];
  t.idx(input.cranker);
  for (const k of accounts) t.idx(k);
  const hd = t.idx(input.programId);
  const secp = t.idx(SECP256R1);
  const logs = [`Program ${input.programId} invoke [1]`];
  for (const r of input.rigs) {
    const ev: HdEvent =
      r.outcome.kind === "recorded"
        ? { kind: "HeartbeatsRecorded", rig: r.rig, roundId: input.boardRound, darkRoundsAdded: r.outcome.darkRoundsAdded }
        : { kind: "RigSkipped", rig: r.rig, roundId: input.boardRound, error: r.outcome.error };
    logs.push(`Program data: ${b64(encodeHdEvent(ev))}`);
  }
  logs.push(`Program ${input.programId} consumed ${8000 * input.rigs.length} of 1400000 compute units`, `Program ${input.programId} success`);
  return {
    slot: input.slot,
    blockTime: input.blockTime,
    version: 0,
    transaction: {
      signatures: [input.signature],
      message: {
        accountKeys: t.keys,
        instructions: [
          { programIdIndex: secp, accounts: [], data: "1" },
          { programIdIndex: hd, accounts: accounts.map((k) => t.idx(k)), data: encodeBase58(heartbeatEntriesData(7, input.rigs.map((r) => r.heartbeat), 0)) },
        ],
      },
    },
    meta: { err: null, logMessages: logs, innerInstructions: [], loadedAddresses: { writable: [], readonly: [] } },
  };
}

// ------------------------------------------------------------------ events-only instructions

/** A heads_down transaction whose single instruction logs `events` (arm_shift, end_shift, verify_seeker, ...). */
export function buildEventTx(input: {
  signature: string;
  slot: number;
  blockTime: number;
  signer: string;
  programId: string;
  events: HdEvent[];
  /** The instruction (default: inferred from the first event). */
  ix?: { data: Uint8Array; accounts: string[] };
  /** arm_shift plan when the instruction is inferred from a ShiftArmed. */
  plan?: PlanInput;
}): RawTransaction {
  const ix = input.ix ?? defaultIx(input.events, input.signer, input.programId, input.plan);
  const t = new KeyTable();
  t.idx(input.signer);
  const accounts = ix.accounts.map((k) => t.idx(k));
  const hd = t.idx(input.programId);
  const logs = [`Program ${input.programId} invoke [1]`];
  for (const ev of input.events) logs.push(`Program data: ${b64(encodeHdEvent(ev))}`);
  logs.push(`Program ${input.programId} consumed 9000 of 200000 compute units`, `Program ${input.programId} success`);
  return {
    slot: input.slot,
    blockTime: input.blockTime,
    version: 0,
    transaction: {
      signatures: [input.signature],
      message: { accountKeys: t.keys, instructions: [{ programIdIndex: hd, accounts, data: encodeBase58(ix.data) }] },
    },
    meta: { err: null, logMessages: logs, innerInstructions: [], loadedAddresses: { writable: [], readonly: [] } },
  };
}
