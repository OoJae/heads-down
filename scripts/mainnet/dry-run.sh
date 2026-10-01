#!/usr/bin/env bash
# Rehearse the whole mainnet runbook against a clean local mainnet fork, end to end:
#
#   keys.sh -> preflight.sh -> deploy.sh -> init-config.sh -> scripts/devstack/smoke.sh -> governance pause
#
#   scripts/mainnet/dry-run.sh [--keep] [--no-build] [--skip-smoke] [--allow-dirty]
#
# Isolation: its own dev-stack home, key directories and ports (RPC 18899, crank 18787, indexer
# 18788, faucet 19900, gossip 28001+), so it never touches a dev stack already running on the
# default ports, nor ~/.config/heads-down/mainnet. The deploy keys are a throwaway set in
# ~/.config/heads-down/dryrun/keys (deployer funded by the local faucet). The fork is
# `scripts/devstack/up.sh --no-deploy`: ORE and mainnet's feature set, no heads_down, so deploy.sh
# runs its real fresh-deploy path (same program id HDn4vg…, --max-len, buffer, receipt).
# The stack is stopped at the end unless --keep. Output: $HD_DRYRUN_HOME/dry-run.log.
HD_SCRIPT=dry-run
source "$(dirname "$0")/lib.sh"

KEEP=0 UP_ARGS=() SMOKE=1 DEPLOY_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep) KEEP=1; shift ;;
    --no-build) UP_ARGS+=(--no-build); shift ;;
    --skip-smoke) SMOKE=0; shift ;;
    --allow-dirty) DEPLOY_ARGS+=(--allow-dirty); shift ;;
    -h | --help) sed -n '2,16p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done

export HD_DEVSTACK_HOME="${HD_DRYRUN_HOME:-$HOME/.local/share/heads-down/dryrun}"
export HD_DEVSTACK_KEYS="${HD_DRYRUN_DEVSTACK_KEYS:-$HOME/.config/heads-down/dryrun/devstack}"
export HD_RPC_PORT="${HD_DRYRUN_RPC_PORT:-18899}"
export HD_WS_PORT=$((HD_RPC_PORT + 1))
export HD_CRANK_PORT="${HD_DRYRUN_CRANK_PORT:-18787}"
export HD_INDEXER_PORT="${HD_DRYRUN_INDEXER_PORT:-18788}"
export HD_REGISTRAR_PORT="${HD_DRYRUN_REGISTRAR_PORT:-18790}"
export HD_FAUCET_PORT="${HD_DRYRUN_FAUCET_PORT:-19900}"
export HD_GOSSIP_PORT="${HD_DRYRUN_GOSSIP_PORT:-28001}"
export HD_DYNAMIC_PORTS="${HD_DRYRUN_DYNAMIC_PORTS:-28002-28040}"
export HD_STATE="$HD_DEVSTACK_HOME/deploy"
DRY_KEYS="$HD_DRYRUN_KEYS"
DEVSTACK="$REPO_ROOT/scripts/devstack"
mkdir -p "$HD_DEVSTACK_HOME"
LOG="$HD_DEVSTACK_HOME/dry-run.log"
: >"$LOG"
step() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*" | tee -a "$LOG"; }
run() { "$@" 2>&1 | tee -a "$LOG"; return "${PIPESTATUS[0]}"; }

STARTED=0
finish() {
  local rc=$?
  if [[ $STARTED == 1 && $KEEP == 0 ]]; then
    printf '\n\033[1;36m== stop the stack ==\033[0m\n' | tee -a "$LOG"
    "$DEVSTACK/down.sh" 2>&1 | tee -a "$LOG" || true
  fi
  if [[ $rc -eq 0 ]]; then
    printf '\n\033[1;32mDRY RUN PASSED\033[0m (log %s)\n' "$LOG"
  else
    printf '\n\033[1;31mDRY RUN FAILED (exit %s)\033[0m (log %s)\n' "$rc" "$LOG"
  fi
}
trap finish EXIT

step "0. local stack without heads_down (up.sh --no-deploy, RPC :$HD_RPC_PORT, home $HD_DEVSTACK_HOME)"
STARTED=1
run "$DEVSTACK/up.sh" --no-deploy ${UP_ARGS[@]+"${UP_ARGS[@]}"}

step "1. keys.sh (throwaway deploy keys in $DRY_KEYS)"
run "$MAINNET_SCRIPTS/keys.sh" --cluster localnet --keys-dir "$DRY_KEYS" --no-funding
run "$DEVSTACK/fund.sh" "$(pubkey_of "$DRY_KEYS/deployer.json")" 5
run "$DEVSTACK/fund.sh" "$(pubkey_of "$DRY_KEYS/governance.json")" 1
run "$DEVSTACK/fund.sh" "$(pubkey_of "$DRY_KEYS/crank-payer.json")" 1
run "$MAINNET_SCRIPTS/keys.sh" --cluster localnet --keys-dir "$DRY_KEYS"

step "2. preflight.sh --cluster localnet (read-only)"
run "$MAINNET_SCRIPTS/preflight.sh" --cluster localnet --keys-dir "$DRY_KEYS"

step "3. deploy.sh --cluster localnet (build, preflight, deploy, verify, receipt)"
run "$MAINNET_SCRIPTS/deploy.sh" --cluster localnet --keys-dir "$DRY_KEYS" --yes ${DEPLOY_ARGS[@]+"${DEPLOY_ARGS[@]}"}

step "4. init-config.sh --cluster localnet (initialize_config + Executor float)"
run "$MAINNET_SCRIPTS/init-config.sh" --cluster localnet --keys-dir "$DRY_KEYS" --yes
run "$MAINNET_SCRIPTS/governance.sh" --cluster localnet --keys-dir "$DRY_KEYS" show

if [[ $SMOKE == 1 ]]; then
  step "5. scripts/devstack/smoke.sh against the deployed + initialized program"
  run "$DEVSTACK/smoke.sh" --keep
fi

step "6. rollback drill: governance.sh pause (immediate), then show"
run "$MAINNET_SCRIPTS/governance.sh" --cluster localnet --keys-dir "$DRY_KEYS" pause
run "$MAINNET_SCRIPTS/governance.sh" --cluster localnet --keys-dir "$DRY_KEYS" show
