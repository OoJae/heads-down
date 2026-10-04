/**
 * The real `serve` command as its own process, with an RPC that refuses connections: it has to
 * stay up, keep answering /v1/health, log the failed check and report it, and stop cleanly.
 * (Before the genesis-hash check moved into the ingest loop this process exited with code 1 about
 * eight seconds after it started listening.)
 */
import { spawn, type ChildProcess } from "node:child_process";
import { once } from "node:events";
import { createServer } from "node:net";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it } from "vitest";

/** A port nothing listens on: bound once to learn a free number, then closed. */
async function closedPort(): Promise<number> {
  const s = createServer();
  await new Promise<void>((r) => s.listen(0, "127.0.0.1", r));
  const { port } = s.address() as { port: number };
  await new Promise((r) => s.close(r));
  return port;
}

let child: ChildProcess | null = null;
afterEach(() => {
  child?.kill("SIGKILL");
  child = null;
});

describe("serve with an unreachable RPC", () => {
  // About 9 s on an idle machine; the waits below allow 40 s each when it is busy.
  it("stays up, answers /v1/health with 200 and reports the failed pass", { timeout: 120_000 }, async () => {
    const [apiPort, rpcPort] = [await closedPort(), await closedPort()];
    const lines: { msg: string; error?: string }[] = [];
    child = spawn(process.execPath, ["src/main.ts", "serve"], {
      cwd: fileURLToPath(new URL("..", import.meta.url)),
      // Only these: nothing of the developer's environment (an RPC_URL, a DATABASE_URL) leaks in.
      env: {
        DATABASE_URL: "pglite://memory", INDEXER_DATASET: "mainnet", RPC_URL: `http://127.0.0.1:${rpcPort}/?api-key=not-a-real-key-0001`, HOST: "127.0.0.1",
        PORT: String(apiPort), INGEST_INTERVAL_S: "1", ORE_API_ENABLED: "0", MARKET_PRICE_SOURCES: "none",
      },
      stdio: ["ignore", "ignore", "pipe"],
    });
    let buffered = "";
    let stderr = "";
    child.stderr!.setEncoding("utf8").on("data", (chunk: string) => {
      stderr += chunk;
      buffered += chunk;
      const parts = buffered.split("\n");
      buffered = parts.pop()!;
      for (const p of parts) {
        try {
          lines.push(JSON.parse(p));
        } catch {
          /* not a log line (a Node warning) */
        }
      }
    });
    const logged = async (msg: string) => {
      for (let i = 0; i < 400; i++) {
        const hit = lines.find((l) => l.msg === msg);
        if (hit) return hit;
        if (child!.exitCode !== null) throw new Error(`serve exited with code ${child!.exitCode}: ${stderr.slice(-600)}`);
        await new Promise((r) => setTimeout(r, 100));
      }
      throw new Error(`no "${msg}" within 40 s: ${stderr.slice(-600)}`);
    };
    const health = async () => {
      const r = await fetch(`http://127.0.0.1:${apiPort}/v1/health`);
      return { status: r.status, data: ((await r.json()) as { data: Record<string, unknown> }).data };
    };

    await logged("api listening");
    expect(await health()).toMatchObject({ status: 200, data: { status: "ok", txs: 0, lastPollAt: null, lastPollOk: null } });

    // Five attempts with 0.5, 1, 2 and 4 s between them, then the pass fails and is logged.
    const failure = await logged("ingest error");
    expect(failure.error).toBe(`getGenesisHash: request to 127.0.0.1:${rpcPort} failed (TypeError)`);
    // The outcome is stored right after the log line.
    let after = await health();
    for (let i = 0; i < 50 && after.data.lastPollAt === null; i++) {
      await new Promise((r) => setTimeout(r, 100));
      after = await health();
    }
    expect(after).toMatchObject({ status: 200, data: { status: "ok", txs: 0, lastPollOk: false, lastOkPollAt: null } });
    expect(typeof after.data.lastPollAt).toBe("number");
    expect(child.exitCode).toBeNull();
    expect(lines.some((l) => l.msg === "fatal" || l.msg === "ingest stopped")).toBe(false);
    expect(stderr).not.toContain("not-a-real-key-0001");

    child.kill("SIGTERM");
    const [code] = (await once(child, "exit")) as [number | null];
    expect(code).toBe(0);
  });
});
