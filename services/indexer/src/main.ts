/**
 * CLI:
 *   node src/main.ts migrate                      apply migrations
 *   node src/main.ts serve                        public API (+ background ingest if RPC_URL is set)
 *   node src/main.ts ingest [--once]              poll RPC / api.ore.com into INDEXER_DATASET
 *   node src/main.ts simulate [--seed s] [--rigs n] [--nights n] [--start YYYY-MM-DD]
 *                                                 (re)generate the `simulated` dataset
 *   node src/main.ts demo [--port 8787]           in-memory Postgres + simulation + API, one command
 */
import { parseArgs } from "node:util";
import { createApiServer } from "./api/server.ts";
import { GENESIS_HASH, describeConfig, loadConfig, type Config } from "./config.ts";
import { resolveExecutorPda, CONFIG_PDA, HEADS_DOWN_PROGRAM_ID } from "./constants.ts";
import { derivePda } from "./constants.ts";
import { pollRpcOnce, type IngestContext } from "./ingest.ts";
import { DEFAULT_SIM, runSimulation } from "./sim/simulate.ts";
import { pollOreRounds } from "./sources/oreApi.ts";
import { RpcClient } from "./sources/rpc.ts";
import { migrate, openDb, type Db } from "./store/db.ts";
import { Store } from "./store/store.ts";

const log = (msg: string, fields: Record<string, unknown> = {}) =>
  process.stderr.write(JSON.stringify({ t: new Date().toISOString(), msg, ...fields }) + "\n");

async function bind(db: Db, cfg: Config, simSeed?: string): Promise<IngestContext> {
  const executorPda = await resolveExecutorPda(cfg.programId);
  const store = await Store.bind(db, { name: cfg.dataset, programId: cfg.programId, executorPda, simSeed: simSeed ?? null });
  return { store, programId: cfg.programId, executorPda, log };
}

async function checkCluster(cfg: Config, rpc: RpcClient): Promise<void> {
  const expected = GENESIS_HASH[cfg.dataset];
  if (!expected) return;
  const got = await rpc.call<string>("getGenesisHash", []);
  if (got !== expected) throw new Error(`RPC ${rpc.host} is not ${cfg.dataset} (genesis ${got}); refusing to mix clusters`);
}

async function ingestLoop(ctx: IngestContext, cfg: Config, once: boolean): Promise<void> {
  const rpc = cfg.rpcUrl ? new RpcClient(cfg.rpcUrl) : null;
  if (rpc) await checkCluster(cfg, rpc);
  if (!rpc && !cfg.oreApiEnabled) throw new Error("nothing to ingest: set RPC_URL and/or ORE_API_ENABLED");
  for (;;) {
    try {
      if (rpc) {
        const r = await pollRpcOnce(ctx, rpc, { addresses: [ctx.programId, ctx.executorPda], maxBackfill: cfg.rpcMaxBackfill, concurrency: 4 });
        log("rpc poll", r);
      }
      if (cfg.oreApiEnabled && cfg.dataset === "mainnet") {
        const r = await pollOreRounds(ctx, { since: cfg.oreRoundsSince, maxPages: 200, verifySample: cfg.oreApiVerifySample, sleepMs: 500 }, rpc);
        log("ore rounds", r);
      }
    } catch (e) {
      log("ingest error", { error: e instanceof Error ? e.message : String(e) });
      if (once) throw e;
    }
    if (once) return;
    await new Promise((r) => setTimeout(r, cfg.ingestIntervalS * 1000));
  }
}

async function simulate(db: Db, cfg: Config, args: Record<string, string | undefined>): Promise<string[]> {
  const seed = args.seed ?? DEFAULT_SIM.seed;
  const executorPda = await resolveExecutorPda(cfg.programId);
  // A fresh simulation always replaces the previous one; it never touches real datasets.
  await db.transaction(async (tx) => {
    for (const t of ["ev_rig_dug", "ev_rig_skipped", "ev_shift_armed", "ev_shift_ended", "ev_seeker_verified", "ore_deploys",
      "txs", "ore_rounds", "acc_rigs", "acc_shift_logs", "acc_seeker_seats", "acc_config", "ingest_cursors", "ingest_problems"]) {
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
  log("simulation stored (dataset = simulated)", { seed, txs: out.txs.length, ms: Date.now() - t });
  return out.teamCrankers;
}

async function serve(db: Db, cfg: Config, ctx: IngestContext, teamCrankers: string[]): Promise<void> {
  const rpc = cfg.rpcUrl ? new RpcClient(cfg.rpcUrl) : null;
  const server = createApiServer({
    store: ctx.store,
    info: await ctx.store.info(),
    defaultTzOffsetMinutes: cfg.tzOffsetMinutes,
    teamCrankers,
    corsOrigin: cfg.corsOrigin,
    webhook: cfg.heliusWebhookSecret && cfg.dataset !== "simulated"
      ? { secret: cfg.heliusWebhookSecret, rpc, trustPayload: cfg.heliusTrustPayload, ctx }
      : null,
    log,
  });
  await new Promise<void>((r) => server.listen(cfg.port, cfg.host, r));
  log("api listening", describeConfig(cfg));
  if (cfg.dataset !== "simulated" && cfg.ingestIntervalS > 0 && (rpc || cfg.oreApiEnabled)) {
    void ingestLoop(ctx, cfg, false);
  }
  const stop = () => server.close(() => void db.close().then(() => process.exit(0)));
  process.on("SIGINT", stop);
  process.on("SIGTERM", stop);
}

async function main(): Promise<void> {
  const [cmd = "serve", ...rest] = process.argv.slice(2);
  const { values } = parseArgs({
    args: rest,
    options: {
      once: { type: "boolean" },
      seed: { type: "string" },
      rigs: { type: "string" },
      nights: { type: "string" },
      start: { type: "string" },
      port: { type: "string" },
    },
  });
  if (cmd === "demo") {
    const cfg = loadConfig(process.env, {
      dataset: "simulated",
      databaseUrl: process.env.DATABASE_URL || "pglite://memory",
      ...(values.port ? { port: Number(values.port) } : {}),
    });
    const db = await openDb(cfg.databaseUrl);
    await migrate(db);
    const team = await simulate(db, cfg, values as Record<string, string | undefined>);
    const ctx = await bind(db, cfg, values.seed ?? DEFAULT_SIM.seed);
    await serve(db, cfg, ctx, team);
    return;
  }
  const cfg = loadConfig(process.env, values.port ? { port: Number(values.port) } : {});
  const db = await openDb(cfg.databaseUrl);
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
      await ingestLoop(ctx, cfg, values.once === true);
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
