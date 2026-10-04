/**
 * Reading ORE's round list (src/sources/oreApi.ts pollOreRounds) against a stand-in for api.ore.com:
 * the page budget and the pause, pages stored as they arrive, a refusal half way and the pass after
 * it, the list moving between and during passes, a head gap larger than the budget, rounds that
 * come twice, the `since` bound, Retry-After, items that do not decode, rounds the list leaves out,
 * the end of the list, and the spot check. The real API is not asked by any test.
 */
import { readFileSync } from "node:fs";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { encodeBase58 } from "../src/codec/base58.ts";
import { EXECUTOR_PDA, HEADS_DOWN_PROGRAM_ID } from "../src/constants.ts";
import type { IngestContext } from "../src/ingest.ts";
import type { LogLevel } from "../src/log.ts";
import {
  ORE_API_MAX_RETRY_AFTER_S,
  ORE_API_PAGE_PAUSE_MS,
  parseJsonBig,
  parseResetItem,
  pollOreRounds,
  retryAfterSeconds,
  type OreApiOptions,
} from "../src/sources/oreApi.ts";
import { RpcClient } from "../src/sources/rpc.ts";
import { migrate, openDb, type Db } from "../src/store/db.ts";
import { Store } from "../src/store/store.ts";
import { fakeChain, fakeRpcFetch } from "./helpers.ts";
import { OreStandIn, resetItem } from "./oreStandIn.ts";

const HD = HEADS_DOWN_PROGRAM_ID;
/** The newest round of the stand-in list, and its reset time. */
const N = 430_000;
const T0 = 1_791_000_000;
const noSleep = async () => undefined;
const iso = (ts: number) => new Date(ts * 1000).toISOString();
/** Round ids `lo..hi`, oldest first, as the store returns them. */
const ids = (hi: number, lo: number) => Array.from({ length: hi - lo + 1 }, (_, i) => lo + i);
const pages = (from: number, to: number) => ids(to, from);

let db: Db;
let ctx: IngestContext;
let logged: { msg: string; level: LogLevel; [field: string]: unknown }[];
let pauses: number[];
/** Unix seconds, as the pass sees them. */
let clock: number;

beforeAll(async () => {
  db = await openDb("pglite://memory");
  await migrate(db);
  ctx = {
    store: await Store.bind(db, { name: "mainnet", programId: HD, executorPda: EXECUTOR_PDA }),
    programId: HD,
    executorPda: EXECUTOR_PDA,
    log: (msg, fields, level = "info") => logged.push({ msg, level, ...fields }),
  };
});
afterAll(async () => db.close());
beforeEach(async () => {
  for (const t of ["ore_rounds", "ingest_cursors", "ingest_problems"]) await db.query(`DELETE FROM ${t}`);
  logged = [];
  pauses = [];
  clock = T0 + 10;
});

/** One pass over `api`, ten pages unless `o` says otherwise, without the spot check. */
const pass = (api: OreStandIn, o: Partial<OreApiOptions> = {}, rpc: RpcClient | null = null) =>
  pollOreRounds(ctx, { since: 0, maxPages: 10, verifySample: 0, fetchImpl: api.fetch, sleep: async (ms) => void pauses.push(ms), now: () => clock, ...o }, rpc);

const storedIds = async () =>
  (await db.query<{ id: string }>("SELECT round_id::text AS id FROM ore_rounds WHERE dataset = 'mainnet' ORDER BY ore_rounds.round_id")).map((r) => Number(r.id));
const problems = async () => db.query<{ subject: string; location: string; code: string }>("SELECT subject, location, code FROM ingest_problems WHERE dataset = 'mainnet' ORDER BY code, subject");
/** The reset time of a round of the stand-in list. */
const tsOf = (api: OreStandIn, id: number) => api.rounds.find((r) => r.id === id)!.ts;
/** A round as the store takes it, from the stand-in's item. */
const stored = (id: number, ts: number) => parseResetItem(parseJsonBig(JSON.stringify(resetItem(id, ts))));

