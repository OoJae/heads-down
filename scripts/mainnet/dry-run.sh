#!/usr/bin/env bash
# Rehearse the whole mainnet runbook against a clean local mainnet fork, end to end:
#
#   selftest.sh -> keys.sh -> preflight.sh -> a deploy stopped part way and refunded -> a deploy
#   stopped part way and continued by re-running deploy.sh -> init-config.sh
#   -> scripts/devstack/smoke.sh -> deploy.sh --mode upgrade behind a rate limit -> the same
#   upgrade with --cli-only (the fallback) -> an upgrade to a build that outgrew --max-len
#   -> deploy.sh --mode buffer (Squads) -> governance pause
#
#   scripts/mainnet/dry-run.sh [--keep] [--no-build] [--skip-smoke] [--allow-dirty] [--tight]
#
# --tight funds every key with exactly the lamports the funding table asks for (the amounts the
# founder is told to send on mainnet), not a lamport more, so a passing run proves those amounts
# cover a fresh deploy that stops part way and is continued, and init-config, with no top-up in
# between. Each later drill is then topped up with what the runbook says it needs: the temporary
# buffer rent plus the fee budget. The growing upgrade is topped up to exactly what preflight
# asks for.
#
# The growing upgrade deploys this same build padded with zero bytes to 101 bytes past --max-len
# (deploy.sh --skip-build): there is no larger build yet, and to the CLI and the loader the
# padded file is a larger program. It rehearses the path, not a larger program's code.
#
# The two "stopped part way" drills kill the buffer writer (SIGKILL) once the buffer holds some
# chunks. The refund drill uses a throwaway deployer of its own, so the fees it spends do not
# touch the key that then deploys. The upgrade drill goes through scripts/devstack/
# rate-limit-proxy.py, which answers HTTP 429 to sendTransaction beyond HD_DRYRUN_SEND_LIMIT a
# second (default 4) in the form Helius documents; the writer asks for more and has to slow down.
# HD_DRYRUN_SEND_LIMIT=1 HD_DRYRUN_LIMITED_WRITE_RATE=1 runs that drill at mainnet's own pace
# against the free plan's limit (about two minutes longer): the writer must then never be refused.
#
# Isolation: its own dev-stack home, key directories and ports (RPC 38899, crank 38787, indexer
# 38788, faucet 39900, gossip 38001+; override with HD_DRYRUN_*_PORT), so it never touches a dev
# stack already running on other ports, nor ~/.config/heads-down/mainnet. The deploy keys are a throwaway set in
# ~/.config/heads-down/dryrun/keys (deployer funded by the local faucet; the refund drill's are
# in keys-refund next to it; HD_DRYRUN_KEYS moves both). The fork is
# `scripts/devstack/up.sh --no-deploy`: ORE and mainnet's feature set, no heads_down, so deploy.sh
# runs its real fresh-deploy path (same program id HDn4vg…, --max-len, buffer, receipt).
# The buffer is written at HD_DRYRUN_WRITE_RATE transactions a second (default 50; mainnet's
# default is 1) by the same code as on mainnet.
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
    -h | --help) sed -n '2,40p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done
