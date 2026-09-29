#!/usr/bin/env bash
# One command: a local mainnet-fork Heads Down stack on this Mac.
#
#   scripts/devstack/up.sh [--engine test-validator|surfpool] [--refresh-fixtures] [--resume]
#                          [--with-registrar] [--no-build]
#
# 1. fork engine with the live mainnet ORE, entropy and ORE-mint programs + ORE state
# 2. heads_down built with scripts/build.sh (mainnet feature) and deployed with the
#    out-of-repo program keypair; upgrade authority = a local dev key
# 3. initialize_config + Executor PDA float
# 4. ore-round-driver (rounds keep advancing), hd-crank, indexer (real RPC mode), optional registrar
#
# See docs/DEVSTACK.md.
source "$(dirname "$0")/lib.sh"

REFRESH=0 RESUME=0 REGISTRAR=0 BUILD=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --engine) HD_DEVSTACK_ENGINE="$2"; shift 2 ;;
    --refresh-fixtures) REFRESH=1; shift ;;
    --resume) RESUME=1; shift ;;
    --with-registrar) REGISTRAR=1; shift ;;
    --no-build) BUILD=0; shift ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done
[[ "$HD_DEVSTACK_ENGINE" == "test-validator" || "$HD_DEVSTACK_ENGINE" == "surfpool" ]] || die "HD_DEVSTACK_ENGINE must be test-validator or surfpool"

# ---- preflight ----------------------------------------------------------------------------------
need solana "install the Agave CLI 4.1 (~/.local/share/solana/install/active_release/bin)"
need solana-keygen "install the Agave CLI"
need cargo "install Rust (rustup)"
need node "install Node >= 26 (brew install node)"
need pnpm "install pnpm (brew install pnpm)"
need curl "install curl"
need lsof "lsof is part of macOS"
need python3 "python3 ships with the Xcode command line tools"
if [[ "$HD_DEVSTACK_ENGINE" == "test-validator" ]]; then
  need solana-test-validator "install the Agave CLI 4.1"
else
  [[ -x "$SURFPOOL_BIN" ]] || die "surfpool not installed: run scripts/devstack/install-surfpool.sh"
fi
[[ -f "$HD_PROGRAM_KEYPAIR" ]] || die "heads_down program keypair not found at $HD_PROGRAM_KEYPAIR"
[[ "$(pubkey "$HD_PROGRAM_KEYPAIR")" == "$HD_PROGRAM_ID" ]] || die "$HD_PROGRAM_KEYPAIR is not $HD_PROGRAM_ID"
for n in validator driver crank indexer registrar; do
  is_running "$n" && die "the stack is already running ($n); run scripts/devstack/down.sh first"
done
for p in "$HD_RPC_PORT" "$HD_WS_PORT" "$HD_CRANK_PORT" "$HD_INDEXER_PORT" "$HD_FAUCET_PORT"; do
  port_busy "$p" && die "port $p is in use (lsof -nP -iTCP:$p -sTCP:LISTEN)"
