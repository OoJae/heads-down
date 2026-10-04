#!/usr/bin/env python3
"""A local JSON-RPC proxy that rate-limits sendTransaction the way a metered provider does.

    scripts/devstack/rate-limit-proxy.py --listen 127.0.0.1:38898 --upstream http://127.0.0.1:38899 \
        --sends-per-second 1

Every request is passed on to the upstream RPC (the local validator), except that a
`sendTransaction` arriving less than 1 / --sends-per-second after the last one that was let
through is answered with HTTP 429 and the JSON-RPC body Helius documents for its rate limits
(code -32005, "Too many requests"). `GET /stats` answers how many sends were let through and
how many were refused.

It exists for scripts/mainnet/dry-run.sh, so that a deploy can be rehearsed against the limit of
Helius' free plan (one sendTransaction a second) without Helius. It is loopback-only and keeps
nothing; it is not a model of anything else Helius does (credits, the 10 requests a second
overall, bursts).
"""
import argparse
import json
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REFUSAL = {"code": -32005, "message": "Too many requests"}


class Limiter:
    def __init__(self, per_second):
        self.gap = 1.0 / per_second
        self.last = None
        self.passed = 0
        self.refused = 0
        self.lock = threading.Lock()

    def take(self):
        """True if a send may pass now."""
        with self.lock:
            now = time.monotonic()
            if self.last is not None and now - self.last < self.gap:
                self.refused += 1
                return False
            self.last = now
            self.passed += 1
            return True

    def stats(self):
        with self.lock:
            return {"sends_passed": self.passed, "sends_refused": self.refused, "sends_per_second": 1.0 / self.gap}


def handler(upstream, limiter):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def answer(self, status, body, content_type="application/json"):
            self.send_response(status)
            self.send_header("content-type", content_type)
            self.send_header("content-length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/stats":
                self.answer(200, json.dumps(limiter.stats()).encode())
            else:
                self.answer(404, b"{}")

        def do_POST(self):
            body = self.rfile.read(int(self.headers.get("content-length", "0")))
            try:
                request = json.loads(body)
            except ValueError:
                request = None
            if isinstance(request, dict) and request.get("method") == "sendTransaction" and not limiter.take():
                refusal = {"jsonrpc": "2.0", "error": REFUSAL, "id": request.get("id")}
                self.answer(429, json.dumps(refusal).encode())
                return
            forward = urllib.request.Request(upstream, data=body, headers={"content-type": "application/json"})
            try:
                with urllib.request.urlopen(forward, timeout=60) as response:
                    self.answer(response.status, response.read(), response.headers.get("content-type", "application/json"))
            except urllib.error.HTTPError as e:
                self.answer(e.code, e.read())
            except OSError as e:
                self.answer(502, json.dumps({"jsonrpc": "2.0", "error": {"code": -32000, "message": f"upstream: {e}"}, "id": None}).encode())

        def log_message(self, *_):
            pass

    return Handler


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        # A client that drops its connection (the Solana CLI does when it exits) is not an error.
        if isinstance(sys.exc_info()[1], (ConnectionError, TimeoutError)):
            return
        super().handle_error(request, client_address)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--listen", default="127.0.0.1:38898")
    parser.add_argument("--upstream", default="http://127.0.0.1:38899")
    parser.add_argument("--sends-per-second", type=float, default=1.0)
    args = parser.parse_args()
    host, _, port = args.listen.rpartition(":")
    if host not in ("127.0.0.1", "localhost", "::1"):
        sys.exit("--listen must be a loopback address")
    if args.sends_per_second <= 0:
        sys.exit("--sends-per-second must be above 0")
    server = Server((host, int(port)), handler(args.upstream, Limiter(args.sends_per_second)))
    print(f"rate-limit-proxy: {args.listen} -> {args.upstream}, {args.sends_per_second} sendTransaction a second", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
