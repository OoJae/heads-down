/**
 * ORE rounds from api.ore.com `/events/reset` (newest first, 100 per page). Each item is
 * `[reset_tx_signature_bytes[64], ResetEvent]`, so every round we store keeps the signature
 * of the on-chain reset transaction: anyone can open it and check `total_miners`.
 *
 * The API is a convenience, not a trust root. With an RPC client configured, a sample of new
 * rounds per poll is re-read from chain (the ResetEvent inside the reset transaction's ORE Log
 * instruction); a mismatch is recorded and the chain value wins.
 *
 * u64 fields (e.g. `rng`) exceed 2^53, so JSON is parsed with source-text access into BigInt.
 */
import { encodeBase58, isSignature } from "../codec/base58.ts";
import { DecodeError } from "../codec/errors.ts";
import { U64_MAX } from "../codec/bytes.ts";
import { normalizeWinningSquare, type OreResetEvent } from "../codec/ore.ts";
import { extractTransaction } from "../codec/tx.ts";
import type { IngestContext } from "../ingest.ts";
import type { RpcClient } from "./rpc.ts";

export const ORE_API = "https://api.ore.com";
const USER_AGENT = "HeadsDown-indexer/0.1 (+https://github.com/heads-down; public traction dashboard)";

/** JSON.parse that turns every integer literal into a BigInt (exact u64). */
export function parseJsonBig(text: string): unknown {
  return JSON.parse(text, function (_key, value, ctx?: { source?: string }) {
    if (typeof value === "number" && ctx?.source !== undefined && /^-?\d+$/.test(ctx.source)) return BigInt(ctx.source);
    return value;
  });
}

function u64(v: unknown, field: string): bigint {
  if (typeof v !== "bigint" || v < 0n || v > U64_MAX) throw new DecodeError("BAD_FIELD", `${field} is not a u64`);
  return v;
}

function byteArray(v: unknown, len: number, field: string): Uint8Array {
  if (!Array.isArray(v) || v.length !== len) throw new DecodeError("BAD_FIELD", `${field} must be ${len} bytes`);
  const out = new Uint8Array(len);
  for (let i = 0; i < len; i++) {
    const b = v[i];
    if (typeof b !== "bigint" || b < 0n || b > 255n) throw new DecodeError("BAD_FIELD", `${field}[${i}]`);
    out[i] = Number(b);
  }
  return out;
}

export function parseResetItem(item: unknown): { event: OreResetEvent; resetSignature: string } {
  if (!Array.isArray(item) || item.length !== 2) throw new DecodeError("BAD_FIELD", "reset item must be [signature, event]");
  const signature = encodeBase58(byteArray(item[0], 64, "signature"));
  if (!isSignature(signature)) throw new DecodeError("BAD_FIELD", "signature");
  const e = item[1] as Record<string, unknown>;
  if (typeof e !== "object" || e === null) throw new DecodeError("BAD_FIELD", "event");
  const ts = u64(e.ts, "ts");
  const miners = e.total_miners ?? e.num_winners; // api.ore.com names ResetEvent.total_miners "num_winners"
  return {
    resetSignature: signature,
    event: {
      kind: "OreReset",
      roundId: u64(e.round_id, "round_id"),
      startSlot: u64(e.start_slot, "start_slot"),
      endSlot: u64(e.end_slot, "end_slot"),
      winningSquare: normalizeWinningSquare(u64(e.winning_square, "winning_square")),
      topMiner: encodeBase58(byteArray(e.top_miner, 32, "top_miner")),
      totalMiners: u64(miners, "total_miners"),
      motherlode: u64(e.motherlode, "motherlode"),
      totalDeployed: u64(e.total_deployed, "total_deployed"),
      totalVaulted: u64(e.total_vaulted, "total_vaulted"),
      totalWinnings: u64(e.total_winnings, "total_winnings"),
      totalMinted: u64(e.total_minted, "total_minted"),
      ts,
      rng: u64(e.rng, "rng"),
      deployedWinningSquare: u64(e.deployed_winning_square, "deployed_winning_square"),
    },
  };
}