need curl "install curl"
need python3 "python3 ships with the Xcode command line tools"

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
REFUND_KEYS="${HD_DRYRUN_KEYS}-refund"
DEVSTACK="$REPO_ROOT/scripts/devstack"
# Writes a second: the rehearsal's speed, and the slower one of the two runs that are stopped
# part way (so that the writer is caught with some chunks written and most still to write).
RATE="${HD_DRYRUN_WRITE_RATE:-50}"
SLOW_RATE="${HD_DRYRUN_SLOW_WRITE_RATE:-8}"
STOP_AFTER="${HD_DRYRUN_STOP_AFTER_CHUNKS:-20}"
# The rate-limited RPC of the upgrade drill: a proxy in front of the validator, the sends a
# second it lets through, and the writes a second the writer is asked for behind it.
SEND_LIMIT="${HD_DRYRUN_SEND_LIMIT:-4}"
LIMITED_RATE="${HD_DRYRUN_LIMITED_WRITE_RATE:-$RATE}"
LIMITED_RPC="http://127.0.0.1:${HD_DRYRUN_PROXY_PORT:-38898}"
# The size of the stand-in for a larger build in the growth drill: 101 bytes more than fit.
GROWN_LEN=$((HD_MAX_LEN + 101))
mkdir -p "$HD_DEVSTACK_HOME"
LOG="$HD_DEVSTACK_HOME/dry-run.log"
: >"$LOG"
step() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*" | tee -a "$LOG"; }
run() { "$@" 2>&1 | tee -a "$LOG"; return "${PIPESTATUS[0]}"; }
say() { printf '%s\n' "$*" | tee -a "$LOG"; }

STARTED=0 SUMMARY=() SMOKE_RC=0 LIMITER_PID=""
# start_limiter: the rate-limit proxy in front of the validator; stop_limiter ends it.
start_limiter() {
  python3 "$DEVSTACK/rate-limit-proxy.py" --listen "${LIMITED_RPC#http://}" --upstream "http://127.0.0.1:$HD_RPC_PORT" \
    --sends-per-second "$SEND_LIMIT" >>"$LOG" 2>&1 &
  LIMITER_PID=$!
  local n
  for n in $(seq 1 50); do
    if curl -s -m 2 -o /dev/null "$LIMITED_RPC/stats"; then return 0; fi
    sleep 0.2
  done
  fail "the rate-limit proxy did not come up at $LIMITED_RPC after $n tries"
}
stop_limiter() {
  if [[ -n "$LIMITER_PID" ]]; then
    kill "$LIMITER_PID" 2>/dev/null || true
    wait "$LIMITER_PID" 2>/dev/null || true
    LIMITER_PID=""
  fi
}
finish() {
  local rc=$?
  stop_limiter
  if [[ $STARTED == 1 && $KEEP == 0 ]]; then
    printf '\n\033[1;36m== stop the stack ==\033[0m\n' | tee -a "$LOG"
    "$DEVSTACK/down.sh" 2>&1 | tee -a "$LOG" || true
  fi
  printf '\n\033[1msummary\033[0m\n' | tee -a "$LOG"
  for s in ${SUMMARY[@]+"${SUMMARY[@]}"}; do printf '  %s\n' "$s" | tee -a "$LOG"; done
  if [[ $rc -eq 0 ]]; then
    printf '\n\033[1;32mDRY RUN PASSED\033[0m (log %s)\n' "$LOG" | tee -a "$LOG"
  else
    printf '\n\033[1;31mDRY RUN FAILED (exit %s)\033[0m (log %s)\n' "$rc" "$LOG" | tee -a "$LOG"
  fi
}
trap finish EXIT
ok() { SUMMARY+=("PASS  $*"); }
fail() { SUMMARY+=("FAIL  $*"); printf '\033[1;31mFAIL: %s\033[0m\n' "$*" | tee -a "$LOG"; exit 1; }
# expect WHAT GOT WANT
expect() { [[ "$2" == "$3" ]] || fail "$1: got $2, expected $3"; }

COMMIT="$(git_commit)"
tool_local() { HD_DEVSTACK_RPC="http://127.0.0.1:$HD_RPC_PORT" HD_CLUSTER=localnet "$TOOL_BIN" "$@"; }
deploy() { run "$MAINNET_SCRIPTS/deploy.sh" --cluster localnet --yes ${DEPLOY_ARGS[@]+"${DEPLOY_ARGS[@]}"} "$@"; }
# deploy_limited ARGS...: the same, with the rate-limit proxy as its RPC.
deploy_limited() {
  run env HD_LOCALNET_RPC="$LIMITED_RPC" "$MAINNET_SCRIPTS/deploy.sh" --cluster localnet --yes ${DEPLOY_ARGS[@]+"${DEPLOY_ARGS[@]}"} "$@"
}
# deploy_build SO ARGS...: deploy the file SO as it is (deploy.sh --skip-build), not this commit's build.
deploy_build() {
  local so="$1"
  shift
  run env HD_SO="$so" "$MAINNET_SCRIPTS/deploy.sh" --cluster localnet --yes --skip-build ${DEPLOY_ARGS[@]+"${DEPLOY_ARGS[@]}"} "$@"
}

