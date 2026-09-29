# sgt-verify

In-program verification of the **Seeker Genesis Token (SGT)** for Pinocchio
programs: given a token account, a mint and the wallet that should hold it,
decide whether that wallet holds a genuine SGT, and return the SGT mint and
member number.

- `no_std`, no allocator, `#![forbid(unsafe_code)]`. The library denies
  clippy's `arithmetic_side_effects`, `indexing_slicing`, `panic`,
  `unwrap_used` and `expect_used`, so nothing an attacker writes can panic
  it.
- About **1,900 CU**: the whole probe instruction (verify plus return data)
  costs 1,876 CU in LiteSVM.
- Every rejection has its own `SgtError`. It reaches the runtime as
  `ProgramError::Custom(0x5347_xxxx)`.
- Tested on real mainnet SGT bytes, a spoof suite built from those bytes,
  exhaustive bit-flip properties, and a LiteSVM suite. In that suite the
  real Token-2022 program is used to try to forge an SGT.

```toml
[dependencies]
sgt-verify = { path = "../crates/sgt-verify", features = ["mainnet"] }
```

```rust
use sgt_verify::{verify_sgt, SgtInfo};

// accounts: [token_account, sgt_mint, holder (signer), ...]
if !holder.is_signer() {
    return Err(ProgramError::MissingRequiredSignature);
}
let SgtInfo { mint, member_number, frozen } =
    verify_sgt(token_account, sgt_mint, holder.address())?; // SgtError -> ProgramError
// Key the device seat by `mint`, e.g. PDA [b"seeker", mint]. Never key it by wallet.
```

`probe/src/lib.rs` is a complete example program (about 50 lines).

## What a real SGT looks like

Everything here was read from mainnet and is pinned by tests
(`fixtures/`, `tests/real_fixtures.rs`).

**Mint** (450 bytes, owned by Token-2022 `TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb`):

| field | value |
|---|---|
| decimals / supply | 0 / 1 |
| mint authority, freeze authority | `GT2zuHVaZQYZSyQMgJPLzvkmyztfyXg2NJunqFp4p3A4` |
| `MetadataPointer` (18) | authority GT2zuH…, address **`GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te`** (the group) |
| `PermanentDelegate` (12) | GT2zuH… |
| `MintCloseAuthority` (3) | GT2zuH… |
| `GroupMemberPointer` (22) | authority GT2zuH…, address = **the mint itself** |
| `TokenGroupMember` (23) | mint = itself, group = **GT22s89…**, `member_number` |

**Group** `GT22s89…` is a Token-2022 mint with supply 0. It carries a
`TokenGroup` extension: update authority GT2zuH…, max size 1,000,000, and size
121,035 at the time of fetching. It also carries `TokenMetadata` ("Seeker
Genesis Token", `SeekerGT`).

**Holder account** is the Token-2022 ATA of the holder (170 bytes,
`ImmutableOwner`), `amount = 1`, **state `Frozen`**.

**Issuance** (mainnet tx
`33pVjopV6gAwk4DdNmPjoRdXx3nfAK1yq4xhFGMiG5GsFRs97duJYs7yGz7jLJ65xQKxhfTjq4nKV3V7pQngCroh`) follows these steps: `createAccount(374)`,
then `initializeMetadataPointer`, `initializePermanentDelegate`,
`initializeMintCloseAuthority`, `initializeGroupMemberPointer`,
`initializeMint`, `initializeMember` (which reallocates the mint to 450),
`createIdempotent` ATA, `mintToChecked(1)` and finally **`freezeAccount`**.

**A permissioned move** between a user's Seed Vault wallets (mainnet tx
`4QEZJiQTe8vDDthW4YkwNopfWjXbWNyKuWZ255KCMiWi9G6TPqyUbrfJyxed8WFcwZ67Poe4UxRw4dLJUAit76DX`) is `thawAccount(source)`, then `transferChecked` signed by the
**permanent delegate**, then **`freezeAccount(destination)`**.

## Checks

`verify_sgt(token_account, mint, expected_owner)` runs these checks in order.
Each row lists the error it returns.

