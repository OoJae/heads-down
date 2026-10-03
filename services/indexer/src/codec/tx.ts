/**
 * Turns one `getTransaction` (encoding "json") result, or one Helius *raw* webhook item (same
 * shape), into the heads_down events, heads_down instructions and ORE events it contains.
 *
 * Trust rules:
 *  - A failed transaction (`meta.err != null`) contributes NOTHING: its state changes and its
 *    logs were rolled back. (Showing success after an on-chain failure is an audit class.)
 *  - heads_down events are accepted only from `Program data:` lines written while the
 *    heads_down program was executing (see logs.ts).
 *  - `end_shift` emits ShiftEnded (4) and then its superset ShiftEndedV2 (10). When both are
 *    present for the same (rig, shift_id) only tag 10 is kept, so a shift is never counted
 *    twice; a disagreement between the two is recorded as a problem.
 *  - heads_down instructions (top level or CPI) are decoded from the message. Each is matched to
 *    the events its own invocation logged (the k-th heads_down `invoke` frame is the k-th
 *    heads_down instruction in execution order), which tells whether each heartbeat entry of a
 *    `dig` / `record_heartbeats` was applied (its lease granted) or refused. A `stack_checkin`
 *    entry in verify mode applies its heartbeat exactly as `record_heartbeats` does (the program
 *    logs HeartbeatsRecorded for it), so it is recorded as a `record` heartbeat too.
 *  - v1.2 / v1.3 events (Stack, Focus Bond, Gift, Bury, governance, ShiftLogClosed) are kept
 *    apart in `hdExtEvents`: nothing in the haul or the v1.1 metrics reads them.
 *  - ORE events are accepted only from inner instructions whose program is ORE, whose data
 *    starts with the Log tag and whose single account is the ORE Board. ORE's Log handler
 *    requires the Board to sign, and only ORE can sign for its Board PDA.
 *  - A DeployEvent counts as Heads Down only if `signer` is the Heads Down Executor PDA.
 */
import { decodeBase58, isAddress, isSignature } from "./base58.ts";
import { DecodeError } from "./errors.ts";
import { SKIPS_AFTER_HEARTBEAT, decodeHdEvent, type HdEvent, type HdExtEvent } from "./events.ts";
import { NO_HEARTBEAT, decodeHdInstruction, rigAccountIndex, type ArmPlan, type DecodedHdIx } from "./ix.ts";
import { parseProgramData } from "./logs.ts";
import { decodeOreLogInstruction, type OreDeployEvent, type OreResetEvent } from "./ore.ts";
import { ORE_BOARD, ORE_PROGRAM_ID } from "../constants.ts";

/** Solana's packet limit bounds any v0 instruction's data; v1 transactions go up to 4,096 B. */
const MAX_IX_DATA = 4096;
const MAX_ACCOUNT_KEYS = 512;
const MAX_INNER_IXS = 4096;

export interface RawInstruction {
  programIdIndex: number;
  accounts: number[];
  data: string;
  stackHeight?: number | null;
}

export interface RawTransaction {
  slot: number;
  blockTime?: number | null;
  version?: "legacy" | number;
  transaction: {
    signatures: string[];
    message: {
      accountKeys: (string | { pubkey: string })[];
      instructions?: unknown[];
    };
  };
  meta: {
    err: unknown;
    logMessages?: string[] | null;
    innerInstructions?: { index: number; instructions: RawInstruction[] }[] | null;
    loadedAddresses?: { writable?: string[]; readonly?: string[] } | null;
  } | null;
}

export interface Located<T> {
  /** Ordinal within the transaction (stable across re-ingestion; part of the primary key). */
  index: number;
  event: T;
  raw: Uint8Array;
}

/** One heads_down instruction, decoded, with its accounts resolved. */
export interface HdInstruction {
  /** Ordinal among the transaction's heads_down instructions, in execution order. */
  index: number;
  /** "i" (top level) or "i.j" (inner instruction j of top-level i). */
  path: string;
  ix: DecodedHdIx;
  accounts: string[];
  /** Events this invocation logged (null when the logs could not be matched, e.g. truncated). */
  events: HdEvent[] | null;
}

