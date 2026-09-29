# heads_down: the on-chain program

When a phone lies face-down, it mines ORE inside the user's **own** ORE Automation.
The user's Automation names this program's **Executor PDA** as its executor. That
Automation uses the Discretionary strategy with a fixed fee. `heads_down` signs ORE
`deploy` for it only when all of the following hold in the same transaction:

- It carries a fresh Android Keystore **P-256 heartbeat** for this rig, shift and ORE
  round, verified by the `Secp256r1SigVerify` precompile.
- The dig stays inside the caps the wallet signed.
- The **Motherlode-aware production-cost gate** is open.

The user's SOL never leaves ORE custody.

- Program id: **`HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p`**, declared with
  `Address::new_from_array` and unit-tested against base58.
- Executor PDA: `By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge` (bump 249).
- Config PDA: `inzDn4ogmXbx9YDAKDHkfwJHy1jhsaWxGQvricDAEmW` (bump 253).
- Pinocchio 0.11, `no_std`, no allocator. There is **one** `unsafe` block, the
  `sol_log_data` syscall in `events.rs`. The build is about 106 KB.
- Contract: [`INTERFACE.md`](INTERFACE.md). Every extension and deviation, with exact
  account lists and data layouts: [`INTERFACE-NOTES.md`](INTERFACE-NOTES.md).

```
program/            the SBF program (crate `heads-down`, lib `heads_down`)
  src/lib.rs          id, PDAs, dispatch
  src/state.rs        zero-copy Config / Rig / SeekerSeat / ShiftLog, const offset pins
  src/ore.rs          pinned ORE ids, layouts, checked readers, distribution_mask, deploy CPI
  src/logic.rs        pure gate / budget / tiles / lease / streak logic (unit-tested)
  src/message.rs      HEARTBEAT / BREAK / FREEZE / PLAN / registrar preimages
  src/ed25519.rs      registrar attestation introspection
  src/instructions/   one module per instruction (account lists in each module doc)
tests/              LiteSVM fork suite + reference client (tests/src/lib.rs)
  fixtures/           fetch-fixtures.sh → live ORE bytecode + accounts (gitignored)
  mock-ore/           TEST ONLY: misbehaving ORE stand-in for the invariant tests
scripts/build.sh    both SBF variants (+ mock)
scripts/test.sh     fixtures → build → unit + fork tests → clippy -D warnings
```

## Build

The Agave 4.1.2 CLI is not on `PATH` by default:
`export PATH="$HOME/.local/share/solana/install/active_release/bin:$PATH"`.

```bash
cd programs/heads-down
# Mainnet: the hardcoded SGT anchors (group GT22s89…, authority GT2zuH…).
cargo build-sbf --manifest-path program/Cargo.toml --features mainnet
# Devnet / test: SGT anchors swapped for a test group at compile time.
SGT_VERIFY_TEST_GROUP=<group mint> SGT_VERIFY_TEST_AUTHORITY=<authority> \
  cargo build-sbf --manifest-path program/Cargo.toml --features devnet --sbf-out-dir target/deploy-devnet
# Or both at once. For the LiteSVM suite the test-group variables must be UNSET
# (the suite signs with the public test keys); scripts/build.sh unsets them unless
# HD_DEVNET_ANCHORS=env.
bash scripts/build.sh
```

The `mainnet` and `devnet` features are mutually exclusive: `sgt-verify` emits a
`compile_error!` if both are set, so the mainnet binary can never contain the
test-group path. A build with neither feature also uses the mainnet anchors.

## Deploy to devnet (not done here: instructions only)

The deploy keypair lives **outside the repo** at
`~/.config/heads-down/heads_down-program-keypair.json`. Never copy it into the tree;
`*-keypair.json` is gitignored as a safety net.

```bash
KP=~/.config/heads-down/heads_down-program-keypair.json
solana address -k "$KP"            # must print HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p
# 1. Test-group anchors YOU control (crates/sgt-verify/scripts/make-test-sgt.sh prints them).
export SGT_VERIFY_TEST_GROUP=... SGT_VERIFY_TEST_AUTHORITY=...
HD_DEVNET_ANCHORS=env bash scripts/build.sh
# 2. Deploy. The fee payer / upgrade authority is your devnet wallet.
solana program deploy -u devnet --program-id "$KP" target/deploy-devnet/heads_down.so
# 3. initialize_config (tag 0) signed by that upgrade authority, with the
#    governance key, registrar key, crank_fee <= executor_fee, bury_bps and
#    ore_layout_hash = sha256(ore::LAYOUT_PREIMAGE) (see heads_down::ore::layout_hash()).
#    tests/src/lib.rs::ix_initialize_config builds the instruction.
# 4. Fund the Executor PDA float: a plain SOL transfer to By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge.
# 5. Before mainnet: move the upgrade authority to the Squads vault (72 h timelock), then revoke.
```

