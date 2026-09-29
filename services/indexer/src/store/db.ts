/**
 * Minimal database abstraction over two engines that speak the same SQL:
 *  - node-postgres (`postgres://...`) for production and docker-compose;
 *  - PGlite (`pglite://<dir>` or `pglite://memory`), real Postgres compiled to WASM, for tests,
 *    the simulator and laptops without Docker.
 * Both run the exact same migrations, so tests exercise the production schema.
 */
import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

export type Param = string | number | boolean | null | Uint8Array;

export interface Db {
  query<T = Record<string, unknown>>(sql: string, params?: Param[]): Promise<T[]>;
  /** Multi-statement SQL without parameters (migrations). */
  exec(sql: string): Promise<void>;
  transaction<T>(fn: (db: Db) => Promise<T>): Promise<T>;
  close(): Promise<void>;
  readonly engine: "postgres" | "pglite";
}

function norm(params?: Param[]): unknown[] {
  return (params ?? []).map((p) => (p instanceof Uint8Array && !Buffer.isBuffer(p) ? Buffer.from(p) : p));
}

class PgDb implements Db {
  readonly engine = "postgres" as const;
  private readonly pool: import("pg").Pool;
  private readonly client: import("pg").PoolClient | null;

  constructor(pool: import("pg").Pool, client: import("pg").PoolClient | null = null) {
    this.pool = pool;
    this.client = client;
  }

  async query<T>(sql: string, params?: Param[]): Promise<T[]> {
    const res = await (this.client ?? this.pool).query(sql, norm(params));
    return res.rows as T[];
  }

  async exec(sql: string): Promise<void> {
    await (this.client ?? this.pool).query(sql);
  }

  async transaction<T>(fn: (db: Db) => Promise<T>): Promise<T> {
    if (this.client) return fn(this); // already inside a transaction
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      const out = await fn(new PgDb(this.pool, client));
      await client.query("COMMIT");
      return out;
    } catch (e) {
      await client.query("ROLLBACK").catch(() => undefined);
      throw e;
    } finally {
      client.release();
    }
  }

  async close(): Promise<void> {
    if (!this.client) await this.pool.end();
  }
}

type PGliteLike = {
  query<T>(sql: string, params?: unknown[]): Promise<{ rows: T[] }>;
  exec(sql: string): Promise<unknown>;
  transaction<T>(fn: (tx: { query<U>(sql: string, params?: unknown[]): Promise<{ rows: U[] }>; exec(sql: string): Promise<unknown> }) => Promise<T>): Promise<T>;
  close(): Promise<void>;
};

class PGliteDb implements Db {
  readonly engine = "pglite" as const;
  private readonly pg: PGliteLike;
  private readonly inTx: {
    query<U>(sql: string, params?: unknown[]): Promise<{ rows: U[] }>;
    exec(sql: string): Promise<unknown>;
  } | null;

  constructor(pg: PGliteLike, inTx: PGliteDb["inTx"] = null) {
    this.pg = pg;
    this.inTx = inTx;
  }

  async query<T>(sql: string, params?: Param[]): Promise<T[]> {
    const res = await (this.inTx ?? this.pg).query<T>(sql, params ?? []);
    return res.rows;
  }

  async exec(sql: string): Promise<void> {
    await (this.inTx ?? this.pg).exec(sql);
  }

  async transaction<T>(fn: (db: Db) => Promise<T>): Promise<T> {
    if (this.inTx) return fn(this);
    return this.pg.transaction((tx) => fn(new PGliteDb(this.pg, tx)));
  }

  async close(): Promise<void> {
    if (!this.inTx) await this.pg.close();
  }
}

/**
 * Opens `postgres://…`/`postgresql://…` with node-postgres, or `pglite://memory` /
 * `pglite://<directory>` with PGlite.
 */
export async function openDb(url: string): Promise<Db> {
  if (url.startsWith("postgres://") || url.startsWith("postgresql://")) {
    const pg = await import("pg");
    const pool = new pg.default.Pool({ connectionString: url, max: 8, statement_timeout: 30_000 });
    return new PgDb(pool);
  }
  if (url.startsWith("pglite://")) {
    const { PGlite } = await import("@electric-sql/pglite");
    const where = url.slice("pglite://".length);
    const pg = where === "memory" || where === "" ? new PGlite() : new PGlite(where);
    return new PGliteDb(pg as unknown as PGliteLike);
  }
  throw new Error("DATABASE_URL must start with postgres://, postgresql:// or pglite://");
}

const MIGRATIONS_DIR = fileURLToPath(new URL("../../migrations/", import.meta.url));

/** Applies migrations/NNN_*.sql in order, each once, inside a transaction. */
export async function migrate(db: Db): Promise<string[]> {
  await db.exec(
    "CREATE TABLE IF NOT EXISTS schema_migrations (version TEXT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())",
  );
  const done = new Set((await db.query<{ version: string }>("SELECT version FROM schema_migrations")).map((r) => r.version));
  const files = readdirSync(MIGRATIONS_DIR).filter((f) => /^\d{3}_[a-z0-9_]+\.sql$/.test(f)).sort();
  const applied: string[] = [];
  for (const f of files) {
    if (done.has(f)) continue;
    const sql = readFileSync(MIGRATIONS_DIR + f, "utf8");
    await db.transaction(async (tx) => {
      await tx.exec(sql);
      await tx.query("INSERT INTO schema_migrations (version) VALUES ($1)", [f]);
    });
    applied.push(f);
  }
  return applied;
}
