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
import type { DatasetInfo, Envelope } from "./types";

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

export const client = createClient(API_BASE);

const EXPLORERS = [/^https:\/\/solscan\.io\/(tx|account)\/[1-9A-HJ-NP-Za-km-z]{32,88}(\?cluster=devnet)?$/, /^https:\/\/explorer\.solana\.com\/(tx|address)\/[1-9A-HJ-NP-Za-km-z]{32,88}\?cluster=custom&customUrl=[A-Za-z0-9%._-]+$/];

/** Returns the URL only if it is an allowlisted explorer link for a base58 id. */
export function safeExplorerUrl(url: string | null | undefined): string | null {
  if (!url) return null;
  return EXPLORERS.some((re) => re.test(url)) ? url : null;
}
