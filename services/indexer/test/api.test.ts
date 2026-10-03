import type { AddressInfo } from "node:net";
import type http from "node:http";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { csvCell, toCsv } from "../src/api/csv.ts";
import { explorerUrl } from "../src/api/explorer.ts";
import { openApiDocument } from "../src/api/openapi.ts";
import { API_ROUTES, createApiServer } from "../src/api/server.ts";
import { CONFIG_PDA, EXECUTOR_PDA, HEADS_DOWN_PROGRAM_ID } from "../src/constants.ts";
import { extractTransaction } from "../src/codec/tx.ts";
import { runSimulation } from "../src/sim/simulate.ts";
import { buildDigTx } from "../src/sim/txbuilder.ts";
import { migrate, openDb, type Db } from "../src/store/db.ts";
import { Store } from "../src/store/store.ts";
import { MarketPrice, fixedSource } from "../src/sources/market.ts";
import { addr, sig } from "./helpers.ts";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const j = (r: Response): Promise<any> => r.json();

// ---- a minimal JSON-Schema checker for the subset openapi.ts uses --------------------------
type Schema = Record<string, unknown>;
const components = openApiDocument.components.schemas as unknown as Record<string, Schema>;
function check(v: unknown, s: Schema, path = "$"): string[] {
  if (s.$ref) return check(v, components[String(s.$ref).split("/").pop()!]!, path);
  if (s.oneOf) {
    const opts = s.oneOf as Schema[];
    return opts.some((o) => check(v, o, path).length === 0) ? [] : [`${path}: matches no oneOf branch (${JSON.stringify(v)?.slice(0, 80)})`];
  }
  const errs: string[] = [];
  const t = s.type;
  const typeOk =
    t === undefined ||
    (t === "null" && v === null) ||
    (t === "string" && typeof v === "string") ||
    (t === "integer" && Number.isInteger(v)) ||
    (t === "number" && typeof v === "number" && Number.isFinite(v)) ||
    (t === "boolean" && typeof v === "boolean") ||
    (t === "array" && Array.isArray(v)) ||
    (t === "object" && typeof v === "object" && v !== null && !Array.isArray(v));
  if (!typeOk) return [`${path}: expected ${String(t)}, got ${JSON.stringify(v)?.slice(0, 60)}`];
  if (s.enum && !(s.enum as unknown[]).includes(v)) errs.push(`${path}: ${String(v)} not in enum`);
  if (s.pattern && typeof v === "string" && !new RegExp(String(s.pattern)).test(v)) errs.push(`${path}: pattern`);
  if (t === "array") (v as unknown[]).forEach((x, i) => errs.push(...check(x, s.items as Schema, `${path}[${i}]`)));
  if (t === "object") {
    const o = v as Record<string, unknown>;
    for (const k of (s.required as string[]) ?? []) if (!(k in o)) errs.push(`${path}.${k}: missing`);
    for (const [k, sub] of Object.entries((s.properties as Record<string, Schema>) ?? {})) if (k in o) errs.push(...check(o[k], sub, `${path}.${k}`));
  }
  return errs;
}
function responseSchema(route: string): Schema {
  const p = (openApiDocument.paths as unknown as Record<string, { get: { responses: Record<string, { content: Record<string, { schema: Schema }> }> } }>)[route]!;
  return p.get.responses["200"]!.content["application/json"]!.schema;
}

// ---- servers -----------------------------------------------------------------------------
let db: Db;
const servers: http.Server[] = [];
async function listen(s: http.Server): Promise<string> {
  servers.push(s);
  await new Promise<void>((r) => s.listen(0, "127.0.0.1", r));
  return `http://127.0.0.1:${(s.address() as AddressInfo).port}`;
}
let simBase = "";
let mainBase = "";

