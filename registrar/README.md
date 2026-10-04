# Heads Down registrar

An off-chain service with two jobs, and **no custody of anything**:

1. **Sign-In-With-Solana (SIWS).** Single-use, 128-bit, 10-minute nonces and short-lived HMAC
   session tokens bound to a wallet address.
2. **Android Key Attestation registrar.** It checks that a rig's P-256 key was generated inside the
   TEE or StrongBox of a genuine, locked Android device, by the genuine Heads Down app, for this
   wallet. It then signs an **Ed25519 voucher**. The `heads_down` program verifies the voucher
   through the Ed25519SigVerify precompile in `register_rig` / `rotate_key` and sets
   `Rig.attestation_level`.

Why a registrar exists at all: Google's attestation roots are RSA-4096 and P-384 inside X.509
chains, which a Solana program cannot verify. The registrar verifies them off-chain and says so with
one Ed25519 signature that the program *can* verify (THREAT_MODEL.md, K4). Every voucher it issues
is published with its full certificate chain, so nobody has to trust it.

```
phone (Keystore P-256)            registrar                               heads_down program
  |  POST /siws/nonce  ------------> nonce N1 (single use, 10 min)
  |  MWA signIn(N1) -> signature
  |  POST /siws/verify ------------> Ed25519 check, consume N1 -> session token
  |  GET /attest/challenge --------> nonce N2 bound to authority
  |  <- challenge = SHA-256("HDattest" || authority || N2)
  |  generate key with setAttestationChallenge(challenge)
  |  POST /attest (chain, key, N2) -> chain to Google root, policy, consume N2,
  |                                   sign HDreg voucher, append to public log
  |  <- Ed25519SigVerify ix bytes
  |  wallet signs register_rig tx [Ed25519SigVerify ix, register_rig ix] -----> checks voucher,
  |                                                                               sets attestation_level
```

## Quick start

```sh
cd registrar
cargo test                                   # 114 tests; no network needed
mkdir -p data
cargo run -- keygen ./data/registrar-keypair.json   # prints the registrar pubkey; file is 0600
export HD_SESSION_SECRET=$(openssl rand -hex 32)
export HD_REGISTRAR_KEYPAIR=./data/registrar-keypair.json
export HD_APP_RELEASE_CERT_SHA256=<sha256 of the release signing cert>
export HD_SIWS_DOMAIN=headsdown.example      # the host of the site the app identifies itself with
export HD_LOG_FORMAT=pretty
cargo run -- serve                           # listens on 0.0.0.0:8080
curl -s localhost:8080/registrar | jq .
```

Container: `docker build -t hd-registrar .` (see the header of `Dockerfile`). All settings are
`HD_*` environment variables, documented in [`.env.example`](.env.example).

## Protocol

### 1. Sign-In-With-Solana

`POST /siws/nonce` returns the nonce **and every field the app must put into MWA's
`SignInWithSolana.Payload`**:

```json
{ "nonce": "52abd86bf70689cd6894b60a38a4b2b6", "domain": "headsdown.example",
  "uri": "https://headsdown.example", "version": "1", "chain_ids": ["solana:mainnet"],
  "statement": "Sign in to Heads Down.", "issued_at": "2026-09-29T19:50:28Z",
  "expiration_time": "2026-09-29T20:00:28Z" }
```

The wallet signs the CAIP-122 text produced by wallet-standard's `createSignInMessageText` and MWA's
`SignInWithSolana.Payload.prepareMessage()` (identical formats; confirmed by disassembling
clientlib 2.0.3):

```
headsdown.example wants you to sign in with your Solana account:
<base58 address>

Sign in to Heads Down.

URI: https://headsdown.example
Version: 1
Chain ID: solana:mainnet
Nonce: 52abd86bf70689cd6894b60a38a4b2b6
Issued At: 2026-09-29T19:50:28Z
Expiration Time: 2026-09-29T20:00:28Z
```

`POST /siws/verify` with `{"signed_message": base64, "signature": base64, "address"?: base58}`
checks, in order, and **without touching server state**:

