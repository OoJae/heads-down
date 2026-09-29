# Heads Down x ORE

How Heads Down uses ORE: the accounts, the instructions, the CPI flow, the fees, the failure modes, the version pin, the circuit breaker, and the milestone proposal for the ORE matched prize.

**Two audiences.** If you are judging, read the summary and sections 1, 4 and 9. If you are auditing, every ORE claim below cites a file and line in ORE's source at a pinned commit, and section 1 shows how to check that the pinned source is what runs on mainnet.

---

## Summary

- **ORE is the product.** Each rig is an ordinary ORE miner with its **own** ORE `Automation` account. The user's SOL sits in that ORE-owned account and never passes through a Heads Down vault.
- **Heads Down is the automation's executor.** The user sets the executor to the Heads Down **Executor PDA**, with the `Discretionary` strategy and a **fixed** fee per round. Only the Heads Down program can sign for that PDA, and it signs ORE `deploy` only when the rig's phone has produced a fresh Keystore P-256 heartbeat, verified on-chain by the secp256r1 precompile.
- **ORE stores `max_production_cost` but does not enforce it.** `deploy` checks only the Motherlode conditions. Heads Down enforces the production-cost gate itself, against `Board.production_cost_ema`.
- **Pinned and verified.** Everything here refers to ORE commit `b92c5043`, which verify.osec.io reports as the exact source of the deployed program.
- **Auditable by ORE from ORE's own logs.** Every Heads Down deploy emits ORE's own `DeployEvent` with `signer = Executor PDA`, so ORE can measure Heads Down usage without trusting our dashboard.

---

## 1. Source pin and how to reproduce it

