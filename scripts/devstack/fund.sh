#!/usr/bin/env bash
# Local dev wallet funding: airdrop SOL on the local fork (never mainnet).
#   scripts/devstack/fund.sh <pubkey> [sol=10]
source "$(dirname "$0")/lib.sh"

[[ $# -ge 1 ]] || die "usage: fund.sh <pubkey> [sol]"
[[ -x "$TOOL_BIN" ]] || die "build the stack first (up.sh)"
"$TOOL_BIN" --rpc "$RPC_URL" fund "$1" "${2:-10}"