| # | check | why | error |
|---|---|---|---|
| 1 | token account owned by Token-2022 | never read bytes another program wrote (legacy SPL Token, attacker programs) | `TokenAccountNotToken2022` |
| 2 | mint owned by Token-2022 | same | `MintNotToken2022` |
| 3 | token account ≠ mint | duplicate-account confusion | `DuplicateAccount` |
| 4 | token account layout: length ≥ 165 and ≠ 355 (Multisig); if extended, byte 165 = `Account` (2); well-formed TLV | type cosplay; malformed data | `TokenAccountInvalidLength`, `TokenAccountTypeMismatch`, `MalformedTlv`, … |
| 5 | state is `Initialized` or `Frozen`; any other byte is rejected | uninitialized or undefined state | `TokenAccountNotInitialized`, `TokenAccountInvalidState` |
| 6 | `token_account.mint == mint` | a token account for a different mint | `TokenAccountMintMismatch` |
| 7 | `token_account.owner == expected_owner` | someone else's SGT | `TokenAccountOwnerMismatch` |
| 8 | not native, `amount == 1` | wrapped SOL; empty or inflated balance | `NativeTokenAccount`, `AmountNotOne` |
| 9 | mint layout: 82 bytes or extended (> 165, ≠ 355), padding 82..165 all zero, byte 165 = `Mint` (1), `is_initialized == 1`, `COption` tags ∈ {0, 1}, well-formed TLV, at least one extension | type cosplay (a live token account cannot pass: its state byte sits in the mint padding); malformed data | `MintInvalidLength`, `MintPaddingNotZero`, `MintAccountTypeMismatch`, `MintNotInitialized`, `MintInvalidOption`, `MintMissingExtensions` |
| 10 | mint authority = freeze authority = `Some(GT2zuH…)` | consistency with issuance | `MintAuthorityMismatch`, `FreezeAuthorityMismatch` |
| 11 | decimals 0, supply 1 | NFT shape; rejects the group mint itself (supply 0) | `DecimalsNotZero`, `SupplyNotOne` |
| 12 | **`TokenGroupMember { mint: self, group: GT22s89…, member_number ≥ 1 }`** | **the anchor, see below** | `MissingGroupMember`, `GroupMismatch`, `GroupMemberMintMismatch`, `InvalidMemberNumber` |
| 13 | `GroupMemberPointer { GT2zuH… → self }` | Token-2022 requires it for membership; real SGTs point at themselves | `MissingGroupMemberPointer`, `GroupMemberPointerMismatch` |
| 14 | `PermanentDelegate == GT2zuH…` | consistency; see threat model | `MissingPermanentDelegate`, `PermanentDelegateMismatch` |
| 15 | `MetadataPointer { GT2zuH… → GT22s89… }` | consistency (this is the check ORE relied on) | `MissingMetadataPointer`, `MetadataPointerMismatch` |
| 16 | `MintCloseAuthority == GT2zuH…` | consistency | `MissingMintCloseAuthority`, `MintCloseAuthorityMismatch` |

The layouts and `ExtensionType` numbers are transcribed from spl-token-2022
9.0.0 (`src/pod.rs`, `src/extension/mod.rs`) and spl-token-group-interface
0.6.0. `src/layout.rs` cites each value.

### TLV parsing

`src/tlv.rs` walks the TLV entries the same way Token-2022's
`get_tlv_data_info` does, so the verifier and the token program always agree
on which extensions exist:

- Fewer than 2 bytes left is realloc slack and counts as the end.
- Type `0` also ends the walk. Bytes after it are invisible to both.
- A type without a length, or a value that runs past the end of the data, is
  `MalformedTlv`.

The walk is **stricter** than Token-2022 in two ways, and both reject only
bytes Token-2022 never writes:

- A repeated extension type is an error, so "first match wins" can never
  hide a second value.
- At most 32 entries are walked, which bounds compute.

Every read is a checked slice operation.

## Threat model

### The only unforgeable fact is group membership

A Token-2022 mint's fields are much easier to fake than they look. Anyone can
build all of the following **without Solana Mobile's key**:

- **Pointer and delegate extensions.** The authorities and targets in
  `MetadataPointer`, `GroupMemberPointer`, `PermanentDelegate` and
  `MintCloseAuthority` are plain instruction data. Initializing them needs no
  signature from the key they name.
- **`mint_authority = GT2zuH…`.** The forger initializes the mint with their
  own authority, mints 1 token to themselves, then calls
  `SetAuthority(MintTokens → GT2zuH…)`. That call needs only the *current*
  authority's signature.
- **`freeze_authority = GT2zuH…`** is set at initialization.

`TokenGroupMember` is different. Token-2022 writes it only in
`InitializeMember`, and that instruction:

- requires the **group's update authority** to sign (GT2zuH… for GT22s89…);
- mutates the group account, so the group must be a Token-2022 mint that
  carries a `TokenGroup` extension;
- assigns `member_number` by incrementing the group size.

So `TokenGroupMember.group == GT22s89…` inside a mint owned by Token-2022 can
only exist if Solana Mobile signed. Every other check in the table is a
consistency check layered on top of that anchor.

