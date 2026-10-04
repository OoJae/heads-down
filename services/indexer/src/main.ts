/**
 * CLI:
 *   node src/main.ts migrate                      apply migrations
 *   node src/main.ts serve                        public API (+ background ingest if RPC_URL is set)
 *   node src/main.ts ingest [--once]              poll RPC / api.ore.com into INDEXER_DATASET
 *   node src/main.ts simulate [--seed s] [--rigs n] [--nights n] [--start YYYY-MM-DD]
 *                                                 (re)generate the `simulated` dataset
 *   node src/main.ts demo [--port 8787]           in-memory Postgres + simulation + API, one command
 *   node src/main.ts decode <signature> [--json]  print a transaction's heads_down instructions and
 *                                                 events with names (needs RPC_URL)
 */
import { parseArgs } from "node:util";
import { createApiServer } from "./api/server.ts";
import { describeConfig, loadConfig, type Config } from "./config.ts";
import { resolveExecutorPda, CONFIG_PDA, HEADS_DOWN_PROGRAM_ID } from "./constants.ts";
import { derivePda } from "./constants.ts";
import type { IngestContext } from "./ingest.ts";
import { clusterCheck, ingestLoop } from "./loop.ts";
import { DEFAULT_SIM, runSimulation } from "./sim/simulate.ts";
import { describeTransaction, formatDescribed } from "./decode.ts";
import { explorerUrl } from "./api/explorer.ts";
import { isSignature } from "./codec/base58.ts";
import { MarketPrice, fixedSource, sourcesFromConfig } from "./sources/market.ts";
import { RpcClient } from "./sources/rpc.ts";
import { migrate, openDb, type Db } from "./store/db.ts";
import { Store } from "./store/store.ts";

/** Every per-dataset table, children before parents (txs is referenced by the event tables). */
const SIMULATED_TABLES = [
  "ev_rig_dug", "ev_rig_skipped", "ev_shift_armed", "ev_shift_ended", "ev_seeker_verified", "ev_rig_registered", "ev_rig_closed",
  "ev_heartbeats_recorded", "ev_shift_broken", "hd_heartbeats", "hd_arm_plans", "ore_deploys", "txs", "ore_rounds", "ore_round_state",
  "ore_round_missing", "acc_rigs", "acc_shift_logs", "acc_seeker_seats", "acc_config", "ingest_cursors", "ingest_problems",
];

const log = (msg: string, fields: Record<string, unknown> = {}) =>
  process.stderr.write(JSON.stringify({ t: new Date().toISOString(), msg, ...fields }) + "\n");

async function bind(db: Db, cfg: Config, simSeed?: string): Promise<IngestContext> {
  const executorPda = await resolveExecutorPda(cfg.programId);
  const store = await Store.bind(db, { name: cfg.dataset, programId: cfg.programId, executorPda, simSeed: simSeed ?? null });
  return { store, programId: cfg.programId, executorPda, log };
}

async function simulate(db: Db, cfg: Config, args: Record<string, string | undefined>): Promise<string[]> {
  const seed = args.seed ?? DEFAULT_SIM.seed;
  const executorPda = await resolveExecutorPda(cfg.programId);
  // A fresh simulation always replaces the previous one; it never touches real datasets.
  await db.transaction(async (tx) => {
    for (const t of SIMULATED_TABLES) {
      await tx.query(`DELETE FROM ${t} WHERE dataset = 'simulated'`);
    }
    await tx.query("DELETE FROM datasets WHERE name = 'simulated'");
  });
  const store = await Store.bind(db, { name: "simulated", programId: cfg.programId, executorPda, simSeed: seed });
  const configPda = cfg.programId === HEADS_DOWN_PROGRAM_ID ? CONFIG_PDA : (await derivePda(cfg.programId, "config")).address;
  const t = Date.now();
  const out = await runSimulation(
    { store, programId: cfg.programId, executorPda, log },
    {
      seed,
      rigs: Number(args.rigs ?? DEFAULT_SIM.rigs),
      nights: Number(args.nights ?? DEFAULT_SIM.nights),
      startDay: args.start ?? DEFAULT_SIM.startDay,
      programId: cfg.programId,
      executorPda,
      configPda,
    },
    (m) => log(m),
  );
  await store.setCursor("sim", "team-crankers", out.teamCrankers.join(","));
  await store.setCursor("sim", "market-lamports-per-ore", out.marketLamportsPerOre.toString());
  log("simulation stored (dataset = simulated)", { seed, txs: out.txs.length, ms: Date.now() - t });
  return out.teamCrankers;
}

