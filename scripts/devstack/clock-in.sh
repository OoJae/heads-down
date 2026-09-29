#!/usr/bin/env bash
# Arm a rig on the local fork for a PHONE's Keystore P-256 key, signed by a Mac-held dev wallet.
# The phone then only has to stream heartbeats to the crank (no wallet on the phone needed).
#
#   scripts/devstack/clock-in.sh <p256 hex, 33-byte compressed> [--lease 1-3] [--hours H]
#
# The dev wallet is $HD_DEVSTACK_KEYS/dev-wallet.json (created on first use, mode 600, airdropped).
source "$(dirname "$0")/lib.sh"

[[ $# -ge 1 ]] || die "usage: clock-in.sh <p256-hex> [--lease N] [--hours H]"
P256="$1"; shift
WALLET="$HD_DEVSTACK_KEYS/dev-wallet.json"
ensure_key "$WALLET"
"$TOOL_BIN" --rpc "$RPC_URL" clock-in --wallet "$WALLET" --p256 "$P256" "$@"
