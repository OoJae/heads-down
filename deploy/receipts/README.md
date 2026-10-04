# Deploy receipts

Public, committed records of every mainnet change to `heads_down`
(`HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p`). They hold no secrets: no RPC URL, no key
material, only public keys, hashes, slots and signatures that anyone can check against the chain.

| File | Written by | What |
|---|---|---|
| `mainnet/<UTC>-fresh-<commit>.json` | `scripts/mainnet/deploy.sh` | first deploy |
| `mainnet/<UTC>-upgrade-<commit>.json` | `deploy.sh --mode upgrade` | in-place upgrade by the deployer |
| `mainnet/<UTC>-buffer-<commit>.json` | `deploy.sh --mode buffer` | a buffer handed to the Squads vault for an upgrade proposal |
| `mainnet/<UTC>-init.json` | `scripts/mainnet/init-config.sh` | `initialize_config` and Executor float top-ups |

Local dry runs write to `localnet/`, which is git-ignored.

## Deploy receipt (`heads-down/deploy-receipt/v1`)

| Field | Meaning |
|---|---|
| `kind` | `fresh`, `upgrade` or `buffer` |
| `cluster`, `genesis_hash` | where it was checked (`5eykt4Us…` is mainnet) |
| `program_id`, `programdata`, `upgrade_authority`, `fee_payer` | the program and who controls it |
| `max_len`, `programdata_len`, `programdata_rent_lamports` | space reserved for upgrades and its rent |
| `last_deploy_slot`, `signature`, `tx_slot`, `tx_fee_lamports` | the final deploy transaction |
| `so.sha256` | sha256 of the `.so` file `programs/heads-down/scripts/build.sh` produced |
| `so.program_hash`, `onchain.program_hash` | sha256 with trailing zero bytes stripped: what `solana-verify get-program-hash` prints; the two must be equal |
| `onchain.matches_local_so` | the ProgramData bytes equal the local build byte for byte, and the rest of max-len is zero |
| `build.git_commit`, `build.git_dirty` | the commit the build came from (mainnet refuses a dirty tree) |
| `build.solana_cli`, `build.cargo_build_sbf` | the toolchain, for reproducing the build |
| `build.buffer_written_by` | `hd-devstack-write-buffer` (the default) or `solana-cli` (`deploy.sh --cli-only`) |
| `build.programdata_extended_bytes` | only for an upgrade that outgrew the ProgramData: the bytes `deploy.sh` added with `solana program extend` before the upgrade |
| `buffer_write` | what the paced writer did in this run: `created` (false when it continued a buffer), `buffer_lamports`, `chunks_total`, `chunks_already_written` (chunks that needed no write: already in the buffer, or all zero in a new one), `transactions_sent` (sends an RPC took and sends that got no answer), `transactions_landed`, `signed_again`, `slow_downs`, `compute_unit_limit`, `fees_lamports`, `verified` (the buffer was read back and equals the build) |
| `cli_phase` | the transactions the fee payer paid for after the writer finished: `transactions` (1 for a deploy or an upgrade, 2 when the ProgramData was extended first), `failed`, `fees_lamports`. Missing if the count could not be read |
| `fee_payer_spent_lamports` | what this run of `deploy.sh` took out of the deployer, rent included. When the run continued a buffer (`buffer_write.created` is false), the buffer's rent left the deployer in the earlier run and is not in this figure |

## Check one yourself

```bash
solana program show HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p -um   # authority, last deploy slot, data length
solana-verify get-program-hash HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p -um   # = onchain.program_hash
git checkout <build.git_commit> && bash programs/heads-down/scripts/build.sh
sha256sum programs/heads-down/target/deploy/heads_down.so                       # = so.sha256 (same toolchain)
```

The init receipt (`heads-down/init-receipt/v1`) records the Config parameters
(`governance`, `registrar`, `executor_fee`, `crank_fee`, `bury_bps`, `ore_layout_hash`), the
`initialize_config` and float-transfer signatures, and the Executor PDA balance afterwards.
