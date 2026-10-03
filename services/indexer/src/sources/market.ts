/**
 * Market price of ORE in SOL, for the haul's "mining vs buying" comparison. Pluggable and always
 * labelled with where it came from; null when no source answers (the haul then shows no market).
 *
 * Sources (both quote USD, so the price is ORE/USD ÷ SOL/USD):
 *  - `jupiter`: Jupiter Price API v3, https://lite-api.jup.ag/price/v3?ids=<ORE mint>,<wSOL mint>
 *  - `ore-api`: api.ore.com /market, `active_price_usd` ÷ `sol_price_usd`
 *
 * A quote is cached for `ttlMs` (60 s) and served stale for at most `maxStaleMs` (5 min) while
 * every source fails; past that the price is null. After a round of failures the sources are left
 * alone for `retryMs` (30 s), so a haul request never waits on the same timeouts twice in a row
 * (an offline devstack answers at once, with no market price). Nothing here is a trust root for
 * money: it is a display comparison, and the haul says which source it used.
 */
export const ORE_MINT = "oreoU2P8bN6jkk3jbaiVxYnG1dCXcYxwhwyK9jSybcp";
export const WSOL_MINT = "So11111111111111111111111111111111111111112";

export interface MarketQuote {
  /** Lamports per whole ORE. */
  lamportsPerOre: bigint;
  /** Stable label, e.g. "jupiter-price-v3". */
  source: string;
  fetchedAt: number;
}

export interface MarketSource {
  name: string;
  fetch(now: number): Promise<MarketQuote>;
}

const USER_AGENT = "HeadsDown-indexer/0.1 (+public haul API)";

async function getJson(f: typeof fetch, url: string): Promise<unknown> {
  const res = await f(url, { headers: { accept: "application/json", "user-agent": USER_AGENT }, signal: AbortSignal.timeout(8_000) });
  if (!res.ok) throw new Error(`${new URL(url).host}: HTTP ${res.status}`);
  const text = await res.text();
  if (text.length > 1_000_000) throw new Error("price response too large");
  return JSON.parse(text);
}

/** lamports per ORE from two USD prices; throws unless both are finite and positive. */
export function lamportsPerOre(oreUsd: unknown, solUsd: unknown): bigint {
  if (typeof oreUsd !== "number" || typeof solUsd !== "number" || !(oreUsd > 0) || !(solUsd > 0) || !Number.isFinite(oreUsd) || !Number.isFinite(solUsd)) {
    throw new Error("price source returned no usable ORE/SOL prices");
  }
  const v = Math.round((oreUsd / solUsd) * 1e9);
  if (!Number.isSafeInteger(v) || v <= 0) throw new Error("price out of range");
  return BigInt(v);
}

export function jupiterSource(f: typeof fetch = fetch, base = "https://lite-api.jup.ag"): MarketSource {
  return {
    name: "jupiter-price-v3",
    async fetch(now) {
      const body = (await getJson(f, `${base}/price/v3?ids=${ORE_MINT},${WSOL_MINT}`)) as Record<string, { usdPrice?: unknown } | undefined>;
      return { lamportsPerOre: lamportsPerOre(body?.[ORE_MINT]?.usdPrice, body?.[WSOL_MINT]?.usdPrice), source: "jupiter-price-v3", fetchedAt: now };
    },
  };
}

export function oreApiSource(f: typeof fetch = fetch, base = "https://api.ore.com"): MarketSource {
  return {
    name: "ore-api-market",
    async fetch(now) {
      const body = (await getJson(f, `${base}/market`)) as { active_price_usd?: unknown; sol_price_usd?: unknown };
      return { lamportsPerOre: lamportsPerOre(body?.active_price_usd, body?.sol_price_usd), source: "api.ore.com/market", fetchedAt: now };
    },
  };
}

/** A fixed quote, for the simulated dataset (labelled "simulated"). */
export function fixedSource(lamports: bigint, source: string): MarketSource {
  return { name: source, fetch: async (now) => ({ lamportsPerOre: lamports, source, fetchedAt: now }) };
}

export function sourcesFromConfig(names: readonly string[], f: typeof fetch = fetch): MarketSource[] {
  return names.map((n) => {
    if (n === "jupiter") return jupiterSource(f);
    if (n === "ore-api") return oreApiSource(f);
    throw new Error(`unknown market price source ${n} (use jupiter, ore-api or none)`);
  });
}

export class MarketPrice {
  private cached: MarketQuote | null = null;
  private inflight: Promise<MarketQuote | null> | null = null;
  private readonly sources: MarketSource[];
  private readonly ttlMs: number;
  private readonly maxStaleMs: number;
  private readonly retryMs: number;
  private readonly now: () => number;
  private failedAt = Number.NEGATIVE_INFINITY;
  lastError: string | null = null;

  constructor(sources: MarketSource[], opts: { ttlMs?: number; maxStaleMs?: number; retryMs?: number; now?: () => number } = {}) {
    this.sources = sources;
    this.ttlMs = opts.ttlMs ?? 60_000;
    this.maxStaleMs = opts.maxStaleMs ?? 300_000;
    this.retryMs = opts.retryMs ?? 30_000;
    this.now = opts.now ?? (() => Date.now());
  }

  private stale(): MarketQuote | null {
    return this.cached && this.now() - this.cached.fetchedAt < this.maxStaleMs ? this.cached : null;
  }

  async get(): Promise<MarketQuote | null> {
    const t = this.now();
    if (this.cached && t - this.cached.fetchedAt < this.ttlMs) return this.cached;
    if (this.sources.length === 0) return null;
    if (t - this.failedAt < this.retryMs) return this.stale();
    this.inflight ??= (async () => {
      for (const s of this.sources) {
        try {
          const q = await s.fetch(this.now());
          this.cached = q;
          this.lastError = null;
          return q;
        } catch (e) {
          this.lastError = `${s.name}: ${e instanceof Error ? e.message : String(e)}`.slice(0, 200);
        }
      }
      this.failedAt = this.now();
      return this.stale();
    })().finally(() => {
      this.inflight = null;
    });
    return this.inflight;
  }
}