/** One `dig` / `record_heartbeats` / `stack_checkin` heartbeat entry, attributed to its rig. */
export interface HeartbeatUse {
  ixIndex: number;
  entryIndex: number;
  /** `stack_checkin` entries are `record`: the program applies them exactly as record_heartbeats does. */
  kind: "dig" | "record";
  rig: string;
  /** dig only: the authority passed for the rig. */
  authority: string | null;
  /** hb_ix != 0xFF: a fresh HEARTBEAT (a lease reuse carries no heartbeat). */
  fresh: boolean;
  counter: bigint;
  /** The round the phone signed for (the lease starts here). */
  hbRound: bigint;
  leaseRounds: number;
  /** Board.round_id when it landed (from the rig's RigDug / RigSkipped / HeartbeatsRecorded). */
  boardRound: bigint | null;
  /**
   * true: the heartbeat verified and its lease was granted (it is consumed even if a later step
   * skipped the rig). false: refused, or a lease reuse. null: the logs could not be matched.
   */
  applied: boolean | null;
}

export interface ArmPlanUse {
  ixIndex: number;
  rig: string;
  plan: ArmPlan;
}

export interface ExtractedTx {
  signature: string;
  slot: number;
  blockTime: number | null;
  /** accountKeys[0]. */
  feePayer: string;
  failed: boolean;
  logsTruncated: boolean;
  hdEvents: Located<HdEvent>[];
  /** v1.2 (SKR) and v1.3 events, in log order (`index` continues the same ordinal as `hdEvents`). */
  hdExtEvents: Located<HdExtEvent>[];
  hdInstructions: HdInstruction[];
  heartbeats: HeartbeatUse[];
  armPlans: ArmPlanUse[];
  /** DeployEvents whose signer is the Heads Down Executor PDA. */
  oreDeploys: Located<OreDeployEvent>[];
  oreResets: Located<OreResetEvent>[];
  unknownHdEventTags: number[];
  problems: { location: string; code: string; message: string }[];
}

export class TxShapeError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "TxShapeError";
  }
}

function keyOf(k: unknown): string {
  const s = typeof k === "string" ? k : typeof k === "object" && k !== null ? (k as { pubkey?: unknown }).pubkey : undefined;
  if (!isAddress(s)) throw new TxShapeError(`invalid account key ${String(s).slice(0, 60)}`);
  return s;
}

function isIndex(n: unknown, len: number): n is number {
  return typeof n === "number" && Number.isInteger(n) && n >= 0 && n < len;
}

function asInstruction(ix: unknown): RawInstruction | null {
  const o = ix as RawInstruction | null;
  if (typeof o !== "object" || o === null) return null;
  if (typeof o.programIdIndex !== "number" || !Array.isArray(o.accounts) || typeof o.data !== "string") return null;
  return o;
}

export interface ExtractOptions {
  programId: string;
  executorPda: string;
}

const eventRig = (e: HdEvent) => e.rig;

/** Was entry `i` (rig `rig`) of this dig / record_heartbeats applied, given the events its invocation logged? */
function heartbeatOutcome(kind: "dig" | "record", fresh: boolean, rig: string, events: HdEvent[]): { applied: boolean | null; boardRound: bigint | null } {
  const ev = events.find((e) => eventRig(e) === rig && (e.kind === "RigDug" || e.kind === "RigSkipped" || e.kind === "HeartbeatsRecorded"));
  if (!ev) return { applied: null, boardRound: null };
  const boardRound = (ev as { roundId: bigint }).roundId;
  if (!fresh) return { applied: false, boardRound };
  if (kind === "record") return { applied: ev.kind === "HeartbeatsRecorded", boardRound };
  if (ev.kind === "RigDug") return { applied: true, boardRound };
  if (ev.kind === "RigSkipped") return { applied: SKIPS_AFTER_HEARTBEAT.has(ev.error), boardRound };
  return { applied: null, boardRound };
}

