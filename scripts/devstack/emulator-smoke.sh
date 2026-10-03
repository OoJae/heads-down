#!/usr/bin/env bash
# The real Android app, in the loop: on a running emulator (or a phone), against the local stack.
#
#   scripts/devstack/emulator-smoke.sh [--serial <adb serial>] [--apk PATH] [--keep-data]
#                                      [--wallet <fakewallet.apk>]
#
# What it does, with the localdev APK:
#   1. installs it, clears its data (unless --keep-data) and walks the setup screens: notifications,
#      background running, the Quick Settings tile, the rig key (created in the device's Keystore);
#   2. reads the key from the app's debug screen, arms a rig for it on the local fork with a
#      Mac-held dev wallet (clock-in.sh), and attaches the app to that rig;
#   3. puts the device face-down, on the charger, screen off, and waits until the crank has
#      accepted a heartbeat signed by that Keystore key and landed a dig on-chain;
#   4. lifts it (upright, screen on) and waits until the BREAK the app signs has landed.
#
# With --wallet, a wallet app signs instead of the Mac: Solana Mobile's fakewallet (build it from
# github.com/solana-mobile/mobile-wallet-adapter, android/: ./gradlew :fakewallet:assembleDebug,
# and pass fakewallet-v1-debug.apk). Step 2 becomes the app's own "Clock in" (wallet approval,
# the app submits and confirms), and after step 4 the same wallet signs:
#   5. the clock-out that ends the shift early;
#   6. taking the SOL back out of the ORE Automation and closing the rig, checked against the
#      wallet's balance to the lamport;
#   7. a second clock-in, over the tombstone the closed rig left: it must arm shift 2.
#
# Needs: the stack up (up.sh), `adb` with one device, and for step 3 an emulator: the sensor and
# power commands are emulator console commands (`adb emu`). On a phone, do step 3 and 4 by hand
# when asked. Start an emulator with, for example:
#   $ANDROID_HOME/emulator/emulator -avd <name> -no-window -no-audio -no-boot-anim
# Android 14 with Google APIs (arm64) was the image this was written against.
source "$(dirname "$0")/lib.sh"

SERIAL="" APK="$REPO_ROOT/android/app/build/outputs/apk/localdev/app-localdev.apk" KEEP=0 WALLET_APK=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    --keep-data) KEEP=1; shift ;;
    --wallet) WALLET_APK="$2"; shift 2 ;;
    -h | --help) sed -n '2,28p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done
# adb is often installed with the SDK and not on PATH.
for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
  if [[ -n "$d" && -x "$d/platform-tools/adb" ]]; then PATH="$d/platform-tools:$PATH"; fi
done
need adb "brew install android-platform-tools"
[[ -z "$SERIAL" ]] || export ANDROID_SERIAL="$SERIAL"
adb get-state >/dev/null 2>&1 || die "no device: start an emulator or plug in a phone (adb devices)"
[[ -f "$APK" ]] || die "no APK at $APK: cd android && ./gradlew :app:assembleLocaldev"
[[ -z "$WALLET_APK" || -f "$WALLET_APK" ]] || die "no wallet APK at $WALLET_APK"
curl -fsS -m 5 "http://127.0.0.1:$HD_CRANK_PORT/healthz" >/dev/null || die "the stack is not up: scripts/devstack/up.sh"

