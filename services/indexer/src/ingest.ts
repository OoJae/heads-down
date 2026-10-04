/**
 * Ingestion pipeline shared by every source (RPC polling, Helius webhooks, simulator):
 * raw transaction JSON -> extractTransaction -> Store. Account snapshots and ORE rounds too.
 */
import { UNINDEXED_ACCOUNT_TAGS, decodeHdAccount, verifyAccount, type ConfigAccount, type RigAccount, type SeekerSeatAccount, type ShiftLogAccount } from "./codec/accounts.ts";
import { encodeBase58 } from "./codec/base58.ts";
import { DecodeError } from "./codec/errors.ts";
import { TxShapeError, extractTransaction, type ExtractedTx, type RawTransaction } from "./codec/tx.ts";
import type { Store } from "./store/store.ts";
import { RpcClient, collectNewSignatures, mapLimit, type RawAccount } from "./sources/rpc.ts";

export interface IngestContext {
  store: Store;
  programId: string;
  executorPda: string;
  log?: (msg: string, fields?: Record<string, unknown>) => void;
}

export async function ingestRawTransactions(ctx: IngestContext, raws: RawTransaction[], source: string): Promise<number> {
  const extracted: ExtractedTx[] = [];
  for (const raw of raws) {
    try {
      extracted.push(extractTransaction(raw, { programId: ctx.programId, executorPda: ctx.executorPda }));
    } catch (e) {
      if (!(e instanceof TxShapeError) && !(e instanceof DecodeError)) throw e;
      const subject = typeof raw?.transaction?.signatures?.[0] === "string" ? raw.transaction.signatures[0].slice(0, 90) : "unknown";
      await ctx.store.recordProblem(subject, source, "TX_SHAPE", e.message);
    }
  }
  return ctx.store.ingestTxs(extracted, source);
}

/** Decodes and verifies a full getProgramAccounts scan, then replaces the snapshot. */
export async function ingestAccountSnapshot(ctx: IngestContext, accounts: RawAccount[], contextSlot: number): Promise<{ rigs: number; problems: number }> {
  const snap = {
    rigs: [] as { address: string; account: RigAccount; data: Uint8Array }[],
    shiftLogs: [] as { address: string; account: ShiftLogAccount; data: Uint8Array }[],
    seats: [] as { address: string; account: SeekerSeatAccount; data: Uint8Array }[],
    config: null as { address: string; account: ConfigAccount; data: Uint8Array } | null,
  };
  let problems = 0;
  for (const a of accounts) {
    // SKR accounts and tombstones are owned by the program but not part of the snapshot.
    if (a.data.length > 0 && UNINDEXED_ACCOUNT_TAGS.has(a.data[0]!)) continue;
    try {
      const acc = decodeHdAccount(a.data);
      const bad = verifyAccount(acc, a.address, a.owner, ctx.programId);
      if (bad) throw new DecodeError("BAD_FIELD", bad);
      if (acc.kind === "Rig") snap.rigs.push({ address: a.address, account: acc, data: a.data });
      else if (acc.kind === "ShiftLog") snap.shiftLogs.push({ address: a.address, account: acc, data: a.data });
      else if (acc.kind === "SeekerSeat") snap.seats.push({ address: a.address, account: acc, data: a.data });
      else snap.config = { address: a.address, account: acc, data: a.data };
    } catch (e) {
      if (!(e instanceof DecodeError)) throw e;
      problems++;
      await ctx.store.recordProblem(a.address, "account", e.code, e.message);
    }
  }
  await ctx.store.replaceAccounts(snap, contextSlot);
  return { rigs: snap.rigs.length, problems };
}

// ------------------------------------------------------------------ RPC polling source

export interface RpcPollOptions {
  /** Addresses whose signatures are polled (program id and Executor PDA). */
  addresses: string[];
  /** First run only: how many recent signatures per address to backfill. */
  maxBackfill: number;
  concurrency: number;
  /** Polls between account snapshots when no new signature asks for one (1 or absent: every poll). */
  snapshotEvery?: number;
}

/**
 * What a poller remembers between polls to decide when the account snapshot is due. It lives in
 * memory, so a process always starts with a snapshot.
 */
export interface SnapshotState {
  /** A snapshot is owed: none was taken yet, or a transaction succeeded that none has seen. */
  due: boolean;
  /** Highest slot among those transactions: a scan answered from an older slot has not seen them. */
  dueSlot: number;
  /** Polls since the last snapshot. */
  polls: number;
}

export const newSnapshotState = (): SnapshotState => ({ due: true, dueSlot: 0, polls: 0 });

