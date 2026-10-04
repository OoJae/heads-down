/**
 * ORE rounds from api.ore.com `/events/reset` (newest first, 100 per page). Each item is
 * `[reset_tx_signature_bytes[64], ResetEvent]`, so every round we store keeps the signature
 * of the on-chain reset transaction: anyone can open it and check `total_miners`.
 *
 * The API is a convenience, not a trust root. With an RPC client configured, a sample of new
 * rounds per poll is re-read from chain (the ResetEvent inside the reset transaction's ORE Log
 * instruction); a mismatch is recorded and the chain value wins.
 *
 * u64 fields (e.g. `rng`) exceed 2^53, so JSON is parsed with source-text access into BigInt.
 *
 * The API is ORE's, and it answers HTTP 429 to a client that asks for many pages in a row (the
 * live indexer was turned away past page 100 on 2026-10-04). So a pass asks for a few pages only,
 * keeps what each page brought, and takes a refusal as "later", not as an error: see
 * {@link pollOreRounds}.
 */
import { encodeBase58, isSignature } from "../codec/base58.ts";
import { DecodeError } from "../codec/errors.ts";
import { U64_MAX } from "../codec/bytes.ts";
import { normalizeWinningSquare, type OreResetEvent } from "../codec/ore.ts";
import { extractTransaction } from "../codec/tx.ts";
import type { IngestContext } from "../ingest.ts";
import type { Store } from "../store/store.ts";
import type { RpcClient } from "./rpc.ts";

export const ORE_API = "https://api.ore.com";
const USER_AGENT = "HeadsDown-indexer/0.1 (+https://github.com/heads-down; public traction dashboard)";
/** What `limit=100` asks for. How many rounds a page really holds is taken from page 0 of each pass. */
const PAGE_SIZE = 100n;
/** Between two requests of one pass. */
export const ORE_API_PAGE_PAUSE_MS = 1000;
/** A longer `Retry-After` is cut to this, so that one answer cannot stop the rounds for good. */
export const ORE_API_MAX_RETRY_AFTER_S = 3600;
/** Passes in a row that api.ore.com may turn away, none getting further than the one before, until the pass counts as failed. */
export const ORE_API_STALLED_PASSES = 3;

/** JSON.parse that turns every integer literal into a BigInt (exact u64). */
export function parseJsonBig(text: string): unknown {
  return JSON.parse(text, function (_key, value, ctx?: { source?: string }) {
    if (typeof value === "number" && ctx?.source !== undefined && /^-?\d+$/.test(ctx.source)) return BigInt(ctx.source);
    return value;
  });
}

function u64(v: unknown, field: string): bigint {
  if (typeof v !== "bigint" || v < 0n || v > U64_MAX) throw new DecodeError("BAD_FIELD", `${field} is not a u64`);
  return v;
}

function byteArray(v: unknown, len: number, field: string): Uint8Array {
  if (!Array.isArray(v) || v.length !== len) throw new DecodeError("BAD_FIELD", `${field} must be ${len} bytes`);
  const out = new Uint8Array(len);
  for (let i = 0; i < len; i++) {
    const b = v[i];
    if (typeof b !== "bigint" || b < 0n || b > 255n) throw new DecodeError("BAD_FIELD", `${field}[${i}]`);
    out[i] = Number(b);
  }
  return out;
}

export function parseResetItem(item: unknown): { event: OreResetEvent; resetSignature: string } {
  if (!Array.isArray(item) || item.length !== 2) throw new DecodeError("BAD_FIELD", "reset item must be [signature, event]");
  const signature = encodeBase58(byteArray(item[0], 64, "signature"));
  if (!isSignature(signature)) throw new DecodeError("BAD_FIELD", "signature");
  const e = item[1] as Record<string, unknown>;
  if (typeof e !== "object" || e === null) throw new DecodeError("BAD_FIELD", "event");
  const ts = u64(e.ts, "ts");
  const miners = e.total_miners ?? e.num_winners; // api.ore.com names ResetEvent.total_miners "num_winners"
  return {
    resetSignature: signature,
    event: {
      kind: "OreReset",
      roundId: u64(e.round_id, "round_id"),
      startSlot: u64(e.start_slot, "start_slot"),
      endSlot: u64(e.end_slot, "end_slot"),
      winningSquare: normalizeWinningSquare(u64(e.winning_square, "winning_square")),
      topMiner: encodeBase58(byteArray(e.top_miner, 32, "top_miner")),
      totalMiners: u64(miners, "total_miners"),
      motherlode: u64(e.motherlode, "motherlode"),
      totalDeployed: u64(e.total_deployed, "total_deployed"),
      totalVaulted: u64(e.total_vaulted, "total_vaulted"),
      totalWinnings: u64(e.total_winnings, "total_winnings"),
      totalMinted: u64(e.total_minted, "total_minted"),
      ts,
      rng: u64(e.rng, "rng"),
      deployedWinningSquare: u64(e.deployed_winning_square, "deployed_winning_square"),
    },
  };
}

