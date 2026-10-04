/**
 * The node-postgres engine when Postgres goes away, with the real pg driver (pool and client)
 * against a stand-in server that speaks just enough of the Postgres wire protocol: startup,
 * simple queries, and the FATAL "terminating connection due to administrator command" a server
 * sends when it shuts down. No real Postgres runs here (none is installed where this suite runs),
 * so this covers the driver's behaviour on a lost connection, not Postgres itself.
 *
 * Without the 'error' listeners in store/db.ts each of these tests ends with an uncaught exception.
 */
import { createServer, type Server, type Socket } from "node:net";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { openDb, type Db } from "../src/store/db.ts";

const message = (type: string, body: Buffer) => {
  const b = Buffer.alloc(5 + body.length);
  b.write(type, 0, "latin1");
  b.writeInt32BE(4 + body.length, 1);
  body.copy(b, 5);
  return b;
};
const READY = message("Z", Buffer.from("I"));
const SHUTDOWN = message("E", Buffer.from("SFATAL\0VFATAL\0C57P01\0Mterminating connection due to administrator command\0\0"));

/** Accepts connections, answers every simple query with an empty result, and can drop every connection the way a restart does. */
async function standInPostgres() {
  const sockets = new Set<Socket>();
  const state = { connections: 0, queries: [] as string[] };
  const server: Server = createServer((sock) => {
    state.connections++;
    sockets.add(sock);
    sock.on("close", () => sockets.delete(sock)).on("error", () => undefined);
    let buf = Buffer.alloc(0);
    let started = false;
    sock.on("data", (chunk: Buffer) => {
      buf = Buffer.concat([buf, chunk]);
      for (;;) {
        if (!started) {
          // StartupMessage: length, protocol version, parameters. No authentication asked for.
          if (buf.length < 4 || buf.length < buf.readInt32BE(0)) return;
          buf = buf.subarray(buf.readInt32BE(0));
          started = true;
          sock.write(Buffer.concat([message("R", Buffer.alloc(4)), READY]));
          continue;
        }
        if (buf.length < 5 || buf.length < 1 + buf.readInt32BE(1)) return;
        const end = 1 + buf.readInt32BE(1);
        const type = String.fromCharCode(buf[0]!);
        const body = buf.subarray(5, end);
        buf = buf.subarray(end);
        if (type === "Q") {
          const sql = body.toString("utf8").replace(/\0$/, "");
          state.queries.push(sql);
          if (sql.includes("never answered")) continue; // a query that is still running when the server goes away
          const tag = /^(BEGIN|COMMIT|ROLLBACK)$/.test(sql) ? sql : "SELECT 0";
          sock.write(Buffer.concat([message("C", Buffer.from(`${tag}\0`)), READY]));
        } else if (type === "X") {
          sock.end();
        }
      }
    });
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  return {
    state,
    port: (server.address() as { port: number }).port,
    /** What clients see when Postgres is shut down: the FATAL message, then the connection ends. */
    shutDownConnections: () => {
      for (const s of sockets) s.end(SHUTDOWN);
    },
    close: () => new Promise((r) => (sockets.forEach((s) => s.destroy()), server.close(r))),
  };
}

const until = async (cond: () => boolean) => {
  for (let i = 0; i < 200 && !cond(); i++) await new Promise((r) => setTimeout(r, 10));
  expect(cond()).toBe(true);
};

describe("Postgres connection loss (real pg driver, stand-in server)", () => {
  let pg: Awaited<ReturnType<typeof standInPostgres>>;
  let db: Db;
  let logged: { msg: string; error?: unknown }[];
  beforeEach(async () => {
    pg = await standInPostgres();
    logged = [];
    db = await openDb(`postgres://indexer:not-a-real-password@127.0.0.1:${pg.port}/headsdown`, (msg, fields) => logged.push({ msg, ...fields }));
  });
  afterEach(async () => {
    await db.close();
    await pg.close();
  });

  it("logs the loss of an idle connection, stays up, and connects again for the next query", async () => {
    expect(db.engine).toBe("postgres");
    expect(await db.query("SELECT 1")).toEqual([]);
    expect(pg.state.connections).toBe(1);
    pg.shutDownConnections(); // the client sits idle in the pool
    await until(() => logged.length > 0);
    expect(logged).toEqual([{ msg: "pg pool error", error: "terminating connection due to administrator command" }]);
    expect(await db.query("SELECT 1")).toEqual([]);
    expect(pg.state.connections).toBe(2);
    expect(JSON.stringify(logged)).not.toContain("not-a-real-password");
  });

  it("survives losing the connection inside a transaction: the transaction rejects, the next one works", async () => {
    const failing = db.transaction(async (tx) => {
      await tx.query("SELECT 1");
      pg.shutDownConnections(); // the client is checked out: the pool is not listening to it
      await until(() => logged.length > 0);
      await tx.query("SELECT 2");
    });
    await expect(failing).rejects.toThrow(/not queryable|Connection terminated/);
    expect(logged.length).toBeGreaterThan(0);
    expect(logged.every((l) => l.msg === "pg client error")).toBe(true);
    expect(logged[0]).toEqual({ msg: "pg client error", error: "terminating connection due to administrator command" });
    expect(pg.state.queries).toEqual(["BEGIN", "SELECT 1"]);

    expect(await db.transaction(async (tx) => (await tx.query("SELECT 3")).length)).toBe(0);
    expect(pg.state.queries.slice(2)).toEqual(["BEGIN", "SELECT 3", "COMMIT"]);
    expect(pg.state.connections).toBe(2);
  });

  it("fails the query that was in flight inside a transaction, and stays up", async () => {
    const failing = db.transaction(async (tx) => {
      const inFlight = tx.query("SELECT 'never answered'");
      await until(() => pg.state.queries.length === 2);
      pg.shutDownConnections();
      await inFlight;
    });
    await expect(failing).rejects.toThrow("terminating connection due to administrator command");
    // The query took the server's message; the client then reports the closed connection.
    await until(() => logged.length > 0);
    expect(logged.every((l) => l.msg === "pg client error" || l.msg === "pg pool error")).toBe(true);
    expect(await db.query("SELECT 1")).toEqual([]);
    expect(pg.state.connections).toBe(2);
  });
});
