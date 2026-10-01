#!/bin/bash
# Configures :app with endpoint overrides and checks what Gradle accepts.
# Release and debug must refuse http:// and ws://; only the localdev build type may use loopback
# cleartext (127.0.0.1 / localhost), for a devstack behind `adb reverse`.
# Usage: android/app/scripts/check-endpoint-policy.sh   (exits non-zero on any mismatch)
cd "$(dirname "$0")/../.." || exit 1
failures=0

expect() {
  local want="$1" label="$2"; shift 2
  local got reason=""
  if out=$(./gradlew -q :app:help "$@" 2>&1); then
    got=ACCEPTED
  else
    got=REFUSED
    reason=" -> $(printf '%s\n' "$out" | grep -m1 -E 'must|allowed' | sed 's/^[[:space:]]*//')"
  fi
  if [ "$got" = "$want" ]; then echo "ok    $got  $label$reason"; else echo "FAIL  $got (wanted $want)  $label$reason"; failures=$((failures + 1)); fi
}

expect ACCEPTED "default endpoints"
expect REFUSED  "rpcUrl=http://127.0.0.1:8899 (debug and release)" -Pheadsdown.rpcUrl=http://127.0.0.1:8899
expect REFUSED  "crankUrl=ws://127.0.0.1:8787/ws (debug and release)" -Pheadsdown.crankUrl=ws://127.0.0.1:8787/ws
expect REFUSED  "rpcUrl with ?api-key=" "-Pheadsdown.rpcUrl=https://rpc.example.org/?api-key=x"
expect ACCEPTED "localdev.rpcUrl=http://localhost:8899" -Pheadsdown.localdev.rpcUrl=http://localhost:8899
expect ACCEPTED "localdev.crankUrl=ws://localhost:8787/ws" -Pheadsdown.localdev.crankUrl=ws://localhost:8787/ws
expect REFUSED  "localdev.rpcUrl=http://192.168.1.5:8899" -Pheadsdown.localdev.rpcUrl=http://192.168.1.5:8899
expect REFUSED  "localdev.rpcUrl=http://10.0.2.2:8899" -Pheadsdown.localdev.rpcUrl=http://10.0.2.2:8899
expect REFUSED  "localdev.crankUrl=ws://127.0.0.1.nip.io:8787/ws" -Pheadsdown.localdev.crankUrl=ws://127.0.0.1.nip.io:8787/ws
expect REFUSED  "registrarUrl=http://127.0.0.1:8790 (debug and release)" -Pheadsdown.registrarUrl=http://127.0.0.1:8790
expect REFUSED  "indexerUrl with ?api-key=" "-Pheadsdown.indexerUrl=https://indexer.example.org/?api-key=x"
expect ACCEPTED "registrarUrl= and indexerUrl= (disabled)" -Pheadsdown.registrarUrl= -Pheadsdown.indexerUrl=
expect ACCEPTED "localdev.registrarUrl=http://127.0.0.1:8790" -Pheadsdown.localdev.registrarUrl=http://127.0.0.1:8790
expect REFUSED  "localdev.indexerUrl=http://10.0.2.2:8788" -Pheadsdown.localdev.indexerUrl=http://10.0.2.2:8788
# Clock-in policy for demo takes: checked at configuration time.
expect ACCEPTED "policy: a 25-minute Day Shift, lease 3, 10 split tiles" -Pheadsdown.policy.mode=day -Pheadsdown.policy.windowMinutes=25 -Pheadsdown.policy.leaseRounds=3 -Pheadsdown.policy.splitTiles=10
expect REFUSED  "policy.leaseRounds=4" -Pheadsdown.policy.leaseRounds=4
expect REFUSED  "policy.planMaxEvCost above capMaxCost" -Pheadsdown.policy.planMaxEvCost=700000000 -Pheadsdown.policy.capMaxCost=600000000
expect REFUSED  "policy.digLamports below 0.001 SOL" -Pheadsdown.policy.digLamports=500000
expect REFUSED  "policy.mode=dusk" -Pheadsdown.policy.mode=dusk

exit "$failures"