type ResetItem = ReturnType<typeof parseResetItem>;

/** The round id of an item that did not decode, when that much of it can be read. */
function roundIdOf(item: unknown): bigint | null {
  const e: unknown = Array.isArray(item) ? item[1] : null;
  const id = typeof e === "object" && e !== null ? (e as { round_id?: unknown }).round_id : null;
  return typeof id === "bigint" && id >= 0n && id <= U64_MAX ? id : null;
}

// ------------------------------------------------------------------ what has been read so far

/** Round ids `hi` down to `lo`, both included. */
type Range = [hi: bigint, lo: bigint];

/** Cursor `ore-api`/`covered`: which rounds of the list have been read. */
interface Covered {
  /**
   * Newest first, and no two touch. A round inside a range was stored, did not decode (recorded
   * as a problem), or is missing from the list (recorded as ORE_API_GAP).
   */
  ranges: Range[];
  /**
   * Nothing at or below `id` is wanted: the newest round found to be older than `since` (`ts` is
   * its time), or the end of the list (`ts` -1).
   */
  floor: { id: bigint; ts: number } | null;
}

function addRange(ranges: Range[], hi: bigint, lo: bigint): Range[] {
  const out: Range[] = [];
  for (const [h, l] of ranges) {
    if (l > hi + 1n || h < lo - 1n) out.push([h, l]);
    else {
      // Overlapping or touching: they become one range.
      if (h > hi) hi = h;
      if (l < lo) lo = l;
    }
  }
  out.push([hi, lo]);
  return out.sort((a, b) => (a[0] < b[0] ? 1 : -1));
}

const covers = (ranges: Range[], id: bigint) => ranges.some(([h, l]) => id <= h && id >= l);

/** The floor while it holds: one found under an earlier `since` holds as long as its round is older than the present one. */
const floorOf = (c: Covered, since: number) => (c.floor !== null && c.floor.ts < since ? c.floor : null);

/** A value this code did not write reads as nothing covered: those rounds are asked for again. */
function parseCovered(raw: string | null): Covered {
  if (raw === null) return { ranges: [], floor: null };
  try {
    const v = JSON.parse(raw) as { ranges: [string, string][]; floor: [string, number] | null };
    let ranges: Range[] = [];
    for (const [hi, lo] of v.ranges) {
      if (BigInt(lo) < 0n || BigInt(lo) > BigInt(hi)) throw new Error("not a range");
      ranges = addRange(ranges, BigInt(hi), BigInt(lo));
    }
    if (v.floor !== null && !Number.isSafeInteger(v.floor[1])) throw new Error("not a floor");
    return { ranges, floor: v.floor === null ? null : { id: BigInt(v.floor[0]), ts: v.floor[1] } };
  } catch {
    return { ranges: [], floor: null };
  }
}

const formatCovered = (c: Covered) =>
  JSON.stringify({ ranges: c.ranges.map(([hi, lo]) => [hi.toString(), lo.toString()]), floor: c.floor === null ? null : [c.floor.id.toString(), c.floor.ts] });

/**
 * The stored note. A database from before pages were stored one by one has none, only the cursor
 * `newest-round`: that code wrote a poll's rounds all at once, so the unbroken run of stored
 * rounds that ends at that cursor is what it read.
 */
async function loadCovered(store: Store): Promise<Covered> {
  const raw = await store.getCursor("ore-api", "covered");
  if (raw !== null) return parseCovered(raw);
  const old = await store.getCursor("ore-api", "newest-round");
  if (old === null || !/^\d{1,20}$/.test(old)) return { ranges: [], floor: null };
  const lo = await store.oreRunBottom(BigInt(old));
  if (lo === null) return { ranges: [], floor: null };
  const c: Covered = { ranges: [[BigInt(old), lo]], floor: null };
  await store.setCursor("ore-api", "covered", formatCovered(c));
  return c;
}

