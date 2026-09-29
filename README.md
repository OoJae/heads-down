# Heads Down — your phone's night shift, powered by ORE

> Put your phone face-down and it mines [ORE](https://ore.com). Pick it up and the rig goes cold.
> Nothing but your phone's own hardware key can switch it on — not a server, not us.

**Status:** in active development for [Clock In](https://solanamobile.radiant.nexus/) (Solana Mobile Hackathon #3).

### Proven so far
- **Trustless executor on a mainnet fork:** a heads_down PDA deploys through the live ORE program via CPI, and
  nobody else can deploy for the user's Automation ([spikes/ore-executor](spikes/ore-executor/README.md), 5/5).
- **Phone-key heartbeats on-chain:** Android-Keystore-style P-256 signatures verified by Solana's secp256r1
  precompile, with every spoofing path tested (sysvar spoof, foreign offsets, high-S, replay)
  ([spikes/secp256r1](spikes/secp256r1/README.md), 16/16 LiteSVM, 9/9 on a real validator).
- **Unforgeable Seeker check:** in-program Seeker Genesis Token verification anchored on Token-2022 group
  membership, tested against real mainnet SGTs and a forgery that fooled prior art ([crates/sgt-verify](crates/sgt-verify/README.md)).
- **Honest economics:** a backtest over 58,801 real ORE rounds decides *when* to dig
  ([ml/forecaster/RESULTS.md](ml/forecaster/RESULTS.md)).
- **Android app:** native Kotlin/Compose, accelerometer-only face-down detection (works on phones without a
  gyroscope), Keystore rig key, Quick Settings tile, shift service, HyperOS keep-alive onboarding, 138 unit tests
  ([android/README.md](android/README.md)).

## How it works (one paragraph)
You clock in with one wallet confirmation that funds a capped shift inside **your own ORE Automation
account**. While the phone lies face-down, it signs a heartbeat every ORE round (~78 s) with a
non-exportable Android Keystore P-256 key. A permissionless crank may deploy for your rig **only** when
that heartbeat verifies on-chain via Solana's secp256r1 precompile. Pick the phone up, and the next dig
fails with `StaleHeartbeat`. Funds never leave ORE's own deploy-or-return custody.

## Repo layout
| Path | What |
|---|---|
| `programs/heads-down` | On-chain program (Pinocchio) — rigs, heartbeat-gated `dig`, rooms, Stack, gifts, bury |
| `crates/sgt-verify` | Open-source in-program Seeker Genesis Token verifier + spoof suite |
| `crates/p256-introspect` | secp256r1 precompile introspection + Android Keystore DER/low-S helpers |
| `crank` | Permissionless dig crank (Helius LaserStream, batched tx) |
| `registrar` | Android Key Attestation verifier + SIWS nonce service |
| `services/*` | Push/presence and indexer |
| `dashboard` | Public, verifiable traction dashboard |
| `android` | Native Kotlin + Compose app |
| `ml` | Foreman on-device models: notebooks + model cards |
| `spikes` | De-risking experiments (see `docs/SPEC.md` → derisking) |
| `docs` | Spec, research, threat model |

## Docs
- [`docs/SPEC.md`](docs/SPEC.md) — full product + technical spec
- [`docs/research/`](docs/research/) — the research and selection process behind this project
- [`buildplan.md`](buildplan.md) — approved build plan

## License
MIT (TBD)