| Check | Error code |
|---|---|
| message <= 2 KiB, UTF-8 | `message_too_large`, `message_not_utf8` |
| canonical parse: re-serializing must reproduce the bytes exactly; no control characters, no duplicated, reordered or unknown fields, no trailing newline | `message_malformed` |
| `domain` == `HD_SIWS_DOMAIN`, `URI` == `HD_SIWS_URI` | `domain_mismatch`, `uri_mismatch` |
| `Version: 1`, `Chain ID` in `HD_SIWS_CHAINS` | `unsupported_version`, `chain_not_allowed` |
| nonce present and shaped like ours | `nonce_missing`, `nonce_malformed` |
| `Issued At` <= now + 60 s and >= now - (600 + 60) s; `Expiration Time` > now and <= `Issued At` + 660 s; `Not Before` <= now + 60 s | `issued_in_future`, `issued_too_long_ago`, `message_expired`, `validity_too_long`, `message_not_yet_valid` |
| address is a canonical base58 32-byte key (and equals `address` if given) | `address_invalid`, `address_mismatch` |
| Ed25519 `verify_strict` over the exact signed bytes | `signature_invalid` |

Only then is the nonce **consumed atomically** (`nonce_unknown_or_used`, `nonce_expired`). So a
forged request cannot burn a legitimate nonce, and of any number of concurrent replays exactly one
succeeds (tested with 16 in parallel). The response is
`{"session_token", "address", "chain_id", "expires_at"}`.

**Session token:** `hds1.<base64url(JSON claims)>.<base64url(HMAC-SHA256(key, "hds1." || claims_b64))>`,
claims `{sub, chain, iat, exp, jti}`, default lifetime 1 h (max 24 h). The MAC is checked in
constant time before any JSON is parsed. Send it as `Authorization: Bearer <token>`.

### 2. Attestation challenge

`GET /attest/challenge` (Bearer session) issues a nonce bound to the session's wallet and returns:

```
challenge = SHA-256( "HDattest" || authority(32) || nonce(16) )      -- 56-byte preimage
```

```json
{ "authority": "<base58>", "nonce": "<32 hex>", "challenge": "<64 hex>",
  "challenge_base64": "...", "expires_at": "..." }
```

The app passes the 32 challenge bytes to `KeyGenParameterSpec.Builder.setAttestationChallenge()`
when it generates the rig key (`RigKeyManager.generate(alias, challenge)`).

> The challenge cannot contain the P-256 key: Android fixes the challenge *before* the key exists.
> The key is bound instead by the leaf certificate, whose SubjectPublicKeyInfo **is** the attested
> key and must equal the submitted key. See [INTERFACE-NOTES.md](INTERFACE-NOTES.md).

### 3. `POST /attest`

```json
{ "authority": "<base58, must equal the session's address>",
  "p256_pubkey_compressed": "<66 hex: 02|03 || x>",
  "attestation_chain": ["<base64 DER leaf>", "...", "<base64 DER root>"],
  "nonce": "<hex from /attest/challenge>",
  "session_token": "<or send Authorization: Bearer>" }
```

The order of operations matters for safety and for the user, because the challenge is burned into
the key's certificate and a key can be attested at most once:

1. The session is valid and for exactly this `authority` (else 401/403).
2. Every input is well-formed: the key is a valid compressed P-256 point, 1..6 certificates of at
   most 16 KiB each, and the nonce has the right shape (else 400).
3. The revocation list and the finalized slot are available (else **503 before anything is spent**).
4. The chain is verified on a bounded blocking pool (at most `HD_MAX_CONCURRENT_ATTEST` at once).
   Any failure returns 422 and **does not consume the nonce**.
5. The nonce is consumed atomically and must have been issued to this authority
   (`nonce_wrong_subject`).
6. The voucher is signed, **appended to the transparency log and fsynced**, and then returned. If
   the log write fails, the voucher is withheld and the nonce restored.

#### Chain verification (mirrors Google's reference verifier, `android/keyattestation`)