| Item | Value |
|---|---|
| Repository | https://github.com/regolith-labs/ore |
| Pinned commit | `b92c5043581a4ad513401f7d5aabd1eb21148c12` (the `master` HEAD when this was written) |
| Crate version | `ore-api` / `ore` workspace `3.8.25` (`Cargo.toml:6`) |
| Program ID | `oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv` (`api/src/lib.rs:19`) |
| ProgramData | `GXa6JV9AwsccP3hxvKFcZGp4w3MMtf7PJ6HYuTSyokfJ` |
| Last deploy slot | `450,496,378` (block time 2026-09-25 23:10:01 UTC) |
| Verified build | verify.osec.io: `is_verified: true`, commit `b92c5043…`, on-chain hash `d9601d4e8b0e7f6db2fbf0984dced7eba23029e1e29e8d6c9c7809cb5ea238a3`, last verified 2026-09-25 23:12 UTC |
| Upgrade authority | `J5K5tWj3nKfxuSkAJ25WTMf4u5EsxJRfUoRKKxgrfFGV` (ORE's `docs/DEPLOY.md` says upgrades go through a Squads multisig; we have not independently derived that this address is its vault) |
| Release profile | `overflow-checks = true` (`Cargo.toml:48`), so ORE's own arithmetic panics rather than wraps |

In this document a reference like `deploy.rs:76` means `program/src/deploy.rs` line 76, and `state/board.rs:20` means `api/src/state/board.rs` line 20, both at the pinned commit:
`https://github.com/regolith-labs/ore/blob/b92c5043581a4ad513401f7d5aabd1eb21148c12/<path>#L<line>`.

**Reproduce** (all read-only; the Solana CLI is at `$HOME/.local/share/solana/install/active_release/bin`):

```bash
# 1. Is the deployed binary the pinned commit?
curl -s https://verify.osec.io/status/oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv
# 2. When was ORE last upgraded? (ProgramData header: u32 tag=3, u64 slot, Option<Pubkey>)
solana program show oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv -um
# 3. Dump the live binary and the accounts deploy touches (used by the fork tests)
spikes/ore-executor/fetch-fixtures.sh
```

**Live values read on 2026-09-29, around slot 451,705,990** (mainnet RPC; they are used in the examples below):

| Account | Size (bytes) | Discriminator | Values |
|---|---|---|---|
| Board | 40 | 105 | `round_id` 422,601; `production_cost_ema` 918,782,720 lamports per ORE (0.919 SOL) |
| Treasury | 48 | 104 | `motherlode` 344.0 ORE |
| Config | 232 | 101 | `round_slots` 240, `intermission_slots` 48 |
| Round (ids 422,590 to 422,601) | 952 | 109 | total deployed 10.79 to 11.93 SOL per round (median 11.04); `total_vaulted` median 1.049 SOL; 165 to 172 miners per round |
| Automation (sampled) | 160 | 100 | one live third-party executor uses `strategy = 2` (Discretionary) with `fee = 12,000` lamports; ORE's own permissionless automations use `fee = 7,000` |
| Miner (sampled) | 752 | 103 | |

Every sampled Automation had `conditions.max_production_cost = u64::MAX` (the default). No one we sampled relies on that field, which is consistent with ORE never reading it.

---

## 2. The ORE mechanics that matter to Heads Down

- **Board and rounds.** A 5x5 board. A round starts at its first deploy (`deploy.rs:47-50`) and accepts deploys while `start_slot <= slot < end_slot` (`deploy.rs:33`), where `end_slot = start_slot + round_slots` (240 slots). `reset` may run once `slot >= end_slot + intermission_slots` (48 slots; `reset.rs:28`). The research notes measured about 78 s per round (`docs/research/skr-and-ore.md`).
- **Winning square.** `rng % 25` (`state/round.rs:75-77`), with randomness from ORE's Entropy var (`reset.rs:78-96`).
- **SOL fees.** On every square with SOL, ORE takes an admin fee of `max(deployed/100, 1)`. On every losing square it also takes a protocol fee of `max((deployed - admin)/10, 1)` (`state/round.rs:86-99`). A winning square therefore returns 99% of its SOL and a losing square 89.1% (`checkpoint.rs:94-97` and `149-153`). There is no parimutuel transfer between miners: your losses go to ORE, not to other miners.
- **ORE reward.** Each round mints up to 1 ORE for the winning square (`reset.rs:147-153`). On 15 "split" squares it is shared pro rata; on 10 "solo" squares a single miner wins it, with odds weighted by SOL (`checkpoint.rs:100-130`). Which squares are solo is a deterministic function of the round id (`state/round.rs:125-164`), so it is known before the round starts.
- **Motherlode.** Each round adds up to 0.2 ORE to `Treasury.motherlode` (`reset.rs:149` and `167`). A round hits it when `rng.reverse_bits() % 500 == 0` (`state/round.rs:107-109`), and the pool is then shared pro rata on the winning square (`checkpoint.rs:133-146`).
- **Production-cost EMA.** After each round, `reset` computes `production_cost = total_vaulted * ONE_ORE / total_mint_amount` and folds it into `Board.production_cost_ema` with a 20-round window: `ema = (cost + 19 * ema) / 20` (`reset.rs:239-251`). The unit is **lamports per whole ORE**. It counts only the protocol fee (`total_vaulted`), not the 1% admin fee, and it divides by the 1.2 ORE minted per round (the base reward plus the Motherlode top-up). [ECONOMICS.md](ECONOMICS.md) converts it into an all-in price.
- **Refining.** Claiming unrefined ORE costs a 10% refining fee, which is shared among everyone still holding unrefined ORE (`state/miner.rs:89-99` and `111-125`). Partial claims use `bps` (`claim_ore.rs:10-13`).

---

## 3. The ORE accounts Heads Down touches

ORE accounts are Steel accounts: an 8-byte header whose first byte is the type discriminator, followed by a `repr(C)` struct with no padding (steel 4.0.9 `account/deserialize.rs`; ORE's `Numeric` is `[u8; 16]`, so it has alignment 1). Heads Down reads them by fixed offsets, and every read is preceded by address or seed, owner, exact length and discriminator checks.

| Account | Address or seeds (ORE program) | Size / disc | Heads Down reads | Written (by ORE, inside the CPI) | Heads Down checks before use |
|---|---|---|---|---|---|
| Board | `BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi` (`consts.rs:110`) | 40 / 105 | `round_id` @8, `start_slot` @16, `end_slot` @24, `production_cost_ema` @32 | yes | address, owner = ORE, len = 40, disc = 105 |
| Round | `["round", round_id u64 LE]` | 952 / 109 | `id` @8, `deployed[25]` @16..216 (least-crowded tiles) | yes | re-derived from `Board.round_id`, owner, len, disc, `id == Board.round_id` |
| Treasury | `45db2FSR4mcXdSVVZbKbwojU6uYDpMyhpEi7cC8nHaWG` (`consts.rs:113`) | 48 / 104 | `motherlode` @8 | yes | address, owner, len, disc |
| Config | `9c9X7aDRAF41faiDs94ELjT19UrGnn72wBW9hPsS4Awy` (`consts.rs:116`) | 232 / 101 | `round_slots` @160 (display only) | no | address, owner, len, disc |
| Automation | `["automation", authority]` | 160 / 100 | `amount` @8, `authority` @16, `balance` @48, `executor` @56, `fee` @88, `strategy` @96 | yes | re-derived from `rig.authority`; owner, len, disc; `executor == Executor PDA`; `authority == rig.authority`; `strategy == 2`; `fee == Config.crank_fee` |
| Miner | `["miner", authority]` | 752 / 103 | `authority` @8, `checkpoint_id` @48, `checkpoint_fee` @56, `deployed[25]` @64..264, `round_id` @664 | yes | re-derived; owner, len, disc; `authority == rig.authority` |
| Entropy Var | `BWCaDY96Xe4WkFq1M7UiCCRcChsJ3p51L5KrGzhxgm2E` (`consts.rs:104`), owned by `3jSkUuYBoJzQPMEzTvkDFXCZUBksPamrVhrnHR9igu2X` | n/a | no | only on the first deploy of a round | address |
| ORE ProgramData | `GXa6JV9AwsccP3hxvKFcZGp4w3MMtf7PJ6HYuTSyokfJ` | header 45 | `slot` @4 (circuit breaker, section 7) | no | address, owner = BPFLoaderUpgradeable |
| ORE mint, Treasury ORE ATA, stake accounts | `oreoU2P8bN6jkk3jbaiVxYnG1dCXcYxwhwyK9jSybcp` (`consts.rs:71`) | n/a | no | inside `bury` only | address; ORE and the stake program validate the rest |

Full offsets for the two structs Heads Down reads most:

- `Automation` (`state/automation.rs:9-43`): `amount` 8, `authority` 16, `balance` 48, `executor` 56, `fee` 88, `strategy` 96, `mask` 104, `reload` 112, `total_sol_spent` 120, `total_ore_earned` 128, `conditions` 136 (`max_production_cost` 136, `min_motherlode` 144 as u16, `max_motherlode` 146 as u16, `split_tiles` 148, `solo_tiles` 150, `_buffer` 152), ending at 160.
- `Miner` (`state/miner.rs:8-66`): `authority` 8, `auto_return` 40, `checkpoint_id` 48, `checkpoint_fee` 56, `deployed` 64..264, `mass` 264..464, `cumulative` 464..664, `round_id` 664, `rewards_factor` 672..688, `rewards_sol` 688, `refined_ore` 696, `rewards_ore` 704, `last_claim_ore_at` 712, `last_claim_sol_at` 720, `lifetime_rewards_ore` 728, `lifetime_deployed` 736, `lifetime_rewards_sol` 744, ending at 752.

The live sizes match these layouts exactly (table in section 1).

---

## 4. Instructions

ORE's instruction tags are in `api/src/instruction.rs:5-23`. Instruction data is one tag byte followed by an exact-size struct.

| ORE instruction (tag) | Who signs | Used by Heads Down for | Accounts (order) |
|---|---|---|---|
| `automate` (0, `AutomateV2` layout) | the user's wallet | refuel: deposit, `executor = Executor PDA`, `strategy = 2`, `fee = Config.crank_fee`, per-square `amount`, `reload`, conditions | `signer, automation, executor, miner, system` (`automate.rs:41`) |
| `deploy` (6) | Executor PDA via `invoke_signed` | every dig | `signer, authority(w), automation(w), board, config, miner(w), round, treasury, system, ore_program`, then `var(w), entropy_program` (`deploy.rs:17`, `sdk.rs:110-157`) |
| `checkpoint` (2) | any signer (the crank's own key) | settling the previous round before the next deploy | `signer, authority(w), automation, board, miner(w), round(w), treasury, system` (`checkpoint.rs:10`, `sdk.rs:334-354`) |
| `claim_ore` (4) | the user's wallet only | clock-out claim, including partial claims | `claim_ore.rs:17`; the Miner must be `["miner", signer]` (`claim_ore.rs:24-27`) |
| `claim_sol` (3) | the user's wallet only | only if `reload = 0` and `auto_return = 0` | `claim_sol.rs:10-18` |
| `automate` with `executor = Pubkey::default()` | the user's wallet | **Revoke**: closes the Automation and returns every lamport to the user | `automate.rs:88-97` |
| `bury` (24) | any signer holding ORE | the Bury auction burns its ORE proceeds | `bury.rs:13-21`; the sender must be the signer's ORE ATA |

**`automate` (the refuel, user-signed).** One wallet approval does several things. It creates or updates the Automation (`automate.rs:100-134`) and the Miner (`automate.rs:57-85`). It pays `CHECKPOINT_FEE` into the Miner if the Miner has none (`automate.rs:137-140`). It moves the deposit into the Automation (`automate.rs:143`). ORE refuses `Discretionary` together with the permissionless `EXECUTOR_ADDRESS` (`automate.rs:48-54`), which is why Heads Down needs its own executor. ORE bounds the fee only for `DiscretionaryBps` (at most 100 bps, `automate.rs:122-124`); a `Discretionary` fee can be any u64. That is why `dig` checks `automation.fee == Config.crank_fee`: it protects users from a mistyped fee and protects the Executor pool from rigs that set the fee to 0 but still get their crank reimbursed.

The solo/split conditions are accepted only with the `Random` strategy (`automate.rs:32-38`), so a Discretionary rig cannot use them. Heads Down computes tiles itself (section 5).

**What the executor can and cannot do.** Under `Discretionary` the executor supplies the per-square amount, capped by ORE at `automation.amount`, and the square mask (`deploy.rs:201-207`; ORE's own `docs/DISCRETIONARY_BPS.md`). In one round it can deploy at most `25 x automation.amount`, and ORE charges the fixed `fee` once, on the first deploy of that round (`deploy.rs:338-342`). The executor cannot:

- withdraw: the Automation's lamports leave only to the Round (the deploy), to the signer (the fee), or to the authority on close (`deploy.rs:273-276`, `345-352`);
- change the fee, the executor or the per-square cap, because only the authority can call `automate` (`automate.rs:113-119`);
- claim ORE or SOL, because both require the Miner authority to sign (`claim_ore.rs:24-27`, `claim_sol.rs:15-18`);
- redirect SOL returns: `checkpoint` sends them to the Automation (if `reload`), the authority, or the Miner (`checkpoint.rs:195-221`).

So even a fully compromised Heads Down program is bounded by ORE: at most `25 x automation.amount` per round, deployed into ORE rounds (where about 89 to 99% comes back to the user, not to the attacker), plus the fixed fee per round. The Heads Down app therefore sets `automation.amount` to the plan's per-square amount, not to a large value. That ORE-enforced ceiling survives even a malicious Heads Down upgrade ([THREAT_MODEL.md](THREAT_MODEL.md)).

---

## 5. The dig CPI flow

```
crank tx (fee payer = crank key; no user signature)
 |
 |-- ComputeBudget
 |-- ORE checkpoint(rig_i, previous round)        top-level, crank signs, permissionless
 |-- Secp256r1SigVerify (one sig per rig heartbeat, offsets point into this ix only)
 '-- heads_down::dig(rigs[], hb_ix_index[])
       for each rig:
        1. introspect the secp256r1 ix via the instructions sysvar (address checked);
           pubkey == rig.p256; message == H(domain | program | rig | Board.round_id |
           counter | DOWN | shift_id | lease_end); counter/lease rules
        2. shift armed, not broken or frozen; caps and expiry left
        3. production-cost gate: Board.production_cost_ema <= min(rig ceiling, plan threshold)
        4. pre-flight: mirror every ORE check that would abort the tx (below)
        5. compute amount and a least-crowded square mask from the owner-checked Round
        6. snapshot: automation.balance, executor lamports, miner.deployed
        7. invoke_signed ORE deploy  --------------------------------------------.
                                                                                  |
             ORE deploy (stack height 2) <----------------------------------------'
               |-- entropy `next`      (only on the round's first deploy; height 3)
               |-- System transfer     CHECKPOINT_FEE from Executor PDA, if Miner has none (height 3)
               '-- ORE Log self-CPI    DeployEvent{signer = Executor PDA, authority, round_id, ...}
        8. reload ORE accounts; assert the deltas (below); update rig counters and spend
        9. reimburse the cranker a fixed amount from the Executor PDA,
           only if ORE credited this rig's fee in this CPI
```

On the paths we traced, the stack height stays at 3, within the runtime's limit of 5. ORE's `Log` is a no-op that only checks its signer is the Board (`log.rs`), and the entropy program's own internals were not traced. The spike measures the real depth and compute units.

**Pre-flight mirror checks (step 4).** A failing CPI aborts the whole transaction, so one bad rig would kill a batch of 6 to 10. Before the CPI, `dig` repeats ORE's own abort conditions and **skips** (does not fail) a rig that would trip one of them:

| ORE check that would abort | Source | Heads Down pre-flight |
|---|---|---|
| `start_slot <= slot < end_slot` | `deploy.rs:33` | same comparison with the Clock sysvar (a round not yet started, `end_slot == u64::MAX`, is allowed) |
| Round is `["round", board.round_id]` and `id` matches | `deploy.rs:34-37` | same |
| `executor == signer` and `authority` matches | `deploy.rs:76-77` | same, against the Executor PDA and `rig.authority` |
| The Miner exists and its authority matches | `deploy.rs:217-248` | the Miner must already exist, because if it is missing ORE tries to create it with the *signer's* seeds, which fails |
| The Miner has been checkpointed (`assert!` panic) | `deploy.rs:251-256` | `miner.round_id == round.id` or `miner.checkpoint_id == miner.round_id` |
| The executor can pay `CHECKPOINT_FEE` by System transfer | `deploy.rs:327-330` | Executor lamports >= rent-exempt minimum for 0 bytes + 10,000 |
| The entropy tail is present on the round's first deploy | `deploy.rs:14`, `47-69` | always pass all 12 accounts; `split_at(10)` panics on fewer than 10 |

**Post-CPI assertions (step 8).** Pinocchio reads account data from the runtime's buffer, so values read before the CPI are stale afterwards. Heads Down re-reads them and asserts:

- **The Automation may have closed** (`deploy.rs:273-276` and `350-352`). Check the owner and length before any re-read. If it closed, end the shift with reason `TankEmpty`.
- **A no-op deploy is not a dig.** When the Motherlode conditions fail, ORE returns `Ok(())` without deploying (`deploy.rs:79-84`). If `balance_before == balance_after`, Heads Down counts no spend, advances no dig counter and pays no reimbursement.
- **`spent = balance_before - balance_after` must not exceed the per-round, per-shift and per-week caps**, and must equal the new `sum(miner.deployed)` for this round plus at most one `fee`.
- **Executor lamports:** `after + CHECKPOINT_FEE >= before`. The Executor PDA is a signer inside ORE's CPI, and signer privileges extend to ORE's nested calls, so an upgraded ORE could debit it. This check bounds that exposure to the documented 10,000 lamports.

**Why `authority` must come from the Rig.** If a caller could choose `authority`, they could pass the Executor PDA itself. ORE would then find no Automation at `["automation", executor]` (`deploy.rs:73`), treat the call as a manual deploy, and take the SOL from the signer, the Executor PDA (`deploy.rs:353-355`). `dig` never accepts `authority` from instruction data: it reads `rig.authority` and re-derives the Automation and Miner from it.

**Tiles.** The program ranks the 25 squares by `Round.deployed[i]` (live, owner-checked) and picks the least crowded, which is the lever that most lowers the expected cost per ORE ([ECONOMICS.md](ECONOMICS.md)). The P-256-signed plan can add a variance policy: **Steady** uses split squares only, **Hunter** allows solo squares. Heads Down reimplements `distribution_mask` (`state/round.rs:125-164`) bit for bit and tests it against ORE's own unit vectors (`state/round.rs:191-240`). Squares the Miner already holds this round are skipped by ORE (`deploy.rs:297-299`), so a repeated dig cannot double-spend.

**Checkpoint.** `checkpoint` is permissionless (`checkpoint.rs:15`) and idempotent (`checkpoint.rs:31-33`). It is a no-op until the round has been reset (`checkpoint.rs:48-51`). The default crank checkpoints every rig right after `reset`, bundled in the next dig transaction. With `reload = 1`, returned SOL goes back into the Automation (`checkpoint.rs:197-204`), so it stays in ORE custody and counts against the same wallet-signed caps.

---

## 6. Fees, and who receives them

| Fee | Size | Charged when | Paid to | Source |
|---|---|---|---|---|
| ORE admin fee | `max(sq/100, 1)` lamports per square with SOL | every round | ORE `ADMIN_FEE_COLLECTOR` | `state/round.rs:86-99`, `reset.rs:259` |
| ORE protocol fee | `max((sq - admin)/10, 1)` per losing square | every round | ORE Treasury (buyback and bury) | `state/round.rs:94`, `reset.rs:260` |
| Heads Down crank fee (`Discretionary` fixed) | `Config.crank_fee` lamports per rig-round (TBD, sized to measured crank cost; see ECONOMICS.md) | once, on the rig's first deploy of the round | Executor PDA, then crank reimbursement, with any surplus to the Bury path | `deploy.rs:338-347`, `state/automation.rs:130-136` |
| Final fee on auto-close | the same fixed fee | when ORE closes an underfunded Automation without deploying | Executor PDA | `deploy.rs:268-277` |
| `CHECKPOINT_FEE` | 10,000 lamports (`consts.rs:89`), kept on the Miner | paid by the user at `automate`; paid again from the **Executor PDA** only when it has been spent | the checkpointing bot, but only within the last 12 h before the round's account expires | `automate.rs:137-140`, `deploy.rs:327-330`, `checkpoint.rs:61-67` |
| ORE refining fee | 10% of the unrefined ORE claimed | `claim_ore` | other holders of unrefined ORE | `state/miner.rs:89-99` |
| Solana fees | 5,000 lamports per signature, plus priority fee and Jito tip | every crank tx | validators | paid by the crank, covered by the crank fee |

**When the Executor pays `CHECKPOINT_FEE`.** A Round's `expires_at` is `end_slot + ONE_DAY_SLOTS` (`deploy.rs:50`). A bot may take the Miner's 10,000-lamport reserve only if it checkpoints in the last 12 h before that (`checkpoint.rs:63-67`). The default crank checkpoints seconds after `reset`, so the reserve normally stays in place and the Executor never has to refill it. It refills from the Executor PDA only after a late third-party checkpoint. That System transfer is also why the Executor PDA must be a **data-less, System-owned** account holding lamports (steel `collect` is a plain `system_instruction::transfer` from the signer; steel 4.0.9 `account/lamports.rs:16-21`).

There is no percentage fee on mining. Heads Down's only revenue is a disclosed Jupiter platform fee on the user-signed buy leg ([ECONOMICS.md](ECONOMICS.md)).

---

## 7. Failure modes

| # | What happens | ORE source | Effect on the user | Heads Down handling |
|---|---|---|---|---|
| F1 | Motherlode condition unmet | `deploy.rs:79-84` returns `Ok(())` | nothing deployed, no fee | post-CPI no-op detection; no spend, no reimbursement |
| F2 | Balance below `amount x squares + fee` on the first deploy | `deploy.rs:268-277` | the Automation closes to the user, one fee is paid, nothing is deployed | pre-flight shrinks the square count to fit the balance, or skips the rig and marks it `TankEmpty` |
| F3 | Balance below one more square after a deploy | `deploy.rs:350-352` | the Automation closes to the user | post-CPI closure check; the shift ends |
| F4 | The user re-pointed or revoked the executor | `deploy.rs:76`, `automate.rs:88-97` | Heads Down can no longer deploy | pre-flight skip; the rig shows `Revoked` |
| F5 | Miner not checkpointed | `deploy.rs:253-256` (`assert!`) | the whole tx would abort | pre-flight; the crank checkpoints first |
| F6 | Deploy outside the round window | `deploy.rs:33` | tx aborts | pre-flight; retry next round |
| F7 | Executor float too low for `CHECKPOINT_FEE` | `deploy.rs:329` | tx aborts | pre-flight; alert; anyone can top up the Executor PDA |
| F8 | No one checkpoints within about 24 h | `checkpoint.rs:38-43`, `55-59` | **rewards for that round are forfeited** | the crank checkpoints at once; the Miner's 10,000-lamport bot fee pays third parties to do it in the last 12 h |
| F9 | `reset` stalls (entropy not finalized, no caller) | `reset.rs:28`, `78-84` | no new round, nothing mines | rigs stay cold, with no loss. `reset` is permissionless, but the caller must pass the correct top miner or it panics (`reset.rs:190-211`), so the crank calls it only as a verified backup |
| F10 | No RNG for a round | `reset.rs:99-133`, `checkpoint.rs:156-168` | all SOL refunded at checkpoint | none needed |
| F11 | An ORE upgrade changes a layout or an instruction | n/a | a wrong read could bypass the cost gate | circuit breaker (section 8); rigs go cold and funds stay in users' Automations |
| F12 | A batch contains a rig that trips an abort | any of the above | the whole batch fails | pre-flight skip, crank simulation, smaller batches on retry |

---

## 8. Version pinning and the circuit breaker

ORE changes often (about 15 program commits in September 2026, per `docs/research/redteam.md`), and `deploy` already has a variable-length entropy tail. Heads Down pins ORE at three levels.

**Level 1: layout pins (on-chain, always on, cannot be switched off).**
- Program ID, Board, Treasury, Config, Var and mint addresses are compile-time constants; Round, Automation and Miner are re-derived from seeds.
- Every ORE account must be owned by ORE, have the exact size (40, 952, 160, 752, 48, 232) and the exact discriminator (105, 109, 100, 103, 104, 101).
- Values must be sane: `Round.id == Board.round_id`; `Board.end_slot == u64::MAX` or `end_slot > start_slot`; `production_cost_ema > 0`; `Automation.strategy == 2`.
- The `Deploy` data is exactly 13 bytes: tag 6, amount u64 LE, mask u32 LE (`instruction.rs:66-69`).
- Any mismatch makes `dig` refuse with `OreLayoutMismatch`. Nothing is deployed, and the funds stay in users' Automations.

**Level 2: version pin (on-chain, beta only).** `dig` reads ORE's ProgramData header and compares the `slot` field (the last upgrade slot, `450,496,378` today) with `Config.ore_programdata_slot`. After any ORE upgrade it refuses until the pin is updated. Updating the pin is a Config change behind a timelock. Its only effect is to accept the ORE code that is already deployed; it cannot move funds.
- **Trade-off:** safety over liveness. A harmless ORE upgrade turns every rig cold until the pin moves. Users lose nothing, but nobody mines.
- **Why immutable v1 drops Level 2.** Once the upgrade authority is revoked there is no one left to move the pin, so the first ORE upgrade would brick v1 forever. v1 relies on Level 1 plus Level 3, and an ORE change that is incompatible even with Level 1 is handled by deploying v2 and having users re-point their executor with one approval.

**Level 3: off-chain breaker (crank and CI).**
- The crank watches ORE's ProgramData over LaserStream. On a change it pauses digs, dumps the new binary, and runs the fork suite (`spikes/ore-executor/fetch-fixtures.sh` plus the LiteSVM tests against the live `.so`), including every negative case in [THREAT_MODEL.md](THREAT_MODEL.md).
- It resumes only when the suite is green. A nightly CI job does the same against mainnet.
- Level 3 is a convenience: the on-chain checks decide, not the crank.

**What no pin can do.** The Automation is owned by ORE, so ORE's upgrade authority can move user funds regardless of Heads Down. Heads Down inherits ORE's custody trust assumption in full, and the THREAT_MODEL says so.

---

## 9. Milestone proposal for the ORE matched prize

ORE pays the match in stages against milestones agreed after the hackathon, with usage updates required (`docs/research/skr-and-ore.md`). We propose metrics that **ORE can verify from its own on-chain events**, with no trust in our dashboard:

- **Heads Down miners in round `r`** = distinct `authority` values in ORE `DeployEvent`s with `signer == Executor PDA` and `round_id == r` (`deploy.rs:366-380`).
- **Share of ORE miners** = that count divided by `ResetEvent.total_miners` for round `r` (`reset.rs:217-237`).
- **SOL deployed through Heads Down** = the sum of `DeployEvent.amount x total_squares` for those events.
- **ORE buried by Heads Down** = `BuryEvent`s signed by the Heads Down BuryVault PDA (`bury.rs:84-97`).

The dashboard exports all of these as CSV, recomputed from chain data every month.

| Milestone | Deadline (from results, Nov 10) | Base target | Stretch (the SPEC's numbers) |
|---|---|---|---|
| **M1 Launch** | +30 days, matching the dApp Store publishing deadline | Live on the Solana dApp Store; trustless PDA executor on mainnet; THREAT_MODEL and audit report public; 250 registered rigs, of which at least 50 are SGT-verified | 250 SGT-verified rigs |
| **M2 Adoption** | +90 days | 1,000 rigs; 300 nightly active rigs; Heads Down at 15% or more of unique ORE miners per round during 00:00-06:00 local in one region | 25% or more in two regions (at about 165 to 172 miners per round that means about 42 or more concurrent rigs) |
| **M3 Durability** | +180 days | Immutable v1 (upgrade authority revoked); at least one third-party crank landing digs; cumulative ORE mined, bought and buried published monthly | Bury-auction volume reported as a separate line |

The base targets are ours; the stretch targets are the SPEC's. The base numbers are lower because no Seeker is available to the builder and Seeker-tier rigs come from remote testers.

**What ORE gets.**
- A native Android ORE client for Seeker and any other Android phone, with SGT-verified rigs, reviving the idea behind ORE's removed `claim_seeker`. Only the `SEEKER` seed survives in `consts.rs:56`.
- New unique miners per round, visible on ORE's own board.
- Morning buy-leg demand for ORE.
- ORE burned through ORE's own `bury` instruction from SKR forfeits.
- ORE as the primary brand on the rig, the reveal, the share cards, the deck and every launch post. No competing mining product is supported.

**For ORE (not yet raised with them).** `AutomationConditions.max_production_cost` is documented as "Deploy blocked if EMA exceeds this" (`state/automation.rs:49-51`), but nothing in `program/src` reads it. A search for `max_production_cost` finds only `api/src/state/automation.rs`. Any automation that relies on it today is unprotected. A two-line check next to the Motherlode check in `deploy.rs:79-84` (`board.production_cost_ema > automation.conditions.max_production_cost` means return `Ok(())`) would make it real. Until then, Heads Down enforces the gate itself. We will raise this with ORE through their preferred channel (`SECURITY.md` for anything security-relevant).

---

## 10. Burying ORE: `bury`, not the grams address

Research notes mention a "grams address" `GHRBYPA4cujFwfyhNNm6NLTh4egdTrcz7xkBbEwM4xX` that "automatically buries any ORE sent to it". On mainnet it is an ordinary System-owned account with no data (0.05 SOL at the time of reading), and it appears nowhere in ORE's program source. Whatever buries ORE sent there runs off-chain and cannot be verified.

ORE's `bury` instruction is permissionless and verifiable (`bury.rs:18`). It moves `min(balance, amount)` ORE from the signer's ATA to the Treasury (`bury.rs:35-42`), sends 10% to ORE's stake program through `distribute` (`bury.rs:45-59`), and burns the other 90% (`bury.rs:66-74`). The Heads Down Bury auction therefore CPIs `bury`, signed by the BuryVault PDA, and reports "buried" honestly as 90% burned plus 10% distributed by ORE. (`buyback`, by contrast, is restricted to ORE's `BURY_AUTHORITY`, `buyback.rs:16`.) Where the SPEC says proceeds go "to ORE's bury address", read "through ORE's `bury` instruction".
