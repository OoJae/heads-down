# p256-introspect

Verify, from inside a Solana program, that the **current transaction** contains a
`Secp256r1SigVerify` precompile instruction that verified a given
`(33-byte compressed P-256 public key, message)` pair, for example a heartbeat
signed by an Android Keystore key.

- `no_std`, `#![forbid(unsafe_code)]`, no allocator, Pinocchio 0.11 types (`AccountView`, `ProgramError`).
- Nothing on the on-chain path panics on attacker input. The crate is built with
  `deny(clippy::arithmetic_side_effects, indexing_slicing, unwrap_used, expect_used, panic)`.
- Host helpers behind `features = ["client"]` (p256 0.14): DER to raw `r||s`, low-S,
  SEC1/SPKI to compressed key, and the precompile instruction builder.

Heads Down uses it so that every ORE deploy carries a fresh P-256 heartbeat from the
phone's hardware-backed key. It is written as a standalone crate so any program can
use it.

## What is checked, and why

| # | Check | Bug class it closes |
|---|-------|---------------------|
| 1 | The account passed as the instructions sysvar has address `Sysvar1nstructions1111111111111111111111111` | Spoofed sysvar. This is the Wormhole bug (Feb 2022): the deprecated `load_instruction_at` did not check this, so a forged account "proved" signatures that were never verified |
| 2 | The instruction at the caller-supplied index exists and has program id `Secp256r1SigVerify1111111111111111111111111` | Missing precompile, or a look-alike instruction |
| 3 | `1 <= num_signatures <= 8`, and all offset records are present | Malformed data (Agave's own limits) |
| 4 | **Every** record's `signature_instruction_index`, `public_key_instruction_index` and `message_instruction_index` is `0xFFFF` or the precompile's own index | Offsets pointing into another instruction. The precompile then verifies bytes elsewhere, while a naive reader returns unverified bytes from the precompile's own data |
| 5 | Every range is in bounds, computed with checked arithmetic | Out-of-bounds reads, `u16` wrap-around |
| 6 | `1 <= r <= n-1`, `1 <= s <= n/2` | High-S malleability (the precompile enforces this too; the check is repeated here) |
| 7 | Key prefix is `0x02`/`0x03`; the expected key and message match byte for byte | Wrong signer or wrong message |

The instructions sysvar is parsed by a fully bounds-checked reader
(`InstructionsSysvar`), not by pointer casts. A forged buffer can only produce an error.

What this crate does **not** do: freshness. It proves that a key signed a message. Your
program must bind a nonce, counter, slot or round into the message and store what it
has consumed. ECDSA signatures are malleable (`(r, n-s)`), and anyone can re-normalize
them, so never use the signature bytes as a replay key.

## Wire format (SIMD-0075)

```text
[0]        num_signatures: u8            1..=8 (Agave rejects 0 and > 8)
[1]        padding: u8                   ignored
[2 + 14*i] Secp256r1SignatureOffsets     7 x u16 LE, no padding between records:
             signature_offset, signature_instruction_index,
             public_key_offset, public_key_instruction_index,
             message_data_offset, message_data_size, message_instruction_index
[...]      payload                       64-byte r||s, 33-byte compressed key, message
```

`*_instruction_index == 0xFFFF` means "this instruction". The precompile checks
`1 <= r <= n-1` and `1 <= s <= n/2`, decodes the key, and verifies ECDSA over
**SHA-256(message)**.

Sources, read for this implementation:

- SIMD-0075, *Precompile for verifying secp256r1 sig.*: <https://github.com/solana-foundation/solana-improvement-documents/blob/main/proposals/0075-precompile-for-secp256r1-sigverify.md>
- Agave precompile, `agave-precompiles` 4.3.0 `src/secp256r1.rs` (`verify`, `get_data_slice`).
- `solana-secp256r1-program` 3.0.0 (constants, `Secp256r1SignatureOffsets`, the SDK builder layout).
- `solana-instructions-sysvar` 3.0.1 (`serialize_instructions`: sysvar layout).
- Where SIMD-0075 and Agave differ, the stricter Agave behaviour is followed. The SIMD
  pseudocode tolerates `num_signatures == 0` with 1-byte data; Agave rejects any 0.

Mainnet status, read from the feature accounts: `srremy31J5Y25FrAApwVb9kZcfXbusYMMsvTK9aWv5q`
(SIMD-0075) has been active since slot 345,600,000 (epoch 800).

## On-chain usage (Pinocchio)

```toml
[dependencies]
p256-introspect = { path = "../../crates/p256-introspect" }
```

```rust
use p256_introspect::{verify_secp256r1_signature, with_secp256r1_instruction};

// accounts[0] is whatever the caller claims is the instructions sysvar; the crate checks it.
verify_secp256r1_signature(
    &accounts[0],
    precompile_ix_index,   // u16: where the client placed the precompile instruction
    0,                     // u8: entry within that instruction
    &rig.p256_pubkey,      // [u8; 33], registered earlier
    &expected_message,     // rebuilt on-chain from state, never trusted from the caller
)?;

// Batch form (for example one dig covering N rigs):
with_secp256r1_instruction(&accounts[0], precompile_ix_index, |ix| {
    for (i, rig) in rigs.iter().enumerate() {
        ix.expect_entry(i as u8, &rig.pubkey, &rig.expected_message)?;
    }
    Ok(())
})?;
```

The precompile instruction may come before or after the instruction that inspects it.
If it fails, the whole transaction fails.

### Error codes

`ProgramError::Custom(0x2560_0000 | code)`. The codes read as "P256" in logs
(`custom program error: 0x25600007`).

| Code | Error | Code | Error |
|---|---|---|---|
| `0x25600001` | InvalidInstructionsSysvar | `0x25600008` | OffsetOutOfBounds |
| `0x25600002` | MalformedInstructionsSysvar | `0x25600009` | SignatureIndexOutOfBounds |
| `0x25600003` | InstructionIndexOutOfBounds | `0x2560000a` | HighS |
| `0x25600004` | NotSecp256r1Instruction | `0x2560000b` | ScalarOutOfRange |
| `0x25600005` | InvalidSignatureCount | `0x2560000c` | InvalidPublicKeyEncoding |
| `0x25600006` | TruncatedOffsets | `0x2560000d` | PublicKeyMismatch |
| `0x25600007` | ForeignInstructionIndex | `0x2560000e` | MessageMismatch |

Precompile failures surface as `InstructionError::Custom(n)` on the precompile's own
instruction index: 0 InvalidPublicKey, 2 InvalidSignature, 3 InvalidDataOffsets,
4 InvalidInstructionDataSize.

## Host helpers (`features = ["client"]`)

| Android output | Precompile input | Helper |
|---|---|---|
| `Signature("SHA256withECDSA").sign()`: ASN.1 DER, `s` may be high | 64-byte `r\|\|s`, `s <= n/2` | `client::der_to_low_s_raw` (or `der_to_raw` + `normalize_low_s`) |
| `PublicKey.getEncoded()`: X.509 SubjectPublicKeyInfo, 91 bytes | 33-byte compressed | `client::spki_der_to_compressed` |
| `0x04 \|\| x \|\| y` | 33-byte compressed | `client::compress_public_key` |
| 1..=8 `(sig, key, msg)` | instruction data (indices `0xFFFF`) | `client::build_instruction_data` |
| n/a | pre-flight check that mirrors the precompile | `client::verify_like_precompile` |

The builder emits the same per-entry layout as the SDK's
`new_secp256r1_instruction_with_signature` (key at 16, signature at 49, message at 113
for one entry). The instruction takes no accounts.

## Android Keystore recipe (Kotlin)

The heartbeat key is a non-exportable P-256 key in the TEE, or in StrongBox where the
device has one. The phone signs the **raw message bytes**: `SHA256withECDSA` hashes them
with SHA-256, and the precompile hashes the same bytes with SHA-256. Do not pre-hash,
and do not use `NONEwithECDSA`.

### 1. Generate the key (with attestation)

```kotlin
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

const val RIG_KEY_ALIAS = "heads_down_rig_p256_v1"

private fun rigKeySpec(challenge: ByteArray, strongBox: Boolean) =
    KeyGenParameterSpec.Builder(RIG_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .setDigests(KeyProperties.DIGEST_SHA256)
        // Heartbeats are signed every ORE round with the phone face-down and locked.
        .setUserAuthenticationRequired(false)
        .setUnlockedDeviceRequired(false)
        // Key Attestation: the registrar's single-use nonce.
        .setAttestationChallenge(challenge)
        .setIsStrongBoxBacked(strongBox)
        .build()

fun generateRigKey(challenge: ByteArray): KeyPair {
    val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
    return try {
        kpg.initialize(rigKeySpec(challenge, strongBox = true)); kpg.generateKeyPair()
    } catch (e: StrongBoxUnavailableException) {        // most phones, likely including the Redmi 14C
        kpg.initialize(rigKeySpec(challenge, strongBox = false)); kpg.generateKeyPair()
    }
}
```

`packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)` tells you in
advance whether to try StrongBox at all.

Record where the key actually lives. `KeyInfo.securityLevel` (API 31+) returns
`SECURITY_LEVEL_STRONGBOX`, `SECURITY_LEVEL_TRUSTED_ENVIRONMENT` or
`SECURITY_LEVEL_SOFTWARE`. Send `keyStore.getCertificateChain(RIG_KEY_ALIAS)` to the
registrar, which verifies the chain up to Google's attestation root and reads the
KeyDescription extension (OID `1.3.6.1.4.1.11129.2.1.17`).

### 2. Export the compressed public key (33 bytes)

```kotlin
import java.math.BigInteger
import java.security.interfaces.ECPublicKey

/** Unsigned big-endian, left-padded to exactly 32 bytes. */
fun to32(x: BigInteger): ByteArray {
    val b = x.toByteArray()                       // two's complement: may carry a 0x00 sign byte
    return when {
        b.size == 32 -> b
        b.size == 33 && b[0] == 0.toByte() -> b.copyOfRange(1, 33)
        b.size < 32 -> ByteArray(32 - b.size) + b
        else -> throw IllegalArgumentException("scalar wider than 32 bytes")
    }
}

fun compressedPublicKey(pub: ECPublicKey): ByteArray {
    val prefix: Byte = if (pub.w.affineY.testBit(0)) 0x03 else 0x02
    return byteArrayOf(prefix) + to32(pub.w.affineX)
}
```

`pub.encoded` is the 91-byte SubjectPublicKeyInfo. The Rust `spki_der_to_compressed`
accepts it directly if you would rather convert server-side.

### 3. Sign a heartbeat, convert DER to raw, normalize to low-S

```kotlin
import java.security.KeyStore
import java.security.Signature

private val N = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
private val HALF_N = N.shiftRight(1)

fun signHeartbeat(message: ByteArray): ByteArray {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val entry = ks.getEntry(RIG_KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
    val der = Signature.getInstance("SHA256withECDSA").run {
        initSign(entry.privateKey)
        update(message)          // raw bytes; SHA-256 is applied inside Keystore
        sign()                   // ASN.1 DER ECDSA-Sig-Value; s is NOT normalized
    }
    return derToLowSRaw(der)     // 64 bytes, ready for the precompile
}

/** SEQUENCE { INTEGER r, INTEGER s } -> r||s (32+32), with s := n - s when s > n/2. */
fun derToLowSRaw(der: ByteArray): ByteArray {
    // P-256 signatures are at most 72 bytes, so every DER length is short-form.
    require(der.size in 8..72 && der[0] == 0x30.toByte() && (der[1].toInt() and 0xFF) == der.size - 2)
    var i = 2
    fun readInt(): BigInteger {
        require(der[i] == 0x02.toByte())
        val len = der[i + 1].toInt() and 0xFF
        require(len in 1..33 && i + 2 + len <= der.size)
        return BigInteger(1, der.copyOfRange(i + 2, i + 2 + len)).also { i += 2 + len }
    }
    val r = readInt()
    var s = readInt()
    require(i == der.size)
    require(r.signum() > 0 && r < N && s.signum() > 0 && s < N)
    if (s > HALF_N) s = N.subtract(s)   // the precompile rejects high-S; about half of Keystore sigs are high
    return to32(r) + to32(s)
}
```

Low-S normalization needs no private key, so the crank or relayer can do it (the Rust
`client::normalize_low_s`). Doing it on the phone keeps the heartbeat self-contained.

### `setUserAuthenticationRequired` vs background heartbeats

- `setUserAuthenticationRequired(true)` with a zero timeout needs a `BiometricPrompt` for
  every signature. Nobody can do that every ~78 s with the phone face-down.
- With a timeout (`setUserAuthenticationParameters(t, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)`),
  the key works only for `t` seconds after the last authentication. An 8-hour shift
  outlives any sensible window, so the rig would go cold in the middle of the night.
- `setUnlockedDeviceRequired(true)` blocks signing while the device is locked, and a
  face-down phone is locked.
- An auth-bound key is also permanently invalidated when the secure lock screen is
  removed, and by default on new biometric enrollment
  (`setInvalidatedByBiometricEnrollment`).

So the heartbeat key is **not** auth-bound. Authority is split instead:

- Anything that raises risk (funding, caps, expiry, unfreezing) is signed by the wallet
  through MWA (Seed Vault or wallet biometrics).
- The P-256 key can only heartbeat, arm within wallet-signed caps, tighten, break or
  freeze. The on-chain program enforces this.
- If the key is extracted, the worst case is the armed, wallet-capped budget buying ORE
  at or below the capped production cost. The TEE (or StrongBox) plus attestation makes
  extraction the hard part.

If a future flow wants a user-present P-256 signature (for example a "freeze now" button),
use a **second** key with `setUserAuthenticationRequired(true)`, and do not reuse the
heartbeat key.

## Test vectors

RFC 6979 A.2.5 (P-256, SHA-256), private key
`C9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721`, message `"sample"`:

| | value |
|---|---|
| compressed key | `03` `60FED4BA255A9D31C961EB74C6356D68C049B8923B61FA6CE669622E60F29FB6` |
| r | `EFD48B2AACB6A8FD1140DD9CD45E81D69D2C877B56AAF991C34D0EA84EAF3716` |
| s (as published, **high-S**) | `F7CB1C942D657C41D436C7A1B6E29F65F3E900DBB9AFF4064DC4AB2F843ACDA8` |
| s after normalization (`n - s`) | `0834E36AD29A83BF2BC9385E491D6099C8FDF9D1ED67AA7EA5F51F93782857A9` |

The published signature is rejected by the precompile, and the normalized one is
accepted. Both facts are asserted: in `tests/client.rs` against p256, and in
`spikes/secp256r1` against the real Agave precompile (OpenSSL) in LiteSVM.

Real Keystore vectors from the Redmi 14C are pending: no device was connected when this
was built. The Kotlin above is the recipe to capture them.

## Tests

```sh
cargo test --release --all-features          # 30 tests
cargo +1.95 clippy --all-features --all-targets
```

- `tests/parser.rs`: sysvar layout, precompile layout, and every negative case: foreign
  index on each field, one foreign record poisoning the whole instruction, out-of-bounds
  and wrap-around offsets, high-S at the exact `n/2` boundary, zero and out-of-range
  scalars, key prefixes, and a count of 0 or 9.
- `tests/fuzz_no_panic.rs`: 400k randomized mutations of sysvar and precompile bytes. No
  panics, and anything accepted still satisfies the invariants.
- `tests/constants.rs`: program ids against base58, and the curve constants against p256
  scalar arithmetic.
- `tests/client.rs`: the RFC 6979 KAT, Keystore-style DER round trips (about half
  high-S), left padding of short DER components, strict DER rejection, SPKI and off-curve
  keys, and builder output parsed by the on-chain parser for 1 to 8 signatures.

End-to-end tests against the real precompile, with measurements, are in
[`spikes/secp256r1`](../../spikes/secp256r1/README.md).