describe("api.ore.com rounds: a pass", () => {
  it("asks for the newest pages first, no more than its budget and with a pause between them, and stores each", async () => {
    const api = new OreStandIn(N, 2500, T0);
    const r = await pass(api);
    expect(api.takePages()).toEqual(pages(0, 9));
    expect(pauses).toEqual(Array.from({ length: 9 }, () => ORE_API_PAGE_PAUSE_MS));
    expect(r).toEqual({ stored: 1000, verified: 0, mismatches: 0, pages: 10, newest: String(N), backTo: iso(T0 - 999 * 77), backfill: "running" });
    expect(await storedIds()).toEqual(ids(N, N - 999));
  });

  it("with a budget of one page reads the newest page only", async () => {
    const api = new OreStandIn(N, 2500, T0);
    for (let i = 0; i < 3; i++) expect(await pass(api, { maxPages: 1 })).toMatchObject({ stored: i === 0 ? 100 : 0, pages: 1, backfill: "running" });
    expect(api.takePages()).toEqual([0, 0, 0]);
    expect(pauses).toEqual([]);
  });

  it("goes on where the pass before it stopped, and ends at `since`", async () => {
    const api = new OreStandIn(N, 5000, T0);
    const since = tsOf(api, N - 2349); // the 2,350 newest rounds are wanted
    await pass(api, { since });
    expect(api.takePages()).toEqual(pages(0, 9));

    const second = await pass(api, { since });
    expect(api.takePages()).toEqual([0, ...pages(10, 18)]);
    expect(second).toMatchObject({ stored: 900, pages: 10, backfill: "running", backTo: iso(tsOf(api, N - 1899)) });

    // Page 23 holds rounds N-2300 down to N-2399: the first 50 are wanted, the rest are older than `since`.
    const third = await pass(api, { since });
    expect(api.takePages()).toEqual([0, ...pages(19, 23)]);
    expect(third).toMatchObject({ stored: 450, pages: 6, backfill: "done", backTo: iso(since) });
    expect(await storedIds()).toEqual(ids(N, N - 2349));

    // Done: a pass reads the newest page and nothing else. The page at the bound is not asked for again.
    for (let i = 0; i < 2; i++) expect(await pass(api, { since })).toMatchObject({ stored: 0, pages: 1, backfill: "done" });
    expect(api.takePages()).toEqual([0, 0]);
    api.add(3);
    expect(await pass(api, { since })).toMatchObject({ stored: 3, pages: 1, newest: String(N + 3), backfill: "done" });
    expect(await storedIds()).toEqual(ids(N + 3, N - 2349));
  });

  it("stores nothing when every round is older than `since`, and takes up the rounds that arrive", async () => {
    const api = new OreStandIn(N, 500, T0);
    expect(await pass(api, { since: T0 + 1 })).toMatchObject({ stored: 0, pages: 1, backTo: null, backfill: "done" });
    api.add(2); // reset at T0 + 77 and T0 + 154
    expect(await pass(api, { since: T0 + 1 })).toMatchObject({ stored: 2, pages: 1, backfill: "done" });
    expect(await storedIds()).toEqual([N + 1, N + 2]);
    expect(api.takePages()).toEqual([0, 0]);
  });

  it("reads further back when `since` is moved back, and no further when it is moved forward", async () => {
    const api = new OreStandIn(N, 1000, T0);
    expect(await pass(api, { since: tsOf(api, N - 149) })).toMatchObject({ stored: 150, pages: 2, backfill: "done" });
    api.takePages();
    // Forward (a restart moves the default, 14 days ago, forward): what is stored stays, nothing is asked below it.
    expect(await pass(api, { since: tsOf(api, N - 49) })).toMatchObject({ stored: 0, pages: 1, backfill: "done" });
    expect(api.takePages()).toEqual([0]);
    // Back: the rounds between the old bound and the new one are read (page 4 is the one that shows the new bound).
    expect(await pass(api, { since: tsOf(api, N - 399) })).toMatchObject({ stored: 250, backfill: "done" });
    expect(api.takePages()).toEqual([0, 1, 2, 3, 4]);
    expect(await storedIds()).toEqual(ids(N, N - 399));
  });

  it("reaches the end of the list and does not ask past it again", async () => {
    const api = new OreStandIn(N, 250, T0);
    // Pages 0 and 1, the 50 rounds of page 2, and page 3, which is empty.
    expect(await pass(api)).toMatchObject({ stored: 250, pages: 4, backfill: "done", backTo: iso(tsOf(api, N - 249)) });
    expect(api.takePages()).toEqual([0, 1, 2, 3]);
    expect(await pass(api)).toMatchObject({ stored: 0, pages: 1, backfill: "done" });
    expect(api.takePages()).toEqual([0]);
  });

  it("does not take a short page for the end of the list until the page after it comes back empty", async () => {
    const api = new OreStandIn(N, 250, T0);
    expect(await pass(api, { maxPages: 3 })).toMatchObject({ stored: 250, pages: 3, backfill: "running" });
    api.takePages();
    expect(await pass(api, { maxPages: 3 })).toMatchObject({ stored: 0, pages: 3, backfill: "done" });
    expect(api.takePages()).toEqual([0, 2, 3]);
  });

  it("does not take an empty page for the end of the list when it has not read the page before it in the same pass", async () => {
    const api = new OreStandIn(N, 300, T0);
    expect(await pass(api, { maxPages: 3 })).toMatchObject({ stored: 300, pages: 3, backfill: "running" });
    api.takePages();
    // The round below the last one stored would be the first of page 3, and page 3 is empty. That alone does
    // not say where the list ends (rounds may have moved since the pages before it were read): the pass reads
    // page 2, and then page 3 again.
    expect(await pass(api)).toMatchObject({ stored: 0, pages: 4, backfill: "done" });
    expect(api.takePages()).toEqual([0, 3, 2, 3]);
    expect(await ctx.store.getCursor("ore-api", "covered")).toBe(JSON.stringify({ ranges: [[String(N), String(N - 299)]], floor: [String(N - 300), -1] }));
    expect(await pass(api)).toMatchObject({ stored: 0, pages: 1, backfill: "done" });
  });

  it("counts in the page size the API gives, when that is not the 100 it was asked for", async () => {
    const api = new OreStandIn(N, 3000, T0);
    api.pageSize = 50;
    const since = tsOf(api, N - 1199);
    expect(await pass(api, { since })).toMatchObject({ stored: 500, pages: 10 });
    expect(api.takePages()).toEqual(pages(0, 9));
    // The backfill stopped 500 rounds below the newest: with fifty to a page that is page 10, not page 5.
    api.add(4);
    expect(await pass(api, { since })).toMatchObject({ stored: 4 + 46 + 400, pages: 10 });
    expect(api.takePages()).toEqual([0, ...pages(10, 18)]);
    expect(await pass(api, { since })).toMatchObject({ stored: 250 + 4, pages: 7, backfill: "done" });
    expect(api.takePages()).toEqual([0, ...pages(19, 24)]);
    expect(await storedIds()).toEqual(ids(N + 4, N - 1199));
  });

  it("stops with an error when the API answers every page with the same rounds", async () => {
    const api = new OreStandIn(N, 3000, T0);
    api.ignoresPage = true;
    await expect(pass(api)).rejects.toThrow("api.ore.com /events/reset page 1: the same rounds as page 0");
    // Two requests, not the whole budget; what page 0 brought is kept.
    expect(api.takePages()).toEqual([0, 1]);
    expect(await storedIds()).toEqual(ids(N, N - 99));
  });

  it("an empty list is not an error", async () => {
    const api = new OreStandIn(N, 0, T0);
    expect(await pass(api)).toEqual({ stored: 0, verified: 0, mismatches: 0, pages: 1, newest: null, backTo: null, backfill: "running" });
    expect(api.takePages()).toEqual([0]);
  });
});