The ORE addresses are pinned to **mainnet** ORE (`oreV3EG1…`, Board `BrcSxdp1…`, and
so on). On devnet the registration, SGT, caps, arming, signals, heartbeats and
`end_shift` flows all work. `dig` needs ORE at those addresses, so it is exercised
against a mainnet fork: this LiteSVM suite, or Surfpool with the same fixtures.

## Tests

```bash
cd programs/heads-down
bash scripts/test.sh                 # everything (≈ 2 minutes after the first build)
cargo +1.97.1 test -p heads-down     # host unit tests only
```

LiteSVM 0.17 with `features = ["precompiles"]` runs the real Agave secp256r1 and
ed25519 precompiles, and its agave 4.3 dependencies need rustc 1.97.1
(`rust-toolchain.toml`). `tests/fixtures/fetch-fixtures.sh` dumps the **live mainnet
ORE program** (`ore.so`, sha256 `57503f43…`, fetched 2026-09-29T19:03Z), the entropy
program, and the Board, Config, Treasury, Var and current Round (round 422,685).
heads_down is loaded at its real program id through the upgradeable loader, with a
test upgrade authority written into its ProgramData.

Last run, `bash scripts/test.sh`: **76 passed, 0 failed**. `cargo +1.97.1 clippy
--workspace --all-targets -- -D warnings` is clean.

| Suite | Tests | What it proves |
|---|---|---|
| unit (`program/src`) | 14 | program id / PDAs vs base58 and `find_program_address`; `distribution_mask` equals a verbatim transcription of ORE's over 20k round ids; gate matches `ml/forecaster` (incl. the live fixture); budget reserves the fee; tile choice; leases; streak; overflow edges |
| `lifecycle` | 3 | one-transaction onboarding: ORE `automate` (Discretionary, fee = `executor_fee`, executor = PDA) + `register_rig` + `set_caps` + `arm_shift`; a real P-256 heartbeat dig through the gate credits the 10 least-crowded split tiles, debits tiles + fee, reimburses the crank and emits `RigDug`; ShiftLog; close |
| `batch` | 3 | 6 rigs: gate-closed, lease-less, un-checkpointed Miner and underfunded rigs are skipped with exact codes while 2 dig (one reimbursement each); duplicate rig fails; a user re-pointing or revoking its executor skips only itself |
| `heartbeat` | 9 | stale counter; exact replay; future / old / mismatched round; wrong `shift_id`; another rig's key; offsets smuggled into a foreign instruction; forged instructions sysvar; high-S; bad precompile indices; entries swapped between rigs |
| `dig_checks` | 19 | gate by plan and by wallet cap, and the Motherlode pot opening it; caps expired; outside window; budget exhausted by shift or week (fee inside caps); strategy / fee (incl. 0) mismatch; Motherlode no-op; round window closed; executor underfunded; frozen / broken / idle; focus-only; non-ORE-owned Board / Treasury; look-alike Board; spoofed Round / ORE / entropy; another user's Automation or Miner; caller-chosen authority (incl. the Executor PDA); wrong executor; uninitialized Config; paused Config; malformed data / counts / missing crank signature |
| `shifts` | 8 | P-256 PLAN arming within caps (replay, over-cap, foreign key, no wallet signature); plan validation; pickup → Cooling → fresh heartbeat resumes; BREAK not replayable as FREEZE; only the wallet unfreezes; permissionless `end_shift` only after window and lease; streak with freezes over 5 nights; focus-only `record_heartbeats`; close-then-reuse |
| `admin` | 8 | only the ProgramData upgrade authority initializes (stranger, forged ProgramData, immutable program, no signature); pre-funded Config; no re-init; layout hash / fee checks; 72 h timelock (eta−1 refused, eta applied); immediate pause, delayed unpause; registrar Ed25519 attestation incl. smuggled offsets and spoofed sysvar; register / rotate / caps signer and PDA checks |
| `seeker` | 4 | **devnet build + Token-2022-issued test SGTs**: verify, non-holder refused, Solana Mobile-style move, `SeatTaken`, seat re-pointed, old rig downgraded, close with seat; rejects real SGTs, a forger's group, legacy-token owner. **Mainnet build + real SGT fixtures** (#20, #121,035, real holders): verify, re-point after a move, test-group SGTs rejected |
| `ore_semantics` | 4 | live ORE closing the Automation mid-CPI is accounted; a TEST-ONLY mock ORE that drains the Executor float (reverted), no-ops (skipped, no reimbursement) or over-debits (reverted) |
| `fuzz_no_panic` | 2 | 3,000 random + 2,000 mutated instructions on the SBF binary: only clean errors, no aborts |
| `capacity` | 2 | the measurements below |