/**
 * Cursor `ore-api`/`busy`: the last refusal. `at` is the round that was asked for ("newest" for
 * page 0), `passes` how many passes in a row were turned away without getting further (0: the last
 * pass was not turned away), `until` the unix time before which nothing is asked (0: none).
 */
interface Busy {
  at: string;
  passes: number;
  until: number;
  why: string;
}

function parseBusy(raw: string | null): Busy {
  const none: Busy = { at: "", passes: 0, until: 0, why: "" };
  if (raw === null) return none;
  try {
    const v = JSON.parse(raw) as Partial<Busy>;
    if (typeof v.at !== "string" || typeof v.why !== "string" || !Number.isSafeInteger(v.passes) || !Number.isSafeInteger(v.until)) return none;
    return { at: v.at, passes: v.passes!, until: v.until!, why: v.why };
  } catch {
    return none;
  }
}

// ------------------------------------------------------------------ one request

/** api.ore.com has no answer for now: HTTP 429, a 5xx, a timeout, or a connection that failed. */
class OreApiBusy extends Error {
  /** From `Retry-After`, in seconds; null when the answer carried none. */
  readonly retryAfterS: number | null;
  constructor(what: string, retryAfterS: number | null) {
    super(what);
    this.retryAfterS = retryAfterS;
  }
}

/** The seconds a `Retry-After` header asks for (a number of seconds or an HTTP date); null when it says neither. */
export function retryAfterSeconds(header: string | null, now: () => number): number | null {
  const h = header?.trim() ?? "";
  if (/^\d{1,9}$/.test(h)) return Number(h);
  if (!/^[A-Za-z]{3}, \d{2} [A-Za-z]{3} \d{4} \d{2}:\d{2}:\d{2} GMT$/.test(h)) return null;
  const at = Date.parse(h);
  return Number.isNaN(at) ? null : Math.max(0, Math.ceil(at / 1000) - now());
}

/** Only the kind of failure is kept, as for the RPC. */
const noAnswer = (e: unknown) => new OreApiBusy(`no answer (${e instanceof Error ? e.name : "error"})`, null);

async function getPage(base: string, page: number, f: typeof fetch, now: () => number): Promise<unknown[]> {
  let res: Response;
  try {
    res = await f(`${base}/events/reset?page=${page}&limit=100`, {
      headers: { "user-agent": USER_AGENT, accept: "application/json" },
      signal: AbortSignal.timeout(20_000),
    });
  } catch (e) {
    throw noAnswer(e);
  }
  if (res.status === 429 || res.status >= 500) {
    void res.body?.cancel().catch(() => undefined);
    throw new OreApiBusy(`HTTP ${res.status}`, retryAfterSeconds(res.headers.get("retry-after"), now));
  }
  if (!res.ok) throw new Error(`api.ore.com /events/reset page ${page}: HTTP ${res.status}`);
  let text: string;
  try {
    text = await res.text();
  } catch (e) {
    throw noAnswer(e);
  }
  if (text.length > 8 * 1024 * 1024) throw new Error("api.ore.com response too large");
  let body: unknown;
  try {
    body = parseJsonBig(text);
  } catch {
    throw new Error(`api.ore.com /events/reset page ${page}: not JSON`);
  }
  if (!Array.isArray(body)) throw new Error("api.ore.com: expected an array");
  return body;
}

/**
 * The spot check: re-reads `sample` from chain. Where the chain's ResetEvent differs from the
 * API's, the difference is recorded and the chain's takes its place.
 */
async function verifyOnChain(ctx: IngestContext, rpc: RpcClient, sample: ResetItem[]): Promise<{ verified: number; mismatches: number }> {
  let verified = 0;
  let mismatches = 0;
  for (const r of sample) {
    const tx = await rpc.getTransaction(r.resetSignature);
    if (!tx) continue;
    const x = extractTransaction(tx, { programId: ctx.programId, executorPda: ctx.executorPda });
    const chain = x.oreResets.find((e) => e.event.roundId === r.event.roundId)?.event;
    if (!chain) continue;
    verified++;
    if (JSON.stringify(chain, big) !== JSON.stringify(r.event, big)) {
      mismatches++;
      await ctx.store.recordProblem(r.resetSignature, `round ${r.event.roundId}`, "ORE_API_MISMATCH", "api.ore.com ResetEvent differs from chain; chain value stored");
      r.event = chain;
    }
  }
  return { verified, mismatches };
}

