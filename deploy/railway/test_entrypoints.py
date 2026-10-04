#!/usr/bin/env python3
"""Runs the crank's and the registrar's entrypoint for real, without Docker.

    python3 deploy/railway/test_entrypoints.py                       # the `bash` on PATH
    python3 deploy/railway/test_entrypoints.py --bash /bin/bash --bash /path/to/bash-5

Each run starts a copy of an entrypoint in a fresh directory under the system's temporary
directory, outside the repository, with a made-up 64-number key and a stub in place of the
service binary. Three things are changed in a copy and nothing else: the service binary (the
stub), /data (a scratch directory; the volume guard's own "/data" is left alone) and /dev/shm
(a scratch directory). One test also redirects /proc, and says so.

What is checked, per entrypoint and per bash: a plain run (key file 0600 in a 0700 directory,
there while the service starts, gone once its port answers, not inherited as a variable);
`bash -x` and SHELLOPTS=xtrace (refused, exit 2); a service that fails, and one that exits
later (exit code passed on); SIGTERM while the port is awaited and before the service is
started; RAILWAY_SERVICE_NAME with and without a volume at /data; a malformed key; early
exits after the key file is written; a service that never listens (30 s). In every run no
output line may hold three consecutive numbers of the key, nor the session secret.

What is not: nothing here runs as root. The root branch is reached with stand-ins for `id`,
`chown` and `setpriv`, so the real drop to uid 10001 and a real tmpfs /dev/shm are not
exercised. The uid in the 'key file removed' line is read from /proc: real on Linux, and
simulated elsewhere (macOS has no /proc and no GNU stat).
"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import os
import random
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SERVICES = {
    "crank": dict(binary="/usr/local/bin/hd-crank", keyvar="HD_CRANK_KEYPAIR_JSON", pathvar="HD_CRANK_KEYPAIR",
                  args=["run"], forwards="SIGINT", prefix="hd-crank.", data_hits=2),
    "registrar": dict(binary="/usr/local/bin/hd-registrar", keyvar="HD_REGISTRAR_KEYPAIR_JSON", pathvar="HD_REGISTRAR_KEYPAIR",
                      args=["serve"], forwards="SIGTERM", prefix="hd-registrar.", data_hits=8),
}
LINUX = sys.platform.startswith("linux")
rng = random.Random(20261004)
KEY_NUMS = [rng.randint(100, 255) for _ in range(64)]
KEY_COMPACT = "[" + ",".join(map(str, KEY_NUMS)) + "]"
# As pasted: spaces, a tab, a line break and a trailing CR LF, all of which the entrypoint strips.
KEY_PASTED = "[ " + ", ".join(map(str, KEY_NUMS[:32])) + ",\n\t" + ", ".join(map(str, KEY_NUMS[32:])) + " ]\r\n"
KEY_SHA = hashlib.sha256(KEY_COMPACT.encode()).hexdigest()
SESSION_SECRET = "5e55104e" * 8

# The stand-in for hd-crank / hd-registrar. It never prints: what it saw (names, modes, a hash
# comparison) goes to $STUB_OUT as JSON lines.
STUB = r'''
import hashlib, json, os, signal, socket, stat, sys, time

def note(**kw):
    with open(os.environ["STUB_OUT"], "a") as f:
        f.write(json.dumps(kw) + "\n")

def key_state():
    p = sys.argv[sys.argv.index("--keypair") + 1] if "--keypair" in sys.argv else os.environ.get("HD_REGISTRAR_KEYPAIR")
    if not p or not os.path.exists(p):
        return {"key_file": p, "exists": False}
    return {
        "key_file": p,
        "exists": True,
        "sha_ok": hashlib.sha256(open(p, "rb").read()).hexdigest() == os.environ.get("STUB_EXPECT_SHA256"),
        "file_mode": oct(stat.S_IMODE(os.stat(p).st_mode)),
        "dir_mode": oct(stat.S_IMODE(os.stat(os.path.dirname(p)).st_mode)),
    }

def on_signal(signum, _frame):
    note(event="signal", signal=signal.Signals(signum).name)
    sys.exit(int(os.environ.get("STUB_EXIT_ON_SIGNAL", "0")))

signal.signal(signal.SIGINT, on_signal)
signal.signal(signal.SIGTERM, on_signal)
if os.environ.get("STUB_FAKE_PROC"):
    os.makedirs(os.path.join(os.environ["STUB_FAKE_PROC"], str(os.getpid())), exist_ok=True)
note(event="start", pid=os.getpid(), argv=sys.argv[1:], env_names=sorted(os.environ),
     bind=os.environ.get("HD_CRANK_LISTEN") or os.environ.get("HD_BIND"),
     has_session_secret=bool(os.environ.get("HD_SESSION_SECRET")), key=key_state())
mode = os.environ.get("STUB_MODE", "listen")
time.sleep(float(os.environ.get("STUB_DELAY", "0")))
note(event="after_delay", key=key_state())
if mode == "fail":
    sys.exit(int(os.environ.get("STUB_EXIT_CODE", "7")))
if mode == "listen":
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", int(os.environ["PORT"])))
    s.listen(16)
    s.settimeout(0.2)
    note(event="listening")
    end = os.environ.get("STUB_EXIT_AFTER")
    deadline = time.time() + float(end) if end else None
    while deadline is None or time.time() < deadline:
        try:
            s.accept()[0].close()
        except socket.timeout:
            pass
    sys.exit(int(os.environ.get("STUB_EXIT_CODE", "0")))
while True:  # "hang": never listens
    time.sleep(0.2)
'''

# Stand-ins put first on PATH. `stat-gnu` answers GNU `stat -c %u PATH` where the real stat is BSD.
SHIMS = {
    "id-root": ("id", 'if [ "$1" = "-u" ]; then echo 0; else exec /usr/bin/id "$@"; fi\n'),
    "chown-ok": ("chown", 'echo "chown $*" >> "$SHIM_LOG"\n'),
    "chown-fail": ("chown", 'echo "chown $*" >> "$SHIM_LOG"; echo "chown: simulated failure" >&2; exit 1\n'),
    "setpriv": ("setpriv", 'echo "setpriv $*" >> "$SHIM_LOG"; while [ "$1" != "--" ]; do shift; done; shift; exec "$@"\n'),
    "chmod-slow": ("chmod", 'touch "$SHIM_MARK"; sleep 4; exec /bin/chmod "$@"\n'),
    "stat-gnu": ("stat", 'if [ "$1" = "-c" ] && [ "$2" = "%u" ]; then exec /usr/bin/stat -f %u "$3"; fi; exec /usr/bin/stat "$@"\n'),
}


def leaks(text: str) -> list[str]:
    """Key material in `text`: any three consecutive key numbers, or the session secret."""
    squashed = re.sub(r"\s+", "", text)
    found = [w for w in (",".join(map(str, KEY_NUMS[i:i + 3])) for i in range(62)) if w in squashed]
    return found + (["the session secret"] if SESSION_SECRET in text else [])


_handed_out: set[int] = set()
_handed_out_lock = threading.Lock()


def free_port() -> int:
    """A loopback port nothing listens on, and that no other run here was given. The runs are
    parallel and some stubs listen late or never: without the second half the system can hand
    the same port to two of them, and one would then see the other's stub answer."""
    with _handed_out_lock:
        while True:
            s = socket.socket()
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
            s.close()
            if port not in _handed_out:
                _handed_out.add(port)
                return port


