#!/usr/bin/env bash
# The real Android app, in the loop: on a running emulator (or a phone), against the local stack.
#
#   scripts/devstack/emulator-smoke.sh [--serial <adb serial>] [--apk PATH] [--keep-data]
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
# Needs: the stack up (up.sh), `adb` with one device, and for step 3 an emulator: the sensor and
# power commands are emulator console commands (`adb emu`). On a phone, do step 3 and 4 by hand
# when asked. Start an emulator with, for example:
#   $ANDROID_HOME/emulator/emulator -avd <name> -no-window -no-audio -no-boot-anim
# Android 14 with Google APIs (arm64) was the image this was written against.
source "$(dirname "$0")/lib.sh"

SERIAL="" APK="$REPO_ROOT/android/app/build/outputs/apk/localdev/app-localdev.apk" KEEP=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    --keep-data) KEEP=1; shift ;;
    -h | --help) sed -n '2,19p' "$0"; exit 0 ;;
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

# ---- 2. attach to a rig armed for this key
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

printf '\n\033[1;32mEMULATOR SMOKE PASSED\033[0m: setup, Keystore key, attach, heartbeat, on-chain dig, pickup, BREAK landed. No crash.\n'
