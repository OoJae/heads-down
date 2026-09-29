# Attestation status list fixtures

- `status_live_sample.json`: seven real entries copied verbatim from
  <https://android.googleapis.com/attestation/status> as served on 2026-09-29
  (`last-modified: Sun, 27 Sep 2026 00:58:00 GMT`, 1,757 entries at the time): three whose keys
  look decimal, three lowercase-hex 128-bit serials, one `SOFTWARE_FLAW`. None of the committed
  test chains is on the live list (checked on 2026-09-29).
- `status_revoked_fixture.json`: the same sample plus two **fake** entries (labelled in their
  `comment`) that mark serials from our real test chains as `REVOKED` / `SUSPENDED`, so the
  revocation path is exercised end to end on genuine chains:
  - `16580768335559031605`: the Sony factory batch key in `sony_xperia10iii_sdk33_TEE_EC.pem`
    (chain index 1);
  - `2b3eba002ab308157af014549df1c83b`: the RKP attestation key in
    `frankel_sdk37_TEE_EC_2026.pem` (chain index 1).

The registrar never reads these at runtime unless `HD_STATUS_LIST_FILE` points at one
(development only).
