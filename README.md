# Heads Down — your phone's night shift, powered by ORE

> Put your phone face-down and it mines [ORE](https://ore.com). Pick it up and the rig goes cold.
> Nothing but your phone's own hardware key can switch it on — not a server, not us.

**Status:** early build for [Clock In](https://solanamobile.radiant.nexus/) (Solana Mobile Hackathon #3).

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
