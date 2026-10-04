#!/usr/bin/env bash
# Build heads_down from a clean, recorded commit, preflight it, deploy it, check the on-chain bytes
# against the build, and write the public receipt to deploy/receipts/<cluster>/.
#
#   scripts/mainnet/deploy.sh [--cluster mainnet|localnet] [--keys-dir DIR] [--mode fresh|upgrade|buffer]
#                             [--max-len N] [--cu-price MICRO_LAMPORTS] [--write-rate TX_PER_SECOND]
#                             [--cli-only] [--max-sign-attempts N] [--buffer-authority PUBKEY]
#                             [--public-rpc] [--skip-build] [--allow-dirty] [--yes]
#
# fresh    first deploy: `solana program deploy --program-id <program keypair> --upgrade-authority
#          deployer.json --max-len N` (the program keypair only signs; it stays where it is)
# upgrade  in-place upgrade signed by the deployer (while it is still the upgrade authority)
# buffer   write a fresh buffer (and hand it to --buffer-authority, e.g. the Squads vault) for a
#          multisig upgrade proposal; nothing is upgraded here
#
# The buffer is written in two steps. First `hd-devstack write-buffer` creates it as the Solana
# CLI would and writes it at --write-rate transactions a second (default 1: Helius' free plan
# allows one sendTransaction a second). Then the CLI runs as before with --buffer: it finds every
# chunk written, sends no write, and sends only its final transaction. --cli-only skips the first
# step and lets the CLI write the buffer itself: about 200 transactions 10 ms apart, which by
# the CLI's source an RPC with that limit lets through a few at a time (not tried on mainnet).
#
# An upgrade to a build that outgrew the deployed ProgramData extends it first, with the CLI's
# own `program extend` (the bytes preflight names), and upgrades once the cluster is some slots
# further: the loader refuses an upgrade in the slot of an extension, and with the buffer
# written beforehand the CLI would send the two back to back.
#
# The buffer keypair is kept per commit in the key dir (buffer-<commit>.json), so a deploy that
# stopped part way continues by re-running this script at the same commit: preflight counts the
# rent and the chunks the buffer already holds, and the CLI never prints a recovery seed phrase.
# --public-rpc (or HD_PUBLIC_RPC=1) uses the public mainnet RPC although helius.env is there.
# --cluster localnet runs the same path against the dev stack (scripts/mainnet/dry-run.sh).
HD_SCRIPT=deploy
source "$(dirname "$0")/lib.sh"

MODE=fresh SKIP_BUILD=0 ALLOW_DIRTY=0 BUFFER_AUTHORITY="" CLI_ONLY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --cluster) set_cluster "$2"; shift 2 ;;
    --keys-dir) KEYS="$2"; shift 2 ;;
    --mode) MODE="$2"; shift 2 ;;
    --max-len) HD_MAX_LEN="$2"; shift 2 ;;
    --cu-price) HD_CU_PRICE="$2"; shift 2 ;;
    --write-rate) HD_WRITE_RATE="$2"; shift 2 ;;
    --cli-only) CLI_ONLY=1; shift ;;
    --max-sign-attempts) HD_MAX_SIGN_ATTEMPTS="$2"; shift 2 ;;
    --buffer-authority) BUFFER_AUTHORITY="$2"; shift 2 ;;
    --public-rpc) PUBLIC_RPC=1; shift ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --allow-dirty) ALLOW_DIRTY=1; shift ;;
    --yes) YES=1; shift ;;
    -h | --help) sed -n '2,32p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done
case "$MODE" in fresh | upgrade | buffer) ;; *) die "--mode must be fresh, upgrade or buffer" ;; esac
[[ "$HD_WRITE_RATE" =~ ^[0-9]+([.][0-9]+)?$ && ! "$HD_WRITE_RATE" =~ ^0+([.]0+)?$ ]] \
  || die "--write-rate must be a number of transactions a second above 0 (got '$HD_WRITE_RATE')"
if [[ -n "$BUFFER_AUTHORITY" ]]; then
  [[ "$MODE" == buffer ]] || die "--buffer-authority only applies to --mode buffer"
  [[ "$BUFFER_AUTHORITY" =~ ^[1-9A-HJ-NP-Za-km-z]{32,44}$ ]] || die "--buffer-authority is not a base58 address: '$BUFFER_AUTHORITY'"
