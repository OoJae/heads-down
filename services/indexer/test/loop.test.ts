/**
 * The ingest loop against a fake RPC and a fake api.ore.com: the genesis-hash check runs inside the
 * loop and is retried every interval, nothing is ingested before it has passed, a wrong cluster never
 * ingests, a failed pass does not end the loop, `--once` still fails, and each pass leaves its time
 * and outcome for /v1/health.
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { GENESIS_HASH, loadConfig, type Config } from "../src/config.ts";
import { CONFIG_PDA, EXECUTOR_PDA, HEADS_DOWN_PROGRAM_ID } from "../src/constants.ts";
import type { IngestContext } from "../src/ingest.ts";
import { clusterCheck, ingestLoop } from "../src/loop.ts";
import { RpcClient } from "../src/sources/rpc.ts";
import { buildDigTx } from "../src/sim/txbuilder.ts";
import { migrate, openDb, type Db } from "../src/store/db.ts";
import { Store } from "../src/store/store.ts";
import type { Dataset } from "../src/model.ts";
import { addTx, addr, fakeChain, fakeRpcFetch, sig, type FakeChain } from "./helpers.ts";

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
  logs: { msg: string; error?: string }[];
  /** Calls made to the fake api.ore.com. */
  ore: { calls: number; fetch: typeof fetch };
  now: () => number;
}

/** A dataset, a fake chain with one dig, and a config whose ORE round resolver is off (it has its own tests). */
async function setup(dataset: Dataset, env: Record<string, string> = {}): Promise<Setup> {
  const cfg = loadConfig({ INDEXER_DATASET: dataset, RESOLVE_ROUNDS: "0", ...env });
  const logs: Setup["logs"] = [];
  const store = await Store.bind(db, { name: dataset, programId: HD, executorPda: EXECUTOR_PDA });
  const ctx: IngestContext = { store, programId: HD, executorPda: EXECUTOR_PDA, log: (msg, fields) => logs.push({ msg, ...fields }) };
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
  it("is retried every interval while the RPC is unreachable, ingests nothing until it passes, then is not asked again", async () => {
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
    expect(seen[1]).toBe(0);
    expect(seen[2]).toBe(0);
    expect(seen[3]).toMatchObject({ txs: 0, lastPollAt: 1_800_000_001, lastPollOk: false, lastOkPollAt: null });
    expect(errors(r)).toEqual(Array.from({ length: 2 }, () => "getGenesisHash: request to rpc.example failed (TypeError)"));
    expect(JSON.stringify(r.logs)).not.toContain(KEY);
    // Passes 3 and 4 ran: one more getGenesisHash (it passed), then the poll; pass 4 does not ask again.
    expect(methods(r.chain).filter((m) => m === "getGenesisHash")).toHaveLength(11);
    expect(methods(r.chain).slice(10, 12)).toEqual(["getGenesisHash", "getSignaturesForAddress"]);
    expect((await r.ctx.store.loadMetricsInput()).digs).toHaveLength(1);
    expect(r.ore.calls).toBe(2);
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
    expect(methods(r.chain)).toEqual(["getGenesisHash", "getGenesisHash", "getGenesisHash"]);
    expect(errors(r)).toEqual(Array.from({ length: 3 }, () => `RPC rpc.example is not mainnet (genesis ${GENESIS_HASH.devnet}); refusing to mix clusters`));
    expect(r.ore.calls).toBe(0);
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
      { msg: "rpc poll", ingested: 1, accounts: 0 },
      { msg: "rpc poll", ingested: 0, accounts: null },
      { msg: "rpc poll", ingested: 0, accounts: null },
      { msg: "rpc poll", ingested: 0, accounts: 0 },
      { msg: "rpc poll", ingested: 0, accounts: null },
      { msg: "rpc poll", ingested: 0, accounts: null },
      { msg: "rpc poll", ingested: 0, accounts: 0 },
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
    expect(r.logs.map((l) => `${l.msg}${l.error ? `: ${l.error}` : ""}`)).toEqual([
      "ingest error: getSignaturesForAddress: HTTP 500 from rpc.example",
      "ingest: poll outcome not stored: database is gone",
      "ingest error: getSignaturesForAddress: HTTP 500 from rpc.example",
      "rpc poll",
    ]);
    expect(await r.ctx.store.health()).toMatchObject({ txs: 1, lastPollAt: 1_800_000_002, lastPollOk: true, lastOkPollAt: 1_800_000_002 });
  });

  it("reports a pass as failed when api.ore.com fails after the RPC poll went through", async () => {
    const r = await setup("mainnet");
    const down = (async () => new Response("unavailable", { status: 503 })) as typeof fetch;
    await expect(ingestLoop(r.ctx, r.cfg, r.rpc, { once: true, now: r.now, oreFetch: down })).rejects.toThrow("api.ore.com /events/reset page 0: HTTP 503");
    expect(errors(r)).toEqual(["api.ore.com /events/reset page 0: HTTP 503"]);
    // What the RPC poll stored stays; the outcome is that of the whole pass.
    expect(await r.ctx.store.health()).toMatchObject({ txs: 1, lastPollAt: 1_800_000_000, lastPollOk: false, lastOkPollAt: null });
  });

  it("counts a pass that waits for a transaction the RPC does not serve yet as succeeded", async () => {
    // What /v1/health cannot show: if the RPC never served that transaction, ingestion would stand still with lastPollOk true.
    const r = await setup("mainnet", { ORE_API_ENABLED: "0" });
    r.chain.unavailable.add(sig(1));
    await ingestLoop(r.ctx, r.cfg, r.rpc, { once: true, now: r.now });
    expect(r.logs.map((l) => l.msg)).toEqual(["rpc: transaction not yet available, will retry", "rpc poll"]);
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
