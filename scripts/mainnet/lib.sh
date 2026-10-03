# shellcheck shell=bash
# shellcheck disable=SC2034  # the variables below are used by the scripts that source this file
# Shared by scripts/mainnet/*.sh (see docs/DEPLOY.md). Sourced, never executed.
#
# Secrets, and how this file keeps them out of sight:
#   * keys live outside the repo: $HD_MAINNET_KEYS (default ~/.config/heads-down/mainnet), dir 700,
#     files 600. Only public keys are ever printed. New keys are made with `solana-keygen --silent`
#     (no seed phrase on screen) or `hd-registrar keygen`.
#   * the Helius key is parsed (not executed) from $HD_HELIUS_ENV. It lives in a NON-exported
#     shell variable and reaches a child only through a prefix assignment (HD_DEVSTACK_RPC=… cmd),
#     which puts it in that child's environment and never in any argv (`ps` cannot see it), or
#     through a mode-600 Solana CLI config in a private temp dir that is deleted on exit.
#   * every child's output goes through `redact`, which removes the key and any api-key= value.
#   * xtrace is refused: `set -x` would print the URL.

set -euo pipefail
case $- in
  *x*) echo "refusing to run with xtrace: set -x would print secrets" >&2; exit 2 ;;
esac
# Parse the whole calling script before running any of it. bash executes line by line, so a
# syntax error late in a script (bash 3.2, the macOS default, is stricter than shellcheck)
# would otherwise surface only after the steps before it had already run.
"$BASH" -n "$0" || { echo "$0 does not parse with bash $BASH_VERSION; nothing was run" >&2; exit 2; }

MAINNET_SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$MAINNET_SCRIPTS/../.." && pwd)"
export PATH="$HOME/.local/share/solana/install/active_release/bin:$HOME/.cargo/bin:$PATH"

HD_PROGRAM_ID="HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p"
HD_EXECUTOR_PDA="By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge"
HD_CONFIG_PDA="inzDn4ogmXbx9YDAKDHkfwJHy1jhsaWxGQvricDAEmW"
HD_PROGRAM_KEYPAIR="${HD_PROGRAM_KEYPAIR:-$HOME/.config/heads-down/heads_down-program-keypair.json}"
HD_MAINNET_KEYS="${HD_MAINNET_KEYS:-$HOME/.config/heads-down/mainnet}"
HD_DRYRUN_KEYS="${HD_DRYRUN_KEYS:-$HOME/.config/heads-down/dryrun/keys}"
HD_HELIUS_ENV="${HD_HELIUS_ENV:-$HD_MAINNET_KEYS/helius.env}"
# The founder's deployer (mainnet only): a different file in the mainnet key dir is a NO-GO.
HD_EXPECTED_DEPLOYER="${HD_EXPECTED_DEPLOYER:-9DSVM862oJrstiPmmQmgqXb7AkuXrKJtYgd1fwbeqeeW}"
HD_STATE="${HD_STATE:-$HOME/.local/share/heads-down/deploy}"