describe("api.ore.com rounds: the list moves", () => {
  it("finds its place from the round ids when new rounds have moved every page between two passes", async () => {
    const api = new OreStandIn(N, 5000, T0);
    const since = tsOf(api, N - 2349);
    await pass(api, { since });
    api.takePages();

    // Four new rounds: every older round is four places further down the list.
    api.add(4);
    const second = await pass(api, { since });
    expect(api.takePages()).toEqual([0, ...pages(10, 18)]);
    // Page 10 now begins four rounds above the place the backfill stopped at: those four are not stored twice.
    expect(second).toMatchObject({ stored: 4 + 96 + 800, pages: 10, newest: String(N + 4) });
    expect(await storedIds()).toEqual(ids(N + 4, N - 1895));

    // More than a page of new rounds: pages 0 and 1 close the head, then the backfill goes on at page 20.
    api.add(137);
    const third = await pass(api, { since });
    expect(api.takePages()).toEqual([0, 1, ...pages(20, 24)]);
    expect(third).toMatchObject({ stored: 100 + 37 + 63 + 300 + 91, pages: 7, backfill: "done" });
    expect(third.gaps).toBeUndefined();
    expect(await storedIds()).toEqual(ids(N + 141, N - 2349));
  });

  it("steps back a page in the same pass when rounds missing further up have put its round one page earlier", async () => {
    const api = new OreStandIn(N, 1000, T0);
    api.missing = new Set([N - 10, N - 20, N - 30]);
    expect(await pass(api, { maxPages: 2 })).toMatchObject({ stored: 200, pages: 2 });
    expect(await storedIds()).toEqual(ids(N, N - 202).filter((id) => !api.missing.has(id)));
    api.takePages();
    // 97 new rounds. The round wanted next, N-203, is 300 ids below the newest, which says page 3; with
    // three ids missing above it, it is the 298th of the list, on page 2. Page 3 begins at N-206.
    api.add(97);
    const r = await pass(api, { maxPages: 5 });
    // Page 3 is kept, page 2 closes what lay between, and the pass goes on at page 4.
    expect(api.takePages()).toEqual([0, 3, 2, 4, 5]);
    expect(r).toMatchObject({ stored: 97 + 100 + 3 + 200, pages: 5 });
    expect(r.gaps).toBeUndefined();
    expect(await storedIds()).toEqual(ids(N + 97, N - 505).filter((id) => !api.missing.has(id)));
  });

  it("skips no round and stores none twice when the list moves between two pages of one pass", async () => {
    const api = new OreStandIn(N, 5000, T0);
    api.served = () => api.add(1); // a new round after every page
    const r = await pass(api);
    // Each page after the first starts with the last round of the page before it.
    expect(r).toMatchObject({ stored: 100 + 9 * 99, pages: 10, newest: String(N) });
    expect(await storedIds()).toEqual(ids(N, N - 990));
    // The ten rounds that arrived meanwhile are the next pass's head.
    api.served = () => undefined;
    expect(await pass(api, { maxPages: 1 })).toMatchObject({ stored: 10, newest: String(N + 10) });
    expect(await storedIds()).toEqual(ids(N + 10, N - 990));
  });

  it("counts a round once when a page repeats rounds of the page before it", async () => {
    const api = new OreStandIn(N, 5000, T0);
    api.served = (page) => void (page === 0 && api.add(30));
    // Page 1 repeats the last 30 rounds of page 0.
    expect(await pass(api, { maxPages: 2 })).toMatchObject({ stored: 170, pages: 2 });
    expect(await storedIds()).toEqual(ids(N, N - 169));
  });

  it("keeps a round the chain delivered first", async () => {
    const api = new OreStandIn(N, 300, T0);
    const fromChain = stored(N - 5, tsOf(api, N - 5));
    fromChain.event.totalMiners = 999n;
    await ctx.store.upsertRounds([fromChain], "chain");
    await pass(api, { maxPages: 1 });
    expect(await storedIds()).toEqual(ids(N, N - 99));
    expect(await db.query("SELECT source, total_miners::text AS miners FROM ore_rounds WHERE dataset = 'mainnet' AND round_id = $1", [String(N - 5)])).toEqual([{ source: "chain", miners: "999" }]);
  });

  it("closes a head gap that is larger than a pass's budget in the passes that follow, and reports it while it is open", async () => {
    const api = new OreStandIn(N, 3000, T0);
    const since = tsOf(api, N - 599);
    expect(await pass(api, { since })).toMatchObject({ stored: 600, backfill: "done" });
    api.takePages();

    // The indexer was away for a day and a half: 1,650 new rounds, and it may ask for three pages a pass.
    api.add(1650);
    const first = await pass(api, { since, maxPages: 3 });
    expect(api.takePages()).toEqual([0, 1, 2]);
    expect(first).toMatchObject({ stored: 300, newest: String(N + 1650), backTo: iso(tsOf(api, N + 1351)), backfill: "running", gaps: 1 });
    expect(await storedIds()).toEqual([...ids(N, N - 599), ...ids(N + 1650, N + 1351)]);

    // Every pass: the newest page, then the two pages below what it has. The eighth reaches the stored rounds.
    const asked: number[][] = [];
    let r = first;
    while (r.backfill !== "done" && asked.length < 20) {
      r = await pass(api, { since, maxPages: 3 });
      asked.push(api.takePages());
      if (r.backfill !== "done") expect(r.gaps).toBe(1);
    }
    expect(asked).toEqual([[0, 3, 4], [0, 5, 6], [0, 7, 8], [0, 9, 10], [0, 11, 12], [0, 13, 14], [0, 15, 16]]);
    expect(r.gaps).toBeUndefined();
    expect(await storedIds()).toEqual(ids(N + 1650, N - 599));
  });

  it("only asks again when the note of what was read is replaced by an older one", async () => {
    const api = new OreStandIn(N, 5000, T0);
    const since = tsOf(api, N - 1499);
    await pass(api, { since });
    const older = await ctx.store.getCursor("ore-api", "covered");
    expect(older).toBe(JSON.stringify({ ranges: [[String(N), String(N - 999)]], floor: null }));
    expect(await pass(api, { since })).toMatchObject({ backfill: "done" });
    expect(await ctx.store.getCursor("ore-api", "covered")).toBe(JSON.stringify({ ranges: [[String(N), String(N - 1499)]], floor: [String(N - 1500), tsOf(api, N - 1500)] }));
    api.takePages();
    // What a second process that started from the older note would write.
    await ctx.store.setCursor("ore-api", "covered", older!);
    expect(await pass(api, { since })).toMatchObject({ pages: 7, backfill: "done" });
    expect(api.takePages()).toEqual([0, ...pages(10, 15)]);
    expect(await storedIds()).toEqual(ids(N, N - 1499));
    // A note this code did not write reads as nothing read.
    await ctx.store.setCursor("ore-api", "covered", "page 12");
    expect(await pass(api, { since })).toMatchObject({ pages: 10, backfill: "running" });
    expect(await storedIds()).toEqual(ids(N, N - 1499));
  });

  it("takes over a database written before pages were stored one by one", async () => {
    const api = new OreStandIn(N, 3000, T0);
    // What that code left: the rounds of its polls, and the cursor of the newest.
    await ctx.store.upsertRounds(ids(N - 50, N - 739).map((id) => stored(id, tsOf(api, id))), "ore-api");
    await ctx.store.setCursor("ore-api", "newest-round", String(N - 50));
    const r = await pass(api, { since: tsOf(api, N - 1999) });
    // The 50 new rounds of page 0, then straight below the stored run: none of its pages is read again.
    expect(api.takePages()).toEqual([0, ...pages(7, 15)]);
    expect(r).toMatchObject({ stored: 50 + 60 + 800, pages: 10, backfill: "running" });
    expect(await storedIds()).toEqual(ids(N, N - 1599));
    // Its cursor without its rounds (or with a value that is no round id) is no claim at all.
    for (const t of ["ore_rounds", "ingest_cursors"]) await db.query(`DELETE FROM ${t}`);
    await ctx.store.setCursor("ore-api", "newest-round", String(N - 50));
    expect(await pass(api, { maxPages: 2 })).toMatchObject({ stored: 200 });
    expect(api.takePages()).toEqual([0, 1]);
  });
});

