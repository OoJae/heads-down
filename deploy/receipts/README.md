# Deploy receipts

Public, committed records of every deploy, upgrade, upgrade buffer and `initialize_config` or
Executor float top-up that `scripts/mainnet/deploy.sh` and `init-config.sh` send for
`heads_down` (`HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p`) on mainnet. Governance
transactions (`governance.sh pause`, `unpause`, `propose`, `apply`) and anything sent through
`solana.sh` (a transfer, `set-upgrade-authority`, `program extend`, `program close`) write no
receipt: those are on chain only. Receipts hold no secrets: no RPC URL, no key material, only
public keys, hashes, slots and signatures that anyone can check against the chain.

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
| `buffer.address`, `buffer.authority`, `buffer.lamports`, `buffer.len` | only in a `buffer` receipt: the buffer, who may use it now (the Squads vault after a hand-over) and the rent in it. A `buffer` receipt has no `programdata`, `upgrade_authority`, `max_len`, `programdata_len`, `programdata_rent_lamports`, `last_deploy_slot`, `signature`, `tx_slot` or `tx_fee_lamports` |
| `max_len`, `programdata_len`, `programdata_rent_lamports` | space reserved for upgrades and its rent |
| `last_deploy_slot`, `signature`, `tx_slot`, `tx_fee_lamports` | the final deploy transaction |
| `so.sha256` | sha256 of the `.so` file `programs/heads-down/scripts/build.sh` produced |
| `so.program_hash`, `onchain.program_hash` | sha256 with trailing zero bytes stripped: what `solana-verify get-program-hash` prints; the two must be equal |
| `onchain.matches_local_so` | the ProgramData bytes equal the local build byte for byte, and the rest of max-len is zero |
| `build.git_commit`, `build.git_dirty` | the commit the build came from (mainnet refuses a dirty tree) |
| `build.solana_cli`, `build.cargo_build_sbf` | the toolchain, for reproducing the build |
| `build.buffer_written_by` | `hd-devstack-write-buffer` (the default) or `solana-cli` (`deploy.sh --cli-only`) |
| `build.programdata_extended_bytes` | only for an upgrade that outgrew the ProgramData: the bytes `deploy.sh` added with `solana program extend` before the upgrade |
| `buffer_write` | what the paced writer did in this run: `created` (false when it continued a buffer), `buffer_lamports`, `chunks_total`, `chunks_already_written` (chunks that needed no write: already in the buffer, or all zero in a new one), `transactions_sent` (sends an RPC took and sends that got no answer), `transactions_landed`, `signed_again`, `slow_downs`, `compute_unit_limit`, `fees_lamports` (the fee payer's balance change less what it put into a buffer it created: a difference of balances, not a sum of fees, so a lamport that reaches the fee payer during the run lowers it; in the first deploy's receipt it is one lamport under the sum computed for its transactions, [docs/MAINNET.md](../../docs/MAINNET.md#what-is-deployed)), `verified` (the buffer was read back and equals the build). Missing with `deploy.sh --cli-only` |
| `cli_phase` | the transactions the fee payer paid for after the writer finished: `transactions` (1 for a deploy or an upgrade, 2 when the ProgramData was extended first, 1 for a buffer's hand-over; with `--cli-only` every transaction the CLI sent, about 200), `failed`, `fees_lamports`. Missing if the count could not be read |
| `fee_payer_spent_lamports` | what this run of `deploy.sh` took out of the deployer, rent included. When the run continued a buffer (`buffer_write.created` is false), the buffer's rent left the deployer in the earlier run and is not in this figure |

## Check one yourself

```bash
solana program show HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p -um   # authority, last deploy slot, data length
solana-verify get-program-hash HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p -um   # = onchain.program_hash
solana program dump HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p /tmp/heads_down.onchain.so -um
head -c <so.len> /tmp/heads_down.onchain.so | shasum -a 256                       # = so.sha256
```

Those compare the chain with the receipt. They do not compare either with the source: the build
is not reproducible across directories. `git checkout <build.git_commit>` and
`bash programs/heads-down/scripts/build.sh` gave `so.sha256` only in the checkout the deployed
file was built in. On 10 October 2026 the same sources and toolchain gave three other hashes in
three other directories of the same machine, each for a file of the same 190,048 bytes
([docs/MAINNET.md](../../docs/MAINNET.md#check-it-yourself)). No build in a pinned container
has been set up.

Two receipts are committed, both of 10 October 2026: the first deploy
(`mainnet/20261010T184116Z-fresh-d67a1a40c2a0.json`) and `initialize_config` with the Executor
float (`mainnet/20261010T184715Z-init.json`).

The init receipt (`heads-down/init-receipt/v1`) records the Config parameters
(`governance`, `registrar`, `executor_fee`, `crank_fee`, `bury_bps`, `ore_layout_hash`), the
`initialize_config` and float-transfer signatures, and the Executor PDA balance afterwards.