# ---- parameters (docs/DEPLOY.md, "Parameters and why") -------------------------------------------
# --max-len: 192 KiB, 0.9996 SOL of ProgramData rent at mainnet's current rent. The v1.3 build
# (SKR, hardening and the audit fixes) is 190,048 bytes, so about 6.5 KB of headroom is left:
# an upgrade that grows the program past it needs `solana program extend` first (5,080 lamports
# per extra byte).
HD_MAX_LEN="${HD_MAX_LEN:-196608}"
# Config.executor_fee (immutable) and crank_fee (<= executor_fee, timelocked): crank/README.md
# measures 5,500-7,550 lamports of crank cost per fresh-heartbeat dig (6,723 end to end).
HD_EXECUTOR_FEE="${HD_EXECUTOR_FEE:-10000}"
HD_CRANK_FEE="${HD_CRANK_FEE:-7000}"
HD_BURY_BPS="${HD_BURY_BPS:-0}"
# Reimbursements the Executor float covers on top of rent-exempt(0) + 10 x CHECKPOINT_FEE.
HD_CRANK_RESERVE_DIGS="${HD_CRANK_RESERVE_DIGS:-100}"
# Priority fee for deploy / admin transactions (micro-lamports per CU; ~0.001 SOL for the whole deploy).
HD_CU_PRICE="${HD_CU_PRICE:-100000}"
HD_MAX_SIGN_ATTEMPTS="${HD_MAX_SIGN_ATTEMPTS:-20}"
# deploy_fee_budget: lamports the payer must hold for fees before one deploy, upgrade or buffer
# write. `solana program deploy` refuses to start unless the payer holds the CLI's own fee
# estimate on top of the rent, and with a priority fee that estimate prices every transaction at
# the 1.4M compute-unit maximum (the CLI simulates the real limit only afterwards). So this is
# that estimate for a program of --max-len, not what a deploy costs (about 0.001 SOL): the
# difference stays in the deployer. scripts/mainnet/dry-run.sh --tight proves it is enough.
#   transactions = ceil(max-len / 900 bytes per write) + 4    (the CLI packs about 960 per write)
#   each         = 5,000 lamports + 1,400,000 CU x HD_CU_PRICE micro-lamports
# HD_DEPLOY_FEE_BUDGET overrides it. Call it after the flags are parsed (--max-len, --cu-price).
deploy_fee_budget() {
  if [[ -n "${HD_DEPLOY_FEE_BUDGET:-}" ]]; then
    echo "$HD_DEPLOY_FEE_BUDGET"
  else
    echo $(( ((HD_MAX_LEN + 899) / 900 + 4) * (5000 + 1400000 * HD_CU_PRICE / 1000000) ))
  fi
}
# Recommended balances for the service keys (lamports).
HD_CRANK_PAYER_LAMPORTS="${HD_CRANK_PAYER_LAMPORTS:-50000000}"   # 0.05 SOL
HD_GOVERNANCE_LAMPORTS="${HD_GOVERNANCE_LAMPORTS:-10000000}"     # 0.01 SOL

HD_SO="${HD_SO:-$REPO_ROOT/programs/heads-down/target/deploy/heads_down.so}"
TOOL_BIN="$REPO_ROOT/scripts/devstack/tool/target/release/hd-devstack"
RECEIPTS="$REPO_ROOT/deploy/receipts"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
log() { printf '\033[1m[%s]\033[0m %s\n' "${HD_SCRIPT:-mainnet}" "$*"; }
warn() { printf '\033[33m[%s] WARNING: %s\033[0m\n' "${HD_SCRIPT:-mainnet}" "$*" >&2; }
die() { printf '\033[31m[%s] %s\033[0m\n' "${HD_SCRIPT:-mainnet}" "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "missing '$1': $2"; }

# ---- cluster and keys -----------------------------------------------------------------------------
CLUSTER="${HD_CLUSTER_NAME:-mainnet}"
KEYS=""
HELIUS_API_KEY=""
RPC_URL=""
WS_URL=""
RPC_HOST=""

# set_cluster NAME: mainnet | localnet.
set_cluster() {
  case "$1" in
    mainnet | localnet) CLUSTER="$1" ;;
    *) die "--cluster must be mainnet or localnet (got '$1')" ;;
  esac
}

# The default key directory: never the mainnet one for localnet runs.
default_keys() {
  if [[ -z "$KEYS" ]]; then
    if [[ "$CLUSTER" == mainnet ]]; then KEYS="$HD_MAINNET_KEYS"; else KEYS="$HD_DRYRUN_KEYS"; fi
  fi
  if [[ "$CLUSTER" == localnet && "$(abspath "$KEYS")" == "$(abspath "$HD_MAINNET_KEYS")" ]]; then
    die "--cluster localnet must not use the mainnet key directory ($HD_MAINNET_KEYS)"
  fi
  K_DEPLOYER="$KEYS/deployer.json"
  K_CRANK="$KEYS/crank-payer.json"
  K_GOVERNANCE="$KEYS/governance.json"
  K_REGISTRAR="$KEYS/registrar.json"
  K_SESSION="$KEYS/registrar-session-secret"
}

