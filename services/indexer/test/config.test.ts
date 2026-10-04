import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { describeConfig, loadConfig, loopbackRpc } from "../src/config.ts";
import { explorerUrl } from "../src/api/explorer.ts";

describe("config", () => {
  it("takes the account snapshot every 20th idle poll unless SNAPSHOT_EVERY_N_POLLS says otherwise", () => {
    expect(loadConfig({}).snapshotEveryPolls).toBe(20);
    expect(loadConfig({ SNAPSHOT_EVERY_N_POLLS: "1" }).snapshotEveryPolls).toBe(1);
    expect(loadConfig({ SNAPSHOT_EVERY_N_POLLS: "240" }).snapshotEveryPolls).toBe(240);
    // 0 would mean "never": the snapshot always has a safety net.
    for (const bad of ["0", "-1", "2.5", "often"]) expect(() => loadConfig({ SNAPSHOT_EVERY_N_POLLS: bad }), bad).toThrow(/SNAPSHOT_EVERY_N_POLLS/);
    expect(describeConfig(loadConfig({ SNAPSHOT_EVERY_N_POLLS: "7" }))).toMatchObject({ snapshotEveryPolls: 7, ingestIntervalS: 30 });
  });

  it("lists every variable it reads in both .env.example files", () => {
    // Every name loadConfig looks up, recorded by the environment object itself.
    const read = new Set<string>();
    loadConfig(new Proxy({}, { get: (_t, name) => (typeof name === "string" && read.add(name), undefined) }) as NodeJS.ProcessEnv);
    expect(read.size).toBeGreaterThanOrEqual(21);
    expect([...read]).toEqual(expect.arrayContaining(["RPC_URL", "INGEST_INTERVAL_S", "SNAPSHOT_EVERY_N_POLLS", "MARKET_PRICE_SOURCES", "RESOLVE_ROUNDS", "RESOLVE_MAX_ROUNDS", "RESOLVE_RESET_LOOKUPS"]));
    for (const file of ["../.env.example", "../../../deploy/railway/indexer/.env.example"]) {
      // A variable is listed when a line sets it, or shows it commented out.
      const listed = new Set([...readFileSync(new URL(file, import.meta.url), "utf8").matchAll(/^#?\s*([A-Z][A-Z0-9_]*)=/gm)].map((m) => m[1]));
      expect([...read].filter((name) => !listed.has(name)), file).toEqual([]);
      expect([...listed].filter((name) => !read.has(name!)), file).toEqual([]);
    }
  });

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