// ------------------------------------------------------------------ one pass

export interface OreApiOptions {
  baseUrl?: string;
  /** Backfill no further back than this unix time. */
  since: number;
  /** Requests to api.ore.com in one pass, page 0 included. */
  maxPages: number;
  /** How many new rounds per poll to re-verify on chain (0 = none). */
  verifySample: number;
  fetchImpl?: typeof fetch;
  /** The pause between two requests (default: a real wait of ORE_API_PAGE_PAUSE_MS). */
  sleep?: (ms: number) => Promise<void>;
  /** Unix seconds; only looked at when `Retry-After` is in play. */
  now?: () => number;
}

export interface OreRoundsResult {
  /** Rounds this pass read for the first time and wrote. One the chain had delivered already is counted too, and keeps the chain's values. */
  stored: number;
  verified: number;
  mismatches: number;
  /** Pages api.ore.com answered in this pass. */
  pages: number;
  /** The newest round id it listed; null when page 0 was not read. */
  newest: string | null;
  /** Time of the oldest round of the unbroken run that ends at the newest round read so far; null before the first. */
  backTo: string | null;
  /** "done" when that run reaches `since` or the end of the list. */
  backfill: "done" | "running";
  /** Open ranges inside what has been read, left by a pass that ran out of pages or was turned away. Later passes close them. */
  gaps?: number;
  /** What api.ore.com turned the pass away with, and where. */
  stopped?: string;
  /** Seconds until api.ore.com is asked again, when its answer carried `Retry-After`. */
  retryAfterS?: number;
  /** Passes in a row that were turned away without getting further. */
  stalledPasses?: number;
  /** No request was made: the time `Retry-After` named has not come. */
  waiting?: true;
}

const defaultSleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** `a / b` rounded down (BigInt division rounds towards zero). */
const floorDiv = (a: bigint, b: bigint) => (a % b !== 0n && a < 0n ? a / b - 1n : a / b);

/**
 * One pass over the list: at most `opts.maxPages` requests, with a pause between them.
 *
 * Page 0 comes first: the newest rounds. After it the pass asks, again and again, for the newest
 * round id it does not have below them. That closes the head (rounds that arrived since the last
 * pass), then any range an earlier pass left open, and then goes on with the backfill where it
 * stopped, until a round older than `opts.since` or the end of the list.
 *
 * Each page is stored when it arrives, with the note of what has been read (cursor
 * `ore-api`/`covered`), so a later page that fails loses nothing. No page number is kept: every
 * new round moves the older ones one place down the list. The page of a round is worked out from
 * the ids of the page read last, and a page is taken for the ids it holds, whatever was expected
 * of it. A page that overlaps an earlier one only repeats rounds, and a round is stored once.
 *
 * A round is believed missing from the list only when one page shows its two neighbours next to
 * each other; it is then recorded (ORE_API_GAP) and not asked for again. A round that should sit
 * between two pages stays open: once new rounds have moved it inside a page, a later pass settles it.
 *
 * HTTP 429, a 5xx, a timeout or a connection that fails end the pass here without an error. The
 * result says where (`stopped`); `Retry-After` is kept, up to ORE_API_MAX_RETRY_AFTER_S, and
 * nothing is asked before it has passed. `stalledPasses` counts the passes in a row that were
 * turned away without getting further: the ingest loop reports ORE_API_STALLED_PASSES of them as a
 * failed pass. Any other answer (a 4xx, a body that is not the list) is an error, as before.
 */
