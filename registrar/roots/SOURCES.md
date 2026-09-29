# Google Key Attestation trust anchors

Fetched on 2026-09-29 from the official Android documentation and API. They are
committed so the registrar never downloads trust anchors at runtime.

| File | Source | Key | SPKI SHA-256 |
|---|---|---|---|
| `google_hardware_attestation_root_rsa4096_2022.pem` | <https://developer.android.com/privacy-and-security/security-key-attestation#root_certificate> ("Root Certificates", first block) and <https://android.googleapis.com/attestation/root> | RSA-4096, subject `serialNumber=f92009e853b6b045`, valid 2022-03-20 to 2042-03-15 | `feb2ea7551ee316ed4bb443c8293b884dbfdea40b603ee3e4f4a897e4580fbae` |
| `google_key_attestation_ca1_p384_2025.pem` | same page, "will begin signing attestation certificate chains on February 1, 2026", and <https://android.googleapis.com/attestation/root> | ECDSA P-384, subject `CN=Key Attestation CA1, OU=Android, O=Google LLC, C=US`, valid 2025-07-17 to 2035-07-15 | `3ee44512a1af2beb39c889490c60ea3f82e43f5d5a5532f5ab9419f676cd07ec` |
| `google_hardware_attestation_root_rsa4096_2016.pem` | same page, "Previously Issued Root Certificates" | same RSA key as the 2022 root (expired 2026-05-24) | `feb2ea75...` |
| `google_hardware_attestation_root_rsa4096_2019.pem` | same page, "Previously Issued Root Certificates" | same RSA key | `feb2ea75...` |
| `google_hardware_attestation_root_rsa4096_2021.pem` | same page, "Previously Issued Root Certificates" | same RSA key | `feb2ea75...` |
| `attestation_root.json` | <https://android.googleapis.com/attestation/root> (JSON array of the two current root PEMs) | the 2022 RSA root and Key Attestation CA1 | |

The registrar trusts **public keys**, not certificates: the five PEMs reduce to exactly two
trust anchors (the RSA-4096 key and the P-384 key). `src/attest/roots.rs` pins both SPKI
hashes above, and a unit test fails if any PEM here changes its key.

Why keys and not certificates: Google's guidance on the same page is to "continue trusting
certificates that chain to the root with the subject SERIALNUMBER=f92009e853b6b045, regardless
of certificate validity period". Factory-provisioned chains still end in the 2016 root
certificate, which expired on 2026-05-24; its key is the same RSA key.

## Remote Key Provisioning (RKP) chains

RKP chains are `leaf -> attestation key (O=TEE|StrongBox, ProvisioningInfo extension) ->
Droid CA3 -> Droid CA2 -> root`. `Droid CA2` is signed either by the RSA root (RKP before
February 2026) or by `Key Attestation CA1` (from February 2026). The intermediates rotate every
few weeks, so they are **not** pinned: they arrive inside each chain and are verified up to one
of the two root keys, with their validity periods checked (Google: "It's critical, however, to
ensure that Remote Key Provisioning (RKP) certificates continue to have their validity period
checked"). Real RKP chains for both roots are in `../testdata/chains/` (see its `SOURCES.md`).

## Revocation list

Not committed (it changes daily): <https://android.googleapis.com/attestation/status>, fetched and
cached at runtime (`src/attest/revocation.rs`). On 2026-09-29 it served 1,757 entries, all
`REVOKED` (1,731 `KEY_COMPROMISE`, 26 `SOFTWARE_FLAW`), with `cache-control: max-age=86400`.