export interface OreApiOptions {
  baseUrl?: string;
  /** Backfill no further back than this unix time. */
  since: number;
  maxPages: number;
  /** How many new rounds per poll to re-verify on chain (0 = none). */
  verifySample: number;
  fetchImpl?: typeof fetch;
  sleepMs?: number;
}

async function getPage(base: string, page: number, f: typeof fetch): Promise<unknown[]> {
  const res = await f(`${base}/events/reset?page=${page}&limit=100`, {
    headers: { "user-agent": USER_AGENT, accept: "application/json" },
    signal: AbortSignal.timeout(20_000),
  });
  if (!res.ok) throw new Error(`api.ore.com /events/reset page ${page}: HTTP ${res.status}`);
  const text = await res.text();
  if (text.length > 8 * 1024 * 1024) throw new Error("api.ore.com response too large");
  const body = parseJsonBig(text);
  if (!Array.isArray(body)) throw new Error("api.ore.com: expected an array");
  return body;
}

export async function pollOreRounds(
  ctx: IngestContext,
  opts: OreApiOptions,
  rpc: RpcClient | null,
): Promise<{ stored: number; verified: number; mismatches: number }> {
  const base = opts.baseUrl ?? ORE_API;
  const f = opts.fetchImpl ?? fetch;
  const newest = await ctx.store.getCursor("ore-api", "newest-round");
  const newestRound = newest === null ? null : BigInt(newest);
  const fresh: { event: OreResetEvent; resetSignature: string }[] = [];
  for (let page = 0; page < opts.maxPages; page++) {
    const items = await getPage(base, page, f);
    let reachedKnown = false;
    for (const it of items) {
      try {
        const r = parseResetItem(it);
        if (newestRound !== null && r.event.roundId <= newestRound) reachedKnown = true;
        else if (Number(r.event.ts) >= opts.since) fresh.push(r);
        else reachedKnown = true;
      } catch (e) {
        if (!(e instanceof DecodeError)) throw e;
        await ctx.store.recordProblem(`ore-api page ${page}`, "reset item", e.code, e.message);
      }
    }
    if (reachedKnown || items.length < 100) break;
    if (opts.sleepMs) await new Promise((r) => setTimeout(r, opts.sleepMs));
  }
  if (fresh.length === 0) return { stored: 0, verified: 0, mismatches: 0 };

  let verified = 0;
  let mismatches = 0;
  if (rpc && opts.verifySample > 0) {
    const sample = [...fresh].sort((a, b) => (a.event.roundId < b.event.roundId ? 1 : -1)).slice(0, opts.verifySample);
    for (const r of sample) {
      const tx = await rpc.getTransaction(r.resetSignature);
      if (!tx) continue;
      const x = extractTransaction(tx, { programId: ctx.programId, executorPda: ctx.executorPda });
      const chain = x.oreResets.find((e) => e.event.roundId === r.event.roundId)?.event;
      if (!chain) continue;
      verified++;
      if (JSON.stringify(chain, big) !== JSON.stringify(r.event, big)) {
        mismatches++;
        await ctx.store.recordProblem(r.resetSignature, `round ${r.event.roundId}`, "ORE_API_MISMATCH", "api.ore.com ResetEvent differs from chain; chain value stored");
        r.event = chain;
      }
    }
  }
  await ctx.store.upsertRounds(fresh, "ore-api");
  const max = fresh.reduce((m, r) => (r.event.roundId > m ? r.event.roundId : m), 0n);
  await ctx.store.setCursor("ore-api", "newest-round", max.toString());
  return { stored: fresh.length, verified, mismatches };
}

function big(_k: string, v: unknown) {
  return typeof v === "bigint" ? v.toString() : v;
}
