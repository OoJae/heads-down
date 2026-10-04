#!/usr/bin/env python3
"""Checks for deploy/railway (and registrar/Dockerfile), runnable without Docker. All of them
read files, except one: the dashboard's build guard is also run.

    python3 deploy/railway/check.py            # exit 1 on any failure

Per service (crank, registrar, indexer, dashboard):
  * Dockerfile: every COPY source (not --from) exists in the build context; every FROM resolves
    to an image pinned by digest; no VOLUME (Railway refuses it); the process does not stay root
    (a USER line, or an entrypoint that drops privileges with setpriv);
  * railway.json: only keys Railway's config-as-code schema defines, builder DOCKERFILE, the
    dockerfilePath exists, numReplicas 1, a healthcheck path that the service really serves;
  * .env.example: secret-bearing variables have no value;
  * hadolint and shellcheck, when they are installed (or HADOLINT / SHELLCHECK point at them).

The entrypoints (crank, registrar): the xtrace guard, the EXIT trap and the Railway volume
guard are there, and come before the first line that names a secret; the 'key file removed'
log line carries the uid. What they do when they run is test_entrypoints.py's job.

The dashboard Dockerfile: the guard on NEXT_PUBLIC_HD_API_BASE is the first thing in the RUN
that builds the site, and it is run here, on its own, under the local `sh` (and `dash` when
installed) against values it must accept and values it must refuse.

README.md: its link into docs/DEPLOY.md points at a heading that exists.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
RAILWAY = REPO / "deploy" / "railway"
SERVICES = ["crank", "registrar", "indexer", "dashboard"]
# Where each healthcheck path is served, as (file, needle).
ROUTES = {
    "crank": ("crank/src/intake.rs", '.route("/healthz"'),
    "registrar": ("registrar/src/http/mod.rs", '.route("/healthz"'),
    "indexer": ("services/indexer/src/api/server.ts", '"/v1/health"'),
    "dashboard": ("deploy/railway/dashboard/server.mjs", 'pathname === "/healthz"'),
}
SCHEMA_KEYS = {
    "build": {"builder", "watchPatterns", "buildCommand", "dockerfilePath", "nixpacksConfigPath", "nixpacksPlan", "nixpacksVersion", "railpackVersion"},
    "deploy": {
        "startCommand", "preDeployCommand", "preDeployTimeoutSeconds", "numReplicas", "healthcheckPath", "healthcheckTimeout",
        "sleepApplication", "runtime", "registryCredentials", "restartPolicyType", "restartPolicyMaxRetries", "cronSchedule",
        "region", "multiRegionConfig", "limitOverride", "requiredMountPath", "overlapSeconds", "drainingSeconds", "ipv6EgressEnabled",
    },
}
SECRET = re.compile(r"(KEY|SECRET|TOKEN|PASSWORD|KEYPAIR|_JSON|_B58|DATABASE_URL|RPC_URL)$")
DIGEST = re.compile(r"^[a-z0-9./_-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}$")
# What an entrypoint must carry before its first line that names a secret.
ENTRYPOINT_GUARDS = {
    "the xtrace guard": re.compile(r"case \$- in\s+\*x\*\)[^\n]*\bexit 2\b"),
    "the EXIT trap that removes the key directory": re.compile(r"""trap '[^'\n]*rm -rf "\$key_dir"[^'\n]*' EXIT"""),
    "the Railway volume guard": re.compile(r"RAILWAY_SERVICE_NAME[^\n]*\n[^\n]*RAILWAY_VOLUME_MOUNT_PATH[^\n]*\n[^\n]*== /data \]\] \|\| fail "),
}
# NEXT_PUBLIC_HD_API_BASE values the dashboard build must accept, and values it must refuse.
# `0123abcd-dummy` stands for a key: it must never come back in a message.
API_BASE_ACCEPTED = [
    "https://indexer-production-1a2b.up.railway.app",
    "https://indexer-production-1a2b.up.railway.app/",
    "https://indexer.example.org",
    "https://indexer.example.org:8443",
]
API_BASE_REFUSED = [
    "",
    "https://",
    "https:///",
    "https://.example.org",
    "http://indexer.example.org",
    "HTTPS://indexer.example.org",
    "indexer.example.org",
    "https://mainnet.helius-rpc.com/?api-key=0123abcd-dummy",
    "https://indexer.example.org?token=0123abcd-dummy",
    "https://user:0123abcd-dummy@indexer.example.org",
    "https://indexer.example.org/API-KEY/0123abcd-dummy",
    "https://api-key.example.org",
    "https://rpc.example.org/v2/0123abcd-dummy",
    "https://indexer.example.org/v1",
    "https://indexer.example.org//",
    "https://indexer.example.org#0123abcd-dummy",
    "https://indexer.example.org/ ",
    " https://indexer.example.org",
    "https://indexer.example.org\nhttps://other.example.org",
    "https://${{indexer.RAILWAY_PUBLIC_DOMAIN}}",
]

failures: list[str] = []


def ok(msg: str) -> None:
    print(f"PASS  {msg}")


def fail(msg: str) -> None:
    failures.append(msg)
    print(f"FAIL  {msg}")


def instructions(dockerfile: Path) -> list[tuple[str, str]]:
    """(INSTRUCTION, arguments) with continuations joined and comments dropped."""
    out, buf = [], ""
    for raw in dockerfile.read_text().splitlines():
        line = raw.strip()
        if not buf and (not line or line.startswith("#")):
            continue
        if line.endswith("\\"):
            buf += line[:-1] + " "
            continue
        buf += line
        word, _, rest = buf.strip().partition(" ")
        out.append((word.upper(), rest.strip()))
        buf = ""
    return out


def check_dockerfile(name: str, dockerfile: Path, context: Path, entrypoint: Path | None) -> None:
    ins = instructions(dockerfile)
    args = {}
    for word, rest in ins:
        if word == "ARG" and "=" in rest:
            k, v = rest.split("=", 1)
            args[k.strip()] = v.strip()
    stages = set()
    for word, rest in ins:
        if word == "FROM":
            parts = rest.split()
            image = re.sub(r"\$\{(\w+)\}", lambda m: args.get(m.group(1), m.group(0)), parts[0])
            if len(parts) >= 3 and parts[1].upper() == "AS":
                stages.add(parts[2])
            if DIGEST.match(image):
                ok(f"{name}: FROM {image.split('@')[0]} pinned by digest")
            else:
                fail(f"{name}: FROM {image} is not pinned by digest")
        elif word == "VOLUME":
            fail(f"{name}: VOLUME is refused by Railway (use a Railway volume)")
        elif word in ("COPY", "ADD"):
            toks = [t for t in rest.split() if not t.startswith("--")]
            if any(t.startswith("--from=") for t in rest.split()):
                continue
            for src in toks[:-1]:
                p = context / src
                if p.exists():
                    ok(f"{name}: COPY {src} exists in the build context")
                else:
                    fail(f"{name}: COPY {src} does not exist under {context.relative_to(REPO) if context != REPO else '.'}")
    users = [rest for word, rest in ins if word == "USER"]
    drops = entrypoint is not None and "setpriv" in entrypoint.read_text()
    if users and users[-1].split(":")[0] not in ("root", "0"):
        ok(f"{name}: runs as USER {users[-1]}")
    elif drops:
        ok(f"{name}: entrypoint drops to an unprivileged uid with setpriv")
    elif users:
        fail(f"{name}: last USER is root")
    else:
        ok(f"{name}: no USER (base image default)") if name == "registrar/Dockerfile" else fail(f"{name}: runs as root")
    hadolint = os.environ.get("HADOLINT") or shutil.which("hadolint")
    if hadolint:
        r = subprocess.run([hadolint, "--failure-threshold", "warning", str(dockerfile)], capture_output=True, text=True)
        if r.returncode == 0:
            ok(f"{name}: hadolint clean")
        else:
            fail(f"{name}: hadolint\n{r.stdout}{r.stderr}")
    else:
        print(f"SKIP  {name}: hadolint not installed")


def check_railway_json(svc: str) -> None:
    p = RAILWAY / svc / "railway.json"
    try:
        cfg = json.loads(p.read_text())
    except (OSError, ValueError) as e:
        fail(f"{svc}: railway.json: {e}")
        return
    extra = set(cfg) - {"$schema", "build", "deploy", "environments"}
    if extra:
        fail(f"{svc}: railway.json has unknown top-level keys {sorted(extra)}")
    for section, keys in SCHEMA_KEYS.items():
        unknown = set(cfg.get(section, {})) - keys
        if unknown:
            fail(f"{svc}: railway.json {section} has keys outside Railway's schema: {sorted(unknown)}")
    b, d = cfg.get("build", {}), cfg.get("deploy", {})
    if b.get("builder") != "DOCKERFILE":
        fail(f"{svc}: builder must be DOCKERFILE")
    df = REPO / b.get("dockerfilePath", "")
    if b.get("dockerfilePath") and df.is_file():
        ok(f"{svc}: dockerfilePath {b['dockerfilePath']} exists")
    else:
        fail(f"{svc}: dockerfilePath {b.get('dockerfilePath')} missing")
    if d.get("restartPolicyType") not in ("ON_FAILURE", "ALWAYS", "NEVER"):
        fail(f"{svc}: restartPolicyType {d.get('restartPolicyType')}")
    if d.get("numReplicas") != 1:
        fail(f"{svc}: numReplicas must be 1 (volumes and the crank's in-memory heartbeats need a single instance)")
    path = d.get("healthcheckPath")
    src, needle = ROUTES[svc]
    if path and needle in (REPO / src).read_text() and path in needle:
        ok(f"{svc}: healthcheck {path} is served ({src})")
    else:
        fail(f"{svc}: healthcheck {path} not found in {src}")
    for pat in b.get("watchPatterns", []):
        if not pat.startswith("/"):
            fail(f"{svc}: watch pattern {pat} must be absolute (Railway evaluates them from /)")


def check_env_example(svc: str) -> None:
    p = RAILWAY / svc / ".env.example"
    if not p.is_file():
        fail(f"{svc}: .env.example missing")
        return
    names = []
    for line in p.read_text().splitlines():
        m = re.match(r"^\s*([A-Z][A-Z0-9_]*)=(.*)$", line)
        if not m:
            continue
        k, v = m.group(1), m.group(2).strip()
        names.append(k)
        if SECRET.search(k) and v and not v.startswith("${{"):
            fail(f"{svc}: .env.example gives {k} a value; secrets must be empty")
    ok(f"{svc}: .env.example lists {len(names)} variables, secrets empty")


def check_entrypoint(svc: str, entrypoint: Path) -> None:
    """The guards are there, and nothing that names a secret comes before them."""
    code = "\n".join(line for line in entrypoint.read_text().splitlines() if not line.lstrip().startswith("#"))
    first_secret = re.search(r"KEYPAIR|SECRET", code)
    for what, pattern in ENTRYPOINT_GUARDS.items():
        m = pattern.search(code)
        if m and first_secret and m.end() < first_secret.start():
            ok(f"{svc}: entrypoint.sh has {what}, before the first line that names a secret")
        else:
            fail(f"{svc}: entrypoint.sh lacks {what}, or a line that names a secret comes before it")
    line = f'key file removed; hd-{svc} (pid $child, uid $(stat -c %u "/proc/$child" 2>/dev/null || echo unknown))'
    if line in code:
        ok(f"{svc}: the 'key file removed' log line carries the uid of the service")
    else:
        fail(f"{svc}: the 'key file removed' log line does not carry the uid of the service")


def check_dashboard_guard() -> None:
    """The guard on NEXT_PUBLIC_HD_API_BASE gates the build, and behaves under POSIX shells."""
    runs = [rest for word, rest in instructions(RAILWAY / "dashboard" / "Dockerfile") if word == "RUN" and "pnpm build" in rest]
    m = re.match(r'(case "\$NEXT_PUBLIC_HD_API_BASE" in .*? esac) +&& pnpm build\b', runs[0]) if len(runs) == 1 else None
    if not m:
        fail("dashboard: the RUN that builds the site does not start with the NEXT_PUBLIC_HD_API_BASE guard")
        return
    ok("dashboard: the NEXT_PUBLIC_HD_API_BASE guard comes before pnpm build in the same RUN")
    cases = [(v, True) for v in API_BASE_ACCEPTED] + [(v, False) for v in API_BASE_REFUSED]
    shells = [s for s in ("sh", "dash") if shutil.which(s)]
    for shell in shells:
        wrong = []
        with tempfile.TemporaryDirectory() as tmp:
            for value, accepted in cases:
                r = subprocess.run(
                    [shutil.which(shell), "-c", m.group(1)],
                    env={"PATH": "/usr/bin:/bin", "NEXT_PUBLIC_HD_API_BASE": value},
                    cwd=tmp, capture_output=True, text=True, timeout=30,
                )
                said = r.stdout + r.stderr
                if accepted:
                    good = r.returncode == 0 and not said
                else:
                    # Refused with a message that names the variable and never shows its value.
                    good = r.returncode == 1 and not r.stdout and r.stderr.startswith("NEXT_PUBLIC_HD_API_BASE ")
                    good = good and "0123abcd-dummy" not in said and not (len(value) > len("https://") and value in said)
                if not good:
                    wrong.append(value)
        if wrong:
            fail(f"dashboard: the guard, run under {shell}, is wrong about {wrong}")
        else:
            ok(f"dashboard: the guard, run under {shell}, accepts {len(API_BASE_ACCEPTED)} values and refuses {len(API_BASE_REFUSED)} without showing them")
    for shell in ("sh", "dash"):
        if shell not in shells:
            print(f"SKIP  dashboard: {shell} not installed, the guard was not run under it")


def check_readme_links() -> None:
    """Links from README.md into docs/ name a file and a heading that exist."""
    links = re.findall(r"\]\(((?:\.\./)+docs/[A-Za-z0-9_./-]+\.md)#([^)\s]+)\)", (RAILWAY / "README.md").read_text())
    if not links:
        fail("README.md: no link into docs/ with a heading anchor (the setup is in docs/DEPLOY.md)")
    for target, anchor in links:
        doc = (RAILWAY / target).resolve()
        if not doc.is_file():
            fail(f"README.md: {target} does not exist")
            continue
        headings = [h.strip() for h in re.findall(r"^#{1,6} +(.+)$", doc.read_text(), re.M)]
        # GitHub's anchor: lower case, punctuation dropped, spaces to hyphens.
        anchors = {re.sub(r"[^\w\- ]", "", h.lower()).replace(" ", "-") for h in headings}
        if anchor in anchors:
            ok(f"README.md: {target}#{anchor} is a heading there")
        else:
            fail(f"README.md: {target} has no heading with the anchor #{anchor}")


def main() -> int:
    for svc in SERVICES:
        print(f"\n== {svc} ==")
        ep = RAILWAY / svc / "entrypoint.sh"
        check_dockerfile(svc, RAILWAY / svc / "Dockerfile", REPO, ep if ep.exists() else None)
        check_railway_json(svc)
        check_env_example(svc)
        if ep.exists():
            check_entrypoint(svc, ep)
            sc = os.environ.get("SHELLCHECK") or shutil.which("shellcheck")
            if sc:
                r = subprocess.run([sc, str(ep)], capture_output=True, text=True)
                ok(f"{svc}: shellcheck entrypoint.sh clean") if r.returncode == 0 else fail(f"{svc}: shellcheck\n{r.stdout}")
            else:
                print(f"SKIP  {svc}: shellcheck not installed")
        if svc == "dashboard":
            check_dashboard_guard()
    print("\n== registrar/Dockerfile (standalone image, context registrar/) ==")
    check_dockerfile("registrar/Dockerfile", REPO / "registrar" / "Dockerfile", REPO / "registrar", None)
    print("\n== README.md ==")
    check_readme_links()
    print()
    if failures:
        print(f"{len(failures)} failure(s)")
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
