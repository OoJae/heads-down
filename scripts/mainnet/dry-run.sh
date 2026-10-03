#!/usr/bin/env bash
# Rehearse the whole mainnet runbook against a clean local mainnet fork, end to end:
#
#   keys.sh -> preflight.sh -> deploy.sh -> init-config.sh -> scripts/devstack/smoke.sh
#   -> deploy.sh --mode upgrade -> deploy.sh --mode buffer (Squads) -> governance pause
#
#   scripts/mainnet/dry-run.sh [--keep] [--no-build] [--skip-smoke] [--allow-dirty] [--tight]
#
# --tight funds every key with exactly the lamports the funding table asks for (the amounts the
# founder is told to send on mainnet), not a lamport more, so a passing run proves those amounts
# cover the fresh deploy and init-config. Each later drill is then topped up with what the runbook
# says it needs: the temporary buffer rent plus the fee budget.
#
# Isolation: its own dev-stack home, key directories and ports (RPC 38899, crank 38787, indexer
# 38788, faucet 39900, gossip 38001+; override with HD_DRYRUN_*_PORT), so it never touches a dev
# stack already running on other ports, nor ~/.config/heads-down/mainnet. The deploy keys are a throwaway set in
# ~/.config/heads-down/dryrun/keys (deployer funded by the local faucet). The fork is
# `scripts/devstack/up.sh --no-deploy`: ORE and mainnet's feature set, no heads_down, so deploy.sh
# runs its real fresh-deploy path (same program id HDn4vg…, --max-len, buffer, receipt).
# The stack is stopped at the end unless --keep. Output: $HD_DRYRUN_HOME/dry-run.log.
HD_SCRIPT=dry-run
source "$(dirname "$0")/lib.sh"

KEEP=0 UP_ARGS=() SMOKE=1 DEPLOY_ARGS=() TIGHT=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep) KEEP=1; shift ;;
    --no-build) UP_ARGS+=(--no-build); shift ;;
    --skip-smoke) SMOKE=0; shift ;;
    --allow-dirty) DEPLOY_ARGS+=(--allow-dirty); shift ;;
    --tight) TIGHT=1; shift ;;
    -h | --help) sed -n '2,21p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done

export HD_DEVSTACK_HOME="${HD_DRYRUN_HOME:-$HOME/.local/share/heads-down/dryrun}"
export HD_DEVSTACK_KEYS="${HD_DRYRUN_DEVSTACK_KEYS:-$HOME/.config/heads-down/dryrun/devstack}"
export HD_RPC_PORT="${HD_DRYRUN_RPC_PORT:-38899}"
export HD_WS_PORT=$((HD_RPC_PORT + 1))
export HD_CRANK_PORT="${HD_DRYRUN_CRANK_PORT:-38787}"
export HD_INDEXER_PORT="${HD_DRYRUN_INDEXER_PORT:-38788}"
export HD_REGISTRAR_PORT="${HD_DRYRUN_REGISTRAR_PORT:-38790}"
export HD_FAUCET_PORT="${HD_DRYRUN_FAUCET_PORT:-39900}"
export HD_GOSSIP_PORT="${HD_DRYRUN_GOSSIP_PORT:-38001}"
export HD_DYNAMIC_PORTS="${HD_DRYRUN_DYNAMIC_PORTS:-38002-38040}"
export HD_STATE="$HD_DEVSTACK_HOME/deploy"
DRY_KEYS="$HD_DRYRUN_KEYS"
DEVSTACK="$REPO_ROOT/scripts/devstack"
mkdir -p "$HD_DEVSTACK_HOME"
LOG="$HD_DEVSTACK_HOME/dry-run.log"
: >"$LOG"
step() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*" | tee -a "$LOG"; }
run() { "$@" 2>&1 | tee -a "$LOG"; return "${PIPESTATUS[0]}"; }

