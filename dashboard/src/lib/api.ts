/**
 * Browser client for the indexer's public API.
 *
 *  - The base URL comes from NEXT_PUBLIC_HD_API_BASE (public by definition; no secrets exist
 *    in this app). It must be http(s).
 *  - Dataset guard: the first response pins the dataset (mainnet, simulated, ...). Any later
 *    response from a different dataset is rejected, so a misconfigured deployment can never
 *    show simulated and real numbers side by side.
 *  - Explorer links from the API are only rendered if they point at an allowlisted explorer
 *    (see safeExplorerUrl), so even a compromised API cannot inject javascript: URLs.
 */
import type { DatasetInfo, DatasetName, Envelope, HaulSummary } from "./types";

export function parseApiBase(raw: string | undefined | null): string | null {
  if (!raw) return null;
  try {
    const u = new URL(raw);
    if (u.protocol !== "https:" && u.protocol !== "http:") return null;
    if (u.username || u.password) return null; // never embed credentials in a public site
    return u.toString().replace(/\/+$/, "");
  } catch {
    return null;
  }
}

export const API_BASE = parseApiBase(process.env.NEXT_PUBLIC_HD_API_BASE);

export class DatasetMismatchError extends Error {
  constructor(first: string, now: string) {
    super(`The API switched from the "${first}" dataset to "${now}". Refusing to mix datasets; reload the page.`);
    this.name = "DatasetMismatchError";
  }
}

const DATASETS = new Set(["mainnet", "devnet", "localnet", "simulated"]);

function isEnvelope(x: unknown): x is Envelope<unknown> {
  if (typeof x !== "object" || x === null) return false;
  const e = x as Record<string, unknown>;
  const d = e.dataset as Record<string, unknown> | undefined;
  return (
    typeof d === "object" && d !== null &&
    typeof d.name === "string" && DATASETS.has(d.name) &&
    typeof d.simulated === "boolean" && d.simulated === (d.name === "simulated") &&
    typeof e.asOf === "number" && "data" in e
  );
}

type Listener = (d: DatasetInfo) => void;

