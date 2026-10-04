/**
 * The ingest loop against a fake RPC and a fake api.ore.com: the genesis-hash check runs inside the
 * loop and is retried every interval, nothing is read from the RPC before it has passed, a wrong
 * cluster never ingests, a failed pass does not end the loop, `--once` still fails, and each pass
 * leaves its time and outcome for /v1/health. The two steps of a pass: one that fails does not keep
 * the other from running, and api.ore.com turning a pass away is a failure only when it lasts.
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { GENESIS_HASH, loadConfig, type Config } from "../src/config.ts";
import { CONFIG_PDA, EXECUTOR_PDA, HEADS_DOWN_PROGRAM_ID } from "../src/constants.ts";
import type { IngestContext } from "../src/ingest.ts";
import type { LogLevel } from "../src/log.ts";
import { clusterCheck, ingestLoop } from "../src/loop.ts";
import { ORE_API_PAGE_PAUSE_MS } from "../src/sources/oreApi.ts";
import { RpcClient } from "../src/sources/rpc.ts";
import { buildDigTx } from "../src/sim/txbuilder.ts";
import { migrate, openDb, type Db } from "../src/store/db.ts";
import { Store } from "../src/store/store.ts";
import type { Dataset } from "../src/model.ts";
import { addTx, addr, fakeChain, fakeRpcFetch, sig, type FakeChain } from "./helpers.ts";
import { OreStandIn } from "./oreStandIn.ts";

const HD = HEADS_DOWN_PROGRAM_ID;
const noSleep = async () => undefined;
const KEY = "not-a-real-key-0f1e2d3c-4b5a-6978";

const dig = (n: number) =>
  buildDigTx({
    signature: sig(n), slot: 5000 + n, blockTime: 1_790_800_000 + n * 80, cranker: addr(9), programId: HD, configPda: CONFIG_PDA,
    executorPda: EXECUTOR_PDA, roundAccount: addr(10), roundId: 422_700n + BigInt(n),
    rigs: [{ rig: addr(1), authority: addr(11), automation: addr(12), miner: addr(13), outcome: { kind: "dug", perTile: 66_666n, mask: 0x7fff, emaEv: 1n } }],
  });

let db: Db;
beforeEach(async () => {
  db = await openDb("pglite://memory");
  await migrate(db);
});
afterEach(async () => db.close());

interface Setup {
  ctx: IngestContext;
  cfg: Config;
  chain: FakeChain;
  rpc: RpcClient;
  /** Every log line, with the level it was written at. */
  logs: { msg: string; level: LogLevel; error?: string; [field: string]: unknown }[];
  /** Calls made to the fake api.ore.com, whose list is empty. */
  ore: { calls: number; fetch: typeof fetch };
  now: () => number;
}

/** A dataset, a fake chain with one dig, and a config whose ORE round resolver is off (it has its own tests). */
async function setup(dataset: Dataset, env: Record<string, string> = {}): Promise<Setup> {
  const cfg = loadConfig({ INDEXER_DATASET: dataset, RESOLVE_ROUNDS: "0", ...env });
  const logs: Setup["logs"] = [];
  const store = await Store.bind(db, { name: dataset, programId: HD, executorPda: EXECUTOR_PDA });
  const ctx: IngestContext = { store, programId: HD, executorPda: EXECUTOR_PDA, log: (msg, fields, level = "info") => logs.push({ msg, level, ...fields }) };
  const chain = fakeChain();
  chain.slot = 9000;
  addTx(chain, dig(1), [HD, EXECUTOR_PDA]);
  const rpc = new RpcClient(`https://rpc.example/?api-key=${KEY}`, { fetchImpl: fakeRpcFetch(chain), sleep: noSleep });
  const ore: Setup["ore"] = { calls: 0, fetch: (async () => (ore.calls++, new Response("[]", { status: 200 }))) as typeof fetch };
  let t = 1_800_000_000;
  return { ctx, cfg, chain, rpc, logs, ore, now: () => t++ };
}