async function serve(db: Db, cfg: Config, ctx: IngestContext, teamCrankers: string[]): Promise<void> {
  const rpc = cfg.rpcUrl ? new RpcClient(cfg.rpcUrl) : null;
  // One genesis-hash check for the ingest loop and the webhook: neither stores anything before it has passed.
  const verifyCluster = clusterCheck(cfg, rpc);
  // The simulated dataset carries its own (simulated, labelled) market price; real datasets ask Jupiter / api.ore.com.
  const simPrice = cfg.dataset === "simulated" ? await ctx.store.getCursor("sim", "market-lamports-per-ore") : null;
  const market =
    cfg.dataset === "simulated"
      ? simPrice
        ? new MarketPrice([fixedSource(BigInt(simPrice), "simulated")])
        : null
      : cfg.marketSources.length
        ? new MarketPrice(sourcesFromConfig(cfg.marketSources))
        : null;
  const server = createApiServer({
    store: ctx.store,
    info: await ctx.store.info(),
    defaultTzOffsetMinutes: cfg.tzOffsetMinutes,
    teamCrankers,
    corsOrigin: cfg.corsOrigin,
    market,
    localExplorerRpc: cfg.localExplorerRpc,
    webhook: cfg.heliusWebhookSecret && cfg.dataset !== "simulated"
      ? { secret: cfg.heliusWebhookSecret, rpc, trustPayload: cfg.heliusTrustPayload, ctx, verifyCluster }
      : null,
    log,
  });
  await new Promise<void>((r) => server.listen(cfg.port, cfg.host, r));
  log("api listening", describeConfig(cfg));
  if (cfg.dataset !== "simulated" && cfg.ingestIntervalS > 0 && (rpc || cfg.oreApiEnabled)) {
    // The loop catches a failed pass itself. This is the second guard: whatever still escapes it is
    // logged instead of ending the process, and /v1/health then shows the last poll growing old.
    ingestLoop(ctx, cfg, rpc, { once: false, verifyCluster }).catch((e: unknown) => log("ingest stopped", { error: e instanceof Error ? e.message : String(e) }));
  }
  const stop = () => server.close(() => void db.close().then(() => process.exit(0)));
  process.on("SIGINT", stop);
  process.on("SIGTERM", stop);
}

async function main(): Promise<void> {
  const [cmd = "serve", ...rest] = process.argv.slice(2);
  const { values, positionals } = parseArgs({
    args: rest,
    options: {
      once: { type: "boolean" },
      seed: { type: "string" },
      rigs: { type: "string" },
      nights: { type: "string" },
      start: { type: "string" },
      port: { type: "string" },
      json: { type: "boolean" },
    },
    allowPositionals: true,
  });
  if (cmd === "decode") {
    const cfg = loadConfig(process.env);
    const sig = positionals[0];
    if (!isSignature(sig)) throw new Error("usage: node src/main.ts decode <transaction signature> [--json]  (RPC_URL selects the cluster)");
    if (!cfg.rpcUrl) throw new Error("decode needs RPC_URL");
    const rpc = new RpcClient(cfg.rpcUrl);
    const tx = await rpc.getTransaction(sig);
    if (!tx) throw new Error(`transaction ${sig} not found at finalized commitment on ${rpc.host}`);
    const d = describeTransaction(tx, { programId: cfg.programId, executorPda: await resolveExecutorPda(cfg.programId) });
    process.stdout.write(
      values.json
        ? JSON.stringify(d, null, 2) + "\n"
        : formatDescribed(d, (s) => (cfg.dataset === "simulated" ? null : explorerUrl(cfg.dataset, "tx", s, cfg.localExplorerRpc))) + "\n",
    );
    return;
  }
  if (cmd === "demo") {
    const cfg = loadConfig(process.env, {
      dataset: "simulated",
      databaseUrl: process.env.DATABASE_URL || "pglite://memory",
      ...(values.port ? { port: Number(values.port) } : {}),
    });
    const db = await openDb(cfg.databaseUrl, log);
    await migrate(db);
    const team = await simulate(db, cfg, values as Record<string, string | undefined>);
    const ctx = await bind(db, cfg, values.seed ?? DEFAULT_SIM.seed);
    await serve(db, cfg, ctx, team);
    return;
  }
  const cfg = loadConfig(process.env, values.port ? { port: Number(values.port) } : {});
  const db = await openDb(cfg.databaseUrl, log);
  await migrate(db);
  switch (cmd) {
    case "migrate":
      log("migrations applied");
      await db.close();
      return;
    case "simulate":
      await simulate(db, { ...cfg, dataset: "simulated" }, values as Record<string, string | undefined>);
      await db.close();
      return;
    case "ingest": {
      if (cfg.dataset === "simulated") throw new Error("ingest writes real data; use `simulate` for the simulated dataset");
      const ctx = await bind(db, cfg);
      await ingestLoop(ctx, cfg, cfg.rpcUrl ? new RpcClient(cfg.rpcUrl) : null, { once: values.once === true });
      await db.close();
      return;
    }
    case "serve": {
      const info = await db.query<{ sim_seed: string | null }>("SELECT sim_seed FROM datasets WHERE name = $1", [cfg.dataset]);
      if (cfg.dataset === "simulated" && !info[0]) throw new Error("no simulated dataset yet: run `pnpm simulate` first (or `pnpm demo`)");
      const ctx = await bind(db, cfg, info[0]?.sim_seed ?? undefined);
      const simTeam = cfg.dataset === "simulated" ? (await ctx.store.getCursor("sim", "team-crankers"))?.split(",") : undefined;
      await serve(db, cfg, ctx, simTeam ?? cfg.teamCrankers);
      return;
    }
    default:
      throw new Error(`unknown command ${cmd}`);
  }
}

main().catch((e) => {
  log("fatal", { error: e instanceof Error ? e.message : String(e) });
  process.exit(1);
});
