/**
 * Environment configuration. Secrets (RPC_URL may embed an API key, HELIUS_WEBHOOK_SECRET,
 * DATABASE_URL with a password) are read here and never logged; `describeConfig` prints a
 * redacted view.
 */
import { isAddress } from "./codec/base58.ts";
import { HEADS_DOWN_PROGRAM_ID } from "./constants.ts";
import { assertTzOffset } from "./metrics/time.ts";
import { isDataset, type Dataset } from "./model.ts";

export interface Config {
  databaseUrl: string;
  dataset: Dataset;
  programId: string;
  rpcUrl: string | null;
  heliusWebhookSecret: string | null;
  heliusTrustPayload: boolean;
  host: string;
  port: number;
  corsOrigin: string;
  tzOffsetMinutes: number;
  teamCrankers: string[];
  ingestIntervalS: number;
  /**
   * Account snapshot (four getProgramAccounts scans) at least every this many polls. It is also
   * taken on the first poll after start and on every poll that finds a new transaction that
   * succeeded; 1 = every poll.
   */
  snapshotEveryPolls: number;
  rpcMaxBackfill: number;
  oreApiEnabled: boolean;
  oreRoundsSince: number;
  oreApiVerifySample: number;
  /** Market price sources for the haul, tried in order ([] = none). */
  marketSources: string[];
  /** ORE round resolver (Round accounts + reset transactions over RPC). */
  resolveRounds: boolean;
  resolveMaxRounds: number;
  resolveResetLookups: number;
  /** RPC shown in localnet explorer links (only when it is a loopback URL, never a keyed one). */
  localExplorerRpc: string;
}

/** A loopback http(s) URL is safe to show in links; anything else may embed a key. */
export function loopbackRpc(url: string | null): string | null {
  if (!url) return null;
  try {
    const u = new URL(url);
    if ((u.protocol === "http:" || u.protocol === "https:") && (u.hostname === "127.0.0.1" || u.hostname === "localhost") && !u.username && !u.password && !u.search) {
      return `${u.protocol}//${u.host}`;
    }
  } catch {
    /* not a URL */
  }
  return null;
}

function int(env: NodeJS.ProcessEnv, name: string, def: number, min: number, max: number): number {
  const raw = env[name];
  if (raw === undefined || raw === "") return def;
  if (!/^-?\d+$/.test(raw)) throw new Error(`${name} must be an integer`);
  const v = Number(raw);
  if (v < min || v > max) throw new Error(`${name} must be in [${min}, ${max}]`);
  return v;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env, overrides: Partial<Config> = {}): Config {
  const dataset = overrides.dataset ?? env.INDEXER_DATASET ?? "simulated";
  if (!isDataset(dataset)) throw new Error("INDEXER_DATASET must be mainnet | devnet | localnet | simulated");
  const programId = env.HEADS_DOWN_PROGRAM_ID || HEADS_DOWN_PROGRAM_ID;
  if (!isAddress(programId)) throw new Error("HEADS_DOWN_PROGRAM_ID is not a valid address");
  const teamCrankers = (env.TEAM_CRANKERS ?? "").split(",").map((s) => s.trim()).filter(Boolean);
  for (const k of teamCrankers) if (!isAddress(k)) throw new Error("TEAM_CRANKERS must be comma-separated addresses");
  const rpcUrl = env.RPC_URL || null;
  if (rpcUrl) new URL(rpcUrl); // throws on garbage
  const secret = env.HELIUS_WEBHOOK_SECRET || null;
  if (secret !== null && secret.length < 16) throw new Error("HELIUS_WEBHOOK_SECRET must be at least 16 characters");
  return {
    databaseUrl: env.DATABASE_URL || "pglite://.data/pg",
    dataset,
    programId,
    rpcUrl,
    heliusWebhookSecret: secret,
    heliusTrustPayload: env.HELIUS_WEBHOOK_TRUST_PAYLOAD === "1",
    host: env.HOST || "127.0.0.1",
    port: int(env, "PORT", 8787, 0, 65535),
    corsOrigin: env.CORS_ORIGIN || "*",
    tzOffsetMinutes: assertTzOffset(int(env, "NIGHT_TZ_OFFSET_MINUTES", 60, -720, 840)),
    teamCrankers,
    ingestIntervalS: int(env, "INGEST_INTERVAL_S", 30, 0, 86_400),
    snapshotEveryPolls: int(env, "SNAPSHOT_EVERY_N_POLLS", 20, 1, 100_000),
    rpcMaxBackfill: int(env, "RPC_MAX_BACKFILL", 5000, 0, 1_000_000),
    oreApiEnabled: env.ORE_API_ENABLED !== "0",
    oreRoundsSince: int(env, "ORE_ROUNDS_SINCE", Math.floor(Date.now() / 1000) - 14 * 86_400, 0, 4_102_444_800),
    oreApiVerifySample: int(env, "ORE_API_VERIFY_SAMPLE", 3, 0, 100),
    marketSources: parseSources(env.MARKET_PRICE_SOURCES),
    resolveRounds: env.RESOLVE_ROUNDS !== "0",
    resolveMaxRounds: int(env, "RESOLVE_MAX_ROUNDS", 200, 0, 5000),
    resolveResetLookups: int(env, "RESOLVE_RESET_LOOKUPS", 10, 0, 500),
    localExplorerRpc: loopbackRpc(rpcUrl) ?? "http://127.0.0.1:8899",
    ...overrides,
  };
}

function parseSources(raw: string | undefined): string[] {
  if (raw === undefined || raw === "") return ["jupiter", "ore-api"];
  if (raw === "none") return [];
  const list = raw.split(",").map((x) => x.trim()).filter(Boolean);
  for (const x of list) if (x !== "jupiter" && x !== "ore-api") throw new Error("MARKET_PRICE_SOURCES must list jupiter and/or ore-api, or be none");
  return list;
}

export function describeConfig(c: Config): Record<string, unknown> {
  return {
    dataset: c.dataset,
    programId: c.programId,
    database: c.databaseUrl.startsWith("pglite://") ? c.databaseUrl : `${new URL(c.databaseUrl).protocol}//<redacted>`,
    rpc: c.rpcUrl ? new URL(c.rpcUrl).host : null,
    heliusWebhook: c.heliusWebhookSecret ? (c.heliusTrustPayload ? "on (trust payload)" : "on (verify via RPC)") : "off",
    listen: `${c.host}:${c.port}`,
    nightTz: c.tzOffsetMinutes,
    ingestIntervalS: c.ingestIntervalS,
    snapshotEveryPolls: c.snapshotEveryPolls,
    oreApi: c.oreApiEnabled,
    marketSources: c.marketSources,
    roundResolver: c.resolveRounds,
  };
}

/** Genesis hashes, so a mainnet dataset can never be fed from a devnet RPC (or vice versa). */
export const GENESIS_HASH: Partial<Record<Dataset, string>> = {
  mainnet: "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d",
  devnet: "EtWTRABZaYq6iMfeYKouRu166VL8Lv3NEMP2SLgEcMdE",
};
