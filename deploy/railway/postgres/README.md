# Postgres (Railway template)

The indexer's database is Railway's own **PostgreSQL** database service, not an image from this
repo: Railway runs, backs up and upgrades it, and provides the connection variables.

1. In the Railway project: **+ New → Database → Add PostgreSQL**. Keep the service name
   `Postgres` (the reference below uses it).
2. Railway attaches a volume and generates the credentials. The service exposes
   `DATABASE_URL` (private network: `postgresql://postgres:…@postgres.railway.internal:5432/railway`),
   `DATABASE_PUBLIC_URL` (TCP proxy, for your own `psql` only), and `PGHOST`, `PGPORT`,
   `PGUSER`, `PGPASSWORD`, `PGDATABASE`.
3. In the **indexer** service set `DATABASE_URL=${{Postgres.DATABASE_URL}}`. The private URL
   stays on Railway's internal network (no egress cost, no TLS needed). Do not give the indexer
   the public URL.
4. Nothing else: `node src/main.ts serve` applies `services/indexer/migrations/*.sql` on start
   (each once, in a transaction, recorded in `schema_migrations`).

Sizing: the indexer stores events and accounts for one dataset, tens of MB at hackathon scale.
The smallest instance (about 256 MB of RAM in use, 1 GB volume) is enough; Railway bills
usage, roughly $2-4 a month at that size.

Backups: the database can be rebuilt from the chain (`INDEXER_DATASET=mainnet`, empty database,
`RPC_MAX_BACKFILL` large enough), so a lost database costs re-ingestion time, not data.
Railway's volume backups (service → Backups) are still worth enabling on the Pro plan.

Local equivalent: `services/indexer/docker-compose.yml` (postgres:17-alpine on 127.0.0.1:54329)
or `DATABASE_URL=pglite://.data/pg` with no Postgres at all.
