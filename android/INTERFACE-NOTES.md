# Interface notes from the Android app

**The program contract is `programs/heads-down/INTERFACE.md` v1.1 and the vectors in
`programs/heads-down/vectors/`.** They supersede the instruction layouts, account lists and
proposals this file used to record (the attestation encoding, the P-256 tail order, the wallet
path account order, `end_shift` and `arm_shift` without the ORE Board, break reasons 7 and 8, and
`plan_flags` bit1 are all settled there, and the app now follows them byte for byte:
`core/chain` `GoldenInstructionsTest`, `GoldenMessagesTest`, `RegistrarVoucherTest`).

What follows is only what the program contract does not cover: the shared contracts with the
crank and the indexer, the registrar flow as the phone runs it, and the clock-in composition.

## 1. Phone → crank intake (contract A)

WebSocket at `/ws` (the crank also accepts `/v1/heartbeats`), `wss` only (`ws://` to loopback in
the `localdev` build), JSON text frames. Integers are exact JSON numbers (the crank also accepts
decimal strings); `sig64` is standard base64 of the 64-byte low-S `r‖s`; `rig` is base58.

```json
{"type":"heartbeat","rig":"<base58>","counter":42,"shift_id":7,"round_id":422700,"lease_rounds":1,"sig64":"<b64>"}
{"type":"break","rig":"<base58>","counter":43,"shift_id":7,"reason":1,"sig64":"<b64>"}
{"type":"freeze","rig":"<base58>","counter":44,"shift_id":7,"reason":3,"sig64":"<b64>"}
```

- Signed bytes: `SHA-256` of the v1.1 preimage (HEARTBEAT 94 B, BREAK / FREEZE 86 B).
- BREAK reasons sent: 1 lifted, 2 screen stayed on, 7 unplugged, 8 unlocked (the encoder refuses
  0 and 3). FREEZE always carries 3. PLAN is never sent.
- The crank lands phone-signed BREAK and FREEZE on-chain itself (P-256 path). The phone keeps a
  local JSON-lines record of every frame.
- Replies: `{"type":"ack","counter":N,"ok":true|false,"reason":"<code>"}` with code in
  `accepted, bad_signature, stale_counter, unknown_rig, rate_limited, malformed, lease_invalid`.
  The phone matches acks to the counters it sent (others are ignored), shows a current refusal
  on the rig card, debug-logs counter and code, and on `stale_counter` re-reads `Rig.hb_counter`.
  Other frame types (the crank's `status`) are ignored.
- If the crank is unreachable, nothing is signed into the void: ticks are reported undelivered,
  at most 8 fresh frames are queued for a reconnect, and the rig stops digging (fail-safe).
- BREAKs are not sent after the plan window ends: the program digs nothing then, and a BREAK
  would make `end_shift` record pickup or screen-on instead of `completed`.

## 2. Morning haul (contract B)

`GET /v1/rigs/{rig}/haul/latest` and `GET /v1/rigs/{rig}/haul/{shift_id}` → `HaulSummary`; 404 when
there is no finished shift. Parsed strictly by `core/chain` `IndexerHaulClient`:

- integers as numbers or decimal strings; `ore_mined_atoms` as integer atoms or as ORE with up to
  11 decimals; prices as decimal strings (rounded to whole lamports for display);
- a summary for another rig, a `dug_mask` beyond 25 squares, a `winning_square` outside 0..24
  (null is allowed: not reset yet), or a non-https explorer link is refused or dropped;
- `first_pickup_ts` is filled from the phone's shift journal (first lift or unlock after going
  dark, or the shift ended by hand), since it is not observable on-chain;
- `simulated: true` is labelled on screen and never pushed to the widget.

## 3. Registrar (registrar/INTERFACE-NOTES.md N1, N7)

1. `POST /siws/nonce`. The phone refuses an answer for another domain, a URI that is not https on
   the app's domain, a version other than 1, or a chain list without this build's chain id
   (`solana:mainnet`, `solana:devnet`, or `solana:localnet` for localdev).
2. MWA Sign In With Solana with `nonce`, `uri`, `issued_at`, `expiration_time`, `statement`,
   `version` and the chain id copied verbatim.
3. `POST /siws/verify` → a session token (memory only, never logged).
4. `GET /attest/challenge` (Bearer) → the phone re-derives
   `SHA-256("HDattest" ‖ authority ‖ nonce)` and refuses a mismatch.
5. The rig key is generated with that challenge; `POST /attest` sends the certificate chain, the
   session token (body and Bearer) and the nonce.
6. Level 1/2: the 223-byte Ed25519SigVerify instruction is checked against the HDreg preimage
   rebuilt from this program, wallet, key, level and expiry, and stored. Level 0, a refused chain,
   an unreachable registrar or a declined sign-in: guest (`has_attestation = 0`).

The Ed25519 signature itself is checked by the precompile in the clock-in transaction (Android
31-32 ship no Ed25519 verifier); the phone checks the structure, the message and that the signing
key is `Config.registrar`.

## 4. Clock-in transaction (one wallet approval)

```
[ComputeBudget limit+price]?  only if a priority fee is configured (0 by default)
[end_shift]?                  Rig.shift_open (@336); writes the ShiftLog of the current shift_id
[ed25519 voucher] [rotate_key]?   the Rig exists with another key, or a guest rig gets its voucher
[ORE automate]?               see below; never for focus-only
[ed25519 voucher] [register_rig]? the Rig does not exist yet
set_caps                      cap_round = dig + executor_fee; shift / week + one fee per dig round
arm_shift (wallet)            window = [now, now + policy window]; plan_flags bit0 focus, bit1 day
```

- ORE automate: `executor = Executor PDA`, strategy 2, `fee = Config.executor_fee` (@80),
  `reload = 1`, per-tile `amount = dig_lamports / (split + solo)` (floored),
  `deposit = max(0, cap_shift − automation.balance)`. Skipped when nothing would change.
- The voucher goes in only when it covers this wallet and key, was signed by `Config.registrar`
  and expires at least 600 slots after the current slot; `ed25519_ix` is its absolute index.
- The worst case (every optional instruction, voucher included) fits one legacy packet.
- After confirmation (`err == null` only) the phone re-reads the Rig: `shift_id` is
  authoritative, and the local counter is raised to `hb_counter`.

## 5. Open points for the other teams

- **Clock-out on-chain.** Ending a shift on the phone sends no BREAK (a manual BREAK would cost
  the night's streak); digs stop when the last lease runs out (`lease_rounds` ORE rounds at most).
  A permissionless `end_shift` after the window and the lease (by anyone, for example the crank)
  seals the night as `completed`; the phone ends a still-open shift itself at its next clock-in.
- **ORE `max_production_cost`** is sent as `u64::MAX`. ORE does not enforce it, and it would
  compare the raw EMA, not the program's pot-adjusted `ema_ev`.
