/**
 * The real `serve` and `ingest` commands as their own processes: the wiring in src/main.ts, which
 * the in-process tests of the loop and of the API do not run.
 *
 *  - `serve` with an RPC that refuses connections has to stay up, keep answering /v1/health, log
 *    the failed check and report it, and stop cleanly. (Before the genesis-hash check moved into
 *    the ingest loop this process exited with code 1 about eight seconds after it started listening.)
 *  - `serve` with an RPC on another cluster stores nothing, by the poller or by the webhook, until
 *    the RPC shows the right cluster.
 *  - `ingest --once` exits with a non-zero code when its pass fails.
 *  - Every log line carries a level; info lines are written to stdout, the others to stderr.
 */
import { spawn, type ChildProcess } from "node:child_process";
import { once } from "node:events";
import { createServer as createHttpServer, type Server as HttpServer } from "node:http";
import { createServer, type AddressInfo } from "node:net";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it } from "vitest";
import { GENESIS_HASH } from "../src/config.ts";
import { CONFIG_PDA, EXECUTOR_PDA, HEADS_DOWN_PROGRAM_ID } from "../src/constants.ts";
import { buildDigTx } from "../src/sim/txbuilder.ts";
import { addr, sig } from "./helpers.ts";

/** A port nothing listens on: bound once to learn a free number, then closed. */
async function closedPort(): Promise<number> {
  const s = createServer();
  await new Promise<void>((r) => s.listen(0, "127.0.0.1", r));
  const { port } = s.address() as { port: number };
  await new Promise((r) => s.close(r));
  return port;
}

const children: ChildProcess[] = [];
const rpcs: HttpServer[] = [];
afterEach(() => {
  for (const c of children.splice(0)) c.kill("SIGKILL");
  for (const s of rpcs.splice(0)) {
    s.closeAllConnections();
    s.close();
  }
});

interface Line {
  msg: string;
  level?: string;
  error?: string;
  /** Where the process wrote it. */
  stream: "stdout" | "stderr";
  [field: string]: unknown;
}

/**
 * `node src/main.ts <args>` with exactly `env`: nothing of the developer's environment (an RPC_URL,
 * a DATABASE_URL) leaks in. Its log lines are collected from both streams as they are written.
 */
function run(args: string[], env: Record<string, string>) {
  const child = spawn(process.execPath, ["src/main.ts", ...args], { cwd: fileURLToPath(new URL("..", import.meta.url)), env, stdio: ["ignore", "pipe", "pipe"] });
  children.push(child);
  const lines: Line[] = [];
  /** Everything the process wrote, to either stream. */
  let output = "";
  for (const stream of ["stdout", "stderr"] as const) {
    let buffered = "";
    child[stream]!.setEncoding("utf8").on("data", (chunk: string) => {
      output += chunk;
      buffered += chunk;
      const parts = buffered.split("\n");
      buffered = parts.pop()!;
      for (const p of parts) {
        try {
          lines.push({ ...JSON.parse(p), stream });
        } catch {
          /* not a log line (a Node warning) */
        }
      }
    });
  }
  const logged = async (msg: string) => {
    for (let i = 0; i < 400; i++) {
      const hit = lines.find((l) => l.msg === msg);
      if (hit) return hit;
      if (child.exitCode !== null) throw new Error(`${args.join(" ")} exited with code ${child.exitCode}: ${output.slice(-600)}`);
      await new Promise((r) => setTimeout(r, 100));
    }
    throw new Error(`no "${msg}" within 40 s: ${output.slice(-600)}`);
  };
  /** The exit code, once the process has ended and its log has been read to the end. */
  const ended = async () => ((await once(child, "close")) as [number | null])[0];
  return { child, lines, output: () => output, logged, ended };
}

async function health(apiPort: number) {
  const r = await fetch(`http://127.0.0.1:${apiPort}/v1/health`);
  return { status: r.status, data: ((await r.json()) as { data: Record<string, unknown> }).data };
}
/** /v1/health once a pass has finished: the outcome is stored right after the pass is logged. */
async function healthAfterPass(apiPort: number) {
  let h = await health(apiPort);
  for (let i = 0; i < 50 && h.data.lastPollAt === null; i++) {
    await new Promise((r) => setTimeout(r, 100));
    h = await health(apiPort);
  }
  return h;
}

