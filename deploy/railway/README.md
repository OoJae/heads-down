# Railway services

One directory per service. Every Dockerfile builds from the **repository root** (each
service's Root Directory is `/`). The step-by-step setup, the variable matrix and the costs are
in [`docs/DEPLOY.md`](../../docs/DEPLOY.md#10-railway).

| Service | Image | Port | Healthcheck | Volume | Variables to seal |
|---|---|---|---|---|---|
| `crank` | Rust build → debian slim; entrypoint drops to uid 10001 | 8787 | `/healthz` | `/data` (lookup-table state) | `HD_CRANK_KEYPAIR_JSON`, `HELIUS_API_KEY` |
| `registrar` | Rust build → debian slim; entrypoint drops to uid 10001 | 8080 | `/healthz` | `/data` (nonces, transparency log) | `HD_REGISTRAR_KEYPAIR_JSON`, `HD_SESSION_SECRET`; `HD_RPC_URL` only if it is set to a URL with a key |
| `indexer` | node 26 alpine, production deps, `USER node` | 8080 | `/v1/health` | none (Postgres) | `RPC_URL` |
| `dashboard` | Next static export → node 26 alpine + `server.mjs`, `USER node` | 8080 | `/healthz` | none | none (`NEXT_PUBLIC_HD_API_BASE` is public, build time) |
| `Postgres` | Railway's PostgreSQL template ([postgres/README.md](postgres/README.md)) | 5432 (private) | Railway | Railway-managed | none of ours: Railway generates its credentials |

## The railway.json files are a record, not a switch

Railway's documentation says "New services cannot opt into Config as Code"
(<https://docs.railway.com/config-as-code>; files that older services already use stop being
read on 2026-12-01). So for a service created now, do not expect Railway to read
`deploy/railway/<service>/railway.json`. Each file records what its service needs, and the
values have to be set on the service, by hand or through the API, before its first deploy:

| In `railway.json` | Set on the service as | If it is missing |
|---|---|---|
| `build.dockerfilePath` | the service variable `RAILWAY_DOCKERFILE_PATH` (for example `deploy/railway/crank/Dockerfile`), or `dockerfilePath` through the API | Railway looks for a `Dockerfile` at the repository root, and there is none |
| `deploy.healthcheckPath`, `deploy.healthcheckTimeout` | the healthcheck path and timeout in the service's settings (the timeout also as the variable `RAILWAY_HEALTHCHECK_TIMEOUT_SEC`), or `healthcheckPath` and `healthcheckTimeout` through the API | no healthcheck: Railway does not wait for the service to answer before it switches to the new deploy |
| `build.watchPatterns` | the watch paths in the service's settings, or `watchPatterns` through the API | every push to `main` rebuilds the service |
| `deploy.drainingSeconds` | the service variable `RAILWAY_DEPLOYMENT_DRAINING_SECONDS`, or `drainingSeconds` through the API | Railway's default is 0 seconds between SIGTERM and SIGKILL, and the crank waits up to 8 seconds for what is in flight when it shuts down |
| `deploy.restartPolicyType`, `deploy.restartPolicyMaxRetries` | nothing: On Failure with 10 restarts is Railway's default | |
| `deploy.numReplicas` | nothing, as long as the service stays at one replica | |
| `deploy.requiredMountPath` | nothing: there is no such setting on a service. The crank's and the registrar's entrypoints check for the volume themselves (below) | |

Not tested, because it needs a service to be created: whether a new service still reads the
file when its path is given in the service's settings. Setting the values above is harmless
if it does.

Start commands come from the images (ENTRYPOINT/CMD): leave the service's Custom Start Command
empty, because one would replace the crank's and the registrar's entrypoint.

## Keys, the volume and the first deploy (crank and registrar)

The keypair JSON arrives as a sealed variable. The entrypoint checks its shape without
printing it, writes it to a `0600` file in a private `0700` directory on tmpfs (`/dev/shm`),
unsets the variable, starts the service as uid 10001 with `setpriv --no-new-privs`, and deletes
the file as soon as the service listens (it loads the key before it binds). If the entrypoint
stops before that, because a step failed or SIGTERM arrived, it removes the key directory on
its way out. SIGKILL cannot be caught: the file then stays until the container, and with it
the tmpfs, is gone.

Besides a missing or malformed key, two things make it refuse to start:

- **Shell tracing** (`bash -x` as a start command, or `SHELLOPTS=xtrace` among the variables):
  tracing would print the key into the deploy log. Exit code 2.
- **On Railway without a volume at `/data`**: when `RAILWAY_SERVICE_NAME` is set and
  `RAILWAY_VOLUME_MOUNT_PATH` is not `/data`. Without the volume the crank forgets its lookup
  table and creates (and pays rent for) a new one on every deploy, and the registrar loses its
  nonce database and its transparency log. Exit code 1, with the reason in the log. Attach the
  volume before the service's first deploy. Outside Railway nothing changes.

After the first deploy of each, read the deploy log for the entrypoint's line with this message
(the pid and the address will differ):

```
key file removed; hd-crank (pid 7, uid 10001) on 0.0.0.0:8787
```

`uid 10001` says the drop from root happened; `uid 0` or `uid unknown` on Railway means it did
not, or could not be read. This replaces looking with `ps` in a shell (the images have no `ps`).
The uid is the owner of `/proc/<pid>`; that part has not run on Linux yet (on macOS, which has
no `/proc`, the line says `unknown`).

The registrar leaves a second line to read, about its RPC. It asks `HD_RPC_URL` for the slot
once at start and logs `slot source answered`, or the warning `slot source gave no slot` with
the step that failed (the HTTP status, if there was one). `/healthz` makes no network call and
stays 200 either way, while without a slot every attestation answers 503 `slot_unavailable`.
The code's default is the public mainnet RPC;
[registrar/.env.example](registrar/.env.example) says why that default is not to be counted
on from Railway.

**Do not open a Railway shell (`railway ssh`) on crank or registrar.** The service does not
have the keypair JSON in its environment, but the entrypoint, which stays process 1, was started
with it, and Linux keeps a process's starting environment readable to root in
`/proc/<pid>/environ`. `HD_SESSION_SECRET` and the Helius key stay in the service's own
environment because it reads them there. If you must open one, never run `env`, `printenv` or
`set`, and never read `/proc/*/environ`.

## Sealed variables

Sealing is done in the Railway dashboard (the variable's three-dot menu, Seal). A sealed value
is given to builds and deployments and can no longer be read in the UI or through the API.

- Seal the three private values: `HD_CRANK_KEYPAIR_JSON`, `HD_REGISTRAR_KEYPAIR_JSON` and
  `HD_SESSION_SECRET`.
- Seal every variable that carries the Helius key or a URL with the key in it: the shared
  `HELIUS_API_KEY`, the crank's `HELIUS_API_KEY`, the indexer's `RPC_URL`, and the registrar's
  `HD_RPC_URL` if it is set to such a URL. A variable that only references the shared one
  (`${{shared.HELIUS_API_KEY}}`) is a variable of its own and must be sealed as well: Railway's
  documentation does not say whether an unsealed reference to a sealed value can be read back,
  and it was not tested.
- `DATABASE_URL` on the indexer is not a sealed value. It is a reference to a variable Railway
  generates for Postgres (`${{Postgres.DATABASE_URL}}`), so whoever can list the project's
  variables can read the database password. [postgres/README.md](postgres/README.md) says how
  to keep that password from being enough to reach the database.
- Never run `railway config pull --include-variables` in this repository. `railway config pull`
  writes the project's configuration to `.railway/railway.ts`, and with that flag every value
  that is not sealed is written into the file (Railway's Infrastructure as Code page). The root
  `.gitignore` ignores `/.railway/` as a second line of defence.

## Checks without Docker

```bash
python3 deploy/railway/check.py             # static checks, and the dashboard's build guard under sh and dash
python3 deploy/railway/test_entrypoints.py  # runs both entrypoints with a made-up key and a stub service
```

`check.py`: pinned base images, COPY sources, no `VOLUME`, schema keys, healthcheck routes,
empty secrets, the three guards of each entrypoint and the uid in its log line, the dashboard's
guard on `NEXT_PUBLIC_HD_API_BASE` (run on its own against accepted and refused values), the
link from this file into `docs/DEPLOY.md`, and hadolint and shellcheck when installed.

`test_entrypoints.py`: copies of the two entrypoints in a scratch directory outside the
repository, with the bash on PATH or the ones named with `--bash`. Its first lines say what it
covers and what it cannot (nothing runs as root, so the real drop to uid 10001 is not
exercised).

Base images are pinned by digest (multi-arch index): `rust:1.97.1-bookworm` (= the repo's
toolchain pin), `debian:bookworm-20260918-slim`, `node:26.10.0-alpine3.24`, and for the
standalone `registrar/Dockerfile`, `gcr.io/distroless/cc-debian12:nonroot`. To bump one,
resolve the new digest (`docker buildx imagetools inspect <image:tag>`) and update every file
that uses it.
