# Spike 1(b): secp256r1 end to end

**Question.** Can a program verify, inside the current transaction, a P-256 signature
produced the way Android Keystore produces it (SHA256withECDSA, DER, high-S about half
the time), with every Wormhole-class substitution rejected? And how many verifications
fit in one transaction?

**Result.**

| Part | Status |
|---|---|
| On-chain verification (`crates/p256-introspect` + this program), LiteSVM 0.17 with the real Agave precompile | **PASS**: 16/16 end-to-end tests, 3/3 measurement tests |
| Same program on a real validator (`solana-test-validator` 4.1.2), signed by Node's OpenSSL | **PASS**: 9/9 |
| Signing on the Redmi 14C's Keystore (TEE level, attestation, sign latency) | **NOT RUN in this spike**: no device was connected. Outside it, on 10 October 2026, the app on a Redmi 14C signed with a key in the phone's TEE, the live registrar accepted that key's attestation, and its signatures passed the precompile on mainnet in five digs and a BREAK ([docs/MAINNET.md](../../docs/MAINNET.md)). Sign latency was not measured and no vector was exported. The Kotlin recipe is in [`crates/p256-introspect/README.md`](../../crates/p256-introspect/README.md#android-keystore-recipe-kotlin); see "Still to do on the device" below |

## Layout

```text
program/           Pinocchio SBF program (11.8 KB), instructions:
                     0 verify_heartbeat(precompile_ix_index, signature_index, expected_pubkey, expected_message)
                     1 verify_batch(precompile_ix_index, [(pubkey, message)])
tests/tests/       LiteSVM suites: secp256r1_e2e.rs (behaviour), capacity.rs (measurements)
validator-check/   the same attacks against solana-test-validator, with a Node signer
run-tests.sh       build + LiteSVM suites
```

## Run

```sh
./run-tests.sh                 # cargo-build-sbf + cargo test (LiteSVM)
./validator-check/run.sh       # boots solana-test-validator on :18899, runs check.mjs
```

Environment notes, verified here:

- **LiteSVM 0.17 runs the secp256r1 precompile only with `features = ["precompiles"]`.**
  That feature compiles `src/precompiles.rs::load_precompiles`, which installs the program
  accounts, and the `InvokeContextCallback::{is_precompile, process_precompile}` overrides
  in `src/callback.rs`. Those dispatch to `agave-precompiles` 4.3.0, where secp256r1 is
  now unconditional (`feature: None`). This is the same OpenSSL code a validator runs. It
  builds vendored OpenSSL once (about 4 minutes).
- litesvm 0.17.0 depends on Agave 4.3.0 crates that declare `rust-version = 1.97.1`. The
  local toolchain is 1.96.0, so tests run with `cargo test --ignore-rust-version`. They
  compile and pass on 1.96; drop the flag once rustc is at least 1.97.1.
- litesvm 0.17 links `solana-transaction` **4.x** and `solana-system-interface` 3.x, not
  3.x/2.x. The test crate pins versions to match (`tests/Cargo.toml`).

## What the tests prove

Each negative test asserts the exact failing instruction index and error code, so it is
clear which layer rejected the attack.