done
mkdir -p "$HD_DEVSTACK_HOME" "$LOGS" "$RUN"
# Keep the previous run's logs one level down; every run starts with fresh log files.
rm -rf "$LOGS/prev" && mkdir -p "$LOGS/prev"
for f in "$LOGS"/*.log; do if [[ -e "$f" ]]; then mv "$f" "$LOGS/prev/"; fi; done

# ---- build ---------------------------------------------------------------------------------------
if [[ $BUILD == 1 || ! -f "$HD_SO" ]]; then
  log "building heads_down (programs/heads-down/scripts/build.sh, mainnet feature)"
  bash "$REPO_ROOT/programs/heads-down/scripts/build.sh" >"$LOGS/build-program.log" 2>&1 || die "program build failed: $LOGS/build-program.log"
fi
if [[ $BUILD == 1 || ! -x "$CRANK_BIN" ]]; then
  log "building hd-crank (release)"
  (cd "$REPO_ROOT/crank" && cargo build --release) >"$LOGS/build-crank.log" 2>&1 || die "crank build failed: $LOGS/build-crank.log"
fi
if [[ $BUILD == 1 || ! -x "$TOOL_BIN" ]]; then
  log "building hd-devstack (release)"
  (cd "$DEVSTACK_DIR/tool" && cargo build --release) >"$LOGS/build-tool.log" 2>&1 || die "tool build failed: $LOGS/build-tool.log"
fi
if [[ ! -d "$REPO_ROOT/services/indexer/node_modules" ]]; then
  log "installing indexer dependencies (pnpm, frozen lockfile)"
  (cd "$REPO_ROOT/services/indexer" && pnpm install --frozen-lockfile) >"$LOGS/build-indexer.log" 2>&1 || die "pnpm install failed: $LOGS/build-indexer.log"
fi

# ---- keys (outside the repo) ------------------------------------------------------------------------
for k in "$K_UPGRADE" "$K_GOVERNANCE" "$K_REGISTRAR" "$K_CRANK" "$K_DRIVER" "$K_BACKGROUND"; do ensure_key "$k"; done

# ---- engine ------------------------------------------------------------------------------------------
if [[ "$HD_DEVSTACK_ENGINE" == "test-validator" ]]; then
  if [[ $RESUME == 1 && -d "$LEDGER" ]]; then
    log "resuming the existing ledger (genesis accounts are not re-applied)"
    RESET=()
  else
    if [[ $REFRESH == 1 || ! -f "$FIXTURES/fetched.env" ]]; then bash "$DEVSTACK_DIR/fetch-mainnet.sh"; fi
    "$TOOL_BIN" genesis --fixtures "$FIXTURES" --out "$GENESIS" --entropy-secret "$ENTROPY_SECRET" \
      ${HD_ROUND_SLOTS:+--round-slots "$HD_ROUND_SLOTS"} ${HD_INTERMISSION_SLOTS:+--intermission-slots "$HD_INTERMISSION_SLOTS"}
    rm -rf "$LEDGER" "$RUN/crank-state" "$RUN/indexer-pg"
    RESET=(--reset)
  fi
  # Mirror mainnet's activated runtime features (a bare test validator activates everything,
  # including SIMD-0500, which refuses to deploy SBPFv0 programs like heads_down and ORE).
  if [[ "${HD_CLONE_FEATURES:-1}" == 1 ]]; then
    FEATURE_ARGS=(--clone-feature-set --url "$HD_MAINNET_RPC")
  else
    FEATURE_ARGS=(--deactivate-feature B8JJXCy5amZyWG9r7EnUYLwzXSXTxG7GZ1qZ1qggo83g)
  fi
  ACCOUNT_ARGS=()
  while IFS= read -r line; do ACCOUNT_ARGS+=("$line"); done <"$GENESIS/accounts.args"
  log "starting solana-test-validator $(solana-test-validator --version | awk '{print $2}') on $RPC_URL (ws $WS_URL)"
  start_bg validator "$LOGS/validator.log" solana-test-validator ${RESET[@]+"${RESET[@]}"} --quiet \
    --ledger "$LEDGER" --bind-address 127.0.0.1 --rpc-port "$HD_RPC_PORT" --faucet-port "$HD_FAUCET_PORT" \
    --gossip-port "$HD_GOSSIP_PORT" --dynamic-port-range "$HD_DYNAMIC_PORTS" "${FEATURE_ARGS[@]}" \
    --bpf-program oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv "$FIXTURES/ore.so" \
    --bpf-program 3jSkUuYBoJzQPMEzTvkDFXCZUBksPamrVhrnHR9igu2X "$FIXTURES/entropy.so" \
    --bpf-program mintzxW6Kckmeyh1h6Zfdj9QcYgCzhPSGiC8ChZ6fCx "$FIXTURES/ore_mint.so" \
    "${ACCOUNT_ARGS[@]}"
else
  # Surfpool keeps its state in memory: every start is a fresh fork of mainnet "now".
  rm -rf "$RUN/crank-state" "$RUN/indexer-pg" "$RUN/surfpool"
  mkdir -p "$RUN/surfpool"
  log "starting surfpool $("$SURFPOOL_BIN" --version | awk '{print $2}') (lazy mainnet fork) on $RPC_URL (ws $WS_URL)"
  (
    cd "$RUN/surfpool"
    start_bg validator "$LOGS/validator.log" "$SURFPOOL_BIN" start --rpc-url "$HD_MAINNET_RPC" --no-tui --no-studio \
      --no-deploy --port "$HD_RPC_PORT" --ws-port "$HD_WS_PORT" --airdrop-amount 0 --log-bytes-limit 0 \
      --log-path "$LOGS/surfpool"
  )
fi
for _ in $(seq 1 180); do
  rpc getSlot | grep -q '"result"' && break
  is_running validator || die "the engine exited: $LOGS/validator.log"
  sleep 0.5
done
rpc getSlot | grep -q '"result"' || die "RPC did not come up (see $LOGS/validator.log)"
log "RPC up at slot $(slot)"
if [[ "$HD_DEVSTACK_ENGINE" == "surfpool" ]]; then
  "$TOOL_BIN" --rpc "$RPC_URL" surgery --entropy-secret "$ENTROPY_SECRET" \
    ${HD_ROUND_SLOTS:+--round-slots "$HD_ROUND_SLOTS"} ${HD_INTERMISSION_SLOTS:+--intermission-slots "$HD_INTERMISSION_SLOTS"}
fi

# ---- heads_down ------------------------------------------------------------------------------------
UA="$(pubkey "$K_UPGRADE")"
"$TOOL_BIN" --rpc "$RPC_URL" fund "$UA" 100 >/dev/null
if rpc getAccountInfo "[\"$HD_PROGRAM_ID\",{\"encoding\":\"base64\"}]" | grep -q '"executable":true'; then
  log "heads_down already deployed at $HD_PROGRAM_ID"
else
  log "deploying heads_down ($(wc -c <"$HD_SO" | tr -d ' ') bytes, sha256 $(shasum -a 256 "$HD_SO" | cut -c1-16)…) as $HD_PROGRAM_ID, upgrade authority $UA"
  solana -u "$RPC_URL" program deploy --use-rpc --program-id "$HD_PROGRAM_KEYPAIR" --upgrade-authority "$K_UPGRADE" \
    --keypair "$K_UPGRADE" "$HD_SO" >"$LOGS/deploy.log" 2>&1 || die "deploy failed: $LOGS/deploy.log"
  wait_slots 2
fi
"$TOOL_BIN" --rpc "$RPC_URL" init --authority "$K_UPGRADE" --governance "$(pubkey "$K_GOVERNANCE")" \
  --registrar "$(pubkey "$K_REGISTRAR")" --executor-fee "$HD_EXECUTOR_FEE" --crank-fee "$HD_CRANK_FEE" \
  --executor-float "$HD_EXECUTOR_FLOAT" | tee -a "$LOGS/init.log"

# ---- ore-round-driver ------------------------------------------------------------------------------
start_bg driver "$LOGS/driver.log" "$TOOL_BIN" --rpc "$RPC_URL" driver --payer "$K_DRIVER" \
  --entropy-secret "$ENTROPY_SECRET" --background "$K_BACKGROUND"
log "ore-round-driver started (log: $LOGS/driver.log)"

# ---- crank --------------------------------------------------------------------------------------------
"$TOOL_BIN" --rpc "$RPC_URL" fund "$(pubkey "$K_CRANK")" 100 >/dev/null
mkdir -p "$RUN/crank-state"
cat >"$RUN/crank.toml" <<EOF
# Generated by scripts/devstack/up.sh for the local fork. Not a secret; the keypair is a path.
rpc_url = "$RPC_URL"
ws_url = "$WS_URL"
commitment = "confirmed"
program_id = "$HD_PROGRAM_ID"
listen = "127.0.0.1:$HD_CRANK_PORT"
state_dir = "$RUN/crank-state"
log_json = false

[dig]
# The local ORE ProgramData is created at genesis, not at mainnet's upgrade slot.
ore_programdata_slot = 0
config_poll_secs = 5
EOF
start_bg crank "$LOGS/crank.log" env RUST_LOG="${RUST_LOG:-hd_crank=info}" "$CRANK_BIN" --config "$RUN/crank.toml" \
  --keypair "$K_CRANK" run
wait_http "http://127.0.0.1:$HD_CRANK_PORT/healthz" 90 "hd-crank"
log "hd-crank up: intake ws://127.0.0.1:$HD_CRANK_PORT/ws (log: $LOGS/crank.log)"

# ---- indexer ----------------------------------------------------------------------------------------
(
  cd "$REPO_ROOT/services/indexer"
  start_bg indexer "$LOGS/indexer.log" env INDEXER_DATASET=localnet RPC_URL="$RPC_URL" \
    DATABASE_URL="pglite://$RUN/indexer-pg" HOST=127.0.0.1 PORT="$HD_INDEXER_PORT" ORE_API_ENABLED=0 \
    INGEST_INTERVAL_S=5 TEAM_CRANKERS="$(pubkey "$K_CRANK")" node src/main.ts serve
)
wait_http "http://127.0.0.1:$HD_INDEXER_PORT/v1/health" 90 "indexer"
log "indexer up: http://127.0.0.1:$HD_INDEXER_PORT/v1/health (dataset localnet, log: $LOGS/indexer.log)"

# ---- registrar (optional) -------------------------------------------------------------------------
if [[ $REGISTRAR == 1 ]]; then
  port_busy "$HD_REGISTRAR_PORT" && die "port $HD_REGISTRAR_PORT is in use"
  (cd "$REPO_ROOT/registrar" && cargo build --release) >"$LOGS/build-registrar.log" 2>&1 || die "registrar build failed"
  SECRET_FILE="$HD_DEVSTACK_KEYS/registrar-session-secret"
  [[ -f "$SECRET_FILE" ]] || (umask 077 && openssl rand -hex 32 >"$SECRET_FILE")
  CERT="${HD_DEVSTACK_APP_CERT_SHA256:-}"
  if [[ -z "$CERT" && -f "$HOME/.android/debug.keystore" ]] && command -v keytool >/dev/null; then
    CERT=$(keytool -list -v -keystore "$HOME/.android/debug.keystore" -storepass android -alias androiddebugkey 2>/dev/null \
      | awk '/SHA256:/{print $2}' | tr -d ':' | tr 'A-F' 'a-f')
  fi
  [[ -n "$CERT" ]] || { CERT=$(printf '0%.0s' $(seq 1 64)); log "no debug keystore: registrar accepts no app (set HD_DEVSTACK_APP_CERT_SHA256)"; }
  mkdir -p "$RUN/registrar"
  start_bg registrar "$LOGS/registrar.log" env HD_SESSION_SECRET="$(cat "$SECRET_FILE")" \
    HD_REGISTRAR_KEYPAIR="$K_REGISTRAR" HD_APP_DEBUG_CERT_SHA256="$CERT" HD_RPC_URL="$RPC_URL" \
    HD_SIWS_DOMAIN=localhost HD_SIWS_URI="http://127.0.0.1:$HD_REGISTRAR_PORT" HD_SIWS_CHAINS=solana:localnet \
    HD_NONCE_STORE="sqlite:$RUN/registrar/nonces.db" HD_TRANSPARENCY_LOG="$RUN/registrar/attestations.jsonl" \
    HD_BIND="127.0.0.1:$HD_REGISTRAR_PORT" HD_LOG_FORMAT=pretty \
    HD_STATUS_LIST_FILE="$REPO_ROOT/registrar/testdata/status/status_live_sample.json" \
    "$REPO_ROOT/registrar/target/release/hd-registrar" serve
  wait_http "http://127.0.0.1:$HD_REGISTRAR_PORT/registrar" 60 "registrar"
  log "registrar up: http://127.0.0.1:$HD_REGISTRAR_PORT (dev key $(pubkey "$K_REGISTRAR") = Config.registrar)"
fi

echo
log "stack is up ($HD_DEVSTACK_ENGINE)"
cat <<EOF
  RPC        $RPC_URL          (ws $WS_URL)
  crank      ws://127.0.0.1:$HD_CRANK_PORT/ws   http://127.0.0.1:$HD_CRANK_PORT/healthz  /metrics
  indexer    http://127.0.0.1:$HD_INDEXER_PORT/v1/health   /v1/digs/recent
  program    $HD_PROGRAM_ID   Executor PDA $(cd "$DEVSTACK_DIR" && "$TOOL_BIN" --rpc "$RPC_URL" status | awk '/Executor PDA/{print $3}')
  logs       $LOGS
  next       scripts/devstack/smoke.sh | status.sh | phone.sh | fund.sh <pubkey> | down.sh
EOF