export function extractTransaction(tx: RawTransaction, opts: ExtractOptions): ExtractedTx {
  if (typeof tx !== "object" || tx === null) throw new TxShapeError("transaction is not an object");
  const sig = tx.transaction?.signatures?.[0];
  if (!isSignature(sig)) throw new TxShapeError("missing or invalid signature");
  if (!Number.isSafeInteger(tx.slot) || tx.slot < 0) throw new TxShapeError("invalid slot");
  const blockTime = tx.blockTime ?? null;
  if (blockTime !== null && (!Number.isSafeInteger(blockTime) || blockTime < 0)) {
    throw new TxShapeError("invalid blockTime");
  }
  const meta = tx.meta;
  if (meta === null || typeof meta !== "object") throw new TxShapeError("missing meta");

  const staticKeys = tx.transaction.message?.accountKeys;
  if (!Array.isArray(staticKeys) || staticKeys.length === 0 || staticKeys.length > MAX_ACCOUNT_KEYS) {
    throw new TxShapeError("invalid accountKeys");
  }
  const out: ExtractedTx = {
    signature: sig,
    slot: tx.slot,
    blockTime,
    feePayer: keyOf(staticKeys[0]),
    failed: meta.err !== null && meta.err !== undefined,
    logsTruncated: false,
    hdEvents: [],
    hdExtEvents: [],
    hdInstructions: [],
    heartbeats: [],
    armPlans: [],
    oreDeploys: [],
    oreResets: [],
    unknownHdEventTags: [],
    problems: [],
  };
  if (out.failed) return out;

  const loaded = meta.loadedAddresses ?? {};
  const keys = [
    ...staticKeys.map(keyOf),
    ...(loaded.writable ?? []).map(keyOf),
    ...(loaded.readonly ?? []).map(keyOf),
  ];
  const groups = meta.innerInstructions ?? [];
  if (!Array.isArray(groups)) throw new TxShapeError("invalid innerInstructions");

  // ---- heads_down events from the logs --------------------------------------------------
  const hdFrames: number[] = [];
  const eventsByFrame = new Map<number, HdEvent[]>();
  const extByFrame = new Map<number, HdExtEvent[]>();
  const logs = meta.logMessages;
  let logsUsable = false;
  if (Array.isArray(logs)) {
    const parsed = parseProgramData(logs);
    logsUsable = parsed.anomalies.length === 0 && !parsed.truncated;
    out.logsTruncated = parsed.truncated;
    parsed.frames.forEach((f, k) => {
      if (f.programId === opts.programId) hdFrames.push(k);
    });
    for (const a of parsed.anomalies) out.problems.push({ location: "logs", code: "LOG_ANOMALY", message: a });
    for (const b of parsed.badData) {
      out.problems.push({ location: `log line ${b.line}`, code: "BAD_ENCODING", message: b.error });
    }
    let hdIndex = 0;
    const decoded: { located: Located<HdEvent>; frame: number }[] = [];
    for (const entry of parsed.entries) {
      if (entry.programId !== opts.programId) continue;
      const index = hdIndex++;
      try {
        const ev = decodeHdEvent(entry.data);
        if (ev.kind === "Unknown") out.unknownHdEventTags.push(ev.tag);
        else if (ev.kind === "Ext") {
          out.hdExtEvents.push({ index, event: ev, raw: entry.data });
          const l = extByFrame.get(entry.frame);
          if (l) l.push(ev);
          else extByFrame.set(entry.frame, [ev]);
        } else decoded.push({ located: { index, event: ev, raw: entry.data }, frame: entry.frame });
      } catch (e) {
        if (!(e instanceof DecodeError)) throw e;
        out.problems.push({ location: `hd event ${index}`, code: e.code, message: e.message });
      }
    }
    // ShiftEndedV2 supersedes the ShiftEnded logged just before it in the same invocation.
    for (const d of decoded) {
      const ev = d.located.event;
      if (ev.kind === "ShiftEnded") {
        const v2 = decoded.find(
          (o) => o.frame === d.frame && o.located.event.kind === "ShiftEndedV2" && o.located.event.rig === ev.rig && o.located.event.shiftId === ev.shiftId,
        );
        if (v2) {
          const w = v2.located.event as Extract<HdEvent, { kind: "ShiftEndedV2" }>;
          if (w.darkRounds !== ev.darkRounds || w.roundsDug !== ev.roundsDug || w.lamports !== ev.lamports || w.reason !== ev.reason) {
            out.problems.push({ location: `hd event ${d.located.index}`, code: "SHIFT_ENDED_MISMATCH", message: "ShiftEnded and ShiftEndedV2 disagree; V2 kept" });
          }
          continue;
        }
      }
      out.hdEvents.push(d.located);
      const l = eventsByFrame.get(d.frame);
      if (l) l.push(ev);
      else eventsByFrame.set(d.frame, [ev]);
    }
  }

  // ---- heads_down instructions (execution order: top-level i, then its inner CPIs) ----------
  const topLevel = tx.transaction.message.instructions;
  const innerByIndex = new Map<number, RawInstruction[]>();
  for (const g of groups) {
    if (!Array.isArray(g?.instructions) || !Number.isInteger(g.index)) throw new TxShapeError("invalid inner instruction group");
    innerByIndex.set(g.index, g.instructions);
  }
  const hdRaw: { path: string; ix: RawInstruction }[] = [];
  if (Array.isArray(topLevel)) {
    topLevel.forEach((raw, i) => {
      const ix = asInstruction(raw);
      if (ix && isIndex(ix.programIdIndex, keys.length) && keys[ix.programIdIndex] === opts.programId) hdRaw.push({ path: `${i}`, ix });
      (innerByIndex.get(i) ?? []).forEach((inner, j) => {
        const c = asInstruction(inner);
        if (c && isIndex(c.programIdIndex, keys.length) && keys[c.programIdIndex] === opts.programId) hdRaw.push({ path: `${i}.${j}`, ix: c });
      });
    });
  }
  const framesMatch = logsUsable && hdFrames.length === hdRaw.length;
  if (hdRaw.length > 0 && Array.isArray(logs) && !framesMatch) {
    out.problems.push({
      location: "instructions",
      code: "IX_LOG_MISMATCH",
      message: `${hdRaw.length} heads_down instructions but ${hdFrames.length} heads_down invocations in the logs`,
    });
  }
  hdRaw.forEach(({ path, ix: raw }, index) => {
    let data: Uint8Array;
    let ix: DecodedHdIx;
    try {
      data = decodeBase58(raw.data, MAX_IX_DATA);
      ix = decodeHdInstruction(data);
    } catch (e) {
      if (!(e instanceof DecodeError)) throw e;
      out.problems.push({ location: `hd ix ${path}`, code: `IX_${e.code}`, message: e.message });
      return;
    }
    if (!raw.accounts.every((a) => isIndex(a, keys.length))) throw new TxShapeError("instruction account index out of range");
    const accounts = raw.accounts.map((a) => keys[a]!);
    const events = framesMatch ? (eventsByFrame.get(hdFrames[index]!) ?? []) : null;
    out.hdInstructions.push({ index, path, ix, accounts, events });

    if (ix.name === "dig" || ix.name === "record_heartbeats") {
      const kind = ix.name === "dig" ? "dig" : "record";
      ix.entries.forEach((entry, entryIndex) => {
        const at = rigAccountIndex(ix, entryIndex)!;
        const rig = accounts[at];
        if (rig === undefined) return; // the program would have failed: InvalidInstruction
        const fresh = entry.hbIx !== NO_HEARTBEAT;
        const outcome = events ? heartbeatOutcome(kind, fresh, rig, events) : { applied: null, boardRound: null };
        if (events && outcome.applied === null) {
          out.problems.push({ location: `hd ix ${path} entry ${entryIndex}`, code: "HEARTBEAT_UNMATCHED", message: `no dig outcome event for rig ${rig}` });
        }
        out.heartbeats.push({
          ixIndex: index,
          entryIndex,
          kind,
          rig,
          authority: kind === "dig" ? (accounts[at + 1] ?? null) : null,
          fresh,
          counter: entry.counter,
          hbRound: entry.roundId,
          leaseRounds: entry.leaseRounds,
          boardRound: outcome.boardRound,
          applied: outcome.applied,
        });
      });
    } else if (ix.name === "stack_checkin") {
      const ext = framesMatch ? (extByFrame.get(hdFrames[index]!) ?? []) : null;
      ix.entries.forEach((entry, entryIndex) => {
        const rig = accounts[rigAccountIndex(ix, entryIndex)!];
        if (rig === undefined) return; // the program would have failed: exactly 3 + 2n accounts
        const fresh = entry.hbIx !== NO_HEARTBEAT;
        // One StackCheckin per seat carries Board.round_id; a heartbeat verified here also logged
        // HeartbeatsRecorded for the rig. No HeartbeatsRecorded: observe mode, or the seat rule
        // stopped before the heartbeat (it was not consumed).
        const checkin = ext?.find((e) => e.name === "StackCheckin" && e.fields.rig === rig);
        const recorded = events?.some((e) => e.kind === "HeartbeatsRecorded" && e.rig === rig) ?? false;
        if (events && ext && checkin === undefined) {
          out.problems.push({ location: `hd ix ${path} entry ${entryIndex}`, code: "HEARTBEAT_UNMATCHED", message: `no StackCheckin event for rig ${rig}` });
        }
        out.heartbeats.push({
          ixIndex: index,
          entryIndex,
          kind: "record",
          rig,
          authority: null,
          fresh,
          counter: entry.counter,
          hbRound: entry.roundId,
          leaseRounds: entry.leaseRounds,
          boardRound: checkin ? (checkin.fields.round_id as bigint) : null,
          applied: checkin ? fresh && recorded : null,
        });
      });
    } else if (ix.name === "arm_shift" && ix.plan && accounts[0] !== undefined) {
      out.armPlans.push({ ixIndex: index, rig: accounts[0], plan: ix.plan });
    }
  });

  // ---- ORE events from inner instructions ------------------------------------------------
  let oreIndex = 0;
  let seen = 0;
  for (const group of groups) {
    const ixs = group.instructions;
    for (const ix of ixs) {
      if (++seen > MAX_INNER_IXS) throw new TxShapeError("too many inner instructions");
      if (!isIndex(ix?.programIdIndex, keys.length)) throw new TxShapeError("programIdIndex out of range");
      if (keys[ix.programIdIndex] !== ORE_PROGRAM_ID) continue;
      if (typeof ix.data !== "string" || !Array.isArray(ix.accounts)) throw new TxShapeError("bad inner ix");
      let data: Uint8Array;
      try {
        data = decodeBase58(ix.data, MAX_IX_DATA);
      } catch (e) {
        if (!(e instanceof DecodeError)) throw e;
        out.problems.push({ location: `ore inner ix`, code: e.code, message: e.message });
        continue;
      }
      let ev;
      const index = oreIndex;
      try {
        ev = decodeOreLogInstruction(data);
      } catch (e) {
        if (!(e instanceof DecodeError)) throw e;
        oreIndex++;
        out.problems.push({ location: `ore log ${index}`, code: e.code, message: e.message });
        continue;
      }
      if (ev === null) continue; // some other ORE instruction (deploy itself, checkpoint, ...)
      oreIndex++;
      const acct = ix.accounts.length === 1 && isIndex(ix.accounts[0], keys.length) ? keys[ix.accounts[0]] : undefined;
      if (acct !== ORE_BOARD) {
        out.problems.push({ location: `ore log ${index}`, code: "NOT_BOARD_SIGNED", message: "ORE Log without the Board as its only account" });
        continue;
      }
      if (ev.kind === "OreDeploy") {
        if (ev.signer === opts.executorPda) out.oreDeploys.push({ index, event: ev, raw: data.subarray(1) });
      } else if (ev.kind === "OreReset") {
        out.oreResets.push({ index, event: ev, raw: data.subarray(1) });
      }
    }
  }
  return out;
}
