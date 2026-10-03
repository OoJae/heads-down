import { describe, expect, it } from "vitest";
import { describeConfig, loadConfig, loopbackRpc } from "../src/config.ts";
import { explorerUrl } from "../src/api/explorer.ts";

describe("config", () => {
  it("defaults: market sources jupiter then api.ore.com, round resolver on", () => {
    const c = loadConfig({});
    expect(c.marketSources).toEqual(["jupiter", "ore-api"]);
    expect(c.resolveRounds).toBe(true);
    expect(c.resolveMaxRounds).toBe(200);
    expect(c.localExplorerRpc).toBe("http://127.0.0.1:8899");
  });

  it("parses and validates MARKET_PRICE_SOURCES", () => {
    expect(loadConfig({ MARKET_PRICE_SOURCES: "none" }).marketSources).toEqual([]);
    expect(loadConfig({ MARKET_PRICE_SOURCES: "ore-api" }).marketSources).toEqual(["ore-api"]);
    expect(() => loadConfig({ MARKET_PRICE_SOURCES: "jupiter,binance" })).toThrow(/MARKET_PRICE_SOURCES/);
    expect(loadConfig({ RESOLVE_ROUNDS: "0" }).resolveRounds).toBe(false);
  });

  it("shows only a loopback RPC in localnet explorer links, never a keyed URL", () => {
    expect(loopbackRpc("http://127.0.0.1:18899")).toBe("http://127.0.0.1:18899");
    expect(loopbackRpc("http://localhost:8899/")).toBe("http://localhost:8899");
    expect(loopbackRpc("https://mainnet.helius-rpc.com/?api-key=SECRET")).toBeNull();
    expect(loopbackRpc("http://127.0.0.1:8899/?api-key=SECRET")).toBeNull();
    expect(loopbackRpc("http://user:pw@127.0.0.1:8899")).toBeNull();
    const c = loadConfig({ RPC_URL: "http://127.0.0.1:18899", INDEXER_DATASET: "localnet" });
    expect(c.localExplorerRpc).toBe("http://127.0.0.1:18899");
    const keyed = loadConfig({ RPC_URL: "https://rpc.example/?api-key=SECRET", INDEXER_DATASET: "devnet" });
    expect(keyed.localExplorerRpc).toBe("http://127.0.0.1:8899");
    expect(JSON.stringify(describeConfig(keyed))).not.toContain("SECRET");
    expect(explorerUrl("localnet", "account", "By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge", c.localExplorerRpc)).toContain(encodeURIComponent("http://127.0.0.1:18899"));
  });
});