# json_of FILE PATH: one value of a JSON file (PATH like buffer_write.chunks_total); "null" if absent.
json_of() {
  python3 - "$1" "$2" <<'PYEOF'
import json, sys
value = json.load(open(sys.argv[1]))
for key in sys.argv[2].split("."):
    value = value.get(key) if isinstance(value, dict) else None
    if value is None:
        break
print("null" if value is None else str(value).lower() if isinstance(value, bool) else value)
PYEOF
}
# last_receipt MODE: the newest receipt deploy.sh wrote for that mode at this commit (the names
# start with the UTC time, so the last one in sorted order is the newest).
last_receipt() {
  local f last=""
  for f in "$RECEIPTS/localnet/"*"-$1-${COMMIT:0:12}.json"; do last="$f"; done
  [[ -f "$last" ]] || fail "deploy.sh left no $1 receipt for ${COMMIT:0:12} in $RECEIPTS/localnet"
  echo "$last"
}

step "0a. selftest.sh (no cluster: which RPC a script picks, the pins on the funded addresses, argument checks)"
SELFTEST="$("$MAINNET_SCRIPTS/selftest.sh" 2>&1)" || { say "$SELFTEST"; fail "0a selftest.sh failed (its output is above)"; }
say "$(printf '%s\n' "$SELFTEST" | tail -1)"
ok "0a selftest.sh: $(printf '%s\n' "$SELFTEST" | grep -c '^ok ') checks passed"

step "0. local stack without heads_down (up.sh --no-deploy, RPC :$HD_RPC_PORT, home $HD_DEVSTACK_HOME)"
STARTED=1
run "$DEVSTACK/up.sh" --no-deploy ${UP_ARGS[@]+"${UP_ARGS[@]}"}
if [[ ! -f "$HD_SO" ]]; then
  # preflight.sh needs a build to look at (deploy.sh builds it again from the commit).
  say "[dry-run] building heads_down (programs/heads-down/scripts/build.sh): no build in this checkout yet"
  bash "$REPO_ROOT/programs/heads-down/scripts/build.sh" >"$HD_DEVSTACK_HOME/logs/build-program.log" 2>&1 \
    || fail "program build failed: $HD_DEVSTACK_HOME/logs/build-program.log"
fi
ok "0 up.sh --no-deploy: fork, driver, crank, indexer up; no heads_down"

