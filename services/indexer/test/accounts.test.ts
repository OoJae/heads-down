/**
 * Account layouts vs an independent oracle (Python struct.pack_into at the INTERFACE.md
 * offsets) and PDA re-derivation vs `solana find-program-derived-address`.
 */
import { describe, expect, it } from "vitest";
import { fromHex, toHex } from "../src/codec/bytes.ts";
import {
  decodeHdAccount,
  decodeRig,
  decodeSeekerSeat,
  decodeShiftLog,
  encodeRig,
  encodeSeekerSeat,
  encodeShiftLog,
  verifyAccount,
} from "../src/codec/accounts.ts";
import { createProgramAddress, findProgramAddress, seed, addrBytes } from "../src/codec/pda.ts";
import { HEADS_DOWN_PROGRAM_ID } from "../src/constants.ts";

const AUTH = "9FsGp26UkKndmewwV5sNTPXfBoNP1BxywifBrpWrxpVP";
const MINT = "GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te";
// `solana find-program-derived-address HDn4… string:rig pubkey:9FsG…` → bump 255
const RIG_PDA = "4yvkvw1yk8X2qHnxKd28XtT2HNTN6yTJQCMhya3SL11a";
// `… string:shift pubkey:4yvk… u64le:12` → bump 254
const SHIFT_PDA = "AvGwp78dCEBSxAKUMeB2xz8wMCL9jZ6AzWS36vMsaYpW";
// `… string:seeker pubkey:GT22…` → bump 253
const SEAT_PDA = "4NsqsLoADpX2Epv1v4HTSFVFpmELeXmsv5q9tVihgqaL";

const RIG_HEX =
  "0201ff00000000007aac785b72905097ccd58aa3ac5a278ac390e4083068b48a8929536be7658412020102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f2001010200000000e589926d17981a1f887707e48906848e981943fe8151939a752baa73e2109ee9c0b6e11a000000000076b01000000000005a62020000000040420f00000000000027b92900000000c07dc06a000000000046c3230000000040420f00000000000f000300000000008070bd6a00000000b0e5bd6a000000000c00000000000000e1100000000000002c730600000000002e7306000000000003000000000000001c9698000000000080c3c90100000000a0dcb86a000000002c7306000000000000720600000000002c010000000000000a000000000000008813000000000000e600000000000000847cb50d000000000900000002000000f550000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000";
const SHIFT_HEX =
  "0401fe00000000003b285eaa49f00afbdc8cde70f0b913764c387524f7bb4e358d6e507aed1d41290c000000000000000072060000000000727306000000000072010000000000001700000000000000daf25e010000000001000000000000006022bd6a00000000c08fbd6a0000000000000000000000000000000000000000";
const SEAT_HEX =
  "0301fd0000000000e589926d17981a1f887707e48906848e981943fe8151939a752baa73e2109ee93b285eaa49f00afbdc8cde70f0b913764c387524f7bb4e358d6e507aed1d41297aac785b72905097ccd58aa3ac5a278ac390e4083068b48a8929536be7658412cbd80100000000002065ec1a000000000000000000000000";

describe("Rig (384 B)", () => {
  it("decodes every field at its INTERFACE offset", () => {
    const rig = decodeRig(fromHex(RIG_HEX));
    expect(rig).toMatchObject({
      bump: 255,
      authority: AUTH,
      p256Pubkey: "02" + toHex(Uint8Array.from({ length: 32 }, (_, i) => i + 1)),
      attestationLevel: 1,
      tier: 1,
      state: 2,
      sgtMint: MINT,
      attestationExpirySlot: 451_000_000n,
      capWeek: 280_000_000n,
      capShift: 40_000_000n,
      capRound: 1_000_000n,
      capMaxCost: 700_000_000n,
      capsExpiryTs: 1_791_000_000n,
      planMaxEvCost: 600_000_000n,
      planDigLamports: 1_000_000n,
      planSplitTiles: 15,
      planSoloTiles: 0,
      planLeaseRounds: 3,
      planFlags: 0,
      planWindowStartTs: 1_790_800_000n,
      planWindowEndTs: 1_790_830_000n,
      shiftId: 12n,
      hbCounter: 4321n,
      leaseFromRound: 422_700n,
      leaseToRound: 422_702n,
      gapCount: 3,
      spentShift: 9_999_900n,
      spentWeek: 30_000_000n,
      weekStartTs: 1_790_500_000n,
      lastDugRound: 422_700n,
      shiftStartRound: 422_400n,
      shiftDarkRounds: 300n,
      shiftRoundsDug: 10n,
      lifetimeDarkRounds: 5000n,
      lifetimeRoundsDug: 230n,
      lifetimeLamportsDeployed: 229_997_700n,
      streak: 9,
      freezesLeft: 2,
      lastShiftDay: 20_725n,
    });
  });

  it("encodes back to the oracle bytes", () => {
    expect(toHex(encodeRig(decodeRig(fromHex(RIG_HEX))))).toBe(RIG_HEX);
  });

  it("maps a zero sgt_mint to null (guest)", () => {
    const b = fromHex(RIG_HEX);
    b.fill(0, 80, 112);
    b[74] = 0;
    expect(decodeRig(b).sgtMint).toBeNull();
  });

  it("rejects wrong length, tag, version, tier, state and a non-compressed key", () => {
    const good = fromHex(RIG_HEX);
    expect(() => decodeRig(good.subarray(0, 383))).toThrow(/BAD_LENGTH/);
    const mut = (i: number, v: number) => {
      const b = good.slice();
      b[i] = v;
      return b;
    };
    expect(() => decodeRig(mut(0, 3))).toThrow(/BAD_TAG/);
    expect(() => decodeRig(mut(1, 2))).toThrow(/BAD_TAG/);
    expect(() => decodeRig(mut(74, 2))).toThrow(/tier/);
    expect(() => decodeRig(mut(75, 6))).toThrow(/state/);
    expect(() => decodeRig(mut(40, 0x04))).toThrow(/SEC1/);
  });
});