def make_copy(tree: Path, work: str, service: str, fake_proc: bool) -> str:
    cfg = SERVICES[service]
    out, n_data, n_bin, n_shm, n_proc = [], 0, 0, 0, 0
    for line in (tree / "deploy" / "railway" / service / "entrypoint.sh").read_text().splitlines(keepends=True):
        if not line.lstrip().startswith("#"):
            if "RAILWAY_VOLUME_MOUNT_PATH" not in line:
                n_data += line.count("/data")
                line = line.replace("/data", work + "/data")
            n_bin += line.count(cfg["binary"])
            line = line.replace(cfg["binary"], work + "/stub")
            n_shm += line.count("secret_root=/dev/shm")
            line = line.replace("secret_root=/dev/shm", "secret_root=" + work + "/shm")
            if fake_proc:
                n_proc += line.count('"/proc/$child"')
                line = line.replace('"/proc/$child"', '"' + work + '/proc/$child"')
        out.append(line)
    # A copy that does not match the entrypoint would test something else: stop instead.
    if (n_data, n_bin, n_shm, n_proc) != (cfg["data_hits"], 1, 1, 1 if fake_proc else 0):
        raise AssertionError(f"{service}: the entrypoint changed shape ({n_data} /data, {n_bin} binary, {n_shm} shm, {n_proc} proc)")
    path = os.path.join(work, "entrypoint.sh")
    Path(path).write_text("".join(out))
    return path