beforeAll(async () => {
  db = await openDb("pglite://memory");
  await migrate(db);
  const sim = await Store.bind(db, { name: "simulated", programId: HEADS_DOWN_PROGRAM_ID, executorPda: EXECUTOR_PDA, simSeed: "api-test" });
  const out = await runSimulation(
    { store: sim, programId: HEADS_DOWN_PROGRAM_ID, executorPda: EXECUTOR_PDA },
    { seed: "api-test", rigs: 12, nights: 9, startDay: "2026-09-10", programId: HEADS_DOWN_PROGRAM_ID, executorPda: EXECUTOR_PDA, configPda: CONFIG_PDA },
  );
  simBase = await listen(
    createApiServer({
      store: sim, info: await sim.info(), defaultTzOffsetMinutes: 60, teamCrankers: out.teamCrankers,
      market: new MarketPrice([fixedSource(out.marketLamportsPerOre, "simulated")]),
    }),
  );

  const main = await Store.bind(db, { name: "mainnet", programId: HEADS_DOWN_PROGRAM_ID, executorPda: EXECUTOR_PDA });
  const tx = buildDigTx({
    signature: sig(1), slot: 1, blockTime: 1_790_800_000, cranker: addr(9), programId: HEADS_DOWN_PROGRAM_ID, configPda: CONFIG_PDA,
    executorPda: EXECUTOR_PDA, roundAccount: addr(10), roundId: 5n,
    rigs: [{ rig: addr(1), authority: addr(11), automation: addr(12), miner: addr(13), outcome: { kind: "dug", perTile: 10n, mask: 7, emaEv: 1n } }],
  });
  await main.ingestTxs([extractTransaction(tx, { programId: HEADS_DOWN_PROGRAM_ID, executorPda: EXECUTOR_PDA })], "test");
  mainBase = await listen(
    createApiServer({
      store: main, info: await main.info(), defaultTzOffsetMinutes: 60, teamCrankers: [], now: () => 1_790_900_000,
      webhook: { secret: "hook-secret", rpc: null, trustPayload: true, ctx: { store: main, programId: HEADS_DOWN_PROGRAM_ID, executorPda: EXECUTOR_PDA } },
    }),
  );
});
afterAll(async () => {
  for (const s of servers) await new Promise((r) => s.close(r));
  await db.close();
});

describe("OpenAPI", () => {
  it("documents exactly the served routes", () => {
    expect(Object.keys(openApiDocument.paths).sort()).toEqual([...API_ROUTES].sort());
  });

  it("is served at /openapi.json", async () => {
    const r = await fetch(`${simBase}/openapi.json`);
    expect(r.status).toBe(200);
    expect((await j(r)).openapi).toBe("3.1.0");
  });
});

describe("JSON endpoints (simulated dataset)", () => {
  for (const route of ["/v1/health", "/v1/summary", "/v1/cohorts", "/v1/share-by-hour", "/v1/digs/recent", "/v1/skips", "/v1/milestones"]) {
    it(`${route} matches its schema and is badged simulated`, async () => {
      const r = await fetch(`${simBase}${route}`);
      expect(r.status).toBe(200);
      expect(r.headers.get("x-headsdown-dataset")).toBe("simulated");
      expect(r.headers.get("access-control-allow-origin")).toBe("*");
      expect(r.headers.get("x-content-type-options")).toBe("nosniff");
      const body = await j(r);
      expect(body.dataset).toMatchObject({ name: "simulated", simulated: true, simSeed: "api-test" });
      expect(check(body, responseSchema(route))).toEqual([]);
      // Simulated data never links to an explorer.
      expect(JSON.stringify(body)).not.toMatch(/solscan|explorer\.solana/);
    });
  }

  it("is deterministic: the simulated asOf is frozen", async () => {
    const a = await j(await fetch(`${simBase}/v1/summary`));
    const b = await j(await fetch(`${simBase}/v1/summary`));
    expect(a.asOf).toBe(b.asOf);
    expect(a.data).toEqual(b.data);
  });

  it("validates query parameters", async () => {
    for (const q of ["/v1/summary?tz=abc", "/v1/summary?tz=9999", "/v1/share-by-hour?days=0", "/v1/digs/recent?limit=201", "/v1/share-by-hour?days=1e3"]) {
      const r = await fetch(`${simBase}${q}`);
      expect(r.status, q).toBe(400);
      expect(Object.keys(await j(r))).toEqual(["error"]);
    }
    expect((await fetch(`${simBase}/v1/digs/recent?limit=3`).then(j)).data).toHaveLength(3);
  });

  it("404s unknown paths, 405s writes, answers CORS preflight", async () => {
    expect((await fetch(`${simBase}/v1/nope`)).status).toBe(404);
    expect((await fetch(`${simBase}/v1/summary`, { method: "POST" })).status).toBe(405);
    expect((await fetch(`${simBase}/v1/summary`, { method: "OPTIONS" })).status).toBe(204);
    // The webhook does not exist on a simulated server.
    expect((await fetch(`${simBase}/webhooks/helius`, { method: "POST", body: "[]", headers: { authorization: "x" } })).status).toBe(404);
  });
});

