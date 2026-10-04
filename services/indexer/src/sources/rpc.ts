/**
 * JSON-RPC client and the RPC polling source.
 *
 *  - Only `finalized` commitment: a rolled-back fork can never leave phantom digs behind.
 *  - `maxSupportedTransactionVersion: 1`: mainnet already carries v1 transactions (the ORE
 *    reset fixture is one), and heads_down cranks may use v1 for 27 heartbeats per tx.
 *  - The RPC URL may embed an API key (Helius `?api-key=`); it is never logged or returned in
 *    errors, only its host. A provider's own error text is scrubbed of the URL and its keys
 *    before it is used ({@link scrubRpcText}), as the crank does (crank/src/redact.rs).
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

const REDACTED = "<redacted>";
/** Shorter parts of a URL are not treated as keys: replacing them would mangle ordinary error text. */
const MIN_SECRET_LEN = 8;

/**
 * The parts of an RPC URL that may be a provider key: query values (`?api-key=<key>`), path
 * segments (`/<key>/`) and userinfo, as written in the URL and percent-decoded. Longest first, so
 * a key that contains another is replaced whole.
 */
export function urlSecrets(url: string): string[] {
  const u = new URL(url);
  const parts = [u.username, u.password, u.hash.slice(1), ...u.pathname.split("/"), ...u.search.slice(1).split("&").map((kv) => kv.slice(kv.indexOf("=") + 1))];
  const out = new Set<string>();
  for (const p of parts) {
    out.add(p);
    try {
      out.add(decodeURIComponent(p));
    } catch {
      /* not percent-encoded text */
    }
  }
  return [...out].filter((s) => s.length >= MIN_SECRET_LEN).sort((a, b) => b.length - a.length);
}

/**
 * `text` from a provider without the request URL, any `api-key=` value or the URL's own keys
 * (`secrets`, from {@link urlSecrets}). A provider is not trusted to leave the URL out of its errors.
 */
export function scrubRpcText(text: string, url: string, secrets: string[]): string {
  const u = new URL(url);
  // A URL that is only scheme and host (a local validator, a public endpoint) holds no key and stays readable.
  const bare = !u.username && !u.password && !u.search && !u.hash && u.pathname === "/";
  let s = bare ? text : text.replaceAll(url, `${u.protocol}//${u.host}/${REDACTED}`);
  s = s.replace(/(api[-_]?key=)[^&\s"']*/gi, `$1${REDACTED}`);
  for (const k of secrets) s = s.replaceAll(k, REDACTED);
  return s;
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
  private readonly secrets: string[];
  readonly host: string;
  private readonly opts: Required<RpcOptions>;
  private id = 0;

  constructor(url: string, opts: RpcOptions = {}) {
    const u = new URL(url);
    if (u.protocol !== "https:" && u.protocol !== "http:") throw new Error("RPC URL must be http(s)");
    this.url = url;
    this.secrets = urlSecrets(url);
    this.host = u.host;
    this.opts = {
      timeoutMs: opts.timeoutMs ?? 20_000,
      maxAttempts: opts.maxAttempts ?? 5,
      maxResponseBytes: opts.maxResponseBytes ?? 64 * 1024 * 1024,
      fetchImpl: opts.fetchImpl ?? fetch,
      sleep: opts.sleep ?? defaultSleep,
    };
  }

  /** Text that came from this provider, made safe to log or put in an error. */
  scrub(text: string): string {
    return scrubRpcText(text, this.url, this.secrets);
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
          // JSON-RPC errors are deterministic: do not retry. The text is the provider's: scrub it, then cut it.
          throw Object.assign(new RpcError(`${method}: ${this.scrub(String(body.error.message ?? "error")).slice(0, 200)}`, body.error.code ?? null), { final: true });
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

  /** Up to 100 accounts at `finalized`; null for an address with no account. */
  async getMultipleAccounts(addresses: string[]): Promise<{ slot: number; accounts: (RawAccount | null)[] }> {
    if (addresses.length > 100) throw new RpcError("getMultipleAccounts: at most 100 addresses");
    for (const a of addresses) if (!isAddress(a)) throw new RpcError("getMultipleAccounts: invalid address");
    const r = await this.call<{ context: { slot: number }; value: ({ data: [string, string]; owner: string } | null)[] }>("getMultipleAccounts", [
      addresses,
      { encoding: "base64", commitment: "finalized" },
    ]);
    if (!r || !Array.isArray(r.value) || r.value.length !== addresses.length || !Number.isSafeInteger(r.context?.slot)) {
      throw new RpcError("getMultipleAccounts: malformed response");
    }
    const accounts = r.value.map((v, i): RawAccount | null => {
      if (v === null) return null;
      if (!isAddress(v?.owner) || !Array.isArray(v.data) || v.data[1] !== "base64") throw new RpcError("getMultipleAccounts: malformed account");
      return { address: addresses[i]!, owner: v.owner, data: decodeBase64Strict(v.data[0], 10 * 1024 * 1024) };
    });
    return { slot: r.context.slot, accounts };
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
 *
 * A node with a short ledger history (solana-test-validator keeps ~10,000 shreds; pruned RPC
 * nodes) eventually forgets the cursor transaction and answers "not found". Then the walk runs
 * without `until` and stops below `untilSlot` (the cursor's slot); signatures in that very slot
 * may come back again, which the idempotent store skips.
 */
export async function collectNewSignatures(
  rpc: RpcClient,
  address: string,
  until: string | null,
  maxBackfill: number,
  untilSlot: number | null = null,
): Promise<SignatureInfo[]> {
  const walk = async (stopAt: string | null, minSlot: number | null): Promise<SignatureInfo[]> => {
    const out: SignatureInfo[] = [];
    let before: string | undefined;
    for (;;) {
      const page = await rpc.getSignaturesForAddress(address, { before, until: stopAt ?? undefined, limit: 1000 });
      if (!Array.isArray(page)) throw new RpcError("getSignaturesForAddress: malformed response");
      let reached = false;
      for (const s of page) {
        if (!isSignature(s?.signature) || !Number.isSafeInteger(s.slot)) throw new RpcError("getSignaturesForAddress: malformed entry");
        if (minSlot !== null && s.slot < minSlot) {
          reached = true;
          break;
        }
        out.push(s);
      }
      if (reached || page.length < 1000) break;
      if (stopAt === null && minSlot === null && out.length >= maxBackfill) break;
      before = page[page.length - 1]!.signature;
    }
    return out;
  };
  if (until === null) return (await walk(null, null)).slice(0, maxBackfill);
  try {
    return await walk(until, null);
  } catch (e) {
    if (!(e instanceof RpcError) || !/not found/i.test(e.message)) throw e;
    if (untilSlot === null) return (await walk(null, null)).slice(0, maxBackfill);
    return walk(null, untilSlot);
  }
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
