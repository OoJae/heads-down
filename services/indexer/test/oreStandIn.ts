/**
 * A stand-in for api.ore.com `/events/reset`: a list of rounds, newest first, 100 to a page, each
 * item in the API's own shape (taken from the recorded page in fixtures/). A test adds rounds to
 * it (every older round then sits one place further down the list), leaves rounds out of it, serves
 * an item broken, makes it refuse a request, makes it answer with fewer rounds than a page holds,
 * and changes what an answer's items say. It answers a `fetch` in process and, for the tests that
 * run the real commands, over HTTP on a loopback port.
 *
 * It is what the tests believe about the real API, checked against it by hand on two days. On
 * 2026-10-04 pages 0 and 60 each held 100 rounds with consecutive ids, and page 60 began exactly
 * 6,000 ids below page 0. On 2026-10-10, three requests, three seconds apart: page 0 held rounds
 * 435,113 down to 435,014, consecutive, 64 s apart on average; page 2,000 held 100 consecutive
 * rounds of April 2026 and began 28 ids further down than 200,000 below page 0, so the list leaves
 * rounds out there too; page 1,000,000, far past the end of the list, was answered with HTTP 200
 * and an empty list (what the stand-in gives past its own end). All three answers came through
 * Cloudflare and carried no header about a request limit. What the real API sends with a 429 (a
 * `Retry-After` or not) was not seen, nor whether it ever answers with an empty or a short page
 * where rounds are.
 */
import { readFileSync } from "node:fs";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";

const TEMPLATE = (JSON.parse(readFileSync(new URL("./fixtures/api-ore-events-reset-page.json", import.meta.url), "utf8")) as [number[], Record<string, unknown>][])[0]![1];

/** 64 bytes that differ for every round id, as the API writes a signature. */
const signatureBytes = (id: number) => Array.from({ length: 64 }, (_, i) => (i < 4 ? ((id >>> (8 * (3 - i))) & 0xff) | (i === 0 ? 0x80 : 0) : (i * 13 + 5) & 0xff));

/** One item of the list: `[signature bytes, ResetEvent]`. */
export function resetItem(id: number, ts: number): [number[], Record<string, unknown>] {
  return [signatureBytes(id), { ...TEMPLATE, round_id: id, ts, start_slot: id * 240, end_slot: id * 240 + 240, rng: id }];
}

export interface Refusal {
  status: number;
  headers?: Record<string, string>;
}

export class OreStandIn {
  /** The rounds, oldest first. */
  rounds: { id: number; ts: number }[] = [];
  /** Round ids the list leaves out. */
  missing = new Set<number>();
  /** Round ids whose item is served with a `top_miner` of the wrong length (the round id stays readable). */
  broken = new Set<number>();
  /** Round ids whose item is served as a bare string. */
  junk = new Set<number>();
  /** Every request, in order: the page asked for, the status answered, how many rounds the answer held, and when (ms). */
  requests: { page: number; status: number; items: number; at: number }[] = [];
  /** Called for each request with its page and its place in `requests` (0 for the first since the list was last taken); what it returns is answered instead of the page. */
  refuse: (page: number, nth: number) => Refusal | null = () => null;
  /**
   * Called for each request that is not refused, like `refuse`. A number makes the answer hold only that many of
   * the page's rounds, the newest ones: 0 is an empty list although the page holds rounds. What an API in trouble
   * might answer; the real one was not seen to.
   */
  cutTo: (page: number, nth: number) => number | null = () => null;
  /** Called with the items of each answer that holds any; what it returns is sent in their place (a test changes what an item says). */
  rewrite: (items: unknown[], page: number) => unknown[] = (items) => items;
  /** Called after each page that was served (a test moves the list here, between two requests of one pass). */
  served: (page: number) => void = () => undefined;
  /** Rounds to a page, whatever `limit` asks for (the real API gave the 100 it was asked for). */
  pageSize = 100;
  /** Every page number gets page 0's rounds. */
  ignoresPage = false;
  readonly roundS: number;

  /** `count` rounds ending at round `newest`, which was reset at `newestTs`; one round every `roundS` seconds. */
  constructor(newest: number, count: number, newestTs: number, roundS = 77) {
    this.roundS = roundS;
    for (let id = newest - count + 1; id <= newest; id++) this.rounds.push({ id, ts: newestTs - (newest - id) * roundS });
  }

  get newest() {
    return this.rounds[this.rounds.length - 1]!;
  }

  /** `n` new rounds at the head of the list. */
  add(n: number) {
    for (let i = 0; i < n; i++) this.rounds.push({ id: this.newest.id + 1, ts: this.newest.ts + this.roundS });
  }

  /** The pages asked for since the last call. */
  takePages(): number[] {
    return this.requests.splice(0).map((r) => r.page);
  }

  answer(path: string): { status: number; headers: Record<string, string>; body: string } {
    const url = new URL(path, "http://stand-in");
    const page = Number(url.searchParams.get("page"));
    if (url.pathname !== "/events/reset" || url.searchParams.get("limit") !== "100" || !Number.isSafeInteger(page) || page < 0) {
      return { status: 404, headers: {}, body: "not found" };
    }
    const refusal = this.refuse(page, this.requests.length);
    if (refusal) {
      this.requests.push({ page, status: refusal.status, items: 0, at: Date.now() });
      return { status: refusal.status, headers: refusal.headers ?? {}, body: "slow down" };
    }
    const listed = this.missing.size === 0 ? this.rounds : this.rounds.filter((r) => !this.missing.has(r.id));
    const items: unknown[] = [];
    const from = listed.length - 1 - (this.ignoresPage ? 0 : page) * this.pageSize;
    const size = Math.min(this.pageSize, this.cutTo(page, this.requests.length) ?? this.pageSize);
    for (let i = from; i > from - size && i >= 0; i--) {
      const r = listed[i]!;
      const item = resetItem(r.id, r.ts);
      if (this.junk.has(r.id)) items.push("junk");
      else if (this.broken.has(r.id)) items.push([item[0], { ...item[1], top_miner: [1, 2, 3] }]);
      else items.push(item);
    }
    this.requests.push({ page, status: 200, items: items.length, at: Date.now() });
    this.served(page);
    return { status: 200, headers: { "content-type": "application/json" }, body: JSON.stringify(items.length === 0 ? items : this.rewrite(items, page)) };
  }

  readonly fetch = (async (url: string | URL | Request) => {
    const u = new URL(String(url));
    const a = this.answer(u.pathname + u.search);
    return new Response(a.body, { status: a.status, headers: a.headers });
  }) as typeof fetch;

  /** The same list over HTTP on a free loopback port. */
  async listen(): Promise<{ url: string; server: Server }> {
    const server = createServer((req, res) => {
      const a = this.answer(req.url ?? "/");
      res.writeHead(a.status, a.headers).end(a.body);
    });
    await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
    return { url: `http://127.0.0.1:${(server.address() as AddressInfo).port}`, server };
  }
}