describe("api.ore.com rounds: turned away", () => {
  it("keeps the pages it got when the API answers 429 half way, and goes on from there in the next pass", async () => {
    const api = new OreStandIn(N, 5000, T0);
    api.refuse = (_page, nth) => (nth === 5 ? { status: 429 } : null); // the sixth request
    const first = await pass(api);
    expect(api.takePages()).toEqual(pages(0, 5));
    expect(first).toEqual({
      stored: 500, verified: 0, mismatches: 0, pages: 5, newest: String(N), backTo: iso(tsOf(api, N - 499)), backfill: "running",
      stopped: `HTTP 429 at page 5, asking for round ${N - 500}`, stalledPasses: 1,
    });
    expect(await storedIds()).toEqual(ids(N, N - 499));

    api.refuse = () => null;
    const second = await pass(api);
    // Page 0 for the head, then page 5: the four pages between are not read again.
    expect(api.takePages()).toEqual([0, ...pages(5, 13)]);
    expect(second).toEqual({ stored: 900, verified: 0, mismatches: 0, pages: 10, newest: String(N), backTo: iso(tsOf(api, N - 1399)), backfill: "running" });
    expect(await storedIds()).toEqual(ids(N, N - 1399));
  });

  it("is turned away by a 5xx, a timeout and a failed connection as by a 429", async () => {
    const api = new OreStandIn(N, 500, T0);
    api.refuse = () => ({ status: 503 });
    expect(await pass(api)).toMatchObject({ stored: 0, pages: 0, newest: null, stopped: "HTTP 503 at page 0, asking for the newest rounds", stalledPasses: 1 });
    const failing = (e: Error) => (async () => Promise.reject(e)) as unknown as typeof fetch;
    expect(await pass(api, { fetchImpl: failing(new TypeError("fetch failed")) })).toMatchObject({ pages: 0, stopped: "no answer (TypeError) at page 0, asking for the newest rounds", stalledPasses: 2 });
    expect(await pass(api, { fetchImpl: failing(new DOMException("The operation was aborted due to timeout", "TimeoutError")) })).toMatchObject({
      stopped: "no answer (TimeoutError) at page 0, asking for the newest rounds", stalledPasses: 3,
    });
    // A body that ends before it is complete is no answer either.
    const cut = (async () => new Response(new ReadableStream({ start: (c) => c.error(new TypeError("terminated")) }), { status: 200 })) as unknown as typeof fetch;
    expect(await pass(api, { fetchImpl: cut })).toMatchObject({ stopped: "no answer (TypeError) at page 0, asking for the newest rounds", stalledPasses: 4 });
    expect(await storedIds()).toEqual([]);
  });

  it("treats any other answer as an error", async () => {
    const api = new OreStandIn(N, 500, T0);
    api.refuse = (page) => (page === 1 ? { status: 404 } : null);
    await expect(pass(api)).rejects.toThrow("api.ore.com /events/reset page 1: HTTP 404");
    // Page 0 was stored before the error.
    expect(await storedIds()).toEqual(ids(N, N - 99));
    const answering = (body: string) => (async () => new Response(body, { status: 200 })) as unknown as typeof fetch;
    await expect(pass(api, { fetchImpl: answering("{}") })).rejects.toThrow("api.ore.com: expected an array");
    await expect(pass(api, { fetchImpl: answering("<html>busy</html>") })).rejects.toThrow("api.ore.com /events/reset page 0: not JSON");
  });

  it("honours Retry-After: nothing is asked before the time it names", async () => {
    const api = new OreStandIn(N, 5000, T0);
    api.refuse = (page) => (page === 2 ? { status: 429, headers: { "retry-after": "120" } } : null);
    const first = await pass(api);
    expect(first).toMatchObject({ stored: 200, pages: 2, stopped: `HTTP 429 at page 2, asking for round ${N - 200}`, retryAfterS: 120, stalledPasses: 1 });
    expect(api.takePages()).toEqual([0, 1, 2]);

    // 119 seconds later: no request at all, and the pass says how long is left.
    api.refuse = () => null;
    clock += 119;
    expect(await pass(api)).toEqual({
      stored: 0, verified: 0, mismatches: 0, pages: 0, newest: null, backTo: iso(tsOf(api, N - 199)), backfill: "running",
      stopped: first.stopped, retryAfterS: 1, stalledPasses: 1, waiting: true,
    });
    expect(api.requests).toEqual([]);

    clock += 1;
    const again = await pass(api);
    expect(api.takePages()).toEqual([0, ...pages(2, 10)]);
    expect(again).toMatchObject({ stored: 900, pages: 10 });
    expect(again.waiting).toBeUndefined();
    expect(again.stopped).toBeUndefined();
  });

  it("reads Retry-After as seconds or as an HTTP date, with a 503 as with a 429, and waits an hour at most", async () => {
    const now = () => T0;
    const httpDate = (ts: number) => new Date(ts * 1000).toUTCString(); // "Sat, 03 Oct 2026 01:20:00 GMT"
    expect(httpDate(T0)).toMatch(/^[A-Z][a-z]{2}, \d{2} [A-Z][a-z]{2} 2026 \d{2}:\d{2}:\d{2} GMT$/);
    expect(retryAfterSeconds("120", now)).toBe(120);
    expect(retryAfterSeconds(" 7 ", now)).toBe(7);
    expect(retryAfterSeconds("0", now)).toBe(0);
    expect(retryAfterSeconds(httpDate(T0 + 90), now)).toBe(90);
    expect(retryAfterSeconds(httpDate(T0 - 600), now)).toBe(0); // already past
    for (const not of [null, "", "soon", "-5", "1.5", "2026-10-03T03:21:30Z", "1e3"]) expect(retryAfterSeconds(not, now), String(not)).toBeNull();

    const api = new OreStandIn(N, 500, T0);
    api.refuse = () => ({ status: 503, headers: { "retry-after": new Date((clock + 45) * 1000).toUTCString() } });
    expect(await pass(api)).toMatchObject({ stopped: "HTTP 503 at page 0, asking for the newest rounds", retryAfterS: 45 });
    clock += 45;
    api.refuse = () => ({ status: 429, headers: { "retry-after": "86400" } });
    expect(await pass(api)).toMatchObject({ retryAfterS: ORE_API_MAX_RETRY_AFTER_S, stalledPasses: 2 });
    api.refuse = () => null;
    api.takePages();
    clock += ORE_API_MAX_RETRY_AFTER_S - 1;
    expect(await pass(api)).toMatchObject({ waiting: true, retryAfterS: 1 });
    clock += 1;
    expect(await pass(api)).toMatchObject({ stored: 500 });
    expect(api.takePages().slice(0, 2)).toEqual([0, 1]);
  });

  // What the live indexer met on 2026-10-04: an API that answers only so many requests in a stretch of time,
  // and a 14-day backfill. The real limit is not known; this one is 100 requests in any ten minutes, and a
  // pass every 30 s asks for more than that allows.
  it("finishes a 14-day backfill against an API with a request limit, in 22 passes instead of 18", { timeout: 180_000 }, async () => {
    const api = new OreStandIn(N, 18_000, T0);
    const since = T0 - 14 * 86_400;
    /** Seconds, on a clock of this test's own: a pause between two pages is a second, an answer takes a second, a pass starts every 30 s. */
    let t = 0;
    const answered: number[] = [];
    api.refuse = () => {
      while (answered.length > 0 && answered[0]! <= t - 600) answered.shift();
      if (answered.length >= 100) return { status: 429 };
      answered.push(t);
      return null;
    };
    api.served = () => void (t += 1);
    const sleep = async (ms: number) => void (t += ms / 1000);
    const seen = { passes: 0, turnedAway: 0, stalledMost: 0, mostPages: 0 };
    let r = await pass(api, { since, sleep });
    for (;;) {
      seen.passes++;
      if (r.stopped !== undefined) seen.turnedAway++;
      seen.stalledMost = Math.max(seen.stalledMost, r.stalledPasses ?? 0);
      seen.mostPages = Math.max(seen.mostPages, api.takePages().length);
      if (r.backfill === "done" || seen.passes >= 300) break;
      t += 30;
      if (seen.passes % 2 === 0) api.add(1); // a new round every other pass
      r = await pass(api, { since, sleep });
    }
    expect(r.backfill).toBe("done");
    // The first hundred requests are ten passes. Then the limit holds for two minutes: four passes in a row are
    // turned away at the newest page (the ingest loop reports the third and the fourth as failed). After that the
    // requests of ten minutes before fall out of the limit as fast as new ones are made, and no pass is turned away.
    expect(seen).toEqual({ passes: 22, turnedAway: 4, stalledMost: 4, mostPages: 10 });
    expect(await storedIds()).toEqual(ids(api.newest.id, N - 15_709));
  });

  it("counts the passes in a row that are turned away without getting further", async () => {
    const api = new OreStandIn(N, 5000, T0);
    api.refuse = () => ({ status: 429 });
    for (const n of [1, 2, 3]) expect((await pass(api)).stalledPasses).toBe(n);
    // The newest page gets through and the page after it is refused: that is further, the count starts again.
    api.refuse = (page) => (page === 1 ? { status: 429 } : null);
    expect(await pass(api)).toMatchObject({ stored: 100, stopped: `HTTP 429 at page 1, asking for round ${N - 100}`, stalledPasses: 1 });
    // Refused at that same round pass after pass, while the head keeps moving: not further.
    for (const n of [2, 3, 4]) {
      api.add(3);
      expect(await pass(api)).toMatchObject({ stored: 3, stopped: `HTTP 429 at page 1, asking for round ${N - 100}`, stalledPasses: n });
    }
    // A pass that is not turned away ends the count.
    api.refuse = () => null;
    expect((await pass(api, { maxPages: 2 })).stalledPasses).toBeUndefined();
    api.refuse = () => ({ status: 500 });
    expect((await pass(api)).stalledPasses).toBe(1);
    // Refused somewhere else, but with nothing new on the way there (the newest page held no new round): not further either.
    api.refuse = (page) => (page === 0 ? null : { status: 500 });
    expect(await pass(api)).toMatchObject({ stored: 0, pages: 1, stopped: `HTTP 500 at page 2, asking for round ${N - 191}`, stalledPasses: 2 });
  });
});

