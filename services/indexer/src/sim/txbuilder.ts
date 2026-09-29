/**
 * Builds `getTransaction`-shaped JSON for heads_down transactions, byte-for-byte in the form
 * the real program will produce (INTERFACE.md): `Program data:` lines for heads_down events,
 * and ORE's DeployEvent as an inner `Log` instruction signed by the Board.
 *
 * Used by the simulator (so simulated data goes through the exact same decode path as real
 * data) and by tests. Nothing here is ever presented as real: the simulator tags every row
 * with dataset = "simulated".
 */
import { encodeBase58, decodeBase58 } from "../codec/base58.ts";
import { ByteWriter } from "../codec/bytes.ts";
import { encodeHdEvent, type HdEvent } from "../codec/events.ts";
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

export interface DigRigInput {
  rig: string;
  authority: string;
  automation: string;
  miner: string;
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
  // Fixed account order of `dig` (INTERFACE.md).
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
          { programIdIndex: hd, accounts: [], data: "7" },
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

/** A heads_down transaction that only emits events (arm_shift, end_shift, verify_seeker ...). */
export function buildEventTx(input: {
  signature: string;
  slot: number;
  blockTime: number;
  signer: string;
  programId: string;
  events: HdEvent[];
}): RawTransaction {
  const keys = [input.signer, input.programId];
  const logs = [`Program ${input.programId} invoke [1]`];
  for (const ev of input.events) logs.push(`Program data: ${b64(encodeHdEvent(ev))}`);
  logs.push(`Program ${input.programId} consumed 9000 of 200000 compute units`, `Program ${input.programId} success`);
  return {
    slot: input.slot,
    blockTime: input.blockTime,
    version: 0,
    transaction: {
      signatures: [input.signature],
      message: { accountKeys: keys, instructions: [{ programIdIndex: 1, accounts: [0], data: "1" }] },
    },
    meta: { err: null, logMessages: logs, innerInstructions: [], loadedAddresses: { writable: [], readonly: [] } },
  };
}