## Measurements (live ORE fork, `tests/tests/capacity.rs`)

Each rig: a fresh heartbeat (one secp256r1 entry), 10 least-crowded split tiles,
`deploy` CPI, reimbursement. Median of 5 fresh forks. The spread comes from ORE's own
`has_seeds` bump search on each random wallet's Automation and Miner (1,500 CU per
extra attempt). heads_down's own share is deterministic, because it caches the
canonical bumps at registration.

| Rigs per dig | Total CU (min / median / max) | Per rig (median) | of which ORE `deploy` CPI | of which heads_down | v1 tx size |
|---|---|---|---|---|---|
| 1 | 36,225 / 37,725 / 51,225 | 37,725 | 21,758 | 15,967 (incl. per-tx fixed cost) | 881 B |
| 4 | 135,624 / 144,624 / 149,124 | 36,156 | 24,008 | 12,148 | 1,766 B |
| 7 | 230,386 / 245,386 / 264,886 | 35,055 | 23,258 | 11,797 | 2,651 B |

Maximum rigs per `dig` transaction. The largest fitting batch was actually executed,
and every rig dug:

| Format | Max rigs | Size / CU at max | What binds |
|---|---|---|---|
| legacy / v0, no lookup table | **2** | 1,205 B / 68,869 CU | 3 rigs = 1,500 B > 1,232 B |
| v0 + one address lookup table (all non-signer accounts) | **5** | 1,163 B / 180,708 CU | 6 rigs = 1,334 B > 1,232 B |
| v1 (4,096 B, no ALTs, ≤ 64 addresses) | **11** | 3,837 B / 372,109 CU | 12 rigs = 4,132 B > 4,096 B (the 64-address cap, 14 + 4n addresses, would bind at 13) |

Per rig, a transaction carries 4 account keys (rig, authority, automation, miner), a
20-byte entry and a 143-byte secp256r1 entry (14 offsets + 33 key + 64 sig + 32
digest). Compute is never the binding limit: 11 rigs use about 27% of 1.4M CU. Each
secp256r1 signature adds one 5,000-lamport signature fee, and `crank_fee` must cover it.

## Security

### Invariants

- **No value leaves a user's Automation except through ORE's deploy-or-return.**
  heads_down has no instruction that moves Automation lamports. The Executor PDA signs
  only ORE `deploy` and its own reimbursement transfer, and there is no withdraw path
  anywhere.
- **Every dig needs a fresh, bound P-256 signature.** It must name the rig, shift, round,
  kind and program, use a counter strictly greater than any seen before, and be
  verified in the same transaction.
- **Spend stays within wallet-signed caps.** The Automation fee is reserved inside
  every cap, and the post-CPI debit is re-checked against `cap_round`, `cap_shift` and
  `cap_week`.
- **The Executor float cannot be drained through ORE.**
  `executor_after + CHECKPOINT_FEE >= executor_before`, and strictly no loss when ORE
  had no checkpoint fee to top up.
- **The crank is reimbursed only for a real deploy.** `automation.fee` must equal
  `executor_fee`, and `crank_fee <= executor_fee`.
- **One verified rig per SGT mint.** The seat is keyed by mint and re-pointed only by
  the current holder.

### Audit checklist: the 16 bug classes

Each row lists the concrete check (file / function) and the tests that fail if it is
removed. Test paths are relative to `tests/tests/`.