describe("api.ore.com rounds: what the list does not give", () => {
  it("records an item that does not decode, stores the rest of its page, and does not ask for it again", async () => {
    const api = new OreStandIn(N, 300, T0);
    api.broken.add(N - 150);
    expect(await pass(api)).toMatchObject({ stored: 299, pages: 4, backfill: "done" });
    expect(await storedIds()).toEqual(ids(N, N - 299).filter((id) => id !== N - 150));
    expect(await problems()).toEqual([{ subject: `ore-api round ${N - 150}`, location: "reset item", code: "BAD_FIELD" }]);
    expect(logged).toEqual([]);
    // The round counts as read: the next pass reads the newest page only.
    api.takePages();
    expect(await pass(api)).toMatchObject({ stored: 0, pages: 1, backfill: "done" });
    expect(api.takePages()).toEqual([0]);
    expect(await problems()).toHaveLength(1);
  });

  it("records an item it can make nothing of, and the round that is then missing", async () => {
    const api = new OreStandIn(N, 300, T0);
    api.junk.add(N - 150);
    expect(await pass(api)).toMatchObject({ stored: 299, pages: 4, backfill: "done" });
    expect(await problems()).toEqual([
      { subject: "ore-api page 1", location: "reset item", code: "BAD_FIELD" },
      { subject: `ore-api round ${N - 150}`, location: "reset list", code: "ORE_API_GAP" },
    ]);
    api.takePages();
    expect(await pass(api)).toMatchObject({ stored: 0, pages: 1, backfill: "done" });
  });

  it("records rounds the list leaves out when one page shows their neighbours side by side, and does not ask for them again", async () => {
    const api = new OreStandIn(N, 300, T0);
    api.missing = new Set([N - 150, N - 151]);
    expect(await pass(api)).toMatchObject({ stored: 298, pages: 4, backfill: "done" });
    expect(await storedIds()).toEqual(ids(N, N - 299).filter((id) => !api.missing.has(id)));
    expect(await problems()).toEqual([{ subject: `ore-api rounds ${N - 151}..${N - 150}`, location: "reset list", code: "ORE_API_GAP" }]);
    expect(logged).toEqual([{ msg: "ore rounds: not listed by api.ore.com", level: "warn", from: String(N - 151), to: String(N - 150) }]);
    api.takePages();
    logged = [];
    expect(await pass(api)).toMatchObject({ stored: 0, pages: 1, backfill: "done" });
    expect(api.takePages()).toEqual([0]);
    expect(logged).toEqual([]);
  });

  it("reports a missing round once, however often its page is read", async () => {
    const api = new OreStandIn(N, 300, T0);
    api.missing.add(N - 5); // on the newest page, which every pass reads
    for (let i = 0; i < 3; i++) await pass(api, { maxPages: 1 });
    api.add(2);
    await pass(api, { maxPages: 1 });
    expect(logged).toEqual([{ msg: "ore rounds: not listed by api.ore.com", level: "warn", from: String(N - 5), to: String(N - 5) }]);
    expect(await problems()).toEqual([{ subject: `ore-api round ${N - 5}`, location: "reset list", code: "ORE_API_GAP" }]);
  });

  it("records a round the list leaves out right above the `since` bound, and is done", async () => {
    const api = new OreStandIn(N, 300, T0);
    const since = tsOf(api, N - 150); // round N-150 is the oldest one wanted...
    api.missing.add(N - 150); // ...and the list does not hold it: page 1 shows N-149 next to N-151, which is older than `since`
    expect(await pass(api, { since })).toMatchObject({ stored: 150, pages: 2, backfill: "done" });
    expect(await problems()).toEqual([{ subject: `ore-api round ${N - 150}`, location: "reset list", code: "ORE_API_GAP" }]);
    expect(await storedIds()).toEqual(ids(N, N - 149));
    api.takePages();
    expect(await pass(api, { since })).toMatchObject({ stored: 0, pages: 1, backfill: "done" });
    expect(api.takePages()).toEqual([0]);
  });

  it("leaves a round open that should sit between two pages, until the list has moved it inside one", async () => {
    const api = new OreStandIn(N, 300, T0);
    api.missing.add(N - 100); // page 0 ends just above it, page 1 begins just below it
    const first = await pass(api);
    // Everything else is stored, and the open round is counted, not declared missing.
    expect(first).toMatchObject({ stored: 299, pages: 4, backfill: "running", gaps: 1 });
    expect(await problems()).toEqual([]);
    api.takePages();

    // The list has not moved: pages 0 and 1 still show it between them. Two requests, and it stays open.
    expect(await pass(api)).toMatchObject({ stored: 0, pages: 2, backfill: "running", gaps: 1 });
    expect(api.takePages()).toEqual([0, 1]);
    expect(await problems()).toEqual([]);

    // One new round: page 1 now holds both neighbours, and the round is recorded as missing.
    api.add(1);
    const settled = await pass(api);
    expect(api.takePages()).toEqual([0, 1]);
    expect(settled).toMatchObject({ stored: 1, pages: 2, backfill: "done" });
    expect(settled.gaps).toBeUndefined();
    expect(await problems()).toEqual([{ subject: `ore-api round ${N - 100}`, location: "reset list", code: "ORE_API_GAP" }]);
    expect(await storedIds()).toEqual(ids(N + 1, N - 299).filter((id) => id !== N - 100));
    expect(await pass(api)).toMatchObject({ stored: 0, pages: 1, backfill: "done" });
  });
});