/**
 * One poll: new signatures of `opts.addresses`, their transactions, then the account snapshot.
 *
 * The snapshot is four getProgramAccounts scans, so it is taken only when it can have changed:
 * on the first poll with a given `state`, on a poll that sees a new successful signature, and
 * otherwise every `opts.snapshotEvery` polls. `accounts` is null when it was not taken. Without
 * `state` every call takes it.
 *
 * New signatures are enough because only the owning program can write, tag or close the accounts
 * the scans read, and a transaction that runs the program is in the program id's signature list.
 * The periodic snapshot covers the rest (README, "Ingest loop and RPC use").
 */
export async function pollRpcOnce(
  ctx: IngestContext,
  rpc: RpcClient,
  opts: RpcPollOptions,
  state: SnapshotState = newSnapshotState(),
): Promise<{ ingested: number; accounts: number | null }> {
  let ingested = 0;
  const done = new Set<string>();
  state.polls++;
  for (const address of opts.addresses) {
    // Cursor: "<slot>:<signature>" (older rows hold just the signature).
    const raw = await ctx.store.getCursor("rpc-signatures", address);
    const m = raw === null ? null : /^(\d{1,15}):(.+)$/.exec(raw);
    const cursor = m ? m[2]! : raw;
    const cursorSlot = m ? Number(m[1]) : null;
    const newestFirst = await collectNewSignatures(rpc, address, cursor, opts.maxBackfill, cursorSlot);
    const oldestFirst = newestFirst.reverse();
    // Noted before anything is fetched: the snapshot stays owed if the rest of this poll fails.
    for (const s of oldestFirst) {
      if (s.err !== null && s.err !== undefined) continue; // a failed transaction changed no account data
      state.due = true;
      if (s.slot > state.dueSlot) state.dueSlot = s.slot;
    }
    for (let i = 0; i < oldestFirst.length; i += 50) {
      const chunk = oldestFirst.slice(i, i + 50);
      const wanted = chunk.filter((s) => !done.has(s.signature) && (s.err === null || s.err === undefined));
      const txs = await mapLimit(wanted, opts.concurrency, (s) => rpc.getTransaction(s.signature));
      const missing = txs.findIndex((t) => t === null);
      const ready = (missing < 0 ? txs : txs.slice(0, missing)) as RawTransaction[];
      ingested += await ingestRawTransactions(ctx, ready, "rpc");
      for (const t of ready) done.add(t.transaction.signatures[0]!);
      if (missing >= 0) {
        // Not yet served by this RPC node: keep the cursor before it and retry next poll.
        const lastOk = missing === 0 ? null : wanted[missing - 1]!;
        if (lastOk) await ctx.store.setCursor("rpc-signatures", address, `${lastOk.slot}:${lastOk.signature}`);
        ctx.log?.("rpc: transaction not yet available, will retry", { address });
        return { ingested, accounts: null };
      }
      const last = chunk[chunk.length - 1]!;
      await ctx.store.setCursor("rpc-signatures", address, `${last.slot}:${last.signature}`);
    }
  }
  if (!state.due && state.polls < (opts.snapshotEvery ?? 1)) return { ingested, accounts: null };
  const snap = await snapshotAccountsViaRpc(ctx, rpc);
  state.polls = 0;
  // A scan answered from a slot before the newest signature (a node that lags) has not seen it: scan again next poll.
  state.due = snap.slot < state.dueSlot;
  return { ingested, accounts: snap.rigs };
}

/** The four scans (Rig, SeekerSeat, ShiftLog, Config); `slot` is the oldest of their context slots. */
export async function snapshotAccountsViaRpc(ctx: IngestContext, rpc: RpcClient): Promise<{ rigs: number; slot: number }> {
  const tagFilter = (tag: number, size: number) => [{ dataSize: size }, { memcmp: { offset: 0, bytes: encodeBase58(Uint8Array.of(tag)) } }];
  const scans = await Promise.all([
    rpc.getProgramAccounts(ctx.programId, tagFilter(2, 384)),
    rpc.getProgramAccounts(ctx.programId, tagFilter(3, 128)),
    rpc.getProgramAccounts(ctx.programId, tagFilter(4, 128)),
    rpc.getProgramAccounts(ctx.programId, tagFilter(1, 256)),
  ]);
  const slot = Math.min(...scans.map((s) => s.slot));
  const res = await ingestAccountSnapshot(ctx, scans.flatMap((s) => s.accounts), slot);
  return { rigs: res.rigs, slot };
}
