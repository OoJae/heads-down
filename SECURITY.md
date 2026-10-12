# Security

## Reporting a problem

If you find something that can move funds, forge a heartbeat or take a rig's key off its phone,
please report it privately before publishing: use "Report a vulnerability" on this repository's
Security tab where it is offered, or contact the maintainer ([@OoJae](https://github.com/OoJae))
on GitHub first. Anything else can go in a public issue.

## What a scanner finds here, and what it is

- **`api-key=SECRET`, `SECRET-KEY-123` and similar strings in test code** (`crank/src`,
  `registrar/src`, `services/indexer/test`, `android/core/chain/src/test`,
  `crates/sgt-verify/scripts`). They are placeholders. The tests they sit in check that an RPC URL
  is redacted before it is logged. No real key is in the repository.
- **`{HELIUS_API_KEY}` in `deploy/railway/crank/crank.toml`.** A placeholder the crank fills from
  its environment at start. The crank refuses to start on a literal `api-key=` value in that file.
- **`.pem` files under `registrar/roots/` and `registrar/testdata/`.** Public certificates:
  Google's hardware attestation roots, and attestation chains used as test fixtures. None is a
  private key.
- **Keypairs.** None is committed. The deployed services read theirs from the environment, and the
  local development stack generates its own under the user's home directory.

## Where the security work is written down

- [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md): what the design defends against and what it does not.
- [docs/SECURITY_REVIEW.md](docs/SECURITY_REVIEW.md): our own reviews, each finding and its fix.
- [docs/PRIVACY.md](docs/PRIVACY.md): what leaves the phone and what never does.

## Open, stated once

- There has been no third-party audit.
- One key, the founder's, can upgrade the on-chain program. There is no multisig and no delay.
- The published APK is signed with a debug key and is not on the Solana dApp Store.
