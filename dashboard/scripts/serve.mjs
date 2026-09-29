// Serves the static export (out/) for a local look. Loopback only, GET/HEAD only, and no path
// escapes out/. Production hosting is any static host (the site has no server code).
import http from "node:http";
import { readFile, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(fileURLToPath(new URL("../out/", import.meta.url)));
const port = Number(process.env.PORT ?? 3000);
const types = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript",
  ".css": "text/css",
  ".json": "application/json",
  ".txt": "text/plain; charset=utf-8",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
  ".woff2": "font/woff2",
};

http
  .createServer(async (req, res) => {
    if (req.method !== "GET" && req.method !== "HEAD") return res.writeHead(405).end();
    let rel;
    try {
      rel = decodeURIComponent(new URL(req.url ?? "/", "http://localhost").pathname);
    } catch {
      return res.writeHead(400).end();
    }
    let file = path.resolve(root, "." + rel);
    if (file !== root && !file.startsWith(root + path.sep)) return res.writeHead(403).end();
    try {
      if ((await stat(file)).isDirectory()) file = path.join(file, "index.html");
      const body = await readFile(file);
      res.writeHead(200, { "content-type": types[path.extname(file)] ?? "application/octet-stream", "x-content-type-options": "nosniff" });
      res.end(req.method === "HEAD" ? undefined : body);
    } catch {
      res.writeHead(404, { "content-type": "text/plain" }).end("not found");
    }
  })
  .listen(port, "127.0.0.1", () => console.log(`serving ${root} on http://127.0.0.1:${port}`));