ORE's `claim_seeker` (see Credits) checked the mint owner, the caller's ATA
holding 1, `mint_authority == GT2zuH…` and `MetadataPointer {GT2zuH… →
GT22s89…}`, but **not** group membership. The LiteSVM suite
(`litesvm-tests/tests/mainnet.rs`) forges such a mint with the real
Token-2022 program. It passes a model of those checks. `verify_sgt` rejects
it as `MissingGroupMember`, or as `GroupMismatch` once the forger joins a
group of their own. The same suite also shows that `InitializeMember` into
the real GT22s89… fails inside Token-2022 with `IncorrectUpdateAuthority`.

### Solana Mobile controls every SGT: always key by mint

GT2zuH… is the permanent delegate, the freeze authority and the mint close
authority of every SGT:

- **It can move or burn any SGT at any time** (permanent delegate).
- **Holders cannot transfer SGTs themselves.** Every holding is frozen at
  rest, and moves are permissioned: thaw, transfer by the permanent
  delegate, then freeze. Solana Mobile uses this to move an SGT between a
  user's own Seed Vault wallets.
- The holding wallet is therefore **not a stable identity**. The mint is:
  one mint per device, and the mint keeps its address across moves. Key
  seats, rigs, gifts and escrows by `SgtInfo::mint`. After a move, the new
  wallet verifies under the same mint and the old ATA fails with
  `AmountNotOne` (the LiteSVM test
  `permissioned_move_transfers_the_seat_to_the_new_wallet`). Heads Down's
  `rebind` should re-verify the new holder and re-point the existing seat.
- Verification is **point-in-time**. An SGT verified in one transaction can
  be moved or burned before the next one. Re-verify wherever holding the SGT
  still matters (seat-gated actions, gift claims), and design so that a
  moved SGT only transfers control of state, never lets anyone withdraw.
- GT2zuH… and GT22s89… are the root of trust. If Solana Mobile's key is
  compromised, SGTs can be minted at will, and no verifier can detect it.

### Frozen accounts are accepted

The original brief asked to reject frozen token accounts. On mainnet
**every** SGT holding is frozen, so that rule would reject every real SGT
(`tests/real_fixtures.rs::real_sgt_holdings_are_frozen_and_accepted`). The
verifier accepts `Frozen` and `Initialized`, rejects `Uninitialized` and
undefined state bytes, and reports the state in `SgtInfo::frozen`. A program
that wants holdings frozen at rest can require `frozen == true`.

### Deliberately not checked

- **ATA canonicality.** ORE required the ATA. Ownership by Token-2022 plus
  the `mint`, `owner` and `amount` fields already settle who holds the SGT.
  Real holdings are ATAs (a test checks this).
- **The token account's delegate and close authority.** A delegate cannot
  move a frozen token, and a close authority cannot close an account while
  its amount is 1. The bit-flip test lists exactly which token-account bytes
  are free to change.
- **Extra mint extensions.** The verdict rests on the anchored facts, so an
  unrelated extension does not make a spoof. Duplicates and malformed
  entries are still rejected.