PKG=xyz.headsdown.localdev
UI="$DEVSTACK_DIR/emulator/ui.py"
ui() { python3 "$UI" "$@"; }
step() { printf '\033[1m[emulator-smoke +%4ss]\033[0m %s\n' "$((SECONDS))" "$*"; }
metric() { curl -fsS -m 5 "http://127.0.0.1:$HD_CRANK_PORT/metrics" | awk -v m="$1" '$1 == m { print int($2); found = 1 } END { if (!found) print 0 }'; }
wait_metric() { # name, floor, seconds: until the metric is above the floor
  local t=0
  until [[ "$(metric "$1")" -gt "$2" ]]; do
    sleep 3; t=$((t + 3))
    [[ $t -lt $3 ]] || die "$1 did not move past $2 in $3 s (crank log: $LOGS/crank.log)"
  done
}
crashed() { adb logcat -d -s AndroidRuntime:E | grep -c "FATAL EXCEPTION" || true; }
# The Rig's state byte (INTERFACE: offset 75), by name.
rig_state() {
  curl -fsS -m 10 "$RPC_URL" -H 'content-type: application/json' \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"getAccountInfo\",\"params\":[\"$1\",{\"encoding\":\"base64\",\"commitment\":\"confirmed\"}]}" |
    python3 -c 'import base64, json, sys
data = base64.b64decode(json.load(sys.stdin)["result"]["value"]["data"][0])
print(["Idle", "Armed", "Down", "Cooling", "Broken", "Frozen"][data[75]])'
}
is_emulator() { [[ "$(adb shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')" == 1 ]]; }
rpc() { curl -fsS -m 10 "$RPC_URL" -H 'content-type: application/json' -d "$1"; }
balance() { rpc "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"getBalance\",\"params\":[\"$1\",{\"commitment\":\"confirmed\"}]}" | python3 -c 'import json, sys; print(json.load(sys.stdin)["result"]["value"])'; }
# The Rig account whose authority (offset 8) is $1: "<address> <size> <shift_id>", or nothing.
rig_of() {
  rpc "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"getProgramAccounts\",\"params\":[\"$HD_PROGRAM_ID\",{\"encoding\":\"base64\",\"commitment\":\"confirmed\",\"filters\":[{\"dataSize\":384},{\"memcmp\":{\"offset\":8,\"bytes\":\"$1\"}}]}]}" |
    python3 -c 'import base64, json, struct, sys
for a in json.load(sys.stdin)["result"]:
    d = base64.b64decode(a["account"]["data"][0])
    print(a["pubkey"], len(d), struct.unpack_from("<Q", d, 200)[0])'
}
# The wallet app is in front after an action of ours: approve what it asks.
wallet_approve() {
  wait_ui "Fake Wallet app" 30
  sleep 2
  if ui has "AUTHORIZE DAPP"; then ui tap "AUTHORIZE" exact >/dev/null; sleep 4; fi
  wait_ui "SIGN TRANSACTION(S)" 30
  ui tap "AUTHORIZE" exact >/dev/null
  sleep 12
}
wait_ui() { # text, seconds: until it is on screen
  local t=0
  until ui has "$1"; do
    sleep 1; t=$((t + 1))
    [[ $t -lt ${2:-20} ]] || die "'$1' did not appear: $(ui texts | head -n 6)"
  done
}
home() { adb shell am start -n "$PKG/xyz.headsdown.MainActivity" >/dev/null 2>&1; sleep 3; adb shell input swipe 540 700 540 1900 250; sleep 1; adb shell input swipe 540 700 540 1900 250; sleep 1; }

"$DEVSTACK_DIR/phone.sh" >/dev/null
adb install -r "$APK" >/dev/null
[[ $KEEP == 1 ]] || adb shell pm clear "$PKG" >/dev/null
adb logcat -c
adb shell input keyevent 224 # wake
adb shell am start -n "$PKG/xyz.headsdown.MainActivity" >/dev/null
sleep 3
step "installed $(basename "$APK") on $(adb get-serialno) (Android $(adb shell getprop ro.build.version.release | tr -d '\r')) and started it"

# ---- 1. setup
# Each step is skipped when it is already done (a tile stays added across a data clear).
# `ui has` looks at the screen as it is (system dialogs); `ui find` and `ui tap` scroll down to look.
dialog() { sleep 2; if ui has "$1" exact; then ui tap "$1" exact >/dev/null; sleep 2; fi; }
if ! ui has "Rounds dark"; then
  if ui find "Set up my rig" >/dev/null 2>&1; then ui tap "Set up my rig" >/dev/null; sleep 2; fi
  if ui has "Allow notifications"; then ui tap "Allow notifications" >/dev/null; dialog "Allow"; fi
  if ui has "Allow background running"; then ui tap "Allow background running" >/dev/null; dialog "Allow"; fi
  if ui find "Add the tile" >/dev/null 2>&1; then ui tap "Add the tile" >/dev/null; dialog "Add tile"; fi
  if ui find "Create rig key" >/dev/null 2>&1; then ui tap "Create rig key" >/dev/null; sleep 6; fi
  if ui has "Continue"; then ui tap "Continue" >/dev/null; sleep 2; fi
