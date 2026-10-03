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
}

export async function pollRpcOnce(ctx: IngestContext, rpc: RpcClient, opts: RpcPollOptions): Promise<{ ingested: number; accounts: number }> {
  let ingested = 0;
  const done = new Set<string>();
  for (const address of opts.addresses) {
    // Cursor: "<slot>:<signature>" (older rows hold just the signature).
    const raw = await ctx.store.getCursor("rpc-signatures", address);
    const m = raw === null ? null : /^(\d{1,15}):(.+)$/.exec(raw);
    const cursor = m ? m[2]! : raw;
    const cursorSlot = m ? Number(m[1]) : null;
    const newestFirst = await collectNewSignatures(rpc, address, cursor, opts.maxBackfill, cursorSlot);
    const oldestFirst = newestFirst.reverse();
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
        return { ingested, accounts: 0 };
      }
      const last = chunk[chunk.length - 1]!;
      await ctx.store.setCursor("rpc-signatures", address, `${last.slot}:${last.signature}`);
    }
  }
  const accounts = await snapshotAccountsViaRpc(ctx, rpc);
  return { ingested, accounts };
}

export async function snapshotAccountsViaRpc(ctx: IngestContext, rpc: RpcClient): Promise<number> {
  const tagFilter = (tag: number, size: number) => [{ dataSize: size }, { memcmp: { offset: 0, bytes: encodeBase58(Uint8Array.of(tag)) } }];
  const scans = await Promise.all([
    rpc.getProgramAccounts(ctx.programId, tagFilter(2, 384)),
    rpc.getProgramAccounts(ctx.programId, tagFilter(3, 128)),
    rpc.getProgramAccounts(ctx.programId, tagFilter(4, 128)),
    rpc.getProgramAccounts(ctx.programId, tagFilter(1, 256)),
  ]);
  const slot = Math.min(...scans.map((s) => s.slot));
  const res = await ingestAccountSnapshot(ctx, scans.flatMap((s) => s.accounts), slot);
  return res.rigs;
}