class Run:
    """One entrypoint started in its own scratch directory."""

    def __init__(self, ctx, service, *, env=None, drop=(), args=None, flags=(), shims=(), fake_proc=False, key=KEY_PASTED, data_mode=None, data_files=()):
        self.service, self.cfg = service, SERVICES[service]
        self.work = tempfile.mkdtemp(prefix=f"{service}-", dir=ctx["runs"])
        for d in ("tmp", "shm", "data", "shims"):
            os.mkdir(os.path.join(self.work, d))
        stub = os.path.join(self.work, "stub")
        Path(stub).write_text("#!" + sys.executable + "\n" + STUB)
        os.chmod(stub, 0o755)
        for name in shims:
            target, body = SHIMS[name]
            shim = os.path.join(self.work, "shims", target)
            Path(shim).write_text("#!/bin/sh\n" + body)
            os.chmod(shim, 0o755)
        for name in data_files:
            Path(self.work, "data", name).touch()
        if data_mode is not None:
            os.chmod(os.path.join(self.work, "data"), data_mode)
        self.port = free_port()
        self.stub_out = os.path.join(self.work, "stub.jsonl")
        self.shim_log = os.path.join(self.work, "shim.log")
        self.mark = os.path.join(self.work, "mark")
        e = {
            "PATH": os.path.join(self.work, "shims") + ":/usr/bin:/bin:/usr/sbin:/sbin",
            "HOME": self.work,
            "TMPDIR": os.path.join(self.work, "tmp"),
            "PORT": str(self.port),
            "STUB_OUT": self.stub_out,
            "STUB_EXPECT_SHA256": KEY_SHA,
            "SHIM_LOG": self.shim_log,
            "SHIM_MARK": self.mark,
            self.cfg["keyvar"]: key,
        }
        if service == "registrar":
            e["HD_SESSION_SECRET"] = SESSION_SECRET
        e.update({k: v.replace("@WORK@", self.work) for k, v in (env or {}).items()})
        for k in drop:
            e.pop(k, None)
        script = make_copy(ctx["tree"], self.work, service, fake_proc)
        self.log = os.path.join(self.work, "output.log")
        self.proc = subprocess.Popen(
            [ctx["bash"], *flags, script, *(self.cfg["args"] if args is None else args)],
            env=e, stdin=subprocess.DEVNULL, stdout=open(self.log, "w"), stderr=subprocess.STDOUT,
            start_new_session=True, cwd=self.work,
        )

    def output(self) -> str:
        return Path(self.log).read_text(errors="replace")

    def wait_for(self, pattern: str, timeout: float):
        end = time.time() + timeout
        while time.time() < end:
            m = re.search(pattern, self.output())
            if m or self.proc.poll() is not None:
                return m or re.search(pattern, self.output())
            time.sleep(0.05)
        return None

    def wait_path(self, path: str, timeout: float) -> bool:
        end = time.time() + timeout
        while time.time() < end and not os.path.exists(path):
            time.sleep(0.02)
        return os.path.exists(path)

    def events(self, name=None) -> list[dict]:
        if not os.path.exists(self.stub_out):
            return []
        ev = [json.loads(line) for line in Path(self.stub_out).read_text().splitlines() if line.strip()]
        return [e for e in ev if name is None or e["event"] == name]

    def wait_event(self, name: str, timeout: float):
        end = time.time() + timeout
        while time.time() < end:
            ev = self.events(name)
            if ev:
                return ev[0]
            time.sleep(0.05)
        return None

    def key_left(self) -> list[str]:
        """Key directories and key files still on disk."""
        left = []
        for root in ("tmp", "shm"):
            for base, dirs, files in os.walk(os.path.join(self.work, root)):
                left += [os.path.join(base, d) for d in dirs if d.startswith(self.cfg["prefix"])]
                left += [os.path.join(base, f) for f in files if f.endswith(".json")]
        return left

    def finish(self, timeout: float = 20):
        try:
            return self.proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            return "timeout"
        finally:
            try:
                os.killpg(self.proc.pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                pass

    def shim_lines(self) -> list[str]:
        return Path(self.shim_log).read_text().splitlines() if os.path.exists(self.shim_log) else []


def never_leaks(f: list[str], r: Run) -> None:
    hits = leaks(r.output())
    if hits:
        f.append(f"key material in the output ({len(hits)} matches)")
    for line in r.output().splitlines():
        if line.startswith('{"timestamp"'):
            try:
                json.loads(line)
            except ValueError:
                f.append("an entrypoint log line is not JSON: " + line[:80])


def removed_line(svc: str) -> str:
    return r'key file removed; hd-%s \(pid (\d+), uid (\w+)\) on ([^"]+)"' % svc


# ---- the tests: each returns a list of failures ---------------------------------------------------

def t_parse(ctx, svc):
    """bash -n"""
    p = subprocess.run([ctx["bash"], "-n", str(ctx["tree"] / "deploy" / "railway" / svc / "entrypoint.sh")], capture_output=True, text=True)
    return [] if p.returncode == 0 else [p.stderr.strip()]


def t_plain(ctx, svc, extra_env=None, uid=None):
    """Key file 0600 in a 0700 directory, still there while the service starts, gone once the
    port answers; the service does not inherit the JSON; SIGTERM is forwarded; exit 0."""
    f = []
    env = {"STUB_MODE": "listen", "STUB_DELAY": "1.5"}
    env.update(extra_env or {})
    r = Run(ctx, svc, env=env)
    m = r.wait_for(removed_line(svc), 25)
    start, after = r.wait_event("start", 2), r.wait_event("after_delay", 2)
    want_uid = uid or (str(os.getuid()) if LINUX else "unknown")
    if not m:
        f.append("no 'key file removed' line with pid and uid")
    if not start or not after:
        f.append("the stub did not start")
    else:
        k0, k1 = start["key"], after["key"]
        if not (k0["exists"] and k0["sha_ok"] and k0["file_mode"] == "0o600" and k0["dir_mode"] == "0o700"):
            f.append(f"key file at start: {k0}")
        if not (k1["exists"] and k1["sha_ok"]):
            f.append(f"key file gone before the port answered: {k1}")
        if r.cfg["keyvar"] in start["env_names"]:
            f.append("the service inherited the keypair JSON")
        if m and (int(m.group(1)) != start["pid"] or m.group(2) != want_uid or m.group(3) != f"0.0.0.0:{r.port}" or start["bind"] != m.group(3)):
            f.append(f"log line {m.group(0)!r}: expected pid {start['pid']}, uid {want_uid}, bind {start['bind']}")
        if svc == "crank" and start["argv"] != ["--config", "/etc/hd-crank/crank.toml", "--keypair", k0["key_file"], "run"]:
            f.append(f"argv {start['argv']}")
        if svc == "registrar" and not (start["argv"] == ["serve"] and start["has_session_secret"] and "HD_REGISTRAR_KEYPAIR" in start["env_names"]):
            f.append(f"registrar argv or environment: {start['argv']}")
    if m and r.key_left():
        f.append(f"still on disk after the log line: {r.key_left()}")
    r.proc.send_signal(signal.SIGTERM)
    rc = r.finish()
    sig = r.events("signal")
    if rc != 0:
        f.append(f"exit code {rc}, expected 0")
    if not sig or sig[0]["signal"] != r.cfg["forwards"]:
        f.append(f"forwarded signal: {sig}")
    never_leaks(f, r)
    return f


def t_xtrace(ctx, svc, how):
    """Refused with exit 2 before the key is read."""
    f = []
    r = Run(ctx, svc, flags=("-x",)) if how == "flag" else Run(ctx, svc, env={"SHELLOPTS": "xtrace"})
    rc = r.finish()
    if rc != 2:
        f.append(f"exit code {rc}, expected 2")
    if "refusing to run with xtrace" not in r.output():
        f.append("no refusal message")
    if r.key_left() or r.events():
        f.append("a key file was written or the service was started")
    never_leaks(f, r)
    return f


def t_child_fails(ctx, svc):
    """The service exits 7 before it listens: exit 7, key removed."""
    f = []
    r = Run(ctx, svc, env={"STUB_MODE": "fail", "STUB_DELAY": "0.3", "STUB_EXIT_CODE": "7"})
    rc = r.finish()
    if rc != 7:
        f.append(f"exit code {rc}, expected 7")
    if not r.events("start") or r.key_left():
        f.append(f"stub started: {bool(r.events('start'))}; still on disk: {r.key_left()}")
    never_leaks(f, r)
    return f


def t_exit_code_later(ctx, svc):
    """The service listens, then exits 3 by itself: exit 3."""
    f = []
    r = Run(ctx, svc, env={"STUB_MODE": "listen", "STUB_EXIT_AFTER": "1.0", "STUB_EXIT_CODE": "3"})
    rc = r.finish()
    if rc != 3:
        f.append(f"exit code {rc}, expected 3")
    if not re.search(removed_line(svc), r.output()) or r.key_left():
        f.append("no log line, or the key is still on disk")
    never_leaks(f, r)
    return f


def t_term_while_starting(ctx, svc):
    """SIGTERM while the port is awaited: forwarded, key removed, the service's exit code passed on."""
    f = []
    r = Run(ctx, svc, env={"STUB_MODE": "hang", "STUB_EXIT_ON_SIGNAL": "5"})
    if not r.wait_event("start", 10):
        f.append("the stub did not start")
    time.sleep(0.7)
    had_key = bool(r.key_left())
    r.proc.send_signal(signal.SIGTERM)
    rc = r.finish()
    sig = r.events("signal")
    if not had_key:
        f.append("no key file while the service was starting")
    if rc != 5:
        f.append(f"exit code {rc}, expected 5")
    if not sig or sig[0]["signal"] != r.cfg["forwards"]:
        f.append(f"forwarded signal: {sig}")
    if r.key_left():
        f.append(f"left behind: {r.key_left()}")
    never_leaks(f, r)
    return f


def t_term_before_child(ctx, svc):
    """SIGTERM after the key file is written and before the service is started (chmod is slowed)."""
    f = []
    r = Run(ctx, svc, shims=("chmod-slow",))
    if not r.wait_path(r.mark, 10):
        f.append("the slow chmod was never reached")
    had_key = bool(r.key_left())
    t0 = time.time()
    r.proc.send_signal(signal.SIGTERM)
    rc = r.finish(10)
    if not had_key:
        f.append("no key file on disk at the time of the signal")
    if rc != -signal.SIGTERM:
        f.append(f"exit {rc}, expected death by SIGTERM")
    if time.time() - t0 > 3.5:
        f.append("the entrypoint waited for the step it was in")
    if r.key_left():
        f.append(f"left behind: {r.key_left()}")
    if r.events():
        f.append("the service was started")
    never_leaks(f, r)
    return f


def refused(ctx, svc, needle, **kw):
    """Exit 1 with `needle` in the output, no key on disk, the service never started."""
    f = []
    r = Run(ctx, svc, **kw)
    rc = r.finish()
    if rc != 1:
        f.append(f"exit code {rc}, expected 1")
    if needle not in r.output():
        f.append(f"{needle!r} is not in the output")
    if r.key_left():
        f.append(f"left behind: {r.key_left()}")
    if r.events():
        f.append("the service was started")
    never_leaks(f, r)
    return f, r


def t_guard(ctx, svc, volume, ok):
    """RAILWAY_SERVICE_NAME set: refuse unless RAILWAY_VOLUME_MOUNT_PATH is /data."""
    env = {"RAILWAY_SERVICE_NAME": svc}
    if volume is not None:
        env["RAILWAY_VOLUME_MOUNT_PATH"] = volume
    if ok:
        return t_plain(ctx, svc, extra_env=dict(env, STUB_DELAY="0.2"))
    return refused(ctx, svc, "no Railway volume is mounted at /data", env=env)[0]


def t_volume_var_alone(ctx, svc):
    """No RAILWAY_SERVICE_NAME: a stray RAILWAY_VOLUME_MOUNT_PATH changes nothing."""
    return t_plain(ctx, svc, extra_env={"RAILWAY_VOLUME_MOUNT_PATH": "/elsewhere", "STUB_DELAY": "0.2"})


def t_malformed_key(ctx, svc):
    """63 numbers, and a value with shell syntax in it: refused, not echoed, nothing written or run."""
    f, _ = refused(ctx, svc, "is not a Solana CLI keypair", key="[" + ",".join(map(str, KEY_NUMS[:63])) + "]")
    f2, r = refused(ctx, svc, "is not a Solana CLI keypair", key=KEY_COMPACT + '; $(touch "$HOME/pwned") `touch "$HOME/pwned"`')
    if os.path.exists(os.path.join(r.work, "pwned")):
        f2.append("the key value was evaluated by the shell")
    return f + f2


def t_path_variable(ctx, svc):
    """The path variable is the entrypoint's to set: refused when the operator sets it."""
    return refused(ctx, svc, f"not {SERVICES[svc]['pathvar']} (a path)", env={SERVICES[svc]["pathvar"]: "/somewhere/key.json"})[0]


def t_chown_fails(ctx, svc):
    """Root branch (stand-ins): chown fails after the key file is written: exit 1, key removed."""
    return refused(ctx, svc, "chown: simulated failure", shims=("id-root", "chown-fail", "setpriv"))[0]


def t_no_session_secret(ctx, svc):
    """Refused after the key file is written: exit 1, key removed."""
    return refused(ctx, svc, "HD_SESSION_SECRET is not set", drop=("HD_SESSION_SECRET",))[0]


def t_data_not_writable(ctx, svc):
    """Not root and /data not writable: refused after the key file is written, key removed."""
    return refused(ctx, svc, "is not writable by uid", data_mode=0o500)[0]


def t_both_keys(ctx, svc):
    """Refused."""
    return refused(ctx, svc, "set only one of HD_REGISTRAR_KEYPAIR_JSON and HD_REGISTRAR_SECRET_B58", env={"HD_REGISTRAR_SECRET_B58": "dummy"})[0]


def t_root_branch(ctx, svc):
    """Root branch with stand-ins for id, chown and setpriv: the volume and the key go to uid
    10001 and the service is started through setpriv."""
    f = []
    files = ("nonces.db", "attestations.jsonl") if svc == "registrar" else ()
    r = Run(ctx, svc, shims=("id-root", "chown-ok", "setpriv"), env={"STUB_MODE": "listen", "STUB_DELAY": "0.2"}, data_files=files)
    m = r.wait_for(removed_line(svc), 25)
    start = r.wait_event("start", 2)
    if not m or not start:
        f.append("no log line, or the stub did not start")
    else:
        key_file = start["key"]["key_file"]
        key_dir, data = os.path.dirname(key_file), os.path.join(r.work, "data")
        as_app = "setpriv --reuid=10001 --regid=10001 --clear-groups --no-new-privs --"
        if svc == "crank":
            want = [f"chown 10001:10001 {data} {data}/hd-crank {key_dir} {key_file}",
                    f"{as_app} {r.work}/stub --config /etc/hd-crank/crank.toml --keypair {key_file} run"]
        else:
            want = [f"chown 10001:10001 {data}", f"chown 10001:10001 {data}/nonces.db", f"chown 10001:10001 {data}/attestations.jsonl",
                    f"chown -R 10001:10001 {key_dir}", f"{as_app} {r.work}/stub serve"]
        if r.shim_lines() != want:
            f.append(f"calls {r.shim_lines()}, expected {want}")
        if not (start["key"]["exists"] and start["key"]["sha_ok"]):
            f.append("the service could not read the key")
    r.proc.send_signal(signal.SIGTERM)
    rc = r.finish()
    if rc != 0 or r.key_left():
        f.append(f"exit code {rc}; still on disk: {r.key_left()}")
    never_leaks(f, r)
    return f


def t_uid_simulated(ctx, svc):
    """/proc redirected to a scratch directory the stub fills, and a stand-in for GNU
    `stat -c %u`: the plumbing of the uid in the log line, not Linux itself."""
    return t_plain_with(ctx, svc, shims=("stat-gnu",), fake_proc=True, env={"STUB_FAKE_PROC": "@WORK@/proc"}, uid=str(os.getuid()))


def t_plain_with(ctx, svc, *, uid, **kw):
    f = []
    kw["env"] = dict(kw.get("env", {}), STUB_MODE="listen", STUB_DELAY="0.2")
    r = Run(ctx, svc, **kw)
    m = r.wait_for(removed_line(svc), 25)
    if not m or m.group(2) != uid:
        f.append(f"uid in the log line: {m.group(2) if m else None}, expected {uid}")
    r.proc.send_signal(signal.SIGTERM)
    if r.finish() != 0:
        f.append("exit code")
    never_leaks(f, r)
    return f


def t_timeout(ctx, svc):
    """The service never listens: after 30 s the key file is removed anyway and the service stays up."""
    f = []
    r = Run(ctx, svc, env={"STUB_MODE": "hang"})
    t0 = time.time()
    start = r.wait_event("start", 10)
    m = r.wait_for(removed_line(svc), 55)
    took = time.time() - t0
    if not m or not start:
        f.append("no log line")
    elif not 28 <= took <= 50:
        f.append(f"the key file was removed after {took:.1f} s, expected about 30")
    if r.key_left():
        f.append("the key is still on disk")
    if start:
        try:
            os.kill(start["pid"], 0)
        except ProcessLookupError:
            f.append("the service is gone")
    r.proc.send_signal(signal.SIGTERM)
    if r.finish() != 0:
        f.append("exit code after SIGTERM")
    never_leaks(f, r)
    return f


def t_b58(ctx, svc):
    """HD_REGISTRAR_SECRET_B58 instead of the JSON: no key file and no 'key file removed' line."""
    f = []
    r = Run(ctx, svc, drop=("HD_REGISTRAR_KEYPAIR_JSON",), env={"HD_REGISTRAR_SECRET_B58": "dummy", "STUB_MODE": "listen"})
    if not r.wait_event("listening", 10) or r.key_left() or "key file removed" in r.output():
        f.append("unexpected key handling")
    r.proc.send_signal(signal.SIGTERM)
    if r.finish() != 0:
        f.append("exit code")
    never_leaks(f, r)
    return f


def t_other_command(ctx, svc):
    """A command other than serve: no key handling, exit code passed on."""
    f = []
    r = Run(ctx, svc, args=["healthcheck"], drop=("HD_REGISTRAR_KEYPAIR_JSON", "HD_SESSION_SECRET"), env={"STUB_MODE": "fail", "STUB_EXIT_CODE": "3"})
    rc = r.finish()
    start = r.events("start")
    if rc != 3 or not start or start[0]["argv"] != ["healthcheck"] or r.key_left():
        f.append(f"exit {rc}, start {start}")
    never_leaks(f, r)
    return f


BOTH = [
    ("bash -n", t_parse, ()),
    ("plain run", t_plain, ()),
    ("bash -x", t_xtrace, ("flag",)),
    ("SHELLOPTS=xtrace", t_xtrace, ("shellopts",)),
    ("the service fails (exit 7)", t_child_fails, ()),
    ("the service exits 3 after listening", t_exit_code_later, ()),
    ("SIGTERM while the port is awaited", t_term_while_starting, ()),
    ("SIGTERM before the service is started", t_term_before_child, ()),
    ("Railway, no volume variable", t_guard, (None, False)),
    ("Railway, volume at /var/lib/data", t_guard, ("/var/lib/data", False)),
    ("Railway, volume at /data", t_guard, ("/data", True)),
    ("Railway, volume at /data/", t_guard, ("/data/", True)),
    ("no RAILWAY_SERVICE_NAME, stray volume variable", t_volume_var_alone, ()),
    ("malformed key", t_malformed_key, ()),
    ("path variable set", t_path_variable, ()),
    ("root branch (stand-ins), chown fails", t_chown_fails, ()),
    ("root branch (stand-ins)", t_root_branch, ()),
    ("the service never listens (30 s)", t_timeout, ()),
] + ([] if LINUX else [("uid in the log line (simulated /proc and stat)", t_uid_simulated, ())])
REGISTRAR_ONLY = [
    ("no HD_SESSION_SECRET", t_no_session_secret, ()),
    ("/data not writable, not root", t_data_not_writable, ()),
    ("both key forms", t_both_keys, ()),
    ("HD_REGISTRAR_SECRET_B58", t_b58, ()),
    ("a command other than serve", t_other_command, ()),
]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--bash", action="append", help="a bash to run the entrypoints with (repeatable; default: the bash on PATH)")
    ap.add_argument("--tree", default=str(REPO), help="the tree whose deploy/railway/*/entrypoint.sh to test (default: this repository)")
    opts = ap.parse_args()
    if os.geteuid() == 0:
        print("run this as a normal user: as root the copies would chown and drop privileges for real", file=sys.stderr)
        return 2
    shells = opts.bash or [shutil.which("bash")]
    runs = tempfile.mkdtemp(prefix="hd-entrypoint-test-")
    jobs = []
    for bash in shells:
        version = subprocess.run([bash, "-c", "echo $BASH_VERSION"], capture_output=True, text=True).stdout.strip()
        ctx = {"bash": bash, "tree": Path(opts.tree).resolve(), "runs": runs}
        for svc in SERVICES:
            for name, fn, extra in BOTH + (REGISTRAR_ONLY if svc == "registrar" else []):
                jobs.append((f"{svc}, bash {version}: {name}", fn, (ctx, svc) + extra))
    results = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        futures = {pool.submit(fn, *a): label for label, fn, a in jobs}
        for fut in concurrent.futures.as_completed(futures):
            try:
                results[futures[fut]] = fut.result()
            except Exception as e:  # a harness error is a failure too
                results[futures[fut]] = [f"harness error: {e!r}"]
    failed = 0
    for label, _fn, _a in jobs:
        fails = results[label]
        failed += bool(fails)
        print(("FAIL  " if fails else "PASS  ") + label + "".join("\n        " + x for x in fails))
    print(f"\n{len(jobs) - failed} passed, {failed} failed")
    if failed:
        print(f"the runs are kept in {runs}")
        return 1
    shutil.rmtree(runs, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