fi
ui has "Rounds dark" || die "setup did not end on the home screen: $(ui texts | tail -n 5)"
step "setup done: notifications, background running, tile, rig key in the device's Keystore"

# ---- 2. a rig for this phone's key: armed by the Mac's dev wallet, or by a wallet app on the device
if [[ -z "$WALLET_APK" ]]; then
  ui tap "Rig key and devstack" >/dev/null; sleep 2
  KEY="$(ui texts | awk '{ print $2 }' | grep -E '^0[23][0-9a-f]{64}$' | head -1)"
  [[ -n "$KEY" ]] || die "no rig key on the debug screen"
  # A new dev wallet every run: a cleared app has a new Keystore key, and one wallet has one rig.
  WALLET="$HD_DEVSTACK_KEYS/emulator-smoke-$(date +%s).json"
  ensure_key "$WALLET"
  OUT="$("$TOOL_BIN" --rpc "$RPC_URL" clock-in --wallet "$WALLET" --p256 "$KEY" 2>&1)" || die "clock-in failed: $OUT"
  AUTHORITY="$(awk '$1 == "authority" { print $2 }' <<<"$OUT")"
  RIG="$(awk '$1 == "rig" { print $2 }' <<<"$OUT")"
  [[ -n "$AUTHORITY" && -n "$RIG" ]] || die "clock-in printed no authority: $OUT"
  step "rig $RIG armed for key ${KEY:0:16}… by the dev wallet $AUTHORITY"
  ui tap "authority (base58)" >/dev/null; sleep 1
  adb shell input text "$AUTHORITY"; sleep 1
  adb shell input keyevent 111 # close the keyboard
  sleep 1
  ui tap "Attach and arm" >/dev/null; sleep 4
  ui find "Attached to shift" >/dev/null || die "the app did not attach: $(ui texts | tail -n 6)"
  step "app attached: $(ui find "Attached to shift")"
else
  WPKG=com.solana.mobilewalletadapter.fakewallet
  adb install -r "$WALLET_APK" >/dev/null
  adb shell pm clear "$WPKG" >/dev/null # a new account every run: one wallet has one rig
  adb shell am start -n "$WPKG/.MainActivity" >/dev/null
  wait_ui "Fake Wallet app" 30
  if ui has "GENERATE" exact; then ui tap "GENERATE" exact >/dev/null; sleep 3; fi
  AUTHORITY="$(ui texts | awk '{ print $2 }' | grep -E '^[1-9A-HJ-NP-Za-km-z]{32,44}$' | head -1 || true)"
  [[ -n "$AUTHORITY" ]] || die "the wallet shows no account: $(ui texts | head -n 6)"
  "$DEVSTACK_DIR/fund.sh" "$AUTHORITY" 5 >/dev/null
  home
  ui tap "Clock in" exact >/dev/null
  wallet_approve
  ui has "Armed" exact || die "the clock-in did not arm the shift: $(ui texts | head -n 8)"
  read -r RIG _ SHIFT <<<"$(rig_of "$AUTHORITY")"
  [[ -n "$RIG" && "$SHIFT" == 1 ]] || die "no rig with shift 1 for $AUTHORITY on-chain"
  step "CLOCKED IN with the wallet $AUTHORITY: the app built the transaction, the wallet signed, the app submitted and confirmed it; rig $RIG is armed"
fi

# ---- 3. face-down, on the charger, screen off
BEATS="$(metric hd_crank_heartbeats_accepted_total)" DIGS="$(metric hd_crank_digs_landed_total)" BREAKS="$(metric 'hd_crank_signals_landed_total{kind="break"}')"
if is_emulator; then
  adb emu power ac on >/dev/null; adb emu power status charging >/dev/null
  adb emu sensor set acceleration 0:0:-9.81 >/dev/null
  sleep 1
  adb shell input keyevent 223 # sleep
else
  step "NOW: lay the phone face-down on its charger with the screen off"
fi
wait_metric hd_crank_heartbeats_accepted_total "$BEATS" 300
step "the crank accepted a heartbeat signed by the device's Keystore key"
wait_metric hd_crank_digs_landed_total "$DIGS" 420
SIG="$(sed 's/\x1b\[[0-9;]*m//g' "$LOGS/crank.log" | awk '/dig landed/ { for (i = 1; i <= NF; i++) if ($i ~ /^sig=/) s = substr($i, 5) } END { print s }')"
step "DIG LANDED on-chain with that heartbeat: tx $SIG"