export async function pollOreRounds(ctx: IngestContext, opts: OreApiOptions, rpc: RpcClient | null): Promise<OreRoundsResult> {
  const base = opts.baseUrl ?? ORE_API;
  const f = opts.fetchImpl ?? fetch;
  const sleep = opts.sleep ?? defaultSleep;
  const now = opts.now ?? (() => Math.floor(Date.now() / 1000));
  const store = ctx.store;
  const count = { stored: 0, verified: 0, mismatches: 0, pages: 0 };
  const busy = parseBusy(await store.getCursor("ore-api", "busy"));
  let covered = await loadCovered(store);

  /** How far back the stored rounds reach, from what is covered now. */
  const standing = async () => {
    const floor = floorOf(covered, opts.since);
    const top = covered.ranges[0];
    const done = top === undefined ? floor !== null : top[1] === 0n || (floor !== null && top[1] - 1n <= floor.id);
    const gaps = covered.ranges.filter(([hi], i) => i > 0 && (floor === null || hi > floor.id)).length;
    const ts = top === undefined ? null : await store.oreRoundTime(top[1]);
    return {
      backTo: ts === null ? null : new Date(ts * 1000).toISOString(),
      backfill: done ? ("done" as const) : ("running" as const),
      ...(gaps > 0 ? { gaps } : {}),
    };
  };

  // Told to wait, and the time has not come: nothing is asked.
  const wait = busy.until > 0 ? busy.until - now() : 0;
  if (wait > 0) return { ...count, newest: null, ...(await standing()), stopped: busy.why, retryAfterS: wait, stalledPasses: busy.passes, waiting: true };

  /** Pages read in this pass that held rounds: none is asked for twice. */
  const seen = new Set<number>();
  /** Round ids that were not on the page their id pointed to: left for the next pass. */
  const deferred = new Set<bigint>();
  /** The page read last, by its newest and oldest round id. */
  let last: { page: number; first: bigint; lo: bigint } | null = null;
  /** Rounds to a page, as page 0 of this pass held them. */
  let perPage = PAGE_SIZE;
  /** A page that came back empty: the list ends before it. */
  let emptyFrom: number | null = null;
  let toVerify = rpc === null ? 0 : opts.verifySample;
  let newest: string | null = null;
  let progressed = false;
  let turnedAway: { what: string; page: number; want: bigint | null; retryAfterS: number | null } | null = null;

  /** Stores what a page brought, with the note of what has now been read. */
  const keep = async (rows: ResetItem[], range: Range | null, floor: Covered["floor"]) => {
    const next: Covered = { ranges: range === null ? covered.ranges : addRange(covered.ranges, range[0], range[1]), floor: floor ?? covered.floor };
    await store.storeOreApiPage(rows, formatCovered(next));
    covered = next;
    progressed = true;
  };

  /** The newest round id still wanted and the page it should be on; null when this pass has nothing left to ask. */
  const pick = (): { page: number; want: bigint } | null => {
    if (last === null) return null;
    const floor = floorOf(covered, opts.since);
    for (const [, lo] of covered.ranges) {
      const want = lo - 1n;
      if (want < 0n || (floor !== null && want <= floor.id)) return null;
      if (deferred.has(want)) continue;
      // Right below the page read last: the next page. Anywhere else: as many pages on as its id is rounds away.
      let page = want === last.lo - 1n ? last.page + 1 : last.page + Number(floorDiv(last.first - want, perPage));
      if (want !== last.lo - 1n && emptyFrom !== null && page >= emptyFrom) page = emptyFrom - 1;
      if (page < 0) page = 0;
      if (seen.has(page)) {
        deferred.add(want);
        continue;
      }
      return { page, want };
    }
    return null;
  };

  let next: { page: number; want: bigint | null } | null = { page: 0, want: null };
  for (let asked = 0; next !== null && asked < opts.maxPages; asked++) {
    if (asked > 0) await sleep(ORE_API_PAGE_PAUSE_MS);
    const { page, want } = next;
    let items: unknown[];
    try {
      items = await getPage(base, page, f, now);
    } catch (e) {
      if (!(e instanceof OreApiBusy)) throw e;
      turnedAway = { what: e.message, page, want, retryAfterS: e.retryAfterS };
      break;
    }
    count.pages++;
    if (items.length === 0) {
      if (page === 0) break; // the list is empty
      // The list ends before this page. When the page before it was read first, that page's oldest round is the oldest there is.
      if (last !== null && last.page === page - 1) await keep([], null, { id: last.lo - 1n, ts: -1 });
      emptyFrom = emptyFrom === null ? page : Math.min(emptyFrom, page);
      next = pick();
      continue;
    }
    seen.add(page);

    const rounds: ResetItem[] = [];
    const ids: bigint[] = [];
    for (const it of items) {
      try {
        const r = parseResetItem(it);
        rounds.push(r);
        ids.push(r.event.roundId);
      } catch (e) {
        if (!(e instanceof DecodeError)) throw e;
        // With its round id readable the round counts as read: it is not asked for again.
        const id = roundIdOf(it);
        if (id !== null) ids.push(id);
        await store.recordProblem(id === null ? `ore-api page ${page}` : `ore-api round ${id}`, "reset item", e.code, e.message);
      }
    }
    if (ids.length === 0) break; // nothing on this page says where in the list it is
    ids.sort((a, b) => (a < b ? 1 : a > b ? -1 : 0));
    // The rounds of the page before it under another page number: the API is not paging, and asking on would lead nowhere.
    if (last !== null && last.first === ids[0] && last.lo === ids[ids.length - 1]) throw new Error(`api.ore.com /events/reset page ${page}: the same rounds as page ${last.page}`);
    last = { page, first: ids[0]!, lo: ids[ids.length - 1]! };
    if (page === 0) {
      newest = last.first.toString();
      perPage = BigInt(items.length);
    }

    // Nothing at or below `cut` is wanted: the floor, or this page's newest round that is older than `since`.
    const floor = floorOf(covered, opts.since);
    let cut = floor;
    for (const r of rounds) {
      const ts = Number(r.event.ts);
      if (ts < opts.since && (cut === null || r.event.roundId > cut.id)) cut = { id: r.event.roundId, ts };
    }
    const limit = cut === null ? -1n : cut.id;
    const wanted = ids.filter((id) => id > limit);
    const have = covered.ranges;
    const fresh = [...new Map(rounds.filter((r) => r.event.roundId > limit && !covers(have, r.event.roundId)).map((r) => [r.event.roundId, r])).values()];
    fresh.sort((a, b) => (a.event.roundId < b.event.roundId ? 1 : -1));

    if (rpc !== null && toVerify > 0 && fresh.length > 0) {
      // The newest new rounds of the pass, before they are stored: the chain's value has to be the one that is written.
      const sample = fresh.slice(0, toVerify);
      toVerify -= sample.length;
      const v = await verifyOnChain(ctx, rpc, sample);
      count.verified += v.verified;
      count.mismatches += v.mismatches;
    }

    // An id this page skips between two neighbours is missing from the list (as far as it is wanted: above `cut`).
    for (let i = 1; i < ids.length; i++) {
      const hi = ids[i - 1]! - 1n;
      const lo = ids[i]! > limit ? ids[i]! + 1n : limit + 1n;
      if (hi < lo || covers(have, hi)) continue;
      const which = hi === lo ? `round ${lo}` : `rounds ${lo}..${hi}`;
      await store.recordProblem(`ore-api ${which}`, "reset list", "ORE_API_GAP", `no readable ResetEvent for ${which} in api.ore.com /events/reset`);
      ctx.log?.("ore rounds: not listed by api.ore.com", { from: lo.toString(), to: hi.toString() }, "warn");
    }

    // What this page has read: from its newest wanted round down to its oldest, or down to `cut` when it shows a round at or below it.
    const range: Range | null = wanted.length === 0 ? null : [wanted[0]!, last.lo <= limit ? limit + 1n : wanted[wanted.length - 1]!];
    const known = range === null || have.some(([h, l]) => h >= range[0] && l <= range[1]);
    if (fresh.length > 0 || !known || cut !== floor) await keep(fresh, range, cut === floor ? null : cut);
    count.stored += fresh.length;
    next = pick();
  }

  if (turnedAway === null) {
    if (busy.passes > 0 || busy.until > 0) await store.setCursor("ore-api", "busy", "{}");
    return { ...count, newest, ...(await standing()) };
  }
  const at = turnedAway.want === null ? "newest" : turnedAway.want.toString();
  const passes = busy.at === at || !progressed ? busy.passes + 1 : 1;
  const retryAfterS = turnedAway.retryAfterS === null ? null : Math.min(turnedAway.retryAfterS, ORE_API_MAX_RETRY_AFTER_S);
  const why = `${turnedAway.what} at page ${turnedAway.page}, asking for ${turnedAway.want === null ? "the newest rounds" : `round ${turnedAway.want}`}`;
  await store.setCursor("ore-api", "busy", JSON.stringify({ at, passes, until: retryAfterS === null ? 0 : now() + retryAfterS, why } satisfies Busy));
  return { ...count, newest, ...(await standing()), stopped: why, ...(retryAfterS === null ? {} : { retryAfterS }), stalledPasses: passes };
}

function big(_k: string, v: unknown) {
  return typeof v === "bigint" ? v.toString() : v;
}
