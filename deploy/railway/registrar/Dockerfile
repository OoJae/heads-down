# syntax=docker/dockerfile:1.7
#
# hd-registrar on Railway (docs/DEPLOY.md, "Railway"). Same build as registrar/Dockerfile
# (same pinned Rust image, same two-step cached build, same runtime defaults); the differences
# are the build context and the runtime:
#   * context = the REPOSITORY ROOT (one Root Directory, "/", for every service), so paths below
#     start with registrar/. This file's path is a setting of the service
#     (deploy/railway/README.md); railway.json records it.
#   * runtime = debian bookworm-slim instead of distroless, because the entrypoint (bash) writes
#     HD_REGISTRAR_KEYPAIR_JSON to a 0600 file on tmpfs and hands the Railway volume (/data:
#     nonce database + transparency log) to uid 10001 before dropping to it with setpriv.
# Locally:
#   docker build -f deploy/railway/registrar/Dockerfile -t hd-registrar-railway .

ARG RUST_IMAGE=rust:1.97.1-bookworm@sha256:0e2bcaef56d041a486784e54104a81aebe0da44bd03019bd70bc0401e42e4a97
ARG RUNTIME_IMAGE=debian:bookworm-20260918-slim@sha256:3783cc01769c7b2b1b83a5c5ad96c815348e28ed7da68e2e3687004faa906251

FROM ${RUST_IMAGE} AS build
ENV CARGO_TERM_COLOR=never \
    CARGO_NET_RETRY=5
WORKDIR /src

# 1. Dependencies only (cached until Cargo.toml / Cargo.lock change).
COPY registrar/Cargo.toml registrar/Cargo.lock ./
RUN mkdir src \
 && echo 'fn main() {}' > src/main.rs \
 && touch src/lib.rs \
 && cargo build --release --locked \
 && rm -rf src

# 2. The service. Trust anchors are compiled in from roots/.
COPY registrar/src ./src
COPY registrar/roots ./roots
RUN touch src/main.rs src/lib.rs \
 && cargo build --release --locked --bin hd-registrar \
 && install -m 0755 target/release/hd-registrar /hd-registrar

FROM ${RUNTIME_IMAGE} AS runtime
RUN groupadd --system --gid 10001 hd \
 && useradd --system --uid 10001 --gid hd --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin hd \
 && mkdir -p /data \
 && chown 10001:10001 /data
COPY --from=build /hd-registrar /usr/local/bin/hd-registrar
COPY deploy/railway/registrar/entrypoint.sh /usr/local/bin/hd-registrar-entrypoint
RUN chmod 0755 /usr/local/bin/hd-registrar-entrypoint
# The same defaults as registrar/Dockerfile; HD_BIND is derived from PORT by the entrypoint.
ENV HD_NONCE_STORE=sqlite:/data/nonces.db \
    HD_TRANSPARENCY_LOG=/data/attestations.jsonl \
    HD_LOG_FORMAT=json \
    RUST_LOG=info \
    PORT=8080
EXPOSE 8080
# No USER line on purpose: the entrypoint drops to uid 10001 itself (see the header).
ENTRYPOINT ["/bin/bash", "/usr/local/bin/hd-registrar-entrypoint"]
CMD ["serve"]
