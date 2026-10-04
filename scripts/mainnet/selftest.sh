#!/usr/bin/env bash
# Checks of scripts/mainnet that need no cluster and no real key: which RPC a script picks, the
# pins on the three funded addresses, the buffer hand-over warning, the fee budget, and the
# argument checks of deploy.sh. Everything runs in a temporary directory with throwaway keys, a
# made-up Helius key and a HOME of its own; ~/.config/heads-down is never read and nothing is sent.
#
#   scripts/mainnet/selftest.sh [--live]
#
# --live adds one read-only call to the public mainnet RPC (`governance.sh --public-rpc show`).
HD_SCRIPT=selftest
# What is checked is what lib.sh ships, not what the caller's environment overrides.
unset HD_MAINNET_KEYS HD_PUBLIC_RPC HD_EXPECTED_DEPLOYER HD_EXPECTED_CRANK_PAYER HD_EXPECTED_GOVERNANCE
source "$(dirname "$0")/lib.sh"

LIVE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --live) LIVE=1; shift ;;
    -h | --help) sed -n '2,9p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done
need solana "install the Agave CLI 4.1"
need solana-keygen "install the Agave CLI 4.1"
need perl "perl ships with macOS"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/hd-selftest.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
PASSED=0 FAILED=0
pass() { PASSED=$((PASSED + 1)); printf 'ok    %s\n' "$1"; }
flunk() { FAILED=$((FAILED + 1)); printf 'FAIL  %s\n        %s\n' "$1" "$2"; }
# has NAME TEXT NEEDLE / hasnt NAME TEXT NEEDLE / is NAME GOT WANT
has() { if [[ "$2" == *"$3"* ]]; then pass "$1"; else flunk "$1" "missing: $3"; fi; }
hasnt() { if [[ "$2" != *"$3"* ]]; then pass "$1"; else flunk "$1" "must not contain: $3"; fi; }
is() { if [[ "$2" == "$3" ]]; then pass "$1"; else flunk "$1" "got '$2', expected '$3'"; fi; }
# out CMD...: run it, keep stdout and stderr (colours removed) in $OUT and the exit code in $RC.
out() {
  RC=0
  OUT="$("$@" 2>&1)" || RC=$?
  OUT="$(printf '%s\n' "$OUT" | perl -pe 's/\e\[[0-9;]*m//g')"
}
newkey() { (umask 077 && solana-keygen new --no-bip39-passphrase --silent --outfile "$1" >/dev/null); }

# A HOME of its own: the default mainnet key directory, with throwaway keys in it.
FAKE_HOME="$TMP/home"
K="$FAKE_HOME/.config/heads-down/mainnet"
mkdir -p "$K"
chmod 700 "$K"
for k in deployer crank-payer governance registrar; do newkey "$K/$k.json"; done
newkey "$FAKE_HOME/.config/heads-down/heads_down-program-keypair.json"
D="$(pubkey_of "$K/deployer.json")"
C="$(pubkey_of "$K/crank-payer.json")"
G="$(pubkey_of "$K/governance.json")"
newkey "$TMP/other.json"
OTHER="$(pubkey_of "$TMP/other.json")"
FAKE_KEY="selftest-not-a-key-0123456789"
printf 'HELIUS_API_KEY=%s\n' "$FAKE_KEY" >"$TMP/helius.env"
chmod 600 "$TMP/helius.env"
# in_home [VAR=VALUE...] SCRIPT ARGS...: a script of this directory with the HOME above, and
# without the overrides the caller's environment may carry.
in_home() {
  env -u HD_MAINNET_KEYS -u HD_PROGRAM_KEYPAIR -u HD_PUBLIC_RPC -u HD_EXPECTED_DEPLOYER -u HD_EXPECTED_CRANK_PAYER \
    -u HD_EXPECTED_GOVERNANCE HOME="$FAKE_HOME" HD_HELIUS_ENV="$TMP/helius.env" "$@"
}

