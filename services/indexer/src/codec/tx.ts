/**
 * Turns one `getTransaction` (encoding "json" or "jsonParsed") result, or one Helius *raw*
 * webhook item (same shape), into the heads_down events and ORE events it contains.
 *
 * Trust rules:
 *  - A failed transaction (`meta.err != null`) contributes NO events: its state changes and
 *    its logs were rolled back. (Showing success after an on-chain failure is an audit class.)
 *  - heads_down events are accepted only from `Program data:` lines written while the
 *    heads_down program was executing (see logs.ts).
 *  - ORE events are accepted only from inner instructions whose program is ORE, whose data
 *    starts with the Log tag and whose single account is the ORE Board. ORE's Log handler
 *    requires the Board to sign, and only ORE can sign for its Board PDA.
 *  - A DeployEvent counts as Heads Down only if `signer` is the Heads Down Executor PDA.
 */
import { decodeBase58, isAddress, isSignature } from "./base58.ts";
import { DecodeError } from "./errors.ts";
import { decodeHdEvent, type HdEvent } from "./events.ts";
import { parseProgramData } from "./logs.ts";
import { decodeOreLogInstruction, type OreDeployEvent, type OreResetEvent } from "./ore.ts";
import { ORE_BOARD, ORE_PROGRAM_ID } from "../constants.ts";

/** Solana's packet limit bounds any instruction's data. */
const MAX_IX_DATA = 1232;
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

export interface ExtractedTx {
  signature: string;
  slot: number;
  blockTime: number | null;
  failed: boolean;
  logsTruncated: boolean;
  hdEvents: Located<HdEvent>[];
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

export interface ExtractOptions {
  programId: string;
  executorPda: string;
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

  const out: ExtractedTx = {
    signature: sig,
    slot: tx.slot,
    blockTime,
    failed: meta.err !== null && meta.err !== undefined,
    logsTruncated: false,
    hdEvents: [],
    oreDeploys: [],
    oreResets: [],
    unknownHdEventTags: [],
    problems: [],
  };
  if (out.failed) return out;

  // ---- heads_down events from the logs --------------------------------------------------
  const logs = meta.logMessages;
  if (Array.isArray(logs)) {
    const parsed = parseProgramData(logs);
    out.logsTruncated = parsed.truncated;
    for (const a of parsed.anomalies) out.problems.push({ location: "logs", code: "LOG_ANOMALY", message: a });
    for (const b of parsed.badData) {
      out.problems.push({ location: `log line ${b.line}`, code: "BAD_ENCODING", message: b.error });
    }
    let hdIndex = 0;
    for (const entry of parsed.entries) {
      if (entry.programId !== opts.programId) continue;
      const index = hdIndex++;
      try {
        const ev = decodeHdEvent(entry.data);
        if (ev.kind === "Unknown") out.unknownHdEventTags.push(ev.tag);
        else out.hdEvents.push({ index, event: ev, raw: entry.data });
      } catch (e) {
        if (!(e instanceof DecodeError)) throw e;
        out.problems.push({ location: `hd event ${index}`, code: e.code, message: e.message });
      }
    }
  }

  // ---- ORE events from inner instructions ------------------------------------------------
  const staticKeys = tx.transaction.message?.accountKeys;
  if (!Array.isArray(staticKeys) || staticKeys.length > MAX_ACCOUNT_KEYS) {
    throw new TxShapeError("invalid accountKeys");
  }
  const loaded = meta.loadedAddresses ?? {};
  const keys = [
    ...staticKeys.map(keyOf),
    ...(loaded.writable ?? []).map(keyOf),
    ...(loaded.readonly ?? []).map(keyOf),
  ];
  const groups = meta.innerInstructions ?? [];
  if (!Array.isArray(groups)) throw new TxShapeError("invalid innerInstructions");
  let oreIndex = 0;
  let seen = 0;
  for (const group of groups) {
    const ixs = group?.instructions;
    if (!Array.isArray(ixs)) throw new TxShapeError("invalid inner instruction group");
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