| # | Class | Concrete check | Tests |
|---|---|---|---|
| 1 | **Missing signer check** | `util::require_signer` / `require_rig_authority` on every wallet action (`register_rig`, `verify_seeker`, `set_caps`, `rotate_key`, `unfreeze_rig`, `close_rig`, wallet-mode `arm/break/freeze`), the upgrade authority in `initialize_config`, governance in `propose_config`, the cranker in `dig`, the caller in `end_shift`. P-256 messages replace the wallet only for tighten-only actions (arm within caps, break, freeze, heartbeat) | `admin::register_rig_checks`, `admin::rotate_key_and_set_caps_need_the_wallet`, `admin::only_the_upgrade_authority_initializes_the_config`, `shifts::phone_key_arms_a_shift_within_wallet_caps`, `shifts::phone_can_freeze_only_the_wallet_can_unfreeze`, `dig_checks::malformed_dig_data_and_account_counts_fail`, `seeker::mainnet_build_rejects_test_group_sgts` |
| 2 | **Missing owner check** | `state::load*` (owner == heads_down before any byte); `ore::is_ore_account` (owner == ORE); `sgt_verify` (Token-2022); Executor must be System-owned and data-less; ProgramData owned by the upgradeable loader | `dig_checks::non_ore_owned_board_or_treasury_fails`, `seeker::devnet_build_rejects_real_and_forged_sgts` (legacy-token owner), `admin::only_the_upgrade_authority…` (forged ProgramData), `dig_checks::wrong_executor_account_fails` |
| 3 | **Account data matching** | Automation and Miner re-derived from `rig.authority` (canonical bump stored at registration); `automation.authority == rig.authority`; `executor == Executor PDA`; `strategy == 2`, `fee == executor_fee`; the authority account must equal `rig.authority` (never from ix data); Round == PDA(`board.round_id`) and `id` matches; seat mint / seat.rig; ShiftLog PDA | `dig_checks::another_users_automation_fails`, `dig_checks::authority_is_never_taken_from_the_caller`, `dig_checks::strategy_or_fee_mismatch_is_skipped`, `dig_checks::executor_not_configured_on_the_automation_is_skipped`, `dig_checks::spoofed_round_or_ore_program_or_entropy_fails`, `seeker::devnet_build_verifies…` (SeatTaken), `shifts::anyone_may_end_a_shift…` (wrong ShiftLog) |
| 4 | **Type cosplay** | heads_down: owner + exact length + tag + version before reading; ORE: owner + exact length + Steel discriminator (Board 105/40, Round 109/952, Automation 100/160, Miner 103/752, Treasury 104/48, Config 101/232) | `dig_checks::spoofed_round_or_ore_program_or_entropy_fails` (a Miner as the Round), `shifts::close_rig_requires_an_idle_rig_and_its_authority` (closed rig reused), `fuzz_no_panic::mutated_valid_instructions_never_abort` (account swaps) |
| 5 | **Arbitrary CPI** | CPI program ids are constants: `ore::cpi_deploy` uses `ORE_PROGRAM_ID`; System via `pinocchio-system`. The ORE, entropy and system account slots are compared to constants | `dig_checks::spoofed_round_or_ore_program_or_entropy_fails` |
| 6 | **Duplicate mutable accounts** | `dig` and `record_heartbeats` reject a repeated rig (`DuplicateRig`) before any processing; `close_account` refuses account == recipient; the `verify_seeker` previous rig must equal `seat.rig` (≠ the current rig); Pinocchio borrow tracking as a second net | `batch::a_rig_twice_in_one_batch_fails_the_transaction`, `shifts::record_heartbeats_counts_dark_rounds_without_deploying` |
| 7 | **Non-canonical bumps** | Every PDA is created with the `find_program_address` bump and stores it. The Executor bump is found at init, checked against the compile-time constant, stored in Config and never taken from ix data. ORE bumps come from `find` at registration | `lib::tests::pdas_are_canonical_and_match_the_constants`, `admin::only_the_upgrade_authority…` (stored bumps), `admin::register_rig_checks` (non-canonical rig address → InvalidSeeds) |
| 8 | **PDA sharing** | Distinct seed per purpose (`config`, `executor`, `rig`, `seeker`, `shift`). Only the Executor PDA ever signs a CPI, only for ORE `deploy` and its own reimbursement | `dig_checks::wrong_executor_account_fails` (Config PDA in the executor slot) |
| 9 | **Reinitialization** | `pda::create_pda_account` requires a System-owned, empty target (init-only) and tolerates pre-funding (transfer + allocate + assign); `state::load_uninit_mut` refuses a non-zero header | `admin::register_rig_checks` (pre-funded rig; register twice), `admin::only_the_upgrade_authority…` (pre-funded Config; init twice), `lifecycle::shift_ends_into_a_shift_log…` (end twice) |
| 10 | **Closing accounts** | `pda::close_account`: zero data, move every lamport to the recipient fixed by state (`rig.authority`), `close()` (owner System, len 0). The seat is closed only if it points at this rig | `lifecycle::shift_ends_into_a_shift_log_and_the_rig_closes`, `shifts::close_rig_requires_an_idle_rig_and_its_authority` (close then reuse in the same tx fails; re-register works), `seeker::devnet_build_verifies…` (close with seat) |
| 11 | **Unchecked arithmetic** | `checked_*` / `saturating_*` everywhere; the gate in u128; `overflow-checks = true`; the crate denies `clippy::arithmetic_side_effects`, `indexing_slicing`, `cast_possible_truncation`, `unwrap_used`, `expect_used` and `panic` | `logic::tests::*` (u64::MAX caps, overflow → error), `ore::tests::readers_never_panic_on_short_data`, `fuzz_no_panic::*` |
| 12 | **Stale data after CPI** | After `deploy`: reload the Executor lamports, the Automation (closure = owner or length change), the Miner (Δdeployed; untouched = no-op) and the Round and Board before the next rig; the debit must equal tiles + fee received | `ore_semantics::real_ore_closing_the_automation_mid_cpi_is_accounted`, `ore_semantics::ore_returning_ok_without_deploying_is_not_a_dig`, `ore_semantics::ore_debiting_more_than_it_deployed_reverts_the_dig`, `batch::failing_rigs_are_skipped…` (per-rig re-read) |
| 13 | **Signer passthrough** | The Executor signature goes only to ORE `deploy`; afterwards `executor_after + CHECKPOINT_FEE >= executor_before` (strict when no top-up was due) and the Executor must still be System-owned and data-less; the crank's signature never enters a CPI | `ore_semantics::ore_draining_the_executor_float_reverts_the_dig` |
| 14 | **Token-2022 extension pitfalls** | `sgt_verify::verify_sgt`: Token-2022 owner on both accounts; account-type bytes; mint / owner / amount == 1; frozen accepted (all real SGTs are frozen); `TokenGroupMember.group` anchor; bounded TLV. The seat is keyed by mint, and ownership is point-in-time (re-point after a move) | `seeker::*` (real SGTs, test SGTs through Token-2022, moved SGT, forged group, legacy owner, wrong anchors per build) |
| 15 | **Sysvar and introspection spoofing** | Instructions sysvar address checked (`p256_introspect::check_instructions_sysvar`) in `dig` / `record_heartbeats` / signals / attestation; Clock and Rent read by syscall; precompile program ids checked; **every** offsets record confined to the precompile's own instruction (secp256r1 and ed25519); bounds-checked parsing; low-S | `heartbeat::spoofed_instructions_sysvar_fails_the_transaction`, `heartbeat::offsets_pointing_into_a_foreign_instruction_are_rejected`, `heartbeat::high_s_and_missing_precompiles_are_rejected`, `admin::bad_attestations_are_rejected` |
| 16 | **Resource exhaustion and griefing** | Batch capped at 32 rigs; exact account and data lengths; bounded loops (25 squares, n rigs, ≤ 8 precompile records); every user-controllable ORE abort is **pre-flighted and skipped**, never aborting a batch; reimbursement only after a real deploy and never below the Executor reserve; a zero-fee Automation is refused; PDA bumps cached so per-rig CU is predictable | `batch::failing_rigs_are_skipped_and_the_rest_deploy`, `batch::a_rig_revoking_its_executor_mid_flight_only_skips_itself`, `dig_checks::strategy_or_fee_mismatch_is_skipped` (zero fee), `dig_checks::executor_underfunded…`, `capacity::*`, `fuzz_no_panic::*` |