describe("GET /v1/skips", () => {
  it("lists skips newest first with names, ranges and plain labels, and a histogram", async () => {
    const body = await j(await fetch(`${simBase}/v1/skips?limit=5`));
    expect(check(body, responseSchema("/v1/skips"))).toEqual([]);
    const d = body.data;
    expect(d.items).toHaveLength(5);
    expect(d.total).toBe(d.histogram.reduce((n: number, h: { count: number }) => n + h.count, 0));
    const codes = d.histogram.map((h: { code: number }) => h.code);
    expect(codes).toEqual(expect.arrayContaining([1, 7]));
    // Sorted by count, every code named and explained.
    const counts = d.histogram.map((h: { count: number }) => h.count);
    expect([...counts].sort((a, b) => b - a)).toEqual(counts);
    expect(d.histogram.every((h: { name: string; label: string }) => !h.name.startsWith("unknown") && h.label.length > 0)).toBe(true);
    const stale = d.histogram.find((h: { code: number }) => h.code === 7);
    expect(stale).toMatchObject({ name: "StaleHeartbeat", range: "heads_down", label: "replay rejected: heartbeat counter not newer" });
    const slots = d.items.map((i: { slot: number }) => i.slot);
    expect([...slots].sort((a, b) => b - a)).toEqual(slots);
    expect(d.items.every((i: { txUrl: string | null }) => i.txUrl === null)).toBe(true); // simulated: no links
  });

  it("paginates with an opaque cursor, without gaps or repeats", async () => {
    const all = (await j(await fetch(`${simBase}/v1/skips?limit=500`))).data.items.map((i: { signature: string; rig: string }) => `${i.signature}:${i.rig}`);
    const seen: string[] = [];
    let cursor: string | null = null;
    for (let n = 0; n < 100; n++) {
      const page: { items: { signature: string; rig: string }[]; nextCursor: string | null } = (await j(await fetch(`${simBase}/v1/skips?limit=7${cursor ? `&cursor=${cursor}` : ""}`))).data;
      seen.push(...page.items.map((i: { signature: string; rig: string }) => `${i.signature}:${i.rig}`));
      cursor = page.nextCursor;
      if (!cursor) break;
    }
    expect(seen).toEqual(all);
  });

  it("filters by error (code, hex or name) and by rig", async () => {
    const byName = (await j(await fetch(`${simBase}/v1/skips?error=StaleHeartbeat&limit=500`))).data;
    expect(byName.items.length).toBeGreaterThan(0);
    expect(byName.items.every((i: { error: number }) => i.error === 7)).toBe(true);
    expect(byName.total).toBe(byName.items.length);
    expect((await j(await fetch(`${simBase}/v1/skips?error=0x7&limit=500`))).data.items).toEqual(byName.items);
    expect((await j(await fetch(`${simBase}/v1/skips?error=7&limit=500`))).data.items).toEqual(byName.items);
    const rig = byName.items[0].rig;
    const mine = (await j(await fetch(`${simBase}/v1/skips?rig=${rig}&limit=500`))).data;
    expect(mine.items.every((i: { rig: string }) => i.rig === rig)).toBe(true);
    expect(mine.filter).toEqual({ rig, error: null });
  });

  it("rejects bad filters and cursors", async () => {
    for (const q of ["?error=NotAnError", "?error=-1", "?rig=abc", "?limit=0", "?limit=501", "?cursor=zzz", `?cursor=${Buffer.from("[1,2,3]").toString("base64url")}`]) {
      const r = await fetch(`${simBase}/v1/skips${q}`);
      expect(r.status, q).toBe(400);
    }
  });

  it("exports skips.csv with the code in decimal and hex, its name and meaning", async () => {
    const r = await fetch(`${simBase}/v1/export/skips.csv?error=StaleHeartbeat`);
    expect(r.status).toBe(200);
    expect(r.headers.get("content-disposition")).toMatch(/SIMULATED-heads-down-simulated-skips-/);
    const lines = (await r.text()).trimEnd().split("\r\n");
    expect(lines[0]).toBe("dataset,signature,block_time_utc,slot,rig,round_id,error_code,error_hex,error_name,meaning");
    expect(lines.length).toBeGreaterThan(1);
    expect(lines.slice(1).every((l) => l.startsWith("simulated,") && l.includes(",7,0x00000007,StaleHeartbeat,replay rejected: heartbeat counter not newer"))).toBe(true);
  });
});

describe("haul on the simulated dataset", () => {
  it("serves contract B with simulated: true, a labelled simulated market price and no explorer links", async () => {
    const feed = (await j(await fetch(`${simBase}/v1/digs/recent?limit=50`))).data;
    let served = 0;
    for (const rig of new Set<string>(feed.map((f: { rig: string }) => f.rig))) {
      const r = await fetch(`${simBase}/v1/rigs/${rig}/haul/latest`);
      if (r.status !== 200) continue;
      const h = await j(r);
      expect(check(h, components.HaulSummary!)).toEqual([]);
      expect(h.simulated).toBe(true);
      expect(h.explorer).toEqual({ shift_log: null, sample_digs: [] });
      expect(h.market_source).toBe("simulated");
      expect(h.first_pickup_ts).toBeNull();
      expect(r.headers.get("x-headsdown-dataset")).toBe("simulated");
      expect(r.headers.get("x-headsdown-haul-checks")).toBe("dark=match; sol-returned=exact; deploys=match");
      expect(JSON.stringify(h)).not.toMatch(/solscan|explorer\.solana/);
      served++;
    }
    expect(served).toBeGreaterThan(0);
  });
});