fi
default_keys
need solana "install the Agave CLI 4.1 (~/.local/share/solana/install/active_release/bin)"
need solana-keygen "install the Agave CLI 4.1"
need cargo-build-sbf "install the Agave CLI 4.1"
need perl "perl ships with macOS"
need python3 "python3 ships with the Xcode command line tools"
resolve_rpc
mkdir -p "$HD_STATE"
TS="$(timestamp)"

# ---- 1. a clean, recorded commit ----------------------------------------------------------------
COMMIT="$(git_commit)"
DIRTY="$(git_dirty)"
if [[ "$DIRTY" == 1 ]]; then
  if [[ "$CLUSTER" == mainnet ]]; then die "the tree has local changes: commit or stash them; mainnet deploys only a clean, recorded commit"; fi
  [[ $ALLOW_DIRTY == 1 ]] || die "the tree has local changes (localnet: pass --allow-dirty to deploy them anyway)"
  warn "deploying a dirty tree to localnet (recorded as git_dirty=1)"
fi
if [[ $SKIP_BUILD == 1 && "$CLUSTER" == mainnet ]]; then die "--skip-build is localnet-only: mainnet always builds from the recorded commit"; fi

# ---- 2. build (programs/heads-down/scripts/build.sh, mainnet feature) --------------------------------
if [[ $SKIP_BUILD == 0 ]]; then
  log "building heads_down at ${COMMIT:0:12} (programs/heads-down/scripts/build.sh, --features mainnet)"
  bash "$REPO_ROOT/programs/heads-down/scripts/build.sh" >"$HD_STATE/build-program-$TS.log" 2>&1 \
    || die "program build failed: $HD_STATE/build-program-$TS.log"
  if [[ "$DIRTY" == 0 && "$(git_dirty)" == 1 ]]; then die "the build changed tracked files; refusing to deploy"; fi
fi
[[ -f "$HD_SO" ]] || die "$HD_SO missing"
SO_SHA="$(shasum -a 256 "$HD_SO" | cut -d' ' -f1)"
SO_LEN="$(wc -c <"$HD_SO" | tr -d ' ')"
SOLANA_VER="$(solana --version)"
SBF_VER="$(cargo-build-sbf --version | tr '\n' ' ' | sed 's/ *$//')"
log "built $SO_LEN bytes, sha256 $SO_SHA ($SBF_VER)"

# ---- 3. the per-commit buffer, then preflight (read-only) ----------------------------------------------
# The buffer's address is fixed before preflight, so that a buffer left by a run that stopped part
# way is found and counted: its rent is not needed again, and only the missing chunks are.
key_ok "$K_DEPLOYER" || die "$K_DEPLOYER missing or not mode 600: run scripts/mainnet/keys.sh"
DEPLOYER="$(pubkey_of "$K_DEPLOYER")"
BUFFER_KP="$(buffer_keypair "$KEYS" "$COMMIT")"
if [[ ! -e "$BUFFER_KP" ]]; then
  (umask 077 && solana-keygen new --no-bip39-passphrase --silent --outfile "$BUFFER_KP" >/dev/null)
fi
chmod 600 "$BUFFER_KP"
BUFFER="$(pubkey_of "$BUFFER_KP")"
KEYS_HINT=""
if [[ "$KEYS" != "$HD_MAINNET_KEYS" ]]; then KEYS_HINT=" --keys-dir $KEYS"; fi

PREFLIGHT_JSON="$HD_STATE/preflight-$CLUSTER-$TS.json"
# The fee budget preflight checks depends on the priority fee: hand it the one this deploy uses.
HD_CU_PRICE="$HD_CU_PRICE" HD_PUBLIC_RPC="$PUBLIC_RPC" "$MAINNET_SCRIPTS/preflight.sh" --cluster "$CLUSTER" --keys-dir "$KEYS" --mode "$MODE" \
  --max-len "$HD_MAX_LEN" --so "$HD_SO" --buffer "$BUFFER" --json "$PREFLIGHT_JSON" || die "preflight is NO-GO; nothing was sent"

# ---- 4. plan and confirmation --------------------------------------------------------------------------
if [[ $CLI_ONLY == 1 ]]; then
  WRITER="the Solana CLI itself (--cli-only): its writes go out 10 ms apart, up to $HD_MAX_SIGN_ATTEMPTS signing rounds"
else
  WRITER="hd-devstack write-buffer, at most $HD_WRITE_RATE transaction(s) a second; it continues a buffer that is part written"
