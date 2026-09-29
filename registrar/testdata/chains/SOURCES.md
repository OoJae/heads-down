# Real Android Key Attestation chains (test vectors)

Copied unmodified from Google's attestation verification library,
<https://github.com/android/keyattestation> at commit
`55c35040a1b5b72e6d63bfb150c5c68a175c1462` (directory `testdata/`), licensed Apache-2.0
(`LICENSE-android-keyattestation`). Each `.pem` is a full chain, **leaf first, root last**; each
`.json` is Google's own decoding of the leaf's KeyDescription, used by our tests as the expected
values.

| File here | Upstream path | Provisioning / root | What it exercises |
|---|---|---|---|
| `frankel_sdk37_TEE_EC_2026.pem` | `testdata/frankel/sdk37/TEE_EC_2026.pem` | RKP, **Key Attestation CA1 (P-384)** | TEE EC P-256 key, attestation v500, locked + verified boot |
| `tegu_sdk36_SB_EC_2026_ROOT.pem` | `testdata/tegu/sdk36/SB_EC_2026_ROOT.pem` | RKP, Key Attestation CA1 | StrongBox EC key under the new root |
| `caiman_sdk36_TEE_EC_RKP.pem` | `testdata/caiman/sdk36/TEE_EC_RKP.pem` | RKP, RSA root | TEE EC key (Pixel 9 Pro) |
| `caiman_sdk36_SB_EC_RKP.pem` | `testdata/caiman/sdk36/SB_EC_RKP.pem` | RKP, RSA root | StrongBox EC key (Pixel 9 Pro) |
| `sony_xperia10iii_sdk33_TEE_EC.pem` | `testdata/sony-xperia10-iii/sdk33/TEE_EC.pem` | factory keys, RSA root (2016 cert) | non-Pixel OEM, expired factory intermediates (must still pass) |
| `blueline_sdk28_TEE_EC_NONE.pem` | `testdata/blueline/sdk28/TEE_EC_NONE.pem` | factory keys, RSA root (2016 cert) | **unlocked bootloader** (`deviceLocked=false`, `UNVERIFIED`) |
| `marlin_sdk29_SOFTWARE_EC.pem` | `testdata/marlin/sdk29/TEE_EC_NONE.pem` | Android **software** attestation root | unknown root; `attestationSecurityLevel=SOFTWARE` |
| `akita_sdk34_SB_RSA_NONE.pem` | `testdata/akita/sdk34/SB_RSA_NONE.pem` | RKP, RSA root | an **RSA** leaf key (must be rejected: rigs are P-256) |
| `edited_tags_not_in_ascending_order.pem` | `testdata/invalid/tags_not_in_ascending_order.pem` | factory keys, RSA root | a real chain whose leaf was **edited** (AuthorizationList tags reordered) without re-signing. Google's `VerifierCliTest.run_invalidChain_outputsFailure` expects "Verification Failed"; we reject it with `BadSignature(0)`, while its KeyDescription still parses (tags flagged as unordered) |
| `quirk_non_der_bool_device_locked.pem` | `testdata/invalid/malformed_rot_device_locked.pem` | factory keys, RSA root | real device quirk: `deviceLocked` BOOLEAN encoded as `0x01` (Google logs and accepts as true) |
| `android_software_attestation_roots.pem` | `src/main/kotlin/SoftwareRoot.kt` (two PEM literals) | | the AOSP software attestation roots; used only to let a test reach the software-level policy |

None of these chains carries the `xyz.headsdown` package or a Heads Down challenge, so the
end-to-end tests configure the verifier with each vector's own package, signing digest and
challenge (from the `.json`). The Heads Down specific paths (challenge derivation from the server
nonce, package pinning, HTTP flow) are covered by synthetic chains built in the tests with
`rcgen` under a test-only trust anchor.

RKP intermediates are short-lived, so the tests verify each RKP vector at a fixed instant inside
its chain's validity window (see `tests/attest_vectors.rs`).
