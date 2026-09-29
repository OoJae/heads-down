#!/usr/bin/env python3
"""Fetch real Seeker Genesis Token (SGT) accounts from mainnet as test fixtures.

For every SGT mint given on the command line (or the defaults below) this
script writes, under ``fixtures/<label>/``:

  mint.json           the Token-2022 mint account (raw base64 bytes)
  token_account.json  the token account currently holding that SGT

plus ``fixtures/group.json`` (the SGT collection mint ``GT22s89…``) and
``fixtures/manifest.json``. The manifest records what the RPC's *own*
Token-2022 parser (``jsonParsed``) says about each account: member number,
holder, token-account state. The Rust tests use it as an independent oracle,
so the crate's hand-written parser is checked against spl-token-2022's.

Holder discovery: ``getTokenLargestAccounts`` first. The public RPC
rate-limits that call hard, so on failure we fall back to scanning the mint's
recent transactions for a post-token-balance of exactly 1, then re-read that
account to confirm it still holds the SGT *now*.

Usage:
    python3 scripts/fetch_fixtures.py [MINT ...]
    RPC_URL=https://... python3 scripts/fetch_fixtures.py

Only standard-library modules are used (Python 3.9+).
"""

import base64
import json
import os
import struct
import sys
import time
import urllib.error
import urllib.request

RPC_URL = os.environ.get("RPC_URL", "https://api.mainnet-beta.solana.com")
TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
SGT_GROUP = "GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te"

# member 20 (the example from Solana Mobile's docs) and a freshly issued SGT.
DEFAULT_MINTS = [
    "5mXbkqKz883aufhAsx3p5Z1NcvD2ppZbdTTznM6oUKLj",
    "5pWbRnGUS84m3mP1XBMryXjKBTn9EdMUi4a5dPZLVfnp",
]

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURES = os.path.join(os.path.dirname(HERE), "fixtures")


class RpcError(Exception):
    pass


def rpc(method, params, retries=6):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    delay = 1.5
    for attempt in range(retries):
        req = urllib.request.Request(
            RPC_URL, data=body, headers={"Content-Type": "application/json"}
        )
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                out = json.load(resp)
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503, 504) and attempt + 1 < retries:
                time.sleep(delay)
                delay *= 2
                continue
            raise RpcError(f"{method}: HTTP {e.code}") from e
        if "error" in out:
            code = out["error"].get("code")
            if code == 429 and attempt + 1 < retries:
                time.sleep(delay)
                delay *= 2
                continue
            raise RpcError(f"{method}: {out['error']}")
        return out["result"]
    raise RpcError(f"{method}: retries exhausted")


def get_account(address, encoding):
    res = rpc("getAccountInfo", [address, {"encoding": encoding, "commitment": "finalized"}])
    if res["value"] is None:
        raise RpcError(f"account {address} not found")
    return res["context"]["slot"], res["value"]


def raw_fixture(address):
    slot, v = get_account(address, "base64")
    return {
        "source": f"mainnet-beta getAccountInfo (finalized) via {RPC_URL}",
        "slot": slot,
        "pubkey": address,
        "account": {
            "lamports": v["lamports"],
            "owner": v["owner"],
            "executable": v["executable"],
            "rentEpoch": v["rentEpoch"],
            "space": v.get("space", len(base64.b64decode(v["data"][0]))),
            "data": v["data"],
        },
    }


def holds_sgt_now(token_account, mint):
    """Re-read `token_account` raw and check mint field + amount == 1."""
    _, v = get_account(token_account, "base64")
    if v["owner"] != TOKEN_2022:
        return False
    data = base64.b64decode(v["data"][0])
    if len(data) < 165:
        return False
    return b58encode(data[0:32]) == mint and struct.unpack_from("<Q", data, 64)[0] == 1


def find_holder(mint):
    try:
        res = rpc("getTokenLargestAccounts", [mint, {"commitment": "finalized"}], retries=3)
        for entry in res["value"]:
            if entry["amount"] == "1":
                return entry["address"]
    except RpcError as e:
        print(f"  getTokenLargestAccounts unavailable ({e}); scanning transactions", file=sys.stderr)

    sigs = rpc("getSignaturesForAddress", [mint, {"limit": 25}])
    for s in sigs:
        if s.get("err") is not None:
            continue
        tx = rpc(
            "getTransaction",
            [s["signature"], {"encoding": "json", "maxSupportedTransactionVersion": 0}],
        )
        if tx is None:
            continue
        keys = list(tx["transaction"]["message"]["accountKeys"])
        loaded = tx["meta"].get("loadedAddresses") or {}
        keys += loaded.get("writable", []) + loaded.get("readonly", [])
        for bal in tx["meta"].get("postTokenBalances") or []:
            if bal["mint"] == mint and bal["uiTokenAmount"]["amount"] == "1":
                candidate = keys[bal["accountIndex"]]
                if holds_sgt_now(candidate, mint):
                    return candidate
    raise RpcError(f"no current holder found for {mint}")


# --- tiny base58 encoder so the script has no third-party dependencies ------
_B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"


def b58encode(raw):
    n = int.from_bytes(raw, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = _B58[r] + out
    pad = len(raw) - len(raw.lstrip(b"\0"))
    return "1" * pad + out


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")
    print(f"  wrote {os.path.relpath(path, os.path.dirname(HERE))} ({os.path.getsize(path)} bytes)")


def main(mints):
    manifest = {"rpc": RPC_URL, "group": SGT_GROUP, "sgts": []}

    print(f"group {SGT_GROUP}")
    write_json(os.path.join(FIXTURES, "group.json"), raw_fixture(SGT_GROUP))

    for mint in mints:
        print(f"mint {mint}")
        _, parsed_mint = get_account(mint, "jsonParsed")
        info = parsed_mint["data"]["parsed"]["info"]
        member = next(
            e["state"] for e in info.get("extensions", []) if e["extension"] == "tokenGroupMember"
        )
        label = f"member-{member['memberNumber']}"

        holder_account = find_holder(mint)
        _, parsed_ta = get_account(holder_account, "jsonParsed")
        ta_info = parsed_ta["data"]["parsed"]["info"]

        write_json(os.path.join(FIXTURES, label, "mint.json"), raw_fixture(mint))
        write_json(os.path.join(FIXTURES, label, "token_account.json"), raw_fixture(holder_account))

        manifest["sgts"].append(
            {
                "label": label,
                "mint": mint,
                "token_account": holder_account,
                # Everything below is what the RPC's spl-token-2022 parser reports.
                "holder": ta_info["owner"],
                "member_number": member["memberNumber"],
                "member_group": member["group"],
                "token_account_state": ta_info["state"],
                "token_account_extensions": [e["extension"] for e in ta_info.get("extensions", [])],
                "mint_extensions": [e["extension"] for e in info.get("extensions", [])],
                "mint_authority": info["mintAuthority"],
                "freeze_authority": info["freezeAuthority"],
                "supply": info["supply"],
                "decimals": info["decimals"],
            }
        )
        time.sleep(0.5)

    write_json(os.path.join(FIXTURES, "manifest.json"), manifest)


if __name__ == "__main__":
    main(sys.argv[1:] or DEFAULT_MINTS)