const STOP = new Error("stop the loop");
/** A sleep that never waits: it runs `between(n)` after pass n and ends the loop after `passes` passes. */
function stopAfter(passes: number, between: (pass: number) => Promise<void> | void = () => undefined) {
  const waits: number[] = [];
  return {
    waits,
    sleep: async (ms: number) => {
      waits.push(ms);
      if (waits.length >= passes) throw STOP;
      await between(waits.length);
    },
  };
}
const methods = (chain: FakeChain) => chain.calls.map((c) => c.method);
const errors = (r: Setup) => r.logs.filter((l) => l.msg === "ingest error").map((l) => l.error);

describe("ingest loop: the genesis-hash check", () => {
  it("is retried every interval while the RPC is unreachable, reads nothing from it until it passes, then is not asked again", async () => {
    const r = await setup("mainnet");
    r.chain.offline = true;
    const seen: unknown[] = [];
    const { sleep, waits } = stopAfter(4, async (pass) => {
      if (pass !== 2) return;
      // Two passes have failed: only getGenesisHash was tried (5 attempts each), nothing is stored, health says so.
      seen.push(methods(r.chain), r.ore.calls, (await r.ctx.store.loadMetricsInput()).digs.length, await r.ctx.store.health());
      r.chain.offline = false;
    });
    await expect(ingestLoop(r.ctx, r.cfg, r.rpc, { once: false, sleep, now: r.now, oreFetch: r.ore.fetch })).rejects.toBe(STOP);

    expect(seen[0]).toEqual(Array.from({ length: 10 }, () => "getGenesisHash"));
    // api.ore.com is not reached through the RPC: each of the two passes asked it all the same.
    expect(seen[1]).toBe(2);
    expect(seen[2]).toBe(0);
    expect(seen[3]).toMatchObject({ txs: 0, lastPollAt: 1_800_000_001, lastPollOk: false, lastOkPollAt: null });
    expect(errors(r)).toEqual(Array.from({ length: 2 }, () => "getGenesisHash: request to rpc.example failed (TypeError)"));
    expect(r.logs.filter((l) => l.msg === "ingest error").map((l) => [l.level, l.step])).toEqual([["error", "rpc"], ["error", "rpc"]]);
    expect(JSON.stringify(r.logs)).not.toContain(KEY);
    // Passes 3 and 4 ran: one more getGenesisHash (it passed), then the poll; pass 4 does not ask again.
    expect(methods(r.chain).filter((m) => m === "getGenesisHash")).toHaveLength(11);
    expect(methods(r.chain).slice(10, 12)).toEqual(["getGenesisHash", "getSignaturesForAddress"]);
    expect((await r.ctx.store.loadMetricsInput()).digs).toHaveLength(1);
    expect(r.ore.calls).toBe(4);
    expect(waits).toEqual([30_000, 30_000, 30_000, 30_000]);
    expect(await r.ctx.store.health()).toMatchObject({ txs: 1, lastPollAt: 1_800_000_003, lastPollOk: true, lastOkPollAt: 1_800_000_003 });
  });

  it("is retried while the RPC answers HTTP 429 (credits used up) and the loop goes on afterwards", async () => {
    const r = await setup("mainnet");
    r.chain.throttleNext = 5; // exactly the five attempts of the first pass
    const { sleep } = stopAfter(2);
    await expect(ingestLoop(r.ctx, r.cfg, r.rpc, { once: false, sleep, now: r.now, oreFetch: r.ore.fetch })).rejects.toBe(STOP);
    expect(errors(r)).toEqual(["getGenesisHash: HTTP 429 from rpc.example"]);
    expect((await r.ctx.store.loadMetricsInput()).digs).toHaveLength(1);
  });

  it("never ingests from an RPC on another cluster, and says why on every pass", async () => {
    const r = await setup("mainnet");
    r.chain.genesisHash = GENESIS_HASH.devnet!;
    const { sleep } = stopAfter(3);
    await expect(ingestLoop(r.ctx, r.cfg, r.rpc, { once: false, sleep, now: r.now, oreFetch: r.ore.fetch })).rejects.toBe(STOP);
    // Nothing but the check was asked of that RPC: no signature, no account, and no transaction for the ORE spot check.
    expect(methods(r.chain)).toEqual(["getGenesisHash", "getGenesisHash", "getGenesisHash"]);
    expect(errors(r)).toEqual(Array.from({ length: 3 }, () => `RPC rpc.example is not mainnet (genesis ${GENESIS_HASH.devnet}); refusing to mix clusters`));
    // api.ore.com holds mainnet's rounds whatever the RPC is: it was asked on every pass.
    expect(r.ore.calls).toBe(3);
    expect(await r.ctx.store.health()).toMatchObject({ txs: 0, lastPollAt: 1_800_000_002, lastPollOk: false, lastOkPollAt: null });
  });

  it("reports what the RPC answered instead of the genesis hash, scrubbed of the key and cut short", async () => {
    const r = await setup("mainnet");
    r.chain.genesisHash = `no hash for key ${KEY} ${"y".repeat(200)}`;
    const err = (await clusterCheck(r.cfg, r.rpc)().catch((e: unknown) => e)) as Error;
    expect(err.message).toBe(`RPC rpc.example is not mainnet (genesis no hash for key <redacted> ${"y".repeat(37)}); refusing to mix clusters`);
    expect(err.message).not.toContain(KEY);
  });

  it("makes `ingest --once` fail: the pass rejects, and its failure is still recorded", async () => {
    const r = await setup("mainnet");
    r.chain.genesisHash = GENESIS_HASH.devnet!;
    await expect(ingestLoop(r.ctx, r.cfg, r.rpc, { once: true, now: r.now, oreFetch: r.ore.fetch })).rejects.toThrow(/is not mainnet .*refusing to mix clusters/);
    r.chain.offline = true;
    await expect(ingestLoop(r.ctx, r.cfg, r.rpc, { once: true, now: r.now, oreFetch: r.ore.fetch })).rejects.toThrow(/getGenesisHash: request to rpc\.example failed/);
    expect(await r.ctx.store.health()).toMatchObject({ txs: 0, lastPollAt: 1_800_000_001, lastPollOk: false, lastOkPollAt: null });
    // The right cluster: one pass, no sleep, recorded as ok.
    r.chain.offline = false;
    r.chain.genesisHash = GENESIS_HASH.mainnet!;
    const sleep = async () => {
      throw new Error("--once must not sleep");
    };
    await ingestLoop(r.ctx, r.cfg, r.rpc, { once: true, sleep, now: r.now, oreFetch: r.ore.fetch });
    expect(await r.ctx.store.health()).toMatchObject({ txs: 1, lastPollAt: 1_800_000_002, lastPollOk: true, lastOkPollAt: 1_800_000_002 });
  });

  it("is not made for a dataset without a pinned genesis hash (localnet)", async () => {
    const r = await setup("localnet");
    await ingestLoop(r.ctx, r.cfg, r.rpc, { once: true, now: r.now, oreFetch: r.ore.fetch });
    expect(methods(r.chain)).not.toContain("getGenesisHash");
    expect((await r.ctx.store.loadMetricsInput()).digs).toHaveLength(1);
    expect(r.ore.calls).toBe(0); // api.ore.com holds mainnet rounds only
  });

  it("shares one request between calls that overlap, and costs nothing once it has passed", async () => {
    const r = await setup("mainnet");
    const verify = clusterCheck(r.cfg, r.rpc);
    await Promise.all([verify(), verify(), verify()]);
    await verify();
    expect(methods(r.chain)).toEqual(["getGenesisHash"]);
    // Without an RPC there is nothing to compare.
    await clusterCheck(r.cfg, null)();
    // A failure is not remembered: the next call asks again.
    const other = await setup("devnet");
    const again = clusterCheck(other.cfg, other.rpc);
    await expect(again()).rejects.toThrow(/is not devnet/);
    other.chain.genesisHash = GENESIS_HASH.devnet!;
    await again();
    expect(methods(other.chain)).toEqual(["getGenesisHash", "getGenesisHash"]);
  });
});