- **Whether the signer is a phone.** That is device attestation, a separate
  layer (Heads Down's Keystore P-256 heartbeat).

## Trust anchors and features

| build | group / authority | how |
|---|---|---|
| default or `features = ["mainnet"]` | GT22s89… / GT2zuH… (hardcoded) | the only code path |
| `features = ["test-group"]` | `SGT_VERIFY_TEST_GROUP` / `SGT_VERIFY_TEST_AUTHORITY` at build time, falling back to public test keys | constants swapped at compile time |

- `test-group` **replaces** the anchors. It never adds a second set: a
  test-group build rejects real SGTs (`MintAuthorityMismatch`).
- `mainnet` together with `test-group` is a `compile_error!`. Enable
  `sgt-verify/mainnet` in the mainnet build of your program, and feature
  unification can never slip test anchors into it.
- A malformed override is a compile-time error (const evaluation of
  `Address::from_str_const`).
- The fallback keys `PUBLIC_TEST_GROUP` and `PUBLIC_TEST_AUTHORITY` come from
  **published** seeds (`testkit::PUBLIC_TEST_*_SEED`). Anyone can mint
  "SGTs" for them. Use them only for local tests. For devnet, set the
  environment variables to keys you control, and assert
  `!sgt_verify::anchors::USING_PUBLIC_TEST_ANCHORS` in that build.

A typical program wiring:

```toml
[features]
mainnet = ["sgt-verify/mainnet"]
devnet  = ["sgt-verify/test-group"]
```

## Testing without a Seeker

There are four ways to test without a Seeker. All of them produce accounts
with the real SGT extension set:

1. **LiteSVM with real Token-2022 instructions** (feature `std`):
   - `testkit::create_test_group` creates a group mint you control.
   - `testkit::issue_test_sgt` replays Solana Mobile's issuance transaction
     step for step, freeze included.
   - `testkit::move_test_sgt` replays a permissioned move.

   The resulting mint and ATA bytes are identical to the testkit's synthetic
   bytes (`litesvm-tests/tests/test_group.rs`).
2. **Synthetic accounts** (feature `std`): `testkit::sgt_mint_bytes` and
   `testkit::sgt_token_account_bytes` produce the exact 450- and 170-byte
   layouts. You can inject them with `LiteSVM::set_account` or a Surfpool
   `surfnet_setAccount` cheatcode. Given the mainnet anchors and a real mint's
   address and member number, they reproduce the on-chain bytes exactly
   (`tests/testkit.rs`).
3. **A local validator or devnet**, with only the CLI:

   ```sh
   scripts/make-test-sgt.sh http://127.0.0.1:8899   # or https://api.devnet.solana.com
   ```

   The script creates (once) your test group and authority keys in
   `.test-sgt/` (gitignored). It then issues a member SGT to a holder, mints
   and freezes it, and verifies the result with a test-group build. It prints
   the `SGT_VERIFY_TEST_*` values to build your devnet program with. It was
   checked against `solana-test-validator` (Agave 4.1.2, spl-token-cli
   5.6.1); consecutive runs issued members #1 and #2.
4. **Checking any account pair offline**:

   ```sh
   solana account <MINT> --output json > mint.json
   solana account <TOKEN_ACCOUNT> --output json > ta.json
   cargo run --example verify_account_dump -- mint.json ta.json <HOLDER>
   ```

## Tests

```sh
scripts/test-all.sh
```

The script runs every step below. The results are from the last run:

| suite | what | result |
|---|---|---|
| `src/tlv.rs` unit | TLV walker edge cases | 9 passed |
| `tests/real_fixtures.rs` | both real SGTs verify through `verify_sgt_raw` and through `verify_sgt` on a host `AccountView`; parser equals the RPC's `jsonParsed` field by field; group premise; frozen | 10 passed |
| `tests/spoof.rs` | 45 mutations of real bytes, each with its exact error; **every-bit flip**; 40k randomized corruptions | 45 passed |
| `tests/testkit.rs` (`std`) | synthetic bytes equal mainnet; ATA derivation finds the real holders | 5 passed |
| `tests/test_group.rs` (`test-group,std`) | anchors swapped; mainnet SGTs rejected | 5 passed |
| `litesvm-tests` mainnet probe | real SGTs on chain; impostor signer, non-signer, mutated bytes, legacy owner, duplicate account; Token-2022 forgeries | 9 passed |
| `litesvm-tests` test-group probe (sigverify on) | issue, count members, byte identity, synthetic injection, permissioned move, mainnet rejection | 6 passed |

The every-bit-flip property checks all 3,600 single-bit mutations of the real
mint. The only ones that still verify fall in the 8 member-number bytes, so
every other byte of the mint is pinned by some check. The same test on the
token account shows that mint, owner, amount, state, account type and TLV
lengths are all pinned.

The LiteSVM step needs the SBF probes (`scripts/build-sbf.sh`, cargo-build-sbf
from Agave 4.1.2, platform-tools v1.54). It also needs rustc ≥ 1.97.1,
because litesvm 0.17's agave 4.3 dependencies declare that `rust-version`.
`test-all.sh` uses `cargo +1.97.1` unless `LITESVM_TOOLCHAIN` says otherwise.

### Fixtures

`scripts/fetch_fixtures.py` (Python 3, standard library only) refreshes
`fixtures/`. It fetches:

- member #20, the example from Solana Mobile's docs;
- member #121,035, the newest at fetch time;
- their current holder accounts;
- the group.

Holder discovery tries `getTokenLargestAccounts` first. The public RPC
rate-limits that call, so the script falls back to scanning the mint's
transactions and then re-reads the account to confirm it still holds the SGT.
The manifest records the RPC's own spl-token-2022 parse of every account,
and the Rust tests use it as an independent oracle.

## Credits

- ORE's `claim_seeker` instruction by Regolith Labs
  (github.com/regolith-labs/ore):
  - `program/src/claim_seeker.rs` at commit `037aa5e480` ("seeker",
    2025-09-26);
  - last revision `e42a12c8ba`;
  - removed in `3b03981e9f` (2025-11-06).

  It was the first in-program SGT check. This crate keeps its mint-authority
  and metadata-pointer checks and adds the group-membership anchor that makes
  it unforgeable.
- Solana Mobile's docs:
  - [Seeker Genesis Token](https://docs.solanamobile.com/solana-mobile-stack/seeker-genesis-token.md)
  - [Detecting Seeker users](https://docs.solanamobile.com/recipes/general/detecting-seeker-users.md)

Licensed Apache-2.0.