STARTED=0 SUMMARY=() SMOKE_RC=0
finish() {
  local rc=$?
  if [[ $STARTED == 1 && $KEEP == 0 ]]; then
    printf '\n\033[1;36m== stop the stack ==\033[0m\n' | tee -a "$LOG"
    "$DEVSTACK/down.sh" 2>&1 | tee -a "$LOG" || true
  fi
  printf '\n\033[1msummary\033[0m\n' | tee -a "$LOG"
  for s in ${SUMMARY[@]+"${SUMMARY[@]}"}; do printf '  %s\n' "$s" | tee -a "$LOG"; done
  if [[ $rc -eq 0 ]]; then
    printf '\n\033[1;32mDRY RUN PASSED\033[0m (log %s)\n' "$LOG"
  else
    printf '\n\033[1;31mDRY RUN FAILED (exit %s)\033[0m (log %s)\n' "$rc" "$LOG"
  fi
}
trap finish EXIT
ok() { SUMMARY+=("PASS  $*"); }

step "0. local stack without heads_down (up.sh --no-deploy, RPC :$HD_RPC_PORT, home $HD_DEVSTACK_HOME)"
STARTED=1
run "$DEVSTACK/up.sh" --no-deploy ${UP_ARGS[@]+"${UP_ARGS[@]}"}
ok "0 up.sh --no-deploy: fork, driver, crank, indexer up; no heads_down"

# --tight: the funding table as JSON, for a build of $1 bytes (the same call keys.sh prints from).
FUNDING_JSON="$HD_DEVSTACK_HOME/funding.json"
funding_json() {
  HD_DEVSTACK_RPC="http://127.0.0.1:$HD_RPC_PORT" HD_CLUSTER=localnet "$TOOL_BIN" funding \
    --deployer "$(pubkey_of "$DRY_KEYS/deployer.json")" --so-len "$1" --max-len "$HD_MAX_LEN" \
    --crank-fee "$HD_CRANK_FEE" --crank-reserve-digs "$HD_CRANK_RESERVE_DIGS" --fee-budget "$(deploy_fee_budget)" \
    --key "crank-payer=$(pubkey_of "$DRY_KEYS/crank-payer.json"):$HD_CRANK_PAYER_LAMPORTS" \
    --key "governance=$(pubkey_of "$DRY_KEYS/governance.json"):$HD_GOVERNANCE_LAMPORTS" \
    --json "$FUNDING_JSON" >/dev/null
}
# Lamports a key needs (deployer, crank-payer, governance), or a rent figure with "rent.<name>".
funding_of() {
  python3 - "$FUNDING_JSON" "$1" <<'PYEOF'
import json, sys
table, what = json.load(open(sys.argv[1])), sys.argv[2]
if what.startswith("rent."):
    print(table["rent"][what[5:]])
else:
    print(next(row["need"] for row in table["rows"] if row["key"] == what))
PYEOF
}
# fund.sh takes SOL and truncates to lamports: half a lamport more makes the amount exact.
fund_lamports() {
  run "$DEVSTACK/fund.sh" "$1" "$(python3 -c 'import sys; print(f"{(int(sys.argv[1]) + 0.5) / 1e9:.10f}")' "$2")"
}

step "1. keys.sh (throwaway deploy keys in $DRY_KEYS)"
run "$MAINNET_SCRIPTS/keys.sh" --cluster localnet --keys-dir "$DRY_KEYS" --no-funding
if [[ $TIGHT == 1 ]]; then
  # The deployer's need does not depend on the build's size (only the buffer line does).
  funding_json 0
  for key in deployer crank-payer governance; do
    fund_lamports "$(pubkey_of "$DRY_KEYS/$key.json")" "$(funding_of "$key")"
  done
  FUNDED="every key holds exactly what the funding table asks for (deployer $(funding_of deployer) lamports)"
else
  run "$DEVSTACK/fund.sh" "$(pubkey_of "$DRY_KEYS/deployer.json")" 5
  run "$DEVSTACK/fund.sh" "$(pubkey_of "$DRY_KEYS/governance.json")" 1
  run "$DEVSTACK/fund.sh" "$(pubkey_of "$DRY_KEYS/crank-payer.json")" 1
  FUNDED="public keys and funding printed"
fi
run "$MAINNET_SCRIPTS/keys.sh" --cluster localnet --keys-dir "$DRY_KEYS"
ok "1 keys.sh: keys created (600 in a 700 dir), $FUNDED"