bold "which RPC a script uses"
# rpc_of CLUSTER PUBLIC_RPC HELIUS_ENV [public-ok]: the URL and the host line resolve_rpc picks.
rpc_of() { (CLUSTER="$1" PUBLIC_RPC="$2" HD_HELIUS_ENV="$3"; resolve_rpc ${4:+"$4"} && printf '%s\n%s\n' "$RPC_URL" "$RPC_HOST") 2>&1 || true; }
OUT="$(rpc_of mainnet 0 "$TMP/helius.env")"
has "mainnet: Helius by default" "$OUT" "https://mainnet.helius-rpc.com/?api-key=$FAKE_KEY"
has "mainnet: the host line names Helius, not the key" "$(printf '%s\n' "$OUT" | tail -1)" "https://mainnet.helius-rpc.com (Helius, key from helius.env)"
hasnt "mainnet: the host line does not carry the key" "$(printf '%s\n' "$OUT" | tail -1)" "$FAKE_KEY"
OUT="$(rpc_of mainnet 1 "$TMP/helius.env")"
is "--public-rpc: the public RPC although helius.env is well-formed" "$(printf '%s\n' "$OUT" | head -1)" "https://api.mainnet-beta.solana.com"
has "--public-rpc: the host line says it was asked for" "$OUT" "public RPC, asked for with --public-rpc; helius.env is not used"
hasnt "--public-rpc: the key is not in the URL" "$OUT" "$FAKE_KEY"
OUT="$(rpc_of mainnet 1 "$TMP/absent.env")"
is "--public-rpc: works without helius.env" "$(printf '%s\n' "$OUT" | head -1)" "https://api.mainnet-beta.solana.com"
OUT="$(rpc_of mainnet 0 "$TMP/absent.env")"
has "no helius.env and no switch: refused" "$OUT" "missing $TMP/absent.env"
OUT="$(rpc_of mainnet 0 "$TMP/absent.env" public-ok)"
has "read-only scripts still fall back when helius.env is missing" "$OUT" "public; helius.env missing or invalid"
OUT="$(rpc_of localnet 1 "$TMP/helius.env")"
is "localnet: the switch changes nothing" "$(printf '%s\n' "$OUT" | head -1)" "http://127.0.0.1:${HD_RPC_PORT:-8899}"
OUT="$(rpc_of mainnet yes "$TMP/helius.env")"
has "HD_PUBLIC_RPC must be 0 or 1" "$OUT" "HD_PUBLIC_RPC must be 0 or 1"

# solana.sh end to end, without a network call: `solana config get` prints the URL the CLI was given.
out in_home "$MAINNET_SCRIPTS/solana.sh" -- config get
has "solana.sh: Helius by default" "$OUT" "RPC URL: https://mainnet.helius-rpc.com/?api-key=<redacted>"
has "solana.sh: names the RPC in use" "$OUT" "[solana] RPC: https://mainnet.helius-rpc.com (Helius, key from helius.env)"
hasnt "solana.sh: the key is redacted" "$OUT" "$FAKE_KEY"
out in_home "$MAINNET_SCRIPTS/solana.sh" --public-rpc -- config get
has "solana.sh --public-rpc: the CLI gets the public RPC" "$OUT" "RPC URL: https://api.mainnet-beta.solana.com"
has "solana.sh --public-rpc: names the RPC in use" "$OUT" "[solana] RPC: https://api.mainnet-beta.solana.com (public RPC, asked for with --public-rpc"
out in_home HD_PUBLIC_RPC=1 "$MAINNET_SCRIPTS/solana.sh" -- config get
has "solana.sh with HD_PUBLIC_RPC=1: the CLI gets the public RPC" "$OUT" "RPC URL: https://api.mainnet-beta.solana.com"
OUT="$(in_home "$MAINNET_SCRIPTS/solana.sh" --public-rpc -- config get 2>/dev/null)"
hasnt "solana.sh: the RPC line goes to stderr, not into the CLI's output" "$OUT" "[solana] RPC"
# The other operator scripts take the switch (they stop later, before any network call).
out in_home "$MAINNET_SCRIPTS/governance.sh" --public-rpc
has "governance.sh takes --public-rpc" "$OUT" "usage: governance.sh"
out in_home "$MAINNET_SCRIPTS/init-config.sh" --public-rpc --keys-dir "$TMP/none"
has "init-config.sh takes --public-rpc" "$OUT" "deployer.json missing or not mode 600"
out in_home "$MAINNET_SCRIPTS/preflight.sh" --public-rpc --so "$TMP/none.so"
has "preflight.sh --public-rpc: helius.env is not needed" "$OUT" "not used: --public-rpc"

