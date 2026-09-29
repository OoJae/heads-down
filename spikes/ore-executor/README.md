# Spike 1(a): program-derived executor for ORE Automation, run against real mainnet ORE

**Result: PASS (5/5).** A Heads Down program PDA can be the executor of a user's own ORE
Automation, signing ORE `deploy` through `invoke_signed`. Nobody else can deploy for that
automation, and the production-cost gate is enforced by our program, because ORE itself does not.

## What was tested
LiteSVM 0.17 loaded the **live mainnet ORE program binary** (`oreV3EG1…`, dumped 2026-09-29) plus the
entropy program and the real Board, Config, Treasury, Round and Var accounts, i.e. a local fork.

| Test | Proves |
|---|---|
| `pda_executor_deploys_for_user_automation` | The user runs `automate` (Discretionary strategy, fixed fee 5,000 lamports, executor = our PDA). Our `dig` CPIs `deploy`: 5 tiles are credited on the live round, the automation is debited exactly `5 × amount + fee`, the PDA earns the fee, and the PDA stays a data-less, System-owned account. **26k CU per rig (5 tiles).** |
| `program_enforces_production_cost_gate` | `dig` refuses (`CostGate`) when `Board.production_cost_ema` is above the rig's cap and succeeds at or below it. |
| `attacker_cannot_deploy_for_user_directly` | A random signer calling ORE `deploy` for the user's automation fails (`executor == signer` check). |
| `wrong_executor_or_fake_board_rejected` | A different PDA of our program is rejected (`InvalidExecutor`). A spoofed, non-ORE-owned Board carrying a fake low EMA is rejected (`InvalidOreAccount`). |
| `executor_pda_pays_checkpoint_fee_through_nested_cpi` | When `miner.checkpoint_fee == 0`, ORE pulls `CHECKPOINT_FEE` (10,000 lamports) from the signer via a System transfer **inside ORE's CPI**, and the PDA's signer privilege propagates correctly. |

## Facts established from ORE source and this fork
- `deploy.rs`: `automation.executor == signer || executor == EXECUTOR_ADDRESS`. A PDA signing via `invoke_signed` satisfies this.
- `automate.rs` forbids Discretionary strategies with the permissionless `EXECUTOR_ADDRESS`, so a custom executor is required and liveness is ours.
- **`AutomationConditions.max_production_cost` is stored but NOT enforced by ORE `deploy`** (only min/max Motherlode). Heads Down enforces the gate itself against `Board.production_cost_ema` (lamports per whole ORE; a 20-round EMA updated in `reset.rs`).
- `checkpoint` is permissionless, so the crank can checkpoint before digging in a new round.
- The executor PDA must be funded (it may have to pay `CHECKPOINT_FEE`) and must stay data-less and System-owned.
- The Discretionary fixed fee is charged once per round, on the first deploy.
- If the automation balance can't cover the requested tiles on a first deploy, ORE closes the automation back to the user. That is safe, but `dig` should size requests to the balance.

## Run it
```bash
./fetch-fixtures.sh                                            # mainnet dumps → fixtures/ (gitignored)
cargo build-sbf --manifest-path program/Cargo.toml             # Agave 4.1 platform-tools
cargo test -p hd-spike-tests -- --nocapture --test-threads=1   # uses rust-toolchain.toml (1.97.1)
```

## Next (folded into `programs/heads-down`)
Batch several rigs per `dig`, gate each on a secp256r1-verified P-256 heartbeat (spike 1(b)), read the
Round's per-tile totals for least-crowded tile selection, and measure CU and tx-size limits (v0 vs v1).
