# Railway services

One directory per service. Every Dockerfile builds from the **repository root** (each
service's Root Directory is `/`), and each `railway.json` names its Dockerfile, its watch paths,
its healthcheck and its restart policy. The step-by-step setup, the variable matrix and the
costs are in [`docs/DEPLOY.md`](../../docs/DEPLOY.md#5-railway).

| Service | Railway config file | Image | Port | Healthcheck | Volume | Secrets (sealed variables) |
|---|---|---|---|---|---|---|
| `crank` | `/deploy/railway/crank/railway.json` | Rust build → debian slim; entrypoint drops to uid 10001 | 8787 | `/healthz` | `/data` (lookup-table state) | `HD_CRANK_KEYPAIR_JSON`, `HELIUS_API_KEY` |
| `registrar` | `/deploy/railway/registrar/railway.json` | Rust build → debian slim; entrypoint drops to uid 10001 | 8080 | `/healthz` | `/data` (nonces, transparency log) | `HD_REGISTRAR_KEYPAIR_JSON`, `HD_SESSION_SECRET`, `HD_RPC_URL` |
| `indexer` | `/deploy/railway/indexer/railway.json` | node 26 alpine, production deps, `USER node` | 8080 | `/v1/health` | none (Postgres) | `DATABASE_URL`, `RPC_URL` |
| `dashboard` | `/deploy/railway/dashboard/railway.json` | Next static export → node 26 alpine + `server.mjs`, `USER node` | 8080 | `/healthz` | none | none (`NEXT_PUBLIC_HD_API_BASE` is public, build time) |
| `Postgres` | Railway's PostgreSQL template ([postgres/README.md](postgres/README.md)) | Railway-managed | 5432 (private) | Railway | Railway-managed | Railway-generated |

Start commands come from the images (ENTRYPOINT/CMD), so `railway.json` sets none: leave the
service's Custom Start Command empty, because one would replace the crank's and the
registrar's entrypoint.

Key handling (crank and registrar): the keypair JSON arrives as a sealed variable; the
entrypoint checks its shape without printing it, writes it to a `0600` file in a private `0700`
directory on tmpfs (`/dev/shm`), unsets the variable, starts the service as uid 10001 with
`setpriv --no-new-privs`, and deletes the file as soon as the service listens (it loads the key
before it binds). Only root in the container can read the original environment.

Checks without Docker (pinned base images, COPY sources, no `VOLUME`, schema keys,
healthcheck routes, empty secrets, hadolint and shellcheck when installed):

```bash
python3 deploy/railway/check.py
```

Base images are pinned by digest (multi-arch index): `rust:1.97.1-bookworm` (= the repo's
toolchain pin), `debian:bookworm-20260918-slim`, `node:26.10.0-alpine3.24`, and for the
standalone `registrar/Dockerfile`, `gcr.io/distroless/cc-debian12:nonroot`. To bump one,
resolve the new digest (`docker buildx imagetools inspect <image:tag>`) and update every file
that uses it.