abspath() { (cd "$(dirname "$1")" 2>/dev/null && printf '%s/%s\n' "$(pwd -P)" "$(basename "$1")") || printf '%s\n' "$1"; }

mode_of() { stat -f '%Lp' "$1" 2>/dev/null || stat -c '%a' "$1"; }

# key_ok FILE: exists, is a regular file owned by us, mode 600 or 400.
key_ok() {
  [[ -f "$1" && -O "$1" ]] || return 1
  case "$(mode_of "$1")" in 600 | 400) return 0 ;; *) return 1 ;; esac
}

dir_ok() { [[ -d "$1" && -O "$1" && "$(mode_of "$1")" == 700 ]]; }

pubkey_of() { solana-keygen pubkey "$1"; }

# ---- the Helius key -------------------------------------------------------------------------------
# load_helius: parse HELIUS_API_KEY=… from $HD_HELIUS_ENV into a non-exported variable. The file is
# read, never sourced, so nothing in it can run. Accepts `HELIUS_API_KEY=v`, `export …`, quotes.
load_helius() {
  [[ -f "$HD_HELIUS_ENV" ]] || return 1
  local line value=""
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line#export }"
    if [[ "$line" == HELIUS_API_KEY=* ]]; then value="${line#HELIUS_API_KEY=}"; fi
  done <"$HD_HELIUS_ENV"
  value="${value%\"}"; value="${value#\"}"; value="${value%\'}"; value="${value#\'}"
  value="${value%"${value##*[![:space:]]}"}"
  [[ "$value" =~ ^[A-Za-z0-9_-]{8,}$ ]] || return 2
  HELIUS_API_KEY="$value"
}

helius_file_ok() { key_ok "$HD_HELIUS_ENV"; }

# resolve_rpc [public-ok]: set RPC_URL / WS_URL / RPC_HOST for $CLUSTER. On mainnet this needs
# helius.env; with "public-ok" (read-only scripts) it falls back to the public RPC.
resolve_rpc() {
  if [[ "$CLUSTER" == localnet ]]; then
    RPC_URL="${HD_LOCALNET_RPC:-http://127.0.0.1:${HD_RPC_PORT:-8899}}"
    WS_URL="${HD_LOCALNET_WS:-ws://127.0.0.1:${HD_WS_PORT:-$(( ${HD_RPC_PORT:-8899} + 1 ))}}"
    RPC_HOST="$RPC_URL"
    return 0
  fi
  local rc=0
  load_helius || rc=$?
  if [[ $rc -eq 0 ]]; then
    RPC_URL="https://mainnet.helius-rpc.com/?api-key=${HELIUS_API_KEY}"
    WS_URL="wss://mainnet.helius-rpc.com/?api-key=${HELIUS_API_KEY}"
    RPC_HOST="https://mainnet.helius-rpc.com (Helius, key from $(basename "$HD_HELIUS_ENV"))"
    return 0
  fi
  if [[ "${1:-}" == public-ok ]]; then
    RPC_URL="https://api.mainnet-beta.solana.com"
    WS_URL="wss://api.mainnet-beta.solana.com"
    RPC_HOST="https://api.mainnet-beta.solana.com (public; helius.env missing or invalid)"
    return 0
  fi
  if [[ $rc -eq 1 ]]; then die "missing $HD_HELIUS_ENV (one line: HELIUS_API_KEY=...; chmod 600)"; fi
  die "$HD_HELIUS_ENV has no well-formed HELIUS_API_KEY= line"
}

# redact: strip the Helius key and any api-key= value from a stream.
redact() {
  HD_REDACT_KEY="$HELIUS_API_KEY" perl -pe 'BEGIN { $| = 1; $k = $ENV{HD_REDACT_KEY} // q(); } s/\Q$k\E/<redacted>/g if length $k; s/(api-key=)[A-Za-z0-9_\-]+/${1}<redacted>/gi;'
}

# tool ARGS...: hd-devstack against $CLUSTER; the RPC URL goes in the environment only.
tool() {
  [[ -x "$TOOL_BIN" ]] || build_tool
  HD_DEVSTACK_RPC="$RPC_URL" HD_CLUSTER="$CLUSTER" "$TOOL_BIN" "$@" 2>&1 | redact
}

build_tool() {
  log "building hd-devstack (scripts/devstack/tool, release)"
  mkdir -p "$HD_STATE"
  (cd "$REPO_ROOT/scripts/devstack/tool" && cargo build --release) >"$HD_STATE/build-tool.log" 2>&1 \
    || die "hd-devstack build failed: $HD_STATE/build-tool.log"
}

# ---- the Solana CLI -------------------------------------------------------------------------------
SOLANA_CFG_DIR=""
cleanup_solana_cfg() { if [[ -n "$SOLANA_CFG_DIR" ]]; then rm -rf "$SOLANA_CFG_DIR"; fi; }

# solana_cfg KEYPAIR: a private CLI config (dir 700, file 600) holding the RPC URL, so the key is
# never on a command line. Removed when the script exits.
solana_cfg() {
  cleanup_solana_cfg
  SOLANA_CFG_DIR="$(mktemp -d "${TMPDIR:-/tmp}/hd-solana.XXXXXX")"
  chmod 700 "$SOLANA_CFG_DIR"
  trap cleanup_solana_cfg EXIT
  (
    umask 077
    {
      echo "---"
      printf 'json_rpc_url: "%s"\n' "$RPC_URL"
      printf 'websocket_url: "%s"\n' "$WS_URL"
      printf 'keypair_path: "%s"\n' "$1"
      echo "address_labels: {}"
      echo "commitment: confirmed"
    } >"$SOLANA_CFG_DIR/config.yml"
  )
  SOLANA_CFG="$SOLANA_CFG_DIR/config.yml"
}

# scli ARGS...: the Agave CLI against $CLUSTER (call solana_cfg first). Output is redacted.
scli() {
  [[ -n "${SOLANA_CFG:-}" ]] || die "internal: solana_cfg not set"
  solana -C "$SOLANA_CFG" "$@" 2>&1 | redact
}

# ---- confirmation ---------------------------------------------------------------------------------
YES=0
# confirm PHRASE WHAT: on mainnet, unless --yes, the operator must type PHRASE.
confirm() {
  [[ "$CLUSTER" == mainnet ]] || return 0
  [[ $YES == 1 ]] && return 0
  [[ -r /dev/tty ]] || die "mainnet: no terminal to confirm $2 on; re-run with --yes once you have reviewed the plan"
  printf '\n\033[1mMAINNET: %s.\033[0m Type "%s" to continue: ' "$2" "$1" >/dev/tty
  local answer
  IFS= read -r answer </dev/tty || true
  [[ "$answer" == "$1" ]] || die "not confirmed; nothing was sent"
}

# ---- git ------------------------------------------------------------------------------------------
git_commit() { git -C "$REPO_ROOT" rev-parse HEAD; }
# git_dirty: 1 if tracked files differ from HEAD or untracked files exist where the build reads.
git_dirty() {
  if ! git -C "$REPO_ROOT" diff --quiet HEAD -- || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- programs/heads-down crates)" ]]; then
    echo 1
  else
    echo 0
  fi
}

timestamp() { date -u +%Y%m%dT%H%M%SZ; }
