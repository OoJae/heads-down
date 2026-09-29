#!/usr/bin/env python3
"""Pull and cache the ORE history the Cost Forecaster backtest needs.

Stdlib only (runs on the system python3.9 without the venv).

Sources (all public, no auth):
  api.ore.com
    /stats/history          hourly snapshots (price, production_cost in USD) + hourly mining
                            aggregates. The endpoint only serves the last ~7 days, so every run
                            MERGES into the cached copy: the archive grows each time you run it.
    /stats/revenue-24h      protocol revenue (lamports), appended to a log with the fetch time.
    /events/motherlode      every Motherlode hit (paged, 100 per page).
    /events/reset           one row per ORE round (paged, 100 per page): winning square, total
                            deployed, deployed on the winning square, protocol fee (vaulted),
                            SOL returned, ORE minted, Motherlode payout, split/solo.
    /round/{id}/miners      top-12 miners of a round with their tile masks (optional sample).
    /market                 current ORE and SOL USD prices.
  api.geckoterminal.com
    ORE/SOL Orca whirlpool hourly OHLCV, quoted in SOL (currency=token) and in USD.
    Used as the "buy at market" price series and to convert between SOL and USD.

Everything lands in ml/forecaster/data/ (gitignored). `--make-sample` writes the small
committed sample in data/sample/ (<50 KB) from the cache.

Politeness: one request at a time, a fixed sleep between requests (default 1.0 s),
exponential backoff on 429/5xx, and a descriptive User-Agent.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import os
import sys
import time
import urllib.error
import urllib.request
from typing import Any, Dict, Iterable, List, Optional

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
SAMPLE = os.path.join(DATA, "sample")

ORE_API = "https://api.ore.com"
GECKO_API = "https://api.geckoterminal.com/api/v2"
# Deepest ORE/SOL pool listed by api.ore.com/market (Orca whirlpool).
ORE_SOL_POOL = "27ExzqiGapKFd6NhffapRfdSkuykTVUqY5qeuNnrzBNm"
DEFAULT_RPC = "https://api.mainnet-beta.solana.com"
BOARD_ADDRESS = "BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi"
TREASURY_ADDRESS = "45db2FSR4mcXdSVVZbKbwojU6uYDpMyhpEi7cC8nHaWG"
ORE_PROGRAM = "oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv"
USER_AGENT = "HeadsDown-forecaster/0.1 (+research backtest; polite fetcher)"

# ORE sentinel pubkey written into Round.top_miner when the +1 ORE is split pro-rata.
SPLIT_ADDRESS_B58 = "SpLiT11111111111111111111111111111111111112"
U64_MAX = (1 << 64) - 1

RESET_FIELDS = [
    "round_id", "ts", "start_slot", "end_slot", "winning_square", "is_split", "num_winners",
    "motherlode", "total_deployed", "total_vaulted", "total_winnings", "total_minted",
    "deployed_winning_square", "top_miner",
]
MOTHERLODE_FIELDS = RESET_FIELDS


# --------------------------------------------------------------------------- helpers

_B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"


def b58decode(s: str) -> bytes:
    n = 0
    for ch in s:
        n = n * 58 + _B58.index(ch)
    raw = n.to_bytes((n.bit_length() + 7) // 8, "big") if n else b""
    pad = len(s) - len(s.lstrip("1"))
    return b"\x00" * pad + raw


def b58encode(b: bytes) -> str:
    n = int.from_bytes(b, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = _B58[r] + out
    pad = len(b) - len(b.lstrip(b"\x00"))
    return "1" * pad + out


SPLIT_ADDRESS = b58decode(SPLIT_ADDRESS_B58)
assert len(SPLIT_ADDRESS) == 32


class Fetcher:
    def __init__(self, sleep: float = 1.0, retries: int = 6, timeout: float = 60.0, verbose: bool = True):
        self.sleep = sleep
        self.retries = retries
        self.timeout = timeout
        self.verbose = verbose
        self._last = 0.0
        self.requests = 0

    def get_json(self, url: str) -> Any:
        backoff = max(self.sleep, 1.0)
        for attempt in range(self.retries):
            wait = self._last + self.sleep - time.time()
            if wait > 0:
                time.sleep(wait)
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    body = resp.read()
                self._last = time.time()
                self.requests += 1
                return json.loads(body)
            except urllib.error.HTTPError as e:
                self._last = time.time()
                if e.code in (429, 500, 502, 503, 504):
                    retry_after = e.headers.get("Retry-After") if e.headers else None
                    delay = float(retry_after) if retry_after and retry_after.isdigit() else backoff
                    log(f"  HTTP {e.code} on {url}; retry {attempt + 1}/{self.retries} in {delay:.0f}s")
                    time.sleep(delay)
                    backoff = min(backoff * 2, 120)
                    continue
                raise
            except (urllib.error.URLError, TimeoutError, ConnectionError, json.JSONDecodeError) as e:
                self._last = time.time()
                log(f"  {type(e).__name__} on {url}: {e}; retry {attempt + 1}/{self.retries} in {backoff:.0f}s")
                time.sleep(backoff)
                backoff = min(backoff * 2, 120)
        raise RuntimeError(f"giving up on {url} after {self.retries} attempts")


    def post_json(self, url: str, payload: Any) -> Any:
        wait = self._last + self.sleep - time.time()
        if wait > 0:
            time.sleep(wait)
        req = urllib.request.Request(url, data=json.dumps(payload).encode(), method="POST",
                                     headers={"User-Agent": USER_AGENT, "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            body = resp.read()
        self._last = time.time()
        self.requests += 1
        return json.loads(body)


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def iso(ts: float) -> str:
    return dt.datetime.fromtimestamp(ts, tz=dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_since(s: str) -> int:
    d = dt.datetime.fromisoformat(s)
    if d.tzinfo is None:
        d = d.replace(tzinfo=dt.timezone.utc)
    return int(d.timestamp())


def write_csv(path: str, fields: List[str], rows: Iterable[Dict[str, Any]]) -> int:
    tmp = f"{path}.{os.getpid()}.tmp"
    n = 0
    with open(tmp, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            w.writerow(r)
            n += 1
    os.replace(tmp, path)
    return n


def read_csv(path: str) -> List[Dict[str, str]]:
    if not os.path.exists(path):
        return []
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def dump_json(path: str, obj: Any) -> None:
    tmp = f"{path}.{os.getpid()}.tmp"
    with open(tmp, "w") as f:
        json.dump(obj, f, separators=(",", ":"))
    os.replace(tmp, path)


# --------------------------------------------------------------------------- parsing

def parse_round_event(item: Any) -> Optional[Dict[str, Any]]:
    """api.ore.com returns [signature_bytes, ResetEvent]. Keep only the event fields we use."""
    ev = item[1] if isinstance(item, list) and len(item) == 2 else item
    if not isinstance(ev, dict) or "round_id" not in ev:
        return None
    top = bytes(ev.get("top_miner") or [])
    ws = int(ev["winning_square"])
    return {
        "round_id": int(ev["round_id"]),
        "ts": int(ev["ts"]),
        "start_slot": int(ev["start_slot"]),
        "end_slot": int(ev["end_slot"]),
        # u64::MAX means "no entropy value, every lamport refunded".
        "winning_square": -1 if ws == U64_MAX else ws,
        "is_split": int(top == SPLIT_ADDRESS),
        "num_winners": int(ev.get("num_winners", 0)),
        "motherlode": int(ev["motherlode"]),
        "total_deployed": int(ev["total_deployed"]),
        "total_vaulted": int(ev["total_vaulted"]),
        "total_winnings": int(ev["total_winnings"]),
        "total_minted": int(ev["total_minted"]),
        "deployed_winning_square": int(ev["deployed_winning_square"]),
        "top_miner": b58encode(top) if len(top) == 32 else "",
    }


# --------------------------------------------------------------------------- endpoints

def fetch_stats_history(f: Fetcher) -> None:
    path = os.path.join(DATA, "stats_history.json")
    new = f.get_json(f"{ORE_API}/stats/history")
    old = {"snapshots": [], "mining": []}
    if os.path.exists(path):
        with open(path) as fh:
            old = json.load(fh)
    merged = {}
    for key in ("snapshots", "mining"):
        by_ts = {r["ts"]: r for r in old.get(key, [])}
        added = 0
        for r in new.get(key, []):
            if r["ts"] not in by_ts:
                added += 1
            by_ts[r["ts"]] = r
        merged[key] = [by_ts[k] for k in sorted(by_ts)]
        log(f"stats/history {key}: {len(merged[key])} rows ({added} new)")
    dump_json(path, merged)
    write_csv(os.path.join(DATA, "stats_snapshots.csv"),
              ["ts", "price", "production_cost", "volume_24h", "staking_apy", "staking_balance",
               "staking_store_balance", "circulating_supply", "liquidity", "holders"], merged["snapshots"])
    write_csv(os.path.join(DATA, "stats_mining.csv"), ["ts", "avg_deployed", "avg_miners"], merged["mining"])


def fetch_revenue_and_market(f: Fetcher) -> None:
    rev = f.get_json(f"{ORE_API}/stats/revenue-24h")
    path = os.path.join(DATA, "revenue_24h_log.csv")
    rows = read_csv(path)
    rows.append({"fetched_at": iso(time.time()), "revenue_24h_lamports": int(rev)})
    write_csv(path, ["fetched_at", "revenue_24h_lamports"], rows)
    market = f.get_json(f"{ORE_API}/market")
    market.pop("windows", None)  # liquidity-depth ladder, large and not used here
    dump_json(os.path.join(DATA, "market_latest.json"), market)
    log(f"revenue-24h {int(rev) / 1e9:.1f} SOL; market ORE ${market.get('active_price_usd'):.2f} "
        f"SOL ${market.get('sol_price_usd'):.2f}")


def fetch_motherlode(f: Fetcher, max_pages: int = 50) -> None:
    rows: Dict[int, Dict[str, Any]] = {}
    for page in range(max_pages):
        items = f.get_json(f"{ORE_API}/events/motherlode?page={page}&limit=100")
        if not items:
            break
        for it in items:
            r = parse_round_event(it)
            if r:
                rows[r["round_id"]] = r
        log(f"motherlode page {page}: {len(items)} events, oldest round {min(rows)} ({iso(rows[min(rows)]['ts'])})")
        if len(items) < 100:
            break
    old = {int(r["round_id"]): r for r in read_csv(os.path.join(DATA, "motherlode_events.csv"))}
    old.update(rows)
    n = write_csv(os.path.join(DATA, "motherlode_events.csv"), MOTHERLODE_FIELDS,
                  (old[k] for k in sorted(old)))
    log(f"motherlode_events.csv: {n} hits")


def fetch_resets(f: Fetcher, since_ts: int, max_pages: int) -> None:
    """Page /events/reset (newest first) back to `since_ts`, resuming from the cache.

    Pages shift as new rounds arrive, so rows are de-duplicated by round_id.
      head: walk from page 0 until the page overlaps the newest cached round;
      tail: if the cache does not reach `since_ts`, jump to the page holding the oldest cached
            round (one page early, for overlap) and keep paging until older than `since_ts`.
    """
    path = os.path.join(DATA, "reset_events.csv")
    cache: Dict[int, Dict[str, Any]] = {int(r["round_id"]): r for r in read_csv(path)}
    fresh: Dict[int, Dict[str, Any]] = {}

    def save() -> None:
        allrows = dict(cache)
        allrows.update(fresh)
        write_csv(path, RESET_FIELDS, (allrows[k] for k in sorted(allrows)))

    def get_page(page: int):
        items = f.get_json(f"{ORE_API}/events/reset?page={page}&limit=100")
        parsed = [r for r in (parse_round_event(it) for it in items or []) if r]
        for r in parsed:
            fresh[r["round_id"]] = r
        return parsed

    pages_done = 0
    newest_cached = max(cache) if cache else None
    oldest_cached = min(cache) if cache else None
    cache_reaches_cutoff = bool(cache) and int(cache[oldest_cached]["ts"]) <= since_ts

    # head
    page, latest_round, done = 0, None, False
    while pages_done < max_pages:
        parsed = get_page(page)
        pages_done += 1
        if not parsed:
            done = True
            break
        lo = min(r["round_id"] for r in parsed)
        if latest_round is None:
            latest_round = max(r["round_id"] for r in parsed)
        if page < 2 or pages_done % 10 == 0:
            log(f"reset page {page} [head]: oldest round {lo} {iso(min(r['ts'] for r in parsed))} "
                f"({len(fresh)} fetched, {f.requests} requests)")
        if pages_done % 25 == 0:
            save()
        if min(r["ts"] for r in parsed) < since_ts:
            done = True
            break
        if newest_cached is not None and lo <= newest_cached:
            break
        page += 1

    # tail
    if not done and cache and not cache_reaches_cutoff and latest_round is not None:
        page = max(0, (latest_round - oldest_cached) // 100 - 1)
        log(f"extending cache below round {oldest_cached} from page {page}")
        while pages_done < max_pages:
            parsed = get_page(page)
            pages_done += 1
            if not parsed:
                break
            lo_ts = min(r["ts"] for r in parsed)
            if pages_done % 10 == 0:
                log(f"reset page {page} [tail]: oldest round {min(r['round_id'] for r in parsed)} "
                    f"{iso(lo_ts)} ({len(fresh)} fetched, {f.requests} requests)")
            if pages_done % 25 == 0:
                save()
            if lo_ts < since_ts:
                break
            page += 1
    if pages_done >= max_pages:
        log(f"stopped at --max-pages={max_pages}; re-run to continue")
    save()
    ids = sorted(int(r["round_id"]) for r in read_csv(path))
    gaps = sum(1 for a, b in zip(ids, ids[1:]) if b != a + 1)
    log(f"reset_events.csv: {len(ids)} rounds {ids[0]}..{ids[-1]}, {gaps} gaps")


def fetch_gecko(f: Fetcher, since_ts: int, currency: str) -> None:
    """Hourly OHLCV of the ORE/SOL pool. currency=token -> price in SOL; currency=usd -> USD."""
    name = "ore_sol_1h.csv" if currency == "token" else "ore_usd_1h.csv"
    path = os.path.join(DATA, name)
    rows: Dict[int, Dict[str, Any]] = {int(r["ts"]): r for r in read_csv(path)}
    before = None
    for _ in range(20):
        url = (f"{GECKO_API}/networks/solana/pools/{ORE_SOL_POOL}/ohlcv/hour?aggregate=1&limit=1000"
               f"&currency={currency}")
        if before:
            url += f"&before_timestamp={before}"
        d = f.get_json(url)
        lst = d.get("data", {}).get("attributes", {}).get("ohlcv_list", [])
        if not lst:
            break
        for ts, o, h, l, c, v in lst:
            rows[int(ts)] = {"ts": int(ts), "open": o, "high": h, "low": l, "close": c, "volume_usd": v}
        oldest = min(int(x[0]) for x in lst)
        log(f"gecko {currency}: {len(lst)} candles, oldest {iso(oldest)}")
        if oldest <= since_ts or len(lst) < 1000:
            break
        before = oldest
    n = write_csv(path, ["ts", "open", "high", "low", "close", "volume_usd"], (rows[k] for k in sorted(rows)))
    log(f"{name}: {n} hourly candles")


def fetch_round_miners(f: Fetcher, n: int) -> None:
    """Sample /round/{id}/miners (top-12 miners + tile masks) for evenly spaced cached rounds."""
    resets = read_csv(os.path.join(DATA, "reset_events.csv"))
    if not resets or n <= 0:
        return
    path = os.path.join(DATA, "round_miners_sample.jsonl")
    have = set()
    if os.path.exists(path):
        with open(path) as fh:
            have = {json.loads(line)["round_id"] for line in fh if line.strip()}
    ids = [int(r["round_id"]) for r in resets]
    step = max(1, len(ids) // n)
    wanted = [i for i in ids[::step] if i not in have][:n]
    with open(path, "a") as fh:
        for k, rid in enumerate(wanted):
            try:
                d = f.get_json(f"{ORE_API}/round/{rid}/miners")
            except Exception as e:  # sample is best-effort
                log(f"round {rid} miners: {e}")
                continue
            fh.write(json.dumps({"round_id": rid, "miners": [
                {"total_deployed": m.get("total_deployed"), "combined_mask": m.get("combined_mask"),
                 "rewards": m.get("rewards")} for m in d.get("miners", [])]}) + "\n")
            if k % 25 == 0:
                log(f"round miners {k}/{len(wanted)}")


def fetch_chain(f: Fetcher, rpc: str) -> None:
    """Snapshot ORE's Board and Treasury accounts (one getMultipleAccounts call).

    Board    = disc(8) round_id start_slot end_slot production_cost_ema     (u64 LE)
    Treasury = disc(8) motherlode rewards_factor(16) total_refined total_unclaimed
    Used to check the EMA / Motherlode-pot reconstruction against live chain state.
    """
    import base64
    import struct

    res = f.post_json(rpc, {"jsonrpc": "2.0", "id": 1, "method": "getMultipleAccounts",
                            "params": [[BOARD_ADDRESS, TREASURY_ADDRESS],
                                       {"encoding": "base64", "commitment": "confirmed"}]})
    slot = res["result"]["context"]["slot"]
    board_acc, treas_acc = res["result"]["value"]
    for acc, name, size in ((board_acc, "Board", 40), (treas_acc, "Treasury", 48)):
        if acc is None or acc.get("owner") != ORE_PROGRAM:
            raise RuntimeError(f"{name} account missing or not owned by the ORE program")
        if len(base64.b64decode(acc["data"][0])) != size:
            raise RuntimeError(f"{name} account has an unexpected size (layout changed?)")
    b = base64.b64decode(board_acc["data"][0])
    t = base64.b64decode(treas_acc["data"][0])
    round_id, start_slot, end_slot, ema = struct.unpack_from("<4Q", b, 8)
    motherlode = struct.unpack_from("<Q", t, 8)[0]
    total_refined, total_unclaimed = struct.unpack_from("<2Q", t, 32)
    row = {"fetched_at": iso(time.time()), "slot": slot, "round_id": round_id, "start_slot": start_slot,
           "end_slot": end_slot, "production_cost_ema": ema, "motherlode": motherlode,
           "total_refined": total_refined, "total_unclaimed": total_unclaimed}
    path = os.path.join(DATA, "chain_snapshots.csv")
    rows = read_csv(path) + [row]
    write_csv(path, list(row), rows)
    log(f"chain @slot {slot}: round {round_id}, production_cost_ema {ema / 1e9:.4f} SOL/ORE, "
        f"motherlode {motherlode / 1e11:.1f} ORE, unrefined {total_unclaimed / 1e11:.0f} ORE")


# --------------------------------------------------------------------------- sample

def make_sample(n_rounds: int = 300) -> None:
    """Write the small committed sample (<50 KB) used by tests and `--sample` runs."""
    os.makedirs(SAMPLE, exist_ok=True)
    resets = read_csv(os.path.join(DATA, "reset_events.csv"))
    if not resets:
        raise SystemExit("no cached reset_events.csv; run fetch.py first")
    tail = resets[-n_rounds:]
    fields = [k for k in RESET_FIELDS if k != "top_miner"]
    write_csv(os.path.join(SAMPLE, "reset_events_sample.csv"), fields, tail)
    lo_ts = int(tail[0]["ts"]) - 3600
    hi_ts = int(tail[-1]["ts"]) + 3600
    ml = read_csv(os.path.join(DATA, "motherlode_events.csv"))
    write_csv(os.path.join(SAMPLE, "motherlode_events_sample.csv"), fields, ml[-20:])
    gecko = [r for r in read_csv(os.path.join(DATA, "ore_sol_1h.csv")) if lo_ts - 86400 <= int(r["ts"]) <= hi_ts]
    write_csv(os.path.join(SAMPLE, "ore_sol_1h_sample.csv"), ["ts", "open", "high", "low", "close", "volume_usd"], gecko)
    snaps = read_csv(os.path.join(DATA, "stats_snapshots.csv"))[-48:]
    write_csv(os.path.join(SAMPLE, "stats_snapshots_sample.csv"),
              ["ts", "price", "production_cost", "volume_24h", "staking_apy", "circulating_supply", "liquidity"],
              snaps)
    total = sum(os.path.getsize(os.path.join(SAMPLE, x)) for x in os.listdir(SAMPLE))
    log(f"sample written to {SAMPLE}: {total / 1024:.1f} KB")
    if total > 50 * 1024:
        raise SystemExit("sample exceeds 50 KB; lower n_rounds")


# --------------------------------------------------------------------------- main

def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--since", default="2026-08-12T00:00:00",
                    help="oldest round to fetch (UTC ISO). Default: start of the no-parimutuel regime.")
    ap.add_argument("--sleep", type=float, default=1.0, help="seconds between requests (be polite)")
    ap.add_argument("--max-pages", type=int, default=2000, help="cap on /events/reset pages per run")
    ap.add_argument("--miners-sample", type=int, default=0, help="also sample N /round/{id}/miners")
    ap.add_argument("--rpc", default=os.environ.get("SOLANA_RPC_URL", DEFAULT_RPC),
                    help="Solana RPC for the Board/Treasury snapshot (read-only)")
    ap.add_argument("--only", choices=["stats", "motherlode", "resets", "gecko", "miners", "chain"], nargs="*",
                    help="fetch only these sources")
    ap.add_argument("--make-sample", action="store_true", help="write data/sample/ from the cache and exit")
    args = ap.parse_args(argv)

    os.makedirs(DATA, exist_ok=True)
    if args.make_sample:
        make_sample()
        return 0
    since = parse_since(args.since)
    only = set(args.only or ["stats", "motherlode", "resets", "gecko", "chain"])
    f = Fetcher(sleep=args.sleep)
    t0 = time.time()
    if "stats" in only:
        fetch_stats_history(f)
        fetch_revenue_and_market(f)
    if "motherlode" in only:
        fetch_motherlode(f)
    if "gecko" in only:
        # GeckoTerminal's free tier allows ~30 calls/min; keep >= 2 s between its calls.
        g = Fetcher(sleep=max(args.sleep, 2.5))
        fetch_gecko(g, since, "token")
        fetch_gecko(g, since, "usd")
    if "resets" in only:
        fetch_resets(f, since, args.max_pages)
    if "chain" in only:
        fetch_chain(f, args.rpc)
    if "miners" in only or args.miners_sample:
        fetch_round_miners(f, args.miners_sample)
    log(f"done: {f.requests} requests in {time.time() - t0:.0f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
