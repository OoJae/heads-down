#!/usr/bin/env bash
# Boot a throwaway solana-test-validator (Agave CLI) with the spike program
# and a forged instructions-sysvar account preloaded, then run check.mjs.
# Uses non-default ports so it does not collide with another local validator.
set -euo pipefail
cd "$(dirname "$0")"

AGAVE_BIN="$HOME/.local/share/solana/install/active_release/bin"
command -v solana-test-validator >/dev/null 2>&1 || export PATH="$AGAVE_BIN:$PATH"

SO=../target/deploy/p256_spike.so
KEYPAIR=../target/deploy/p256_spike-keypair.json
[ -f "$SO" ] || cargo-build-sbf --manifest-path ../program/Cargo.toml
[ -d node_modules ] || pnpm install --silent

RPC_PORT=${RPC_PORT:-18899}
WORK=$(mktemp -d "${TMPDIR:-/tmp}/p256-validator.XXXXXX")
PROGRAM_ID=$(solana address -k "$KEYPAIR")
node check.mjs forge "$WORK/fake-sysvar.json" "$WORK/fixture.json" >/dev/null
FAKE=$(node -e "console.log(JSON.parse(require('fs').readFileSync('$WORK/fake-sysvar.json')).pubkey)")

solana-test-validator --reset --quiet --ledger "$WORK/ledger" \
  --rpc-port "$RPC_PORT" --faucet-port $((RPC_PORT + 1001)) \
  --gossip-port $((RPC_PORT - 899)) --dynamic-port-range $((RPC_PORT - 799))-$((RPC_PORT - 699)) \
  --bpf-program "$PROGRAM_ID" "$SO" \
  --account "$FAKE" "$WORK/fake-sysvar.json" >"$WORK/validator.log" 2>&1 &
VALIDATOR=$!
trap 'kill $VALIDATOR 2>/dev/null; wait $VALIDATOR 2>/dev/null; rm -rf "$WORK"' EXIT

URL="http://127.0.0.1:$RPC_PORT"
for _ in $(seq 1 60); do
  solana cluster-version -u "$URL" >/dev/null 2>&1 && break
  sleep 1
done
echo "validator: $(solana cluster-version -u "$URL")  program: $PROGRAM_ID"
solana feature status -u "$URL" srremy31J5Y25FrAApwVb9kZcfXbusYMMsvTK9aWv5q 2>&1 | grep -i srremy || true

RPC_URL="$URL" PROGRAM_ID="$PROGRAM_ID" node check.mjs run "$WORK/fixture.json"
