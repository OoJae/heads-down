/**
 * Public read API (node:http, no framework: a small surface to audit).
 *
 *  - GET only (plus OPTIONS for CORS preflight). The only write route is the optional Helius
 *    webhook, which is off unless a secret is configured and is never enabled for the
 *    simulated dataset.
 *  - Bound to ONE dataset; every JSON response carries `dataset` (with `simulated`) and an
 *    `X-HeadsDown-Dataset` header; every CSV row starts with the dataset name.
 *  - Query parameters are integer-validated and range-checked; errors never include stack
 *    traces, SQL or configuration.
 */
import http from "node:http";
import type { Store } from "../store/store.ts";
import type { DatasetInfo, MetricsInput } from "../model.ts";
import {
  computeMilestones,
  computeSummary,
  cohortReport,
  monthlyReport,
  pairDigs,
  recentDigs,
  roundShares,
  shareByHour,
  type Evidence,
  type MetricsOptions,
} from "../metrics/metrics.ts";
import { DAY, MAX_TZ_OFFSET, MIN_TZ_OFFSET, formatTzOffset } from "../metrics/time.ts";
import { toCsv } from "./csv.ts";
import { explorerUrl } from "./explorer.ts";
import { openApiDocument } from "./openapi.ts";
import { MAX_WEBHOOK_BYTES, checkWebhookAuth, handleHeliusPayload } from "../sources/helius.ts";
import type { RpcClient } from "../sources/rpc.ts";
import type { IngestContext } from "../ingest.ts";

export interface ApiDeps {
  store: Store;
  info: DatasetInfo;
  defaultTzOffsetMinutes: number;
  teamCrankers: string[];
  now?: () => number;
  cacheTtlMs?: number;
  corsOrigin?: string;
  webhook?: { secret: string; rpc: RpcClient | null; trustPayload: boolean; ctx: IngestContext } | null;
  log?: (msg: string, fields?: Record<string, unknown>) => void;
}

class HttpError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

function intParam(q: URLSearchParams, name: string, def: number, min: number, max: number): number {
  const raw = q.get(name);
  if (raw === null || raw === "") return def;
  if (!/^-?\d{1,6}$/.test(raw)) throw new HttpError(400, `${name} must be an integer`);
  const v = Number(raw);
  if (v < min || v > max) throw new HttpError(400, `${name} must be between ${min} and ${max}`);
  return v;
}

const iso = (t: number) => new Date(t * 1000).toISOString();