bold "the three funded addresses are pinned (mainnet, default key directory)"
keys_sh() { in_home HD_EXPECTED_DEPLOYER="$1" HD_EXPECTED_CRANK_PAYER="$2" HD_EXPECTED_GOVERNANCE="$3" "$MAINNET_SCRIPTS/keys.sh" --no-funding "${@:4}"; }
out keys_sh "$D" "$C" "$G"
is "keys.sh: matching key files pass" "$RC" 0
has "keys.sh: says the addresses were checked" "$OUT" "derive the funded addresses"
out keys_sh "$D" "$OTHER" "$G"
is "keys.sh: a crank-payer.json that derives another address stops it" "$RC" 1
has "keys.sh: names the file and both addresses" "$OUT" "STOP: crank-payer.json derives $C, not the funded address $OTHER"
has "keys.sh: says how to override on purpose" "$OUT" "set HD_EXPECTED_CRANK_PAYER=$C"
hasnt "keys.sh: prints no funding table after a mismatch" "$OUT" "Funding for the first"
out keys_sh "$D" "$C" "$OTHER"
is "keys.sh: a governance.json that derives another address stops it" "$RC" 1
has "keys.sh: names governance.json" "$OUT" "STOP: governance.json derives $G, not the funded address $OTHER"
out keys_sh "$OTHER" "$C" "$G"
has "keys.sh: names deployer.json" "$OUT" "STOP: deployer.json derives $D, not the funded address $OTHER"
# A key file that was lost: keys.sh creates a new one, which cannot be the funded address.
mv "$K/crank-payer.json" "$TMP/crank-payer.lost"
out keys_sh "$D" "$C" "$G"
is "keys.sh: a re-created crank-payer.json stops it" "$RC" 1
has "keys.sh: the re-created key is not the funded address" "$OUT" "not the funded address $C"
mv "$TMP/crank-payer.lost" "$K/crank-payer.json"
# The pins shipped in lib.sh are the addresses of the funding table in docs/DEPLOY.md.
out in_home "$MAINNET_SCRIPTS/keys.sh" --no-funding
is "keys.sh: throwaway keys do not pass the shipped pins" "$RC" 1
for a in "$HD_EXPECTED_DEPLOYER" "$HD_EXPECTED_CRANK_PAYER" "$HD_EXPECTED_GOVERNANCE"; do
  has "keys.sh: the shipped pin $a is checked" "$OUT" "not the funded address $a"
  has "docs/DEPLOY.md names $a" "$(cat "$REPO_ROOT/docs/DEPLOY.md")" "| \`$a\` |"
done
# Not for localnet, and not for a key directory that is not the default one.
cp -Rp "$K" "$TMP/custom"
out keys_sh "$OTHER" "$OTHER" "$OTHER" --keys-dir "$TMP/custom"
is "keys.sh --keys-dir: the pins do not apply" "$RC" 0
has "keys.sh --keys-dir: says so" "$OUT" "funded addresses not checked"
out in_home HD_MAINNET_KEYS="$TMP/custom" HD_EXPECTED_DEPLOYER="$OTHER" "$MAINNET_SCRIPTS/keys.sh" --no-funding
is "keys.sh with HD_MAINNET_KEYS: the pins do not apply" "$RC" 0
out keys_sh "$OTHER" "$OTHER" "$OTHER" --cluster localnet --keys-dir "$TMP/custom"
is "keys.sh --cluster localnet: the pins do not apply" "$RC" 0
hasnt "keys.sh --cluster localnet: no word about pins" "$OUT" "funded address"

# preflight.sh: one line per pinned key (the chain checks are skipped: there is no build at --so).
preflight_sh() { in_home HD_EXPECTED_DEPLOYER="$1" HD_EXPECTED_CRANK_PAYER="$2" HD_EXPECTED_GOVERNANCE="$3" "$MAINNET_SCRIPTS/preflight.sh" --so "$TMP/none.so" "${@:4}"; }
out preflight_sh "$D" "$C" "$G"
has "preflight.sh: PASS for the deployer" "$OUT" "PASS  deployer address       $D is the funded address"
has "preflight.sh: PASS for the crank payer" "$OUT" "PASS  crank-payer address    $C is the funded address"
has "preflight.sh: PASS for governance" "$OUT" "PASS  governance address     $G is the funded address"
out preflight_sh "$D" "$OTHER" "$OTHER"
is "preflight.sh: NO-GO" "$RC" 1
has "preflight.sh: FAIL for the crank payer" "$OUT" "FAIL  crank-payer address    crank-payer.json derives $C, not the funded address $OTHER"
has "preflight.sh: FAIL for governance" "$OUT" "FAIL  governance address     governance.json derives $G, not the funded address $OTHER"
has "preflight.sh: the deployer still passes" "$OUT" "PASS  deployer address       $D"
out preflight_sh "$OTHER" "$OTHER" "$OTHER" --keys-dir "$TMP/custom"
has "preflight.sh --keys-dir: the pins do not apply, and it says so" "$OUT" "INFO  funded addresses       not checked"
hasnt "preflight.sh --keys-dir: no pin failure" "$OUT" "not the funded address"
out preflight_sh "$OTHER" "$OTHER" "$OTHER" --cluster localnet --keys-dir "$TMP/custom"
hasnt "preflight.sh --cluster localnet: no word about pins" "$OUT" "funded address"