step "2. preflight.sh --cluster localnet (read-only)"
run "$MAINNET_SCRIPTS/preflight.sh" --cluster localnet --keys-dir "$DRY_KEYS"
ok "2 preflight.sh: GO"

step "3. deploy.sh --cluster localnet (build, preflight, deploy, verify, receipt)"
run "$MAINNET_SCRIPTS/deploy.sh" --cluster localnet --keys-dir "$DRY_KEYS" --yes ${DEPLOY_ARGS[@]+"${DEPLOY_ARGS[@]}"}
ok "3 deploy.sh: fresh deploy of HDn4vg… with max-len $HD_MAX_LEN, bytes verified, receipt written"

step "4. init-config.sh --cluster localnet (initialize_config + Executor float)"
run "$MAINNET_SCRIPTS/init-config.sh" --cluster localnet --keys-dir "$DRY_KEYS" --yes
run "$MAINNET_SCRIPTS/governance.sh" --cluster localnet --keys-dir "$DRY_KEYS" show
ok "4 init-config.sh: Config created and read back, Executor float funded, receipt written"

if [[ $SMOKE == 1 ]]; then
  step "5. scripts/devstack/smoke.sh against the deployed + initialized program"
  if run "$DEVSTACK/smoke.sh" --keep; then
    ok "5 smoke.sh: SMOKE PASSED"
  else
    SMOKE_RC=$?
    SUMMARY+=("FAIL  5 smoke.sh exited $SMOKE_RC (its output is above)")
  fi
fi

step "6. upgrade drill: deploy.sh --mode upgrade (same commit, fresh buffer), then solana.sh program show"
if [[ $TIGHT == 1 ]]; then
  # What the runbook says an upgrade needs: the temporary buffer's rent and the fee budget.
  funding_json "$(wc -c <"$HD_SO" | tr -d ' ')"
  fund_lamports "$(pubkey_of "$DRY_KEYS/deployer.json")" "$(( $(funding_of rent.buffer) + $(deploy_fee_budget) ))"
fi
run "$MAINNET_SCRIPTS/deploy.sh" --cluster localnet --keys-dir "$DRY_KEYS" --mode upgrade --yes ${DEPLOY_ARGS[@]+"${DEPLOY_ARGS[@]}"}
run "$MAINNET_SCRIPTS/solana.sh" --cluster localnet --keys-dir "$DRY_KEYS" -- program show "$HD_PROGRAM_ID"
ok "6 deploy.sh --mode upgrade: upgraded in place from a fresh buffer, bytes verified, receipt written"

step "7. Squads drill: deploy.sh --mode buffer, handing the buffer to governance.json's key as a stand-in vault"
# The upgrade's buffer rent came back to the deployer; this drill's stays in the handed-over buffer.
[[ $TIGHT == 0 ]] || fund_lamports "$(pubkey_of "$DRY_KEYS/deployer.json")" "$(deploy_fee_budget)"
run "$MAINNET_SCRIPTS/deploy.sh" --cluster localnet --keys-dir "$DRY_KEYS" --mode buffer \
  --buffer-authority "$(pubkey_of "$DRY_KEYS/governance.json")" --yes ${DEPLOY_ARGS[@]+"${DEPLOY_ARGS[@]}"}
ok "7 deploy.sh --mode buffer: buffer written and handed over, bytes verified, receipt written"

step "8. rollback drill: governance.sh pause (immediate), then show"
run "$MAINNET_SCRIPTS/governance.sh" --cluster localnet --keys-dir "$DRY_KEYS" pause
STATUS_OUT="$(HD_DEVSTACK_RPC="http://127.0.0.1:$HD_RPC_PORT" "$REPO_ROOT/scripts/devstack/tool/target/release/hd-devstack" status)"
printf '%s\n' "$STATUS_OUT" | tee -a "$LOG"
if [[ "$STATUS_OUT" == *"paused true"* ]]; then
  ok "8 governance.sh pause: Config.paused = 1 immediately; un-pause waits for the timelock"
else
  SUMMARY+=("FAIL  8 Config.paused did not read back as true")
  exit 1
fi
[[ $SMOKE_RC -eq 0 ]] || exit "$SMOKE_RC"
