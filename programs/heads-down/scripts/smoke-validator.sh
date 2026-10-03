#!/usr/bin/env bash
# Deploy the built artifact to a REAL validator and exercise it there.
#
#   bash scripts/smoke-validator.sh                       # target/deploy/heads_down.so (SBPFv3)
#   HD_SMOKE_SO=target/deploy-v0/heads_down.so bash scripts/smoke-validator.sh
#   HD_SMOKE_FEATURES=all bash scripts/smoke-validator.sh # every feature active (SIMD-0500 on)
#
# The LiteSVM suite runs the same SVM, but with every feature active and without the
# loader's deploy-time checks. This starts a throwaway solana-test-validator with
# MAINNET'S feature set (--clone-feature-set, what scripts/devstack/up.sh does), deploys
# the artifact through the upgradeable loader at the real program id, and runs
# tests/examples/validator_smoke.rs: initialize_config, a rig through
# register / arm / P-256 heartbeat / end_shift, the v1.3 tombstone (close, re-register,
# next shift), close_shift_log, and the governance rotation instructions.
#
# Needs: network (the feature set is read from HD_MAINNET_RPC), the program keypair
# (outside the repo: the program id is hard-coded), and the ORE Board fixture
# (tests/fixtures; arm_shift and end_shift read it). ORE itself is not deployed: no dig.
#
# Ports: HD_SMOKE_PORT_BASE (default 36000) + 899 rpc, + 900 ws, + 910 faucet, + 1 gossip,
# + 2..40 dynamic. The validator is stopped and its ledger deleted on exit.
set -euo pipefail
export PATH="$HOME/.local/share/solana/install/active_release/bin:$PATH"
cd "$(dirname "$0")/.."
TOOLCHAIN="${HD_TOOLCHAIN:-+1.97.1}"

SO="${HD_SMOKE_SO:-target/deploy/heads_down.so}"
PROGRAM_KP="${HD_PROGRAM_KEYPAIR:-$HOME/.config/heads-down/heads_down-program-keypair.json}"
MAINNET="${HD_MAINNET_RPC:-https://api.mainnet-beta.solana.com}"
FEATURES="${HD_SMOKE_FEATURES:-clone}"
BASE="${HD_SMOKE_PORT_BASE:-36000}"
RPC_PORT=$((BASE + 899))
URL="http://127.0.0.1:$RPC_PORT"
BOARD=BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi
SIMD_0500=B8JJXCy5amZyWG9r7EnUYLwzXSXTxG7GZ1qZ1qggo83g
SBPF_V3=5cC3foj77CWun58pC51ebHFUWavHWKarWyR5UUik7dnC

[[ -f "$SO" ]] || { echo "missing $SO: run scripts/build.sh" >&2; exit 1; }
[[ -f "$PROGRAM_KP" ]] || { echo "missing the program keypair $PROGRAM_KP" >&2; exit 1; }
[[ -f "tests/fixtures/$BOARD.json" ]] || bash tests/fixtures/fetch-fixtures.sh
[[ "$(solana address -k "$PROGRAM_KP")" == HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p ]] \
  || { echo "$PROGRAM_KP is not the heads_down program keypair" >&2; exit 1; }
if lsof -nP -iTCP:"$RPC_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "port $RPC_PORT is in use: set HD_SMOKE_PORT_BASE" >&2
  exit 1
fi
SBPF="$(python3 -c 'import struct,sys; print(struct.unpack_from("<I", open(sys.argv[1],"rb").read(), 48)[0])' "$SO")"
echo "artifact: $SO, SBPFv$SBPF, $(wc -c <"$SO" | tr -d ' ') bytes, sha256 $(shasum -a 256 "$SO" | cut -d' ' -f1)"

TMP="$(mktemp -d)"
VALIDATOR_PID=""
cleanup() {
  if [[ -n "$VALIDATOR_PID" ]]; then
    kill "$VALIDATOR_PID" 2>/dev/null || true
    wait "$VALIDATOR_PID" 2>/dev/null || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

# A throwaway upgrade authority, funded at genesis.
solana-keygen new --no-bip39-passphrase --silent --outfile "$TMP/ua.json" >/dev/null
UA="$(solana address -k "$TMP/ua.json")"

case "$FEATURES" in
  clone) FEATURE_ARGS=(--clone-feature-set --url "$MAINNET") ;;
  all) FEATURE_ARGS=() ;;
  *) echo "HD_SMOKE_FEATURES must be clone (default) or all" >&2; exit 2 ;;
esac
echo "starting solana-test-validator $(solana-test-validator --version | awk '{print $2}') on $URL (features: $FEATURES)"
solana-test-validator --ledger "$TMP/ledger" --reset --quiet --bind-address 127.0.0.1 \
  --rpc-port "$RPC_PORT" --faucet-port $((BASE + 910)) --gossip-port $((BASE + 1)) \
  --dynamic-port-range "$((BASE + 2))-$((BASE + 40))" --mint "$UA" \
  ${FEATURE_ARGS[@]+"${FEATURE_ARGS[@]}"} \
  --account "$BOARD" "tests/fixtures/$BOARD.json" >"$TMP/validator.log" 2>&1 &
VALIDATOR_PID=$!
for _ in $(seq 1 120); do
  if curl -s -m 2 "$URL" -X POST -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","id":1,"method":"getHealth"}' 2>/dev/null | grep -q '"ok"'; then
    break
  fi
  kill -0 "$VALIDATOR_PID" 2>/dev/null || { cat "$TMP/validator.log" >&2; echo "the validator exited" >&2; exit 1; }
  sleep 0.5
done
curl -s -m 2 "$URL" -X POST -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"getHealth"}' | grep -q '"ok"' \
  || { cat "$TMP/validator.log" >&2; echo "the RPC did not come up" >&2; exit 1; }

echo "feature gates on this validator:"
solana -u "$URL" feature status "$SBPF_V3" "$SIMD_0500" 2>&1 | sed 's/^/  /'

echo "deploying as HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p (upgrade authority $UA)"
solana -u "$URL" program deploy --use-rpc --program-id "$PROGRAM_KP" --upgrade-authority "$TMP/ua.json" \
  --keypair "$TMP/ua.json" "$SO" 2>&1 | sed 's/^/  /'

HD_SMOKE_SBPF="$SBPF" cargo "$TOOLCHAIN" run -q -p heads-down-tests --example validator_smoke -- "$URL" "$TMP/ua.json"