describe("api.ore.com rounds: the spot check", () => {
  const resetTx = JSON.parse(readFileSync(new URL("./fixtures/ore-reset-mainnet-v1.json", import.meta.url), "utf8"));
  /** The real reset transaction of round 422,680, filed under the signature the stand-in gives that round. */
  function chainWithReset() {
    const chain = fakeChain();
    chain.txs.set(encodeBase58(Uint8Array.from(resetItem(422_680, 0)[0])), resetTx);
    return { chain, rpc: new RpcClient("https://rpc.example", { fetchImpl: fakeRpcFetch(chain), sleep: noSleep }) };
  }
  const asked = (chain: ReturnType<typeof fakeChain>) => chain.calls.filter((c) => c.method === "getTransaction").length;

  it("re-reads the newest new rounds of the pass from chain before they are stored, and the chain's value is the one stored", async () => {
    const api = new OreStandIn(422_681, 600, T0);
    const { chain, rpc } = chainWithReset();
    // Three pages, a sample of two: rounds 422,681 (its transaction is not on this chain) and 422,680 (the stand-in's values differ from the chain's).
    expect(await pass(api, { maxPages: 3, verifySample: 2 }, rpc)).toMatchObject({ stored: 300, verified: 1, mismatches: 1 });
    expect(asked(chain)).toBe(2);
    expect(await db.query("SELECT total_miners::text AS miners, ts::text AS ts FROM ore_rounds WHERE dataset = 'mainnet' AND round_id = 422680")).toEqual([{ miners: "170", ts: "1790708243" }]);
    expect(await problems()).toEqual([{ subject: encodeBase58(Uint8Array.from(resetItem(422_680, 0)[0])), location: "round 422680", code: "ORE_API_MISMATCH" }]);
    // A pass with nothing new at the head: the sample is the newest two rounds of what it backfills.
    expect(await pass(api, { maxPages: 3, verifySample: 2 }, rpc)).toMatchObject({ stored: 200, verified: 0, mismatches: 0 });
    expect(asked(chain)).toBe(4);
    // Nothing new at all: nothing to check.
    expect(await pass(api, { maxPages: 1, verifySample: 2 }, rpc)).toMatchObject({ stored: 0 });
    expect(asked(chain)).toBe(4);
  });

  it("fails the pass when the RPC fails during the check, and stores nothing of that page", async () => {
    const api = new OreStandIn(422_681, 600, T0);
    const { chain, rpc } = chainWithReset();
    chain.offline = true;
    await expect(pass(api, { verifySample: 2 }, rpc)).rejects.toThrow("getTransaction: request to rpc.example failed (TypeError)");
    expect(await storedIds()).toEqual([]);
    expect(await ctx.store.getCursor("ore-api", "covered")).toBeNull();
    chain.offline = false;
    expect(await pass(api, { maxPages: 1, verifySample: 2 }, rpc)).toMatchObject({ stored: 100, verified: 1, mismatches: 1 });
  });

  it("is left out without an RPC", async () => {
    const api = new OreStandIn(422_681, 600, T0);
    expect(await pass(api, { maxPages: 1, verifySample: 2 }, null)).toMatchObject({ stored: 100, verified: 0, mismatches: 0 });
    // The API's own value was stored.
    expect(await db.query("SELECT ts::text AS ts FROM ore_rounds WHERE dataset = 'mainnet' AND round_id = 422680")).toEqual([{ ts: String(T0 - 77) }]);
  });
});

