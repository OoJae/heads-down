/**
 * JSON-RPC client and the RPC polling source.
 *
 *  - Only `finalized` commitment: a rolled-back fork can never leave phantom digs behind.
 *  - `maxSupportedTransactionVersion: 1`: mainnet already carries v1 transactions (the ORE
 *    reset fixture is one), and heads_down cranks may use v1 for 27 heartbeats per tx.
 *  - The RPC URL may embed an API key (Helius `?api-key=`); it is never logged or returned in
 *    errors, only its host.
 */
import { decodeBase64Strict } from "../codec/bytes.ts";
import { isAddress, isSignature } from "../codec/base58.ts";
import type { RawTransaction } from "../codec/tx.ts";

export class RpcError extends Error {
  readonly code: number | null;
  constructor(message: string, code: number | null = null) {
    super(message);
    this.name = "RpcError";
    this.code = code;
  }
}

export interface RpcOptions {
  timeoutMs?: number;
  maxAttempts?: number;
  maxResponseBytes?: number;
  fetchImpl?: typeof fetch;
  sleep?: (ms: number) => Promise<void>;
}

const defaultSleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

export class RpcClient {
  private readonly url: string;
  readonly host: string;
  private readonly opts: Required<RpcOptions>;
  private id = 0;

  constructor(url: string, opts: RpcOptions = {}) {
    const u = new URL(url);
    if (u.protocol !== "https:" && u.protocol !== "http:") throw new Error("RPC URL must be http(s)");
    this.url = url;
    this.host = u.host;
    this.opts = {
      timeoutMs: opts.timeoutMs ?? 20_000,
      maxAttempts: opts.maxAttempts ?? 5,
      maxResponseBytes: opts.maxResponseBytes ?? 64 * 1024 * 1024,
      fetchImpl: opts.fetchImpl ?? fetch,
      sleep: opts.sleep ?? defaultSleep,
    };
  }

  async call<T>(method: string, params: unknown[]): Promise<T> {
    let lastErr: unknown;
    for (let attempt = 1; attempt <= this.opts.maxAttempts; attempt++) {
      try {
        const res = await this.opts.fetchImpl(this.url, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ jsonrpc: "2.0", id: ++this.id, method, params }),
          signal: AbortSignal.timeout(this.opts.timeoutMs),
        });
        if (res.status === 429 || res.status >= 500) {
          const ra = Number(res.headers.get("retry-after"));
          throw Object.assign(new RpcError(`${method}: HTTP ${res.status} from ${this.host}`), {
            retryAfterMs: Number.isFinite(ra) && ra > 0 ? Math.min(ra * 1000, 30_000) : null,
          });
        }
        if (!res.ok) throw new RpcError(`${method}: HTTP ${res.status} from ${this.host}`);
        const text = await res.text();
        if (text.length > this.opts.maxResponseBytes) throw new RpcError(`${method}: response too large`);
        const body = JSON.parse(text) as { result?: T; error?: { code?: number; message?: string } };
        if (body.error) {
          // JSON-RPC errors are deterministic: do not retry.
          throw Object.assign(new RpcError(`${method}: ${String(body.error.message ?? "error").slice(0, 200)}`, body.error.code ?? null), { final: true });
        }
        return body.result as T;
      } catch (e) {
        lastErr = e;
        if ((e as { final?: boolean }).final) throw e;
        if (attempt === this.opts.maxAttempts) break;
        const hinted = (e as { retryAfterMs?: number | null }).retryAfterMs;
        await this.opts.sleep(hinted ?? Math.min(500 * 2 ** (attempt - 1), 8000));
      }
    }
    if (lastErr instanceof RpcError) throw lastErr;
    throw new RpcError(`${method}: request to ${this.host} failed (${(lastErr as Error)?.name ?? "error"})`);
  }

  getSignaturesForAddress(address: string, opts: { before?: string; until?: string; limit?: number }) {
    return this.call<SignatureInfo[]>("getSignaturesForAddress", [
      address,
      { commitment: "finalized", limit: opts.limit ?? 1000, ...(opts.before ? { before: opts.before } : {}), ...(opts.until ? { until: opts.until } : {}) },
    ]);
  }

  getTransaction(signature: string) {
    return this.call<RawTransaction | null>("getTransaction", [
      signature,
      { encoding: "json", commitment: "finalized", maxSupportedTransactionVersion: 1 },
    ]);
  }

  async getProgramAccounts(programId: string, filters: unknown[]): Promise<{ slot: number; accounts: RawAccount[] }> {
    const r = await this.call<{ context: { slot: number }; value: { pubkey: string; account: { data: [string, string]; owner: string } }[] }>(
      "getProgramAccounts",
      [programId, { encoding: "base64", commitment: "finalized", withContext: true, filters }],
    );
    if (!r || typeof r !== "object" || !Array.isArray(r.value) || !Number.isSafeInteger(r.context?.slot)) {
      throw new RpcError("getProgramAccounts: malformed response");
    }
    const accounts: RawAccount[] = [];
    for (const v of r.value) {
      if (!isAddress(v?.pubkey) || !isAddress(v?.account?.owner) || !Array.isArray(v.account.data) || v.account.data[1] !== "base64") {
        throw new RpcError("getProgramAccounts: malformed account");
      }
      accounts.push({ address: v.pubkey, owner: v.account.owner, data: decodeBase64Strict(v.account.data[0], 10 * 1024 * 1024) });
    }
    return { slot: r.context.slot, accounts };
  }
}

export interface SignatureInfo {
  signature: string;
  slot: number;
  err: unknown;
  blockTime?: number | null;
}

export interface RawAccount {
  address: string;
  owner: string;
  data: Uint8Array;
}

/**
 * Newest-first signatures for `address` strictly newer than `until` (exclusive), paging
 * backwards. Without a cursor, stops after `maxBackfill` signatures.
 */
export async function collectNewSignatures(
  rpc: RpcClient,
  address: string,
  until: string | null,
  maxBackfill: number,
): Promise<SignatureInfo[]> {
  const out: SignatureInfo[] = [];
  let before: string | undefined;
  for (;;) {
    const page = await rpc.getSignaturesForAddress(address, { before, until: until ?? undefined, limit: 1000 });
    if (!Array.isArray(page)) throw new RpcError("getSignaturesForAddress: malformed response");
    for (const s of page) {
      if (!isSignature(s?.signature) || !Number.isSafeInteger(s.slot)) throw new RpcError("getSignaturesForAddress: malformed entry");
      out.push(s);
    }
    if (page.length < 1000) break;
    if (until === null && out.length >= maxBackfill) break;
    before = page[page.length - 1]!.signature;
  }
  return until === null ? out.slice(0, maxBackfill) : out;
}

/** Runs `fn` over `items` with at most `n` in flight, preserving order. */
export async function mapLimit<T, U>(items: T[], n: number, fn: (t: T) => Promise<U>): Promise<U[]> {
  const out = new Array<U>(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(n, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      out[i] = await fn(items[i]!);
    }
  });
  await Promise.all(workers);
  return out;
}