describe("CSV exports", () => {
  it("rounds.csv: header, dataset column, SIMULATED filename", async () => {
    const r = await fetch(`${simBase}/v1/export/rounds.csv?days=3`);
    expect(r.status).toBe(200);
    expect(r.headers.get("content-type")).toContain("text/csv");
    expect(r.headers.get("content-disposition")).toMatch(/filename="SIMULATED-heads-down-simulated-rounds-\d{4}-\d{2}-\d{2}\.csv"/);
    const lines = (await r.text()).trimEnd().split("\r\n");
    expect(lines[0]).toBe("dataset,round_id,reset_time_utc,hour_utc,ore_total_miners,heads_down_miners,heads_down_share,heads_down_lamports,reset_signature,sample_dig_signature");
    expect(lines.length).toBeGreaterThan(100);
    expect(lines.slice(1).every((l) => l.startsWith("simulated,"))).toBe(true);
  });

  it("digs.csv and monthly.csv", async () => {
    const digs = await (await fetch(`${simBase}/v1/export/digs.csv`)).text();
    expect(digs.split("\r\n")[0]).toContain("signature,block_time_utc,slot,rig,authority");
    const monthly = await (await fetch(`${simBase}/v1/export/monthly.csv`)).text();
    expect(monthly.split("\r\n")[0]).toContain("ore_bought,ore_buried");
    expect(monthly).toContain("not_shipped");
  });

  it("escapes cells and neutralises spreadsheet formulas", () => {
    expect(csvCell('a,"b"')).toBe('"a,""b"""');
    expect(csvCell("=HYPERLINK(1)")).toBe("'=HYPERLINK(1)");
    expect(csvCell("-1+2")).toBe("'-1+2");
    expect(csvCell(-5)).toBe("-5");
    expect(csvCell(NaN)).toBe("");
    expect(csvCell(12n)).toBe("12");
    expect(toCsv(["a"], [[null], ["x\ny"]])).toBe('a\r\n\r\n"x\ny"\r\n');
  });
});

describe("real dataset", () => {
  it("links evidence to Solscan", async () => {
    const body = await j(await fetch(`${mainBase}/v1/summary`));
    expect(body.dataset.simulated).toBe(false);
    expect(body.data.roundsDug.evidence[0].url).toBe(`https://solscan.io/account/${EXECUTOR_PDA}`);
    const feed = await j(await fetch(`${mainBase}/v1/digs/recent`));
    expect(feed.data[0].txUrl).toBe(`https://solscan.io/tx/${sig(1)}`);
    const csv = await fetch(`${mainBase}/v1/export/digs.csv`);
    expect(csv.headers.get("content-disposition")).not.toContain("SIMULATED");
  });

  it("webhook requires the secret and caps the body", async () => {
    expect((await fetch(`${mainBase}/webhooks/helius`, { method: "POST", body: "[]" })).status).toBe(401);
    expect((await fetch(`${mainBase}/webhooks/helius`, { method: "POST", body: "[]", headers: { authorization: "wrong" } })).status).toBe(401);
    const ok = await fetch(`${mainBase}/webhooks/helius`, { method: "POST", body: "[]", headers: { authorization: "hook-secret" } });
    expect(ok.status).toBe(200);
    expect((await fetch(`${mainBase}/webhooks/helius`, { method: "POST", body: "{", headers: { authorization: "hook-secret" } })).status).toBe(400);
    const huge = "[" + "0,".repeat(3_000_000) + "0]";
    const r = await fetch(`${mainBase}/webhooks/helius`, { method: "POST", body: huge, headers: { authorization: "hook-secret" } }).catch(() => null);
    expect(r === null || r.status === 413).toBe(true);
  });
});

describe("explorerUrl", () => {
  it("builds cluster-correct links and refuses invalid ids", () => {
    expect(explorerUrl("devnet", "account", EXECUTOR_PDA)).toBe(`https://solscan.io/account/${EXECUTOR_PDA}?cluster=devnet`);
    expect(explorerUrl("localnet", "tx", sig(1))).toContain("cluster=custom");
    expect(explorerUrl("simulated", "tx", sig(1))).toBeNull();
    expect(explorerUrl("mainnet", "tx", "javascript:alert(1)")).toBeNull();
    expect(explorerUrl("mainnet", "account", sig(1))).toBeNull();
  });
});