# The funding table as JSON for the deployer of key dir $1 and a build of $2 bytes (the same call
# keys.sh prints from).
FUNDING_JSON="$HD_DEVSTACK_HOME/funding.json"
funding_json() {
  tool_local funding --deployer "$(pubkey_of "$1/deployer.json")" --so-len "$2" --max-len "$HD_MAX_LEN" \
    --crank-fee "$HD_CRANK_FEE" --crank-reserve-digs "$HD_CRANK_RESERVE_DIGS" --fee-budget "$(deploy_fee_budget)" \
    --key "crank-payer=$(pubkey_of "$1/crank-payer.json"):$HD_CRANK_PAYER_LAMPORTS" \
    --key "governance=$(pubkey_of "$1/governance.json"):$HD_GOVERNANCE_LAMPORTS" \
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
# paid PUBKEY: what that key paid for since the fork started -> $FEES_JSON (transactions,
# fees_lamports, and its balance now).
FEES_JSON="$HD_DEVSTACK_HOME/fees.json"
paid() { tool_local fees --payer "$1" --json "$FEES_JSON" | tee -a "$LOG"; }
# buffer_state KEYDIR: what this commit's buffer of that key dir holds -> $STATUS_JSON.
STATUS_JSON="$HD_DEVSTACK_HOME/buffer-status.json"
buffer_state() {
  tool_local buffer-status --so "$HD_SO" --buffer "$(pubkey_of "$(buffer_keypair "$1" "$COMMIT")")" \
    --deployer "$(pubkey_of "$1/deployer.json")" --json "$STATUS_JSON"
}

# stop_part_way KEYDIR: run a fresh deploy.sh with a slow writer, kill the writer (SIGKILL) once
# the buffer holds $STOP_AFTER chunks, wait until nothing more lands, and check what is left: no
# program, and a buffer that is the deployer's and holds the ProgramData rent and some chunks.
# Sets BUFFER, WRITTEN, TO_WRITE and TOTAL.
stop_part_way() {
  local keys="$1" kp deployer job pids n=0 same=0 last="" state status
  kp="$(buffer_keypair "$keys" "$COMMIT")"
  deployer="$(pubkey_of "$keys/deployer.json")"
  deploy --keys-dir "$keys" --write-rate "$SLOW_RATE" &
  job=$!
  WRITTEN=0
  while kill -0 "$job" 2>/dev/null; do
    sleep 0.5
    n=$((n + 1))
    [[ $n -lt 1200 ]] || { kill "$job" 2>/dev/null || true; fail "deploy.sh was still not writing after 10 minutes"; }
    [[ -f "$kp" && -f "$HD_SO" ]] || continue
    buffer_state "$keys" >/dev/null 2>&1 || continue
    [[ "$(json_of "$STATUS_JSON" state)" == resumable ]] || continue
    WRITTEN=$(( $(json_of "$STATUS_JSON" chunks_total) - $(json_of "$STATUS_JSON" chunks_to_write) ))
    if [[ $WRITTEN -ge $STOP_AFTER ]]; then
      # The writer is the hd-devstack process that was given this buffer keypair. (grep -F: the
      # path is a fixed string, not the pattern pgrep would take it for.)
      # shellcheck disable=SC2009
      pids="$(ps -axo pid=,command= | grep -F "write-buffer" | grep -F -- "--buffer $kp" | grep -v grep | awk '{print $1}')" || true
      [[ -n "$pids" ]] || continue
      # shellcheck disable=SC2086  # one pid per word
      kill -KILL $pids
      say "[dry-run] killed the buffer writer (pid ${pids//$'\n'/ }) with $WRITTEN chunks written"
      break
    fi
  done
  if wait "$job"; then fail "deploy.sh finished: the writer was not stopped part way"; fi
  [[ $WRITTEN -ge $STOP_AFTER ]] || fail "deploy.sh stopped before the buffer held $STOP_AFTER chunks (its output is above)"
  # Writes that were already on their way may still land: wait until three looks in a row agree.
  while [[ $same -lt 3 ]]; do
    sleep 1
    buffer_state "$keys" >/dev/null
    state="$(json_of "$STATUS_JSON" chunks_to_write)"
    if [[ "$state" == "$last" ]]; then same=$((same + 1)); else same=0; fi
    last="$state"
  done
  run buffer_state "$keys"
  BUFFER="$(json_of "$STATUS_JSON" address)"
  TOTAL="$(json_of "$STATUS_JSON" chunks_total)"
  TO_WRITE="$(json_of "$STATUS_JSON" chunks_to_write)"
  WRITTEN=$((TOTAL - TO_WRITE))
  funding_json "$keys" "$(wc -c <"$HD_SO" | tr -d ' ')"
  expect "the buffer's state" "$(json_of "$STATUS_JSON" state)" resumable
  expect "the buffer's authority" "$(json_of "$STATUS_JSON" authority)" "$deployer"
  expect "the buffer's lamports (the ProgramData rent)" "$(json_of "$STATUS_JSON" lamports)" "$(funding_of rent.programdata)"
  [[ $WRITTEN -gt 0 && $TO_WRITE -gt 0 ]] || fail "the buffer holds $WRITTEN of $TOTAL chunks: not part way"
  status="$(tool_local status)"
  say "$status"
  [[ "$status" == *"$HD_PROGRAM_ID not deployed"* ]] || fail "the program exists after a deploy that was stopped part way"
  say "[dry-run] stopped part way: no program; buffer $BUFFER is the deployer's, holds $(funding_of rent.programdata) lamports and $WRITTEN of $TOTAL chunks"
}

step "1. keys.sh (throwaway deploy keys in $DRY_KEYS)"
run "$MAINNET_SCRIPTS/keys.sh" --cluster localnet --keys-dir "$DRY_KEYS" --no-funding
run "$MAINNET_SCRIPTS/keys.sh" --cluster localnet --keys-dir "$REFUND_KEYS" --no-funding >/dev/null
if [[ $TIGHT == 1 ]]; then
  # The deployer's need does not depend on the build's size.
  funding_json "$DRY_KEYS" 0
  for key in deployer crank-payer governance; do
    fund_lamports "$(pubkey_of "$DRY_KEYS/$key.json")" "$(funding_of "$key")"
  done
  fund_lamports "$(pubkey_of "$REFUND_KEYS/deployer.json")" "$(funding_of deployer)"
  FUNDED="every key holds exactly what the funding table asks for (deployer $(funding_of deployer) lamports)"
else
  run "$DEVSTACK/fund.sh" "$(pubkey_of "$DRY_KEYS/deployer.json")" 5
  run "$DEVSTACK/fund.sh" "$(pubkey_of "$DRY_KEYS/governance.json")" 1
  run "$DEVSTACK/fund.sh" "$(pubkey_of "$DRY_KEYS/crank-payer.json")" 1
  run "$DEVSTACK/fund.sh" "$(pubkey_of "$REFUND_KEYS/deployer.json")" 5
  FUNDED="public keys and funding printed"
fi
run "$MAINNET_SCRIPTS/keys.sh" --cluster localnet --keys-dir "$DRY_KEYS"
ok "1 keys.sh: keys created (600 in a 700 dir), $FUNDED"

step "2. preflight.sh --cluster localnet (read-only)"
run "$MAINNET_SCRIPTS/preflight.sh" --cluster localnet --keys-dir "$DRY_KEYS"
ok "2 preflight.sh: GO"

step "3. refund drill: a fresh deploy stopped part way, then its buffer closed (a deployer of its own: $REFUND_KEYS)"
REFUND_DEPLOYER="$(pubkey_of "$REFUND_KEYS/deployer.json")"
paid "$REFUND_DEPLOYER" >/dev/null
REFUND_BEFORE="$(json_of "$FEES_JSON" balance)"
stop_part_way "$REFUND_KEYS"
run "$MAINNET_SCRIPTS/solana.sh" --cluster localnet --keys-dir "$REFUND_KEYS" -- program close "$BUFFER" --recipient "$REFUND_DEPLOYER"
run buffer_state "$REFUND_KEYS"
expect "the closed buffer" "$(json_of "$STATUS_JSON" state)" absent
paid "$REFUND_DEPLOYER"
REFUND_FEES="$(json_of "$FEES_JSON" fees_lamports)"
REFUND_TXS="$(json_of "$FEES_JSON" transactions)"
expect "the deployer after the refund (what it held, less the fees of its $REFUND_TXS transactions)" \
  "$(json_of "$FEES_JSON" balance)" "$((REFUND_BEFORE - REFUND_FEES))"
ok "3 refund: writer killed at $WRITTEN of $TOTAL chunks; solana.sh program close gave back all but the $REFUND_FEES lamports of fees ($REFUND_BEFORE -> $((REFUND_BEFORE - REFUND_FEES)))"

step "4. a fresh deploy stopped part way, then continued by re-running deploy.sh at the same commit"
stop_part_way "$DRY_KEYS"
# No top-up: the deployer holds what the first attempt left, and the buffer holds the rent.
PRE="$("$MAINNET_SCRIPTS/preflight.sh" --cluster localnet --keys-dir "$DRY_KEYS" 2>&1)" || { say "$PRE"; fail "preflight is NO-GO for the half-written buffer"; }
say "$PRE"
[[ "$PRE" == *"$TO_WRITE of $TOTAL chunks still to write"* ]] || fail "preflight did not report $TO_WRITE of $TOTAL chunks still to write"
[[ "$PRE" == *"GO: localnet preflight passed"* ]] || fail "preflight did not say GO"
deploy --keys-dir "$DRY_KEYS" --write-rate "$RATE"
RECEIPT="$(last_receipt fresh)"
expect "chunks the continued run found written" "$(json_of "$RECEIPT" buffer_write.chunks_already_written)" "$WRITTEN"
expect "the buffer after the continued run" "$(json_of "$RECEIPT" buffer_write.verified)" true
expect "transactions the Solana CLI sent (the deploy itself)" "$(json_of "$RECEIPT" cli_phase.transactions)" 1
expect "the deployed bytes" "$(json_of "$RECEIPT" onchain.matches_local_so)" true
ok "4 deploy.sh stopped at $WRITTEN of $TOTAL chunks, re-run with no top-up: preflight GO, the other $TO_WRITE written, the CLI sent 1 transaction, bytes verified, receipt written"

step "5. init-config.sh --cluster localnet (initialize_config + Executor float)"
run "$MAINNET_SCRIPTS/init-config.sh" --cluster localnet --keys-dir "$DRY_KEYS" --yes
run "$MAINNET_SCRIPTS/governance.sh" --cluster localnet --keys-dir "$DRY_KEYS" show
ok "5 init-config.sh: Config created and read back, Executor float funded, receipt written"

if [[ $SMOKE == 1 ]]; then
  step "6. scripts/devstack/smoke.sh against the deployed + initialized program"
  if run "$DEVSTACK/smoke.sh" --keep; then
    ok "6 smoke.sh: SMOKE PASSED"
  else
    SMOKE_RC=$?
    SUMMARY+=("FAIL  6 smoke.sh exited $SMOKE_RC (its output is above)")
  fi
fi

step "7. upgrade drill behind a rate limit: deploy.sh --mode upgrade through an RPC that answers HTTP 429 beyond $SEND_LIMIT sendTransaction a second"
funding_json "$DRY_KEYS" "$(wc -c <"$HD_SO" | tr -d ' ')"
if [[ $TIGHT == 1 ]]; then
  # What the runbook says an upgrade needs: the temporary buffer's rent and the fee budget.
  fund_lamports "$(pubkey_of "$DRY_KEYS/deployer.json")" "$(( $(funding_of rent.upgrade_buffer) + $(deploy_fee_budget) ))"
fi
start_limiter
deploy_limited --keys-dir "$DRY_KEYS" --mode upgrade --write-rate "$LIMITED_RATE"
LIMITER_JSON="$HD_DEVSTACK_HOME/limiter.json"
curl -s -m 5 "$LIMITED_RPC/stats" >"$LIMITER_JSON"
stop_limiter
run "$MAINNET_SCRIPTS/solana.sh" --cluster localnet --keys-dir "$DRY_KEYS" -- program show "$HD_PROGRAM_ID"
RECEIPT="$(last_receipt upgrade)"
REFUSED="$(json_of "$LIMITER_JSON" sends_refused)"
SLOWED="$(json_of "$RECEIPT" buffer_write.slow_downs)"
say "[dry-run] the rate limit let $(json_of "$LIMITER_JSON" sends_passed) sends through and refused $REFUSED; the writer was asked for $LIMITED_RATE a second, counted $SLOWED slow-downs and signed $(json_of "$RECEIPT" buffer_write.signed_again) writes again"
expect "the writer's slow-downs, against the sends the rate limit refused" "$SLOWED" "$REFUSED"
if [[ "$(python3 -c 'import sys; print(int(float(sys.argv[1]) > float(sys.argv[2])))' "$LIMITED_RATE" "$SEND_LIMIT")" == 1 ]]; then
  # Asked for more than the limit lets through: it must have been refused, and slowed down.
  [[ $REFUSED -gt 0 ]] || fail "the rate limit never refused a send: the drill did not test it"
  HOW="$REFUSED sends refused with HTTP 429, the writer slowed down and finished"
else
  # Asked for no more than the limit: a write that waits its turn is never refused.
  expect "sends the rate limit refused at $LIMITED_RATE writes a second" "$REFUSED" 0
  HOW="the writer at $LIMITED_RATE a second was never refused"
fi
expect "the buffer after the rate-limited write" "$(json_of "$RECEIPT" buffer_write.verified)" true
expect "the upgrade buffer's lamports (the rent of 45 + build bytes)" "$(json_of "$RECEIPT" buffer_write.buffer_lamports)" "$(funding_of rent.upgrade_buffer)"
expect "transactions the Solana CLI sent (the upgrade itself)" "$(json_of "$RECEIPT" cli_phase.transactions)" 1
ok "7 deploy.sh --mode upgrade behind a rate limit of $SEND_LIMIT sends a second: $HOW, the CLI sent 1 transaction, bytes verified, receipt written"

step "8. fallback drill: the same upgrade with --cli-only (the Solana CLI writes the buffer itself)"
# The last upgrade's buffer rent is back in the deployer; the buffer is gone, so every chunk is to write.
run buffer_state "$DRY_KEYS"
expect "the buffer after the upgrade" "$(json_of "$STATUS_JSON" state)" absent
CLI_WRITES="$(json_of "$STATUS_JSON" chunks_to_write)"
deploy --keys-dir "$DRY_KEYS" --mode upgrade --cli-only
RECEIPT="$(last_receipt upgrade)"
expect "who wrote the buffer" "$(json_of "$RECEIPT" build.buffer_written_by)" solana-cli
expect "the paced writer's record in a --cli-only receipt" "$(json_of "$RECEIPT" buffer_write)" null
expect "transactions the Solana CLI sent (the buffer, $CLI_WRITES writes, the upgrade)" "$(json_of "$RECEIPT" cli_phase.transactions)" "$((CLI_WRITES + 2))"
ok "8 deploy.sh --mode upgrade --cli-only: the CLI sent $((CLI_WRITES + 2)) transactions itself, bytes verified, receipt written"

step "9. growth drill: an upgrade to a build that outgrew --max-len (this build, padded with zero bytes to $GROWN_LEN bytes)"
# No larger build exists yet, so the same program stands in for one: zero bytes behind it are
# what the ProgramData holds there anyway, and the CLI and the loader take the file as a program
# of $GROWN_LEN bytes. It no longer fits the ProgramData of 45 + $HD_MAX_LEN bytes.
GROWN_SO="$HD_DEVSTACK_HOME/heads_down-grown.so"
python3 - "$HD_SO" "$GROWN_SO" "$GROWN_LEN" <<'PYEOF'
import sys
build = open(sys.argv[1], "rb").read()
open(sys.argv[2], "wb").write(build + bytes(int(sys.argv[3]) - len(build)))
PYEOF
GROW_JSON="$HD_DEVSTACK_HOME/preflight-grown.json"
rm -f "$GROW_JSON"
# (In --tight mode this preflight is NO-GO: the deployer does not hold the larger buffer's rent yet.)
PRE="$("$MAINNET_SCRIPTS/preflight.sh" --cluster localnet --keys-dir "$DRY_KEYS" --mode upgrade --so "$GROWN_SO" --json "$GROW_JSON" 2>&1)" || true
[[ -s "$GROW_JSON" ]] || { say "$PRE"; fail "preflight wrote no figures for the grown build"; }
ADD="$(json_of "$GROW_JSON" funding.programdata_extend_bytes)"
[[ "$ADD" =~ ^[0-9]+$ && $ADD -ge 10240 ]] || { say "$PRE"; fail "preflight does not say the ProgramData grows by at least 10,240 bytes (it says '$ADD')"; }
[[ "$PRE" == *"the upgrade extends it by $ADD bytes"* ]] || { say "$PRE"; fail "preflight did not warn that the upgrade extends the ProgramData by $ADD bytes"; }
GROW_NEED="$(json_of "$GROW_JSON" funding.deployer_need)"
if [[ $TIGHT == 1 ]]; then
  # Exactly what preflight asks for, not a lamport more: its figure has to cover the buffer of
  # the larger build, the rent of the bytes the loader adds, and the fees.
  GROW_HAVE="$(json_of "$GROW_JSON" funding.deployer_balance)"
  [[ $GROW_NEED -gt $GROW_HAVE ]] || fail "the deployer already holds more ($GROW_HAVE) than the growing upgrade needs ($GROW_NEED): the drill cannot fund it to the lamport"
  fund_lamports "$(pubkey_of "$DRY_KEYS/deployer.json")" "$((GROW_NEED - GROW_HAVE))"
fi
deploy_build "$GROWN_SO" --keys-dir "$DRY_KEYS" --mode upgrade --write-rate "$RATE"
RECEIPT="$(last_receipt upgrade)"
expect "the bytes the upgrade added to the ProgramData" "$(json_of "$RECEIPT" build.programdata_extended_bytes)" "$ADD"
expect "the ProgramData's length after the upgrade" "$(json_of "$RECEIPT" programdata_len)" "$((45 + HD_MAX_LEN + ADD))"
expect "the deployed bytes" "$(json_of "$RECEIPT" onchain.matches_local_so)" true
expect "transactions the Solana CLI sent (the extension, then the upgrade)" "$(json_of "$RECEIPT" cli_phase.transactions)" 2
expect "CLI transactions that failed" "$(json_of "$RECEIPT" cli_phase.failed)" 0
ok "9 deploy.sh --mode upgrade with a build of $GROWN_LEN bytes: the ProgramData extended by $ADD bytes, then upgraded at the first try (deployer need $GROW_NEED lamports), bytes verified, receipt written"

step "10. Squads drill: deploy.sh --mode buffer, handing the buffer to governance.json's key as a stand-in vault"
# The upgrades' buffer rent came back to the deployer; this drill's stays in the handed-over buffer.
[[ $TIGHT == 0 ]] || fund_lamports "$(pubkey_of "$DRY_KEYS/deployer.json")" "$(deploy_fee_budget)"
deploy --keys-dir "$DRY_KEYS" --mode buffer --buffer-authority "$(pubkey_of "$DRY_KEYS/governance.json")" --write-rate "$RATE"
RECEIPT="$(last_receipt buffer)"
expect "the handed-over buffer's lamports (the rent of 37 + build bytes)" "$(json_of "$RECEIPT" buffer.lamports)" "$(funding_of rent.buffer)"
expect "transactions the Solana CLI sent (the hand-over)" "$(json_of "$RECEIPT" cli_phase.transactions)" 1
ok "10 deploy.sh --mode buffer: buffer written and handed over, bytes verified, receipt written"

step "11. rollback drill: governance.sh pause (immediate), then show"
run "$MAINNET_SCRIPTS/governance.sh" --cluster localnet --keys-dir "$DRY_KEYS" pause
STATUS_OUT="$(tool_local status)"
printf '%s\n' "$STATUS_OUT" | tee -a "$LOG"
if [[ "$STATUS_OUT" == *"paused true"* ]]; then
  ok "11 governance.sh pause: Config.paused = 1 immediately; un-pause waits for the timelock"
else
  fail "11 Config.paused did not read back as true"
fi
[[ $SMOKE_RC -eq 0 ]] || exit "$SMOKE_RC"