fi
# An upgrade to a build that outgrew the deployed ProgramData: the bytes preflight says the
# loader has to add, and the rent they lock (0 and 0 when the build fits, and in the other modes).
EXTEND="$(python3 -c 'import json, sys
f = json.load(open(sys.argv[1]))["funding"]
b, l = f.get("programdata_extend_bytes") or 0, f.get("programdata_growth_lamports") or 0
print(b, "%d.%09d" % (l // 10**9, l % 10**9))' "$PREFLIGHT_JSON")"
EXTEND_BYTES="${EXTEND%% *}" EXTEND_SOL="${EXTEND##* }"
[[ "$EXTEND_BYTES" =~ ^[0-9]+$ ]] || die "could not read the ProgramData growth from $PREFLIGHT_JSON"
echo
bold "deploy plan ($CLUSTER, mode $MODE)"
cat <<EOF
  program id         $HD_PROGRAM_ID
  fee payer          $DEPLOYER (deployer.json)
  upgrade authority  $DEPLOYER (moves to a Squads vault later: docs/DEPLOY.md)
  build              $SO_LEN bytes, sha256 $SO_SHA, commit $COMMIT (dirty=$DIRTY)
  max-len            $([[ $MODE == fresh ]] && echo "$HD_MAX_LEN bytes" || echo "n/a ($MODE)")
  buffer             $BUFFER (buffer-${COMMIT:0:12}.json; what it already holds is in the preflight above)
  buffer written by  $WRITER
  priority fee       $HD_CU_PRICE micro-lamports/CU
  RPC                $RPC_HOST
EOF
if [[ "$EXTEND_BYTES" -gt 0 ]]; then
  echo "  ProgramData        extended by $EXTEND_BYTES bytes before the upgrade (the build outgrew it): $EXTEND_SOL SOL of rent, locked like the rest"
fi
WHAT_SPENT="this $MODE spends real SOL from $DEPLOYER"
if [[ "$MODE" == buffer && -n "$BUFFER_AUTHORITY" ]]; then
  # What a wrong address would lose: the lamports the buffer holds, or will be created with
  # (lib.sh: handover_plan, handover_confirm).
  HANDED_SOL="$(python3 -c 'import json, sys
f = json.load(open(sys.argv[1]))["funding"]
l = f.get("buffer_lamports") or f["rent"]["buffer"]
print("%d.%09d" % (l // 10**9, l % 10**9))' "$PREFLIGHT_JSON")"
  handover_plan "$BUFFER_AUTHORITY" "$HANDED_SOL"
  WHAT_SPENT="$(handover_confirm "$BUFFER_AUTHORITY" "$HANDED_SOL" "$DEPLOYER")"
elif [[ "$MODE" == buffer ]]; then
  echo "  buffer authority   $DEPLOYER (unchanged: the buffer stays the deployer's)"
fi
confirm "deploy heads_down to mainnet" "$WHAT_SPENT"

# ---- 5. deploy ----------------------------------------------------------------------------------------
solana_cfg "$K_DEPLOYER"
BAL_BEFORE="$(scli balance "$DEPLOYER" --lamports | awk '{print $1}')"
OUT="$HD_STATE/deploy-$CLUSTER-$TS.out"
COMMON=(--use-rpc --with-compute-unit-price "$HD_CU_PRICE" --max-sign-attempts "$HD_MAX_SIGN_ATTEMPTS" --commitment confirmed --output json-compact)
# signature_of FILE: the "signature" of the last JSON object the CLI printed (one line with
# json-compact, several with json). Kept out of any command substitution: bash 3.2, the macOS
# default, misparses a here-document inside one.
signature_of() {
  python3 - "$1" <<'PY'
import json
import sys

lines = open(sys.argv[1]).read().splitlines()
for i in range(len(lines) - 1, -1, -1):
    if lines[i].lstrip().startswith("{"):
        try:
            print(json.loads("\n".join(lines[i:])).get("signature") or "")
            break
        except ValueError:
            continue
PY
}
recover_hint() {
  cat >&2 <<EOF

Nothing is lost. If the buffer $BUFFER was created, it is the deployer's, and
it keeps the chunks that were written and the rent that was put into it:
  continue  re-run this script at the same commit (it reuses buffer-${COMMIT:0:12}.json). Preflight
            counts what the buffer holds, so no more SOL is needed for it.
  refund    scripts/mainnet/solana.sh --cluster $CLUSTER$KEYS_HINT -- program close $BUFFER --recipient $DEPLOYER
  look      scripts/mainnet/preflight.sh --cluster $CLUSTER$KEYS_HINT --mode $MODE
            (its "buffer" line says how many chunks are still to write)
If the RPC key has no credits left, add --public-rpc to any of the three.
EOF
}

# 5a. the buffer, written at a pace the RPC accepts (the default).
VERIFY_EXTRA=()
if [[ $CLI_ONLY == 0 ]]; then
  WRITER_JSON="$HD_STATE/write-buffer-$CLUSTER-$TS.json"
  WRITER_OUT="$HD_STATE/write-buffer-$CLUSTER-$TS.out"
  set +e
  tool write-buffer --so "$HD_SO" --buffer "$BUFFER_KP" --authority "$K_DEPLOYER" --mode "$MODE" --max-len "$HD_MAX_LEN" \
    --rate "$HD_WRITE_RATE" --cu-price "$HD_CU_PRICE" --json "$WRITER_JSON" --yes | tee "$WRITER_OUT"
  RC=${PIPESTATUS[0]}
  set -e
  if [[ $RC -ne 0 ]]; then
    recover_hint
    die "the buffer write stopped (exit $RC); log $WRITER_OUT"
  fi
  VERIFY_EXTRA+=(--buffer-write "$WRITER_JSON")
fi

# 5b. the Solana CLI: with the buffer written it sends its final transaction only. Its
# transactions are counted from this slot on, into the receipt.
confirmed_slot() { scli slot --commitment confirmed | awk 'NR==1 && /^[0-9]+$/ {print $1}'; }
CLI_SINCE="$(confirmed_slot)" || true
if [[ "$CLI_SINCE" =~ ^[0-9]+$ ]]; then
  VERIFY_EXTRA+=(--cli-since-slot "$CLI_SINCE")
else
  warn "could not read the current slot: the receipt will not count the CLI's transactions"
fi

# An upgrade that outgrew the ProgramData. Left to itself the CLI extends it and upgrades right
# after, and with the buffer written beforehand no write lies between the two. It then stopped
# with "invalid program argument" once the extension had landed, four times out of four on a
# local validator, and a second run upgraded. By the sources, the loader refuses an upgrade in
# the slot of an extension and the CLI simulates the upgrade at the newest confirmed slot, which
# is still that one. So the extension goes out here, as the CLI's own `program extend`, and the
# upgrade follows when the cluster is some slots further.
if [[ "$EXTEND_BYTES" -gt 0 ]]; then
  log "extending the ProgramData of $HD_PROGRAM_ID by $EXTEND_BYTES bytes ($EXTEND_SOL SOL of rent)"
  EXTEND_OUT="$HD_STATE/extend-$CLUSTER-$TS.out"
  set +e
  # (program extend takes no priority-fee flag; it is one small transaction.)
  scli program extend "$HD_PROGRAM_ID" "$EXTEND_BYTES" --keypair "$K_DEPLOYER" --commitment confirmed | tee "$EXTEND_OUT"
  RC=${PIPESTATUS[0]}
  set -e
  if [[ $RC -ne 0 ]]; then
    recover_hint
    die "solana program extend failed (exit $RC); log $EXTEND_OUT"
  fi
  VERIFY_EXTRA+=(--meta "programdata_extended_bytes=$EXTEND_BYTES")
  # Eight slots (about three seconds) past the slot the extension is confirmed in.
  EXTENDED_AT="$(confirmed_slot)" || true
  if [[ "$EXTENDED_AT" =~ ^[0-9]+$ ]]; then
    NOW="$EXTENDED_AT"
    for _ in $(seq 1 120); do
      if [[ "$NOW" =~ ^[0-9]+$ && $NOW -ge $((EXTENDED_AT + 8)) ]]; then break; fi
      sleep 0.5
      NOW="$(confirmed_slot)" || true
    done
    [[ "$NOW" =~ ^[0-9]+$ && $NOW -ge $((EXTENDED_AT + 8)) ]] \
      || warn "the cluster did not move 8 slots on in a minute; if the upgrade is refused, run this script again"
  else
    warn "could not read the slot after the extension; waiting 10 s before the upgrade"
    sleep 10
  fi
fi
set +e
case "$MODE" in
  fresh)
    WHAT="solana program deploy"
    scli program deploy "${COMMON[@]}" --program-id "$HD_PROGRAM_KEYPAIR" --upgrade-authority "$K_DEPLOYER" \
      --keypair "$K_DEPLOYER" --buffer "$BUFFER_KP" --max-len "$HD_MAX_LEN" "$HD_SO" | tee "$OUT"
    RC=${PIPESTATUS[0]}
    ;;
  upgrade)
    WHAT="solana program deploy (upgrade)"
    scli program deploy "${COMMON[@]}" --program-id "$HD_PROGRAM_ID" --upgrade-authority "$K_DEPLOYER" \
      --keypair "$K_DEPLOYER" --buffer "$BUFFER_KP" "$HD_SO" | tee "$OUT"
    RC=${PIPESTATUS[0]}
    ;;
  buffer)
    WHAT="solana program write-buffer"
    scli program write-buffer "${COMMON[@]}" --buffer "$BUFFER_KP" --keypair "$K_DEPLOYER" "$HD_SO" | tee "$OUT"
    RC=${PIPESTATUS[0]}
    ;;
esac
set -e
if [[ $RC -ne 0 ]]; then
  recover_hint
  die "$WHAT failed (exit $RC); log $OUT"
fi
SIG="$(signature_of "$OUT")"
[[ -n "$SIG" || "$MODE" == buffer ]] || warn "no transaction signature in the CLI output ($OUT); the receipt will not carry one"
if [[ "$MODE" == buffer && -n "$BUFFER_AUTHORITY" ]]; then
  log "handing the buffer to $BUFFER_AUTHORITY"
  # (set-buffer-authority takes no priority-fee flag; it is one small transaction.)
  scli program set-buffer-authority "$BUFFER" --new-buffer-authority "$BUFFER_AUTHORITY" \
    --buffer-authority "$K_DEPLOYER" --keypair "$K_DEPLOYER" --commitment confirmed | tee -a "$OUT"
fi

# ---- 6. verify the bytes and write the receipt ------------------------------------------------------
mkdir -p "$RECEIPTS/$CLUSTER"
RECEIPT="$RECEIPTS/$CLUSTER/$TS-$MODE-${COMMIT:0:12}.json"
# The receipt is public: record the build's path relative to the repository, not this machine's.
cd "$REPO_ROOT"
SO_ARG="$HD_SO"
if [[ "$HD_SO" == "$REPO_ROOT"/* ]]; then SO_ARG="${HD_SO#"$REPO_ROOT"/}"; fi
VERIFY=(--mode "$MODE" --so "$SO_ARG" --program-id "$HD_PROGRAM_ID" --deployer "$DEPLOYER" --balance-before "$BAL_BEFORE"
  --meta "git_commit=$COMMIT" --meta "git_dirty=$DIRTY" --meta "build_script=programs/heads-down/scripts/build.sh"
  --meta "cargo_features=mainnet" --meta "solana_cli=$SOLANA_VER" --meta "cargo_build_sbf=$SBF_VER"
  --meta "cu_price_micro_lamports=$HD_CU_PRICE" --meta "preflight=GO"
  --meta "buffer_written_by=$([[ $CLI_ONLY == 1 ]] && echo solana-cli || echo hd-devstack-write-buffer)" --out "$RECEIPT")
VERIFY+=(${VERIFY_EXTRA[@]+"${VERIFY_EXTRA[@]}"})
if [[ -n "$SIG" ]]; then VERIFY+=(--signature "$SIG"); fi
case "$MODE" in
  fresh) VERIFY+=(--max-len "$HD_MAX_LEN") ;;
  buffer) VERIFY+=(--buffer "$BUFFER" --authority "${BUFFER_AUTHORITY:-$DEPLOYER}") ;;
esac
tool verify-deploy "${VERIFY[@]}"
[[ -s "$RECEIPT" ]] || die "verify-deploy wrote no receipt ($RECEIPT): treat this deploy as unverified"

echo
bold "done: $MODE on $CLUSTER"
echo "  receipt   ${RECEIPT#"$REPO_ROOT"/}"
if [[ "$MODE" == fresh ]]; then
  echo "  next      scripts/mainnet/init-config.sh --cluster $CLUSTER$KEYS_HINT"
elif [[ "$MODE" == buffer ]]; then
  echo "  next      propose the upgrade in Squads: program $HD_PROGRAM_ID, buffer $BUFFER, spill $DEPLOYER"
fi
if [[ "$CLUSTER" == mainnet ]]; then echo "  commit    git add deploy/receipts/mainnet && git commit -m 'chore(deploy): mainnet receipt ${COMMIT:0:12}'"; fi
