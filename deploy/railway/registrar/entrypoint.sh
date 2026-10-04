#!/bin/bash
# hd-registrar container entrypoint (deploy/railway/registrar). Never prints a secret.
#
# 1. HD_REGISTRAR_KEYPAIR_JSON (Railway sealed variable: the contents of registrar.json from
#    `hd-registrar keygen`, a JSON array of 64 numbers) is checked for shape, written to a 0600
#    file in a private 0700 directory on tmpfs (/dev/shm), removed from the environment, and
#    passed as HD_REGISTRAR_KEYPAIR (the registrar refuses group/other-writable key files).
#    HD_REGISTRAR_SECRET_B58 is still accepted instead (set only one of the two).
# 2. Running as root (the default on Railway), the volume at /data (nonce database and the
#    append-only transparency log) is handed to uid 10001 and the registrar runs as uid 10001
#    with setpriv --no-new-privs.
# 3. The registrar listens on $PORT, dual-stack ([::]) when the container has IPv6.
# 4. It loads the key before it binds its port; once the port answers (or after 30 s) the key
#    file is deleted. SIGTERM / SIGINT are forwarded (the registrar shuts down gracefully).
#    If this script stops before that (a step fails, a signal arrives), the key directory is
#    removed on the way out.
# 5. Shell tracing is refused (`bash -x`, SHELLOPTS=xtrace): it would print the key and the
#    session secret into the deploy log.
# 6. On Railway it refuses to start unless a volume is mounted at /data: without one every
#    deploy loses the nonce database and the transparency log.
set -euo pipefail
case $- in
  *x*) echo "refusing to run with xtrace: set -x would print secrets" >&2; exit 2 ;;
esac
umask 077

APP_UID=10001
PORT="${PORT:-8080}"

say() { printf '{"timestamp":"%s","level":"%s","source":"entrypoint","message":"%s"}\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$2" >&2; }
fail() { say ERROR "$1"; exit 1; }

# The key directory, once it exists. Whatever ends this script takes it along: a step below can
# fail after the key file is written, and a signal can arrive before the registrar is up.
key_dir=""
trap 'if [[ -n "$key_dir" ]]; then rm -rf "$key_dir"; fi' EXIT

[[ "$PORT" =~ ^[0-9]{1,5}$ ]] || fail "PORT must be a port number"
# Railway sets RAILWAY_SERVICE_NAME in every container and RAILWAY_VOLUME_MOUNT_PATH when a
# volume is attached. Without the volume the nonce database and the transparency log would sit
# on the container's own disk and be gone after the next deploy.
if [[ -n "${RAILWAY_SERVICE_NAME:-}" ]]; then
  volume="${RAILWAY_VOLUME_MOUNT_PATH:-}"
  [[ "${volume%/}" == /data ]] || fail "no Railway volume is mounted at /data (RAILWAY_VOLUME_MOUNT_PATH is ${volume:-not set}): attach a volume to this service with mount path /data"
fi
if [[ -z "${HD_BIND:-}" ]]; then
  if [[ -s /proc/net/if_inet6 ]]; then HD_BIND="[::]:$PORT"; else HD_BIND="0.0.0.0:$PORT"; fi
  export HD_BIND
fi

# ---- 1. the voucher key ----------------------------------------------------------------------------
if [[ "${1:-serve}" == serve ]]; then
  [[ -z "${HD_REGISTRAR_KEYPAIR:-}" ]] || fail "set HD_REGISTRAR_KEYPAIR_JSON (the key itself), not HD_REGISTRAR_KEYPAIR (a path)"
  if [[ -n "${HD_REGISTRAR_KEYPAIR_JSON:-}" ]]; then
    [[ -z "${HD_REGISTRAR_SECRET_B58:-}" ]] || fail "set only one of HD_REGISTRAR_KEYPAIR_JSON and HD_REGISTRAR_SECRET_B58"
    key_json="$(printf '%s' "$HD_REGISTRAR_KEYPAIR_JSON" | tr -d ' \t\r\n')"
    unset HD_REGISTRAR_KEYPAIR_JSON
    [[ "$key_json" =~ ^\[([0-9]{1,3},){63}[0-9]{1,3}\]$ ]] || fail "HD_REGISTRAR_KEYPAIR_JSON is not a Solana CLI keypair (a JSON array of 64 numbers)"
    secret_root=/dev/shm
    if [[ ! -d "$secret_root" || ! -w "$secret_root" || "$(stat -f -c %T "$secret_root" 2>/dev/null || true)" != tmpfs ]]; then
      secret_root="${TMPDIR:-/tmp}"
      say WARN "/dev/shm is not a writable tmpfs: the key file goes to $secret_root (0600) until the registrar has loaded it"
    fi
    key_dir="$(mktemp -d "$secret_root/hd-registrar.XXXXXXXX")"
    printf '%s' "$key_json" >"$key_dir/registrar-keypair.json"
    unset key_json
    chmod 0700 "$key_dir"
    chmod 0600 "$key_dir/registrar-keypair.json"
    export HD_REGISTRAR_KEYPAIR="$key_dir/registrar-keypair.json"
  elif [[ -z "${HD_REGISTRAR_SECRET_B58:-}" ]]; then
    fail "HD_REGISTRAR_KEYPAIR_JSON is not set (sealed variable: the contents of registrar.json)"
  fi
  [[ -n "${HD_SESSION_SECRET:-}" ]] || fail "HD_SESSION_SECRET is not set (sealed variable: registrar-session-secret)"
fi

# ---- 2. the volume and the unprivileged user -----------------------------------------------------
if [[ "$(id -u)" == 0 ]]; then
  chown "$APP_UID:$APP_UID" /data
  for f in /data/nonces.db /data/nonces.db-wal /data/nonces.db-shm /data/attestations.jsonl; do
    if [[ -e "$f" ]]; then chown "$APP_UID:$APP_UID" "$f"; fi
  done
  if [[ -n "$key_dir" ]]; then chown -R "$APP_UID:$APP_UID" "$key_dir"; fi
  as_app=(setpriv --reuid="$APP_UID" --regid="$APP_UID" --clear-groups --no-new-privs --)
else
  [[ -w /data ]] || fail "/data is not writable by uid $(id -u): attach the Railway volume at /data"
  as_app=()
fi

# ---- 3. run, then drop the key file --------------------------------------------------------------
${as_app[@]+"${as_app[@]}"} /usr/local/bin/hd-registrar "$@" &
child=$!
trap 'kill -TERM "$child" 2>/dev/null || true' TERM INT
if [[ -n "$key_dir" ]]; then
  for _ in $(seq 1 60); do
    kill -0 "$child" 2>/dev/null || break
    if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then break; fi
    sleep 0.5
  done
  rm -rf "$key_dir"
  key_dir=""
  # /proc/<pid> is owned by the uid the process runs as (Linux), so this line shows whether the
  # drop to uid 10001 happened. "unknown" where there is no /proc (macOS) or the registrar has
  # already exited. The Linux half has not run yet: the first Railway deploy is its first run.
  say INFO "key file removed; hd-registrar (pid $child, uid $(stat -c %u "/proc/$child" 2>/dev/null || echo unknown)) on $HD_BIND"
fi

status=0
while :; do
  if wait "$child"; then status=0; else status=$?; fi
  kill -0 "$child" 2>/dev/null || break
done
exit "$status"