describe("ingest loop: passes", () => {
  it("runs without an RPC: no chain ingestion and no RPC call, ORE rounds still come from api.ore.com", async () => {
    const r = await setup("mainnet");
    await ingestLoop(r.ctx, r.cfg, null, { once: true, now: r.now, oreFetch: r.ore.fetch });
    expect(r.chain.calls).toEqual([]);
    expect(r.ore.calls).toBe(1);
    expect(await r.ctx.store.health()).toMatchObject({ txs: 0, lastPollOk: true });
    expect(r.logs.map((l) => l.msg)).toEqual(["ore rounds"]);
    // Nothing configured at all is an error, not a silent loop.
    await expect(ingestLoop(r.ctx, { ...r.cfg, oreApiEnabled: false }, null, { once: true })).rejects.toThrow(/nothing to ingest/);
  });

  it("takes the account snapshot on the first pass and then every SNAPSHOT_EVERY_N_POLLS passes while nothing happens", async () => {
    const r = await setup("mainnet", { SNAPSHOT_EVERY_N_POLLS: "3", ORE_API_ENABLED: "0" });
    const scansAfter: number[] = [];
    const scans = () => methods(r.chain).filter((m) => m === "getProgramAccounts").length;
    const { sleep } = stopAfter(7, () => void scansAfter.push(scans()));
    await expect(ingestLoop(r.ctx, r.cfg, r.rpc, { once: false, sleep, now: r.now })).rejects.toBe(STOP);
    expect([...scansAfter, scans()]).toEqual([4, 4, 4, 8, 8, 8, 12]);
    expect(r.logs.filter((l) => l.msg === "rpc poll")).toEqual([
      { msg: "rpc poll", level: "info", ingested: 1, accounts: 0 },
      { msg: "rpc poll", level: "info", ingested: 0, accounts: null },
      { msg: "rpc poll", level: "info", ingested: 0, accounts: null },
      { msg: "rpc poll", level: "info", ingested: 0, accounts: 0 },
      { msg: "rpc poll", level: "info", ingested: 0, accounts: null },
      { msg: "rpc poll", level: "info", ingested: 0, accounts: null },
      { msg: "rpc poll", level: "info", ingested: 0, accounts: 0 },
    ]);
  });

  it("survives a pass that fails after the check, and a database that cannot take the outcome", async () => {
    const r = await setup("mainnet", { ORE_API_ENABLED: "0" });
    r.chain.broken.add("getSignaturesForAddress");
    // The outcome cannot be stored on the first pass: that is logged, and the loop goes on.
    let refuse = true;
    const store = Object.create(r.ctx.store) as Store;
    store.recordPoll = async (at, ok) => {
      if (refuse) throw new Error("database is gone");
      return r.ctx.store.recordPoll(at, ok);
    };
    const { sleep } = stopAfter(3, (pass) => {
      refuse = false;
      if (pass === 2) r.chain.broken.clear();
    });
    await expect(ingestLoop({ ...r.ctx, store }, r.cfg, r.rpc, { once: false, sleep, now: r.now })).rejects.toBe(STOP);
    expect(r.logs.map((l) => `${l.level} ${l.msg}${l.error ? `: ${l.error}` : ""}`)).toEqual([
      "error ingest error: getSignaturesForAddress: HTTP 500 from rpc.example",
      "error ingest: poll outcome not stored: database is gone",
      "error ingest error: getSignaturesForAddress: HTTP 500 from rpc.example",
      "info rpc poll",
    ]);
    expect(await r.ctx.store.health()).toMatchObject({ txs: 1, lastPollAt: 1_800_000_002, lastPollOk: true, lastOkPollAt: 1_800_000_002 });
  });

  it("counts a pass that waits for a transaction the RPC does not serve yet as succeeded", async () => {
    // What /v1/health cannot show: if the RPC never served that transaction, ingestion would stand still with lastPollOk true.
    const r = await setup("mainnet", { ORE_API_ENABLED: "0" });
    r.chain.unavailable.add(sig(1));
    await ingestLoop(r.ctx, r.cfg, r.rpc, { once: true, now: r.now });
    expect(r.logs.map((l) => `${l.level} ${l.msg}`)).toEqual(["warn rpc: transaction not yet available, will retry", "info rpc poll"]);
    expect(await r.ctx.store.health()).toMatchObject({ txs: 0, lastPollOk: true });
    r.chain.unavailable.clear();
    await ingestLoop(r.ctx, r.cfg, r.rpc, { once: true, now: r.now });
    expect(await r.ctx.store.health()).toMatchObject({ txs: 1, lastPollOk: true });
  });

  it("refuses to loop without a pause between passes", async () => {
    const r = await setup("mainnet", { INGEST_INTERVAL_S: "0" });
    await expect(ingestLoop(r.ctx, r.cfg, r.rpc, { once: false, now: r.now, oreFetch: r.ore.fetch })).rejects.toThrow(/INGEST_INTERVAL_S=0/);
    expect(r.chain.calls).toEqual([]);
    await ingestLoop(r.ctx, r.cfg, r.rpc, { once: true, now: r.now, oreFetch: r.ore.fetch });
    expect((await r.ctx.store.loadMetricsInput()).digs).toHaveLength(1);
  });
});