# ---- 4. lift
if is_emulator; then
  adb emu sensor set acceleration 0:9.776:0.812 >/dev/null
  adb shell input keyevent 224
else
  step "NOW: pick the phone up and turn the screen on"
fi
wait_metric 'hd_crank_signals_landed_total{kind="break"}' "$BREAKS" 120
STATE="$(rig_state "$RIG")"
[[ "$STATE" == Cooling || "$STATE" == Broken ]] || die "after the BREAK the rig is $STATE, not Cooling or Broken"
step "LIFTED: the app signed a BREAK and the crank landed it (the rig is now $STATE on-chain)"
[[ "$(crashed)" == 0 ]] || die "the app crashed during the run: adb logcat -d -s AndroidRuntime:E"

if [[ -n "$WALLET_APK" ]]; then
  # ---- 5. clock out: the shift cooled inside its window, so sealing it is the user's choice
  adb shell input keyevent 82 # dismiss the keyguard
  home
  ui tap "Clock out: seal the shift" >/dev/null; sleep 4
  ui tap "End the shift now" exact >/dev/null; sleep 1
  ui has "This cannot be undone" || die "ending early did not state its consequences: $(ui texts | head -n 8)"
  ui tap "End the shift and clock out" exact >/dev/null
  wallet_approve
  ui has "Confirmed on-chain. Shift sealed" || die "the clock-out was not confirmed: $(ui texts | head -n 6)"
  step "CLOCKED OUT with the wallet: $(ui find "Confirmed on-chain")"
  ui tap "Done" exact >/dev/null; sleep 2

  # ---- 6. take the SOL back and close the rig, to the lamport
  ui tap "Take SOL back or close the rig" >/dev/null; sleep 4
  SAID="$(ui find "Your ORE Automation holds")"
  ui tap "Take it back to my wallet" exact >/dev/null; sleep 1
  ui tap "Close my rig" exact >/dev/null; sleep 1
  BEFORE="$(balance "$AUTHORITY")"
  ui tap "Take SOL back and close the rig" exact >/dev/null
  wallet_approve
  DONE="$(ui find "Confirmed on-chain")" || die "the withdrawal was not confirmed: $(ui texts | head -n 6)"
  AFTER="$(balance "$AUTHORITY")"
  # What the screen promised (two SOL amounts in its closing line), less the 5,000 lamport fee.
  PROMISED="$(python3 -c 'import re, sys
print(sum(round(float(x) * 1e9) for x in re.findall(r"([0-9]+\.[0-9]+) SOL", sys.argv[1])) - 5000)' "$DONE")"
  [[ $((AFTER - BEFORE)) == "$PROMISED" ]] || die "the wallet gained $((AFTER - BEFORE)) lamports, the screen said $PROMISED ($DONE)"
  [[ -z "$(rig_of "$AUTHORITY")" ]] || die "the rig is still there after close_rig"
  step "TOOK IT BACK with the wallet: +$((AFTER - BEFORE)) lamports, exactly what the screen said; the rig is closed"
  ui tap "Done" exact >/dev/null; sleep 2

  # ---- 7. clock in again, over the tombstone
  home
  ui tap "Clock in" exact >/dev/null
  wallet_approve
  read -r RIG2 _ SHIFT2 <<<"$(rig_of "$AUTHORITY")"
  [[ "$RIG2" == "$RIG" && "$SHIFT2" == 2 ]] || die "after the second clock-in the rig is '$RIG2' with shift '$SHIFT2', not $RIG with shift 2"
  step "CLOCKED IN AGAIN over the tombstone: the same rig address, now on shift 2"
  [[ "$(crashed)" == 0 ]] || die "the app crashed during the wallet steps: adb logcat -d -s AndroidRuntime:E"
  printf '\n\033[1;32mEMULATOR SMOKE PASSED\033[0m (with a wallet app): setup, clock-in, heartbeat, on-chain dig, pickup, BREAK, clock-out, SOL back, rig closed, clock-in again. No crash.\n'
  exit 0
fi

printf '\n\033[1;32mEMULATOR SMOKE PASSED\033[0m: setup, Keystore key, attach, heartbeat, on-chain dig, pickup, BREAK landed. No crash.\n'