/**
 * A stand-in RPC on a loopback port. It answers getGenesisHash with `genesis`, which a test can
 * change while the indexer runs, knows no signatures and no accounts, and keeps the methods asked.
 */
async function standInRpc(genesis: string) {
  const state = { genesis, methods: [] as string[] };
  const server = createHttpServer((req, res) => {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", (chunk: string) => (body += chunk));
    req.on("end", () => {
      const { id, method } = JSON.parse(body) as { id: number; method: string };
      state.methods.push(method);
      const result = method === "getGenesisHash" ? state.genesis : method === "getSignaturesForAddress" ? [] : { context: { slot: 1 }, value: [] };
      res.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify({ jsonrpc: "2.0", id, result }));
    });
  });
  rpcs.push(server);
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  return { state, port: (server.address() as AddressInfo).port };
}

describe("serve with an unreachable RPC", () => {
  // About 9 s on an idle machine; the waits below allow 40 s each when it is busy.
  it("stays up, answers /v1/health with 200 and reports the failed pass", { timeout: 120_000 }, async () => {
    const [apiPort, rpcPort] = [await closedPort(), await closedPort()];
    const p = run(["serve"], {
      DATABASE_URL: "pglite://memory", INDEXER_DATASET: "mainnet", RPC_URL: `http://127.0.0.1:${rpcPort}/?api-key=not-a-real-key-0001`, HOST: "127.0.0.1",
      PORT: String(apiPort), INGEST_INTERVAL_S: "1", ORE_API_ENABLED: "0", MARKET_PRICE_SOURCES: "none",
    });

    // An info line, on stdout: a log viewer must not show it as an error.
    expect(await p.logged("api listening")).toMatchObject({ level: "info", stream: "stdout", dataset: "mainnet", rpc: `127.0.0.1:${rpcPort}` });
    expect(await health(apiPort)).toMatchObject({ status: 200, data: { status: "ok", txs: 0, lastPollAt: null, lastPollOk: null } });

    // Five attempts with 0.5, 1, 2 and 4 s between them, then the pass fails and is logged: an error, on stderr.
    const failure = await p.logged("ingest error");
    expect(failure).toMatchObject({ level: "error", stream: "stderr", error: `getGenesisHash: request to 127.0.0.1:${rpcPort} failed (TypeError)` });
    const after = await healthAfterPass(apiPort);
    expect(after).toMatchObject({ status: 200, data: { status: "ok", txs: 0, lastPollOk: false, lastOkPollAt: null } });
    expect(typeof after.data.lastPollAt).toBe("number");
    expect(p.child.exitCode).toBeNull();
    expect(p.lines.some((l) => l.msg === "fatal" || l.msg === "ingest stopped")).toBe(false);
    expect(p.output()).not.toContain("not-a-real-key-0001");

    p.child.kill("SIGTERM");
    expect(await p.ended()).toBe(0);
  });
});

