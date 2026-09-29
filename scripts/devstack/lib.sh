# shellcheck shell=bash
# Shared settings for scripts/devstack/*.sh. Sourced, never executed.
#
# Everything secret or large lives OUTSIDE the repo:
#   keys  $HD_DEVSTACK_KEYS  (default ~/.config/heads-down/devstack)   dev keypairs + entropy secret, mode 600
#   state $HD_DEVSTACK_HOME  (default ~/.local/share/heads-down/devstack) fixtures, genesis, ledger, logs, pids

set -euo pipefail

DEVSTACK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DEVSTACK_DIR/../.." && pwd)"

export PATH="$HOME/.local/share/solana/install/active_release/bin:$HOME/.cargo/bin:$PATH"

HD_DEVSTACK_ENGINE="${HD_DEVSTACK_ENGINE:-test-validator}"   # test-validator | surfpool
HD_DEVSTACK_HOME="${HD_DEVSTACK_HOME:-$HOME/.local/share/heads-down/devstack}"
HD_DEVSTACK_KEYS="${HD_DEVSTACK_KEYS:-$HOME/.config/heads-down/devstack}"
HD_PROGRAM_KEYPAIR="${HD_PROGRAM_KEYPAIR:-$HOME/.config/heads-down/heads_down-program-keypair.json}"
HD_PROGRAM_ID="HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p"
HD_MAINNET_RPC="${HD_MAINNET_RPC:-https://api.mainnet-beta.solana.com}"

# Ports. The phone reaches the first three over `adb reverse` (phone.sh).
HD_RPC_PORT="${HD_RPC_PORT:-8899}"
HD_WS_PORT="${HD_WS_PORT:-$((HD_RPC_PORT + 1))}"
HD_CRANK_PORT="${HD_CRANK_PORT:-8787}"
HD_INDEXER_PORT="${HD_INDEXER_PORT:-8788}"
HD_REGISTRAR_PORT="${HD_REGISTRAR_PORT:-8790}"
HD_FAUCET_PORT="${HD_FAUCET_PORT:-9900}"
HD_GOSSIP_PORT="${HD_GOSSIP_PORT:-18001}"
HD_DYNAMIC_PORTS="${HD_DYNAMIC_PORTS:-18002-18040}"

# heads_down Config economics (crank/README.md "Operating costs"): executor_fee = ECONOMICS.md's
# 10,000-lamport placeholder; crank_fee = 7,000 (ORE's own executor fee; covers a batched
# fresh-heartbeat dig at ~6,050-6,723 lamports per rig).
HD_EXECUTOR_FEE="${HD_EXECUTOR_FEE:-10000}"
HD_CRANK_FEE="${HD_CRANK_FEE:-7000}"
HD_EXECUTOR_FLOAT="${HD_EXECUTOR_FLOAT:-1000000000}"
# Optional ORE timing overrides (default: mainnet's 240-slot rounds + 48-slot intermission).
HD_ROUND_SLOTS="${HD_ROUND_SLOTS:-}"
HD_INTERMISSION_SLOTS="${HD_INTERMISSION_SLOTS:-}"

RPC_URL="http://127.0.0.1:$HD_RPC_PORT"
WS_URL="ws://127.0.0.1:$HD_WS_PORT"

FIXTURES="$HD_DEVSTACK_HOME/fixtures"
GENESIS="$HD_DEVSTACK_HOME/genesis"
LEDGER="$HD_DEVSTACK_HOME/ledger"
LOGS="$HD_DEVSTACK_HOME/logs"
RUN="$HD_DEVSTACK_HOME/run"

K_UPGRADE="$HD_DEVSTACK_KEYS/upgrade-authority.json"
K_GOVERNANCE="$HD_DEVSTACK_KEYS/governance.json"
K_REGISTRAR="$HD_DEVSTACK_KEYS/registrar.json"
K_CRANK="$HD_DEVSTACK_KEYS/crank.json"
K_DRIVER="$HD_DEVSTACK_KEYS/round-driver.json"
K_BACKGROUND="$HD_DEVSTACK_KEYS/background-miner.json"
ENTROPY_SECRET="$HD_DEVSTACK_KEYS/entropy-secret"

HD_SO="$REPO_ROOT/programs/heads-down/target/deploy/heads_down.so"
CRANK_BIN="$REPO_ROOT/crank/target/release/hd-crank"
TOOL_BIN="$DEVSTACK_DIR/tool/target/release/hd-devstack"
SURFPOOL_BIN="${SURFPOOL_BIN:-$HD_DEVSTACK_HOME/bin/surfpool}"

log() { printf '\033[1m[devstack]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[devstack] %s\033[0m\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "missing '$1': $2"; }

port_busy() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

pidfile() { echo "$RUN/$1.pid"; }

is_running() {
  local f; f="$(pidfile "$1")"
  [[ -f "$f" ]] && kill -0 "$(cat "$f")" 2>/dev/null
}

# start NAME LOGFILE CMD... : run in the background, remember the pid.
start_bg() {
  local name="$1" logf="$2"; shift 2
  mkdir -p "$RUN" "$LOGS"
  nohup "$@" >>"$logf" 2>&1 &
  echo $! >"$(pidfile "$name")"
}

stop_bg() {
  local name="$1" f pid
  f="$(pidfile "$name")"
  [[ -f "$f" ]] || return 0
  pid="$(cat "$f")"
  if kill -0 "$pid" 2>/dev/null; then
    pkill -TERM -P "$pid" 2>/dev/null || true
    kill -TERM "$pid" 2>/dev/null || true
    for _ in $(seq 1 50); do kill -0 "$pid" 2>/dev/null || break; sleep 0.2; done
    kill -KILL "$pid" 2>/dev/null || true
  fi
  rm -f "$f"
}

rpc() { # rpc METHOD [JSON-PARAMS]
  curl -s -m 5 -X POST -H 'content-type: application/json' \
    --data "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$1\",\"params\":${2:-[]}}" "$RPC_URL"
}

wait_http() { # wait_http URL SECONDS WHAT
  local url="$1" secs="$2" what="$3"
  for _ in $(seq 1 "$((secs * 2))"); do
    if curl -s -m 2 -o /dev/null -w '%{http_code}' "$url" | grep -q '^200$'; then return 0; fi
    sleep 0.5
  done
  die "$what did not come up at $url within ${secs}s (see $LOGS)"
}

ensure_key() { # ensure_key PATH
  if [[ ! -f "$1" ]]; then
    mkdir -p "$(dirname "$1")"
    chmod 700 "$(dirname "$1")"
    solana-keygen new --no-bip39-passphrase --silent --outfile "$1" >/dev/null
  fi
  chmod 600 "$1"
}

pubkey() { solana-keygen pubkey "$1"; }

slot() { rpc getSlot | python3 -c 'import json,sys;print(json.load(sys.stdin)["result"])'; }

wait_slots() { # wait_slots N: let N slots pass (a program deployed in slot s is callable from s+1)
  local target=$(( $(slot) + $1 ))
  while [[ $(slot) -lt $target ]]; do sleep 0.3; done
}
