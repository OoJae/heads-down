import { readFileSync } from "node:fs";
import { encodeBase58 } from "../src/codec/base58.ts";
import { fromHex } from "../src/codec/bytes.ts";
import { decodeHdEvent, type HdEvent, type HdExtEvent } from "../src/codec/events.ts";
import type { RawTransaction } from "../src/codec/tx.ts";
import { buildEventTx } from "../src/sim/txbuilder.ts";

export const addr = (n: number) => encodeBase58(Uint8Array.from({ length: 32 }, (_, i) => (i * 7 + n) & 0xff));
export const sig = (n: number) => encodeBase58(Uint8Array.from({ length: 64 }, (_, i) => (i * 13 + n + 1) & 0xff));

export interface GoldenVector {
  name: string;
  tag: number;
  step: number;
  data_hex: string;
  accounts: { role: string; pubkey: string }[];
  transaction: { fee_payer: string };
  litesvm: { events?: { event: string; hex: string; fields: Record<string, string | number> }[] };
}
/** The program's golden instruction vectors (real LiteSVM runs on a fork of live mainnet ORE). */
export const GOLDEN_VECTORS = JSON.parse(readFileSync(new URL("../../../programs/heads-down/vectors/instructions.json", import.meta.url), "utf8")) as {
  program_id: string;
  instructions: GoldenVector[];
};
export function goldenVector(name: string): GoldenVector {
  const v = GOLDEN_VECTORS.instructions.find((i) => i.name === name);
  if (!v) throw new Error(`no golden vector ${name}`);
  return v;
}
/** A transaction rebuilt from a golden vector: its instruction data, its accounts and the events the program logged. */
export function goldenTx(name: string, signature: string, slot: number): RawTransaction {
  const v = goldenVector(name);
  const events = (v.litesvm.events ?? []).map((e) => decodeHdEvent(fromHex(e.hex)) as HdEvent | HdExtEvent);
  return buildEventTx({
    signature,
    slot,
    blockTime: 1_790_800_000 + slot,
    signer: v.transaction.fee_payer,
    programId: GOLDEN_VECTORS.program_id,
    events,
    ix: { data: fromHex(v.data_hex), accounts: v.accounts.map((a) => a.pubkey) },
  });
}

export interface FakeChain {
  /** address -> signatures, oldest first */
  sigs: Map<string, { signature: string; slot: number; err: unknown }[]>;
  txs: Map<string, RawTransaction>;
  accounts: { address: string; owner: string; data: Uint8Array }[];
  slot: number;
  calls: { method: string; params: unknown[] }[];
  /** Respond with HTTP 429 to the next N calls. */
  throttleNext: number;
  /** Signatures getTransaction pretends not to have yet. */
  unavailable: Set<string>;
  /** Signatures a short-history node no longer knows (getSignaturesForAddress `until` fails). */
  pruned: Set<string>;
}

export function fakeChain(): FakeChain {
  return { sigs: new Map(), txs: new Map(), accounts: [], slot: 1000, calls: [], throttleNext: 0, unavailable: new Set(), pruned: new Set() };
}

export function addTx(chain: FakeChain, tx: RawTransaction, addresses: string[]) {
  const s = tx.transaction.signatures[0]!;
  chain.txs.set(s, tx);
  for (const a of addresses) {
    const l = chain.sigs.get(a) ?? [];
    l.push({ signature: s, slot: tx.slot, err: tx.meta?.err ?? null });
    chain.sigs.set(a, l);
  }
}

/** A fetch() that implements the handful of JSON-RPC methods the indexer uses. */
export function fakeRpcFetch(chain: FakeChain): typeof fetch {
  return (async (_url: string | URL | Request, init?: RequestInit) => {
    const req = JSON.parse(String(init?.body));
    chain.calls.push({ method: req.method, params: req.params });
    if (chain.throttleNext > 0) {
      chain.throttleNext--;
      return new Response("slow down", { status: 429 });
    }
    const ok = (result: unknown) => new Response(JSON.stringify({ jsonrpc: "2.0", id: req.id, result }), { status: 200 });
    switch (req.method) {
      case "getSignaturesForAddress": {
        const [address, opts] = req.params as [string, { before?: string; until?: string; limit: number }];
        if (opts.until && chain.pruned.has(opts.until)) {
          return new Response(JSON.stringify({ jsonrpc: "2.0", id: req.id, error: { code: -32009, message: `Transaction ${opts.until} not found` } }), { status: 200 });
        }
        const newestFirst = [...(chain.sigs.get(address) ?? [])].reverse().filter((s) => !chain.pruned.has(s.signature));
        let start = 0;
        if (opts.before) start = newestFirst.findIndex((s) => s.signature === opts.before) + 1;
        let end = newestFirst.length;
        if (opts.until) {
          const u = newestFirst.findIndex((s) => s.signature === opts.until);
          if (u >= 0) end = u;
        }
        return ok(newestFirst.slice(start, end).slice(0, opts.limit).map((s) => ({ ...s, blockTime: null, memo: null })));
      }
      case "getTransaction": {
        const [s] = req.params as [string];
        if (chain.unavailable.has(s)) return ok(null);
        return ok(chain.txs.get(s) ?? null);
      }
      case "getMultipleAccounts": {
        const [addresses] = req.params as [string[]];
        const value = addresses.map((k) => {
          const a = chain.accounts.find((x) => x.address === k);
          return a ? { data: [Buffer.from(a.data).toString("base64"), "base64"], owner: a.owner, lamports: 1, executable: false, space: a.data.length } : null;
        });
        return ok({ context: { slot: chain.slot }, value });
      }
      case "getProgramAccounts": {
        const [, cfg] = req.params as [string, { filters: ({ dataSize?: number } | { memcmp?: { offset: number; bytes: string } })[] }];
        const size = (cfg.filters.find((f) => "dataSize" in f) as { dataSize: number }).dataSize;
        const tagB58 = (cfg.filters.find((f) => "memcmp" in f) as { memcmp: { bytes: string } }).memcmp.bytes;
        const tag = "123456789".indexOf(tagB58); // base58 of a single byte n < 9 is the digit n+1
        const value = chain.accounts
          .filter((a) => a.data.length === size && a.data[0] === tag)
          .map((a) => ({ pubkey: a.address, account: { data: [Buffer.from(a.data).toString("base64"), "base64"], owner: a.owner, lamports: 1, executable: false, space: a.data.length } }));
        return ok({ context: { slot: chain.slot }, value });
      }
      default:
        return new Response(JSON.stringify({ jsonrpc: "2.0", id: req.id, error: { code: -32601, message: "method not found" } }), { status: 200 });
    }
  }) as typeof fetch;
}