bold "handing a buffer over: the address on a line of its own"
OUT="$(handover_plan "$OTHER" 0.966282040)"
is "plan: the address is alone on its line" "$(printf '%s\n' "$OUT" | grep -c "^      $OTHER\$")" 1
has "plan: says the rent is lost for good" "$OUT" "the 0.966282040 SOL are gone for good"
OUT="$(handover_confirm "$OTHER" 0.966282040 "$D")"
is "confirmation: the address is alone on its line" "$(printf '%s\n' "$OUT" | grep -c "^    $OTHER\$")" 1
has "confirmation: says it is for good" "$OUT" "That is for good, also if the address is mistyped"
# deploy.sh checks its arguments before it touches a key or the network.
out in_home "$MAINNET_SCRIPTS/deploy.sh" --cluster localnet --mode buffer --buffer-authority "not-an-address"
has "deploy.sh: a --buffer-authority that is not base58 is refused" "$OUT" "--buffer-authority is not a base58 address"
out in_home "$MAINNET_SCRIPTS/deploy.sh" --cluster localnet --buffer-authority "$OTHER"
has "deploy.sh: --buffer-authority outside --mode buffer is refused" "$OUT" "only applies to --mode buffer"
for bad in 0 0.0 fast -1; do
  out in_home "$MAINNET_SCRIPTS/deploy.sh" --cluster localnet --write-rate "$bad"
  has "deploy.sh: --write-rate $bad is refused" "$OUT" "--write-rate must be a number"
done

bold "the fee budget"
is "one transaction is budgeted at 5,000 lamports + 1.4M CU at the price" "$(HD_CU_PRICE=100000 deploy_fee_per_tx)" 145000
is "the budget for max-len 196,608" "$(HD_CU_PRICE=100000 HD_MAX_LEN=196608 deploy_fee_budget)" 32335000
is "without a priority fee" "$(HD_CU_PRICE=0 HD_MAX_LEN=196608 deploy_fee_budget)" 1115000
is "HD_DEPLOY_FEE_BUDGET overrides the total" "$(HD_DEPLOY_FEE_BUDGET=7 deploy_fee_budget)" 7
# A build that fits max-len changes nothing; a larger one (an upgrade that outgrew it) has more
# writes than the max-len budget covers: 250,000 bytes are 261 writes of 960 bytes, for which
# the CLI wants 150,000 + 261 x 145,000 + 145,000 lamports in the payer.
is "a build that fits max-len: the same budget" "$(HD_CU_PRICE=100000 HD_MAX_LEN=196608 deploy_fee_budget_for 190048)" 32335000
is "a build larger than max-len: budgeted for its own size" "$(HD_CU_PRICE=100000 HD_MAX_LEN=196608 deploy_fee_budget_for 250000)" 40890000
is "HD_DEPLOY_FEE_BUDGET overrides that too" "$(HD_DEPLOY_FEE_BUDGET=7 HD_MAX_LEN=196608 deploy_fee_budget_for 250000)" 7
is "the buffer keypair is per key directory and commit" "$(buffer_keypair /k 0123456789abcdef0123)" "/k/buffer-0123456789ab.json"

if [[ $LIVE == 1 ]]; then
  bold "live: governance.sh --public-rpc show (read-only, public mainnet RPC)"
  out in_home "$MAINNET_SCRIPTS/governance.sh" --public-rpc show
  is "governance.sh --public-rpc show: answers" "$RC" 0
  has "governance.sh --public-rpc: names the RPC in use" "$OUT" "[governance] RPC: https://api.mainnet-beta.solana.com (public RPC, asked for with --public-rpc"
  has "governance.sh --public-rpc: the tool talks to the public RPC" "$OUT" "cluster mainnet via https://api.mainnet-beta.solana.com"
  hasnt "governance.sh --public-rpc: Helius is not used" "$OUT" "helius-rpc"
fi

echo
if [[ $FAILED -eq 0 ]]; then
  bold "selftest: $PASSED checks passed"
else
  printf '\033[31;1mselftest: %s of %s checks FAILED\033[0m\n' "$FAILED" "$((PASSED + FAILED))"
  exit 1
fi
