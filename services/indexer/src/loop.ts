/**
 * The ingest loop behind `serve` and `ingest`: every INGEST_INTERVAL_S one pass over the sources
 * that are configured (the RPC poll with its account snapshot, the ORE round resolver, api.ore.com).
 *
 * A pass has two steps, the RPC's and api.ore.com's. A step that fails is logged as `ingest error`
 * and does not keep the other from running; the pass is failed if either did, and it is tried
 * again at the next interval. It never ends the process: the API keeps serving what is stored
 * while the RPC is down or out of credits. The time and outcome of each pass are stored for
 * /v1/health.
 */
import { GENESIS_HASH, type Config } from "./config.ts";
import { newSnapshotState, pollRpcOnce, type IngestContext } from "./ingest.ts";
import { ORE_API_STALLED_PASSES, pollOreRounds } from "./sources/oreApi.ts";
import { resolveRounds } from "./sources/rounds.ts";
import type { RpcClient } from "./sources/rpc.ts";

const errorText = (e: unknown) => (e instanceof Error ? e.message : String(e));

/**
 * The genesis-hash check, so a mainnet dataset can never be fed from a devnet RPC (or the reverse).
 * The returned function resolves once the RPC has shown the dataset's genesis hash and rejects until
 * then; each call before that asks the RPC again (calls that overlap share one request). After it has
 * passed it costs nothing. With no RPC, or a dataset without a pinned hash (localnet), there is
 * nothing to compare and it resolves at once.
 */
export function clusterCheck(cfg: Config, rpc: RpcClient | null): () => Promise<void> {
  const expected = rpc ? GENESIS_HASH[cfg.dataset] : undefined;
  let passed = !rpc || !expected;
  let running: Promise<void> | null = null;
  return () => {
    if (passed || !rpc) return Promise.resolve();
    running ??= (async () => {
      try {
        const got = await rpc.call<unknown>("getGenesisHash", []);
        // What it answered instead is the provider's text: scrubbed and cut like its error messages.
        if (got !== expected) throw new Error(`RPC ${rpc.host} is not ${cfg.dataset} (genesis ${rpc.scrub(String(got)).slice(0, 64)}); refusing to mix clusters`);
        passed = true;
      } finally {
        running = null;
      }
    })();
    return running;
  };
}

export interface LoopOptions {
  /** One pass, then return; a failed pass rejects (`ingest --once`). */
  once: boolean;
  /** The cluster check to wait for (default: a new one). `serve` shares it with the webhook. */
  verifyCluster?: () => Promise<void>;
  sleep?: (ms: number) => Promise<void>;
  /** Unix seconds. */
  now?: () => number;
  /** fetch for api.ore.com. */
  oreFetch?: typeof fetch;
  /** The pause between two api.ore.com pages (default: a real wait). */
  orePause?: (ms: number) => Promise<void>;
}

export async function ingestLoop(ctx: IngestContext, cfg: Config, rpc: RpcClient | null, opts: LoopOptions): Promise<void> {
  if (!rpc && !cfg.oreApiEnabled) throw new Error("nothing to ingest: set RPC_URL and/or ORE_API_ENABLED");
  // Without a pause between passes the loop would call the RPC as fast as it answers.
  if (!opts.once && cfg.ingestIntervalS <= 0) throw new Error("INGEST_INTERVAL_S=0 turns polling off: use `ingest --once` for a single pass");
  const log = ctx.log ?? (() => undefined);
  const verifyCluster = opts.verifyCluster ?? clusterCheck(cfg, rpc);
  const sleep = opts.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
  const now = opts.now ?? (() => Math.floor(Date.now() / 1000));
  const snapshot = newSnapshotState();
  for (;;) {
    const failures: unknown[] = [];
    const failed = (step: "rpc" | "ore-api", e: unknown) => {
      failures.push(e);
      log("ingest error", { step, error: errorText(e) }, "error");
    };
    // Whether the RPC answered everything this pass asked of it: the ORE spot check below needs that.
    let rpcWorked = false;
    if (rpc) {
      try {
        // First in the step: until the RPC has shown the right cluster, nothing is read from it.
        await verifyCluster();
        const r = await pollRpcOnce(
          ctx,
          rpc,
          { addresses: [ctx.programId, ctx.executorPda], maxBackfill: cfg.rpcMaxBackfill, concurrency: 4, snapshotEvery: cfg.snapshotEveryPolls },
          snapshot,
        );
        log("rpc poll", r);
        if (cfg.resolveRounds) {
          // Without api.ore.com (localnet, devnet) the chain is the only source for every round of a shift.
          const withShiftRounds = !(cfg.oreApiEnabled && cfg.dataset === "mainnet");
          const rr = await resolveRounds(ctx, rpc, { maxRounds: cfg.resolveMaxRounds, withShiftRounds, resetLookups: cfg.resolveResetLookups });
          if (rr.snapshots || rr.missing || rr.resets) log("ore rounds resolved", { ...rr });
        }
        rpcWorked = true;
      } catch (e) {
        failed("rpc", e);
      }
    }
    if (cfg.oreApiEnabled && cfg.dataset === "mainnet") {
      try {
        // api.ore.com is not reached through the RPC, so it is asked whatever became of the step above.
        // Its spot check does use the RPC: after an RPC step that failed, this pass stores its rounds without it.
        const r = await pollOreRounds(
          ctx,
          { since: cfg.oreRoundsSince, maxPages: cfg.oreApiPagesPerPass, verifySample: cfg.oreApiVerifySample, fetchImpl: opts.oreFetch, sleep: opts.orePause, now },
          rpcWorked ? rpc : null,
        );
        log("ore rounds", { ...r });
        // Turned away is not a failure. Turned away pass after pass, without getting further, is.
        if ((r.stalledPasses ?? 0) >= ORE_API_STALLED_PASSES) throw new Error(`api.ore.com: ${r.stopped}; ${r.stalledPasses} passes in a row without progress`);
      } catch (e) {
        failed("ore-api", e);
      }
    }
    // Its own guard: a database that cannot take this note must not end the loop.
    await ctx.store.recordPoll(now(), failures.length === 0).catch((e: unknown) => log("ingest: poll outcome not stored", { error: errorText(e) }, "error"));
    if (opts.once) {
      if (failures.length > 0) throw failures[0];
      return;
    }
    await sleep(cfg.ingestIntervalS * 1000);
  }
}