| Rule | Code |
|---|---|
| Leaf first; the last certificate is self-issued and its **public key** equals a pinned Google root key: RSA-4096 `serialNumber=f92009e853b6b045` or ECDSA P-384 "Key Attestation CA1" (SPKI SHA-256 pinned in `src/attest/roots.rs`) | `unknown_root` |
| Exact shape by provisioning: factory (serialNumber-named intermediate) = 4, RKP (`CN=Droid CA2, O=Google LLC`) = 5, else 3. This blocks chain extension, where an attested key signs a forged leaf | `chain_shape` |
| Byte-exact issuer/subject chaining | `name_chaining` |
| Signatures via `ring`: RSA PKCS#1 v1.5 SHA-256/384/512, ECDSA P-256/P-384 with SHA-256/384, Ed25519. Outer and TBS algorithms must match. Nothing weaker (no SHA-1, no PSS) | `bad_signature`, `unsupported_algorithm` |
| Validity: never the leaf (device clock) or the anchor. Intermediates must not be "not yet valid". Expiry is enforced for RKP chains but not for factory chains, whose keys cannot be rotated (Google's rule) | `cert_not_yet_valid`, `cert_expired` |
| KeyDescription (1.3.6.1.4.1.11129.2.1.17) exactly once, in the leaf only | `key_description_missing`, `key_description_outside_leaf` |
| No serial (unpadded lowercase hex) on Google's status list as `REVOKED` or `SUSPENDED` | `revoked` |
| KeyDescription parsed as strict DER with `asn1-rs`: exact counts and tags, minimal integers, known enums, bounded lists, **duplicate tags rejected**. The only leniencies are the two real-device quirks Google also accepts (unordered tags, BER `TRUE`) | `key_description_malformed` |

#### Policy

| Rule | Code |
|---|---|
| Leaf key is EC P-256 and equals `p256_pubkey_compressed` | `leaf_not_p256`, `pubkey_mismatch` |
| `attestationChallenge` == the challenge derived from (authority, nonce) (constant-time) | `challenge_mismatch` |
| `attestationApplicationId`: exactly one package, `HD_APP_PACKAGE`; **every** signing digest in `HD_APP_RELEASE_CERT_SHA256` (or, in development only, `HD_APP_DEBUG_CERT_SHA256`) | `wrong_package`, `wrong_signer` |
| `attestationSecurityLevel` == `keyMintSecurityLevel`, not Software (Software: reject or level 0 per `HD_SOFTWARE_KEY_POLICY`) | `software_key`, `security_level_mismatch` |
| The level matches the chain's own claim: an RKP attestation key named `O=TEE` cannot vouch for StrongBox, and a factory chain vouches for StrongBox only if its names say so (CTS rule) | `security_level_mismatch` |
| Hardware-enforced `origin == GENERATED` (an imported key existed outside secure hardware) | `imported_key` |
| Hardware-enforced `algorithm == EC`, `ecCurve == P_256`, `keySize` 256 if present, purposes include SIGN and are a subset of {SIGN, VERIFY} | `wrong_key_parameters` |
| Hardware-enforced `rootOfTrust` present, `verifiedBootState == Verified`, `deviceLocked == true` (else reject, or level 0 per `HD_UNLOCKED_DEVICE_POLICY`) | `root_of_trust_missing`, `device_not_locked` |

The level is **2** for StrongBox, **1** for TEE, and **0** ("unattested") only when a downgrade
policy allows it.

#### The voucher: `HDreg` preimage (111 bytes, Ed25519-signed as-is, not hashed)

| Offset | Size | Field |
|---:|---:|---|
| 0 | 5 | ASCII `"HDreg"` = `48 44 72 65 67` |
| 5 | 32 | `program_id` (`HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p`) |
| 37 | 32 | `authority` (the wallet; the Rig PDA is `[b"rig", authority]`) |
| 69 | 33 | `p256_pubkey`, SEC1 compressed |
| 102 | 1 | `level` u8: 0 unattested, 1 TEE, 2 StrongBox |
| 103 | 8 | `expiry_slot` u64 LE = finalized slot at issuance + `HD_VOUCHER_TTL_SLOTS` (default 6,480,000: about 20 days at today's slot time of about 270 ms, 30 days at 400 ms) |

#### The Ed25519SigVerify instruction (223 bytes, no accounts)

Byte-identical to `solana_ed25519_program::new_ed25519_instruction_with_signature` (asserted in
tests):

```
[0..16]    01 00 | 30 00 ff ff | 10 00 ff ff | 70 00 6f 00 ff ff
           n=1, pad | sig_off=48, sig_ix=this | pk_off=16, pk_ix=this | msg_off=112, msg_len=111, msg_ix=this
[16..48]   registrar Ed25519 public key
[48..112]  signature
[112..223] HDreg preimage
```

Response:

```json
{ "level": 1, "expiry_slot": 400000000, "issued_slot": 393520000,
  "program_id": "HDn4...", "authority": "...", "p256_pubkey": "02...", "registrar": "<base58>",
  "message": "<hex HDreg>", "signature": "<hex>",
  "ed25519_instruction": { "program_id": "Ed25519SigVerify111111111111111111111111111",
                           "accounts": [], "data_base64": "...", "data_hex": "..." },
  "attestation": { "level": 1, "signer": "release", "downgrades": [], "provisioning": "remote_key_provisioning",
                   "anchor": "google-key-attestation-ca1-p384", "attestation_version": 400, ... },
  "log_index": 42, "entry_hash": "<hex>" }
```

### 4. What `register_rig` must check (program side)

With the Ed25519SigVerify instruction at index `i` of the same transaction (read through the
instructions sysvar, address-checked; the same rules `p256-introspect` applies to secp256r1):

1. The program id at `i` is `Ed25519SigVerify111111111111111111111111111`.
2. `data.len() == 223` and `data[0..16] == 01 00 30 00 ff ff 10 00 ff ff 70 00 6f 00 ff ff`. This
   pins one signature, every index to "this instruction", and every offset. Parsing is then trivial
   and cannot be pointed at another instruction.
3. `data[16..48] == config.registrar`.
4. `data[112..223] ==` the preimage rebuilt from the program's own values: `"HDreg" || crate::ID ||
   authority (the signer) || p256_pubkey (from the ix data) || level || expiry_slot`, taking only
   `level` and `expiry_slot` from the instruction data.
5. `level <= 2` and `expiry_slot > Clock::slot`. Set `attestation_level = level` and
   `attestation_expiry_slot = expiry_slot`. Level 0 is equivalent to "no voucher".

If the precompile's own check fails, the whole transaction fails, so the program never sees a bad
signature. Error on mismatch: `InvalidAttestation (21)`.

### 5. Transparency log

`GET /attest/log?from=0&limit=50` (max 100) pages the JSONL log. `GET /attest/voucher?authority=..&p256=..`
returns the latest voucher for a key, so a client that lost the `/attest` response can recover it
(the nonce is spent and the challenge is fixed in the key's certificate, so it could not re-attest
the same key).

Each line is the full transcript: the preimage, signature, nonce, base64 chain and summary. Lines
are hash-chained so history cannot be rewritten silently:

```
entry_hash = SHA-256("HDlog1" || prev_hash(32) || index u64 LE || message(111) || signature(64)
                     || nonce(16) || chain_hash)
chain_hash = SHA-256(for each cert, leaf first: len u32 LE || DER)       prev_hash[0] = 0^32
```

On startup the whole log is replayed and verified. A broken link, a deleted line, JSON fields that
disagree with the signed preimage, a bad signature or a torn last line all stop the service.
`hd-registrar audit-log <file>` re-checks, offline, everything a third party can: the hash chain,
every voucher signature, `challenge == SHA-256("HDattest" || authority || nonce)`, leaf key ==
vouched key, and each chain up to Google's roots as of its issuance time.

### Other endpoints

- `GET /healthz`: `{status, version, nonce_store, transparency_log_size, status_list{source, entries, age_secs}}`.
  It makes no network calls and is not rate limited.
- `GET /registrar`: the public parameters (registrar key, program, SIWS domain and chains, package,
  release digests, policies, anchors with SPKI hashes, challenge and preimage formats).

All errors are `{"error": "<stable code>", "message": "<fixed text>"}` and never echo input.

## Threat model

The registrar holds three secrets. For each, the worst case if it is compromised:

| Asset | Worst case if compromised | Why it is bounded |
|---|---|---|
| **Registrar Ed25519 key** | Vouches for software keys as TEE/StrongBox. A cheater can then script "hardware" heartbeats and win remote "honor-plus" Stack tables, up to `bond_cap x (seats - 1)` per table, and inflate leaderboard tiers. | **No custody and no mining authority.** A voucher only sets `attestation_level`. It cannot register a rig (`register_rig` needs the wallet's signature, and the voucher is bound to that wallet), move SOL or SKR, raise caps or dig. Guest and Seeker rigs gate only their owners' own money whatever their level. Stack bonds are capped and in-person tables are the primary mode. Vouchers expire. The key is rotated through the Config 72 h timelock. |
| **Session HMAC key** | Mints sessions for any address: reads coarse presence from the heartbeat intake and requests challenges "as" anyone. | A challenge only helps an attacker who also has a genuine hardware attestation chain. The resulting voucher names the victim's wallet and is useless without that wallet's signature on `register_rig`. Heartbeats are P-256-signed and cannot be forged with a session. |
| **Nonce store / transparency log** | Deleting nonces blocks sign-ins. Editing the log is detected. | The hash chain is verified at startup and by `audit-log`, and the log is public. |

Other adversaries:

- **Replay.** SIWS and attestation nonces are single-use (atomic remove-and-return in both
  backends; 16-way concurrent replay test). The challenge binds the wallet and the nonce. The
  voucher binds program, wallet, key, level and expiry.
- **Chain forgery.** Pinned root keys, exact chain shape, KeyDescription only in the leaf (the
  "attested key signs a forged leaf" attack is tested), strict DER, strict algorithms, and origin
  `GENERATED` (no imported keys).
- **Leaked factory keyboxes / rooted devices ("Tricky Store").** Google's revocation list is checked
  on every certificate and fails closed after `HD_STATUS_MAX_STALE_SECS`. RKP keys cannot leak this
  way and their chains expire in weeks. **Residual:** a not-yet-revoked leaked keybox passes, as
  THREAT_MODEL.md K4 states. The damage is the capped Stack exposure above.
- **Debug builds.** Anyone can sign with a debug keystore, so debug digests are off unless
  `HD_APP_DEBUG_CERT_SHA256` is set. The service then warns at startup and labels such vouchers
  `"signer": "debug"` in the log; the voucher itself, and so the chain, does not say which
  certificate signed the app. It is for the operator's own phone while no release key exists,
  with `HD_APP_RELEASE_CERT_SHA256` left unset, and it must be removed before anyone else installs
  the app.
- **Denial of service.** Per-IP GCRA rate limits: a general bucket and a tight attest bucket
  (default 6/min, burst 3). By default the limits key on the TCP peer and no header is read.
  Behind a proxy one setting names the header to trust: `HD_TRUST_REAL_IP=true` for `X-Real-IP`
  (its last line), or `HD_TRUSTED_PROXY_HOPS=n` for the n-th address from the right of
  `X-Forwarded-For`; the registrar refuses to start with both. Either is only as good as the
  proxy: with nothing in front that sets the header, a caller picks its own bucket by sending it.
  There is a 64 KiB body cap, a 20 s timeout, bounded verification concurrency, an
  outstanding-nonce cap, and size limits inside every parser.
- **Dependency outage.** A revocation-list or RPC outage returns 503 **before** the nonce is spent.
  There is no fallback slot guess.

**Logging and PII.** Logs are JSON with route template, request id, status, latency and error
code. They contain no tokens, signed messages, signatures, wallet addresses, certificate chains or
IP addresses (IPs exist only in rate-limiter memory). The `Authorization` header is marked
sensitive. Heads Down never requests device-ID attestation, and the verifier records only whether
such tags were present. **Published** transcripts contain the certificate chain, whose attestation
key serial can link two rigs registered from the same phone while that key lives
(docs/PRIVACY.md).

**Secrets handling.** The registrar key is loaded from a file (refused if group/other-writable;
warning if readable) or from an env var. It is zeroized on drop, has no printable form except its
public key, and is created by `keygen` with mode 0600 and no overwrite. The session key must be
>= 32 bytes and is zeroized and redacted. `HD_RPC_URL` may carry a provider's API key: a `{:?}`
of the configuration shows only its scheme, host and port, and the service does not log it (a
run at `RUST_LOG=trace` with a key in the path and in the query string, once with the RPC
answering and once with it down, logged neither). No secret is committed, and `.gitignore` and
`.dockerignore` exclude keypairs, `.env` and runtime state.

## Code map

| Path | What |
|---|---|
| `src/siws/` | CAIP-122 parser/serializer (canonical round trip) and stateless verification |
| `src/nonce/` | `NonceStore` trait; `MemoryNonceStore`, `SqliteNonceStore` (`DELETE ... RETURNING`) |
| `src/session.rs` | HMAC session tokens |
| `src/attest/keydesc.rs` | KeyDescription DER parser |
| `src/attest/chain.rs` | chain verification |
| `src/attest/policy.rs` | Heads Down policy and levels |
| `src/attest/roots.rs` | compiled-in, SPKI-pinned Google root keys (`roots/`, see `roots/SOURCES.md`) |
| `src/attest/revocation.rs` | status list parsing, caching, fail-closed |
| `src/voucher.rs` | HDreg preimage, Ed25519 signing, precompile instruction bytes, registrar key loading |
| `src/translog.rs` | hash-chained JSONL log, replay, audit |
| `src/http/` | axum routes, errors, rate limiting |
| `tests/attest_vectors.rs` | **real** Google chains (RKP under the RSA and the 2026 P-384 root, factory OEM, StrongBox), and negatives on them |
| `tests/siws_http.rs`, `tests/attest_http.rs` | the HTTP API end to end with a synthetic RKP-shaped CA |

## Tests

`cargo test` runs 114 tests offline in about a second: 63 unit, 19 real-vector, 14 SIWS HTTP and
18 attestation HTTP. Two of the unit tests read `.env.example` and
`deploy/railway/registrar/.env.example`: both files must name exactly the variables the code
reads and show its defaults. Lint: `cargo +1.97.1 clippy --all-targets`. The
library and binary deny `unwrap`, `expect`, `panic`, indexing and unchecked arithmetic outside
tests. Format: `cargo +1.95 fmt --check`.

Real test chains come from Google's `android/keyattestation` (Apache-2.0) at a pinned commit; see
`testdata/chains/SOURCES.md`. Because they do not carry our package or challenge, those tests
configure the verifier with each vector's own values (from Google's decoded JSON). The Heads Down
specific flow runs on synthetic chains. RKP vectors are verified at an instant inside their short
validity windows.

## Operations

- **Keys.** `hd-registrar keygen <path>` / `hd-registrar pubkey <path>`. Put the public key into
  `Config.registrar` at `initialize_config`. Rotation goes through `propose_config` / `apply_config`
  (72 h timelock): run old and new registrars side by side until `apply_config`.
- **Deploy behind a proxy:** set the one setting that names the header the proxy writes
  (`HD_TRUST_REAL_IP=true` on Railway, whose documentation says its edge sets `X-Real-IP`;
  `HD_TRUSTED_PROXY_HOPS=n` behind n proxies that append to `X-Forwarded-For`) and check it with
  the two commands in `deploy/railway/registrar/.env.example`. Mount `/data` on a persistent
  volume, and back up `attestations.jsonl` (append-only; mirror it publicly).
- **Devnet builds** sign in with `solana:devnet`: set `HD_SIWS_CHAINS=solana:devnet` on the devnet
  registrar. Keep mainnet and devnet registrars separate (different keys and program ids).