describe("ingest loop: the two steps of a pass", () => {
  /** The newest round of the stand-in for api.ore.com, and its reset time. */
  const N = 430_000;
  const T0 = 1_791_000_000;
  const noPause = async () => undefined;
  const gone = (async () => new Response("not found", { status: 404 })) as typeof fetch;
  const oreLines = (r: Setup) => r.logs.filter((l) => l.msg === "ore rounds");
  const rounds = async (r: Setup) => (await r.ctx.store.loadMetricsInput()).rounds.length;

  it("asks api.ore.com although the RPC step failed, and stores its rounds without the spot check", async () => {
    const r = await setup("mainnet", { ORE_API_PAGES_PER_PASS: "2", ORE_ROUNDS_SINCE: "0" });
    const api = new OreStandIn(N, 500, T0);
    r.chain.offline = true;
    await expect(ingestLoop(r.ctx, r.cfg, r.rpc, { once: true, now: r.now, oreFetch: api.fetch, orePause: noPause })).rejects.toThrow("getGenesisHash: request to rpc.example failed (TypeError)");
    expect(api.takePages()).toEqual([0, 1]);
    expect(await rounds(r)).toBe(200);
    expect(r.logs.map((l) => `${l.level} ${l.msg}`)).toEqual(["error ingest error", "info ore rounds"]);
    expect(r.logs[0]).toMatchObject({ step: "rpc" });
    expect(r.logs[1]).toMatchObject({ stored: 200, verified: 0, pages: 2 });
    // Of the RPC only its cluster was asked: no transaction for the spot check.
    expect([...new Set(methods(r.chain))]).toEqual(["getGenesisHash"]);
    expect(await r.ctx.store.health()).toMatchObject({ txs: 0, lastPollOk: false, lastOkPollAt: null });

    // The RPC is back: the pass succeeds and its new rounds are spot-checked again. Four getTransaction
    // calls: the dig, and the three newest new rounds (which this chain does not hold).
    r.chain.offline = false;
    r.chain.calls.length = 0;
    api.add(5);
    await ingestLoop(r.ctx, r.cfg, r.rpc, { once: true, now: r.now, oreFetch: api.fetch, orePause: noPause });
    expect(methods(r.chain).filter((m) => m === "getTransaction")).toHaveLength(4);
    expect(oreLines(r)[1]).toMatchObject({ stored: 5 + 95, verified: 0, pages: 2 });
    expect(await r.ctx.store.health()).toMatchObject({ txs: 1, lastPollOk: true });
  });

  it("runs the RPC step although api.ore.com answers with an error, and the pass is failed", async () => {
    const r = await setup("mainnet");
    await expect(ingestLoop(r.ctx, r.cfg, r.rpc, { once: true, now: r.now, oreFetch: gone })).rejects.toThrow("api.ore.com /events/reset page 0: HTTP 404");
    expect(r.logs).toEqual([
      { msg: "rpc poll", level: "info", ingested: 1, accounts: 0 },
      { msg: "ingest error", level: "error", step: "ore-api", error: "api.ore.com /events/reset page 0: HTTP 404" },
    ]);
    // What the RPC poll stored stays; the outcome is that of the whole pass.
    expect(await r.ctx.store.health()).toMatchObject({ txs: 1, lastPollAt: 1_800_000_000, lastPollOk: false, lastOkPollAt: null });
  });

  it("logs each step that fails, and `--once` fails with the first", async () => {
    const r = await setup("mainnet");
    r.chain.broken.add("getSignaturesForAddress");
    await expect(ingestLoop(r.ctx, r.cfg, r.rpc, { once: true, now: r.now, oreFetch: gone })).rejects.toThrow("getSignaturesForAddress: HTTP 500 from rpc.example");
    expect(r.logs).toEqual([
      { msg: "ingest error", level: "error", step: "rpc", error: "getSignaturesForAddress: HTTP 500 from rpc.example" },
      { msg: "ingest error", level: "error", step: "ore-api", error: "api.ore.com /events/reset page 0: HTTP 404" },
    ]);
    expect(await r.ctx.store.health()).toMatchObject({ lastPollOk: false });
  });

  it("does not fail a pass that api.ore.com turns away, until the third in a row that got no further", async () => {
    const r = await setup("mainnet", { ORE_ROUNDS_SINCE: "0" });
    const api = new OreStandIn(N, 300, T0);
    api.refuse = () => ({ status: 429 });
    const outcomes: (boolean | null)[] = [];
    const { sleep } = stopAfter(5, async (pass) => {
      outcomes.push((await r.ctx.store.health()).lastPollOk);
      if (pass === 3) api.refuse = () => null;
    });
    // No RPC, as on the live service on 2026-10-04: api.ore.com is the only source.
    await expect(ingestLoop(r.ctx, r.cfg, null, { once: false, sleep, now: r.now, oreFetch: api.fetch, orePause: noPause })).rejects.toBe(STOP);
    outcomes.push((await r.ctx.store.health()).lastPollOk);
    expect(outcomes).toEqual([true, true, false, true, true]);
    expect(r.logs.map((l) => `${l.level} ${l.msg}`)).toEqual(["info ore rounds", "info ore rounds", "info ore rounds", "error ingest error", "info ore rounds", "info ore rounds"]);
    const stopped = "HTTP 429 at page 0, asking for the newest rounds";
    expect(oreLines(r).slice(0, 3)).toEqual(
      [1, 2, 3].map((n) => ({ msg: "ore rounds", level: "info", stored: 0, verified: 0, mismatches: 0, pages: 0, newest: null, backTo: null, backfill: "running", stopped, stalledPasses: n })),
    );
    expect(r.logs[3]).toEqual({ msg: "ingest error", level: "error", step: "ore-api", error: `api.ore.com: ${stopped}; 3 passes in a row without progress` });
    // The pass after: everything the list holds, and no trace of the refusals.
    expect(oreLines(r)[3]).toEqual({ msg: "ore rounds", level: "info", stored: 300, verified: 0, mismatches: 0, pages: 4, newest: String(N), backTo: new Date((T0 - 299 * 77) * 1000).toISOString(), backfill: "done" });
    expect(await rounds(r)).toBe(300);
  });

  it("lets `ingest --once` succeed when api.ore.com turns it away, and fail when that has lasted three runs", async () => {
    const r = await setup("mainnet");
    const api = new OreStandIn(N, 300, T0);
    api.refuse = () => ({ status: 503 });
    // Each run is a process of its own: the count is kept with the cursors.
    const once = () => ingestLoop(r.ctx, r.cfg, null, { once: true, now: r.now, oreFetch: api.fetch, orePause: noPause });
    await once();
    await once();
    expect(await r.ctx.store.health()).toMatchObject({ lastPollOk: true });
    await expect(once()).rejects.toThrow("api.ore.com: HTTP 503 at page 0, asking for the newest rounds; 3 passes in a row without progress");
    expect(await r.ctx.store.health()).toMatchObject({ lastPollOk: false });
  });

  it("waits out a Retry-After over several passes, by the loop's clock", async () => {
    const r = await setup("mainnet", { ORE_ROUNDS_SINCE: "0" });
    const api = new OreStandIn(N, 300, T0);
    api.refuse = (_page, nth) => (nth === 0 ? { status: 429, headers: { "retry-after": "70" } } : null);
    let t = 1_800_000_000;
    const { sleep } = stopAfter(4, () => void (t += 30)); // a pass every 30 s
    await expect(ingestLoop(r.ctx, r.cfg, null, { once: false, sleep, now: () => t, oreFetch: api.fetch, orePause: noPause })).rejects.toBe(STOP);
    // Turned away at 0 s; nothing asked at 30 s and at 60 s; asked again at 90 s.
    expect(api.requests.map((q) => q.status)).toEqual([429, 200, 200, 200, 200]);
    expect(oreLines(r).map((l) => [l.pages, l.waiting ?? false, l.retryAfterS ?? null])).toEqual([[0, false, 70], [0, true, 40], [0, true, 10], [4, false, null]]);
    expect(r.logs.every((l) => l.level === "info")).toBe(true);
    expect(await r.ctx.store.health()).toMatchObject({ lastPollOk: true });
  });

  it("asks for ORE_API_PAGES_PER_PASS pages a pass, with a pause of its own between them", async () => {
    const r = await setup("mainnet", { ORE_API_PAGES_PER_PASS: "3", ORE_ROUNDS_SINCE: "0" });
    const api = new OreStandIn(N, 1000, T0);
    const pauses: number[] = [];
    const { sleep, waits } = stopAfter(2);
    await expect(ingestLoop(r.ctx, r.cfg, null, { once: false, sleep, now: r.now, oreFetch: api.fetch, orePause: async (ms) => void pauses.push(ms) })).rejects.toBe(STOP);
    expect(api.takePages()).toEqual([0, 1, 2, 0, 3, 4]);
    expect(pauses).toEqual(Array.from({ length: 4 }, () => ORE_API_PAGE_PAUSE_MS));
    // The loop's own sleep is the one between passes.
    expect(waits).toEqual([30_000, 30_000]);
  });

  it("stores no round older than ORE_ROUNDS_SINCE", async () => {
    const r = await setup("mainnet", { ORE_ROUNDS_SINCE: String(T0 - 249 * 77) });
    const api = new OreStandIn(N, 1000, T0);
    await ingestLoop(r.ctx, r.cfg, null, { once: true, now: r.now, oreFetch: api.fetch, orePause: noPause });
    expect(api.takePages()).toEqual([0, 1, 2]);
    expect(await rounds(r)).toBe(250);
    expect(oreLines(r)[0]).toMatchObject({ stored: 250, backfill: "done" });
  });
});
