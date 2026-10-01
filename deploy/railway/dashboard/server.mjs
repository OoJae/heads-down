// Static server for the dashboard's Next export (deploy/railway/dashboard). No dependencies.
//
// * GET/HEAD only; /healthz answers 200 for Railway's deploy healthcheck.
// * Paths resolve inside ROOT only (decoded, normalized, prefix-checked); dotfiles are refused.
// * Directories serve index.html; a directory without its trailing slash is redirected (308),
//   matching `trailingSlash: true` in dashboard/next.config.ts so relative assets resolve.
// * 404s serve the export's 404.html with status 404.
// * Hashed assets under /_next/static/ are cached for a year; HTML is revalidated every time.
//   The CSP stays the dashboard's own <meta> tag; these headers add what a meta tag cannot.
// * Listens on [::] (dual-stack) when the host has IPv6, else 0.0.0.0, on $PORT.
import http from "node:http";
import { readFile, stat } from "node:fs/promises";
import path from "node:path";

const ROOT = path.resolve(process.env.DASHBOARD_ROOT ?? "/app/out");
const PORT = Number(process.env.PORT ?? 8080);
const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json",
  ".txt": "text/plain; charset=utf-8",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".webp": "image/webp",
  ".woff2": "font/woff2",
  ".woff": "font/woff",
  ".map": "application/json",
  ".webmanifest": "application/manifest+json",
};
const SECURITY = {
  "x-content-type-options": "nosniff",
  "x-frame-options": "DENY",
  "referrer-policy": "strict-origin-when-cross-origin",
  "permissions-policy": "camera=(), microphone=(), geolocation=(), interest-cohort=()",
  "cross-origin-opener-policy": "same-origin",
  "strict-transport-security": "max-age=31536000",
};

function log(fields) {
  process.stdout.write(JSON.stringify({ t: new Date().toISOString(), ...fields }) + "\n");
}

async function file(p) {
  try {
    const s = await stat(p);
    return s.isFile() ? p : s.isDirectory() ? "dir" : null;
  } catch {
    return null;
  }
}

async function send(res, req, status, p, extra = {}) {
  const body = await readFile(p);
  const ext = path.extname(p).toLowerCase();
  const cache = p.includes(`${path.sep}_next${path.sep}static${path.sep}`)
    ? "public, max-age=31536000, immutable"
    : "public, max-age=0, must-revalidate";
  res.writeHead(status, {
    ...SECURITY,
    "content-type": TYPES[ext] ?? "application/octet-stream",
    "content-length": body.length,
    "cache-control": cache,
    ...extra,
  });
  res.end(req.method === "HEAD" ? undefined : body);
}

async function handle(req, res) {
  if (req.method !== "GET" && req.method !== "HEAD") {
    res.writeHead(405, { ...SECURITY, allow: "GET, HEAD" }).end();
    return;
  }
  let pathname;
  try {
    pathname = decodeURIComponent(new URL(req.url ?? "/", "http://localhost").pathname);
  } catch {
    res.writeHead(400, SECURITY).end();
    return;
  }
  if (pathname === "/healthz") {
    res.writeHead(200, { ...SECURITY, "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" });
    res.end(req.method === "HEAD" ? undefined : "ok\n");
    return;
  }
  if (pathname.includes("\0") || pathname.split("/").some((seg) => seg.startsWith(".") && seg !== "")) {
    res.writeHead(404, SECURITY).end();
    return;
  }
  const target = path.resolve(ROOT, "." + pathname);
  if (target !== ROOT && !target.startsWith(ROOT + path.sep)) {
    res.writeHead(403, SECURITY).end();
    return;
  }
  const kind = await file(target);
  if (kind === "dir") {
    if (!pathname.endsWith("/")) {
      const q = new URL(req.url ?? "/", "http://localhost").search;
      res.writeHead(308, { ...SECURITY, location: `${pathname}/${q}` }).end();
      return;
    }
    if ((await file(path.join(target, "index.html"))) !== null) {
      await send(res, req, 200, path.join(target, "index.html"));
      return;
    }
  } else if (kind !== null) {
    await send(res, req, 200, target);
    return;
  }
  const notFound = path.join(ROOT, "404.html");
  if ((await file(notFound)) !== null) {
    await send(res, req, 404, notFound);
  } else {
    res.writeHead(404, { ...SECURITY, "content-type": "text/plain; charset=utf-8" }).end("not found\n");
  }
}

const server = http.createServer((req, res) => {
  handle(req, res).catch((e) => {
    log({ level: "error", msg: "request failed", error: e instanceof Error ? e.message : String(e) });
    if (!res.headersSent) res.writeHead(500, SECURITY);
    res.end();
  });
});

function listen(host) {
  server.once("error", (e) => {
    if (host === "::" && (e.code === "EAFNOSUPPORT" || e.code === "EADDRNOTAVAIL")) {
      listen("0.0.0.0");
      return;
    }
    log({ level: "error", msg: "listen failed", error: e.message });
    process.exit(1);
  });
  server.listen(PORT, host, () => log({ level: "info", msg: "dashboard listening", host, port: PORT, root: ROOT }));
}

for (const sig of ["SIGTERM", "SIGINT"]) {
  process.on(sig, () => server.close(() => process.exit(0)));
}
listen("::");