describe("api.ore.com rounds: whatever happens on the way", () => {
  /** A small seeded generator (mulberry32): the same seed gives the same run. */
  function seeded(seed: number) {
    let a = seed >>> 0;
    return () => {
      a = (a + 0x6d2b79f5) >>> 0;
      let t = a;
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4_294_967_296;
    };
  }

  // 25 passes in which the list moves between and during passes, requests are refused, the budget
  // changes, rounds are missing and items are broken; then the trouble stops. The seeds are fixed.
  it.each(Array.from({ length: 24 }, (_, i) => i + 1))("ends with every round the list holds above `since`, each once (run %i)", { timeout: 180_000 }, async (seed) => {
    const rnd = seeded(seed);
    const upTo = (n: number) => Math.floor(rnd() * n);
    const count = 600 + upTo(1200);
    const api = new OreStandIn(N, count, T0);
    // The oldest ten rounds stay as they are: a round missing at the very end of the list leaves no trace.
    for (let i = 0; i < 6; i++) {
      // Some single, some next to each other, and one on the edge of a page as the list stands at first.
      const id = i === 0 ? N - 100 * (1 + upTo(5)) : N - upTo(count - 10);
      api.missing.add(id);
      if (rnd() < 0.4) api.missing.add(id - 1);
    }
    api.broken.add(N - upTo(count - 10));
    api.junk.add(N - upTo(count - 10));
    const since = T0 - (count - 6 - upTo(200)) * 77;

    for (let p = 0; p < 25; p++) {
      const budget = 1 + upTo(6);
      api.refuse = () => (rnd() < 0.15 ? { status: rnd() < 0.5 ? 429 : 503 } : null);
      api.served = () => void (rnd() < 0.3 && api.add(1 + upTo(3)));
      // Now and then a pass has a later bound, as after a restart; the passes after it have the first one again.
      await pass(api, { since: rnd() < 0.2 ? since + 3600 * (1 + upTo(6)) : since, maxPages: budget });
      const asked = api.requests.splice(0);
      expect(asked.length).toBeLessThanOrEqual(budget);
      // No page that held rounds is asked for twice in one pass, and nothing is asked after a refusal.
      const held = asked.filter((q) => q.items > 0).map((q) => q.page);
      expect(new Set(held).size).toBe(held.length);
      expect(asked.findIndex((q) => q.status !== 200)).toBeOneOf([-1, asked.length - 1]);
      api.add(upTo(4) === 0 ? 100 + upTo(150) : upTo(6));
    }

    // Calm: nothing is refused, and one new round per pass (which moves a round that sat between two pages inside one).
    api.refuse = () => null;
    api.served = () => undefined;
    const readable = (x: { id: number }) => !api.missing.has(x.id) && !api.junk.has(x.id) && !api.broken.has(x.id);
    const have = new Set(await storedIds());
    const stillToStore = api.rounds.filter((x) => x.ts >= since && readable(x) && !have.has(x.id)).length;
    api.add(1);
    let r = await pass(api, { since });
    let calm = 1;
    for (; (r.backfill !== "done" || r.gaps !== undefined) && calm < 60; calm++) {
      api.add(1);
      r = await pass(api, { since });
    }
    expect(r).toMatchObject({ backfill: "done", newest: String(api.newest.id) });
    expect(r.gaps).toBeUndefined();
    // No dawdling: a pass of ten pages brings some 900 rounds, and a round left open between two pages costs one pass more.
    expect(calm).toBeLessThanOrEqual(Math.ceil(stillToStore / 800) + 2);

    expect(await storedIds()).toEqual(api.rounds.filter((x) => x.ts >= since && readable(x)).map((x) => x.id));
    // Recorded as missing: exactly the rounds above the bound that the list leaves out or serves as junk.
    const bound = Math.max(...api.rounds.filter((x) => x.ts < since && readable(x)).map((x) => x.id));
    const recorded = (await problems())
      .filter((p) => p.code === "ORE_API_GAP")
      .flatMap((p) => {
        const m = /^ore-api rounds? (\d+)(?:\.\.(\d+))?$/.exec(p.subject)!;
        return ids(Number(m[2] ?? m[1]), Number(m[1]));
      })
      .sort((a, b) => a - b);
    expect(recorded).toEqual(api.rounds.filter((x) => x.id > bound && (api.missing.has(x.id) || api.junk.has(x.id))).map((x) => x.id));
    // And the next pass has the newest page to read, nothing else.
    api.takePages();
    expect(await pass(api, { since })).toMatchObject({ stored: 0, pages: 1 });
    expect(api.takePages()).toEqual([0]);
  });
});

