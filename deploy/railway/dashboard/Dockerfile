# syntax=docker/dockerfile:1.7
#
# Heads Down dashboard on Railway (docs/DEPLOY.md, "Railway"): the static Next export
# (dashboard/, `output: "export"`) served by deploy/railway/dashboard/server.mjs, a
# dependency-free static server with /healthz. The site has no secrets: its only setting,
# NEXT_PUBLIC_HD_API_BASE (the indexer's public URL), is inlined at BUILD time, so set it as a
# service variable before the first build (Railway passes variables to builds that declare
# them with ARG, below). The build stops unless it is https://<host> and nothing else.
#
# Build context: the REPOSITORY ROOT (Root Directory "/"). This file's path is a setting of the
# service (deploy/railway/README.md); railway.json records it. Locally:
#   docker build -f deploy/railway/dashboard/Dockerfile \
#     --build-arg NEXT_PUBLIC_HD_API_BASE=https://indexer.example.org -t hd-dashboard .

ARG NODE_IMAGE=node:26.10.0-alpine3.24@sha256:0b36e8c136b94cd4fcf02188228e76c31ad5872eef3fec8cbd2eee500cfd9e80

FROM ${NODE_IMAGE} AS build
# Node 25+ no longer ships corepack: install the pnpm version package.json pins.
RUN npm install --global --no-fund --no-audit pnpm@11.1.2
WORKDIR /app
COPY dashboard/package.json dashboard/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
# Exactly the tracked sources (no host node_modules/.next/out can leak in).
COPY dashboard/next.config.ts dashboard/tsconfig.json dashboard/vitest.config.ts ./
COPY dashboard/src ./src
COPY dashboard/test ./test
COPY dashboard/scripts ./scripts
ARG NEXT_PUBLIC_HD_API_BASE
ENV NEXT_PUBLIC_HD_API_BASE=${NEXT_PUBLIC_HD_API_BASE} \
    NEXT_TELEMETRY_DISABLED=1
# Whatever this variable holds is published in the site's JavaScript, so the build stops unless
# it is https://<host> (a port and one trailing "/" are allowed) with nothing a secret could
# sit in: no "?", no "@", no "api-key", no path. A bare "https://" is refused as well: it is
# what the reference https://${{indexer.RAILWAY_PUBLIC_DOMAIN}} would leave if it resolved to
# nothing (not checked on Railway). The messages never show the value. The check runs under
# the image's /bin/sh (busybox ash), so it is POSIX sh; deploy/railway/check.py runs it under
# the local sh, and under dash when that is installed. It has not run under busybox.
RUN case "$NEXT_PUBLIC_HD_API_BASE" in \
      "") echo "NEXT_PUBLIC_HD_API_BASE is empty: set it to the indexer's public https URL before building" >&2; exit 1 ;; \
      *[?@]*|*[Aa][Pp][Ii]-[Kk][Ee][Yy]*) echo "NEXT_PUBLIC_HD_API_BASE has a '?', an '@' or 'api-key' in it: the value is published in the site's JavaScript, so it must be the indexer's bare https URL, never a URL with a key" >&2; exit 1 ;; \
      *[!A-Za-z0-9.:/-]*|https://*/?*) echo "NEXT_PUBLIC_HD_API_BASE must be https://<host> and nothing else: no path, no spaces, no other characters" >&2; exit 1 ;; \
      https://[A-Za-z0-9]*) ;; \
      *) echo "NEXT_PUBLIC_HD_API_BASE is not an https URL with a host (if it is a bare https://, check that the indexer has a public domain)" >&2; exit 1 ;; \
    esac \
 && pnpm build \
 && test -f out/index.html

FROM ${NODE_IMAGE} AS runtime
ENV NODE_ENV=production \
    PORT=8080 \
    DASHBOARD_ROOT=/app/out
WORKDIR /app
COPY --from=build /app/out ./out
COPY deploy/railway/dashboard/server.mjs ./server.mjs
# The image's own unprivileged user (uid 1000).
USER node
EXPOSE 8080
CMD ["node", "server.mjs"]
