# Registrar: interface notes for the lead

Where `registrar/` deviates from, or makes precise, `programs/heads-down/INTERFACE.md` and the
workstream brief. `INTERFACE.md` itself was not edited.

## N1. Attestation challenge cannot include the P-256 key (DEVIATION)

The brief says `attestationChallenge == SHA-256(authority || p256_pubkey || server-nonce)`. That is
impossible: Android takes the challenge in `KeyGenParameterSpec.Builder.setAttestationChallenge()`
**before** generating the key, and the attestation certificate is created with the key. No public
API re-attests an existing key with a new challenge. A challenge can never commit to its own public
key.

**Implemented:**

```
challenge = SHA-256( "HDattest"(8 ASCII) || authority(32) || nonce(16) )    -- 56-byte preimage
```

- `nonce` is 16 random bytes, issued by `GET /attest/challenge` to the SIWS-authenticated
  authority, single-use, with a 10-minute TTL. `POST /attest` must echo it (`"nonce"` field, an
  addition to the brief's request body).
- The key binding comes from the certificate itself. The leaf's SubjectPublicKeyInfo is the
  attested key, and the registrar requires it to equal the submitted `p256_pubkey_compressed`.
  This is strictly stronger than hashing the key into the challenge, because the key is certified
  by the TEE and not merely named by the client.
- The server nonce provides freshness. The authority is bound twice: by the challenge, and by the
  session (`session.sub == authority`, and the nonce is stored with its subject).

Android impact: `RigKeyManager.generate(alias, challenge)` already takes 1..128 bytes. Pass the 32
bytes of `challenge` (hex or base64 in the response).

## N2. Exact voucher preimage `HDreg` (PRECISION)

INTERFACE.md, `register_rig`: `("HDreg"|program|authority|p256|level u8|expiry_slot u64)`. The
exact bytes, **111 bytes, signed raw with Ed25519 (not SHA-256 first)**:

| Off | Size | Field |
|---:|---:|---|
| 0 | 5 | `48 44 72 65 67` ("HDreg") |
| 5 | 32 | program_id |
| 37 | 32 | authority (wallet) |
| 69 | 33 | p256_pubkey, SEC1 compressed (`02`/`03`) |
| 102 | 1 | level: 0 unattested, 1 TEE, 2 StrongBox |
| 103 | 8 | expiry_slot, u64 LE |

This differs on purpose from the P-256 messages, which are `SHA-256(preimage)` because the
secp256r1 precompile hashes again. Ed25519 has its own internal SHA-512.

## N3. Exact Ed25519SigVerify instruction (PRECISION, for the program team)

223 bytes, no accounts. It is byte-identical to
`solana_ed25519_program::new_ed25519_instruction_with_signature` (a test asserts this). The first 16
bytes are always

```
01 00  30 00  ff ff  10 00  ff ff  70 00  6f 00  ff ff
```

(1 signature; sig_off 48; pk_off 16; msg_off 112; msg_len 111; every `*_instruction_index` =
0xFFFF, meaning "this instruction"). Recommended `register_rig` / `rotate_key` check: the
instruction at the caller-given index has program id `Ed25519SigVerify111...`, `len == 223`, the
header equals the constant above, `data[16..48] == config.registrar`, and `data[112..223] ==` the
preimage rebuilt from the program's own values (signer authority, the p256 from ix data,
`crate::ID`), with only `level` and `expiry_slot` read from the ix data. Then require `level <= 2`
and `expiry_slot > Clock::slot`. A fixed header avoids all offset parsing and cannot point at
another instruction. Rust constant: `hd_registrar::voucher::IX_HEADER`.

## N4. Level semantics (PRECISION)

- `level = 2` only if both `attestationSecurityLevel` and `keyMintSecurityLevel` are StrongBox
  **and** the chain corroborates it (an RKP attestation certificate `O=StrongBox`, or a factory
  chain naming StrongBox). A mismatch is rejected, not downgraded.
- `level = 0` vouchers exist only when an operator enables `HD_SOFTWARE_KEY_POLICY=level0` or
  `HD_UNLOCKED_DEVICE_POLICY=level0`. The program should treat level 0 exactly like "no voucher"
  (it is an "unattested but same app and same wallet" statement).
- Key-parameter checks go beyond the brief: `origin == GENERATED` (imported keys rejected) and
  purposes a subset of {SIGN, VERIFY}. Real Pixels report `[SIGN, VERIFY]` for a SIGN-only request.

## N5. Voucher binds `authority`, not `rig` or `sgt_mint` (NOTE)

THREAT_MODEL.md TB4 describes the voucher as over "(sgt_mint or rig, P-256 key, level, expiry)".
Following INTERFACE.md, it binds the **authority**. The Rig PDA is `[b"rig", authority]`, so this
is equivalent for rigs. It is not tied to an SGT: tier and attestation are independent.

## N6. `expiry_slot` source (PRECISION)

`expiry_slot = finalized slot at issuance (RPC getSlot, commitment finalized) + HD_VOUCHER_TTL_SLOTS`
(default 6,480,000, about 30 days at 400 ms). If RPC is unreachable, `/attest` returns 503 and does
not guess. The program decides what an expired `attestation_expiry_slot` means after registration
(suggested: Stack joins require `attestation_expiry_slot > now`).

## N7. SIWS payload the Android client must send (ACTION for android/core/wallet)

`HeadsDownWallet.signIn` currently passes `uri = null`, `issuedAt = null`,
`expirationTime = null`. The registrar **requires** all three (domain and URI binding, time
window), so today's payload would fail with `uri_mismatch` / `timestamps_missing`. Copy these
verbatim from the `POST /siws/nonce` response: `nonce`, `uri` (`https://headsdown.xyz`),
`issued_at`, `expiration_time`, `statement`, `chain_ids[0]`, and `version = "1"`.

Chain id: `HeadsDownWallet` defaults to `Solana.Devnet` (`solana:devnet`), while the registrar
defaults to `solana:mainnet`. The devnet registrar must run with `HD_SIWS_CHAINS=solana:devnet`.

`POST /siws/verify` body: `{"signed_message": base64(SignInResult.signedMessage),
"signature": base64(SignInResult.signature)}`.

## N8. Session token format (NEW, for the heartbeat intake)

`hds1.<base64url claims>.<base64url HMAC-SHA256>`, claims
`{sub: base58 address, chain, iat, exp, jti}`. It is not in INTERFACE.md. Any service that accepts
these tokens (heartbeat intake) must share `HD_SESSION_SECRET` and reuse
`hd_registrar::session::SessionKey::verify`, or call the registrar. Default lifetime is 1 h. The
night service will need re-sign-in or a longer `HD_SESSION_TTL_SECS` (max 24 h).

## N9. Transparency log format (NEW, for the indexer / dashboard)

JSONL, one voucher per line, hash-chained (`entry_hash`, `prev_hash`). The formula is in
`README.md`. Public at `GET /attest/log`. The indexer can flag any on-chain `register_rig` whose
voucher signature does not appear in the log, which detects use of a leaked registrar key.

## N10. Android: never request device-ID attestation (NOTE)

Published transcripts contain the full chain. If the app ever called
`setDevicePropertiesAttestationIncluded(true)` or ID attestation, brand, model and IMEI tags would
be published. The registrar does not require them and flags their presence (`has_device_ids`), but
the app must not request them (docs/PRIVACY.md).