describe("ShiftLog and SeekerSeat (128 B)", () => {
  it("ShiftLog decodes and re-encodes", () => {
    const s = decodeShiftLog(fromHex(SHIFT_HEX));
    expect(s).toEqual({
      kind: "ShiftLog",
      bump: 254,
      rig: RIG_PDA,
      shiftId: 12n,
      startRound: 422_400n,
      endRound: 422_770n,
      darkRounds: 370n,
      roundsDug: 23n,
      lamportsDeployed: 22_999_770n,
      breakReason: 1,
      mode: 0,
      startTs: 1_790_780_000n,
      endTs: 1_790_808_000n,
    });
    expect(toHex(encodeShiftLog(s))).toBe(SHIFT_HEX);
  });

  it("SeekerSeat decodes and re-encodes", () => {
    const s = decodeSeekerSeat(fromHex(SEAT_HEX));
    expect(s).toEqual({
      kind: "SeekerSeat",
      bump: 253,
      sgtMint: MINT,
      rig: RIG_PDA,
      authority: AUTH,
      memberNumber: 121_035n,
      verifiedSlot: 451_700_000n,
    });
    expect(toHex(encodeSeekerSeat(s))).toBe(SEAT_HEX);
  });

  it("dispatches by tag and refuses a SeekerSeat parsed as a ShiftLog", () => {
    expect(decodeHdAccount(fromHex(SEAT_HEX)).kind).toBe("SeekerSeat");
    expect(() => decodeShiftLog(fromHex(SEAT_HEX))).toThrow(/BAD_TAG/);
    expect(() => decodeHdAccount(new Uint8Array([9]))).toThrow(/BAD_TAG/);
  });
});

describe("verifyAccount: owner + canonical PDA re-derivation", () => {
  const P = HEADS_DOWN_PROGRAM_ID;

  it("accepts the CLI-derived addresses", () => {
    expect(verifyAccount(decodeRig(fromHex(RIG_HEX)), RIG_PDA, P, P)).toBeNull();
    expect(verifyAccount(decodeShiftLog(fromHex(SHIFT_HEX)), SHIFT_PDA, P, P)).toBeNull();
    expect(verifyAccount(decodeSeekerSeat(fromHex(SEAT_HEX)), SEAT_PDA, P, P)).toBeNull();
  });

  it("rejects a foreign owner", () => {
    expect(verifyAccount(decodeRig(fromHex(RIG_HEX)), RIG_PDA, "11111111111111111111111111111111", P)).toMatch(/owner/);
  });

  it("rejects a rig stored at another rig's address (authority swapped)", () => {
    const b = fromHex(RIG_HEX);
    b[8] = b[8]! ^ 1; // different authority, same address
    expect(verifyAccount(decodeRig(b), RIG_PDA, P, P)).toMatch(/not the canonical Rig PDA/);
  });

  it("rejects a non-canonical bump even when the address is a valid PDA", () => {
    const seeds = [seed("rig"), addrBytes(AUTH)];
    const canonical = findProgramAddress(seeds, P);
    expect(canonical).toEqual({ address: RIG_PDA, bump: 255 });
    let lower = -1;
    for (let b = 254; b >= 0; b--) {
      if (createProgramAddress(seeds, b, P) !== null) {
        lower = b;
        break;
      }
    }
    expect(lower).toBeGreaterThanOrEqual(0);
    const alt = createProgramAddress(seeds, lower, P)!;
    const bytes = fromHex(RIG_HEX);
    bytes[2] = lower;
    expect(verifyAccount(decodeRig(bytes), alt, P, P)).toMatch(/canonical/);
  });
});
