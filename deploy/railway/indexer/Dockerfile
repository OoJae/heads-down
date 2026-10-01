# syntax=docker/dockerfile:1.7
#
# Heads Down indexer on Railway (docs/DEPLOY.md, "Railway"): the public read API behind the
# dashboard, ingesting mainnet into Railway Postgres (DATABASE_URL).
#
# Build context: the REPOSITORY ROOT (Root Directory "/"); config file
# /deploy/railway/indexer/railway.json. Locally:
#   docker build -f deploy/railway/indexer/Dockerfile -t hd-indexer .
#
# Node 26 runs the TypeScript sources directly (type stripping), so there is no build step:
# production dependencies only (pg, @electric-sql/pglite, @solana/addresses), installed from
# the frozen lockfile. `serve` applies the migrations itself before it listens.

ARG NODE_IMAGE=node:26.10.0-alpine3.24@sha256:0b36e8c136b94cd4fcf02188228e76c31ad5872eef3fec8cbd2eee500cfd9e80

FROM ${NODE_IMAGE} AS deps
# Node 25+ no longer ships corepack: install the pnpm version package.json pins.
RUN npm install --global --no-fund --no-audit pnpm@11.1.2
WORKDIR /app
COPY services/indexer/package.json services/indexer/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile --prod

FROM ${NODE_IMAGE} AS runtime
ENV NODE_ENV=production \
    HOST=0.0.0.0 \
    PORT=8080 \
    INDEXER_DATASET=mainnet
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY services/indexer/package.json ./package.json
COPY services/indexer/src ./src
COPY services/indexer/migrations ./migrations
# The image's own unprivileged user (uid 1000).
USER node
EXPOSE 8080
CMD ["node", "src/main.ts", "serve"]