export function createClient(base: string | null, fetchImpl: typeof fetch = (...a) => fetch(...a)) {
  let pinned: DatasetInfo | null = null;
  const listeners = new Set<Listener>();
  return {
    base,
    dataset: () => pinned,
    subscribe(l: Listener) {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    url(path: string) {
      if (!base) return null;
      if (!path.startsWith("/v1/") && path !== "/openapi.json") throw new Error("unexpected API path");
      return `${base}${path}`;
    },
    async get<T>(path: string, signal?: AbortSignal): Promise<Envelope<T>> {
      const url = this.url(path);
      if (!url) throw new Error("NEXT_PUBLIC_HD_API_BASE is not configured");
      const res = await fetchImpl(url, { signal, headers: { accept: "application/json" }, credentials: "omit" });
      if (!res.ok) throw new Error(`API ${path}: HTTP ${res.status}`);
      const body: unknown = await res.json();
      if (!isEnvelope(body)) throw new Error(`API ${path}: unexpected response shape`);
      if (pinned === null) {
        pinned = body.dataset;
        for (const l of listeners) l(body.dataset);
      } else if (pinned.name !== body.dataset.name || pinned.programId !== body.dataset.programId) {
        throw new DatasetMismatchError(pinned.name, body.dataset.name);
      }
      return body as Envelope<T>;
    },
  };
}

export type ApiClient = ReturnType<typeof createClient>;

const BASE58_ADDRESS = /^[1-9A-HJ-NP-Za-km-z]{32,44}$/;

export function isRigAddress(s: string | null | undefined): s is string {
  return typeof s === "string" && BASE58_ADDRESS.test(s);
}

const isU64 = (v: unknown) => (typeof v === "number" && Number.isSafeInteger(v) && v >= 0) || (typeof v === "string" && /^\d{1,20}$/.test(v));
const isDecOrNull = (v: unknown) => v === null || (typeof v === "string" && /^\d{1,40}$/.test(v));

/** Shape check for contract B (the haul is not enveloped). */
export function isHaulSummary(x: unknown): x is HaulSummary {
  if (typeof x !== "object" || x === null) return false;
  const h = x as Record<string, unknown>;
  const ex = h.explorer as Record<string, unknown> | undefined;
  return (
    isRigAddress(h.rig as string) &&
    isU64(h.shift_id) &&
    (h.mode === "night" || h.mode === "day" || h.mode === "focus_only") &&
    Number.isSafeInteger(h.start_ts) &&
    Number.isSafeInteger(h.end_ts) &&
    isU64(h.start_round) &&
    isU64(h.end_round) &&
    Array.isArray(h.rounds) &&
    h.rounds.length <= 20_000 &&
    (h.rounds as Record<string, unknown>[]).every(
      (r) =>
        isU64(r.round_id) &&
        typeof r.dark === "boolean" &&
        Number.isInteger(r.dug_mask) &&
        (r.dug_mask as number) >= 0 &&
        (r.dug_mask as number) < 1 << 25 &&
        (r.winning_square === null || (Number.isInteger(r.winning_square) && (r.winning_square as number) >= 0 && (r.winning_square as number) <= 24)) &&
        typeof r.motherlode === "boolean" &&
        typeof r.split === "boolean",
    ) &&
    isU64(h.dark_rounds) &&
    isU64(h.rounds_dug) &&
    isU64(h.sol_placed_lamports) &&
    isU64(h.fees_lamports) &&
    typeof h.ore_mined_atoms === "string" &&
    /^\d{1,40}$/.test(h.ore_mined_atoms) &&
    isDecOrNull(h.effective_lamports_per_ore) &&
    isDecOrNull(h.market_lamports_per_ore) &&
    (h.market_source === null || typeof h.market_source === "string") &&
    Number.isInteger(h.streak_before) &&
    Number.isInteger(h.streak_after) &&
    Number.isInteger(h.break_reason) &&
    h.first_pickup_ts === null &&
    typeof h.simulated === "boolean" &&
    typeof ex === "object" &&
    ex !== null &&
    (ex.shift_log === null || typeof ex.shift_log === "string") &&
    Array.isArray(ex.sample_digs) &&
    ex.sample_digs.every((u) => typeof u === "string")
  );
}

export type HaulResult =
  | { status: "ok"; haul: HaulSummary; dataset: DatasetName }
  | { status: "none"; message: string; retryAfterS: number | null };

/**
 * GET /v1/rigs/{rig}/haul/{shift}: contract B is not enveloped, so the dataset comes from the
 * X-HeadsDown-Dataset header (exposed by the API's CORS) and must agree with both the pinned
 * dataset and the haul's own `simulated` flag.
 */
export async function getHaul(api: ApiClient, rig: string, shift: string, fetchImpl: typeof fetch = (...a) => fetch(...a), signal?: AbortSignal): Promise<HaulResult> {
  if (!isRigAddress(rig)) throw new Error("not a rig address");
  if (shift !== "latest" && !/^\d{1,20}$/.test(shift)) throw new Error("not a shift id");
  const url = api.url(`/v1/rigs/${rig}/haul/${shift}`);
  if (!url) throw new Error("NEXT_PUBLIC_HD_API_BASE is not configured");
  const res = await fetchImpl(url, { signal, headers: { accept: "application/json" }, credentials: "omit" });
  const body: unknown = await res.json().catch(() => null);
  if (res.status === 404) {
    const ra = Number(res.headers.get("retry-after"));
    const msg = typeof (body as { error?: unknown } | null)?.error === "string" ? (body as { error: string }).error : "no finished shift";
    return { status: "none", message: msg.slice(0, 300), retryAfterS: Number.isFinite(ra) && ra > 0 ? ra : null };
  }
  if (!res.ok) throw new Error(`API haul: HTTP ${res.status}`);
  const name = res.headers.get("x-headsdown-dataset");
  if (!name || !DATASETS.has(name)) throw new Error("API haul: missing dataset header");
  if (!isHaulSummary(body)) throw new Error("API haul: unexpected response shape");
  if (body.simulated !== (name === "simulated")) throw new Error("API haul: dataset header and simulated flag disagree");
  const pinned = api.dataset();
  if (pinned && pinned.name !== name) throw new DatasetMismatchError(pinned.name, name);
  return { status: "ok", haul: body, dataset: name as DatasetName };
}

export const client = createClient(API_BASE);

const EXPLORERS = [/^https:\/\/solscan\.io\/(tx|account)\/[1-9A-HJ-NP-Za-km-z]{32,88}(\?cluster=devnet)?$/, /^https:\/\/explorer\.solana\.com\/(tx|address)\/[1-9A-HJ-NP-Za-km-z]{32,88}\?cluster=custom&customUrl=[A-Za-z0-9%._-]+$/];

/** Returns the URL only if it is an allowlisted explorer link for a base58 id. */
export function safeExplorerUrl(url: string | null | undefined): string | null {
  if (!url) return null;
  return EXPLORERS.some((re) => re.test(url)) ? url : null;
}