### Worst case per key (from `docs/THREAT_MODEL.md`, as enforced here)

- **Rig P-256 key.** It can arm a shift only inside wallet-signed caps and expiry, and
  can dig only while the gate is open, at most `plan_dig_lamports` per round and at most
  one dig per round. The deployed SOL goes into ORE rounds, not to the attacker. It
  can freeze and break its own rig, but it cannot unfreeze, raise caps or change keys.
- **Cranker.** It chooses which heartbeats to land and when. It cannot choose amounts,
  tiles or authority, and it is reimbursed only for real deploys.
- **Governance.** It can change the registrar, `crank_fee` (≤ `executor_fee`) or
  `bury_bps` after 72 h, and pause immediately. It cannot move funds or change
  `executor_fee`.
- **Upgrade authority (beta).** This is the full program authority. Mitigate it with a
  Squads vault behind a 72 h timelock, then revoke it.

## Known limitations

See `INTERFACE-NOTES.md` §10. Two more:

- The Android `HeartbeatMessage` in `android/core/keys` still uses the old 101-byte raw
  format. It must switch to `SHA-256` of the 94-byte `HDv1` preimage
  (`message::heartbeat_preimage`).
- No physical device was available. Keystore signatures are simulated with p256 in
  Keystore's format (DER, then low-S raw).