export function createApiServer(deps: ApiDeps): http.Server {
  const now = deps.now ?? (() => Math.floor(Date.now() / 1000));
  const ttl = deps.cacheTtlMs ?? 30_000;
  const cors = deps.corsOrigin ?? "*";
  const ds = deps.info;
  const datasetJson = { name: ds.name, simulated: ds.simulated, programId: ds.programId, executorPda: ds.executorPda, simSeed: ds.simSeed };
  const link = (kind: "tx" | "account", id: string | null) => (id ? explorerUrl(ds.name, kind, id) : null);
  const withUrls = (e: Evidence[]) => e.map((x) => ({ ...x, url: explorerUrl(ds.name, x.kind, x.id) }));

  // One shared, briefly cached snapshot of the metrics input.
  let cache: { at: number; input: Promise<MetricsInput> } | null = null;
  const loadInput = () => {
    const t = Date.now();
    if (!cache || t - cache.at > ttl) {
      const input = deps.store.loadMetricsInput();
      cache = { at: t, input };
      input.catch(() => {
        if (cache?.input === input) cache = null;
      });
    }
    return cache.input;
  };
  // Simulated data is frozen at its generation time so it renders the same every time.
  const asOf = () => (ds.simulated && ds.simAsOf !== null ? ds.simAsOf : now());
  const metricOpts = (tz: number): MetricsOptions => ({
    asOf: asOf(),
    tzOffsetMinutes: tz,
    programId: ds.programId,
    executorPda: ds.executorPda,
    teamCrankers: deps.teamCrankers,
  });

  const json = (res: http.ServerResponse, status: number, body: unknown) => {
    const text = JSON.stringify(body, (_k, v) => (typeof v === "bigint" ? v.toString() : v));
    res.writeHead(status, {
      "content-type": "application/json; charset=utf-8",
      "cache-control": status === 200 ? "public, max-age=30" : "no-store",
    });
    res.end(text);
  };
  const ok = (res: http.ServerResponse, data: unknown) =>
    json(res, 200, { dataset: datasetJson, asOf: asOf(), generatedAt: new Date().toISOString(), data });
  const csv = (res: http.ServerResponse, name: string, header: string[], rows: (string | number | bigint | null)[][]) => {
    const stamp = iso(asOf()).slice(0, 10);
    const file = `${ds.simulated ? "SIMULATED-" : ""}heads-down-${ds.name}-${name}-${stamp}.csv`;
    res.writeHead(200, {
      "content-type": "text/csv; charset=utf-8",
      "content-disposition": `attachment; filename="${file}"`,
      "cache-control": "public, max-age=60",
    });
    res.end(toCsv(["dataset", ...header], rows.map((r) => [ds.name, ...r])));
  };

  const routes: Record<string, (q: URLSearchParams, res: http.ServerResponse) => Promise<void>> = {
    "/v1/health": async (_q, res) => ok(res, { status: "ok", ...(await deps.store.health()) }),

    "/v1/summary": async (q, res) => {
      const tz = intParam(q, "tz", deps.defaultTzOffsetMinutes, MIN_TZ_OFFSET, MAX_TZ_OFFSET);
      const s = computeSummary(await loadInput(), metricOpts(tz));
      ok(res, {
        ...s,
        rigs: { ...s.rigs, evidence: withUrls(s.rigs.evidence) },
        nightlyActive: { ...s.nightlyActive, evidence: withUrls(s.nightlyActive.evidence) },
        darkHours: { ...s.darkHours, evidence: withUrls(s.darkHours.evidence) },
        roundsDug: { ...s.roundsDug, evidence: withUrls(s.roundsDug.evidence) },
        solDeployed: { ...s.solDeployed, evidence: withUrls(s.solDeployed.evidence) },
        ore: { ...s.ore, mined: { ...s.ore.mined, evidence: withUrls(s.ore.mined.evidence) } },
        gate: { ...s.gate, evidence: withUrls(s.gate.evidence) },
      });
    },

    "/v1/cohorts": async (q, res) => {
      const tz = intParam(q, "tz", deps.defaultTzOffsetMinutes, MIN_TZ_OFFSET, MAX_TZ_OFFSET);
      ok(res, cohortReport(await loadInput(), metricOpts(tz)));
    },

    "/v1/share-by-hour": async (q, res) => {
      const tz = intParam(q, "tz", deps.defaultTzOffsetMinutes, MIN_TZ_OFFSET, MAX_TZ_OFFSET);
      const days = intParam(q, "days", 7, 1, 90);
      const input = await loadInput();
      const to = asOf();
      const r = shareByHour(roundShares(input.deploys, input.rounds), tz, to - days * DAY, to, days);
      ok(res, {
        ...r,
        hours: r.hours.map((h) => ({
          ...h,
          peak: h.peak ? { ...h.peak, resetUrl: link("tx", h.peak.resetSignature), digUrl: link("tx", h.peak.sampleDig) } : null,
        })),
      });
    },

    "/v1/digs/recent": async (q, res) => {
      const limit = intParam(q, "limit", 50, 1, 200);
      const feed = recentDigs(await loadInput(), ds.programId, limit);
      ok(res, feed.map((f) => ({ ...f, txUrl: link("tx", f.signature), rigUrl: link("account", f.rig) })));
    },

    "/v1/milestones": async (q, res) => {
      const tz = intParam(q, "tz", deps.defaultTzOffsetMinutes, MIN_TZ_OFFSET, MAX_TZ_OFFSET);
      const input = await loadInput();
      const opts = metricOpts(tz);
      ok(res, computeMilestones(input, computeSummary(input, opts), opts));
    },

    "/v1/export/rounds.csv": async (q, res) => {
      const days = intParam(q, "days", 30, 1, 90);
      const input = await loadInput();
      const to = asOf();
      const from = to - days * DAY;
      const rows = roundShares(input.deploys, input.rounds)
        .filter((s) => s.ts >= from && s.ts <= to)
        .map((s) => [
          s.roundId, iso(s.ts), new Date(s.ts * 1000).getUTCHours(), s.totalMiners, s.hdMiners,
          s.share === null ? null : Number(s.share.toFixed(6)), s.hdLamports, s.resetSignature, s.sampleDig,
        ]);
      csv(res, "rounds", ["round_id", "reset_time_utc", "hour_utc", "ore_total_miners", "heads_down_miners", "heads_down_share", "heads_down_lamports", "reset_signature", "sample_dig_signature"], rows);
    },

    "/v1/export/digs.csv": async (q, res) => {
      const days = intParam(q, "days", 30, 1, 90);
      const input = await loadInput();
      const to = asOf();
      const from = to - days * DAY;
      const digs = input.digs.filter((d) => d.blockTime !== null && d.blockTime >= from && d.blockTime <= to);
      const sigs = new Set(digs.map((d) => d.signature));
      const { paired } = pairDigs(digs, input.deploys.filter((d) => sigs.has(d.signature)), ds.programId);
      csv(
        res,
        "digs",
        ["signature", "block_time_utc", "slot", "rig", "authority", "round_id", "lamports", "mask", "ema_ev_lamports_per_ore", "fee_payer"],
        paired.map((p) => [p.dig.signature, iso(p.dig.blockTime!), p.dig.slot, p.dig.rig, p.authority, p.dig.roundId, p.dig.lamports, p.dig.mask, p.dig.emaEv, p.dig.feePayer]),
      );
    },

    "/v1/export/monthly.csv": async (q, res) => {
      const tz = intParam(q, "tz", deps.defaultTzOffsetMinutes, MIN_TZ_OFFSET, MAX_TZ_OFFSET);
      const rows = monthlyReport(await loadInput(), metricOpts(tz));
      csv(
        res,
        "monthly",
        ["month_utc", "night_tz", "rigs_first_seen", "rigs_cumulative", "seeker_verified_cumulative", "nightly_active_avg", "nightly_active_peak", "rig_rounds_dug", "lamports_deployed", "ore_mined_base_units", "ore_bought", "ore_buried", "heads_down_share_mean"],
        rows.map((r) => [
          r.month, formatTzOffset(tz), r.rigsFirstSeen, r.rigsCumulative, r.seekerVerifiedCumulative, Number(r.nightlyActiveAvg.toFixed(2)),
          r.nightlyActivePeak, r.rigRoundsDug, r.lamportsDeployed, r.oreMined, "not_shipped", "not_shipped",
          r.hdShareMean === null ? null : Number(r.hdShareMean.toFixed(6)),
        ]),
      );
    },

    "/openapi.json": async (_q, res) => json(res, 200, openApiDocument),
  };

  async function webhook(req: http.IncomingMessage, res: http.ServerResponse) {
    const w = deps.webhook;
    if (!w || ds.simulated) throw new HttpError(404, "not found");
    if (!checkWebhookAuth(req.headers.authorization, w.secret)) throw new HttpError(401, "unauthorized");
    const chunks: Buffer[] = [];
    let size = 0;
    for await (const c of req) {
      size += (c as Buffer).length;
      if (size > MAX_WEBHOOK_BYTES) throw new HttpError(413, "payload too large");
      chunks.push(c as Buffer);
    }
    let body: unknown;
    try {
      body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    } catch {
      throw new HttpError(400, "invalid JSON");
    }
    const r = await handleHeliusPayload(w.ctx, body, { rpc: w.rpc, trustPayload: w.trustPayload });
    cache = null;
    json(res, 200, r);
  }

  return http.createServer(async (req, res) => {
    res.setHeader("access-control-allow-origin", cors);
    res.setHeader("access-control-allow-methods", "GET, OPTIONS");
    res.setHeader("x-content-type-options", "nosniff");
    res.setHeader("referrer-policy", "no-referrer");
    res.setHeader("content-security-policy", "default-src 'none'; frame-ancestors 'none'");
    res.setHeader("x-headsdown-dataset", ds.name);
    try {
      if ((req.url ?? "").length > 1024) throw new HttpError(414, "URI too long");
      const url = new URL(req.url ?? "/", "http://localhost");
      if (req.method === "OPTIONS") {
        res.writeHead(204, { "access-control-max-age": "600" });
        res.end();
        return;
      }
      if (url.pathname === "/webhooks/helius") {
        if (req.method !== "POST") throw new HttpError(405, "method not allowed");
        await webhook(req, res);
        return;
      }
      const route = routes[url.pathname];
      if (!route) throw new HttpError(404, "not found");
      if (req.method !== "GET" && req.method !== "HEAD") throw new HttpError(405, "method not allowed");
      await route(url.searchParams, res);
    } catch (e) {
      const status = e instanceof HttpError ? e.status : 500;
      if (status === 500) deps.log?.("api: internal error", { error: e instanceof Error ? e.name : "unknown" });
      if (!res.headersSent) json(res, status, { error: e instanceof HttpError ? e.message : "internal error" });
      else res.destroy();
    }
  });
}

/** Route list, for the OpenAPI completeness test. */
export const API_ROUTES = [
  "/v1/health",
  "/v1/summary",
  "/v1/cohorts",
  "/v1/share-by-hour",
  "/v1/digs/recent",
  "/v1/milestones",
  "/v1/export/rounds.csv",
  "/v1/export/digs.csv",
  "/v1/export/monthly.csv",
  "/openapi.json",
];