describe("api.ore.com rounds: a first start", () => {
  // The numbers the README and the .env.example files give. With a round every 77 s (what the list
  // showed on 2026-10-04) about four new ones arrive between two passes at INGEST_INTERVAL_S=300 and
  // about one at 30. Over August and September 2026 a round took 72 s on average (ml/forecaster/RESULTS.md).
  it.each([
    { days: 14, roundS: 77, arriving: 4, rounds: 15_710, perPass: [...Array.from({ length: 17 }, () => 10), 5] }, // 18 passes, 175 requests
    { days: 14, roundS: 77, arriving: 1, rounds: 15_710, perPass: [...Array.from({ length: 17 }, () => 10), 5] },
    { days: 14, roundS: 72, arriving: 4, rounds: 16_801, perPass: [...Array.from({ length: 18 }, () => 10), 7] }, // 19 passes, 187 requests
    { days: 1, roundS: 77, arriving: 4, rounds: 1_123, perPass: [10, 3] }, // 2 passes, 13 requests
  ])("backfills $days days of $roundS s rounds in passes of at most ten pages ($arriving new rounds between two passes)", { timeout: 180_000 }, async ({ days, roundS, arriving, rounds, perPass }) => {
    const api = new OreStandIn(N, 18_000, T0, roundS);
    const since = T0 - days * 86_400;
    const asked: number[] = [];
    let r = await pass(api, { since });
    asked.push(api.takePages().length);
    while (r.backfill !== "done" && asked.length < 40) {
      api.add(arriving);
      r = await pass(api, { since });
      asked.push(api.takePages().length);
    }
    // The last pass ends with the page that shows the bound.
    expect(asked).toEqual(perPass);
    // Every round from the first one inside those days to the newest, each once.
    const oldest = api.rounds.find((x) => x.ts >= since)!.id;
    expect(oldest).toBe(N - rounds + 1);
    expect(await storedIds()).toEqual(ids(N + (perPass.length - 1) * arriving, oldest));
    expect(r.gaps).toBeUndefined();
    expect(await problems()).toEqual([]);
  });
});
