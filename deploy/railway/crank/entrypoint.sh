#!/bin/bash
# hd-crank container entrypoint (deploy/railway/crank). Never prints a secret.
#
# 1. HD_CRANK_KEYPAIR_JSON (Railway sealed variable: the contents of crank-payer.json, a JSON
#    array of 64 numbers) is checked for shape, written to a 0600 file in a private 0700
#    directory on tmpfs (/dev/shm), and removed from the environment hd-crank inherits.
# 2. Running as root (the default on Railway), the volume at /data is handed to uid 10001 and
#    hd-crank is started as uid 10001 with setpriv --no-new-privs; root can read the key file,
#    hd-crank can read it, nobody else can.
# 3. hd-crank listens on $PORT, dual-stack ([::]) when the container has IPv6.
# 4. hd-crank loads the key before it binds its port; once the port answers (or after 30 s),
#    the key file is deleted. SIGTERM / SIGINT are forwarded as SIGINT (hd-crank's shutdown).
set -euo pipefail
umask 077

APP_UID=10001
PORT="${PORT:-8787}"
CONFIG="${HD_CRANK_CONFIG:-/etc/hd-crank/crank.toml}"
STATE_DIR=/data/hd-crank

say() { printf '{"timestamp":"%s","level":"%s","source":"entrypoint","message":"%s"}\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$2" >&2; }
fail() { say ERROR "$1"; exit 1; }

[[ "$PORT" =~ ^[0-9]{1,5}$ ]] || fail "PORT must be a port number"
if [[ -z "${HD_CRANK_LISTEN:-}" ]]; then
  if [[ -s /proc/net/if_inet6 ]]; then HD_CRANK_LISTEN="[::]:$PORT"; else HD_CRANK_LISTEN="0.0.0.0:$PORT"; fi
  export HD_CRANK_LISTEN
fi

# ---- 1. the fee-payer key ------------------------------------------------------------------------
[[ -z "${HD_CRANK_KEYPAIR:-}" ]] || fail "set HD_CRANK_KEYPAIR_JSON (the key itself), not HD_CRANK_KEYPAIR (a path)"
[[ -n "${HD_CRANK_KEYPAIR_JSON:-}" ]] || fail "HD_CRANK_KEYPAIR_JSON is not set (sealed variable: the contents of crank-payer.json)"
key_json="$(printf '%s' "$HD_CRANK_KEYPAIR_JSON" | tr -d ' \t\r\n')"
unset HD_CRANK_KEYPAIR_JSON
[[ "$key_json" =~ ^\[([0-9]{1,3},){63}[0-9]{1,3}\]$ ]] || fail "HD_CRANK_KEYPAIR_JSON is not a Solana CLI keypair (a JSON array of 64 numbers)"

secret_root=/dev/shm
if [[ ! -d "$secret_root" || ! -w "$secret_root" || "$(stat -f -c %T "$secret_root" 2>/dev/null || true)" != tmpfs ]]; then
  secret_root="${TMPDIR:-/tmp}"
  say WARN "/dev/shm is not a writable tmpfs: the key file goes to $secret_root (0600) until hd-crank has loaded it"
fi
key_dir="$(mktemp -d "$secret_root/hd-crank.XXXXXXXX")"
key_file="$key_dir/crank-payer.json"
printf '%s' "$key_json" >"$key_file"
unset key_json
chmod 0700 "$key_dir"
chmod 0600 "$key_file"

# ---- 2. the volume and the unprivileged user -----------------------------------------------------
if [[ "$(id -u)" == 0 ]]; then
  mkdir -p "$STATE_DIR"
  chown "$APP_UID:$APP_UID" /data "$STATE_DIR" "$key_dir" "$key_file"
  as_app=(setpriv --reuid="$APP_UID" --regid="$APP_UID" --clear-groups --no-new-privs --)
else
  mkdir -p "$STATE_DIR" 2>/dev/null || true
  [[ -w "$STATE_DIR" ]] || say WARN "$STATE_DIR is not writable by uid $(id -u): lookup tables will be re-created after every restart"
  as_app=()
fi

# ---- 3. run, then drop the key file --------------------------------------------------------------
${as_app[@]+"${as_app[@]}"} /usr/local/bin/hd-crank --config "$CONFIG" --keypair "$key_file" "$@" &
child=$!
trap 'kill -INT "$child" 2>/dev/null || true' TERM INT
for _ in $(seq 1 60); do
  kill -0 "$child" 2>/dev/null || break
  if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then break; fi
  sleep 0.5
done
rm -rf "$key_dir"
say INFO "key file removed; hd-crank (pid $child) on $HD_CRANK_LISTEN"

status=0
while :; do
  if wait "$child"; then status=0; else status=$?; fi
  kill -0 "$child" 2>/dev/null || break
done
exit "$status"