describe("serve with an RPC on another cluster", () => {
  const KEY = "not-a-real-key-0002";
  const HOOK = "not-a-real-hook-secret-0002";
  const dig = buildDigTx({
    signature: sig(2), slot: 2, blockTime: 1_790_800_000, cranker: addr(9), programId: HEADS_DOWN_PROGRAM_ID, configPda: CONFIG_PDA,
    executorPda: EXECUTOR_PDA, roundAccount: addr(10), roundId: 5n,
    rigs: [{ rig: addr(1), authority: addr(11), automation: addr(12), miner: addr(13), outcome: { kind: "dug", perTile: 10n, mask: 7, emaEv: 1n } }],
  });

  it("stores nothing, by the poller or by the webhook, until the RPC shows the right cluster", { timeout: 120_000 }, async () => {
    const rpc = await standInRpc(GENESIS_HASH.devnet!);
    const apiPort = await closedPort();
    // The webhook trusts its payload here, so only the cluster check stands between a posted dig and the store.
    const p = run(["serve"], {
      DATABASE_URL: "pglite://memory", INDEXER_DATASET: "mainnet", RPC_URL: `http://127.0.0.1:${rpc.port}/?api-key=${KEY}`, HOST: "127.0.0.1",
      PORT: String(apiPort), INGEST_INTERVAL_S: "1", ORE_API_ENABLED: "0", RESOLVE_ROUNDS: "0", MARKET_PRICE_SOURCES: "none",
      HELIUS_WEBHOOK_SECRET: HOOK, HELIUS_WEBHOOK_TRUST_PAYLOAD: "1",
    });
    const post = () => fetch(`http://127.0.0.1:${apiPort}/webhooks/helius`, { method: "POST", body: JSON.stringify([dig]), headers: { authorization: HOOK } });
    const refusal = `RPC 127.0.0.1:${rpc.port} is not mainnet (genesis ${GENESIS_HASH.devnet}); refusing to mix clusters`;

    expect((await p.logged("ingest error")).error).toBe(refusal);
    const refused = await post();
    expect(refused.status).toBe(503);
    expect(refused.headers.get("retry-after")).toBe("30");
    expect(await p.logged("webhook: refused, the RPC's cluster is not verified")).toMatchObject({ level: "warn", stream: "stderr", error: refusal });
    expect((await healthAfterPass(apiPort)).data).toMatchObject({ txs: 0, lastPollOk: false, lastOkPollAt: null });
    // Only the check was asked of the RPC: no signature, no transaction, no account.
    expect([...new Set(rpc.state.methods)]).toEqual(["getGenesisHash"]);
    expect(p.child.exitCode).toBeNull();

    // The RPC now shows mainnet: the next pass polls, and the webhook stores the dig.
    rpc.state.genesis = GENESIS_HASH.mainnet!;
    expect(await p.logged("rpc poll")).toMatchObject({ level: "info", stream: "stdout" });
    const taken = await post();
    expect(taken.status).toBe(200);
    expect(await taken.json()).toEqual({ received: 1, ingested: 1, rejected: 0 });
    expect((await health(apiPort)).data).toMatchObject({ txs: 1, lastSlot: 2 });
    expect(rpc.state.methods).toEqual(expect.arrayContaining(["getSignaturesForAddress", "getProgramAccounts"]));
    expect(p.output()).not.toContain(KEY);
    expect(p.output()).not.toContain(HOOK);
    // Every line has its level, and the stream that goes with it.
    expect(p.lines.length).toBeGreaterThan(3);
    for (const l of p.lines) expect([l.msg, l.level, l.stream]).toEqual([l.msg, expect.stringMatching(/^(info|warn|error)$/), l.level === "info" ? "stdout" : "stderr"]);

    p.child.kill("SIGTERM");
    expect(await p.ended()).toBe(0);
  });
});

describe("ingest --once", () => {
  it("exits with code 1 when its pass fails, and with 0 when it succeeds", { timeout: 120_000 }, async () => {
    const rpc = await standInRpc(GENESIS_HASH.devnet!);
    const env = { DATABASE_URL: "pglite://memory", INDEXER_DATASET: "mainnet", RPC_URL: `http://127.0.0.1:${rpc.port}`, ORE_API_ENABLED: "0", RESOLVE_ROUNDS: "0" };
    const refusal = `RPC 127.0.0.1:${rpc.port} is not mainnet (genesis ${GENESIS_HASH.devnet}); refusing to mix clusters`;

    const failing = run(["ingest", "--once"], env);
    expect(await failing.ended()).toBe(1);
    // The whole of each line: the time, the level, the message and its fields. Both are errors, on stderr.
    expect(failing.lines).toEqual([
      { t: expect.any(String), level: "error", msg: "ingest error", error: refusal, stream: "stderr" },
      { t: expect.any(String), level: "error", msg: "fatal", error: refusal, stream: "stderr" },
    ]);
    expect(rpc.state.methods).toEqual(["getGenesisHash"]);

    rpc.state.genesis = GENESIS_HASH.mainnet!;
    rpc.state.methods.length = 0;
    const passing = run(["ingest", "--once"], env);
    expect(await passing.ended()).toBe(0);
    expect(passing.lines).toEqual([{ t: expect.any(String), level: "info", msg: "rpc poll", ingested: 0, accounts: 0, stream: "stdout" }]);
    // One pass: the check, the two signature lists, the four scans of a first poll.
    expect(rpc.state.methods.slice(0, 3)).toEqual(["getGenesisHash", "getSignaturesForAddress", "getSignaturesForAddress"]);
    expect(rpc.state.methods.slice(3)).toEqual(Array.from({ length: 4 }, () => "getProgramAccounts"));
  });
});