| Case | Rejected by | Where |
|---|---|---|
| Valid Keystore-style signature (DER to raw to low-S) | accepted | `keystore_heartbeat_verifies_end_to_end` |
| Signature that was high-S out of "Keystore", normalized | accepted | `originally_high_s_keystore_signature_passes_after_normalization` |
| RFC 6979 A.2.5 vector (published high-S), normalized, through Agave's OpenSSL | accepted | `rfc6979_vector_verifies_in_the_agave_precompile` |
| Precompile placed *after* the verifying instruction | accepted; a bad one still sinks the tx | `precompile_may_come_after_the_verifying_instruction` |
| Wrong message | introspection: `MessageMismatch` (ix 1) | `wrong_message_is_rejected` |
| Wrong pubkey | introspection: `PublicKeyMismatch` (ix 1); claiming the victim key in the precompile fails the precompile (ix 0) | `wrong_pubkey_is_rejected` |
| High-S signature (the malleable twin `(r, n-s)`, a valid ECDSA signature) | precompile: `InvalidSignature` (ix 0); also `HighS` in the crate's own parser | `high_s_signature_is_rejected_by_the_precompile` |
| Offsets pointing into another instruction (precompile passes; a naive reader would read the victim's key and message) | introspection: `ForeignInstructionIndex` (ix 2) | `offsets_pointing_into_another_instruction_are_rejected` |
| Spoofed instructions sysvar: a perfect forged image, owned by System and also by `Sysvar111...` | introspection: `InvalidInstructionsSysvar` (ix 0) | `spoofed_instructions_sysvar_is_rejected` |
| Precompile missing (index is self, or past the end) | introspection: `NotSecp256r1Instruction` / `InstructionIndexOutOfBounds` | `missing_precompile_instruction_is_rejected` |
| Replay of round 42's signature for round 43's message | precompile (sig over wrong msg), introspection (`MessageMismatch`), precompile (malleated twin) | `replayed_signature_for_a_different_message_is_rejected` |
| Identical heartbeat in a new transaction | **accepted by this stateless spike**, by design | `stateless_spike_accepts_an_identical_replay_so_the_program_must_bind_a_counter` |

The last row marks the boundary of what signature verification can do. Freshness has to
come from state: `heads_down::dig` binds `ore_round_id` and a strictly increasing counter
into the message, and stores the counter in the Rig.

## Measurements

All from `tests/tests/capacity.rs` (`./run-tests.sh` prints them). The key numbers were
reproduced on `solana-test-validator`.

### Compute and fees

| What | Value |
|---|---|
| Precompile execution, charged to the tx CU meter | **0 CU** (8 signatures: 0) |
| `verify_heartbeat` (sysvar check, locate, parse, compare), 32- or 101-byte message | **477 CU** (LiteSVM and validator agree) |
| `verify_batch`, n = 1..8 entries | 511, 783, 1055, 1327, 1599, 1871, 2143, 2415 CU, so **~272 CU per extra entry** |
| Block-packing cost per secp256r1 signature | 4,800 CU (Agave `cost-model/src/block_cost_limits.rs`: `SECP256R1_VERIFY_COST = COMPUTE_UNIT_TO_US_RATIO * 160`) |
| Fee per secp256r1 signature | one extra `lamports_per_signature` (5,000), asserted: 1 sig costs 10,000; 8 sigs cost 45,000; validator 7 sigs cost 40,000 |

### Signatures per transaction

- **`max_signatures` per `Secp256r1SigVerify` instruction = 8.** 9 fails with
  `InvalidInstructionDataSize` (Custom 4). A transaction can carry several precompile
  instructions.
- Each signature costs `14 (offsets) + 33 (key) + 64 (sig) + L (message)` bytes.
- Measured with real wire serialization (wincode) and executed at the maximum.
  "precompile" means the only instructions are secp256r1. "+verify_batch" adds this
  spike's consumer instruction, which repeats every key and message, so it is a
  pessimistic lower bound.

| Format | Limit | Msg 32 B: precompile | Msg 32 B: +verify_batch | Msg 101 B: precompile | Msg 101 B: +verify_batch |
|---|---|---|---|---|---|
| legacy | 1232 | **7** (1173 B) | 4 | **5** (exactly 1232 B) | 2 |
| v0 (no ALT) | 1232 | **7** (1175 B) | 4 | **4** (5 would be 1234 B) | 2 |
| v1 | 4096 | **27** (4063 B, 4 precompile ixs) | 18 | **18** (4012 B) | 10 |

The formulas, checked to the byte:
legacy `166 + 6k + N(111+L)`, v0 = legacy + 2, v1 `178 + 6k + N(111+L)`,
with `k = ceil(N/8)` precompile instructions.

**v1 transactions (4096 bytes) are live on mainnet.** The feature account
`txv1aq4pp281K9um3tnPgkfX8UqtFT6wcVW3hNezGLL` (`enable_tx_v1`) reads activated at slot
447,120,000; mainnet was at slot ~451.7M when checked. LiteSVM 0.17 executes the v1
cases above. Not tested here: whether RPC providers, the TPU path and MWA wallets accept
a >1232-byte v1 transaction. That is spike 1(d).

### What this means for `heads_down::dig`

1. **Sign a 32-byte digest, not the 101-byte preimage.** Use
   `message = SHA-256('HDv1' || program_id || rig || round || counter || state || shift_id || lease_end)`,
   recomputed on-chain with the `sol_sha256` syscall. That is 143 instead of 212
   bytes per rig: 27 rather than 18 signatures fit in v1.
2. Transaction size is the binding constraint, not compute. Verifying 8 rigs costs about
   2.4k CU on the program side. The ORE `deploy` CPIs will dominate CU.
3. A rough per-tx estimate for a real dig (not measured; the ORE accounts come from spike
   1(a)): with ~12 shared ORE/system accounts and ~3 accounts per rig,
   - v1, static keys: `(4096 - 178 - 12*32) / (143 + 3*32 + ~8)` gives about **14 rigs**;
   - v0 with a lookup table: `(1232 - ~200) / (143 + 3 + ~8)` gives about **6 rigs**.

   Both are consistent with the spec's "6 to 10 rigs per dig".
4. Budget one extra 5,000-lamport signature fee per rig per round for the cranker. The
   crank's self-funding fee must cover it.
5. The same precompile entry can be read by two instructions in one transaction. `dig`
   must reject duplicate rigs and advance the counter, as the spec already requires.

## Still to do on the device (Redmi 14C)

No phone was attached to this spike, so it claims none of these. The app's first shift
on the Redmi 14C (10 October 2026, [docs/MAINNET.md](../../docs/MAINNET.md)) answers the
first in practice: the rig key is in the TEE, with attestation level 1 on its Rig on
mainnet. The others are still open as far as this repository records: no captured
chain, no latency figure, no exported vector, and no separate record of signing with
the screen off and the device locked.

- Generate the key with the Kotlin recipe and record `KeyInfo.securityLevel`. Expected:
  TEE, since StrongBox is unlikely on a Helio G81.
- Capture the attestation chain.
- Log `SHA256withECDSA` sign latency.
- Export 3 to 5 real `(compressed key, message, DER)` triples as test vectors and add them
  to `crates/p256-introspect/tests/`. Each should pass `der_to_low_s_raw` and
  `verify_like_precompile`, and pass through LiteSVM.
- Confirm signing works with the screen off and the device locked, with
  `setUserAuthenticationRequired(false)` and `setUnlockedDeviceRequired(false)`.
