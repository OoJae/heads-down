/**
 * Market price sources against REAL responses (fetched 2026-10-01): Jupiter Price API v3 and
 * api.ore.com /market. ORE/SOL = ORE/USD ÷ SOL/USD, in lamports per ORE, always labelled.
 */
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { MarketPrice, fixedSource, jupiterSource, lamportsPerOre, oreApiSource, sourcesFromConfig } from "../src/sources/market.ts";

const jup = JSON.parse(readFileSync(new URL("./fixtures/market-jupiter-price-v3.json", import.meta.url), "utf8")).response;
const ore = JSON.parse(readFileSync(new URL("./fixtures/market-ore-api.json", import.meta.url), "utf8")).response;

const fetchOf = (routes: Record<string, unknown>, calls: string[] = []) =>
  (async (url: string | URL | Request) => {
    const u = String(url);
    calls.push(u);
    const key = Object.keys(routes).find((k) => u.includes(k));
    if (!key) return new Response("no", { status: 404 });
    const body = routes[key];
    if (body instanceof Error) throw body;
    return new Response(JSON.stringify(body), { status: 200 });
  }) as unknown as typeof fetch;

describe("market sources (real responses)", () => {
  it("Jupiter v3: ORE usdPrice ÷ SOL usdPrice", async () => {
    const q = await jupiterSource(fetchOf({ "/price/v3": jup })).fetch(1);
    const want = BigInt(Math.round((jup.oreoU2P8bN6jkk3jbaiVxYnG1dCXcYxwhwyK9jSybcp.usdPrice / jup.So11111111111111111111111111111111111111112.usdPrice) * 1e9));
    expect(q).toEqual({ lamportsPerOre: want, source: "jupiter-price-v3", fetchedAt: 1 });
    expect(q.lamportsPerOre).toBeGreaterThan(500_000_000n); // ~0.81 SOL per ORE on 2026-10-01
    expect(q.lamportsPerOre).toBeLessThan(1_200_000_000n);
  });

  it("api.ore.com /market: active_price_usd ÷ sol_price_usd", async () => {
    const q = await oreApiSource(fetchOf({ "/market": ore })).fetch(2);
    expect(q.source).toBe("api.ore.com/market");
    expect(q.lamportsPerOre).toBe(BigInt(Math.round((ore.active_price_usd / ore.sol_price_usd) * 1e9)));
  });

  it("refuses unusable prices", () => {
    expect(() => lamportsPerOre(0, 100)).toThrow();
    expect(() => lamportsPerOre(1, -1)).toThrow();
    expect(() => lamportsPerOre("1", 1)).toThrow();
    expect(() => lamportsPerOre(Number.NaN, 1)).toThrow();
    expect(lamportsPerOre(81, 100)).toBe(810_000_000n);
  });

  it("falls back to the next source, caches for the TTL, serves stale for at most maxStale, then null", async () => {
    let now = 1_000_000;
    const calls: string[] = [];
    const routes: Record<string, unknown> = { "/price/v3": new Error("down"), "/market": ore };
    const m = new MarketPrice([jupiterSource(fetchOf(routes, calls)), oreApiSource(fetchOf(routes, calls))], { ttlMs: 60_000, maxStaleMs: 300_000, now: () => now });
    const q1 = await m.get();
    expect(q1?.source).toBe("api.ore.com/market");
    expect(m.lastError).toBeNull();
    expect(calls).toHaveLength(2);
    now += 30_000;
    expect(await m.get()).toBe(q1); // cached
    expect(calls).toHaveLength(2);
    routes["/market"] = new Error("down too");
    now += 60_000;
    expect(await m.get()).toBe(q1); // stale but within 5 minutes
    expect(m.lastError).toMatch(/ore-api-market/);
    expect(calls).toHaveLength(4);
    // Both sources just failed: the next request does not wait on them again for 30 s.
    now += 10_000;
    expect(await m.get()).toBe(q1);
    expect(calls).toHaveLength(4);
    now += 300_000;
    expect(await m.get()).toBeNull();
    expect(calls).toHaveLength(6);
    now += 29_000;
    expect(await m.get()).toBeNull();
    expect(calls).toHaveLength(6);
    // A source comes back: the next attempt after the pause picks it up.
    routes["/market"] = ore;
    now += 2_000;
    expect((await m.get())?.source).toBe("api.ore.com/market");
    expect(calls).toHaveLength(8);
  });

  it("is configurable and never invents a source", async () => {
    expect(sourcesFromConfig(["jupiter", "ore-api"]).map((s) => s.name)).toEqual(["jupiter-price-v3", "ore-api-market"]);
    expect(() => sourcesFromConfig(["coingecko"])).toThrow(/unknown market price source/);
    expect(await new MarketPrice([]).get()).toBeNull();
    expect(await new MarketPrice([fixedSource(7n, "simulated")]).get()).toMatchObject({ lamportsPerOre: 7n, source: "simulated" });
  });
});
