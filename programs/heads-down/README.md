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

On top of that core, v1.2 adds the **SKR features**: Stack (an SKR-bonded
self-control contest settled from the phones' heartbeats), Focus Bonds, Gift a
Rig, and a no-oracle Bury auction that sells SKR forfeits for ORE that ORE's own
`bury` instruction burns. See "SKR features (v1.2)" below.

- Program id: **`HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p`**, declared with
  `Address::new_from_array` and unit-tested against base58.
- Executor PDA: `By3vJvQUsCLexnv7VqHuEhtZZCmpmjZjfhxvqCnWPkge` (bump 249).
- Config PDA: `inzDn4ogmXbx9YDAKDHkfwJHy1jhsaWxGQvricDAEmW` (bump 253).
- BuryVault PDA: `6i46qfoKvAQssmf9A6yJ8rihGfP5XvUQfgrHsSzEm9ZS` (bump 255).
- Pinocchio 0.11, `no_std`, no allocator. There is **one** `unsafe` block, the
  `sol_log_data` syscall in `events.rs`. The build is about 180 KB (SBPF v0, the
  default) or 177 KB with `--arch v3` (see "SBPFv3" below).
- Contract: [`INTERFACE.md`](INTERFACE.md) **v1.3**: the v1.1 core (§0 to §10),
  frozen from this code (every instruction's data and account list, every event,
  errors 0..31, the Rig field usage, the dig budget, pause and state-machine
  semantics, measured transaction limits), plus §11, the **additive** SKR section
  (tags 15..27, accounts 5..9, events 11..23, errors 32..48), and §12, the
  **additive** hardening section (governance rotation, the Rig tombstone,
  `close_shift_log`, attestation re-checked at every Stack check-in; tags 28..31,
  events 24..27, errors 49 and 50). No v1.1 layout, event byte or error code
  changed. `INTERFACE-NOTES.md` is superseded.
- **Reviewed before deployment.** §12.13 lists the seven rules a security review
  tightened (reimbursement only out of a fee received, a bound on counter jumps and
  on voucher lifetime, a grace before anyone else may end a shift, Bury auction
  anchoring, the tombstone's `last_dug_round`, a closed rig skipping itself in a
  batch). The record is [`docs/SECURITY_REVIEW.md`](../../docs/SECURITY_REVIEW.md).
- Machine-checked contract: [`vectors/`](vectors/) (golden instructions, messages,
  events and registrar voucher, all generated from LiteSVM runs; see "Golden
  vectors" below). Where each consumer disagrees: [`vectors/CROSSCHECK.md`](vectors/CROSSCHECK.md).

```
program/            the SBF program (crate `heads-down`, lib `heads_down`)
  src/lib.rs          id, PDAs, dispatch
  src/state.rs        zero-copy Config / Rig / SeekerSeat / ShiftLog, and the v1.2
                      StackTable / StackSeat / FocusBond / GiftEscrow / BuryVault, const offset pins
  src/ore.rs          pinned ORE ids, layouts, checked readers, distribution_mask, deploy and bury CPIs
  src/logic.rs        pure gate / budget / tiles / lease / streak logic (unit-tested)
  src/skr.rs          v1.2 caps, Stack finish rule and payout split, Bury auction price (unit-tested)
  src/token.rs        v1.2 classic SPL Token checks and the Transfer / CloseAccount CPIs
  src/message.rs      HEARTBEAT / BREAK / FREEZE / PLAN / registrar preimages
  src/ed25519.rs      registrar attestation introspection
  src/instructions/   one module per instruction or SKR feature (stack, focus_bond, gift, bury);
                      account lists in each module doc
tests/              LiteSVM fork suite + reference client (tests/src/lib.rs, tests/src/skr.rs)
  fixtures/           fetch-fixtures.sh → live ORE bytecode + accounts, the SKR and ORE mints,
                      the ORE stake program and bury's accounts (gitignored)
  mock-ore/           TEST ONLY: misbehaving ORE stand-in for the invariant tests (deploy and bury)
scripts/build.sh    both SBF variants (+ mock)
scripts/test.sh     fixtures → build → unit + fork tests (incl. the golden-vector drift test) → clippy -D warnings
scripts/test-v3.sh  both variants with --arch v3 (SBPFv3), then the whole fork suite against them
scripts/vectors.sh  regenerate vectors/ after an intentional contract change
vectors/            golden vectors (JSON), CROSSCHECK.md, crosscheck/ (reproducible consumer checks)
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
against a mainnet fork: this LiteSVM suite, or Surfpool with the same fixtures. The
v1.2 SKR paths likewise pin the mainnet SKR and ORE mints, so they too run on a
mainnet fork (the devstack's `solana-test-validator` clones, or this suite); after a
deploy, run `init_bury_vault` once (anyone may) and create its two ATAs.

## SKR features (v1.2)

INTERFACE.md §11 is the contract and [`docs/SKR.md`](../../docs/SKR.md) the
product description. SKR is collateral, bond and gift currency only: the program
mints nothing, pays nothing for holding SKR, and never touches Solana Mobile's SKR
staking program.

| Feature | Instructions | What the program enforces |
|---|---|---|
| **Stack** | `open_stack` 15, `join_stack` 16, `stack_checkin` 17, `settle_stack` 18, `claim_stack` 19 | Equal SKR bonds in a table-owned vault; joins only before the window (remote tables: attested Seekers with a re-verified SGT, one seat per SGT; guests only up to 500 SKR); a round counts only if a heartbeat for it landed during it, read from existing Rig fields (`shift_id`, `break_reason`, `plan_lease_rounds == 1`, `lease_from == lease_to == Board.round_id`); a seat finishes iff never broken in its bound shift, it counted `end_round`, and its gaps ≤ grace; permissionless settle with the 80/20 split (dust to Bury), `sum(payouts) + bury == bonds`; pull claims that close the seat; a timeout refund |
| **Focus Bond** | `lock_focus_bond` 20, `release_focus_bond` 21, `forfeit_focus_bond` 22 | SKR locked on an open, clean shift; released to the owner only if that shift's ShiftLog (matched by id, start round and start time) says `completed`, otherwise forfeited permissionlessly to the Bury lot, including when the rig was closed mid-shift |
| **Gift a Rig** | `create_gift` 23, `claim_gift` 24, `refund_gift` 25 | Lamports escrowed for a wallet or an SGT mint; the claimer must be that wallet or hold that SGT now (`sgt-verify`); the claim can share a transaction with ORE `automate` + `register_rig`; refund to the sender only, from day 30 |
| **Bury auction** | `init_bury_vault` 26, `bury_auction_buy` 27 | One pooled SKR lot; a linear Dutch price from the slot (no oracle) that restarts at 4× the last clearing price on each new lot; the buyer's ORE goes into the BuryVault's ORE ATA and through ORE's permissionless `bury` signed by the BuryVault PDA; the vault delta and the 90% supply burn are checked before the SKR leaves |

## Tests

```bash
cd programs/heads-down
bash scripts/test.sh                 # everything (≈ 2 minutes after the first build)
cargo +1.97.1 test -p heads-down     # host unit tests only
```

LiteSVM 0.17 with `features = ["precompiles"]` runs the real Agave secp256r1 and
ed25519 precompiles, and its agave 4.3 dependencies need rustc 1.97.1
(`rust-toolchain.toml`). `tests/fixtures/fetch-fixtures.sh` dumps the **live mainnet
ORE program** (`ore.so`, sha256 `57503f43…`; last fetched 2026-09-30T23:23Z, round
424,018), the entropy program, and the Board, Config, Treasury, Var and current
Round. For v1.2 it also dumps the **SKR and ORE mints**, the **live ORE stake
program** (`ore_stake.so`, sha256 `1ea52a5d…`) and every account ORE `bury` touches
(the Treasury's ORE ATA, the stake Treasury, its ORE ATA and Vesting; the Vesting
schedule is pinned to the suite's clock). heads_down is loaded at its real program
id through the upgradeable loader, with a test upgrade authority written into its
ProgramData. SKR and ORE balances are account surgery: the fork cannot mint either.

Last run, `bash scripts/test.sh` (2026-10-03): **171 passed, 0 failed, 1 ignored**
(33 unit + 138 fork; the ignored one is `crosscheck`). The table below was written for
v1.2: v1.3 added `tombstone`, `governance`, `shift_log`, `fuzz_v13` and `v13_capacity`,
and the review added one regression test per fix. The fork reads the live ORE
bytecode and accounts as fetched, except the two inputs of the cost gate
(`Board.production_cost_ema` and `Treasury.motherlode`), which are pinned: with the
live values the suite passed or failed with the day's ORE cost. `cargo +1.97.1 clippy
--workspace --all-targets -- -D warnings` is clean. `bash scripts/test-v3.sh` runs the
same 107 fork tests against the SBPFv3 builds: all pass.

| Suite | Tests | What it proves |
|---|---|---|
| unit (`program/src`) | 30 | program id / PDAs vs base58 and `find_program_address` (incl. the BuryVault and the pinned SKR ids); `distribution_mask` equals a verbatim transcription of ORE's over 20k round ids; gate matches `ml/forecaster` (incl. the live fixture); budget reserves the fee; tile choice; leases; streak; overflow edges; every event encoder (tags 1..23) fills its exact length; **v1.2:** Stack payout conservation over every finisher subset of 1 to 8 seats, the finish rule, the refund timeout, the Dutch price, purchase rounding and bury's 90/10 split, strict SPL Token account parsing |
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
| `events_v11` | 5 | v1.1: BREAK 7 unplugged → Cooling (a fresh heartbeat resumes), 8 unlocked → Broken and sealed with reason 8; ShiftBroken on BREAK and on a shift-interrupting FREEZE only; HeartbeatsRecorded reports only the rounds added; ShiftEndedV2 mode; every reason code → state |
| `registrar_voucher` | 3 | a voucher built exactly like `registrar/src/voucher.rs` (111-byte HDreg, 223-byte Ed25519SigVerify, `IX_HEADER`) is accepted by `register_rig` and `rotate_key`; level 0, level 3, expired, another wallet, a non-registrar key and a mismatched level are refused |
| `vectors` | 4 | the committed golden vectors are byte-identical to a fresh LiteSVM generation; generation is deterministic; INTERFACE.md's event, error and instruction tables match the program, for v1.1 (§5, §7, §8) and v1.2 (§11.4, §11.9, §11.10) |
| `skr_stack` | 13 | **v1.2 Stack:** a six-seat table settled from real P-256 check-ins (finish, grace, broken, too many gaps, missed end round) with the exact 80/20 split, rounding dust to Bury, conservation and single claims; bury-only and nobody-finishes tables; the timeout refund; join gating (time, room, signature, uniqueness, canonical seat, guest cap, attested-only, remote Seekers with a re-verified real SGT, one seat per SGT); classic SPL Token only (Token-2022 look-alikes, fake mints, wrong owners, swapped vaults); check-in rules (lease 1, late and replayed heartbeats, shift binding, no binding to a broken shift, observe mode after a real dig, FREEZE, a closed rig cannot sink a batch); settle needs every seat once |
| `skr_bond` | 6 | **v1.2 Focus Bond:** release to the owner only after `completed`; forfeit to the Bury lot after a hard break; a resumed pickup keeps the bond; bonds on a rig closed mid-shift (then re-registered) are abandoned and forfeit; lock gating |
| `skr_gift` | 4 | **v1.2 Gift a Rig:** a wallet gift claimed in the same transaction as the recipient's ORE `automate` and `register_rig`; an SGT gift that follows a real SGT to its current holder; refunds from day 30 to the stored sender only; argument checks, one escrow per (sender, nonce), pre-funding |
| `skr_bury` | 6 | **v1.2 Bury auction:** lots from real forfeits sold through the **live ORE `bury` and ORE stake programs** (supply −90%, stake Treasury +10%); price decay, restarts, floor, one-atom minimum; slippage and amount guards; every pinned account; a TEST-ONLY mock ORE that returns Ok without taking the ORE, or takes it without burning, is caught (`BuryMismatch`) |
| `fuzz_skr` | 2 | 2,000 random tag 15..27 instructions and 2,000 mutations of valid SKR instructions on the SBF binary: only clean errors |
| `skr_capacity` | 2 | the SKR measurements in INTERFACE.md §11.11 |
| `crosscheck` | (ignored) | executes the Android and crank vectors and the consumer assumptions (`vectors/CROSSCHECK.md`); run with `-- --ignored` |

## Golden vectors

`vectors/` is the contract in machine-checked form. `tests/src/vectors.rs`
generates it from one deterministic scenario on a **pinned** fork: the live ORE
and entropy bytecode, fixed public test keys, and the ORE Board, Treasury and
Round pinned to round 422,700, EMA 918,782,720 and a 344 ORE pot.

| File | Contents |
|---|---|
| `instructions.json` | All 32 tags, both auth paths (47 vectors: the 25 v1.1 vectors, then 16 v1.2 SKR vectors, then 6 v1.3 vectors; `end_shift_permissionless` moved to round 422,704 for the 3-round grace). Each has data hex with a per-field layout, the ordered account metas (role, signer, writable, PDA seeds and bump), the full transaction including precompile, ATA and compute-budget companions, and the executed LiteSVM result with its raw events. Every vector must succeed. The SKR scenario: `init_bury_vault`; a three-seat Stack with verify-mode and observe-mode check-ins, settle (80/20, a Bury lot) and a claim; Focus Bond lock, forfeit and release; wallet and SGT-mint gifts with claims and a refund; a Bury auction buy through the live ORE `bury` |
| `messages.json` | HEARTBEAT (94 B), BREAK and FREEZE (86 B) and PLAN (113 B) preimages, SHA-256 digests, RFC 6979 and low-S signatures from the RFC 6979 A.2.5 test key, and the 145-byte Secp256r1SigVerify data. Each was verified by the real precompile, and a tampered copy was rejected. (Stack check-ins reuse HEARTBEAT: no new message kind.) |
| `events.json` | Tags 1..27 with layouts and bytes captured from runs, plus `RigSkipped` captured for 20 skip codes, including every ORE pre-flight code 25..31 |
| `registrar.json` | The 111-byte HDreg preimage and the 223-byte Ed25519SigVerify instruction in `registrar/src/voucher.rs` format, accepted by `register_rig` (level 2). Level 0 and expired vouchers are rejected |

These tests guard the vectors:

- `tests/tests/vectors.rs` fails if a committed file drifts by one byte, if
  generation is not deterministic, or if INTERFACE.md's event, error or
  instruction tables disagree with the program.
- The output does not change when the fixtures are re-fetched at a newer ORE
  round (checked 422,755 → 422,769).

To regenerate after an intentional change, run `bash scripts/vectors.sh`, review
the diff, and update INTERFACE.md in the same commit.

`vectors/CROSSCHECK.md` records where the Android, crank, indexer and registrar
assumptions match or differ. Every item was executed; reproduce all of them with
`bash vectors/crosscheck/run.sh`.

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

Re-measured on 2026-10-01 with the round-424,018 fixtures, heads_down's own share of a
`dig` is 19,310 / 13,290 / 12,605 CU per rig for 1 / 4 / 7 rigs, identical (±1 CU) to
the pre-v1.2 program built from `main` on the same fixtures: the SKR work did not
change `dig`. The table above was measured earlier; the ORE share moves with the live
round.

**v1.2 SKR instructions** (`tests/tests/skr_capacity.rs`; INTERFACE.md §11.11):

| Instruction | CU | Transaction |
|---|---|---|
| `stack_checkin`, verify mode | 8,846 for 4 seats | the 4-seat maximum per 1,232-byte packet (1,226 B) |
| `stack_checkin`, observe mode | 8,171 for 8 seats | 961 B |
| `settle_stack` | 5,250 for 8 seats | 634 B |
| `join_stack` / `claim_stack` | 6k to 7.5k / 2,734 | |
| `lock_focus_bond` / `release` / `forfeit` | 8.7k to 11.7k / 3,650 / 6,251 | |
| `create_gift` / `claim_gift` (wallet; SGT re-verified) / `refund_gift` | 4,993 / 879; 2,604 / 695 | |
| `bury_auction_buy` (with ORE `bury` and stake `distribute`) | 104,464 | 780 B |

## SBPFv3

`cargo build-sbf --arch v3` builds heads_down as SBPFv3 (ELF `e_flags` 3; 177 KB
against 180 KB for the default v0). `bash scripts/test-v3.sh` builds both variants
that way and runs the **whole fork suite** against them (`HD_SBF_ARCH=v3` makes the
harness load `target/deploy-v3` and `target/deploy-devnet-v3`): all 107 fork tests
pass, including the golden vectors, which are byte-identical to the v0 run, and the
fuzzers. Compute is marginally lower: deterministic paths drop by 2 to 52 CU (for
example `stack_checkin` with 4 verified seats 8,846 → 8,794; `settle_stack` 5,250 →
5,245; heads_down's share of a 1-rig `dig` 19,310 → 19,301). Its CPIs into the
SBPFv0 ORE, ORE stake and SPL Token programs all work from v3.

**On a real validator (2026-10-01).** On a bare `solana-test-validator` 4.1.2 (every
feature active, so SIMD-0500 is on), the default v0 build is **refused** at deploy
("Detected sbpf_version required by the executable which are not enabled"), while the
`--arch v3` build **deploys** at `HDn4vgLW…` and executes: `initialize_config` read the
upgrade authority from the real loader's ProgramData and created the Config (256 B,
bump 253), `init_bury_vault` created the BuryVault (192 B, bump 255), and an unknown tag
failed cleanly with `Custom(0)`.

**Mainnet feature gates (queried 2026-10-01):** SBPFv3 deployment and execution
(SIMD-0178/0189/0377, `5cC3foj7…`) is **active** since epoch 993; SIMD-0500 (no more
v0/v1/v2 deploys, `B8JJXCy5…`) is **inactive**. So heads_down can be deployed to
mainnet as SBPFv3 today, and once SIMD-0500 activates the v0 build will no longer
deploy. `scripts/build.sh` still builds v0 by default (the devstack clones mainnet's
feature set and deploys that build); switching the mainnet deploy to `--arch v3` is
recommended.

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
- **Stack conservation (v1.2).** `sum(payouts) + bury == sum(bonds)` for every table;
  each seat claims once (claiming closes it); claims never exceed the settled payouts,
  or the bonds when refunding.
- **SKR leaves a vault only to a recipient fixed by state (v1.2).** Table, bond and
  Bury vaults are SPL Token ATAs owned by their own PDA; each PDA signs only transfers
  out of its own vault to the seat's wallet, the bond's owner, the Bury lot or a buyer
  who paid. There is no admin, and no withdraw path.
- **Bury ORE is really buried (v1.2).** The SKR lot leaves only after the buyer's ORE
  went through ORE `bury`: the vault lost exactly the payment and the ORE supply fell
  by the burned 90%, re-read after the CPI.

### Audit checklist: the 16 bug classes

Each row lists the concrete check (file / function) and the tests that fail if it is
removed. Test paths are relative to `tests/tests/`. The table covers the v1.1 core; the
same classes for the v1.2 SKR instructions (signers, SPL Token owners, vault and seat
relationships, constant CPI targets, duplicates, seeds, init-only accounts, closes,
u128 payouts, the post-`bury` re-reads, Token-2022 look-alikes, griefing) are mapped
check by check, with their tests, in
[`docs/THREAT_MODEL.md`](../../docs/THREAT_MODEL.md) §7 "SKR checks as built".

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
  With SKR (v1.2), a forged BREAK forfeits only this rig's own Stack seats and Focus
  Bonds; it cannot move SKR anywhere but the Bury lot.
- **Cranker.** It chooses which heartbeats to land and when. It cannot choose amounts,
  tiles or authority, and it is reimbursed only for real deploys. With SKR (v1.2) it
  can withhold a seat's `stack_checkin` (the seat records a gap), but anyone else can
  land it; it cannot touch bonds, gifts or the Bury lot.
- **Bury auction buyer (v1.2).** It chooses when to buy, so lots may sell cheaply
  after a long decay; it always pays the program's price in ORE that ORE burns (90%).
- **Governance.** It can change the registrar, `crank_fee` (≤ `executor_fee`) or
  `bury_bps` after 72 h, and pause immediately. It cannot move funds or change
  `executor_fee`.
- **Upgrade authority (beta).** This is the full program authority. Mitigate it with a
  Squads vault behind a 72 h timelock, then revoke it.

## Known limitations

See `INTERFACE.md` §10 and, for the SKR features, §11.12 (tables and their vaults are
never closed; in-person co-presence is not verified on-chain; caps and auction
constants are compile-time constants; SKR fuel is a client-side swap). Also:

- Stack is fail-closed on liveness: a round in which nobody lands the seat's
  `stack_checkin` is a gap, so the crank must check in every seated rig every round
  (4 verified seats or 8 observed seats per packet).
- The consumers follow v1.3: the crank (its builders and decoders against these
  vectors; it keeps the v1.3 governance events raw), the indexer (all 27 events and
  32 instructions) and the Android chain layer (every instruction a phone sends,
  byte for byte). `vectors/CROSSCHECK.md` records the disagreements found while they
  were still on v1.1; they are resolved. The app has no screens yet for Stack, Gift,
  Revoke, Unfreeze or Claim.
- No physical device was available. Keystore signatures are simulated with p256 in
  Keystore's format (DER, then low-S raw).
